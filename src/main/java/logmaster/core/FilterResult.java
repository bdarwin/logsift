package logmaster.core;

import java.util.Arrays;

/**
 * The lines that pass the filters, as an immutable snapshot.
 *
 * <p>Two sets are kept: the lines that <em>match</em>, and the lines <em>shown</em> (the
 * matches plus any context lines around them). Each is stored either as a sorted list, or (for
 * formulas like {@code NOT C}) as the lines {@code [0, universe)} minus a sorted list, so
 * hiding a few lines never needs a list of all the others.
 *
 * <p>An optional window {@code [start, end)} (a time range) limits both sets: lines outside it
 * are never shown, and "all except" means all lines of the window except the list.
 */
public final class FilterResult {

    /** A sorted list of lines, or every line in [0, universe) except the list. */
    record Lines(long[] a, int n, boolean comp) {
        static final Lines NONE_EXCLUDED = new Lines(new long[0], 0, true);
    }

    private final int version;
    private final boolean filtering;
    private final long universe;
    private final long start;
    private final long end;
    private final Lines shown;
    private final Lines matched;
    private final boolean complete;

    FilterResult(int version, boolean filtering, long universe, Lines shown, Lines matched,
                 boolean complete) {
        this(version, filtering, universe, 0, Long.MAX_VALUE, shown, matched, complete);
    }

    FilterResult(int version, boolean filtering, long universe, long start, long end, Lines shown,
                 Lines matched, boolean complete) {
        this.version = version;
        this.filtering = filtering;
        this.universe = universe;
        this.start = start;
        this.end = end;
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
        return l.comp ? Math.max(0, Math.min(universe, end) - start) - l.n : l.n;
    }

    /** First line of the time window (0 without one). */
    public long windowStart() {
        return start;
    }

    /** End of the time window (exclusive), or Long.MAX_VALUE without one. */
    public long windowEnd() {
        return end;
    }

    /** Line number of the {@code row}-th shown line. */
    public long lineAt(long row) {
        Lines l = shown;
        if (!l.comp) {
            return l.a[(int) row];
        }
        // Lines excluded before the answer: the largest k with a[k-1] - start - (k-1) <= row.
        int lo = 0;
        int hi = l.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (l.a[mid] - start - mid <= row) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return start + row + lo;
    }

    /** Row of the first shown line at or after {@code line}; {@link #count()} if none. */
    public long rowAtOrAfter(long line) {
        line = Math.max(line, start);
        if (line >= universe || line >= end) {
            return count();
        }
        Lines l = shown;
        int i = Arrays.binarySearch(l.a, 0, l.n, line);
        int before = i >= 0 ? i : -i - 1;
        return l.comp ? line - start - before : before;
    }

    /** Receives runs of consecutive lines: {@code [from, to)}. */
    public interface RunConsumer {
        void run(long from, long to) throws java.io.IOException;
    }

    /**
     * Calls {@code out} for each run of consecutive shown lines, in order, up to
     * {@code lineCount} (lines beyond what was checked count as shown).
     */
    public void forEachRun(long lineCount, RunConsumer out) throws java.io.IOException {
        Lines l = shown;
        long lo = start;
        long hi = Math.min(end, lineCount);
        if (!l.comp) {
            int i = 0;
            while (i < l.n) {
                long a = l.a[i];
                int j = i;
                while (j + 1 < l.n && l.a[j + 1] == l.a[j] + 1) {
                    j++;
                }
                out.run(a, l.a[j] + 1);
                i = j + 1;
            }
            if (universe < hi) {
                out.run(Math.max(lo, universe), hi); // not checked yet: shown
            }
            return;
        }
        long next = lo;
        for (int i = 0; i < l.n; i++) {
            long e = l.a[i];
            if (e > next) {
                out.run(next, Math.min(e, hi));
            }
            next = Math.max(next, e + 1);
        }
        if (next < hi) {
            out.run(next, hi);
        }
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
        if (line < start || line >= end) {
            return false;
        }
        if (line >= universe) {
            return true;
        }
        boolean inList = Arrays.binarySearch(l.a, 0, l.n, line) >= 0;
        return inList != l.comp;
    }
}
