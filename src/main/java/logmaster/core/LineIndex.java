package logmaster.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Sparse index of line start offsets.
 *
 * <p>The file is split into chunks of a few MB. For each chunk we store the global number of its
 * first line, how many lines start inside it, and the byte offset of every {@value #SAMPLE}th
 * line. Finding line N means: binary search the chunk, take the nearest stored offset, and scan
 * forward at most 63 lines. A 10 GB log with 100M lines needs only about 12 MB of index.
 *
 * <p>Chunks are scanned in parallel and published in order, so the top of the file can be shown
 * while the rest is still being indexed. One thread (the document's indexer) writes; any thread
 * may read through an immutable {@link Snapshot}.
 */
public final class LineIndex {
    public static final int SAMPLE = 64;
    public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;

    /**
     * Lines whose start offset lies in {@code [startByte, endByte)}.
     *
     * @param checkpoints byte offset of local lines 0, SAMPLE, 2*SAMPLE, ...
     * @param endsWithNewline whether the byte at {@code endByte - 1} is a newline
     */
    public record Chunk(long startByte, long endByte, long firstLine, int lineCount,
                        long[] checkpoints, boolean endsWithNewline) {
        Chunk withFirstLine(long first) {
            return new Chunk(startByte, endByte, first, lineCount, checkpoints, endsWithNewline);
        }

        long endLine() {
            return firstLine + lineCount;
        }
    }

    /** Immutable view of the index at one moment. */
    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(new Chunk[0], 0);

        private final Chunk[] chunks;
        private final int chunkCount;
        /** Number of lines known so far. */
        public final long lineCount;
        /** Number of bytes indexed so far. */
        public final long indexedBytes;
        /** Whether the indexed bytes end with a newline (so the last line is complete). */
        public final boolean endsWithNewline;

        Snapshot(Chunk[] chunks, int chunkCount) {
            this.chunks = chunks;
            this.chunkCount = chunkCount;
            if (chunkCount == 0) {
                lineCount = 0;
                indexedBytes = 0;
                endsWithNewline = false;
            } else {
                Chunk last = chunks[chunkCount - 1];
                lineCount = last.endLine();
                indexedBytes = last.endByte;
                endsWithNewline = last.endsWithNewline;
            }
        }

        /**
         * Lines that are certainly complete (followed by a newline). Only these are safe to
         * search while a file is still growing.
         */
        public long completeLineCount() {
            return endsWithNewline || lineCount == 0 ? lineCount : lineCount - 1;
        }

        public int chunkCount() {
            return chunkCount;
        }

        public Chunk chunk(int i) {
            if (i >= chunkCount) {
                throw new IndexOutOfBoundsException(i);
            }
            return chunks[i];
        }

        /** Index of the chunk that holds {@code line}. Requires {@code line < lineCount}. */
        public int chunkFor(long line) {
            if (line < 0 || line >= lineCount) {
                throw new IndexOutOfBoundsException("line " + line + " of " + lineCount);
            }
            // Last chunk whose firstLine <= line. Empty chunks (inside one very long line)
            // share firstLine with the next chunk, so they are skipped automatically.
            int lo = 0;
            int hi = chunkCount - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (chunks[mid].firstLine <= line) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }
    }

    /** Read buffer per worker thread, shared by all indexes (one chunk at a time). */
    private static final ThreadLocal<byte[]> BUFFERS = ThreadLocal.withInitial(() -> new byte[0]);

    private final int chunkSize;

    // Writer state (indexer thread only).
    private Chunk[] array = new Chunk[64];
    private int count;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public LineIndex() {
        this(DEFAULT_CHUNK_SIZE);
    }

    /** {@code chunkSize} is configurable so tests can stress chunk boundaries. */
    public LineIndex(int chunkSize) {
        if (chunkSize < 2) {
            throw new IllegalArgumentException("chunkSize");
        }
        this.chunkSize = chunkSize;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Indexes the file from where the index currently ends up to {@code newSize}. Must only be
     * called from one thread at a time. {@code onPublish} is called after each chunk becomes
     * visible.
     *
     * @return false if cancelled
     */
    public boolean indexTo(FileSource source, long newSize, ExecutorService pool,
                           BooleanSupplier cancelled, Runnable onPublish) {
        long start = snapshot.indexedBytes;
        if (newSize <= start) {
            return true;
        }
        // A short last chunk (typical when tailing) is scanned again together with the new
        // bytes, so chunks stay close to chunkSize instead of piling up as tiny pieces.
        if (count > 0) {
            Chunk last = array[count - 1];
            if (last.endByte - last.startByte < chunkSize) {
                start = last.startByte;
                array = Arrays.copyOf(array, array.length); // older snapshots keep the old one
                count--;
            }
        }

        // Keep a bounded number of chunks in flight so memory stays small on huge files.
        int maxInFlight = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        Deque<Future<Chunk>> inFlight = new ArrayDeque<>();
        long next = start;
        try {
            while (next < newSize || !inFlight.isEmpty()) {
                while (next < newSize && inFlight.size() < maxInFlight) {
                    long a = next;
                    long b = Math.min(newSize, a + chunkSize);
                    inFlight.add(pool.submit(() -> scan(source, a, b)));
                    next = b;
                }
                if (cancelled.getAsBoolean()) {
                    inFlight.forEach(f -> f.cancel(false));
                    return false;
                }
                Chunk c = inFlight.removeFirst().get();
                long firstLine = count == 0 ? 0 : array[count - 1].endLine();
                append(c.withFirstLine(firstLine));
                onPublish.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlight.forEach(f -> f.cancel(false));
            return false;
        } catch (ExecutionException e) {
            inFlight.forEach(f -> f.cancel(false));
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException u) {
                throw u;
            }
            if (cause instanceof FileChangedException f) {
                throw f;
            }
            throw new IllegalStateException("Indexing failed", cause);
        }
        return true;
    }

    private void append(Chunk c) {
        if (count == array.length) {
            array = Arrays.copyOf(array, array.length * 2);
        }
        array[count++] = c;
        snapshot = new Snapshot(array, count);
    }

    /** Scans the line starts in {@code [a, b)}. A line starts at 0 and after every newline. */
    private Chunk scan(FileSource source, long a, long b) {
        byte[] buf = BUFFERS.get();
        if (buf.length < chunkSize + 1) {
            buf = new byte[chunkSize + 1];
            BUFFERS.set(buf);
        }
        // Read one byte before 'a' so a newline at a-1 marks a line start at 'a'.
        long readFrom = a == 0 ? 0 : a - 1;
        int len = (int) (b - readFrom);
        int n;
        try {
            n = source.read(readFrom, buf, 0, len);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (n < len) {
            // The file shrank while indexing; the document starts over.
            throw new FileChangedException("File shrank while indexing: " + source.path());
        }
        long[] cps = new long[16];
        int cpCount = 0;
        int lines = 0;
        if (a == 0 && b > 0) {
            cps[cpCount++] = 0;
            lines = 1;
        }
        // Newlines at positions [a-1, b-1) start lines at [a, b).
        int i = 0;
        int scanEnd = len - 1; // exclude b-1: a newline there starts a line in the next chunk
        while (true) {
            int nl = ByteScan.indexOf(buf, i, scanEnd, (byte) '\n');
            if (nl < 0) {
                break;
            }
            if (lines % SAMPLE == 0) {
                if (cpCount == cps.length) {
                    cps = Arrays.copyOf(cps, cpCount * 2);
                }
                cps[cpCount++] = readFrom + nl + 1;
            }
            lines++;
            i = nl + 1;
        }
        boolean endsWithNewline = len > 0 && buf[len - 1] == '\n';
        return new Chunk(a, b, 0, lines, Arrays.copyOf(cps, cpCount), endsWithNewline);
    }
}
