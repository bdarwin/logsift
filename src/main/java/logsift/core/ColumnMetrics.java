package logsift.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.regex.Pattern;

/**
 * Measurements from a log's columns (a CSV's, or a layout's like the access log's size): a
 * column that holds numbers is a measurement over time ({@code duration_ms}, {@code bytes},
 * {@code latency}); a column with a few values ({@code status}, {@code host}) gives how many rows
 * have each value per minute (or per second, per hour: whatever fits the log's time span).
 * Durations are in milliseconds and sizes in bytes, from the unit in the value or in the column's
 * name ({@code _ms}, {@code _bytes}, {@code duration}).
 */
public final class ColumnMetrics {
    private ColumnMetrics() {}

    static final int SAMPLE_ROWS = 3000;
    static final int MIN_ROWS = 20;
    static final int MAX_NUMBER_COLUMNS = 8;
    static final int MAX_COUNT_COLUMNS = 2;
    static final int MAX_VALUES = 5;
    static final int MAX_DISTINCT = 12;
    private static final int MAX_VALUE_LENGTH = 40;
    private static final int MAX_BUCKETS = 2000;

    /** Columns Logsift already uses (the timeline, the tree, level colors), or free text. */
    private static final Set<String> SKIP = Set.of("time", "level", "thread", "logger", "message", "fields",
            "source");
    /** Names of columns that number things rather than measure them. */
    private static final Pattern ID = Pattern.compile(
            "(?i)(?:^|.*[^a-z])(?:id|pid|tid|port|year|seq|line|row|no|nr|num|number|index|uuid|zip|code)$|^id[^a-z].*");
    private static final Pattern DURATION = Pattern.compile(
            "(?i).*(?:duration|latency|elapsed|took|time_?taken|response_?time|wait|delay).*");
    private static final long[] BUCKETS = {1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000,
        600_000, 900_000, 1_800_000, 3_600_000, 7_200_000, 21_600_000, 43_200_000, 86_400_000};

    /**
     * The unit a column's name gives its numbers ("ms" for {@code took_ms}, "b" for {@code bytes}),
     * or "" for none.
     */
    static String unitOf(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[\\s()\\[\\]]+", "_");
        if (n.matches(".*(?:^|_|-)(?:ms|millis|milliseconds)_?$") || n.endsWith("ms") && n.length() > 3
                && DURATION.matcher(n).matches()) {
            return "ms";
        }
        if (n.matches(".*(?:^|_|-)(?:us|µs|micros)_?$")) {
            return "us";
        }
        if (n.matches(".*(?:^|_|-)ns_?$")) {
            return "ns";
        }
        if (n.matches(".*(?:^|_|-)(?:s|sec|secs|seconds)_?$")) {
            return "s";
        }
        if (n.matches(".*(?:^|_|-)(?:kb|kib)_?$")) {
            return "kb";
        }
        if (n.matches(".*(?:^|_|-)(?:mb|mib)_?$")) {
            return "mb";
        }
        if (n.matches(".*(?:^|_|-)(?:gb|gib)_?$")) {
            return "gb";
        }
        if (n.matches("(?:size|bytes|.*(?:^|_|-)bytes?_?)")) {
            return "b";
        }
        if (n.matches(".*(?:%|pct|percent)_?$")) {
            return "%";
        }
        if (DURATION.matcher(n).matches()) {
            return "ms";
        }
        return "";
    }

    /** What the sample says about one column. */
    private static final class Look {
        int filled;
        int numbers;
        int rising = 0;      // values not smaller than the one before
        double last = Double.NaN;
        final Map<String, Integer> distinct = new HashMap<>();
        boolean tooMany;
        boolean tooLong;
    }

