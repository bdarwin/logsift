package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.jvm.DumpText;
import logsift.jvm.JThread;
import logsift.jvm.ThreadAnalysis;
import logsift.jvm.ThreadDump;

/**
 * Thread dumps, explained: what stands out at the top (deadlocks, lock queues, stuck and busy
 * threads), then the threads of one dump (grouped by pool or by stack, filtered by state or
 * text), threads over time across dumps, and what changed between two dumps. With a live JVM
 * behind it, takes new dumps, one at a time or several some seconds apart.
 */
final class ThreadsTab extends JPanel {

    /** Where new dumps come from (a running JVM); {@link #take} runs on a background thread. */
    interface Capture {
        String label();

        ThreadDump take() throws Exception;

        /** False once closed, or the JVM has gone. */
        default boolean isOpen() {
            return true;
        }

        default void close() {
        }
    }

    private static final Color RED = new Color(0xDC2626);
    private static final Color AMBER = new Color(0xD97706);
    private static final Color BLUE = new Color(0x2563EB);
    private static final Color GREEN = new Color(0x16A34A);

    private final String source;
    private final Capture capture;
    private final List<ThreadDump> dumps = new ArrayList<>();
    private int current = -1;
    private ThreadAnalysis.Report report;
    private Runnable changed = () -> { };
    private Runnable profile;

    private final JComboBox<String> dumpBox = new JComboBox<>();
    private final JLabel summary = new JLabel(" ");
    private final JButton take = new JButton("Take Dump", Icons.of("camera"));
    private final JButton record = new JButton("Record…", Icons.of("circle-dot"));
    private final JButton stop = new JButton("Stop", Icons.of("square"));
    private final JButton profileButton = new JButton("Profile…", Icons.of("flame"));
    private final JButton heapButton = new JButton("Heap…", Icons.of("memory-stick"));
    private Runnable heap;
    private final JButton save = new JButton("Save…", Icons.of("save"));
    private final JButton inLog = new JButton("Lines in Log", Icons.of("file-text"));
    private java.util.function.Consumer<String> showInLog;
    private String logName;
    private final JLabel status = new JLabel(" ");
    private volatile AtomicBoolean recording;

    private final JPanel findings = new InsightsListPanel();
    private final JSplitPane split;
    private final JScrollPane findingsScroll;

    private final JComboBox<String> group = new JComboBox<>(new String[] {"No groups", "Group by pool",
        "Group by same stack"});
    private final JComboBox<String> stateFilter = new JComboBox<>(new String[] {"All states", "Runnable",
        "Blocked", "Waiting", "Busy (not idle)"});
    private final JTextField search = new JTextField(16);
    private final JCheckBox jvmThreads = new JCheckBox("JVM threads");
    private final JButton focusChip = new JButton();
    private Set<String> focus;
    private int sortColumn = -1;   // before the table: it asks for column names when made
    private boolean sortDown;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final RowModel model = new RowModel();
    private final JTable table = new JTable(model);
    private final ThreadStackView stack = new ThreadStackView(this::selectKey);

    private final JTabbedPane views = new JTabbedPane();
    private final JCheckBox busyOnly = new JCheckBox("Only threads that were busy", true);
    private final ThreadStackView timelineStack = new ThreadStackView(this::selectKey);
    private final ThreadTimeline timeline = new ThreadTimeline((key, d) -> {
        setCurrent(d);
        JThread t = dumps.get(d).find(key);
        timelineStack.show(dumps.get(d), t);
    });

    private final JComboBox<String> cmpA = new JComboBox<>();
    private final JComboBox<String> cmpB = new JComboBox<>();
    private final List<ThreadAnalysis.Change> changes = new ArrayList<>();
    private final CompareModel cmpModel = new CompareModel();
    private final JTable cmpTable = new JTable(cmpModel);
    private final ThreadStackView cmpStack = new ThreadStackView(this::selectKey);
    private final JLabel cmpSummary = new JLabel(" ");
    private final JCheckBox onlyChanges = new JCheckBox("Only changes", true);

