package logsift.jvm;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Java GC log read into collections: when each one ran, what kind it was and why, how much
 * heap it found and left, and how long it stopped the program. Reads the logs of Java 9 and later
 * ({@code -Xlog:gc*}, any decorations, also mixed into other output) and the older
 * {@code -XX:+PrintGCDetails} logs of Java 8 and earlier, for every collector (G1, Parallel,
 * Serial, CMS, ZGC, Shenandoah).
 */
public final class GcLog {

    /** What a collection (or a stop) was. */
    public enum Kind {
        YOUNG("Young"), MIXED("Mixed"), FULL("Full"), REMARK("Remark"), CLEANUP("Cleanup"),
        INITIAL_MARK("Initial mark"), PAUSE("Other pause"), CONCURRENT("Concurrent"), STALL("Allocation stall");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Whether the program's threads stood still for it. */
        public boolean stops() {
            return this != CONCURRENT;
        }
    }

    /**
     * One collection, pause or stall.
     *
     * @param line     the log line it was read from (0-based)
     * @param time     when it started, in seconds since the JVM started
     * @param wall     when it started as epoch millis, or {@link Long#MIN_VALUE} if the log has no dates
     * @param id       the GC number the log gives it, or -1
     * @param name     what the log calls it ("Pause Young (Normal)", "Full GC")
     * @param cause    why it ran ("Allocation Failure", "System.gc()"), or ""
     * @param before   heap in use before, in bytes, or -1
     * @param after    heap in use after, in bytes, or -1
     * @param capacity heap size after, in bytes, or -1
     * @param ms       how long it took
     * @param user     CPU seconds of the GC threads in user mode, or -1
     * @param sys      ... in the kernel, or -1
     * @param real     seconds on the clock, or -1
     * @param trouble  what went wrong in it ("to-space exhausted", "promotion failed"), or ""
     */
    public record Event(long line, double time, long wall, int id, Kind kind, String name, String cause,
                        long before, long after, long capacity, double ms, double user, double sys, double real,
                        String trouble) {
        public boolean stops() {
            return kind.stops();
        }

        /** The time it ended, in seconds since the JVM started. */
        public double end() {
            return time + ms / 1000;
        }
    }

    /**
     * Threads stopped at a safepoint (GC or not).
     *
     * @param reachMs how long it took all threads to stop, or -1
     * @param totalMs how long they were stopped in all
     */
    public record Stop(long line, double time, double reachMs, double totalMs, String reason) {}

    private final List<Event> events = new ArrayList<>();
    private final List<Stop> stops = new ArrayList<>();
    private String collector = "";
    private String jvm = "";
    private long maxHeap = -1;
    private String flags = "";
    private long wallOffset = Long.MIN_VALUE; // epoch millis at JVM start
    private boolean modern;

    private GcLog() {}

    public List<Event> events() {
        return events;
    }

    public List<Stop> stops() {
        return stops;
    }

    /** "G1", "Parallel", "ZGC"…, or "" if the log doesn't say. */
    public String collector() {
        return collector;
    }

    /** The JVM's version if the log says, or "". */
    public String jvmVersion() {
        return jvm;
    }

    /** The largest the heap may grow to, in bytes, or -1. */
    public long maxHeap() {
        return maxHeap;
    }

    /** The JVM's flags if the log lists them (Java 8: "CommandLine flags"), or "". */
    public String flags() {
        return flags;
    }

    /** Whether the log is in the format of Java 9 and later. */
    public boolean isUnified() {
        return modern;
    }

    /** Epoch millis of a time in seconds since the JVM started, or {@link Long#MIN_VALUE} without dates. */
    public long wallOf(double time) {
        return wallOffset == Long.MIN_VALUE ? Long.MIN_VALUE : wallOffset + Math.round(time * 1000);
    }

    // ---------------------------------------------------------------- reading

    /** Lines of the log, read in blocks. */
    public interface Lines {
        long count();

        /** Reads lines {@code [first, first + n)} into {@code out}. */
        void read(long first, int n, String[] out);
    }

    /** A log in memory, as lines. */
    public static Lines of(List<String> lines) {
        return new Lines() {
            @Override
            public long count() {
                return lines.size();
            }

            @Override
            public void read(long first, int n, String[] out) {
                for (int i = 0; i < n; i++) {
                    out[i] = lines.get((int) first + i);
                }
            }
        };
    }

