package logsift.ui;

import java.awt.BorderLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;

/**
 * The assistant in a window of its own (on another screen, say), kept where it was put. It's
 * still the main window's chat: it asks about the log shown there, and its links show the lines
 * there.
 */
final class ChatWindow extends JFrame {
    /** The smallest a window is put back at: room for the box and an answer. */
    static final int MIN_W = 420;
    static final int MIN_H = 360;
    /** Wide enough at first for the chats at the left and the conversation. */
    static final int WIDTH = 900;

    private final Preferences prefs;

    /**
     * @param back  ⌘I (Ctrl+I) in the window: back to the logs
     * @param close the window's close box: the assistant is hidden, as its tab's ✕ does
     */
    ChatWindow(ChatPanel panel, Window beside, Preferences prefs, Runnable back, Runnable close) {
        super("Assistant · " + logsift.core.Channel.appName());
        this.prefs = prefs;
        setName("assistantWindow");
        setIconImages(logsift.tools.IconGenerator.windowIcons());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        getContentPane().add(panel, BorderLayout.CENTER);
        setBounds(place(saved(prefs), screens(), beside == null ? null : beside.getBounds()));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close.run();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                remember();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                remember();
            }
        });
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_I, menu), "back", back);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), "close", close);
        // its own text size: ⌘+ / ⌘- (Ctrl), as View → Bigger Font in Logsift's window
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menu), "bigger", () -> ChatFonts.step(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "bigger+",
                () -> ChatFonts.step(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menu), "plus", () -> ChatFonts.step(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menu), "smaller", () -> ChatFonts.step(-1));
    }

    private void bind(KeyStroke key, String name, Runnable r) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }

    private void remember() {
        if (isShowing() && (getExtendedState() & MAXIMIZED_BOTH) == 0) {
            Rectangle b = getBounds();
            prefs.put("window", b.x + "," + b.y + "," + b.width + "," + b.height);
        }
    }

    /** Where the window was last, or null. */
    static Rectangle saved(Preferences prefs) {
        String[] v = prefs.get("window", "").split(",");
        if (v.length != 4) {
            return null;
        }
        try {
            return new Rectangle(Integer.parseInt(v[0].strip()), Integer.parseInt(v[1].strip()),
                    Integer.parseInt(v[2].strip()), Integer.parseInt(v[3].strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The screens' usable areas (without the menu bar, dock or taskbar). */
    static List<Rectangle> screens() {
        List<Rectangle> out = new ArrayList<>();
        if (GraphicsEnvironment.isHeadless()) {
            return out;
        }
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration c = d.getDefaultConfiguration();
            Rectangle b = c.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(c);
            out.add(new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right,
                    b.height - in.top - in.bottom));
        }
        return out;
    }

    /**
     * Where the window goes: where it was, if that's still on a screen (its title bar on one, at
     * least a good part of it); else beside the main window, on its screen. Never larger than the
     * screen it's on.
     */
    static Rectangle place(Rectangle saved, List<Rectangle> screens, Rectangle beside) {
        if (screens.isEmpty()) {
            return saved != null ? saved : new Rectangle(0, 0, WIDTH, 820);
        }
        if (saved != null && saved.width >= MIN_W && saved.height >= MIN_H) {
            Rectangle title = new Rectangle(saved.x, saved.y, saved.width, 28);
            for (Rectangle s : screens) {
                Rectangle seen = s.intersection(title);
                if (!seen.isEmpty() && seen.width >= Math.min(120, saved.width) && seen.height >= 14) {
                    return fit(saved, s);
                }
            }
        }
        Rectangle screen = screens.get(0);
        if (beside != null) {
            for (Rectangle s : screens) {
                if (s.contains(beside.x + beside.width / 2, beside.y + beside.height / 2)) {
                    screen = s;
                    break;
                }
            }
        }
        int w = Math.min(WIDTH, screen.width);
        int h = Math.min(beside != null ? Math.max(MIN_H, beside.height) : 820, screen.height);
        int x;
        int y;
        if (beside == null) {
            x = screen.x + (screen.width - w) / 2;
            y = screen.y + (screen.height - h) / 2;
        } else if (beside.x + beside.width + w <= screen.x + screen.width) {
            x = beside.x + beside.width; // at the right of the logs, if there's room
            y = beside.y;
        } else if (beside.x - w >= screen.x) {
            x = beside.x - w; // else at their left
            y = beside.y;
        } else {
            x = screen.x + screen.width - w; // else over their right side
            y = beside.y;
        }
        return fit(new Rectangle(x, y, w, h), screen);
    }

    /** Moved and shrunk onto the screen, whole. */
    static Rectangle fit(Rectangle r, Rectangle screen) {
        int w = Math.min(r.width, screen.width);
        int h = Math.min(r.height, screen.height);
        int x = Math.max(screen.x, Math.min(r.x, screen.x + screen.width - w));
        int y = Math.max(screen.y, Math.min(r.y, screen.y + screen.height - h));
        return new Rectangle(x, y, w, h);
    }
}
