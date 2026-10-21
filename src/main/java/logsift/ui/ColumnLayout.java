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

    ColumnLayout(LogFormat format, boolean showSource) {
        this.format = format;
        List<Integer> cols = new ArrayList<>();
        List<String> names = format.columns();
        int message = names.indexOf("message");
        for (int i = 0; i < names.size(); i++) {
            if (i == message || (!showSource && names.get(i).equals("source"))) {
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

    boolean isLevel(int col) {
        return format.columns().get(col).equals("level");
    }

    String title(int col) {
        String n = format.columns().get(col);
        return n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }
}
