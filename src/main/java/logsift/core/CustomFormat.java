package logsift.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A log layout the user described, as a simple pattern like
 * {@code {time} {level} [{thread}] {logger} - {message}} (or a regex with named groups).
 *
 * <p>In a pattern, {@code {name}} is a column and everything else is matched as written, with
 * any run of spaces matching one or more spaces or tabs. {@code {time}} takes a timestamp
 * (any common one, or exactly {@code timePattern} when given), {@code {level}} a level word,
 * {@code {*}} skips a part, and other columns take everything up to the next character of the
 * pattern (the last column takes the rest of the line).
 *
 * @param name shown in menus
 * @param pattern the layout of a line
 * @param timePattern how times are written, like {@code dd.MM.yyyy HH:mm:ss.SSS}, or empty
 * @param files file names this format is always used for, like {@code app*.log; *.trace}, or empty
 */
public record CustomFormat(String name, String pattern, String timePattern, String files) {

    public CustomFormat {
        name = name == null ? "" : name.strip();
        pattern = pattern == null ? "" : pattern;
        timePattern = timePattern == null ? "" : timePattern.strip();
        files = files == null ? "" : files.strip();
    }

    /** Timestamps {@code {time}} takes when no time pattern is given (all those Logsift reads). */
    static final String ANY_TIME = String.join("|",
            "\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?",
            "\\d{2}[-/.]\\d{2}[-/.]\\d{4} \\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?",
            "\\d{2}[-/][A-Z][a-z]{2}[-/]\\d{4}[ :]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?: [+-]\\d{4})?",
            "[A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2}",
            "\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?",
            "\\d{13}|\\d{10}(?:\\.\\d+)?");

    /** Level words {@code {level}} takes, any case (also one-letter levels like {@code E}). */
    static final String ANY_LEVEL = "(?i:TRACE|DEBUG|INFO|NOTICE|WARNING|WARN|ERROR|ERR|SEVERE|FATAL"
            + "|CRITICAL|CRIT|ALERT|EMERGENCY|EMERG|FINEST|FINER|FINE|CONFIG|VERBOSE"
            + "|WRN|INF|DBG|TRC|FTL|[TDIWEFV])";

    /** Whether the pattern is a regex with named groups rather than a {@code {column}} pattern. */
    public static boolean isRegex(String pattern) {
        return pattern.contains("(?<");
    }

