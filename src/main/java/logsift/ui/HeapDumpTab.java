package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import logsift.heap.HeapDump;
import logsift.heap.HeapFindings;

/**
 * A heap dump (.hprof), explained: what holds most of the memory and where it is kept (in
 * sentences first), the biggest objects and what they hold, the classes, and for any object the
 * chain of references that keeps it alive.
 */
final class HeapDumpTab extends JPanel {
    private static final int KIDS = 200; // children shown per tree level

    private final Path file;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Runnable changed = () -> { };
    private volatile HeapDump dump;
    private String status = "Reading…";

    private final CardLayout cards = new CardLayout();
    private final JLabel loadingText = new JLabel("Reading the heap dump…");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel header = new JLabel();
    private final JPanel findings = new JPanel();
    private final JTabbedPane views = new JTabbedPane();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(treeModel);
    private final ClassModel classModel = new ClassModel();
    private final JTable classTable = new JTable(classModel);
    private final JTextField classFilter = new JTextField();
    private final JEditorPane why = new JEditorPane("text/html", "");
    private int selected = -1;

    HeapDumpTab(Path file) {
        super(new BorderLayout());
        this.file = file;
        setLayout(cards);
        add(loadingPanel(), "loading");
        add(mainPanel(), "main");
        cards.show(this, "loading");
        start();
    }

    String title() {
        return "Heap: " + file.getFileName();
    }

    String statusText() {
        return status;
    }

    void onChanged(Runnable r) {
        changed = r;
    }

    /** Removes heap dump indexes left on disk by earlier runs (in the background). */
    static void cleanUpIndexes() {
        Thread t = new Thread(() -> HeapDump.cleanUp(AppState.home().resolve("heap-index")), "logsift-heap-cleanup");
        t.setDaemon(true);
        t.start();
    }

    Path file() {
        return file;
    }

    HeapDump dumpForTest() {
        return dump;
    }

    void close() {
        cancel.set(true);
        HeapDump d = dump;
        dump = null;
        if (d != null) {
            d.close();
        }
    }

