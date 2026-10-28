package logsift.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import logsift.core.LogFormat;

/**
 * Which columns of a {@link LogFormat} are shown, and how wide (in characters, so they scale
 * with the font). The last shown column (normally the message) takes the rest of the row.
 */
final class ColumnLayout {
    private final LogFormat format;
    private final int[] visible;   // indexes into format.columns()
    private final int[] chars;     // width of each visible column but the last
    private final List<String> hidden = new ArrayList<>(); // columns the user hid

    ColumnLayout(LogFormat format, boolean showSource) {
        this(format, showSource, java.util.Set.of());
    }

    /** {@code hide}: names of columns not to show (the message is always shown). */
    ColumnLayout(LogFormat format, boolean showSource, java.util.Set<String> hide) {
        this.format = format;
        List<Integer> cols = new ArrayList<>();
        List<String> names = format.columns();
        int message = names.indexOf("message");
        for (int i = 0; i < names.size(); i++) {
            // a merged log's labels; a CSV's own column called "source" always shows
            if (i == message || (!showSource && !format.isCsv() && names.get(i).equals("source"))) {
                continue;
            }
            if (hide.contains(names.get(i))) {
                hidden.add(names.get(i));
                continue;
            }
            cols.add(i);
        }
        if (message >= 0) {
            cols.add(message); // the message last, taking the rest of the row
        }
        visible = cols.stream().mapToInt(Integer::intValue).toArray();
        chars = new int[Math.max(0, visible.length - 1)];
        for (int c = 0; c < chars.length; c++) {
            chars[c] = defaultWidth(names.get(visible[c]));
        }
    }

    private static int defaultWidth(String column) {
        return switch (column) {
            case "time" -> 25;
            case "level" -> 7;
            case "thread" -> 20;
            case "logger" -> 30;
            case "source" -> 14;
            case "host", "process", "client" -> 16;
            case "pid", "status" -> 7;
            case "user" -> 12;
            case "size" -> 9;
            case "request" -> 44;
            case "fields" -> 48;
            default -> 16;
        };
    }

    /** Columns the user hid, in the format's order. */
    List<String> hidden() {
        return hidden;
    }

    /** Keeps the widths the user gave columns that are in both layouts. */
    void copyWidths(ColumnLayout old) {
        if (old == null || old.format != format) {
            return;
        }
        for (int c = 0; c < chars.length; c++) {
            String name = format.columns().get(visible[c]);
            for (int o = 0; o < old.chars.length; o++) {
                if (old.format.columns().get(old.visible[o]).equals(name)) {
                    chars[c] = old.chars[o];
                }
            }
        }
    }

    LogFormat format() {
        return format;
    }

    int[] visible() {
        return visible;
    }

    /** Width in pixels of visible column {@code c} (not the last one). */
    int width(int c, int charWidth) {
        return chars[c] * charWidth;
    }

    /** Sets the width of visible column {@code c} in pixels (kept in characters). */
    void setWidth(int c, int px, int charWidth) {
        chars[c] = Math.max(3, Math.round((float) px / Math.max(1, charWidth)));
    }

    /** X offset of the last column (the message). */
    int messageX(int charWidth) {
        int x = 0;
        for (int c = 0; c < chars.length; c++) {
            x += width(c, charWidth);
        }
        return x;
    }

    int fixedWidth(int charWidth) {
        return messageX(charWidth);
    }

    /** Index (in the format's columns) of the last shown column, normally the message. */
    int messageColumn() {
        return visible.length == 0 ? -1 : visible[visible.length - 1];
    }

    /**
     * Whether a line that isn't a row is drawn from the left edge rather than in the last column:
     * a CSV without a message column (its last column is just another value, often far right).
     */
    boolean leftOfAll() {
        return format.isCsv() && !format.columns().contains("message");
    }

    boolean isLevel(int col) {
        return format.columns().get(col).equals("level");
    }

    String title(int col) {
        return format.displayName(format.columns().get(col));
    }
}
