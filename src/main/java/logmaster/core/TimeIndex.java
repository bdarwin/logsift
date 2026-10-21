package logmaster.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * When each part of the file was written: one (line, time) sample about every
 * {@value LineIndex#SAMPLE} lines (the first line with a timestamp at or after every 64th line,
 * so stack traces are skipped). Built in parallel like the line index, and kept up to date
 * while the file grows. 100 million lines need about 25 MB.
 */
public final class TimeIndex implements AutoCloseable {

    /** Called on a background thread when samples were added or the index was reset. */
    public interface Listener {
        void updated(TimeIndex index);
    }

    /** Samples at one moment; arrays are only appended to, so entries below {@code n} stay valid. */
    public record Samples(long[] lines, long[] times, int n) {
        /** Index of the last sample with line <= {@code line}, or -1. */
        public int sampleAtOrBefore(long line) {
            int i = Arrays.binarySearch(lines, 0, n, line);
            return i >= 0 ? i : -i - 2;
        }

        /** Approximate time of a line (of the sample at or before it), or NONE. */
        public long timeOf(long line) {
            int i = sampleAtOrBefore(line);
            return i < 0 ? (n > 0 ? times[0] : TimeFormat.NONE) : times[i];
        }

        /**
         * A line at or before the first line with time >= {@code t}, assuming times mostly
         * increase. Refine by reading forward from it.
         */
        public long lineBefore(long t) {
            int lo = 0;
            int hi = n;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (times[mid] < t) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo == 0 ? 0 : lines[lo - 1];
        }

        public long minTime() {
            long m = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                m = Math.min(m, times[i]);
            }
            return m;
        }

        public long maxTime() {
            long m = Long.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                m = Math.max(m, times[i]);
            }
            return m;
        }
    }

    private final LogDocument doc;
    private final TimeFormat format;
    private final ExecutorService coordinator =
            Executors.newSingleThreadExecutor(LogDocument.daemon("lm-time"));
    private final AtomicBoolean queued = new AtomicBoolean();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LogDocument.Listener docListener;
    private static final ThreadLocal<byte[]> BUFFERS = ThreadLocal.withInitial(() -> new byte[0]);

    private volatile long[] lines = new long[1024];
    private volatile long[] times = new long[1024];
    private volatile int size;
    private volatile boolean cancelled;
    private volatile boolean running;
    // coordinator thread only
    private int generation;
    private long searched;   // complete lines done
    private long lastSampleLine = -1;

    public TimeIndex(LogDocument doc, TimeFormat format) {
        this.doc = doc;
        this.format = format;
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

    public TimeIndex start() {
        doc.addListener(docListener);
        schedule();
        return this;
    }

    public TimeFormat format() {
        return format;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public Samples samples() {
        int n = size;
        return new Samples(lines, times, Math.min(n, lines.length));
    }

    public boolean isRunning() {
        return running || queued.get();
    }

    public boolean awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        return doc.awaitIdle(timeoutMillis) && LogDocument.awaitExecutor(coordinator,
                Math.max(1, deadline - System.currentTimeMillis()));
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
        if (cancelled) {
            return;
        }
        running = true;
        try {
            if (doc.generation() != generation) {
                generation = doc.generation();
                size = 0;
                lines = new long[1024];
                times = new long[1024];
                searched = 0;
                lastSampleLine = -1;
            }
            LineIndex.Snapshot s = doc.snapshot();
            long complete = s.completeLineCount();
            if (complete > searched) {
                scanLines(s, searched, complete);
                searched = complete;
            }
            running = false;
            for (Listener l : listeners) {
                l.updated(this);
            }
        } catch (FileChangedException e) {
            running = false;
            doc.requestRefresh();
        } catch (RuntimeException e) {
            running = false;
        }
    }

    private record Range(long firstLine, long endLine, long startByte, long endByte) {}

    private record Found(long[] lines, long[] times, int n) {}

    private void scanLines(LineIndex.Snapshot s, long from, long to) {
        int maxInFlight = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        Deque<Future<Found>> inFlight = new ArrayDeque<>();
        int chunk = s.chunkFor(from);
        long lo = from;
        BooleanSupplier stop = () -> cancelled || doc.generation() != generation;
        try {
            while (lo < to || !inFlight.isEmpty()) {
                while (lo < to && inFlight.size() < maxInFlight) {
                    LineIndex.Chunk c = s.chunk(chunk);
                    long hi = Math.min(to, c.endLine());
                    if (hi > lo) {
                        Range r = new Range(lo, hi, doc.lineStart(s, lo), doc.lineStart(s, hi));
                        inFlight.add(LogDocument.WORKERS.submit(() -> scan(r)));
                        lo = hi;
                    }
                    chunk++;
                }
                if (stop.getAsBoolean()) {
                    inFlight.forEach(f -> f.cancel(false));
                    return;
                }
                Found f = inFlight.removeFirst().get();
                for (int i = 0; i < f.n; i++) {
                    // a range starts sampling at its first 64th line; skip if too close to the last
                    if (f.lines[i] > lastSampleLine) {
                        add(f.lines[i], f.times[i]);
                        lastSampleLine = f.lines[i];
                    }
                }
                for (Listener l : listeners) {
                    l.updated(this);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void add(long line, long time) {
        int n = size;
        if (n == lines.length) {
            long[] nl = Arrays.copyOf(lines, n * 2);
            long[] nt = Arrays.copyOf(times, n * 2);
            // publish times first: a reader taking the new lines array must find its times
            times = nt;
            lines = nl;
        }
        lines[n] = line;
        times[n] = time;
        size = n + 1;
    }

    /** Samples of one range: the first line with a timestamp at or after every 64th line. */
    private Found scan(Range r) {
        long len = Math.min(r.endByte - r.startByte, 256L * 1024 * 1024);
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
        long[] ls = new long[64];
        long[] ts = new long[64];
        int k = 0;
        int pos = 0;
        long line = r.firstLine;
        boolean want = true; // look for a time on the first line of the range too
        while (pos < n) {
            int nl = ByteScan.indexOf(buf, pos, n, (byte) '\n');
            int end = nl < 0 ? n : nl;
            if (line % LineIndex.SAMPLE == 0) {
                want = true;
            }
            if (want) {
                long t = format.parse(buf, pos, end);
                if (t != TimeFormat.NONE) {
                    if (k == ls.length) {
                        ls = Arrays.copyOf(ls, k * 2);
                        ts = Arrays.copyOf(ts, k * 2);
                    }
                    ls[k] = line;
                    ts[k] = t;
                    k++;
                    want = false;
                }
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
            line++;
        }
        return new Found(ls, ts, k);
    }

    @Override
    public void close() {
        cancelled = true;
        doc.removeListener(docListener);
        coordinator.shutdown();
    }
}
