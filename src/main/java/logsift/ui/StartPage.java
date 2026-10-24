package logsift.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * What the window shows with nothing open: big buttons for the ways to start (open a log, a
 * folder, a remote log, a JVM...) and the files opened recently.
 */
final class StartPage extends JPanel {

    /** What the buttons do. */
    interface Host {
        void openLogs();

        void openFolder();

        void openRemote();

        void attachJvm();

        void openThreadDump();

        void openProfile();

        void openRecent(Path file);

        void reopenSession();
    }

    private final Host host;
    private final JPanel recent = new JPanel();
    private final List<JButton> actions = new ArrayList<>();

    StartPage(Host host) {
        super(new GridBagLayout());
        this.host = host;
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Logsift");
        JLabel tag = new JLabel("Find out what your app did, from its logs to its threads.");
        tag.setFont(UIManager.getFont("Label.font").deriveFont(15f));
        title.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, 26f));
        JLabel drop = new JLabel("Drop log files, folders or zips anywhere in this window, or:");
        drop.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        col.add(left(title));
        col.add(Box.createVerticalStrut(2));
        col.add(left(tag));
        col.add(Box.createVerticalStrut(10));
        col.add(left(drop));
        col.add(Box.createVerticalStrut(18));

        String m = MainFrame.isMac() ? "⌘" : "Ctrl+";
        String sm = MainFrame.isMac() ? "⇧⌘" : "Ctrl+Shift+";
        JPanel grid = new JPanel(new GridLayout(2, 3, 12, 12));
        grid.setOpaque(false);
        grid.add(action("folder-open", "Open Log…", m + "O", "Files, zips, .gz", host::openLogs));
        grid.add(action("folder-search", "Open Folder…", sm + "F", "Search every log in it, zips too",
                host::openFolder));
        grid.add(action("server", "Open Remote…", sm + "O", "A log on another machine (SSH)", host::openRemote));
        grid.add(action("cpu", "Attach to a JVM…", sm + "J", "Threads, profiles, heap, its logs",
                host::attachJvm));
        grid.add(action("activity", "Open Thread Dump…", null, "jstack, jcmd or kill -3 output",
                host::openThreadDump));
        grid.add(action("flame", "Open Profile…", null, "A JFR recording or a saved profile", host::openProfile));
        col.add(left(grid));
        col.add(Box.createVerticalStrut(26));

        recent.setOpaque(false);
        recent.setLayout(new BoxLayout(recent, BoxLayout.Y_AXIS));
        col.add(left(recent));
        add(col, new GridBagConstraints());
        refresh();
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private JButton action(String icon, String name, String key, String detail, Runnable r) {
        String sub = "#858b94"; // readable on light and dark
        JButton b = new JButton("<html><b style='font-size:1.1em'>" + name + "</b>"
                + (key == null ? "" : "&nbsp;&nbsp;<span style='color:" + sub + "'>" + key + "</span>")
                + "<br><span style='color:" + sub + "'>" + detail + "</span></html>",
                Icons.of(icon, Theme.uiColor("Component.accentColor", new Color(0x2563EB)), 26));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setIconTextGap(14);
        b.setFocusable(false);
        b.putClientProperty("FlatLaf.style", "arc: 14; margin: 14,16,14,18");
        b.setPreferredSize(new Dimension(300, 72));
        b.addActionListener(e -> r.run());
        actions.add(b);
        return b;
    }

    /** Shows the files opened recently (call when the page is shown). */
    void refresh() {
        recent.removeAll();
        List<Path> files = AppState.recentFiles();
        AppState.Session s = AppState.lastSession();
        if (!files.isEmpty() || !s.files().isEmpty()) {
            JLabel h = new JLabel("Recent");
            h.setFont(h.getFont().deriveFont(Font.BOLD));
            recent.add(left(h));
            recent.add(Box.createVerticalStrut(6));
        }
        if (!s.files().isEmpty()) {
            int n = s.files().size();
            recent.add(left(link("Reopen last session (" + n + (n == 1 ? " file" : " files") + ")", null,
                    "rotate-ccw", host::reopenSession)));
        }
        for (Path p : files.subList(0, Math.min(8, files.size()))) {
            Path parent = p.getParent();
            recent.add(left(link(String.valueOf(p.getFileName()), parent == null ? null : parent.toString(), "file-text",
                    () -> host.openRecent(p))));
        }
        recent.revalidate();
        recent.repaint();
    }

    private static JButton link(String name, String where, String icon, Runnable r) {
        String dim = "#858b94";
        JButton b = new JButton("<html>" + escape(name)
                + (where == null ? "" : "&nbsp;&nbsp;<span style='color:" + dim + "'>" + escape(shorten(where))
                + "</span>") + "</html>", Icons.of(icon));
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 6));
        if (where != null) {
            b.setToolTipText(where);
        }
        b.addActionListener(e -> r.run());
        return b;
    }

    private static String shorten(String path) {
        String home = System.getProperty("user.home");
        if (home != null && path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }
        return path.length() > 70 ? "…" + path.substring(path.length() - 69) : path;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The big buttons' names, for tests. */
    List<String> actionsForTest() {
        List<String> out = new ArrayList<>();
        for (JButton b : actions) {
            out.add(b.getText().replaceAll("<[^>]+>", "|").replaceAll("\\|+", "|").split("\\|")[1]);
        }
        return out;
    }

    JButton actionForTest(String name) {
        for (JButton b : actions) {
            if (b.getText().contains(">" + name + "<")) {
                return b;
            }
        }
        return null;
    }
}
