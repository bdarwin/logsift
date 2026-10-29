package logsift.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The layout of a log's lines as columns (time, level, thread, logger, message, ...), given as
 * a regex with named groups. Detected from a sample among common layouts, or set by the user.
 * Lines that don't match (stack traces, wrapped text) continue the message of the line above.
 */
public final class LogFormat {
    private final String name;
    private final Pattern pattern;          // null for JSON lines
    private final List<String> columns;
    private final java.util.Map<String, String> jsonKeys; // column -> JSON key (JSON lines only)
    private final boolean custom;           // made by the user (see CustomFormat)
    private final String timePattern;       // how the time column is written (custom only), or null
    private final char delimiter;           // CSV: the separator; 0 otherwise
    private final String header;            // CSV: the header row (not a row of values)
    private final int rowFields;            // CSV: how many fields rows usually have
    private volatile List<String> headerNames;           // the header's names, once split
    private volatile java.util.Map<String, Integer> columnIndex; // a column's place, by name

    static final String TS = "\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?";
    static final String LEVEL = "TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|SEVERE|FATAL|CRITICAL";
    /** Lines of merged logs start with a source label. */
    static final String SOURCE = "(?:<(?<source>[^>\\s]{1,40})> )?";

    /** Common layouts, most specific first. */
    static final List<LogFormat> PRESETS = List.of(
            new LogFormat("logback / log4j (time level [thread] logger - message)",
                    "^" + SOURCE + "(?<time>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+\\[(?<thread>[^\\]]*)\\]\\s+(?<logger>\\S+)\\s+-\\s(?<message>.*)$"),
            new LogFormat("log4j (time [thread] level logger - message)",
                    "^" + SOURCE + "(?<time>" + TS + ")\\s+\\[(?<thread>[^\\]]*)\\]\\s+(?<level>" + LEVEL + ")\\s+(?<logger>\\S+)\\s+-\\s(?<message>.*)$"),
            new LogFormat("Spring Boot (time level pid --- [thread] logger : message)",
                    "^" + SOURCE + "(?<time>" + TS + ")\\s+(?<level>" + LEVEL + ")\\s+(?<pid>\\d+)\\s+---\\s+(?:\\[(?<app>[^\\]]*)\\]\\s+)?\\[\\s*(?<thread>[^\\]]*?)\\]\\s+(?:\\[(?<correlation>[^\\]]*)\\]\\s+)?(?<logger>\\S+)\\s+:\\s(?<message>.*)$"),
            new LogFormat("Python (time - logger - level - message)",
                    "^" + SOURCE + "(?<time>" + TS + ")\\s+-\\s+(?<logger>\\S+)\\s+-\\s+(?<level>" + LEVEL + ")\\s+-\\s(?<message>.*)$"),
            new LogFormat("syslog (time host process[pid]: message)",
                    "^" + SOURCE + "(?<time>[A-Z][a-z]{2}\\s+\\d{1,2}\\s\\d{2}:\\d{2}:\\d{2})\\s+(?<host>\\S+)\\s+(?<process>[^:\\[\\s]+)(?:\\[(?<pid>\\d+)\\])?:\\s(?<message>.*)$"),
            new LogFormat("access log (client - user [time] \"request\" status size)",
                    "^" + SOURCE + "(?<client>\\S+) \\S+ (?<user>\\S+) \\[(?<time>[^\\]]+)\\] \"(?<request>[^\"]*)\" (?<status>\\d{3}) (?<size>\\S+)(?<message>.*)$"),
            new LogFormat("time level message",
                    "^" + SOURCE + "\\[?(?<time>" + TS + ")\\]?\\s+\\[?(?<level>" + LEVEL + ")\\]?\\s+(?<message>.*)$"),
            new LogFormat("time message",
                    "^" + SOURCE + "\\[?(?<time>" + TS + ")\\]?\\s+(?<message>.*)$"));

    /**
     * @throws PatternSyntaxException if the regex is invalid
     * @throws IllegalArgumentException if it has no named groups
     */
    public LogFormat(String name, String regex) {
        this(name, regex, false, null);
    }

