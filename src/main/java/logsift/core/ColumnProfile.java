package logsift.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/**
 * What each column of a table (a CSV file, or any log with columns) holds: its kind (numbers,
 * times, a few categories, booleans, IDs or free text), how many values are filled in and
 * different, and what a chart needs (a histogram, the top values, rows over time). Big files are
 * read in blocks spread over the file, so the profile then comes from a sample.
 */
public final class ColumnProfile {
    private ColumnProfile() {}

    /** What a column holds. */
    public enum Kind {
        NUMBER("number"), TIME("time"), CATEGORY("category"), BOOLEAN("yes / no"), ID("ID"), TEXT("text"),
        EMPTY("empty");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** One bar of a chart: a value (or a range of numbers or times) and how many rows have it. */
    public record Bar(String label, long count, double from, double to) {}

    /**
     * One column.
     *
     * @param filled rows with a value; {@code empty} rows without one
     * @param distinct different values (at least this many when {@code distinctCapped})
     * @param stats numbers: min, median, mean, 95%, max; times: first, last (ms); else empty
     * @param bars the chart: a histogram (numbers, times) or the top values with "other" last
     * @param logScale the histogram's buckets grow ×10 (numbers over several orders of magnitude)
     * @param shortest the shortest and longest value (in characters)
     */
    public record Column(String name, String title, Kind kind, Numbers.Unit unit, long filled, long empty,
                         long distinct, boolean distinctCapped, double[] stats, List<Bar> bars, boolean logScale,
                         int shortest, int longest) {}

    /**
     * @param rows rows read (lines with the format; not the header or pieces of a quoted value)
     * @param sampled true when only part of the file was read
     */
    public record Result(List<Column> columns, long rows, long linesRead, long lineCount, boolean sampled,
                         boolean filtered) {}

    /** Reads lines {@code first..first+count} into {@code out} (null for lines it can't read). */
    public interface Lines {
        void read(long first, int count, String[] out);
    }

    /** Files with more lines are read in blocks spread over the file. */
    public static final int MAX_ROWS = 200_000;
    /**
     * The most cells kept (every value is kept until the column is profiled): a wide table is
     * read in fewer rows, so 2,000 columns don't fill the memory.
     */
    static final long MAX_CELLS = 4_000_000;
    private static final int MIN_ROWS = 1_000;

    /** How many rows are read for a table this wide. */
    static int rowsFor(int columns) {
        return (int) Math.max(MIN_ROWS, Math.min(MAX_ROWS, MAX_CELLS / Math.max(1, columns)));
    }
    private static final int BLOCK = 500;
    private static final int MAX_TRACKED = 10_000; // different values counted exactly
    static final int MAX_CATEGORIES = 20;
    static final int TOP = 10;
    static final int BINS = 20;
    private static final Set<String> BOOLEANS = Set.of("true", "false", "yes", "no", "y", "n", "t", "f", "0", "1",
            "on", "off");
    private static final Pattern ID_NAME = Pattern.compile(
            "(?i)(?:^|.*[^a-z])(?:id|uuid|guid|key|hash|token|ref|code|number|no|nr)$|^id[^a-z].*");
    private static final Pattern TIME_NAME = Pattern.compile("(?i).*(?:time|date|^ts$|_ts$|epoch|stamp).*");

