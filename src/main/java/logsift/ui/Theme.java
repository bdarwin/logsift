package logsift.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;
import javax.swing.UIManager;

/** Colors and fonts for the log views, for light and dark mode. */
final class Theme {
    private Theme() {}

    static void apply(boolean dark) {
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        FlatLaf.updateUI();
    }

    static boolean isDark() {
        return FlatLaf.isLafDark();
    }

    static Color pick(Color light, Color dark) {
        return isDark() ? dark : light;
    }

    static Color background() {
        return pick(new Color(0xFFFFFF), new Color(0x1E1F22));
    }

    static Color foreground() {
        return pick(new Color(0x1F1F1F), new Color(0xD4D4D4));
    }

    static Color gutterBackground() {
        return pick(new Color(0xF3F3F3), new Color(0x26282B));
    }

    static Color gutterForeground() {
        return pick(new Color(0x9A9A9A), new Color(0x6E7277));
    }

    static Color selection() {
        return pick(new Color(0xC9DDFB), new Color(0x2F4B73));
    }

    static Color selectionInactive() {
        return pick(new Color(0xD5DEEA), new Color(0x3A3F47));
    }

    static Color dimmedForeground() {
        return pick(new Color(0xB4B8BE), new Color(0x55595F));
    }

    static Color errorMark() {
        return new Color(0xE5484D);
    }

    static Color warningMark() {
        return new Color(0xF5A524);
    }

    static Color findMark() {
        return pick(new Color(0xC99700), new Color(0xFFD54F));
    }

    /** Background of every other line: barely visible, just enough to separate lines. */
    static Color stripe() {
        return pick(new Color(0xF5F7FA), new Color(0x232529));
    }

    static Color currentLine() {
        return pick(new Color(0xEEF4FD), new Color(0x262B33));
    }

    static Color match() {
        return pick(new Color(0xFFD54F), new Color(0x8A6A00));
    }

    static Color marker() {
        return pick(new Color(0x3B82F6), new Color(0x60A5FA));
    }

    private static final String[] MONO_FONTS = {"JetBrains Mono", "Cascadia Mono", "Consolas",
        "Menlo", "SF Mono", "DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono"};

    static Font monoFont(float size) {
        Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames());
        for (String name : MONO_FONTS) {
            if (available.contains(name)) {
                return new Font(name, Font.PLAIN, 12).deriveFont(size);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 12).deriveFont(size);
    }

    static Color uiColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }
}
