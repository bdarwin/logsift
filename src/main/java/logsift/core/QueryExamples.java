package logsift.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Queries to start from, made for a file's columns: for a table, by what its columns hold
 * (numbers, times, text) and, for a wide one, by its families of columns alike
 * ({@code cpu_0001}, {@code cpu_0002}…), which SQL reaches all at once with {@code COLUMNS}.
 * The Query tab's Examples and agents' {@code query_examples} tool show them.
 */
public final class QueryExamples {
    private QueryExamples() {}

    /** A query and what it shows. */
    public record Example(String title, String sql) {}

    /** A table this wide gets examples for its families of columns, and for finding a column. */
    static final int WIDE = 20;

    /** At least this many columns alike make a family. */
    static final int FAMILY = 5;

    /** Columns the first rows are summarized for, on a table wider than this. */
    static final int SUMMARIZED = 20;

    private static final Pattern NUMBERED = Pattern.compile("(.*?\\D)(\\d+)$");

    /** Words a column is better not named bare in SQL (keywords, types): quoted then. */
    private static final Set<String> QUOTED = Set.of("all", "and", "any", "array", "as", "asc", "at", "between", "both",
            "by", "case", "cast", "check", "collate", "column", "columns", "constraint", "create", "cross", "current_date",
            "current_time", "current_timestamp", "date", "default", "desc", "describe", "distinct", "do", "else", "end",
            "except", "false", "fetch", "filter", "for", "foreign", "from", "full", "grant", "group", "having", "in",
            "inner", "interval", "intersect", "into", "is", "join", "lateral", "left", "like", "limit", "line", "natural",
            "not", "null", "offset", "on", "only", "or", "order", "outer", "over", "pivot", "primary", "qualify",
            "references", "returning", "right", "select", "semi", "table", "then", "time", "timestamp", "to", "true",
            "union", "unique", "unpivot", "user", "using", "value", "values", "when", "where", "window", "with");

    /** A column's name as SQL: bare when that reads the same, else quoted. */
    public static String name(String column) {
        if (column.matches("[a-z_][a-z0-9_]*") && !QUOTED.contains(column)) {
            return column;
        }
        return Sql.ident(column);
    }