    public static GcLog parse(List<String> lines) {
        return parse(of(lines), null, () -> false);
    }

    /**
     * Reads a log. {@code progress} (or null) gets 0..1.
     *
     * @throws java.util.concurrent.CancellationException if cancelled
     */
    public static GcLog parse(Lines src, DoubleConsumer progress, BooleanSupplier cancelled) {
        GcLog g = new GcLog();
        Parser p = g.new Parser();
        long n = src.count();
        String[] buf = new String[4096];
        for (long i = 0; i < n; i += buf.length) {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            int c = (int) Math.min(buf.length, n - i);
            src.read(i, c, buf);
            for (int j = 0; j < c; j++) {
                if (buf[j] != null) {
                    p.line(i + j, buf[j]);
                }
            }
            if (progress != null) {
                progress.accept((double) Math.min(n, i + c) / Math.max(1, n));
            }
        }
        p.finish();
        g.events.sort(java.util.Comparator.comparingDouble(Event::time));
        return g;
    }

    /**
     * Whether text (the start of a file) looks like a GC log, or has GC log lines in it: a few
     * collections in either format.
     */
    public static boolean looksLikeGcLog(String head) {
        int hits = 0;
        for (String line : head.split("\n", 5000)) {
            if (UNIFIED_GC.matcher(line).find() || OLD_EVENT.matcher(line).find()) {
                if (++hits >= 3) {
                    return true;
                }
            }
        }
        return false;
    }

    // a Java 9+ line about a collection: decorations, then "GC(12) Pause …" or the like
    private static final Pattern UNIFIED_GC = Pattern.compile(
            "^(?:\\[[^\\]]*\\])+\\s*(?:GC\\(\\d+\\) |Using |Allocation Stall )");
    // a Java 8 collection: "2026-…: 1.234: [GC (…" or "1.234: [Full GC (…"
    private static final Pattern OLD_EVENT = Pattern.compile(
            "^(?:(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d+[+-]\\d{4}): )?(?:(\\d+[.,]\\d+): )?"
                    + "\\[(Full GC|GC|CMS-concurrent-|GC concurrent-)");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx", Locale.ROOT);