    /**
     * Profiles every column.
     *
     * @param lines reads lines of the file
     * @param include which lines to count (the filters' lines), or null for all
     * @param progress receives 0..1 (may be null)
     * @return null if cancelled
     */
    public static Result analyze(LogFormat format, long lineCount, Lines lines, LongPredicate include, int year,
                                 BooleanSupplier cancelled, java.util.function.DoubleConsumer progress) {
        List<String> cols = format.columns();
        List<String> titles = format.titles();
        int n = cols.size();
        List<List<String>> values = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            values.add(new ArrayList<>());
        }
        int maxRows = rowsFor(n);
        boolean sampled = lineCount > maxRows;
        long rows = 0;
        long read = 0;
        long blocks = sampled ? Math.max(1, maxRows / BLOCK) : 1;
        String[] buf = new String[4096];
        for (long b = 0; b < blocks; b++) {
            long from = sampled ? b * (lineCount / blocks) : 0;
            long to = sampled ? Math.min(lineCount, from + BLOCK) : lineCount;
            for (long first = from; first < to; first += buf.length) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                int k = (int) Math.min(buf.length, to - first);
                lines.read(first, k, buf);
                for (int i = 0; i < k; i++) {
                    read++;
                    String t = buf[i];
                    if (t == null || t.isBlank() || (include != null && !include.test(first + i))) {
                        continue;
                    }
                    String[] cells = format.split(t, null);
                    if (cells == null) {
                        continue;
                    }
                    rows++;
                    for (int c = 0; c < n; c++) {
                        values.get(c).add(cells[c] == null ? "" : cells[c].strip());
                    }
                }
                if (progress != null) {
                    progress.accept(sampled ? (double) (b + 1) / blocks : (double) (first + k) / Math.max(1, to));
                }
            }
        }
        List<Column> out = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            out.add(column(cols.get(c), titles.get(c), values.get(c), year));
        }
        return new Result(out, rows, read, lineCount, sampled, include != null);
    }

    /** The profile of one column from its values ("" for an empty cell). */
    static Column column(String name, String title, List<String> values, int year) {
        long empty = 0;
        Map<String, long[]> counts = new HashMap<>();
        boolean capped = false;
        int shortest = Integer.MAX_VALUE;
        int longest = 0;
        List<String> filled = new ArrayList<>();
        for (String v : values) {
            if (v.isEmpty() || v.equals("-") || v.equalsIgnoreCase("null")) {
                empty++;
                continue;
            }
            filled.add(v);
            shortest = Math.min(shortest, v.length());
            longest = Math.max(longest, v.length());
            long[] k = counts.get(v);
            if (k != null) {
                k[0]++;
            } else if (counts.size() < MAX_TRACKED) {
                counts.put(v, new long[] {1});
            } else {
                capped = true;
            }
        }
        if (filled.isEmpty()) {
            return new Column(name, title, Kind.EMPTY, Numbers.Unit.NONE, 0, empty, 0, false, new double[0],
                    List.of(), false, 0, 0);
        }
        long distinct = counts.size();
        String unitHint = ColumnMetrics.unitOf(title);
        Numbers.Unit[] unit = {Numbers.Unit.NONE};
        double[] nums = new double[filled.size()];
        int numeric = 0;
        for (String v : filled) {
            double d = Numbers.parse(v, unitHint, unit);
            if (!Double.isNaN(d)) {
                nums[numeric++] = d;
            }
        }
        boolean isNumber = numeric >= filled.size() * 0.95;
        boolean idName = ID_NAME.matcher(title).matches() || ID_NAME.matcher(name).matches();
        boolean isTimeName = TIME_NAME.matcher(title).matches() || name.equals("time");
        // booleans: two values, both yes/no words
        if (distinct == 2 && counts.keySet().stream().allMatch(k -> BOOLEANS.contains(k.toLowerCase(Locale.ROOT)))) {
            return category(name, title, Kind.BOOLEAN, counts, filled.size(), empty, distinct, capped, shortest, longest);
        }
        // times: values a time format reads (a number only when the name says so: an epoch)
        if (!isNumber || isTimeName) {
            TimeFormat tf = TimeFormat.detect(filled.subList(0, Math.min(200, filled.size())), year);
            if (tf != null) {
                long[] t = new long[filled.size()];
                int k = 0;
                for (String v : filled) {
                    long x = tf.parse(v);
                    if (x != TimeFormat.NONE) {
                        t[k++] = x;
                    }
                }
                if (k >= filled.size() * 0.9) {
                    return time(name, title, Arrays.copyOf(t, k), filled.size(), empty, distinct, capped,
                            shortest, longest);
                }
            }
        }
        if (distinct <= MAX_CATEGORIES && !capped && (distinct < filled.size() || filled.size() < 3)) {
            return category(name, title, Kind.CATEGORY, counts, filled.size(), empty, distinct, capped, shortest,
                    longest);
        }
        if (isNumber && !(idName && unitHint.isEmpty())) {
            return number(name, title, Arrays.copyOf(nums, numeric), unit[0], filled.size(), empty, distinct, capped,
                    shortest, longest);
        }
        double avg = filled.stream().mapToInt(String::length).average().orElse(0);
        boolean words = filled.stream().limit(500).filter(v -> v.indexOf(' ') > 0).count() > Math.min(500,
                filled.size()) / 3;
        Kind kind = !words && avg <= 40 ? Kind.ID : Kind.TEXT;
        return new Column(name, title, kind, Numbers.Unit.NONE, filled.size(), empty, distinct, capped, new double[0],
                top(counts, filled.size(), 5), false, shortest, longest);
    }

    private static Column category(String name, String title, Kind kind, Map<String, long[]> counts, long filled,
                                   long empty, long distinct, boolean capped, int shortest, int longest) {
        return new Column(name, title, kind, Numbers.Unit.NONE, filled, empty, distinct, capped, new double[0],
                top(counts, filled, TOP), false, shortest, longest);
    }

    /** The most frequent values, then "other" with the rest (when there is a rest). */
    private static List<Bar> top(Map<String, long[]> counts, long filled, int max) {
        List<Map.Entry<String, long[]>> all = new ArrayList<>(counts.entrySet());
        all.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -e.getValue()[0])
                .thenComparing(Map.Entry::getKey));
        List<Bar> out = new ArrayList<>();
        long shown = 0;
        for (Map.Entry<String, long[]> e : all.subList(0, Math.min(max, all.size()))) {
            out.add(new Bar(e.getKey(), e.getValue()[0], Double.NaN, Double.NaN));
            shown += e.getValue()[0];
        }
        if (filled > shown) {
            out.add(new Bar(OTHER, filled - shown, Double.NaN, Double.NaN));
        }
        return out;
    }

    /** The label of the bar with the values not shown. */
    public static final String OTHER = "other";

    private static Column number(String name, String title, double[] v, Numbers.Unit unit, long filled, long empty,
                                 long distinct, boolean capped, int shortest, int longest) {
        double[] s = v.clone();
        Arrays.sort(s);
        double min = s[0];
        double max = s[s.length - 1];
        double mean = Arrays.stream(s).average().orElse(Double.NaN);
        double[] stats = {min, s[(s.length - 1) / 2], mean, s[Math.max(0, (int) Math.ceil(0.95 * s.length) - 1)], max};
        boolean log = min > 0 && max / min >= 1000;
        List<Bar> bars = new ArrayList<>();
        if (min == max) {
            bars.add(new Bar(unit.format(min), s.length, min, max));
        } else if (log) {
            // a bucket per power of ten (exact decimals, so a filter's text reads back the same edge)
            int e = (int) Math.floor(Math.log10(min));
            while (java.math.BigDecimal.ONE.scaleByPowerOfTen(e).doubleValue() > min) {
                e--;
            }
            List<double[]> ranges = new ArrayList<>();
            for (double lo = pow10(e); lo <= max; lo = pow10(++e)) {
                ranges.add(new double[] {lo, pow10(e + 1)});
            }
            for (int i = 0; i < ranges.size(); i++) {
                double lo = ranges.get(i)[0];
                double hi = ranges.get(i)[1];
                bars.add(new Bar(unit.format(lo) + " – " + unit.format(hi),
                        countIn(s, lo, hi, i == ranges.size() - 1), lo, hi));
            }
        } else {
            double[] edges = niceEdges(min, max, BINS);
            for (int i = 0; i + 1 < edges.length; i++) {
                bars.add(new Bar(unit.format(edges[i]) + " – " + unit.format(edges[i + 1]),
                        countIn(s, edges[i], edges[i + 1], i + 2 == edges.length), edges[i], edges[i + 1]));
            }
        }
        return new Column(name, title, Kind.NUMBER, unit, filled, empty, distinct, capped, stats, bars, log,
                shortest, longest);
    }

    private static double pow10(int e) {
        return java.math.BigDecimal.ONE.scaleByPowerOfTen(e).doubleValue();
    }

    /**
     * The text of a filter on a number column's bar {@code i}: "5..<10", or "95..100" for the
     * last bar (which has the largest value). The rows it keeps are the bar's.
     */
    public static String filterText(Column c, int i) {
        Bar b = c.bars().get(i);
        boolean last = i == c.bars().size() - 1;
        return ColumnFilter.write(b.from(), c.unit()) + (last ? ".." : "..<") + ColumnFilter.write(b.to(), c.unit());
    }

    /** Values in [lo, hi) of a sorted array ([lo, hi] when {@code last}). */
    static long countIn(double[] sorted, double lo, double hi, boolean last) {
        int a = lowerBound(sorted, lo);
        int b = last ? upperBound(sorted, hi) : lowerBound(sorted, hi);
        return Math.max(0, b - a);
    }

    private static int lowerBound(double[] s, double x) {
        int lo = 0;
        int hi = s.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (s[m] < x) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    private static int upperBound(double[] s, double x) {
        int lo = 0;
        int hi = s.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (s[m] <= x) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    /**
     * Bucket edges at round numbers (1, 2 or 5 × a power of ten), at most about {@code bins}
     * buckets. Each edge is the double nearest to its decimal (0.3, not 0.30000000000000004), so
     * a filter written with it keeps the same rows as the bucket.
     */
    static double[] niceEdges(double min, double max, int bins) {
        double raw = (max - min) / bins;
        int e = (int) Math.floor(Math.log10(raw));
        double p = pow10(e);
        int m = raw <= p ? 1 : raw <= 2 * p ? 2 : raw <= 5 * p ? 5 : 10;
        java.math.BigDecimal step = java.math.BigDecimal.valueOf(m).scaleByPowerOfTen(e);
        long k = (long) Math.floor(min / step.doubleValue());
        while (step.multiply(java.math.BigDecimal.valueOf(k)).doubleValue() > min) {
            k--;
        }
        List<Double> out = new ArrayList<>();
        for (; ; k++) {
            double x = step.multiply(java.math.BigDecimal.valueOf(k)).doubleValue();
            out.add(x);
            if (x > max || out.size() > bins * 2 + 2) {
                break;
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static Column time(String name, String title, long[] t, long filled, long empty, long distinct,
                               boolean capped, int shortest, int longest) {
        long[] s = t.clone();
        Arrays.sort(s);
        long first = s[0];
        long last = s[s.length - 1];
        List<Bar> bars = new ArrayList<>();
        if (first < last) {
            int nb = 40;
            double size = (double) (last - first) / nb;
            double[] d = Arrays.stream(s).asDoubleStream().toArray();
            for (int i = 0; i < nb; i++) {
                double lo = first + i * size;
                double hi = i == nb - 1 ? last : first + (i + 1) * size;
                bars.add(new Bar("", countIn(d, lo, hi, i == nb - 1), lo, hi));
            }
        }
        return new Column(name, title, Kind.TIME, Numbers.Unit.NONE, filled, empty, distinct, capped,
                new double[] {first, last}, bars, false, shortest, longest);
    }
}