    /** A regular expression as a SQL string: {@code '^cpu_[0-9]+$'}. */
    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** Text as a regular expression that matches just it. */
    private static String regexOf(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Columns alike: {@code metric_cpu_0001…} share {@code metric_cpu_}. */
    record Family(String prefix, List<String> columns, boolean numbers) {
        String regex() {
            return "^" + regexOf(prefix) + "[0-9]+$";
        }

        String label() {
            return prefix + "…";
        }
    }

    /** The biggest family of numbered columns (numbers first), or null. */
    static Family family(List<String> columns, Map<String, Pivot.Kind> kinds) {
        Map<String, List<String>> byPrefix = new LinkedHashMap<>();
        for (String c : columns) {
            Matcher m = NUMBERED.matcher(c);
            if (m.matches()) {
                byPrefix.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(c);
            }
        }
        Family best = null;
        for (var e : byPrefix.entrySet()) {
            List<String> cs = e.getValue();
            if (cs.size() < FAMILY) {
                continue;
            }
            boolean numbers = cs.stream().allMatch(c -> kinds.get(c) == Pivot.Kind.NUMBER);
            if (best == null || (numbers && !best.numbers()) || (numbers == best.numbers()
                    && cs.size() > best.columns().size())) {
                best = new Family(e.getKey(), cs, numbers);
            }
        }
        return best;
    }

    private static final List<String> GROUP_NAMES = List.of("host", "hostname", "server", "node", "service", "app",
            "status", "state", "type", "kind", "category", "region", "country", "level", "source", "name", "user");
    private static final List<String> NUMBER_NAMES = List.of("ms", "duration", "elapsed", "latency", "time_ms", "amount",
            "total", "price", "bytes", "size", "count", "value");

    /** The column of this kind whose name is likeliest to be meant, or the first of the kind. */
    private static String pick(List<String> columns, Map<String, Pivot.Kind> kinds, Pivot.Kind kind,
                               List<String> liked, Family skip) {
        String first = null;
        int bestRank = Integer.MAX_VALUE;
        String best = null;
        for (String c : columns) {
            if (kinds.get(c) != kind || (skip != null && skip.columns().contains(c))) {
                continue;
            }
            if (first == null) {
                first = c;
            }
            String l = c.toLowerCase(Locale.ROOT);
            for (int i = 0; i < liked.size() && i < bestRank; i++) {
                if (l.equals(liked.get(i)) || l.endsWith("_" + liked.get(i))) {
                    bestRank = i;
                    best = c;
                }
            }
        }
        return best != null ? best : first;
    }

    /**
     * Examples for a table {@code t}: {@code columns} as SQL names them (without {@code line}),
     * {@code kinds} what they hold where known.
     */
    public static List<Example> forTable(List<String> columns, Map<String, Pivot.Kind> kinds) {
        List<String> cols = columns.stream().filter(c -> !c.equals("line")).toList();
        List<Example> ex = new ArrayList<>();
        ex.add(new Example("The first rows", "SELECT * FROM t LIMIT 100"));
        Family fam = cols.size() >= WIDE ? family(cols, kinds) : null;
        String text = pick(cols, kinds, Pivot.Kind.TEXT, GROUP_NAMES, fam);
        String num = pick(cols, kinds, Pivot.Kind.NUMBER, NUMBER_NAMES, fam);
        if (num == null) {
            num = pick(cols, kinds, Pivot.Kind.NUMBER, NUMBER_NAMES, null);
        }
        String time = pick(cols, kinds, Pivot.Kind.TIME, List.of("time", "timestamp", "date", "ts"), null);
        if (cols.size() <= 100) {
            ex.add(new Example("Every column summarized (min, max, distinct, nulls…)", "SUMMARIZE t"));
        } else {
            List<String> first = new ArrayList<>();
            for (String c : cols.subList(0, SUMMARIZED)) {
                first.add(name(c));
            }
            ex.add(new Example("The first " + SUMMARIZED + " columns summarized (min, max, distinct, nulls…)",
                    "SUMMARIZE SELECT " + String.join(", ", first) + " FROM t"));
        }
        if (text == null && kinds.isEmpty() && !cols.isEmpty()) {
            text = cols.get(0); // what the columns hold isn't known: the first
        }
        if (text != null) {
            ex.add(new Example("Rows per value of " + text,
                    "SELECT " + name(text) + ", count(*) AS rows\nFROM t\nGROUP BY ALL\nORDER BY rows DESC, 1"));
        }
        if (num != null && text != null) {
            ex.add(new Example("Count, average and highest " + num + " by " + text,
                    "SELECT " + name(text) + ", count(*) AS rows, avg(" + name(num) + ") AS avg, max(" + name(num)
                            + ") AS max\nFROM t\nGROUP BY ALL\nORDER BY rows DESC, 1"));
        }
        if (num != null) {
            ex.add(new Example("Rows with the highest " + num,
                    "SELECT line, " + (text != null ? name(text) + ", " : "") + name(num) + "\nFROM t\nORDER BY "
                            + name(num) + " DESC NULLS LAST, line\nLIMIT 20"));
            ex.add(new Example("Median and percentiles of " + num,
                    "SELECT median(" + name(num) + ") AS median, quantile_cont(" + name(num) + ", 0.95) AS p95, "
                            + "quantile_cont(" + name(num) + ", 0.99) AS p99\nFROM t"));
        }
        if (time != null) {
            ex.add(new Example("Rows per hour" + (num != null ? ", with the average " + num : ""),
                    "SELECT date_trunc('hour', " + name(time) + ") AS hour, count(*) AS rows"
                            + (num != null ? ", avg(" + name(num) + ") AS avg" : "") + "\nFROM t\nWHERE " + name(time)
                            + " IS NOT NULL\nGROUP BY hour\nORDER BY hour"));
        }
        if (fam != null) {
            String re = literal(fam.regex());
            String n = String.format(Locale.ROOT, "%,d", fam.columns().size());
            ex.add(new Example("The " + fam.label() + " columns (" + n + " of them, by a pattern)",
                    "SELECT line, COLUMNS(" + re + ")\nFROM t\nLIMIT 100"));
            if (fam.numbers()) {
                if (text != null) {
                    ex.add(new Example("Average of each " + fam.label() + " column by " + text,
                            "SELECT " + name(text) + ", avg(COLUMNS(" + re + "))\nFROM t\nGROUP BY ALL\nORDER BY ALL"));
                }
                if (time != null) {
                    ex.add(new Example("Average of each " + fam.label() + " column per minute",
                            "SELECT date_trunc('minute', " + name(time) + ") AS minute, avg(COLUMNS(" + re + "))\nFROM t\n"
                                    + "WHERE " + name(time) + " IS NOT NULL\nGROUP BY ALL\nORDER BY minute"));
                }
                ex.add(new Example("Total of the " + fam.label() + " columns in each row",
                        "SELECT line, " + (text != null ? name(text) + ", " : "") + "list_sum([*COLUMNS(" + re
                                + ")]) AS total\nFROM t\nORDER BY total DESC NULLS LAST, line\nLIMIT 20"));
                ex.add(new Example("Rows with the highest " + fam.label() + " value",
                        "SELECT line, " + (text != null ? name(text) + ", " : "") + "greatest(*COLUMNS(" + re
                                + ")) AS highest\nFROM t\nORDER BY highest DESC NULLS LAST, line\nLIMIT 20"));
                ex.add(new Example("The " + fam.label() + " columns as rows, highest average first",
                        "SELECT name, avg(value) AS avg, max(value) AS max\nFROM (UNPIVOT (SELECT COLUMNS(" + re
                                + ") FROM t)\n      ON COLUMNS(*) INTO NAME name VALUE value)\nGROUP BY name\n"
                                + "ORDER BY avg DESC, name\nLIMIT 20"));
            }
        }
        if (cols.size() >= WIDE) {
            String word = fam != null ? fam.prefix().replaceAll("[^A-Za-z0-9]+$", "") : cols.get(cols.size() - 1);
            ex.add(new Example("Find columns by name",
                    "SELECT column_name, column_type\nFROM (DESCRIBE t)\nWHERE column_name ILIKE "
                            + literal("%" + word + "%")));
        }
        return ex;
    }

    /**
     * Examples that use the other open tables: a join where {@code table} and another share a
     * column name, else the other's first rows. {@code others}: each one's columns, by its name.
     */
    public static List<Example> withOthers(String table, List<String> columns, Map<String, List<String>> others) {
        List<Example> ex = new ArrayList<>();
        java.util.Set<String> mine = new java.util.HashSet<>(columns);
        mine.remove("line");
        for (var o : others.entrySet()) {
            String shared = o.getValue().stream().filter(c -> !c.equals("line") && mine.contains(c)).findFirst()
                    .orElse(null);
            if (shared != null) {
                ex.add(new Example("Rows with their match in " + o.getKey() + " (by " + shared + ")",
                        "SELECT *\nFROM " + table + "\nJOIN " + o.getKey() + " USING (" + name(shared) + ")\nLIMIT 100"));
            } else {
                ex.add(new Example("The first rows of " + o.getKey() + " (another open table)",
                        "SELECT * FROM " + o.getKey() + " LIMIT 100"));
            }
        }
        return ex;
    }

    /** Examples for a log, the table {@code log} with these columns. */
    public static List<Example> forLog(List<String> cols) {
        List<Example> ex = new ArrayList<>();
        boolean level = cols.contains("level");
        boolean message = cols.contains("message");
        if (level) {
            ex.add(new Example("Lines per level",
                    "SELECT level, count(*) AS lines\nFROM log\nWHERE starts\nGROUP BY level\nORDER BY lines DESC"));
            ex.add(new Example("Errors per minute",
                    "SELECT date_trunc('minute', time) AS minute, count(*) AS errors\nFROM log\nWHERE level = 'ERROR'\n"
                            + "GROUP BY minute\nORDER BY minute"));
        }
        if (level && message) {
            ex.add(new Example("The most frequent errors (numbers blanked)",
                    "SELECT regexp_replace(message, '[0-9]+', '#', 'g') AS kind, count(*) AS times, min(line) AS first\n"
                            + "FROM log\nWHERE level = 'ERROR'\nGROUP BY kind\nORDER BY times DESC\nLIMIT 50"));
        }
        if (cols.contains("logger") && level) {
            ex.add(new Example("Errors and warnings by logger",
                    "PIVOT (SELECT logger, level FROM log WHERE level IN ('ERROR', 'WARN'))\nON level\nUSING count(*)\n"
                            + "ORDER BY 2 DESC"));
        }
        String m = message ? "message" : "text";
        ex.add(new Example("Durations in messages (… 345 ms): slowest first",
                "SELECT line, time, CAST(regexp_extract(" + m + ", '([0-9]+) ?ms', 1) AS INTEGER) AS ms, " + m
                        + "\nFROM log\nWHERE " + m + " SIMILAR TO '.*[0-9]+ ?ms.*'\nORDER BY ms DESC\nLIMIT 100"));
        ex.add(new Example("Lines with a text", "SELECT line, " + (cols.contains("time") ? "time, " : "") + "text\nFROM log\n"
                + "WHERE text ILIKE '%timeout%'\nLIMIT 1000"));
        ex.add(new Example("Lines per hour", "SELECT date_trunc('hour', time) AS hour, count(*) AS lines\nFROM log\n"
                + "WHERE time IS NOT NULL\nGROUP BY hour\nORDER BY hour"));
        return ex;
    }

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern ISO_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?(Z|[+-]\\d{2}(:?\\d{2})?)?");

    /**
     * What a table's columns hold, by their SQL names, from its first rows: numbers or times only
     * where every value is one, as DuckDB reading the file takes them (one "n/a" makes a column
     * text there), so that an example works on the file read in place as well as from the cache.
     */
    public static Map<String, Pivot.Kind> kinds(List<String> sqlColumns, LogFormat format, ColumnProfile.Lines lines,
                                                long lineCount) {
        Map<String, Pivot.Kind> out = new LinkedHashMap<>();
        int n = sqlColumns.size();
        boolean[] seen = new boolean[n];
        boolean[] numbers = new boolean[n];
        boolean[] times = new boolean[n];
        java.util.Arrays.fill(numbers, true);
        java.util.Arrays.fill(times, true);
        int want = (int) Math.min(lineCount, 100);
        String[] buf = new String[Math.max(1, want)];
        if (want > 0) {
            lines.read(0, want, buf);
        }
        for (int i = 0; i < want; i++) {
            String t = buf[i];
            if (t == null || t.isBlank() || format.isHeader(t)) {
                continue;
            }
            String[] cells = format.split(t, null);
            for (int c = 0; cells != null && c < n && c < cells.length; c++) {
                String v = cells[c] == null ? "" : cells[c].strip();
                if (v.isEmpty()) {
                    continue;
                }
                seen[c] = true;
                numbers[c] &= NUMBER.matcher(v).matches();
                times[c] &= ISO_TIME.matcher(v).matches();
            }
        }
        for (int c = 0; c < n; c++) {
            out.put(sqlColumns.get(c), !seen[c] ? Pivot.Kind.TEXT : numbers[c] ? Pivot.Kind.NUMBER
                    : times[c] ? Pivot.Kind.TIME : Pivot.Kind.TEXT);
        }
        return out;
    }
}
