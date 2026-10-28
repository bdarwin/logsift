package logsift.core;

import java.nio.file.Path;

/**
 * Which build this is: the normal one, or a beta ({@code -Dlogsift.channel=beta}). A beta keeps
 * its own settings ({@code ~/.logsift-beta}) and its own window, so it runs next to the normal
 * Logsift without touching it.
 */
public final class Channel {
    private Channel() {}

    public static boolean isBeta() {
        return "beta".equals(System.getProperty("logsift.channel"));
    }

    /** "Logsift", or "Logsift Beta". */
    public static String appName() {
        return isBeta() ? "Logsift Beta" : "Logsift";
    }

    /**
     * For a beta, points Logsift's folder and preferences at the beta's own (unless they are
     * set already, as tests do). Call first thing in {@code main}.
     */
    public static void apply() {
        if (!isBeta()) {
            return;
        }
        if (System.getProperty("logsift.home") == null) {
            System.setProperty("logsift.home",
                    Path.of(System.getProperty("user.home"), ".logsift-beta").toString());
        }
        if (System.getProperty("logsift.prefs") == null) {
            System.setProperty("logsift.prefs", "/logsift/beta");
        }
    }
}
