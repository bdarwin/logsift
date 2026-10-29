package logsift.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/**
 * A pivot table of a table's rows (a CSV file, or any log with columns), as in a spreadsheet:
 * rows grouped by some columns, split into columns by others, and values summed, counted or
 * averaged in each cell, with subtotals and totals. Times can be grouped by year, month, day or
 * hour. Every row is read once; nothing is sampled.
 */
public final class Pivot {
    private Pivot() {}

    /** How a value is summed up in a cell. */
    public enum Agg {
        COUNT("Count"), COUNT_DISTINCT("Count distinct"), SUM("Sum"), AVERAGE("Average"), MIN("Min"), MAX("Max"),
        MEDIAN("Median"), P95("95th percentile"), P99("99th percentile");

        public final String label;

        Agg(String label) {
            this.label = label;
        }

        /** Whether it needs numbers (count and count distinct take any value). */
        public boolean numeric() {
            return this != COUNT && this != COUNT_DISTINCT;
        }
    }

    /** How a cell's value is shown. */
    public enum ShowAs {
        VALUE("The value"), PCT_TOTAL("% of the grand total"), PCT_ROW("% of the row's total"),
        PCT_COLUMN("% of the column's total");

        public final String label;

        ShowAs(String label) {
            this.label = label;
        }
    }

    /** How a row or column field groups its values (times by period, numbers by range). */
    public enum Group {
        NONE("Each value"), YEAR("Year"), QUARTER("Quarter"), MONTH("Month"), WEEK("Week"), DAY("Day"),
        HOUR("Hour"), MINUTE("Minute"), WEEKDAY("Day of the week"), HOUR_OF_DAY("Hour of the day");

        public final String label;

        Group(String label) {
            this.label = label;
        }

        public boolean isTime() {
            return this != NONE;
        }
    }

    /** A column used to group rows (or columns). */
    public record Field(int column, Group group) {
        public Field(int column) {
            this(column, Group.NONE);
        }
    }

    /** A column summed up in the cells. */
    public record Value(int column, Agg agg, ShowAs showAs) {
        public Value(int column, Agg agg) {
            this(column, agg, ShowAs.VALUE);
        }
    }

    /**
     * What to compute.
     *
     * @param rows    fields down the side, outermost first
     * @param cols    fields across the top
     * @param values  what the cells hold (none: the number of rows)
     * @param filters for some columns, the only values kept (by their text; "" for blank)
     */
    public record Spec(List<Field> rows, List<Field> cols, List<Value> values, Map<Integer, Set<String>> filters) {
        public Spec {
            rows = List.copyOf(rows);
            cols = List.copyOf(cols);
            values = List.copyOf(values);
            filters = java.util.Collections.unmodifiableMap(new HashMap<>(filters)); // null: every value kept
        }

        /** The values, or a count of rows when none are chosen. */
        public List<Value> cellValues() {
            return values.isEmpty() ? List.of(new Value(-1, Agg.COUNT)) : values;
        }
    }

    /** The most groups down the side; more go to "(other)". */
    public static final int MAX_ROW_GROUPS = 100_000;
    /** The most column groups; more go to "(other)". */
    public static final int MAX_COL_GROUPS = 400;
    /** Distinct values counted for one cell (count distinct); more are estimated as at least this. */
    static final int MAX_DISTINCT = 200_000;
    /** Values listed for a filter. */
    public static final int MAX_FILTER_VALUES = 5_000;

    public static final String BLANK = "(blank)";
    public static final String OTHER = "(other)";

    /** A group of rows: a label, the groups inside it, and its cells (one per column group, plus the total). */
    public static final class Node {
        final String label;
        final Node parent;
        final int depth;
        LinkedHashMap<String, Node> kids;
        /** Cells by column group (index {@code cols}: the row's total), each with an accumulator per value. */
        Acc[][] cells;
        long rows;

        Node(String label, Node parent, int depth) {
            this.label = label;
            this.parent = parent;
            this.depth = depth;
        }

        public String label() {
            return label;
        }

        public Node parent() {
            return parent;
        }

