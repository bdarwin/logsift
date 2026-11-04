package logsift.core;

import io.quackjvm.agent.catalog.ColumnInfo;
import io.quackjvm.agent.catalog.TableInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Queries to start from, made for a file's columns by quackjvm's {@code QueryExamples}: for a
 * table, by what its columns hold (numbers, times, text) and, for a wide one, by its families of
 * columns alike ({@code cpu_0001}, {@code cpu_0002}…), which SQL reaches all at once with
 * {@code COLUMNS}; for a log, its levels, errors and durations; joins with the other tables open.
 * Logsift tells it the columns and what they hold, read from the file's first rows as DuckDB will
 * read it. The Query tab's Examples and agents' {@code query_examples} tool show them.
 */
public final class QueryExamples {
    private QueryExamples() {}

    /** A query and what it shows. */
    public record Example(String title, String sql) {}

    /**
     * The examples for a table or a log, with joins to the other tables open (by a column of the same
     * name and kind).
     *
     * @param table  what queries call it: {@code t} or {@code log}
     * @param kinds  a table's columns and what they hold (by SQL name, without {@code line}); null for a log
     * @param others the other tables open: their names and SQL columns
     */
    public static List<Example> of(String table, List<String> columns, Map<String, Pivot.Kind> kinds,
                                   Map<String, List<String>> others) {
        TableInfo main = info(table, columns, kinds, Map.of());
        Map<String, ColumnInfo> byName = new LinkedHashMap<>();
        for (ColumnInfo c : main.columns()) {
            byName.put(c.name().toLowerCase(java.util.Locale.ROOT), c);
        }
        List<TableInfo> rest = new ArrayList<>();
        for (Map.Entry<String, List<String>> o : others.entrySet()) {
            // the other table's kinds aren't known: a column it shares by name is taken to hold the same
            List<ColumnInfo> cols = new ArrayList<>();
            for (String c : o.getValue()) {
                ColumnInfo same = byName.get(c.toLowerCase(java.util.Locale.ROOT));
                cols.add(same != null ? new ColumnInfo(c, same.sqlType(), same.javaType(), true, null, null, null)
                        : column(c, "VARCHAR"));
            }
            rest.add(new TableInfo(o.getKey(), null, 0, cols));
        }
        List<Example> out = new ArrayList<>();
        for (io.quackjvm.agent.catalog.QueryExamples.Example e : io.quackjvm.agent.catalog.QueryExamples.of(main, rest)) {
            out.add(new Example(e.title(), e.sql()));
        }
        return out;
    }

    /** The table as quackjvm's catalog describes one: a log's columns as {@link Sql#log} makes them, a table's by kind. */
    static TableInfo info(String table, List<String> columns, Map<String, Pivot.Kind> kinds, Map<String, String> types) {
        List<ColumnInfo> cols = new ArrayList<>();
        for (String c : columns) {
            if (kinds != null && !c.equalsIgnoreCase("line") && !kinds.containsKey(c)) {
                continue; // no value in the rows read: nothing to say about it (and DuckDB may name it otherwise)
            }
            String type;
            if (c.equalsIgnoreCase("line")) {
                type = "BIGINT";
            } else if (kinds == null) {
                type = switch (c) {
                    case "starts" -> "BOOLEAN";
                    case "time" -> "TIMESTAMP";
                    default -> "VARCHAR";
                };
            } else {
                type = switch (kinds.getOrDefault(c, Pivot.Kind.TEXT)) {
                    case NUMBER -> "DOUBLE";
                    case TIME -> "TIMESTAMP";
                    case TEXT -> "VARCHAR";
                };
            }
            cols.add(column(c, type));
        }
        return new TableInfo(table, null, 0, cols);
    }

    private static ColumnInfo column(String name, String sqlType) {
        Class<?> java = switch (sqlType) {
            case "BIGINT" -> Long.class;
            case "BOOLEAN" -> Boolean.class;
            case "DOUBLE" -> Double.class;
            case "TIMESTAMP" -> java.time.LocalDateTime.class;
            default -> String.class;
        };
        return new ColumnInfo(name, sqlType, java, true, null, null, null);
    }

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern ISO_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?(Z|[+-]\\d{2}(:?\\d{2})?)?");

    /**
     * What a table's columns hold, by their SQL names, from its first rows (a column with no value
     * there is left out): numbers or times only where every value is one, as DuckDB reading the file takes them (one "n/a" makes a column
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
        List<String[]> rows = new ArrayList<>();
        List<Boolean> full = new ArrayList<>();
        int whole = 0;
        for (int i = 0; i < want; i++) {
            String t = buf[i];
            if (t == null || t.isBlank() || format.isHeader(t)) {
                continue;
            }
            String[] cells = format.split(t, null);
            if (cells != null) {
                // a part of a row over several lines (read before those rows are known) has fewer fields
                boolean f = Csv.fieldCount(t, format.delimiter()) >= n;
                rows.add(cells);
                full.add(f);
                whole += f ? 1 : 0;
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            if (whole > 0 && !full.get(r)) {
                continue;
            }
            for (int c = 0; c < n && c < cells.length; c++) {
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
            if (seen[c]) { // a column with no value in the rows read is left out: nothing to say about it
                out.put(sqlColumns.get(c), numbers[c] ? Pivot.Kind.NUMBER : times[c] ? Pivot.Kind.TIME : Pivot.Kind.TEXT);
            }
        }
        return out;
    }
}