    static long wall(String s) {
        try {
            if (s.endsWith("Z")) {
                return java.time.Instant.parse(s).toEpochMilli();
            }
            return OffsetDateTime.parse(s, ISO).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }

    /** "24M", "65536K", "24576.0K", "0.0B", "1G" in bytes; -1 if not a size. */
    static long bytes(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        char u = Character.toUpperCase(s.charAt(s.length() - 1));
        long mul = switch (u) {
            case 'B' -> 1;
            case 'K' -> 1L << 10;
            case 'M' -> 1L << 20;
            case 'G' -> 1L << 30;
            case 'T' -> 1L << 40;
            default -> 0;
        };
        String num = mul == 0 ? s : s.substring(0, s.length() - 1);
        if (mul == 0) {
            mul = 1;
        }
        try {
            return Math.round(Double.parseDouble(num.replace(',', '.')) * mul);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    /** The text in the first parentheses (nested ones kept: "System.gc()"), or "". */
    static String firstParens(String s) {
        int open = s.indexOf('(');
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return s.substring(open + 1, i);
            }
        }
        return "";
    }

    /** Every top-level parenthesized group, in order. */
    static List<String> parens(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                if (depth++ == 0) {
                    start = i + 1;
                }
            } else if (c == ')' && depth > 0 && --depth == 0) {
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the parser

    // Java 9+: what follows the decorations
    private static final Pattern TAGS = Pattern.compile("[a-z][a-z0-9_]*(,[a-z][a-z0-9_]*)*");
    private static final Pattern U_PAUSE = Pattern.compile(
            "^GC\\((\\d+)\\) (?:([YO]): )?(Pause .+?)(?: (\\d[\\d.,]*[BKMGT])->(\\d[\\d.,]*[BKMGT])\\((\\d[\\d.,]*[BKMGT])\\))? (\\d[\\d.,]*)ms$");
    private static final Pattern U_CONCURRENT = Pattern.compile(
            "^GC\\((\\d+)\\) (Concurrent .+?)(?: (\\d[\\d.,]*[BKMGT])->(\\d[\\d.,]*[BKMGT])\\((\\d[\\d.,]*[BKMGT])\\))? (\\d[\\d.,]*)ms$");
    private static final Pattern U_ZCYCLE = Pattern.compile(
            "^GC\\((\\d+)\\) ((?:Major|Minor) Collection|Garbage Collection) \\((.+)\\) "
                    + "(\\d[\\d.,]*[BKMGT])\\((\\d+)%\\)->(\\d[\\d.,]*[BKMGT])\\((\\d+)%\\)(?: (\\d[\\d.,]*)s)?$");
    private static final Pattern U_STALL = Pattern.compile("^Allocation Stall \\((.+)\\) (\\d[\\d.,]*)ms$");
    private static final Pattern U_CPU = Pattern.compile(
            "^GC\\((\\d+)\\) User=(\\d[\\d.,]*)s Sys=(\\d[\\d.,]*)s Real=(\\d[\\d.,]*)s$");
    private static final Pattern U_USING = Pattern.compile("^Using (.+)$");
    private static final Pattern U_VERSION = Pattern.compile("^Version: (\\S+)");
    private static final Pattern U_MAX = Pattern.compile("^(?:Heap )?Max Capacity: (\\d[\\d.,]*[BKMGT])$");
    private static final Pattern U_MAX11 = Pattern.compile("size: (\\d+) MB, Compressed Oops");
    private static final Pattern U_TROUBLE = Pattern.compile("^GC\\((\\d+)\\) (To-space exhausted|Evacuation Failure.*)$");
    private static final Pattern U_SAFEPOINT17 = Pattern.compile(
            "^Safepoint \"([^\"]+)\", Time since last: \\d+ ns, Reaching safepoint: (\\d+) ns,.* Total: (\\d+) ns");
    private static final Pattern STOPPED = Pattern.compile(
            "Total time for which application threads were stopped: (\\d[\\d.,]*) seconds, Stopping threads took: (\\d[\\d.,]*) seconds");

    // Java 8
    private static final Pattern O_G1HEAP = Pattern.compile(
            "Heap: (\\d[\\d.,]*[BKMGT])\\((\\d[\\d.,]*[BKMGT])\\)->(\\d[\\d.,]*[BKMGT])\\((\\d[\\d.,]*[BKMGT])\\)");
    private static final Pattern O_HEAP = Pattern.compile(
            "(\\d[\\d.,]*[BKMGT])->(\\d[\\d.,]*[BKMGT])\\((\\d[\\d.,]*[BKMGT])\\)");
    private static final Pattern O_SECS = Pattern.compile(", (\\d[\\d.,]*) secs\\]");
    private static final Pattern O_TIMES = Pattern.compile(
            "\\[Times: user=(\\d[\\d.,]*) sys=(\\d[\\d.,]*), real=(\\d[\\d.,]*) secs\\]");
    private static final Pattern O_CMS_CONC = Pattern.compile(
            "\\[CMS-concurrent-([a-z-]+): (\\d[\\d.,]*)/(\\d[\\d.,]*) secs\\]");
    private static final Pattern O_G1_CONC = Pattern.compile("\\[GC concurrent-([a-z-]+)-end, (\\d[\\d.,]*) secs\\]");
    private static final Pattern O_PHASE_START = Pattern.compile(
            "(?:\\d{4}-\\d\\d-\\d\\dT[\\d:.]+[+-]\\d{4}: )?(?:\\d+[.,]\\d+: )?\\[(?:CMS-concurrent|GC concurrent)-[a-z-]+-start\\]");
    private static final Pattern O_DATE_ONLY = Pattern.compile(
            "(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d+[+-]\\d{4}): (?!\\d+[.,]\\d+: )");
    private static final Pattern O_STAMP = Pattern.compile(
            "(?:(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d+[+-]\\d{4}): )?(\\d+[.,]\\d+): ");

    private final class Parser {
        // Java 8: the collection being read (its lines until [Times: …] or the next one)
        private StringBuilder pending;
        private long pendingLine;
        private double pendingTime;
        private long pendingWall;
        private final java.util.Map<Integer, Integer> lastPauseOfId = new java.util.HashMap<>();
        private final java.util.Map<Integer, String> troubleOfId = new java.util.HashMap<>();

        void line(long no, String s) {
            if (s.isEmpty()) {
                return;
            }
            if (s.charAt(0) == '<') {
                int close = s.indexOf("> ");
                if (close > 0 && close < 120) {
                    s = s.substring(close + 2); // a merged log's "<gc.log.1> " in front
                }
            }
            if (s.isEmpty()) {
                return;
            }
            if (s.charAt(0) == '[' && unified(no, s)) {
                return;
            }
            old(no, s);
        }

        // ---- Java 9+

        private boolean unified(long no, String s) {
            int pos = 0;
            double up = Double.NaN;
            long wall = Long.MIN_VALUE;
            String tags = null;
            int len = s.length();
            while (pos < len && s.charAt(pos) == '[') {
                int close = s.indexOf(']', pos);
                if (close < 0) {
                    return false;
                }
                String d = s.substring(pos + 1, close).trim();
                pos = close + 1;
                if (d.isEmpty()) {
                    continue;
                }
                char c0 = d.charAt(0);
                if (Character.isDigit(c0)) {
                    if (d.length() > 18 && d.charAt(4) == '-' && d.charAt(10) == 'T') {
                        wall = wall(d);
                    } else if (d.endsWith("ms")) {
                        up = parseOr(d.substring(0, d.length() - 2), Double.NaN) / 1000;
                    } else if (d.endsWith("ns")) {
                        up = parseOr(d.substring(0, d.length() - 2), Double.NaN) / 1e9;
                    } else if (d.endsWith("s")) {
                        up = parseOr(d.substring(0, d.length() - 1), Double.NaN);
                    }
                } else if (!isLevel(d)) {
                    String t = d.replace(" ", "");
                    if (TAGS.matcher(t).matches()) {
                        tags = t;
                    } else if (d.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == ':' || ch == '(')) {
                        return false; // not decorations ("[GC (Allocation Failure) [PSYoungGen: …" of an old log)
                    }
                    // else a host name ("[prod-app-01.acme.local]"): skipped
                }
            }
            String msg = s.substring(pos).trim();
            boolean tagless = false;
            if (tags == null || !tags.startsWith("gc") && !tags.startsWith("safepoint")) {
                // "-Xlog:gc:file=…:time" writes no level and tags: tell by the message
                if (msg.startsWith("GC(") || msg.startsWith("Using ") || msg.startsWith("Allocation Stall ")
                        || msg.startsWith("Version: ") || msg.contains("Max Capacity: ")) {
                    tags = "gc";
                } else if (msg.startsWith("Safepoint \"") || msg.startsWith("Total time for which")) {
                    tags = "safepoint";
                } else if (tags == null) {
                    return false;
                }
                tagless = true;
            }
            modern = true;
            if (Double.isNaN(up) && wall != Long.MIN_VALUE) {
                if (wallOffset == Long.MIN_VALUE) {
                    wallOffset = wall; // no uptime: time counts from the first line
                }
                up = (wall - wallOffset) / 1000.0;
            } else if (wall != Long.MIN_VALUE && wallOffset == Long.MIN_VALUE && !Double.isNaN(up)) {
                wallOffset = wall - Math.round(up * 1000);
            }
            if (tags.startsWith("safepoint")) {
                safepoint(no, up, msg);
                return true;
            }
            if (!tags.startsWith("gc")) {
                return true;
            }
            boolean plain = tags.equals("gc") || tagless;
            boolean phases = tags.equals("gc,phases");
            Matcher m;
            if ((plain || phases) && msg.startsWith("GC(") && (m = U_PAUSE.matcher(msg)).matches()) {
                if (phases && !isZ()) {
                    return true; // a part of a pause (logged in its summary)
                }
                int id = Integer.parseInt(m.group(1));
                String name = m.group(3);
                double ms = num(m.group(7));
                String label = name;
                String cause = "";
                int p = name.indexOf(" (");
                if (p > 0) {
                    label = name.substring(0, p);
                    List<String> ps = parens(name.substring(p));
                    // "Pause Young (Normal) (G1 Evacuation Pause)": the type, then why
                    if (ps.size() >= 2 && label.equals("Pause Young")) {
                        label += " (" + ps.get(0) + ")";
                        cause = ps.get(1);
                    } else if (!ps.isEmpty()) {
                        cause = ps.get(ps.size() - 1);
                        if (label.startsWith("Pause Mark Start") || label.startsWith("Pause Init Mark")
                                || label.startsWith("Pause Final Mark")) {
                            cause = ""; // "(Major)", "(unload classes)": not a reason
                        }
                    }
                    for (String x : ps) {
                        if (x.startsWith("Evacuation Failure")) {
                            troubleOfId.put(id, x);
                            cause = ps.size() >= 2 ? ps.get(1) : cause;
                        }
                    }
                }
                Kind k = unifiedKind(label);
                if (m.group(2) != null) { // generational ZGC: "Y: Pause Mark Start"
                    label += m.group(2).equals("Y") ? " (young)" : " (old)";
                }
                String trouble = troubleOfId.getOrDefault(id, "");
                add(new Event(no, up - ms / 1000, wallOf(up - ms / 1000), id, k, label, cause,
                        bytesOr(m.group(4)), bytesOr(m.group(5)), bytesOr(m.group(6)), ms, -1, -1, -1, trouble));
                lastPauseOfId.put(id, events.size() - 1);
                return true;
            }
            if (plain && msg.startsWith("GC(") && (m = U_ZCYCLE.matcher(msg)).matches()) {
                long before = bytes(m.group(4));
                int pct = Integer.parseInt(m.group(5));
                long cap = pct > 0 ? before * 100 / pct : maxHeap;
                double ms = m.group(8) == null ? 0 : num(m.group(8)) * 1000;
                String name = m.group(2);
                add(new Event(no, up - ms / 1000, wallOf(up - ms / 1000), Integer.parseInt(m.group(1)), Kind.CONCURRENT,
                        name, m.group(3), before, bytes(m.group(6)), maxHeap > 0 ? maxHeap : cap, ms, -1, -1, -1, ""));
                return true;
            }
            if (plain && (m = U_STALL.matcher(msg)).matches()) {
                double ms = num(m.group(2));
                add(new Event(no, up - ms / 1000, wallOf(up - ms / 1000), -1, Kind.STALL, "Allocation Stall",
                        m.group(1), -1, -1, -1, ms, -1, -1, -1, ""));
                return true;
            }
            if (plain && msg.startsWith("GC(") && (m = U_CONCURRENT.matcher(msg)).matches()) {
                double ms = num(m.group(6));
                String name = m.group(2);
                int p = name.indexOf(" (");
                add(new Event(no, up - ms / 1000, wallOf(up - ms / 1000), Integer.parseInt(m.group(1)), Kind.CONCURRENT,
                        p > 0 ? name.substring(0, p) : name, "", bytesOr(m.group(3)), bytesOr(m.group(4)),
                        bytesOr(m.group(5)), ms, -1, -1, -1, ""));
                return true;
            }
            if ((tags.equals("gc,cpu") || tagless) && (m = U_CPU.matcher(msg)).matches()) {
                Integer at = lastPauseOfId.get(Integer.parseInt(m.group(1)));
                if (at != null && at < events.size()) {
                    Event e = events.get(at);
                    events.set(at, new Event(e.line(), e.time(), e.wall(), e.id(), e.kind(), e.name(), e.cause(),
                            e.before(), e.after(), e.capacity(), e.ms(), num(m.group(2)), num(m.group(3)),
                            num(m.group(4)), e.trouble()));
                }
                return true;
            }
            if ((m = U_TROUBLE.matcher(msg)).matches()) {
                int id = Integer.parseInt(m.group(1));
                troubleOfId.put(id, m.group(2));
                Integer at = lastPauseOfId.get(id);
                if (at != null && at < events.size() && events.get(at).trouble().isEmpty()) {
                    Event e = events.get(at);
                    events.set(at, new Event(e.line(), e.time(), e.wall(), e.id(), e.kind(), e.name(), e.cause(),
                            e.before(), e.after(), e.capacity(), e.ms(), e.user(), e.sys(), e.real(), m.group(2)));
                }
                return true;
            }
            if (collector.isEmpty() && (m = U_USING.matcher(msg)).matches()) {
                collector = collectorName(m.group(1));
                return true;
            }
            if (jvm.isEmpty() && (m = U_VERSION.matcher(msg)).find()) {
                jvm = m.group(1);
                return true;
            }
            if ((m = U_MAX.matcher(msg)).matches() && (tags.startsWith("gc,init") || tagless)) {
                maxHeap = bytes(m.group(1));
                return true;
            }
            if (maxHeap < 0 && (m = U_MAX11.matcher(msg)).find()) {
                maxHeap = Long.parseLong(m.group(1)) << 20;
            }
            return true;
        }

        private boolean isZ() {
            return collector.equals("ZGC");
        }

        private void safepoint(long no, double up, String msg) {
            Matcher m = U_SAFEPOINT17.matcher(msg);
            if (m.find()) {
                double total = Long.parseLong(m.group(3)) / 1e6;
                stops.add(new Stop(no, up - total / 1000, Long.parseLong(m.group(2)) / 1e6, total, m.group(1)));
                return;
            }
            m = STOPPED.matcher(msg);
            if (m.find()) {
                double total = num(m.group(1)) * 1000;
                stops.add(new Stop(no, up - total / 1000, num(m.group(2)) * 1000, total, ""));
            }
        }

        private static boolean isLevel(String d) {
            return switch (d) {
                case "trace", "debug", "info", "warning", "error", "off" -> true;
                default -> false;
            };
        }

        private static Kind unifiedKind(String label) {
            String l = label.toLowerCase(Locale.ROOT);
            if (l.contains("full") || l.contains("degenerated")) {
                return Kind.FULL;
            }
            if (l.contains("mixed")) {
                return Kind.MIXED;
            }
            if (l.startsWith("pause young")) {
                return Kind.YOUNG;
            }
            if (l.contains("remark") || l.contains("final mark") || l.contains("mark end")) {
                return Kind.REMARK;
            }
            if (l.contains("cleanup")) {
                return Kind.CLEANUP;
            }
            if (l.contains("init mark") || l.contains("mark start") || l.contains("initial mark")) {
                return Kind.INITIAL_MARK;
            }
            return Kind.PAUSE;
        }

        // ---- Java 8

        private void old(long no, String s) {
            if (s.length() > 30 && Character.isDigit(s.charAt(0)) || s.contains(": [")) {
                s = withUptime(s);
            }
            if (s.contains("-start]")) {
                // "[CMS-concurrent-sweep-start]" can be printed into the middle of a collection's line
                s = O_PHASE_START.matcher(s).replaceAll("");
                if (s.isBlank()) {
                    return;
                }
            }
            Matcher m = STOPPED.matcher(s);
            if (m.find()) {
                Matcher st = O_STAMP.matcher(s);
                double up = st.lookingAt() ? num(st.group(2)) : Double.NaN;
                double total = num(m.group(1)) * 1000;
                stops.add(new Stop(no, up, num(m.group(2)) * 1000, total, ""));
                return;
            }
            // a concurrent phase may be printed in the middle of a collection's lines
            m = O_CMS_CONC.matcher(s);
            if (m.find()) {
                double up = stampBefore(s, m.start());
                double ms = num(m.group(3)) * 1000;
                add(new Event(no, up, wallOfOld(s, m.start(), up), -1, Kind.CONCURRENT, "Concurrent " + m.group(1), "",
                        -1, -1, -1, ms, -1, -1, -1, ""));
                int cut = m.end();
                Matcher times = O_TIMES.matcher(s);
                if (times.find(cut) && s.substring(cut, times.start()).isBlank()) {
                    cut = times.end(); // its own [Times: …], not the collection's
                }
                s = s.substring(0, stampStart(s, m.start())) + s.substring(cut);
                if (s.isBlank()) {
                    return;
                }
            }
            m = O_G1_CONC.matcher(s);
            if (m.find()) {
                double up = stampBefore(s, m.start());
                double ms = num(m.group(2)) * 1000;
                add(new Event(no, up - ms / 1000, wallOfOld(s, m.start(), up - ms / 1000), -1, Kind.CONCURRENT,
                        "Concurrent " + m.group(1), "", -1, -1, -1, ms, -1, -1, -1, ""));
                return;
            }
            m = OLD_EVENT.matcher(s);
            if (m.find() && m.group(2) != null && !m.group(3).endsWith("concurrent-")) {
                finishPending();
                pending = new StringBuilder(s.substring(m.start(3) - 1));
                pendingLine = no;
                pendingTime = num(m.group(2));
                pendingWall = m.group(1) != null ? wall(m.group(1)) : Long.MIN_VALUE;
                if (pendingWall != Long.MIN_VALUE && wallOffset == Long.MIN_VALUE) {
                    wallOffset = pendingWall - Math.round(pendingTime * 1000);
                }
                if (s.contains("[Times:")) {
                    finishPending();
                }
                return;
            }
            if (s.startsWith("CommandLine flags: ")) {
                flags = s.substring("CommandLine flags: ".length()).trim();
                Matcher mx = Pattern.compile("-XX:MaxHeapSize=(\\d+)").matcher(flags);
                if (mx.find()) {
                    maxHeap = Long.parseLong(mx.group(1));
                }
                collector = flags.contains("UseG1GC") ? "G1" : flags.contains("UseConcMarkSweepGC") ? "CMS"
                        : flags.contains("UseSerialGC") ? "Serial" : flags.contains("UseParallelGC")
                        || flags.contains("UseParallelOldGC") ? "Parallel" : collector;
                return;
            }
            if (jvm.isEmpty() && (s.startsWith("Java HotSpot(TM)") || s.startsWith("OpenJDK "))) {
                // "… VM (25.392-b08) for bsd-aarch64 JRE (…) (1.8.0_392-b08), built on …": the last is Java's
                Matcher v = Pattern.compile("\\((1\\.[0-9][^)]*|[0-9]+\\.[0-9]+\\.[0-9][^)]*)\\)").matcher(s);
                while (v.find()) {
                    jvm = v.group(1);
                }
                return;
            }
            if (pending != null) {
                pending.append('\n').append(s);
                if (s.contains("[Times:") || pending.length() > 64_000) {
                    finishPending();
                }
            }
        }

        /**
         * "-XX:+PrintGCDateStamps" without "-XX:+PrintGCTimeStamps": puts the seconds since the
         * first date after each date ("2026-…+0800: [GC" → "2026-…+0800: 0.000: [GC").
         */
        private String withUptime(String s) {
            Matcher d = O_DATE_ONLY.matcher(s);
            if (!d.find()) {
                return s;
            }
            StringBuilder out = new StringBuilder();
            int last = 0;
            do {
                long w = wall(d.group(1));
                if (w == Long.MIN_VALUE) {
                    continue;
                }
                if (wallOffset == Long.MIN_VALUE) {
                    wallOffset = w;
                }
                out.append(s, last, d.end()).append(String.format(Locale.ROOT, "%.3f: ", (w - wallOffset) / 1000.0));
                last = d.end();
            } while (d.find());
            return out.append(s, last, s.length()).toString();
        }

        private double stampBefore(String s, int at) {
            Matcher st = O_STAMP.matcher(s);
            double up = Double.NaN;
            while (st.find() && st.end() <= at) {
                up = num(st.group(2));
            }
            return up;
        }

        private int stampStart(String s, int at) {
            Matcher st = O_STAMP.matcher(s);
            int start = at;
            while (st.find() && st.end() <= at) {
                start = st.end() == at ? st.start() : start;
            }
            return start;
        }

        private long wallOfOld(String s, int at, double up) {
            return Double.isNaN(up) ? Long.MIN_VALUE : wallOf(up);
        }

        private void finishPending() {
            StringBuilder p = pending;
            pending = null;
            if (p == null) {
                return;
            }
            String text = p.toString();
            // "GC (Allocation Failure)", "Full GC (Ergonomics)", "GC pause (G1 Evacuation Pause) (young)"
            String head = text;
            Kind kind;
            String name;
            String cause;
            if (head.startsWith("[Full GC")) {
                kind = Kind.FULL;
                name = "Full GC";
                cause = firstParens(head.substring(8, Math.min(head.length(), 80)));
            } else if (head.startsWith("[GC pause")) {
                String rest = head.substring(9, Math.min(head.length(), 160));
                List<String> ps = parens(rest.split("\n", 2)[0]);
                cause = ps.isEmpty() ? "" : ps.get(0);
                boolean mixed = ps.contains("mixed");
                kind = mixed ? Kind.MIXED : Kind.YOUNG;
                name = mixed ? "Pause Mixed" : ps.contains("initial-mark") ? "Pause Young (Initial Mark)" : "Pause Young";
            } else if (head.startsWith("[GC remark")) {
                kind = Kind.REMARK;
                name = "Remark";
                cause = "";
            } else if (head.startsWith("[GC cleanup")) {
                kind = Kind.CLEANUP;
                name = "Cleanup";
                cause = "";
            } else {
                cause = firstParens(head.substring(3, Math.min(head.length(), 80)));
                if (cause.equals("CMS Initial Mark")) {
                    kind = Kind.INITIAL_MARK;
                    name = "Initial Mark";
                    cause = "";
                } else if (cause.equals("CMS Final Remark")) {
                    kind = Kind.REMARK;
                    name = "Final Remark";
                    cause = "";
                } else {
                    kind = Kind.YOUNG;
                    name = "Young GC";
                }
            }
            long before = -1;
            long after = -1;
            long cap = -1;
            Matcher h = O_G1HEAP.matcher(text);
            if (h.find()) {
                before = bytes(h.group(1));
                after = bytes(h.group(3));
                cap = bytes(h.group(4));
            } else {
                h = O_HEAP.matcher(text);
                while (h.find()) {
                    String lead = text.substring(Math.max(0, h.start() - 24), h.start()).stripTrailing();
                    if (lead.endsWith(":")) {
                        continue; // a generation's ("PSYoungGen: …") or Metaspace's
                    }
                    before = bytes(h.group(1));
                    after = bytes(h.group(2));
                    cap = bytes(h.group(3));
                    break;
                }
            }
            double ms = 0;
            int timesAt = text.indexOf("[Times:");
            Matcher secs = O_SECS.matcher(timesAt < 0 ? text : text.substring(0, timesAt));
            while (secs.find()) {
                ms = num(secs.group(1)) * 1000;
            }
            double user = -1;
            double sys = -1;
            double real = -1;
            Matcher t = O_TIMES.matcher(text);
            if (t.find()) {
                user = num(t.group(1));
                sys = num(t.group(2));
                real = num(t.group(3));
            }
            String lower = text.toLowerCase(Locale.ROOT);
            String trouble = lower.contains("to-space exhausted") || lower.contains("to-space overflow")
                    ? "to-space exhausted" : lower.contains("promotion failed") ? "promotion failed"
                    : lower.contains("concurrent mode failure") ? "concurrent mode failure"
                    : lower.contains("concurrent mode interrupted") ? "concurrent mode interrupted" : "";
            if (kind == Kind.YOUNG && (trouble.equals("promotion failed") || trouble.equals("concurrent mode failure"))) {
                kind = Kind.FULL; // the young collection failed and became a full one
                name = "Full GC";
            }
            add(new Event(pendingLine, pendingTime, pendingWall != Long.MIN_VALUE ? pendingWall : wallOf(pendingTime),
                    -1, kind, name, cause, before, after, cap, ms, user, sys, real, trouble));
        }

        void finish() {
            finishPending();
            if (collector.isEmpty()) {
                collector = guessCollector();
            }
        }

        private String guessCollector() {
            for (Event e : events) {
                String n = e.name();
                if (n.contains("Garbage Collection") || n.contains("Major Collection") || n.contains("Minor Collection")
                        || n.contains("Mark Start")) {
                    return "ZGC";
                }
                if (n.contains("Init Mark") || n.contains("Final Update Refs")) {
                    return "Shenandoah";
                }
                if (e.cause().startsWith("G1 ") || n.contains("Pause Mixed") || n.contains("Remark") && n.contains("Pause")) {
                    return "G1";
                }
                if (n.equals("Initial Mark") || n.equals("Final Remark")) {
                    return "CMS";
                }
            }
            return "";
        }
    }

    private void add(Event e) {
        events.add(e);
    }

    private static double parseOr(String s, double or) {
        try {
            return num(s);
        } catch (NumberFormatException e) {
            return or;
        }
    }

    private static long bytesOr(String s) {
        return s == null ? -1 : bytes(s);
    }

    static String collectorName(String using) {
        String u = using.toLowerCase(Locale.ROOT);
        if (u.contains("z garbage") || u.equals("zgc")) {
            return "ZGC";
        }
        if (u.contains("shenandoah")) {
            return "Shenandoah";
        }
        if (u.contains("g1")) {
            return "G1";
        }
        if (u.contains("parallel")) {
            return "Parallel";
        }
        if (u.contains("serial")) {
            return "Serial";
        }
        if (u.contains("concurrent mark sweep")) {
            return "CMS";
        }
        if (u.contains("epsilon")) {
            return "Epsilon";
        }
        return using;
    }
}
