package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
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
import logsift.jvm.Profile;
import logsift.jvm.Profiler;

/**
 * Where a JVM spends its time: choose what to measure (CPU, wall clock, allocations, locks) and
 * for how long, then read the flame graph and the methods with the most time of their own.
 */
final class ProfileTab extends JPanel {
    private final Profiler.Target target;
    private final String source;
    private Runnable changed = () -> { };

    private final JComboBox<Profiler.Engine> engine = new JComboBox<>();
    private final JComboBox<Profiler.Mode> mode = new JComboBox<>(Profiler.Mode.values());
    private final JSpinner seconds = new JSpinner(new SpinnerNumberModel(
            AppState.prefs().getInt("profile.seconds", 30), 2, 3600, 5));
    private final JButton start = new JButton("Start Profiling", Icons.of("flame"));
    private final JButton stop = new JButton("Stop", Icons.of("square"));
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    private final JButton save = new JButton("Save…", Icons.of("save"));
    private final JLabel title = new JLabel(" ");
    private final JLabel warnings = new JLabel();
    private final JTextField search = new JTextField(18);
    private final JLabel matched = new JLabel(" ");
    private final JButton resetZoom = new JButton("Show All");
    private final FlameGraph graph = new FlameGraph();
    private final List<Profile.Method> methods = new ArrayList<>();
    private final MethodModel methodModel = new MethodModel();
    private final JTable methodTable = new JTable(methodModel);
    private final JLabel empty = new JLabel();
    private final JPanel center = new JPanel(new java.awt.CardLayout());
    private volatile AtomicBoolean running;

