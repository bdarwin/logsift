package logsift.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What is going on in a log, at a glance: error bursts, the errors that matter (and when new
 * ones began), silences, restarts, and noise. Works from what Logsift already has: the error
 * and warning lines, times sampled through the file, and the kinds of messages.
 */
public final class Insights {
    private Insights() {}

    public enum Severity { ERROR, WARN, INFO, OK }

    public enum Kind { BURST, ERROR_KIND, NEW_ERROR, KNOWN, EXCEPTION, UNFINISHED, SPIKE, WARNING_KIND, SILENCE, RESTART,
        NOISE, CALM }

    /**
     * One finding. {@code line}: where to go (or -1); {@code from}/{@code to}: a time range to
     * show (or {@link TimeFormat#NONE}); {@code template}: the kind of message (or null).
     */
    public record Finding(Kind kind, Severity severity, String title, String detail, long line,
                          long from, long to, MessageStats.Template template, KnownProblems.Signature known) {
        public Finding(Kind kind, Severity severity, String title, String detail, long line, long from, long to,
                       MessageStats.Template template) {
            this(kind, severity, title, detail, line, from, to, template, null);
        }
    }

    /** The whole picture: a one-line summary and the findings, most important first. */
    public record Report(String summary, long firstTime, long lastTime, long lines, long errors,
                         long warnings, List<Finding> findings) {
        public long problems() {
            return findings.stream().filter(f -> f.severity == Severity.ERROR).count();
        }
    }

    /**
     * What the findings are made from. Line arrays are sorted; times may be null.
     * {@code lastTime}: the time of the last line with one (samples skip the end), or NONE.
     * {@code lineTime}: reads one line's own time (NONE if it has none); null: use the samples.
     */
    public record Input(long lineCount, long[] sampleLines, long[] sampleTimes, int samples, long lastTime,
                        long[] errorLines, long errorCount, long[] warningLines, long warningCount,
                        MessageStats.Result messages, java.util.function.LongUnaryOperator lineTime) {}

    /** Most findings of one kind shown. */
    static final int MAX_PER_KIND = 3;

    /** The report, with the most frequent root causes of the log's exceptions (null: none looked for). */
    public static Report analyze(Input in, Exceptions.Result exceptions) {
        return analyze(in, exceptions, null);
    }

    /** The report with the exceptions' root causes, and what never ended or got slow (null: not looked for). */
    public static Report analyze(Input in, Exceptions.Result exceptions, Metrics metrics) {
        Report r = analyze(in);
        boolean noEx = exceptions == null || exceptions.groups().isEmpty();
        List<Finding> out = new ArrayList<>(r.findings());
        boolean timed = r.firstTime() != TimeFormat.NONE;
        WITH_DATE.set(timed && r.lastTime() - r.firstTime() > 20 * 3_600_000L);
        try {
            if (!noEx) {
                out.addAll(exceptionKinds(in, exceptions, timed));
            }
            out.addAll(known(in, exceptions, timed));
            if (metrics != null) {
                out.addAll(measurements(metrics, timed));
            }
        } finally {
            WITH_DATE.remove();
        }
        if (out.stream().anyMatch(f -> f.kind != Kind.CALM && (f.severity == Severity.ERROR || f.severity == Severity.WARN))) {
            out.removeIf(f -> f.kind == Kind.CALM);
        }
        out.sort(Comparator.comparingInt((Finding f) -> f.severity.ordinal()).thenComparingInt(f -> order(f.kind)));
        return new Report(r.summary(), r.firstTime(), r.lastTime(), r.lines(), r.errors(), r.warnings(), out);
    }

