package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.heap.HeapDiff;
import logsift.heap.HeapDump;
import logsift.heap.HeapFindings;

/**
 * Two heap dumps of the same program compared: what grew between them, and what holds the growth
 * (the field it piles up in), then every class with its objects before and after.
 */
final class HeapCompareTab extends JPanel {
    private final Path before;
    private final Path after;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Runnable changed = () -> { };
    private volatile HeapDump a;
    private volatile HeapDump b;
    private volatile HeapDiff.Report report;
    private String status = "Reading…";

    private final CardLayout cards = new CardLayout();
    private final JLabel loadingText = new JLabel("Reading the heap dumps…");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel header = new JLabel();
    private final JPanel findings = new JPanel();
    private final JTabbedPane views = new JTabbedPane();
    private final Model places = new Model("Where it is kept", "Before", "After", "Growth", "What it is now");
    private final Model classes = new Model("Class", "Objects before", "Objects after", "Growth", "Size now");

    /** {@code before} and {@code after} are put in order by when they were taken once read. */
    HeapCompareTab(Path before, Path after) {
        this.before = before.toAbsolutePath().normalize();
        this.after = after.toAbsolutePath().normalize();
        setLayout(cards);
        add(loadingPanel(), "loading");
        add(mainPanel(), "main");
        cards.show(this, "loading");
        start();
    }

    String title() {
        HeapDump x = a;
        HeapDump y = b;
        return "Heap: " + (x != null ? x.file() : before).getFileName() + " → "
                + (y != null ? y.file() : after).getFileName();
    }

    String statusText() {
        return status;
    }

    void onChanged(Runnable r) {
        changed = r;
    }

    void close() {
        cancel.set(true);
        HeapDump x = a;
        HeapDump y = b;
        a = null;
        b = null;
        if (x != null) {
            x.close();
        }
        if (y != null) {
            y.close();
        }
    }

    private JPanel loadingPanel() {
        JPanel p = new JPanel(new java.awt.GridBagLayout());
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        loadingText.setFont(loadingText.getFont().deriveFont(15f));
        loadingText.setAlignmentX(LEFT_ALIGNMENT);
        bar.setAlignmentX(LEFT_ALIGNMENT);
        bar.setPreferredSize(new Dimension(420, 8));
        bar.setMaximumSize(new Dimension(420, 8));
        JButton stop = new JButton("Cancel");
        stop.setAlignmentX(LEFT_ALIGNMENT);
        stop.addActionListener(e -> cancel.set(true));
        col.add(loadingText);
        col.add(Box.createVerticalStrut(10));
        col.add(bar);
        col.add(Box.createVerticalStrut(12));
        col.add(stop);
        p.add(col);
        return p;
    }

    private HeapDump read(Path f, int which) throws Exception {
        Path work = HeapDump.needsDisk(Files.size(f))
                ? AppState.home().resolve("heap-index").resolve(java.util.UUID.randomUUID().toString()) : null;
        String name = f.getFileName().toString();
        return HeapDump.open(f, work, (stage, x) -> SwingUtilities.invokeLater(() -> {
            loadingText.setText(name + ": " + stage.toLowerCase(java.util.Locale.ROOT) + "…");
            bar.setValue((int) Math.round((which + x) * 500));
            status = "Reading " + name + "… " + Math.round(x * 100) + "%";
            changed.run();
        }), cancel::get);
    }

    private void start() {
        Thread t = new Thread(() -> {
            HeapDump x = null;
            HeapDump y = null;
            try {
                String room = HeapDump.roomProblem(AppState.home().resolve("heap-index"), Files.size(before),
                        Files.size(after));
                if (room != null) {
                    SwingUtilities.invokeLater(() -> failed(room));
                    return;
                }
                x = read(before, 0);
                y = read(after, 1);
                if (x.timestamp() > y.timestamp() && y.timestamp() > 0) { // the earlier one first
                    HeapDump s = x;
                    x = y;
                    y = s;
                }
                SwingUtilities.invokeLater(() -> loadingText.setText("Comparing…"));
                HeapDiff.Report r = HeapDiff.of(x, y, cancel::get);
                HeapDump fx = x;
                HeapDump fy = y;
                SwingUtilities.invokeLater(() -> {
                    if (cancel.get()) {
                        fx.close();
                        fy.close();
                        return;
                    }
                    a = fx;
                    b = fy;
                    report = r;
                    show(r);
                });
            } catch (CancellationException e) {
                closeAll(x, y);
                SwingUtilities.invokeLater(() -> failed("Cancelled."));
            } catch (Exception | OutOfMemoryError e) {
                closeAll(x, y);
                SwingUtilities.invokeLater(() -> failed("Could not compare them: " + e.getMessage()));
            }
        }, "logsift-heap-compare");
        t.setDaemon(true);
        t.start();
    }

