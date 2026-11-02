package logsift.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import logsift.core.CsvRows;
import logsift.core.LogAnalysis;
import logsift.core.LogDocument;
import logsift.core.LogFormat;
import logsift.core.Pivot;
import logsift.core.PivotText;

/** Tables (CSV, TSV) and logs with columns: Pivot, as in the Pivot tab. */
public final class TableTools {
    private TableTools() {}

    public static List<Tool> all() {
        return List.of(PIVOT);
    }

    private record Def(String name, String title, String description, Map<String, Object> inputSchema, Body body)
            implements Tool {
        @Override
        public String run(Args args, Workspace w) throws Exception {
            return body.run(args, w);
        }
    }

    @FunctionalInterface
    private interface Body {
        String run(Tool.Args a, Workspace w) throws Exception;
    }

    static final Tool PIVOT = new Def("pivot", "Pivot a table",
            "Groups a table's rows (CSV/TSV, or a log's columns such as level and logger) and totals them, as "
                    + "the Pivot tab does: rows by one or more fields, optionally columns by another, and values "
                    + "such as count, sum(ms), avg(bytes), max(ms), median(ms), p95(ms), p99(ms), "
                    + "count_distinct(user). A time field groups by year, quarter, month, week, day, hour, minute, "
                    + "weekday or hour_of_day (time:hour). A pattern sums many fields as one value: sum(cpu_*). "
                    + "Numbers are read as Pivot reads them (1,250.50 too); blank cells group as (blank). "
                    + "For questions SQL answers better (joins, conditions), use query.",
            new Tool.Schema().string("log", LogTools.LOG_ARG, true)
                    .string("rows", "Fields to group rows by, outer first, comma-separated: host, or "
                            + "timestamp:hour, region.", false)
                    .string("columns", "A field (or several) to spread across columns, like status.", false)
                    .string("values", "What to total, comma-separated (default count): count, sum(ms), avg(ms), "
                            + "min(x), max(x), median(x), p95(x), p99(x), count_distinct(x), sum(cpu_*).", false)
                    .string("filters", "Keep only rows with these values: host=web-1|web-2; status=500.", false)
                    .choice("show", "Each value as itself (default), or as % of the grand total, of its row's "
                            + "total, or of its column's total.", false, "value", "pct_total", "pct_row", "pct_column")
                    .choice("sort", "Groups by label (default), or by the first value's total.", false, "label",
                            "largest", "smallest")
                    .integer("limit", "Most groups listed (default 100, at most 2000).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = LogTools.ready(a, w);
                String h = w.handleOf(log);
                LogDocument doc = log.document();
                var formats = log.formats();
                LogFormat f = formats == null ? null : formats.columns();
                if (f == null || f.columns().isEmpty()) {
                    throw new Tool.Failure("This log has no columns to group by (it isn't a table, and no log format "
                            + "was found). Use top_messages, metrics or query instead.");
                }
                Pivot.Fields fields = w.await(h + ":pivot-fields:" + doc.generation(), "looking at the fields",
                        () -> Pivot.fields(f, doc.lineCount(), CsvRows.reader(doc, f), java.time.Year.now().getValue()));
                Pivot.ShowAs show = switch (a.string("show", "value")) {
                    case "pct_total" -> Pivot.ShowAs.PCT_TOTAL;
                    case "pct_row" -> Pivot.ShowAs.PCT_ROW;
                    case "pct_column" -> Pivot.ShowAs.PCT_COLUMN;
                    default -> Pivot.ShowAs.VALUE;
                };
                Pivot.Spec spec = new Pivot.Spec(fieldsOf(a.string("rows", ""), fields),
                        fieldsOf(a.string("columns", ""), fields), valuesOf(a.string("values", "count"), fields, show),
                        filtersOf(a.string("filters", ""), fields));
                int limit = (int) a.number("limit", 100, 1, 2000);
                Pivot.Result r = w.await(h + ":pivot:" + doc.generation() + ":" + doc.lineCount() + ":" + spec,
                        "adding up " + log.path().getFileName(), () -> Pivot.compute(f, doc.lineCount(),
                                CsvRows.reader(doc, f), null, spec, fields, LogTools.INTERRUPTED, null));
                if (r == null) {
                    throw new Tool.Failure("Stopped before the pivot finished.");
                }
                switch (a.string("sort", "label")) {
                    case "largest" -> r.sort(0, -1, true);
                    case "smallest" -> r.sort(0, -1, false);
                    default -> { }
                }
                return w.finish(text(r, limit), LogTools.maxChars(a));
            });