    /** Problems known to look like this, with what they mean and what to try. */
    static List<Finding> known(Input in, Exceptions.Result exceptions, boolean timed) {
        List<Finding> out = new ArrayList<>();
        for (KnownProblems.Match m : KnownProblems.find(in.messages == null ? null : in.messages.templates(),
                exceptions)) {
            KnownProblems.Signature s = m.signature();
            String when = timed ? time(exactTime(in, m.firstLine()))
                    + (m.count() > 1 ? " → " + time(exactTime(in, m.lastLine())) : "") : "";
            String why = s.why().length() > 110 ? s.why().substring(0, s.why().lastIndexOf(' ', 107)) + " …" : s.why();
            out.add(new Finding(Kind.KNOWN, s.error() ? Severity.ERROR : Severity.WARN,
                    s.title() + String.format(" (%,d×)", m.count()),
                    (when.isEmpty() ? "" : when + "   ·   ") + why,
                    m.firstLine(), TimeFormat.NONE, TimeFormat.NONE, null, s));
        }
        return out;
    }

    /** Things that started and never ended, and measurements that got far worse for a while. */
    static List<Finding> measurements(Metrics m, boolean timed) {
        List<Finding> out = new ArrayList<>();
        for (Spans.Kind k : m.spans().kinds()) {
            if (k.unfinishedCount() == 0 || out.size() >= 2) {
                continue;
            }
            Spans.Span first = k.unfinished().get(0);
            String what = k.name();
            out.add(new Finding(Kind.UNFINISHED, Severity.WARN,
                    String.format("%,d %s started and never ended", k.unfinishedCount(), what),
                    "First: " + (first.key() == null ? "" : first.key() + " ")
                            + (timed && first.start() != TimeFormat.NONE ? "at " + time(first.start()) : "")
                            + String.format("   ·   %,d ended, usually after %s", k.count(),
                                    Numbers.Unit.MILLIS.format(Math.max(0, k.median()))),
                    first.startLine(), TimeFormat.NONE, TimeFormat.NONE, null));
        }
        if (timed) {
            List<Numbers.Spike> spikes = new ArrayList<>();
            for (Numbers.Series s : m.numbers()) {
                // busy times are the timeline's business; a column that only goes up is no measurement
                if (s.source() == Numbers.Source.MESSAGE
                        || s.source() == Numbers.Source.COLUMN && !Numbers.mostlyRising(s)) {
                    spikes.addAll(Numbers.spikes(s));
                }
            }
            spikes.sort(Comparator.comparingDouble((Numbers.Spike x) -> x.median() / x.usual()).reversed());
            for (Numbers.Spike sp : spikes.subList(0, Math.min(2, spikes.size()))) {
                Numbers.Unit u = sp.series().unit();
                String name = sp.series().name();
                out.add(new Finding(Kind.SPIKE, Severity.WARN,
                        "High: " + (name.length() > 70 ? name.substring(0, 67) + "…" : name) + ", "
                                + time(sp.from()) + " – " + time(sp.to()),
                        "Median " + u.format(sp.median()) + " then, usually " + u.format(sp.usual()) + "   ·   max "
                                + u.format(sp.max()),
                        sp.line(), sp.from(), sp.to() + 1000, null));
            }
        }
        return out;
    }

    /** The exceptions that happen most, by root cause and where it was thrown. */
    static List<Finding> exceptionKinds(Input in, Exceptions.Result ex, boolean timed) {
        List<Finding> out = new ArrayList<>();
        for (Exceptions.Group g : ex.groups().subList(0, Math.min(2, ex.groups().size()))) {
            Exceptions.Thrown root = g.root();
            Exceptions.Frame w = root.where();
            String title = root.simpleType() + (w == null ? "" : " at " + w.shortText())
                    + String.format(" (%,d×)", g.count());
            StringBuilder d = new StringBuilder("Root cause");
            if (!root.message().isEmpty()) {
                String m = root.message().replace('\n', ' ');
                d.append(": ").append(m.length() > 80 ? m.substring(0, 77) + "…" : m);
            }
            if (!g.wrappers().isEmpty()) {
                d.append("   ·   wrapped in ").append(String.join(", ", g.wrappers()));
            }
            if (timed && g.count() > 1) {
                d.append("   ·   ").append(time(exactTime(in, g.firstLine()))).append(" → ")
                        .append(time(exactTime(in, g.lastLine())));
            }
            if (ex.groups().size() > 2 && out.isEmpty()) {
                d.append("   ·   ").append(ex.groups().size()).append(" kinds in all");
            }
            out.add(new Finding(Kind.EXCEPTION, Severity.ERROR, title, d.toString(), g.firstLine(),
                    TimeFormat.NONE, TimeFormat.NONE, null));
        }
        return out;
    }

