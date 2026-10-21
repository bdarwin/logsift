package logmaster.core;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Searches every matching file under a folder (folder search tools style), in parallel, including files
 * inside {@code .gz} and {@code .zip} archives. Files are streamed in large blocks through the
 * same fast line matcher as Find All, so nothing is indexed or kept in memory except the hits.
 */
public final class FolderSearch implements AutoCloseable {

    /**
     * What to search.
     *
     * @param root the folder
     * @param fileNames file name patterns like {@code *.log;*.txt} (empty = all files)
     * @param recursive also search subfolders
     * @param archives also search inside .gz and .zip files
     */
    public record Options(Path root, String fileNames, boolean recursive, boolean archives,
                          SearchQuery query) {}

    /** One matching line. Text is cut to {@link #MAX_TEXT} characters. */
    public record Hit(long line, String text) {}

    /** Hits in one file (or one entry of a zip file). */
    public static final class FileResult {
        private final Path file;
        private final String entry;
        private final List<Hit> hits = new ArrayList<>();
        private volatile long hitCount;
        private long lastLine = -1; // worker thread only

        FileResult(Path file, String entry) {
            this.file = file;
            this.entry = entry;
        }

        /** The file on disk (the archive, for a zip entry). */
        public Path file() {
            return file;
        }

        /** Name inside a zip file, or null. */
        public String entry() {
            return entry;
        }

        /** Whether the content is compressed (a .gz file or a zip entry). */
        public boolean isCompressed() {
            return entry != null || isGzip(file);
        }

        /** Number of matching lines (may be more than {@link #hits()} keeps). */
        public long hitCount() {
            return hitCount;
        }

        /** The kept hits, in line order. Safe to read from any thread. */
        public synchronized List<Hit> hits() {
            return List.copyOf(hits);
        }

        public synchronized int keptHits() {
            return hits.size();
        }

        public synchronized Hit hit(int i) {
            return hits.get(i);
        }

        synchronized void add(Hit h, boolean keep) {
            if (keep) {
                hits.add(h);
            }
            hitCount++;
        }
    }

    /** Called on background threads. */
    public interface Listener {
        void updated(FolderSearch s);

        default void finished(FolderSearch s, long millis) {}
    }

    public static final int MAX_TEXT = 1000;
    /** Hits kept with their text, per file and in total; more are only counted. */
    public static final int MAX_KEPT_PER_FILE = 10_000;
    public static final int MAX_KEPT_TOTAL = 1_000_000;
    private static final int BLOCK = 4 * 1024 * 1024;
    private static final int MAX_LINE = 64 * 1024 * 1024;

    private final Options options;
    private final int blockSize;
    private final List<PathMatcher> matchers = new ArrayList<>();
    private final List<FileResult> results = new ArrayList<>(); // sorted by path
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService pool;
    private final AtomicInteger filesFound = new AtomicInteger();
    private final AtomicInteger filesDone = new AtomicInteger();
    private final AtomicInteger filesSkipped = new AtomicInteger();
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicLong totalHits = new AtomicLong();
    private final AtomicLong keptHits = new AtomicLong();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean cancelled;
    private volatile boolean walking = true;
    private volatile boolean finished;
    private volatile long lastNotify;
    private long startNanos;

    public FolderSearch(Options options) {
        this(options, BLOCK);
    }

