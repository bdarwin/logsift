package logsift.core.diff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * What a {@link TableDiff} found: one item per row (or pair of rows) in display order, each with
 * its status, its left and right row, and which compared columns differ. Rows' values are read
 * again from the files when asked for (the last few thousand are kept).
 */
public final class TableDiffResult {
    private final CsvFile left;
    private final CsvFile right;
    private final CsvFile.RowIndex leftIndex;
    private final CsvFile.RowIndex rightIndex;
    private final TableDiff.Spec spec;
    private final int[] compared;     // pair indices compared, in order
    private final byte[] status;
    private final int[] leftRow;
    private final int[] rightRow;
    private final int[] changeStart;  // item i's changes: changes[changeStart[i] .. changeStart[i + 1])
    private final int[] changes;      // indices into compared
    private final int size;
    private final int[] counts = new int[DiffStatus.values().length];
    private final int[] changedRows;  // per compared column
    private final int duplicateKeys;
    private final long millis;

    private TableDiffResult(Builder b, long millis) {
        this.left = b.left;
        this.right = b.right;
        this.leftIndex = b.li;
        this.rightIndex = b.ri;
        this.spec = b.spec;
        this.compared = b.compared;
        this.size = b.size;
        this.status = Arrays.copyOf(b.status, size);
        this.leftRow = Arrays.copyOf(b.leftRow, size);
        this.rightRow = Arrays.copyOf(b.rightRow, size);
        this.changeStart = Arrays.copyOf(b.changeStart, size + 1);
        this.changes = Arrays.copyOf(b.changes, b.changeCount);
        this.changedRows = new int[compared.length];
        for (int i = 0; i < size; i++) {
            counts[status[i]]++;
        }
        for (int i = 0; i < b.changeCount; i++) {
            changedRows[changes[i]]++;
        }
        this.duplicateKeys = b.duplicateKeys;
        this.millis = millis;
    }

    /** Collects items in order (for {@link TableDiff}). */
    static final class Builder {
        final CsvFile left;
        final CsvFile right;
        final CsvFile.RowIndex li;
        final CsvFile.RowIndex ri;
        final TableDiff.Spec spec;
        final int[] compared;
        byte[] status = new byte[1024];
        int[] leftRow = new int[1024];
        int[] rightRow = new int[1024];
        int[] changeStart = new int[1025];
        int[] changes = new int[1024];
        int size;
        int changeCount;
        int duplicateKeys;

        Builder(CsvFile left, CsvFile right, CsvFile.RowIndex li, CsvFile.RowIndex ri, TableDiff.Spec spec,
                int[] compared) {
            this.left = left;
            this.right = right;
            this.li = li;
            this.ri = ri;
            this.spec = spec;
            this.compared = compared;
        }

        void add(DiffStatus s, int l, int r, int[] changed, int n) {
            if (size == status.length) {
                int c = size + (size >> 1);
                status = Arrays.copyOf(status, c);
                leftRow = Arrays.copyOf(leftRow, c);
                rightRow = Arrays.copyOf(rightRow, c);
                changeStart = Arrays.copyOf(changeStart, c + 1);
            }
            if (changeCount + n > changes.length) {
                changes = Arrays.copyOf(changes, Math.max(changes.length * 2, changeCount + n));
            }
            status[size] = (byte) s.ordinal();
            leftRow[size] = l;
            rightRow[size] = r;
            for (int i = 0; i < n; i++) {
                changes[changeCount++] = changed[i];
            }
            size++;
            changeStart[size] = changeCount;
        }

        void duplicateKeys(int n) {
            duplicateKeys = n;
        }

        TableDiffResult build(long millis) {
            return new TableDiffResult(this, millis);
        }
    }

    public CsvFile left() {
        return left;
    }

    public CsvFile right() {
        return right;
    }

    public TableDiff.Spec spec() {
        return spec;
    }

    public ColumnMap columns() {
        return spec.columns();
    }

