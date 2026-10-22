package logsift.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A running JVM: its process id, what it runs ({@code com.acme.App}, {@code app.jar}), the whole
 * command, the OS user it runs as, how long it has been running, and whether it is in a
 * container (seen from the host).
 */
public record JvmProcess(long pid, String name, String command, String user, String uptime, boolean container) {

    /** Options of the {@code java} command that take the next word as their value. */
    private static final Set<String> WITH_VALUE = Set.of("-cp", "-classpath", "--class-path", "-p",
            "--module-path", "--upgrade-module-path", "--add-modules", "--limit-modules", "--add-opens",
            "--add-exports", "--add-reads", "--patch-module", "--enable-native-access", "--source", "-splash");

    /** What a {@code java} command line runs: the jar, the module's class, or the main class. */
    public static String describe(String command) {
        List<String> words = words(command);
        if (words.isEmpty()) {
            return "";
        }
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            if (w.equals("-jar") && i + 1 < words.size()) {
                String jar = words.get(i + 1);
                return jar.substring(Math.max(jar.lastIndexOf('/'), jar.lastIndexOf('\\')) + 1);
            }
            if ((w.equals("-m") || w.equals("--module")) && i + 1 < words.size()) {
                String m = words.get(i + 1);
                return m.substring(m.indexOf('/') + 1);
            }
            if (w.startsWith("--module=")) {
                String m = w.substring("--module=".length());
                return m.substring(m.indexOf('/') + 1);
            }
            if (WITH_VALUE.contains(w)) {
                i++;
                continue;
            }
            if (!w.startsWith("-")) {
                return w;
            }
        }
        // a launcher (not java itself): its own name
        String first = words.get(0);
        return first.substring(Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\')) + 1);
    }

    /** True if a command line is a JVM (the {@code java} command, or one in a JDK's bin). */
    public static boolean isJava(String command) {
        List<String> words = words(command);
        if (words.isEmpty()) {
            return false;
        }
        String first = words.get(0);
        String base = first.substring(Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\')) + 1);
        return base.equals("java") || base.equals("java.exe") || base.equals("javaw.exe") || base.equals("javaw")
                || JAVA_PATH.matcher(command).lookingAt(); // a path with spaces, not in quotes
    }

    private static final java.util.regex.Pattern JAVA_PATH = java.util.regex.Pattern.compile(
            "^\\S(?:(?!\\s-).)*?[\\\\/]javaw?(?:\\.exe)?(?:\\s|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Splits a command line at spaces (quotes keep words together). */
    static List<String> words(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    sb.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }
}
