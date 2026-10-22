package logsift.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * A converted layout: a Logsift pattern, how its times are written (or ""), what kind of
     * layout it was, and where in the config it was found (an appender's name, or "").
     */
    public record Result(String pattern, String timePattern, String from, String where, boolean console) {
        public Result(String pattern, String timePattern, String from) {
            this(pattern, timePattern, from, "", false);
        }

        Result at(String where, boolean console) {
            return new Result(pattern, timePattern, from, where, console);
        }
    }

    /** Whether the text looks like a logging layout or a config file with one. */
    public static boolean looksLikeLayout(String text) {
        return text.contains("%(") || ELEMENT.matcher(text).find() || ATTRIBUTE.matcher(text).find()
                || KEY.matcher(text).find()
                || Pattern.compile("%-?\\d*(?:\\.-?\\d+)?[a-zA-Z]").matcher(text).find();
    }

    /** The layout as a Logsift pattern (a file appender's if there are several), or null if there is none. */
    public static Result convert(String text) {
        return convert(text, List.of());
    }

    /**
     * The layout as a Logsift pattern, or null if there is none. When the config has several,
     * the one most of the sample lines fit (then a file appender's, then the first).
     */
    public static Result convert(String text, List<String> sample) {
        List<Result> all = convertAll(text);
        if (all.isEmpty()) {
            return null;
        }
        Result best = null;
        int bestHits = -1;
        for (Result r : all) {
            int hits = sample == null || sample.isEmpty() ? 0 : fits(r, sample);
            if (hits > bestHits || hits == bestHits && best.console() && !r.console()) {
                best = r;
                bestHits = hits;
            }
        }
        return best;
    }

    /** How many sample lines a converted layout fits. */
    public static int fits(Result r, List<String> sample) {
        try {
            return Formats.matchCount(new CustomFormat("t", r.pattern(), r.timePattern(), "").compile(), sample)[0];
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Every layout in the text, converted (the same one used by several appenders once). */
    public static List<Result> convertAll(String text) {
        List<Result> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Config c = new Config(text);
        for (Found f : c.found) {
            Result r = f.layout.contains("%(") ? python(f.layout) : java(f.layout, c.log4j2);
            if (r == null) {
                continue;
            }
            r = r.at(f.where, f.console);
            boolean merged = false;
            for (int i = 0; i < out.size(); i++) {
                Result o = out.get(i);
                if (o.pattern().equals(r.pattern()) && o.timePattern().equals(r.timePattern())) {
                    String w = o.where().isEmpty() ? r.where()
                            : r.where().isEmpty() || o.where().contains(r.where()) ? o.where() : o.where() + ", " + r.where();
                    out.set(i, o.at(w, o.console() && r.console()));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                out.add(r);
            }
        }
        return out;
    }

    /** Why nothing could be converted (a property the layout uses is not in the text), or null. */
    public static String problem(String text) {
        if (text == null || text.isBlank() || !convertAll(text).isEmpty()) {
            return null;
        }
        Config c = new Config(text);
        if (c.missing.isEmpty()) {
            return null;
        }
        String names = String.join(", ", c.missing);
        return "The layout uses ${" + names.replace(", ", "}, ${") + "}, which "
                + (c.missing.size() == 1 ? "is" : "are") + " not defined in this text.\n"
                + "Paste the whole config file, or the value of " + names + ".";
    }

    // logback <pattern>…</pattern>, Log4j 2 <Pattern>…</Pattern>
    private static final Pattern ELEMENT = Pattern.compile(
            "(?is)<pattern>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</pattern>");
    // Log4j 2 pattern="…" / defaultPattern="…" (not filePattern)
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(?i)(?<![\\w.:-])(?:default)?pattern\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    // log4j ConversionPattern=…, Log4j 2 appender.x.layout.pattern = …, Spring Boot logging.pattern.file=…
    private static final Pattern KEY = Pattern.compile(
            "(?im)^[ \\t]*([\\w.\\-]*?(?:conversionpattern|logging\\.pattern\\.(?:console|file)|\\.layout\\.pattern))"
                    + "[ \\t]*[=:][ \\t]*(.+?)[ \\t]*$");
    // JSON "pattern": "…"
    private static final Pattern JSON = Pattern.compile("(?i)\"(?:default)?pattern\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    // YAML pattern: "…" (and Spring Boot's logging: pattern: console: …)
    private static final Pattern YAML = Pattern.compile(
            "(?im)^[ \\t]*-?[ \\t]*((?:default)?pattern|console|file)[ \\t]*:[ \\t]*(['\"]?)(.*(?:%|\\$\\{).*?)\\2[ \\t]*$");
    private static final Pattern PYTHON = Pattern.compile(
            "(?m)format\\s*[=:]\\s*['\"]?([^'\"\\r\\n]*%\\(.*?)['\"]?\\s*$");

    private static final String SPRING_DEFAULT = "%clr(%d{${LOG_DATEFORMAT_PATTERN:-yyyy-MM-dd'T'HH:mm:ss.SSSXXX}}){faint} "
            + "%clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} "
            + "${LOG_CORRELATION_PATTERN:-}%clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n"
            + "${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}";

    private record Found(String layout, String where, boolean console, int at) {}

    /** The layouts in a config, with its properties filled in. */
    private static final class Config {
        final List<Found> found = new ArrayList<>();
        final Set<String> missing = new LinkedHashSet<>();
        final Map<String, String> props = new HashMap<>();
        final boolean log4j2;
        private final String text;

        Config(String raw) {
            String t = raw.replaceAll("(?s)<!--.*?-->", "");
            boolean xml = t.stripLeading().startsWith("<");
            if (!xml) {
                t = t.replaceAll("\\\\\\R[ \\t]*", ""); // .properties lines continued with a backslash
            }
            text = t;
            log4j2 = Pattern.compile("(?i)<Appenders>|<PatternLayout|appender\\.[\\w-]+\\.type\\s*=|\"PatternLayout\"|PatternLayout\\s*:")
                    .matcher(t).find();
            readProperties(xml);
            Matcher m = ELEMENT.matcher(t);
            while (m.find()) {
                add(xmlUnescape(joinLines(m.group(1))), m.start());
            }
            m = ATTRIBUTE.matcher(t);
            while (m.find()) {
                add(xmlUnescape(m.group(1) != null ? m.group(1) : m.group(2)), m.start());
            }
            if (!xml) {
                m = KEY.matcher(t);
                while (m.find()) {
                    add(m.group(2), m.start(), keyWhere(m.group(1)));
                }
                m = JSON.matcher(t);
                while (m.find()) {
                    add(jsonUnescape(m.group(1)), m.start(), "");
                }
                if (found.isEmpty()) {
                    m = YAML.matcher(t);
                    while (m.find()) {
                        add(m.group(3), m.start(), "");
                    }
                }
                if (found.isEmpty() && Pattern.compile("(?i)logging\\.pattern\\.|LOG_(?:DATEFORMAT|LEVEL)_PATTERN")
                        .matcher(t).find()) {
                    add(SPRING_DEFAULT, 0, "Spring Boot's default");
                }
                if (found.isEmpty()) {
                    m = PYTHON.matcher(t);
                    if (m.find()) {
                        found.add(new Found(m.group(1), "", false, m.start()));
                    }
                }
            }
            if (found.isEmpty()) {
                bare(t);
            }
            found.sort(java.util.Comparator.comparingInt(Found::at));
        }

        private void bare(String t) {
            String s = t.strip();
            if (s.contains("\n")) {
                for (String line : s.split("\\R")) {
                    if (line.contains("%") || line.contains("${")) {
                        add(line.strip(), 0, "");
                        return;
                    }
                }
            } else if (s.contains("%") || s.contains("${")) {
                add(s, 0, "");
            }
        }

        private void add(String layout, int at) {
            String[] w = xmlWhere(at);
            add(layout, at, w[0], w[1] != null);
        }

        private void add(String layout, int at, String where) {
            add(layout, at, where, where.toLowerCase(Locale.ROOT).matches(".*(console|stdout|stderr).*")
                    || consoleKey(where));
        }

        private void add(String layout, int at, String where, boolean console) {
            String v = resolve(layout.strip(), 0);
            if (v.contains("%")) {
                found.add(new Found(v, where, console, at));
            }
        }

        // ---- where a layout is

        /** The appender around a layout in an XML config: {label, "console" or null}. */
        private String[] xmlWhere(int at) {
            Matcher m = Pattern.compile("<([\\w:.]+)\\b([^>]*?)\\bname\\s*=\\s*\"([^\"]+)\"([^>]*)>").matcher(text);
            String label = "";
            String console = null;
            while (m.find() && m.start() < at) {
                String tag = m.group(1);
                if (tag.matches("(?i)property|variable|springProperty|logger|root|asyncLogger|asyncRoot|Script|KeyValuePair")) {
                    continue;
                }
                String attrs = m.group(2) + m.group(4);
                Matcher cls = Pattern.compile("\\bclass\\s*=\\s*\"(?:[\\w$]+\\.)*([\\w$]+)\"").matcher(attrs);
                String kind = cls.find() ? cls.group(1) : tag;
                label = kind + " \u201c" + m.group(3) + "\u201d";
                console = (kind + " " + m.group(3)).toLowerCase(Locale.ROOT).matches(".*(console|stdout|stderr).*")
                        ? "console" : null;
            }
            return new String[] {label, console};
        }

        /** "log4j.appender.R.layout.ConversionPattern" → "appender “R”". */
        private static String keyWhere(String key) {
            Matcher m = Pattern.compile("(?i)appender\\.([\\w-]+)\\.").matcher(key);
            if (m.find()) {
                return "appender \u201c" + m.group(1) + "\u201d";
            }
            Matcher s = Pattern.compile("(?i)logging\\.pattern\\.(\\w+)").matcher(key);
            return s.find() ? "Spring Boot " + s.group(1) : "";
        }

        /** Whether the appender named in a properties key writes to the console. */
        private boolean consoleKey(String where) {
            Matcher m = Pattern.compile("appender \u201c(.+)\u201d").matcher(where);
            if (!m.find()) {
                return false;
            }
            String n = Pattern.quote(m.group(1));
            return Pattern.compile("(?im)^\\s*(?:log4j\\.)?appender\\." + n + "(?:\\.type)?\\s*[=:]\\s*\\S*Console")
                    .matcher(text).find();
        }

        // ---- properties

        private void readProperties(boolean xml) {
            Matcher m = Pattern.compile("(?is)<(?:property|variable|springProperty)\\b([^>]*?)/>").matcher(text);
            while (m.find()) {
                String name = attr(m.group(1), "name");
                String value = attr(m.group(1), "value");
                if (value == null) {
                    value = attr(m.group(1), "defaultValue");
                }
                if (name != null && value != null) {
                    props.putIfAbsent(name, xmlUnescape(value));
                }
            }
            m = Pattern.compile("(?is)<(property|variable)\\b([^>]*)>(.*?)</\\1>").matcher(text);
            while (m.find()) {
                String name = attr(m.group(2), "name");
                if (name != null) {
                    String body = m.group(3).replaceAll("(?s)^\\s*<!\\[CDATA\\[(.*)]]>\\s*$", "$1");
                    props.putIfAbsent(name, xmlUnescape(joinLines(body)));
                }
            }
            if (xml) {
                return;
            }
            m = Pattern.compile("(?m)^[ \\t]*([\\w.\\-]+)[ \\t]*[=:][ \\t]*(.*?)[ \\t]*$").matcher(text);
            while (m.find()) {
                String key = m.group(1);
                String value = unquote(m.group(2));
                props.putIfAbsent(key, value);
                if (key.startsWith("property.")) {
                    props.putIfAbsent(key.substring(9), value);
                }
            }
            // YAML / JSON lists of {name, value}
            m = Pattern.compile("(?m)name[\"']?\\s*:\\s*[\"']?([\\w.\\-]+)[\"']?\\s*,?\\s*\\R?\\s*[\"']?value[\"']?\\s*:\\s*(.+?)\\s*,?\\s*$")
                    .matcher(text);
            while (m.find()) {
                String v = m.group(2).replaceAll("\\s*}$", "");
                props.putIfAbsent(m.group(1), v.startsWith("\"") ? jsonUnescape(unquote(v)) : unquote(v));
            }
            // Spring Boot's logging.pattern.* settings fill in its default layout
            for (String[] s : new String[][] {{"dateformat", "LOG_DATEFORMAT_PATTERN"}, {"level", "LOG_LEVEL_PATTERN"},
                    {"correlation", "LOG_CORRELATION_PATTERN"}}) {
                String v = props.get("logging.pattern." + s[0]);
                if (v == null && text.contains("logging:")) {
                    Matcher y = Pattern.compile("(?m)^[ \\t]+" + s[0] + "[ \\t]*:[ \\t]*(.+?)[ \\t]*$").matcher(text);
                    v = y.find() ? unquote(y.group(1)) : null;
                }
                if (v != null) {
                    props.putIfAbsent(s[1], v);
                }
            }
        }

        /**
         * Fills in ${NAME}, ${sys:NAME}, ${env:NAME:-default}, $${…}: from the properties in the
         * text, else the default; ${PID} is the process id; others are skipped.
         */
        String resolve(String s, int depth) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < s.length()) {
                if (s.startsWith("$${", i)) {
                    i++;
                    continue;
                }
                if (!s.startsWith("${", i)) {
                    out.append(s.charAt(i++));
                    continue;
                }
                int end = matching(s, i + 1, '{', '}');
                if (end < 0) {
                    out.append(s.substring(i));
                    break;
                }
                String inner = s.substring(i + 2, end);
                i = end + 1;
                int d = topLevel(inner, ":-");
                String name = (d >= 0 ? inner.substring(0, d) : inner).strip();
                String bare = name.replaceFirst("^[a-zA-Z]+:(?=.)", "");
                String value = props.containsKey(name) ? props.get(name) : props.get(bare);
                if (bare.equals("PID") && value == null) {
                    out.append("%pid"); // Spring Boot fills in the process id
                } else if (value != null && depth < 10) {
                    out.append(resolve(value, depth + 1));
                } else if (d >= 0) {
                    out.append(resolve(inner.substring(d + 2), depth + 1));
                } else {
                    missing.add(bare);
                    out.append("%skip");
                }
            }
            return out.toString();
        }
    }

    private static int topLevel(String s, String what) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 0 && s.startsWith(what, i)) {
                return i;
            }
        }
        return -1;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?i)(?<![\\w-])" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(attrs);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    private static String unquote(String v) {
        String s = v.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** A layout written over several lines, as one. */
    private static String joinLines(String s) {
        StringBuilder out = new StringBuilder();
        for (String line : s.strip().split("\\R")) {
            if (!line.isBlank()) {
                out.append(out.isEmpty() ? "" : " ").append(line.strip());
            }
        }
        return out.toString();
    }

    private static String xmlUnescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(cp == 10 || cp == 13 ? "" : new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String jsonUnescape(String s) {
        return s.replace("\\\"", "\"").replace("\\t", "\t").replace("\\n", "").replace("\\r", "")
                .replace("\\/", "/").replace("\\\\", "\\");
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
            Map.entry("cn", "context"), Map.entry("contextName", "context"),
            Map.entry("l", "location"), Map.entry("location", "location"),
            Map.entry("x", "ndc"), Map.entry("NDC", "ndc"), Map.entry("ndc", "ndc"),
            Map.entry("N", "nanos"), Map.entry("nano", "nanos"),
            Map.entry("markerSimpleName", "marker"), Map.entry("fqcn", "fqcn"),
            Map.entry("applicationName", "app"), Map.entry("prefix", "prefix"));

    /** Words that print nothing on a normal line (stack traces, line ends, ...). */
    private static final Set<String> JAVA_NOTHING = Set.of("n", "ex", "exception", "throwable",
            "xEx", "xException", "xThrowable", "rEx", "rException", "rThrowable", "wEx", "wex",
            "nopex", "nopexception", "caller", "kvp", "xwEx", "xwException", "xwThrowable", "rootException",
            "extendedThrowable");

    /** Log4j 2 wrappers whose first {…} is a pattern. */
    private static final Set<String> BRACE_WRAPPERS = Set.of("highlight", "style", "equals", "equalsIgnoreCase",
            "replace", "maxLen", "maxLength", "encode", "enc", "notEmpty", "varsNotEmpty", "variablesNotEmpty",
            "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white", "Black", "Red", "Green",
            "Yellow", "Blue", "Magenta", "Cyan", "White", "truncate");

    /** Parts that are usually empty (left out, so lines without them fit). */
    private static final Set<String> OPTIONAL = Set.of("notEmpty", "varsNotEmpty", "variablesNotEmpty", "esb");

    private static final Map<String, String> NAMED_DATES = Map.ofEntries(
            Map.entry("ISO8601", "yyyy-MM-dd HH:mm:ss,SSS"),
            Map.entry("ISO8601_PERIOD", "yyyy-MM-dd HH:mm:ss.SSS"),
            Map.entry("ISO8601_PERIOD_MICROS", "yyyy-MM-dd HH:mm:ss.SSSSSS"),
            Map.entry("DEFAULT_PERIOD", "yyyy-MM-dd HH:mm:ss.SSS"),
            Map.entry("DEFAULT_MICROS", "yyyy-MM-dd HH:mm:ss,SSSSSS"),
            Map.entry("DEFAULT_NANOS", "yyyy-MM-dd HH:mm:ss,SSSSSSSSS"),
            Map.entry("ABSOLUTE_PERIOD", "HH:mm:ss.SSS"),
            Map.entry("ABSOLUTE_MICROS", "HH:mm:ss,SSSSSS"),
            Map.entry("ABSOLUTE_NANOS", "HH:mm:ss,SSSSSSSSS"),
            Map.entry("DATE_PERIOD", "dd MMM yyyy HH:mm:ss.SSS"),
            Map.entry("ISO8601_BASIC_PERIOD", "yyyyMMdd'T'HHmmss.SSS"),
            Map.entry("US_MONTH_DAY_YEAR2_TIME", "dd/MM/yy HH:mm:ss.SSS"),
            Map.entry("US_MONTH_DAY_YEAR4_TIME", "dd/MM/yyyy HH:mm:ss.SSS"),
            Map.entry("ISO8601_BASIC", "yyyyMMdd'T'HHmmss,SSS"),
            Map.entry("ISO8601_OFFSET_DATE_TIME_HH", "yyyy-MM-dd'T'HH:mm:ss,SSSX"),
            Map.entry("ISO8601_OFFSET_DATE_TIME_HHMM", "yyyy-MM-dd'T'HH:mm:ss,SSSXX"),
            Map.entry("ISO8601_OFFSET_DATE_TIME_HHCMM", "yyyy-MM-dd'T'HH:mm:ss,SSSXXX"),
            Map.entry("ABSOLUTE", "HH:mm:ss,SSS"),
            Map.entry("DATE", "dd MMM yyyy HH:mm:ss,SSS"),
            Map.entry("DEFAULT", "yyyy-MM-dd HH:mm:ss,SSS"),
            Map.entry("COMPACT", "yyyyMMddHHmmssSSS"));

    private static Result java(String layout, boolean log4j2) {
        StringBuilder out = new StringBuilder();
        String[] time = {""};
        Set<String> used = new HashSet<>();
        javaPart(layout, out, time, used, log4j2);
        String pattern = tidy(out.toString());
        return pattern.replace("{*}", "").contains("{")
                ? new Result(pattern, time[0], log4j2 ? "Log4j 2" : "log4j / logback") : null;
    }

    private static void javaPart(String s, StringBuilder out, String[] time, Set<String> used, boolean log4j2) {
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
            // logback %clr(...){faint}, %highlight(...), %replace(...){a, b}: the part inside counts
            if (w < n && s.charAt(w) == '(') {
                int close = matching(s, w, '(', ')');
                if (close < 0) {
                    close = n - 1;
                }
                String inner = s.substring(w + 1, close);
                i = skipOptions(s, close + 1);
                if (!word.equals("esb")) {
                    javaPart(inner, out, time, used, log4j2);
                }
                continue;
            }
            // Log4j 2 %highlight{...}{STYLE}, %style{...}{red}, %notEmpty{...}: the first part is a pattern
            if (w < n && s.charAt(w) == '{' && BRACE_WRAPPERS.contains(word)) {
                int close = matching(s, w, '{', '}');
                if (close > 0) {
                    String inner = s.substring(w + 1, close);
                    i = skipOptions(s, close + 1);
                    if (!OPTIONAL.contains(word)) {
                        javaPart(inner, out, time, used, log4j2);
                    }
                    continue;
                }
            }
            String option = null;
            int after = w;
            if (after < n && s.charAt(after) == '{') {
                int close = matching(s, after, '{', '}');
                if (close > 0) {
                    option = s.substring(after + 1, close);
                    after = skipOptions(s, close + 1); // %d{HH:mm:ss}{UTC}
                }
            }
            i = after;
            if (word.isEmpty() || JAVA_NOTHING.contains(word) || OPTIONAL.contains(word)) {
                continue;
            }
            if (word.equals("skip")) {
                out.append("{*}");
                continue;
            }
            String column;
            if (word.equals("X") || word.equals("mdc") || word.equals("MDC") || word.equals("K") || word.equals("map")) {
                column = option == null || option.isBlank() ? "mdc" : clean(option.split(":-")[0]);
            } else {
                column = JAVA_WORDS.getOrDefault(word, clean(word));
            }
            if (column.equals("time") && used.contains("time")) {
                column = "time2";
            }
            if (column.equals("time")) {
                time[0] = javaDate(option, log4j2);
            }
            out.append(placeholder(column, used));
        }
    }

    private static int skipOptions(String s, int i) {
        while (i < s.length() && s.charAt(i) == '{') {
            int close = matching(s, i, '{', '}');
            if (close < 0) {
                return i;
            }
            i = close + 1;
        }
        return i;
    }

    /** The date pattern of %d{…}: without a time zone after a comma, named ones resolved. */
    static String javaDate(String option, boolean log4j2) {
        if (option == null || option.isBlank()) {
            return NAMED_DATES.get("DEFAULT");
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
        if (log4j2 && o.startsWith("ISO8601") && !o.startsWith("ISO8601_BASIC")) {
            return NAMED_DATES.getOrDefault(o, o).replace(' ', 'T').replace("THH", "'T'HH");
        }
        return o.equals("UNIX") || o.equals("UNIX_MILLIS") ? "" : NAMED_DATES.getOrDefault(o, o);
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
