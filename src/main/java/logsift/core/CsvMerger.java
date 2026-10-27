package logsift.core;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Merges CSV (and TSV) files into one CSV: one header with every file's columns (matched by
 * name, ignoring case and spaces; the first file's order, then new ones), a {@code source}
 * column with each row's file, commas as separators, and fields quoted where they need it.
 * When every file has times the rows go in time order (streaming, like {@link LogMerger});
 * otherwise one file after the other. Afterwards {@link #follow} keeps appending new rows.
 */
public final class CsvMerger implements AutoCloseable {

    /** How the files will be merged. */
    public enum Order { BY_TIME, ONE_AFTER_THE_OTHER }

    /**
     * What a merge will do. {@code columns}: the merged header (without the source column);
     * {@code untimed}: files without times (when not empty, the files go one after the other).
     */
    public record Plan(List<String> columns, String sourceColumn, Order order, List<String> untimed) {}

    private final List<Reader> readers = new ArrayList<>();
    private final Path out;
    private final Plan plan;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(LogDocument.daemon("logsift-merge"));
    private volatile boolean closed;
    private long rowsWritten;

    private CsvMerger(Path out, Plan plan) {
        this.out = out;
        this.plan = plan;
    }

    /** True when every file is a CSV (or TSV) file Logsift reads as columns. */
    public static boolean allCsv(List<Path> files) throws IOException {
        for (Path p : files) {
            if (detect(p).columns() == null || !detect(p).columns().isCsv()) {
                return false;
            }
        }
        return !files.isEmpty();
    }

    /** True when some files are CSV and some aren't (they are then merged as logs). */
    public static boolean someCsv(List<Path> files) throws IOException {
        for (Path p : files) {
            Formats.Detected d = detect(p);
            if (d.columns() != null && d.columns().isCsv()) {
                return true;
            }
        }
        return false;
    }

    private static Formats.Detected detect(Path p) throws IOException {
        List<String> sample = sample(p);
        int year = java.time.Year.now().getValue();
        Formats.Detected d = Formats.detect(sample, List.of(), p.getFileName() == null ? "" : p.getFileName().toString(),
                null, year);
        if (d.times() == null && d.columns() != null && d.columns().isCsv() && sample.size() > 1) {
            // a file of a row or two: too few lines to be sure of its times, so its rows count thrice
            List<String> more = new ArrayList<>(sample);
            while (more.size() < 4) {
                more.addAll(sample.subList(1, sample.size()));
            }
            TimeFormat t = TimeFormat.inColumn(d.columns(), more, year);
            return new Formats.Detected(d.entries(), d.columns(), t);
        }
        return d;
    }

    /** The merged header and the order, from the files' headers (they must all be CSV). */
    public static Plan plan(List<LogMerger.Source> sources) throws IOException {
        Map<String, String> union = new LinkedHashMap<>(); // key -> name as first written
        List<String> untimed = new ArrayList<>();
        for (LogMerger.Source s : sources) {
            Formats.Detected d = detect(s.path());
            if (d.columns() == null || !d.columns().isCsv()) {
                throw new IOException(s.path().getFileName() + " is not a CSV file");
            }
            for (String n : d.columns().headerNames()) {
                union.putIfAbsent(key(n), n);
            }
            if (d.times() == null) {
                untimed.add(s.path().getFileName().toString());
            }
        }
        String source = "source";
        for (int k = 2; union.containsKey(key(source)); k++) {
            source = "source " + k;
        }
        return new Plan(List.copyOf(union.values()), source,
                untimed.isEmpty() ? Order.BY_TIME : Order.ONE_AFTER_THE_OTHER, List.copyOf(untimed));
    }

    /** How column names are matched: case and spaces around don't count. */
    static String key(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        return n.startsWith("﻿") ? n.substring(1) : n;
    }

    /** Merges the files into {@code out} (replacing it). */
    public static CsvMerger merge(List<LogMerger.Source> sources, Path out, DoubleConsumer progress,
                                  BooleanSupplier cancelled) throws IOException {
        Plan plan = plan(sources);
        CsvMerger m = new CsvMerger(out, plan);
        long total = 0;
        try {
            for (LogMerger.Source s : sources) {
                m.readers.add(new Reader(s, plan));
                total += Files.size(s.path());
            }
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
                StringBuilder header = new StringBuilder(quote(plan.sourceColumn()));
                for (String c : plan.columns()) {
                    header.append(',').append(quote(c));
                }
                os.write(header.append('\n').toString().getBytes(StandardCharsets.UTF_8));
                m.mergeAvailable(os, total, progress, cancelled);
            }
        } catch (IOException | RuntimeException e) {
            m.close();
            throw e;
        }
        return m;
    }

    public Path output() {
        return out;
    }

    public Plan plan() {
        return plan;
    }

    public long rowsWritten() {
        return rowsWritten;
    }

    /** Keeps appending the rows the files get, checking every {@code millis} ms. */
    public void follow(long millis) {
        timer.scheduleWithFixedDelay(() -> {
            if (closed) {
                return;
            }
            try (OutputStream os = new BufferedOutputStream(
                    Files.newOutputStream(out, StandardOpenOption.APPEND), 1 << 16)) {
                mergeAvailable(os, 0, null, () -> closed);
            } catch (IOException | RuntimeException e) {
                // a source may be rotating right now; try again next time
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    private record Pending(long time, int source, long seq, byte[] row) {}

    /** Writes every complete row the files have now. */
    private synchronized void mergeAvailable(OutputStream os, long totalBytes, DoubleConsumer progress,
                                             BooleanSupplier cancelled) throws IOException {
        long seq = 0;
        long lastReport = 0;
        if (plan.order() == Order.ONE_AFTER_THE_OTHER) {
            for (Reader r : readers) {
                byte[] row;
                while ((row = r.nextRow()) != null) {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    os.write(row);
                    rowsWritten++;
                    if (progress != null && totalBytes > 0 && (++seq & 4095) == 0) {
                        lastReport = report(progress, totalBytes, lastReport);
                    }
                }
            }
            os.flush();
            return;
        }
        PriorityQueue<Pending> queue = new PriorityQueue<>((a, b) -> {
            int c = Long.compare(a.time, b.time);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.source, b.source);
            return c != 0 ? c : Long.compare(a.seq, b.seq);
        });
        for (int i = 0; i < readers.size(); i++) {
            Reader r = readers.get(i);
            byte[] row = r.nextRow();
            if (row != null) {
                queue.add(new Pending(r.rowTime, i, seq++, row));
            }
        }
        while (!queue.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            Pending p = queue.poll();
            os.write(p.row);
            rowsWritten++;
            Reader r = readers.get(p.source);
            byte[] row = r.nextRow();
            if (row != null) {
                queue.add(new Pending(r.rowTime, p.source, seq++, row));
            }
            if (progress != null && totalBytes > 0 && (seq & 4095) == 0) {
                lastReport = report(progress, totalBytes, lastReport);
            }
        }
        os.flush();
    }

    private long report(DoubleConsumer progress, long totalBytes, long lastReport) {
        long done = 0;
        for (Reader rd : readers) {
            done += rd.consumed;
        }
        if (done - lastReport > totalBytes / 200) {
            progress.accept((double) done / totalBytes);
            return done;
        }
        return lastReport;
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdown();
        for (Reader r : readers) {
            r.close();
        }
    }

    /** A field as CSV: quoted when it holds a comma, a quote, a line break or spaces at an end. */
    static String quote(String v) {
        boolean needs = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || (!v.isEmpty() && (Character.isWhitespace(v.charAt(0))
                        || Character.isWhitespace(v.charAt(v.length() - 1))));
        return needs ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    /** The first lines of a file (for detecting its format). */
    static List<String> sample(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while (out.size() < 2000 && (line = r.readLine()) != null) {
                out.add(line);
            }
        } catch (java.nio.charset.MalformedInputException e) {
            // keep what was read
        }
        return out;
    }

    /**
     * Reads one file row by row (a quoted field may go over several lines), skipping its
     * header, and writes each row in the merged columns. Remembers where it stopped, so later
     * calls continue with new rows.
     */
    private static final class Reader {
        private static final int BLOCK = 1 << 20;
        private final String label;
        private final FileChannel channel;
        private final char delimiter;
        private final int[] target;      // the file's column i goes to merged column target[i]
        private final int width;         // merged columns
        private final TimeFormat times;
        private final ByteBuffer block = ByteBuffer.allocate(BLOCK);
        private long position;
        private long consumed;
        private boolean headerSkipped;
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream(); // a row not complete yet
        private boolean inQuotes;
        private long lastTime = Long.MIN_VALUE;
        long rowTime;

        Reader(LogMerger.Source s, Plan plan) throws IOException {
            Formats.Detected d = detect(s.path());
            LogFormat f = d.columns();
            this.label = s.label();
            this.delimiter = f.delimiter();
            this.times = d.times();
            List<String> names = f.headerNames();
            List<String> keys = plan.columns().stream().map(CsvMerger::key).toList();
            this.target = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                target[i] = keys.indexOf(key(names.get(i)));
            }
            this.width = plan.columns().size();
            this.channel = FileChannel.open(s.path(), StandardOpenOption.READ);
            block.limit(0);
        }

        /** The next complete line including its newline, or null if none is complete yet. */
        private byte[] readLine() throws IOException {
            ByteArrayOutputStream acc = null;
            while (true) {
                if (!block.hasRemaining()) {
                    block.clear();
                    int n = channel.read(block, position);
                    block.flip();
                    if (n <= 0) {
                        if (acc != null) {
                            position -= acc.size(); // an unfinished last line: later
                        }
                        block.limit(0);
                        return null;
                    }
                    position += n;
                }
                byte[] arr = block.array();
                int from = block.position();
                int nl = ByteScan.indexOf(arr, from, block.limit(), (byte) '\n');
                if (nl < 0) {
                    if (acc == null) {
                        acc = new ByteArrayOutputStream(256);
                    }
                    acc.write(arr, from, block.limit() - from);
                    block.position(block.limit());
                    continue;
                }
                block.position(nl + 1);
                if (acc == null) {
                    return java.util.Arrays.copyOfRange(arr, from, nl + 1);
                }
                acc.write(arr, from, nl + 1 - from);
                return acc.toByteArray();
            }
        }

        /** The next row in the merged columns (with its newline), or null if none is complete yet. */
        byte[] nextRow() throws IOException {
            while (true) {
                byte[] line = readLine();
                if (line == null) {
                    return null;
                }
                consumed += line.length;
                partial.write(line, 0, line.length);
                for (byte b : line) {
                    if (b == '"') {
                        inQuotes = !inQuotes;
                    }
                }
                if (inQuotes) {
                    continue; // a quoted field goes on in the next line
                }
                String record = partial.toString(StandardCharsets.UTF_8);
                partial.reset();
                int end = record.length();
                while (end > 0 && (record.charAt(end - 1) == '\n' || record.charAt(end - 1) == '\r')) {
                    end--;
                }
                record = record.substring(0, end);
                if (record.startsWith("﻿")) {
                    record = record.substring(1);
                }
                if (record.isBlank()) {
                    continue;
                }
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                return row(record);
            }
        }

        private byte[] row(String record) {
            List<String> fields = Csv.split(record, delimiter, null);
            String[] merged = new String[width];
            for (int i = 0; i < fields.size(); i++) {
                int k = i < target.length ? target[i] : target[target.length - 1]; // extra: with the last
                if (k < 0) {
                    continue;
                }
                merged[k] = merged[k] == null || i < target.length ? fields.get(i)
                        : merged[k] + delimiter + fields.get(i);
            }
            if (times != null) {
                long t = times.parse(record);
                rowTime = t == TimeFormat.NONE ? lastTime : t;
                lastTime = rowTime;
            }
            StringBuilder sb = new StringBuilder(record.length() + label.length() + 8).append(quote(label));
            for (String v : merged) {
                sb.append(',').append(v == null ? "" : quote(v));
            }
            return sb.append('\n').toString().getBytes(StandardCharsets.UTF_8);
        }

        void close() {
            try {
                channel.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
