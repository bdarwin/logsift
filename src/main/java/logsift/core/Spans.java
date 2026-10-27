package logsift.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Things that start and end in a log: {@code Started job sync-7} … {@code Finished job sync-7},
 * {@code Processing request r-12} … {@code Completed request r-12}, a task's begin and end on one
 * thread. Pairs of message kinds are found by their words; their lines are matched by the value
 * they share (the job, the request) or else by thread. For each: how long they took, the slowest,
 * and the ones that started and never ended (often what hung).
 */
public final class Spans {
    private Spans() {}

    /** One start and its end ({@code endLine} -1: it never ended). Times may be {@link TimeFormat#NONE}. */
    public record Span(long startLine, long endLine, long start, long end, String key) {
        public boolean open() {
            return endLine < 0;
        }

        public long millis() {
            return open() || start == TimeFormat.NONE || end == TimeFormat.NONE ? -1 : Math.max(0, end - start);
        }
    }

    /**
     * A kind of thing that starts and ends. {@code name}: what it is ("job"); the start and end
     * message kinds; how its lines were matched ("by job" / "by thread").
     */
    public record Kind(String name, MessageStats.Template start, MessageStats.Template end, String matchedBy,
                       int count, long median, long p95, long max, List<Span> slowest, List<Span> unfinished,
                       int unfinishedCount) {}

    public record Result(List<Kind> kinds) {
        public static final Result EMPTY = new Result(List.of());
    }

    /** The lines of a message kind (sorted), found by the caller's search. */
    public interface Lines {
        long[] of(MessageStats.Template t, BooleanSupplier cancelled);
    }

    static final int MAX_PAIRS = 8;
    static final int MAX_LINES = 200_000;
    static final int KEEP = 200;

