package logsift.ui;

import java.nio.file.Path;
import logsift.core.CacheSettings;

/** Where Pivot keeps the cache of big files' columns, and how much room it may take (Settings). */
final class PivotCaches {
    private PivotCaches() {}

    static Path dir() {
        return CacheSettings.dir();
    }

    /** The room for caches, in GB (0: no cache). */
    static int limitGb() {
        return CacheSettings.limitGb();
    }

    static void setLimitGb(int gb) {
        CacheSettings.setLimitGb(gb);
    }

    static long limit() {
        return CacheSettings.limit();
    }

    static boolean enabled() {
        return CacheSettings.enabled();
    }

    /** Files from this size on are cached (tests make it smaller). */
    static long minBytes() {
        return CacheSettings.minBytes();
    }
}