    private static void closeAll(HeapDump x, HeapDump y) {
        if (x != null) {
            x.close();
        }
        if (y != null) {
            y.close();
        }
    }

    private void failed(String text) {
        // wrapped: a message about disk space names a folder and can be long
        loadingText.setText("<html><div style='width:420px'>" + text.replace("&", "&amp;").replace("<", "&lt;")
                + "</div></html>");
        loadingText.setForeground(new Color(0xDC2626));
        bar.setVisible(false);
        status = text;
        changed.run();
    }

    private JPanel mainPanel() {
        JPanel p = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        p.add(header, BorderLayout.NORTH);
        findings.setLayout(new BoxLayout(findings, BoxLayout.Y_AXIS));
        findings.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        JPanel top = new JPanel(new BorderLayout());
        top.add(findings, BorderLayout.NORTH);
        JPanel grew = new JPanel(new BorderLayout());
        grew.add(top, BorderLayout.NORTH);
        grew.add(new JScrollPane(table(places, 360, java.util.Set.of(), java.util.Set.of(3))), BorderLayout.CENTER);
        views.addTab("What grew", grew);
        views.addTab("Classes", new JScrollPane(table(classes, 420, java.util.Set.of(1, 2), java.util.Set.of(3))));
        p.add(views, BorderLayout.CENTER);
        return p;
    }