    /** Profiles {@code target} on request (null: shows {@code opened}, read from a file). */
    ProfileTab(String source, Profiler.Target target, Profile opened) {
        super(new BorderLayout());
        this.source = source;
        this.target = target;

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        if (target != null) {
            for (Profiler.Engine e : target.engines()) {
                engine.addItem(e);
            }
            engine.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    return super.getListCellRendererComponent(l, ((Profiler.Engine) v).label, i, s, f);
                }
            });
            engine.setToolTipText("async-profiler sees native code and the kernel too; Flight Recorder is built into the JVM");
            mode.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    Profiler.Mode m = (Profiler.Mode) v;
                    super.getListCellRendererComponent(l, m.label, i, s, f);
                    setToolTipText(m.description);
                    return this;
                }
            });
            mode.setSelectedIndex(AppState.prefs().getInt("profile.mode", 0));
            mode.setToolTipText("What to measure");
            start.putClientProperty("JButton.buttonType", "default");
            start.addActionListener(e -> startProfiling());
            stop.addActionListener(e -> {
                AtomicBoolean r = running;
                if (r != null) {
                    r.set(true);
                    status.setText("Stopping…");
                }
            });
            stop.setVisible(false);
            bar.setPreferredSize(new Dimension(160, 12));
            bar.setVisible(false);
            controls.add(new JLabel("Measure"));
            controls.add(mode);
            controls.add(new JLabel("for"));
            controls.add(seconds);
            controls.add(new JLabel("seconds with"));
            controls.add(engine);
            controls.add(start);
            controls.add(stop);
            controls.add(bar);
        }
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        controls.add(status);
        save.putClientProperty("JButton.buttonType", "toolBarButton");
        save.setToolTipText("Save the profile (collapsed stacks; it opens again here, and in other flame graph tools)");
        save.addActionListener(e -> saveDialog());
        save.setEnabled(false);
        JPanel controlsRow = new JPanel(new BorderLayout());
        controlsRow.add(controls, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        right.add(save);
        controlsRow.add(right, BorderLayout.EAST);
        controlsRow.setAlignmentX(LEFT_ALIGNMENT);
        top.add(controlsRow);

        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        info.add(title);
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
        info.add(Box.createHorizontalStrut(10));
        info.add(search);
        info.add(matched);
        resetZoom.putClientProperty("JButton.buttonType", "toolBarButton");
        resetZoom.addActionListener(e -> graph.zoomTo(null));
        resetZoom.setVisible(false);
        info.add(resetZoom);
        info.setAlignmentX(LEFT_ALIGNMENT);
        top.add(info);
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
        JScrollPane graphScroll = new JScrollPane(graph);
        graphScroll.getVerticalScrollBar().setUnitIncrement(18);
        methodTable.setFillsViewportHeight(true);
        methodTable.setShowGrid(false);
        methodTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        methodTable.setRowHeight(methodTable.getRowHeight() + 3);
        methodTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        methodTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        methodTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        DefaultTableCellRenderer pct = new DefaultTableCellRenderer();
        pct.setHorizontalAlignment(SwingConstants.RIGHT);
        methodTable.getColumnModel().getColumn(1).setCellRenderer(pct);
        methodTable.getColumnModel().getColumn(2).setCellRenderer(pct);
        methodTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Profile.Method m = (Profile.Method) v;
                super.getTableCellRendererComponent(t, FlameGraph.shortName(m.name()), sel, f, r, c);
                setFont(Theme.monoFont(t.getFont().getSize2D() - 1));
                setToolTipText("<html>" + ThreadStackView.esc(m.name()) + "<br>" + FlameGraph.kindLabel(m.kind()) + "</html>");
                return this;
            }
        });
        methodTable.getSelectionModel().addListSelectionListener(e -> {
            int r = methodTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && r >= 0 && r < methods.size()) {
                search.setText("");
                graph.setExact(methods.get(r).name());
                matched.setText(FlameGraph.pct(Math.round(graph.matchedShare() * 1000), 1000) + " of the time shown");
            }
        });
        JPanel side = new JPanel(new BorderLayout());
        JLabel sideTitle = new JLabel("Most time of their own");
        sideTitle.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        sideTitle.setFont(sideTitle.getFont().deriveFont(Font.BOLD));
        side.add(sideTitle, BorderLayout.NORTH);
        JScrollPane methodScroll = new JScrollPane(methodTable);
        side.add(methodScroll, BorderLayout.CENTER);
        side.setPreferredSize(new Dimension(380, 300));
        graphScroll.setPreferredSize(new Dimension(900, 300));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphScroll, side);
        split.setResizeWeight(0.75);
        split.setBorder(null);

        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        empty.setText("<html><div style='text-align:center'>Choose what to measure and click <b>Start Profiling</b>.<br><br>"
                + "The JVM keeps running as usual; profiling costs it a few percent of CPU.</div></html>");
        center.add(empty, "empty");
        center.add(split, "graph");
        add(center, BorderLayout.CENTER);
        if (opened != null) {
            show(opened);
        } else {
            ((java.awt.CardLayout) center.getLayout()).show(center, "empty");
        }
    }

    String title() {
        return "Profile: " + source;
    }

    void onChanged(Runnable r) {
        changed = r;
    }

    String statusText() {
        return graph.profile() == null ? " " : graph.profile().title() + " · " + graph.profile().amount(graph.profile().total());
    }

    boolean isRunning() {
        return running != null;
    }

    Profile profile() {
        return graph.profile();
    }

    FlameGraph graphForTest() {
        return graph;
    }

    JTable methodsForTest() {
        return methodTable;
    }

    void setForTest(Profiler.Mode m, Profiler.Engine e, int s) {
        mode.setSelectedItem(m);
        if (e != null) {
            engine.setSelectedItem(e);
        }
        seconds.setValue(s);
    }

    void close() {
        AtomicBoolean r = running;
        if (r != null) {
            r.set(true);
        }
    }

    void startProfiling() {
        if (target == null || running != null) {
            return;
        }
        Profiler.Engine e = (Profiler.Engine) engine.getSelectedItem();
        Profiler.Mode m = (Profiler.Mode) mode.getSelectedItem();
        int s = (Integer) seconds.getValue();
        AppState.prefs().putInt("profile.seconds", s);
        AppState.prefs().putInt("profile.mode", mode.getSelectedIndex());
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        start.setEnabled(false);
        stop.setVisible(true);
        bar.setValue(0);
        bar.setVisible(true);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText("Profiling " + m.label.toLowerCase(java.util.Locale.ROOT) + " for " + s + " s…");
        long t0 = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            Profile p = null;
            String error = null;
            try {
                p = target.run(e, m, s, f -> SwingUtilities.invokeLater(() -> {
                    bar.setValue((int) (f * 1000));
                    long left = Math.max(0, s - (System.currentTimeMillis() - t0) / 1000);
                    if (running == cancel && !cancel.get()) {
                        status.setText(left > 0 ? "Profiling… " + left + " s left" : "Reading the results…");
                    }
                }), cancel::get);
            } catch (IOException | RuntimeException ex) {
                error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            Profile done = p;
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
                    show(done);
                }
                changed.run();
            });
        }, "logsift-profile");
        t.setDaemon(true);
        t.start();
    }

    /** Shows a profile. */
    void show(Profile p) {
        ((java.awt.CardLayout) center.getLayout()).show(center, "graph");
        graph.setProfile(p);
        title.setText(p.title() + " · " + p.amount(p.total()));
        methods.clear();
        methods.addAll(p.hottest(300));
        methodModel.fireTableDataChanged();
        save.setEnabled(true);
        if (p.warnings().isEmpty()) {
            warnings.setVisible(false);
        } else {
            warnings.setText("<html>" + String.join("<br>", p.warnings().stream().map(ThreadStackView::esc).toList())
                    + "</html>");
            warnings.setVisible(true);
        }
        if (p.total() == 0) {
            status.setText("No samples: nothing ran (or waited) in that time.");
        }
        resetZoom.setVisible(false);
        highlight();
        changed.run();
    }

    private void highlight() {
        String q = search.getText();
        if (!q.isBlank()) {
            methodTable.clearSelection();
            graph.setQuery(q);
        } else if (methodTable.getSelectedRow() < 0) {
            graph.setQuery("");
        }
        double share = graph.matchedShare();
        matched.setText(q.isBlank() && methodTable.getSelectedRow() < 0 ? " "
                : FlameGraph.pct(Math.round(share * 1000), 1000) + " of the time shown");
    }

    private void saveDialog() {
        Profile p = graph.profile();
        if (p == null) {
            return;
        }
        JFileChooser ch = new JFileChooser();
        ch.setSelectedFile(new java.io.File("profile-" + source.replaceAll("[^A-Za-z0-9._-]+", "-") + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".collapsed.txt"));
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

    private final class MethodModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return methods.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Method", "Self", "Total"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Profile.Method m = methods.get(r);
            long all = graph.profile() == null ? 1 : graph.profile().total();
            return switch (c) {
                case 0 -> m;
                case 1 -> FlameGraph.pct(m.self(), all);
                default -> FlameGraph.pct(m.total(), all);
            };
        }
    }
}
