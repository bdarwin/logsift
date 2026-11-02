package logsift.ui;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/** Where Pivot keeps the cache of big files' columns, and how much room it may take (Settings). */
final class PivotCaches {
    private PivotCaches() {}

    private static final Preferences PREFS = AppState.prefs();

    static Path dir() {
        return AppState.home().resolve("cache").resolve("pivot");
    }

    /** The room for caches, in GB (0: no cache). */
    static int limitGb() {
        return PREFS.getInt("pivotCacheGb", (int) (logsift.core.PivotCache.DEFAULT_LIMIT >> 30));
    }

    static void setLimitGb(int gb) {
        PREFS.putInt("pivotCacheGb", Math.max(0, gb));
    }

    static long limit() {
        return (long) limitGb() << 30;
    }

    static boolean enabled() {
        return limitGb() > 0;
    }

    /** Files from this size on are cached (tests make it smaller). */
    static long minBytes() {
        return Long.getLong("logsift.pivotCacheMinBytes", logsift.core.PivotCache.MIN_BYTES);
    }
}