    /** A table whose number columns are bytes, except {@code counts}; {@code signed} ones show + or −. */
    private static JTable table(Model m, int firstWidth, java.util.Set<Integer> counts, java.util.Set<Integer> signed) {
        JTable t = new JTable(m);
        t.setAutoCreateRowSorter(true);
        for (int c = 1; c < m.getColumnCount(); c++) {
            if (m.getColumnClass(c) != Long.class) {
                continue;
            }
            boolean count = counts.contains(c);
            boolean sign = signed.contains(c);
            t.getColumnModel().getColumn(c).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                protected void setValue(Object v) {
                    setHorizontalAlignment(SwingConstants.RIGHT);
                    long n = v instanceof Number x ? x.longValue() : 0;
                    String text = count ? String.format("%,d", Math.abs(n)) : HeapFindings.bytes(Math.abs(n));
                    setText(sign ? (n > 0 ? "+" : n < 0 ? "−" : "") + text : (n < 0 ? "−" : "") + text);
                }
            });
        }
        t.getColumnModel().getColumn(0).setPreferredWidth(firstWidth);
        return t;
    }

    private void show(HeapDiff.Report r) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String from = r.beforeTime() > 0 ? f.format(Instant.ofEpochMilli(r.beforeTime()).atZone(ZoneId.systemDefault())) : "";
        String to = r.afterTime() > 0 ? f.format(Instant.ofEpochMilli(r.afterTime()).atZone(ZoneId.systemDefault())) : "";
        if (from.length() > 10 && to.startsWith(from.substring(0, 10))) {
            to = to.substring(11); // the same day
        }
        String when = !from.isEmpty() && !to.isEmpty() ? " · taken " + from + " → " + to : "";
        long g = r.growth();
        header.setText("<html><span style='font-size:1.15em'><b>" + esc(a.file().getFileName().toString()) + " → "
                + esc(b.file().getFileName().toString()) + "</b></span>&nbsp;&nbsp;<span style='color:#858b94'>"
                + HeapFindings.bytes(r.beforeBytes()) + " → <b>" + HeapFindings.bytes(r.afterBytes()) + "</b> in use ("
                + (g >= 0 ? "+" : "−") + HeapFindings.bytes(Math.abs(g)) + ")" + esc(when) + "</span></html>");
        status = "In use: " + HeapFindings.bytes(r.beforeBytes()) + " → " + HeapFindings.bytes(r.afterBytes());

        findings.removeAll();
        int shown = 0;
        for (HeapDiff.Place p : r.places()) {
            if (shown == 4 || p.growth() < Math.max(1, Math.abs(g)) * 0.05) {
                break;
            }
            findings.add(card(p));
            findings.add(Box.createVerticalStrut(10));
            shown++;
        }
        if (shown == 0) {
            JLabel none = new JLabel("<html><b>" + (g <= 0 ? "The heap didn't grow: " + HeapFindings.bytes(Math.abs(g))
                    + " less is in use." : "No one place holds the growth: it is spread out.")
                    + "</b><br><span style='color:#6b7280'>The classes show what there is more of.</span></html>");
            findings.add(none);
        }
        places.rows.clear();
        for (HeapDiff.Place p : r.places()) {
            places.rows.add(new Object[] {p.where(), p.beforeBytes(), p.afterBytes(), p.growth(),
                    HeapFindings.shorten(p.after())});
        }
        places.fireTableDataChanged();
        classes.rows.clear();
        for (HeapDiff.ClassDelta c : r.classes()) {
            if (c.beforeCount() != c.afterCount() || c.beforeBytes() != c.afterBytes()) {
                classes.rows.add(new Object[] {c.name(), c.beforeCount(), c.afterCount(), c.growth(), c.afterBytes()});
            }
        }
        classes.fireTableDataChanged();
        cards.show(this, "main");
        changed.run();
    }

    private JPanel card(HeapDiff.Place p) {
        JPanel c = new JPanel(new BorderLayout(0, 6));
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, new Color(0xDC2626)),
                BorderFactory.createEmptyBorder(6, 12, 6, 6)));
        c.setAlignmentX(LEFT_ALIGNMENT);
        String what = HeapFindings.shorten(p.after());
        String title = p.beforeBytes() == 0
                ? "New: " + what + " in " + p.where() + " holds " + HeapFindings.bytes(p.afterBytes())
                : what.replaceAll(" \\(.*", "") + " in " + p.where() + " grew by " + HeapFindings.bytes(p.growth())
                        + " (" + HeapFindings.bytes(p.beforeBytes()) + " → " + HeapFindings.bytes(p.afterBytes()) + ")";
        String sizes = p.beforeBytes() > 0 && p.before().contains("(") && p.after().contains("(")
                ? p.before().replaceAll(".*\\(|\\)", "") + " → " + p.after().replaceAll(".*\\(|\\)", "") + ". "
                : "";
        JLabel t = new JLabel("<html><div style='width:600px'><b style='font-size:1.15em'>" + esc(title)
                + "</b><br><span style='color:#6b7280'>" + esc(sizes + p.contents()) + "</span></div></html>");
        c.add(t, BorderLayout.CENTER);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Rows; numbers are bytes except the object counts. */
    private static final class Model extends AbstractTableModel {
        private final String[] names;
        final List<Object[]> rows = new java.util.ArrayList<>();

        Model(String... names) {
            this.names = names;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 || names[c].startsWith("What") ? String.class : Long.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }
    }

    // ---------------------------------------------------------------- for tests

    boolean isLoadedForTest() {
        return report != null;
    }

    String findingsTextForTest() {
        StringBuilder sb = new StringBuilder();
        for (java.awt.Component c : findings.getComponents()) {
            if (c instanceof JPanel p) {
                for (java.awt.Component k : p.getComponents()) {
                    if (k instanceof JLabel l) {
                        sb.append(l.getText().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim()).append('\n');
                    }
                }
            } else if (c instanceof JLabel l) {
                sb.append(l.getText().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim()).append('\n');
            }
        }
        return sb.toString();
    }

    void showTabForTest(int i) {
        views.setSelectedIndex(i);
    }
}