    // ---------------------------------------------------------------- loading

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
        JLabel note = new JLabel("<html>Every object in the dump is read and linked; for a dump of"
                + " several GB this takes a minute.<br>Big dumps use an index on disk, not memory.</html>");
        note.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        note.setAlignmentX(LEFT_ALIGNMENT);
        col.add(loadingText);
        col.add(Box.createVerticalStrut(10));
        col.add(bar);
        col.add(Box.createVerticalStrut(10));
        col.add(note);
        col.add(Box.createVerticalStrut(12));
        col.add(stop);
        p.add(col);
        return p;
    }

    private void start() {
        Thread t = new Thread(() -> {
            try {
                long size = Files.size(file);
                Path work = HeapDump.needsDisk(size)
                        ? AppState.home().resolve("heap-index").resolve(java.util.UUID.randomUUID().toString()) : null;
                HeapDump d = HeapDump.open(file, work, (stage, f) -> SwingUtilities.invokeLater(() -> {
                    loadingText.setText(stage + "…");
                    bar.setValue((int) Math.round(f * 1000));
                    status = stage + "… " + Math.round(f * 100) + "%";
                    changed.run();
                }), cancel::get);
                List<HeapFindings.Finding> found = HeapFindings.of(d);
                SwingUtilities.invokeLater(() -> {
                    if (cancel.get()) {
                        d.close();
                        return;
                    }
                    dump = d;
                    show(found);
                });
            } catch (CancellationException e) {
                SwingUtilities.invokeLater(() -> failed("Cancelled."));
            } catch (Exception | OutOfMemoryError e) {
                SwingUtilities.invokeLater(() -> failed("Could not read this heap dump: " + e.getMessage()));
            }
        }, "logsift-heap-dump");
        t.setDaemon(true);
        t.start();
    }

    private void failed(String text) {
        loadingText.setText(text);
        loadingText.setForeground(new Color(0xDC2626));
        bar.setVisible(false);
        status = text;
        changed.run();
    }

    // ---------------------------------------------------------------- the dump

    private JPanel mainPanel() {
        JPanel p = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        p.add(header, BorderLayout.NORTH);

        findings.setLayout(new BoxLayout(findings, BoxLayout.Y_AXIS));
        findings.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        JPanel top = new JPanel(new BorderLayout());
        top.add(findings, BorderLayout.NORTH);
        views.addTab("What fills the heap", new JScrollPane(top));

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);
        tree.setCellRenderer(new NodeRenderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                load((DefaultMutableTreeNode) e.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (e.getNewLeadSelectionPath() != null
                    && e.getNewLeadSelectionPath().getLastPathComponent() instanceof DefaultMutableTreeNode n
                    && n.getUserObject() instanceof Item it && it.node >= 0) {
                select(it.node);
            }
        });
        JPanel objects = new JPanel(new BorderLayout());
        JLabel hint = new JLabel("Each object with what it holds: freeing it would free all of that."
                + " Biggest first.");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        hint.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        objects.add(hint, BorderLayout.NORTH);
        objects.add(new JScrollPane(tree), BorderLayout.CENTER);
        views.addTab("Biggest objects", objects);

        classTable.setAutoCreateRowSorter(false);
        TableRowSorter<ClassModel> sorter = new TableRowSorter<>(classModel);
        classTable.setRowSorter(sorter);
        classTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object v) {
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText(v instanceof Long l ? HeapFindings.bytes(l) : String.valueOf(v));
            }
        };
        DefaultTableCellRenderer count = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object v) {
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText(String.format("%,d", (Long) v));
            }
        };
        classTable.getColumnModel().getColumn(1).setCellRenderer(count);
        classTable.getColumnModel().getColumn(2).setCellRenderer(right);
        classTable.getColumnModel().getColumn(3).setCellRenderer(right);
        classTable.getColumnModel().getColumn(0).setPreferredWidth(420);
        classTable.getSelectionModel().addListSelectionListener(e -> {
            int row = classTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0) {
                selectBiggestOf(classModel.rows.get(classTable.convertRowIndexToModel(row)).name());
            }
        });
        classFilter.putClientProperty("JTextField.placeholderText", "Filter classes");
        classFilter.putClientProperty("JTextField.showClearButton", true);
        classFilter.getDocument().addDocumentListener(new DocumentListener() {
            private void f() {
                String q = classFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
                sorter.setRowFilter(q.isEmpty() ? null : new javax.swing.RowFilter<>() {
                    @Override
                    public boolean include(Entry<? extends ClassModel, ? extends Integer> en) {
                        return en.getStringValue(0).toLowerCase(java.util.Locale.ROOT).contains(q);
                    }
                });
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                f();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                f();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                f();
            }
        });
        JPanel classes = new JPanel(new BorderLayout());
        JPanel cf = new JPanel(new BorderLayout());
        cf.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        cf.add(classFilter, BorderLayout.CENTER);
        JLabel ch = new JLabel("  Retained: what the class's objects hold (freed with them)");
        ch.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        cf.add(ch, BorderLayout.EAST);
        classes.add(cf, BorderLayout.NORTH);
        classes.add(new JScrollPane(classTable), BorderLayout.CENTER);
        views.addTab("Classes", classes);

        why.setEditable(false);
        why.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        why.setFont(javax.swing.UIManager.getFont("Label.font"));
        why.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription().startsWith("node:")) {
                int v = Integer.parseInt(e.getDescription().substring(5));
                showInTree(v);
            }
        });
        JPanel whyPanel = new JPanel(new BorderLayout());
        JLabel wt = new JLabel("Why is it alive?");
        wt.setFont(wt.getFont().deriveFont(Font.BOLD, 14f));
        wt.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        whyPanel.add(wt, BorderLayout.NORTH);
        whyPanel.add(new JScrollPane(why), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, views, whyPanel);
        split.setResizeWeight(0.62);
        split.setBorder(null);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void show(List<HeapFindings.Finding> found) {
        HeapDump d = dump;
        String when = d.timestamp() > 0 ? " · taken " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(Instant.ofEpochMilli(d.timestamp()).atZone(ZoneId.systemDefault())) : "";
        long size = 0;
        try {
            size = Files.size(file);
        } catch (java.io.IOException e) {
            // shown without
        }
        header.setText("<html><span style='font-size:1.15em'><b>" + esc(file.getFileName().toString()) + "</b></span>"
                + "&nbsp;&nbsp;<span style='color:#858b94'>" + HeapFindings.bytes(size) + " file" + when + " · "
                + String.format("%,d", d.objectCount()) + " objects · <b>" + HeapFindings.bytes(d.reachableBytes())
                + "</b> in use" + (d.compressedPointers() ? "" : " · sizes without compressed pointers")
                + "</span></html>");
        status = HeapFindings.bytes(d.reachableBytes()) + " in use in " + String.format("%,d", d.reachableCount())
                + " objects" + (d.isOnDisk() ? " · index on disk" : "");

        findings.removeAll();
        if (found.isEmpty()) {
            JLabel none = new JLabel("<html><b>No single object holds 10% of the heap or more.</b><br>"
                    + "The memory is spread out: see the biggest objects and the classes.</html>");
            findings.add(none);
        }
        boolean first = true;
        for (HeapFindings.Finding f : found) {
            findings.add(card(f, first));
            findings.add(Box.createVerticalStrut(10));
            first = false;
        }
        findings.add(Box.createVerticalStrut(6));
        findings.add(topClasses());

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Item(d.root(), "All objects in use"));
        root.add(new DefaultMutableTreeNode("…"));
        treeModel.setRoot(root);
        load(root);
        tree.expandRow(0);
        classModel.set(d.classes());

        cards.show(this, "main");
        changed.run();
        if (!found.isEmpty() && found.get(0).node() >= 0) {
            select(found.get(0).node());
        } else {
            why.setText(html("<p style='color:#858b94'>Select an object to see the chain of references"
                    + " that keeps it alive.</p>"));
        }
    }

    private JPanel card(HeapFindings.Finding f, boolean first) {
        JPanel c = new JPanel(new BorderLayout(0, 6));
        Color line = f.kind() == HeapFindings.Kind.HOLDER ? new Color(0xDC2626) : new Color(0x9CA3AF);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, line),
                BorderFactory.createEmptyBorder(6, 12, 6, 6)));
        c.setAlignmentX(LEFT_ALIGNMENT);
        JLabel t = new JLabel("<html><div style='width:560px'><b style='font-size:1.15em'>" + esc(f.title())
                + "</b><br><span style='color:#6b7280'>" + esc(f.detail()) + "</span></div></html>");
        c.add(t, BorderLayout.CENTER);
        if (f.node() >= 0) {
            JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JButton w = new JButton("Why is it alive?");
            w.addActionListener(e -> select(f.node()));
            JButton h = new JButton("What it holds");
            h.addActionListener(e -> showInTree(f.node()));
            b.add(w);
            b.add(Box.createHorizontalStrut(6));
            b.add(h);
            c.add(b, BorderLayout.SOUTH);
        }
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    /** The classes with the most memory, as bars. */
    private JPanel topClasses() {
        HeapDump d = dump;
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(LEFT_ALIGNMENT);
        JLabel h = new JLabel("What the memory is made of (the objects themselves, by class)");
        h.setFont(h.getFont().deriveFont(Font.BOLD));
        h.setAlignmentX(LEFT_ALIGNMENT);
        p.add(h);
        p.add(Box.createVerticalStrut(6));
        long heap = Math.max(1, d.totalBytes());
        List<HeapDump.ClassStat> bySize = new ArrayList<>(d.classes());
        bySize.sort((x, y) -> Long.compare(y.shallow(), x.shallow()));
        int shown = 0;
        for (HeapDump.ClassStat s : bySize) {
            if (shown++ == 8) {
                break;
            }
            double share = Math.min(1, (double) s.shallow() / heap);
            JPanel row = new JPanel(new BorderLayout(8, 0)) {
                @Override
                protected void paintComponent(java.awt.Graphics g) {
                    super.paintComponent(g);
                    g.setColor(Theme.isDark() ? new Color(0x1E3A5F) : new Color(0xDBEAFE));
                    g.fillRect(0, 2, (int) (getWidth() * share), getHeight() - 4);
                }
            };
            row.setOpaque(false);
            JLabel name = new JLabel(" " + s.name());
            JLabel val = new JLabel(HeapFindings.bytes(s.shallow()) + "  ·  " + String.format("%,d", s.count())
                    + " objects ");
            val.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            row.add(name, BorderLayout.CENTER);
            row.add(val, BorderLayout.EAST);
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(720, 24));
            row.setPreferredSize(new Dimension(720, 24));
            p.add(row);
        }
        return p;
    }

    // ---------------------------------------------------------------- the tree of what holds what

    private record Item(int node, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    /** Fills a tree node's children (the objects it holds), once. */
    private void load(DefaultMutableTreeNode t) {
        HeapDump d = dump;
        if (d == null || !(t.getUserObject() instanceof Item it) || it.node < 0) {
            return;
        }
        if (t.getChildCount() != 1 || !"…".equals(((DefaultMutableTreeNode) t.getChildAt(0)).getUserObject())) {
            return;
        }
        t.removeAllChildren();
        int[] kids = d.held(it.node, KIDS);
        for (int k : kids) {
            t.add(treeNode(k));
        }
        int more = d.heldCount(it.node) - kids.length;
        if (more > 0) {
            t.add(new DefaultMutableTreeNode(new Item(-1, "… and " + String.format("%,d", more) + " smaller")));
        }
        treeModel.nodeStructureChanged(t);
    }

    private DefaultMutableTreeNode treeNode(int v) {
        HeapDump d = dump;
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Item(v, d.describe(v)));
        if (d.heldCount(v) > 0) {
            n.add(new DefaultMutableTreeNode("…"));
        }
        return n;
    }

    /** Opens the tree down to this object and selects it. */
    void showInTree(int v) {
        HeapDump d = dump;
        if (d == null) {
            return;
        }
        List<Integer> chain = new ArrayList<>();
        for (int x = v; x >= 0 && x != d.root(); x = d.holder(x)) {
            chain.add(0, x);
        }
        DefaultMutableTreeNode cur = (DefaultMutableTreeNode) treeModel.getRoot();
        for (int x : chain) {
            load(cur);
            DefaultMutableTreeNode next = null;
            for (int i = 0; i < cur.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) cur.getChildAt(i);
                if (c.getUserObject() instanceof Item it && it.node == x) {
                    next = c;
                    break;
                }
            }
            if (next == null) { // not among the biggest shown: add it
                next = treeNode(x);
                cur.insert(next, Math.max(0, cur.getChildCount() - 1));
                treeModel.nodeStructureChanged(cur);
            }
            cur = next;
        }
        TreePath path = new TreePath(cur.getPath());
        views.setSelectedIndex(1);
        tree.expandPath(path.getParentPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    private final class NodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean exp, boolean leaf,
                                                      int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
            setIcon(null);
            HeapDump d = dump;
            if (d != null && value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Item it
                    && it.node >= 0) {
                long r = d.retained(it.node);
                String dim = sel ? "" : " color='#858b94'";
                setText("<html><b>" + HeapFindings.bytes(r) + "</b>&nbsp;<font" + dim + ">"
                        + pct(r, d.reachableBytes()) + "</font>&nbsp;&nbsp;" + esc(it.text) + "</html>");
            }
            return this;
        }
    }

    private static String pct(long part, long whole) {
        double p = whole == 0 ? 0 : 100.0 * part / whole;
        return p >= 1 ? Math.round(p) + "%" : p >= 0.1 ? String.format("%.1f%%", p) : "";
    }

    // ---------------------------------------------------------------- why is it alive

    /** Shows why this object is alive (the chain of references from a GC root). */
    void select(int v) {
        HeapDump d = dump;
        if (d == null) {
            return;
        }
        selected = v;
        why.setText(html("<p style='color:#858b94'>Finding the references…</p>"));
        Thread t = new Thread(() -> {
            String text;
            try {
                text = whyHtml(d, v);
            } catch (Exception e) {
                text = "<p>Could not read it: " + esc(String.valueOf(e.getMessage())) + "</p>";
            }
            String done = text;
            SwingUtilities.invokeLater(() -> {
                if (selected == v && dump == d) {
                    why.setText(html(done));
                    why.setCaretPosition(0);
                }
            });
        }, "logsift-heap-why");
        t.setDaemon(true);
        t.start();
    }

    private static String whyHtml(HeapDump d, int v) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<p style='font-size:1.1em'><b>").append(esc(d.describe(v))).append("</b><br>")
                .append("<span style='color:#858b94'>holds ").append(HeapFindings.bytes(d.retained(v)))
                .append(" · itself ").append(HeapFindings.bytes(d.shallow(v))).append("</span></p>");
        List<HeapDump.Step> path = d.pathFromRoot(v);
        if (path.isEmpty()) {
            sb.append("<p>Nothing keeps it alive through strong references: only weak or soft references reach it,"
                    + " or it is garbage. It would be freed.</p>");
            return sb.toString();
        }
        sb.append("<p style='color:#858b94'>Kept alive by this chain of references, from a GC root:</p>");
        for (int i = 0; i < path.size(); i++) {
            HeapDump.Step s = path.get(i);
            if (i == 0) {
                sb.append("<p style='margin-top:4px'><span style='color:#dc2626'>●</span> <b>GC root:</b> ")
                        .append(esc(s.via())).append("</p>");
            } else {
                sb.append("<p style='margin:2px 0 2px 14px;color:#2563eb'>↓ ").append(esc(s.via())).append("</p>");
            }
            boolean last = i == path.size() - 1;
            sb.append("<p style='margin:0 0 0 14px'>").append(last ? "<b>" : "")
                    .append("<a href='node:").append(s.node()).append("'>").append(esc(d.describe(s.node())))
                    .append("</a>").append(last ? "</b>" : "")
                    .append(" <span style='color:#858b94'>· holds ").append(HeapFindings.bytes(d.retained(s.node())))
                    .append("</span></p>");
        }
        sb.append("<p style='color:#858b94;margin-top:10px'>To free it, the reference marked ↓ somewhere in this"
                + " chain has to go: usually the collection that keeps adding to it.</p>");
        return sb.toString();
    }

    /** Shows why the biggest object of a class is alive. */
    private void selectBiggestOf(String className) {
        HeapDump d = dump;
        if (d == null) {
            return;
        }
        Thread t = new Thread(() -> {
            int[] top = d.objectsOf(className, 1);
            if (top.length > 0) {
                SwingUtilities.invokeLater(() -> select(top[0]));
            }
        }, "logsift-heap-class");
        t.setDaemon(true);
        t.start();
    }

    private static String html(String body) {
        return "<html><body style='font-family:sans-serif;margin:6px 10px'>" + body + "</body></html>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- classes

    private static final class ClassModel extends AbstractTableModel {
        List<HeapDump.ClassStat> rows = List.of();

        void set(List<HeapDump.ClassStat> r) {
            rows = r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Class", "Objects", "Size", "Retained"}[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? String.class : Long.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            HeapDump.ClassStat s = rows.get(r);
            return switch (c) {
                case 0 -> s.name();
                case 1 -> s.count();
                case 2 -> s.shallow();
                default -> s.retained();
            };
        }
    }

    // ---------------------------------------------------------------- for tests

    String findingsTextForTest() {
        StringBuilder sb = new StringBuilder();
        for (Component c : findings.getComponents()) {
            if (c instanceof JPanel p) {
                for (Component k : p.getComponents()) {
                    if (k instanceof JLabel l) {
                        sb.append(l.getText().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim()).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    String whyTextForTest() {
        try {
            javax.swing.text.Document doc = why.getDocument();
            return doc.getText(0, doc.getLength());
        } catch (javax.swing.text.BadLocationException e) {
            return "";
        }
    }

    boolean isLoadedForTest() {
        return dump != null;
    }

    void showTabForTest(int i) {
        views.setSelectedIndex(i);
    }

    JTable classTableForTest() {
        return classTable;
    }
}