    public static Report analyze(Input in) {
        boolean timed = in.samples >= 2 && in.sampleTimes != null;
        long first = timed ? min(in.sampleTimes, in.samples) : TimeFormat.NONE;
        long last = timed ? Math.max(max(in.sampleTimes, in.samples), in.lastTime) : TimeFormat.NONE;
        WITH_DATE.set(timed && last - first > 20 * 3_600_000L); // a log over days: times with dates
        try {
            return analyze(in, timed, first, last);
        } finally {
            WITH_DATE.remove();
        }
    }

    private static final ThreadLocal<Boolean> WITH_DATE = ThreadLocal.withInitial(() -> false);

    private static Report analyze(Input in, boolean timed, long first, long last) {
        List<Finding> out = new ArrayList<>();
        if (timed && last > first) {
            out.addAll(bursts(in, first, last));
        }
        out.addAll(errorKinds(in, timed));
        if (timed) {
            out.addAll(silences(in, first, last));
        }
        out.addAll(restarts(in, timed));
        out.addAll(noise(in));
        out.sort(Comparator.comparingInt((Finding f) -> f.severity.ordinal()).thenComparingInt(f -> order(f.kind)));
        if (out.stream().noneMatch(f -> f.severity == Severity.ERROR || f.severity == Severity.WARN)) {
            out.add(0, new Finding(Kind.CALM, Severity.OK, "Nothing unusual",
                    in.errorCount == 0 ? "No errors, error bursts, silences or restarts."
                            : "Errors are spread out evenly: no bursts, silences or restarts.",
                    -1, TimeFormat.NONE, TimeFormat.NONE, null));
        }
        return new Report(summary(in, first, last), first, last, in.lineCount, in.errorCount,
                in.warningCount, out);
    }

    private static int order(Kind k) {
        return switch (k) {
            case BURST -> 0;
            case NEW_ERROR -> 1;
            case KNOWN -> 1;
            case EXCEPTION -> 2;
            case SPIKE -> 3;
            case UNFINISHED -> 4;
            case RESTART -> 5;
            case SILENCE -> 6;
            case ERROR_KIND -> 7;
            case WARNING_KIND -> 8;
            case NOISE -> 9;
            case CALM -> 10;
        };
    }

    private static String summary(Input in, long first, long last) {
        StringBuilder sb = new StringBuilder();
        if (first != TimeFormat.NONE && last > first) {
            sb.append(time(first)).append(" → ").append(time(last)).append("  (").append(duration(last - first))
                    .append(")   ·   ");
        }
        sb.append(String.format("%,d lines", in.lineCount));
        sb.append(String.format("   ·   %,d error%s", in.errorCount, in.errorCount == 1 ? "" : "s"));
        if (in.errorCount > 0 && in.lineCount > 0) {
            sb.append(" (").append(percent(in.errorCount, in.lineCount)).append(")");
        }
        sb.append(String.format("   ·   %,d warning%s", in.warningCount, in.warningCount == 1 ? "" : "s"));
        return sb.toString();
    }

    // ---------------------------------------------------------------- bursts

    private static final long[] BUCKETS = {1_000, 5_000, 10_000, 30_000, 60_000, 300_000, 600_000, 1_800_000,
        3_600_000, 6 * 3_600_000L, 86_400_000};

