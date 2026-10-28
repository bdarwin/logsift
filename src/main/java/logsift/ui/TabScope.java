package logsift.ui;

/**
 * Whether a tab below the log shows the whole log or only the lines the filters keep, and how
 * that is said: the words of the banner and of the tab's tooltip. No Swing here (tested headless).
 */
final class TabScope {
    private TabScope() {}

    /** The tooltip of a tab while it counts only the filtered lines. */
    static final String FOLLOWS = "Counts only the lines the filters keep";
    /** The tooltip of a tab that never follows the filters. */
    static final String WHOLE = "Always the whole log";

    /** "Only the 225 of 2,099 lines the filters keep". */
    static String bannerText(long kept, long total) {
        return String.format("Only the %,d of %,d lines the filters keep", kept, total);
    }

    /**
     * Whether Metrics or the Profile count only the filtered lines: with Matching only on and
     * filters (or a time range) set.
     */
    static boolean followsMatchingOnly(boolean matchingOnly, boolean filtering) {
        return matchingOnly && filtering;
    }
}