        /** 0 for the grand total, 1 for the outermost groups. */
        public int depth() {
            return depth;
        }

        /** The groups inside, in their order (after {@link Result#sort}). */
        public List<Node> kids() {
            return kids == null ? List.of() : List.copyOf(kids.values());
        }

        public boolean isLeaf() {
            return kids == null || kids.isEmpty();
        }

        /** The rows in this group. */
        public long rows() {
            return rows;
        }

        /** The labels from the outermost group to this one. */
        public List<String> path() {
            List<String> out = new ArrayList<>();
            for (Node n = this; n.parent != null; n = n.parent) {
                out.add(0, n.label);
            }
            return out;
        }
    }

    /** A cell's running sum, count, min, max and distinct values for one value field. */
    static final class Acc {
        long count;
        long numbers;
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        Set<String> distinct;
        boolean distinctCapped;

        /** The numbers, for median and percentiles: all of them, or a fair sample past {@link #MAX_KEPT}. */
        double[] kept;
        int nKept;
        private long rnd = 0x9E3779B97F4A7C15L;

        void add(String text, double number, Agg agg) {
            count++;
            if (!Double.isNaN(number)) {
                numbers++;
                sum += number;
                min = Math.min(min, number);
                max = Math.max(max, number);
                if (agg == Agg.MEDIAN || agg == Agg.P95 || agg == Agg.P99) {
                    keep(number);
                }
            }
            if (agg == Agg.COUNT_DISTINCT) {
                if (distinct == null) {
                    distinct = new HashSet<>();
                }
                if (distinct.size() < MAX_DISTINCT) {
                    distinct.add(text);
                } else {
                    distinctCapped |= !distinct.contains(text);
                }
            }
        }

        private void keep(double v) {
            if (kept == null) {
                kept = new double[16];
            }
            if (nKept < MAX_KEPT) {
                if (nKept == kept.length) {
                    kept = Arrays.copyOf(kept, Math.min(MAX_KEPT, kept.length * 2));
                }
                kept[nKept++] = v;
                return;
            }
            // reservoir sampling: every number has the same chance to be in the sample
            rnd ^= rnd << 13;
            rnd ^= rnd >>> 7;
            rnd ^= rnd << 17;
            long j = Math.floorMod(rnd, numbers);
            if (j < MAX_KEPT) {
                kept[(int) j] = v;
            }
        }

        private boolean sorted;

        /** The q-quantile of the numbers (0.5: the median), interpolated as spreadsheets do. */
        double quantile(double q) {
            if (nKept == 0) {
                return Double.NaN;
            }
            if (!sorted) {
                Arrays.sort(kept, 0, nKept);
                sorted = true;
            }
            double pos = q * (nKept - 1);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(nKept - 1, lo + 1);
            return kept[lo] + (kept[hi] - kept[lo]) * (pos - lo);
        }

        double get(Agg agg) {
            return switch (agg) {
                case MEDIAN -> quantile(0.5);
                case P95 -> quantile(0.95);
                case P99 -> quantile(0.99);
                case COUNT -> count;
                case COUNT_DISTINCT -> distinct == null ? 0 : distinct.size();
                case SUM -> numbers == 0 ? Double.NaN : sum;
                case AVERAGE -> numbers == 0 ? Double.NaN : sum / numbers;
                case MIN -> numbers == 0 ? Double.NaN : min;
                case MAX -> numbers == 0 ? Double.NaN : max;
            };
        }
    }

    /** What a pivot found. */
    public static final class Result {
        private final Spec spec;
        private final List<String> titles;
        private final Node root;
        private final List<List<String>> colKeys;
        private final long rows;
        private final long read;
        private final boolean rowsCapped;
        private final boolean colsCapped;
        private final Map<Integer, List<String>> filterValues;
        private final long millis;

        Result(Spec spec, List<String> titles, Node root, List<List<String>> colKeys, long rows, long read,
               boolean rowsCapped, boolean colsCapped, Map<Integer, List<String>> filterValues, long millis) {
            this.spec = spec;
            this.titles = titles;
            this.root = root;
            this.colKeys = colKeys;
            this.rows = rows;
            this.read = read;
            this.rowsCapped = rowsCapped;
            this.colsCapped = colsCapped;
            this.filterValues = filterValues;
            this.millis = millis;
        }

