package logsift.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numbers written in messages, as measurements over time: {@code Order 812 placed in 345 ms},
 * {@code queue size=42}, {@code took 1.5s}. A message kind's variable part is a measurement when
 * a unit follows it ({@code ms}, {@code MB}, {@code %}) or a word like {@code took}, {@code size}
 * or {@code latency} comes before it; order numbers and IDs are not. Durations are in
 * milliseconds and sizes in bytes, whatever unit each line used.
 */
public final class Numbers {
    private Numbers() {}

    public enum Unit {
        MILLIS, BYTES, PERCENT, NONE;

        /** "345 ms", "8.1 s", "12.5 MB", "42 %", "42". */
        public String format(double v) {
            return switch (this) {
                case MILLIS -> v < 1 ? String.format(Locale.ROOT, "%.2f ms", v)
                        : v < 1000 ? Math.round(v) + " ms"
                        : v < 60_000 ? String.format(Locale.ROOT, v < 10_000 ? "%.2f s" : "%.1f s", v / 1000)
                        : Insights.duration((long) v);
                case BYTES -> v < 1024 ? Math.round(v) + " B" : v < 1024 * 1024 ? String.format(Locale.ROOT, "%.1f KB", v / 1024)
                        : v < 1024.0 * 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", v / 1024 / 1024)
                        : String.format(Locale.ROOT, "%.2f GB", v / 1024 / 1024 / 1024);
                case PERCENT -> trim(v) + " %";
                case NONE -> trim(v);
            };
        }

