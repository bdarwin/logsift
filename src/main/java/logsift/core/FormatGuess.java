package logsift.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests a {@link CustomFormat} pattern from a log's lines: a timestamp at the start, a level
 * word, a [thread], a logger name, separators like {@code -} and {@code :}, and the message; or
 * columns split by {@code |}, {@code ;} or tabs. The suggestion that fits most lines wins.
 */
public final class FormatGuess {
    private FormatGuess() {}

    /** A suggested pattern, and how many of the sample's non-blank lines have it. */
    public record Guess(String pattern, String timePattern, int matching, int lines) {}

    /** Dates in any order with a time, like 21.10.26 10:02:13.250 (the time format is guessed). */
    private static final String OTHER_TIME =
            "\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}[ T]\\d{1,2}:\\d{2}(?::\\d{2})?(?:[.,]\\d{1,9})?";
    private static final Pattern TIME_AT_START = Pattern.compile(
            "^(\\[?)(" + CustomFormat.ANY_TIME + "|" + OTHER_TIME + ")(]?)");

    private static final Pattern LEVEL = Pattern.compile("^" + CustomFormat.ANY_LEVEL + "$");
    private static final Pattern LOGGER = Pattern.compile("^[A-Za-z_$][\\w$]*(?:[.:/][\\w$]+)+:?$");

