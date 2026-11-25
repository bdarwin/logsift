package logsift.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import logsift.core.LogAnalysis;
import logsift.core.LogDocument;
import logsift.heap.HeapDiff;
import logsift.heap.HeapDump;
import logsift.heap.HeapFindings;
import logsift.jvm.DumpParser;
import logsift.jvm.HeapHistogram;
import logsift.jvm.JThread;
import logsift.jvm.Profile;
import logsift.jvm.Profiler;
import logsift.jvm.ThreadAnalysis;
import logsift.jvm.ThreadDump;

/**
 * Two of the same thing from a JVM, compared as the window compares them: heap dumps (what grew
 * and what holds it), heap histograms (the leak check), profiles (what got slower or faster) and
 * thread dumps (what changed, what stays stuck). Also a short Flight Recorder recording of a
 * running JVM, always asked first. Only explanations go back, never whole files.
 */
public final class JvmCompareTools {
    private JvmCompareTools() {}

    public static List<Tool> all() {
        return List.of(COMPARE_HEAP_DUMPS, COMPARE_HISTOGRAMS, COMPARE_PROFILES, COMPARE_THREAD_DUMPS, RECORD_PROFILE);
    }

    private record Def(String name, String title, String description, Map<String, Object> inputSchema, Body body)
            implements Tool {
        @Override
        public String run(Args args, Workspace w) throws Exception {
            return body.run(args, w);
        }
    }

    @FunctionalInterface
    private interface Body {
        String run(Tool.Args a, Workspace w) throws Exception;
    }

    private static final String FILE_ARG = " A path (~ is the home folder), a name from list_logs, or a log handle "
            + "(log:3) that another tool gave.";

    /** A file an agent named: a path, a name from the log locations, or a handle of a file Logsift made. */
    static Path file(Tool.Args a, String name, Workspace w, String what) throws Exception {
        String s = a.string(name).strip();
        if (s.matches("log:\\d+")) {
            Path p = w.shownPath(s);
            if (p == null) {
                throw new Tool.Failure("No open log " + s + " for " + name + ". Give the file's path instead.");
            }
            return p;
        }
        Path p = w.check(w.pathOf(s), what);
        if (Files.isDirectory(p)) {
            throw new Tool.Failure(name + " is a folder: " + p + ". Give a file.");
        }
        if (!Files.isRegularFile(p)) {
            throw new Tool.Failure("No such file for " + name + ": " + p + ". Check the path (list_logs, or ask "
                    + "the person where it is).");
        }
        return p;
    }

    private static int maxChars(Tool.Args a) throws Tool.Failure {
        return (int) a.number("max_chars", Workspace.DEFAULT_MAX_CHARS, 500, Workspace.HARD_MAX_CHARS);
    }

