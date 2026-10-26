package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.jvm.HeapHistogram;
import logsift.jvm.Profiler;

/**
 * What fills a JVM's heap, by class (the JVM's class histogram), and what grew since an earlier
 * look: take a histogram, let the program run, take another, and the classes that keep growing
 * are where a leak is. Also saves a heap dump (.hprof) for a heap analyzer.
 */
final class HeapTab extends JPanel {
    private final Profiler.Target target;
    private final String source;
    private Runnable changed = () -> { };
    private java.util.function.Consumer<Path> dumpSaved = p -> { };

    private final JButton take = new JButton("Take Histogram", Icons.of("memory-stick"));
    private final JCheckBox liveOnly = new JCheckBox("Only live objects (a full GC first)",
            AppState.prefs().getBoolean("heap.live", true));
    private final JButton dump = new JButton("Save Heap Dump…", Icons.of("download"));
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    private final JLabel heapInfo = new JLabel(" ");
    private final JLabel summary = new JLabel(" ");
    private final JComboBox<String> snapshotBox = new JComboBox<>();
    private final JLabel compareLabel = new JLabel("compared with");
    private final JComboBox<String> compareBox = new JComboBox<>();
    private final JTextField search = new JTextField(18);
    private final List<HeapHistogram> snapshots = new ArrayList<>();
    private final List<Boolean> liveOnlyOf = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private int sortColumn = 2;      // by size
    private boolean filling;
    private volatile AtomicBoolean busy;

    /** One class: now, and (when comparing) before. */
    private record Row(String name, String module, long instances, long bytes, long instancesBefore, long bytesBefore,
                       boolean compared) {
        long grown() {
            return bytes - bytesBefore;
        }
    }

