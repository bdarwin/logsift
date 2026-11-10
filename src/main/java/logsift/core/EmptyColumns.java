package logsift.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A table's columns that have no value in any row: the empty columns an export adds (a separator
 * too many on every line, a column named but never filled).
 */
public final class EmptyColumns {
    private EmptyColumns() {}

    /** Rows read at a time. */
    private static final int BLOCK = 512;

    /**
     * The columns (names, as {@link LogFormat#columns()}) empty in every row of the first
     * {@code lines} lines; null when stopped. Stops early once every column has a value.
     */
    public static List<String> find(LogFormat format, ColumnProfile.Lines rows, long lines, BooleanSupplier stop) {
        int n = format.columns().size();
        BitSet filled = new BitSet(n);
        String[] block = new String[BLOCK];
        boolean anyRow = false;
        for (long first = 0; first < lines && filled.cardinality() < n; first += BLOCK) {
            if (stop.getAsBoolean()) {
                return null;
            }
            int count = (int) Math.min(BLOCK, lines - first);
            java.util.Arrays.fill(block, null);
            rows.read(first, count, block);
            for (int i = 0; i < count; i++) {
                String[] v = block[i] == null ? null : format.split(block[i], null);
                if (v == null) {
                    continue;
                }
                anyRow = true;
                for (int c = 0; c < v.length && c < n; c++) {
                    if (v[c] != null && !v[c].isBlank()) {
                        filled.set(c);
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        if (!anyRow) {
            return out; // no rows: nothing is known to be empty
        }
        for (int c = filled.nextClearBit(0); c < n; c = filled.nextClearBit(c + 1)) {
            out.add(format.columns().get(c));
        }
        return out;
    }
}
