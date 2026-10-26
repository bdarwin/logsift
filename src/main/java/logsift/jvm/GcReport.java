package logsift.jvm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a GC log says, worked out: how much of the time the program stood still, the longest
 * pauses and what caused them, how fast it allocates, whether the memory left after each
 * collection keeps growing, and what went wrong (full collections, failures, stalls), each in a
 * sentence with what to try.
 */
public final class GcReport {

    /** In the order findings are listed: problems first, then "looks healthy", then the rest. */
    public enum Severity { BAD, WARN, GOOD, INFO }

    /**
     * One thing worth knowing.
     *
     * @param line the log line that shows it best, or -1
     */
    public record Finding(Severity severity, String title, String detail, String advice, long line) {}

    /** Pause times in ms. */
    public record Stats(int count, double total, double max, double avg, double p50, double p95, double p99,
                        double p999) {
        static Stats of(double[] ms) {
            if (ms.length == 0) {
                return new Stats(0, 0, 0, 0, 0, 0, 0, 0);
            }
            double[] s = ms.clone();
            Arrays.sort(s);
            double total = 0;
            for (double v : s) {
                total += v;
            }
            return new Stats(s.length, total, s[s.length - 1], total / s.length, pct(s, 0.5), pct(s, 0.95),
                    pct(s, 0.99), pct(s, 0.999));
        }

        private static double pct(double[] sorted, double p) {
            return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * (sorted.length - 1) + 0.5))];
        }
    }

    /** The pauses of one kind and cause. */
    public record Group(String name, String cause, int count, double total, double max, long worstLine) {}

    /** Pauses by length: {@code count} of them, {@code total} ms, between {@code fromMs} and {@code toMs}. */
    public record Bucket(double fromMs, double toMs, int count, double total) {}

    private final GcLog log;
    private final List<Finding> findings = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<Bucket> buckets = new ArrayList<>();
    private Stats pauses;
    private Stats stalls;
    private Stats full;
    private double start;
    private double end;
    private double pausedPct;
    private double worstWindowPct;
    private double worstWindowStart;
    private double windowSeconds;
    private double allocated; // bytes
    private double allocRate; // bytes per second
    private double peakAllocRate;
    private long peakUsed = -1;
    private long peakAfter = -1;
    private double concurrentMs;
    private double safepointMs;
    private double maxReachMs;
    private long maxReachLine = -1;

    private GcReport(GcLog log) {
        this.log = log;
    }

    public GcLog log() {
        return log;
    }

    public List<Finding> findings() {
        return findings;
    }

    /** Pauses by kind and cause, most total time first. */
    public List<Group> groups() {
        return groups;
    }

    public List<Bucket> buckets() {
        return buckets;
    }

    /** Pauses that stopped every thread (not allocation stalls). */
    public Stats pauses() {
        return pauses;
    }

    /** Threads waiting for memory (ZGC, Shenandoah). */
    public Stats stalls() {
        return stalls;
    }

    public Stats fullCollections() {
        return full;
    }

    /** Seconds since the JVM started of the first and the last thing in the log. */
    public double start() {
        return start;
    }

    public double end() {
        return end;
    }

    public double seconds() {
        return Math.max(0, end - start);
    }

    /** Share of the time (0..100) every thread stood still for GC. */
    public double pausedPercent() {
        return pausedPct;
    }

    /** The worst stretch of {@link #windowSeconds()}: its share in GC and where it starts. */
    public double worstWindowPercent() {
        return worstWindowPct;
    }

    public double worstWindowStart() {
        return worstWindowStart;
    }

    public double windowSeconds() {
        return windowSeconds;
    }

    /** Bytes allocated in all (from the heap before each collection and after the one before). */
    public double allocatedBytes() {
        return allocated;
    }

    /** Bytes allocated per second, on average and at the most (between two collections). */
    public double allocationRate() {
        return allocRate;
    }

    public double peakAllocationRate() {
        return peakAllocRate;
    }

    /** The most heap in use (before a collection), or -1. */
    public long peakUsed() {
        return peakUsed;
    }

    /** The most heap left after a collection, or -1. */
    public long peakAfterGc() {
        return peakAfter;
    }

    /** Time GC worked alongside the program, in ms. */
    public double concurrentMs() {
        return concurrentMs;
    }

    /** Time threads stood still at safepoints in all (GC or not), in ms; 0 if the log doesn't say. */
    public double safepointMs() {
        return safepointMs;
    }

    /** The longest it took threads to stop at a safepoint, in ms. */
    public double maxReachMs() {
        return maxReachMs;
    }

    /** The heap size to measure against: the largest the heap may be, or the largest it was. */
    public long heapSize() {
        long cap = log.maxHeap();
        for (GcLog.Event e : log.events()) {
            cap = Math.max(cap, e.capacity());
        }
        return cap;
    }

    // ---------------------------------------------------------------- working it out

    public static GcReport of(GcLog log) {
        GcReport r = new GcReport(log);
        r.compute();
        return r;
    }

    private void compute() {
        List<GcLog.Event> ev = log.events();
        start = Double.MAX_VALUE;
        end = -Double.MAX_VALUE;
        List<Double> p = new ArrayList<>();
        List<Double> st = new ArrayList<>();
        List<Double> fl = new ArrayList<>();
        Map<String, double[]> byGroup = new LinkedHashMap<>(); // count, total, max, worst line
        for (GcLog.Event e : ev) {
            start = Math.min(start, e.time());
            end = Math.max(end, e.end());
            if (e.kind() == GcLog.Kind.STALL) {
                st.add(e.ms());
            } else if (e.stops()) {
                p.add(e.ms());
                if (e.kind() == GcLog.Kind.FULL) {
                    fl.add(e.ms());
                }
                double[] g = byGroup.computeIfAbsent(e.name() + "\u0000" + e.cause(), k -> new double[] {0, 0, -1, -1});
                g[0]++;
                g[1] += e.ms();
                if (e.ms() > g[2]) {
                    g[2] = e.ms();
                    g[3] = e.line();
                }
            }
            if (e.before() > peakUsed) {
                peakUsed = e.before();
            }
            if (e.after() > peakAfter) {
                peakAfter = e.after();
            }
        }
        if (ev.isEmpty()) {
            start = 0;
            end = 0;
        }
        for (GcLog.Stop s : log.stops()) {
            safepointMs += s.totalMs();
            if (s.reachMs() > maxReachMs) {
                maxReachMs = s.reachMs();
                maxReachLine = s.line();
            }
        }
        pauses = Stats.of(p.stream().mapToDouble(Double::doubleValue).toArray());
        stalls = Stats.of(st.stream().mapToDouble(Double::doubleValue).toArray());
        full = Stats.of(fl.stream().mapToDouble(Double::doubleValue).toArray());
        byGroup.forEach((k, g) -> {
            String[] nc = k.split("\u0000", 2);
            groups.add(new Group(nc[0], nc[1], (int) g[0], g[1], g[2], (long) g[3]));
        });
        groups.sort((a, b) -> Double.compare(b.total(), a.total()));
        concurrentMs = concurrent(ev);
        double span = seconds();
        pausedPct = span > 0 ? Math.min(100, pauses.total() / 10 / span) : 0;
        computeBuckets();
        windows();
        allocation();
        computeFindings();
    }

    /**
     * Time GC worked alongside the program: G1 logs one line per cycle (count those), ZGC one per
     * collection (minor ones inside a major one are not counted again), the others their phases.
     */
    private double concurrent(List<GcLog.Event> ev) {
        boolean cycles = ev.stream().anyMatch(e -> e.kind() == GcLog.Kind.CONCURRENT && e.name().endsWith("Cycle"));
        boolean major = ev.stream().anyMatch(e -> e.name().equals("Major Collection"));
        double sum = 0;
        for (GcLog.Event e : ev) {
            if (e.kind() != GcLog.Kind.CONCURRENT || cycles && !e.name().endsWith("Cycle")
                    || major && e.name().equals("Minor Collection")) {
                continue;
            }
            sum += e.ms();
        }
        return sum;
    }

    private static final double[] EDGES = {0, 1, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, Double.MAX_VALUE};

    private void computeBuckets() {
        int[] count = new int[EDGES.length - 1];
        double[] total = new double[EDGES.length - 1];
        for (GcLog.Event e : log.events()) {
            if (!e.stops() || e.kind() == GcLog.Kind.STALL) {
                continue;
            }
            for (int i = 0; i < count.length; i++) {
                if (e.ms() < EDGES[i + 1]) {
                    count[i]++;
                    total[i] += e.ms();
                    break;
                }
            }
        }
        int last = count.length - 1;
        while (last > 0 && count[last] == 0) {
            last--;
        }
        for (int i = 0; i <= last; i++) {
            buckets.add(new Bucket(EDGES[i], EDGES[i + 1], count[i], total[i]));
        }
    }

    /** The share of time in pauses per stretch of time; the worst one. */
    private void windows() {
        double span = seconds();
        windowSeconds = span >= 600 ? 60 : span >= 60 ? 10 : Math.max(1, span / 10);
        double[] w = pausedPerWindow(windowSeconds);
        for (int i = 0; i < w.length; i++) {
            double pct = w[i] / 10 / windowSeconds;
            if (pct > worstWindowPct) {
                worstWindowPct = Math.min(100, pct);
                worstWindowStart = start + i * windowSeconds;
            }
        }
    }

    /** Pause ms in each stretch of {@code seconds} from the start (pauses split at the edges). */
    public double[] pausedPerWindow(double seconds) {
        int n = Math.max(1, (int) Math.ceil(seconds() / seconds));
        double[] out = new double[n];
        for (GcLog.Event e : log.events()) {
            if (!e.stops() || e.kind() == GcLog.Kind.STALL || e.ms() <= 0) {
                continue;
            }
            double a = e.time() - start;
            double b = a + e.ms() / 1000;
            for (int i = Math.max(0, (int) (a / seconds)); i < n && i * seconds < b; i++) {
                double lo = Math.max(a, i * seconds);
                double hi = Math.min(b, (i + 1) * seconds);
                if (hi > lo) {
                    out[i] += (hi - lo) * 1000;
                }
            }
        }
        return out;
    }

    /**
     * Bytes allocated in each stretch of {@code seconds} from the start: what the heap grew by
     * between two collections, counted in the stretch the second one started in.
     */
    public double[] allocatedPerWindow(double seconds) {
        int n = Math.max(1, (int) Math.ceil(seconds() / seconds));
        double[] out = new double[n];
        List<GcLog.Event> h = withHeap();
        for (int i = 1; i < h.size(); i++) {
            double grew = Math.max(0, h.get(i).before() - h.get(i - 1).after());
            int w = (int) Math.min(n - 1, Math.max(0, (h.get(i).time() - start) / seconds));
            out[w] += grew;
        }
        return out;
    }

    /** Collections with the heap before and after, in time order. */
    public List<GcLog.Event> withHeap() {
        List<GcLog.Event> out = new ArrayList<>();
        for (GcLog.Event e : log.events()) {
            if (e.before() >= 0 && e.after() >= 0) {
                out.add(e);
            }
        }
        return out;
    }

    private void allocation() {
        List<GcLog.Event> h = withHeap();
        for (int i = 1; i < h.size(); i++) {
            GcLog.Event prev = h.get(i - 1);
            GcLog.Event cur = h.get(i);
            double grew = Math.max(0, cur.before() - prev.after());
            allocated += grew;
            double dt = cur.time() - prev.end();
            if (dt > 0.05) {
                peakAllocRate = Math.max(peakAllocRate, grew / dt);
            }
        }
        if (h.size() >= 2) {
            double dt = h.get(h.size() - 1).time() - h.get(0).end();
            allocRate = dt > 0 ? allocated / dt : 0;
        }
    }

    /**
     * The least heap left after a collection in each tenth of the log: what the program keeps
     * alive (it can't be less). Rising floors mean memory that is never freed.
     */
    public long[] floors(int parts) {
        long[] out = new long[parts];
        Arrays.fill(out, -1);
        double span = Math.max(1e-9, seconds());
        for (GcLog.Event e : withHeap()) {
            int i = (int) Math.min(parts - 1, Math.max(0, (e.end() - start) / span * parts));
            if (out[i] < 0 || e.after() < out[i]) {
                out[i] = e.after();
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- findings

    private void computeFindings() {
        List<GcLog.Event> ev = log.events();
        long heap = heapSize();
        String collector = log.collector();
        boolean concurrentCollector = collector.equals("G1") || collector.equals("CMS") || collector.equals("ZGC")
                || collector.equals("Shenandoah");
        if (ev.isEmpty()) {
            findings.add(new Finding(Severity.INFO, "No garbage collections in this log",
                    "Logsift found no collections it can read. The log may be cut short, or use a format Logsift"
                            + " doesn't know.", "Log GC with -Xlog:gc*:file=gc.log:time,uptime,level,tags (Java 9"
                            + " and later) or -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log (Java 8).", -1));
            return;
        }

        // time stopped
        if (pauses.count() > 0) {
            Severity sev = pausedPct >= 10 ? Severity.BAD : pausedPct >= 5 ? Severity.WARN : Severity.INFO;
            String worst = worstWindowPct >= pausedPct * 1.5 && worstWindowPct >= 10
                    ? String.format(Locale.ROOT, " At its worst, %s from %s, it was %s of the time.",
                    duration(windowSeconds), when(worstWindowStart), pct(worstWindowPct)) : "";
            findings.add(new Finding(sev, String.format(Locale.ROOT, "GC stopped the program %s of the time",
                    pct(pausedPct)),
                    String.format(Locale.ROOT, "%,d pauses, %s in all over %s.", pauses.count(), ms(pauses.total()),
                            duration(seconds())) + worst,
                    sev == Severity.INFO ? "" : "The heap is probably too small for how much the program keeps and"
                            + " allocates: give it more memory (-Xmx), or allocate less.", -1));
        }

        // longest pause
        GcLog.Event longest = null;
        for (GcLog.Event e : ev) {
            if (e.stops() && e.kind() != GcLog.Kind.STALL && (longest == null || e.ms() > longest.ms())) {
                longest = e;
            }
        }
        if (longest != null) {
            long over200 = ev.stream().filter(e -> e.stops() && e.kind() != GcLog.Kind.STALL && e.ms() >= 200).count();
            Severity sev = longest.ms() >= 1000 ? Severity.BAD : longest.ms() >= 200 ? Severity.WARN : Severity.INFO;
            findings.add(new Finding(sev, "Longest pause: " + ms(longest.ms()),
                    describe(longest) + " at " + when(longest.time()) + "."
                            + (over200 > 1 ? String.format(Locale.ROOT, " %,d pauses took 200 ms or more.", over200) : "")
                            + String.format(Locale.ROOT, " Half the pauses take under %s, 99%% under %s.",
                            ms(pauses.p50()), ms(pauses.p99())),
                    sev == Severity.INFO ? "" : advice(longest), longest.line()));
        }

        // full collections
        if (full.count() > 0) {
            Map<String, Integer> causes = new LinkedHashMap<>();
            GcLog.Event worst = null;
            boolean degenerated = false;
            for (GcLog.Event e : ev) {
                if (e.kind() == GcLog.Kind.FULL) {
                    causes.merge(e.cause().isEmpty() ? "no cause given" : e.cause(), 1, Integer::sum);
                    degenerated |= e.name().contains("Degenerated");
                    if (worst == null || e.ms() > worst.ms()) {
                        worst = e;
                    }
                }
            }
            StringBuilder why = new StringBuilder();
            causes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4).forEach(c -> {
                why.append(why.length() == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%,d %s", c.getValue(),
                        c.getKey()));
            });
            boolean onlySystem = causes.size() == 1 && causes.containsKey("System.gc()");
            Severity sev = onlySystem ? Severity.INFO : concurrentCollector || full.count() * 60.0 > seconds()
                    ? Severity.BAD : Severity.WARN;
            String what = degenerated ? "Shenandoah fell back to stopping the program" : "Full collections";
            findings.add(new Finding(sev, String.format(Locale.ROOT, "%s: %,d, %s in all", what, full.count(),
                    ms(full.total())),
                    "Why: " + why + ". The longest took " + ms(full.max()) + "."
                            + (concurrentCollector && !onlySystem ? " " + collector
                            + " works alongside the program; a full collection means it couldn't keep up and"
                            + " stopped everything." : ""),
                    onlySystem ? "" : "Give the heap more room (-Xmx), or find what fills it: if the memory left"
                            + " after GC keeps growing, take a heap dump.", worst == null ? -1 : worst.line()));
        }

        // memory left after GC keeps growing: the floors trend up (G1's are noisy: a young
        // collection doesn't touch the old generation, so the trend counts, not each step)
        long[] f = floors(12);
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < f.length; i++) {
            if (f[i] >= 0) {
                pts.add(new double[] {i, f[i]});
            }
        }
        int third = Math.max(1, pts.size() / 3);
        double early = 0;
        double late = 0;
        for (int i = 0; i < third && pts.size() >= 3; i++) {
            early += pts.get(i)[1] / third;
            late += pts.get(pts.size() - 1 - i)[1] / third;
        }
        if (pts.size() >= 6 && correlation(pts) >= 0.7 && late >= early * 1.4
                && (heap <= 0 || late - early > heap * 0.1)) {
            findings.add(new Finding(Severity.BAD, "The memory left after GC keeps growing",
                    String.format(Locale.ROOT, "From about %s to %s over %s%s. The program keeps more and more:"
                                    + " a leak, or a cache with no limit.", size(early), size(late), duration(seconds()),
                            heap > 0 ? " (the heap is " + size(heap) + ")" : ""),
                    "Take two heap dumps some time apart and compare them (Logsift: Compare with Another Dump)"
                            + " to see what grows.", -1));
        } else if (heap > 0 && !pts.isEmpty() && late > heap * 0.8) {
            findings.add(new Finding(Severity.WARN, String.format(Locale.ROOT, "After GC the heap is still %s full",
                    pct(100.0 * late / heap)),
                    "About " + size(late) + " of " + size(heap) + " stays in use, so collections free little"
                            + " and come often.",
                    "Give the heap more room (-Xmx), or look at a heap dump for what holds the memory.", -1));
        }

        // things that went wrong
        Map<String, List<GcLog.Event>> trouble = new LinkedHashMap<>();
        for (GcLog.Event e : ev) {
            if (!e.trouble().isEmpty()) {
                String t = e.trouble().toLowerCase(Locale.ROOT);
                String key = t.contains("to-space") || t.contains("evacuation failure") ? "evacuation"
                        : t.contains("promotion failed") ? "promotion" : t.contains("concurrent mode") ? "cmf" : t;
                trouble.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
        }
        trouble.forEach((k, list) -> {
            GcLog.Event e = list.get(0);
            switch (k) {
                case "evacuation" -> findings.add(new Finding(Severity.BAD, String.format(Locale.ROOT,
                        "G1 ran out of room to copy objects %s", times(list.size())),
                        "Collections with \"" + e.trouble() + "\" are slow, and often end in a full collection.",
                        "Give the heap more room (-Xmx), start marking earlier (lower -XX:InitiatingHeapOccupancyPercent),"
                                + " or keep more free (-XX:G1ReservePercent).", e.line()));
                case "promotion" -> findings.add(new Finding(Severity.BAD, String.format(Locale.ROOT,
                        "Promotion failed %s", times(list.size())),
                        "The old generation had no room for objects moving up from the young one, so a full collection"
                                + " ran instead.", "Give the heap more room, or start the old generation's"
                        + " collections earlier (-XX:CMSInitiatingOccupancyFraction).", e.line()));
                case "cmf" -> findings.add(new Finding(Severity.BAD, String.format(Locale.ROOT,
                        "CMS couldn't keep up %s (concurrent mode failure)", times(list.size())),
                        "The old generation filled before CMS finished, so it stopped the program for a full collection.",
                        "Start CMS earlier (-XX:CMSInitiatingOccupancyFraction=60 -XX:+UseCMSInitiatingOccupancyOnly),"
                                + " give the heap more room, or move to G1.", e.line()));
                default -> findings.add(new Finding(Severity.WARN, String.format(Locale.ROOT, "%s: %s", e.trouble(),
                        times(list.size())), "", "", e.line()));
            }
        });

        // threads waiting for memory
        if (stalls.count() > 0) {
            GcLog.Event worst = ev.stream().filter(e -> e.kind() == GcLog.Kind.STALL)
                    .max((a, b) -> Double.compare(a.ms(), b.ms())).orElse(null);
            findings.add(new Finding(Severity.BAD, String.format(Locale.ROOT,
                    "Threads waited for memory %s, %s in all", times(stalls.count()), ms(stalls.total())),
                    "An allocation stall stops the thread that allocates until GC frees memory: " + collector
                            + " couldn't free it fast enough. The longest wait was " + ms(stalls.max()) + ".",
                    "Give the heap more room (-Xmx), or GC more threads (-XX:ConcGCThreads).",
                    worst == null ? -1 : worst.line()));
        }

        // causes worth knowing
        cause(ev, "System.gc()", Severity.WARN, "Something called System.gc() %s",
                "Each call runs a full collection (unless the JVM makes it concurrent). Libraries do it too, such as RMI"
                        + " every hour.",
                "Find the caller, or let the JVM ignore it (-XX:+DisableExplicitGC) or run it alongside the program"
                        + " (-XX:+ExplicitGCInvokesConcurrent).");
        cause(ev, "Metadata GC Threshold", Severity.WARN, "Metaspace filled %s",
                "Loading classes filled the metaspace, which starts a collection.",
                "Start with a bigger metaspace (-XX:MetaspaceSize=256m). If it keeps happening, classes are loaded"
                        + " again and again (a class loader leak).");
        cause(ev, "G1 Humongous Allocation", Severity.INFO, n -> n == 1 ? "A large array started a collection"
                        : String.format(Locale.ROOT, "Large arrays started %,d collections", n),
                "Objects of half a G1 region or more are \"humongous\": they are put in the old generation directly"
                        + " and can start collections early.",
                "Use bigger regions (-XX:G1HeapRegionSize=16m or 32m), or allocate fewer large arrays.");
        cause(ev, "GCLocker Initiated GC", Severity.INFO, "JNI critical sections delayed GC %s",
                "A collection waited for native code holding a JNI critical section.", "");

        // the clock vs the GC threads
        int slow = 0;
        int kernel = 0;
        GcLog.Event slowest = null;
        for (GcLog.Event e : ev) {
            if (e.real() >= 0.1 && e.real() > (e.user() + e.sys()) * 1.2 && e.user() >= 0) {
                slow++;
                if (slowest == null || e.real() > slowest.real()) {
                    slowest = e;
                }
            }
            if (e.real() >= 0.05 && e.sys() > e.user() && e.sys() > 0.03) {
                kernel++;
            }
        }
        if (slow > 0) {
            findings.add(new Finding(Severity.WARN, String.format(Locale.ROOT,
                    "%s waited for the machine, not for GC work", slow == 1 ? "A pause" : String.format(Locale.ROOT, "%,d pauses", slow)),
                    String.format(Locale.ROOT, "They took longer on the clock than the GC threads worked (the worst:"
                                    + " %.2f s on the clock, %.2f s of CPU).", slowest.real(),
                            slowest.user() + slowest.sys()),
                    "The machine was busy with something else, swapping, or the container's CPU was throttled. Check"
                            + " the CPU limit, other processes and swap.", slowest.line()));
        }
        if (kernel > 2) {
            findings.add(new Finding(Severity.WARN, String.format(Locale.ROOT,
                    "%,d pauses spent most of their time in the kernel", kernel),
                    "GC threads spent more time in the operating system than in GC work.",
                    "Usually swapping or transparent huge pages: check swap, and try"
                            + " echo madvise > /sys/kernel/mm/transparent_hugepage/enabled.", -1));
        }

        // stopping threads
        if (maxReachMs >= 100) {
            findings.add(new Finding(maxReachMs >= 1000 ? Severity.BAD : Severity.WARN,
                    "Threads took up to " + ms(maxReachMs) + " to stop for a safepoint",
                    "Every thread must stop before GC (and other JVM work) can start; one that doesn't stop soon"
                            + " holds up all the others.",
                    "A long loop without a safepoint check, or a large array copy. Log -Xlog:safepoint and"
                            + " look for the thread with -XX:+SafepointTimeout.", maxReachLine));
        }
        if (safepointMs > 0 && pauses.total() > 0 && safepointMs > pauses.total() * 1.5 && safepointMs - pauses.total() > 1000) {
            findings.add(new Finding(Severity.INFO, "The JVM stopped the program for more than GC",
                    String.format(Locale.ROOT, "Threads stood still %s in all, of which GC pauses were %s.",
                            ms(safepointMs), ms(pauses.total())),
                    "Other safepoints: deoptimization, biased locking, thread dumps, class redefinition.", -1));
        }

        // allocation
        if (allocRate > 0) {
            findings.add(new Finding(allocRate > 1e9 ? Severity.WARN : Severity.INFO,
                    "The program allocates " + size(allocRate) + " a second",
                    String.format(Locale.ROOT, "%s in all%s.", size(allocated), peakAllocRate > allocRate * 1.5
                            ? ", up to " + size(peakAllocRate) + " a second" : ""),
                    allocRate > 1e9 ? "That much allocation keeps GC busy: look for objects made in hot loops"
                            + " (a profile's allocation view shows where)." : "", -1));
        }

        if (findings.stream().noneMatch(x -> x.severity() == Severity.BAD || x.severity() == Severity.WARN)) {
            findings.add(0, new Finding(Severity.GOOD, "GC looks healthy",
                    "Short pauses, little time stopped, and no collection had trouble.", "", -1));
        }
        findings.sort((a, b) -> a.severity().compareTo(b.severity()));
    }

    private static double correlation(List<double[]> pts) {
        int n = pts.size();
        double sx = 0;
        double sy = 0;
        for (double[] p : pts) {
            sx += p[0];
            sy += p[1];
        }
        double mx = sx / n;
        double my = sy / n;
        double xy = 0;
        double xx = 0;
        double yy = 0;
        for (double[] p : pts) {
            xy += (p[0] - mx) * (p[1] - my);
            xx += (p[0] - mx) * (p[0] - mx);
            yy += (p[1] - my) * (p[1] - my);
        }
        return xx == 0 || yy == 0 ? 0 : xy / Math.sqrt(xx * yy);
    }

    /** "once", "twice", "12 times". */
    static String times(long n) {
        return n == 1 ? "once" : n == 2 ? "twice" : String.format(Locale.ROOT, "%,d times", n);
    }

    private void cause(List<GcLog.Event> ev, String cause, Severity sev, String title, String detail, String advice) {
        cause(ev, cause, sev, n -> String.format(Locale.ROOT, title, times(n)), detail, advice);
    }

    private void cause(List<GcLog.Event> ev, String cause, Severity sev, java.util.function.IntFunction<String> title,
                       String detail, String advice) {
        int n = 0;
        long line = -1;
        for (GcLog.Event e : ev) {
            if (e.cause().equals(cause) && (e.stops() || e.kind() == GcLog.Kind.CONCURRENT)) {
                n++;
                if (line < 0) {
                    line = e.line();
                }
            }
        }
        if (n > 0) {
            findings.add(new Finding(sev, title.apply(n), detail, advice, line));
        }
    }

    private String describe(GcLog.Event e) {
        String s = e.name() + (e.cause().isEmpty() ? "" : " (" + e.cause() + ")");
        if (e.before() >= 0 && e.after() >= 0) {
            s += ", " + size(e.before()) + " → " + size(e.after());
        }
        return s;
    }

    private String advice(GcLog.Event e) {
        if (e.kind() == GcLog.Kind.FULL) {
            return "Full collections stop everything for the whole heap. Avoid them: give the heap more room, or"
                    + " find what fills it.";
        }
        if (e.real() >= 0 && e.real() > (e.user() + e.sys()) * 1.2 && e.real() > 0.1) {
            return "This pause waited for the machine (more clock time than CPU): check CPU limits and swapping.";
        }
        if (log.collector().equals("G1")) {
            return "For shorter pauses: -XX:MaxGCPauseMillis lower (G1 then collects less each time), or a"
                    + " collector made for short pauses (ZGC, Shenandoah).";
        }
        if (log.collector().equals("Parallel") || log.collector().equals("Serial")) {
            return log.collector() + " stops the program for all its work. For shorter pauses use G1 (or ZGC).";
        }
        return "";
    }

    String when(double t) {
        long w = log.wallOf(t);
        if (w != Long.MIN_VALUE) {
            return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                    .format(java.time.Instant.ofEpochMilli(w).atZone(java.time.ZoneId.systemDefault()));
        }
        return duration(t) + " after the start";
    }

    // ---------------------------------------------------------------- words

    /** "12 ms", "1.4 s", "0.12 ms". */
    public static String ms(double ms) {
        if (ms >= 1000) {
            return String.format(Locale.ROOT, ms >= 10_000 ? "%.0f s" : "%.1f s", ms / 1000);
        }
        if (ms >= 10) {
            return String.format(Locale.ROOT, "%.0f ms", ms);
        }
        if (ms >= 1) {
            return String.format(Locale.ROOT, "%.1f ms", ms);
        }
        return String.format(Locale.ROOT, "%.2f ms", ms);
    }

    /** "2 h 5 min", "3 min 20 s", "12.3 s". */
    public static String duration(double s) {
        if (s >= 3600) {
            return String.format(Locale.ROOT, "%d h %d min", (long) (s / 3600), (long) (s % 3600 / 60));
        }
        if (s >= 60) {
            return String.format(Locale.ROOT, "%d min %d s", (long) (s / 60), (long) (s % 60));
        }
        return String.format(Locale.ROOT, s >= 10 ? "%.0f s" : "%.1f s", s);
    }

    public static String pct(double p) {
        return String.format(Locale.ROOT, p >= 10 || p == 0 ? "%.0f%%" : p >= 1 ? "%.1f%%" : "%.2f%%", p);
    }

    /** "1.2 GB", "340 MB", "12 KB". */
    public static String size(double bytes) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v >= 100 || i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
