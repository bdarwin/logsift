package logsift.jvm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What stands out in thread dumps: deadlocks, threads queueing for a lock, threads stuck in the
 * same place across dumps, threads using a lot of CPU, pools with every thread busy, and a
 * growing number of threads. Several dumps taken some seconds apart show the last three.
 */
public final class ThreadAnalysis {
    private ThreadAnalysis() {
    }

    public enum Severity { ERROR, WARN, INFO, OK }

    public enum Kind { DEADLOCK, CONTENTION, STUCK, HOT, BUSY_POOL, GROWTH, CALM }

    /**
     * One thing that stands out.
     *
     * @param keys the threads concerned ({@link JThread#key()}) in dump {@code dump}
     */
    public record Finding(Kind kind, Severity severity, String title, String detail, List<String> keys, int dump) {
        public Finding {
            keys = List.copyOf(keys);
        }
    }

    public record Report(String summary, List<Finding> findings) {
        public int problems() {
            return (int) findings.stream().filter(f -> f.severity() == Severity.ERROR
                    || f.severity() == Severity.WARN).count();
        }
    }

    /** Threads waiting for one lock, and who holds it (null if no thread in the dump does). */
    public record Contention(JThread.Lock lock, JThread holder, List<JThread> waiters) {}

    /** Threads in the same place (and state) in each of the last {@code dumps} dumps. */
    public record Stuck(List<String> keys, JThread sample, int dumps, long millis) {}

    /** A thread's CPU use: {@code share} of one core (1.0 = a whole core). */
    public record Hot(JThread thread, double share, boolean sinceStart) {}

    /** Threads of one pool (by name) or with the same stack. */
    public record Group(String name, List<JThread> threads) {}

    public enum ChangeKind { NEW, GONE, CHANGED, SAME }

    /** One thread in two dumps ({@code before} or {@code after} null when only in one). */
    public record Change(ChangeKind kind, JThread before, JThread after) {
        public JThread thread() {
            return after != null ? after : before;
        }
    }

    // ---------------------------------------------------------------- the report

