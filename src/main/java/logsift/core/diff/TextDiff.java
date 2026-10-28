package logsift.core.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Two texts compared line by line, the way a code review shows them: runs of equal lines, and
 * blocks of lines inserted, deleted or changed. In a changed block, lines are paired and the
 * words that differ are marked. Lines can be compared ignoring spaces or case, and with times,
 * numbers or IDs masked, so two runs of the same job line up.
 */
public final class TextDiff {
    private TextDiff() {}

    /** How spaces count. */
    public enum Spaces {
        EXACT("Compare spaces"), ENDS("Ignore spaces at the ends"), ALL("Ignore all spaces");

        public final String label;

        Spaces(String label) {
            this.label = label;
        }
    }

    /**
     * What counts as the same line.
     *
     * @param spaces  how spaces count
     * @param ignoreCase upper and lower case are the same
     * @param maskTimes dates and times count as equal ("2026-10-29 10:02:13,123", "10:02:13")
     * @param maskNumbers every number counts as equal
     * @param maskIds UUIDs, long hex strings and IDs with digits count as equal
     */
    public record Options(Spaces spaces, boolean ignoreCase, boolean maskTimes, boolean maskNumbers, boolean maskIds) {
        public static final Options DEFAULT = new Options(Spaces.EXACT, false, false, false, false);

        public boolean masks() {
            return maskTimes || maskNumbers || maskIds;
        }
    }

