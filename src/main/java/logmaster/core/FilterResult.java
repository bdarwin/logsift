package logmaster.core;

import java.util.Arrays;

/**
 * The lines that pass the filters, as an immutable snapshot. Stored either as a sorted list of
 * the passing lines, or (for formulas like {@code NOT C}) as the lines {@code [0, universe)}
 * minus a sorted list, so hiding a few lines never needs a list of all the others.
 */
public final class FilterResult {
    private final int version;
    private final boolean filtering;
    private final long universe;
    private final long[] list;
    private final int size;
    private final boolean complement;
    private final boolean complete;

    FilterResult(int version, boolean filtering, long universe, long[] list, int size,
                 boolean complement, boolean complete) {
        this.version = version;
        this.filtering = filtering;
        this.universe = universe;
        this.list = list;
        this.size = size;
        this.complement = complement;
        this.complete = complete;
    }

    static FilterResult none(int version, long lineCount) {
        return new FilterResult(version, false, lineCount, new long[0], 0, true, true);
    }

    /** Changes whenever the filters or formula change (not when the file grows). */
    public int version() {
        return version;
    }

    /** False when no formula is active, so every line passes. */
    public boolean isFiltering() {
        return filtering;
    }

    /** Lines that have been checked: {@code [0, checkedLines())}. */
    public long checkedLines() {
        return universe;
    }

    /** True when every filter has searched every known line. */
    public boolean isComplete() {
        return complete;
    }

    /** Number of passing lines. */
    public long count() {
        return complement ? universe - size : size;
    }

    /** Line number of the {@code row}-th passing line. */
    public long lineAt(long row) {
        if (!complement) {
            return list[(int) row];
        }
        // Lines excluded before the answer: the largest k with list[k-1] - (k-1) <= row.
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list[mid] - mid <= row) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return row + lo;
    }

    /** Row of the first passing line at or after {@code line}; {@link #count()} if none. */
    public long rowAtOrAfter(long line) {
        if (line >= universe) {
            return count();
        }
        int i = Arrays.binarySearch(list, 0, size, line);
        if (!complement) {
            return i >= 0 ? i : -i - 1;
        }
        int excludedBefore = i >= 0 ? i : -i - 1;
        long row = line - excludedBefore;
        // if this line is excluded, the next passing line has the same row number
        return row;
    }

    /** Whether {@code line} passes (lines not yet checked count as passing). */
    public boolean contains(long line) {
        if (line >= universe) {
            return true;
        }
        boolean inList = Arrays.binarySearch(list, 0, size, line) >= 0;
        return inList != complement;
    }
}
