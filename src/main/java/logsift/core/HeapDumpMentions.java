package logsift.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a JVM said it wrote a heap dump. A JVM started with -XX:+HeapDumpOnOutOfMemoryError
 * prints {@code Dumping heap to /var/dumps/java_pid4242.hprof ...} when it runs out of memory;
 * the path is relative to the JVM's working folder unless -XX:HeapDumpPath made it absolute.
 */
public final class HeapDumpMentions {
    private HeapDumpMentions() {
    }

    private static final Pattern DUMPING = Pattern.compile("Dumping heap to (.+?\\.hprof)(?=\\s|$|\\.\\.\\.)");

    /** The path in a "Dumping heap to …" line, or null. */
    public static String pathIn(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = DUMPING.matcher(line);
        return m.find() ? m.group(1).strip() : null;
    }

    /** True for a line about running out of memory, or about a heap dump being written. */
    public static boolean isAbout(String line) {
        return line != null && (line.contains("OutOfMemoryError") || line.contains("Dumping heap to "));
    }

    /** True for an absolute path on Unix or Windows ("/x", "C:\x", "\\server\x"). */
    public static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.startsWith("\\\\") || path.matches("^[A-Za-z]:[\\\\/].*");
    }

    /** The file name of a path written on Unix or Windows. */
    public static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }

    /**
     * Where to look for the dump, best first: the path as said (if absolute), then the file name
     * next to the log ({@code logDir}, written with '/').
     */
    public static List<String> candidates(String said, String logDir) {
        List<String> out = new ArrayList<>();
        if (isAbsolute(said)) {
            out.add(said);
        } else if (logDir != null) {
            out.add(logDir + "/" + said.replace('\\', '/'));
        }
        if (logDir != null) {
            String next = logDir + "/" + fileName(said);
            if (!out.contains(next)) {
                out.add(next);
            }
        }
        return out;
    }
}