    private static final Set<String> START = Set.of("start", "started", "starting", "starts", "begin", "begins",
            "beginning", "began", "enter", "entering", "entered", "launch", "launching", "launched", "opening",
            "processing", "running", "received", "receiving", "accepted", "submitted", "scheduling", "executing",
            "invoking", "calling", "sending", "acquiring", "connecting", "loading", "initializing", "creating",
            "handling", "importing", "exporting", "syncing", "uploading", "downloading", "building", "migrating");
    private static final Set<String> END = Set.of("finish", "finished", "finishing", "end", "ended", "ending", "ends",
            "complete", "completed", "completing", "done", "exit", "exiting", "exited", "closed", "closing", "took",
            "succeeded", "success", "successful", "failed", "stopped", "returned", "processed", "sent",
            "acquired", "connected", "loaded", "initialized", "created", "handled", "imported", "exported", "synced",
            "uploaded", "downloaded", "built", "migrated", "response", "responded");
    private static final Set<String> FILLER = Set.of("the", "a", "an", "for", "of", "in", "on", "to", "with", "after",
            "ms", "s", "sec", "secs", "seconds", "millis", "milliseconds", "min", "minutes", "at", "by", "from", "is",
            "was", "has", "been", "and", "id", "status", "result", "successfully", "ok", "time", "duration",
            "elapsed", "total");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z_]+");

    /** The words of a message that name the thing (not start/end words or fillers), and which kind of word it has. */
    record Words(List<String> subject, boolean starts, boolean ends) {}

    static Words words(String message) {
        List<String> subject = new ArrayList<>();
        boolean starts = false;
        boolean ends = false;
        Matcher m = WORD.matcher(message.replace(MessageStats.WILDCARD, " "));
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            boolean s = START.contains(w);
            boolean e = END.contains(w);
            starts |= s && !e;
            ends |= e;
            if (!s && !e && !FILLER.contains(w)) {
                subject.add(w);
            }
        }
        return new Words(subject, starts, ends);
    }

    /** Pairs of message kinds that start and end the same thing, most frequent first. */
    static List<MessageStats.Template[]> pairs(List<MessageStats.Template> templates) {
        List<MessageStats.Template[]> out = new ArrayList<>();
        Set<MessageStats.Template> used = new HashSet<>();
        List<MessageStats.Template> sorted = new ArrayList<>(templates);
        sorted.sort(Comparator.comparingLong(MessageStats.Template::count).reversed());
        for (MessageStats.Template a : sorted) {
            if (out.size() >= MAX_PAIRS || used.contains(a)) {
                continue;
            }
            Words wa = words(Insights.display(a.text()));
            if (!wa.starts || wa.ends || wa.subject.isEmpty()) {
                continue;
            }
            MessageStats.Template best = null;
            for (MessageStats.Template b : sorted) {
                if (b == a || used.contains(b) || b.count() * 5 < a.count() || b.count() > a.count() * 3 / 2 + 2) {
                    continue;
                }
                Words wb = words(Insights.display(b.text()));
                if (!wb.ends || !sameThing(wa.subject, wb.subject)) {
                    continue;
                }
                if (best == null || Math.abs(b.count() - a.count()) < Math.abs(best.count() - a.count())) {
                    best = b;
                }
            }
            if (best != null) {
                out.add(new MessageStats.Template[] {a, best});
                used.add(a);
                used.add(best);
            }
        }
        return out;
    }

    /** The same words, or one's words all in the other's (with something in common). */
    private static boolean sameThing(List<String> a, List<String> b) {
        if (a.equals(b)) {
            return true;
        }
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        return !sa.isEmpty() && !sb.isEmpty() && (sb.containsAll(sa) || sa.containsAll(sb))
                && Math.abs(sa.size() - sb.size()) <= 2;
    }

    /** A regex for a message kind's lines with the variable parts captured. */
    static Pattern capturing(String template) {
        StringBuilder sb = new StringBuilder();
        String[] parts = template.split(Pattern.quote(MessageStats.WILDCARD), -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                // a variable part right before , ; | or = may hold those too: "web-2,u1" is one <*>
                // when the template came from a CSV row (digit tokens joined by punctuation)
                boolean separatorNext = !parts[i].isEmpty() && ",;|=".indexOf(parts[i].charAt(0)) >= 0;
                sb.append(separatorNext ? "([^\\s()\\[\\]{}\"'<>]+?)" : "([^\\s,;()\\[\\]{}\"'<>|=]+)");
            }
            String[] words = parts[i].split(" ", -1);
            for (int w = 0; w < words.length; w++) {
                if (w > 0) {
                    sb.append("\\s+");
                }
                sb.append(Pattern.quote(words[w]).replace("\\Q\\E", ""));
            }
        }
        return Pattern.compile(sb.toString());
    }

    /**
     * Finds the start/end pairs among the message kinds and measures them.
     *
     * @param lines finds the lines of a message kind
     * @param reader reads a line, its time and its thread
     */
    public static Result analyze(List<MessageStats.Template> templates, Lines lines, RequestTrace.Reader reader,
                                 BooleanSupplier cancelled) {
        List<Kind> out = new ArrayList<>();
        for (MessageStats.Template[] pair : pairs(templates)) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            long[] starts = lines.of(pair[0], cancelled);
            long[] ends = lines.of(pair[1], cancelled);
            if (starts == null || ends == null) {
                return null;
            }
            Kind k = measure(pair[0], pair[1], cap(starts), cap(ends), reader);
            if (k != null) {
                out.add(k);
            }
        }
        out.sort(Comparator.comparingInt(Kind::count).reversed());
        return new Result(out);
    }

    private static long[] cap(long[] a) {
        return a.length > MAX_LINES ? Arrays.copyOf(a, MAX_LINES) : a;
    }

    /** One kind: its lines matched, durations and the unfinished ones. Null if they don't pair up. */
    static Kind measure(MessageStats.Template a, MessageStats.Template b, long[] starts, long[] ends,
                        RequestTrace.Reader reader) {
        Pattern pa = capturing(a.text());
        Pattern pb = capturing(b.text());
        List<String[]> va = new ArrayList<>();
        List<String> ta = new ArrayList<>();
        List<Long> timesA = new ArrayList<>();
        for (long l : starts) {
            String text = reader.text(l);
            va.add(captures(pa, text));
            ta.add(text == null ? null : reader.thread(text));
            timesA.add(text == null ? TimeFormat.NONE : reader.time(text));
        }
        List<String[]> vb = new ArrayList<>();
        List<String> tb = new ArrayList<>();
        List<Long> timesB = new ArrayList<>();
        for (long l : ends) {
            String text = reader.text(l);
            vb.add(captures(pb, text));
            tb.add(text == null ? null : reader.thread(text));
            timesB.add(text == null ? TimeFormat.NONE : reader.time(text));
        }
        int[] key = keyColumns(va, vb);
        String matchedBy;
        List<Span> spans = new ArrayList<>();
        if (key != null) {
            matchedBy = "by its " + keyName(a.text(), key[0]);
            Map<String, ArrayDeque<Integer>> waiting = new HashMap<>();
            int j = 0;
            List<int[]> matched = new ArrayList<>(); // {start index, end index}
            // walk both lists in line order: an end closes the oldest open start with its key
            int i = 0;
            boolean[] closed = new boolean[starts.length];
            while (i < starts.length || j < ends.length) {
                if (j >= ends.length || i < starts.length && starts[i] < ends[j]) {
                    String k = val(va.get(i), key[0]);
                    if (k != null) {
                        waiting.computeIfAbsent(k, x -> new ArrayDeque<>()).add(i);
                    }
                    i++;
                } else {
                    String k = val(vb.get(j), key[1]);
                    ArrayDeque<Integer> q = k == null ? null : waiting.get(k);
                    if (q != null && !q.isEmpty()) {
                        int s = q.poll();
                        closed[s] = true;
                        matched.add(new int[] {s, j});
                    }
                    j++;
                }
            }
            for (int[] m : matched) {
                spans.add(new Span(starts[m[0]], ends[m[1]], timesA.get(m[0]), timesB.get(m[1]), val(va.get(m[0]), key[0])));
            }
            for (int s = 0; s < starts.length; s++) {
                if (!closed[s]) {
                    spans.add(new Span(starts[s], -1, timesA.get(s), TimeFormat.NONE, val(va.get(s), key[0])));
                }
            }
        } else if (ta.stream().anyMatch(java.util.Objects::nonNull)) {
            matchedBy = "by thread";
            Map<String, ArrayDeque<Integer>> waiting = new HashMap<>();
            boolean[] closed = new boolean[starts.length];
            int i = 0;
            int j = 0;
            while (i < starts.length || j < ends.length) {
                if (j >= ends.length || i < starts.length && starts[i] < ends[j]) {
                    if (ta.get(i) != null) {
                        waiting.computeIfAbsent(ta.get(i), x -> new ArrayDeque<>()).add(i);
                    }
                    i++;
                } else {
                    ArrayDeque<Integer> q = tb.get(j) == null ? null : waiting.get(tb.get(j));
                    if (q != null && !q.isEmpty()) {
                        int s = q.pollLast(); // the latest start on the thread: nested calls end first
                        closed[s] = true;
                        spans.add(new Span(starts[s], ends[j], timesA.get(s), timesB.get(j), ta.get(s)));
                    }
                    j++;
                }
            }
            for (int s = 0; s < starts.length; s++) {
                if (!closed[s]) {
                    spans.add(new Span(starts[s], -1, timesA.get(s), TimeFormat.NONE, ta.get(s)));
                }
            }
        } else {
            return null; // nothing to tell which end belongs to which start
        }
        long done = spans.stream().filter(s -> !s.open()).count();
        if (done == 0 || done * 2 < Math.min(starts.length, ends.length)) {
            return null; // they don't really pair up
        }
        long[] ms = spans.stream().mapToLong(Span::millis).filter(x -> x >= 0).sorted().toArray();
        List<Span> slowest = spans.stream().filter(s -> s.millis() >= 0)
                .sorted(Comparator.comparingLong(Span::millis).reversed()).limit(KEEP).toList();
        List<Span> unfinished = spans.stream().filter(Span::open).sorted(Comparator.comparingLong(Span::startLine))
                .toList();
        String name = String.join(" ", words(Insights.display(a.text())).subject);
        return new Kind(name.isEmpty() ? Insights.display(a.text()) : name, a, b, matchedBy, (int) done,
                ms.length == 0 ? -1 : ms[ms.length / 2], ms.length == 0 ? -1 : ms[Math.min(ms.length - 1,
                        (int) Math.ceil(ms.length * 0.95) - 1)], ms.length == 0 ? -1 : ms[ms.length - 1], slowest,
                unfinished.size() > KEEP ? unfinished.subList(0, KEEP) : unfinished, unfinished.size());
    }

    private static String[] captures(Pattern p, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        String[] out = new String[m.groupCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = m.group(i + 1);
        }
        return out;
    }

    private static String val(String[] v, int i) {
        return v == null || i >= v.length ? null : v[i];
    }

    /**
     * Which variable part of the start and of the end hold the same thing (the job's name): the
     * pair whose values agree most, if most ends find their start that way; else null.
     */
    static int[] keyColumns(List<String[]> a, List<String[]> b) {
        int na = a.stream().filter(java.util.Objects::nonNull).mapToInt(x -> x.length).max().orElse(0);
        int nb = b.stream().filter(java.util.Objects::nonNull).mapToInt(x -> x.length).max().orElse(0);
        int[] best = null;
        double bestShare = 0.5;
        for (int i = 0; i < na; i++) {
            Set<String> values = new LinkedHashSet<>();
            int have = 0;
            for (String[] v : a) {
                if (v != null && i < v.length) {
                    values.add(v[i]);
                    have++;
                }
            }
            if (values.size() * 2 < have || values.size() < 2 && have > 2) {
                continue; // a few values again and again (a thread, a host): not what tells them apart
            }
            for (int j = 0; j < nb; j++) {
                int found = 0;
                int n = 0;
                for (String[] v : b) {
                    if (v != null && j < v.length) {
                        n++;
                        if (values.contains(v[j])) {
                            found++;
                        }
                    }
                }
                double share = n == 0 ? 0 : (double) found / n;
                if (share > bestShare) {
                    bestShare = share;
                    best = new int[] {i, j};
                }
            }
        }
        return best;
    }

    /** "job" for the word before the key's place in the message ("Started job <*>"), else "value". */
    private static String keyName(String template, int index) {
        String[] parts = template.split(Pattern.quote(MessageStats.WILDCARD), -1);
        if (index < parts.length) {
            Matcher m = WORD.matcher(parts[index]);
            String last = null;
            while (m.find()) {
                last = m.group();
            }
            if (last != null && !START.contains(last.toLowerCase(Locale.ROOT))) {
                return last.toLowerCase(Locale.ROOT);
            }
        }
        return "value";
    }
}
