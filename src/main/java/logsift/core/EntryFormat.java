package logsift.core;

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
    private final LogFormat lines;   // or: lines with this layout start entries

    private EntryFormat(String name, String shape, String... words) {
        this.lines = null;
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

    private EntryFormat(LogFormat lines) {
        this.name = "lines in the format " + lines.name();
        this.shape = null;
        this.words = new byte[0][];
        this.lines = lines;
    }

    /** Entries start with each line that has this layout (for the user's own formats). */
    public static EntryFormat matching(LogFormat format) {
        return new EntryFormat(format);
    }

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

    /** The lines of one entry: {@code first..last} (inclusive) and their text. */
    public record Entry(long first, long last, java.util.List<String> lines) {}

    private static final java.util.regex.Pattern TRACE_LINE = java.util.regex.Pattern.compile(
            "^(\\s|at |Caused by:|Suppressed:|\\.\\.\\. \\d+ (more|common frames omitted)|[a-zA-Z_$][\\w$]*(\\.[\\w$]+)+(Exception|Error|Throwable)(:|$)).*",
            java.util.regex.Pattern.DOTALL);

    /**
     * The entry that {@code line} belongs to: the line that starts it and the lines under it
     * (a stack trace, a wrapped message). Without a format, lines that look like part of a stack
     * trace (indented, {@code at …}, {@code Caused by: …}) continue the entry. At most
     * {@code maxLines} lines are looked at either way.
     */
    public static Entry around(LogDocument doc, EntryFormat format, long line, int maxLines) {
        long n = doc.lineCount();
        if (line < 0 || line >= n) {
            return new Entry(line, line, java.util.List.of());
        }
        java.util.function.Predicate<String> starts = format == null
                ? s -> !TRACE_LINE.matcher(s).matches()
                : s -> !s.isEmpty() && format.startsEntry(s);
        // up to the line that starts the entry
        int back = (int) Math.min(line, maxLines);
        String[] up = new String[back + 1];
        doc.readLines(line - back, back + 1, up);
        int i = back;
        while (i > 0 && up[i] != null && !starts.test(up[i])) {
            i--;
        }
        long first = line - back + i;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int k = i; k <= back; k++) {
            out.add(up[k] == null ? "" : up[k]);
        }
        // down while lines continue it
        long last = line;
        long chars = 0;
        int chunk = 256;
        outer:
        while (last + 1 < n && out.size() < 2 * maxLines) {
            int k = (int) Math.min(chunk, n - last - 1);
            String[] buf = new String[k];
            doc.readLines(last + 1, k, buf);
            for (String s : buf) {
                if (s == null || starts.test(s) || out.size() >= 2 * maxLines || chars > 4_000_000) {
                    break outer;
                }
                out.add(s);
                chars += s.length();
                last++;
            }
            chunk = Math.min(4096, chunk * 2);
        }
        // blank lines at the end belong to nobody
        while (last > line && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
            last--;
        }
        return new Entry(first, last, out);
    }

    /** Whether this line starts a new entry. */
    public boolean startsEntry(String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return startsEntry(b, 0, b.length);
    }

    /**
     * Skips a source label like {@code <node1> } at the start of a line (lines of merged logs
     * start with one). Returns the position after it, or {@code start} if there is none.
     */
    static int skipSourceTag(byte[] b, int start, int end) {
        if (start < end && b[start] == '<') {
            int limit = Math.min(end - 1, start + 48);
            for (int i = start + 1; i < limit; i++) {
                byte c = b[i];
                if (c == '>') {
                    return i > start + 1 && b[i + 1] == ' ' ? i + 2 : start;
                }
                if (c == ' ' || c == '<') {
                    return start;
                }
            }
        }
        return start;
    }

    /** Whether the line in {@code buf[start, end)} starts a new entry. */
    boolean startsEntry(byte[] buf, int start, int end) {
        if (lines != null) {
            return lines.matches(new String(buf, start, end - start, StandardCharsets.UTF_8));
        }
        start = skipSourceTag(buf, start, end);
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