    /** Whether rows were matched by key (else lined up in file order). */
    public boolean keyed() {
        return !spec.keys().isEmpty();
    }

    /** The pairs whose values were compared (not keys, not ignored), in order. */
    public int[] compared() {
        return compared.clone();
    }

    public int size() {
        return size;
    }

    public DiffStatus status(int item) {
        return DiffStatus.values()[status[item]];
    }

    /** The item's row in the left file (0-based, the header not counted), or -1. */
    public int leftRow(int item) {
        return leftRow[item];
    }

    public int rightRow(int item) {
        return rightRow[item];
    }

    /** The line (0-based) of the item's left row in its file, or -1. */
    public long leftLine(int item) {
        return leftRow[item] < 0 ? -1 : leftIndex.line(leftRow[item]);
    }

    public long rightLine(int item) {
        return rightRow[item] < 0 ? -1 : rightIndex.line(rightRow[item]);
    }

    public int leftRows() {
        return leftIndex.size();
    }

    public int rightRows() {
        return rightIndex.size();
    }

    /** How many items have this status. */
    public int count(DiffStatus s) {
        return counts[s.ordinal()];
    }

    /** Distinct keys found more than once (in either file). */
    public int duplicateKeys() {
        return duplicateKeys;
    }

    public long millis() {
        return millis;
    }

    /** The pairs (indices into {@code columns().pairs()}) whose values differ in this item. */
    public int[] changedPairs(int item) {
        int[] out = new int[changeStart[item + 1] - changeStart[item]];
        for (int i = 0; i < out.length; i++) {
            out[i] = compared[changes[changeStart[item] + i]];
        }
        return out;
    }

    /** Whether pair {@code pair}'s values differ in this item. */
    public boolean isChanged(int item, int pair) {
        for (int i = changeStart[item]; i < changeStart[item + 1]; i++) {
            if (compared[changes[i]] == pair) {
                return true;
            }
        }
        return false;
    }

    /** How many rows have a different value in pair {@code pair} (0 for pairs not compared). */
    public int changedRows(int pair) {
        for (int i = 0; i < compared.length; i++) {
            if (compared[i] == pair) {
                return changedRows[i];
            }
        }
        return 0;
    }

    /** Compared columns with changes, most changed first: pair index → rows. */
    public Map<Integer, Integer> changesPerColumn() {
        Integer[] order = new Integer[compared.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> changedRows[b] != changedRows[a] ? Integer.compare(changedRows[b], changedRows[a])
                : Integer.compare(a, b));
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int i : order) {
            if (changedRows[i] > 0) {
                out.put(compared[i], changedRows[i]);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- values

    private final Map<Long, String[]> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String[]> e) {
            return size() > 4096;
        }
    };

    /** The left row's fields (as many as the left header has), or null if none. */
    public String[] leftValues(int item) {
        return row(true, leftRow[item]);
    }

    public String[] rightValues(int item) {
        return row(false, rightRow[item]);
    }

