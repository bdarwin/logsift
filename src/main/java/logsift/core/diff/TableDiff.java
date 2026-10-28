package logsift.core.diff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Compares two CSV files row by row.
 *
 * <p>With keys, rows are matched by their key wherever they are in the files: a hash join on
 * the key (two 64-bit hashes of it, after the options' spaces and case). Each file is read once,
 * keeping per row only its key hashes, a hash of the values compared and where it is; rows whose
 * hashes differ are read again and compared value by value (numbers within the tolerance can
 * still be equal). A key found more than once in a file is a duplicate: its rows are listed, on
 * both sides, and never matched.
 *
 * <p>Without keys, rows are lined up in file order with {@link SequenceDiff} over the row hashes
 * (or, with {@link DiffOptions#ignoreRowOrder}, equal rows are matched wherever they are).
 */
public final class TableDiff {
    private TableDiff() {}

    /**
     * What to compare.
     *
     * @param columns which columns are compared with which
     * @param keys the pairs (indices into {@code columns.pairs()}) that make a row's key; empty:
     *             rows are lined up in file order
     * @param ignored pairs not compared
     * @param options when values are equal
     */
    public record Spec(ColumnMap columns, List<Integer> keys, Set<Integer> ignored, DiffOptions options) {
        public Spec {
            keys = List.copyOf(keys);
            ignored = Set.copyOf(ignored);
        }

        /** The pairs whose values are compared: not keys, not ignored. */
        public int[] compared() {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < columns.pairs().size(); i++) {
                if (!keys.contains(i) && !ignored.contains(i)) {
                    out.add(i);
                }
            }
            return out.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** One file's rows as read: where each is and its hashes. */
    private static final class Side {
        CsvFile.RowIndex index;
        long[] k1 = new long[1024];
        long[] k2 = new long[1024];
        long[] content = new long[1024];
        int size;

        void add(long a, long b, long c) {
            if (size == k1.length) {
                int n = size + (size >> 1);
                k1 = Arrays.copyOf(k1, n);
                k2 = Arrays.copyOf(k2, n);
                content = Arrays.copyOf(content, n);
            }
            k1[size] = a;
            k2[size] = b;
            content[size] = c;
            size++;
        }

        /** An open-addressing table of rows by key: the first row of each key. */
        int[] table;
        BitSet dup = new BitSet();

        void buildTable() {
            int cap = Integer.highestOneBit(Math.max(4, size * 2 - 1)) << 1;
            table = new int[cap];
            Arrays.fill(table, -1);
            int mask = cap - 1;
            for (int row = 0; row < size; row++) {
                int slot = (int) k1[row] & mask;
                while (true) {
                    int r = table[slot];
                    if (r < 0) {
                        table[slot] = row;
                        break;
                    }
                    if (k1[r] == k1[row] && k2[r] == k2[row]) {
                        dup.set(r);
                        dup.set(row);
                        break;
                    }
                    slot = (slot + 1) & mask;
                }
            }
        }

        /** The first row with this key, or -1. */
        int find(long a, long b) {
            int mask = table.length - 1;
            int slot = (int) a & mask;
            while (true) {
                int r = table[slot];
                if (r < 0) {
                    return -1;
                }
                if (k1[r] == a && k2[r] == b) {
                    return r;
                }
                slot = (slot + 1) & mask;
            }
        }
    }

    /**
     * Compares {@code left} (the older file) with {@code right}.
     *
     * @throws CancellationException when {@code cancelled} says so
     */
    public static TableDiffResult compare(CsvFile left, CsvFile right, Spec spec, DoubleConsumer progress,
                                          BooleanSupplier cancelled) throws IOException {
        long t0 = System.nanoTime();
        DoubleConsumer p = progress != null ? progress : f -> { };
        BooleanSupplier stop = cancelled != null ? cancelled : () -> false;
        List<ColumnMap.Pair> pairs = spec.columns().pairs();
        int[] cmp = spec.compared();
        int[] keyL = spec.keys().stream().mapToInt(k -> pairs.get(k).left()).toArray();
        int[] keyR = spec.keys().stream().mapToInt(k -> pairs.get(k).right()).toArray();
        int[] cmpL = Arrays.stream(cmp).map(k -> pairs.get(k).left()).toArray();
        int[] cmpR = Arrays.stream(cmp).map(k -> pairs.get(k).right()).toArray();
        DiffOptions o = spec.options();
        Side l = read(left, keyL, cmpL, o, f -> p.accept(f * 0.45), stop);
        Side r = read(right, keyR, cmpR, o, f -> p.accept(0.45 + f * 0.45), stop);
        TableDiffResult.Builder b = new TableDiffResult.Builder(left, right, l.index, r.index, spec, cmp);
        Comparer c = new Comparer(left, right, l.index, r.index, cmpL, cmpR, o, stop);
        if (!spec.keys().isEmpty()) {
            keyed(l, r, b, c, p, stop);
        } else if (o.ignoreRowOrder()) {
            unordered(l, r, b, stop);
        } else {
            inOrder(l, r, b, c, p, stop);
        }
        p.accept(1);
        return b.build((System.nanoTime() - t0) / 1_000_000);
    }

    private static Side read(CsvFile f, int[] key, int[] cmp, DiffOptions o, DoubleConsumer progress,
                             BooleanSupplier stop) throws IOException {
        Side s = new Side();
        s.index = f.scan((row, v) -> {
            long a = Hashes.SEED1;
            long b = Hashes.SEED2;
            for (int k : key) {
                String part = o.keyOf(v[k]);
                a = Hashes.add1(a, part);
                b = Hashes.add2(b, part);
            }
            long h = Hashes.SEED1;
            for (int c : cmp) {
                h = Hashes.add1(h, o.canonical(v[c]));
            }
            s.add(a, b, h);
            return true;
        }, progress, stop);
        return s;
    }

    /** Reads matched rows again and finds the values that differ. */
    private static final class Comparer {
        private final CsvFile left;
        private final CsvFile right;
        private final CsvFile.RowIndex li;
        private final CsvFile.RowIndex ri;
        private final int[] cmpL;
        private final int[] cmpR;
        private final DiffOptions o;
        private final BooleanSupplier stop;
        private final int[] scratch;
        private int count;

        Comparer(CsvFile left, CsvFile right, CsvFile.RowIndex li, CsvFile.RowIndex ri, int[] cmpL, int[] cmpR,
                 DiffOptions o, BooleanSupplier stop) {
            this.left = left;
            this.right = right;
            this.li = li;
            this.ri = ri;
            this.cmpL = cmpL;
            this.cmpR = cmpR;
            this.o = o;
            this.stop = stop;
            this.scratch = new int[cmpL.length];
        }

        /** Adds rows {@code l} and {@code r} as same or changed. */
        void pair(TableDiffResult.Builder b, int l, int r, boolean sameHash) throws IOException {
            if (sameHash) {
                b.add(DiffStatus.SAME, l, r, null, 0);
                return;
            }
            if ((++count & 1023) == 0 && stop.getAsBoolean()) {
                throw new CancellationException();
            }
            String[] a = left.read(li, l);
            String[] c = right.read(ri, r);
            int n = 0;
            for (int i = 0; i < cmpL.length; i++) {
                if (!o.same(a[cmpL[i]], c[cmpR[i]])) {
                    scratch[n++] = i;
                }
            }
            b.add(n == 0 ? DiffStatus.SAME : DiffStatus.CHANGED, l, r, scratch, n);
        }
    }

    private static void keyed(Side l, Side r, TableDiffResult.Builder b, Comparer c, DoubleConsumer p,
                              BooleanSupplier stop) throws IOException {
        l.buildTable();
        r.buildTable();
        int nl = l.size;
        int nr = r.size;
        int[] matchOfR = new int[nr];
        for (int i = 0; i < nr; i++) {
            int m = l.find(r.k1[i], r.k2[i]);
            matchOfR[i] = m;
            if (m >= 0 && r.dup.get(i)) {
                l.dup.set(m); // the key is twice on the right: its left row isn't matched either
            }
        }
        int[] matchOfL = new int[nl];
        Arrays.fill(matchOfL, -1);
        for (int i = 0; i < nr; i++) {
            int m = matchOfR[i];
            if (m >= 0 && !r.dup.get(i) && !l.dup.get(m)) {
                matchOfL[m] = i;
            }
        }
        // rows only on the right go after the matched row before them on the right
        int[] anchor = new int[nr];
        int last = -1;
        for (int i = 0; i < nr; i++) {
            int m = matchOfR[i];
            if (m >= 0 && matchOfL[m] == i) {
                last = m;
                anchor[i] = -2;
            } else {
                anchor[i] = last;
            }
        }
        int[] start = new int[nl + 2];
        for (int i = 0; i < nr; i++) {
            if (anchor[i] != -2) {
                start[anchor[i] + 2]++;
            }
        }
        for (int i = 1; i < start.length; i++) {
            start[i] += start[i - 1];
        }
        int[] grouped = new int[start[start.length - 1]];
        int[] fill = Arrays.copyOf(start, start.length);
        for (int i = 0; i < nr; i++) {
            if (anchor[i] != -2) {
                grouped[fill[anchor[i] + 1]++] = i;
            }
        }
        // start[g]..start[g+1]: the rows after left row g-1 (g = 0: before all)
        emitRight(r, matchOfR, grouped, start[0], start[1], b);
        for (int i = 0; i < nl; i++) {
            if ((i & 4095) == 0) {
                if (stop.getAsBoolean()) {
                    throw new CancellationException();
                }
                p.accept(0.9 + 0.1 * i / Math.max(1, nl));
            }
            if (l.dup.get(i)) {
                b.add(DiffStatus.DUPLICATE, i, -1, null, 0);
            } else if (matchOfL[i] >= 0) {
                int m = matchOfL[i];
                c.pair(b, i, m, l.content[i] == r.content[m]);
            } else {
                b.add(DiffStatus.REMOVED, i, -1, null, 0);
            }
            emitRight(r, matchOfR, grouped, start[i + 1], start[i + 2], b);
        }
        Set<Long> dupKeys = new HashSet<>();
        for (int i = l.dup.nextSetBit(0); i >= 0; i = l.dup.nextSetBit(i + 1)) {
            dupKeys.add(l.k1[i] ^ Long.rotateLeft(l.k2[i], 17));
        }
        for (int i = r.dup.nextSetBit(0); i >= 0; i = r.dup.nextSetBit(i + 1)) {
            dupKeys.add(r.k1[i] ^ Long.rotateLeft(r.k2[i], 17));
        }
        for (int i = 0; i < nr; i++) {
            if (!r.dup.get(i) && matchOfR[i] >= 0 && l.dup.get(matchOfR[i])) {
                dupKeys.add(r.k1[i] ^ Long.rotateLeft(r.k2[i], 17));
            }
        }
        b.duplicateKeys(dupKeys.size());
    }

    private static void emitRight(Side r, int[] matchOfR, int[] grouped, int from, int to,
                                  TableDiffResult.Builder b) {
        for (int g = from; g < to; g++) {
            int i = grouped[g];
            boolean dup = r.dup.get(i) || matchOfR[i] >= 0;
            b.add(dup ? DiffStatus.DUPLICATE : DiffStatus.ADDED, -1, i, null, 0);
        }
    }

    private static void unordered(Side l, Side r, TableDiffResult.Builder b, BooleanSupplier stop) {
        Map<Long, Integer> first = new HashMap<>();
        int[] next = new int[l.size];
        for (int i = l.size - 1; i >= 0; i--) {
            Integer f = first.put(l.content[i], i);
            next[i] = f == null ? -1 : f;
        }
        int[] matchOfL = new int[l.size];
        Arrays.fill(matchOfL, -1);
        BitSet added = new BitSet();
        for (int i = 0; i < r.size; i++) {
            if ((i & 4095) == 0 && stop.getAsBoolean()) {
                throw new CancellationException();
            }
            Integer m = first.get(r.content[i]);
            if (m == null) {
                added.set(i);
            } else {
                matchOfL[m] = i;
                if (next[m] < 0) {
                    first.remove(r.content[i]);
                } else {
                    first.put(r.content[i], next[m]);
                }
            }
        }
        for (int i = 0; i < l.size; i++) {
            b.add(matchOfL[i] >= 0 ? DiffStatus.SAME : DiffStatus.REMOVED, i, matchOfL[i], null, 0);
        }
        for (int i = added.nextSetBit(0); i >= 0; i = added.nextSetBit(i + 1)) {
            b.add(DiffStatus.ADDED, -1, i, null, 0);
        }
    }

    private static void inOrder(Side l, Side r, TableDiffResult.Builder b, Comparer c, DoubleConsumer p,
                                BooleanSupplier stop) throws IOException {
        List<Edit> edits = SequenceDiff.diff(Arrays.copyOf(l.content, l.size), Arrays.copyOf(r.content, r.size));
        if (stop.getAsBoolean()) {
            throw new CancellationException();
        }
        p.accept(0.93);
        for (Edit e : edits) {
            switch (e.kind()) {
                case EQUAL -> {
                    for (int i = 0; i < e.aLength(); i++) {
                        b.add(DiffStatus.SAME, e.aStart() + i, e.bStart() + i, null, 0);
                    }
                }
                case DELETE -> {
                    for (int i = e.aStart(); i < e.aEnd(); i++) {
                        b.add(DiffStatus.REMOVED, i, -1, null, 0);
                    }
                }
                case INSERT -> {
                    for (int i = e.bStart(); i < e.bEnd(); i++) {
                        b.add(DiffStatus.ADDED, -1, i, null, 0);
                    }
                }
                case REPLACE -> {
                    int n = Math.min(e.aLength(), e.bLength());
                    for (int i = 0; i < n; i++) {
                        c.pair(b, e.aStart() + i, e.bStart() + i, false);
                    }
                    for (int i = e.aStart() + n; i < e.aEnd(); i++) {
                        b.add(DiffStatus.REMOVED, i, -1, null, 0);
                    }
                    for (int i = e.bStart() + n; i < e.bEnd(); i++) {
                        b.add(DiffStatus.ADDED, -1, i, null, 0);
                    }
                }
            }
        }
    }
}