    FolderSearch(Options options, int blockSize) {
        this.options = options;
        this.blockSize = blockSize;
        options.query().toPattern(); // fail early on a bad regex
        for (String p : options.fileNames().split("[;,\\s]+")) {
            if (!p.isBlank()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + p.trim()));
            }
        }
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        pool = Executors.newFixedThreadPool(threads, LogDocument.daemon("lm-folder"));
    }

    public Options options() {
        return options;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public FolderSearch start() {
        startNanos = System.nanoTime();
        Thread walker = LogDocument.daemon("lm-folder-walk").newThread(this::walk);
        walker.start();
        return this;
    }

    // ---------------------------------------------------------------- results

    /** Files with at least one hit, in folder order. */
    public synchronized List<FileResult> results() {
        return List.copyOf(results);
    }

    public int filesFound() {
        return filesFound.get();
    }

    public int filesDone() {
        return filesDone.get();
    }

    /** Files skipped as binary or unreadable. */
    public int filesSkipped() {
        return filesSkipped.get();
    }

    public long bytesDone() {
        return bytesDone.get();
    }

    public long totalHits() {
        return totalHits.get();
    }

    public List<String> errors() {
        synchronized (errors) {
            return List.copyOf(errors);
        }
    }

    public boolean isRunning() {
        return !finished;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean awaitDone(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!finished) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    // ---------------------------------------------------------------- walking

    private void walk() {
        try {
            int depth = options.recursive() ? Integer.MAX_VALUE : 1;
            Files.walkFileTree(options.root(), EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    depth, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                            if (cancelled) {
                                return FileVisitResult.TERMINATE;
                            }
                            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            boolean hidden = name.startsWith(".") && !dir.equals(options.root());
                            return hidden ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            if (cancelled) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (a.isRegularFile() && wanted(file)) {
                                filesFound.incrementAndGet();
                                pool.execute(() -> searchFile(file));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            errors.add(file + ": " + e.getMessage());
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            errors.add(options.root() + ": " + e.getMessage());
        } finally {
            walking = false;
            pool.shutdown();
            try {
                pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished = true;
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            for (Listener l : listeners) {
                l.updated(this);
                l.finished(this, ms);
            }
        }
    }

    /** Rotation suffixes: app.log.1, app.log.2026-10-21, app.log.2026-10-21.3 */
    private static final java.util.regex.Pattern ROTATION =
            java.util.regex.Pattern.compile("\\.(\\d+|\\d{4}-\\d{2}-\\d{2}(\\.\\d+)?)$");

    /**
     * File name patterns. Rotated files count as the log they came from (app.log.1 and
     * app.log.2026-10-21 match *.log); with archives on, app.log.2.gz does too, and every zip
     * is opened (its entries are then matched by name).
     */
    boolean wanted(Path file) {
        String name = file.getFileName().toString();
        if (options.archives()) {
            if (isZip(file)) {
                return true;
            }
            if (isGzip(file)) {
                String inner = name.substring(0, name.length() - 3);
                if (nameOrRotatedMatches(inner)) {
                    return true;
                }
            }
        }
        return nameOrRotatedMatches(name);
    }

    private boolean nameOrRotatedMatches(String name) {
        if (nameMatches(name)) {
            return true;
        }
        String base = ROTATION.matcher(name).replaceFirst("");
        return !base.equals(name) && nameMatches(base);
    }

    private boolean nameMatches(String name) {
        if (matchers.isEmpty()) {
            return true;
        }
        Path p = Path.of(name);
        for (PathMatcher m : matchers) {
            if (m.matches(p)) {
                return true;
            }
        }
        return false;
    }

    static boolean isGzip(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".gz");
    }

    static boolean isZip(Path p) {
        return p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
    }

    // ---------------------------------------------------------------- searching

    private void searchFile(Path file) {
        if (cancelled) {
            return;
        }
        try {
            if (options.archives() && isZip(file)) {
                searchZip(file);
            } else {
                try (InputStream raw = Files.newInputStream(file)) {
                    InputStream in = options.archives() && isGzip(file)
                            ? new GZIPInputStream(new BufferedInputStream(raw, 1 << 16), 1 << 16)
                            : raw;
                    searchStream(new FileResult(file, null), in);
                }
            }
        } catch (IOException | RuntimeException e) {
            filesSkipped.incrementAndGet();
            errors.add(file + ": " + e.getMessage());
        } finally {
            filesDone.incrementAndGet();
            notifyUpdated();
        }
    }

    private void searchZip(Path file) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null && !cancelled) {
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (!e.isDirectory() && nameMatches(base)) {
                    // don't let the search close the zip stream at the end of an entry
                    InputStream entry = new FilterInputStream(zin) {
                        @Override
                        public void close() {}
                    };
                    searchStream(new FileResult(file, name), entry);
                }
            }
        }
    }

    /** Streams the content in blocks of whole lines through the matcher. */
    private void searchStream(FileResult result, InputStream in) throws IOException {
        LineMatcher matcher = LineMatcher.of(options.query());
        byte[] buf = new byte[blockSize];
        int len = 0;
        long line = 0;
        boolean first = true;
        boolean eof = false;
        LongList found = new LongList();
        while (!eof && !cancelled) {
            int n = in.read(buf, len, buf.length - len);
            if (n < 0) {
                eof = true;
            } else {
                len += n;
                bytesDone.addAndGet(n);
            }
            if (first && (len >= 8192 || eof)) {
                first = false;
                if (looksBinary(buf, Math.min(len, 8192))) {
                    filesSkipped.incrementAndGet();
                    return;
                }
            }
            int end;
            if (eof) {
                end = len;
            } else {
                int nl = ByteScan.lastIndexOf(buf, 0, len, (byte) '\n');
                if (nl < 0) {
                    if (len == buf.length) {
                        if (buf.length >= MAX_LINE) {
                            end = len; // a gigantic line: search it in pieces
                        } else {
                            buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                            continue;
                        }
                    } else {
                        continue;
                    }
                } else {
                    end = nl + 1;
                }
            }
            if (end == 0) {
                continue;
            }
            found.size = 0;
            matcher.scan(buf, end, line, l -> {
                found.add(l);
                return true;
            }, () -> cancelled);
            if (found.size > 0) {
                addHits(result, buf, end, line, found);
            }
            line += ByteScan.count(buf, 0, end, (byte) '\n');
            System.arraycopy(buf, end, buf, 0, len - end);
            len -= end;
        }
    }

    /** Adds hits of one block, with their text. */
    private void addHits(FileResult r, byte[] buf, int end, long firstLine, LongList found) {
        boolean firstHitInFile = r.hitCount() == 0;
        int pos = 0;
        long cur = firstLine;
        for (int i = 0; i < found.size; i++) {
            long target = found.a[i];
            if (target <= r.lastLine) {
                continue; // a gigantic line searched in pieces: count it once
            }
            r.lastLine = target;
            while (cur < target) {
                pos = ByteScan.indexOf(buf, pos, end, (byte) '\n') + 1;
                cur++;
            }
            int nl = ByteScan.indexOf(buf, pos, end, (byte) '\n');
            int lineEnd = nl < 0 ? end : nl;
            if (lineEnd > pos && buf[lineEnd - 1] == '\r') {
                lineEnd--;
            }
            boolean keep = r.keptHits() < MAX_KEPT_PER_FILE
                    && keptHits.get() < MAX_KEPT_TOTAL;
            String text = null;
            if (keep) {
                text = new String(buf, pos, Math.min(lineEnd - pos, MAX_TEXT * 4),
                        StandardCharsets.UTF_8);
                if (text.length() > MAX_TEXT) {
                    text = text.substring(0, MAX_TEXT);
                }
                keptHits.incrementAndGet();
            }
            r.add(new Hit(target, text), keep);
            totalHits.incrementAndGet();
        }
        if (firstHitInFile) {
            synchronized (this) {
                int i = 0;
                while (i < results.size() && compare(results.get(i), r) < 0) {
                    i++;
                }
                results.add(i, r);
            }
        }
        notifyUpdated();
    }

    /** By path (folder by folder, ignoring case), then by name inside the zip. */
    private static int compare(FileResult a, FileResult b) {
        int c = comparePaths(a.file, b.file);
        if (c != 0) {
            return c;
        }
        return String.valueOf(a.entry).compareToIgnoreCase(String.valueOf(b.entry));
    }

    private static int comparePaths(Path a, Path b) {
        int n = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < n; i++) {
            boolean aLast = i == a.getNameCount() - 1;
            boolean bLast = i == b.getNameCount() - 1;
            if (aLast != bLast) {
                return aLast ? -1 : 1; // files before subfolders
            }
            int c = a.getName(i).toString().compareToIgnoreCase(b.getName(i).toString());
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    }

    /** Like grep: a NUL byte near the start means binary. */
    private static boolean looksBinary(byte[] buf, int len) {
        return ByteScan.indexOf(buf, 0, len, (byte) 0) >= 0;
    }

    private void notifyUpdated() {
        long now = System.nanoTime();
        if (now - lastNotify < 80_000_000L) {
            return;
        }
        lastNotify = now;
        for (Listener l : listeners) {
            l.updated(this);
        }
    }

    @Override
    public void close() {
        cancelled = true;
        pool.shutdown();
    }

    boolean isWalking() {
        return walking;
    }

    private static final class LongList {
        long[] a = new long[64];
        int size;

        void add(long v) {
            if (size == a.length) {
                a = java.util.Arrays.copyOf(a, size * 2);
            }
            a[size++] = v;
        }
    }
}
