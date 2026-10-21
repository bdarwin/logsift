package logmaster.core;

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
    private final Pattern pattern;
    private final List<String> columns;

    private static final String TS = "\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?";
    private static final String LEVEL = "TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|SEVERE|FATAL|CRITICAL";
    /** Lines of merged logs start with a source label. */
    private static final String SOURCE = "(?:<(?<source>[^>\\s]{1,40})> )?";

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
        this.name = name;
        this.pattern = Pattern.compile(regex);
        this.columns = groupNames(regex);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("The pattern needs named groups like (?<level>...)");
        }
    }

    public String name() {
        return name;
    }

    public String regex() {
        return pattern.pattern();
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
        for (LogFormat f : PRESETS) {
            int hits = 0;
            Matcher m = f.pattern.matcher("");
            for (String s : sample) {
                if (s != null && !s.isBlank() && m.reset(s).matches()) {
                    hits++;
                }
            }
            double share = (double) hits / nonBlank;
            if (share > bestShare + 1e-9) {
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