    /**
     * The format as columns.
     *
     * @throws IllegalArgumentException with a message for the user, if the pattern can't be used
     */
    public LogFormat compile() {
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Enter a pattern, like {time} {level} {message}");
        }
        String time = timePattern.isEmpty() ? null : timePattern;
        if (time != null) {
            formatter(time); // checks it
        }
        String regex = isRegex(pattern) ? pattern : toRegex(pattern, time);
        try {
            return new LogFormat(name.isEmpty() ? "Custom" : name, regex, true, time);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("The regular expression is invalid: " + e.getDescription());
        }
    }

    /** Whether this format is set to be used for a file with this name. */
    public boolean appliesTo(String fileName) {
        if (files.isEmpty() || fileName == null) {
            return false;
        }
        for (String glob : files.split("[;,]")) {
            glob = glob.strip();
            if (!glob.isEmpty() && globRegex(glob).matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern globRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ---------------------------------------------------------------- pattern to regex

    /** The regex for a {@code {column}} pattern ({@code time}: how times are written, or null). */
    static String toRegex(String template, String time) {
        return toRegexWithTime(template, time != null ? timeRegex(time) : ANY_TIME);
    }

    /** Like {@link #toRegex}, with the regex {time} takes. */
    static String toRegexWithTime(String template, String timeRegex) {
        StringBuilder re = new StringBuilder("^").append(LogFormat.SOURCE);
        Set<String> names = new HashSet<>();
        int n = template.length();
        int i = 0;
        boolean any = false;
        while (i < n) {
            char c = template.charAt(i);
            if (c == '{') {
                int j = template.indexOf('}', i);
                if (j < 0) {
                    throw new IllegalArgumentException("A { has no closing }");
                }
                String name = template.substring(i + 1, j).strip();
                boolean skip = name.equals("*");
                if (!skip && !name.matches("[A-Za-z][A-Za-z0-9]*")) {
                    throw new IllegalArgumentException("Column names are letters and digits, like {thread}: {" + name + "}");
                }
                if (!skip && name.equalsIgnoreCase("source")) {
                    throw new IllegalArgumentException("{source} is used by merged logs; choose another name");
                }
                if (!skip && !names.add(name)) {
                    throw new IllegalArgumentException("{" + name + "} is used twice");
                }
                int after = j + 1;
                if (after < n && template.charAt(after) == '{') {
                    throw new IllegalArgumentException("Put a space or another character between {"
                            + name + "} and the next column");
                }
                String body;
                if (name.equals("time")) {
                    body = timeRegex;
                } else if (name.equals("level")) {
                    body = ANY_LEVEL;
                } else if (after >= n) {
                    body = ".*";
                } else {
                    char next = template.charAt(after);
                    body = Character.isWhitespace(next) ? "\\S+" : "[^" + escapeInClass(next) + "]*";
                }
                if (name.equals("level")) {
                    re.append("\\s*(?<level>").append(body).append(")\\s*"); // padded levels like "INFO "
                } else if (skip) {
                    re.append("(?:").append(body).append(')');
                } else {
                    re.append("(?<").append(name).append('>').append(body).append(')');
                    any = true;
                }
                any |= name.equals("level");
                i = after;
            } else if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(template.charAt(i))) {
                    i++;
                }
                // before a last free column (the message) the space is optional: it may be empty
                String tail = template.substring(i);
                boolean lastFree = tail.matches("\\{\\s*[A-Za-z][A-Za-z0-9]*\\s*}")
                        && !tail.matches("\\{\\s*(time|level)\\s*}");
                re.append(i >= n || lastFree ? "\\s*" : "\\s+");
            } else {
                re.append(escape(c));
                i++;
            }
        }
        if (!any) {
            throw new IllegalArgumentException("The pattern needs at least one column, like {level} or {message}");
        }
        return re.append('$').toString();
    }

    private static String escape(char c) {
        return "\\.[]{}()*+?^$|/".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c);
    }

    private static String escapeInClass(char c) {
        return "\\[]^-".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c);
    }

    // ---------------------------------------------------------------- time patterns

    /** A parser for a date-time pattern like {@code dd.MM.yyyy HH:mm:ss.SSS}. */
    static DateTimeFormatter formatter(String timePattern) {
        try {
            return new DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient()
                    .appendPattern(timePattern).toFormatter(Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The time format is invalid: " + e.getMessage());
        }
    }

    /** A regex for text written with a date-time pattern. */
    static String timeRegex(String p) {
        StringBuilder re = new StringBuilder();
        int n = p.length();
        int i = 0;
        while (i < n) {
            char c = p.charAt(i);
            if (c == '\'') {
                int j = i + 1;
                StringBuilder lit = new StringBuilder();
                while (j < n) {
                    if (p.charAt(j) == '\'') {
                        if (j + 1 < n && p.charAt(j + 1) == '\'') {
                            lit.append('\'');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    lit.append(p.charAt(j++));
                }
                if (j == i + 1) {
                    lit.append('\''); // '' is a quote
                }
                for (char l : lit.toString().toCharArray()) {
                    re.append(escape(l));
                }
                i = j + 1;
                continue;
            }
            if (c == '[' || c == ']') {
                throw new IllegalArgumentException("Optional parts [ ] aren't supported in the time format");
            }
            if (!Character.isLetter(c)) {
                re.append(c == ' ' ? " +" : escape(c));
                i++;
                continue;
            }
            int j = i;
            while (j < n && p.charAt(j) == c) {
                j++;
            }
            int k = j - i;
            re.append(switch (c) {
                case 'y', 'u' -> k == 2 ? "\\d{2}" : "\\d{4}";
                case 'M', 'L' -> k == 1 ? "\\d{1,2}" : k == 2 ? "\\d{2}" : k == 3 ? "[A-Za-z]{3}\\.?" : "[A-Za-z]+";
                case 'd', 'H', 'h', 'k', 'K', 'm', 's' -> k == 1 ? "\\d{1,2}" : "\\d{2}";
                case 'S' -> "\\d{" + k + "}";
                case 'n' -> "\\d+";
                case 'D' -> "\\d{1,3}";
                case 'a' -> "[AaPp]\\.?[Mm]\\.?";
                case 'E' -> k <= 3 ? "[A-Za-z]{3}" : "[A-Za-z]+";
                case 'z', 'V' -> "\\S+";
                case 'Z' -> "(?:[+-]\\d{2}:?\\d{2}|Z)";
                case 'X', 'x', 'O' -> "(?:Z|GMT|[+-]\\d{1,2}(?::?\\d{2})?)";
                default -> throw new IllegalArgumentException("The time format has an unknown letter: " + c);
            });
            i = j;
        }
        return re.toString();
    }

    // ---------------------------------------------------------------- storage

    /** Formats saved in a file (none if it doesn't exist or can't be read). */
    public static List<CustomFormat> load(Path file) {
        List<CustomFormat> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return out;
        }
        for (int i = 1; p.containsKey("format." + i + ".name"); i++) {
            String k = "format." + i + ".";
            out.add(new CustomFormat(p.getProperty(k + "name"), p.getProperty(k + "pattern", ""),
                    p.getProperty(k + "time", ""), p.getProperty(k + "files", "")));
        }
        return out;
    }

    public static void save(Path file, List<CustomFormat> formats) throws IOException {
        Properties p = new Properties();
        int i = 1;
        for (CustomFormat f : formats) {
            String k = "format." + i++ + ".";
            p.setProperty(k + "name", f.name());
            p.setProperty(k + "pattern", f.pattern());
            p.setProperty(k + "time", f.timePattern());
            p.setProperty(k + "files", f.files());
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "Logsift log formats");
        }
    }
}
