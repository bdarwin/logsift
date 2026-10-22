package logsift.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small LRU cache of fixed-size file pages, used for random access while displaying lines.
 * Bulk work (indexing, searching) reads the file directly and does not go through this cache.
 */
final class PageCache {
    static final int PAGE_SIZE = 64 * 1024;

    private record Page(byte[] data, int length) {}

    /** Thrown when a page is not in memory and the caller must not wait for the disk. */
    static final class NotLoaded extends RuntimeException {
        private NotLoaded() {
            super("not loaded", null, false, false);
        }
    }

    static final NotLoaded NOT_LOADED = new NotLoaded();

    private final FileSource source;
    private final Map<Long, Page> pages;

    PageCache(FileSource source, int maxPages) {
        this.source = source;
        this.pages = new LinkedHashMap<>(maxPages * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Page> eldest) {
                return size() > maxPages;
            }
        };
    }

    synchronized void clear() {
        pages.clear();
    }

    /**
     * Returns the page with number {@code pageNo}, valid up to {@code limit} (the size the caller
     * believes the file has). A cached page that is shorter than needed is read again, because
     * the file may have grown since. The disk is read outside the lock, so a slow read (a
     * network drive) never holds up others; {@code cachedOnly} throws {@link #NOT_LOADED}
     * instead of reading.
     */
    private Page page(long pageNo, long limit, boolean cachedOnly) {
        long start = pageNo * PAGE_SIZE;
        long wanted = Math.min(PAGE_SIZE, limit - start);
        Page p;
        synchronized (this) {
            p = pages.get(pageNo);
        }
        if (p != null && p.length >= wanted) {
            return p;
        }
        if (cachedOnly) {
            throw NOT_LOADED;
        }
        byte[] data = new byte[PAGE_SIZE];
        int n;
        try {
            n = source.read(start, data, 0, PAGE_SIZE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        p = new Page(data, n);
        synchronized (this) {
            Page now = pages.get(pageNo);
            if (now == null || now.length < n) {
                pages.put(pageNo, p);
            }
        }
        return p;
    }

    long indexOf(long from, long limit, byte b) {
        return indexOf(from, limit, b, false);
    }

    /**
     * Position of the first {@code b} in {@code [from, limit)}, or -1 if there is none.
     */
    long indexOf(long from, long limit, byte b, boolean cachedOnly) {
        long pos = from;
        while (pos < limit) {
            long pageNo = pos / PAGE_SIZE;
            Page p = page(pageNo, limit, cachedOnly);
            long pageStart = pageNo * PAGE_SIZE;
            int startInPage = (int) (pos - pageStart);
            int endInPage = (int) Math.min(p.length, limit - pageStart);
            if (endInPage <= startInPage) {
                return -1; // file is shorter than expected
            }
            int i = ByteScan.indexOf(p.data, startInPage, endInPage, b);
            if (i >= 0) {
                return pageStart + i;
            }
            pos = pageStart + endInPage;
        }
        return -1;
    }

    int read(long pos, byte[] dst, int off, int len) {
        return read(pos, dst, off, len, false);
    }

    /** Copies bytes {@code [pos, pos+len)} into {@code dst}; returns the number copied. */
    int read(long pos, byte[] dst, int off, int len, boolean cachedOnly) {
        int done = 0;
        long limit = pos + len;
        while (done < len) {
            long cur = pos + done;
            long pageNo = cur / PAGE_SIZE;
            Page p = page(pageNo, limit, cachedOnly);
            long pageStart = pageNo * PAGE_SIZE;
            int inPage = (int) (cur - pageStart);
            int n = Math.min(p.length - inPage, len - done);
            if (n <= 0) {
                break;
            }
            System.arraycopy(p.data, inPage, dst, off + done, n);
            done += n;
        }
        return done;
    }
}
