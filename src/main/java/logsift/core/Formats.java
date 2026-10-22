package logsift.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out how a log is laid out from its first lines: how entries start, its columns and its
 * timestamps, from the built-in layouts and the user's own formats (or the one chosen by hand).
 */
public final class Formats {
    private Formats() {}

    /** The name to choose for "no columns": every line is plain text. */
    public static final String PLAIN = "Plain text";

    /**
     * @param entries how entries start, or null (every line stands alone)
     * @param columns the layout as columns, or null
     * @param times how lines are timestamped, or null
     */
    public record Detected(EntryFormat entries, LogFormat columns, TimeFormat times) {}

    /**
     * @param sample the first lines of the file
     * @param custom the user's formats (ones that don't compile are skipped)
     * @param fileName the file's name, for formats set to be used for certain names
     * @param chosen the name of the format chosen by hand, {@link #PLAIN}, or null to detect it
     * @param year the year for timestamps without one
     */
    public static Detected detect(List<String> sample, List<CustomFormat> custom, String fileName,
                                  String chosen, int year) {
        List<LogFormat> mine = new ArrayList<>();
        LogFormat forFile = null;
        for (CustomFormat c : custom) {
            try {
                LogFormat f = c.compile();
                mine.add(f);
                if (forFile == null && c.appliesTo(fileName)) {
                    forFile = f;
                }
            } catch (IllegalArgumentException e) {
                // a format with a mistake in it: not used
            }
        }
        EntryFormat entries = EntryFormat.detect(sample);
        LogFormat columns;
        if (PLAIN.equals(chosen)) {
            return new Detected(entries, null, TimeFormat.detect(sample, year));
        } else if (chosen != null && find(chosen, mine, sample) != null) {
            columns = find(chosen, mine, sample);
        } else if (forFile != null) {
            columns = forFile;
        } else {
            columns = LogFormat.detect(sample, mine);
        }
        TimeFormat times = null;
        if (columns != null && columns.isCustom()) {
            if (entries == null || !agree(entries, columns, sample)) {
                entries = EntryFormat.matching(columns);
            }
            if (columns.timePattern() != null) {
                times = TimeFormat.inColumn(columns, sample, year);
            }
        }
        if (times == null) {
            times = TimeFormat.detect(sample, year);
        }
        if (times == null && columns != null && !columns.isJson()) {
            times = TimeFormat.inColumn(columns, sample, year);
        }
        return new Detected(entries, columns, times);
    }

    /** A format by name: one of the user's, a built-in layout, or JSON lines. */
    private static LogFormat find(String name, List<LogFormat> mine, List<String> sample) {
        for (LogFormat f : mine) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        for (LogFormat f : LogFormat.PRESETS) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        LogFormat json = LogFormat.detectJson(sample);
        return json != null && json.name().equals(name) ? json : null;
    }

    /**
     * Whether the quick built-in entry check gives the same answer as the format on nearly all
     * lines (then it is used, being much faster than the format's regex).
     */
    private static boolean agree(EntryFormat entries, LogFormat format, List<String> sample) {
        int lines = 0;
        int same = 0;
        for (String s : sample) {
            if (s == null || s.isBlank()) {
                continue;
            }
            lines++;
            if (entries.startsEntry(s) == format.matches(s)) {
                same++;
            }
        }
        return lines > 0 && same >= lines * 0.98;
    }

    /** How many of the non-blank lines have the layout, and how many there are. */
    public static int[] matchCount(LogFormat format, List<String> sample) {
        int lines = 0;
        int hits = 0;
        for (String s : sample) {
            if (s == null || s.isBlank()) {
                continue;
            }
            lines++;
            if (format.matches(s)) {
                hits++;
            }
        }
        return new int[] {hits, lines};
    }
}