    /** The answer: what was counted, the table, and what Pivot left out. */
    static String text(Pivot.Result r, int limit) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%,d", r.rows())).append(r.rows() == 1 ? " row" : " rows");
        if (!r.spec().filters().isEmpty()) {
            b.append(" kept by the filters");
        }
        b.append(" in ").append(String.format(Locale.ROOT, "%,d", r.millis())).append(" ms:\n");
        b.append(PivotText.table(r, limit));
        if (r.rowsCapped()) {
            b.append("(More groups than Pivot keeps (").append(String.format(Locale.ROOT, "%,d", Pivot.MAX_ROW_GROUPS))
                    .append("): the rest are counted in ").append(Pivot.OTHER).append(".)\n");
        }
        if (r.colsCapped()) {
            b.append("(More column groups than Pivot keeps (").append(Pivot.MAX_COL_GROUPS)
                    .append("): the rest are in ").append(Pivot.OTHER).append(".)\n");
        }
        return b.toString().stripTrailing();
    }

    // ---------------------------------------------------------------- what the agent asked for

    private static final Map<String, Pivot.Group> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("year", Pivot.Group.YEAR);
        GROUPS.put("quarter", Pivot.Group.QUARTER);
        GROUPS.put("month", Pivot.Group.MONTH);
        GROUPS.put("week", Pivot.Group.WEEK);
        GROUPS.put("day", Pivot.Group.DAY);
        GROUPS.put("hour", Pivot.Group.HOUR);
        GROUPS.put("minute", Pivot.Group.MINUTE);
        GROUPS.put("weekday", Pivot.Group.WEEKDAY);
        GROUPS.put("hour_of_day", Pivot.Group.HOUR_OF_DAY);
    }

    private static final Map<String, Pivot.Agg> AGGS = new LinkedHashMap<>();

    static {
        AGGS.put("count", Pivot.Agg.COUNT);
        AGGS.put("count_distinct", Pivot.Agg.COUNT_DISTINCT);
        AGGS.put("distinct", Pivot.Agg.COUNT_DISTINCT);
        AGGS.put("sum", Pivot.Agg.SUM);
        AGGS.put("avg", Pivot.Agg.AVERAGE);
        AGGS.put("average", Pivot.Agg.AVERAGE);
        AGGS.put("mean", Pivot.Agg.AVERAGE);
        AGGS.put("min", Pivot.Agg.MIN);
        AGGS.put("max", Pivot.Agg.MAX);
        AGGS.put("median", Pivot.Agg.MEDIAN);
        AGGS.put("p50", Pivot.Agg.MEDIAN);
        AGGS.put("p95", Pivot.Agg.P95);
        AGGS.put("p99", Pivot.Agg.P99);
    }

    /** "host, timestamp:hour" as fields. */
    static List<Pivot.Field> fieldsOf(String text, Pivot.Fields fields) throws Tool.Failure {
        List<Pivot.Field> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String part : split(text, ',')) {
            String name = part;
            Pivot.Group g = Pivot.Group.NONE;
            int colon = part.lastIndexOf(':');
            if (colon > 0 && GROUPS.containsKey(part.substring(colon + 1).strip().toLowerCase(Locale.ROOT))) {
                g = GROUPS.get(part.substring(colon + 1).strip().toLowerCase(Locale.ROOT));
                name = part.substring(0, colon).strip();
            }
            int c = column(name, fields);
            if (g != Pivot.Group.NONE && fields.kind(c) != Pivot.Kind.TIME) {
                throw new Tool.Failure("“" + fields.titles().get(c) + "” isn't a time, so it can't be grouped by "
                        + g.label.toLowerCase(Locale.ROOT) + "." + timesHint(fields));
            }
            if (!seen.add(c)) {
                throw new Tool.Failure("“" + fields.titles().get(c) + "” is there twice.");
            }
            out.add(new Pivot.Field(c, g));
        }
        return out;
    }

    /** "count, sum(ms), sum(cpu_*)" as values. */
    static List<Pivot.Value> valuesOf(String text, Pivot.Fields fields, Pivot.ShowAs show) throws Tool.Failure {
        List<Pivot.Value> out = new ArrayList<>();
        List<String> parts = split(text, ',');
        if (parts.isEmpty()) {
            parts = List.of("count");
        }
        for (String part : parts) {
            String p = part.strip();
            String fn;
            String arg;
            int open = p.indexOf('(');
            if (open > 0 && p.endsWith(")")) {
                fn = p.substring(0, open).strip().toLowerCase(Locale.ROOT);
                arg = p.substring(open + 1, p.length() - 1).strip();
            } else if (AGGS.containsKey(p.toLowerCase(Locale.ROOT)) || p.equalsIgnoreCase("rows")) {
                fn = p.equalsIgnoreCase("rows") ? "count" : p.toLowerCase(Locale.ROOT);
                arg = "";
            } else {
                fn = null; // a field alone: summed if it holds numbers, else counted
                arg = p;
            }
            Pivot.Agg agg = fn == null ? null : AGGS.get(fn);
            if (fn != null && agg == null) {
                throw new Tool.Failure("“" + fn + "” isn't a total Pivot knows. Use one of: count, sum, avg, min, max, "
                        + "median, p95, p99, count_distinct.");
            }
            if (arg.isEmpty() || arg.equals("*")) {
                if (agg != null && agg != Pivot.Agg.COUNT) {
                    throw new Tool.Failure(fn + " needs a field, like " + fn + "(ms).");
                }
                out.add(new Pivot.Value(-1, Pivot.Agg.COUNT, show));
                continue;
            }
            if (arg.indexOf('*') >= 0 || arg.indexOf('?') >= 0) {
                List<Integer> cols = matching(arg, fields);
                if (cols.isEmpty()) {
                    throw new Tool.Failure("No field matches “" + arg + "”." + fieldsHint(fields));
                }
                boolean numbers = cols.stream().anyMatch(c -> fields.kind(c) == Pivot.Kind.NUMBER);
                out.add(Pivot.Value.of(cols, arg, agg != null ? agg : numbers ? Pivot.Agg.SUM : Pivot.Agg.COUNT, show));
                continue;
            }
            int c = column(arg, fields);
            Pivot.Agg use = agg != null ? agg : fields.kind(c) == Pivot.Kind.NUMBER ? Pivot.Agg.SUM : Pivot.Agg.COUNT;
            out.add(new Pivot.Value(c, use, show));
        }
        return out;
    }

    /** "host=web-1|web-2; status=500" as the values each field keeps. */
    static Map<Integer, Set<String>> filtersOf(String text, Pivot.Fields fields) throws Tool.Failure {
        Map<Integer, Set<String>> out = new LinkedHashMap<>();
        for (String part : split(text, ';')) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new Tool.Failure("A filter is field=value or field=a|b, not “" + part + "”.");
            }
            int c = column(part.substring(0, eq).strip(), fields);
            Set<String> keep = new LinkedHashSet<>();
            for (String v : part.substring(eq + 1).split("\\|", -1)) {
                keep.add(v.strip().isEmpty() ? Pivot.BLANK : v.strip());
            }
            out.merge(c, keep, (x, y) -> {
                Set<String> both = new LinkedHashSet<>(x);
                both.retainAll(y);
                return both;
            });
        }
        return out;
    }

    /** A field by its title: as written, else ignoring case. */
    static int column(String name, Pivot.Fields fields) throws Tool.Failure {
        String n = unquote(name);
        int c = fields.titles().indexOf(n);
        if (c >= 0) {
            return c;
        }
        for (int i = 0; i < fields.titles().size(); i++) {
            if (fields.titles().get(i).strip().equalsIgnoreCase(n)) {
                return i;
            }
        }
        throw new Tool.Failure("There is no field “" + n + "”." + fieldsHint(fields));
    }

    private static List<Integer> matching(String pattern, Pivot.Fields fields) {
        StringBuilder re = new StringBuilder();
        for (char ch : unquote(pattern).toLowerCase(Locale.ROOT).toCharArray()) {
            re.append(ch == '*' ? ".*" : ch == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(re.toString());
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < fields.titles().size(); c++) {
            if (p.matcher(fields.titles().get(c).toLowerCase(Locale.ROOT)).matches()) {
                out.add(c);
            }
        }
        return out;
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.charAt(0) == '"' && t.endsWith("\"") || t.charAt(0) == '\'' && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** The parts of a list, split at {@code sep} outside quotes and brackets. */
    static List<String> split(String text, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            } else if (ch == sep && depth == 0) {
                if (!cur.toString().isBlank()) {
                    out.add(cur.toString().strip());
                }
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    private static String fieldsHint(Pivot.Fields fields) {
        List<String> t = fields.titles();
        StringBuilder b = new StringBuilder(" Fields: ");
        for (int i = 0; i < t.size() && i < 40; i++) {
            b.append(i == 0 ? "" : ", ").append(t.get(i)).append(kind(fields.kind(i)));
        }
        if (t.size() > 40) {
            b.append(String.format(Locale.ROOT, " … and %,d more (a pattern like cpu_* names many)", t.size() - 40));
        }
        return b.append('.').toString();
    }

    private static String timesHint(Pivot.Fields fields) {
        List<String> times = new ArrayList<>();
        for (int i = 0; i < fields.titles().size() && times.size() < 10; i++) {
            if (fields.kind(i) == Pivot.Kind.TIME) {
                times.add(fields.titles().get(i));
            }
        }
        return times.isEmpty() ? " This table has no time field." : " Time fields: " + String.join(", ", times) + ".";
    }

    private static String kind(Pivot.Kind k) {
        return switch (k) {
            case NUMBER -> " (number)";
            case TIME -> " (time)";
            case TEXT -> "";
        };
    }
}
