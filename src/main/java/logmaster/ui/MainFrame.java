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
public final class MainFrame extends JFrame implements LogTab.Host, FolderSearchTab.Host {
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainFrame.class);
    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel center = new JPanel(new CardLayout());
    private final JTextField findField = new JTextField(28);
    private final JToggleButton matchCase = toggle("Aa", "Match case");
    private final JToggleButton wholeWord = toggle("W", "Whole word");
    private final JToggleButton regex = toggle(".*", "Regular expression");
    private final JToggleButton followButton = new JToggleButton("Follow");
    private final JToggleButton wrapButton = new JToggleButton("Wrap");
    private boolean wrap = PREFS.getBoolean("wrap", false);
    private final JLabel status = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private float fontSize = PREFS.getFloat("fontSize", 13f);

    public MainFrame() {
        super("LogMaster");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(logmaster.tools.IconGenerator.windowIcons());
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
            if (tabs.getComponentAt(i) instanceof LogTab lt && lt.document().path().equals(abs)) {
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
            tab.mainView().setWrap(wrap);
            tab.resultsView().setWrap(wrap);
            tab.setTimelineVisible(PREFS.getBoolean("timeline", true));
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

    private void mergeDialog() {
        List<Path> candidates = new ArrayList<>();
        for (LogTab t : allTabs()) {
            if (t.sourceLabels() == null) {
                candidates.add(t.document().path());
            }
        }
        JPanel list = new JPanel();
        list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
        List<javax.swing.JCheckBox> boxes = new ArrayList<>();
        java.util.function.Consumer<Path> addBox = p -> {
            javax.swing.JCheckBox b = new javax.swing.JCheckBox(p.getFileName() + "   (" + p.getParent() + ")", true);
            b.putClientProperty("path", p);
            boxes.add(b);
            list.add(b);
            list.revalidate();
        };
        candidates.forEach(addBox);
        JButton add = new JButton("Add Files…");
        add.addActionListener(e -> {
            FileDialog fd = new FileDialog(this, "Add Logs to Merge", FileDialog.LOAD);
            fd.setMultipleMode(true);
            fd.setVisible(true);
            for (File f : fd.getFiles()) {
                addBox.accept(f.toPath());
            }
            SwingUtilities.getWindowAncestor(list).pack();
        });
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>Merge these logs into one, in time order.<br>"
                + "Each line gets a label like &lt;node1&gt; showing where it came from.</html>"),
                BorderLayout.NORTH);
        panel.add(new javax.swing.JScrollPane(list), BorderLayout.CENTER);
        panel.add(add, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(560, 300));
        if (JOptionPane.showConfirmDialog(this, panel, "Merge Files by Time",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<Path> chosen = new ArrayList<>();
        for (javax.swing.JCheckBox b : boxes) {
            if (b.isSelected()) {
                chosen.add((Path) b.getClientProperty("path"));
            }
        }
        if (chosen.size() < 2) {
            JOptionPane.showMessageDialog(this, "Choose at least two logs to merge.");
            return;
        }
        mergeFiles(chosen);
    }

    /** Merges logs by timestamp into a new tab (in the background, with progress). */
    public void mergeFiles(List<Path> files) {
        List<String> labels = logmaster.core.LogMerger.labels(files);
        List<logmaster.core.LogMerger.Source> sources = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            sources.add(new logmaster.core.LogMerger.Source(files.get(i), labels.get(i)));
        }
        javax.swing.JDialog dlg = new javax.swing.JDialog(this, "Merging…", false);
        JProgressBar bar = new JProgressBar(0, 1000);
        JButton cancel = new JButton("Cancel");
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        cancel.addActionListener(e -> cancelled.set(true));
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(new JLabel("Merging " + files.size() + " logs by time…"), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        p.add(cancel, BorderLayout.EAST);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        Thread t = new Thread(() -> {
            try {
                Path dir = Files.createTempDirectory("logmaster-merge-");
                dir.toFile().deleteOnExit();
                Path out = dir.resolve("merged-" + files.size() + "-logs.log");
                out.toFile().deleteOnExit();
                logmaster.core.LogMerger m = logmaster.core.LogMerger.merge(sources, out,
                        f -> SwingUtilities.invokeLater(() -> bar.setValue((int) (f * 1000))),
                        cancelled::get);
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    if (cancelled.get()) {
                        m.close();
                        return;
                    }
                    openFile(out);
                    LogTab tab = currentTab();
                    if (tab != null && tab.document().path().equals(out.toAbsolutePath().normalize())) {
                        tab.setSourceLabels(labels);
                        tab.addCloseHook(m::close);
                        int i = tabs.indexOfComponent(tab);
                        tabs.setTitleAt(i, "Merged (" + files.size() + " logs)");
                        tabs.setToolTipTextAt(i, tab.sourceLegend());
                        m.follow(500);
                    } else {
                        m.close();
                    }
                });
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    JOptionPane.showMessageDialog(this, "Cannot merge: " + e.getMessage(), "Merge",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "lm-merge-start");
        t.setDaemon(true);
        t.start();
    }

    /** Opens a new Folder Search tab (folder null = the current file's folder or the last one). */
    public FolderSearchTab openFolderSearch(Path folder) {
        if (folder == null && currentTab() != null) {
            folder = currentTab().document().path().getParent();
        }
        String text = findField.getText().isEmpty() ? null : findField.getText();
        FolderSearchTab tab = new FolderSearchTab(this, folder, text);
        tab.resultsView().setFontSize(fontSize);
        tabs.addTab(tab.title(), tab);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        updateEmptyState();
        SwingUtilities.invokeLater(tab::focusFind);
        return tab;
    }

    @Override
    public void runFindAll(SearchQuery q) {
        findAll(q);
    }

    @Override
    public void folderTabChanged(FolderSearchTab tab) {
        int i = tabs.indexOfComponent(tab);
        if (i >= 0) {
            tabs.setTitleAt(i, tab.title());
        }
        refreshStatus();
    }

    /** Unpacked copies of compressed files, so opening the same hit twice is instant. */
    private final java.util.Map<String, Path> unpacked = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void openHit(logmaster.core.FolderSearch.FileResult f, long line, SearchQuery q) {
        if (!f.isCompressed()) {
            openAt(f.file(), line, q);
            return;
        }
        String name = f.entry() != null ? f.entry().substring(f.entry().lastIndexOf('/') + 1)
                : f.file().getFileName().toString().replaceFirst("(?i)\\.gz$", "");
        status.setText("Unpacking " + name + "…");
        Thread t = new Thread(() -> {
            try {
                Path out = unpack(f, name);
                SwingUtilities.invokeLater(() -> openAt(out, line, q));
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Cannot unpack " + name + ":\n" + e.getMessage(), "Open",
                        JOptionPane.ERROR_MESSAGE));
            }
        }, "lm-unpack");
        t.setDaemon(true);
        t.start();
    }

    private Path unpack(logmaster.core.FolderSearch.FileResult f, String name) throws IOException {
        String key = f.file() + "!" + f.entry() + "@" + Files.getLastModifiedTime(f.file()).toMillis();
        Path done = unpacked.get(key);
        if (done != null && Files.exists(done)) {
            return done;
        }
        Path dir = Files.createTempDirectory("logmaster-");
        dir.toFile().deleteOnExit();
        Path out = dir.resolve(name.isEmpty() ? "entry.log" : name);
        if (f.entry() != null) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f.file().toFile())) {
                java.util.zip.ZipEntry e = zip.getEntry(f.entry());
                try (var in = zip.getInputStream(e)) {
                    Files.copy(in, out);
                }
            }
        } else {
            try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(f.file()), 1 << 16)) {
                Files.copy(in, out);
            }
        }
        out.toFile().deleteOnExit();
        unpacked.put(key, out);
        return out;
    }

    /** Opens a file at a line and runs Find All for the query there. */
    private void openAt(Path file, long line, SearchQuery q) {
        openFile(file);
        LogTab t = currentTab();
        if (t != null && t.document().path().equals(file.toAbsolutePath().normalize())) {
            findAll(q);
            t.showLineWhenAvailable(line);
            t.mainView().focusView();
        }
    }

    public LogTab currentTab() {
        return tabs.getSelectedComponent() instanceof LogTab t ? t : null;
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
        wrapButton.setToolTipText("Wrap long lines (" + (isMac() ? "⌥Z" : "Alt+Z") + ")");
        wrapButton.setSelected(wrap);
        wrapButton.addActionListener(e -> setWrap(wrapButton.isSelected()));

        tb.add(new JLabel(" "));
        tb.add(findField);
        tb.addSeparator(new Dimension(6, 1));
        tb.add(findAll);
        tb.add(prev);
        tb.add(next);
        tb.addSeparator();
        tb.add(followButton);
        tb.add(wrapButton);
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
        file.add(item("Merge Files by Time…", KeyStroke.getKeyStroke(KeyEvent.VK_M, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> mergeDialog()));
        file.add(item("Search in Folder…", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> openFolderSearch(null)));
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
        edit.add(item("Next Error", KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), e -> nextError(true)));
        edit.add(item("Previous Error", KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.SHIFT_DOWN_MASK), e -> nextError(false)));
        edit.add(item("Close Results", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.SHIFT_DOWN_MASK), e -> {
            LogTab t = currentTab();
            if (t != null) {
                t.closeResults();
            }
        }));
        edit.addSeparator();
        edit.add(item("Go to Line…", KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU), e -> goToLineDialog()));
        edit.add(item("Go to Time…", KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> goToTimeDialog()));
        bar.add(edit);

        JMenu view = new JMenu("View");
        view.add(item("Follow (Tail)", KeyStroke.getKeyStroke(KeyEvent.VK_T, MENU), e -> setFollow(!followButton.isSelected())));
        view.addSeparator();
        view.add(item("Timeline", null, e -> {
            boolean on = !PREFS.getBoolean("timeline", true);
            PREFS.putBoolean("timeline", on);
            for (LogTab t : allTabs()) {
                t.setTimelineVisible(on);
            }
        }));
        view.add(item("Word Wrap", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.ALT_DOWN_MASK),
                e -> setWrap(!wrap)));
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
        java.awt.Component c = tabs.getComponentAt(index);
        tabs.removeTabAt(index);
        if (c instanceof LogTab t) {
            t.close();
        } else if (c instanceof FolderSearchTab f) {
            f.close();
        }
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

    private void nextError(boolean forward) {
        LogTab t = currentTab();
        if (t != null && !t.nextError(forward)) {
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

    private void goToTimeDialog() {
        LogTab t = currentTab();
        if (t == null || t.timeIndex() == null) {
            JOptionPane.showMessageDialog(this, "No timestamps found in this file.", "Go to Time",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        long ref = t.currentLineTime();
        if (ref == logmaster.core.TimeFormat.NONE) {
            ref = t.timeIndex().samples().minTime();
        }
        String s = (String) JOptionPane.showInputDialog(this,
                "Time (10:05, 10:05:30.250 or 2026-10-21 10:05):", "Go to Time",
                JOptionPane.QUESTION_MESSAGE, null, null, TimelineBar.formatFull(ref).substring(11, 19));
        if (s == null) {
            return;
        }
        long time = TimelineBar.parseUserTime(s.trim(), ref);
        if (time == logmaster.core.TimeFormat.NONE) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        t.goToTime(time);
    }

    private void changeFont(int delta) {
        fontSize = Math.max(8, Math.min(32, fontSize + delta));
        PREFS.putFloat("fontSize", fontSize);
        for (LogTab t : allTabs()) {
            t.mainView().setFontSize(fontSize);
            t.resultsView().setFontSize(fontSize);
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof FolderSearchTab f) {
                f.resultsView().setFontSize(fontSize);
            }
        }
    }

    /** Wraps long lines in every log view. */
    public void setWrap(boolean on) {
        wrap = on;
        PREFS.putBoolean("wrap", on);
        wrapButton.setSelected(on);
        for (LogTab t : allTabs()) {
            t.mainView().setWrap(on);
            t.resultsView().setWrap(on);
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
            if (tabs.getComponentAt(i) instanceof LogTab t) {
                out.add(t);
            }
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
        if (tabs.getSelectedComponent() instanceof FolderSearchTab f) {
            setTitle(f.title() + " — LogMaster");
            status.setText(f.statusText());
            statusRight.setText(memory());
            progress.setVisible(f.isSearching());
            progress.setValue((int) (1000 * f.progress()));
            return;
        }
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
        long time = t.currentLineTime();
        if (time != logmaster.core.TimeFormat.NONE) {
            pos += "   " + TimelineBar.formatFull(time);
        }
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
                    if (f.isDirectory()) {
                        openFolderSearch(f.toPath()); // a dropped folder: search in it
                    } else {
                        openFile(f.toPath());
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