        public Spec spec() {
            return spec;
        }

        /** The columns' titles, as in the file's header. */
        public List<String> titles() {
            return titles;
        }

        /** The grand total; its kids are the outermost row groups. */
        public Node root() {
            return root;
        }

        /** The column groups, each its labels from the outermost field in. Empty: no column fields. */
        public List<List<String>> colKeys() {
            return colKeys;
        }

        /** The number of value columns before the totals: one per column group and value. */
        public int colGroups() {
            return Math.max(1, colKeys.size());
        }

        /** Rows counted (after the filters). */
        public long rows() {
            return rows;
        }

        /** Lines read. */
        public long read() {
            return read;
        }

        public boolean rowsCapped() {
            return rowsCapped;
        }

        public boolean colsCapped() {
            return colsCapped;
        }

        /** The values a filter field has (for its list), sorted, at most {@link #MAX_FILTER_VALUES}. */
        public List<String> filterValues(int column) {
            return filterValues.getOrDefault(column, List.of());
        }

        public long millis() {
            return millis;
        }

        /**
         * A cell's value as shown (a % when asked for).
         *
         * @param col the column group, or -1 for the row's total
         * @param v   the value field (index into {@link Spec#cellValues()})
         */
        public double value(Node n, int col, int v) {
            Value val = spec.cellValues().get(v);
            double x = raw(n, col, v);
            return switch (val.showAs()) {
                case VALUE -> x;
                case PCT_TOTAL -> pct(x, raw(root, -1, v));
                case PCT_ROW -> pct(x, raw(n, -1, v));
                case PCT_COLUMN -> pct(x, raw(root, col, v));
            };
        }

        /** A cell's value before "show as". */
        public double raw(Node n, int col, int v) {
            int at = col < 0 ? colKeys.size() : col;
            if (n.cells == null || at >= n.cells.length || n.cells[at] == null) {
                Agg agg = spec.cellValues().get(v).agg();
                return agg == Agg.COUNT || agg == Agg.COUNT_DISTINCT ? 0 : Double.NaN;
            }
            return n.cells[at][v].get(spec.cellValues().get(v).agg());
        }

        private static double pct(double x, double of) {
            return Double.isNaN(x) || of == 0 || Double.isNaN(of) ? Double.NaN : x * 100 / of;
        }

        /** Whether a count distinct in this cell stopped counting (so it is at least the value). */
        public boolean distinctCapped(Node n, int col, int v) {
            int at = col < 0 ? colKeys.size() : col;
            return n.cells != null && at < n.cells.length && n.cells[at] != null && n.cells[at][v].distinctCapped;
        }

        /** A field's title: the column's, with its grouping ("time (Month)"). */
        public String fieldTitle(Field f) {
            String t = f.column() >= 0 && f.column() < titles.size() ? titles.get(f.column()) : "?";
            return f.group() == Group.NONE ? t : t + " (" + f.group().label + ")";
        }

        /** A value's title: "Sum of amount", "Count of rows". */
        public String valueTitle(int v) {
            Value val = spec.cellValues().get(v);
            String of = val.column() < 0 ? "rows" : val.column() < titles.size() ? titles.get(val.column()) : "?";
            String t = val.agg().label + " of " + of;
            return val.showAs() == ShowAs.VALUE ? t : t + " (%)";
        }

        /**
         * Sorts every level: by label (numbers and dates in order), or by a value, largest first
         * when {@code descending}. Totals stay where they are.
         *
         * @param byCol the column group to sort by (-1: the row's total), with {@code byValue}
         * @param byValue the value field to sort by, or -1 to sort by label
         */
        public void sort(int byValue, int byCol, boolean descending) {
            Comparator<Node> c;
            if (byValue < 0) {
                c = (a, b) -> compareLabels(a.label, b.label);
            } else {
                c = Comparator.comparingDouble(n -> {
                    double x = raw(n, byCol, byValue);
                    return Double.isNaN(x) ? Double.NEGATIVE_INFINITY : x;
                });
            }
            Comparator<Node> order = descending ? c.reversed() : c;
            // (other) always last
            Comparator<Node> withOther = (a, b) -> {
                boolean oa = a.label.equals(OTHER);
                boolean ob = b.label.equals(OTHER);
                return oa != ob ? (oa ? 1 : -1) : order.compare(a, b);
            };
            sortKids(root, withOther);
        }