    private static final Pattern TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{1,2}:\\d{2}(?::\\d{2}(?:[.,]\\d{1,9})?)?(?:Z|[+-]\\d{2}:?\\d{2})?"
            + "|\\d{1,2}/\\d{1,2}/\\d{2,4}(?:[ :]\\d{1,2}:\\d{2}(?::\\d{2})?)?"
            + "|\\d{4}-\\d{2}-\\d{2}"
            + "|\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?\\b"
            + "|\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) {1,2}\\d{1,2} \\d{2}:\\d{2}:\\d{2}\\b");
    private static final Pattern ID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
            + "|\\b(?:0x)?[0-9a-fA-F]{12,}\\b"
            + "|\\b(?=[A-Za-z_-]*\\d)(?=\\d*[A-Za-z])[A-Za-z0-9_-]{6,}\\b");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:[.,]\\d+)*");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** A line as compared: with the options applied (spaces, case, masks). */
    public static String normalize(String line, Options o) {
        String s = line;
        if (o.maskTimes()) {
            s = TIME.matcher(s).replaceAll("\u0001T");
        }
        if (o.maskIds()) {
            s = ID.matcher(s).replaceAll("\u0001I");
        }
        if (o.maskNumbers()) {
            s = NUMBER.matcher(s).replaceAll("\u0001N");
        }
        s = switch (o.spaces()) {
            case EXACT -> s;
            case ENDS -> s.strip();
            case ALL -> SPACES.matcher(s).replaceAll("");
        };
        if (o.ignoreCase()) {
            s = s.toLowerCase(Locale.ROOT);
        }
        return s;
    }

    /** A block of the comparison: lines {@code [l1, l2)} on the left against {@code [r1, r2)} on the right. */
    public record Block(Edit.Kind kind, int l1, int l2, int r1, int r2) {
        public boolean isChange() {
            return kind != Edit.Kind.EQUAL;
        }
    }

    /**
     * A row of the side-by-side view: a left line and a right line, either -1 where that side has
     * none (lines inserted or deleted are faced by an empty row).
     */
    public record Row(int left, int right, int block) {}

    /** Characters {@code [start, end)} of a line that differ from the line it is paired with. */
    public record Span(int start, int end) {}

    /** What a comparison found. */
    public static final class Result {
        private final List<String> left;
        private final List<String> right;
        private final List<Block> blocks;
        private final List<Row> rows;
        private final Options options;
        private final long millis;

        Result(List<String> left, List<String> right, List<Block> blocks, List<Row> rows, Options options,
               long millis) {
            this.left = left;
            this.right = right;
            this.blocks = blocks;
            this.rows = rows;
            this.options = options;
            this.millis = millis;
        }

        public List<String> left() {
            return left;
        }

        public List<String> right() {
            return right;
        }

        /** The blocks, equal and changed, covering both texts in order. */
        public List<Block> blocks() {
            return blocks;
        }

        /** The rows of the side-by-side view. */
        public List<Row> rows() {
            return rows;
        }

        public Options options() {
            return options;
        }

        public long millis() {
            return millis;
        }

        /** The changed blocks. */
        public List<Block> changes() {
            return blocks.stream().filter(Block::isChange).toList();
        }

        public int count(Edit.Kind kind) {
            return (int) blocks.stream().filter(b -> b.kind() == kind).count();
        }

        /** Lines only on the right (inserted, and the extra lines of changed blocks). */
        public int linesAdded() {
            int n = 0;
            for (Block b : blocks) {
                if (b.isChange()) {
                    n += Math.max(0, (b.r2() - b.r1()) - (b.kind() == Edit.Kind.REPLACE ? b.l2() - b.l1() : 0));
                }
            }
            return n;
        }

        /** Lines only on the left. */
        public int linesRemoved() {
            int n = 0;
            for (Block b : blocks) {
                if (b.isChange()) {
                    n += Math.max(0, (b.l2() - b.l1()) - (b.kind() == Edit.Kind.REPLACE ? b.r2() - b.r1() : 0));
                }
            }
            return n;
        }

        /** Lines changed (paired in changed blocks). */
        public int linesChanged() {
            int n = 0;
            for (Block b : blocks) {
                if (b.kind() == Edit.Kind.REPLACE) {
                    n += Math.min(b.l2() - b.l1(), b.r2() - b.r1());
                }
            }
            return n;
        }

        /** Whether the texts are the same (as compared). */
        public boolean same() {
            return blocks.stream().noneMatch(Block::isChange);
        }

        /**
         * The parts of a row's lines that differ (words, as a reader sees them): for the left
         * line, then the right. Empty for equal rows and for lines without a partner.
         */
        public List<List<Span>> inline(Row row) {
            if (row.left() < 0 || row.right() < 0 || row.block() < 0 || !blocks.get(row.block()).isChange()) {
                return List.of(List.of(), List.of());
            }
            return words(left.get(row.left()), right.get(row.right()), options);
        }
    }

    /** Compares two texts given as lines (null: cancelled). */
    public static Result compare(List<String> left, List<String> right, Options o) {
        long t0 = System.nanoTime();
        long[] a = new long[left.size()];
        long[] b = new long[right.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = hash(normalize(left.get(i), o));
        }
        for (int i = 0; i < b.length; i++) {
            b[i] = hash(normalize(right.get(i), o));
        }
        List<Edit> edits = SequenceDiff.diff(a, b);
        List<Block> blocks = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (Edit e : merge(edits)) {
            int bi = blocks.size();
            blocks.add(new Block(e.kind(), e.aStart(), e.aEnd(), e.bStart(), e.bEnd()));
            switch (e.kind()) {
                case EQUAL -> {
                    for (int i = 0; i < e.aLength(); i++) {
                        rows.add(new Row(e.aStart() + i, e.bStart() + i, bi));
                    }
                }
                case DELETE -> {
                    for (int i = e.aStart(); i < e.aEnd(); i++) {
                        rows.add(new Row(i, -1, bi));
                    }
                }
                case INSERT -> {
                    for (int i = e.bStart(); i < e.bEnd(); i++) {
                        rows.add(new Row(-1, i, bi));
                    }
                }
                case REPLACE -> {
                    for (int[] p : pair(left, right, e, o)) {
                        rows.add(new Row(p[0], p[1], bi));
                    }
                }
            }
        }
        return new Result(List.copyOf(left), List.copyOf(right), List.copyOf(blocks), List.copyOf(rows), o,
                (System.nanoTime() - t0) / 1_000_000);
    }

    /** Runs of the same kind next to each other (a delete then an insert) are one changed block. */
    private static List<Edit> merge(List<Edit> edits) {
        List<Edit> out = new ArrayList<>();
        for (Edit e : edits) {
            if (e.aLength() == 0 && e.bLength() == 0) {
                continue;
            }
            Edit last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.kind() != Edit.Kind.EQUAL && e.kind() != Edit.Kind.EQUAL) {
                int a = last.aLength() + e.aLength();
                int b = last.bLength() + e.bLength();
                Edit.Kind k = a == 0 ? Edit.Kind.INSERT : b == 0 ? Edit.Kind.DELETE : Edit.Kind.REPLACE;
                out.set(out.size() - 1, new Edit(k, last.aStart(), e.aEnd(), last.bStart(), e.bEnd()));
            } else {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The rows of a changed block: lines paired with the most alike line on the other side, in
     * order (so a line inserted in the middle of changed ones faces an empty row), the rest alone.
     */
    static List<int[]> pair(List<String> left, List<String> right, Edit e, Options o) {
        int n = e.aLength();
        int m = e.bLength();
        List<int[]> out = new ArrayList<>();
        if ((long) n * m > 40_000) {
            // big blocks: line by line, in order
            for (int i = 0; i < Math.max(n, m); i++) {
                out.add(new int[] {i < n ? e.aStart() + i : -1, i < m ? e.bStart() + i : -1});
            }
            return out;
        }
        // an alignment that pairs lines alike enough (dynamic programming over the two runs)
        double[][] sim = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                sim[i][j] = similarity(normalize(left.get(e.aStart() + i), o), normalize(right.get(e.bStart() + j), o));
            }
        }
        double[][] best = new double[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                double take = sim[i][j] >= 0.3 ? sim[i][j] + best[i + 1][j + 1] : Double.NEGATIVE_INFINITY;
                best[i][j] = Math.max(take, Math.max(best[i + 1][j], best[i][j + 1]));
            }
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && sim[i][j] >= 0.3 && best[i][j] == sim[i][j] + best[i + 1][j + 1]) {
                out.add(new int[] {e.aStart() + i, e.bStart() + j});
                i++;
                j++;
            } else if (i < n && (j >= m || best[i][j] == best[i + 1][j])) {
                out.add(new int[] {e.aStart() + i, -1});
                i++;
            } else {
                out.add(new int[] {-1, e.bStart() + j});
                j++;
            }
        }
        // lone lines next to each other face each other (a changed line stays on one row)
        List<int[]> tidy = new ArrayList<>();
        for (int k = 0; k < out.size(); k++) {
            int[] p = out.get(k);
            if (p[0] >= 0 && p[1] < 0 && k + 1 < out.size() && out.get(k + 1)[0] < 0 && out.get(k + 1)[1] >= 0) {
                tidy.add(new int[] {p[0], out.get(k + 1)[1]});
                k++;
            } else {
                tidy.add(p);
            }
        }
        return tidy;
    }

    /** How alike two lines are, 0 to 1 (shared characters of their bigrams). */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1;
        }
        if (a.isBlank() || b.isBlank()) {
            return a.isBlank() && b.isBlank() ? 1 : 0;
        }
        String x = a.strip();
        String y = b.strip();
        java.util.Map<Integer, Integer> grams = new java.util.HashMap<>();
        for (int i = 0; i + 1 < x.length(); i++) {
            grams.merge(x.charAt(i) << 16 | x.charAt(i + 1), 1, Integer::sum);
        }
        int common = 0;
        for (int i = 0; i + 1 < y.length(); i++) {
            int g = y.charAt(i) << 16 | y.charAt(i + 1);
            Integer c = grams.get(g);
            if (c != null && c > 0) {
                common++;
                grams.put(g, c - 1);
            }
        }
        int total = Math.max(1, (x.length() - 1) + (y.length() - 1));
        return 2.0 * common / total;
    }

    /** Splits a line into words, runs of spaces and single other characters, for word-level marks. */
    static List<int[]> tokens(String s) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int j = i + 1;
            if (Character.isLetterOrDigit(c) || c == '_') {
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
            } else if (Character.isWhitespace(c)) {
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
            }
            out.add(new int[] {i, j});
            i = j;
        }
        return out;
    }

    /** The words of {@code a} and {@code b} that differ, as character ranges (merged where they touch). */
    public static List<List<Span>> words(String a, String b, Options o) {
        List<int[]> ta = tokens(a);
        List<int[]> tb = tokens(b);
        long[] ha = new long[ta.size()];
        long[] hb = new long[tb.size()];
        for (int i = 0; i < ha.length; i++) {
            ha[i] = hash(token(a, ta.get(i), o));
        }
        for (int i = 0; i < hb.length; i++) {
            hb[i] = hash(token(b, tb.get(i), o));
        }
        List<Span> sa = new ArrayList<>();
        List<Span> sb = new ArrayList<>();
        // with times, numbers or IDs masked, the words in them don't count
        if (o.masks()) {
            for (int i = 0; i < ha.length; i++) {
                if (masked(a, ta.get(i), o)) {
                    ha[i] = 1;
                }
            }
            for (int i = 0; i < hb.length; i++) {
                if (masked(b, tb.get(i), o)) {
                    hb[i] = 1;
                }
            }
        }
        for (Edit e : SequenceDiff.diff(ha, hb, 500)) {
            if (e.kind() == Edit.Kind.EQUAL) {
                continue;
            }
            if (e.aLength() > 0) {
                add(sa, ta.get(e.aStart())[0], ta.get(e.aEnd() - 1)[1], a);
            }
            if (e.bLength() > 0) {
                add(sb, tb.get(e.bStart())[0], tb.get(e.bEnd() - 1)[1], b);
            }
        }
        return List.of(sa, sb);
    }

    /** Whether a token is inside a time, number or ID that is masked. */
    private static boolean masked(String line, int[] t, Options o) {
        java.util.List<Pattern> ps = new ArrayList<>();
        if (o.maskTimes()) {
            ps.add(TIME);
        }
        if (o.maskIds()) {
            ps.add(ID);
        }
        if (o.maskNumbers()) {
            ps.add(NUMBER);
        }
        for (Pattern p : ps) {
            java.util.regex.Matcher m = p.matcher(line);
            while (m.find()) {
                if (m.start() <= t[0] && t[1] <= m.end()) {
                    return true;
                }
                if (m.start() > t[1]) {
                    break;
                }
            }
        }
        return false;
    }

    private static String token(String s, int[] t, Options o) {
        String w = s.substring(t[0], t[1]);
        if (o.spaces() != Spaces.EXACT && w.isBlank()) {
            return " ";
        }
        return o.ignoreCase() ? w.toLowerCase(Locale.ROOT) : w;
    }

    /** Adds a changed range, without spaces at its ends, joined to the last one if only spaces lie between. */
    private static void add(List<Span> out, int start, int end, String s) {
        while (start < end && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return;
        }
        if (!out.isEmpty()) {
            Span last = out.get(out.size() - 1);
            if (s.substring(last.end(), start).isBlank()) {
                out.set(out.size() - 1, new Span(last.start(), end));
                return;
            }
        }
        out.add(new Span(start, end));
    }

    private static long hash(String s) {
        return Hashes.add2(Hashes.add1(Hashes.SEED1, s), s);
    }
}