    /** A format made by the user; {@code timePattern} (or null) says how its time column is written. */
    LogFormat(String name, String regex, boolean custom, String timePattern) {
        this.name = name;
        this.pattern = Pattern.compile(regex);
        this.columns = groupNames(regex);
        this.jsonKeys = null;
        this.custom = custom;
        this.timePattern = timePattern;
        this.delimiter = 0;
        this.header = null;
        this.rowFields = 0;
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("The pattern needs at least one column, like {level}");
        }
    }

    private LogFormat(String name, List<String> columns, java.util.Map<String, String> jsonKeys) {
        this.name = name;
        this.pattern = null;
        this.columns = List.copyOf(columns);
        this.jsonKeys = jsonKeys;
        this.custom = false;
        this.timePattern = null;
        this.delimiter = 0;
        this.header = null;
        this.rowFields = 0;
    }

    /** CSV: columns named by the header row. */
    private LogFormat(List<String> columns, char delimiter, String header, int rowFields) {
        this.name = CSV;
        this.pattern = null;
        this.columns = List.copyOf(columns);
        this.jsonKeys = null;
        this.custom = false;
        this.timePattern = null;
        this.delimiter = delimiter;
        this.header = header;
        this.rowFields = rowFields;
    }

    /** CSV: whether a line is the header row (which names the columns, and has no values). */
    public boolean isHeader(String line) {
        return header != null && line != null
                && (line.equals(header) || line.length() == header.length() + 1 && line.startsWith("\uFEFF")
                        && line.endsWith(header));
    }

    /** The built-in layouts, for choosing one by hand. */
    public static List<LogFormat> presets() {
        return PRESETS;
    }

    /** Whether the user made this format. */
    public boolean isCustom() {
        return custom;
    }

    /** How the time column is written (a date-time pattern like {@code dd.MM.yyyy HH:mm:ss}), or null. */
    public String timePattern() {
        return timePattern;
    }

    /** The JSON field of a column (JSON lines only), or null. */
    public String jsonKey(String column) {
        return jsonKeys == null ? null : jsonKeys.get(column);
    }

    /** Whether a line has this layout. */
    public boolean matches(String line) {
        if (delimiter != 0) {
            return line != null && split(line, null) != null;
        }
        return jsonKeys != null ? Json.parseObject(line) != null : pattern.matcher(line).matches();
    }

    /** The value of one column in a line, or null if the line doesn't have this layout. */
    public String value(String line, String column) {
        int i = columns.indexOf(column);
        if (i < 0) {
            return null;
        }
        if (jsonKeys != null || delimiter != 0) {
            String[] v = split(line, null);
            return v == null ? null : v[i];
        }
        Matcher m = pattern.matcher(line);
        return m.matches() ? m.group(column) : null;
    }

    /** Common JSON field names for each column, most usual first. */
    private static final String[][] JSON_ALIASES = {
        {"time", "@timestamp", "timestamp", "time", "ts", "datetime", "date", "@t"},
        {"level", "level", "severity", "lvl", "log.level", "levelname", "loglevel", "@l"},
        {"thread", "thread", "thread_name", "threadName"},
        {"logger", "logger", "logger_name", "loggerName", "category", "name"},
        {"message", "message", "msg", "text", "log", "@m", "@mt"}};

    /** JSON lines: columns from the usual field names, other fields together in "fields". */
    static LogFormat detectJson(List<String> sample) {
        int nonBlank = 0;
        int json = 0;
        java.util.Map<String, Integer> keyCount = new java.util.HashMap<>();
        for (String s : sample) {
            if (s == null || s.isBlank()) {
                continue;
            }
            nonBlank++;
            java.util.Map<String, Object> m = Json.parseObject(s);
            if (m != null) {
                json++;
                for (String k : m.keySet()) {
                    keyCount.merge(k, 1, Integer::sum);
                }
            }
        }
        if (nonBlank < 3 || json < nonBlank * 0.5) {
            return null;
        }
        java.util.Map<String, String> keys = new java.util.LinkedHashMap<>();
        for (String[] aliases : JSON_ALIASES) {
            for (int i = 1; i < aliases.length; i++) {
                if (keyCount.getOrDefault(aliases[i], 0) >= json / 3 && !keys.containsValue(aliases[i])) {
                    keys.put(aliases[0], aliases[i]);
                    break;
                }
            }
        }
        List<String> cols = new ArrayList<>(keys.keySet());
        cols.add(cols.contains("message") ? cols.indexOf("message") : cols.size(), "fields");
        return new LogFormat("JSON lines", cols, keys);
    }

    /** The name of the CSV format (any separator; columns from the header row). */
    public static final String CSV = "CSV";

    /**
     * CSV (or TSV): a header row naming the columns, then rows with as many fields, separated
     * by a comma, semicolon, tab or bar (whichever fits). With {@code named} (the file is called
     * .csv or .tsv, or CSV was chosen) two columns are enough; otherwise it takes three, a header
     * of names, and the other formats having found nothing.
     */
    public static LogFormat detectCsv(List<String> sample, boolean named) {
        List<String> lines = new ArrayList<>();
        for (String s : sample) {
            if (s != null && !s.isBlank()) {
                lines.add(lines.isEmpty() && s.startsWith("\uFEFF") ? s.substring(1) : s);
            }
        }
        if (lines.isEmpty() || (!named && lines.size() < 5)) {
            return null;
        }
        char best = 0;
        double bestShare = 0;
        String header = null;
        int bestWidth = 0;
        int bestRowFields = 0;
        for (char d : new char[] {',', ';', '\t', '|'}) {
            // rows, not lines: a quoted value may go over a line break
            List<String> joined = CsvRows.join(lines, d);
            String head = joined.get(0);
            List<String> rows = joined.subList(1, joined.size());
            if (!named && rows.size() < 4) {
                continue;
            }
            int n = Csv.fieldCount(head, d);
            // the rows' usual width: often the header's, but exports also name a column no row
            // fills, or end each row with one separator more than the header has
            java.util.Map<Integer, Integer> widths = new java.util.HashMap<>();
            for (String r : rows) {
                widths.merge(Csv.fieldCount(r, d), 1, Integer::sum);
            }
            int m = n;
            for (var e : widths.entrySet()) {
                if (e.getValue() > widths.getOrDefault(m, 0)) {
                    m = e.getKey();
                }
            }
            int lo = Math.min(n, m);
            int hi = Math.max(n, m);
            if (lo < (named ? 2 : 3)) {
                continue;
            }
            int same = 0;
            for (var e : widths.entrySet()) {
                if (e.getKey() >= lo && e.getKey() <= hi) {
                    same += e.getValue();
                }
            }
            double share = rows.isEmpty() ? 1 : (double) same / rows.size();
            if (share > bestShare) {
                best = d;
                bestShare = share;
                header = head;
                bestWidth = hi;
                bestRowFields = m;
            }
        }
        if (best == 0 || bestShare < (named ? 0.5 : 0.9)) {
            return null;
        }
        List<String> names = new ArrayList<>(Csv.split(header, best, null));
        if (!named) {
            for (String n : names) {
                if (!n.trim().matches("[\\p{L}_@][\\p{L}\\p{N} _.@/()-]{0,40}")) {
                    return null; // a header has names, not values
                }
            }
        }
        // a trailing separator in the header names nothing: no column for it if no row fills it
        while (names.size() > bestRowFields && names.size() > 2 && names.get(names.size() - 1).isBlank()) {
            names.remove(names.size() - 1);
            bestWidth = Math.max(names.size(), bestRowFields);
        }
        while (names.size() < bestWidth) {
            names.add(""); // values past the header's last name: "column 5"
        }
        return new LogFormat(columnNames(names), best, header, Math.min(names.size(), bestRowFields));
    }

    /**
     * The header's names as columns: the usual ones for time, level, thread, logger and message
     * are named so (Logsift knows what they hold); empty and repeated names get a number.
     */
    static List<String> columnNames(List<String> header) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim();
            String name = h.isEmpty() ? "column " + (i + 1) : h;
            for (String[] aliases : CSV_ALIASES) {
                if (!used.contains(aliases[0]) && java.util.Arrays.stream(aliases).anyMatch(h::equalsIgnoreCase)) {
                    name = aliases[0];
                    used.add(name);
                    break;
                }
            }
            String unique = name;
            for (int k = 2; out.contains(unique); k++) {
                unique = name + " " + k;
            }
            out.add(unique);
        }
        return out;
    }

    /** Header names that mean a column Logsift knows (the first of each is its name). */
    private static final String[][] CSV_ALIASES = {
        {"time", "timestamp", "@timestamp", "datetime", "date_time", "ts", "date"},
        {"level", "severity", "loglevel", "log_level", "log.level", "levelname", "lvl"},
        {"thread", "thread_name", "threadname"},
        {"logger", "logger_name", "loggername", "category"},
        {"message", "msg"}};

    /**
     * How a column is named on screen: for CSV the header's name as written ("order_id",
     * "timestamp"), else the column with a capital ("Thread").
     */
    public String displayName(String column) {
        List<String> names = headerNames();
        java.util.Map<String, Integer> at = columnIndex;
        if (at == null) {
            java.util.Map<String, Integer> m = new java.util.HashMap<>();
            for (int k = columns.size() - 1; k >= 0; k--) {
                m.put(columns.get(k), k);
            }
            columnIndex = at = m;
        }
        int i = at.getOrDefault(column, -1);
        if (names != null && i >= 0 && !names.get(i).isEmpty()) {
            return names.get(i);
        }
        return column.isEmpty() ? column : column.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + column.substring(1);
    }

    /**
     * The columns' titles for tables (Profile, Pivot): a CSV's names as written ("order_id"), its
     * column's name where the header has none ("column 5"); the columns of other formats.
     */
    public List<String> titles() {
        List<String> names = headerNames();
        if (names == null) {
            return columns;
        }
        List<String> out = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            out.add(names.get(i).isEmpty() ? columns.get(i) : names.get(i));
        }
        return out;
    }

    /** CSV: the header's names as written, one per column (null for other formats). */
    public List<String> headerNames() {
        if (header == null) {
            return null;
        }
        List<String> names = headerNames;
        if (names == null) {
            // kept: a column's title is asked for each time it is drawn, and a wide header is long
            List<String> split = new ArrayList<>(Csv.split(header, delimiter, null).stream().map(String::strip).toList());
            while (split.size() < columns.size()) {
                split.add(""); // rows have values past the header's last name
            }
            names = List.copyOf(split.subList(0, columns.size()));
            headerNames = names;
        }
        return names;
    }

    /** True for CSV (columns from the header row, not a regex). */
    public boolean isCsv() {
        return delimiter != 0;
    }

    /** The CSV separator, or 0. */
    public char delimiter() {
        return delimiter;
    }

    /** True for JSON lines (columns come from fields, not a regex). */
    public boolean isJson() {
        return jsonKeys != null;
    }

    public String name() {
        return name;
    }

    public String regex() {
        return pattern == null ? "" : pattern.pattern();
    }

    public Pattern pattern() {
        return pattern;
    }

    /** Column names in order (the named groups), for example time, level, thread, logger, message. */
    public List<String> columns() {
        return columns;
    }

    /** The layout most sample lines follow (at least 30% of entry lines), or null. */
    public static LogFormat detect(List<String> sample) {
        return detect(sample, List.of());
    }

    /**
     * Like {@link #detect(List)}, also trying the user's formats (first, so they win a tie
     * with a built-in layout).
     */
    public static LogFormat detect(List<String> sample, List<LogFormat> extra) {
        LogFormat json = detectJson(sample);
        if (json != null) {
            return json;
        }
        LogFormat best = null;
        double bestShare = 0.3;
        int nonBlank = 0;
        for (String s : sample) {
            if (s != null && !s.isBlank()) {
                nonBlank++;
            }
        }
        if (nonBlank < 3) {
            return null;
        }
        List<LogFormat> candidates = new ArrayList<>(extra);
        candidates.addAll(PRESETS);
        for (LogFormat f : candidates) {
            if (f.pattern == null) {
                continue;
            }
            int hits = 0;
            Matcher m = f.pattern.matcher("");
            for (String s : sample) {
                if (s != null && !s.isBlank() && m.reset(s).matches()) {
                    hits++;
                }
            }
            double share = (double) hits / nonBlank;
            // a tie goes to the layout with more columns (a loose one like "{word} {message}"
            // fits anything), then to the one tried first (the user's)
            boolean tie = best != null && Math.abs(share - bestShare) <= 1e-9;
            if (share > bestShare + 1e-9 || (tie && f.columns.size() > best.columns.size())) {
                best = f;
                bestShare = share;
            }
        }
        return best;
    }

    /**
     * Splits a line into column values ({@code null} for groups that didn't take part), or
     * returns null if the line doesn't have this layout. {@code starts} receives each value's
     * start index in the line (or -1).
     */
    public String[] split(String line, int[] starts) {
        if (jsonKeys != null) {
            return splitJson(line, starts);
        }
        if (delimiter != 0) {
            if (isHeader(line)) {
                return null;
            }
            return csvRow(Csv.split(line, delimiter, starts), starts);
        }
        Matcher m = pattern.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String[] out = new String[columns.size()];
        for (int i = 0; i < out.length; i++) {
            String c = columns.get(i);
            out[i] = m.group(c);
            if (starts != null) {
                starts[i] = out[i] == null ? -1 : m.start(c);
            }
        }
        return out;
    }

    /**
     * A CSV line's fields as the columns. A row with a few fields missing (or extra, joined in
     * the last column) is still a row: exports often differ from their header by a trailing
     * separator or two. A line with much fewer (one piece of a value written over several
     * lines) is not.
     */
    private String[] csvRow(List<String> v, int[] starts) {
        int n = columns.size();
        if (v.size() == n) {
            return v.toArray(new String[0]);
        }
        int usual = Math.min(n, rowFields);
        if (v.size() < 2 || v.size() < usual - usual / 2) {
            return null;
        }
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            if (i < v.size()) {
                out[i] = i == n - 1 && v.size() > n
                        ? String.join(String.valueOf(delimiter), v.subList(i, v.size())) : v.get(i);
            } else if (starts != null) {
                starts[i] = -1;
            }
        }
        return out;
    }

    /**
     * CSV: the header row's names, each in its column (null for any other line). The view shows
     * them in the columns, so the header doesn't look like an empty row.
     */
    public String[] headerCells(String line, int[] starts) {
        if (delimiter == 0 || !isHeader(line)) {
            return null;
        }
        String[] v = Csv.split(line, delimiter, starts).toArray(new String[0]);
        if (v.length > 0 && v[0].startsWith("\uFEFF")) {
            v[0] = v[0].substring(1);
            if (starts != null && starts.length > 0) {
                starts[0]++;
            }
        }
        if (v.length != columns.size()) {
            // a header naming more columns than rows fill (dropped), or fewer than rows have
            String[] w = java.util.Arrays.copyOf(v, columns.size());
            for (int i = v.length; i < w.length; i++) {
                w[i] = "";
                if (starts != null && i < starts.length) {
                    starts[i] = -1;
                }
            }
            v = w;
        }
        return v;
    }

    private String[] splitJson(String line, int[] starts) {
        java.util.Map<String, Object> m = Json.parseObject(line);
        if (m == null) {
            return null;
        }
        java.util.Map<String, Object> rest = new java.util.LinkedHashMap<>(m);
        String[] out = new String[columns.size()];
        for (int i = 0; i < out.length; i++) {
            String key = jsonKeys.get(columns.get(i));
            if (key != null && m.containsKey(key)) {
                out[i] = Json.display(m.get(key));
                rest.remove(key);
            }
            if (starts != null) {
                starts[i] = -1;
            }
        }
        int f = columns.indexOf("fields");
        StringBuilder sb = new StringBuilder();
        for (var e : rest.entrySet()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(e.getKey()).append('=').append(Json.display(e.getValue()));
        }
        out[f] = sb.toString();
        return out;
    }

    /** Named groups in the order they appear in the regex. */
    static List<String> groupNames(String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(regex);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return name;
    }
}