        private static void sortKids(Node n, Comparator<Node> c) {
            if (n.kids == null) {
                return;
            }
            List<Node> list = new ArrayList<>(n.kids.values());
            list.sort(c);
            n.kids.clear();
            for (Node k : list) {
                n.kids.put(k.label, k);
                sortKids(k, c);
            }
        }

        /** The groups at {@code depth} (1: the outermost), in order. */
        public List<Node> level(int depth) {
            List<Node> out = new ArrayList<>();
            collect(root, depth, out);
            return out;
        }

        private static void collect(Node n, int depth, List<Node> out) {
            if (n.depth == depth) {
                out.add(n);
                return;
            }
            if (n.kids != null) {
                for (Node k : n.kids.values()) {
                    collect(k, depth, out);
                }
            }
        }
    }

    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern GROUPED = Pattern.compile("[-+]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?");

    /**
     * A cell's number: plain numbers, 1,234.5 with thousands, a currency sign or a % after; NaN
     * for anything else. Units (7 ms, 2 KB) count as the number before them.
     */
    public static double number(String text) {
        if (text == null) {
            return Double.NaN;
        }
        double plain = plainNumber(text);
        if (!Double.isNaN(plain)) {
            return plain;
        }
        String t = text.strip();
        if (t.isEmpty()) {
            return Double.NaN;
        }
        int end = t.length();
        while (end > 0 && (t.charAt(end - 1) == '%' || Character.isLetter(t.charAt(end - 1)) || t.charAt(end - 1) == ' ')) {
            end--;
        }
        int start = 0;
        while (start < end && "$€£¥ ".indexOf(t.charAt(start)) >= 0) {
            start++;
        }
        if (start >= end) {
            return Double.NaN;
        }
        String s = t.substring(start, end);
        if (end < t.length() && !t.substring(end).strip().matches("%|[A-Za-zµ]{1,12}")) {
            return Double.NaN;
        }
        if (GROUPED.matcher(s).matches()) {
            s = s.replace(",", "");
        }
        if (!NUMBER.matcher(s).matches()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** "1508", "-3.25", "12.5": read without patterns (most cells); NaN for anything else. */
    static double plainNumber(String t) {
        int n = t.length();
        if (n == 0 || n > 18) {
            return Double.NaN;
        }
        int i = 0;
        boolean neg = false;
        char c0 = t.charAt(0);
        if (c0 == '-' || c0 == '+') {
            neg = c0 == '-';
            i = 1;
            if (n == 1) {
                return Double.NaN;
            }
        }
        long whole = 0;
        int digits = 0;
        for (; i < n; i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            whole = whole * 10 + (c - '0');
            digits++;
        }
        if (i == n) {
            return digits == 0 ? Double.NaN : neg ? -whole : whole;
        }
        if (t.charAt(i) != '.' || digits == 0) {
            return Double.NaN;
        }
        for (int j = i + 1; j < n; j++) {
            char c = t.charAt(j);
            if (c < '0' || c > '9') {
                return Double.NaN;
            }
        }
        return Double.parseDouble(t); // exact, as before, for decimals
    }

    /**
     * Compares labels the way people expect: numbers as numbers (2 before 10), then text
     * ignoring case; (blank) last.
     */
    public static int compareLabels(String a, String b) {
        boolean ba = a.equals(BLANK);
        boolean bb = b.equals(BLANK);
        if (ba || bb) {
            return ba == bb ? 0 : ba ? 1 : -1;
        }
        double x = number(a);
        double y = number(b);
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            int c = Double.compare(x, y);
            if (c != 0) {
                return c;
            }
        }
        int c = a.compareToIgnoreCase(b);
        return c != 0 ? c : a.compareTo(b);
    }

    /** What kind of values a column holds, for the field list. */
    public enum Kind { NUMBER, TIME, TEXT }

    /** What the fields hold, from the first rows: numbers, times (with their format) or text. */
    public record Fields(List<String> titles, List<Kind> kinds, List<TimeFormat> times) {
        public Kind kind(int column) {
            return column >= 0 && column < kinds.size() ? kinds.get(column) : Kind.TEXT;
        }
    }

    /** Looks at up to 500 rows to tell numbers, times and text apart. */
    public static Fields fields(LogFormat format, long lineCount, ColumnProfile.Lines lines, int year) {
        List<String> cols = format.columns();
        List<String> titles = format.titles();
        int n = cols.size();
        List<List<String>> samples = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            samples.add(new ArrayList<>());
        }
        int want = (int) Math.min(lineCount, 600);
        String[] buf = new String[Math.max(1, want)];
        if (want > 0) {
            lines.read(0, want, buf);
        }
        for (int i = 0; i < want; i++) {
            String t = buf[i];
            if (t == null || t.isBlank()) {
                continue;
            }
            String[] cells = format.split(t, null);
            if (cells == null) {
                continue;
            }
            for (int c = 0; c < n && c < cells.length; c++) {
                String v = cells[c] == null ? "" : cells[c].strip();
                if (!v.isEmpty()) {
                    samples.get(c).add(v);
                }
            }
        }
        List<Kind> kinds = new ArrayList<>();
        List<TimeFormat> times = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            List<String> s = samples.get(c);
            long nums = s.stream().filter(v -> !Double.isNaN(number(v))).count();
            String title = titles.get(c).toLowerCase(Locale.ROOT);
            boolean timeName = title.matches(".*(time|date|^ts$|_ts$|stamp|_at$).*") || cols.get(c).equals("time");
            TimeFormat tf = null;
            if (!s.isEmpty() && (nums < s.size() * 0.9 || timeName)) {
                tf = TimeFormat.detect(s.subList(0, Math.min(200, s.size())), year);
                if (tf != null) {
                    TimeFormat f = tf;
                    long ok = s.stream().filter(v -> f.parse(v) != TimeFormat.NONE).count();
                    if (ok < s.size() * 0.9) {
                        tf = null;
                    }
                }
            }
            times.add(tf);
            kinds.add(tf != null ? Kind.TIME : !s.isEmpty() && nums >= s.size() * 0.9 ? Kind.NUMBER : Kind.TEXT);
        }
        return new Fields(titles, kinds, times);
    }

    /** A time grouped: "2026-10", "2026 Q4", "Mon". */
    public static String timeLabel(long millis, Group g, ZoneId zone) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone);
        return switch (g) {
            case NONE -> t.toString();
            case YEAR -> String.valueOf(t.getYear());
            case QUARTER -> t.getYear() + " Q" + ((t.getMonthValue() - 1) / 3 + 1);
            case MONTH -> String.format(Locale.ROOT, "%d-%02d", t.getYear(), t.getMonthValue());
            case WEEK -> {
                java.time.temporal.WeekFields w = java.time.temporal.WeekFields.ISO;
                yield String.format(Locale.ROOT, "%d-W%02d", t.get(w.weekBasedYear()), t.get(w.weekOfWeekBasedYear()));
            }
            case DAY -> String.format(Locale.ROOT, "%d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
            case HOUR -> String.format(Locale.ROOT, "%d-%02d-%02d %02d:00", t.getYear(), t.getMonthValue(),
                    t.getDayOfMonth(), t.getHour());
            case MINUTE -> String.format(Locale.ROOT, "%d-%02d-%02d %02d:%02d", t.getYear(), t.getMonthValue(),
                    t.getDayOfMonth(), t.getHour(), t.getMinute());
            case WEEKDAY -> t.getDayOfWeek().getValue() + " " + t.getDayOfWeek().getDisplayName(TextStyle.SHORT,
                    Locale.ENGLISH);
            case HOUR_OF_DAY -> String.format(Locale.ROOT, "%02d", t.getHour());
        };
    }

    /** Lines of the table, as {@link ColumnProfile#analyze} reads them. */
    public static Result compute(LogFormat format, long lineCount, ColumnProfile.Lines lines, LongPredicate include,
                                 Spec spec, Fields fields, BooleanSupplier cancelled, DoubleConsumer progress) {
        return compute(format, lineCount, lines, include, spec, fields, ZoneId.systemDefault(), cancelled, progress);
    }

    /**
     * Groups the rows and sums up the values (null if cancelled).
     *
     * @param include the lines to count (the log's filters), or null for all
     * @param fields  what the columns hold (for time grouping)
     */
    public static Result compute(LogFormat format, long lineCount, ColumnProfile.Lines lines, LongPredicate include,
                                 Spec spec, Fields fields, ZoneId zone, BooleanSupplier cancelled,
                                 DoubleConsumer progress) {
        long t0 = System.nanoTime();
        List<String> cols = format.columns();
        List<String> titles = format.titles();
        List<Value> values = spec.cellValues();
        int nv = values.size();
        Agg[] distinct = new Agg[nv]; // what each value's accumulators must keep
        for (int v = 0; v < nv; v++) {
            distinct[v] = values.get(v).agg();
        }
        Node root = new Node("Grand Total", null, 0);
        int[] groups = {0};
        boolean[] rowsCapped = {false};
        Map<String, Integer> colIndex = new HashMap<>();
        List<List<String>> colKeys = new ArrayList<>();
        boolean colsCapped = false;
        boolean[] colsCappedHolder = {false};
        Map<Integer, Set<String>> seen = new HashMap<>();
        for (Integer f : spec.filters().keySet()) {
            seen.put(f, new HashSet<>());
        }
        long rows = 0;
        long read = 0;
        List<Integer> filterCols = new ArrayList<>(spec.filters().keySet());
        int threads = lineCount < PARALLEL_LINES ? 1
                : Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService pool = threads == 1 ? null
                : java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "logsift-pivot-part");
                    t.setDaemon(true);
                    return t;
                });
        java.util.ArrayDeque<java.util.concurrent.Future<Prepared[]>> pending = new java.util.ArrayDeque<>();
        try {
            for (long first = 0; first < lineCount; first += CHUNK) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                int k = (int) Math.min(CHUNK, lineCount - first);
                String[] buf = new String[k];
                lines.read(first, k, buf);
                read += k;
                long at = first;
                java.util.concurrent.Callable<Prepared[]> job = () -> prepare(buf, at, include, format, spec, fields, zone,
                        values, filterCols);
                if (pool == null) {
                    Prepared[] p = job.call();
                    rows += consume(p, spec, filterCols, seen, colIndex, colKeys, root, groups, rowsCapped, distinct,
                            colsCappedHolder);
                } else {
                    pending.add(pool.submit(job));
                    while (pending.size() >= threads * 2) {
                        rows += consume(pending.poll().get(), spec, filterCols, seen, colIndex, colKeys, root, groups,
                                rowsCapped, distinct, colsCappedHolder);
                    }
                }
                if (progress != null) {
                    progress.accept((double) (first + k) / Math.max(1, lineCount));
                }
            }
            while (!pending.isEmpty()) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                rows += consume(pending.poll().get(), spec, filterCols, seen, colIndex, colKeys, root, groups, rowsCapped,
                        distinct, colsCappedHolder);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error er) {
                throw er;
            }
            throw new IllegalStateException(c);
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        colsCapped = colsCappedHolder[0];
        // the total column: cells were kept by column group; the row's total is its own cell
        Map<Integer, List<String>> filterValues = new HashMap<>();
        for (Map.Entry<Integer, Set<String>> e : seen.entrySet()) {
            List<String> l = new ArrayList<>(e.getValue());
            l.sort(Pivot::compareLabels);
            filterValues.put(e.getKey(), l);
        }
        // column groups in order (numbers and dates sorted), cells moved to match
        Integer[] order = new Integer[colKeys.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            List<String> x = colKeys.get(a);
            List<String> y = colKeys.get(b);
            for (int i = 0; i < x.size(); i++) {
                boolean oa = x.get(i).equals(OTHER);
                boolean ob = y.get(i).equals(OTHER);
                if (oa != ob) {
                    return oa ? 1 : -1;
                }
                int c = compareLabels(x.get(i), y.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        int[] newAt = new int[order.length];
        List<List<String>> sortedKeys = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            newAt[order[i]] = i;
            sortedKeys.add(colKeys.get(order[i]));
        }
        reorder(root, newAt, colKeys.size());
        Result res = new Result(spec, titles, root, sortedKeys, rows, read, rowsCapped[0], colsCapped, filterValues,
                (System.nanoTime() - t0) / 1_000_000);
        res.sort(-1, -1, false);
        return res;
    }

    /** Lines read at a time, and the parts done in parallel. */
    static final int CHUNK = 4096;
    /** Numbers kept per cell for median and percentiles; past this, a fair sample. */
    static final int MAX_KEPT = 1_000_000;
    static final int PARALLEL_LINES = 50_000;
    static final int MAX_THREADS = 8;

    /** One row, split and worked out (in parallel); added to the table in file order. */
    private record Prepared(String[] filterValues, String[] rowKey, String[] colKey, String[] texts, double[] nums) {}

    /** The rows of a block of lines: splitting, labels and numbers, which take the time. */
    private static Prepared[] prepare(String[] buf, long first, LongPredicate include, LogFormat format, Spec spec,
                                      Fields fields, ZoneId zone, List<Value> values, List<Integer> filterCols) {
        List<Prepared> out = new ArrayList<>(buf.length);
        int nv = values.size();
        for (int i = 0; i < buf.length; i++) {
            String t = buf[i];
            if (t == null || t.isBlank() || (include != null && !include.test(first + i))) {
                continue;
            }
            String[] cells = format.split(t, null);
            if (cells == null) {
                continue;
            }
            String[] fv = new String[filterCols.size()];
            for (int f = 0; f < fv.length; f++) {
                fv[f] = cell(cells, filterCols.get(f));
            }
            String[] rk = new String[spec.rows().size()];
            for (int r = 0; r < rk.length; r++) {
                rk[r] = label(cells, spec.rows().get(r), fields, zone);
            }
            String[] ck = new String[spec.cols().size()];
            for (int c = 0; c < ck.length; c++) {
                ck[c] = label(cells, spec.cols().get(c), fields, zone);
            }
            String[] texts = new String[nv];
            double[] nums = new double[nv];
            for (int v = 0; v < nv; v++) {
                int c = values.get(v).column();
                texts[v] = c < 0 ? "" : cell(cells, c);
                if (c >= 0 && texts[v].equals(BLANK)) {
                    texts[v] = null; // a blank cell isn't counted
                }
                nums[v] = texts[v] == null ? Double.NaN : number(texts[v]);
            }
            out.add(new Prepared(fv, rk, ck, texts, nums));
        }
        return out.toArray(new Prepared[0]);
    }

    /** Adds prepared rows to the table, in order; returns how many were kept by the filters. */
    private static long consume(Prepared[] rowsIn, Spec spec, List<Integer> filterCols, Map<Integer, Set<String>> seen,
                                Map<String, Integer> colIndex, List<List<String>> colKeys, Node root, int[] groups,
                                boolean[] rowsCapped, Agg[] distinct, boolean[] colsCapped) {
        long kept = 0;
        row:
        for (Prepared p : rowsIn) {
            // filters: the values each filter field has are listed, then the row is kept or not
            for (int f = 0; f < filterCols.size(); f++) {
                Set<String> s = seen.get(filterCols.get(f));
                if (s.size() < MAX_FILTER_VALUES) {
                    s.add(p.filterValues()[f]);
                }
            }
            for (int f = 0; f < filterCols.size(); f++) {
                Set<String> keep = spec.filters().get(filterCols.get(f));
                if (keep != null && !keep.contains(p.filterValues()[f])) {
                    continue row;
                }
            }
            kept++;
            int ci = 0;
            String[] colKey = p.colKey();
            if (colKey.length > 0) {
                String joined = String.join("\u0001", colKey);
                Integer at = colIndex.get(joined);
                if (at == null) {
                    if (colKeys.size() < MAX_COL_GROUPS) {
                        at = colKeys.size();
                        colKeys.add(List.of(colKey.clone()));
                        colIndex.put(joined, at);
                    } else {
                        colsCapped[0] = true;
                        String other = String.join("\u0001", java.util.Collections.nCopies(colKey.length, OTHER));
                        at = colIndex.get(other);
                        if (at == null) {
                            at = colKeys.size();
                            colKeys.add(java.util.Collections.nCopies(colKey.length, OTHER));
                            colIndex.put(other, at);
                        }
                    }
                }
                ci = at;
            }
            Node n = root;
            add(n, ci, colKeys.size(), p.texts(), p.nums(), distinct);
            for (String rk : p.rowKey()) {
                n = child(n, rk, groups, rowsCapped);
                add(n, ci, colKeys.size(), p.texts(), p.nums(), distinct);
            }
        }
        return kept;
    }

    private static String cell(String[] cells, int c) {
        String v = c < cells.length && cells[c] != null ? cells[c].strip() : "";
        return v.isEmpty() ? BLANK : v;
    }

    private static String label(String[] cells, Field f, Fields fields, ZoneId zone) {
        String v = cell(cells, f.column());
        if (f.group() == Group.NONE || v.equals(BLANK)) {
            return v;
        }
        TimeFormat tf = fields == null ? null : fields.times().get(f.column());
        if (tf == null) {
            return v;
        }
        long t = tf.parse(v);
        return t == TimeFormat.NONE ? v : timeLabel(t, f.group(), zone);
    }

    private static Node child(Node n, String label, int[] groups, boolean[] capped) {
        if (n.kids == null) {
            n.kids = new LinkedHashMap<>();
        }
        Node k = n.kids.get(label);
        if (k != null) {
            return k;
        }
        if (groups[0] >= MAX_ROW_GROUPS) {
            capped[0] = true;
            label = OTHER;
            k = n.kids.get(label);
            if (k != null) {
                return k;
            }
        }
        k = new Node(label, n, n.depth + 1);
        n.kids.put(label, k);
        groups[0]++;
        return k;
    }

    private static void add(Node n, int col, int cols, String[] texts, double[] nums, Agg[] distinct) {
        n.rows++;
        if (n.cells == null) {
            n.cells = new Acc[Math.max(cols + 1, 4)][];
        }
        addTo(n, cols > 0 ? col : -1, texts, nums, distinct);
        addTo(n, -2, texts, nums, distinct);
    }

    /** Adds to a column group's cell ({@code col}), or (-2) the row's total, kept at {@link #TOTAL}. */
    private static void addTo(Node n, int col, String[] texts, double[] nums, Agg[] distinct) {
        if (col == -1) {
            return; // no column fields: only the total
        }
        int at = col == -2 ? TOTAL : col + 1;
        if (at >= n.cells.length) {
            n.cells = Arrays.copyOf(n.cells, at + 4);
        }
        Acc[] a = n.cells[at];
        if (a == null) {
            a = new Acc[texts.length];
            for (int v = 0; v < a.length; v++) {
                a[v] = new Acc();
            }
            n.cells[at] = a;
        }
        for (int v = 0; v < a.length; v++) {
            if (texts[v] != null) {
                a[v].add(texts[v], nums[v], distinct[v]);
            }
        }
    }

    /** While counting, the row's total is cell 0 and column group g is cell g + 1. */
    private static final int TOTAL = 0;

    /** Moves cells into their final places: column group g (sorted) at g, the total after the last. */
    private static void reorder(Node n, int[] newAt, int cols) {
        if (n.cells != null) {
            Acc[][] out = new Acc[cols + 1][];
            for (int g = 0; g < cols; g++) {
                if (g + 1 < n.cells.length) {
                    out[newAt[g]] = n.cells[g + 1];
                }
            }
            out[cols] = n.cells[TOTAL];
            n.cells = out;
        }
        if (n.kids != null) {
            for (Node k : n.kids.values()) {
                reorder(k, newAt, cols);
            }
        }
    }
}
