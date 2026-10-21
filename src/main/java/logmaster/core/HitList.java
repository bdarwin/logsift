package logmaster.core;

import java.util.Arrays;

/**
 * Growing, sorted list of matching line numbers. One thread appends; any thread may read.
 */
public final class HitList {
    private volatile long[] data = new long[1024];
    private volatile int size;

    public int size() {
        return size;
    }

    public long get(int i) {
        int n = size;
        if (i < 0 || i >= n) {
            throw new IndexOutOfBoundsException(i);
        }
        return data[i];
    }

    /** Index of the first hit with line >= {@code line}; {@code size()} if none. */
    public int firstAtOrAfter(long line) {
        long[] d = data;
        int n = Math.min(size, d.length);
        int i = Arrays.binarySearch(d, 0, n, line);
        return i >= 0 ? i : -i - 1;
    }

    /**
     * The backing array; entries {@code [0, size())} read before this call are valid. Read
     * {@link #size()} first. Callers must not modify it.
     */
    long[] array() {
        return data;
    }

    void add(long line) {
        int n = size;
        long[] d = data;
        if (n == d.length) {
            d = Arrays.copyOf(d, n * 2);
            data = d;
        }
        d[n] = line;
        size = n + 1;
    }

    /** Removes all hits at or after {@code line}. */
    void truncateFrom(long line) {
        size = firstAtOrAfter(line);
    }

    void clear() {
        size = 0;
        data = new long[1024];
    }
}