    /** Times where errors come much faster than usual. */
    static List<Finding> bursts(Input in, long first, long last) {
        if (in.errorLines == null || in.errorLines.length < 10) {
            return List.of();
        }
        long size = BUCKETS[BUCKETS.length - 1];
        for (long b : BUCKETS) {
            if ((last - first) / b <= 120) {
                size = b;
                break;
            }
        }
        int n = (int) ((last - first) / size) + 1;
        if (n < 6) {
            return List.of(); // too short a log to tell a burst from the rest
        }
        int[] count = new int[n];
        for (long line : in.errorLines) {
            long t = timeOf(in, line);
            if (t != TimeFormat.NONE && t >= first && t <= last) {
                count[(int) ((t - first) / size)]++;
            }
        }
        double mean = (double) in.errorLines.length / n;
        double threshold = Math.max(5, 4 * mean);
        List<int[]> runs = new ArrayList<>(); // {first bucket, last bucket, errors}
        for (int i = 0; i < n; i++) {
            if (count[i] >= threshold) {
                int[] prev = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                if (prev != null && i - prev[1] <= 2) { // one quiet bucket between: the same burst
                    for (int k = prev[1] + 1; k <= i; k++) {
                        prev[2] += count[k];
                    }
                    prev[1] = i;
                } else {
                    runs.add(new int[] {i, i, count[i]});
                }
            }
        }
        long inBursts = runs.stream().mapToLong(r -> r[2]).sum();
        int burstBuckets = runs.stream().mapToInt(r -> r[1] - r[0] + 1).sum();
        if (inBursts > 0.9 * in.errorLines.length && runs.size() == 1 && burstBuckets > n / 2) {
            return List.of(); // errors all along, not a burst
        }
        double usual = Math.max((double) (in.errorLines.length - inBursts) / Math.max(1, n - burstBuckets),
                1.0 / Math.max(1, n));
        runs.sort((a, b) -> Integer.compare(b[2], a[2]));
        List<Finding> out = new ArrayList<>();
        for (int[] r : runs.subList(0, Math.min(MAX_PER_KIND, runs.size()))) {
            long bucketFrom = first + r[0] * size;
            long bucketTo = Math.min(last, first + (r[1] + 1) * size);
            double rate = r[2] / (double) (r[1] - r[0] + 1);
            long firstError = firstErrorAtOrAfter(in, bucketFrom);
            long lastError = lastErrorBefore(in, bucketTo);
            long from = exactTime(in, firstError);
            long to = Math.max(from, exactTime(in, lastError));
            String times = rate / usual >= 1.95 ? Math.round(rate / usual) + "× the usual rate" : "";
            out.add(new Finding(Kind.BURST, Severity.ERROR,
                    String.format("Error burst %s – %s: %,d errors", time(from), time(to),
                            firstError < 0 || lastError < firstError ? r[2]
                                    : Arrays.binarySearch(in.errorLines, lastError)
                                            - Arrays.binarySearch(in.errorLines, firstError) + 1),
                    (times.isEmpty() ? "" : times + "   ·   ") + "over " + duration(Math.max(1000, to - from)),
                    firstError, from, to + 1000, null));
        }
        return out;
    }

    /** The last error line written before {@code t} (exact times near the edge). */
    private static long lastErrorBefore(Input in, long t) {
        long[] e = in.errorLines;
        int i = e.length - 1;
        while (i >= 0 && timeOf(in, e[i]) >= t) {
            i--;
        }
        // samples are a little early: errors just after this one may still be before t
        while (i + 1 < e.length && exactTime(in, e[i + 1]) < t) {
            i++;
        }
        while (i >= 0 && exactTime(in, e[i]) >= t) {
            i--;
        }
        return i >= 0 ? e[i] : (e.length > 0 ? e[0] : -1);
    }

