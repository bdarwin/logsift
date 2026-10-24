package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.table.DefaultTableModel;
import logsift.core.Version;

/** The Help menu's pieces: About, keyboard shortcuts, links, and reporting a bug. */
final class HelpSupport {
    private HelpSupport() {}

    static final String REPO = "https://github.com/bdarwin/logsift";
    static final String DOCS = REPO + "#readme";
    static final String ISSUES = REPO + "/issues";

    /** "macOS 15.5 (aarch64)" and similar. */
    static String osText() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
    }

    static String javaText() {
        return "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
    }

    /** Opens a web page; if there is no browser, copies the address and says so. */
    static void browse(Component parent, String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            // fall through: copy instead
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
        JOptionPane.showMessageDialog(parent, "Couldn't open a browser. The address was copied:\n" + url);
    }

    /** A new-issue page with the bug form filled in with version, system and details. */
    static String bugReportUrl(String title, String details) {
        StringBuilder sb = new StringBuilder(ISSUES).append("/new?template=bug_report.yml");
        sb.append("&version=").append(enc(Version.current()));
        sb.append("&os=").append(enc(osText() + ", " + javaText()));
        if (title != null) {
            sb.append("&title=").append(enc(title));
        }
        if (details != null) {
            // browsers and GitHub limit address length; the full text can be pasted
            String d = details.length() > 4000 ? details.substring(0, 4000) + "\n…" : details;
            sb.append("&details=").append(enc(d));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static void showAbout(Component parent) {
        JEditorPane text = new JEditorPane("text/html", "<html><body style='font-family:sans-serif'>"
                + "<div style='font-size:20pt;font-weight:bold'>Logsift</div>"
                + "<div style='font-size:12pt'>Version " + Version.current() + "</div><br>"
                + "Find out what your app did, from its logs to its threads.<br><br>"
                + osText() + "<br>" + javaText() + "<br><br>"
                + "<a href='" + REPO + "'>" + REPO.replace("https://", "") + "</a><br>"
                + "Free and open source under the MIT license.</body></html>");
        text.setEditable(false);
        text.setOpaque(false);
        text.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                browse(parent, e.getURL().toString());
            }
        });
        JOptionPane.showMessageDialog(parent, text, "About Logsift", JOptionPane.PLAIN_MESSAGE,
                new javax.swing.ImageIcon(logsift.tools.IconGenerator.render(64)));
    }

    // ---------------------------------------------------------------- keyboard shortcuts

    /** Every shortcut: those of the menus (read from the menu bar, so always current) plus a few more. */
    static List<String[]> shortcuts(JMenuBar bar) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            collect(bar.getMenu(i), bar.getMenu(i).getText(), rows);
        }
        boolean mac = MainFrame.isMac();
        String menu = mac ? "⌘" : "Ctrl+";
        rows.add(new String[] {"Find box", "Find All", "Enter"});
        rows.add(new String[] {"Find box", "Stop a running search", "Esc"});
        rows.add(new String[] {"Log", "Copy selected text", menu + "C"});
        rows.add(new String[] {"Log", "Select all", menu + "A"});
        rows.add(new String[] {"Log", "Select lines", "Shift+click, Shift+arrows, or drag in the line numbers"});
        rows.add(new String[] {"Log", "Scroll sideways", "← →"});
        rows.add(new String[] {"Results", "Fold or open a search or file", "Click its header, or Enter / ← / →"});
        rows.add(new String[] {"Results", "Go to the line in the log", "Double-click, or Enter"});
        return rows;
    }

    private static void collect(JMenu menu, String path, List<String[]> rows) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem it = menu.getItem(i);
            if (it == null) {
                continue;
            }
            if (it instanceof JMenu sub) {
                collect(sub, path + " › " + sub.getText(), rows);
            } else if (it.getAccelerator() != null) {
                rows.add(new String[] {path, it.getText(), keyText(it.getAccelerator())});
            }
        }
    }

    static String keyText(KeyStroke k) {
        int m = k.getModifiers();
        StringBuilder sb = new StringBuilder();
        if (MainFrame.isMac()) {
            if ((m & InputEvent.CTRL_DOWN_MASK) != 0) {
                sb.append('⌃');
            }
            if ((m & InputEvent.ALT_DOWN_MASK) != 0) {
                sb.append('⌥');
            }
            if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) {
                sb.append('⇧');
            }
            if ((m & InputEvent.META_DOWN_MASK) != 0) {
                sb.append('⌘');
            }
        } else {
            if ((m & InputEvent.CTRL_DOWN_MASK) != 0) {
                sb.append("Ctrl+");
            }
            if ((m & InputEvent.ALT_DOWN_MASK) != 0) {
                sb.append("Alt+");
            }
            if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) {
                sb.append("Shift+");
            }
        }
        String key = switch (k.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> "Esc";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_MINUS -> "-";
            default -> KeyEvent.getKeyText(k.getKeyCode());
        };
        return sb + key;
    }

    static JDialog shortcutsDialog(java.awt.Frame owner, JMenuBar bar) {
        DefaultTableModel model = new DefaultTableModel(new Object[] {"Where", "Action", "Keys"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (String[] r : shortcuts(bar)) {
            model.addRow(r);
        }
        JTable table = new JTable(model);
        table.setRowHeight(table.getRowHeight() + 6);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.setAutoCreateRowSorter(true);
        table.setFont(table.getFont().deriveFont(Font.PLAIN));
        JDialog d = new JDialog(owner, "Keyboard Shortcuts", false);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        d.add(sp, BorderLayout.CENTER);
        d.setSize(720, 560);
        d.setLocationRelativeTo(owner);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        return d;
    }
}
