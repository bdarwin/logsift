package logmaster.core;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * How a new log entry starts, so that the lines under it (stack traces, wrapped messages) can
 * be kept with it. Detected from a sample of the file: for example lines starting with a date
 * like {@code 2026-10-21}, a time like {@code [10:02:13}, a syslog date, a level word, or
 * {@code {} for JSON lines. Lines that don't start that way continue the entry above.
 *
 * <p>Shapes are checked on raw bytes at the start of each line: {@code d} is a digit,
 * {@code D} a digit or space, {@code A} an uppercase and {@code a} a lowercase ASCII letter;
 * anything else must match exactly.
 */
public final class EntryFormat {
    private final String name;
    private final byte[] shape;      // null for level words
    private final byte[][] words;    // level words (when shape is null)

    private EntryFormat(String name, String shape, String... words) {
        this.name = name;
        this.shape = shape == null ? null : shape.getBytes(StandardCharsets.US_ASCII);
        this.words = new byte[words.length][];
        for (int i = 0; i < words.length; i++) {
            this.words[i] = words[i].getBytes(StandardCharsets.US_ASCII);
        }
    }

    /** Candidates, most specific first. */
    static final List<EntryFormat> CANDIDATES = List.of(
            new EntryFormat("date and time (2026-10-21 10:02:13)", "dddd-dd-dd"),
            new EntryFormat("[date] ([2026-10-21 …)", "[dddd-dd-dd"),
            new EntryFormat("date (2026/10/21)", "dddd/dd/dd"),
            new EntryFormat("date (21-10-2026)", "dd-dd-dddd"),
            new EntryFormat("date (21/10/2026)", "dd/dd/dddd"),
            new EntryFormat("date (21.10.2026)", "dd.dd.dddd"),
            new EntryFormat("date (21-Oct-2026)", "dd-Aaa-dddd"),
            new EntryFormat("syslog date (Oct 21 10:02:13)", "Aaa Dd dd:dd:dd"),
            new EntryFormat("time (10:02:13)", "dd:dd:dd"),
            new EntryFormat("[time] ([10:02:13)", "[dd:dd:dd"),
            new EntryFormat("epoch seconds (1729500000)", "dddddddddd"),
            new EntryFormat("JSON lines ({…})", "{"),
            new EntryFormat("level first (ERROR …)", null,
                    "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "FATAL", "SEVERE", "NOTICE",
                    "[ERROR", "[WARN", "[INFO", "[DEBUG", "[TRACE", "[FATAL"));

    /** A short description for the UI, like "date and time (2026-10-21 10:02:13)". */
    public String name() {
        return name;
    }

    /**
     * The format most lines of the sample start with, or null if no format fits at least 30%
     * of the non-blank lines (then every line is its own entry).
     */
    public static EntryFormat detect(List<String> sample) {
        EntryFormat best = null;
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
        for (EntryFormat f : CANDIDATES) {
            int hits = 0;
            for (String s : sample) {
                if (s != null && !s.isBlank() && f.startsEntry(s)) {
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

    /** Whether this line starts a new entry. */
    public boolean startsEntry(String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return startsEntry(b, 0, b.length);
    }

    /** Whether the line in {@code buf[start, end)} starts a new entry. */
    boolean startsEntry(byte[] buf, int start, int end) {
        if (shape != null) {
            if (end - start < shape.length) {
                return false;
            }
            for (int i = 0; i < shape.length; i++) {
                if (!fits(shape[i], buf[start + i])) {
                    return false;
                }
            }
            return true;
        }
        for (byte[] w : words) {
            if (end - start >= w.length && startsWith(buf, start, w)) {
                // the word must end there (ERROR, not ERRORS)
                int after = start + w.length;
                if (after == end || !Character.isLetterOrDigit(buf[after] & 0xFF)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] buf, int start, byte[] w) {
        for (int i = 0; i < w.length; i++) {
            if (buf[start + i] != w[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean fits(byte pattern, byte b) {
        return switch (pattern) {
            case 'd' -> b >= '0' && b <= '9';
            case 'D' -> (b >= '0' && b <= '9') || b == ' ';
            case 'A' -> b >= 'A' && b <= 'Z';
            case 'a' -> b >= 'a' && b <= 'z';
            default -> b == pattern;
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
