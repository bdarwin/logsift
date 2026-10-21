package logmaster.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * A "Find All" over one document. Searches in parallel, publishes hits in line order as they are
 * found, and keeps searching new lines while the file grows (live Find All on a tailed file).
 */
public final class SearchSession implements AutoCloseable {

    /** Called on a background thread. */
    public interface Listener {
        /** New hits or progress. */
        void updated(SearchSession s);

        /** All lines known so far have been searched. */
        default void caughtUp(SearchSession s, long millis) {}

        default void failed(SearchSession s, Exception e) {}
    }

    /** Stop collecting after this many matching lines (8 bytes each). */
    public static final int MAX_HITS = 20_000_000;
    /** A single search range is cut at this size (only matters for gigantic single lines). */
    private static final int MAX_RANGE_BYTES = 256 * 1024 * 1024;
    private static final long NOTIFY_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(80);

    private final LogDocument doc;
    private final SearchQuery query;
    private final LineMatcher matcher;
    private final HitList hits = new HitList();
    private final ExecutorService coordinator;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    /** Read buffer per worker thread, shared by all searches (one search range at a time). */
    private static final ThreadLocal<byte[]> BUFFERS = ThreadLocal.withInitial(() -> new byte[0]);
    private final LogDocument.Listener docListener;

    private volatile boolean cancelled;
    private volatile boolean running;
    private volatile boolean limitReached;
    private volatile long searchedLines;  // complete lines fully searched
    private volatile long progressLine;   // for display only
    private volatile long targetLines;

    // Coordinator thread only.
    private int generation;
    private long tentativeLine = -1;
    private long lastNotify;
    private long startNanos;

    /** @throws java.util.regex.PatternSyntaxException for an invalid regex */
    public SearchSession(LogDocument doc, SearchQuery query) {
        this.doc = doc;
        this.query = query;
        this.matcher = LineMatcher.of(query);
        this.coordinator = Executors.newSingleThreadExecutor(LogDocument.daemon("lm-search"));
        this.generation = doc.generation();
        this.docListener = new LogDocument.Listener() {
            @Override
            public void linesAdded(LogDocument d) {
                schedule();
            }

            @Override
            public void reset(LogDocument d) {
                schedule();
            }
        };
    }

    /** Starts searching and follows the document as it grows. */
    public SearchSession start() {
        doc.addListener(docListener);
        startNanos = System.nanoTime();
        schedule();
        return this;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public SearchQuery query() {
        return query;
    }

    public LogDocument document() {
        return doc;
    }

    public HitList hits() {
        return hits;
    }

    /** True while lines are being searched. */
    public boolean isRunning() {
        return running || queued.get();
    }

    /**
     * Waits until the document is idle and every known line has been searched.
     *
     * @return false on timeout
     */
    public boolean awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        return doc.awaitIdle(timeoutMillis)
                && LogDocument.awaitExecutor(coordinator,
                        Math.max(1, deadline - System.currentTimeMillis()));
    }

    public boolean isLimitReached() {
        return limitReached;
    }

    /** Fraction of known lines searched so far, 0..1. */
    public double progress() {
        long t = targetLines;
        return t <= 0 ? 1 : Math.min(1, (double) progressLine / t);
    }

    public long searchedLines() {
        return searchedLines;
    }

    private void schedule() {
        if (!cancelled && queued.compareAndSet(false, true)) {
            coordinator.execute(() -> {
                queued.set(false);
                catchUp();
            });
        }
    }

