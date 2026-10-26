package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.jvm.Profile;
import logsift.jvm.Profiler;

/**
 * Where a JVM spends its time, memory or lock waits: choose what to measure and for how long,
 * then read the flame graph and the methods with the most of it. Each run is kept, so one can be
 * compared with another (red: grew, blue: shrank). Right-click a frame to see who calls it, or
 * only (or none of) the stacks through it.
 */
final class ProfileTab extends JPanel {
    private final Profiler.Target target;
    private final String source;
    private Runnable changed = () -> { };

    // ---- what to measure
    private final JComboBox<Profiler.Engine> engine = new JComboBox<>();
    private final JComboBox<Profiler.Mode> mode = new JComboBox<>();
    private final JTextField method = new JTextField(AppState.prefs().get("profile.method", ""), 22);
    private final JSpinner latency = new JSpinner(new SpinnerNumberModel(AppState.prefs().getInt("profile.latency", 50),
            0, 600_000, 10));
    private final JLabel latencyLabel = new JLabel("ms or slower");
    private final JComboBox<String> hardware = new JComboBox<>();
    private final JSpinner seconds = new JSpinner(new SpinnerNumberModel(
            AppState.prefs().getInt("profile.seconds", 30), 2, 3600, 5));
    private final JCheckBox perThread = new JCheckBox("Per thread");
    private final JButton start = new JButton("Start Profiling", Icons.of("flame"));
    private final JButton stop = new JButton("Stop", Icons.of("square"));
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    private final JLabel about = new JLabel(" ");
    private List<Profiler.Mode> asyncModes = List.of();

    // ---- the result
    private record Run(String label, List<Profile> views) {}

    private final List<Run> runs = new ArrayList<>();
    private final JComboBox<String> runBox = new JComboBox<>();
    private final JComboBox<String> viewBox = new JComboBox<>();
    private final JLabel compareLabel = new JLabel("Compare with");
    private final JComboBox<String> compareBox = new JComboBox<>();
    private final List<Profile> compareTargets = new ArrayList<>();
    private final JToggleButton callers = new JToggleButton("Callers", Icons.of("arrow-up-down"));
    private final JButton save = new JButton("Save…", Icons.of("save"));
    private final JLabel title = new JLabel(" ");
    private final JLabel warnings = new JLabel();
    private final JTextField search = new JTextField(16);
    private final JLabel matched = new JLabel(" ");
    private final JButton resetZoom = new JButton("Show All");
    private final JButton focusChip = new JButton();
    private final FlameGraph graph = new FlameGraph();
    private final List<Profile.Method> methods = new ArrayList<>();
    private final MethodModel methodModel = new MethodModel();
    private final JTable methodTable = new JTable(methodModel);
    private final JLabel empty = new JLabel();
    private final JPanel center = new JPanel(new CardLayout());
    private volatile AtomicBoolean running;

    private enum Focus { NONE, ONLY, WITHOUT, CALLERS_OF }

    private Focus focus = Focus.NONE;
    private String focusFrame;
    private Profile base;        // the view shown, before focus and callers
    private boolean filling;     // combos are being filled: ignore their events

