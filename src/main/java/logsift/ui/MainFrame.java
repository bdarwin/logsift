package logsift.ui;

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
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
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
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import logsift.core.LogDocument;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;

/** The application window. */
public final class MainFrame extends JFrame implements LogTab.Host, FolderSearchTab.Host, CompareTab.Host {
    private static final Preferences PREFS = AppState.prefs();
    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel center = new JPanel(new CardLayout());
    private final JLabel status = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final JButton updateButton = new JButton();
    /** What the window shows with nothing open. */
    private final StartPage.Host startHost = new StartPage.Host() {
        @Override
        public void openLogs() {
            openDialog();
        }

        @Override
        public void openFolder() {
            openFolderDialog();
        }

        @Override
        public void searchFolder() {
            openFolderSearch(null);
        }

        @Override
        public void openRemote() {
            openRemoteDialog();
        }

        @Override
        public void attachJvm() {
            openJvmDialog();
        }

        @Override
        public void openThreadDump() {
            openThreadDumpDialog();
        }

        @Override
        public void openProfile() {
            openProfileDialog();
        }

        @Override
        public void openHeapDump() {
            openHeapDumpDialog();
        }

        @Override
        public void openUrl() {
            openUrlDialog();
        }

        @Override
        public void openRecent(Path file) {
            openFile(file);
        }

        @Override
        public void reopenSession() {
            restoreSession();
        }

        @Override
        public void compareFiles() {
            compareFilesDialog();
        }
    };
    private final StartPage startPage = new StartPage(startHost);
    private String updateUrl = HelpSupport.REPO + "/releases/latest";
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private float fontSize = PREFS.getFloat("fontSize", 13f);

    /** Ends the program when the window is closed (set by the app, not by tests). */
    static volatile boolean exitOnClose;

