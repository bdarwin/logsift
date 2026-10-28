package logsift.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.LongUnaryOperator;

/**
 * A log's analyses without any window: its format, messages, exceptions, durations and
 * numbers, and the Insights made from them.
 *
 * <p>The static methods are the steps the window uses too, so both give the same answers.
 * An instance opens a file on its own (for tools and agents): {@link #open}, then ask.
 */
public final class LogAnalysis implements AutoCloseable {

    // ---------------------------------------------------------------- the steps

    /** Lines read to work out the format. */
    static final int SAMPLE_LINES = 2000;

    /**
     * Works out how entries start, the columns and the timestamps from the first lines.
     *
     * @param chosen a format chosen by hand (its name), {@link Formats#PLAIN}, or null: detect
     */
    public static Formats.Detected detect(LogDocument doc, List<CustomFormat> custom, String fileName,
                                          String chosen) {
        return detect(doc, doc.lineCount(), custom, fileName, chosen);
    }

    /** {@link #detect}, from the first of {@code lines} (the lines known when it was asked for). */
    public static Formats.Detected detect(LogDocument doc, long lines, List<CustomFormat> custom, String fileName,
                                          String chosen) {
        String[] sample = new String[(int) Math.min(lines, SAMPLE_LINES)];
        doc.readLines(0, sample.length, sample);
        int year = java.time.Year.now().getValue();
        try {
            year = java.time.Instant.ofEpochMilli(Files.getLastModifiedTime(doc.path()).toMillis())
                    .atZone(java.time.ZoneId.systemDefault()).getYear();
        } catch (IOException e) {
            // keep this year
        }
        return Formats.detect(Arrays.asList(sample), custom, fileName, chosen, year);
    }

    /**
     * Sets {@code e} to find lines of these levels: with a format, by its level column (so "E" or
     * "error" count too); otherwise by the usual words (whole word, exact case).
     */
    public static void configureLevelSearch(FilterEngine e, List<String> levels, LogFormat format) {
        List<FilterEngine.Spec> specs = new ArrayList<>();
        List<Character> ids = new ArrayList<>();
        String regex = Levels.lineRegex(format, levels);
        if (regex != null) {
            specs.add(new FilterEngine.Spec('A', new SearchQuery(regex, true, true, false)));
            ids.add('A');
        } else {
            for (String level : levels) {
                for (String w : Levels.words(level)) {
                    char id = (char) ('A' + ids.size());
                    specs.add(new FilterEngine.Spec(id, new SearchQuery(w, true, false, true)));
                    ids.add(id);
                }
            }
        }
        e.configure(specs, Formula.auto(ids, List.of()));
    }

