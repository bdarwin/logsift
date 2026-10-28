package logsift.core.diff;

/**
 * What happened to one item of a comparison (a row of a table, later a line or a hunk of text).
 * Each has a word and a symbol, so a view never tells them apart by colour alone.
 */
public enum DiffStatus {
    /** In both, equal (with the comparison's options). */
    SAME("same", "="),
    /** In both (same key, or lined up in order), with differences. */
    CHANGED("changed", "≠"),
    /** Only on the right (the newer side). */
    ADDED("added", "+"),
    /** Only on the left (the older side). */
    REMOVED("removed", "−"),
    /** Its key is in a file more than once: listed on its own, never matched. */
    DUPLICATE("duplicate key", "‼");

    private final String label;
    private final String symbol;

    DiffStatus(String label, String symbol) {
        this.label = label;
        this.symbol = symbol;
    }

    public String label() {
        return label;
    }

    public String symbol() {
        return symbol;
    }

    /** Anything but {@link #SAME}. */
    public boolean isDifference() {
        return this != SAME;
    }
}
