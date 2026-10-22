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

    /** Header rows of foldable groups (folder search files, Find results searches). */
    static Color groupHeaderForeground() {
        return isDark() ? new Color(0x93C5FD) : new Color(0x1D4ED8);
    }

    static Color groupHeaderBackground() {
        return isDark() ? new Color(0x2A2E35) : new Color(0xEEF2F7);
    }

    static Color matchLight() {
        return new Color(0xFFD54F);
    }

    static Color matchDark() {
        return new Color(0x8A6A00);
    }

    /**
     * Colors for several searches at once: {highlight light, highlight dark, mark}. The first
     * is the usual yellow.
     */
    static final Color[][] SEARCH_COLORS = {
        {new Color(0xFFD54F), new Color(0x8A6A00), new Color(0xD4A800)},
        {new Color(0xA5E6A9), new Color(0x2F6E3B), new Color(0x2FA84F)},
        {new Color(0xA9CDFF), new Color(0x2E5A94), new Color(0x3B82F6)},
        {new Color(0xFFB8D4), new Color(0x7D3558), new Color(0xEC4899)},
        {new Color(0xFFCC99), new Color(0x80501F), new Color(0xF97316)},
        {new Color(0xD5BEFF), new Color(0x5E4396), new Color(0x8B5CF6)},
        {new Color(0x9FE8E0), new Color(0x1F6B66), new Color(0x14B8A6)}};

    static Color match() {
        return pick(new Color(0xFFD54F), new Color(0x8A6A00));
    }

    static Color marker() {
        return pick(new Color(0x3B82F6), new Color(0x60A5FA));
    }

    private static final String[] MONO_FONTS = {"JetBrains Mono", "Cascadia Mono", "Consolas",
        "Menlo", "SF Mono", "DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono"};

    private static volatile String fontFamily; // chosen in Settings; null: the first one available

    /** The font for logs (null: pick a good monospaced one). */
    static void setFontFamily(String family) {
        fontFamily = family;
    }

    static String fontFamily() {
        return fontFamily;
    }

    static Font monoFont(float size) {
        String chosen = fontFamily;
        if (chosen != null && fontFamilies().contains(chosen)) {
            return new Font(chosen, Font.PLAIN, 12).deriveFont(size);
        }
        return new Font(autoFontFamily(), Font.PLAIN, 12).deriveFont(size);
    }

    /** The monospaced font used when none is chosen. */
    static String autoFontFamily() {
        Set<String> available = fontFamilies();
        for (String name : MONO_FONTS) {
            if (available.contains(name)) {
                return name;
            }
        }
        return Font.MONOSPACED;
    }

    private static Set<String> families;

    private static synchronized Set<String> fontFamilies() {
        if (families == null) {
            families = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        }
        return families;
    }

    /** Installed fonts whose letters all have the same width (for the Settings list). */
    static java.util.List<String> monospacedFamilies() {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        for (String f : new java.util.TreeSet<>(fontFamilies())) {
            java.awt.FontMetrics fm = g.getFontMetrics(new Font(f, Font.PLAIN, 12));
            if (fm.charWidth('i') == fm.charWidth('W') && fm.charWidth('i') > 0
                    && new Font(f, Font.PLAIN, 12).canDisplay('a')) {
                out.add(f);
            }
        }
        g.dispose();
        return out;
    }

    static Color uiColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }
}