    public MainFrame() {
        super(logsift.core.Channel.appName());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(logsift.tools.IconGenerator.windowIcons());
        setSize(PREFS.getInt("width", 1280), PREFS.getInt("height", 820));
        setLocationByPlatform(true);

        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close");
        tabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.IntConsumer) this::closeTab);
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
        // a middle click on a tab closes it, as its ✕ does (not on the empty strip)
        tabs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (MiddleClick.closes(e, i)) {
                    closeTab(i);
                }
            }
        });
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // + after the tabs: the start page in a tab (open a log, a folder, a JVM...)
        JButton plus = new JButton(Icons.of("plus"));
        plus.setToolTipText("Open something: a log, a folder, a remote log, a JVM…");
        plus.putClientProperty("JButton.buttonType", "toolBarButton");
        plus.setFocusable(false);
        plus.addActionListener(e -> openStartTab());
        JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
        trailing.setOpaque(false);
        trailing.add(plus);
        tabs.putClientProperty("JTabbedPane.trailingComponent", trailing);
        // a start tab goes away once something is opened from it (or anywhere)
        tabs.addContainerListener(new java.awt.event.ContainerAdapter() {
            @Override
            public void componentAdded(java.awt.event.ContainerEvent e) {
                if (!(e.getChild() instanceof StartPage)) {
                    SwingUtilities.invokeLater(() -> {
                        for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
                            if (tabs.getComponentAt(i) instanceof StartPage) {
                                tabs.removeTabAt(i);
                            }
                        }
                    });
                }
            }
        });
        tabs.addChangeListener(e -> {
            LogTab t = currentTab();
            if (t != null && t.getClientProperty("alerts") != null) {
                t.putClientProperty("alerts", null); // seen: clear the badge
                int i = tabs.indexOfComponent(t);
                if (t.sourceLabels() == null) {
                    tabs.setTitleAt(i, t.title());
                }
            }
            tabStatusChanged(t);
        });

        center.add(startPage, "empty");
        center.add(tabs, "tabs");

        JPanel root = new JPanel(new BorderLayout());
        root.add(center, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);
        setJMenuBar(buildMenus());
        setTransferHandler(new FileDropHandler());
        updateEmptyState();
        HeapDumpTab.cleanUpIndexes(); // left by a crash, or kept by Windows while mapped

        new Timer(250, e -> refreshStatus()).start();
        Timer updates = new Timer(4000, e -> autoCheckForUpdates());
        updates.setRepeats(false);
        updates.start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                PREFS.putInt("width", getWidth());
                PREFS.putInt("height", getHeight());
                saveSession();
                for (LogTab t : allTabs()) {
                    t.close();
                }
                if (exitOnClose) {
                    // Timers and background work would keep Java running without a window
                    // (and keep the files in use, so an uninstall can't remove them).
                    try {
                        PREFS.flush();
                    } catch (java.util.prefs.BackingStoreException ex) {
                        // saved on exit anyway, where possible
                    }
                    System.exit(0);
                }
            }
        });
    }

    // ---------------------------------------------------------------- public API

    /** Opens files handed over by the system or a second launch, and comes to the front. */
    public void openFiles(List<Path> files) {
        for (Path p : files) {
            if (Files.isDirectory(p)) {
                openFolder(p); // a folder (Open with Logsift on a folder): its files, in a tab
            } else {
                openFile(p);
            }
        }
        if (getExtendedState() == ICONIFIED) {
            setExtendedState(NORMAL);
        }
        toFront();
        requestFocus();
        if (java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
            java.awt.Desktop.getDesktop().requestForeground(true);
        }
    }

    /** Opens the file that piped input is copied into, following it as it grows. */
    public void openPiped(Path capture) {
        openFile(capture, null, t -> setFollow(true));
    }

    // ---------------------------------------------------------------- updates

    private void autoCheckForUpdates() {
        if (!PREFS.getBoolean("updates.auto", true) || logsift.core.UpdateChecker.url() == null) {
            return;
        }
        String known = PREFS.get("updates.latest", null);
        if (known != null && logsift.core.Version.isNewer(known, logsift.core.Version.current())) {
            showUpdate(known, logsift.core.UpdateChecker.page(known)); // known from an earlier check
        }
        long last = PREFS.getLong("updates.lastCheck", 0);
        if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
            checkForUpdates(false);
        }
    }

    /**
     * Asks GitHub for the latest release (in the background). A newer one shows a button in the
     * status bar; when {@code manual}, the answer is also shown in a dialog.
     */
    void checkForUpdates(boolean manual) {
        String url = logsift.core.UpdateChecker.url();
        if (url == null) {
            if (manual) {
                JOptionPane.showMessageDialog(this, "Update checks are turned off.");
            }
            return;
        }
        Thread t = new Thread(() -> {
            java.util.Optional<logsift.core.UpdateChecker.Release> r;
            String error = null;
            try {
                r = logsift.core.UpdateChecker.latest(url);
            } catch (java.io.IOException e) {
                r = java.util.Optional.empty();
                error = e.getMessage();
            }
            java.util.Optional<logsift.core.UpdateChecker.Release> result = r;
            String err = error;
            SwingUtilities.invokeLater(() -> updateChecked(result, err, manual));
        }, "logsift-update-check");
        t.setDaemon(true);
        t.start();
    }

    private void updateChecked(java.util.Optional<logsift.core.UpdateChecker.Release> r, String error, boolean manual) {
        String current = logsift.core.Version.current();
        if (r.isPresent()) {
            PREFS.putLong("updates.lastCheck", System.currentTimeMillis());
            PREFS.put("updates.latest", r.get().version());
            PREFS.put("updates.url", r.get().url());
        }
        boolean newer = r.isPresent() && logsift.core.Version.isNewer(r.get().version(), current);
        if (newer) {
            showUpdate(r.get().version(), r.get().url());
        } else {
            updateButton.setVisible(false);
        }
        if (!manual) {
            return;
        }
        if (newer) {
            int answer = JOptionPane.showOptionDialog(this,
                    "Logsift " + r.get().version() + " is available. You have " + current + ".",
                    "Update Available", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                    new Object[] {"Download", "Later"}, "Download");
            if (answer == 0) {
                HelpSupport.browse(this, r.get().url());
            }
        } else if (r.isPresent()) {
            JOptionPane.showMessageDialog(this, "You have the latest version (" + current + ").",
                    "No Update", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "Couldn't check for updates"
                    + (error == null ? "." : ": " + error) + "\nYou can look at " + HelpSupport.REPO + "/releases",
                    "Check for Updates", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showUpdate(String version, String url) {
        updateUrl = url;
        updateButton.setText("Logsift " + version + " is available");
        updateButton.setToolTipText("Open the download page for version " + version);
        updateButton.setVisible(true);
    }

    /** Shows the start page in a tab (the + after the tabs). */
    void openStartTab() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof StartPage) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
        StartPage p = new StartPage(startHost);
        tabs.addTab("Start", p);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        updateEmptyState();
    }

    StartPage startPageForTest() {
        return startPage;
    }

    void hideUpdateForTest() {
        updateButton.setVisible(false);
    }

    String updateButtonTextForTest() {
        return updateButton.isVisible() ? updateButton.getText() : null;
    }

    private void openErrorLog() {
        Path f = AppState.home().resolve("errors.log");
        if (java.nio.file.Files.exists(f)) {
            openFile(f);
        } else {
            JOptionPane.showMessageDialog(this, "No errors have been logged.");
        }
    }

    /** Opens a file in a new tab (or selects it if already open). */
    public void openFile(Path path) {
        openFile(path, null, null);
    }

    /** Opens a file with a given text encoding (null: detect it). */
    public void openFile(Path path, java.nio.charset.Charset forced) {
        openFile(path, forced, null);
    }

    /** Files being opened right now (opening can take a while on network or cloud drives). */
    private final java.util.Set<Path> opening = new java.util.HashSet<>();
    private static final java.util.concurrent.ExecutorService OPENER =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "logsift-open");
                t.setDaemon(true);
                return t;
            });

    private record Opened(java.nio.charset.Charset charset, LogDocument doc, Path read) {}

    /**
     * Opens (or selects) a file. The slow parts (checking the file, reading its start to detect
     * the encoding, opening it) run in the background, so a file on a slow network or cloud drive
     * never freezes the window: after a moment an "Opening…" window with Cancel appears.
     *
     * @param then gets the file's tab once it is open (not called if it fails or is cancelled)
     */
    public void openFile(Path path, java.nio.charset.Charset forced, java.util.function.Consumer<LogTab> then) {
        Path abs = path.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof LogTab lt && lt.path().equals(abs)) {
                if (restore.takesSelection(abs)) {
                    tabs.setSelectedIndex(i);
                }
                if (!isOwnCopy(abs)) {
                    AppState.addRecent(abs);
                }
                if (then != null) {
                    then.accept(lt);
                }
                return;
            }
        }
        if (logsift.core.Archives.isZip(abs)) {
            openArchive(abs, then);
            return;
        }
        if (abs.getFileName() != null && abs.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".hprof")) {
            openHeapDump(abs);
            return;
        }
        logsift.core.Archives.Located in = logsift.core.Archives.locate(abs);
        if (in != null || logsift.core.Archives.isGzip(abs)) {
            openPacked(abs, in, forced, then);
            return;
        }
        openReal(abs, abs, forced, then);
    }

    /**
     * Opens {@code read} in a tab named by {@code abs}: the same file, or (for a file in a zip
     * or a .gz) the unpacked copy of it.
     */
    private void openReal(Path read, Path abs, java.nio.charset.Charset forced,
                          java.util.function.Consumer<LogTab> then) {
        if (!opening.add(abs)) {
            restore.finished(abs); // already on its way (opened by hand): that open selects it
            return;
        }
        java.util.concurrent.CompletableFuture<Opened> f = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                long testDelay = Long.getLong("logsift.testOpenDelayMs", 0); // tests: a slow drive
                if (testDelay > 0) {
                    Thread.sleep(testDelay);
                }
                if (Files.isDirectory(read)) {
                    throw new FolderNotFile(); // a folder (from Recent files): its tab instead
                }
                if (!Files.isRegularFile(read)) {
                    throw new IOException("Not a file (or not reachable): " + abs);
                }
                java.nio.charset.Charset cs = forced;
                if (cs == null) {
                    try {
                        cs = logsift.core.Encoding.detect(read, fallbackEncoding());
                    } catch (IOException e) {
                        cs = java.nio.charset.StandardCharsets.UTF_8;
                    }
                }
                if (logsift.core.Encoding.isUtf16(cs)) {
                    return new Opened(cs, null, read);
                }
                return new Opened(cs, LogDocument.open(read, logsift.core.LineIndex.DEFAULT_CHUNK_SIZE, cs), read);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }, OPENER);
        try {
            // usually done in milliseconds: then open right away, without any window
            Opened r = f.get(Long.getLong("logsift.openWaitMs", 300), java.util.concurrent.TimeUnit.MILLISECONDS);
            finishOpen(abs, r, then);
        } catch (java.util.concurrent.TimeoutException e) {
            openSlowly(abs, f, then);
        } catch (java.util.concurrent.ExecutionException e) {
            opening.remove(abs);
            showOpenError(abs, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            opening.remove(abs);
            restore.finished(abs);
        }
    }

    /** Shows "Opening…" with Cancel while a slow file opens; the window stays usable. */
    private void openSlowly(Path abs, java.util.concurrent.CompletableFuture<Opened> f,
                            java.util.function.Consumer<LogTab> then) {
        javax.swing.JDialog dlg = new javax.swing.JDialog(this, "Opening " + abs.getFileName(), false);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JButton cancel = new JButton("Cancel");
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(new JLabel("<html>Opening <b>" + abs.getFileName() + "</b>…<br>"
                + "<span style='color:gray'>Waiting for the file. It may be on a network or cloud drive"
                + " that has to fetch it first.</span></html>"), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        p.add(cancel, BorderLayout.EAST);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        cancel.addActionListener(e -> {
            cancelled.set(true);
            opening.remove(abs);
            restore.finished(abs);
            dlg.dispose();
        });
        dlg.setVisible(true);
        status.setText("Opening " + abs + "…");
        f.whenComplete((r, ex) -> SwingUtilities.invokeLater(() -> {
            dlg.dispose();
            if (cancelled.get()) {
                if (r != null && r.doc() != null) {
                    r.doc().close();
                }
                return;
            }
            if (ex != null) {
                opening.remove(abs);
                showOpenError(abs, ex instanceof java.util.concurrent.CompletionException c ? c.getCause() : ex);
            } else {
                finishOpen(abs, r, then);
            }
        }));
    }

    private void finishOpen(Path abs, Opened r, java.util.function.Consumer<LogTab> then) {
        opening.remove(abs);
        if (r.doc() == null) {
            openUtf16(abs, r.read(), r.charset(), then);
            return;
        }
        LogTab tab = new LogTab(r.doc(), abs, null, this);
        addLogTab(tab, abs);
        if (then != null) {
            then.accept(tab);
        }
    }

    /**
     * Files from the last session still on their way: if they are gone now, say nothing, and
     * only the one that was selected takes the selection when it opens.
     */
    private final SessionRestore restore = new SessionRestore();

    /** Thrown when what was opened as a file is a folder. */
    private static final class FolderNotFile extends IOException {
        FolderNotFile() {
            super("a folder");
        }
    }

    private void showOpenError(Path abs, Throwable e) {
        Throwable c = e instanceof java.io.UncheckedIOException u ? u.getCause() : e;
        if (c instanceof FolderNotFile) {
            restore.finished(abs);
            openFolder(abs);
            return;
        }
        if (restore.finished(abs)) {
            status.setText("Not reopened (missing): " + abs);
            return;
        }
        Throwable cause = e instanceof java.io.UncheckedIOException u ? u.getCause() : e;
        JOptionPane.showMessageDialog(this, "Cannot open " + abs + ":\n"
                + (cause == null ? "unknown error" : cause.getMessage()), "Open", JOptionPane.ERROR_MESSAGE);
    }

    /** UTF-16 logs are read through a UTF-8 copy that follows the original. */
    private void openUtf16(Path abs, Path read, java.nio.charset.Charset cs,
                           java.util.function.Consumer<LogTab> then) {
        String message = "Converting " + abs.getFileName() + " from " + cs.name() + "…";
        ProgressDialog.run(this, message,
                (progress, cancelled) -> {
                    Path dir = Files.createTempDirectory("logsift-utf16-");
                    dir.toFile().deleteOnExit();
                    Path copy = dir.resolve(abs.getFileName().toString());
                    copy.toFile().deleteOnExit();
                    return logsift.core.Utf16Mirror.create(read, copy, cs, progress, cancelled);
                },
                mirror -> {
                    try {
                        LogTab tab = new LogTab(mirror.copy(), abs, java.nio.charset.StandardCharsets.UTF_8,
                                cs.name(), this);
                        tab.addCloseHook(mirror::close);
                        mirror.follow(500);
                        addLogTab(tab, abs);
                        if (then != null) {
                            then.accept(tab);
                        }
                    } catch (IOException e) {
                        mirror.close();
                        restore.finished(abs);
                        JOptionPane.showMessageDialog(this, "Cannot open " + abs + ":\n" + e.getMessage(),
                                "Open", JOptionPane.ERROR_MESSAGE);
                    }
                }, e -> {
                    restore.finished(abs);
                    JOptionPane.showMessageDialog(this, message + " failed: " + e.getMessage(), "Logsift",
                            JOptionPane.ERROR_MESSAGE);
                }, () -> restore.finished(abs));
    }

    /** Shows a new log tab with the current view settings and the file's remembered state. */
    private void addLogTab(LogTab tab, Path abs) {
        tab.mainView().setFontSize(fontSize);
        tab.resultsView().setFontSize(fontSize);
        // each log has its own view settings; a new one starts from the last choice
        tab.setWrap(PREFS.getBoolean("wrap", false));
        tab.mainView().setStripes(PREFS.getBoolean("stripes", true));
        tab.resultsView().setStripes(PREFS.getBoolean("stripes", true));
        tab.setTimelineVisible(PREFS.getBoolean("timeline", true));
        tab.setColumnsVisible(PREFS.getBoolean("columns", false));
        tab.setAutoReload(PREFS.getBoolean("autoReload", false));
        tabs.addTab(tab.title(), tab);
        int idx = tabs.getTabCount() - 1;
        tabs.setToolTipTextAt(idx, abs.toString());
        if (restore.takesSelection(abs)) {
            tabs.setSelectedIndex(idx);
        }
        if (!isOwnCopy(abs)) {
            logsift.core.Archives.Located in = logsift.core.Archives.locate(abs);
            Path dirOf = in != null ? in.zip().getParent() : abs.getParent();
            if (dirOf != null) {
                PREFS.put("lastDir", dirOf.toString());
            }
            AppState.addRecent(abs);
        }
        java.util.Properties state = AppState.loadFileState(abs);
        if (state != null) {
            tab.restoreState(state);
        }
        updateEmptyState();
        SwingUtilities.invokeLater(() -> tab.mainView().focusView());
        if (tab.remoteLabel() == null) {
            SwingUtilities.invokeLater(() -> startJvmBar(tab, null)); // after a merged log gets its labels
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
        JLabel how = new JLabel(" ");
        panel.add(how, BorderLayout.NORTH);
        panel.add(new javax.swing.JScrollPane(list), BorderLayout.CENTER);
        panel.add(add, BorderLayout.SOUTH);
        // say what the merge will do, for the files ticked now (reading their first lines)
        Runnable explain = () -> {
            List<Path> on = new ArrayList<>();
            for (javax.swing.JCheckBox b : boxes) {
                if (b.isSelected()) {
                    on.add((Path) b.getClientProperty("path"));
                }
            }
            Thread t = new Thread(() -> {
                String text = mergeExplanation(on);
                SwingUtilities.invokeLater(() -> {
                    how.setText(text);
                    mergeExplanationForTest = text;
                });
            }, "logsift-merge-plan");
            t.setDaemon(true);
            t.start();
        };
        list.addContainerListener(new java.awt.event.ContainerAdapter() {
            @Override
            public void componentAdded(java.awt.event.ContainerEvent e) {
                if (e.getChild() instanceof javax.swing.JCheckBox b) {
                    b.addActionListener(a -> explain.run());
                }
                explain.run();
            }
        });
        for (javax.swing.JCheckBox b : boxes) {
            b.addActionListener(a -> explain.run());
        }
        explain.run();
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

    private RemoteDialog remoteDialog;

    private void openRemoteDialog() {
        if (remoteDialog != null && remoteDialog.isDisplayable()) {
            remoteDialog.toFront();
            return;
        }
        Path knownHosts = Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        remoteDialog = new RemoteDialog(this, knownHosts, this::trustHost,
                c -> openRemote(c.target(), c.lastBytes()));
        remoteDialog.setVisible(true);
    }

    RemoteDialog openRemoteDialogForTest() {
        openRemoteDialog();
        return remoteDialog;
    }

    /** Opens a log on another machine over SFTP, kept up to date while it grows. */
    public void openRemote(logsift.core.RemoteMirror.Target target, long lastBytes) {
        Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        ProgressDialog.run(this, "Connecting to " + target.host() + "…",
                (progress, cancelled) -> {
                    Path dir = Files.createTempDirectory("logsift-remote-");
                    dir.toFile().deleteOnExit();
                    String name = target.path().substring(target.path().lastIndexOf('/') + 1);
                    Path copy = dir.resolve(name.isEmpty() ? "remote.log" : name);
                    copy.toFile().deleteOnExit();
                    return logsift.core.RemoteMirror.open(target, copy, lastBytes,
                            Boolean.getBoolean("logsift.noKnownHosts") ? null : knownHosts,
                            this::trustHost, progress, cancelled);
                },
                mirror -> {
                    try {
                        LogTab tab = new LogTab(mirror.copy(), mirror.copy(),
                                java.nio.charset.StandardCharsets.UTF_8, null, this);
                        tab.setRemote(target.label());
                        tab.setSshTarget(target);
                        tab.addCloseHook(() -> {
                            mirror.close();
                            try {
                                Files.deleteIfExists(mirror.copy());
                            } catch (IOException ex) {
                                // temp file
                            }
                        });
                        mirror.onStatus(st -> SwingUtilities.invokeLater(() -> tab.setRemoteStatus(st)));
                        mirror.follow(1000);
                        addLogTab(tab, mirror.copy());
                        startJvmBar(tab, target);
                        int i = tabs.indexOfComponent(tab);
                        tabs.setToolTipTextAt(i, "sftp://" + target.user() + "@" + target.host() + ":"
                                + target.port() + target.path()
                                + (mirror.isComplete() ? "" : "  (only the end of the file was loaded)"));
                    } catch (IOException e) {
                        mirror.close();
                        JOptionPane.showMessageDialog(this, "Cannot open the copy: " + e.getMessage());
                    }
                });
    }

    /** Asked (on a background thread) whether to trust a server's host key. */
    private boolean trustHost(String message) {
        boolean[] yes = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> yes[0] = JOptionPane.showConfirmDialog(this,
                    "<html><b>First connection to this server.</b><br>" + message.replace("\n", "<br>")
                            + "<br><br>Trust it and remember its key?</html>", "Host Key",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
        } catch (Exception e) {
            return false;
        }
        return yes[0];
    }

    private void compareDialog() {
        LogTab b = currentTab();
        List<LogTab> others = new ArrayList<>(allTabs());
        others.remove(b);
        if (b == null || others.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Open two logs first: the one to look at, and a baseline"
                    + " (for example a run that went well).", "Compare", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<String> pick = new JComboBox<>(others.stream().map(LogTab::title).toArray(String[]::new));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html>Compare <b>" + b.title() + "</b> with which baseline?<br>"
                + "(for example the log of a run that went well)</html>"), BorderLayout.NORTH);
        p.add(pick, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, p, "Compare Logs", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            compare(others.get(pick.getSelectedIndex()), b);
        }
    }

    /** Opens a tab comparing log {@code b} with the baseline {@code a}. */
    public CompareTab compare(LogTab a, LogTab b) {
        CompareTab tab = new CompareTab(a, b, this);
        tabs.addTab(tab.title(), tab);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        updateEmptyState();
        return tab;
    }

    // ---------------------------------------------------------------- comparing files

    /** For tests: the last message the compare tool gave instead of opening a tab. */
    volatile String compareNoteForTest;

    /** File → Compare Files…: a Compare tab (the current CSV file chosen on the left). */
    void compareFilesDialog() {
        LogTab t = currentTab();
        Path left = t != null && t.isCsv() ? t.path() : null;
        openCompare(left, null);
    }

    /** A CSV tab's Compare button: this file on the left, the other one to choose next to it. */
    void compareWith(LogTab t) {
        openCompare(t.path(), null);
    }

    /** Compares two files in a new Compare tab: CSV and TSV files as tables (by key); others get a note for now. */
    void compareFiles(Path left, Path right) {
        openCompare(left, right);
    }

    /**
     * Opens a Compare tab: a file browser on each side, showing {@code left} and {@code right}
     * (either may be null: the browsers then show the last folder used); with both, it compares them.
     */
    FileCompareTab openCompare(Path left, Path right) {
        String last = PREFS.get("lastDir", null);
        Path near = left != null ? left.toAbsolutePath().getParent() : last != null ? Path.of(last) : null;
        FileCompareTab tab = new FileCompareTab(left, right, near, compareHost);
        tabs.addTab(tab.title(), Icons.of("git-compare"), tab);
        int i = tabs.getTabCount() - 1;
        tabs.setToolTipTextAt(i, tab.tooltip());
        tabs.setSelectedIndex(i);
        updateEmptyState();
        return tab;
    }

    /** How a file to compare is read: a .gz unpacked first; folders and zips refused. */
    private CsvCompareTab.Side compareSide(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        if (Files.isDirectory(abs)) {
            throw new IOException(abs.getFileName() + " is a folder: choose a file in it");
        }
        if (logsift.core.Archives.isZip(abs)) {
            throw new IOException(abs.getFileName() + " is a zip: open it and choose a file in it");
        }
        logsift.core.Archives.Located in = logsift.core.Archives.locate(abs);
        if (in != null) {
            Path copy = unpack(in.zip(), in.entry(), -1, f -> { }, () -> false);
            return new CsvCompareTab.Side(copy, abs,
                    logsift.core.Archives.gunzippedName(logsift.core.Archives.baseName(in.entry())));
        }
        if (logsift.core.Archives.isGzip(abs)) {
            Path copy = unpack(abs, null, -1, f -> { }, () -> false);
            return new CsvCompareTab.Side(copy, abs, logsift.core.Archives.gunzippedName(abs.getFileName().toString()));
        }
        return CsvCompareTab.Side.of(abs);
    }

    private void compareNote(String text) {
        compareNoteForTest = text;
        status.setText(text.replace("\n", " "));
    }

    private final FileCompareTab.Host compareHost = new FileCompareTab.Host() {
        @Override
        public void openLine(Path file, long line) {
            openFile(file, null, t -> {
                tabs.setSelectedComponent(t);
                t.showLineWhenAvailable(line);
                t.mainView().focusView();
            });
        }

        @Override
        public void titleChanged(FileCompareTab tab) {
            int i = tabs.indexOfComponent(tab);
            if (i >= 0) {
                tabs.setTitleAt(i, tab.title());
                tabs.setToolTipTextAt(i, tab.tooltip());
                if (tab == tabs.getSelectedComponent()) {
                    refreshStatus();
                }
            }
        }

        @Override
        public CsvCompareTab.Side sideFor(Path p) throws IOException {
            return compareSide(p);
        }

        @Override
        public void note(String text) {
            compareNote(text);
        }
    };

    /** For tests: the menu item with this text, or null. */
    JMenuItem findMenuItemForTest(String text) {
        JMenuBar bar = getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenuItem it = findItem(bar.getMenu(i), text);
            if (it != null) {
                return it;
            }
        }
        return null;
    }

    private static JMenuItem findItem(JMenu m, String text) {
        for (int i = 0; i < m.getItemCount(); i++) {
            JMenuItem it = m.getItem(i);
            if (it instanceof JMenu sub) {
                JMenuItem found = findItem(sub, text);
                if (found != null) {
                    return found;
                }
            } else if (it != null && text.equals(it.getText())) {
                return it;
            }
        }
        return null;
    }

    CsvCompareTab currentCsvCompareTab() {
        return tabs.getSelectedComponent() instanceof FileCompareTab t ? t.csv() : null;
    }

    FileCompareTab currentCompareTabForTest() {
        return tabs.getSelectedComponent() instanceof FileCompareTab t ? t : null;
    }

    /** F7 / Shift+F7 in a comparison. */
    private void nextDifference(boolean forward) {
        CsvCompareTab t = currentCsvCompareTab();
        if (t != null) {
            t.nextChange(forward);
        }
    }

    // ---------------------------------------------------------------- thread dumps

    /** Opens a Threads tab with these dumps (and new ones from {@code capture}, if not null). */
    public ThreadsTab openThreads(String source, List<logsift.jvm.ThreadDump> dumps, ThreadsTab.Capture capture) {
        ThreadsTab tab = new ThreadsTab(source, dumps, capture);
        tabs.addTab(tab.title(), Icons.of("cpu"), tab);
        int i = tabs.getTabCount() - 1;
        tabs.setToolTipTextAt(i, capture != null ? "Threads of " + capture.label() : source);
        tabs.setSelectedIndex(i);
        tab.onChanged(() -> {
            int k = tabs.indexOfComponent(tab);
            if (k >= 0) {
                logsift.jvm.ThreadAnalysis.Report r = tab.report();
                int problems = r == null ? 0 : r.problems();
                tabs.setTitleAt(k, tab.title() + (problems > 0 ? " (" + problems + ")" : ""));
            }
            if (tabs.getSelectedComponent() == tab) {
                refreshStatus();
            }
        });
        tab.onChangedNow();
        updateEmptyState();
        return tab;
    }

    private JvmDialog jvmDialog;

    private void openJvmDialog() {
        if (jvmDialog != null && jvmDialog.isDisplayable()) {
            jvmDialog.toFront();
            return;
        }
        Path knownHosts = knownHostsFile();
        jvmDialog = new JvmDialog(this, knownHosts, this::trustHost,
                c -> openLiveThreads(c.source(), c.first(), c.capture(), c.target()));
        jvmDialog.setVisible(true);
    }

    /** A Threads tab of a running JVM, with its Profile and Heap buttons. */
    private ThreadsTab openLiveThreads(String source, logsift.jvm.ThreadDump first, ThreadsTab.Capture capture,
                                       logsift.jvm.Profiler.Target target) {
        ThreadsTab tab = openThreads(source, List.of(first), capture);
        if (target != null) {
            tab.setProfiler(() -> openProfile(source, target));
            tab.setHeap(() -> openHeap(source, target));
        }
        return tab;
    }

    private Path knownHostsFile() {
        return Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
    }

    // ---------------------------------------------------------------- a log's JVM

    /** The JVM bar of each log tab. */
    private final java.util.Map<LogTab, JvmBar> jvmBars = new java.util.IdentityHashMap<>();
    private JvmDialog linkDialog;

    private final JvmBar.Host jvmBarHost = new JvmBar.Host() {
        @Override
        public JvmConnector connector() {
            return new JvmConnector(MainFrame.this, knownHostsFile(), MainFrame.this::trustHost);
        }

        @Override
        public void linkDialog(JvmBar bar, JvmDialog.Suggestion s) {
            LogTab log = logOf(bar);
            if (log == null) {
                return;
            }
            if (linkDialog != null && linkDialog.isDisplayable()) {
                linkDialog.dispose();
            }
            linkDialog = new JvmDialog(MainFrame.this, knownHostsFile(), MainFrame.this::trustHost, c -> {
                if (tabs.indexOfComponent(log) < 0) {
                    c.capture().close(); // the log was closed meanwhile
                    return;
                }
                bar.linked(c);
                bar.showThreads(t -> { });
            }, log.title(), s);
            linkDialog.setVisible(true);
        }

        @Override
        public ThreadsTab openThreads(JvmBar bar, String source, logsift.jvm.ThreadDump first,
                                      ThreadsTab.Capture capture, logsift.jvm.Profiler.Target target) {
            ThreadsTab tab = openLiveThreads(source, first, capture, target);
            LogTab log = logOf(bar);
            if (log != null) {
                linkThreadsToLog(tab, log);
            }
            return tab;
        }

        @Override
        public void openProfile(String source, logsift.jvm.Profiler.Target target) {
            MainFrame.this.openProfile(source, target);
        }

        @Override
        public void openHeap(String source, logsift.jvm.Profiler.Target target) {
            MainFrame.this.openHeap(source, target);
        }

        @Override
        public boolean selectIfOpen(java.awt.Component tab) {
            if (tabs.indexOfComponent(tab) < 0) {
                return false;
            }
            tabs.setSelectedComponent(tab);
            return true;
        }

        @Override
        public boolean trustHost(String message) {
            return MainFrame.this.trustHost(message);
        }

        @Override
        public Path knownHosts() {
            return knownHostsFile();
        }
    };

    private LogTab logOf(JvmBar bar) {
        for (var e : jvmBars.entrySet()) {
            if (e.getValue() == bar) {
                return e.getKey();
            }
        }
        return null;
    }

    JvmBar jvmBarForTest(LogTab t) {
        return jvmBars.get(t);
    }

    JvmDialog linkDialogForTest() {
        return linkDialog;
    }

    /** Starts looking for the JVM that writes a log ({@code sftp}: the log's server, for one read over SSH). */
    private void startJvmBar(LogTab tab, logsift.core.RemoteMirror.Target sftp) {
        if (Boolean.getBoolean("logsift.noJvmBar")) {
            return;
        }
        Path p = tab.path();
        String tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString();
        if (sftp == null && (tab.sourceLabels() != null || isOwnCopy(p) || tab.origin() != null
                || logsift.core.Archives.locate(p) != null || logsift.core.Archives.isGzip(p))) {
            return; // a merged log, a copy of Logsift's own, or a file from an archive (no JVM writes it)
        }
        JvmBar bar = new JvmBar(jvmBarHost, tab.title(), sftp == null ? p : null, tab.document().path(),
                tab.document().charset(), sftp);
        jvmBars.put(tab, bar);
        tab.setJvmBar(bar);
        bar.start();
    }

    /** The log each Threads tab belongs to (dumps in it, or of its JVM). */
    private final java.util.Map<ThreadsTab, LogTab> threadLogs = new java.util.IdentityHashMap<>();

    /** Lets a Threads tab show its threads' lines in the log they write. */
    private void linkThreadsToLog(ThreadsTab threads, LogTab log) {
        threadLogs.put(threads, log);
        threads.setLog(log.title(), name -> {
            if (tabs.indexOfComponent(log) < 0) {
                JOptionPane.showMessageDialog(this, log.title() + " is closed.", "Threads",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            tabs.setSelectedComponent(log);
            log.showThreadLines(name);
        });
    }

    private void linkCurrentLog() {
        LogTab t = currentTab();
        JvmBar bar = t == null ? null : jvmBars.get(t);
        if (bar == null) {
            JOptionPane.showMessageDialog(this, "Open the log first, then link it to the JVM that writes it.",
                    "Link to a JVM", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        jvmBarHost.linkDialog(bar, bar.suggestion());
    }

    /** Shows a thread a log line names in a dump of the log's JVM (taking one if needed). */
    @Override
    public void showThread(LogTab log, String name) {
        for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
            if (tabs.getComponentAt(i) instanceof ThreadsTab th && threadLogs.get(th) == log
                    && ThreadsTab.threadNamedIn(th, name)) {
                tabs.setSelectedIndex(i);
                th.selectThreadNamed(name);
                return;
            }
        }
        JvmBar bar = jvmBars.get(log);
        if (bar != null && bar.hasJvm()) {
            bar.showThreads(th -> {
                if (!th.selectThreadNamed(name)) {
                    status.setText("No thread named " + name + " in this dump.");
                }
            });
            return;
        }
        JOptionPane.showMessageDialog(this, "<html>There is no thread dump with <b>" + name.replace("<", "&lt;")
                + "</b> for this log yet.<br>Link the log to its JVM (JVM menu, Link This Log to a JVM…),"
                + " or analyze the thread dumps in it.</html>", "Threads", JOptionPane.INFORMATION_MESSAGE);
    }

    JvmDialog openJvmDialogForTest() {
        openJvmDialog();
        return jvmDialog;
    }

    /** Opens a Profile tab for a running JVM. */
    ProfileTab openProfile(String source, logsift.jvm.Profiler.Target target) {
        ProfileTab tab = new ProfileTab(source, target, null);
        addProfileTab(tab);
        return tab;
    }

    /** Test hook: what to answer instead of the SSH question (null: ask). */
    java.util.function.Function<logsift.jvm.JmxJvms.DumpOnServer, logsift.core.RemoteMirror.Target> sshForDumpForTest;

    /**
     * A heap dump the JVM wrote on its own machine (asked over its JMX port): offers to copy it
     * here over SSH and opens it; else says where it is. It is never removed there.
     */
    void copyDumpFromServer(logsift.jvm.JmxJvms.DumpOnServer there, Path local) {
        logsift.core.RemoteMirror.Target login = sshForDumpForTest != null ? sshForDumpForTest.apply(there)
                : askSshForDump(there);
        if (login == null) {
            javax.swing.JTextField where = new javax.swing.JTextField(there.path());
            where.setEditable(false);
            JOptionPane.showMessageDialog(this, new Object[] {"The heap dump stays on " + there.host() + ":", where,
                    "Copy it here yourself and open it (JVM → Open Heap Dump…). Remove it there when you are done."},
                    "Heap Dump", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Path knownHosts = Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        ProgressDialog.run(this, "Copying the heap dump from " + there.host() + "…", (progress, cancelled) -> {
            try (logsift.jvm.SshJvms ssh = logsift.jvm.SshJvms.connect(login, knownHosts, this::trustHost)) {
                try {
                    ssh.download(logsift.jvm.JmxJvms.sftpPath(there.path()), local, progress::accept);
                } catch (IOException e) {
                    Files.deleteIfExists(local);
                    throw e;
                }
            }
            return local;
        }, this::openHeapDump, e -> JOptionPane.showMessageDialog(this, "Could not copy the heap dump: "
                + e.getMessage() + "\nIt stays on " + there.host() + " at " + there.path(), "Heap Dump",
                JOptionPane.WARNING_MESSAGE), () -> { });
    }

    /** Asks how to sign in to the JVM's machine over SSH; null: leave the dump there. */
    private logsift.core.RemoteMirror.Target askSshForDump(logsift.jvm.JmxJvms.DumpOnServer there) {
        java.util.prefs.Preferences p = PREFS;
        javax.swing.JTextField host = new javax.swing.JTextField(there.host(), 20);
        javax.swing.JTextField port = new javax.swing.JTextField("22", 4);
        javax.swing.JTextField user = new javax.swing.JTextField(p.get("remote.user", System.getProperty("user.name")), 12);
        javax.swing.JRadioButton usePw = new javax.swing.JRadioButton("Password");
        javax.swing.JRadioButton useKey = new javax.swing.JRadioButton("Private key");
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        g.add(usePw);
        g.add(useKey);
        javax.swing.JPasswordField pw = new javax.swing.JPasswordField(16);
        Path defKey = java.util.stream.Stream.of("id_ed25519", "id_ecdsa", "id_rsa")
                .map(n -> Path.of(System.getProperty("user.home"), ".ssh", n)).filter(Files::isRegularFile)
                .findFirst().orElse(null);
        javax.swing.JTextField key = new javax.swing.JTextField(p.get("remote.key", defKey == null ? "" : defKey.toString()), 24);
        javax.swing.JPasswordField phrase = new javax.swing.JPasswordField(12);
        phrase.putClientProperty("JTextField.placeholderText", "key passphrase, if any");
        (key.getText().isEmpty() ? usePw : useKey).setSelected(true);
        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(3, 4, 3, 4);
        c.anchor = java.awt.GridBagConstraints.WEST;
        Object[][] rows = {{"Host", host}, {"Port", port}, {"User", user}, {usePw, pw}, {useKey, key}, {"", phrase}};
        for (int r = 0; r < rows.length; r++) {
            c.gridy = r;
            c.gridx = 0;
            form.add(rows[r][0] instanceof String s0 ? new JLabel(s0) : (java.awt.Component) rows[r][0], c);
            c.gridx = 1;
            form.add((java.awt.Component) rows[r][1], c);
        }
        javax.swing.JTextField where = new javax.swing.JTextField(there.path());
        where.setEditable(false);
        Object[] msg = {"<html>Over a JMX port, the JVM writes the heap dump on its own machine. It is saved there:</html>",
                where, "<html><br>Copy it here over SSH and open it? It stays there too: remove it when you are done.</html>", form};
        Object[] options = {"Copy Here", "Leave It There"};
        int answer = JOptionPane.showOptionDialog(this, msg, "Heap Dump on " + there.host(), JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer != 0) {
            return null;
        }
        p.put("remote.user", user.getText().trim());
        if (useKey.isSelected()) {
            p.put("remote.key", key.getText().trim());
        }
        int portNo;
        try {
            portNo = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            portNo = 22;
        }
        return new logsift.core.RemoteMirror.Target(host.getText().trim(), portNo, user.getText().trim(), "",
                usePw.isSelected() ? new String(pw.getPassword()) : null,
                useKey.isSelected() && !key.getText().isBlank() ? Path.of(key.getText().trim()) : null,
                useKey.isSelected() ? new String(phrase.getPassword()) : null);
    }

    /** Opens a Heap tab for a running JVM. */
    HeapTab openHeap(String source, logsift.jvm.Profiler.Target target) {
        HeapTab tab = new HeapTab(source, target);
        tab.onDumpSaved(this::openHeapDump); // a dump just saved is opened to look at
        tab.onDumpOnServer(this::copyDumpFromServer);
        tabs.addTab(tab.title(), Icons.of("memory-stick"), tab);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        tab.onChanged(() -> {
            if (tabs.getSelectedComponent() == tab) {
                refreshStatus();
            }
        });
        updateEmptyState();
        return tab;
    }

    /** Opens a heap dump (.hprof) in a tab of its own; it is read in the background. */
    HeapDumpTab openHeapDump(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof HeapDumpTab h && abs.equals(h.file())) {
                tabs.setSelectedIndex(i);
                return h;
            }
        }
        HeapDumpTab tab = new HeapDumpTab(abs);
        tabs.addTab(tab.title(), Icons.of("memory-stick"), tab);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, abs.toString());
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        tab.onChanged(() -> {
            if (tabs.getSelectedComponent() == tab) {
                refreshStatus();
            }
        });
        tab.onCompare(() -> {
            Path other = chooseHeapDump("Compare with Another Heap Dump of the Same Program",
                    abs.getParent() == null ? null : abs.getParent().toString());
            if (other != null) {
                openHeapCompare(other, abs);
            }
        });
        tab.onShowThreads(text -> {
            List<logsift.jvm.ThreadDump> dumps = logsift.jvm.DumpParser.parse(text);
            if (!dumps.isEmpty()) {
                openThreads(abs.getFileName().toString(), dumps, null);
            }
        });
        if (abs.getParent() != null) {
            PREFS.put("lastDir", abs.getParent().toString());
        }
        AppState.addRecent(abs);
        updateEmptyState();
        return tab;
    }

    private void openHeapDumpDialog() {
        openHeapDumpDialog(PREFS.get("lastDir", null));
    }

    // ---------------------------------------------------------------- heap dumps on servers

    private RemoteDialog heapServerDialog;

    /** Chooses a heap dump on a server, to read it there. */
    private void openHeapDumpOnServerDialog() {
        if (heapServerDialog != null && heapServerDialog.isDisplayable()) {
            heapServerDialog.toFront();
            return;
        }
        Path knownHosts = Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        heapServerDialog = new RemoteDialog(this, knownHosts, this::trustHost,
                c -> openHeapDumpOnServer(c.target(), null), true);
        heapServerDialog.setVisible(true);
    }

    RemoteDialog openHeapDumpOnServerDialogForTest() {
        openHeapDumpOnServerDialog();
        return heapServerDialog;
    }

    /**
     * Reads a heap dump on its server: looks at what it takes, says so, and starts it when the
     * person agrees ({@code workFolder}: where the index goes there, null for the usual place).
     */
    void openHeapDumpOnServer(logsift.core.RemoteMirror.Target target, String workFolder) {
        Path knownHosts = Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        ProgressDialog.run(this, "Looking at the dump on " + target.host() + "…", (progress, cancelled) -> {
            logsift.jvm.SshJvms ssh = logsift.jvm.SshJvms.connect(target, knownHosts, this::trustHost);
            try {
                return new Object[] {ssh, logsift.jvm.RemoteHeaps.plan(ssh, target.path(), workFolder)};
            } catch (IOException | RuntimeException e) {
                ssh.close();
                throw e;
            }
        }, found -> {
            logsift.jvm.SshJvms ssh = (logsift.jvm.SshJvms) found[0];
            logsift.jvm.RemoteHeaps.Plan plan = (logsift.jvm.RemoteHeaps.Plan) found[1];
            if (plan.running() == null) {
                int choice = heapServerConfirm.applyAsInt(plan);
                if (choice == 1) { // another folder
                    ssh.close();
                    String folder = JOptionPane.showInputDialog(this, "A folder on " + plan.host()
                            + " for the index (on a disk with room):", workFolder == null ? "/tmp" : workFolder);
                    if (folder != null && !folder.isBlank()) {
                        openHeapDumpOnServer(target, folder.trim());
                    }
                    return;
                }
                if (choice == 3) {
                    copyHeapDumpDialog(target, ssh, plan);
                    return;
                }
                if (choice != 0) {
                    ssh.close();
                    return;
                }
            }
            openHeapDumpTab(new RemoteHeapSource(ssh, plan), target.label());
        });
    }

    /**
     * Asks before reading a dump on a server: 0 read it there, 1 another folder, 3 copy it to another
     * machine, else cancel (tests replace it).
     */
    java.util.function.ToIntFunction<logsift.jvm.RemoteHeaps.Plan> heapServerConfirm = this::confirmHeapServer;

    private int confirmHeapServer(logsift.jvm.RemoteHeaps.Plan plan) {
        JOptionPane pane = heapServerPane(plan);
        pane.createDialog(this, "Heap Dump on " + plan.host()).setVisible(true);
        Object v = pane.getValue();
        Object[] options = pane.getOptions();
        int r = -1;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(v)) {
                r = i;
            }
        }
        // 0 read it there, 1 another folder, 3 copy elsewhere
        if (plan.problem() != null) {
            return r == 0 ? 3 : r == 1 ? 1 : 2;
        }
        return r == 1 ? 3 : r == 2 ? 1 : r;
    }

    /** What reading a dump on a server takes, as a question. */
    JOptionPane heapServerPane(logsift.jvm.RemoteHeaps.Plan plan) {
        String name = plan.dump().substring(plan.dump().lastIndexOf('/') + 1);
        String problem = plan.problem();
        StringBuilder sb = new StringBuilder("<html><div style='width:460px'>");
        if (problem != null) {
            sb.append("<b>").append(esc(name)).append(" can't be read on ").append(esc(plan.host()))
                    .append(".</b><br><br>").append(esc(problem));
        } else {
            long gb = Math.max(1, plan.dumpBytes() >> 30);
            long minutes = Math.max(1, Math.round(gb * 0.25));
            sb.append("<b>Read ").append(esc(name)).append(" (").append(logsift.heap.HeapFindings.bytes(plan.dumpBytes()))
                    .append(") on ").append(esc(plan.host())).append("?</b><br><br>")
                    .append("Logsift's heap reader runs there at low priority, with Java ").append(plan.javaVersion())
                    .append(" (").append(esc(plan.java())).append("). On that machine it takes:<ul>")
                    .append("<li><b>Disk:</b> up to ").append(logsift.heap.HeapFindings.bytes(plan.diskNeeded()))
                    .append(" for its index, in ").append(esc(plan.workDir()));
            if (plan.freeBytes() >= 0) {
                sb.append(" (").append(logsift.heap.HeapFindings.bytes(plan.freeBytes())).append(" free)");
            }
            sb.append("</li><li><b>Memory:</b> 2 GB, plus the disk cache for its index");
            if (plan.memoryBytes() >= 0) {
                sb.append(" (").append(logsift.heap.HeapFindings.bytes(plan.memoryBytes())).append(" available");
                if (plan.memoryBytes() < 3L << 30) {
                    sb.append(": <b>little to spare</b>");
                }
                sb.append(")");
            }
            sb.append("</li><li><b>Time:</b> about ").append(minutes).append(minutes == 1 ? " minute" : " minutes")
                    .append(" of one CPU core (more on a busy machine)</li>")
                    .append("<li><b>Disk reads:</b> the whole dump, once (and the wasted-memory report after)</li></ul>")
                    .append("It keeps going if you disconnect, and ends when you close the tab or ")
                    .append("after ").append(logsift.jvm.RemoteHeaps.IDLE_MINUTES)
                    .append(" minutes with no one connected. Its index is temporary files that go when it ends;")
                    .append(" nothing else is removed there.<br><br>")
                    .append("<span style='color:#858b94'>On a production server, <b>Copy to Another Machine</b> reads")
                    .append(" it once, at low CPU and disk priority, and does the rest there. The network isn't")
                    .append(" limited: ").append(logsift.heap.HeapFindings.bytes(plan.dumpBytes()))
                    .append(" go from this server to the other one.</span>");
        }
        sb.append("</div></html>");
        Object[] options = problem != null ? new Object[] {"Copy to Another Machine…", "Use Another Folder…", "Cancel"}
                : new Object[] {"Read It There", "Copy to Another Machine…", "Use Another Folder…", "Cancel"};
        return new JOptionPane(sb.toString(), problem != null ? JOptionPane.WARNING_MESSAGE
                : JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[0]);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Chooses where on another machine to copy a dump, then copies it there and reads it there. */
    private void copyHeapDumpDialog(logsift.core.RemoteMirror.Target from, logsift.jvm.SshJvms fromSsh,
                                    logsift.jvm.RemoteHeaps.Plan plan) {
        Path knownHosts = Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        RemoteDialog d = new RemoteDialog(this, knownHosts, this::trustHost,
                c -> copyHeapDump(from, fromSsh, plan, c.target()), RemoteDialog.Mode.FOLDER,
                "Copy the Heap Dump to Another Machine");
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (!copying) {
                    fromSsh.close(); // closed without choosing
                }
            }
        });
        d.setVisible(true);
    }

    private volatile boolean copying;

    /** A copy was stopped: what was copied so far stays on the server (Logsift removes nothing there). */
    private void partLeft(logsift.core.RemoteMirror.Target to, String dest) {
        javax.swing.JTextField where = new javax.swing.JTextField(dest + ".part");
        where.setEditable(false);
        JOptionPane.showMessageDialog(this, new Object[] {"The copy was stopped. What was copied so far stays on "
                + to.host() + ":", where, "Logsift doesn't remove files on a server: remove it there when you want."},
                "Copy Stopped", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Copies a dump to {@code to} (a folder there): directly when that machine can sign in to the
     * first by itself, else (when the person agrees) through this computer; then reads it there.
     */
    void copyHeapDump(logsift.core.RemoteMirror.Target from, logsift.jvm.SshJvms fromSsh,
                      logsift.jvm.RemoteHeaps.Plan plan, logsift.core.RemoteMirror.Target to) {
        copying = true;
        Path knownHosts = Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        String name = plan.dump().substring(plan.dump().lastIndexOf('/') + 1);
        String dest = to.path().replaceAll("/+$", "") + "/" + name;
        ProgressDialog.run(this, "Copying " + name + " to " + to.host() + "…", (progress, cancelled) -> {
            logsift.jvm.SshJvms toSsh = logsift.jvm.SshJvms.connect(to, knownHosts, this::trustHost);
            try {
                long free = logsift.jvm.RemoteCopy.free(toSsh, to.path());
                long need = plan.dumpBytes() + logsift.heap.HeapDump.diskNeeded(plan.dumpBytes()) + (1L << 30);
                if (free >= 0 && free < need) {
                    throw new IOException("The copy and its index need up to " + logsift.heap.HeapFindings.bytes(need)
                            + " in " + to.path() + " on " + to.host() + ", and there is only "
                            + logsift.heap.HeapFindings.bytes(free) + " free.");
                }
                try {
                    logsift.jvm.RemoteCopy.direct(toSsh, from, plan.dump(), dest, plan.dumpBytes(), progress,
                            cancelled);
                    return Boolean.TRUE;
                } catch (logsift.jvm.RemoteCopy.NoDirectWay e) {
                    return e.getMessage();
                }
            } finally {
                toSsh.close();
            }
        }, result -> {
            if (result instanceof String why) {
                long mb = Math.max(1, plan.dumpBytes() >> 20);
                Object[] options = {"Copy Through This Computer", "Cancel"};
                int r = JOptionPane.showOptionDialog(this, "<html><div style='width:440px'><b>" + esc(to.host())
                                + " can't sign in to " + esc(plan.host()) + " by itself.</b><br><br>" + esc(why)
                                + "<br><br>Logsift can copy it through this computer instead: " + String.format("%,d", mb)
                                + " MB go through your network connection, so it takes longer. (Or give "
                                + esc(to.host()) + " a key it can use for " + esc(plan.host()) + ", and try again.)"
                                + "</div></html>", "Copy the Heap Dump", JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                if (r != 0) {
                    copying = false;
                    fromSsh.close();
                    return;
                }
                ProgressDialog.run(this, "Copying " + name + " through this computer…", (progress, cancelled) -> {
                    try (logsift.jvm.SshJvms toSsh = logsift.jvm.SshJvms.connect(to, knownHosts, this::trustHost)) {
                        logsift.jvm.RemoteCopy.throughHere(fromSsh, toSsh, plan.dump(), dest, plan.dumpBytes(),
                                progress, cancelled);
                    }
                    return Boolean.TRUE;
                }, ok -> copied(fromSsh, to, dest), e -> {
                    copying = false;
                    fromSsh.close();
                    JOptionPane.showMessageDialog(this, "Copying failed: " + e.getMessage(), "Logsift",
                            JOptionPane.ERROR_MESSAGE);
                }, () -> {
                    copying = false;
                    fromSsh.close();
                    partLeft(to, dest);
                });
                return;
            }
            copied(fromSsh, to, dest);
        }, e -> {
            copying = false;
            fromSsh.close();
            JOptionPane.showMessageDialog(this, "Copying failed: " + e.getMessage(), "Logsift",
                    JOptionPane.ERROR_MESSAGE);
        }, () -> {
            copying = false;
            fromSsh.close();
            partLeft(to, dest);
        });
    }

    /** The copy is there: read it there. */
    private void copied(logsift.jvm.SshJvms fromSsh, logsift.core.RemoteMirror.Target to, String dest) {
        copying = false;
        fromSsh.close();
        status.setText("Copied to " + to.host() + ":" + dest + " (it stays there: remove it when you are done)");
        openHeapDumpOnServer(new logsift.core.RemoteMirror.Target(to.host(), to.port(), to.user(), dest,
                to.password(), to.privateKey(), to.passphrase()), null);
    }

    /** A dump read on a server: started (or found running) there, and stopped when the tab closes. */
    private static final class RemoteHeapSource implements HeapDumpTab.Source {
        private final logsift.jvm.SshJvms ssh;
        private final logsift.jvm.RemoteHeaps.Plan plan;

        RemoteHeapSource(logsift.jvm.SshJvms ssh, logsift.jvm.RemoteHeaps.Plan plan) {
            this.ssh = ssh;
            this.plan = plan;
        }

        @Override
        public String name() {
            return plan.dump().substring(plan.dump().lastIndexOf('/') + 1) + " on " + plan.host();
        }

        @Override
        public Path file() {
            return null;
        }

        @Override
        public logsift.heap.HeapModel open(logsift.heap.HeapDump.Listener progress,
                                           java.util.function.BooleanSupplier cancel) throws Exception {
            progress.progress(plan.running() != null ? "Connecting to the reader on " + plan.host()
                    : "Starting the reader on " + plan.host(), 0);
            logsift.heap.RemoteHeap r = logsift.jvm.RemoteHeaps.open(ssh, plan, cancel);
            try {
                r.awaitReady((stage, f) -> progress.progress(stage + " on " + plan.host(), f), cancel);
            } catch (java.util.concurrent.CancellationException e) {
                r.stopServer(); // not wanted after all: nothing left running there
                r.close();
                ssh.close();
                throw e;
            } catch (IOException e) {
                r.close();
                ssh.close();
                throw e;
            }
            return r;
        }

        @Override
        public void closed(logsift.heap.HeapModel m) {
            Thread t = new Thread(() -> {
                if (m instanceof logsift.heap.RemoteHeap r) {
                    r.stopServer();
                }
                m.close();
                ssh.close();
            }, "logsift-heap-server-stop");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Adds a heap dump tab (for a dump on this computer or on a server). */
    private HeapDumpTab openHeapDumpTab(HeapDumpTab.Source source, String tooltip) {
        HeapDumpTab tab = new HeapDumpTab(source);
        tabs.addTab(tab.title(), Icons.of("memory-stick"), tab);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, tooltip);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        tab.onChanged(() -> {
            if (tabs.getSelectedComponent() == tab) {
                refreshStatus();
            }
        });
        tab.onShowThreads(text -> {
            List<logsift.jvm.ThreadDump> dumps = logsift.jvm.DumpParser.parse(text);
            if (!dumps.isEmpty()) {
                openThreads(source.name(), dumps, null);
            }
        });
        updateEmptyState();
        return tab;
    }

    /** Asks for a .hprof file; null if none was chosen. */
    private Path chooseHeapDump(String title, String folder) {
        FileDialog fd = new FileDialog(this, title, FileDialog.LOAD);
        if (folder != null) {
            fd.setDirectory(folder);
        }
        fd.setVisible(true);
        return fd.getFile() == null ? null : Path.of(fd.getDirectory(), fd.getFile());
    }

    /** Asks for two heap dumps and compares them. */
    private void compareHeapDumpsDialog() {
        Path first = chooseHeapDump("Compare Heap Dumps: the Earlier Dump", PREFS.get("lastDir", null));
        if (first == null) {
            return;
        }
        Path second = chooseHeapDump("Compare Heap Dumps: the Later Dump", first.getParent().toString());
        if (second != null) {
            openHeapCompare(first, second);
        }
    }

    /** Compares two heap dumps of the same program (put in order by when they were taken). */
    HeapCompareTab openHeapCompare(Path before, Path after) {
        HeapCompareTab tab = new HeapCompareTab(before, after);
        tabs.addTab(tab.title(), Icons.of("git-compare"), tab);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, before + " → " + after);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        tab.onChanged(() -> {
            int i = tabs.indexOfComponent(tab);
            if (i >= 0) {
                tabs.setTitleAt(i, tab.title()); // in order once read
            }
            if (tabs.getSelectedComponent() == tab) {
                refreshStatus();
            }
        });
        updateEmptyState();
        return tab;
    }

    HeapCompareTab currentHeapCompareTab() {
        return tabs.getSelectedComponent() instanceof HeapCompareTab t ? t : null;
    }

    private void openHeapDumpDialog(String folder) {
        FileDialog fd = new FileDialog(this, "Open Heap Dump (.hprof)", FileDialog.LOAD);
        if (folder != null) {
            fd.setDirectory(folder);
        }
        fd.setVisible(true);
        if (fd.getFile() != null) {
            openHeapDump(Path.of(fd.getDirectory(), fd.getFile()));
        }
    }

    /** A CSV tab's Compare button. */
    @Override
    public void compareCsv(LogTab tab) {
        compareWith(tab);
    }

    /** Opens the heap dump a log says the JVM wrote ("Dumping heap to …"), copied over SSH for a remote log. */
    @Override
    public void openHeapDumpOf(LogTab tab, long line) {
        logsift.core.RemoteMirror.Target ssh = tab.sshTarget();
        ProgressDialog.run(this, "Looking for the heap dump…", (progress, cancelled) -> {
            String said = tab.heapDumpMentioned(line, cancelled);
            if (said == null) {
                return new String[] {null, null};
            }
            if (ssh == null) {
                Path dir = tab.path().toAbsolutePath().getParent();
                for (String c : logsift.core.HeapDumpMentions.candidates(said,
                        dir == null ? null : dir.toString().replace('\\', '/'))) {
                    try {
                        if (Files.isRegularFile(Path.of(c))) {
                            return new String[] {said, c};
                        }
                    } catch (java.nio.file.InvalidPathException e) {
                        // a path from another system
                    }
                }
                return new String[] {said, null};
            }
            String dir = ssh.path().contains("/") ? ssh.path().substring(0, ssh.path().lastIndexOf('/')) : ".";
            Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
            try (logsift.jvm.SshJvms con = logsift.jvm.SshJvms.connect(ssh,
                    Boolean.getBoolean("logsift.noKnownHosts") ? null : knownHosts, this::trustHost)) {
                // only find it here: copying a heap-sized file off a server is asked first
                for (String c : logsift.core.HeapDumpMentions.candidates(said, dir)) {
                    long size = con.fileSize(c);
                    if (size >= 0) {
                        return new String[] {said, null, c, String.valueOf(size)};
                    }
                }
            }
            return new String[] {said, null};
        }, found -> {
            if (found[1] != null) {
                openHeapDump(Path.of(found[1]));
                return;
            }
            if (found.length > 2) {
                long size = Long.parseLong(found[3]);
                int choice = remoteDumpConfirm.applyAsInt(new Object[] {ssh, found[2], size});
                if (choice == 0) {
                    copyRemoteDump(ssh, found[2]);
                } else if (choice == 1) {
                    openHeapDumpOnServer(new logsift.core.RemoteMirror.Target(ssh.host(), ssh.port(), ssh.user(),
                            found[2], ssh.password(), ssh.privateKey(), ssh.passphrase()), null);
                }
                return;
            }
            Path logDir = tab.path().toAbsolutePath().getParent();
            String msg = found[0] == null
                    ? "<html><b>The log doesn't say where a heap dump was written.</b><br><br>"
                            + "A JVM started with -XX:+HeapDumpOnOutOfMemoryError writes java_pid&lt;pid&gt;.hprof in its"
                            + " working folder,<br>or where -XX:HeapDumpPath says, and prints \"Dumping heap to …\""
                            + " on its console.</html>"
                    : "<html><b>The log says the heap dump went to</b><br>" + found[0].replace("&", "&amp;")
                            .replace("<", "&lt;") + "<br><br>but it isn't there" + (ssh == null ? " on this computer"
                            : " on " + ssh.host()) + " (or next to the log). It may have been moved or deleted.</html>";
            Object[] options = ssh == null ? new Object[] {"Choose the File…", "Close"} : new Object[] {"Close"};
            int r = JOptionPane.showOptionDialog(this, msg, "Heap Dump", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
            if (ssh == null && r == 0) {
                openHeapDumpDialog(logDir == null ? null : logDir.toString());
            }
        });
    }

    /** Asks before copying a heap dump off a server: 0 copy here, 1 read it there, else neither. Tests replace it. */
    java.util.function.ToIntFunction<Object[]> remoteDumpConfirm = a -> {
        logsift.core.RemoteMirror.Target ssh = (logsift.core.RemoteMirror.Target) a[0];
        Object[] options = {"Copy Here", "Read It There…", "Cancel"};
        return JOptionPane.showOptionDialog(this, "<html><div style='width:460px'><b>Copy " + esc((String) a[1]) + " ("
                        + humanBytes((Long) a[2]) + ") from " + esc(ssh.host()) + "?</b><br><br>It is read from that"
                        + " server's disk and sent over the network at full speed: on a busy production server that"
                        + " takes disk and network from the service. Copy it at a quiet time, or read it on the server"
                        + " (or another machine) instead.</div></html>", "Heap Dump on " + ssh.host(),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
    };

    /** Copies a heap dump off a server and opens it. */
    private void copyRemoteDump(logsift.core.RemoteMirror.Target ssh, String path) {
        Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        ProgressDialog.run(this, "Copying the heap dump from " + ssh.host() + "…", (progress, cancelled) -> {
            try (logsift.jvm.SshJvms con = logsift.jvm.SshJvms.connect(ssh,
                    Boolean.getBoolean("logsift.noKnownHosts") ? null : knownHosts, this::trustHost)) {
                Path local = Files.createTempDirectory("logsift-heap-").resolve(logsift.core.HeapDumpMentions.fileName(path));
                try {
                    con.download(path, local, progress, cancelled);
                } catch (IOException | java.util.concurrent.CancellationException e) {
                    Files.deleteIfExists(local); // the partial copy on this computer
                    Files.deleteIfExists(local.getParent());
                    throw e;
                }
                local.toFile().deleteOnExit();
                local.getParent().toFile().deleteOnExit();
                return local;
            }
        }, this::openHeapDump);
    }

    HeapDumpTab currentHeapDumpTab() {
        return tabs.getSelectedComponent() instanceof HeapDumpTab t ? t : null;
    }

    HeapTab currentHeapTab() {
        return tabs.getSelectedComponent() instanceof HeapTab t ? t : null;
    }

    private void addProfileTab(ProfileTab tab) {
        tabs.addTab(tab.title(), Icons.of("flame"), tab);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        tab.onChanged(() -> {
            if (tabs.getSelectedComponent() == tab) {
                refreshStatus();
            }
        });
        updateEmptyState();
    }

    private void openProfileDialog() {
        FileDialog fd = new FileDialog(this, "Open Profile (.jfr or collapsed stacks)", FileDialog.LOAD);
        String last = PREFS.get("lastDir", null);
        if (last != null) {
            fd.setDirectory(last);
        }
        fd.setVisible(true);
        if (fd.getFile() != null) {
            openProfileFile(Path.of(fd.getDirectory(), fd.getFile()));
        }
    }

    /** Shows a saved profile: a Flight Recorder file or async-profiler's collapsed stacks. */
    void openProfileFile(Path file) {
        ProgressDialog.run(this, "Reading " + file.getFileName() + "…",
                (progress, cancelled) -> logsift.jvm.Profiler.open(file),
                p -> {
                    PREFS.put("lastDir", file.toAbsolutePath().getParent().toString());
                    addProfileTab(new ProfileTab(file.getFileName().toString(), null, p));
                });
    }

    /** The Profile tab shown, or null. */
    ProfileTab currentProfileTab() {
        return tabs.getSelectedComponent() instanceof ProfileTab t ? t : null;
    }

    /** The Threads tab shown, or null. */
    ThreadsTab currentThreadsTab() {
        return tabs.getSelectedComponent() instanceof ThreadsTab t ? t : null;
    }

    JTabbedPane tabsForTest() {
        return tabs;
    }

    void closeCurrentTabForTest() {
        closeTab(tabs.getSelectedIndex());
    }

    private void openThreadDumpDialog() {
        FileDialog fd = new FileDialog(this, "Open Thread Dump", FileDialog.LOAD);
        String last = PREFS.get("lastDir", null);
        if (last != null) {
            fd.setDirectory(last);
        }
        fd.setVisible(true);
        if (fd.getFile() != null) {
            openThreadDump(Path.of(fd.getDirectory(), fd.getFile()));
        }
    }

    /** Reads the thread dumps in a file (jstack, jcmd, kill -3 output, JSON) into a Threads tab. */
    public void openThreadDump(Path file) {
        ProgressDialog.run(this, "Reading " + file.getFileName() + "…", (progress, cancelled) -> {
            try (LogDocument doc = LogDocument.open(file)) {
                doc.awaitIdle(600_000);
                return logsift.jvm.DumpParser.scan(doc, progress, cancelled);
            }
        }, dumps -> {
            if (dumps.isEmpty()) {
                JOptionPane.showMessageDialog(this, "There is no Java thread dump in " + file.getFileName() + ".",
                        "Thread Dumps", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            PREFS.put("lastDir", file.toAbsolutePath().getParent().toString());
            openThreads(file.getFileName().toString(), dumps, null);
        });
    }

    @Override
    public void analyzeThreads(LogTab t) {
        LogDocument doc = t.document();
        ProgressDialog.run(this, "Looking for thread dumps in " + t.title() + "…",
                (progress, cancelled) -> logsift.jvm.DumpParser.scan(doc, progress, cancelled),
                dumps -> {
                    if (dumps.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "There is no Java thread dump in " + t.title() + ".",
                                "Thread Dumps", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        linkThreadsToLog(openThreads(t.title(), dumps, null), t);
                    }
                });
    }

    /** Reads the GC lines of a log (once it is indexed) and explains them in a tab next to it. */
    @Override
    public void analyzeGc(LogTab t) {
        LogDocument doc = t.document();
        ProgressDialog.run(this, "Reading the GC log " + t.title() + "…", (progress, cancelled) -> {
            while (doc.isIndexing()) {
                if (cancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException();
                }
                Thread.sleep(50);
            }
            logsift.jvm.GcLog log = logsift.jvm.GcLog.parse(logsift.jvm.GcLog.of(doc), progress::accept,
                    cancelled::getAsBoolean);
            return logsift.jvm.GcReport.of(log);
        }, report -> {
            if (report.log().events().isEmpty()) {
                JOptionPane.showMessageDialog(this, "There are no garbage collections in " + t.title()
                        + " that Logsift can read.", "GC Log", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            openGcTab(t, report);
        });
    }

    GcTab openGcTab(LogTab t, logsift.jvm.GcReport report) {
        GcTab tab = new GcTab(t.title(), report, line -> {
            if (tabs.indexOfComponent(t) >= 0) {
                tabs.setSelectedComponent(t);
                t.showLine(line);
                t.mainView().focusView();
            }
        });
        int at = tabs.indexOfComponent(t) + 1;
        tabs.insertTab(tab.title(), Icons.of("gauge"), tab, t.path().toString(), at);
        tabs.setSelectedComponent(tab);
        updateEmptyState();
        return tab;
    }

    GcTab currentGcTabForTest() {
        return tabs.getSelectedComponent() instanceof GcTab g ? g : null;
    }

    /** Asks for a GC log, opens it and explains it. */
    private void openGcLogDialog() {
        FileDialog fd = new FileDialog(this, "Open GC Log", FileDialog.LOAD);
        String folder = PREFS.get("lastDir", null);
        if (folder != null) {
            fd.setDirectory(folder);
        }
        fd.setVisible(true);
        if (fd.getFile() != null) {
            openGcLog(Path.of(fd.getDirectory(), fd.getFile()));
        }
    }

    /** Opens a file as a log and explains its GC lines. */
    void openGcLog(Path file) {
        openFile(file, null, this::analyzeGc);
    }

    @Override
    public void showDiff(CompareTab ct, logsift.core.LogCompare.Diff d) {
        boolean inB = d.countB() > 0;
        LogTab t = inB ? ct.b : ct.a;
        if (tabs.indexOfComponent(t) < 0) {
            return; // that log was closed
        }
        tabs.setSelectedComponent(t);
        findAll(new SearchQuery(d.regex(), true, true, false));
        t.showLine(inB ? d.firstB() : d.firstA());
        t.mainView().focusView();
    }

    volatile String mergeExplanationForTest;

    /** What merging these files does, for the merge dialog (reads their first lines). */
    static String mergeExplanation(List<Path> files) {
        String logs = "<html>Merge these logs into one, in time order.<br>"
                + "Each line gets a label like &lt;node1&gt; showing where it came from.</html>";
        try {
            if (files.size() >= 2 && logsift.core.CsvMerger.allCsv(files)) {
                List<String> labels = logsift.core.LogMerger.labels(files);
                List<logsift.core.LogMerger.Source> sources = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    sources.add(new logsift.core.LogMerger.Source(files.get(i), labels.get(i)));
                }
                logsift.core.CsvMerger.Plan plan = logsift.core.CsvMerger.plan(sources);
                return "<html>These are CSV files: they become one CSV with " + plan.columns().size()
                        + " columns (matched by name) and a <b>" + plan.sourceColumn() + "</b> column with each row's file.<br>"
                        + (plan.order() == logsift.core.CsvMerger.Order.BY_TIME
                                ? "Every file has times: rows go in time order."
                                : "Rows go one file after the other, in this order ("
                                        + String.join(", ", plan.untimed()) + (plan.untimed().size() == 1 ? " has" : " have")
                                        + " no times).")
                        + "</html>";
            }
            if (logsift.core.CsvMerger.someCsv(files)) {
                return "<html>Some of these are CSV files and some are logs: they are merged as logs, in time order,"
                        + "<br>each line labelled like &lt;node1&gt;, so the CSV files won't show as columns."
                        + "<br>Merge the CSV files on their own to get one CSV.</html>";
            }
        } catch (IOException | RuntimeException e) {
            logsift.core.Problems.unexpected(e); // a bug, if not a read failure
            // not readable now: the merge will say why
        }
        return logs;
    }

    /** Merges logs by timestamp into a new tab (in the background, with progress). */
    public void mergeFiles(List<Path> files) {
        List<String> labels = logsift.core.LogMerger.labels(files);
        List<logsift.core.LogMerger.Source> sources = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            sources.add(new logsift.core.LogMerger.Source(files.get(i), labels.get(i)));
        }
        javax.swing.JDialog dlg = new javax.swing.JDialog(this, "Merging…", false);
        JProgressBar bar = new JProgressBar(0, 1000);
        JButton cancel = new JButton("Cancel");
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        cancel.addActionListener(e -> cancelled.set(true));
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(new JLabel("Merging " + files.size() + " files…"), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        p.add(cancel, BorderLayout.EAST);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        Thread t = new Thread(() -> {
            try {
                if (logsift.core.CsvMerger.allCsv(files)) {
                    mergeCsv(files, sources, dlg, bar, cancelled);
                    return;
                }
                Path dir = Files.createTempDirectory("logsift-merge-");
                dir.toFile().deleteOnExit();
                Path out = dir.resolve("merged-" + files.size() + "-logs.log");
                out.toFile().deleteOnExit();
                logsift.core.LogMerger m = logsift.core.LogMerger.merge(sources, out,
                        f -> SwingUtilities.invokeLater(() -> bar.setValue((int) (f * 1000))),
                        cancelled::get);
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    if (cancelled.get()) {
                        m.close();
                        return;
                    }
                    openFile(out, java.nio.charset.StandardCharsets.UTF_8, tab -> {
                        tab.setSourceLabels(labels);
                        tab.addCloseHook(m::close);
                        int i = tabs.indexOfComponent(tab);
                        tabs.setTitleAt(i, "Merged (" + files.size() + " logs)");
                        tabs.setToolTipTextAt(i, tab.sourceLegend());
                        m.follow(500);
                    });
                });
            } catch (IOException | RuntimeException e) {
                logsift.core.Problems.unexpected(e); // a bug, if not a read failure
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    JOptionPane.showMessageDialog(this, "Cannot merge: " + e.getMessage(), "Merge",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "logsift-merge-start");
        t.setDaemon(true);
        t.start();
    }

    /** CSV files merged into one CSV (on the merge's thread); it opens as a CSV tab. */
    private void mergeCsv(List<Path> files, List<logsift.core.LogMerger.Source> sources, javax.swing.JDialog dlg,
                          JProgressBar bar, java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
        Path dir = Files.createTempDirectory("logsift-merge-");
        dir.toFile().deleteOnExit();
        Path out = dir.resolve("merged-" + files.size() + "-files.csv");
        out.toFile().deleteOnExit();
        logsift.core.CsvMerger m = logsift.core.CsvMerger.merge(sources, out,
                f -> SwingUtilities.invokeLater(() -> bar.setValue((int) (f * 1000))), cancelled::get);
        SwingUtilities.invokeLater(() -> {
            dlg.dispose();
            if (cancelled.get()) {
                m.close();
                return;
            }
            openFile(out, java.nio.charset.StandardCharsets.UTF_8, tab -> {
                tab.addCloseHook(m::close);
                int i = tabs.indexOfComponent(tab);
                tabs.setTitleAt(i, "Merged (" + files.size() + " CSV files)");
                StringBuilder tip = new StringBuilder("<html>");
                for (logsift.core.LogMerger.Source s : sources) {
                    tip.append(s.label()).append(": ").append(s.path()).append("<br>");
                }
                tabs.setToolTipTextAt(i, tip.append(m.plan().order() == logsift.core.CsvMerger.Order.BY_TIME
                        ? "rows in time order" : "rows one file after the other").append("</html>").toString());
                m.follow(500);
            });
        });
    }

    /** Opens a new Folder Search tab (folder null = the current file's folder or the last one). */
    public FolderSearchTab openFolderSearch(Path folder) {
        LogTab cur = currentTab();
        return openFolderSearch(folder, cur == null || cur.findBar().text().isEmpty() ? null : cur.findBar().text());
    }

    @Override
    public void searchInFolder(String text) {
        openFolderSearch(null, text.isEmpty() ? null : text);
    }

    /** Opens a new Folder Search tab with this text to find (or none). */
    FolderSearchTab openFolderSearch(Path folder, String text) {
        if (folder == null && tabs.getSelectedComponent() instanceof ArchiveTab a) {
            folder = a.zip(); // a zip's tab: search that zip
        } else if (folder == null && currentTab() != null) {
            // a file in a zip: search that zip
            logsift.core.Archives.Located in = logsift.core.Archives.locate(currentTab().path());
            folder = in != null ? in.zip() : currentTab().path().getParent();
        }
        FolderSearchTab tab = new FolderSearchTab(this, folder, text);
        if (folder != null && logsift.core.Archives.isZip(folder) && Files.isRegularFile(folder)) {
            tab.setFileNames(""); // a zip: all its files, whatever they are called
        }
        tab.resultsView().setFontSize(fontSize);
        tabs.addTab(tab.title(), tab);
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        updateEmptyState();
        SwingUtilities.invokeLater(tab::focusFind);
        return tab;
    }

    @Override
    public void alert(LogTab tab, String message, long line) {
        int i = tabs.indexOfComponent(tab);
        if (i >= 0 && tabs.getSelectedComponent() != tab) {
            Integer n = (Integer) tab.getClientProperty("alerts");
            int count = (n == null ? 0 : n) + 1;
            tab.putClientProperty("alerts", count);
            tabs.setTitleAt(i, "(" + count + ") " + tab.title());
        }
        Notifier.notify(this, tab.title(), message, () -> {
            toFront();
            tabs.setSelectedComponent(tab);
            tab.showLine(line);
            tab.mainView().focusView();
        });
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

    @Override
    public void openHit(logsift.core.FolderSearch.FileResult f, long line, SearchQuery q) {
        // a file in a zip opens by its name in the zip, like any log (unpacked once)
        openAt(f.entry() == null ? f.file() : logsift.core.Archives.pathOf(f.file(), f.entry()), line, q);
    }

    // ---------------------------------------------------------------- zip, gz and URLs

    /** Whether a file is one of Logsift's own temporary copies (a download, a merge). */
    private static boolean isOwnCopy(Path p) {
        String tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString();
        String s = p.toAbsolutePath().normalize().toString();
        return s.startsWith(tmp) && s.contains("logsift-");
    }

    /**
     * Opens a zip as a tab with its files (zips inside it too). A zip with one file opens that
     * file right away.
     *
     * @param then gets the tab, when one file was opened right away
     */
    void openArchive(Path zip, java.util.function.Consumer<LogTab> then) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof ArchiveTab at && at.zip().equals(zip)) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
        status.setText("Reading " + zip.getFileName() + "…");
        new javax.swing.SwingWorker<List<logsift.core.Archives.Entry>, Void>() {
            @Override
            protected List<logsift.core.Archives.Entry> doInBackground() throws Exception {
                return logsift.core.Archives.list(zip, () -> false);
            }

            @Override
            protected void done() {
                List<logsift.core.Archives.Entry> entries;
                try {
                    entries = get();
                } catch (Exception e) {
                    logsift.core.Problems.unexpected(e); // a bug, if not a read failure
                    status.setText(" ");
                    showOpenError(zip, e.getCause() != null ? e.getCause() : e);
                    return;
                }
                status.setText(" ");
                if (!isOwnCopy(zip)) {
                    AppState.addRecent(zip);
                    if (zip.getParent() != null) {
                        PREFS.put("lastDir", zip.getParent().toString());
                    }
                }
                List<logsift.core.Archives.Entry> files = entries.stream()
                        .filter(e -> !logsift.core.Archives.isZip(e.fileName())).toList();
                if (files.size() == 1) {
                    openFile(logsift.core.Archives.pathOf(zip, files.get(0).name()), null, then);
                    return;
                }
                ArchiveTab tab = new ArchiveTab(zip, archiveHost);
                tab.setEntries(entries);
                tabs.addTab(tab.title(), Icons.of("folder-archive"), tab);
                int i = tabs.getTabCount() - 1;
                tabs.setToolTipTextAt(i, zip.toString());
                tabs.setSelectedIndex(i);
                updateEmptyState();
                SwingUtilities.invokeLater(tab::focusTree);
            }
        }.execute();
    }

    /** Asks for a folder and shows its files in a tab. */
    void openFolderDialog() {
        Path dir = FolderSearchTab.chooseFolder(this, PREFS.get("lastDir", null));
        if (dir != null) {
            openFolder(dir);
        }
    }

    /**
     * A folder's files as a tab (as a zip's are): a tree with sizes, a name filter, Open, Merge by
     * Time and Find in all files. The files are listed in the background, at most
     * {@link logsift.core.FolderListing#MAX_FILES}.
     */
    void openFolder(Path folder) {
        Path dir = folder.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof ArchiveTab at && at.isFolder() && at.zip().equals(dir)) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
        ArchiveTab tab = new ArchiveTab(dir, true, folderHost);
        tabs.addTab(tab.title(), Icons.of("folder-open"), tab);
        int i = tabs.getTabCount() - 1;
        tabs.setToolTipTextAt(i, dir.toString());
        tabs.setSelectedIndex(i);
        updateEmptyState();
        AppState.addRecent(dir);
        PREFS.put("lastDir", dir.toString());
        listFolder(tab);
    }

    /** Lists a folder tab's files (again), in the background. */
    private void listFolder(ArchiveTab tab) {
        Path dir = tab.zip();
        status.setText("Reading " + dir + "…");
        new javax.swing.SwingWorker<logsift.core.FolderListing.Result, Void>() {
            @Override
            protected logsift.core.FolderListing.Result doInBackground() throws Exception {
                return logsift.core.FolderListing.list(dir, logsift.core.FolderListing.MAX_FILES,
                        () -> !tab.isOpen()); // closed meanwhile: stop (read off the window's thread)
            }

            @Override
            protected void done() {
                status.setText(" ");
                try {
                    logsift.core.FolderListing.Result r = get();
                    tab.setEntries(r.files(), r.capped());
                    SwingUtilities.invokeLater(tab::focusTree);
                } catch (Exception e) {
                    logsift.core.Problems.unexpected(e); // a bug, if not a read failure
                    if (tabs.indexOfComponent(tab) >= 0) {
                        tabs.remove(tab);
                        updateEmptyState();
                        showOpenError(dir, e.getCause() != null ? e.getCause() : e);
                    }
                }
            }
        }.execute();
    }

    private final ArchiveTab.Host folderHost = new ArchiveTab.Host() {
        @Override
        public void openEntries(Path dir, List<logsift.core.Archives.Entry> entries) {
            for (logsift.core.Archives.Entry e : entries) {
                openFile(dir.resolve(e.name()));
            }
        }

        @Override
        public void mergeEntries(Path dir, List<logsift.core.Archives.Entry> entries) {
            // zips hold several files: not merged; .gz files are unpacked first (once)
            List<logsift.core.Archives.Entry> files = entries.stream()
                    .filter(e -> !logsift.core.Archives.isZip(e.fileName())).toList();
            if (files.size() < 2) {
                return;
            }
            ProgressDialog.run(MainFrame.this, "Unpacking…", (progress, cancelled) -> {
                List<Path> paths = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    int at = i;
                    Path p = dir.resolve(files.get(i).name());
                    if (logsift.core.Archives.isGzip(p)) {
                        p = unpack(p, null, files.get(i).size(), f -> progress.accept((at + f) / files.size()),
                                cancelled);
                        if (p == null) {
                            return null;
                        }
                    }
                    paths.add(p);
                }
                return paths;
            }, paths -> {
                if (paths != null) {
                    mergeFiles(paths);
                }
            });
        }

        @Override
        public void searchArchive(Path dir, String text) {
            FolderSearchTab t = openFolderSearch(dir, text == null || text.isEmpty() ? null : text);
            t.setFileNames(""); // every file, as the box says
            if (text != null && !text.isEmpty()) {
                t.search(dir, "", new SearchQuery(text, false, false, false));
            }
        }

        @Override
        public void refresh(ArchiveTab tab) {
            listFolder(tab);
        }

        @Override
        public void compareEntries(Path dir, List<logsift.core.Archives.Entry> entries) {
            openCompare(dir.resolve(entries.get(0).name()), dir.resolve(entries.get(1).name()));
        }
    };

    private final ArchiveTab.Host archiveHost = new ArchiveTab.Host() {
        @Override
        public void openEntries(Path zip, List<logsift.core.Archives.Entry> entries) {
            for (logsift.core.Archives.Entry e : entries) {
                openFile(logsift.core.Archives.pathOf(zip, e.name()));
            }
        }

        @Override
        public void mergeEntries(Path zip, List<logsift.core.Archives.Entry> entries) {
            ProgressDialog.run(MainFrame.this, "Unpacking " + entries.size() + " files…", (progress, cancelled) -> {
                List<Path> copies = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    int at = i;
                    logsift.core.Archives.Entry e = entries.get(i);
                    Path c = unpack(zip, e.name(), e.size(), p -> progress.accept((at + p) / entries.size()),
                            cancelled);
                    if (c == null) {
                        return null;
                    }
                    copies.add(c);
                }
                return copies;
            }, copies -> {
                if (copies != null) {
                    mergeFiles(copies);
                }
            });
        }

        @Override
        public void searchArchive(Path zip, String text) {
            searchInArchive(zip, text);
        }

        @Override
        public void compareEntries(Path zip, List<logsift.core.Archives.Entry> entries) {
            openCompare(logsift.core.Archives.pathOf(zip, entries.get(0).name()),
                    logsift.core.Archives.pathOf(zip, entries.get(1).name()));
        }
    };

    String statusTextForTest() {
        return status.getText();
    }

    ArchiveTab currentArchiveTabForTest() {
        return tabs.getSelectedComponent() instanceof ArchiveTab a ? a : null;
    }

    FolderSearchTab searchInArchiveForTest(Path zip) {
        return searchInArchive(zip, "");
    }

    /** Searches every file in a zip (zips and .gz files in it too); with text, right away. */
    private FolderSearchTab searchInArchive(Path zip, String text) {
        FolderSearchTab t = openFolderSearch(zip, text == null || text.isEmpty() ? null : text);
        t.setFileNames("");
        if (text != null && !text.isEmpty()) {
            t.search(zip, "", new SearchQuery(text, false, false, false));
        }
        return t;
    }

    /** Unpacked copies of files in zips and .gz files, so opening one again is instant. */
    private final java.util.Map<String, Path> unpacked = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Unpacks a file of a zip (or, with {@code entry} null, a .gz file) to a temporary file, once
     * per session (again if the archive changed). Runs in the background.
     *
     * @return the copy, or null if cancelled
     */
    private Path unpack(Path archive, String entry, long size, java.util.function.DoubleConsumer progress,
                        java.util.function.BooleanSupplier cancelled) throws IOException {
        String key = archive + "!" + entry + "@" + Files.getLastModifiedTime(archive).toMillis();
        Path done = unpacked.get(key);
        if (done != null && Files.exists(done)) {
            return done;
        }
        String name = logsift.core.Archives.gunzippedName(entry != null
                ? logsift.core.Archives.baseName(entry) : archive.getFileName().toString());
        Path dir = Files.createTempDirectory("logsift-unpack-");
        dir.toFile().deleteOnExit();
        Path out = dir.resolve(name.isBlank() ? "entry.log" : name);
        if (!logsift.core.Archives.extract(archive, entry, size, out, progress, cancelled)) {
            Files.deleteIfExists(dir);
            return null;
        }
        out.toFile().deleteOnExit();
        unpacked.put(key, out);
        return out;
    }

    /**
     * Opens a file in a zip ({@code abs} names it as {@code x.zip/dir/file}) or a .gz file: it
     * is unpacked to a temporary copy, and the tab is named, remembered and reopened by
     * {@code abs}, like any file.
     */
    private void openPacked(Path abs, logsift.core.Archives.Located in, java.nio.charset.Charset forced,
                            java.util.function.Consumer<LogTab> then) {
        if (!opening.add(abs)) {
            return;
        }
        String name = logsift.core.Archives.gunzippedName(abs.getFileName().toString());
        ProgressDialog.run(this, "Unpacking " + name + "…", (progress, cancelled) -> {
            if (in != null && Files.isRegularFile(abs)) {
                return abs; // a real file in a folder that happens to be called x.zip
            }
            Path archive = in != null ? in.zip() : abs;
            if (!Files.isRegularFile(archive)) {
                throw new IOException("Not a file (or not reachable): " + archive);
            }
            return unpack(archive, in != null ? in.entry() : null, -1, progress, cancelled);
        }, copy -> {
            opening.remove(abs);
            if (copy != null) {
                openReal(copy, abs, forced, then);
            }
        }, e -> {
            opening.remove(abs);
            showOpenError(abs, e);
        }, () -> {
            opening.remove(abs);
            restore.finished(abs);
        });
    }

    private UrlDialog urlDialog;

    /** Asks for web addresses of logs to download and open (or search). */
    void openUrlDialog() {
        if (urlDialog != null && urlDialog.isDisplayable()) {
            urlDialog.toFront();
            return;
        }
        urlDialog = new UrlDialog(this, this::openUrls);
        urlDialog.setVisible(true);
    }

    UrlDialog urlDialogForTest() {
        openUrlDialog();
        return urlDialog;
    }

    /**
     * Downloads files from the web into a temporary folder. One opens in a tab (a .zip or .gz
     * is unpacked first); several are searched together.
     */
    public void openUrls(List<java.net.URI> urls, logsift.core.UrlFetch.Auth auth) {
        String what = urls.size() == 1 ? urls.get(0).getHost() : urls.size() + " files";
        String message = "Downloading from " + what + "…";
        ProgressDialog.run(this, message, (progress, cancelled) -> {
            Path dir = Files.createTempDirectory("logsift-url-");
            dir.toFile().deleteOnExit();
            List<Path> got = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                int at = i;
                Path f = logsift.core.UrlFetch.download(urls.get(i), auth, dir,
                        p -> progress.accept((at + p) / urls.size()), cancelled,
                        anyCertificate(urls.get(i).getHost()));
                if (f == null) {
                    return null;
                }
                f.toFile().deleteOnExit();
                got.add(f);
            }
            return got;
        }, got -> {
            if (got == null) {
                return;
            }
            if (got.size() == 1) {
                Path f = got.get(0);
                String url = urls.get(0).toString();
                if (logsift.core.Archives.isZip(f)) {
                    openArchive(f, null);
                } else {
                    openFile(f, null, tab -> {
                        int i = tabs.indexOfComponent(tab);
                        if (i >= 0) {
                            tabs.setToolTipTextAt(i, url);
                        }
                        tab.setOrigin(url);
                    });
                }
            } else {
                FolderSearchTab t = openFolderSearch(got.get(0).getParent());
                t.setFileNames("");
                status.setText("Downloaded " + got.size() + " files; type what to find in them");
            }
        }, e -> {
            if (e instanceof logsift.core.UrlFetch.UntrustedCertificate u && askAnyCertificate(u)) {
                openUrls(urls, auth); // again, without checking that server's certificate
                return;
            }
            JOptionPane.showMessageDialog(this, message + " failed: " + e.getMessage(), "Logsift",
                    JOptionPane.ERROR_MESSAGE);
        }, () -> { });
    }

    /** Servers whose certificates aren't checked, for this run (and the ones remembered). */
    private static final java.util.Set<String> ANY_CERTIFICATE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean anyCertificate(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return ANY_CERTIFICATE.contains(h)
                || java.util.Arrays.asList(PREFS.get("url.anyCertificate", "").split(",")).contains(h);
    }

    /** Asks whether to open a server's files without checking its certificate. */
    private boolean askAnyCertificate(logsift.core.UrlFetch.UntrustedCertificate u) {
        String host = u.host().toLowerCase(java.util.Locale.ROOT);
        Object[] options = {"Open Anyway", "Always for " + host, "Cancel"};
        int r = JOptionPane.showOptionDialog(this, "<html><div style='width:420px'><b>The certificate of " + host
                        + " isn't trusted.</b><br><br>Neither Java nor this computer trusts it. On a company network,"
                        + " a proxy that checks traffic often uses its own certificate. Open it anyway, without"
                        + " checking the certificate, only if you trust this network and server.</div></html>",
                "Untrusted Certificate", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
                options[2]);
        if (r == 0 || r == 1) {
            ANY_CERTIFICATE.add(host);
            if (r == 1) {
                String kept = PREFS.get("url.anyCertificate", "");
                PREFS.put("url.anyCertificate", kept.isEmpty() ? host : kept + "," + host);
            }
            return true;
        }
        return false;
    }

    /** Opens a file at a line and runs Find All for the query there. */
    @Override
    public void openAt(Path file, long line, SearchQuery q) {
        openFile(file, null, t -> {
            tabs.setSelectedComponent(t);
            findAll(q);
            t.showLineWhenAvailable(line);
            t.mainView().focusView();
        });
    }

    String reloadButtonTextForTest() {
        return currentTab() == null ? "" : currentTab().logBar().reloadText();
    }

    boolean isOpeningForTest(Path p) {
        return opening.contains(p.toAbsolutePath().normalize());
    }

    int currentTabIndexForTest() {
        return tabs.getSelectedIndex();
    }

    /** Closes the tab showing this file, if open. */
    void closeFile(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof LogTab lt && lt.path().equals(abs)) {
                closeTab(i);
                return;
            }
        }
    }

    /** Remembers open files (and each file's filters and position) for next time. */
    void saveSession() {
        List<Path> files = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof LogTab t && t.remembersState()) {
                AppState.saveFileState(t.path(), t.saveState());
                if (i == tabs.getSelectedIndex()) {
                    selected = files.size();
                }
                files.add(t.path());
            }
        }
        AppState.saveSession(files, selected);
    }

    /** Opens the files that were open last time (each with its filters and position). */
    public void restoreSession() {
        AppState.Session s = AppState.lastSession();
        List<Path> files = s.files().stream().map(f -> f.toAbsolutePath().normalize()).toList();
        // files open in the background and finish in any order: only the selected one selects
        restore.start(files, s.selected());
        for (Path p : files) {
            // no existence check here: on an unreachable share it could hang; opening handles it
            openFile(p, null, t -> restore.finished(p));
        }
    }

    /** Whether files of the last session are still being reopened. */
    boolean isRestoringSessionForTest() {
        return !restore.isDone();
    }

    public LogTab currentTab() {
        return tabs.getSelectedComponent() instanceof LogTab t ? t : null;
    }

    /** Runs Find All in the current tab: every match is listed below the log. */
    public void findAll(SearchQuery q) {
        LogTab t = currentTab();
        if (t == null || q.text().isEmpty()) {
            return;
        }
        try {
            t.findAll(q);
        } catch (PatternSyntaxException ex) {
            status.setText("Invalid regular expression: " + ex.getDescription());
        }
    }

    public void setFollow(boolean on) {
        LogTab t = currentTab();
        if (t != null) {
            t.mainView().setFollow(on);
        }
    }

    // ---------------------------------------------------------------- menus & status bar

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
        // "⚠ 2 errors": this session's unexpected errors, to see and report
        errorsButton.putClientProperty("JButton.buttonType", "borderless");
        errorsButton.setFont(errorsButton.getFont().deriveFont(Font.BOLD));
        errorsButton.setFocusable(false);
        errorsButton.setMargin(new java.awt.Insets(0, 4, 0, 4));
        errorsButton.setToolTipText("Logsift hit unexpected errors in this session: see them, and report them");
        errorsButton.addActionListener(e -> showErrors());
        right.add(errorsButton, BorderLayout.WEST);
        ErrorReporter.session().addListener(() -> SwingUtilities.invokeLater(this::updateErrorsButton));
        updateErrorsButton();
        updateButton.putClientProperty("JButton.buttonType", "borderless");
        updateButton.setFont(updateButton.getFont().deriveFont(Font.BOLD));
        updateButton.setForeground(new Color(0x2563EB));
        updateButton.setFocusable(false);
        updateButton.setMargin(new java.awt.Insets(0, 4, 0, 4));
        updateButton.setVisible(false);
        updateButton.addActionListener(e -> HelpSupport.browse(this, updateUrl));
        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.setOpaque(false);
        left.add(updateButton, BorderLayout.WEST);
        left.add(status, BorderLayout.CENTER);
        p.add(left, BorderLayout.CENTER);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private final JButton errorsButton = new JButton();

    /** Shows "⚠ 2 errors" while this session has errors (not cleared); hidden when there are none. */
    private void updateErrorsButton() {
        int n = ErrorReporter.session().count();
        errorsButton.setVisible(n > 0);
        errorsButton.setText("⚠ " + SessionErrors.countText(n));
        errorsButton.setForeground(Theme.isDark() ? new Color(0xF5C451) : new Color(0xB45309));
    }

    JButton errorsButtonForTest() {
        return errorsButton;
    }

    /** This session's errors in a window (Help → Show Errors…). */
    void showErrors() {
        ErrorsDialog.show(this, this::openErrorLog);
    }

    private JMenuBar buildMenus() {
        JMenuBar bar = new JMenuBar();
        int shift = InputEvent.SHIFT_DOWN_MASK;

        // ---- File: opening things, then making things from files, then leaving
        JMenu file = menu("File");
        file.add(item("Open…", "folder-open", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU), e -> openDialog()));
        file.add(item("Open Folder…", "folder", null, e -> openFolderDialog()));
        JMenu recent = submenu("Open Recent", "history");
        onOpen(recent, () -> {
            recent.removeAll();
            List<Path> files = AppState.recentFiles();
            if (files.isEmpty()) {
                JMenuItem none = new JMenuItem("No recent files");
                none.setEnabled(false);
                recent.add(none);
                return;
            }
            for (Path p : files) {
                JMenuItem mi = new JMenuItem(p.getFileName() + "   —   " + p.getParent());
                mi.addActionListener(a -> openFile(p));
                recent.add(mi);
            }
            recent.addSeparator();
            recent.add(item("Clear Recent Files", null, a -> AppState.clearRecent()));
        });
        file.add(recent);
        file.add(item("Open Remote (SFTP)…", "server", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU | shift),
                e -> openRemoteDialog()));
        file.add(item("Open URL…", "globe", KeyStroke.getKeyStroke(KeyEvent.VK_U, MENU | shift),
                e -> openUrlDialog()));
        if (WslSupport.available()) {
            file.add(item("Open from WSL…", "square-terminal", null, e -> openFromWsl()));
        }
        file.add(item("Reopen Last Session", "rotate-ccw", null, e -> restoreSession()));
        file.addSeparator();
        file.add(item("Search in Folder…", "folder-search", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU | shift),
                e -> openFolderSearch(null)));
        file.add(item("Merge Files by Time…", "git-merge", KeyStroke.getKeyStroke(KeyEvent.VK_M, MENU | shift),
                e -> mergeDialog()));
        file.add(item("Compare Files…", "git-compare", null, e -> compareFilesDialog()));
        file.add(item("Compare Logs by Message…", "git-compare", KeyStroke.getKeyStroke(KeyEvent.VK_D, MENU | shift),
                e -> compareDialog()));
        file.addSeparator();
        JMenu export = submenu("Export", "download");
        export.add(item("Lines Shown by Filters…", "filter", KeyStroke.getKeyStroke(KeyEvent.VK_E, MENU | shift),
                e -> exportLines("filtered")));
        export.add(item("Find Results…", "search", null, e -> exportLines("find")));
        export.add(item("Selected Lines…", "text-select", null, e -> exportLines("selection")));
        export.add(item("Everything in the View…", "file-text", null, e -> exportLines("view")));
        file.add(export);
        file.addSeparator();
        file.add(item("Close Tab", "x", KeyStroke.getKeyStroke(KeyEvent.VK_W, MENU), e -> closeTab(tabs.getSelectedIndex())));
        if (!isMac()) { // on macOS, Quit is in the application menu
            file.add(item("Exit", "log-out", KeyStroke.getKeyStroke(KeyEvent.VK_Q, MENU), e -> dispose()));
        }
        bar.add(file);

        // ---- Edit: copying, finding, settings
        JMenu edit = menu("Edit");
        edit.add(item("Copy", "copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, MENU), e -> {
            LogView v = LogView.lastFocused();
            if (v != null) {
                v.copySelection();
            }
        }));
        edit.add(item("Select All", "text-select", KeyStroke.getKeyStroke(KeyEvent.VK_A, MENU), e -> {
            LogView v = LogView.lastFocused();
            if (v != null) {
                v.selectAll();
            }
        }));
        edit.addSeparator();
        edit.add(item("Find…", "search", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU), e -> openFind()));
        edit.add(item("Find Next", "arrow-down", KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), e -> findNext(true)));
        edit.add(item("Find Previous", "arrow-up", KeyStroke.getKeyStroke(KeyEvent.VK_F3, shift), e -> findNext(false)));
        if (isMac()) {
            edit.add(item("Find Next (⌘G)", null, KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU), e -> findNext(true)));
            edit.add(item("Find Previous (⇧⌘G)", null, KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU | shift), e -> findNext(false)));
        }
        edit.add(item("Close Results", "panel-bottom-close", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, shift), e -> {
            LogTab t = currentTab();
            if (t != null) {
                t.closeResults();
            }
        }));
        if (!isMac()) { // on macOS, Settings is in the application menu
            edit.addSeparator();
            edit.add(item("Settings…", "settings", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, MENU), e -> showSettings(null)));
        }
        bar.add(edit);

        // ---- View: what is shown and how
        JMenu view = menu("View");
        view.add(check("Follow (Tail)", "arrow-down-to-line", KeyStroke.getKeyStroke(KeyEvent.VK_T, MENU),
                () -> currentTab() != null && currentTab().mainView().isFollow(),
                e -> setFollow(currentTab() == null || !currentTab().mainView().isFollow())));
        view.add(item("Reload", "refresh-cw", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), e -> reload()));
        view.add(check("Auto Reload", "refresh-ccw-dot", null, () -> viewState("autoReload", false),
                e -> setAutoReload(!viewState("autoReload", false))));
        view.addSeparator();
        view.add(check("Columns", "columns-3", KeyStroke.getKeyStroke(KeyEvent.VK_K, MENU | shift),
                () -> viewState("columns", false), e -> setColumns(!viewState("columns", false))));
        view.add(check("Word Wrap", "wrap-text", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.ALT_DOWN_MASK),
                () -> viewState("wrap", false), e -> setWrap(!viewState("wrap", false))));
        view.add(check("Matching Lines Only", "list-filter", KeyStroke.getKeyStroke(KeyEvent.VK_H, MENU | shift),
                () -> currentTab() != null && currentTab().isMatchingOnly(), e -> {
                    LogTab t = currentTab();
                    if (t != null) {
                        t.setMatchingOnly(!t.isMatchingOnly());
                    }
                }));
        view.addSeparator();
        view.add(check("Tree of Loggers", "list-tree", KeyStroke.getKeyStroke(KeyEvent.VK_B, MENU | shift),
                () -> viewState("valuesTree", true), e -> setValuesTree(!viewState("valuesTree", true))));
        view.add(check("Timeline", "chart-column", null,
                () -> viewState("timeline", true), e -> setTimeline(!viewState("timeline", true))));
        view.add(check("Bottom Panel", "panel-bottom", KeyStroke.getKeyStroke(KeyEvent.VK_J, MENU),
                () -> currentTab() != null && !currentTab().isBottomCollapsed(), e -> {
                    LogTab t = currentTab();
                    if (t != null) {
                        t.toggleBottom();
                    }
                }));
        view.add(check("Line Stripes", "rows-3", null,
                () -> PREFS.getBoolean("stripes", true), e -> setStripes(!PREFS.getBoolean("stripes", true))));
        view.addSeparator();
        JMenu format = submenu("Log Format", "braces");
        onOpen(format, () -> fillFormatMenu(format, currentTab()));
        fillFormatMenu(format, currentTab());
        view.add(format);
        JMenu encoding = submenu("Encoding", "languages");
        encoding.add(item("Detect Automatically", null, e -> reopenWithEncoding(null)));
        encoding.addSeparator();
        for (String cs : new String[] {"UTF-8", "windows-1252", "ISO-8859-1", "ISO-8859-15", "UTF-16LE",
            "UTF-16BE", "windows-1251", "Shift_JIS", "GB18030"}) {
            if (java.nio.charset.Charset.isSupported(cs)) {
                java.nio.charset.Charset c = java.nio.charset.Charset.forName(cs);
                encoding.add(item(logsift.core.Encoding.label(c), null, e -> reopenWithEncoding(c)));
            }
        }
        view.add(encoding);
        view.addSeparator();
        view.add(item("Bigger Font", "a-arrow-up", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, MENU), e -> changeFont(1)));
        view.add(item("Smaller Font", "a-arrow-down", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, MENU), e -> changeFont(-1)));
        view.add(check("Dark Mode", "moon", null, Theme::isDark, e -> toggleDark()));
        bar.add(view);

        // ---- Go: moving around the log
        JMenu go = menu("Go");
        go.add(item("Go to Line…", "hash", KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU), e -> goToLineDialog()));
        go.add(item("Go to Time…", "clock", KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU | shift),
                e -> goToTimeDialog()));
        go.addSeparator();
        go.add(item("Next Error", "circle-alert", KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), e -> nextError(true)));
        go.add(item("Previous Error", null, KeyStroke.getKeyStroke(KeyEvent.VK_F8, shift), e -> nextError(false)));
        go.add(item("Next Difference", "chevron-down", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0),
                e -> nextDifference(true)));
        go.add(item("Previous Difference", "chevron-up", KeyStroke.getKeyStroke(KeyEvent.VK_F7, shift),
                e -> nextDifference(false)));
        go.add(item("Follow This Line's Request", "route", KeyStroke.getKeyStroke(KeyEvent.VK_R, MENU | shift),
                e -> followRequest()));
        go.addSeparator();
        go.add(item("Toggle Bookmark", "bookmark", KeyStroke.getKeyStroke(KeyEvent.VK_F2, MENU), e -> {
            LogTab t = currentTab();
            if (t != null) {
                t.toggleBookmark();
            }
        }));
        go.add(item("Next Bookmark", "chevron-down", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), e -> nextBookmark(true)));
        go.add(item("Previous Bookmark", "chevron-up", KeyStroke.getKeyStroke(KeyEvent.VK_F2, shift),
                e -> nextBookmark(false)));
        bar.add(go);

        // ---- JVM: thread dumps (and running JVMs)
        JMenu jvm = menu("JVM");
        jvm.add(item("Attach to a Running JVM…", "cpu", KeyStroke.getKeyStroke(KeyEvent.VK_J, MENU | shift),
                e -> openJvmDialog()));
        jvm.add(item("Link This Log to a JVM…", "link", null, e -> linkCurrentLog()));
        jvm.addSeparator();
        jvm.add(item("Open Thread Dump…", "file-text", null, e -> openThreadDumpDialog()));
        jvm.add(item("Analyze Thread Dumps in This Log", "activity", null, e -> {
            LogTab t = currentTab();
            if (t == null) {
                JOptionPane.showMessageDialog(this, "Open a log with thread dumps in it first (for example the"
                        + " console log of an application that got kill -3).", "Thread Dumps",
                        JOptionPane.INFORMATION_MESSAGE);
            } else {
                analyzeThreads(t);
            }
        }));
        jvm.add(item("Open GC Log…", "gauge", null, e -> openGcLogDialog()));
        jvm.add(item("Analyze GC in This Log", "gauge", null, e -> {
            LogTab t = currentTab();
            if (t == null) {
                JOptionPane.showMessageDialog(this, "Open a GC log first (written with -Xlog:gc* or"
                        + " -XX:+PrintGCDetails), or use Open GC Log….", "GC Log", JOptionPane.INFORMATION_MESSAGE);
            } else {
                analyzeGc(t);
            }
        }));
        jvm.add(item("Open Profile…", "flame", null, e -> openProfileDialog()));
        jvm.add(item("Open Heap Dump…", "memory-stick", null, e -> openHeapDumpDialog()));
        jvm.add(item("Open Heap Dump on a Server…", "server", null, e -> openHeapDumpOnServerDialog()));
        jvm.add(item("Compare Two Heap Dumps…", "git-compare", null, e -> compareHeapDumpsDialog()));
        bar.add(jvm);

        // ---- Help
        JMenu help = menu("Help");
        help.add(item("Keyboard Shortcuts", "keyboard", KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, MENU),
                e -> HelpSupport.shortcutsDialog(this, getJMenuBar()).setVisible(true)));
        help.add(item("Documentation", "book-open", null, e -> HelpSupport.browse(this, HelpSupport.DOCS)));
        help.addSeparator();
        help.add(item("Report a Bug or Suggest a Feature…", "bug", null,
                e -> HelpSupport.browse(this, HelpSupport.bugReportUrl(null, null))));
        help.add(item("Show Errors…", "triangle-alert", null, e -> showErrors()));
        help.add(item("Open Error Log", "file-warning", null, e -> openErrorLog()));
        help.addSeparator();
        help.add(item("Check for Updates…", "circle-arrow-down", null, e -> checkForUpdates(true)));
        autoUpdates.setSelected(PREFS.getBoolean("updates.auto", true));
        autoUpdates.addActionListener(e -> PREFS.putBoolean("updates.auto", autoUpdates.isSelected()));
        help.add(autoUpdates);
        if (!isMac()) { // on macOS, About is in the application menu
            help.addSeparator();
            help.add(item("About Logsift", "info", null, e -> HelpSupport.showAbout(this)));
        }
        bar.add(help);
        return bar;
    }

    /** A top-level menu whose check marks are brought up to date each time it opens. */
    private JMenu menu(String name) {
        JMenu m = new JMenu(name);
        onOpen(m, () -> {
            for (java.awt.Component c : m.getMenuComponents()) {
                if (c instanceof JCheckBoxMenuItem cb
                        && cb.getClientProperty("state") instanceof java.util.function.BooleanSupplier st) {
                    cb.setSelected(st.getAsBoolean());
                }
            }
        });
        return m;
    }

    private static JMenu submenu(String name, String icon) {
        JMenu m = new JMenu(name);
        m.setIcon(Icons.of(icon));
        return m;
    }

    private static void onOpen(JMenu m, Runnable r) {
        m.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                r.run();
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {}

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
    }

    private static JMenuItem item(String name, String icon, KeyStroke key, java.util.function.Consumer<ActionEvent> a) {
        JMenuItem mi = item(name, key, a);
        if (icon != null) {
            mi.setIcon(Icons.of(icon));
        }
        return mi;
    }

    /** An on/off item: shows {@code state} with a check mark; choosing it runs {@code a}. */
    private static JCheckBoxMenuItem check(String name, String icon, KeyStroke key,
                                           java.util.function.BooleanSupplier state,
                                           java.util.function.Consumer<ActionEvent> a) {
        JCheckBoxMenuItem mi = new JCheckBoxMenuItem(action(name, e -> {
            a.accept(e);
            ((JCheckBoxMenuItem) e.getSource()).setSelected(state.getAsBoolean());
        }));
        mi.putClientProperty("state", state);
        if (icon != null) {
            mi.setIcon(Icons.of(icon));
        }
        if (key != null) {
            mi.setAccelerator(key);
        }
        mi.setSelected(state.getAsBoolean());
        return mi;
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

    /** The file dialog in a WSL distribution's home folder (Windows reaches its files directly). */
    private void openFromWsl() {
        List<String> distros = WslSupport.distributions();
        if (distros.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No WSL distribution found.", "Open from WSL",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String d = distros.get(0);
        if (distros.size() > 1) {
            Object pick = JOptionPane.showInputDialog(this, "Distribution:", "Open from WSL",
                    JOptionPane.QUESTION_MESSAGE, null, distros.toArray(), d);
            if (pick == null) {
                return;
            }
            d = pick.toString();
        }
        FileDialog fd = new FileDialog(this, "Open Log Files from " + d, FileDialog.LOAD);
        fd.setMultipleMode(true);
        fd.setDirectory(WslSupport.home(d).toString());
        fd.setVisible(true);
        for (File f : fd.getFiles()) {
            openFile(f.toPath());
        }
    }

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

    void closeTab(int index) {
        if (index < 0 || index >= tabs.getTabCount()) {
            return;
        }
        java.awt.Component c = tabs.getComponentAt(index);
        if (c instanceof LogTab t && t.remembersState()) {
            AppState.saveFileState(t.path(), t.saveState());
        }
        tabs.removeTabAt(index);
        if (c instanceof LogTab t) {
            JvmBar bar = jvmBars.remove(t);
            if (bar != null) {
                bar.close();
            }
            t.close();
        } else if (c instanceof FolderSearchTab f) {
            f.close();
        } else if (c instanceof CompareTab ct) {
            ct.close();
        } else if (c instanceof FileCompareTab fc) {
            fc.close();
        } else if (c instanceof ThreadsTab th) {
            threadLogs.remove(th);
            th.close();
        } else if (c instanceof ProfileTab pt) {
            pt.close();
        } else if (c instanceof HeapTab ht) {
            ht.close();
        } else if (c instanceof HeapDumpTab hd) {
            hd.close();
        } else if (c instanceof HeapCompareTab hc) {
            hc.close();
        }
        updateEmptyState();
    }

    /** Opens the current log's find bar (Ctrl+F); text selected in the log becomes the search. */
    void openFind() {
        if (tabs.getSelectedComponent() instanceof FolderSearchTab f) {
            f.focusFind();
            return;
        }
        if (tabs.getSelectedComponent() instanceof ArchiveTab a) {
            a.focusFind(); // a zip: find in all its files
            return;
        }
        LogTab t = currentTab();
        if (t != null) {
            LogView v = LogView.lastFocused();
            String sel = v != null && v.isViewFocused() ? v.selectionForFind() : null;
            t.openFindBar(sel);
        }
    }

    /** F3: the find bar's next match, or else the selected Find All's next hit. */
    private void findNext(boolean forward) {
        LogTab t = currentTab();
        if (t == null) {
            return;
        }
        FindBar fb = t.findBar();
        boolean ok;
        if (fb.isVisible() && fb.typedQuery() != null) {
            ok = fb.next(forward);
        } else if (t.currentFind() != null) {
            ok = t.findNext(forward);
        } else if (fb.typedQuery() != null) {
            t.openFindBar(null); // the last search again
            ok = true;
        } else {
            ok = false;
        }
        if (!ok) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private void nextBookmark(boolean forward) {
        LogTab t = currentTab();
        if (t != null && !t.nextBookmark(forward)) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    /** Follows the trace or request ID on the current line (or the selected text). */
    private void followRequest() {
        LogTab t = currentTab();
        if (t != null && !t.followCurrentRequest()) {
            JOptionPane.showMessageDialog(this,
                    "This line has no trace, request or other ID to follow.\n"
                            + "Select the text to follow (an order number, a user…) and try again.",
                    "Follow a Request", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void nextError(boolean forward) {
        LogTab t = currentTab();
        if (t != null && !t.nextError(forward)) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    /** Saves lines of the current log to a file: filtered, find results, selection or view. */
    void exportLines(String what) {
        LogTab t = currentTab();
        if (t == null) {
            return;
        }
        logsift.core.LineExporter.Runs runs;
        String suffix;
        switch (what) {
            case "filtered" -> {
                if (!t.isFiltering()) {
                    JOptionPane.showMessageDialog(this, "No filters are active: add a filter first,"
                            + " or use Export → Everything in the View.");
                    return;
                }
                runs = t.filteredRuns();
                suffix = "-filtered";
            }
            case "find" -> {
                FindGroup g = t.currentFind();
                if (g == null) {
                    JOptionPane.showMessageDialog(this, "Run Find All first.");
                    return;
                }
                runs = g.runs();
                suffix = "-found";
            }
            case "selection" -> {
                runs = t.selectionRuns();
                suffix = "-selection";
            }
            default -> {
                runs = t.visibleRuns();
                suffix = "-view";
            }
        }
        String name = t.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        FileDialog fd = new FileDialog(this, "Export Lines", FileDialog.SAVE);
        logsift.core.Archives.Located in = logsift.core.Archives.locate(t.path());
        Path dirOf = in != null ? in.zip().getParent() : t.path().getParent();
        if (dirOf != null && !isOwnCopy(dirOf)) {
            fd.setDirectory(dirOf.toString());
        }
        fd.setFile(base + suffix + ".log");
        fd.setVisible(true);
        if (fd.getFile() == null) {
            return;
        }
        Path out = Path.of(fd.getDirectory(), fd.getFile());
        if (out.toAbsolutePath().normalize().equals(t.path())
                || out.toAbsolutePath().normalize().equals(t.document().path())) {
            JOptionPane.showMessageDialog(this, "Choose a different file than the log itself.");
            return;
        }
        exportTo(t, runs, out, n -> status.setText(String.format("Exported %,d lines to %s", n, out)));
    }

    /** Exports in the background with progress; {@code done} gets the number of lines. */
    void exportTo(LogTab t, logsift.core.LineExporter.Runs runs, Path out,
                  java.util.function.LongConsumer done) {
        ProgressDialog.run(this, "Exporting lines…",
                (progress, cancelled) -> logsift.core.LineExporter.export(t.document(), runs, out,
                        progress, cancelled),
                n -> done.accept(n));
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
        if (ref == logsift.core.TimeFormat.NONE) {
            ref = t.timeIndex().samples().minTime();
        }
        String s = (String) JOptionPane.showInputDialog(this,
                "Time (10:05, 10:05:30.250 or 2026-10-21 10:05):", "Go to Time",
                JOptionPane.QUESTION_MESSAGE, null, null, TimelineBar.formatFull(ref).substring(11, 19));
        if (s == null) {
            return;
        }
        long time = TimelineBar.parseUserTime(s.trim(), ref);
        if (time == logsift.core.TimeFormat.NONE) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        t.goToTime(time);
    }

    private void changeFont(int delta) {
        setFont(Theme.fontFamily(), fontSize + delta);
    }

    // ---------------------------------------------------------------- settings

    private final javax.swing.JCheckBoxMenuItem autoUpdates =
            new javax.swing.JCheckBoxMenuItem("Check for Updates Automatically");

    /** Opens Settings (on the given tab, or General). */
    void showSettings(String tab) {
        LogTab t = currentTab();
        SettingsDialog d = settingsDialog(t);
        d.showTab(tab);
        d.setVisible(true);
    }

    /** Settings on the Log Formats tab, with a new format guessed from the current log. */
    void newFormatFromCurrentLog() {
        SettingsDialog d = settingsDialog(currentTab());
        d.showTab(SettingsDialog.FORMATS);
        d.formats().newFromSample();
        d.setVisible(true);
    }

    private SettingsDialog settingsDialog(LogTab t) {
        return new SettingsDialog(this, t == null ? List.of() : t.sampleLines(300),
                t == null || t.path().getFileName() == null ? null : t.path().getFileName().toString());
    }

    @Override
    public void defineFormat(LogTab tab) {
        tabs.setSelectedComponent(tab);
        newFormatFromCurrentLog();
    }

    /** The user's formats changed: every log works out its layout again. */
    void formatsChanged() {
        for (LogTab t : allTabs()) {
            t.redetect();
        }
    }

    @Override
    public void fillFormatMenu(LogTab tab, javax.swing.JPopupMenu menu) {
        fillFormatMenu(menu, tab);
    }

    /** The formats to read a log with ({@code t}: that log, or null). */
    private void fillFormatMenu(JComponent menu, LogTab t) {
        Runnable separator = () -> menu.add(new javax.swing.JPopupMenu.Separator());
        menu.removeAll();
        String choice = t == null ? null : t.formatChoice();
        logsift.core.LogFormat now = t == null ? null : t.logFormat();
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        java.util.function.BiConsumer<String, String> radio = (label, name) -> {
            javax.swing.JRadioButtonMenuItem mi = new javax.swing.JRadioButtonMenuItem(label,
                    java.util.Objects.equals(choice, name));
            mi.setEnabled(t != null);
            mi.addActionListener(e -> {
                if (t != null) {
                    t.setFormatChoice(name);
                }
            });
            group.add(mi);
            menu.add(mi);
        };
        radio.accept("Detect Automatically" + (choice == null && now != null ? " (" + now.name() + ")" : ""), null);
        radio.accept(logsift.core.Formats.PLAIN, logsift.core.Formats.PLAIN);
        List<logsift.core.CustomFormat> mine = AppState.customFormats();
        if (!mine.isEmpty()) {
            separator.run();
            for (logsift.core.CustomFormat f : mine) {
                radio.accept(f.name(), f.name());
            }
        }
        separator.run();
        for (logsift.core.LogFormat f : logsift.core.LogFormat.presets()) {
            radio.accept(f.name(), f.name());
        }
        radio.accept("JSON lines", "JSON lines");
        radio.accept("CSV (header row, columns by , ; tab or |)", logsift.core.LogFormat.CSV);
        separator.run();
        JMenuItem create = item("New Format from This Log…", null, e -> {
            if (t != null) {
                defineFormat(t);
            }
        });
        create.setEnabled(t != null);
        menu.add(create);
        menu.add(item("Edit Log Formats…", null, e -> showSettings(SettingsDialog.FORMATS)));
    }

    float fontSize() {
        return fontSize;
    }

    /** The font for logs (family null: automatic) and its size. */
    void setFont(String family, float size) {
        Theme.setFontFamily(family);
        if (family == null) {
            PREFS.remove("fontFamily");
        } else {
            PREFS.put("fontFamily", family);
        }
        fontSize = Math.max(8, Math.min(32, size));
        PREFS.putFloat("fontSize", fontSize);
        applyFontSize();
    }

    void setTabWidth(int width) {
        LogView.setTabWidth(width);
        PREFS.putInt("tabWidth", width);
        applyFontSize(); // lays the lines out again
    }

    /*
     * Wrap, columns, tree, timeline and auto reload belong to each log (its bar has them): these
     * change the current log, and the choice becomes the default for logs opened next.
     */

    /** This view setting of the current log (or the default for new logs, when none is open). */
    private boolean viewState(String pref, boolean def) {
        LogTab t = currentTab();
        if (t == null) {
            return PREFS.getBoolean(pref, def);
        }
        return switch (pref) {
            case "wrap" -> t.isWrap();
            case "columns" -> t.isColumnsWanted();
            case "valuesTree" -> t.isTreeWanted();
            case "timeline" -> t.isTimelineWanted();
            default -> t.isAutoReload();
        };
    }

    /** Shows the tree of loggers (threads, components...) beside the current log. */
    @Override
    public void setValuesTree(boolean on) {
        PREFS.putBoolean("valuesTree", on);
        LogTab t = currentTab();
        if (t != null) {
            t.setValuesTreeVisible(on);
        }
    }

    void setStripes(boolean on) {
        PREFS.putBoolean("stripes", on);
        for (LogTab t : allTabs()) {
            t.mainView().setStripes(on);
            t.resultsView().setStripes(on);
        }
    }

    void setTimeline(boolean on) {
        PREFS.putBoolean("timeline", on);
        LogTab t = currentTab();
        if (t != null) {
            t.setTimelineVisible(on);
        }
    }

    /** The timeline in every open log (from Settings), and for logs opened next. */
    void setTimelineEverywhere(boolean on) {
        PREFS.putBoolean("timeline", on);
        for (LogTab t : allTabs()) {
            t.setTimelineVisible(on);
        }
    }

    void setDark(boolean on) {
        if (on != Theme.isDark()) {
            toggleDark();
        }
    }

    void setPollMillis(int ms) {
        PREFS.putInt("pollMs", ms);
        for (LogTab t : allTabs()) {
            t.setPollMillis(ms);
        }
    }

    void setAutoUpdates(boolean on) {
        PREFS.putBoolean("updates.auto", on);
        autoUpdates.setSelected(on);
    }

    /** How text that is neither UTF-8 nor UTF-16 is read. */
    java.nio.charset.Charset fallbackEncoding() {
        try {
            return java.nio.charset.Charset.forName(PREFS.get("fallbackEncoding", "windows-1252"));
        } catch (IllegalArgumentException e) {
            return logsift.core.Encoding.WINDOWS_1252;
        }
    }

    private void applyFontSize() {
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

    /** Reopens the current log in another text encoding (null: detect it again). */
    void reopenWithEncoding(java.nio.charset.Charset cs) {
        LogTab t = currentTab();
        if (t == null || t.sourceLabels() != null) {
            return;
        }
        Path p = t.path();
        int i = tabs.indexOfComponent(t);
        closeTab(i);
        openFile(p, cs);
    }

    /** Loads changes on disk as they happen (otherwise Reload does it), in the current log. */
    public void setAutoReload(boolean on) {
        PREFS.putBoolean("autoReload", on);
        LogTab t = currentTab();
        if (t != null) {
            t.setAutoReload(on);
        }
        refreshStatus();
    }

    /** Loads what changed in the current log's file. */
    public void reload() {
        LogTab t = currentTab();
        if (t != null) {
            t.reload();
        }
    }

    /** Lights up the current log's Reload button when its file changed and isn't loaded yet. */
    private void updateReloadButton(LogTab t) {
        if (t != null) {
            t.updateReloadButton();
        }
    }

    /** Shows the current log's lines as columns (time, level, thread, ...). */
    public void setColumns(boolean on) {
        PREFS.putBoolean("columns", on);
        LogTab t = currentTab();
        if (t != null) {
            t.setColumnsVisible(on);
        }
    }

    /** Wraps long lines in the current log. */
    public void setWrap(boolean on) {
        PREFS.putBoolean("wrap", on);
        LogTab t = currentTab();
        if (t != null) {
            t.setWrap(on);
        }
    }

    void toggleDark() {
        boolean dark = !Theme.isDark();
        PREFS.putBoolean("dark", dark);
        Theme.apply(dark);
    }

    @Override
    public void openSource(LogTab tab, logsift.core.Exceptions.Frame frame) {
        SourceOpener.open(this, frame);
    }

    @Override
    public List<LogTab> openLogs() {
        return allTabs();
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
        boolean empty = tabs.getTabCount() == 0;
        if (empty) {
            startPage.refresh();
        }
        ((CardLayout) center.getLayout()).show(center, empty ? "empty" : "tabs");
        refreshStatus();
    }

    @Override
    public void tabStatusChanged(LogTab tab) {
        if (tab != null && tab == currentTab()) {
            refreshStatus();
        }
    }

    private void refreshStatus() {
        LogTab t = currentTab();
        updateReloadButton(t);
        if (tabs.getSelectedComponent() instanceof GcTab gt) {
            setTitle(gt.title() + " — " + logsift.core.Channel.appName());
            status.setText(gt.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof HeapDumpTab hd) {
            setTitle(hd.title() + " — " + logsift.core.Channel.appName());
            status.setText(hd.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof HeapCompareTab hc) {
            setTitle(hc.title() + " — " + logsift.core.Channel.appName());
            status.setText(hc.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof HeapTab ht) {
            setTitle(ht.title() + " — " + logsift.core.Channel.appName());
            status.setText(ht.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof ProfileTab pt) {
            setTitle(pt.title() + " — " + logsift.core.Channel.appName());
            status.setText(pt.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof ThreadsTab th) {
            setTitle(th.title() + " — " + logsift.core.Channel.appName());
            status.setText(th.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof FileCompareTab cc) {
            setTitle(cc.title() + " — " + logsift.core.Channel.appName());
            status.setText(cc.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof CompareTab ct) {
            setTitle(ct.title() + " — " + logsift.core.Channel.appName());
            status.setText(" ");
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof FolderSearchTab f) {
            setTitle(f.title() + " — " + logsift.core.Channel.appName());
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
            setTitle(logsift.core.Channel.appName());
            return;
        }
        LogDocument d = t.document();
        setTitle(d.path().getFileName() + " — " + logsift.core.Channel.appName());
        StringBuilder sb = new StringBuilder();
        long size = d.fileSize();
        if (!t.path().equals(d.path())) {
            try {
                size = Files.size(t.path()); // the original, not the converted copy
            } catch (IOException e) {
                // keep the copy's size
            }
        }
        sb.append(t.remoteLabel() != null ? "sftp " + t.remoteLabel()
                        : t.origin() != null ? t.origin() : t.path().toString())
          .append("   ").append(humanBytes(size))
          .append("   ").append(String.format("%,d lines", d.lineCount()));
        if (d.isIndexing()) {
            sb.append("   indexing…");
        } else if (t.indexMillis() >= 0) {
            sb.append(String.format("   indexed in %,d ms", t.indexMillis()));
        }
        if (t.error() != null) {
            sb.append("   ⚠ ").append(t.error());
        }
        if (t.document().pendingChange() != LogDocument.Change.NONE) {
            sb.append(t.document().pendingChange() == LogDocument.Change.GREW
                    ? "   • " + humanBytes(t.document().pendingBytes()) + " more on disk: Reload (F5) to see it"
                    : "   • the file was replaced on disk: Reload (F5)");
        }
        if (t.remoteStatus() != null && !t.remoteStatus().equals("connected")) {
            sb.append("   ⚡ ").append(t.remoteStatus());
        }
        status.setText(sb.toString());

        long cur = t.currentLine();
        String pos = cur >= 0 ? String.format("Ln %,d", cur + 1) : "";
        long time = t.currentLineTime();
        if (time != logsift.core.TimeFormat.NONE) {
            pos += "   " + TimelineBar.formatFull(time);
        }
        if (t.isMatchingOnly()) {
            pos = "MATCHING ONLY   " + pos;
        }
        String follow = t.mainView().isFollow() ? "   FOLLOW" : "";
        statusRight.setText(pos + follow + "   " + t.encodingLabel() + "   " + memory());

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

    /** What to do with two files dropped on the window: "ask", "compare" or "tabs" (Settings). */
    static String twoFilesDropped() {
        String v = AppState.prefs().get("dropTwoFiles", "ask");
        return v.equals("compare") || v.equals("tabs") ? v : "ask";
    }

    /** Asks what to do with two files dropped (tests answer instead): "compare", "tabs", or null to cancel. */
    java.util.function.BiFunction<Path, Path, String> askTwoFiles = this::askTwoFiles;

    /** Two files dropped on the window: compare them, or open each in its own tab (asked, unless chosen in Settings). */
    void droppedTwo(Path a, Path b) {
        String what = twoFilesDropped();
        if (what.equals("ask")) {
            what = askTwoFiles.apply(a, b);
        }
        if ("compare".equals(what)) {
            openCompare(a, b);
        } else if ("tabs".equals(what)) {
            openFile(a);
            openFile(b);
        }
    }

    private String askTwoFiles(Path a, Path b) {
        javax.swing.JCheckBox remember = new javax.swing.JCheckBox("Don't ask again (Settings → General changes it)");
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.add(new JLabel("<html><b>Two files dropped</b><br><br>" + esc(a.getFileName().toString()) + "<br>"
                + esc(b.getFileName().toString()) + "<br><br>Compare them, or open each in its own tab?</html>"),
                BorderLayout.CENTER);
        p.add(remember, BorderLayout.SOUTH);
        Object[] options = {"Compare", "Open in Two Tabs", "Cancel"};
        int got = JOptionPane.showOptionDialog(this, p, "Two Files", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, Icons.of("git-compare", Theme.uiColor("Component.accentColor",
                        new java.awt.Color(0x2563EB)), 32), options, options[0]);
        String what = got == 0 ? "compare" : got == 1 ? "tabs" : null;
        if (what != null && remember.isSelected()) {
            AppState.prefs().put("dropTwoFiles", what);
        }
        return what;
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
                if (files.size() == 2 && files.get(0).isFile() && files.get(1).isFile()) {
                    droppedTwo(files.get(0).toPath(), files.get(1).toPath());
                    return true;
                }
                for (File f : files) {
                    if (f.isDirectory()) {
                        openFolder(f.toPath()); // a dropped folder: its files, in a tab
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