    /** Profiles {@code target} on request (null: shows {@code opened}, read from a file). */
    ProfileTab(String source, Profiler.Target target, List<Profile> opened) {
        super(new BorderLayout());
        this.source = source;
        this.target = target;

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        if (target != null) {
            top.add(controls());
            about.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            about.setBorder(BorderFactory.createEmptyBorder(0, 12, 2, 10));
            about.setAlignmentX(LEFT_ALIGNMENT);
            top.add(about);
            loadChoices();
        }
        top.add(resultBar());
        top.add(toolsBar());
        warnings.setForeground(new Color(0xB45309));
        warnings.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
        warnings.setVisible(false);
        warnings.setAlignmentX(LEFT_ALIGNMENT);
        top.add(warnings);
        add(top, BorderLayout.NORTH);

        graph.onZoom(n -> {
            resetZoom.setVisible(n != graph.profile().root());
            highlight();
        });
        graph.onPopup(this::framePopup);
        JScrollPane graphScroll = new JScrollPane(graph);
        graphScroll.getVerticalScrollBar().setUnitIncrement(18);
        graphScroll.setPreferredSize(new Dimension(900, 300));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphScroll, methodsPanel());
        split.setResizeWeight(0.72);
        split.setBorder(null);

        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        empty.setText("<html><div style='text-align:center'>Choose what to measure and click <b>Start Profiling</b>.<br><br>"
                + "The JVM keeps running as usual; profiling costs it a few percent of CPU.<br>"
                + "Every run stays here, to compare one with another.</div></html>");
        center.add(empty, "empty");
        center.add(split, "graph");
        add(center, BorderLayout.CENTER);
        if (opened != null) {
            addRun(source, opened);
        } else {
            ((CardLayout) center.getLayout()).show(center, "empty");
        }
    }

    private JPanel controls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        engine.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v == null ? "" : ((Profiler.Engine) v).label, i, s, f);
            }
        });
        engine.setToolTipText("async-profiler sees native code and the kernel too; Flight Recorder is built into the JVM");
        engine.addActionListener(e -> {
            if (!filling) {
                fillModes();
            }
        });
        mode.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                Profiler.Mode m = (Profiler.Mode) v;
                super.getListCellRendererComponent(l, m == null ? "" : m.label, i, s, f);
                setToolTipText(m == null ? null : m.description);
                return this;
            }
        });
        mode.setMaximumRowCount(14);
        mode.addActionListener(e -> modeChanged());
        method.putClientProperty("JTextField.placeholderText", "com.acme.Api.handle (* for any)");
        method.setToolTipText("<html>The method: package.Class.method. A * matches anything:<br>"
                + "java.io.FileInputStream.read* or com.acme.Api.*</html>");
        hardware.setToolTipText("The hardware or kernel event to count (Linux)");
        perThread.setToolTipText("A flame graph per thread: each thread's name on top");
        start.putClientProperty("JButton.buttonType", "default");
        start.addActionListener(e -> startProfiling());
        start.setEnabled(false);
        stop.addActionListener(e -> {
            AtomicBoolean r = running;
            if (r != null) {
                r.set(true);
                status.setText("Stopping…");
            }
        });
        stop.setVisible(false);
        bar.setPreferredSize(new Dimension(140, 12));
        bar.setVisible(false);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        controls.add(new JLabel("Measure"));
        controls.add(mode);
        controls.add(method);
        controls.add(latency);
        controls.add(latencyLabel);
        controls.add(hardware);
        controls.add(new JLabel("for"));
        controls.add(seconds);
        controls.add(new JLabel("seconds with"));
        controls.add(engine);
        controls.add(perThread);
        controls.add(start);
        controls.add(stop);
        controls.add(bar);
        controls.add(status);
        controls.setAlignmentX(LEFT_ALIGNMENT);
        method.setVisible(false);
        latency.setVisible(false);
        latencyLabel.setVisible(false);
        hardware.setVisible(false);
        return controls;
    }

    private JPanel resultBar() {
        JPanel info = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        runBox.setToolTipText("The runs in this tab");
        runBox.addActionListener(e -> {
            if (!filling) {
                showRun(runBox.getSelectedIndex(), 0);
            }
        });
        viewBox.setToolTipText("What this run measured");
        viewBox.addActionListener(e -> {
            if (!filling) {
                showView();
            }
        });
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        left.add(runBox);
        left.add(viewBox);
        left.add(title);
        info.add(left, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        compareBox.setToolTipText("Color frames by how their share changed since another run: red grew, blue shrank");
        compareBox.addActionListener(e -> {
            if (!filling) {
                refreshGraph();
            }
        });
        right.add(compareLabel);
        right.add(compareBox);
        save.putClientProperty("JButton.buttonType", "toolBarButton");
        save.setToolTipText("Save what is shown (collapsed stacks; it opens again here, and in other flame graph tools)");
        save.addActionListener(e -> saveDialog());
        save.setEnabled(false);
        right.add(save);
        info.add(right, BorderLayout.EAST);
        info.setAlignmentX(LEFT_ALIGNMENT);
        runBox.setVisible(false);
        viewBox.setVisible(false);
        compareLabel.setVisible(false);
        compareBox.setVisible(false);
        return info;
    }

    private JPanel toolsBar() {
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        callers.putClientProperty("JButton.buttonType", "toolBarButton");
        callers.setToolTipText("Turn the graph upside down: where the time is first, then who called it");
        callers.addActionListener(e -> refreshGraph());
        search.putClientProperty("JTextField.placeholderText", "highlight methods");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                highlight();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                highlight();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        focusChip.putClientProperty("JButton.buttonType", "roundRect");
        focusChip.setForeground(Theme.marker());
        focusChip.setFocusable(false);
        focusChip.setToolTipText("Show all stacks again");
        focusChip.setVisible(false);
        focusChip.addActionListener(e -> setFocus(Focus.NONE, null));
        resetZoom.putClientProperty("JButton.buttonType", "toolBarButton");
        resetZoom.addActionListener(e -> graph.zoomTo(null));
        resetZoom.setVisible(false);
        tools.add(callers);
        tools.add(search);
        tools.add(matched);
        tools.add(focusChip);
        tools.add(resetZoom);
        tools.setAlignmentX(LEFT_ALIGNMENT);
        return tools;
    }

    private JPanel methodsPanel() {
        methodTable.setFillsViewportHeight(true);
        methodTable.setShowGrid(false);
        methodTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        methodTable.setRowHeight(methodTable.getRowHeight() + 3);
        setUpMethodColumns();
        methodTable.getSelectionModel().addListSelectionListener(e -> {
            int r = methodTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && r >= 0 && r < methods.size()) {
                search.setText("");
                graph.setExact(methods.get(r).name());
                showMatched();
            }
        });
        methodTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                popup(e);
            }

            private void popup(java.awt.event.MouseEvent e) {
                int r = methodTable.rowAtPoint(e.getPoint());
                if (e.isPopupTrigger() && r >= 0 && r < methods.size()) {
                    methodTable.getSelectionModel().setSelectionInterval(r, r);
                    JPopupMenu m = new JPopupMenu();
                    addFrameItems(m, methods.get(r).name());
                    m.show(methodTable, e.getX(), e.getY());
                }
            }
        });
        JPanel side = new JPanel(new BorderLayout());
        JLabel sideTitle = new JLabel("Most of it on their own");
        sideTitle.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        sideTitle.setFont(sideTitle.getFont().deriveFont(Font.BOLD));
        side.add(sideTitle, BorderLayout.NORTH);
        side.add(new JScrollPane(methodTable), BorderLayout.CENTER);
        side.setPreferredSize(new Dimension(400, 300));
        return side;
    }

    // ---------------------------------------------------------------- what can be measured

    /** Finds out (in the background: it may ask the server) which engines and modes work. */
    private void loadChoices() {
        status.setText("Checking what can be measured…");
        Thread t = new Thread(() -> {
            List<Profiler.Engine> engines = target.engines();
            boolean ap = engines.contains(Profiler.Engine.ASYNC_PROFILER);
            // hardware events are asked for only when that mode is chosen: asking loads async-profiler
            // into the JVM, which opening this tab shouldn't do
            List<String> hw = List.of();
            List<Profiler.Mode> apModes = ap ? target.modes(Profiler.Engine.ASYNC_PROFILER) : List.of();
            SwingUtilities.invokeLater(() -> {
                asyncModes = apModes;
                filling = true;
                engine.removeAllItems();
                engines.forEach(engine::addItem);
                hardware.setModel(new DefaultComboBoxModel<>(hw.toArray(String[]::new)));
                String lastHw = AppState.prefs().get("profile.hardware", "cache-misses");
                if (hw.contains(lastHw)) {
                    hardware.setSelectedItem(lastHw);
                }
                filling = false;
                fillModes();
                status.setText(" ");
                start.setEnabled(true);
            });
        }, "logsift-profile-choices");
        t.setDaemon(true);
        t.start();
    }

    private void fillModes() {
        Profiler.Engine e = (Profiler.Engine) engine.getSelectedItem();
        if (e == null) {
            return;
        }
        Profiler.Mode was = (Profiler.Mode) mode.getSelectedItem();
        List<Profiler.Mode> modes = e == Profiler.Engine.ASYNC_PROFILER ? asyncModes : target.modes(e);
        filling = true;
        mode.setModel(new DefaultComboBoxModel<>(modes.toArray(Profiler.Mode[]::new)));
        String last = AppState.prefs().get("profile.modeName", Profiler.Mode.CPU.name());
        Profiler.Mode pick = was != null && modes.contains(was) ? was
                : modes.stream().filter(m -> m.name().equals(last)).findFirst().orElse(modes.get(0));
        mode.setSelectedItem(pick);
        filling = false;
        modeChanged();
    }

    private void modeChanged() {
        if (filling) {
            return;
        }
        Profiler.Mode m = (Profiler.Mode) mode.getSelectedItem();
        boolean methodMode = m == Profiler.Mode.METHOD_CALLS || m == Profiler.Mode.METHOD_LATENCY;
        method.setVisible(methodMode);
        latency.setVisible(m == Profiler.Mode.METHOD_LATENCY);
        latencyLabel.setVisible(m == Profiler.Mode.METHOD_LATENCY);
        hardware.setVisible(m == Profiler.Mode.HARDWARE);
        if (m == Profiler.Mode.HARDWARE && hardware.getItemCount() == 0 && !hardwareLoading) {
            loadHardware();
        }
        Profiler.Engine e = (Profiler.Engine) engine.getSelectedItem();
        about.setText(m == null ? " " : "<html>" + m.description + note(m) + "<br><span style='color:#b45309'>"
                + cost(m, e) + "</span></html>");
        revalidate();
    }

    private boolean hardwareLoading;

    /** The hardware events async-profiler offers for this JVM (it is loaded into the JVM to ask). */
    private void loadHardware() {
        hardwareLoading = true;
        Thread t = new Thread(() -> {
            List<String> hw = target.hardwareEvents();
            SwingUtilities.invokeLater(() -> {
                hardware.setModel(new DefaultComboBoxModel<>(hw.toArray(String[]::new)));
                String lastHw = AppState.prefs().get("profile.hardware", "cache-misses");
                if (hw.contains(lastHw)) {
                    hardware.setSelectedItem(lastHw);
                }
                hardwareLoading = false;
            });
        }, "logsift-profile-hardware");
        t.setDaemon(true);
        t.start();
    }

    /** What a run costs the JVM, said where it is chosen. */
    static String cost(Profiler.Mode m, Profiler.Engine e) {
        if (e == Profiler.Engine.JFR) {
            return "Cost to the JVM: about 2–3% CPU while it records; the first recording in a JVM starts Flight"
                    + " Recorder there.";
        }
        String agent = " async-profiler is loaded into the JVM for the first run and stays loaded until the JVM ends.";
        return "Cost to the JVM: " + switch (m) {
            case CPU, ALLOC, HARDWARE -> "light (about 1–3% CPU while it runs).";
            case WALL -> "light, more with many threads (every thread is sampled, idle ones too).";
            case LIVE -> "light, plus a little memory and GC work to follow the sampled objects.";
            case LOCK, NATIVE_LOCKS -> "light to moderate where threads fight over locks.";
            case NATIVE_MEMORY, NATIVE_LEAKS -> "every malloc in the process is recorded: noticeable where native code"
                    + " allocates a lot (TLS, compression, Netty). Better not on a busy production JVM without need.";
            case METHOD_CALLS, METHOD_LATENCY -> "every call of the method costs a little: a method called very often"
                    + " slows the program, and the JVM recompiles some code the first time.";
            case ALL -> "all of these at once, native memory too: heavier. Better not on a busy production JVM.";
        } + agent;
    }

    private static String note(Profiler.Mode m) {
        return switch (m) {
            case LIVE -> ". Run it while memory use climbs.";
            case NATIVE_LEAKS -> ". Allocations in the last tenth of the run are left out (they may simply not be freed yet).";
            case METHOD_CALLS, METHOD_LATENCY -> ". The method is instrumented during the run; the first time,"
                    + " the JVM recompiles some code.";
            case ALL -> ".";
            default -> ".";
        };
    }

    // ---------------------------------------------------------------- running

    String title() {
        return "Profile: " + source;
    }

    void onChanged(Runnable r) {
        changed = r;
    }

    String statusText() {
        Profile p = graph.profile();
        return p == null ? " " : p.title() + " · " + p.amount(p.total());
    }

    boolean isRunning() {
        return running != null;
    }

    /** The profile shown (after focus and callers). */
    Profile profile() {
        return graph.profile();
    }

    FlameGraph graphForTest() {
        return graph;
    }

    JTable methodsForTest() {
        return methodTable;
    }

    boolean choicesLoadedForTest() {
        return start.isEnabled() || running != null;
    }

    List<Profiler.Mode> modesForTest() {
        List<Profiler.Mode> out = new ArrayList<>();
        for (int i = 0; i < mode.getItemCount(); i++) {
            out.add(mode.getItemAt(i));
        }
        return out;
    }

    int viewCountForTest() {
        return viewBox.getItemCount();
    }

    int runCountForTest() {
        return runs.size();
    }

    void setForTest(Profiler.Mode m, Profiler.Engine e, int s) {
        if (e != null) {
            engine.setSelectedItem(e);
        }
        mode.setSelectedItem(m);
        seconds.setValue(s);
    }

    void setMethodForTest(String m, int ms) {
        method.setText(m);
        latency.setValue(ms);
    }

    void close() {
        AtomicBoolean r = running;
        if (r != null) {
            r.set(true);
        }
    }

    void startProfiling() {
        if (target == null || running != null || engine.getSelectedItem() == null) {
            return;
        }
        Profiler.Mode m = (Profiler.Mode) mode.getSelectedItem();
        Profiler.Request req = new Profiler.Request((Profiler.Engine) engine.getSelectedItem(), m,
                (Integer) seconds.getValue(), perThread.isSelected(), method.getText(), (Integer) latency.getValue(),
                hardware.getSelectedItem() == null ? "" : (String) hardware.getSelectedItem());
        String problem = req.problem();
        if (problem != null) {
            status.setForeground(new Color(0xDC2626));
            status.setText(problem);
            return;
        }
        AppState.prefs().putInt("profile.seconds", req.seconds());
        AppState.prefs().put("profile.modeName", m.name());
        AppState.prefs().put("profile.method", method.getText().trim());
        AppState.prefs().putInt("profile.latency", req.latencyMs());
        if (hardware.getSelectedItem() != null) {
            AppState.prefs().put("profile.hardware", (String) hardware.getSelectedItem());
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        start.setEnabled(false);
        stop.setVisible(true);
        bar.setValue(0);
        bar.setVisible(true);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText("Profiling for " + req.seconds() + " s…");
        long t0 = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            List<Profile> p = null;
            String error = null;
            try {
                p = target.run(req, f -> SwingUtilities.invokeLater(() -> {
                    bar.setValue((int) Math.round(f * 1000));
                    long left = Math.max(0, req.seconds() - (System.currentTimeMillis() - t0) / 1000);
                    if (running == cancel && !cancel.get()) {
                        status.setText(left > 0 ? "Profiling… " + left + " s left" : "Reading the results…");
                    }
                }), cancel::get);
            } catch (IOException | RuntimeException ex) {
                error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            List<Profile> done = p;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                running = null;
                start.setEnabled(true);
                stop.setVisible(false);
                bar.setVisible(false);
                if (failed != null) {
                    status.setForeground(new Color(0xDC2626));
                    status.setText("Could not profile: " + failed);
                } else if (done == null) {
                    status.setText("Stopped.");
                } else {
                    status.setText(" ");
                    addRun("Run " + (runs.size() + 1) + " · " + m.label + " · "
                            + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), done);
                }
                changed.run();
            });
        }, "logsift-profile");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- showing

    /** Adds a run (its views) and shows it. */
    void addRun(String label, List<Profile> views) {
        runs.add(new Run(label, List.copyOf(views)));
        filling = true;
        runBox.addItem(label);
        filling = false;
        runBox.setVisible(runs.size() > 1);
        showRun(runs.size() - 1, 0);
    }

    private static String kindOf(Profile p) {
        return p.title().replaceFirst(" of .*| in .*", "");
    }

    private void showRun(int run, int view) {
        if (run < 0 || run >= runs.size()) {
            return;
        }
        Run r = runs.get(run);
        filling = true;
        runBox.setSelectedIndex(run);
        viewBox.setModel(new DefaultComboBoxModel<>(r.views().stream().map(ProfileTab::kindOf).toArray(String[]::new)));
        viewBox.setSelectedIndex(Math.min(view, r.views().size() - 1));
        viewBox.setVisible(r.views().size() > 1);
        filling = false;
        showView();
    }

    private void showView() {
        int run = runBox.getSelectedIndex();
        int view = viewBox.getSelectedIndex();
        if (run < 0 || view < 0) {
            return;
        }
        base = runs.get(run).views().get(view);
        focus = Focus.NONE;
        focusFrame = null;
        fillCompare();
        refreshGraph();
    }

    /** The views of other runs that measured the same thing, to compare with. */
    private void fillCompare() {
        String kind = kindOf(base);
        List<String> items = new ArrayList<>();
        items.add("(nothing)");
        compareTargets.clear();
        int run = runBox.getSelectedIndex();
        for (int i = 0; i < runs.size(); i++) {
            if (i == run) {
                continue;
            }
            for (Profile p : runs.get(i).views()) {
                if (kindOf(p).equals(kind)) {
                    items.add(runs.get(i).label());
                    compareTargets.add(p);
                }
            }
        }
        filling = true;
        compareBox.setModel(new DefaultComboBoxModel<>(items.toArray(String[]::new)));
        filling = false;
        compareBox.setVisible(items.size() > 1);
        compareLabel.setVisible(items.size() > 1);
    }

    /** The profile as shown: with the focus and callers applied. */
    private Profile transform(Profile p) {
        Profile q = switch (focus) {
            case ONLY -> p.filtered(focusFrame, true);
            case WITHOUT -> p.filtered(focusFrame, false);
            case CALLERS_OF -> p.callersOf(focusFrame);
            default -> p;
        };
        return callers.isSelected() && focus != Focus.CALLERS_OF ? q.reversed() : q;
    }

    private void refreshGraph() {
        if (base == null) {
            return;
        }
        ((CardLayout) center.getLayout()).show(center, "graph");
        graph.setProfile(transform(base));
        int c = compareBox.getSelectedIndex();
        graph.setBaseline(c <= 0 || c - 1 >= compareTargets.size() ? null : transform(compareTargets.get(c - 1)));
        title.setText(base.title() + " · " + base.amount(base.total()));
        methods.clear();
        methods.addAll(base.hottest(300));
        methodModel.fireTableStructureChanged();
        setUpMethodColumns();
        save.setEnabled(true);
        if (base.warnings().isEmpty()) {
            warnings.setVisible(false);
        } else {
            warnings.setText("<html>" + String.join("<br>", base.warnings().stream().map(ThreadStackView::esc).toList())
                    + "</html>");
            warnings.setVisible(true);
        }
        if (base.total() == 0 && target != null) {
            status.setText("Nothing was recorded in that time.");
        }
        focusChip.setVisible(focus != Focus.NONE);
        if (focus != Focus.NONE) {
            String what = switch (focus) {
                case ONLY -> "Only stacks through ";
                case WITHOUT -> "Without stacks through ";
                default -> "Callers of ";
            };
            focusChip.setText(what + FlameGraph.shortName(focusFrame) + "  ✕");
        }
        callers.setEnabled(focus != Focus.CALLERS_OF);
        resetZoom.setVisible(false);
        highlight();
        changed.run();
    }

    private void setFocus(Focus f, String frame) {
        focus = frame == null ? Focus.NONE : f;
        focusFrame = frame;
        refreshGraph();
    }

    private void framePopup(Profile.Node n, java.awt.event.MouseEvent e) {
        if (n == graph.profile().root()) {
            return;
        }
        JPopupMenu m = new JPopupMenu();
        JMenuItem zoom = new JMenuItem("Zoom In", Icons.of("search"));
        zoom.addActionListener(a -> graph.zoomTo(n));
        m.add(zoom);
        m.addSeparator();
        addFrameItems(m, n.name());
        m.show(graph, e.getX(), e.getY());
    }

    private void addFrameItems(JPopupMenu m, String frame) {
        String shortName = FlameGraph.shortName(frame);
        JMenuItem callersOf = new JMenuItem("Callers of " + shortName, Icons.of("arrow-up-down"));
        callersOf.addActionListener(a -> setFocus(Focus.CALLERS_OF, frame));
        JMenuItem only = new JMenuItem("Only Stacks Through " + shortName, Icons.of("filter"));
        only.addActionListener(a -> setFocus(Focus.ONLY, frame));
        JMenuItem without = new JMenuItem("Hide Stacks Through " + shortName, Icons.of("eye-off"));
        without.addActionListener(a -> setFocus(Focus.WITHOUT, frame));
        JMenuItem hl = new JMenuItem("Highlight " + shortName, Icons.of("text-select"));
        hl.addActionListener(a -> {
            search.setText("");
            methodTable.clearSelection();
            graph.setExact(frame);
            showMatched();
        });
        JMenuItem copy = new JMenuItem("Copy Name", Icons.of("copy"));
        copy.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(frame), null));
        m.add(callersOf);
        m.add(only);
        m.add(without);
        m.add(hl);
        m.addSeparator();
        m.add(copy);
    }

    /** For tests: the same as the frame menu's items ("only", "without", "callers"; null: clear). */
    void focusForTest(String how, String frame) {
        setFocus(how == null ? Focus.NONE : switch (how) {
            case "only" -> Focus.ONLY;
            case "without" -> Focus.WITHOUT;
            default -> Focus.CALLERS_OF;
        }, frame);
    }

    void callersForTest(boolean on) {
        callers.setSelected(on);
        refreshGraph();
    }

    /** Compares with the n-th other run of the same kind (0: none). */
    void compareForTest(int n) {
        compareBox.setSelectedIndex(n);
    }

    private void highlight() {
        String q = search.getText();
        if (!q.isBlank()) {
            methodTable.clearSelection();
            graph.setQuery(q);
        } else if (methodTable.getSelectedRow() < 0) {
            graph.setQuery("");
        }
        showMatched();
    }

    private void showMatched() {
        boolean any = !search.getText().isBlank() || methodTable.getSelectedRow() >= 0;
        matched.setText(any ? FlameGraph.pct(Math.round(graph.matchedShare() * 1000), 1000) + " of what is shown" : " ");
    }

    private void saveDialog() {
        Profile p = graph.profile();
        if (p == null) {
            return;
        }
        JFileChooser ch = new JFileChooser();
        ch.setSelectedFile(new java.io.File("profile-" + source.replaceAll("[^A-Za-z0-9._-]+", "-") + "-"
                + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".collapsed.txt"));
        if (ch.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                save(ch.getSelectedFile().toPath());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Cannot save: " + e.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    void save(Path file) throws IOException {
        Files.writeString(file, graph.profile().toCollapsed(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- hot methods

    private void setUpMethodColumns() {
        if (methodTable.getColumnCount() == 0) {
            return;
        }
        methodTable.getColumnModel().getColumn(0).setPreferredWidth(250);
        DefaultTableCellRenderer pct = new DefaultTableCellRenderer();
        pct.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int i = 1; i < methodTable.getColumnCount(); i++) {
            methodTable.getColumnModel().getColumn(i).setPreferredWidth(60);
            methodTable.getColumnModel().getColumn(i).setCellRenderer(pct);
        }
        methodTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Profile.Method m = (Profile.Method) v;
                super.getTableCellRendererComponent(t, FlameGraph.shortName(m.name()), sel, f, r, c);
                setFont(Theme.monoFont(t.getFont().getSize2D() - 1));
                setToolTipText("<html>" + ThreadStackView.esc(m.name()) + "<br>" + FlameGraph.kindLabel(m.kind())
                        + "<br>Right-click for callers and filters</html>");
                return this;
            }
        });
    }

    private final class MethodModel extends AbstractTableModel {
        private boolean comparing() {
            return graph.baseline() != null && base != null;
        }

        @Override
        public int getRowCount() {
            return methods.size();
        }

        @Override
        public int getColumnCount() {
            return comparing() ? 4 : 3;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Method", "Self", "Total", "Change"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Profile.Method m = methods.get(r);
            long all = base == null ? 1 : base.total();
            return switch (c) {
                case 0 -> m;
                case 1 -> FlameGraph.pct(m.self(), all);
                case 2 -> FlameGraph.pct(m.total(), all);
                default -> change(m);
            };
        }

        private String change(Profile.Method m) {
            Profile before = graph.baseline();
            if (before == null) {
                return "";
            }
            long was = 0;
            for (Profile.Node n : before.find(m.name())) {
                was += n.self();
            }
            double now = 100.0 * m.self() / Math.max(1, base.total());
            double then = 100.0 * was / Math.max(1, before.total());
            return String.format(Locale.ROOT, "%+.1f", now - then);
        }
    }
}
