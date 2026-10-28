package logsift.core.diff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Suggests the key to match rows by: a column, or two or three together, whose values are
 * filled in and different on every row of both files (of the first rows, in big files). Names
 * like {@code id}, {@code key}, {@code *_id} and {@code code} come first; columns of measured
 * values (decimals) and times come last.
 */
public final class KeySuggester {
    private KeySuggester() {}

    /** The rows of each file looked at. */
    public static final int SAMPLE_ROWS = 20_000;
    private static final int MAX_COLUMNS = 64;

    /** The suggested pairs (empty: none fits) and why, in one line. */
    public record Suggestion(List<Integer> pairs, String reason) {
        public boolean found() {
            return !pairs.isEmpty();
        }
    }

    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d*\\.\\d+");
    private static final Pattern TIME = Pattern.compile(".*\\d{1,2}:\\d{2}.*");

    private static final class Sample {
        final long[][] hashes;
        final int[] empty;
        final int[] measure;
        int rows;
        boolean complete = true;

        Sample(int cols) {
            hashes = new long[cols][1024];
            empty = new int[cols];
            measure = new int[cols];
        }
    }

    public static Suggestion suggest(CsvFile left, CsvFile right, ColumnMap map, BooleanSupplier cancelled)
            throws IOException {
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < map.pairs().size(); i++) {
            if (cand.size() < MAX_COLUMNS || nameScore(map.name(i)) > 0) {
                cand.add(i);
            }
        }
        if (cand.isEmpty()) {
            return new Suggestion(List.of(), "The files have no column in common to match rows by.");
        }
        Sample l = sample(left, cand.stream().mapToInt(p -> map.pairs().get(p).left()).toArray(), cancelled);
        Sample r = sample(right, cand.stream().mapToInt(p -> map.pairs().get(p).right()).toArray(), cancelled);
        if (l.rows == 0 || r.rows == 0) {
            return new Suggestion(List.of(), "A file has no rows: nothing to find a key in.");
        }
        String where = l.complete && r.complete ? "on every row of both files"
                : String.format(Locale.ROOT, "in the first %,d rows of each file", SAMPLE_ROWS);
        int n = cand.size();
        double[] distinct = new double[n];
        boolean[] unique = new boolean[n];
        for (int c = 0; c < n; c++) {
            int dl = distinct(Arrays.copyOf(l.hashes[c], l.rows));
            int dr = distinct(Arrays.copyOf(r.hashes[c], r.rows));
            distinct[c] = Math.min((double) dl / l.rows, (double) dr / r.rows);
            unique[c] = dl == l.rows && dr == r.rows && l.empty[c] == 0 && r.empty[c] == 0;
        }
        // one column
        Integer best = null;
        for (int c = 0; c < n; c++) {
            if (unique[c] && (best == null || score(map, cand, l, c) > score(map, cand, l, best))) {
                best = c;
            }
        }
        if (best != null) {
            String name = map.name(cand.get(best));
            return new Suggestion(List.of(cand.get(best)), name + ": unique and filled in " + where
                    + (nameScore(name) > 0 ? "" : " (no column is named like an id)"));
        }
        // two or three columns: of those without empty values, the most varied
        List<Integer> top = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            if (l.empty[c] == 0 && r.empty[c] == 0 && distinct[c] > 0) {
                top.add(c);
            }
        }
        top.sort(Comparator.<Integer>comparingDouble(c -> -distinct[c])
                .thenComparing(c -> -nameScore(map.name(cand.get(c)))).thenComparing(c -> c));
        List<int[]> combos = new ArrayList<>();
        List<Integer> t8 = top.subList(0, Math.min(8, top.size()));
        for (int i = 0; i < t8.size(); i++) {
            for (int j = i + 1; j < t8.size(); j++) {
                combos.add(new int[] {t8.get(i), t8.get(j)});
            }
        }
        List<Integer> t6 = top.subList(0, Math.min(6, top.size()));
        for (int i = 0; i < t6.size(); i++) {
            for (int j = i + 1; j < t6.size(); j++) {
                for (int k = j + 1; k < t6.size(); k++) {
                    combos.add(new int[] {t6.get(i), t6.get(j), t6.get(k)});
                }
            }
        }
        int[] chosen = null;
        double chosenScore = 0;
        for (int[] combo : combos) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            if (chosen != null && combo.length > chosen.length) {
                break; // fewer columns win
            }
            if (uniqueTogether(l, combo) && uniqueTogether(r, combo)) {
                double s = 0;
                for (int c : combo) {
                    s += score(map, cand, l, c);
                }
                if (chosen == null || s > chosenScore) {
                    chosen = combo;
                    chosenScore = s;
                }
            }
        }
        if (chosen != null) {
            List<Integer> pairs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            int[] sorted = chosen.clone();
            Arrays.sort(sorted);
            for (int c : sorted) {
                pairs.add(cand.get(c));
                names.add(map.name(cand.get(c)));
            }
            return new Suggestion(pairs, String.join(" + ", names) + ": no column alone is unique, together they are "
                    + where);
        }
        return new Suggestion(List.of(), "No column, nor two or three together, is unique " + where
                + ": rows are lined up in file order.");
    }

    private static double score(ColumnMap map, List<Integer> cand, Sample l, int c) {
        double s = nameScore(map.name(cand.get(c))) * 10;
        if (l.measure[c] > l.rows / 2) {
            s -= 5; // decimals or times: measured, not given
        }
        return s - c * 0.01; // then the leftmost
    }

    /** How much a column's name sounds like a key (0: not at all). */
    static double nameScore(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        String raw = name.strip();
        if (n.equals("id") || n.equals("key") || n.equals("uuid") || n.equals("guid")) {
            return 3;
        }
        if (n.endsWith("_id") || n.endsWith(" id") || n.endsWith("-id") || n.endsWith(".id")
                || (raw.endsWith("Id") && raw.length() > 2) || raw.endsWith("ID") || n.endsWith("_key")
                || n.endsWith("uuid") || n.endsWith("guid")) {
            return 2.5;
        }
        if (n.equals("code") || n.endsWith("_code") || n.endsWith(" code") || n.equals("sku") || n.endsWith("_no")
                || n.endsWith("number") || n.endsWith("_num") || n.equals("no") || n.equals("name")) {
            return n.equals("name") ? 1 : 2;
        }
        return 0;
    }

    private static Sample sample(CsvFile f, int[] cols, BooleanSupplier cancelled) throws IOException {
        Sample s = new Sample(cols.length);
        f.scan((row, v) -> {
            if (s.rows == SAMPLE_ROWS) {
                s.complete = false;
                return false;
            }
            if (s.rows == s.hashes[0].length) {
                for (int c = 0; c < cols.length; c++) {
                    s.hashes[c] = Arrays.copyOf(s.hashes[c], s.rows * 2);
                }
            }
            for (int c = 0; c < cols.length; c++) {
                String x = v[cols[c]] == null ? "" : v[cols[c]].strip();
                if (x.isEmpty()) {
                    s.empty[c]++;
                }
                if (DECIMAL.matcher(x).matches() || TIME.matcher(x).matches()) {
                    s.measure[c]++;
                }
                s.hashes[c][s.rows] = Hashes.add2(Hashes.add1(Hashes.SEED1, x), x);
            }
            s.rows++;
            return true;
        }, null, cancelled);
        return s;
    }

    private static int distinct(long[] v) {
        Arrays.sort(v);
        int d = v.length == 0 ? 0 : 1;
        for (int i = 1; i < v.length; i++) {
            if (v[i] != v[i - 1]) {
                d++;
            }
        }
        return d;
    }

    private static boolean uniqueTogether(Sample s, int[] combo) {
        long[] h = new long[s.rows];
        for (int i = 0; i < s.rows; i++) {
            long x = Hashes.SEED1;
            for (int c : combo) {
                x = Hashes.mix(x * 31 + s.hashes[c][i]);
            }
            h[i] = x;
        }
        return distinct(h) == s.rows;
    }
}