    /**
     * The measurements of a log's columns, numbers first, then rows per value.
     *
     * @param text reads a line
     * @param time how lines are timestamped (null: no counts per minute)
     * @return empty when cancelled
     */
    public static List<Numbers.Series> analyze(LogFormat format, long lineCount, LongFunction<String> text,
                                               TimeFormat time, BooleanSupplier cancelled) {
        if (format == null || lineCount == 0) {
            return List.of();
        }
        List<String> cols = format.columns();
        List<String> titles = format.headerNames() != null ? format.headerNames() : cols;
        // 1. a sample from all over the log: which columns hold numbers, which a few values
        Look[] looks = new Look[cols.size()];
        for (int c = 0; c < looks.length; c++) {
            looks[c] = new Look();
        }
        long step = Math.max(1, lineCount / SAMPLE_ROWS);
        int rows = 0;
        for (long l = 0; l < lineCount; l += step) {
            if ((l & 255) == 0 && cancelled.getAsBoolean()) {
                return List.of();
            }
            String[] cells = cellsOf(format, text.apply(l));
            if (cells == null) {
                continue;
            }
            rows++;
            for (int c = 0; c < cells.length; c++) {
                String v = cells[c] == null ? "" : cells[c].strip();
                if (v.isEmpty() || v.equals("-")) {
                    continue;
                }
                Look k = looks[c];
                k.filled++;
                double n = Numbers.parse(v, unitOf(titles.get(c)), new Numbers.Unit[1]);
                if (!Double.isNaN(n)) {
                    k.numbers++;
                    if (!(n < k.last)) {
                        k.rising++;
                    }
                    k.last = n;
                }
                if (v.length() > MAX_VALUE_LENGTH) {
                    k.tooLong = true;
                } else if (!k.tooMany) {
                    k.distinct.merge(v, 1, Integer::sum);
                    k.tooMany = k.distinct.size() > MAX_DISTINCT;
                }
            }
        }
        if (rows < MIN_ROWS) {
            return List.of();
        }
        List<Integer> numberCols = new ArrayList<>();
        List<Integer> countCols = new ArrayList<>();
        for (int c = 0; c < looks.length; c++) {
            Look k = looks[c];
            String name = cols.get(c);
            String unit = unitOf(titles.get(c));
            if (SKIP.contains(name) || k.filled < MIN_ROWS) {
                continue;
            }
            boolean numeric = k.numbers >= k.filled * 0.9;
            boolean counter = k.rising >= k.numbers * 0.98 && k.distinct.size() > MAX_DISTINCT; // a sequence
            boolean id = ID.matcher(titles.get(c)).matches() || ID.matcher(name).matches();
            if (numeric && (!unit.isEmpty() || (!id && !counter && k.tooMany))) {
                if (numberCols.size() < MAX_NUMBER_COLUMNS) {
                    numberCols.add(c);
                }
            } else if (time != null && !k.tooMany && !k.tooLong && k.distinct.size() >= 2) {
                countCols.add(c);
            }
        }
        // columns with the fewest values first: they read best as rows per minute
        countCols.sort((a, b) -> Integer.compare(looks[a].distinct.size(), looks[b].distinct.size()));
        if (countCols.size() > MAX_COUNT_COLUMNS) {
            countCols = new ArrayList<>(countCols.subList(0, MAX_COUNT_COLUMNS));
        }
        if (numberCols.isEmpty() && countCols.isEmpty()) {
            return List.of();
        }
        // 2. the values, from every line (or every n-th of a big log)
        long stride = Math.max(1, (lineCount + Numbers.MAX_POINTS - 1) / Numbers.MAX_POINTS);
        int cap = (int) Math.min(Numbers.MAX_POINTS, (lineCount + stride - 1) / stride);
        Points[] nums = new Points[numberCols.size()];
        for (int i = 0; i < nums.length; i++) {
            nums[i] = new Points(cap);
        }
        List<Map<String, Integer>> valueIds = new ArrayList<>();
        int[][] valueOf = new int[countCols.size()][cap];
        for (int i = 0; i < countCols.size(); i++) {
            valueIds.add(new LinkedHashMap<>());
        }
        long[] rowLines = new long[cap];
        long[] rowTimes = new long[cap];
        int n = 0;
        for (long l = 0; l < lineCount && n < cap; l += stride) {
            if ((l & 1023) == 0 && cancelled.getAsBoolean()) {
                return List.of();
            }
            String t = text.apply(l);
            String[] cells = cellsOf(format, t);
            if (cells == null) {
                continue;
            }
            long when = time == null ? TimeFormat.NONE : time.parse(t);
            for (int i = 0; i < nums.length; i++) {
                int c = numberCols.get(i);
                Numbers.Unit[] unit = {Numbers.Unit.NONE};
                String u = unitOf(titles.get(c));
                double v = cells[c] == null ? Double.NaN : Numbers.parse(cells[c].strip(), u, unit);
                if (!Double.isNaN(v)) {
                    nums[i].add(l, when, v, unit[0]);
                }
            }
            for (int i = 0; i < countCols.size(); i++) {
                String v = cells[countCols.get(i)] == null ? "" : cells[countCols.get(i)].strip();
                Map<String, Integer> ids = valueIds.get(i);
                Integer id = ids.get(v);
                if (id == null) {
                    id = ids.size();
                    ids.put(v, id);
                }
                valueOf[i][n] = id;
            }
            rowLines[n] = l;
            rowTimes[n] = when;
            n++;
        }
        List<Numbers.Series> out = new ArrayList<>();
        for (int i = 0; i < nums.length; i++) {
            Points p = nums[i];
            if (p.n >= MIN_ROWS) {
                out.add(new Numbers.Series(titles.get(numberCols.get(i)), null, numberCols.get(i), p.unit,
                        Arrays.copyOf(p.lines, p.n), Arrays.copyOf(p.times, p.n), Arrays.copyOf(p.values, p.n),
                        p.n * stride, Numbers.Source.COLUMN, "column"));
            }
        }
        for (int i = 0; i < countCols.size(); i++) {
            out.addAll(perTime(titles.get(countCols.get(i)), countCols.get(i), valueIds.get(i), valueOf[i],
                    rowLines, rowTimes, n, stride));
        }
        return out;
    }