    /** What stands out, looking at the last dump (and at all of them for what changes). */
    public static Report analyze(List<ThreadDump> dumps) {
        List<Finding> out = new ArrayList<>();
        if (dumps.isEmpty()) {
            return new Report("No threads", out);
        }
        int last = dumps.size() - 1;
        ThreadDump d = dumps.get(last);
        Set<String> reported = new HashSet<>();

        for (List<JThread> cycle : deadlocks(d)) {
            StringBuilder names = new StringBuilder();
            for (JThread t : cycle) {
                names.append(t.displayName()).append(" → ");
                reported.add(t.key());
            }
            names.append(cycle.get(0).displayName());
            JThread.Lock l = cycle.get(0).blockedOn();
            out.add(new Finding(Kind.DEADLOCK, Severity.ERROR,
                    "Deadlock: " + cycle.size() + " threads wait for each other",
                    names + (l == null ? "" : "  (on " + l.shortType() + " locks)"),
                    cycle.stream().map(JThread::key).toList(), last));
        }

        for (Contention c : contention(d)) {
            List<JThread> waiters = c.waiters().stream().filter(t -> !reported.contains(t.key())).toList();
            if (waiters.size() < 2) {
                continue;
            }
            List<String> keys = new ArrayList<>();
            if (c.holder() != null) {
                keys.add(c.holder().key());
            }
            waiters.forEach(t -> keys.add(t.key()));
            reported.addAll(keys);
            String title = waiters.size() + " threads wait for " + article(c.lock().shortType()) + " "
                    + c.lock().shortType()
                    + (c.holder() == null ? "" : " held by " + c.holder().displayName());
            String detail = c.holder() == null ? "No thread in the dump holds it."
                    : c.holder().displayName() + " is " + doing(c.holder());
            int span = lockedSince(dumps, c.lock(), c.holder());
            if (span > 1) {
                detail += "  ·  the same in the last " + span + " dumps" + spanText(dumps, span);
            }
            out.add(new Finding(Kind.CONTENTION, waiters.size() >= 5 || span > 1 ? Severity.ERROR : Severity.WARN,
                    title, detail, keys, last));
        }

        List<Stuck> stuck = stuck(dumps);
        for (Hot h : hot(dumps)) {
            String key = h.thread().key();
            Stuck same = stuck.stream().filter(s -> s.keys().contains(key)).findFirst().orElse(null);
            out.add(new Finding(Kind.HOT, h.share() >= 0.8 ? Severity.WARN : Severity.INFO,
                    h.thread().displayName() + " uses " + Math.round(h.share() * 100) + "% of a CPU core"
                            + (h.sinceStart() ? " (on average since it started)" : ""),
                    "It is " + doing(h.thread())
                            + (same == null ? "" : ", in the same place in all " + same.dumps() + " dumps"),
                    List.of(key), last));
            reported.add(key);
        }

        for (Stuck s : stuck) {
            List<String> keys = s.keys().stream().filter(k -> !reported.contains(k)).toList();
            if (keys.isEmpty()) {
                continue;
            }
            reported.addAll(keys);
            JThread t = s.sample();
            JThread.Frame f = t.appFrame();
            String where = f == null ? "the same place" : f.shortMethod() + "(" + f.location() + ")";
            String who = keys.size() == 1 ? t.displayName() : keys.size() + " threads";
            boolean waiting = t.state() == JThread.State.WAITING || t.state() == JThread.State.TIMED_WAITING;
            String title = who + (waiting ? " waiting in " : " stuck in ") + where
                    + (s.millis() > 0 ? " for " + duration(s.millis()) : "");
            out.add(new Finding(Kind.STUCK, waiting ? Severity.INFO : Severity.WARN, title,
                    t.state().label() + " in the same place in all " + s.dumps() + " dumps"
                            + (keys.size() > 1 ? " (" + names(d, keys, 4) + ")" : ""),
                    keys, last));
        }

        for (Group g : families(d)) {
            if (g.threads().size() < 4 || g.name().equals("(unnamed)")) {
                continue;
            }
            List<JThread> busy = g.threads().stream().filter(t -> !t.isIdle()).toList();
            if (busy.size() == g.threads().size()) {
                Map<JThread.State, Integer> st = new LinkedHashMap<>();
                busy.forEach(t -> st.merge(t.state(), 1, Integer::sum));
                StringBuilder sb = new StringBuilder();
                st.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(v).append(' ')
                        .append(k.label().toLowerCase(Locale.ROOT)));
                out.add(new Finding(Kind.BUSY_POOL, Severity.WARN,
                        "All " + g.threads().size() + " " + g.name() + " threads are busy",
                        sb + ": new work for this pool has to wait",
                        g.threads().stream().map(JThread::key).toList(), last));
            }
        }

        if (dumps.size() > 1) {
            ThreadDump first = dumps.get(0);
            int a = first.threads().size();
            int b = d.threads().size();
            if (b - a >= 10 && b >= a * 1.2) {
                Map<String, Integer> before = new HashMap<>();
                for (Group g : families(first)) {
                    before.put(g.name(), g.threads().size());
                }
                Group most = null;
                int grew = 0;
                for (Group g : families(d)) {
                    int diff = g.threads().size() - before.getOrDefault(g.name(), 0);
                    if (diff > grew) {
                        grew = diff;
                        most = g;
                    }
                }
                out.add(new Finding(Kind.GROWTH, Severity.WARN,
                        "Threads grew from " + a + " to " + b + spanText(dumps, dumps.size()),
                        most == null ? "" : "Most of them are " + most.name() + " (+" + grew + ")",
                        most == null ? List.of() : most.threads().stream().map(JThread::key).toList(), last));
            }
        }