    private String[] row(boolean isLeft, int row) {
        if (row < 0) {
            return null;
        }
        long key = isLeft ? row : -1L - row;
        synchronized (cache) {
            String[] v = cache.get(key);
            if (v != null) {
                return v;
            }
        }
        String[] v;
        try {
            v = isLeft ? left.read(leftIndex, row) : right.read(rightIndex, row);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        synchronized (cache) {
            cache.put(key, v);
        }
        return v;
    }

    /** Pair {@code pair}'s value in the item's left row (null: no left row, or no value). */
    public String leftValue(int item, int pair) {
        String[] v = leftValues(item);
        return v == null ? null : v[columns().pairs().get(pair).left()];
    }

    public String rightValue(int item, int pair) {
        String[] v = rightValues(item);
        return v == null ? null : v[columns().pairs().get(pair).right()];
    }

    /** The item's key as shown: its key values (the left row's, else the right's), joined. */
    public String keyText(int item) {
        List<String> parts = new ArrayList<>();
        for (int k : spec.keys()) {
            String v = leftRow[item] >= 0 ? leftValue(item, k) : rightValue(item, k);
            parts.add(v == null ? "" : v);
        }
        return String.join(" · ", parts);
    }

    // ---------------------------------------------------------------- choosing items

    /** Items with one of these statuses (all if null), changed in pair {@code pair} if it is not -1. */
    public int[] filter(Set<DiffStatus> statuses, int pair) {
        Set<DiffStatus> want = statuses == null ? EnumSet.allOf(DiffStatus.class) : statuses;
        int ci = -1;
        if (pair >= 0) {
            for (int i = 0; i < compared.length; i++) {
                if (compared[i] == pair) {
                    ci = i;
                }
            }
            if (ci < 0) {
                return new int[0];
            }
        }
        int[] out = new int[size];
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (!want.contains(DiffStatus.values()[status[i]])) {
                continue;
            }
            if (ci >= 0) {
                boolean hit = false;
                for (int k = changeStart[i]; k < changeStart[i + 1] && !hit; k++) {
                    hit = changes[k] == ci;
                }
                if (!hit) {
                    continue;
                }
            }
            out[n++] = i;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * The items among {@code items} with {@code text} (any case) in a value of either row.
     *
     * @throws CancellationException when {@code cancelled} says so
     */
    public int[] search(int[] items, String text, BooleanSupplier cancelled) {
        String q = text.toLowerCase(Locale.ROOT);
        int[] out = new int[items.length];
        int n = 0;
        for (int j = 0; j < items.length; j++) {
            if ((j & 1023) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            int i = items[j];
            if (contains(leftValues(i), q) || contains(rightValues(i), q)) {
                out[n++] = i;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean contains(String[] values, String q) {
        if (values == null) {
            return false;
        }
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- words

    /** "Column region only in the right file", once per column. */
    public List<String> notes() {
        List<String> out = new ArrayList<>();
        ColumnMap m = columns();
        for (int i : m.leftOnly()) {
            out.add("Column " + m.leftNames().get(i) + " only in the left file (" + left.name() + ")");
        }
        for (int i : m.rightOnly()) {
            out.add("Column " + m.rightNames().get(i) + " only in the right file (" + right.name() + ")");
        }
        for (int i : spec.ignored()) {
            if (i < m.pairs().size()) {
                out.add("Column " + m.name(i) + " not compared (ignored)");
            }
        }
        return out;
    }

    /** How rows were matched, in words: "by order_id" or "in file order". */
    public String matchedBy() {
        if (!keyed()) {
            return spec.options().ignoreRowOrder() ? "equal rows matched wherever they are" : "lined up in file order";
        }
        List<String> names = new ArrayList<>();
        for (int k : spec.keys()) {
            names.add(columns().name(k));
        }
        return "by key " + String.join(" + ", names);
    }

    /** A short text summary (for the clipboard or a file). */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Compared %s (%,d rows) with %s (%,d rows), %s.%n", left.name(),
                leftRows(), right.name(), rightRows(), matchedBy()));
        sb.append(String.format(Locale.ROOT, "Added: %,d   Removed: %,d   Changed: %,d   Same: %,d   Duplicate keys: %,d%n",
                count(DiffStatus.ADDED), count(DiffStatus.REMOVED), count(DiffStatus.CHANGED), count(DiffStatus.SAME),
                duplicateKeys));
        Map<Integer, Integer> per = changesPerColumn();
        if (!per.isEmpty()) {
            List<String> parts = new ArrayList<>();
            per.forEach((pair, rows) -> parts.add(String.format(Locale.ROOT, "%s: %,d", columns().name(pair), rows)));
            sb.append("Changes per column: ").append(String.join(", ", parts)).append(System.lineSeparator());
        }
        for (String n : notes()) {
            sb.append(n).append('.').append(System.lineSeparator());
        }
        return sb.toString();
    }
}
