package logsift.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the layout a program logs with into a Logsift pattern: a log4j, Log4j 2 or logback
 * pattern like {@code %d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n}, a whole
 * {@code logback.xml}, {@code log4j2.xml} or {@code log4j.properties} with one in it, or a
 * Python logging format like {@code %(asctime)s %(levelname)s %(name)s: %(message)s}.
 */
public final class LayoutImport {
    private LayoutImport() {}

    /** A converted layout: a Logsift pattern and how its times are written (or ""). */
    public record Result(String pattern, String timePattern, String from) {}

    /** Whether the text looks like a logging layout or a config file with one. */
    public static boolean looksLikeLayout(String text) {
        return text.contains("%(") || PATTERN_IN_CONFIG.matcher(text).find()
                || Pattern.compile("%-?\\d*(?:\\.-?\\d+)?[a-zA-Z]").matcher(text).find();
    }

    /** The layout as a Logsift pattern, or null if there is none in the text. */
    public static Result convert(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String layout = extract(text);
        if (layout == null) {
            return null;
        }
        if (layout.contains("%(")) {
            return python(layout);
        }
        return java(layout);
    }

    // logback <pattern>…</pattern>, Log4j 2 pattern="…", log4j ConversionPattern=…,
    // Spring Boot logging.pattern.console=…
    private static final Pattern PATTERN_IN_CONFIG = Pattern.compile(
            "<pattern>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</pattern>"
                    + "|\\bpattern\\s*=\\s*\"([^\"]*)\""
                    + "|(?i:conversionpattern|logging\\.pattern\\.(?:console|file)|\\.layout\\.pattern)\\s*[=:]\\s*(.+)",
            Pattern.DOTALL);