    private void catchUp() {
        if (cancelled || limitReached) {
            return;
        }
        running = true;
        try {
            if (doc.generation() != generation) {
                // File was truncated or rotated: start over.
                generation = doc.generation();
                hits.clear();
                searchedLines = 0;
                progressLine = 0;
                tentativeLine = -1;
                startNanos = System.nanoTime();
            }
            LineIndex.Snapshot s = doc.snapshot();
            long complete = s.completeLineCount();
            boolean partialLast = s.lineCount > complete;
            targetLines = s.lineCount;
            if (complete > searchedLines || (partialLast && !doc.isIndexing())) {
                // A partial last line searched before may have grown since; search it again.
                if (tentativeLine >= 0) {
                    hits.truncateFrom(tentativeLine);
                    tentativeLine = -1;
                }
            }
            if (complete > searchedLines) {
                searchLines(s, searchedLines, complete);
                searchedLines = complete;
            }
            progressLine = s.lineCount;
            // When the file is quiet, also search an unfinished last line.
            if (partialLast && !doc.isIndexing() && tentativeLine < 0 && !cancelled) {
                long last = s.lineCount - 1;
                searchLines(s, last, s.lineCount);
                tentativeLine = last;
            }
            running = false;
            notifyUpdated(true);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            for (Listener l : listeners) {
                l.caughtUp(this, ms);
            }
        } catch (FileChangedException e) {
            running = false;
            doc.requestRefresh();
        } catch (RuntimeException e) {
            running = false;
            for (Listener l : listeners) {
                l.failed(this, e);
            }
        }
    }

    private record Range(long firstLine, long endLine, long startByte, long endByte) {}

    private record Pending(Range range, Future<long[]> result) {}

    /** Searches lines {@code [from, to)} of snapshot {@code s}, in parallel, publishing in order. */
    private void searchLines(LineIndex.Snapshot s, long from, long to) {
        int maxInFlight = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        Deque<Pending> inFlight = new ArrayDeque<>();
        int chunk = from >= s.lineCount ? s.chunkCount() : s.chunkFor(from);
        long lo = from;
        BooleanSupplier stop = () -> cancelled || limitReached || doc.generation() != generation;
        try {
            while (lo < to || !inFlight.isEmpty()) {
                while (lo < to && inFlight.size() < maxInFlight) {
                    LineIndex.Chunk c = s.chunk(chunk);
                    long hi = Math.min(to, c.endLine());
                    if (hi > lo) {
                        Range r = new Range(lo, hi, doc.lineStart(s, lo), doc.lineStart(s, hi));
                        inFlight.add(new Pending(r,
                                LogDocument.WORKERS.submit(() -> searchRange(r, stop))));
                        lo = hi;
                    }
                    chunk++;
                }
                if (stop.getAsBoolean()) {
                    inFlight.forEach(p -> p.result.cancel(true));
                    return;
                }
                Pending done = inFlight.removeFirst();
                long[] found = done.result.get();
                for (long line : found) {
                    if (hits.size() >= MAX_HITS) {
                        limitReached = true;
                        break;
                    }
                    hits.add(line);
                }
                progressLine = done.range.endLine;
                notifyUpdated(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlight.forEach(p -> p.result.cancel(true));
        } catch (ExecutionException e) {
            inFlight.forEach(p -> p.result.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    private long[] searchRange(Range r, BooleanSupplier stop) {
        long len = Math.min(r.endByte - r.startByte, MAX_RANGE_BYTES);
        byte[] buf = BUFFERS.get();
        if (buf.length < len) {
            buf = new byte[(int) Math.max(len, LineIndex.DEFAULT_CHUNK_SIZE + 64 * 1024)];
            BUFFERS.set(buf);
        }
        int n;
        try {
            n = doc.source().read(r.startByte, buf, 0, (int) len);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LongBuffer out = new LongBuffer();
        matcher.scan(buf, n, r.firstLine, line -> {
            out.add(line);
            return out.size < MAX_HITS;
        }, stop);
        return out.toArray();
    }

    private void notifyUpdated(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastNotify < NOTIFY_INTERVAL_NANOS) {
            return;
        }
        lastNotify = now;
        for (Listener l : listeners) {
            l.updated(this);
        }
    }

    /** Stops searching and stops following the document. */
    @Override
    public void close() {
        cancelled = true;
        doc.removeListener(docListener);
        coordinator.shutdownNow();
    }

    private static final class LongBuffer {
        long[] a = new long[64];
        int size;

        void add(long v) {
            if (size == a.length) {
                a = java.util.Arrays.copyOf(a, size * 2);
            }
            a[size++] = v;
        }

        long[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }
}