        if (out.isEmpty()) {
            out.add(new Finding(Kind.CALM, Severity.OK, "Nothing unusual",
                    "No deadlocks, no threads queueing for a lock"
                            + (dumps.size() > 1 ? ", none stuck in one place" : "")
                            + ". Take a few dumps some seconds apart to see stuck threads and CPU use.",
                    List.of(), last));
        }
        return new Report(summary(dumps, last), out);
    }

    /** "Dump 3 of 3 · 10:23:45 · 32 threads: 9 runnable, 9 blocked, 12 waiting". */
    public static String summary(List<ThreadDump> dumps, int index) {
        ThreadDump d = dumps.get(index);
        StringBuilder sb = new StringBuilder();
        if (dumps.size() > 1) {
            sb.append("Dump ").append(index + 1).append(" of ").append(dumps.size()).append(" · ");
        }
        if (d.time() != ThreadDump.NO_TIME) {
            sb.append(d.clock()).append(" · ");
        }
        sb.append(d.threads().size()).append(d.threads().size() == 1 ? " thread" : " threads");
        Map<JThread.State, Integer> c = d.stateCounts();
        int run = c.getOrDefault(JThread.State.RUNNABLE, 0);
        int blocked = c.getOrDefault(JThread.State.BLOCKED, 0);
        int waiting = c.getOrDefault(JThread.State.WAITING, 0) + c.getOrDefault(JThread.State.TIMED_WAITING, 0);
        sb.append(": ").append(run).append(" runnable, ").append(blocked).append(" blocked, ")
                .append(waiting).append(" waiting");
        long virt = d.threads().stream().filter(JThread::virtual).count();
        if (virt > 0) {
            sb.append(" · ").append(virt).append(" virtual");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- deadlocks and locks

    /** Groups of threads that each wait for a lock the next one holds. */
    public static List<List<JThread>> deadlocks(ThreadDump d) {
        Map<JThread.Lock, JThread> holders = holders(d);
        Map<String, JThread> waitsFor = new HashMap<>();
        for (JThread t : d.threads()) {
            JThread.Lock l = t.blockedOn();
            JThread h = l == null ? null : holders.get(l);
            if (h != null && h != t) {
                waitsFor.put(t.key(), h);
            }
        }
        List<List<JThread>> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (JThread start : d.threads()) {
            if (done.contains(start.key())) {
                continue;
            }
            List<JThread> path = new ArrayList<>();
            Map<String, Integer> at = new HashMap<>();
            JThread t = start;
            while (t != null && !done.contains(t.key()) && !at.containsKey(t.key())) {
                at.put(t.key(), path.size());
                path.add(t);
                t = waitsFor.get(t.key());
            }
            if (t != null && at.containsKey(t.key())) {
                out.add(List.copyOf(path.subList(at.get(t.key()), path.size())));
            }
            path.forEach(p -> done.add(p.key()));
        }
        return out;
    }

    /** Locks that threads wait for, most waiters first. */
    public static List<Contention> contention(ThreadDump d) {
        Map<JThread.Lock, JThread> holders = holders(d);
        Map<JThread.Lock, List<JThread>> waiting = new LinkedHashMap<>();
        for (JThread t : d.threads()) {
            JThread.Lock l = t.blockedOn();
            if (l == null) {
                continue;
            }
            boolean monitor = t.state() == JThread.State.BLOCKED;
            if (monitor || holders.containsKey(l)) { // parked on a condition: not a lock queue
                waiting.computeIfAbsent(l, k -> new ArrayList<>()).add(t);
            }
        }
        List<Contention> out = new ArrayList<>();
        waiting.forEach((l, ts) -> out.add(new Contention(l, holders.get(l), ts)));
        out.sort(Comparator.comparingInt((Contention c) -> c.waiters().size()).reversed());
        return out;
    }

    private static Map<JThread.Lock, JThread> holders(ThreadDump d) {
        Map<JThread.Lock, JThread> m = new HashMap<>();
        for (JThread t : d.threads()) {
            for (JThread.Lock l : t.held()) {
                m.putIfAbsent(l, t);
            }
        }
        return m;
    }

    /** In how many of the last dumps (counting back) the same thread held the lock with waiters. */
    private static int lockedSince(List<ThreadDump> dumps, JThread.Lock lock, JThread holder) {
        if (holder == null) {
            return 1;
        }
        int n = 0;
        for (int i = dumps.size() - 1; i >= 0; i--) {
            JThread h = dumps.get(i).find(holder.key());
            if (h == null || !h.held().contains(lock)) {
                break;
            }
            n++;
        }
        return Math.max(1, n);
    }

    // ---------------------------------------------------------------- across dumps

    /** Threads doing the same thing in each of the last dumps (at least two). */
    public static List<Stuck> stuck(List<ThreadDump> dumps) {
        if (dumps.size() < 2) {
            return List.of();
        }
        ThreadDump last = dumps.get(dumps.size() - 1);
        Map<String, List<JThread>> byStack = new LinkedHashMap<>();
        Map<String, Integer> runs = new HashMap<>();
        for (JThread t : last.threads()) {
            if (t.frames().isEmpty() || t.isIdle() || isSleeping(t)) {
                continue;
            }
            boolean waiting = t.state() == JThread.State.WAITING || t.state() == JThread.State.TIMED_WAITING;
            if (waiting && t.frames().stream().allMatch(JThread.Frame::isJdk)) {
                continue; // the JDK's own housekeeping threads wait like that all the time
            }
            String stack = t.stackKey();
            int run = 1;
            for (int i = dumps.size() - 2; i >= 0; i--) {
                JThread o = dumps.get(i).find(t.key());
                if (o == null || !o.stackKey().equals(stack)) {
                    break;
                }
                run++;
            }
            if (run == dumps.size()) {
                byStack.computeIfAbsent(stack, k -> new ArrayList<>()).add(t);
                runs.put(stack, run);
            }
        }
        long millis = dumps.get(0).time() != ThreadDump.NO_TIME && last.time() != ThreadDump.NO_TIME
                ? last.time() - dumps.get(0).time() : 0;
        List<Stuck> out = new ArrayList<>();
        byStack.forEach((k, ts) -> out.add(new Stuck(ts.stream().map(JThread::key).toList(), ts.get(0),
                runs.get(k), Math.max(0, millis))));
        // the busy ones first, then the most threads
        out.sort(Comparator.comparingInt((Stuck s) -> s.sample().state() == JThread.State.RUNNABLE
                || s.sample().state() == JThread.State.BLOCKED ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Stuck s) -> s.keys().size()).reversed()));
        return out;
    }

    private static boolean isSleeping(JThread t) {
        JThread.Frame top = t.top();
        return t.stateDetail().equals("sleeping")
                || (top != null && (top.method().endsWith("Thread.sleep") || top.method().endsWith("Thread.sleep0")
                || top.method().endsWith("Thread.sleepNanos0") || top.method().endsWith("VirtualThread.parkNanos")
                        && t.frames().stream().anyMatch(f -> f.method().endsWith("Thread.sleep"))));
    }

    /** Threads using the most CPU: between the first and last dump, or since they started. */
    public static List<Hot> hot(List<ThreadDump> dumps) {
        ThreadDump last = dumps.get(dumps.size() - 1);
        List<Hot> out = new ArrayList<>();
        if (dumps.size() > 1) {
            ThreadDump first = dumps.get(0);
            long wall = first.time() != ThreadDump.NO_TIME && last.time() != ThreadDump.NO_TIME
                    ? last.time() - first.time() : -1;
            for (JThread t : last.threads()) {
                JThread o = first.find(t.key());
                if (o == null || t.cpuMs() < 0 || o.cpuMs() < 0) {
                    continue;
                }
                double w = t.elapsedMs() > 0 && o.elapsedMs() > 0 ? t.elapsedMs() - o.elapsedMs() : wall;
                if (w >= 500) {
                    double share = (t.cpuMs() - o.cpuMs()) / w;
                    if (share >= 0.25) {
                        out.add(new Hot(t, Math.min(share, 1.0), false));
                    }
                }
            }
        } else {
            for (JThread t : last.threads()) {
                if (t.cpuMs() >= 0 && t.elapsedMs() >= 10_000) {
                    double share = t.cpuMs() / t.elapsedMs();
                    if (share >= 0.5) {
                        out.add(new Hot(t, Math.min(share, 1.0), true));
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(Hot::share).reversed());
        return out.size() > 5 ? List.copyOf(out.subList(0, 5)) : out;
    }

    // ---------------------------------------------------------------- groups

    /** Threads by pool (name with the number left out), biggest first. */
    public static List<Group> families(ThreadDump d) {
        Map<String, List<JThread>> m = new LinkedHashMap<>();
        for (JThread t : d.threads()) {
            m.computeIfAbsent(t.virtual() && t.name().isEmpty() ? "(virtual threads)" : JThread.family(t.name()),
                    k -> new ArrayList<>()).add(t);
        }
        return sortedGroups(m);
    }

    /** Threads with the same state and stack, biggest group first. */
    public static List<Group> sameStacks(ThreadDump d) {
        Map<String, List<JThread>> m = new LinkedHashMap<>();
        for (JThread t : d.threads()) {
            m.computeIfAbsent(t.stackKey(), k -> new ArrayList<>()).add(t);
        }
        List<Group> out = new ArrayList<>();
        for (List<JThread> ts : m.values()) {
            JThread t = ts.get(0);
            JThread.Frame f = t.appFrame();
            String name = (f == null ? "(no Java stack)" : f.shortMethod() + "(" + f.location() + ")")
                    + " · " + t.state().label().toLowerCase(Locale.ROOT);
            out.add(new Group(name, List.copyOf(ts)));
        }
        out.sort(Comparator.comparingInt((Group g) -> g.threads().size()).reversed());
        return out;
    }

    private static List<Group> sortedGroups(Map<String, List<JThread>> m) {
        List<Group> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Group(k, List.copyOf(v))));
        out.sort(Comparator.comparingInt((Group g) -> g.threads().size()).reversed());
        return out;
    }

    /** How the threads changed from dump {@code a} to dump {@code b}: new, gone, changed first. */
    public static List<Change> compare(ThreadDump a, ThreadDump b) {
        Map<String, JThread> before = new LinkedHashMap<>();
        for (JThread t : a.threads()) {
            before.put(t.key(), t);
        }
        List<Change> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JThread t : b.threads()) {
            JThread o = before.get(t.key());
            seen.add(t.key());
            if (o == null) {
                out.add(new Change(ChangeKind.NEW, null, t));
            } else if (!o.stackKey().equals(t.stackKey())) {
                out.add(new Change(ChangeKind.CHANGED, o, t));
            } else {
                out.add(new Change(ChangeKind.SAME, o, t));
            }
        }
        for (JThread o : a.threads()) {
            if (!seen.contains(o.key())) {
                out.add(new Change(ChangeKind.GONE, o, null));
            }
        }
        out.sort(Comparator.comparingInt(c -> c.kind().ordinal()));
        return out;
    }

    // ---------------------------------------------------------------- words

    /** "Timed waiting (sleeping) in Fixture.sleep(Fixture.java:9)". */
    public static String doing(JThread t) {
        JThread.Frame f = t.appFrame();
        String state = t.state().label().toLowerCase(Locale.ROOT)
                + (t.stateDetail().isEmpty() ? "" : " (" + t.stateDetail() + ")");
        return state + (f == null ? "" : " in " + f.shortMethod() + "(" + f.location() + ")");
    }

    /** "a" or "an" before a word. */
    static String article(String word) {
        return !word.isEmpty() && "AEIOUaeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    private static String names(ThreadDump d, List<String> keys, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, keys.size()); i++) {
            JThread t = d.find(keys.get(i));
            sb.append(i == 0 ? "" : ", ").append(t == null ? keys.get(i) : t.displayName());
        }
        if (keys.size() > max) {
            sb.append(", …");
        }
        return sb.toString();
    }

    private static String spanText(List<ThreadDump> dumps, int n) {
        ThreadDump first = dumps.get(dumps.size() - n);
        ThreadDump last = dumps.get(dumps.size() - 1);
        if (first.time() == ThreadDump.NO_TIME || last.time() == ThreadDump.NO_TIME || last.time() <= first.time()) {
            return "";
        }
        return " (" + duration(last.time() - first.time()) + ")";
    }

    /** "2 s", "1 min 5 s", "3 h 2 min". */
    public static String duration(long ms) {
        long s = Math.round(ms / 1000.0);
        if (s < 60) {
            return Math.max(s, ms > 0 ? 1 : 0) + " s";
        }
        if (s < 3600) {
            return s / 60 + " min" + (s % 60 == 0 ? "" : " " + s % 60 + " s");
        }
        if (s < 2 * 86400) {
            return s / 3600 + " h" + (s % 3600 / 60 == 0 ? "" : " " + s % 3600 / 60 + " min");
        }
        return s / 86400 + " d" + (s % 86400 / 3600 == 0 ? "" : " " + s % 86400 / 3600 + " h");
    }
}
