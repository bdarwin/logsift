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
        return jsonKeys != null ? Json.parseObject(line) != null : pattern.matcher(line).matches();
    }

    /** The value of one column in a line, or null if the line doesn't have this layout. */
    public String value(String line, String column) {
        int i = columns.indexOf(column);
        if (i < 0) {
            return null;
        }
        if (jsonKeys != null) {
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
