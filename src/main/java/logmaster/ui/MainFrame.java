package logmaster.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import logmaster.core.LogDocument;
import logmaster.core.SearchQuery;
import logmaster.core.SearchSession;

/** The application window. */
public final class MainFrame extends JFrame implements LogTab.Host {
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainFrame.class);
    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel center = new JPanel(new CardLayout());
    private final JTextField findField = new JTextField(28);
    private final JToggleButton matchCase = toggle("Aa", "Match case");
    private final JToggleButton wholeWord = toggle("W", "Whole word");
    private final JToggleButton regex = toggle(".*", "Regular expression");
    private final JToggleButton followButton = new JToggleButton("Follow");
    private final JLabel status = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private float fontSize = PREFS.getFloat("fontSize", 13f);

    public MainFrame() {
        super("LogMaster");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(PREFS.getInt("width", 1280), PREFS.getInt("height", 820));
        setLocationByPlatform(true);

        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close");
        tabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.IntConsumer) this::closeTab);
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener(e -> tabStatusChanged(currentTab()));

        JLabel empty = new JLabel("<html><div style='text-align:center'>"
                + "<span style='font-size:16pt'>Drop log files here</span><br><br>"
                + "or use File → Open (" + (isMac() ? "⌘O" : "Ctrl+O") + ")</div></html>",
                SwingConstants.CENTER);
        empty.setForeground(Color.GRAY);
        center.add(empty, "empty");
        center.add(tabs, "tabs");

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);
        setJMenuBar(buildMenus());
        setTransferHandler(new FileDropHandler());
        updateEmptyState();

        new Timer(250, e -> refreshStatus()).start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                PREFS.putInt("width", getWidth());
                PREFS.putInt("height", getHeight());
                for (LogTab t : allTabs()) {
                    t.close();
                }
            }
        });
    }

    // ---------------------------------------------------------------- public API

    /** Opens a file in a new tab (or selects it if already open). */
    public void openFile(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (((LogTab) tabs.getComponentAt(i)).document().path().equals(abs)) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
        if (!Files.isRegularFile(abs)) {
            JOptionPane.showMessageDialog(this, "Not a file: " + abs, "Open",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            LogTab tab = new LogTab(abs, this);
            tab.mainView().setFontSize(fontSize);
            tab.resultsView().setFontSize(fontSize);
            tabs.addTab(tab.title(), tab);
            int idx = tabs.getTabCount() - 1;
            tabs.setToolTipTextAt(idx, abs.toString());
            tabs.setSelectedIndex(idx);
            PREFS.put("lastDir", abs.getParent().toString());
            updateEmptyState();
            SwingUtilities.invokeLater(() -> tab.mainView().focusView());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Cannot open " + abs + ":\n" + e.getMessage(),
                    "Open", JOptionPane.ERROR_MESSAGE);
        }
    }

    public LogTab currentTab() {
        return (LogTab) tabs.getSelectedComponent();
    }

    /** Runs Find All in the current tab with the given query (also fills the toolbar). */
    public void findAll(SearchQuery q) {
        findField.setText(q.text());
        matchCase.setSelected(q.matchCase());
        regex.setSelected(q.regex());
        wholeWord.setSelected(q.wholeWord());
        findAllFromToolbar();
    }

    public void setFollow(boolean on) {
        LogTab t = currentTab();
        if (t != null) {
            t.mainView().setFollow(on);
        }
        followButton.setSelected(on);
    }

    // ---------------------------------------------------------------- toolbar & menus

    private JComponent buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        findField.putClientProperty("JTextField.placeholderText", "Find  (Enter = Find All)");
        findField.putClientProperty("JTextField.showClearButton", true);
        JToolBar inField = new JToolBar();
        inField.setFloatable(false);
        inField.setOpaque(false);
        inField.add(matchCase);
        inField.add(wholeWord);
        inField.add(regex);
        findField.putClientProperty("JTextField.trailingComponent", inField);
        findField.setMaximumSize(new Dimension(520, 32));
        findField.addActionListener(e -> findAllFromToolbar());
        findField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stopSearch");
        findField.getActionMap().put("stopSearch", action("Stop", e -> {
            LogTab t = currentTab();
            if (t != null) {
                t.stopSearch();
                t.mainView().focusView();
            }
        }));

        JButton findAll = new JButton("Find All");
        findAll.addActionListener(e -> findAllFromToolbar());
        JButton prev = new JButton("▲");
        prev.setToolTipText("Previous hit (Shift+F3)");
        prev.addActionListener(e -> findNext(false));
        JButton next = new JButton("▼");
        next.setToolTipText("Next hit (F3)");
        next.addActionListener(e -> findNext(true));

        followButton.setToolTipText("Follow the end of the file as it grows (tail -f)");
        followButton.addActionListener(e -> setFollow(followButton.isSelected()));

        tb.add(new JLabel(" "));
        tb.add(findField);
        tb.addSeparator(new Dimension(6, 1));
        tb.add(findAll);
        tb.add(prev);
        tb.add(next);
        tb.addSeparator();
        tb.add(followButton);
        tb.add(Box.createHorizontalGlue());
        return tb;
    }

    static JToggleButton toggle(String text, String tip) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFont(b.getFont().deriveFont(Font.BOLD, b.getFont().getSize2D() - 1));
        return b;
    }

    private JComponent buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        progress.setPreferredSize(new Dimension(140, 12));
        progress.setVisible(false);
        JPanel right = new JPanel(new BorderLayout(10, 0));
        right.setOpaque(false);
        right.add(statusRight, BorderLayout.CENTER);
        right.add(progress, BorderLayout.EAST);
        p.add(status, BorderLayout.CENTER);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JMenuBar buildMenus() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("Open…", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU), e -> openDialog()));
        file.add(item("Close Tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, MENU), e -> closeTab(tabs.getSelectedIndex())));
        file.addSeparator();
        file.add(item(isMac() ? "Quit" : "Exit", isMac() ? null : KeyStroke.getKeyStroke(KeyEvent.VK_Q, MENU), e -> dispose()));
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.add(item("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, MENU), e -> {
            LogView v = LogView.lastFocused();
            if (v != null) {
                v.copySelection();
            }
        }));
        edit.add(item("Select All", KeyStroke.getKeyStroke(KeyEvent.VK_A, MENU), e -> {
            LogView v = LogView.lastFocused();
            if (v != null) {
                v.selectAll();
            }
        }));
        edit.addSeparator();
        edit.add(item("Find…", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU), e -> {
            // like text editors: text selected in the log becomes the search text
            LogView v = LogView.lastFocused();
            String s = v != null && v.isViewFocused() ? v.selectionForFind() : null;
            if (s != null) {
                findField.setText(s);
            }
            findField.requestFocusInWindow();
            findField.selectAll();
        }));
        edit.add(item("Find Next", KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), e -> findNext(true)));
        edit.add(item("Find Previous", KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), e -> findNext(false)));
        if (isMac()) {
            edit.add(item("Find Next (⌘G)", KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU), e -> findNext(true)));
            edit.add(item("Find Previous (⇧⌘G)", KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU | InputEvent.SHIFT_DOWN_MASK), e -> findNext(false)));
        }
        edit.add(item("Close Results", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.SHIFT_DOWN_MASK), e -> {
            LogTab t = currentTab();
            if (t != null) {
                t.closeResults();
            }
        }));
        edit.addSeparator();
        edit.add(item("Go to Line…", KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU), e -> goToLineDialog()));
        bar.add(edit);

        JMenu view = new JMenu("View");
        view.add(item("Follow (Tail)", KeyStroke.getKeyStroke(KeyEvent.VK_T, MENU), e -> setFollow(!followButton.isSelected())));
        view.addSeparator();
        view.add(item("Show Matching Lines Only / All Lines",
                KeyStroke.getKeyStroke(KeyEvent.VK_H, MENU | InputEvent.SHIFT_DOWN_MASK), e -> {
                    LogTab t = currentTab();
                    if (t != null) {
                        t.setMatchingOnly(!t.isMatchingOnly());
                    }
                }));
        view.add(item("Show / Hide Filters Panel", KeyStroke.getKeyStroke(KeyEvent.VK_J, MENU), e -> {
            LogTab t = currentTab();
            if (t != null) {
                t.toggleBottom();
            }
        }));
        view.addSeparator();
        view.add(item("Bigger Font", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, MENU), e -> changeFont(1)));
        view.add(item("Smaller Font", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, MENU), e -> changeFont(-1)));
        view.addSeparator();
        view.add(item("Toggle Dark Mode", null, e -> toggleDark()));
        bar.add(view);
        return bar;
    }

    private static JMenuItem item(String name, KeyStroke key, java.util.function.Consumer<ActionEvent> a) {
        JMenuItem mi = new JMenuItem(action(name, a));
        if (key != null) {
            mi.setAccelerator(key);
        }
        return mi;
    }

    private static Action action(String name, java.util.function.Consumer<ActionEvent> a) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                a.accept(e);
            }
        };
    }

    // ---------------------------------------------------------------- commands

    private void openDialog() {
        FileDialog fd = new FileDialog(this, "Open Log Files", FileDialog.LOAD);
        fd.setMultipleMode(true);
        String last = PREFS.get("lastDir", null);
        if (last != null) {
            fd.setDirectory(last);
        }
        fd.setVisible(true);
        for (File f : fd.getFiles()) {
            openFile(f.toPath());
        }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.getTabCount()) {
            return;
        }
        LogTab t = (LogTab) tabs.getComponentAt(index);
        tabs.removeTabAt(index);
        t.close();
        updateEmptyState();
    }

    private void findAllFromToolbar() {
        LogTab t = currentTab();
        String text = findField.getText();
        if (t == null || text.isEmpty()) {
            return;
        }
        SearchQuery q = new SearchQuery(text, matchCase.isSelected(), regex.isSelected(),
                wholeWord.isSelected());
        try {
            t.findAll(q);
            findField.putClientProperty("JComponent.outline", null);
        } catch (PatternSyntaxException ex) {
            findField.putClientProperty("JComponent.outline", "error");
            status.setText("Invalid regular expression: " + ex.getDescription());
        }
    }

    private void findNext(boolean forward) {
        LogTab t = currentTab();
        if (t == null) {
            return;
        }
        if (t.search() == null || !t.search().query().text().equals(findField.getText())) {
            findAllFromToolbar();
        }
        if (!t.findNext(forward)) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private void goToLineDialog() {
        LogTab t = currentTab();
        if (t == null) {
            return;
        }
        String s = JOptionPane.showInputDialog(this,
                String.format("Line number (1 – %,d):", t.document().lineCount()),
                "Go to Line", JOptionPane.QUESTION_MESSAGE);
        if (s == null) {
            return;
        }
        try {
            long line = Long.parseLong(s.trim().replace(",", "").replace("_", ""));
            t.showLine(Math.min(Integer.MAX_VALUE - 1, Math.max(0, line - 1)));
            t.mainView().focusView();
        } catch (NumberFormatException ex) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private void changeFont(int delta) {
        fontSize = Math.max(8, Math.min(32, fontSize + delta));
        PREFS.putFloat("fontSize", fontSize);
        for (LogTab t : allTabs()) {
            t.mainView().setFontSize(fontSize);
            t.resultsView().setFontSize(fontSize);
        }
    }

    void toggleDark() {
        boolean dark = !Theme.isDark();
        PREFS.putBoolean("dark", dark);
        Theme.apply(dark);
    }

    private List<LogTab> allTabs() {
        List<LogTab> out = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            out.add((LogTab) tabs.getComponentAt(i));
        }
        return out;
    }

    private void updateEmptyState() {
        ((CardLayout) center.getLayout()).show(center, tabs.getTabCount() == 0 ? "empty" : "tabs");
        refreshStatus();
    }

    @Override
    public void tabStatusChanged(LogTab tab) {
        if (tab != null && tab == currentTab()) {
            followButton.setSelected(tab.mainView().isFollow());
            refreshStatus();
        }
    }

    private void refreshStatus() {
        LogTab t = currentTab();
        if (t == null) {
            status.setText(" ");
            statusRight.setText(memory());
            progress.setVisible(false);
            setTitle("LogMaster");
            return;
        }
        LogDocument d = t.document();
        setTitle(d.path().getFileName() + " — LogMaster");
        StringBuilder sb = new StringBuilder();
        sb.append(d.path()).append("   ").append(humanBytes(d.fileSize()))
          .append("   ").append(String.format("%,d lines", d.lineCount()));
        if (d.isIndexing()) {
            sb.append("   indexing…");
        } else if (t.indexMillis() >= 0) {
            sb.append(String.format("   indexed in %,d ms", t.indexMillis()));
        }
        if (t.error() != null) {
            sb.append("   ⚠ ").append(t.error());
        }
        status.setText(sb.toString());

        long cur = t.currentLine();
        String pos = cur >= 0 ? String.format("Ln %,d", cur + 1) : "";
        if (t.isMatchingOnly()) {
            pos = "MATCHING ONLY   " + pos;
        }
        String follow = t.mainView().isFollow() ? "   FOLLOW" : "";
        statusRight.setText(pos + follow + "   UTF-8   " + memory());

        SearchSession s = t.search();
        if (d.isIndexing() && d.fileSize() > 0) {
            progress.setVisible(true);
            progress.setValue((int) (1000 * d.snapshot().indexedBytes / Math.max(1, d.fileSize())));
        } else if (s != null && s.isRunning()) {
            progress.setVisible(true);
            progress.setValue((int) (1000 * s.progress()));
        } else {
            progress.setVisible(false);
        }
    }

    private static String memory() {
        Runtime r = Runtime.getRuntime();
        long used = (r.totalMemory() - r.freeMemory()) >> 20;
        return used + " MB";
    }

    static String humanBytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.2f %s", v, u[i]);
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Accepts files dropped on the window. */
    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport s) {
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) s.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                for (File f : files) {
                    openFile(f.toPath());
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
