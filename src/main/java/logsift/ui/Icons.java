package logsift.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.Color;
import java.awt.Component;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.UIManager;

/**
 * Line icons for the toolbar and menus (Lucide, ISC license: see icons/LICENSE-lucide.txt),
 * drawn in the text color of the component they are on, so they fit light and dark themes.
 */
final class Icons {
    private Icons() {}

    /** Paints the icons' strokes in the component's text color. */
    private static final FlatSVGIcon.ColorFilter TEXT_COLOR = new FlatSVGIcon.ColorFilter((c, color) -> {
        Color fg = null;
        if (c instanceof JMenuItem mi && (mi.isArmed() || mi.isSelected() && mi.getModel().isArmed())) {
            fg = UIManager.getColor("MenuItem.selectionForeground");
        } else if (c != null) {
            fg = c.getForeground();
        }
        if (fg == null) {
            fg = UIManager.getColor("Label.foreground");
        }
        return fg == null ? color : new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), color.getAlpha());
    });

    /** The icon {@code icons/<name>.svg}, 16×16. */
    static Icon of(String name) {
        FlatSVGIcon icon = new FlatSVGIcon("logsift/icons/" + name + ".svg", 16, 16);
        icon.setColorFilter(TEXT_COLOR);
        return icon;
    }

    static Icon open() {
        return of("folder-open");
    }

    static Icon folder() {
        return of("folder");
    }

    static Icon file() {
        return of("file");
    }

    static Icon remote() {
        return of("server");
    }

    static Icon find() {
        return of("search");
    }

    static Icon up() {
        return of("chevron-up");
    }

    static Icon down() {
        return of("chevron-down");
    }

    static Icon follow() {
        return of("arrow-down-to-line");
    }

    static Icon wrap() {
        return of("wrap-text");
    }

    static Icon columns() {
        return of("columns-3");
    }

    static Icon reload() {
        return of("refresh-cw");
    }

    static Icon autoReload() {
        return of("refresh-ccw-dot");
    }

    /** Whether {@code c} shows one of these icons (for tests). */
    static boolean isIcon(Component c) {
        return c instanceof AbstractButton b && b.getIcon() instanceof FlatSVGIcon;
    }
}
