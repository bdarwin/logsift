package logsift.core.diff;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Next and previous change in a list of items (rows of a table, lines of text) where some
 * items are changes. With {@code blocks}, a run of changed items one after the other is one
 * stop (a hunk of text); without, every changed item is (a changed row).
 */
public final class ChangeNavigator {
    private final int[] stops;

    private ChangeNavigator(int[] stops) {
        this.stops = stops;
    }

    public static ChangeNavigator of(int size, IntPredicate isChange, boolean blocks) {
        int[] s = new int[16];
        int n = 0;
        boolean before = false;
        for (int i = 0; i < size; i++) {
            boolean c = isChange.test(i);
            if (c && !(blocks && before)) {
                if (n == s.length) {
                    s = Arrays.copyOf(s, n * 2);
                }
                s[n++] = i;
            }
            before = c;
        }
        return new ChangeNavigator(Arrays.copyOf(s, n));
    }

    /** How many stops. */
    public int count() {
        return stops.length;
    }

    /** The first stop after {@code position} (-1: none; {@code -1} as position: the first). */
    public int next(int position) {
        int i = Arrays.binarySearch(stops, position);
        i = i >= 0 ? i + 1 : -i - 1;
        return i < stops.length ? stops[i] : -1;
    }

    /** The last stop before {@code position} (-1: none). */
    public int previous(int position) {
        int i = Arrays.binarySearch(stops, position);
        i = i >= 0 ? i - 1 : -i - 2;
        return i >= 0 ? stops[i] : -1;
    }

    /** Which stop (1-based) holds {@code position}, the last one before it, or 0. */
    public int ordinal(int position) {
        int i = Arrays.binarySearch(stops, position);
        return i >= 0 ? i + 1 : -i - 1;
    }
}
