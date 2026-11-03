package logsift.core;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * Where the cache of big tables' columns is kept, and how much room it may take (Settings →
 * General → Pivot cache). Read the same way by the app and by agents' logsift-mcp, so both use
 * one cache and one limit.
 */
public final class CacheSettings {
    private CacheSettings() {}

    private static Preferences prefs() {
        return Preferences.userRoot().node(System.getProperty("logsift.prefs", "/logsift/ui"));
    }

    /** Logsift's folder ({@code ~/.logsift}; tests give another). */
    public static Path home() {
        String h = System.getProperty("logsift.home");
        return h != null ? Path.of(h) : Path.of(System.getProperty("user.home"), ".logsift");
    }

    public static Path dir() {
        return home().resolve("cache").resolve("pivot");
    }

    /** The room for caches, in GB (0: no cache). */
    public static int limitGb() {
        return prefs().getInt("pivotCacheGb", (int) (PivotCache.DEFAULT_LIMIT >> 30));
    }

    public static void setLimitGb(int gb) {
        prefs().putInt("pivotCacheGb", Math.max(0, gb));
    }

    public static long limit() {
        return (long) limitGb() << 30;
    }

    public static boolean enabled() {
        return limitGb() > 0;
    }

    /** Files from this size on are cached (tests make it smaller). */
    public static long minBytes() {
        return Long.getLong("logsift.pivotCacheMinBytes", PivotCache.MIN_BYTES);
    }
}
