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
    private final JButton updateButton = new JButton();
    private String updateUrl = HelpSupport.REPO + "/releases/latest";
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private float fontSize = PREFS.getFloat("fontSize", 13f);

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
            }
        });
    }

    // ---------------------------------------------------------------- public API

    /** Opens files handed over by the system or a second launch, and comes to the front. */
    public void openFiles(List<Path> files) {
        for (Path p : files) {
            openFile(p);
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
                        cs = logsift.core.Encoding.detect(abs);
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
        JMenu recent = new JMenu("Open Recent");
        recent.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
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
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {}

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        file.add(recent);
        file.add(item("Reopen Last Session", null, e -> restoreSession()));
        file.add(item("Open Remote (SFTP)…", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> openRemoteDialog()));
        file.add(item("Merge Files by Time…", KeyStroke.getKeyStroke(KeyEvent.VK_M, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> mergeDialog()));
        file.add(item("Compare With…", KeyStroke.getKeyStroke(KeyEvent.VK_D, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> compareDialog()));
        file.add(item("Search in Folder…", KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> openFolderSearch(null)));
        file.add(item("Close Tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, MENU), e -> closeTab(tabs.getSelectedIndex())));
        file.addSeparator();
        JMenu export = new JMenu("Export");
        export.add(item("Lines Shown by Filters…", KeyStroke.getKeyStroke(KeyEvent.VK_E, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> exportLines("filtered")));
        export.add(item("Find Results…", null, e -> exportLines("find")));
        export.add(item("Selected Lines…", null, e -> exportLines("selection")));
        export.add(item("Everything in the View…", null, e -> exportLines("view")));
        file.add(export);
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
            // text selected in the log becomes the search text
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
        edit.add(item("Toggle Bookmark", KeyStroke.getKeyStroke(KeyEvent.VK_F2, MENU), e -> {
            LogTab t = currentTab();
            if (t != null) {
                t.toggleBookmark();
            }
        }));
        edit.add(item("Next Bookmark", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), e -> nextBookmark(true)));
        edit.add(item("Previous Bookmark", KeyStroke.getKeyStroke(KeyEvent.VK_F2, InputEvent.SHIFT_DOWN_MASK),
                e -> nextBookmark(false)));
        edit.addSeparator();
        edit.add(item("Go to Line…", KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU), e -> goToLineDialog()));
        edit.add(item("Go to Time…", KeyStroke.getKeyStroke(KeyEvent.VK_L, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> goToTimeDialog()));
        bar.add(edit);

        JMenu view = new JMenu("View");
        view.add(item("Follow (Tail)", KeyStroke.getKeyStroke(KeyEvent.VK_T, MENU), e -> setFollow(!followButton.isSelected())));
        view.addSeparator();
        JMenu encoding = new JMenu("Encoding");
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
        view.add(item("Timeline", null, e -> {
            boolean on = !PREFS.getBoolean("timeline", true);
            PREFS.putBoolean("timeline", on);
            for (LogTab t : allTabs()) {
                t.setTimelineVisible(on);
            }
        }));
        view.add(item("Reload", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), e -> reload()));
        view.add(item("Auto Reload", null, e -> setAutoReload(!autoReload)));
        view.addSeparator();
        view.add(item("Columns", KeyStroke.getKeyStroke(KeyEvent.VK_K, MENU | InputEvent.SHIFT_DOWN_MASK),
                e -> setColumns(!columns)));
        view.add(item("Line Stripes", null, e -> {
            boolean on = !PREFS.getBoolean("stripes", true);
            PREFS.putBoolean("stripes", on);
            for (LogTab t : allTabs()) {
                t.mainView().setStripes(on);
                t.resultsView().setStripes(on);
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
        view.add(item("Fold / Open Bottom Panel", KeyStroke.getKeyStroke(KeyEvent.VK_J, MENU), e -> {
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

        JMenu help = new JMenu("Help");
        help.add(item("Keyboard Shortcuts", KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, MENU),
                e -> HelpSupport.shortcutsDialog(this, getJMenuBar()).setVisible(true)));
        help.add(item("Documentation", null, e -> HelpSupport.browse(this, HelpSupport.DOCS)));
        help.addSeparator();
        help.add(item("Report a Bug or Suggest a Feature…", null,
                e -> HelpSupport.browse(this, HelpSupport.bugReportUrl(null, null))));
        help.add(item("Open Error Log", null, e -> openErrorLog()));
        help.addSeparator();
        help.add(item("Check for Updates…", null, e -> checkForUpdates(true)));
        javax.swing.JCheckBoxMenuItem auto = new javax.swing.JCheckBoxMenuItem("Check for Updates Automatically",
                PREFS.getBoolean("updates.auto", true));
        auto.addActionListener(e -> PREFS.putBoolean("updates.auto", auto.isSelected()));
        help.add(auto);
        if (!isMac()) { // on macOS, About is in the application menu
            help.addSeparator();
            help.add(item("About Logsift", null, e -> HelpSupport.showAbout(this)));
        }
        bar.add(help);
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
            t.close();
        } else if (c instanceof FolderSearchTab f) {
            f.close();
        } else if (c instanceof CompareTab ct) {
            ct.close();
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
