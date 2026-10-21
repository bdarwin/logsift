package logmaster.core;

import java.util.Arrays;

/**
 * The lines that pass the filters, as an immutable snapshot.
 *
 * <p>Two sets are kept: the lines that <em>match</em>, and the lines <em>shown</em> (the
 * matches plus any context lines around them). Each is stored either as a sorted list, or (for
 * formulas like {@code NOT C}) as the lines {@code [0, universe)} minus a sorted list, so
 * hiding a few lines never needs a list of all the others.
 */
public final class FilterResult {

    /** A sorted list of lines, or every line in [0, universe) except the list. */
    record Lines(long[] a, int n, boolean comp) {
        static final Lines NONE_EXCLUDED = new Lines(new long[0], 0, true);
    }

    private final int version;
    private final boolean filtering;
    private final long universe;
    private final Lines shown;
    private final Lines matched;
    private final boolean complete;

    FilterResult(int version, boolean filtering, long universe, Lines shown, Lines matched,
                 boolean complete) {
        this.version = version;
        this.filtering = filtering;
        this.universe = universe;
        this.shown = shown;
        this.matched = matched;
        this.complete = complete;
    }

    static FilterResult none(int version, long lineCount) {
        return new FilterResult(version, false, lineCount, Lines.NONE_EXCLUDED, Lines.NONE_EXCLUDED,
                true);
    }

    /** Changes whenever the filters, formula or options change (not when the file grows). */
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

    /** Number of shown lines. */
    public long count() {
        return count(shown);
    }

    /** Number of matching lines (without context lines). */
    public long matchCount() {
        return count(matched);
    }

    private long count(Lines l) {
        return l.comp ? universe - l.n : l.n;
    }

    /** Line number of the {@code row}-th shown line. */
    public long lineAt(long row) {
        Lines l = shown;
        if (!l.comp) {
            return l.a[(int) row];
        }
        // Lines excluded before the answer: the largest k with a[k-1] - (k-1) <= row.
        int lo = 0;
        int hi = l.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (l.a[mid] - mid <= row) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return row + lo;
    }

    /** Row of the first shown line at or after {@code line}; {@link #count()} if none. */
    public long rowAtOrAfter(long line) {
        if (line >= universe) {
            return count();
        }
        Lines l = shown;
        int i = Arrays.binarySearch(l.a, 0, l.n, line);
        int before = i >= 0 ? i : -i - 1;
        return l.comp ? line - before : before;
    }

    /** Whether {@code line} is shown (lines not yet checked count as shown). */
    public boolean contains(long line) {
        return in(shown, line);
    }

    /** Whether {@code line} matches the filters (not just shown as context). */
    public boolean isMatch(long line) {
        return in(matched, line);
    }

    private boolean in(Lines l, long line) {
        if (line >= universe) {
            return true;
        }
        boolean inList = Arrays.binarySearch(l.a, 0, l.n, line) >= 0;
        return inList != l.comp;
    }
}