    /** Dumps read from {@code source} (a file), or taken by {@code capture} (null for a file). */
    ThreadsTab(String source, List<ThreadDump> initial, Capture capture) {
        super(new BorderLayout());
        this.source = source;
        this.capture = capture;

        // ---- top: which dump, what it is, capturing
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 8));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        dumpBox.setToolTipText("Which dump to look at");
        dumpBox.addActionListener(e -> {
            if (dumpBox.getSelectedIndex() >= 0 && dumpBox.getSelectedIndex() != current) {
                setCurrent(dumpBox.getSelectedIndex());
            }
        });
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        left.add(dumpBox);
        left.add(summary);
        top.add(left, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        right.add(status);
        for (JButton b : new JButton[] {take, record, stop, profileButton, heapButton, inLog, save}) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            right.add(b);
        }
        take.setToolTipText("Take a thread dump now (the JVM stops for a moment: milliseconds, more with many threads)");
        record.setToolTipText("Take several dumps some seconds apart, to see stuck threads and CPU use");
        stop.setToolTipText("Stop recording");
        profileButton.setToolTipText("See where the JVM spends its time, memory or lock waits (a flame graph)");
        heapButton.setToolTipText("See what fills the heap, by class, and what grows");
        heapButton.addActionListener(e -> {
            if (heap != null) {
                heap.run();
            }
        });
        heapButton.setVisible(false);
        save.setToolTipText("Save the dumps as a text file (it opens again here, or in any other tool)");
        take.addActionListener(e -> takeDumps(1, 0));
        record.addActionListener(e -> recordDialog());
        stop.addActionListener(e -> {
            AtomicBoolean r = recording;
            if (r != null) {
                r.set(true);
            }
        });
        profileButton.addActionListener(e -> {
            if (profile != null) {
                profile.run();
            }
        });
        save.addActionListener(e -> saveDialog());
        inLog.setVisible(false);
        inLog.setEnabled(false);
        inLog.addActionListener(e -> {
            JThread t = selectedThread();
            if (t != null && showInLog != null) {
                showInLog.accept(t.name());
            }
        });
        take.setVisible(capture != null);
        record.setVisible(capture != null);
        stop.setVisible(false);
        profileButton.setVisible(false);
        top.add(right, BorderLayout.EAST);

        // ---- findings
        findings.setLayout(new BoxLayout(findings, BoxLayout.Y_AXIS));
        findings.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
        findingsScroll = new JScrollPane(findings);
        findingsScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        findingsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);

        // ---- threads
        views.addTab("Threads", Icons.of("rows-3"), threadsPanel());
        views.addTab("Timeline", Icons.of("activity"), timelinePanel());
        views.addTab("Compare", Icons.of("git-compare"), comparePanel());
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, findingsScroll, views);
        split.setResizeWeight(0.25);
        split.setBorder(null);
        split.setDividerLocation(170);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        dumps.addAll(initial);
        refreshDumps();
        setCurrent(dumps.size() - 1);
    }

    // ---------------------------------------------------------------- public bits

    String title() {
        return "Threads: " + source;
    }

    String source() {
        return source;
    }

    List<ThreadDump> dumps() {
        return List.copyOf(dumps);
    }

    ThreadAnalysis.Report report() {
        return report;
    }

    boolean isLive() {
        return capture != null;
    }

    boolean isRecording() {
        return recording != null;
    }

    /** Called when the title or status may have changed. */
    void onChanged(Runnable r) {
        changed = r;
    }

    void onChangedNow() {
        changed.run();
    }

    /** Shows a Profile button that runs {@code r}. */
    void setProfiler(Runnable r) {
        profile = r;
        profileButton.setVisible(r != null);
    }

    /** Shows a Heap button that runs {@code r}. */
    void setHeap(Runnable r) {
        heap = r;
        heapButton.setVisible(r != null);
    }

    JButton heapButtonForTest() {
        return heapButton;
    }

    String statusText() {
        return status.getText().isBlank() ? (report == null ? " " : report.summary()) : status.getText();
    }

    void close() {
        AtomicBoolean r = recording;
        if (r != null) {
            r.set(true);
        }
        if (capture != null) {
            capture.close();
        }
    }

    /** Offers to show a thread's lines in the log {@code name} (the log these threads write). */
    void setLog(String name, java.util.function.Consumer<String> show) {
        logName = name;
        showInLog = show;
        inLog.setVisible(show != null);
        inLog.setToolTipText("Show the selected thread's lines in " + name);
        inLog.setEnabled(selectedThread() != null);
    }

    String logName() {
        return logName;
    }

    String selectedThreadNameForTest() {
        JThread t = selectedThread();
        return t == null ? null : t.name();
    }

    JButton inLogButtonForTest() {
        return inLog;
    }

    private JThread selectedThread() {
        int r = table.getSelectedRow();
        return r >= 0 && r < rows.size() ? rows.get(r).thread() : null;
    }

    /**
     * Selects the thread a log calls {@code logged}: the same name, or a name the log cut short
     * (Spring Boot keeps the last 15 characters: {@code nio-8080-exec-1}). Looks in the dump shown,
     * then the newest. False if no thread has that name.
     */
    boolean selectThreadNamed(String logged) {
        String n = logged == null ? "" : logged.strip();
        if (n.isEmpty() || dumps.isEmpty()) {
            return false;
        }
        List<Integer> order = new ArrayList<>();
        if (current >= 0) {
            order.add(current);
        }
        for (int i = dumps.size() - 1; i >= 0; i--) {
            if (i != current) {
                order.add(i);
            }
        }
        for (int d : order) {
            JThread t = threadNamed(dumps.get(d), n);
            if (t != null) {
                if (d != current) {
                    setCurrent(d);
                }
                views.setSelectedIndex(0);
                selectKey(t.key());
                return true;
            }
        }
        return false;
    }

    /** True if a dump in the tab has a thread a log names {@code n}. */
    static boolean threadNamedIn(ThreadsTab tab, String n) {
        return tab.dumps.stream().anyMatch(d -> threadNamed(d, n.strip()) != null);
    }

    /** The thread of a dump a log names {@code n} (maybe cut short), or null. */
    static JThread threadNamed(ThreadDump dump, String n) {
        JThread cut = null;
        for (JThread t : dump.threads()) {
            if (t.name().equals(n)) {
                return t;
            }
            if (cut == null && n.length() >= 6 && (t.name().endsWith(n) || t.name().startsWith(n))) {
                cut = t;
            }
        }
        return cut;
    }

    /** Takes one more dump (a live tab). */
    void takeDumpNow() {
        if (capture != null && recording == null) {
            takeDumps(1, 0);
        }
    }

    /** Adds a dump (taken just now) and shows it. */
    void addDump(ThreadDump d) {
        dumps.add(d);
        refreshDumps();
        setCurrent(dumps.size() - 1);
    }

    // ---------------------------------------------------------------- capturing

    private void recordDialog() {
        JSpinner count = new JSpinner(new SpinnerNumberModel(AppState.prefs().getInt("threads.recordCount", 6), 2, 200, 1));
        JSpinner every = new JSpinner(new SpinnerNumberModel(AppState.prefs().getInt("threads.recordSeconds", 5), 1, 600, 1));
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.add(new JLabel("Take"));
        p.add(count);
        p.add(new JLabel("dumps,"));
        p.add(every);
        p.add(new JLabel("seconds apart"));
        JPanel all = new JPanel(new BorderLayout(0, 8));
        all.add(p, BorderLayout.CENTER);
        JLabel note = new JLabel("<html>Each dump stops the JVM for a moment: usually milliseconds, longer with"
                + " thousands of threads or a big heap (finding who holds which lock walks the heap).</html>");
        note.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        all.add(note, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, all, "Record Thread Dumps", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            int n = (Integer) count.getValue();
            int s = (Integer) every.getValue();
            AppState.prefs().putInt("threads.recordCount", n);
            AppState.prefs().putInt("threads.recordSeconds", s);
            takeDumps(n, s * 1000L);
        }
    }

    /** Takes {@code n} dumps, {@code everyMs} apart, in the background. */
    void takeDumps(int n, long everyMs) {
        if (capture == null || recording != null) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        recording = cancel;
        take.setEnabled(false);
        record.setEnabled(false);
        stop.setVisible(n > 1);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText(n > 1 ? "Recording 1 of " + n + "…" : "Taking a dump…");
        Thread t = new Thread(() -> {
            String failed = null;
            for (int i = 0; i < n && !cancel.get(); i++) {
                long started = System.currentTimeMillis();
                try {
                    ThreadDump d = capture.take();
                    int done = i + 1;
                    SwingUtilities.invokeLater(() -> {
                        addDump(d);
                        if (n > 1 && done < n) {
                            status.setText("Recording " + (done + 1) + " of " + n + "…");
                        }
                    });
                } catch (Exception e) {
                    failed = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    break;
                }
                if (i < n - 1) {
                    long wait = everyMs - (System.currentTimeMillis() - started);
                    while (wait > 0 && !cancel.get()) {
                        try {
                            Thread.sleep(Math.min(wait, 100));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        wait -= 100;
                    }
                }
            }
            String error = failed;
            SwingUtilities.invokeLater(() -> {
                recording = null;
                take.setEnabled(true);
                record.setEnabled(true);
                stop.setVisible(false);
                if (error != null) {
                    status.setForeground(RED);
                    status.setText("Could not take a dump: " + error);
                } else {
                    status.setText(" ");
                }
                changed.run();
            });
        }, "logsift-threads-capture");
        t.setDaemon(true);
        t.start();
    }

    private void saveDialog() {
        JFileChooser ch = new JFileChooser();
        String name = source.replaceAll("[^A-Za-z0-9._-]+", "-");
        ch.setSelectedFile(new java.io.File("threads-" + name + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"));
        if (ch.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                save(ch.getSelectedFile().toPath());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Cannot save: " + e.getMessage(), "Save",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    void save(Path file) throws IOException {
        Files.writeString(file, DumpText.write(dumps), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- showing a dump

    private void refreshDumps() {
        int keep = dumpBox.getSelectedIndex();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < dumps.size(); i++) {
            ThreadDump d = dumps.get(i);
            labels.add((dumps.size() > 1 ? "Dump " + (i + 1) + " of " + dumps.size() : "Dump")
                    + (d.clock().isEmpty() ? "" : " · " + d.clock()));
        }
        dumpBox.setModel(new javax.swing.DefaultComboBoxModel<>(labels.toArray(String[]::new)));
        dumpBox.setVisible(dumps.size() > 1);
        if (keep >= 0 && keep < labels.size()) {
            dumpBox.setSelectedIndex(keep);
        }
        save.setEnabled(!dumps.isEmpty());
        for (JComboBox<String> c : List.of(cmpA, cmpB)) {
            int sel = c.getSelectedIndex();
            c.setModel(new javax.swing.DefaultComboBoxModel<>(labels.toArray(String[]::new)));
            if (sel >= 0 && sel < labels.size()) {
                c.setSelectedIndex(sel);
            }
        }
        if (dumps.size() > 1) {
            if (cmpA.getSelectedIndex() < 0 || cmpA.getSelectedIndex() >= dumps.size() - 1) {
                cmpA.setSelectedIndex(0);
            }
            cmpB.setSelectedIndex(dumps.size() - 1);
        }
        views.setEnabledAt(1, dumps.size() > 1);
        views.setEnabledAt(2, dumps.size() > 1);
        views.setToolTipTextAt(1, dumps.size() > 1 ? "Each thread's state in every dump"
                : "Take or open several dumps to see threads over time");
        views.setToolTipTextAt(2, dumps.size() > 1 ? "What changed between two dumps"
                : "Take or open several dumps to compare them");
        refreshTimeline();
        refreshCompare();
    }

    int current() {
        return current;
    }

    /** Looks at dump {@code i}: what stands out up to then, and its threads. */
    void setCurrent(int i) {
        if (i < 0 || i >= dumps.size()) {
            current = -1;
            summary.setText(dumps.isEmpty() ? "No dumps yet" + (capture != null ? ": click Take Dump" : "") : " ");
            findings.removeAll();
            rows.clear();
            model.fireTableDataChanged();
            stack.clear(capture != null ? "Take a dump to see the threads." : "No threads.");
            changed.run();
            return;
        }
        String selected = selectedKey();
        current = i;
        if (dumpBox.getSelectedIndex() != i) {
            dumpBox.setSelectedIndex(i);
        }
        report = ThreadAnalysis.analyze(dumps.subList(0, i + 1));
        summary.setText(report.summary());
        showFindings();
        refilter();
        if (selected == null) {
            // start with the thread that matters most
            selected = report.findings().stream().filter(f -> !f.keys().isEmpty())
                    .map(f -> f.keys().get(0)).findFirst().orElse(null);
        }
        if (selected != null) {
            selectKey(selected);
        } else {
            stack.show(dumps.get(i), null);
        }
        fitFindings();
        changed.run();
    }

    /** Gives the findings the room they need (at most about two fifths of the tab). */
    private void fitFindings() {
        SwingUtilities.invokeLater(() -> {
            if (split.getHeight() <= 0) {
                return;
            }
            int want = findings.getPreferredSize().height + 4;
            split.setDividerLocation(Math.max(60, Math.min(want, split.getHeight() * 2 / 5)));
        });
    }

    private void showFindings() {
        findings.removeAll();
        for (ThreadAnalysis.Finding f : report.findings()) {
            findings.add(findingRow(f));
        }
        findings.add(Box.createVerticalGlue());
        findings.revalidate();
        findings.repaint();
    }

    private JPanel findingRow(ThreadAnalysis.Finding f) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(5, 4, 5, 4)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridheight = 2;
        c.anchor = GridBagConstraints.NORTH;
        c.insets = new Insets(1, 2, 0, 10);
        p.add(new JLabel(iconFor(f)), c);
        JLabel title = new JLabel(f.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        c.gridx = 1;
        c.gridheight = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 8);
        p.add(title, c);
        JLabel detail = new JLabel(f.detail().isEmpty() ? " " : f.detail());
        detail.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        c.gridy = 1;
        p.add(detail, c);
        if (!f.keys().isEmpty()) {
            JButton show = new JButton(f.keys().size() == 1 ? "Show thread" : "Show " + f.keys().size() + " threads");
            show.putClientProperty("JButton.buttonType", "toolBarButton");
            show.setFocusable(false);
            show.addActionListener(e -> showFinding(f));
            c.gridx = 2;
            c.gridy = 0;
            c.gridheight = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            p.add(show, c);
            p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            p.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    showFinding(f);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    p.setOpaque(true);
                    p.setBackground(Theme.uiColor("List.hoverBackground",
                            Theme.uiColor("Table.selectionInactiveBackground", new Color(0xEEF2F7))));
                    p.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (!p.contains(e.getPoint())) {
                        p.setOpaque(false);
                        p.repaint();
                    }
                }
            });
        }
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** Shows only the threads of a finding, and the first of them. */
    void showFinding(ThreadAnalysis.Finding f) {
        if (f.dump() != current) {
            setCurrent(f.dump());
        }
        focus = new LinkedHashSet<>(f.keys());
        focusChip.setText("Showing " + f.keys().size() + (f.keys().size() == 1 ? " thread" : " threads")
                + " of: " + shorten(f.title(), 60) + "  ✕");
        focusChip.setVisible(true);
        views.setSelectedIndex(0);
        refilter();
        if (!f.keys().isEmpty()) {
            selectKey(f.keys().get(0));
        }
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Icon iconFor(ThreadAnalysis.Finding f) {
        Color color = switch (f.severity()) {
            case ERROR -> RED;
            case WARN -> AMBER;
            case INFO -> BLUE;
            case OK -> GREEN;
        };
        String name = switch (f.kind()) {
            case DEADLOCK, CONTENTION -> "lock";
            case STUCK -> "timer";
            case HOT -> "cpu";
            case BUSY_POOL -> "gauge";
            case GROWTH -> "activity";
            case CALM -> "circle-check";
        };
        return Icons.of(name, color, 20);
    }

    // ---------------------------------------------------------------- the threads view

    private JPanel threadsPanel() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        group.setMaximumSize(group.getPreferredSize());
        stateFilter.setMaximumSize(stateFilter.getPreferredSize());
        group.setSelectedIndex(AppState.prefs().getInt("threads.group", 1));
        group.addActionListener(e -> {
            AppState.prefs().putInt("threads.group", group.getSelectedIndex());
            refilter();
        });
        stateFilter.addActionListener(e -> refilter());
        search.putClientProperty("JTextField.placeholderText", "search names and stacks");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setMaximumSize(new Dimension(260, 30));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilterAll();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilterAll();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        jvmThreads.setToolTipText("Show the JVM's own threads (GC, compiler, VM threads)");
        jvmThreads.addActionListener(e -> refilterAll());
        focusChip.putClientProperty("JButton.buttonType", "roundRect");
        focusChip.setForeground(Theme.marker());
        focusChip.setFocusable(false);
        focusChip.setToolTipText("Show all threads again");
        focusChip.setVisible(false);
        focusChip.addActionListener(e -> clearFocus());
        bar.add(group);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(stateFilter);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(search);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(jvmThreads);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(focusChip);
        bar.add(Box.createHorizontalGlue());

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setRowHeight(table.getRowHeight() + 3);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateColumnsFromModel(false);
        int[] widths = {230, 120, 70, 330, 260};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new StateRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        table.getColumnModel().getColumn(3).setCellRenderer(new MonoRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
                inLog.setEnabled(selectedThread() != null);
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger() || showInLog == null) {
                    return;
                }
                int r = table.rowAtPoint(e.getPoint());
                if (r < 0 || r >= rows.size() || rows.get(r).thread() == null) {
                    return;
                }
                table.getSelectionModel().setSelectionInterval(r, r);
                String name = rows.get(r).thread().name();
                javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
                javax.swing.JMenuItem mi = new javax.swing.JMenuItem("Show Lines of “" + name + "” in " + logName,
                        Icons.of("file-text"));
                mi.addActionListener(ev -> showInLog.accept(name));
                m.add(mi);
                m.show(table, e.getX(), e.getY());
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
                if (e.isPopupTrigger()) {
                    return;
                }
                int r = table.rowAtPoint(e.getPoint());
                if (r >= 0 && r < rows.size() && rows.get(r).thread() == null) {
                    toggle(rows.get(r).group());
                }
            }
        });
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = table.columnAtPoint(e.getPoint());
                if (col >= 0) {
                    sortDown = col == sortColumn ? !sortDown : col == 2;
                    sortColumn = col;
                    for (int i = 0; i < table.getColumnCount(); i++) {
                        table.getColumnModel().getColumn(i).setHeaderValue(model.getColumnName(i));
                    }
                    table.getTableHeader().repaint();
                    refilter();
                }
            }
        });
        table.getTableHeader().setToolTipText("Click to sort");
        JScrollPane tableScroll = new JScrollPane(table);
        JScrollPane stackScroll = new JScrollPane(stack);
        // the stack's long lines must not take the list's room
        tableScroll.setPreferredSize(new Dimension(760, 300));
        stackScroll.setPreferredSize(new Dimension(520, 300));
        stackScroll.setMinimumSize(new Dimension(200, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, stackScroll);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void clearFocus() {
        focus = null;
        focusChip.setVisible(false);
        refilter();
    }

    private void toggle(ThreadAnalysis.Group g) {
        String key = g.name();
        if (!expanded.remove(key)) {
            expanded.add(key);
        }
        refilter();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).thread() == null && rows.get(i).group().name().equals(key)) {
                table.getSelectionModel().setSelectionInterval(i, i);
                break;
            }
        }
    }

    private void refilterAll() {
        refilter();
        refreshTimeline();
        refreshCompare();
    }

    private boolean shown(JThread t) {
        if (focus != null && !focus.contains(t.key())) {
            return false;
        }
        if (!jvmThreads.isSelected() && t.isJvmInternal() && focus == null) {
            return false;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty() && !matches(t, q)) {
            return false;
        }
        return switch (stateFilter.getSelectedIndex()) {
            case 1 -> t.state() == JThread.State.RUNNABLE;
            case 2 -> t.state() == JThread.State.BLOCKED;
            case 3 -> t.state() == JThread.State.WAITING || t.state() == JThread.State.TIMED_WAITING;
            case 4 -> !t.isIdle();
            default -> true;
        };
    }

    private static boolean matches(JThread t, String q) {
        if (t.name().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (JThread.Frame f : t.frames()) {
            if (f.method().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** One row: a group header (thread null) or a thread (group null outside groups). */
    private record Row(ThreadAnalysis.Group group, JThread thread, int shown) {}

    private void refilter() {
        String keep = selectedKey();
        rows.clear();
        if (current >= 0) {
            ThreadDump d = dumps.get(current);
            int mode = group.getSelectedIndex();
            if (mode == 0 || focus != null && focus.size() <= 12) {
                for (JThread t : sorted(d.threads())) {
                    if (shown(t)) {
                        rows.add(new Row(null, t, 1));
                    }
                }
            } else {
                List<ThreadAnalysis.Group> groups = mode == 1 ? ThreadAnalysis.families(d) : ThreadAnalysis.sameStacks(d);
                for (ThreadAnalysis.Group g : groups) {
                    List<JThread> ts = sorted(g.threads().stream().filter(this::shown).toList());
                    if (ts.isEmpty()) {
                        continue;
                    }
                    if (ts.size() == 1) {
                        rows.add(new Row(null, ts.get(0), 1));
                        continue;
                    }
                    rows.add(new Row(g, null, ts.size()));
                    if (expanded.contains(g.name())) {
                        for (JThread t : ts) {
                            rows.add(new Row(g, t, 1));
                        }
                    }
                }
            }
        }
        model.fireTableDataChanged();
        if (keep != null) {
            selectShown(keep);
        }
    }

    /** Selects a thread if it is in the list as it is (no filters change). */
    private boolean selectShown(String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).thread() != null && rows.get(i).thread().key().equals(key)) {
                table.getSelectionModel().setSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return true;
            }
        }
        return false;
    }

    private List<JThread> sorted(List<JThread> ts) {
        if (sortColumn < 0) {
            return ts;
        }
        Comparator<JThread> c = switch (sortColumn) {
            case 0 -> Comparator.comparing(t -> t.displayName().toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingInt(t -> stateOrder(t.state()));
            case 2 -> Comparator.comparingDouble(JThread::cpuMs);
            case 3 -> Comparator.comparing(t -> where(t));
            default -> Comparator.comparing(t -> lockText(t));
        };
        List<JThread> out = new ArrayList<>(ts);
        out.sort(sortDown ? c.reversed() : c);
        return out;
    }

    private static int stateOrder(JThread.State s) {
        return switch (s) {
            case BLOCKED -> 0;
            case RUNNABLE -> 1;
            case WAITING -> 2;
            case TIMED_WAITING -> 3;
            default -> 4;
        };
    }

    private String selectedKey() {
        int r = table.getSelectedRow();
        if (r >= 0 && r < rows.size() && rows.get(r).thread() != null) {
            return rows.get(r).thread().key();
        }
        return null;
    }

    /** Selects a thread in the list (opening its group, clearing filters that hide it). */
    void selectKey(String key) {
        if (current < 0) {
            return;
        }
        JThread t = dumps.get(current).find(key);
        if (t == null) {
            return;
        }
        if (views.getSelectedIndex() == 2) {
            views.setSelectedIndex(0);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).thread() != null && rows.get(i).thread().key().equals(key)) {
                    table.getSelectionModel().setSelectionInterval(i, i);
                    table.scrollRectToVisible(table.getCellRect(i, 0, true));
                    stack.show(dumps.get(current), t);
                    return;
                }
            }
            // not shown: open its group, or show everything
            if (pass == 0) {
                boolean opened = false;
                for (Row r : rows) {
                    if (r.thread() == null && r.group().threads().contains(t)) {
                        expanded.add(r.group().name());
                        opened = true;
                    }
                }
                if (!opened) {
                    if (focus != null && !focus.contains(key)) {
                        focus = null;
                        focusChip.setVisible(false);
                    }
                    stateFilter.setSelectedIndex(0);
                    if (t.isJvmInternal()) {
                        jvmThreads.setSelected(true);
                    }
                    if (!search.getText().isEmpty() && !matches(t, search.getText().trim().toLowerCase(Locale.ROOT))) {
                        search.setText("");
                    }
                }
                refilter();
            }
        }
        stack.show(dumps.get(current), t);
    }

    private void showSelected() {
        int r = table.getSelectedRow();
        if (current < 0 || r < 0 || r >= rows.size()) {
            return;
        }
        Row row = rows.get(r);
        if (row.thread() != null) {
            stack.show(dumps.get(current), row.thread());
        } else {
            stack.show(dumps.get(current), row.group().threads().get(0));
        }
    }

    private static String where(JThread t) {
        JThread.Frame f = t.appFrame();
        return f == null ? "" : f.shortMethod() + "(" + f.location() + ")";
    }

    private String lockText(JThread t) {
        JThread.Lock w = t.blockedOn();
        ThreadDump d = current < 0 ? null : dumps.get(current);
        if (w != null && (t.state() == JThread.State.BLOCKED || d != null && d.holderOf(w) != null)) {
            JThread h = d == null ? null : d.holderOf(w);
            return "waits for " + w.shortType() + (h == null || h == t ? "" : " (held by " + h.displayName() + ")");
        }
        List<JThread.Lock> held = t.held();
        if (!held.isEmpty() && d != null) {
            int waiting = 0;
            for (JThread.Lock l : held) {
                for (JThread o : d.threads()) {
                    if (o != t && l.equals(o.blockedOn())) {
                        waiting++;
                    }
                }
            }
            if (waiting > 0) {
                return "holds " + held.get(0).shortType() + " · " + waiting + " waiting";
            }
        }
        return "";
    }

    private final class RowModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int c) {
            String name = new String[] {"Thread", "State", "CPU", "Where", "Lock"}[c];
            return c == sortColumn ? name + (sortDown ? " ▼" : " ▲") : name;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            JThread t = row.thread();
            if (t == null) {
                List<JThread> ts = row.group().threads().stream().filter(ThreadsTab.this::shown).toList();
                return switch (c) {
                    case 0 -> row;
                    case 1 -> states(ts);
                    case 2 -> {
                        double sum = ts.stream().mapToDouble(JThread::cpuMs).filter(v -> v >= 0).sum();
                        yield sum > 0 ? ThreadStackView.cpu(sum) : "";
                    }
                    case 3 -> group.getSelectedIndex() == 2 ? "" : commonWhere(ts);
                    default -> "";
                };
            }
            return switch (c) {
                case 0 -> row;
                case 1 -> t;
                case 2 -> t.cpuMs() < 0 ? "" : ThreadStackView.cpu(t.cpuMs());
                case 3 -> where(t);
                default -> lockText(t);
            };
        }
    }

    private static String stateWhere(JThread t) {
        String w = where(t);
        return t.state().label() + (w.isEmpty() ? "" : " · " + w);
    }

    private static String commonWhere(List<JThread> ts) {
        String w = ts.isEmpty() ? "" : where(ts.get(0));
        for (JThread t : ts) {
            if (!where(t).equals(w)) {
                return "(different places)";
            }
        }
        return w;
    }

    private static String states(List<JThread> ts) {
        int run = 0;
        int blocked = 0;
        int waiting = 0;
        for (JThread t : ts) {
            switch (t.state()) {
                case RUNNABLE -> run++;
                case BLOCKED -> blocked++;
                case WAITING, TIMED_WAITING -> waiting++;
                default -> { }
            }
        }
        List<String> parts = new ArrayList<>();
        if (blocked > 0) {
            parts.add(blocked + " blocked");
        }
        if (run > 0) {
            parts.add(run + " runnable");
        }
        if (waiting > 0) {
            parts.add(waiting + " waiting");
        }
        return String.join(", ", parts);
    }

    /** Thread names, group headers with ▸/▾ and a count; idle threads dimmed. */
    private final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus0,
                                                       int row, int col) {
            Row r = (Row) v;
            String text;
            if (r.thread() == null) {
                text = (expanded.contains(r.group().name()) ? "▾ " : "▸ ") + r.group().name() + "  (" + r.shown() + ")";
            } else {
                text = (r.group() != null ? "      " : "") + r.thread().displayName();
            }
            super.getTableCellRendererComponent(t, text, sel, focus0, row, col);
            setFont(getFont().deriveFont(r.thread() == null ? Font.BOLD : Font.PLAIN));
            if (!sel) {
                setForeground(r.thread() == null ? Theme.groupHeaderForeground()
                        : r.thread().isIdle() ? Theme.gutterForeground() : t.getForeground());
            }
            setToolTipText(r.thread() == null ? "Click to open or close the group" : r.thread().name());
            return this;
        }
    }

    private static final class StateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus0,
                                                       int row, int col) {
            if (v instanceof JThread th) {
                super.getTableCellRendererComponent(t, th.state().label(), sel, focus0, row, col);
                setFont(getFont().deriveFont(Font.BOLD));
                if (!sel) {
                    setForeground(ThreadStackView.stateColor(th.state()));
                }
                setToolTipText(th.stateDetail().isEmpty() ? null : th.stateDetail());
            } else {
                super.getTableCellRendererComponent(t, v, sel, focus0, row, col);
                setFont(getFont().deriveFont(Font.PLAIN));
                if (!sel) {
                    setForeground(t.getForeground());
                }
                setToolTipText(null);
            }
            return this;
        }
    }

    private static final class MonoRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus0,
                                                       int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus0, row, col);
            setFont(Theme.monoFont(t.getFont().getSize2D() - 1));
            setToolTipText(v == null || v.toString().isEmpty() ? null : v.toString());
            return this;
        }
    }

    // ---------------------------------------------------------------- timeline

    private JPanel timelinePanel() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        busyOnly.setToolTipText("Leave out threads that were idle (waiting for work) in every dump");
        busyOnly.addActionListener(e -> refreshTimeline());
        bar.add(busyOnly);
        JLabel legend = new JLabel("<html><span style='color:" + ThreadStackView.hex(ThreadStackView.stateColor(JThread.State.RUNNABLE))
                + "'>■</span> runnable &nbsp; <span style='color:" + ThreadStackView.hex(ThreadStackView.stateColor(JThread.State.BLOCKED))
                + "'>■</span> blocked &nbsp; <span style='color:" + ThreadStackView.hex(ThreadStackView.stateColor(JThread.State.WAITING))
                + "'>■</span> waiting &nbsp; <span style='color:" + ThreadStackView.hex(ThreadStackView.stateColor(JThread.State.TIMED_WAITING))
                + "'>■</span> timed waiting &nbsp; (faded: idle; a joined bar: the same stack as before)</html>");
        legend.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        bar.add(legend);
        JScrollPane scroll = new JScrollPane(timeline);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, new JScrollPane(timelineStack));
        split.setResizeWeight(0.6);
        split.setBorder(null);
        timelineStack.clear("Click a cell to see that thread's stack in that dump.");
        JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void refreshTimeline() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (dumps.size() > 1) {
            for (ThreadDump d : dumps) {
                for (JThread t : d.threads()) {
                    if (keys.contains(t.key())) {
                        continue;
                    }
                    if (!jvmThreads.isSelected() && t.isJvmInternal()) {
                        continue;
                    }
                    String q = search.getText().trim().toLowerCase(Locale.ROOT);
                    if (!q.isEmpty() && !matches(t, q)) {
                        continue;
                    }
                    if (busyOnly.isSelected() && !busyInAny(t.key())) {
                        continue;
                    }
                    keys.add(t.key());
                }
            }
        }
        timeline.setData(dumps, new ArrayList<>(keys));
    }

    private boolean busyInAny(String key) {
        for (ThreadDump d : dumps) {
            JThread t = d.find(key);
            if (t != null && !t.isIdle() && !t.isJvmInternal()) {
                return true;
            }
        }
        return false;
    }

    ThreadTimeline timelineForTest() {
        return timeline;
    }

    // ---------------------------------------------------------------- compare

    private JPanel comparePanel() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(new JLabel("From"));
        bar.add(cmpA);
        bar.add(new JLabel("to"));
        bar.add(cmpB);
        bar.add(Box.createHorizontalStrut(8));
        onlyChanges.setToolTipText("Leave out threads doing the same thing in both dumps");
        onlyChanges.addActionListener(e -> refreshCompare());
        bar.add(onlyChanges);
        bar.add(Box.createHorizontalStrut(8));
        cmpSummary.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        bar.add(cmpSummary);
        cmpA.addActionListener(e -> refreshCompare());
        cmpB.addActionListener(e -> refreshCompare());
        cmpTable.setFillsViewportHeight(true);
        cmpTable.setShowGrid(false);
        cmpTable.setRowHeight(cmpTable.getRowHeight() + 3);
        cmpTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {80, 220, 300, 300};
        for (int i = 0; i < widths.length; i++) {
            cmpTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        cmpTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f,
                                                           int row, int col) {
                ThreadAnalysis.ChangeKind k = (ThreadAnalysis.ChangeKind) v;
                super.getTableCellRendererComponent(t, k == null ? "" : k.name().charAt(0)
                        + k.name().substring(1).toLowerCase(Locale.ROOT), sel, f, row, col);
                setFont(getFont().deriveFont(Font.BOLD));
                if (!sel && k != null) {
                    setForeground(switch (k) {
                        case NEW -> GREEN;
                        case GONE -> Theme.gutterForeground();
                        case CHANGED -> AMBER;
                        case SAME -> Theme.gutterForeground();
                    });
                }
                return this;
            }
        });
        cmpTable.getColumnModel().getColumn(2).setCellRenderer(new MonoRenderer());
        cmpTable.getColumnModel().getColumn(3).setCellRenderer(new MonoRenderer());
        cmpTable.getSelectionModel().addListSelectionListener(e -> {
            int r = cmpTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && r >= 0 && r < changes.size()) {
                ThreadAnalysis.Change c = changes.get(r);
                int b = cmpB.getSelectedIndex();
                int a = cmpA.getSelectedIndex();
                if (c.after() != null && b >= 0) {
                    cmpStack.show(dumps.get(b), c.after());
                } else if (a >= 0) {
                    cmpStack.show(dumps.get(a), c.before());
                }
            }
        });
        JScrollPane cmpScroll = new JScrollPane(cmpTable);
        JScrollPane cmpStackScroll = new JScrollPane(cmpStack);
        cmpScroll.setPreferredSize(new Dimension(760, 300));
        cmpStackScroll.setPreferredSize(new Dimension(520, 300));
        cmpStackScroll.setMinimumSize(new Dimension(200, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cmpScroll, cmpStackScroll);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        cmpStack.clear("Click a thread to see its stack (in the later dump).");
        JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void refreshCompare() {
        changes.clear();
        int a = cmpA.getSelectedIndex();
        int b = cmpB.getSelectedIndex();
        if (a >= 0 && b >= 0 && a < dumps.size() && b < dumps.size() && a != b) {
            int news = 0;
            int gone = 0;
            int changed0 = 0;
            int sameCount = 0;
            for (ThreadAnalysis.Change c : ThreadAnalysis.compare(dumps.get(a), dumps.get(b))) {
                JThread t = c.thread();
                if (!jvmThreads.isSelected() && t.isJvmInternal()) {
                    continue;
                }
                String q = search.getText().trim().toLowerCase(Locale.ROOT);
                if (!q.isEmpty() && !matches(t, q)) {
                    continue;
                }
                int same = 0;
                switch (c.kind()) {
                    case NEW -> news++;
                    case GONE -> gone++;
                    case CHANGED -> changed0++;
                    default -> same = 1;
                }
                sameCount += same;
                if (same == 0 || !onlyChanges.isSelected()) {
                    changes.add(c);
                }
            }
            cmpSummary.setText(news + " new · " + gone + " gone · " + changed0 + " doing something else · "
                    + sameCount + " the same" + (news + gone + changed0 == 0 ? ": nothing changed" : ""));
        } else {
            cmpSummary.setText(dumps.size() < 2 ? "Take or open several dumps to compare them." : "Choose two different dumps.");
        }
        cmpModel.fireTableDataChanged();
    }

    void showUnchangedForTest(boolean on) {
        onlyChanges.setSelected(!on);
        refreshCompare();
    }

    String compareSummaryForTest() {
        return cmpSummary.getText();
    }

    List<ThreadAnalysis.Change> changesForTest() {
        return List.copyOf(changes);
    }

    private final class CompareModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return changes.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Change", "Thread", "Before", "After"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            ThreadAnalysis.Change ch = changes.get(r);
            return switch (c) {
                case 0 -> ch.kind();
                case 1 -> ch.thread().displayName();
                case 2 -> ch.before() == null ? "" : stateWhere(ch.before());
                default -> ch.after() == null ? "" : stateWhere(ch.after());
            };
        }
    }

    // ---------------------------------------------------------------- test access

    JButton profileButtonForTest() {
        return profileButton;
    }

    JTable tableForTest() {
        return table;
    }

    JPanel findingsForTest() {
        return findings;
    }

    ThreadStackView stackForTest() {
        return stack;
    }

    JTabbedPane viewsForTest() {
        return views;
    }

    JComboBox<String> groupForTest() {
        return group;
    }

    /** Rows that fill the width and scroll down. */
    private static final class InsightsListPanel extends JPanel implements javax.swing.Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(16, o == SwingConstants.VERTICAL ? r.height - 16 : r.width - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
