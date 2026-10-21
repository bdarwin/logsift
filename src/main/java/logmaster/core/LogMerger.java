package logmaster.core;

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
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Merges several logs into one file in timestamp order (for example the same app on two
 * nodes, or an app log and a server log). Each line gets a source label like {@code <node1> }
 * so it is clear where it came from, and the result is an ordinary log file: every feature
 * (filters, timeline, messages) works on it.
 *
 * <p>Entries (a line and the stack trace under it) stay together. The merge streams all files
 * at once and keeps one entry per file in memory. Afterwards {@link #follow} keeps appending
 * what the sources write; entries that arrive late are appended in arrival order.
 */
public final class LogMerger implements AutoCloseable {

    /** One input. */
    public record Source(Path path, String label) {}

    private final List<Reader> readers = new ArrayList<>();
    private final Path out;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(LogDocument.daemon("lm-merge"));
    private volatile boolean closed;
    private long linesWritten;

    private LogMerger(Path out) {
        this.out = out;
    }

    /**
     * Merges the sources into {@code out} (replacing it).
     *
     * @throws IOException if a file can't be read, or has no timestamps LogMaster understands
     */
    public static LogMerger merge(List<Source> sources, Path out, DoubleConsumer progress,
                                  BooleanSupplier cancelled) throws IOException {
        LogMerger m = new LogMerger(out);
        long total = 0;
        for (Source s : sources) {
            m.readers.add(new Reader(s));
            total += Files.size(s.path());
        }
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            m.mergeAvailable(os, total, progress, cancelled);
        }
        return m;
    }

    public Path output() {
        return out;
    }

    public long linesWritten() {
        return linesWritten;
    }

    /** Keeps appending new entries from the sources, checking every {@code millis} ms. */
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

    private record Pending(long time, int source, long seq, byte[] entry) {}

    /** Merges every complete entry the sources have now. */
    private synchronized void mergeAvailable(OutputStream os, long totalBytes,
                                             DoubleConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        PriorityQueue<Pending> queue = new PriorityQueue<>((a, b) -> {
            int c = Long.compare(a.time, b.time);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.source, b.source);
            return c != 0 ? c : Long.compare(a.seq, b.seq);
        });
        long seq = 0;
        for (int i = 0; i < readers.size(); i++) {
            Reader r = readers.get(i);
            byte[] e = r.nextEntry();
            if (e != null) {
                queue.add(new Pending(r.entryTime, i, seq++, e));
            }
        }
        long lastReport = 0;
        while (!queue.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            Pending p = queue.poll();
            os.write(p.entry);
            linesWritten += countLines(p.entry);
            Reader r = readers.get(p.source);
            byte[] e = r.nextEntry();
            if (e != null) {
                queue.add(new Pending(r.entryTime, p.source, seq++, e));
            }
            if (progress != null && totalBytes > 0 && (seq & 4095) == 0) {
                long done = 0;
                for (Reader rd : readers) {
                    done += rd.consumed;
                }
                if (done - lastReport > totalBytes / 200) {
                    lastReport = done;
                    progress.accept((double) done / totalBytes);
                }
            }
        }
        os.flush();
    }

    private static int countLines(byte[] b) {
        return ByteScan.count(b, 0, b.length, (byte) '\n');
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdown();
        for (Reader r : readers) {
            r.close();
        }
    }

    /**
     * Reads one source entry by entry (only complete lines), prefixing every line with the
     * source label. Remembers where it stopped, so later calls continue with new data.
     */
    private static final class Reader {
        private static final int BLOCK = 1 << 20;
        private final byte[] label;
        private final FileChannel channel;
        private final EntryFormat entries;
        private final TimeFormat times;
        private final ByteBuffer block = ByteBuffer.allocate(BLOCK);
        private long position;       // file position of block's first unread byte
        private long consumed;       // bytes turned into entries
        private byte[] pendingLine;  // a head line read ahead (starts the next entry)
        private long lastTime = Long.MIN_VALUE;
        long entryTime;

        Reader(Source s) throws IOException {
            this.label = ("<" + s.label() + "> ").getBytes(StandardCharsets.UTF_8);
            this.channel = FileChannel.open(s.path(), StandardOpenOption.READ);
            List<String> sample = sample(s.path());
            this.entries = EntryFormat.detect(sample);
            int year = java.time.Year.now().getValue();
            this.times = TimeFormat.detect(sample, year);
            if (times == null) {
                channel.close();
                throw new IOException(s.path().getFileName() + ": no timestamps found to merge by");
            }
            block.limit(0);
        }

        private static List<String> sample(Path p) throws IOException {
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
                            // incomplete last line: leave it for later
                            position -= acc.size();
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

        /**
         * The next entry (labelled lines), or null if no complete entry is available yet. The
         * last entry is only returned once the next one has started, or the file has stopped
         * growing for now (then it is returned as is).
         */
        byte[] nextEntry() throws IOException {
            byte[] head = pendingLine != null ? pendingLine : readLine();
            pendingLine = null;
            if (head == null) {
                return null;
            }
            ByteArrayOutputStream entry = new ByteArrayOutputStream(head.length + label.length + 64);
            appendLine(entry, head);
            long t = times.parse(head, 0, head.length - 1);
            entryTime = t == TimeFormat.NONE ? lastTime : t;
            lastTime = entryTime;
            while (true) {
                byte[] line = readLine();
                if (line == null) {
                    break;
                }
                int end = line.length - 1;
                if (end > 0 && line[end - 1] == '\r') {
                    end--;
                }
                if (entries == null || entries.startsEntry(line, 0, end)) {
                    pendingLine = line;
                    break;
                }
                appendLine(entry, line);
            }
            return entry.toByteArray();
        }

        private void appendLine(ByteArrayOutputStream entry, byte[] line) {
            entry.write(label, 0, label.length);
            entry.write(line, 0, line.length);
            consumed += line.length;
        }

        void close() {
            try {
                channel.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /** Short labels for files: their names without extension, made unique with the folder. */
    public static List<String> labels(List<Path> paths) {
        List<String> base = new ArrayList<>();
        for (Path p : paths) {
            String n = p.getFileName().toString();
            int dot = n.indexOf('.');
            base.add(dot > 0 ? n.substring(0, dot) : n);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            String b = base.get(i);
            boolean dup = false;
            for (int j = 0; j < paths.size(); j++) {
                dup |= j != i && base.get(j).equals(b);
            }
            Path parent = paths.get(i).toAbsolutePath().getParent();
            String label = dup && parent != null && parent.getFileName() != null
                    ? parent.getFileName() + "-" + b : b;
            if (out.contains(label)) {
                label = label + "-" + (i + 1);
            }
            out.add(label.replace(' ', '_').replace('<', '_').replace('>', '_'));
        }
        return out;
    }
}