        private static String trim(double v) {
            return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v)
                    : String.format(Locale.ROOT, "%.2f", v);
        }
    }

    /** Where a measurement comes from. */
    public enum Source {
        /** A number in a kind of message. */
        MESSAGE,
        /** A column of numbers. */
        COLUMN,
        /** How many rows have one value of a column, per stretch of time. */
        ROWS
    }

    /**
     * One measurement over time. {@code name}: the message with the measured part as {@code #}
     * ("order … placed in # ms"), or a column ("duration_ms", "Host = web-1"); points sorted by
     * line, at most {@link #MAX_POINTS}. {@code how}: a few words on what it is ("rows per minute"),
     * or "".
     */
    public record Series(String name, MessageStats.Template template, int column, Unit unit, long[] lines,
                         long[] times, double[] values, long count, Source source, String how) {
        public int size() {
            return values.length;
        }

        /** The value at this share of the sorted values (0.5: the median). */
        public double percentile(double p) {
            double[] v = values.clone();
            Arrays.sort(v);
            return v.length == 0 ? Double.NaN : v[Math.min(v.length - 1, (int) Math.ceil(p * v.length) - 1 < 0 ? 0
                    : (int) Math.ceil(p * v.length) - 1)];
        }

        public double max() {
            return Arrays.stream(values).max().orElse(Double.NaN);
        }

        /** Indexes of the largest values, largest first. */
        public int[] largest(int n) {
            Integer[] idx = new Integer[values.length];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            Arrays.sort(idx, (a, b) -> Double.compare(values[b], values[a]));
            return Arrays.stream(idx).limit(n).mapToInt(Integer::intValue).toArray();
        }
    }

    /** A stretch of time when a measurement was far above its usual value. */
    public record Spike(Series series, long from, long to, double median, double usual, double max, long line) {}

    static final int MAX_SERIES = 10;
    static final int MAX_POINTS = 100_000;
    static final int MIN_COUNT = 20;

    private static final Pattern NUMBER = Pattern.compile(
            "^[~≈]?(-?\\d+(?:[.,]\\d+)?)\\s*(ns|us|µs|ms|s|sec|secs|seconds|m|min|mins|minutes|h|b|kb|mb|gb|k|%)?[.,;:)]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> TIME_UNITS = Set.of("ns", "us", "µs", "ms", "millis", "milliseconds", "s",
            "sec", "secs", "second", "seconds", "min", "mins", "minute", "minutes", "h", "hours");
    private static final Set<String> SIZE_UNITS = Set.of("b", "bytes", "byte", "kb", "mb", "gb", "kib", "mib", "gib");
    private static final Set<String> COUNT_UNITS = Set.of("items", "rows", "records", "messages", "events", "entries",
            "files", "requests", "connections", "threads", "tasks", "jobs", "retries", "attempts", "%", "percent");
    private static final Set<String> KEYS = Set.of("took", "after", "latency", "duration", "elapsed", "time", "size",
            "count", "length", "total", "queue", "queued", "active", "idle", "pending", "waiting", "used", "free",
            "heap", "memory", "mem", "cpu", "load", "rate", "lag", "delay", "backlog", "depth", "inflight",
            "in-flight", "connections", "threads", "sessions", "rss", "gc", "pause", "throughput", "qps", "rps", "tps");
    private static final Pattern LAST_WORD = Pattern.compile("([A-Za-z][\\w-]*)\\W*$");
    private static final Pattern FIRST_WORD = Pattern.compile("^\\s*([A-Za-z%µ][A-Za-z]*)");

    /** The measured parts of a message kind: {column, unit hint} (unit: 0 time, 1 size, 2 other). */
    static List<int[]> measuredColumns(String template) {
        String[] parts = template.split(Pattern.quote(MessageStats.WILDCARD), -1);
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k + 1 < parts.length; k++) {
            String before = parts[k];
            String after = parts[k + 1];
            Matcher fw = FIRST_WORD.matcher(after);
            String unit = fw.find() && (after.startsWith(" ") || after.startsWith("%") || fw.start(1) == 0)
                    ? fw.group(1).toLowerCase(Locale.ROOT) : "";
            if (after.startsWith("%")) {
                unit = "%";
            }
            Matcher lw = LAST_WORD.matcher(before);
            String key = lw.find() ? lw.group(1).toLowerCase(Locale.ROOT) : "";
            if (TIME_UNITS.contains(unit)) {
                out.add(new int[] {k, 0});
            } else if (SIZE_UNITS.contains(unit)) {
                out.add(new int[] {k, 1});
            } else if (COUNT_UNITS.contains(unit) || KEYS.contains(key)
                    || key.endsWith("size") || key.endsWith("count") || key.endsWith("ms") || key.endsWith("time")) {
                out.add(new int[] {k, 2});
            }
        }
        return out;
    }

    /** A value as written, in milliseconds or bytes when it has a unit; NaN if it isn't a number. */
    static double parse(String text, String unitAfter, Unit[] unit) {
        Matcher m = NUMBER.matcher(text.strip());
        if (!m.matches()) {
            return Double.NaN;
        }
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        String u = (m.group(2) != null ? m.group(2) : unitAfter).toLowerCase(Locale.ROOT);
        switch (u) {
            case "ns" -> {
                unit[0] = Unit.MILLIS;
                return v / 1e6;
            }
            case "us", "µs" -> {
                unit[0] = Unit.MILLIS;
                return v / 1e3;
            }
            case "ms", "millis", "milliseconds" -> {
                unit[0] = Unit.MILLIS;
                return v;
            }
            case "s", "sec", "secs", "second", "seconds" -> {
                unit[0] = Unit.MILLIS;
                return v * 1000;
            }
            case "m", "min", "mins", "minute", "minutes" -> {
                unit[0] = Unit.MILLIS;
                return v * 60_000;
            }
            case "h", "hours" -> {
                unit[0] = Unit.MILLIS;
                return v * 3_600_000;
            }
            case "b", "byte", "bytes" -> {
                unit[0] = Unit.BYTES;
                return v;
            }
            case "kb", "kib", "k" -> {
                unit[0] = Unit.BYTES;
                return v * 1024;
            }
            case "mb", "mib" -> {
                unit[0] = Unit.BYTES;
                return v * 1024 * 1024;
            }
            case "gb", "gib" -> {
                unit[0] = Unit.BYTES;
                return v * 1024 * 1024 * 1024;
            }
            case "%", "percent" -> {
                unit[0] = Unit.PERCENT;
                return v;
            }
            default -> {
                return v;
            }
        }
    }

    /**
     * The measurements among the message kinds, most frequent first.
     *
     * @param lines finds the lines of a message kind (sorted)
     * @param reader reads a line and its time
     */
    public static List<Series> analyze(List<MessageStats.Template> templates, Spans.Lines lines,
                                       RequestTrace.Reader reader, BooleanSupplier cancelled) {
        List<MessageStats.Template> candidates = new ArrayList<>();
        for (MessageStats.Template t : templates) {
            if (t.count() >= MIN_COUNT && !measuredColumns(t.text()).isEmpty()) {
                candidates.add(t);
            }
        }
        candidates.sort(Comparator.comparingLong(MessageStats.Template::count).reversed());
        List<Series> out = new ArrayList<>();
        for (MessageStats.Template t : candidates) {
            if (out.size() >= MAX_SERIES) {
                break;
            }
            if (cancelled.getAsBoolean()) {
                return null;
            }
            long[] all = lines.of(t, cancelled);
            if (all == null) {
                return null;
            }
            out.addAll(series(t, all, reader));
        }
        return out.size() > MAX_SERIES ? out.subList(0, MAX_SERIES) : out;
    }

    /** The measurements of one message kind, from its lines. */
    static List<Series> series(MessageStats.Template t, long[] all, RequestTrace.Reader reader) {
        List<int[]> cols = measuredColumns(t.text());
        String[] parts = t.text().split(Pattern.quote(MessageStats.WILDCARD), -1);
        Pattern p = Spans.capturing(t.text());
        int stride = Math.max(1, (all.length + MAX_POINTS - 1) / MAX_POINTS);
        int n = (all.length + stride - 1) / stride;
        List<Series> out = new ArrayList<>();
        for (int[] col : cols) {
            long[] ls = new long[n];
            long[] ts = new long[n];
            double[] vs = new double[n];
            int k = 0;
            Unit[] unit = {col[1] == 0 ? Unit.MILLIS : col[1] == 1 ? Unit.BYTES : Unit.NONE};
            Matcher fw = FIRST_WORD.matcher(parts[col[0] + 1]);
            String unitAfter = parts[col[0] + 1].startsWith("%") ? "%" : fw.find() ? fw.group(1).toLowerCase(Locale.ROOT) : "";
            for (int i = 0; i < all.length; i += stride) {
                String text = reader.text(all[i]);
                if (text == null) {
                    continue;
                }
                Matcher m = p.matcher(text);
                if (!m.find() || col[0] + 1 > m.groupCount()) {
                    continue;
                }
                double v = parse(m.group(col[0] + 1), unitAfter, unit);
                if (Double.isNaN(v)) {
                    continue;
                }
                ls[k] = all[i];
                ts[k] = reader.time(text);
                vs[k] = v;
                k++;
            }
            if (k < Math.min(MIN_COUNT, all.length) / 2 + 1) {
                continue; // mostly not numbers
            }
            out.add(new Series(name(parts, col[0]), t, col[0], unit[0], Arrays.copyOf(ls, k), Arrays.copyOf(ts, k),
                    Arrays.copyOf(vs, k), all.length, Source.MESSAGE, ""));
        }
        return out;
    }

    /** "order … placed in # ms": the message, # for the measured part. */
    private static String name(String[] parts, int col) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(i - 1 == col ? "#" : MessageStats.WILDCARD);
            }
            sb.append(parts[i]);
        }
        return Insights.display(sb.toString());
    }

    /** Whether the values (nearly) only go up: a row number, an offset, a clock, not a measurement. */
    static boolean mostlyRising(Series s) {
        double[] v = s.values();
        int rising = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] >= v[i - 1]) {
                rising++;
            }
        }
        return v.length > 1 && rising >= (v.length - 1) * 0.9;
    }

    /** Times when a measurement was far above usual (at least 3× its median, for a while). */
    public static List<Spike> spikes(Series s) {
        int n = s.size();
        if (n < 50) {
            return List.of();
        }
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (long t : s.times()) {
            if (t != TimeFormat.NONE) {
                first = Math.min(first, t);
                last = Math.max(last, t);
            }
        }
        if (first >= last) {
            return List.of();
        }
        double usual = s.percentile(0.5);
        if (!(usual > 0)) {
            return List.of();
        }
        int buckets = 60;
        long size = Math.max(1000, (last - first) / buckets + 1);
        int nb = (int) ((last - first) / size) + 1;
        List<List<Integer>> by = new ArrayList<>();
        for (int i = 0; i < nb; i++) {
            by.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            if (s.times()[i] != TimeFormat.NONE) {
                by.get((int) ((s.times()[i] - first) / size)).add(i);
            }
        }
        boolean[] hot = new boolean[nb];
        for (int b = 0; b < nb; b++) {
            List<Integer> idx = by.get(b);
            if (idx.size() >= 3) {
                double[] v = idx.stream().mapToDouble(i -> s.values()[i]).sorted().toArray();
                hot[b] = v[v.length / 2] >= 3 * usual;
            }
        }
        List<Spike> out = new ArrayList<>();
        for (int b = 0; b < nb; b++) {
            if (!hot[b]) {
                continue;
            }
            int e = b;
            while (e + 1 < nb && (hot[e + 1] || e + 2 < nb && hot[e + 2] && by.get(e + 1).isEmpty())) {
                e++;
            }
            List<Integer> idx = new ArrayList<>();
            for (int k = b; k <= e; k++) {
                idx.addAll(by.get(k));
            }
            double[] v = idx.stream().mapToDouble(i -> s.values()[i]).sorted().toArray();
            if (idx.size() * 2 < n) { // a spike, not the whole log
                int top = idx.stream().max(Comparator.comparingDouble(i -> s.values()[i])).orElse(idx.get(0));
                long from = idx.stream().mapToLong(i -> s.times()[i]).filter(t -> t != TimeFormat.NONE).min().orElse(first);
                long to = idx.stream().mapToLong(i -> s.times()[i]).filter(t -> t != TimeFormat.NONE).max().orElse(last);
                out.add(new Spike(s, from, to, v[v.length / 2], usual, v[v.length - 1], s.lines()[top]));
            }
            b = e;
        }
        out.sort(Comparator.comparingDouble((Spike x) -> x.median / x.usual).reversed());
        return out;
    }
}