    private static String extract(String text) {
        Matcher m = PATTERN_IN_CONFIG.matcher(text);
        while (m.find()) {
            for (int g = 1; g <= 3; g++) {
                String v = m.group(g);
                if (v != null && v.contains("%")) {
                    return xmlUnescape(v.strip().split("[\\r\\n]")[0]);
                }
            }
        }
        // a format="…" (Python dictConfig / ini) or a bare layout
        Matcher py = Pattern.compile("format\\s*[=:]\\s*['\"]?([^'\"\\r\\n]*%\\(.*?)['\"]?\\s*$",
                Pattern.MULTILINE).matcher(text);
        if (py.find()) {
            return py.group(1);
        }
        String t = text.strip();
        if (t.contains("\n")) {
            for (String line : t.split("\\R")) {
                if (line.contains("%")) {
                    return line.strip();
                }
            }
        }
        return t.contains("%") ? t : null;
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    // ---------------------------------------------------------------- log4j / logback

    private static final Map<String, String> JAVA_WORDS = Map.ofEntries(
            Map.entry("d", "time"), Map.entry("date", "time"),
            Map.entry("p", "level"), Map.entry("le", "level"), Map.entry("level", "level"),
            Map.entry("t", "thread"), Map.entry("thread", "thread"), Map.entry("tn", "thread"),
            Map.entry("threadName", "thread"),
            Map.entry("c", "logger"), Map.entry("lo", "logger"), Map.entry("logger", "logger"),
            Map.entry("C", "class"), Map.entry("class", "class"),
            Map.entry("M", "method"), Map.entry("method", "method"),
            Map.entry("L", "line"), Map.entry("line", "line"),
            Map.entry("F", "file"), Map.entry("file", "file"),
            Map.entry("m", "message"), Map.entry("msg", "message"), Map.entry("message", "message"),
            Map.entry("r", "elapsed"), Map.entry("relative", "elapsed"),
            Map.entry("pid", "pid"), Map.entry("processId", "pid"),
            Map.entry("tid", "tid"), Map.entry("T", "tid"), Map.entry("threadId", "tid"),
            Map.entry("sn", "sequence"), Map.entry("sequenceNumber", "sequence"),
            Map.entry("h", "host"), Map.entry("hostName", "host"),
            Map.entry("marker", "marker"), Map.entry("mn", "marker"),
            Map.entry("u", "uuid"), Map.entry("uuid", "uuid"),
            Map.entry("cn", "context"), Map.entry("contextName", "context"));

    /** Words that print nothing on a normal line (stack traces, line ends, ...). */
    private static final Set<String> JAVA_NOTHING = Set.of("n", "ex", "exception", "throwable",
            "xEx", "xException", "xThrowable", "rEx", "rException", "rThrowable", "wEx", "wex",
            "nopex", "nopexception", "caller", "kvp", "notEmpty", "replace", "maxLen", "maxLength");

    private static final Map<String, String> NAMED_DATES = Map.of(
            "ISO8601", "yyyy-MM-dd HH:mm:ss,SSS",
            "ISO8601_BASIC", "yyyyMMdd'T'HHmmss,SSS",
            "ISO8601_OFFSET_DATE_TIME_HH", "yyyy-MM-dd'T'HH:mm:ss,SSSX",
            "ISO8601_OFFSET_DATE_TIME_HHMM", "yyyy-MM-dd'T'HH:mm:ss,SSSXX",
            "ISO8601_OFFSET_DATE_TIME_HHCMM", "yyyy-MM-dd'T'HH:mm:ss,SSSXXX",
            "ABSOLUTE", "HH:mm:ss,SSS",
            "DATE", "dd MMM yyyy HH:mm:ss,SSS",
            "DEFAULT", "yyyy-MM-dd HH:mm:ss,SSS",
            "COMPACT", "yyyyMMddHHmmssSSS");

    private static Result java(String layout) {
        StringBuilder out = new StringBuilder();
        String[] time = {""};
        Set<String> used = new HashSet<>();
        javaPart(expandProperties(layout), out, time, used);
        String pattern = tidy(out.toString());
        return pattern.contains("{") ? new Result(pattern, time[0], "log4j / logback") : null;
    }

    /** ${NAME:-default} becomes its default; ${NAME} is skipped. */
    private static String expandProperties(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("${", i)) {
                int end = matching(s, i + 1, '{', '}');
                if (end < 0) {
                    break;
                }
                String inner = s.substring(i + 2, end);
                int d = inner.indexOf(":-");
                String var = d >= 0 ? inner.substring(0, d) : inner;
                if (var.equals("PID")) {
                    out.append("%pid"); // Spring Boot fills in the process id
                } else {
                    out.append(d >= 0 ? expandProperties(inner.substring(d + 2)) : "%skip");
                }
                i = end + 1;
            } else {
                out.append(s.charAt(i++));
            }
        }
        return out.append(s.substring(i)).toString();
    }

    private static void javaPart(String s, StringBuilder out, String[] time, Set<String> used) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char e = s.charAt(i + 1);
                out.append(e == 't' ? "\t" : e == 'n' || e == 'r' ? "" : String.valueOf(e));
                i += 2;
                continue;
            }
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < n && s.charAt(i + 1) == '%') {
                out.append('%');
                i += 2;
                continue;
            }
            int j = i + 1;
            while (j < n && "-.0123456789".indexOf(s.charAt(j)) >= 0) {
                j++; // width and truncation like %-5, %.-1, %15.15
            }
            int w = j;
            while (w < n && Character.isLetter(s.charAt(w))) {
                w++;
            }
            String word = s.substring(j, w);
            // %clr(...){faint}, %highlight(...): the part inside counts
            if (w < n && s.charAt(w) == '(') {
                int close = matching(s, w, '(', ')');
                if (close < 0) {
                    close = n - 1;
                }
                String inner = s.substring(w + 1, close);
                i = close + 1;
                i = skipOption(s, i);
                if (!word.equals("replace") && !word.equals("maxLen")) {
                    javaPart(inner, out, time, used);
                } else {
                    javaPart(inner.split(",")[0].replaceAll("^\\s*\\{|}\\s*$", ""), out, time, used);
                }
                continue;
            }
            String option = null;
            int after = w;
            if (after < n && s.charAt(after) == '{') {
                int close = matching(s, after, '{', '}');
                if (close > 0) {
                    option = s.substring(after + 1, close);
                    after = close + 1;
                }
            }
            i = after;
            if (word.isEmpty() || JAVA_NOTHING.contains(word)) {
                continue;
            }
            if (word.equals("skip")) {
                out.append("{*}");
                continue;
            }
            String column;
            if (word.equals("X") || word.equals("mdc") || word.equals("MDC")) {
                column = option == null || option.isBlank() ? "mdc" : clean(option.split(":-")[0]);
            } else {
                column = JAVA_WORDS.getOrDefault(word, clean(word));
            }
            if (column.equals("time") && used.contains("time")) {
                column = "time2";
            }
            if (column.equals("time")) {
                time[0] = javaDate(option);
            }
            out.append(placeholder(column, used));
        }
    }

    private static int skipOption(String s, int i) {
        if (i < s.length() && s.charAt(i) == '{') {
            int close = matching(s, i, '{', '}');
            return close < 0 ? i : close + 1;
        }
        return i;
    }

    /** The date pattern of %d{…}: without a time zone after a comma, named ones resolved. */
    static String javaDate(String option) {
        if (option == null || option.isBlank()) {
            return NAMED_DATES.get("ISO8601");
        }
        String o = option.strip();
        if (o.startsWith("\"")) {
            int q = o.indexOf('"', 1);
            o = q > 0 ? o.substring(1, q) : o.substring(1);
        } else {
            int comma = o.lastIndexOf(',');
            if (comma > 0) {
                String tail = o.substring(comma + 1).strip();
                if (!tail.matches("S+") && tail.matches("[A-Za-z_/+\\-0-9:]+")) {
                    o = o.substring(0, comma).strip(); // %d{HH:mm:ss, UTC}
                }
            }
        }
        return NAMED_DATES.getOrDefault(o, o);
    }

    // ---------------------------------------------------------------- Python

    private static final Map<String, String> PY_WORDS = Map.ofEntries(
            Map.entry("asctime", "time"), Map.entry("levelname", "level"), Map.entry("name", "logger"),
            Map.entry("message", "message"), Map.entry("msg", "message"),
            Map.entry("threadName", "thread"), Map.entry("thread", "tid"),
            Map.entry("process", "pid"), Map.entry("processName", "process"),
            Map.entry("filename", "file"), Map.entry("lineno", "line"), Map.entry("funcName", "method"),
            Map.entry("module", "module"), Map.entry("pathname", "path"),
            Map.entry("levelno", "levelno"), Map.entry("relativeCreated", "elapsed"),
            Map.entry("created", "created"), Map.entry("msecs", "msecs"));

    private static Result python(String layout) {
        StringBuilder out = new StringBuilder();
        Set<String> used = new HashSet<>();
        Matcher m = Pattern.compile("%\\((\\w+)\\)[-#0 +]*\\d*(?:\\.\\d+)?[a-zA-Z]|%%").matcher(layout);
        int last = 0;
        boolean ms = false;
        while (m.find()) {
            out.append(layout, last, m.start());
            last = m.end();
            if (m.group().equals("%%")) {
                out.append('%');
                continue;
            }
            String word = m.group(1);
            if (word.equals("msecs") && used.contains("time")) {
                ms = true; // "%(asctime)s.%(msecs)03d"
                int dot = out.length() - 1;
                if (dot >= 0 && (out.charAt(dot) == '.' || out.charAt(dot) == ',')) {
                    out.setLength(dot);
                }
                continue;
            }
            out.append(placeholder(PY_WORDS.getOrDefault(word, clean(word)), used));
        }
        out.append(layout.substring(last));
        String pattern = tidy(out.toString());
        String time = used.contains("time") ? (ms ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm:ss,SSS") : "";
        return pattern.contains("{") ? new Result(pattern, ms ? "" : time, "Python logging") : null;
    }

    // ---------------------------------------------------------------- helpers

    private static String placeholder(String column, Set<String> used) {
        String c = column;
        for (int k = 2; used.contains(c); k++) {
            c = column + k;
        }
        used.add(c);
        return "{" + c + "}";
    }

    private static String clean(String word) {
        String w = word.replaceAll("[^A-Za-z0-9]", "");
        if (w.isEmpty() || !Character.isLetter(w.charAt(0))) {
            w = "field" + w;
        }
        return w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT))
                ? w.toLowerCase(Locale.ROOT) : Character.toLowerCase(w.charAt(0)) + w.substring(1);
    }

    /** Joins neighbouring columns with a space, and drops trailing spaces. */
    private static String tidy(String p) {
        return p.replace("}{", "} {").replaceAll("[ \\t]+$", "").replaceAll("^\\s+", "");
    }

    private static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) {
                depth++;
            } else if (s.charAt(i) == c && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
