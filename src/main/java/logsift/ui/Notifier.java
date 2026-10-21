package logsift.ui;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Point;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Tells the user about new matching lines: a desktop notification (where the system supports
 * it), a small popup in the corner of the window that jumps to the line when clicked, a sound,
 * and the taskbar asking for attention.
 */
final class Notifier {
    private Notifier() {}

    private static TrayIcon tray;
    private static JWindow popup;
    private static volatile String lastMessage; // for tests

    static String lastMessageForTest() {
        return lastMessage;
    }

    static JWindow popupForTest() {
        return popup;
    }

    static void notify(Frame owner, String title, String message, Runnable onClick) {
        lastMessage = title + ": " + message;
        Toolkit.getDefaultToolkit().beep();
        desktop(title, message);
        showPopup(owner, title, message, onClick);
        try {
            if (Taskbar.isTaskbarSupported()
                    && Taskbar.getTaskbar().isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
                Taskbar.getTaskbar().requestWindowUserAttention(owner);
            }
        } catch (RuntimeException e) {
            // not supported here
        }
    }

    private static void desktop(String title, String message) {
        try {
            if (!SystemTray.isSupported()) {
                return;
            }
            if (tray == null) {
                tray = new TrayIcon(logsift.tools.IconGenerator.render(64), "Logsift");
                tray.setImageAutoSize(true);
                SystemTray.getSystemTray().add(tray);
            }
            tray.displayMessage(title, message, TrayIcon.MessageType.WARNING);
        } catch (AWTException | RuntimeException e) {
            // no desktop notifications here; the popup still shows
        }
    }

    private static void showPopup(Frame owner, String title, String message, Runnable onClick) {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        if (popup != null) {
            popup.dispose();
        }
        JWindow w = new JWindow(owner);
        JPanel p = new JPanel(new BorderLayout(0, 4));
        Color accent = Theme.errorMark();
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                BorderFactory.createEmptyBorder(10, 12, 10, 14)));
        p.setBackground(Theme.pick(new Color(0xFFFFFF), new Color(0x2B2D31)));
        JLabel t = new JLabel(title, new FilterPanel.BellIcon(true), JLabel.LEFT);
        t.setFont(t.getFont().deriveFont(Font.BOLD));
        JLabel m = new JLabel("<html><div style='width:320px'>" + escape(message)
                + "</div><span style='color:gray'>Click to go there</span></html>");
        p.add(t, BorderLayout.NORTH);
        p.add(m, BorderLayout.CENTER);
        w.setContentPane(p);
        w.pack();
        Point o = owner.getLocationOnScreen();
        w.setLocation(o.x + owner.getWidth() - w.getWidth() - 24,
                o.y + owner.getHeight() - w.getHeight() - 48);
        p.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                w.dispose();
                if (onClick != null) {
                    onClick.run();
                }
            }
        });
        w.setVisible(true);
        popup = w;
        Timer close = new Timer(8000, e -> w.dispose());
        close.setRepeats(false);
        close.start();
    }

    private static String escape(String s) {
        if (s.length() > 300) {
            s = s.substring(0, 300) + "…";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /** Closes the popup (for tests and when the window closes). */
    static void dismiss() {
        SwingUtilities.invokeLater(() -> {
            if (popup != null) {
                popup.dispose();
                popup = null;
            }
        });
    }
}
