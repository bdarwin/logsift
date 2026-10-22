package logsift.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Log levels under their many spellings: {@code E}, {@code ERR}, {@code error} and
 * {@code SEVERE} are all errors. Used to color levels and to find error and warning lines in
 * logs with the user's own formats.
 */
public final class Levels {
    private Levels() {}

    public static final String ERROR = "ERROR";
    public static final String WARN = "WARN";
    public static final String INFO = "INFO";
    public static final String DEBUG = "DEBUG";
    public static final String TRACE = "TRACE";

    private static final Map<String, List<String>> SPELLINGS = Map.of(
            ERROR, List.of("ERROR", "ERR", "E", "FATAL", "FTL", "F", "SEVERE", "CRITICAL", "CRIT", "ALERT",
                    "EMERGENCY", "EMERG"),
            WARN, List.of("WARNING", "WARN", "WRN", "W"),
            INFO, List.of("INFO", "INF", "I", "NOTICE", "N"),
            DEBUG, List.of("DEBUG", "DBG", "D", "FINE", "CONFIG", "VERBOSE", "V"),
            TRACE, List.of("TRACE", "TRC", "T", "FINER", "FINEST"));

    /** ERROR, WARN, INFO, DEBUG or TRACE for a level as written (any case), or null. */
    public static String normalize(String level) {
        if (level == null) {
            return null;
        }
        String l = level.strip().toUpperCase(Locale.ROOT);
        for (var e : SPELLINGS.entrySet()) {
            if (e.getValue().contains(l)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * A regex for lines of the format whose level is one of {@code levels} (ERROR, WARN...), or
     * null if the format isn't the user's own or has no level column (then levels are found by
     * their usual words).
     */
    public static String lineRegex(LogFormat format, Collection<String> levels) {
        if (format == null || !format.isCustom() || !format.columns().contains("level")) {
            return null;
        }
        String p = format.regex();
        int at = p.indexOf("(?<level>");
        if (at < 0) {
            return null;
        }
        List<String> words = new ArrayList<>();
        for (String l : levels) {
            words.addAll(SPELLINGS.getOrDefault(l, List.of()));
        }
        if (words.isEmpty()) {
            return "\\A\\z."; // nothing
        }
        // the level must start with one of the spellings, and the word end there
        String look = "(?=(?i:" + String.join("|", words) + ")(?![A-Za-z]))";
        int in = at + "(?<level>".length();
        return p.substring(0, in) + look + p.substring(in);
    }
}