    /** The first error line written at or after {@code t}. */
    private static long firstErrorAtOrAfter(Input in, long t) {
        long[] e = in.errorLines;
        int i = 0;
        while (i < e.length && timeOf(in, e[i]) < t) {
            i++;
        }
        // samples are a little early: errors just before this one may already be at t
        while (i > 0 && exactTime(in, e[i - 1]) >= t) {
            i--;
        }
        while (i < e.length && exactTime(in, e[i]) < t) {
            i++;
        }
        return i < e.length ? e[i] : (e.length > 0 ? e[e.length - 1] : -1);
    }

    // ---------------------------------------------------------------- kinds of errors

    /** The most frequent errors, and errors that only began partway through the log. */
    static List<Finding> errorKinds(Input in, boolean timed) {
        if (in.messages == null) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        List<MessageStats.Template> errors = new ArrayList<>();
        List<MessageStats.Template> warnings = new ArrayList<>();
        for (MessageStats.Template t : in.messages.templates()) {
            if (isIn(in.errorLines, t.firstLine()) || Levels.ERROR.equals(Levels.normalize(t.level()))) {
                errors.add(t);
            } else if (isIn(in.warningLines, t.firstLine()) || Levels.WARN.equals(Levels.normalize(t.level()))) {
                warnings.add(t);
            }
        }
        errors.sort(Comparator.comparingLong(MessageStats.Template::count).reversed());
        warnings.sort(Comparator.comparingLong(MessageStats.Template::count).reversed());
        long late = Math.max(1, in.lineCount / 10);
        int shown = 0;
        for (MessageStats.Template t : errors) {
            if (shown >= 4) {
                break;
            }
            boolean isNew = t.firstLine() >= late && t.count() >= 2;
            boolean stopped = t.lastLine() < in.lineCount - Math.max(1, in.lineCount / 10);
            long ft = timed ? exactTime(in, t.firstLine()) : TimeFormat.NONE;
            long lt = timed ? exactTime(in, t.lastLine()) : TimeFormat.NONE;
            String when = timed ? time(ft) : String.format("line %,d", t.firstLine() + 1);
            String times = String.format("%,d time%s", t.count(), t.count() == 1 ? "" : "s");
            String detail;
            if (isNew) {
                detail = "New: began at " + when + (stopped && timed ? ", stopped at " + time(lt) : "")
                        + "   ·   " + times;
            } else {
                detail = times + (timed && t.count() > 1 ? "   ·   " + time(ft) + " → " + time(lt) : "")
                        + (in.errorCount > 0 && t.count() * 4 >= in.errorCount && errors.size() > 1
                                ? "   ·   " + percent(t.count(), in.errorCount) + " of all errors" : "");
            }
            out.add(new Finding(isNew ? Kind.NEW_ERROR : Kind.ERROR_KIND, Severity.ERROR,
                    display(t.text()), detail, t.firstLine(), TimeFormat.NONE, TimeFormat.NONE, t));
            shown++;
        }
        if (!warnings.isEmpty()) {
            MessageStats.Template t = warnings.get(0);
            out.add(new Finding(Kind.WARNING_KIND, Severity.WARN, display(t.text()),
                    String.format("Most common warning: %,d time%s", t.count(), t.count() == 1 ? "" : "s"),
                    t.firstLine(), TimeFormat.NONE, TimeFormat.NONE, t));
        }
        return out;
    }

    // ---------------------------------------------------------------- silences