    HeapTab(String source, Profiler.Target target) {
        super(new BorderLayout());
        this.source = source;
        this.target = target;
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        take.putClientProperty("JButton.buttonType", "default");
        take.setToolTipText("Count the objects in the heap by class");
        take.addActionListener(e -> takeHistogram());
        liveOnly.setToolTipText("<html>Runs a full garbage collection first, so only objects still in use are counted."
                + "<br>That pauses the JVM (longer for big heaps). Off: counts garbage too, without the pause.</html>");
        liveOnly.addActionListener(e -> AppState.prefs().putBoolean("heap.live", liveOnly.isSelected()));
        dump.putClientProperty("JButton.buttonType", "toolBarButton");
        dump.setToolTipText("Write every object in the heap to a .hprof file, for a heap analyzer");
        dump.addActionListener(e -> heapDumpDialog());
        if (target.noHeapDump() != null) {
            dump.setEnabled(false);
            dump.setToolTipText(target.noHeapDump());
        }
        bar.setPreferredSize(new Dimension(140, 12));
        bar.setVisible(false);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        controls.add(take);
        controls.add(liveOnly);
        controls.add(dump);
        controls.add(bar);
        controls.add(status);
        controls.setAlignmentX(LEFT_ALIGNMENT);
        top.add(controls);
        heapInfo.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        heapInfo.setBorder(BorderFactory.createEmptyBorder(0, 10, 2, 10));
        heapInfo.setAlignmentX(LEFT_ALIGNMENT);
        top.add(heapInfo);

        JPanel view = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        snapshotBox.addActionListener(e -> {
            if (!filling) {
                refresh();
            }
        });
        compareBox.setToolTipText("An earlier histogram: the Change columns show what grew since");
        compareBox.addActionListener(e -> {
            if (!filling) {
                refresh();
            }
        });
        search.putClientProperty("JTextField.placeholderText", "filter classes");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        view.add(snapshotBox);
        view.add(compareLabel);
        view.add(compareBox);
        view.add(search);
        view.add(summary);
        view.setAlignmentX(LEFT_ALIGNMENT);
        top.add(view);
        add(top, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setRowHeight(table.getRowHeight() + 3);
        table.setAutoCreateColumnsFromModel(true);
        table.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int c = table.columnAtPoint(e.getPoint());
                if (c >= 0) {
                    sortColumn = c;
                    refresh();
                }
            }
        });
        table.getTableHeader().setToolTipText("Click to sort");
        add(new JScrollPane(table), BorderLayout.CENTER);
        snapshotBox.setVisible(false);
        compareLabel.setVisible(false);
        compareBox.setVisible(false);
        setUpColumns();
        JLabel hint = new JLabel("  Take a histogram now and another one later: the classes that keep growing are where memory leaks.");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        add(hint, BorderLayout.SOUTH);
    }

    String title() {
        return "Heap: " + source;
    }

    void onChanged(Runnable r) {
        changed = r;
    }

    String statusText() {
        return summary.getText().isBlank() ? " " : summary.getText();
    }

    boolean isBusy() {
        return busy != null;
    }

    boolean canHeapDumpForTest() {
        return dump.isEnabled();
    }

    int snapshotCountForTest() {
        return snapshots.size();
    }

    JTable tableForTest() {
        return table;
    }

    void setLiveForTest(boolean on) {
        liveOnly.setSelected(on);
    }

    void close() {
        AtomicBoolean b = busy;
        if (b != null) {
            b.set(true);
        }
    }

    // ---------------------------------------------------------------- taking

    void takeHistogram() {
        if (busy != null) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        busy = cancel;
        setWorking(true, liveOnly.isSelected() ? "Counting live objects (after a full GC)…" : "Counting objects…");
        boolean live = liveOnly.isSelected();
        background(() -> {
            String text = target.diagnostic("GC.class_histogram", live ? new String[0] : new String[] {"-all"});
            if (!live && !text.contains("Total")) {
                text = target.diagnostic("GC.class_histogram"); // JDK 8 has no -all
            }
            HeapHistogram h = HeapHistogram.parse(text, System.currentTimeMillis());
            if (h.rows().isEmpty()) {
                throw new IOException("the JVM's answer was not a histogram: " + firstLine(text));
            }
            String info;
            try {
                info = target.diagnostic("GC.heap_info");
            } catch (IOException e) {
                info = "";
            }
            String heap = heapLine(info);
            SwingUtilities.invokeLater(() -> {
                setWorking(false, " ");
                heapInfo.setText(heap);
                addSnapshot(h, live);
            });
        }, error -> setWorking(false, "Could not count: " + error));
    }

    /** Adds a histogram (taken just now) and shows it, compared with the one before. */
    void addSnapshot(HeapHistogram h, boolean live) {
        snapshots.add(h);
        liveOnlyOf.add(live);
        filling = true;
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            labels.add("#" + (i + 1) + " · " + clock(snapshots.get(i).time()) + " · " + bytes(snapshots.get(i).bytes())
                    + (liveOnlyOf.get(i) ? " live" : " with garbage"));
        }
        snapshotBox.setModel(new DefaultComboBoxModel<>(labels.toArray(String[]::new)));
        snapshotBox.setSelectedIndex(snapshots.size() - 1);
        List<String> before = new ArrayList<>();
        before.add("(nothing)");
        before.addAll(labels);
        compareBox.setModel(new DefaultComboBoxModel<>(before.toArray(String[]::new)));
        compareBox.setSelectedIndex(snapshots.size() >= 2 ? snapshots.size() - 1 : 0); // the one before
        filling = false;
        snapshotBox.setVisible(true);
        compareLabel.setVisible(snapshots.size() > 1);
        compareBox.setVisible(snapshots.size() > 1);
        if (snapshots.size() == 2 && sortColumn == 2) {
            sortColumn = 4; // with two, what grew matters most
        }
        refresh();
        changed.run();
    }

    private java.util.function.BiConsumer<logsift.jvm.JmxJvms.DumpOnServer, Path> dumpOnServer = (d, p) -> { };

    /**
     * Called when the dump was written on the JVM's own machine (over a JMX port), with where it
     * is and where it was to go here: to copy it over SSH.
     */
    void onDumpOnServer(java.util.function.BiConsumer<logsift.jvm.JmxJvms.DumpOnServer, Path> c) {
        dumpOnServer = c;
    }

    /** Called with a heap dump once it is saved (on this computer). */
    void onDumpSaved(java.util.function.Consumer<Path> c) {
        dumpSaved = c;
    }

    private void heapDumpDialog() {
        JFileChooser ch = new JFileChooser();
        ch.setSelectedFile(new java.io.File("heap-" + source.replaceAll("[^A-Za-z0-9._-]+", "-") + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".hprof"));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "<html>A heap dump pauses the JVM while it is written (seconds, or longer"
                        + " for big heaps),<br>and the file is about as big as the heap. Save one now?</html>",
                "Heap Dump", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        heapDump(ch.getSelectedFile().toPath());
    }

    void heapDump(Path file) {
        if (busy != null) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        busy = cancel;
        setWorking(true, "Writing the heap dump…");
        background(() -> {
            java.nio.file.Files.deleteIfExists(file);
            try {
                target.heapDump(file, f -> SwingUtilities.invokeLater(() -> {
                    bar.setValue((int) Math.round(f * 1000));
                    status.setText("Copying the heap dump… " + Math.round(f * 100) + "%");
                }), cancel::get);
            } catch (logsift.jvm.JmxJvms.DumpOnServer there) {
                SwingUtilities.invokeLater(() -> {
                    setWorking(false, "Saved on " + there.host() + ": " + there.path());
                    dumpOnServer.accept(there, file);
                });
                return;
            }
            long size = java.nio.file.Files.size(file);
            SwingUtilities.invokeLater(() -> {
                setWorking(false, "Saved " + file.getFileName() + " (" + bytes(size) + ")");
                dumpSaved.accept(file); // on this computer (a remote dump is copied here)
            });
        }, error -> setWorking(false, "Could not save a heap dump: " + error));
    }

    private void setWorking(boolean on, String text) {
        if (!on) {
            busy = null;
        }
        take.setEnabled(!on);
        dump.setEnabled(!on);
        bar.setValue(0);
        bar.setVisible(on);
        status.setForeground(text.startsWith("Could not") ? new Color(0xDC2626)
                : Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText(text);
        changed.run();
    }

    // ---------------------------------------------------------------- showing

    private void refresh() {
        rows.clear();
        int now = snapshotBox.getSelectedIndex();
        if (now < 0 || now >= snapshots.size()) {
            model.fireTableStructureChanged();
            setUpColumns();
            return;
        }
        HeapHistogram cur = snapshots.get(now);
        int b = compareBox.getSelectedIndex() - 1;
        HeapHistogram before = b >= 0 && b < snapshots.size() && b != now ? snapshots.get(b) : null;
        Map<String, HeapHistogram.Row> was = new HashMap<>();
        if (before != null) {
            for (HeapHistogram.Row r : before.rows()) {
                was.put(r.className(), r);
            }
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (HeapHistogram.Row r : cur.rows()) {
            seen.add(r.className());
            HeapHistogram.Row w = was.get(r.className());
            add(q, new Row(r.displayName(), r.module(), r.instances(), r.bytes(),
                    w == null ? 0 : w.instances(), w == null ? 0 : w.bytes(), before != null));
        }
        if (before != null) {
            for (HeapHistogram.Row w : before.rows()) {
                if (!seen.contains(w.className())) {
                    add(q, new Row(w.displayName(), w.module(), 0, 0, w.instances(), w.bytes(), true));
                }
            }
        }
        Comparator<Row> c = switch (sortColumn) {
            case 0 -> Comparator.comparing(Row::name);
            case 1 -> Comparator.comparingLong(Row::instances).reversed();
            case 4 -> Comparator.comparingLong(Row::grown).reversed();
            case 5 -> Comparator.comparingLong((Row r) -> r.instances() - r.instancesBefore()).reversed();
            default -> Comparator.comparingLong(Row::bytes).reversed();
        };
        if (before == null && sortColumn >= 4) {
            c = Comparator.comparingLong(Row::bytes).reversed();
        }
        rows.sort(c);
        String s = bytes(cur.bytes()) + " in " + String.format("%,d", cur.instances()) + " objects of "
                + cur.rows().size() + " classes";
        if (before != null && !liveOnlyOf.get(now).equals(liveOnlyOf.get(b))) {
            s += " · compare two histograms of the same kind (live, or with garbage)";
        } else if (before != null) {
            long grew = cur.bytes() - before.bytes();
            s += String.format(" · %s%s since #%d", grew >= 0 ? "+" : "−", bytes(Math.abs(grew)), b + 1);
            Row top = rows.stream().max(Comparator.comparingLong(Row::grown)).orElse(null);
            if (top != null && top.grown() > 0) {
                s += " · grew most: " + top.name() + " (+" + bytes(top.grown()) + ")";
            }
        }
        summary.setText(s);
        model.fireTableStructureChanged();
        setUpColumns();
        changed.run();
    }

    private void add(String q, Row r) {
        if (q.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(q)) {
            rows.add(r);
        }
    }

    private boolean comparing() {
        return !rows.isEmpty() && rows.get(0).compared();
    }

    private void setUpColumns() {
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 420 : 110);
            if (i > 0) {
                table.getColumnModel().getColumn(i).setCellRenderer(i >= 4 ? new ChangeRenderer() : right);
            }
        }
        if (table.getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                    super.getTableCellRendererComponent(t, v, s, f, r, c);
                    setFont(Theme.monoFont(t.getFont().getSize2D() - 1));
                    Row row = r < rows.size() ? rows.get(r) : null;
                    setToolTipText(row == null || row.module().isEmpty() ? null : row.module());
                    return this;
                }
            });
        }
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return comparing() ? 6 : 4;
        }

        @Override
        public String getColumnName(int c) {
            String n = new String[] {"Class", "Objects", "Size", "Share", "Size change", "Objects change"}[c];
            return c == sortColumn ? n + " ▼" : n;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            HeapHistogram cur = snapshots.get(snapshotBox.getSelectedIndex());
            return switch (c) {
                case 0 -> row.name();
                case 1 -> String.format("%,d", row.instances());
                case 2 -> bytes(row.bytes());
                case 3 -> FlameGraph.pct(row.bytes(), cur.bytes());
                case 4 -> signed(row.bytes() - row.bytesBefore(), true);
                default -> signed(row.instances() - row.instancesBefore(), false);
            };
        }
    }

    /** Growth in red, shrinking in blue. */
    private static final class ChangeRenderer extends DefaultTableCellRenderer {
        ChangeRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, s, f, r, c);
            String text = String.valueOf(v);
            if (!s) {
                setForeground(text.startsWith("+") ? new Color(0xDC2626)
                        : text.startsWith("−") ? new Color(0x2563EB) : t.getForeground());
            }
            return this;
        }
    }

    private static String signed(long v, boolean asBytes) {
        if (v == 0) {
            return "0";
        }
        String abs = asBytes ? bytes(Math.abs(v)) : String.format("%,d", Math.abs(v));
        return (v > 0 ? "+" : "−") + abs;
    }

    static String bytes(long b) {
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
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    private static String clock(long ms) {
        return DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    /** The first lines of GC.heap_info, in one line: the collector and how full the heap is. */
    private static String heapLine(String info) {
        StringBuilder sb = new StringBuilder();
        for (String l : info.split("\n")) {
            String t = l.strip();
            if (t.isEmpty() || t.matches("\\d+:")) {
                continue;
            }
            t = t.replaceAll("\\s*\\[0x[0-9a-f, x]+\\)", "");
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(t);
            if (sb.length() > 160) {
                break;
            }
        }
        return sb.length() == 0 ? " " : sb.toString();
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }

    private interface Work {
        void run() throws Exception;
    }

    private static void background(Work w, java.util.function.Consumer<String> failed) {
        Thread t = new Thread(() -> {
            try {
                w.run();
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> failed.accept(m));
            }
        }, "logsift-heap");
        t.setDaemon(true);
        t.start();
    }
}