    /** The lines that match (sorted), at most five million. */
    public static long[] matchingLines(FilterResult r) {
        long n = Math.min(r.count(), 5_000_000);
        long[] out = new long[(int) n];
        int k = 0;
        for (long row = 0; row < n; row++) {
            long line = r.lineAt(row);
            if (r.isMatch(line)) {
                out[k++] = line;
            }
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    /** Finds and groups the log's exceptions (null if stopped). */
    public static Exceptions.Result exceptions(LogDocument doc, EntryFormat ef, BooleanSupplier stop,
                                               DoubleConsumer progress) {
        List<SearchSession> sessions = new ArrayList<>();
        try {
            for (String re : Exceptions.STACK_SEARCHES) {
                sessions.add(new SearchSession(doc, new SearchQuery(re, true, true, false)).start());
            }
            for (SearchSession s : sessions) {
                while (!s.awaitIdle(200)) {
                    if (stop.getAsBoolean()) {
                        return null;
                    }
                }
            }
            int total = 0;
            for (SearchSession s : sessions) {
                total += s.hits().size();
            }
            long[] lines = new long[Math.min(total, 20_000_000)];
            int k = 0;
            for (SearchSession s : sessions) {
                HitList h = s.hits();
                for (int i = 0; i < h.size() && k < lines.length; i++) {
                    lines[k++] = h.get(i);
                }
            }
            Arrays.sort(lines, 0, k);
            return Exceptions.analyze(lines, k, doc::line, ef == null ? null : ef::startsEntry, stop, progress);
        } finally {
            sessions.forEach(SearchSession::close);
        }
    }

    /** The first lines of entries of a message kind (null if stopped). */
    public static long[] templateLines(LogDocument doc, EntryFormat ef, MessageStats.Template t,
                                       BooleanSupplier stop) {
        SearchSession s = new SearchSession(doc, new SearchQuery(t.regex(), true, true, false)).start();
        try {
            while (!s.awaitIdle(200)) {
                if (stop.getAsBoolean()) {
                    return null;
                }
            }
            HitList h = s.hits();
            long[] out = new long[h.size()];
            int k = 0;
            for (int i = 0; i < out.length; i++) {
                long line = h.get(i);
                if (ef == null || i > 50_000 || ef.startsEntry(doc.line(line) == null ? "" : doc.line(line))) {
                    out[k++] = line; // a stack trace line that happens to look the same is not one
                }
            }
            return k == out.length ? out : Arrays.copyOf(out, k);
        } finally {
            s.close();
        }
    }

    /**
     * Durations of things that start and end, numbers in messages, and the log's columns of
     * numbers and of a few values (null if stopped). {@code ef} is asked for at each message
     * kind, as it may change meanwhile.
     */
    public static Metrics metrics(LogDocument doc, java.util.function.Supplier<EntryFormat> ef, TimeFormat tf,
                                  LogFormat lf, MessageStats.Result m, BooleanSupplier stop) {
        Metrics me = Metrics.analyze(m.templates(), (t, c) -> templateLines(doc, ef.get(), t, stop),
                RequestTrace.reader(doc::line, tf, lf), stop);
        if (me == null || stop.getAsBoolean()) {
            return null;
        }
        java.util.List<Numbers.Series> cols = ColumnMetrics.analyze(lf, doc.lineCount(), doc::line, tf, stop);
        return stop.getAsBoolean() ? null : me.withColumns(cols);
    }

    /** The time of the last line that has one (near the end), or {@link TimeFormat#NONE}. */
    public static long lastTime(LogDocument doc, TimeFormat tf, long lineCount) {
        long lastTime = TimeFormat.NONE;
        if (tf != null) {
            for (long l = lineCount - 1; l >= 0 && l > lineCount - 200 && lastTime == TimeFormat.NONE; l--) {
                String text = doc.line(l);
                lastTime = text == null ? TimeFormat.NONE : tf.parse(text);
            }
        }
        return lastTime;
    }

    /** What Insights are made from. {@code ti} and {@code tf} may be null (no timestamps). */
    public static Insights.Input insightsInput(LogDocument doc, long lineCount, TimeFormat tf, TimeIndex ti,
                                               FilterResult errors, FilterResult warnings,
                                               MessageStats.Result messages) {
        TimeIndex.Samples s = ti == null || tf == null ? null : ti.samples();
        long lastTime = lastTime(doc, tf, lineCount);
        LongUnaryOperator lineTime = tf == null ? null : line -> {
            String text = doc.line(line);
            return text == null ? TimeFormat.NONE : tf.parse(text);
        };
        return new Insights.Input(lineCount,
                s == null ? new long[0] : s.lines(), s == null ? null : s.times(), s == null ? 0 : s.n(),
                lastTime, matchingLines(errors), errors.matchCount(), matchingLines(warnings), warnings.matchCount(),
                messages, lineTime);
    }

    // ---------------------------------------------------------------- one file, on its own

    /** Logs bigger than this get no durations and numbers in their Insights (as in the window). */
    public static final long AUTO_METRICS_BYTES = 512L * 1024 * 1024;

    private final LogDocument doc;
    private final Path path;
    private final List<CustomFormat> custom;
    private FilterEngine errorLines;
    private FilterEngine warningLines;
    private TimeIndex timeIndex;
    private Formats.Detected formats;
    private MessageStats.Result messages;
    private Exceptions.Result exceptions;
    private Metrics metrics;
    private boolean metricsDone;
    private Insights.Report insights;

    private LogAnalysis(LogDocument doc, Path path, List<CustomFormat> custom) {
        this.doc = doc;
        this.path = path;
        this.custom = List.copyOf(custom);
    }

    /**
     * Opens a log and starts reading it. Its encoding is found as the window does
     * (UTF-8 if unsure). UTF-16 logs are not read this way yet.
     */
    public static LogAnalysis open(Path path, List<CustomFormat> custom) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a file (or not reachable): " + path);
        }
        Charset cs = Encoding.detect(path, java.nio.charset.StandardCharsets.UTF_8);
        if (Encoding.isUtf16(cs)) {
            throw new IOException("UTF-16 logs can only be opened in the Logsift window for now: " + path);
        }
        return new LogAnalysis(LogDocument.open(path, LineIndex.DEFAULT_CHUNK_SIZE, cs), path, custom);
    }