    private static String n(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static String signed(long v) {
        return (v >= 0 ? "+" : "−") + n(Math.abs(v));
    }

    private static String signedBytes(long v) {
        return (v >= 0 ? "+" : "−") + HeapFindings.bytes(Math.abs(v));
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** A time as this computer shows it (heap dumps and thread dumps keep theirs as instants). */
    private static String when(long millis) {
        return millis <= 0 ? "" : WHEN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    // ---------------------------------------------------------------- heap dumps

    static final Tool COMPARE_HEAP_DUMPS = new Def("compare_heap_dumps", "Compare two heap dumps",
            """
                    Compares two Java heap dumps (.hprof) of the same program, taken some time apart, and says what \
                    grew between them: the places (a static field, a cache, a collection) that hold more memory now, \
                    what they hold, and every class with its objects and bytes before and after. This is how to find \
                    a memory leak: whatever keeps growing is the leak, and its place is what keeps it alive.
                    Use it when the person has two dumps of one program (for example one at start and one when memory \
                    was high), or after taking two with heap_dump some minutes apart. For one dump, use \
                    analyze_heap_dump; for a quick check without dumps, compare_histograms with two heap_histogram \
                    results.
                    The two are put in order by when they were taken, whatever order you give them. Big dumps take a \
                    while to read (about a minute per GB each): when the answer says "Still …", call again with the \
                    same arguments.
                    Example: compare_heap_dumps before=~/dumps/orders-0900.hprof after=~/dumps/orders-1100.hprof
                    The answer starts with "In use: 410 MB → 1.3 GB (+890 MB)", then "What grew" (one line per place, \
                    biggest first), then "Classes that grew" (objects and bytes, before → after).
                    Mistakes to avoid: dumps of two different programs (the comparison means nothing), and calling \
                    it a leak when the growth is small or the program was just busier: say how much grew and where.""",
            new Tool.Schema().string("before", "The earlier heap dump (.hprof)." + FILE_ARG, true)
                    .string("after", "The later heap dump (.hprof)." + FILE_ARG, true)
                    .integer("places", "Most places listed under What grew (default 8, at most 50).", false)
                    .integer("classes", "Most classes listed (default 15, at most 100).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                Path before = file(a, "before", w, "read the heap dump");
                Path after = file(a, "after", w, "read the heap dump");
                if (before.equals(after)) {
                    throw new Tool.Failure("before and after are the same file. Give two dumps taken at different times.");
                }
                int places = (int) a.number("places", 8, 1, 50);
                int classes = (int) a.number("classes", 15, 1, 100);
                String key = "heapdiff:" + before + ":" + Files.getLastModifiedTime(before).toMillis() + ":" + after + ":"
                        + Files.getLastModifiedTime(after).toMillis() + ":" + places + ":" + classes;
                String text;
                try {
                    text = w.await(key, "reading the heap dumps " + before.getFileName() + " and " + after.getFileName(),
                            () -> compareDumps(before, after, places, classes));
                } catch (java.io.IOException e) {
                    throw new Tool.Failure("Could not compare them: " + e.getMessage());
                }
                return w.finish(text, maxChars(a));
            });

    private static String compareDumps(Path before, Path after, int placeLimit, int classLimit) throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long sa = Files.size(before);
        long sb = Files.size(after);
        String room = HeapDump.roomProblem(tmp, sa, sb);
        if ((HeapDump.needsDisk(sa) || HeapDump.needsDisk(sb)) && room != null) {
            throw new java.io.IOException(room);
        }
        Path wa = HeapDump.needsDisk(sa) ? Files.createTempDirectory(tmp, "logsift-heap-") : null;
        Path wb = HeapDump.needsDisk(sb) ? Files.createTempDirectory(tmp, "logsift-heap-") : null;
        java.util.function.BooleanSupplier stop = () -> Thread.currentThread().isInterrupted();
        try (HeapDump x0 = HeapDump.open(before, wa, null, stop); HeapDump y0 = HeapDump.open(after, wb, null, stop)) {
            HeapDump x = x0;
            HeapDump y = y0;
            boolean swapped = false;
            if (x.timestamp() > y.timestamp() && y.timestamp() > 0) { // the earlier one first
                x = y0;
                y = x0;
                swapped = true;
            }
            HeapDiff.Report r = HeapDiff.of(x, y, stop);
            return heapText(x, y, r, swapped, placeLimit, classLimit);
        } finally {
            for (Path d : new Path[] {wa, wb}) {
                if (d != null) {
                    deleteAll(d);
                }
            }
        }
    }

    static String heapText(HeapDump x, HeapDump y, HeapDiff.Report r, boolean swapped, int placeLimit, int classLimit) {
        StringBuilder b = new StringBuilder();
        b.append(x.fileName()).append(" → ").append(y.fileName());
        String from = when(r.beforeTime());
        String to = when(r.afterTime());
        if (!from.isEmpty() && !to.isEmpty()) {
            b.append(" (taken ").append(from).append(" → ").append(to).append(')');
        }
        if (swapped) {
            b.append(" — put in the order they were taken");
        }
        b.append('\n');
        long g = r.growth();
        b.append("In use (strongly reachable): ").append(HeapFindings.bytes(r.beforeBytes())).append(" → ")
                .append(HeapFindings.bytes(r.afterBytes())).append(" (").append(signedBytes(g)).append(").\n");
        b.append("\nWhat grew (where the memory is kept):\n");
        List<HeapDiff.Place> grew = r.places().stream().filter(p -> p.growth() > 0).toList();
        if (grew.isEmpty()) {
            b.append(g <= 0 ? "Nothing: the heap didn't grow (" + HeapFindings.bytes(Math.abs(g)) + " less in use).\n"
                    : "No one place holds the growth: it is spread over many objects. See the classes below.\n");
        }
        int i = 0;
        for (HeapDiff.Place p : grew) {
            if (i >= placeLimit) {
                b.append("… ").append(grew.size() - placeLimit).append(" more places (places=").append(grew.size())
                        .append(" to see them).\n");
                break;
            }
            String what = HeapFindings.shorten(p.after());
            b.append(++i).append(". ");
            if (p.beforeBytes() == 0) {
                b.append("New: ").append(what).append(" in ").append(p.where()).append(" holds ")
                        .append(HeapFindings.bytes(p.afterBytes()));
            } else {
                b.append(what.replaceAll(" \\(.*", "")).append(" in ").append(p.where()).append(" grew by ")
                        .append(HeapFindings.bytes(p.growth())).append(" (").append(HeapFindings.bytes(p.beforeBytes()))
                        .append(" → ").append(HeapFindings.bytes(p.afterBytes())).append(')');
                if (p.before().contains("(") && p.after().contains("(")) {
                    b.append("; ").append(p.before().replaceAll(".*\\(|\\)", "")).append(" → ")
                            .append(p.after().replaceAll(".*\\(|\\)", ""));
                }
            }
            b.append('\n');
            if (!p.contents().isEmpty()) {
                b.append("   ").append(LogTools.cut(p.contents(), 300)).append('\n');
            }
        }
        List<HeapDiff.ClassDelta> cls = new ArrayList<>(r.classes());
        cls.sort(Comparator.comparingLong(HeapDiff.ClassDelta::growth).reversed());
        b.append("\nClasses that grew most (objects before → after, bytes before → after):\n");
        int shown = 0;
        for (HeapDiff.ClassDelta c : cls) {
            if (shown >= classLimit || c.growth() <= 0) {
                break;
            }
            shown++;
            b.append("- ").append(HeapHistogram.readable(c.name())).append(": ").append(n(c.beforeCount())).append(" → ")
                    .append(n(c.afterCount())).append(" objects (").append(signed(c.afterCount() - c.beforeCount()))
                    .append("), ").append(HeapFindings.bytes(c.beforeBytes())).append(" → ")
                    .append(HeapFindings.bytes(c.afterBytes())).append(" (").append(signedBytes(c.growth())).append(")\n");
        }
        if (shown == 0) {
            b.append("(none grew)\n");
        }
        List<HeapDiff.ClassDelta> shrank = cls.stream().filter(c -> c.growth() < 0)
                .sorted(Comparator.comparingLong(HeapDiff.ClassDelta::growth)).limit(5).toList();
        if (!shrank.isEmpty()) {
            b.append("Shrank most: ");
            b.append(String.join(", ", shrank.stream().map(c -> HeapHistogram.readable(c.name()) + " "
                    + signedBytes(c.growth())).toList())).append('\n');
        }
        return b.toString().stripTrailing();
    }

    private static void deleteAll(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException e) {
            // a temporary folder: the system cleans it up too
        }
    }

    // ---------------------------------------------------------------- histograms

    static final Tool COMPARE_HISTOGRAMS = new Def("compare_histograms", "Compare two heap histograms",
            """
                    Compares two heap histograms of the same JVM (objects counted by class, as jcmd GC.class_histogram, \
                    jmap -histo or heap_histogram write them) and lists the classes that grew most between them, by \
                    bytes, with their object counts. The quick leak check: take a histogram, wait some minutes while \
                    the program works, take another, compare. Classes that keep growing are where memory leaks; \
                    compare_heap_dumps then says what holds them.
                    Each of before and after is a histogram file, or the handle heap_histogram gave ("All classes: \
                    log:4").
                    Example: compare_histograms before=log:4 after=log:7
                    The answer: totals before → after, then a table | class | objects | change | size | change |, \
                    biggest growth first, and the classes that shrank most.
                    Mistakes to avoid: comparing a histogram of live objects (jmap -histo:live, which runs a full GC) \
                    with one that counts garbage too (heap_histogram's, jcmd GC.class_histogram -all): garbage \
                    makes classes look like they grew. Compare two of the same kind. One histogram alone says what \
                    is big, not what leaks.""",
            new Tool.Schema().string("before", "The earlier histogram." + FILE_ARG, true)
                    .string("after", "The later histogram." + FILE_ARG, true)
                    .integer("top", "How many classes (default 20, at most 100).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                Path before = file(a, "before", w, "read the histogram");
                Path after = file(a, "after", w, "read the histogram");
                int top = (int) a.number("top", 20, 1, 100);
                HeapHistogram x = histogram(before, "before");
                HeapHistogram y = histogram(after, "after");
                return w.finish(histogramText(before, after, x, y, top), maxChars(a));
            });

    private static HeapHistogram histogram(Path f, String which) throws Exception {
        if (Files.size(f) > 512L << 20) {
            throw new Tool.Failure(which + " is too big for a histogram (" + HeapFindings.bytes(Files.size(f))
                    + "): " + f.getFileName() + ". Is it a heap dump? Use compare_heap_dumps for .hprof files.");
        }
        HeapHistogram h = HeapHistogram.parse(Files.readString(f, StandardCharsets.UTF_8).replace("\r", ""), 0);
        if (h.rows().isEmpty()) {
            throw new Tool.Failure(which + " (" + f.getFileName() + ") is not a heap histogram: no lines like "
                    + "\"   1:   20000   1234567  java.lang.String\". Give the output of heap_histogram, jcmd "
                    + "GC.class_histogram or jmap -histo.");
        }
        return h;
    }

    static String histogramText(Path before, Path after, HeapHistogram x, HeapHistogram y, int top) {
        StringBuilder b = new StringBuilder();
        b.append(before.getFileName()).append(" → ").append(after.getFileName()).append(": ").append(n(x.instances()))
                .append(" → ").append(n(y.instances())).append(" objects (").append(signed(y.instances() - x.instances()))
                .append("), ").append(HeapFindings.bytes(x.bytes())).append(" → ").append(HeapFindings.bytes(y.bytes()))
                .append(" (").append(signedBytes(y.bytes() - x.bytes())).append(").\n");
        List<HeapHistogram.Change> ch = HeapHistogram.compare(x, y);
        List<HeapHistogram.Change> grew = ch.stream().filter(c -> c.bytesGrown() > 0).toList();
        b.append("\nGrew most:\n");
        if (grew.isEmpty()) {
            b.append("No class grew.\n");
        } else {
            b.append("| class | objects | change | size | change |\n|---|---|---|---|---|\n");
            for (HeapHistogram.Change c : grew.subList(0, Math.min(top, grew.size()))) {
                b.append("| ").append(HeapHistogram.readable(c.className())).append(" | ").append(n(c.instancesAfter()))
                        .append(" | ").append(signed(c.instancesGrown())).append(" | ")
                        .append(HeapFindings.bytes(c.bytesAfter())).append(" | ").append(signedBytes(c.bytesGrown()))
                        .append(c.instancesBefore() == 0 ? " (new)" : "").append(" |\n");
            }
            if (grew.size() > top) {
                b.append("… ").append(grew.size() - top).append(" more classes grew (top=").append(Math.min(100,
                        grew.size())).append(").\n");
            }
        }
        List<HeapHistogram.Change> shrank = ch.stream().filter(c -> c.bytesGrown() < 0)
                .sorted(Comparator.comparingLong(HeapHistogram.Change::bytesGrown)).limit(5).toList();
        if (!shrank.isEmpty()) {
            b.append("Shrank most: ").append(String.join(", ", shrank.stream().map(c ->
                    HeapHistogram.readable(c.className()) + " " + signedBytes(c.bytesGrown())).toList())).append('\n');
        }
        return b.toString().stripTrailing();
    }

    // ---------------------------------------------------------------- profiles

    static final Tool COMPARE_PROFILES = new Def("compare_profiles", "Compare two profiles",
            """
                    Compares two profiles of the same program (Flight Recorder .jfr files, async-profiler output, or \
                    collapsed stacks "frame;frame;frame count") and says which methods take a bigger or smaller share \
                    of the time (or of the allocations, or of the lock waits) in the second one. Use it for "it got \
                    slower after the release": a profile of the good version as before, of the slow one as after. \
                    Shares are compared, not raw times, so two recordings of different lengths compare fairly.
                    Each kind a recording holds (CPU, wall clock, allocations, locks) is compared with the same kind \
                    in the other. record_profile makes a recording of a running JVM and says where it saved it.
                    Example: compare_profiles before=~/profiles/v1.jfr after=~/profiles/v2.jfr
                    The answer, per kind: the totals, "Bigger share now" (method, share before → after, in \
                    percentage points), "Smaller share now", and for the program's own code the share with what it \
                    calls.
                    Mistakes to avoid: comparing profiles of different work (a busy hour with a quiet one), or a CPU \
                    profile with a wall-clock one; a method a few tenths of a point apart hasn't changed.""",
            new Tool.Schema().string("before", "The earlier or good profile (.jfr, or collapsed stacks)." + FILE_ARG, true)
                    .string("after", "The later or slow profile." + FILE_ARG, true)
                    .integer("limit", "Most methods per list (default 10, at most 50).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                Path before = file(a, "before", w, "read the profile");
                Path after = file(a, "after", w, "read the profile");
                int limit = (int) a.number("limit", 10, 1, 50);
                List<Profile> x = readProfiles(w, before);
                List<Profile> y = readProfiles(w, after);
                return w.finish(profileText(before, after, x, y, limit), maxChars(a));
            });

    private static List<Profile> readProfiles(Workspace w, Path f) throws Exception {
        try {
            return w.await("profile:" + f + ":" + Files.getLastModifiedTime(f).toMillis(), "reading " + f.getFileName(),
                    () -> Profiler.open(f));
        } catch (java.io.IOException e) {
            throw new Tool.Failure(e.getMessage() + ". Give a .jfr recording or collapsed stacks.");
        }
    }

    /** What a profile measured ("CPU", "Allocations"), from its title; "" when its title doesn't say. */
    static String kind(Profile p) {
        for (Profiler.Mode m : Profiler.Mode.values()) {
            if (p.title().startsWith(m.label + " ")) {
                return m.label;
            }
        }
        return "";
    }

    static String profileText(Path before, Path after, List<Profile> x, List<Profile> y, int limit) {
        StringBuilder b = new StringBuilder();
        b.append(before.getFileName()).append(" → ").append(after.getFileName()).append('\n');
        Map<String, Profile> later = new LinkedHashMap<>();
        for (Profile p : y) {
            later.putIfAbsent(kind(p), p);
        }
        int pairs = 0;
        for (Profile p : x) {
            Profile q = later.get(kind(p));
            if (q == null && x.size() == 1 && y.size() == 1) {
                q = y.get(0); // one each: the same kind, as far as anyone can tell
            }
            if (q == null) {
                continue;
            }
            pairs++;
            pair(b, p, q, limit);
        }
        if (pairs == 0) {
            b.append("Nothing to compare: they measured different things (")
                    .append(String.join(", ", x.stream().map(JvmCompareTools::kind).toList())).append(" against ")
                    .append(String.join(", ", y.stream().map(JvmCompareTools::kind).toList()))
                    .append("). Record the same kind in both.");
        }
        return b.toString().stripTrailing();
    }

    private record Share(String name, double before, double after, boolean own) {
        double change() {
            return after - before;
        }
    }

    private static void pair(StringBuilder b, Profile p, Profile q, int limit) {
        String k = kind(p).isEmpty() ? "Samples" : kind(p);
        long tp = p.total();
        long tq = q.total();
        b.append("\n").append(k).append(": ").append(p.amount(tp)).append(" → ").append(q.amount(tq)).append('\n');
        if (tp <= 0 || tq <= 0) {
            b.append("(one of them has no samples of this kind)\n");
            return;
        }
        Map<String, Profile.Method> mp = new HashMap<>();
        for (Profile.Method m : p.hottest(Integer.MAX_VALUE)) {
            mp.put(m.name(), m);
        }
        Map<String, Profile.Method> mq = new HashMap<>();
        for (Profile.Method m : q.hottest(Integer.MAX_VALUE)) {
            mq.put(m.name(), m);
        }
        java.util.Set<String> names = new java.util.HashSet<>(mp.keySet());
        names.addAll(mq.keySet());
        List<Share> self = new ArrayList<>();
        List<Share> total = new ArrayList<>();
        for (String name : names) {
            Profile.Method a = mp.get(name);
            Profile.Method c = mq.get(name);
            Profile.Method any = c != null ? c : a;
            boolean own = !TraceTools.system(any);
            self.add(new Share(name, a == null ? 0 : 100.0 * a.self() / tp, c == null ? 0 : 100.0 * c.self() / tq, own));
            total.add(new Share(name, a == null ? 0 : 100.0 * a.total() / tp, c == null ? 0 : 100.0 * c.total() / tq, own));
        }
        list(b, "Bigger share now (time of their own):", self.stream().filter(s -> s.change() >= 0.5)
                .sorted(Comparator.comparingDouble(Share::change).reversed()).limit(limit).toList());
        list(b, "Smaller share now (time of their own):", self.stream().filter(s -> s.change() <= -0.5)
                .sorted(Comparator.comparingDouble(Share::change)).limit(limit).toList());
        list(b, "Program's own code, with what it calls:", total.stream().filter(s -> s.own() && Math.abs(s.change()) >= 0.5)
                .sorted(Comparator.comparingDouble((Share s) -> Math.abs(s.change())).reversed()).limit(limit).toList());
    }

    private static void list(StringBuilder b, String title, List<Share> l) {
        b.append(title).append('\n');
        if (l.isEmpty()) {
            b.append("   (no method moved by half a point or more)\n");
            return;
        }
        int i = 0;
        for (Share s : l) {
            b.append(String.format(Locale.ROOT, "%2d. %s  %.1f%% → %.1f%% (%s%.1f points)%s%n", ++i,
                    LogTools.cut(s.name(), 200), s.before(), s.after(), s.change() >= 0 ? "+" : "−", Math.abs(s.change()),
                    s.before() == 0 ? " new" : s.after() == 0 ? " gone" : ""));
        }
    }

    // ---------------------------------------------------------------- thread dumps

    static final Tool COMPARE_THREAD_DUMPS = new Def("compare_thread_dumps", "Compare thread dumps",
            """
                    Compares Java thread dumps taken some seconds apart, from two or more files (each may hold \
                    several dumps): what each thread did in the first and the last, which threads are new or gone, \
                    which stayed stuck in the same place in every dump, who holds the locks others wait for, and the \
                    findings across all dumps (deadlocks, full pools, busy threads). Stuck in the same frame in every \
                    dump means a hang or a slow call there; a thread that moves is working.
                    Use it when the dumps are in separate files (thread_dump gives one handle per dump). For one file \
                    with several dumps in it, analyze_threads says the same.
                    Example: compare_thread_dumps logs=log:3,log:5,log:8   or   logs=~/dumps/t1.txt,~/dumps/t2.txt
                    The answer: the dumps in time order, the findings, "Stuck in every dump" (how many threads, what \
                    they do), "Lock waits in the last dump", then first → last: new, gone and changed threads.
                    Mistakes to avoid: dumps of different JVMs (thread names won't match); calling one dump stuck: \
                    you need two or more.""",
            new Tool.Schema().string("logs", "Two or more thread dump files or log handles, separated by commas, "
                    + "earliest first if their dumps have no times (log:3,log:5 or ~/t1.txt,~/t2.txt).", true)
                    .integer("limit", "Most threads per list (default 15, at most 100).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                List<String> names = new ArrayList<>();
                for (String s : a.string("logs").split("[,\n]")) {
                    if (!s.isBlank()) {
                        names.add(s.strip());
                    }
                }
                if (names.size() < 2) {
                    throw new Tool.Failure("Give two or more files or handles in logs, separated by commas "
                            + "(logs=log:3,log:5). For one file with several dumps, use analyze_threads.");
                }
                if (names.size() > 50) {
                    throw new Tool.Failure("At most 50 files at a time.");
                }
                int limit = (int) a.number("limit", 15, 1, 100);
                record Dump(ThreadDump dump, String from) {}
                List<Dump> dumps = new ArrayList<>();
                for (String name : names) {
                    LogAnalysis log = w.log(name);
                    LogDocument doc = log.document();
                    List<ThreadDump> found = w.await(w.handleOf(log) + ":threads", "reading the thread dumps in "
                            + log.path().getFileName(), () -> {
                                while (doc.isIndexing()) {
                                    Thread.sleep(50);
                                }
                                return DumpParser.scan(doc, null, LogTools.INTERRUPTED);
                            });
                    if (found.isEmpty()) {
                        throw new Tool.Failure("There is no thread dump in " + name + " (" + log.path().getFileName()
                                + "). Give files with jstack, jcmd Thread.print or kill -3 output.");
                    }
                    String h = w.handleOf(log);
                    for (ThreadDump d : found) {
                        dumps.add(new Dump(d, h + " " + log.path().getFileName()));
                    }
                }
                if (dumps.stream().allMatch(d -> d.dump().time() != ThreadDump.NO_TIME)) {
                    dumps.sort(Comparator.comparingLong(d -> d.dump().time()));
                }
                List<ThreadDump> all = dumps.stream().map(Dump::dump).toList();
                StringBuilder b = new StringBuilder();
                b.append(all.size()).append(" thread dumps:\n");
                for (int i = 0; i < dumps.size(); i++) {
                    ThreadDump d = all.get(i);
                    b.append(i + 1).append(". ").append(d.time() == ThreadDump.NO_TIME ? "(no time)" : when(d.time()))
                            .append(", ").append(d.threads().size()).append(" threads, from ").append(dumps.get(i).from())
                            .append('\n');
                }
                ThreadAnalysis.Report r = ThreadAnalysis.analyze(all);
                b.append('\n').append(r.summary()).append('\n');
                int i = 0;
                for (ThreadAnalysis.Finding f : r.findings()) {
                    if (i >= limit) {
                        break;
                    }
                    b.append(++i).append(". [").append(f.severity()).append("] ").append(f.title());
                    if (f.detail() != null && !f.detail().isBlank()) {
                        b.append(" — ").append(LogTools.cut(f.detail().replace('\n', ' '), 400));
                    }
                    b.append('\n');
                }
                List<ThreadAnalysis.Stuck> stuck = ThreadAnalysis.stuck(all);
                b.append("\nStuck in every dump (same stack each time):\n");
                if (stuck.isEmpty()) {
                    b.append("No thread stayed in the same place in every dump (idle and sleeping threads aside).\n");
                }
                for (ThreadAnalysis.Stuck s : stuck.subList(0, Math.min(limit, stuck.size()))) {
                    b.append("- ").append(s.keys().size()).append(s.keys().size() == 1 ? " thread" : " threads")
                            .append(" (").append(LogTools.cut(String.join(", ", s.keys()), 200)).append("): ")
                            .append(ThreadAnalysis.doing(s.sample()))
                            .append(s.millis() > 0 ? " for at least " + ThreadAnalysis.duration(s.millis()) : "").append('\n');
                }
                ThreadDump first = all.get(0);
                ThreadDump last = all.get(all.size() - 1);
                List<ThreadAnalysis.Contention> locks = ThreadAnalysis.contention(last);
                if (!locks.isEmpty()) {
                    b.append("\nLock waits in the last dump:\n");
                    for (ThreadAnalysis.Contention c : locks.subList(0, Math.min(limit, locks.size()))) {
                        b.append("- ").append(c.waiters().size()).append(" waiting for ").append(c.lock().type())
                                .append(" <").append(c.lock().id()).append(">, held by ")
                                .append(c.holder() == null ? "no thread in the dump" : "\"" + c.holder().name() + "\" ("
                                        + ThreadAnalysis.doing(c.holder()) + ")").append('\n');
                    }
                }
                List<ThreadAnalysis.Change> changes = ThreadAnalysis.compare(first, last);
                Map<ThreadAnalysis.ChangeKind, List<ThreadAnalysis.Change>> by = new LinkedHashMap<>();
                for (ThreadAnalysis.Change c : changes) {
                    by.computeIfAbsent(c.kind(), k -> new ArrayList<>()).add(c);
                }
                b.append("\nFirst → last dump: ").append(by.getOrDefault(ThreadAnalysis.ChangeKind.NEW, List.of()).size())
                        .append(" new, ").append(by.getOrDefault(ThreadAnalysis.ChangeKind.GONE, List.of()).size())
                        .append(" gone, ").append(by.getOrDefault(ThreadAnalysis.ChangeKind.CHANGED, List.of()).size())
                        .append(" doing something else, ").append(by.getOrDefault(ThreadAnalysis.ChangeKind.SAME,
                                List.of()).size()).append(" the same.\n");
                for (ThreadAnalysis.ChangeKind k : List.of(ThreadAnalysis.ChangeKind.NEW, ThreadAnalysis.ChangeKind.GONE,
                        ThreadAnalysis.ChangeKind.CHANGED)) {
                    List<ThreadAnalysis.Change> l = by.getOrDefault(k, List.of());
                    if (l.isEmpty()) {
                        continue;
                    }
                    b.append(switch (k) {
                        case NEW -> "New:\n";
                        case GONE -> "Gone:\n";
                        default -> "Doing something else:\n";
                    });
                    for (ThreadAnalysis.Change c : l.subList(0, Math.min(limit, l.size()))) {
                        JThread t = c.thread();
                        b.append("- \"").append(t.name()).append("\": ");
                        if (k == ThreadAnalysis.ChangeKind.CHANGED) {
                            b.append(ThreadAnalysis.doing(c.before())).append(" → ").append(ThreadAnalysis.doing(c.after()));
                        } else {
                            b.append(ThreadAnalysis.doing(t));
                        }
                        b.append('\n');
                    }
                    if (l.size() > limit) {
                        b.append("… ").append(l.size() - limit).append(" more\n");
                    }
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    // ---------------------------------------------------------------- recording a running JVM

    /** One recording at a time, whoever asks. */
    private static final AtomicBoolean RECORDING = new AtomicBoolean();
    private static final AtomicInteger RECORDINGS = new AtomicInteger();
    /** Recordings started, by id: what they record. */
    private static final Map<String, String> STARTED = new ConcurrentHashMap<>();

    /** Where recordings are saved: Logsift's own folder. */
    static Path profilesDir() {
        return logsift.core.CacheSettings.home().resolve("profiles");
    }

    static final Tool RECORD_PROFILE = new Def("record_profile", "Record what a JVM does",
            """
                    Records what a running JVM does for a few seconds with Java Flight Recorder (never anything else): \
                    where its threads spend CPU, wall-clock time, allocations or lock waits. Always asks the person \
                    first (unless they turned recordings off), and only one recording runs at a time. The program \
                    runs a few percent slower while it records; nothing in it is changed.
                    Use it when the question is "what is it doing right now" or "why is it slow now" and logs and \
                    thread dumps don't say. Take thread_dump first: it is quicker and often enough.
                    jvm is a handle from list_jvms (jvm:1), a service name or a program. mode: cpu (default; where \
                    the CPU goes), wall (time running or waiting: slow calls, I/O), alloc (what allocates memory), \
                    lock (where threads wait for locks). seconds: 5 to 60 (default 15).
                    The call waits while it records. If the answer says "Still recording", call record_profile \
                    again with only recording=<the id it gave> (no jvm, nothing is asked again) to get the result.
                    Example: record_profile jvm=jvm:1 mode=cpu seconds=20   then, if told: record_profile recording=rec:1
                    The answer is the profile explained like analyze_profile (methods with the most time, the \
                    program's own code, hottest stacks) and the files it was saved to (collapsed stacks, in Logsift's \
                    folder), for compare_profiles later.
                    Mistakes to avoid: recording a JVM that is idle (nothing to see: record while the slow thing \
                    happens), and asking again after the person said no.""",
            new Tool.Schema().string("jvm", "A handle from list_jvms (jvm:1), a service, or a program.", false)
                    .choice("mode", "What to measure (default cpu).", false, "cpu", "wall", "alloc", "lock")
                    .integer("seconds", "How long to record, 5 to 60 (default 15).", false)
                    .string("recording", "The id of a recording already started (rec:1), to get its result.", false)
                    .integer("limit", "Most methods per list (default 15).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                int limit = (int) a.number("limit", 15, 1, 200);
                if (a.has("recording")) {
                    String id = a.string("recording").strip();
                    String what = STARTED.get(id);
                    if (what == null) {
                        throw new Tool.Failure("No recording " + id + ". Start one with jvm=…");
                    }
                    return w.finish(awaitRecording(w, id, what, null, limit), maxChars(a));
                }
                if (!a.has("jvm")) {
                    throw new Tool.Failure("Missing argument: jvm (a handle from list_jvms, like jvm:1), or recording "
                            + "for one already started.");
                }
                JvmReach.Place p = w.jvms().find(a.string("jvm"));
                String modeName = a.string("mode", "cpu").strip().toLowerCase(Locale.ROOT);
                Profiler.Mode mode = switch (modeName) {
                    case "cpu" -> Profiler.Mode.CPU;
                    case "wall" -> Profiler.Mode.WALL;
                    case "alloc" -> Profiler.Mode.ALLOC;
                    case "lock" -> Profiler.Mode.LOCK;
                    default -> throw new Tool.Failure("mode must be cpu, wall, alloc or lock, not " + modeName);
                };
                int seconds = (int) a.number("seconds", 15, 5, 60);
                if (RECORDING.get()) {
                    throw new Tool.Failure("A recording is already running; one at a time. Wait for it (its "
                            + "recording=… id), then try again.");
                }
                String what = "record " + mode.label.toLowerCase(Locale.ROOT) + " of " + p.label() + " for " + seconds
                        + " seconds (Flight Recorder)";
                w.approve(JvmRules.Action.PROFILE, what, null, "Flight Recorder records for " + seconds + " s; the "
                        + "program runs a few percent slower meanwhile. Nothing is changed. Saved in " + profilesDir()
                        + ".");
                if (!RECORDING.compareAndSet(false, true)) {
                    throw new Tool.Failure("A recording is already running; one at a time. Try again when it's done.");
                }
                String id = "rec:" + RECORDINGS.incrementAndGet();
                STARTED.put(id, what);
                Profiler.Request req = Profiler.Request.of(Profiler.Engine.JFR, mode, seconds);
                return w.finish(awaitRecording(w, id, what, () -> {
                    try {
                        JvmReach.Target t = w.jvms().connect(p);
                        List<Profile> views = t.record(req, () -> Thread.currentThread().isInterrupted());
                        if (views == null || views.isEmpty()) {
                            throw new Tool.Failure("The recording was stopped before it finished.");
                        }
                        return save(w, p, mode, views, limit);
                    } finally {
                        RECORDING.set(false);
                    }
                }, limit), maxChars(a));
            });

    private static String awaitRecording(Workspace w, String id, String what, java.util.concurrent.Callable<String> job,
                                         int limit) throws Exception {
        java.util.concurrent.Callable<String> j = job != null ? job : () -> {
            throw new Tool.Failure("Recording " + id + " isn't known here: start a new one with jvm=…");
        };
        try {
            return w.await("record:" + id, "recording", j);
        } catch (Workspace.StillWorking e) {
            throw new Workspace.StillWorking("Still recording (" + what + "). Call record_profile recording=" + id
                    + " in a few seconds for the result; don't start another.");
        }
    }

    private static String save(Workspace w, JvmReach.Place p, Profiler.Mode mode, List<Profile> views, int limit)
            throws java.io.IOException {
        Path dir = profilesDir();
        Files.createDirectories(dir);
        String base = (p.program().isEmpty() ? "jvm" : p.program()).replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "jvm";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        String stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        StringBuilder b = new StringBuilder();
        TraceTools.profileText(b, views.get(0), limit);
        List<String> saved = new ArrayList<>();
        for (Profile v : views) {
            if (v.total() <= 0) {
                continue;
            }
            String k = kind(v).isEmpty() ? mode.label : kind(v);
            Path f = dir.resolve(base + "-" + stamp + "-" + k.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                    + ".collapsed.txt");
            Files.writeString(f, v.toCollapsed(), StandardCharsets.UTF_8);
            w.made(f);
            saved.add(k + ": " + f);
        }
        b.append("Saved (collapsed stacks; analyze_profile or compare_profiles read them):\n");
        for (String s : saved) {
            b.append("- ").append(s).append('\n');
        }
        b.append("Tell the person where they are: they stay until the person removes them.");
        return b.toString();
    }
}