    /** The line's values in the format's columns; null for other lines (and a CSV's header). */
    private static String[] cellsOf(LogFormat format, String line) {
        return line == null || line.isBlank() ? null : format.split(line, null);
    }

    /** For the most frequent values of a column: how many rows have it, per stretch of time. */
    private static List<Numbers.Series> perTime(String title, int column, Map<String, Integer> ids, int[] valueOf,
                                                long[] lines, long[] times, int n, long stride) {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (int r = 0; r < n; r++) {
            if (times[r] != TimeFormat.NONE) {
                first = Math.min(first, times[r]);
                last = Math.max(last, times[r]);
            }
        }
        if (first >= last || ids.size() < 2) {
            return List.of();
        }
        long size = BUCKETS[BUCKETS.length - 1];
        for (long b : BUCKETS) {
            if ((last - first) / b <= 60) {
                size = b;
                break;
            }
        }
        int nb = (int) Math.min(MAX_BUCKETS, (last - first) / size + 1);
        size = Math.max(size, (last - first) / nb + 1);
        long origin = first - Math.floorMod(first, size);
        nb = (int) ((last - origin) / size) + 1;
        int[] total = new int[ids.size()];
        for (int r = 0; r < n; r++) {
            total[valueOf[r]]++;
        }
        List<Map.Entry<String, Integer>> byCount = new ArrayList<>(ids.entrySet());
        byCount.removeIf(e -> e.getKey().isEmpty());
        byCount.sort((a, b) -> Integer.compare(total[b.getValue()], total[a.getValue()]));
        long[] bucketLine = new long[nb]; // the first row in each stretch (-1: none, a gap in the log)
        Arrays.fill(bucketLine, -1);
        for (int r = 0; r < n; r++) {
            if (times[r] != TimeFormat.NONE) {
                int b = (int) ((times[r] - origin) / size);
                if (bucketLine[b] < 0) {
                    bucketLine[b] = lines[r];
                }
            }
        }
        String per = "rows per " + per(size);
        List<Numbers.Series> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byCount.subList(0, Math.min(MAX_VALUES, byCount.size()))) {
            int id = e.getValue();
            if (total[id] < MIN_ROWS / 2) {
                continue;
            }
            int[] count = new int[nb];
            long[] firstLine = bucketLine.clone();
            boolean[] seen = new boolean[nb];
            for (int r = 0; r < n; r++) {
                if (valueOf[r] == id && times[r] != TimeFormat.NONE) {
                    int b = (int) ((times[r] - origin) / size);
                    count[b]++;
                    if (!seen[b]) {
                        seen[b] = true;
                        firstLine[b] = lines[r];
                    }
                }
            }
            Points p = new Points(nb);
            for (int b = 0; b < nb; b++) {
                if (bucketLine[b] >= 0) {
                    p.add(firstLine[b], origin + b * size, count[b] * (double) stride, Numbers.Unit.NONE);
                }
            }
            out.add(new Numbers.Series(title + " = " + e.getKey(), null, column, Numbers.Unit.NONE,
                    Arrays.copyOf(p.lines, p.n), Arrays.copyOf(p.times, p.n), Arrays.copyOf(p.values, p.n),
                    total[id] * stride, Numbers.Source.ROWS, per));
        }
        return out;
    }

    /** "minute", "10 s", "hour". */
    static String per(long millis) {
        if (millis == 1000) {
            return "second";
        }
        if (millis == 60_000) {
            return "minute";
        }
        if (millis == 3_600_000) {
            return "hour";
        }
        if (millis == 86_400_000) {
            return "day";
        }
        return millis < 60_000 ? millis / 1000 + " s" : millis < 3_600_000 ? millis / 60_000 + " min"
                : millis / 3_600_000 + " h";
    }

    /** Points of a series as they are found. */
    private static final class Points {
        final long[] lines;
        final long[] times;
        final double[] values;
        int n;
        Numbers.Unit unit = Numbers.Unit.NONE;

        Points(int cap) {
            lines = new long[cap];
            times = new long[cap];
            values = new double[cap];
        }

        void add(long line, long time, double value, Numbers.Unit u) {
            if (n < lines.length) {
                lines[n] = line;
                times[n] = time;
                values[n] = value;
                n++;
                if (u != Numbers.Unit.NONE) {
                    unit = u;
                }
            }
        }
    }
}