    public LogDocument document() {
        return doc;
    }

    public Path path() {
        return path;
    }

    /** Waits until the file is read and its format, levels and times are known (false: stopped). */
    public synchronized boolean awaitReady(BooleanSupplier stop) throws InterruptedException {
        while (doc.isIndexing()) {
            if (stop.getAsBoolean()) {
                return false;
            }
            Thread.sleep(50);
        }
        if (formats == null) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            formats = detect(doc, custom, name, null);
            errorLines = new FilterEngine(doc);
            warningLines = new FilterEngine(doc);
            configureLevelSearch(errorLines, List.of(Levels.ERROR), formats.columns());
            configureLevelSearch(warningLines, List.of(Levels.WARN), formats.columns());
            if (formats.times() != null) {
                timeIndex = new TimeIndex(doc, formats.times()).start();
            }
        }
        while (!errorLines.awaitIdle(100) || !warningLines.awaitIdle(100)
                || (timeIndex != null && !timeIndex.awaitIdle(100))) {
            if (stop.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    /** The format found (after {@link #awaitReady}). */
    public synchronized Formats.Detected formats() {
        return formats;
    }

    public synchronized TimeIndex timeIndex() {
        return timeIndex;
    }

    public synchronized FilterResult errorLines() {
        return errorLines == null ? null : errorLines.result();
    }

    public synchronized FilterResult warningLines() {
        return warningLines == null ? null : warningLines.result();
    }

    /** The log's messages grouped by kind (null if stopped). */
    public synchronized MessageStats.Result messages(BooleanSupplier stop, DoubleConsumer progress)
            throws InterruptedException {
        if (messages == null && awaitReady(stop)) {
            MessageStats.Result m = MessageStats.analyze(doc, formats.entries(), formats.columns(), 0,
                    Long.MAX_VALUE, null, stop, progress);
            if (!stop.getAsBoolean()) {
                messages = m;
            }
        }
        return messages;
    }

    /** The log's exceptions grouped by root cause (null if stopped). */
    public synchronized Exceptions.Result exceptions(BooleanSupplier stop, DoubleConsumer progress)
            throws InterruptedException {
        if (exceptions == null && awaitReady(stop)) {
            exceptions = exceptions(doc, formats.entries(), stop, progress);
        }
        return exceptions;
    }

    /** Durations and numbers (null if stopped). */
    public synchronized Metrics metrics(BooleanSupplier stop) throws InterruptedException {
        if (!metricsDone) {
            MessageStats.Result m = messages(stop, null);
            if (m == null) {
                return null;
            }
            Formats.Detected f = formats;
            Metrics me = metrics(doc, f::entries, f.times(), f.columns(), m, stop);
            if (me != null) {
                metrics = me;
                metricsDone = true;
            }
        }
        return metrics;
    }

    /** The Insights report, made as the window makes it (null if stopped). */
    public synchronized Insights.Report insights(BooleanSupplier stop) throws InterruptedException {
        if (insights != null) {
            return insights;
        }
        if (!awaitReady(stop)) {
            return null;
        }
        long n = doc.lineCount();
        MessageStats.Result m = messages(stop, null);
        Exceptions.Result ex = m == null ? null : exceptions(stop, null);
        if (ex == null) {
            return null;
        }
        Metrics me = null;
        if (doc.fileSize() <= AUTO_METRICS_BYTES) {
            me = metrics(stop);
            if (me == null) {
                return null;
            }
        }
        Insights.Input in = insightsInput(doc, n, formats.times(), timeIndex, errorLines.result(),
                warningLines.result(), m);
        insights = Insights.analyze(in, ex, me);
        return insights;
    }

    @Override
    public synchronized void close() {
        if (timeIndex != null) {
            timeIndex.close();
        }
        if (errorLines != null) {
            errorLines.close();
        }
        if (warningLines != null) {
            warningLines.close();
        }
        doc.close();
    }
}