    /** Long stretches without a line: the program hung, stopped, or wasn't running. */
    static List<Finding> silences(Input in, long first, long last) {
        int n = in.samples;
        long[] gaps = new long[Math.max(0, n - 1)];
        for (int i = 0; i + 1 < n; i++) {
            gaps[i] = Math.max(0, in.sampleTimes[i + 1] - in.sampleTimes[i]);
        }
        if (gaps.length < 4) {
            return List.of();
        }
        long[] sorted = gaps.clone();
        Arrays.sort(sorted);
        long median = sorted[sorted.length / 2];
        long threshold = Math.max(60_000, 8 * median);
        List<long[]> found = new ArrayList<>(); // {index, gap}
        for (int i = 0; i < gaps.length; i++) {
            if (gaps[i] >= threshold) {
                found.add(new long[] {i, gaps[i]});
            }
        }
        found.sort((a, b) -> Long.compare(b[1], a[1]));
        List<Finding> out = new ArrayList<>();
        for (long[] g : found.subList(0, Math.min(MAX_PER_KIND, found.size()))) {
            int i = (int) g[0];
            long from = in.sampleTimes[i];
            long to = in.sampleTimes[i + 1];
            long after = in.sampleLines[i + 1];
            if (in.lineTime != null) { // the exact quiet stretch, between two lines near these samples
                long prev = TimeFormat.NONE;
                long bestGap = -1;
                for (long line = in.sampleLines[i]; line <= in.sampleLines[i + 1]; line++) {
                    long t = in.lineTime.applyAsLong(line);
                    if (t == TimeFormat.NONE) {
                        continue;
                    }
                    if (prev != TimeFormat.NONE && t - prev > bestGap) {
                        bestGap = t - prev;
                        from = prev;
                        to = t;
                        after = line;
                    }
                    prev = t;
                }
            }
            out.add(new Finding(Kind.SILENCE, Severity.WARN,
                    "No lines for " + duration(to - from),
                    time(from) + " → " + time(to) + "   ·   the program was quiet, stuck or not running",
                    after, TimeFormat.NONE, TimeFormat.NONE, null));
        }
        return out;
    }

    // ---------------------------------------------------------------- restarts

    private static final Pattern STARTUP = Pattern.compile("(?i)"
            + "\\bStarted \\S+ in \\S+ seconds"                         // Spring Boot
            + "|\\bStarting \\S+ (?:v\\S+ )?using Java\\b"               // Spring Boot
            + "|\\bServer startup in\\b|\\bTomcat started on port"        // Tomcat
            + "|\\bJetty\\S* started\\b|\\bServer started\\b|\\bapplication started\\b"
            + "|\\bstarting up\\b|\\bapp(?:lication)? (?:is )?starting\\b"
            + "|\\blistening on (?:port|\\S+:)\\S*"
            + "|\\bJVM arguments\\b|\\bstartup complete\\b");

    /** The program started (more than once: it restarted, maybe after a crash). */
    static List<Finding> restarts(Input in, boolean timed) {
        if (in.messages == null) {
            return List.of();
        }
        MessageStats.Template best = null;
        for (MessageStats.Template t : in.messages.templates()) {
            if (STARTUP.matcher(t.text()).find() && (best == null || t.count() > best.count())) {
                best = t;
            }
        }
        if (best == null || best.count() < 2) {
            return List.of();
        }
        String last = timed ? time(exactTime(in, best.lastLine())) : String.format("line %,d", best.lastLine() + 1);
        return List.of(new Finding(Kind.RESTART, Severity.WARN,
                String.format("Started %,d times", best.count()),
                "Last start at " + last + "   ·   " + display(best.text()),
                best.lastLine(), TimeFormat.NONE, TimeFormat.NONE, best));
    }

    // ---------------------------------------------------------------- noise

