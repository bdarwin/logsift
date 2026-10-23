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
import javax.swing.JToolBar;
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
    private final JTextField findField = new JTextField(28);
    private final JToggleButton matchCase = toggle("Aa", "Match case");
    private final JToggleButton wholeWord = toggle("W", "Whole word");
    private final JToggleButton regex = toggle(".*", "Regular expression");
    private final JToggleButton followButton = new JToggleButton("Follow");
    private final JToggleButton wrapButton = new JToggleButton("Wrap");
    private final JToggleButton columnsButton = new JToggleButton("Columns");
    private final JToggleButton treeButton = new JToggleButton("Tree");
    private final JToggleButton timelineButton = new JToggleButton("Timeline");
    private boolean reloadStale;
    /** Reload: an amber pill while the file on disk has changes that aren't loaded. */
    private final JButton reloadButton = new JButton("Reload") {
        @Override
        protected void paintComponent(java.awt.Graphics g0) {
            if (reloadStale) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.isDark() ? new Color(0x6B5310) : new Color(0xFFE08A));
                g.fillRoundRect(1, 2, getWidth() - 2, getHeight() - 4, 10, 10);
                g.dispose();
            }
            super.paintComponent(g0);
        }
    };
    private final JToggleButton autoReloadButton = new JToggleButton("Auto reload");
    private boolean autoReload = PREFS.getBoolean("autoReload", false);
    private boolean columns = PREFS.getBoolean("columns", false);
    private boolean wrap = PREFS.getBoolean("wrap", false);
    private final JLabel status = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final javax.swing.JButton formatButton = new javax.swing.JButton();
    private javax.swing.JPopupMenu formatPopup;
    private final JButton updateButton = new JButton();
    private String updateUrl = HelpSupport.REPO + "/releases/latest";
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private float fontSize = PREFS.getFloat("fontSize", 13f);

    /** Ends the program when the window is closed (set by the app, not by tests). */
    static volatile boolean exitOnClose;

    public MainFrame() {
        super("Logsift");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(logsift.tools.IconGenerator.windowIcons());
        setSize(PREFS.getInt("width", 1280), PREFS.getInt("height", 820));
        setLocationByPlatform(true);

        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close");
        tabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.IntConsumer) this::closeTab);
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
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
                openFolderSearch(p); // a folder (Open with Logsift on a folder): search in it
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
            showUpdate(known, PREFS.get("updates.url", updateUrl)); // known from an earlier check
        }
        long last = PREFS.getLong("updates.lastCheck", 0);
        if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
            checkForUpdates(false);
        }
    }

    /**
     * Asks GitHub for the latest release (in the background). A newer one shows a button in the
     * toolbar; when {@code manual}, the answer is also shown in a dialog.
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

    private record Opened(java.nio.charset.Charset charset, LogDocument doc) {}

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
                tabs.setSelectedIndex(i);
                AppState.addRecent(abs);
                if (then != null) {
                    then.accept(lt);
                }
                return;
            }
        }
        if (!opening.add(abs)) {
            return; // already on its way
        }
        java.util.concurrent.CompletableFuture<Opened> f = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                long testDelay = Long.getLong("logsift.testOpenDelayMs", 0); // tests: a slow drive
                if (testDelay > 0) {
                    Thread.sleep(testDelay);
                }
                if (!Files.isRegularFile(abs)) {
                    throw new IOException("Not a file (or not reachable): " + abs);
                }
                java.nio.charset.Charset cs = forced;
                if (cs == null) {
                    try {
                        cs = logsift.core.Encoding.detect(abs, fallbackEncoding());
                    } catch (IOException e) {
                        cs = java.nio.charset.StandardCharsets.UTF_8;
                    }
                }
                if (logsift.core.Encoding.isUtf16(cs)) {
                    return new Opened(cs, null);
                }
                return new Opened(cs, LogDocument.open(abs, logsift.core.LineIndex.DEFAULT_CHUNK_SIZE, cs));
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
            openUtf16(abs, r.charset(), then);
            return;
        }
        LogTab tab = new LogTab(r.doc(), abs, null, this);
        addLogTab(tab, abs);
        if (then != null) {
            then.accept(tab);
        }
    }

    /** Files from the last session: if they are gone now, say nothing. */
    private final java.util.Set<Path> quietOpen = new java.util.HashSet<>();

    private void showOpenError(Path abs, Throwable e) {
        if (quietOpen.remove(abs)) {
            status.setText("Not reopened (missing): " + abs);
            return;
        }
        Throwable cause = e instanceof java.io.UncheckedIOException u ? u.getCause() : e;
        JOptionPane.showMessageDialog(this, "Cannot open " + abs + ":\n"
                + (cause == null ? "unknown error" : cause.getMessage()), "Open", JOptionPane.ERROR_MESSAGE);
    }

    /** UTF-16 logs are read through a UTF-8 copy that follows the original. */
    private void openUtf16(Path abs, java.nio.charset.Charset cs, java.util.function.Consumer<LogTab> then) {
        ProgressDialog.run(this, "Converting " + abs.getFileName() + " from " + cs.name() + "…",
                (progress, cancelled) -> {
                    Path dir = Files.createTempDirectory("logsift-utf16-");
                    dir.toFile().deleteOnExit();
                    Path copy = dir.resolve(abs.getFileName().toString());
                    copy.toFile().deleteOnExit();
                    return logsift.core.Utf16Mirror.create(abs, copy, cs, progress, cancelled);
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
                        JOptionPane.showMessageDialog(this, "Cannot open " + abs + ":\n" + e.getMessage(),
                                "Open", JOptionPane.ERROR_MESSAGE);
                    }
                });
    }

    /** Shows a new log tab with the current view settings and the file's remembered state. */
    private void addLogTab(LogTab tab, Path abs) {
        tab.mainView().setFontSize(fontSize);
        tab.resultsView().setFontSize(fontSize);
        tab.mainView().setWrap(wrap);
        tab.resultsView().setWrap(wrap);
        tab.mainView().setStripes(PREFS.getBoolean("stripes", true));
        tab.resultsView().setStripes(PREFS.getBoolean("stripes", true));
        tab.setTimelineVisible(PREFS.getBoolean("timeline", true));
        tab.setColumnsVisible(columns);
        tab.setAutoReload(autoReload);
        tabs.addTab(tab.title(), tab);
        int idx = tabs.getTabCount() - 1;
        tabs.setToolTipTextAt(idx, abs.toString());
        tabs.setSelectedIndex(idx);
        PREFS.put("lastDir", abs.getParent().toString());
        AppState.addRecent(abs);
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
        if (sftp == null && (tab.sourceLabels() != null || p.toAbsolutePath().normalize().toString().startsWith(tmp)
                && p.toString().contains("logsift-"))) {
            return; // a merged log, or another copy of Logsift's own
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

    /** Opens a Heap tab for a running JVM. */
    HeapTab openHeap(String source, logsift.jvm.Profiler.Target target) {
        HeapTab tab = new HeapTab(source, target);
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
        p.add(new JLabel("Merging " + files.size() + " logs by time…"), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        p.add(cancel, BorderLayout.EAST);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        Thread t = new Thread(() -> {
            try {
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

    /** Opens a new Folder Search tab (folder null = the current file's folder or the last one). */
    public FolderSearchTab openFolderSearch(Path folder) {
        if (folder == null && currentTab() != null) {
            folder = currentTab().path().getParent();
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

    /** Unpacked copies of compressed files, so opening the same hit twice is instant. */
    private final java.util.Map<String, Path> unpacked = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void openHit(logsift.core.FolderSearch.FileResult f, long line, SearchQuery q) {
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
        }, "logsift-unpack");
        t.setDaemon(true);
        t.start();
    }

    private Path unpack(logsift.core.FolderSearch.FileResult f, String name) throws IOException {
        String key = f.file() + "!" + f.entry() + "@" + Files.getLastModifiedTime(f.file()).toMillis();
        Path done = unpacked.get(key);
        if (done != null && Files.exists(done)) {
            return done;
        }
        Path dir = Files.createTempDirectory("logsift-");
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
        openFile(file, null, t -> {
            tabs.setSelectedComponent(t);
            findAll(q);
            t.showLineWhenAvailable(line);
            t.mainView().focusView();
        });
    }

    String reloadButtonTextForTest() {
        return reloadButton.getText();
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
        java.util.function.Consumer<LogTab> select = t -> SwingUtilities.invokeLater(() -> {
            if (tabs.indexOfComponent(t) >= 0) {
                tabs.setSelectedComponent(t);
            }
        });
        for (int i = 0; i < s.files().size(); i++) {
            // no existence check here: on an unreachable share it could hang; opening handles it
            Path p = s.files().get(i).toAbsolutePath().normalize();
            quietOpen.add(p);
            openFile(p, null, i == s.selected() ? select.andThen(t -> quietOpen.remove(p))
                    : t -> quietOpen.remove(p));
        }
    }

    javax.swing.JButton formatButtonForTest() {
        return formatButton;
    }

    javax.swing.JPopupMenu formatPopupForTest() {
        return formatPopup;
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
        // the ✕ in the Find box also closes the selected search's results
        findField.putClientProperty("JTextField.clearCallback", (Runnable) () -> {
            findField.setText("");
            LogTab t = currentTab();
            if (t != null) {
                t.closeResults();
            }
        });
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

        JButton open = new JButton(Icons.open());
        open.setToolTipText("Open log files (" + (isMac() ? "⌘O" : "Ctrl+O") + ")");
        open.addActionListener(e -> openDialog());
        JButton remote = new JButton(Icons.remote());
        remote.setToolTipText("Open a log on another machine over SSH (" + (isMac() ? "⇧⌘O" : "Ctrl+Shift+O") + ")");
        remote.addActionListener(e -> openRemoteDialog());
        JButton findAll = new JButton("Find All", Icons.find());
        findAll.addActionListener(e -> findAllFromToolbar());
        JButton prev = new JButton(Icons.up());
        prev.setToolTipText("Previous hit (Shift+F3)");
        prev.addActionListener(e -> findNext(false));
        JButton next = new JButton(Icons.down());
        next.setToolTipText("Next hit (F3)");
        next.addActionListener(e -> findNext(true));
        followButton.setIcon(Icons.follow());
        wrapButton.setIcon(Icons.wrap());
        columnsButton.setIcon(Icons.columns());
        reloadButton.setIcon(Icons.reload());
        reloadButton.setToolTipText("Load what changed in the file on disk (F5)");
        reloadButton.addActionListener(e -> reload());
        autoReloadButton.setIcon(Icons.autoReload());
        autoReloadButton.setSelected(autoReload);
        autoReloadButton.setToolTipText("<html>Load changes to the file as they happen.<br>"
                + "Off: changes wait until you click Reload (the button lights up). Follow turns it on.</html>");
        autoReloadButton.addActionListener(e -> setAutoReload(autoReloadButton.isSelected()));

        followButton.setToolTipText("Follow the end of the file as it grows (tail -f)");
        followButton.addActionListener(e -> setFollow(followButton.isSelected()));
        wrapButton.setToolTipText("Wrap long lines (" + (isMac() ? "⌥Z" : "Alt+Z") + ")");
        wrapButton.setSelected(wrap);
        wrapButton.addActionListener(e -> setWrap(wrapButton.isSelected()));
        columnsButton.setToolTipText("Show lines as columns: time, level, thread, logger, message");
        columnsButton.setSelected(columns);
        columnsButton.addActionListener(e -> setColumns(columnsButton.isSelected()));
        treeButton.setIcon(Icons.of("list-tree"));
        treeButton.setToolTipText("Tree of loggers (threads, components…) on the left: hide or show their lines ("
                + (isMac() ? "⇧⌘B" : "Ctrl+Shift+B") + ")");
        treeButton.setSelected(PREFS.getBoolean("valuesTree", true));
        treeButton.addActionListener(e -> setValuesTree(treeButton.isSelected()));
        timelineButton.setIcon(Icons.of("chart-column"));
        timelineButton.setToolTipText("Timeline: lines over time, errors in red; click it to jump to a time");
        timelineButton.setSelected(PREFS.getBoolean("timeline", true));
        timelineButton.addActionListener(e -> setTimeline(timelineButton.isSelected()));
        JButton settings = new JButton(Icons.of("settings"));
        settings.setToolTipText("Settings: font, log formats, encoding… (" + (isMac() ? "⌘," : "Ctrl+,") + ")");
        settings.addActionListener(e -> showSettings(null));

        tb.add(open);
        tb.add(remote);
        tb.addSeparator();
        tb.add(findField);
        tb.addSeparator(new Dimension(6, 1));
        tb.add(findAll);
        tb.add(prev);
        tb.add(next);
        tb.addSeparator();
        tb.add(followButton);
        tb.add(wrapButton);
        tb.add(columnsButton);
        tb.add(treeButton);
        tb.add(timelineButton);
        tb.addSeparator();
        tb.add(reloadButton);
        tb.add(autoReloadButton);
        tb.add(Box.createHorizontalGlue());
        updateButton.putClientProperty("JButton.buttonType", "toolBarButton");
        updateButton.setFont(updateButton.getFont().deriveFont(Font.BOLD));
        updateButton.setForeground(new Color(0x2563EB));
        updateButton.setFocusable(false);
        updateButton.setVisible(false);
        updateButton.addActionListener(e -> HelpSupport.browse(this, updateUrl));
        tb.add(updateButton);
        tb.add(settings);
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
        formatButton.putClientProperty("JButton.buttonType", "borderless");
        formatButton.setFocusable(false);
        formatButton.setMargin(new java.awt.Insets(0, 4, 0, 4));
        formatButton.setVisible(false);
        formatButton.addActionListener(e -> {
            javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
            fillFormatMenu(popup);
            formatPopup = popup;
            popup.show(formatButton, 0, -popup.getPreferredSize().height);
        });
        right.add(formatButton, BorderLayout.WEST);
        p.add(status, BorderLayout.CENTER);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JMenuBar buildMenus() {
        JMenuBar bar = new JMenuBar();
        int shift = InputEvent.SHIFT_DOWN_MASK;

        // ---- File: opening things, then making things from files, then leaving
        JMenu file = menu("File");
        file.add(item("Open…", "folder-open", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU), e -> openDialog()));
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
        if (WslSupport.available()) {
            file.add(item("Open from WSL…", "square-terminal", null, e -> openFromWsl()));
        }
        file.add(item("Reopen Last Session", "rotate-ccw", null, e -> restoreSession()));
        file.addSeparator();
        file.add(item("Search in Folder…", "folder-search", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU | shift),
                e -> openFolderSearch(null)));
        file.add(item("Merge Files by Time…", "git-merge", KeyStroke.getKeyStroke(KeyEvent.VK_M, MENU | shift),
                e -> mergeDialog()));
        file.add(item("Compare With…", "git-compare", KeyStroke.getKeyStroke(KeyEvent.VK_D, MENU | shift),
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
        edit.add(item("Find…", "search", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU), e -> {
            // text selected in the log becomes the search text
            LogView v = LogView.lastFocused();
            String s = v != null && v.isViewFocused() ? v.selectionForFind() : null;
            if (s != null) {
                findField.setText(s);
            }
            findField.requestFocusInWindow();
            findField.selectAll();
        }));
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
                followButton::isSelected, e -> setFollow(!followButton.isSelected())));
        view.add(item("Reload", "refresh-cw", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), e -> reload()));
        view.add(check("Auto Reload", "refresh-ccw-dot", null, () -> autoReload, e -> setAutoReload(!autoReload)));
        view.addSeparator();
        view.add(check("Columns", "columns-3", KeyStroke.getKeyStroke(KeyEvent.VK_K, MENU | shift),
                () -> columns, e -> setColumns(!columns)));
        view.add(check("Word Wrap", "wrap-text", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.ALT_DOWN_MASK),
                () -> wrap, e -> setWrap(!wrap)));
        view.add(check("Matching Lines Only", "list-filter", KeyStroke.getKeyStroke(KeyEvent.VK_H, MENU | shift),
                () -> currentTab() != null && currentTab().isMatchingOnly(), e -> {
                    LogTab t = currentTab();
                    if (t != null) {
                        t.setMatchingOnly(!t.isMatchingOnly());
                    }
                }));
        view.addSeparator();
        view.add(check("Tree of Loggers", "list-tree", KeyStroke.getKeyStroke(KeyEvent.VK_B, MENU | shift),
                () -> PREFS.getBoolean("valuesTree", true), e -> setValuesTree(!PREFS.getBoolean("valuesTree", true))));
        view.add(check("Timeline", "chart-column", null,
                () -> PREFS.getBoolean("timeline", true), e -> setTimeline(!PREFS.getBoolean("timeline", true))));
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
        onOpen(format, () -> fillFormatMenu(format));
        fillFormatMenu(format);
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
        jvm.add(item("Open Profile…", "flame", null, e -> openProfileDialog()));
        bar.add(jvm);

        // ---- Help
        JMenu help = menu("Help");
        help.add(item("Keyboard Shortcuts", "keyboard", KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, MENU),
                e -> HelpSupport.shortcutsDialog(this, getJMenuBar()).setVisible(true)));
        help.add(item("Documentation", "book-open", null, e -> HelpSupport.browse(this, HelpSupport.DOCS)));
        help.addSeparator();
        help.add(item("Report a Bug or Suggest a Feature…", "bug", null,
                e -> HelpSupport.browse(this, HelpSupport.bugReportUrl(null, null))));
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
        } else if (c instanceof ThreadsTab th) {
            threadLogs.remove(th);
            th.close();
        } else if (c instanceof ProfileTab pt) {
            pt.close();
        } else if (c instanceof HeapTab ht) {
            ht.close();
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

    private void nextBookmark(boolean forward) {
        LogTab t = currentTab();
        if (t != null && !t.nextBookmark(forward)) {
            Toolkit.getDefaultToolkit().beep();
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
        fd.setDirectory(t.path().getParent().toString());
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

    private void fillFormatMenu(JComponent menu) {
        Runnable separator = () -> menu.add(new javax.swing.JPopupMenu.Separator());
        menu.removeAll();
        LogTab t = currentTab();
        String choice = t == null ? null : t.formatChoice();
        logsift.core.LogFormat now = t == null ? null : t.logFormat();
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        java.util.function.BiConsumer<String, String> radio = (label, name) -> {
            javax.swing.JRadioButtonMenuItem mi = new javax.swing.JRadioButtonMenuItem(label,
                    java.util.Objects.equals(choice, name));
            mi.setEnabled(t != null);
            mi.addActionListener(e -> {
                LogTab cur = currentTab();
                if (cur != null) {
                    cur.setFormatChoice(name);
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
        separator.run();
        JMenuItem create = item("New Format from This Log…", null, e -> newFormatFromCurrentLog());
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

    /** Shows the tree of loggers (threads, components...) beside logs that have columns. */
    @Override
    public void setValuesTree(boolean on) {
        PREFS.putBoolean("valuesTree", on);
        treeButton.setSelected(on);
        for (LogTab t : allTabs()) {
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
        timelineButton.setSelected(on);
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

    /** Loads changes on disk as they happen (otherwise Reload does it). For every log. */
    public void setAutoReload(boolean on) {
        autoReload = on;
        PREFS.putBoolean("autoReload", on);
        autoReloadButton.setSelected(on);
        for (LogTab t : allTabs()) {
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

    /** Lights up the Reload button when the current file changed and isn't loaded yet. */
    private void updateReloadButton(LogTab t) {
        LogDocument.Change c = t == null ? LogDocument.Change.NONE : t.document().pendingChange();
        boolean stale = c != LogDocument.Change.NONE;
        reloadButton.setEnabled(t != null);
        if (stale) {
            reloadStale = true;
            reloadButton.setForeground(Theme.isDark() ? new Color(0xFFE7A3) : new Color(0x5C4300));
            reloadButton.setFont(reloadButton.getFont().deriveFont(Font.BOLD));
            reloadButton.setText(c == LogDocument.Change.GREW
                    ? "Reload (+" + humanBytes(t.document().pendingBytes()) + ")" : "Reload (file replaced)");
        } else {
            reloadStale = false;
            reloadButton.setForeground(null);
            reloadButton.setFont(UIManager.getFont("Button.font"));
            reloadButton.setText("Reload");
        }
    }

    /** Shows lines as columns (time, level, thread, ...) in every log view. */
    public void setColumns(boolean on) {
        columns = on;
        PREFS.putBoolean("columns", on);
        columnsButton.setSelected(on);
        for (LogTab t : allTabs()) {
            t.setColumnsVisible(on);
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
        updateReloadButton(t);
        String fmt = t == null || tabs.getSelectedComponent() != t ? "" : t.formatName();
        formatButton.setVisible(!fmt.isEmpty());
        formatButton.setText("Format: " + fmt + " \u25BE");
        formatButton.setToolTipText(t != null && t.formatChoice() != null
                ? "Lines are read as \u201c" + fmt + "\u201d (chosen for this file). Click to change."
                : "Lines are read as \u201c" + fmt + "\u201d (detected). Click if that is wrong.");
        if (tabs.getSelectedComponent() instanceof HeapTab ht) {
            setTitle(ht.title() + " — Logsift");
            status.setText(ht.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof ProfileTab pt) {
            setTitle(pt.title() + " — Logsift");
            status.setText(pt.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof ThreadsTab th) {
            setTitle(th.title() + " — Logsift");
            status.setText(th.statusText());
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof CompareTab ct) {
            setTitle(ct.title() + " — Logsift");
            status.setText(" ");
            statusRight.setText(memory());
            progress.setVisible(false);
            return;
        }
        if (tabs.getSelectedComponent() instanceof FolderSearchTab f) {
            setTitle(f.title() + " — Logsift");
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
            setTitle("Logsift");
            return;
        }
        LogDocument d = t.document();
        setTitle(d.path().getFileName() + " — Logsift");
        StringBuilder sb = new StringBuilder();
        long size = d.fileSize();
        if (!t.path().equals(d.path())) {
            try {
                size = Files.size(t.path()); // the original, not the converted copy
            } catch (IOException e) {
                // keep the copy's size
            }
        }
        sb.append(t.remoteLabel() != null ? "sftp " + t.remoteLabel() : t.path().toString())
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
