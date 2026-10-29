package logsift.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An open log file: line index, line access, and live following of the file as it grows
 * (tail), including truncation and rotation.
 *
 * <p>Listeners are called on background threads; UI code must hand off to its own thread.
 */
public final class LogDocument implements Closeable {

    /** Called on a background thread. */
    public interface Listener {
        /** New lines were indexed (initial indexing progress, or the file grew). */
        default void linesAdded(LogDocument doc) {}

        /** The file was truncated or replaced; all line numbers are now invalid. */
        default void reset(LogDocument doc) {}

        /** Initial indexing finished. */
        default void indexingFinished(LogDocument doc, long millis) {}

        default void failed(LogDocument doc, Exception e) {}

        /** The file on disk changed and is not loaded yet (auto reload off), or was just loaded. */
        default void staleChanged(LogDocument doc) {}
    }

    /** How the file on disk differs from what is loaded. */
    public enum Change { NONE, GREW, REPLACED }

    /** Lines longer than this are cut for display (searching still sees the full line). */
    public static final int MAX_DISPLAY_BYTES = 64 * 1024;
    private static final int FINGERPRINT_BYTES = 256;
    private static final long NOTIFY_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()), daemon("logsift-worker"));
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(daemon("logsift-timer"));

    private final Path path;
    private final int chunkSize;
    private final ExecutorService serial;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();

    private volatile FileSource source;
    private volatile LineIndex index;
    private volatile PageCache cache;      // lines on screen
    private volatile PageCache bulkCache;  // line starts for background work (searches, timeline)
    private volatile long fileSize;
    private volatile boolean indexing = true;
    private volatile boolean closed;
    private volatile ScheduledFuture<?> poller;

    // Indexer thread only.
    private Object fileKey;
    private byte[] fingerprint = new byte[0];
    private long lastNotify;

    private LogDocument(Path path, int chunkSize) throws IOException {
        this.path = path.toAbsolutePath();
        this.chunkSize = chunkSize;
        this.serial = Executors.newSingleThreadExecutor(daemon("logsift-index-" + path.getFileName()));
        openSource();
    }

    /** Opens a file and starts indexing it in the background. */
    public static LogDocument open(Path path) throws IOException {
        return open(path, LineIndex.DEFAULT_CHUNK_SIZE);
    }

    public static LogDocument open(Path path, int chunkSize) throws IOException {
        return open(path, chunkSize, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Opens a file whose text is in {@code charset}. It must keep newlines as the byte 0x0A
     * (UTF-8, Windows-1252, ISO-8859-x...); UTF-16 files are converted first.
     */
    public static LogDocument open(Path path, int chunkSize, java.nio.charset.Charset charset)
            throws IOException {
        LogDocument doc = new LogDocument(path, chunkSize);
        doc.charset = charset;
        doc.serial.execute(doc::initialIndex);
        return doc;
    }

    private volatile java.nio.charset.Charset charset = java.nio.charset.StandardCharsets.UTF_8;

    /** The text encoding used to decode lines and encode search text. */
    public java.nio.charset.Charset charset() {
        return charset;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Path path() {
        return path;
    }

    /** File size as of the last check. */
    public long fileSize() {
        return fileSize;
    }

    public boolean isIndexing() {
        return indexing;
    }

    public LineIndex.Snapshot snapshot() {
        return index.snapshot();
    }

    public long lineCount() {
        return index.snapshot().lineCount;
    }

    private volatile long lastIndexMillis = -1;

    /** How long the last full (re)index took, or -1 while the first one runs. */
    public long lastIndexMillis() {
        return lastIndexMillis;
    }

    /** Changes every time the document is reset (truncation or rotation). */
    public int generation() {
        return generation.get();
    }

    FileSource source() {
        return source;
    }

    /** Checks the file for growth, truncation or rotation every {@code millis} ms. */
    public void startFollowing(long millis) {
        stopFollowing();
        poller = TIMER.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
    }

    private volatile boolean autoReload = true;
    private volatile Change pending = Change.NONE;
    private volatile long pendingBytes;
    private final AtomicBoolean checkQueued = new AtomicBoolean();

    /**
     * With auto reload (the default) changes on disk are loaded as they happen. Without it they
     * are only noticed ({@link #pendingChange()}) until {@link #reload()} is called.
     */
    public void setAutoReload(boolean on) {
        autoReload = on;
        if (on && pending != Change.NONE) {
            requestRefresh();
        }
    }

    public boolean isAutoReload() {
        return autoReload;
    }

    /** What changed on disk and is not loaded yet. */
    public Change pendingChange() {
        return pending;
    }

    /** For a file that grew: how many bytes are not loaded yet. */
    public long pendingBytes() {
        return pendingBytes;
    }

    /** Loads what changed on disk now. */
    public void reload() {
        requestRefresh();
    }

    private void poll() {
        if (autoReload) {
            requestRefresh();
        } else if (!closed && checkQueued.compareAndSet(false, true)) {
            serial.execute(() -> {
                checkQueued.set(false);
                checkForChange();
            });
        }
    }

    /** Notices a change on disk without loading it. */
    private void checkForChange() {
        if (closed) {
            return;
        }
        Change c = Change.NONE;
        long bytes = 0;
        try {
            Object key;
            try {
                key = fileKey(path);
            } catch (NoSuchFileException e) {
                return;
            }
            long size = source.size();
            LineIndex.Snapshot s = index.snapshot();
            if ((key != null && !key.equals(fileKey)) || (key == null && Files.size(path) < size)
                    || size < s.indexedBytes || !fingerprintStillMatches()) {
                c = Change.REPLACED;
            } else if (size > s.indexedBytes) {
                c = Change.GREW;
                bytes = size - s.indexedBytes;
            }
        } catch (IOException | UncheckedIOException | FileChangedException e) {
            return; // try again next time
        }
        setPending(c, bytes);
    }

    private void setPending(Change c, long bytes) {
        boolean changed = c != pending || bytes != pendingBytes;
        pending = c;
        pendingBytes = bytes;
        if (changed) {
            for (Listener l : listeners) {
                l.staleChanged(this);
            }
        }
    }

    /** Like fingerprintMatches, but without remembering anything. */
    private boolean fingerprintStillMatches() throws IOException {
        byte[] now = readFingerprint(source);
        return now.length >= fingerprint.length
                && Arrays.equals(fingerprint, 0, fingerprint.length, now, 0, fingerprint.length);
    }

    public void stopFollowing() {
        ScheduledFuture<?> p = poller;
        if (p != null) {
            p.cancel(false);
            poller = null;
        }
    }

    /** Asks for an immediate check of the file. Coalesces repeated requests. */
    public void requestRefresh() {
        if (!closed && refreshQueued.compareAndSet(false, true)) {
            serial.execute(() -> {
                refreshQueued.set(false);
                refresh();
            });
        }
    }

    /**
     * Waits until all queued indexing work is done. Mainly for tests and scripted use.
     *
     * @return false on timeout
     */
    public boolean awaitIdle(long timeoutMillis) {
        return awaitExecutor(serial, timeoutMillis);
    }

    static boolean awaitExecutor(ExecutorService ex, long timeoutMillis) {
        try {
            ex.submit(() -> {}).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.RejectedExecutionException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------------------------------------------------------- line access

    /** Byte offset where {@code line} starts, using the given snapshot (for background work). */
    public long lineStart(LineIndex.Snapshot s, long line) {
        return lineStart(s, line, bulkCache, false);
    }

    private long lineStart(LineIndex.Snapshot s, long line, PageCache pc, boolean cachedOnly) {
        if (line == s.lineCount) {
            return s.indexedBytes;
        }
        LineIndex.Chunk c = s.chunk(s.chunkFor(line));
        int local = (int) (line - c.firstLine());
        long pos = c.checkpoints()[local / LineIndex.SAMPLE];
        for (int k = local % LineIndex.SAMPLE; k > 0; k--) {
            long nl = pc.indexOf(pos, c.endByte(), (byte) '\n', cachedOnly);
            if (nl < 0) {
                throw new FileChangedException("Line " + line + " not found; file changed");
            }
            pos = nl + 1;
        }
        return pos;
    }

    /** Text of one line (without line ending), cut at {@link #MAX_DISPLAY_BYTES}. */
    public String line(long line) {
        String[] out = new String[1];
        readLines(line, 1, out);
        return out[0];
    }

    /**
     * Reads {@code count} consecutive lines starting at {@code first} into {@code out}. Lines
     * past the end are set to null.
     */
    public void readLines(long first, int count, String[] out) {
        readFullLines(first, count, out, MAX_DISPLAY_BYTES);
    }

    /**
     * Like {@link #readLines}, with lines up to {@code maxBytes} long (for a table's rows, which
     * can be much longer than what is shown of a line).
     */
    public void readFullLines(long first, int count, String[] out, int maxBytes) {
        try {
            readLinesUnchecked(first, count, out, false, maxBytes);
        } catch (FileChangedException | UncheckedIOException e) {
            Arrays.fill(out, 0, count, null);
            requestRefresh();
        }
    }

    /**
     * Like {@link #readLines}, for painting: waits for the disk at most {@link #PAINT_WAIT_MS}
     * (on a network drive a read can take long). If the lines are not there by then, {@code out}
     * is filled with null, they go on being read in the background, and {@code loaded} runs (on
     * a background thread) when they are there. A newer request from the same {@code owner}
     * replaces one not started yet.
     *
     * @return whether the lines were read
     */
    public boolean readLinesNow(long first, int count, String[] out, Object owner, Runnable loaded) {
        try {
            readLinesUnchecked(first, count, out, true, MAX_DISPLAY_BYTES);
            return true;
        } catch (PageCache.NotLoaded e) {
            Want w = new Want(first, count, new java.util.concurrent.CountDownLatch(1));
            if (wanted.put(owner, w) == null) {
                LOADERS.execute(() -> load(owner, loaded));
            }
            try {
                // after a wait ran out, don't wait again for a moment (many rows, one slow drive)
                if (System.nanoTime() - slowSince > TimeUnit.MILLISECONDS.toNanos(250)
                        && w.done.await(PAINT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    readLinesUnchecked(first, count, out, true, MAX_DISPLAY_BYTES);
                    return true;
                }
                if (w.done.getCount() > 0) {
                    slowSince = System.nanoTime();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (PageCache.NotLoaded again) {
                // dropped from memory meanwhile: comes with the next paint
            }
            Arrays.fill(out, 0, count, null);
            return false;
        } catch (FileChangedException | UncheckedIOException e) {
            Arrays.fill(out, 0, count, null);
            requestRefresh();
            return false;
        }
    }

    /** How long painting waits for lines that are not in memory yet. */
    public static final long PAINT_WAIT_MS = 40;

    private volatile long slowSince = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);

    private record Want(long first, int count, java.util.concurrent.CountDownLatch done) {}

    private void load(Object owner, Runnable loaded) {
        Want w = wanted.remove(owner);
        if (w == null || closed) {
            return;
        }
        try {
            readLines(w.first, w.count, new String[w.count]);
        } finally {
            w.done.countDown();
        }
        if (!closed) {
            loaded.run();
        }
    }

    /** Text of one line if it is in memory, else null (never waits for the disk). */
    public String lineIfLoaded(long line) {
        String[] out = new String[1];
        try {
            readLinesUnchecked(line, 1, out, true, MAX_DISPLAY_BYTES);
            return out[0];
        } catch (PageCache.NotLoaded | FileChangedException | UncheckedIOException e) {
            return null;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<Object, Want> wanted =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Reads lines for painting in the background (shared by all documents). */
    private static final ExecutorService LOADERS = Executors.newFixedThreadPool(4, daemon("logsift-load"));

    private final java.util.concurrent.ConcurrentHashMap<Character, CsvRows> csvRows =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The rows of this file read as CSV with {@code delimiter} (see {@link CsvRows#of}). */
    CsvRows csvRows(char delimiter) {
        return csvRows.computeIfAbsent(delimiter, d -> new CsvRows(this, d));
    }

    private void readLinesUnchecked(long first, int count, String[] out, boolean cachedOnly, int maxBytes) {
        LineIndex.Snapshot s = index.snapshot();
        PageCache pc = cache;
        long limit = Math.max(fileSize, s.indexedBytes);
        byte[] buf = null;
        long pos = -1;
        for (int i = 0; i < count; i++) {
            long line = first + i;
            if (line < 0 || line >= s.lineCount) {
                out[i] = null;
                continue;
            }
            if (pos < 0) {
                pos = lineStart(s, line, pc, cachedOnly);
            }
            long max = Math.min(limit, pos + maxBytes);
            long nl = pc.indexOf(pos, max, (byte) '\n', cachedOnly);
            long end = nl >= 0 ? nl : max;
            int len = (int) (end - pos);
            if (buf == null || buf.length < len) {
                buf = new byte[Math.max(len, 4096)];
            }
            int n = pc.read(pos, buf, 0, len, cachedOnly);
            if (n > 0 && buf[n - 1] == '\r') {
                n--;
            }
            String text = new String(buf, 0, n, charset);
            if (line == 0 && i == 0 && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1); // byte order mark
            }
            out[i] = text;
            // If the line was cut, find the next start through the index.
            pos = nl >= 0 ? nl + 1 : -1;
        }
    }

    // ---------------------------------------------------------------- indexing

    private void openSource() throws IOException {
        FileSource old = source;
        source = new FileSource(path);
        if (old != null) {
            old.close();
        }
        index = new LineIndex(chunkSize);
        cache = new PageCache(source, 512);
        bulkCache = new PageCache(source, 64);
        fileKey = fileKey(path);
        fingerprint = readFingerprint(source);
        fileSize = source.size();
    }

    private void initialIndex() {
        long t0 = System.nanoTime();
        indexing = true;
        try {
            indexTo(source.size());
        } catch (RuntimeException | IOException e) {
            logsift.core.Problems.unexpected(e); // a bug, if not a read or connection failure
            fail(e);
        } finally {
            indexing = false;
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        lastIndexMillis = ms;
        notifyLines(true);
        for (Listener l : listeners) {
            l.indexingFinished(this, ms);
        }
        // Catch up with anything written while we were indexing.
        refresh();
    }

    private void indexTo(long size) {
        fileSize = Math.max(fileSize, size);
        int gen = generation.get();
        index.indexTo(source, size, WORKERS,
                () -> closed || generation.get() != gen,
                () -> notifyLines(false));
    }

    private void refresh() {
        if (closed) {
            return;
        }
        try {
            refreshNow();
        } finally {
            setPending(Change.NONE, 0); // whatever was on disk is loaded now
        }
    }

    private void refreshNow() {
        try {
            Object key;
            try {
                key = fileKey(path);
            } catch (NoSuchFileException e) {
                return; // rotated away and not recreated yet; check again later
            }
            if (key != null && !key.equals(fileKey)) {
                reset(); // file replaced (rotation)
                return;
            }
            long size = source.size();
            if (key == null && Files.size(path) < size) {
                reset(); // Windows: no file key, but a smaller file at the path means rotation
                return;
            }
            LineIndex.Snapshot s = index.snapshot();
            if (size < s.indexedBytes || !fingerprintMatches()) {
                reset(); // truncated or rewritten
                return;
            }
            if (size > s.indexedBytes) {
                indexTo(size);
                notifyLines(true);
            }
        } catch (FileChangedException e) {
            reset();
        } catch (IOException | UncheckedIOException e) {
            fail(e);
        }
    }

    private void reset() {
        generation.incrementAndGet();
        try {
            openSource();
        } catch (IOException e) {
            fail(e);
            return;
        }
        for (Listener l : listeners) {
            l.reset(this);
        }
        indexing = true;
        try {
            indexTo(source.size());
        } catch (FileChangedException e) {
            requestRefresh();
        } catch (IOException | UncheckedIOException e) {
            fail(e);
        } finally {
            indexing = false;
        }
        notifyLines(true);
    }

    private void notifyLines(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastNotify < NOTIFY_INTERVAL_NANOS) {
            return;
        }
        lastNotify = now;
        for (Listener l : listeners) {
            l.linesAdded(this);
        }
    }

    private void fail(Exception e) {
        for (Listener l : listeners) {
            l.failed(this, e);
        }
    }

    private static Object fileKey(Path p) throws IOException {
        // Inode on Linux/macOS; null on Windows (there we rely on size and fingerprint).
        return Files.readAttributes(p, BasicFileAttributes.class).fileKey();
    }

    /** Whether the start of the file is unchanged. Detects a file rewritten in place. */
    private boolean fingerprintMatches() throws IOException {
        byte[] now = readFingerprint(source);
        if (now.length < fingerprint.length) {
            return false;
        }
        if (!Arrays.equals(fingerprint, 0, fingerprint.length, now, 0, fingerprint.length)) {
            return false;
        }
        fingerprint = now; // a short file may have grown; remember the longer prefix
        return true;
    }

    private static byte[] readFingerprint(FileSource src) throws IOException {
        byte[] b = new byte[FINGERPRINT_BYTES];
        int n = src.read(0, b, 0, b.length);
        return n == b.length ? b : Arrays.copyOf(b, n);
    }

    @Override
    public void close() {
        closed = true;
        stopFollowing();
        listeners.clear();
        wanted.clear();
        serial.shutdownNow();
        try {
            source.close();
        } catch (IOException ignored) {
            // closing a read-only file
        }
    }

    @Override
    public String toString() {
        return "LogDocument[" + path + "]";
    }

    static ThreadFactory daemon(String name) {
        Objects.requireNonNull(name);
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