    /** One message that fills a large part of the log (hide it to see the rest). */
    static List<Finding> noise(Input in) {
        if (in.messages == null || in.messages.templates().isEmpty() || in.messages.lines() < 1000) {
            return List.of();
        }
        MessageStats.Template top = in.messages.templates().get(0);
        for (MessageStats.Template t : in.messages.templates()) {
            if (t.count() > top.count()) {
                top = t;
            }
        }
        if (top.count() * 4 < in.messages.lines()) {
            return List.of();
        }
        return List.of(new Finding(Kind.NOISE, Severity.INFO,
                "One message is " + percent(top.count(), in.messages.lines()) + " of the log",
                display(top.text()) + "   ·   hide it to see the rest",
                top.firstLine(), TimeFormat.NONE, TimeFormat.NONE, top));
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isIn(long[] sorted, long line) {
        return sorted != null && Arrays.binarySearch(sorted, line) >= 0;
    }

    /** The time written on a line (or the nearest sample's when it has none). */
    static long exactTime(Input in, long line) {
        if (in.lineTime != null && line >= 0) {
            long t = in.lineTime.applyAsLong(line);
            if (t != TimeFormat.NONE) {
                return t;
            }
        }
        return timeOf(in, line);
    }

    /** Approximate time of a line, from the sample at or before it. */
    static long timeOf(Input in, long line) {
        if (in.samples == 0 || in.sampleTimes == null) {
            return TimeFormat.NONE;
        }
        int i = Arrays.binarySearch(in.sampleLines, 0, in.samples, line);
        i = i >= 0 ? i : -i - 2;
        return in.sampleTimes[Math.max(0, i)];
    }

    private static final Pattern PREFIX = Pattern.compile("^\\[?(?:" + LogFormat.LEVEL + "|WARNING|ERR|E|W|I)\\]?\\s+"
            + "(?:<\\*>\\s+)?(?:---\\s+)?(?:\\[[^\\]]{0,60}\\]\\s+)?(?:[\\w$.<>*-]{2,120}(?:\\s*\\(\\S*\\))?\\s*)?(?:[-:|]\\s+)");

    /**
     * A message kind as people read it: without the time, level, thread and logger in front,
     * … for the parts that vary.
     */
    public static String display(String template) {
        String s = template.strip();
        while (s.startsWith(MessageStats.WILDCARD)) {
            s = s.substring(MessageStats.WILDCARD.length()).stripLeading();
            s = s.replaceFirst("^[,.:T\\-]\\s*", "");
        }
        // "ERROR [main] com.acme.App - Payment declined" → "Payment declined" (the level is shown apart)
        java.util.regex.Matcher m = PREFIX.matcher(s);
        if (m.find() && m.end() < s.length()) {
            s = s.substring(m.end());
        }
        s = s.replace(MessageStats.WILDCARD, "…");
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }

    private static String percent(long part, long whole) {
        double p = 100.0 * part / whole;
        return p >= 10 ? Math.round(p) + "%" : p >= 1 ? String.format(Locale.ROOT, "%.1f%%", p)
                : String.format(Locale.ROOT, "%.2f%%", p);
    }

    /** "10:42:07", with the date in front when the log spans days. Times are wall-clock UTC. */
    static String time(long t) {
        if (t == TimeFormat.NONE) {
            return "?";
        }
        java.time.LocalDateTime d = java.time.LocalDateTime.ofEpochSecond(Math.floorDiv(t, 1000), 0,
                java.time.ZoneOffset.UTC);
        String hms = String.format("%02d:%02d:%02d", d.getHour(), d.getMinute(), d.getSecond());
        return WITH_DATE.get() ? d.toLocalDate() + " " + hms : hms;
    }

    /** "4 min 12 s", "1 h 58 min", "3 days 2 h". */
    public static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + " s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " min" + (s % 60 != 0 && m < 10 ? " " + s % 60 + " s" : "");
        }
        long h = m / 60;
        if (h < 48) {
            return h + " h" + (m % 60 != 0 ? " " + m % 60 + " min" : "");
        }
        return h / 24 + " days" + (h % 24 != 0 ? " " + h % 24 + " h" : "");
    }

    private static long min(long[] a, int n) {
        long m = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (a[i] != TimeFormat.NONE) {
                m = Math.min(m, a[i]);
            }
        }
        return m == Long.MAX_VALUE ? TimeFormat.NONE : m;
    }

    private static long max(long[] a, int n) {
        long m = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (a[i] != TimeFormat.NONE) {
                m = Math.max(m, a[i]);
            }
        }
        return m == Long.MIN_VALUE ? TimeFormat.NONE : m;
    }
}