    /** The best pattern for the sample, or null if no line has any structure. */
    public static Guess guess(List<String> sample) {
        List<String> lines = new ArrayList<>();
        for (String s : sample) {
            if (s != null && !s.isBlank()) {
                lines.add(s);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        int tried = 0;
        for (String l : lines) {
            if (tried >= 40) {
                break;
            }
            if (Character.isWhitespace(l.charAt(0))) {
                continue; // a continuation line (stack trace, wrapped text)
            }
            tried++;
            String d = delimited(l, lines);
            if (d != null && !candidates.contains(d)) {
                candidates.add(d);
            }
            String w = words(l);
            if (w != null && !candidates.contains(w)) {
                candidates.add(w);
            }
        }
        Guess best = null;
        for (String c : candidates) {
            String time = c.contains("{time}") ? timePattern(c, lines) : "";
            LogFormat f;
            try {
                f = new CustomFormat("Guess", c, time, "").compile();
            } catch (IllegalArgumentException e) {
                continue;
            }
            int[] count = Formats.matchCount(f, lines);
            // prefer fitting more lines, then more columns
            if (best == null || count[0] > best.matching()
                    || (count[0] == best.matching() && columns(c) > columns(best.pattern()))) {
                best = new Guess(c, time, count[0], count[1]);
            }
        }
        return best == null || best.matching() == 0 ? null : best;
    }

    /** Whether Logsift's own timestamp reader reads every value (then no time format is needed). */
    private static boolean readByItself(List<String> values) {
        TimeFormat tf = TimeFormat.detect(values, 2000);
        if (tf == null) {
            return false;
        }
        for (String v : values) {
            if (tf.parse(v) == TimeFormat.NONE || !KNOWN.matcher(v).matches()) {
                return false;
            }
        }
        return true;
    }

    private static final Pattern KNOWN = Pattern.compile("\\[?(?:" + CustomFormat.ANY_TIME + ")]?");

    /**
     * The time format for a pattern's {time} values, or "" when they are timestamps Logsift
     * reads by itself. Day and month order come from values that can only be read one way.
     */
    static String timePattern(String pattern, List<String> lines) {
        LogFormat loose;
        try {
            loose = new LogFormat("t", CustomFormat.toRegexWithTime(pattern, OTHER_TIME + "|" + CustomFormat.ANY_TIME),
                    true, null);
        } catch (IllegalArgumentException e) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (String l : lines) {
            String v = loose.value(l, "time");
            if (v != null) {
                values.add(v);
            }
        }
        if (values.isEmpty() || readByItself(values)) {
            return "";
        }
        Matcher m = Pattern.compile("(\\d{1,4})([-/.])(\\d{1,2})[-/.](\\d{1,4})([ T])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:([.,])(\\d{1,9}))?")
                .matcher(values.get(0));
        if (!m.matches()) {
            return "";
        }
        boolean yearFirst = m.group(1).length() == 4;
        boolean dayFirst = true;
        if (!yearFirst) {
            String sep = m.group(2);
            boolean firstOver12 = false;
            boolean secondOver12 = false;
            for (String v : values) {
                Matcher x = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.]").matcher(v);
                if (x.lookingAt()) {
                    firstOver12 |= Integer.parseInt(x.group(1)) > 12;
                    secondOver12 |= Integer.parseInt(x.group(2)) > 12;
                }
            }
            // 10/21/26 is month first; with nothing to tell, "/" is usually month first (US)
            dayFirst = firstOver12 || (!secondOver12 && !sep.equals("/"));
        }
        String sep = m.group(2);
        String year = m.group(4).length() == 4 ? "yyyy" : "yy";
        String date;
        if (yearFirst) {
            date = "yyyy" + sep + (m.group(3).length() == 1 ? "M" : "MM") + sep + (m.group(4).length() == 1 ? "d" : "dd");
        } else {
            String first = m.group(1).length() == 1 ? "1" : "2";
            String second = m.group(3).length() == 1 ? "1" : "2";
            String d = dayFirst ? first : second;
            String mo = dayFirst ? second : first;
            String dd = d.equals("1") ? "d" : "dd";
            String mm = mo.equals("1") ? "M" : "MM";
            date = dayFirst ? dd + sep + mm + sep + year : mm + sep + dd + sep + year;
        }
        String t = (m.group(6).length() == 1 ? "H" : "HH") + ":mm" + (m.group(8) != null ? ":ss" : "");
        if (m.group(10) != null) {
            t += m.group(9) + "S".repeat(m.group(10).length());
        }
        String sepT = m.group(5).equals("T") ? "'T'" : " ";
        return date + sepT + t;
    }

    private static int columns(String pattern) {
        int n = 0;
        for (int i = pattern.indexOf('{'); i >= 0; i = pattern.indexOf('{', i + 1)) {
            n++;
        }
        return n;
    }

    /** Columns split by | ; or tabs, when most lines have the same number of them. */
    private static String delimited(String line, List<String> lines) {
        for (char sep : new char[] {'|', '\t', ';'}) {
            int k = count(line, sep);
            if (k < 2) {
                continue;
            }
            int same = 0;
            for (String l : lines) {
                if (count(l, sep) == k) {
                    same++;
                }
            }
            if (same < lines.size() * 0.5) {
                continue;
            }
            String[] parts = line.split(Pattern.quote(String.valueOf(sep)), -1);
            StringBuilder p = new StringBuilder();
            boolean time = false;
            boolean level = false;
            for (int i = 0; i < parts.length; i++) {
                String v = parts[i].strip();
                String lead = parts[i].substring(0, parts[i].indexOf(v.isEmpty() ? parts[i] : v));
                String name;
                if (i == parts.length - 1) {
                    name = "message";
                } else if (!time && TIME_AT_START.matcher(v).matches() && v.length() >= 8) {
                    name = "time";
                    time = true;
                } else if (!level && LEVEL.matcher(v).matches()) {
                    name = "level";
                    level = true;
                } else {
                    name = "field" + (i + 1);
                }
                if (i > 0) {
                    p.append(sep);
                }
                p.append(lead.isEmpty() ? "" : " ").append('{').append(name).append('}');
                if (i < parts.length - 1 && parts[i].endsWith(" ") && !v.isEmpty()) {
                    p.append(' ');
                }
            }
            return p.toString();
        }
        return null;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** time, level, [thread], logger and separators from the start of the line, then the message. */
    private static String words(String line) {
        StringBuilder p = new StringBuilder();
        String rest = line;
        boolean time = false;
        boolean level = false;
        boolean thread = false;
        boolean logger = false;
        Matcher tm = TIME_AT_START.matcher(rest);
        if (tm.find() && tm.group(1).length() == tm.group(3).length()) {
            p.append(tm.group(1)).append("{time}").append(tm.group(3));
            rest = rest.substring(tm.end());
            time = true;
        }
        int fields = 0;
        while (!rest.isEmpty() && fields < 8) {
            int sp = 0;
            while (sp < rest.length() && Character.isWhitespace(rest.charAt(sp))) {
                sp++;
            }
            if (sp == rest.length()) {
                break;
            }
            String gap = sp > 0 ? " " : "";
            String r = rest.substring(sp);
            String token;
            String piece;
            if (r.charAt(0) == '[' || r.charAt(0) == '(' || r.charAt(0) == '<') {
                char close = r.charAt(0) == '[' ? ']' : r.charAt(0) == '(' ? ')' : '>';
                int end = r.indexOf(close);
                if (end < 0) {
                    break;
                }
                String inside = r.substring(1, end);
                if (!level && LEVEL.matcher(inside.strip()).matches()) {
                    piece = r.charAt(0) + "{level}" + close;
                    level = true;
                } else if (!thread && !inside.isEmpty()) {
                    piece = r.charAt(0) + "{thread}" + close;
                    thread = true;
                } else {
                    break;
                }
                token = r.substring(0, end + 1);
            } else {
                int end = 0;
                while (end < r.length() && !Character.isWhitespace(r.charAt(end))) {
                    end++;
                }
                token = r.substring(0, end);
                if (token.equals("-") || token.equals(":") || token.equals("|") || token.equals("---")
                        || token.equals("--") || token.equals(">")) {
                    piece = token;
                } else if (!level && LEVEL.matcher(token.replaceAll("[:]$", "")).matches()) {
                    piece = token.endsWith(":") ? "{level}:" : "{level}";
                    level = true;
                } else if (!logger && (level || time) && LOGGER.matcher(token).matches()) {
                    piece = token.endsWith(":") ? "{logger}:" : "{logger}";
                    logger = true;
                } else if (!time && !level && fields == 0 && token.matches("\\d+")) {
                    piece = "{pid}";
                } else {
                    break;
                }
            }
            p.append(gap).append(piece);
            rest = r.substring(token.length());
            fields++;
        }
        if (!time && !level && !thread && !logger) {
            return null;
        }
        if (!rest.isBlank()) {
            p.append(' ').append("{message}");
        } else {
            p.append(" {message}");
        }
        return p.toString().strip();
    }
}
