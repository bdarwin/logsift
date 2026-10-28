package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import logsift.core.ColumnStats;
import logsift.core.LogDocument;
import logsift.core.LogFormat;
import logsift.core.ValueTree;

/**
 * The values of a column (loggers, classes, threads...) as a tree with a checkbox each, beside
 * the log: uncheck a branch to hide its lines, or right-click to show only one. Counts are
 * kept up to date while the log grows.
 */
final class ValuesTreePanel extends JPanel {

    /** Told when what is hidden changes. */
    interface Listener {
        void hiddenChanged(ValuesTreePanel panel);

        void closeRequested();
    }

    /** Columns worth a tree, most useful first. */
    static final List<String> PREFERRED = List.of("logger", "class", "component", "category", "module", "name",
            "source", "thread", "process", "host", "file", "client", "user");
    private static final List<String> NEVER = List.of("time", "message", "fields", "level", "msg");

    private final LogDocument doc;
    private final Listener listener;
    private final JComboBox<String> column = new JComboBox<>();
    private final JTextField search = new JTextField();
    private final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(model);
    private final JLabel footer = new JLabel(" ");
    private final JButton showAll = new JButton("Show all");
    private final JProgressBar busy = new JProgressBar();
    private final Timer recount;
    private LogFormat format;
    private ValueTree values;
    private List<ValueTree.Hidden> hidden = List.of();
    private String hiddenColumn;
    private volatile boolean counting;
    private long countedLines = -1;
    private long countMillis;
    private boolean updatingColumns;
    private int generation;

    ValuesTreePanel(LogDocument doc, Listener listener) {
        super(new BorderLayout());
        this.doc = doc;
        this.listener = listener;
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.uiColor("Component.borderColor", Color.GRAY)));
        setPreferredSize(new Dimension(280, 300));
        setMinimumSize(new Dimension(160, 100));

        column.setToolTipText("Which column to group by");
        column.addActionListener(e -> {
            if (!updatingColumns) {
                count();
            }
        });
        JButton close = new JButton("✕");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Hide this panel (View → Tree of Loggers brings it back)");
        close.addActionListener(e -> listener.closeRequested());
        JPanel head = new JPanel(new BorderLayout(4, 0));
        head.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 2));
        head.add(column, BorderLayout.CENTER);
        head.add(close, BorderLayout.EAST);

        search.putClientProperty("JTextField.placeholderText", "Filter the tree");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuild();
            }
        });
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(head, BorderLayout.NORTH);
        JPanel s = new JPanel(new BorderLayout());
        s.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
        s.add(search);
        top.add(s, BorderLayout.CENTER);
        busy.setIndeterminate(true);
        busy.setVisible(false);
        busy.setPreferredSize(new Dimension(10, 3));
        top.add(busy, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(0);
        tree.setToggleClickCount(0);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p == null) {
                    return;
                }
                if (e.isPopupTrigger()) {
                    popup(e, p);
                    return;
                }
                Rectangle r = tree.getPathBounds(p);
                if (SwingUtilities.isLeftMouseButton(e) && r != null && e.getX() < r.x + CHECK_WIDTH) {
                    toggle(node(p));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p != null && e.isPopupTrigger()) {
                    popup(e, p);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                Rectangle r = p == null ? null : tree.getPathBounds(p);
                if (p != null && e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)
                        && r != null && e.getX() >= r.x + CHECK_WIDTH) {
                    showOnly(node(p)); // double-click a name: just that one
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleShown");
        tree.getActionMap().put("toggleShown", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                TreePath p = tree.getSelectionPath();
                if (p != null) {
                    toggle(node(p));
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // rows as wide as the panel again (changing the row height drops cached sizes)
                tree.setRowHeight(1);
                tree.setRowHeight(0);
            }
        });
        add(scroll, BorderLayout.CENTER);

        showAll.putClientProperty("JButton.buttonType", "borderless");
        showAll.addActionListener(e -> {
            if (values != null) {
                values.showAll();
                changed();
            }
        });
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        footer.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        bottom.add(footer, BorderLayout.CENTER);
        bottom.add(showAll, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        showAll.setVisible(false);

        // counts follow the log as it grows (often for small logs, rarely for huge ones)
        recount = new Timer(3000, e -> {
            if (isShowing() && !counting && format != null && doc.lineCount() != countedLines
                    && System.currentTimeMillis() - lastCount > Math.max(3000, countMillis * 10)) {
                count();
            }
        });
        recount.start();
    }

    private long lastCount;

    /** Columns of this format that make a useful tree. */
    static List<String> treeColumns(LogFormat f) {
        List<String> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        for (String p : PREFERRED) {
            if (f.columns().contains(p)) {
                out.add(p);
            }
        }
        for (String c : f.columns()) {
            if (!out.contains(c) && !NEVER.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** The log's layout (re)detected: columns to choose from, and counts. */
    void setFormat(LogFormat f) {
        String keep = (String) column.getSelectedItem();
        format = f;
        updatingColumns = true;
        column.removeAllItems();
        for (String c : treeColumns(f)) {
            column.addItem(c);
        }
        if (keep != null && treeColumns(f).contains(keep)) {
            column.setSelectedItem(keep);
        } else if (hiddenColumn != null && treeColumns(f).contains(hiddenColumn)) {
            column.setSelectedItem(hiddenColumn);
        }
        updatingColumns = false;
        column.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean sel, boolean f) {
                return super.getListCellRendererComponent(l, v == null ? "" : title(format, (String) v), i, sel, f);
            }
        });
        if (f == null || column.getItemCount() == 0) {
            values = null;
            model.setRoot(new DefaultMutableTreeNode());
            footer.setText("No columns in this log");
            return;
        }
        count();
    }

    /** A CSV's column as written ("order_id"); else "logger" → "Loggers". */
    static String title(LogFormat f, String column) {
        return f != null && f.isCsv() ? f.displayName(column) : title(column);
    }

    /** "logger" → "Loggers", "class" → "Classes". */
    static String title(String column) {
        String c = column.substring(0, 1).toUpperCase(Locale.ROOT) + column.substring(1);
        return c.endsWith("s") ? c + "es" : c + "s";
    }

    private void count() {
        String col = (String) column.getSelectedItem();
        LogFormat f = format;
        if (col == null || f == null) {
            return;
        }
        int gen = ++generation;
        counting = true;
        busy.setVisible(true);
        long lines = doc.lineCount();
        COUNTER.execute(() -> {
            ColumnStats.Result r;
            try {
                r = ColumnStats.count(doc, f, col, () -> gen != generation);
            } catch (RuntimeException e) {
                r = null;
            }
            ColumnStats.Result result = r;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                counting = false;
                busy.setVisible(false);
                lastCount = System.currentTimeMillis();
                if (result == null) {
                    return;
                }
                countMillis = result.millis();
                countedLines = lines;
                boolean sameColumn = col.equals(hiddenColumn);
                values = ValueTree.of(result.values());
                if (sameColumn) {
                    restore(values, hidden);
                } else if (!hidden.isEmpty()) {
                    hidden = List.of(); // another column: nothing hidden any more
                    hiddenColumn = col;
                    listener.hiddenChanged(this);
                }
                hiddenColumn = col;
                rebuild();
            });
        });
    }

    private static final java.util.concurrent.ExecutorService COUNTER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "logsift-tree-count");
                t.setDaemon(true);
                return t;
            });

    /** Hides again what was hidden (new values under a hidden branch stay hidden too). */
    private static void restore(ValueTree t, List<ValueTree.Hidden> hidden) {
        for (ValueTree.Hidden h : hidden) {
            ValueTree.Node n = t.find(h.path());
            if (n == null) {
                continue;
            }
            if (h.withChildren()) {
                t.setShown(n, false);
            } else {
                t.setOwnShown(n, false);
            }
        }
    }

    // ---------------------------------------------------------------- the tree

    private void rebuild() {
        if (values == null) {
            return;
        }
        // keep what was open
        List<String> open = new ArrayList<>();
        DefaultMutableTreeNode oldRoot = (DefaultMutableTreeNode) model.getRoot();
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            if (tree.isExpanded(p) && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject()
                    instanceof ValueTree.Node n) {
                open.add(n.path());
            }
        }
        boolean first = oldRoot.getChildCount() == 0;
        String q = search.getText().strip().toLowerCase(Locale.ROOT);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(values.root());
        addChildren(root, values.root(), q);
        model.setRoot(root);
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            ValueTree.Node n = node(p);
            boolean wasOpen = n != null && open.contains(n.path());
            // open the first levels at the start, and everything that matches a search
            if (wasOpen || !q.isEmpty() || (first && p.getPathCount() <= 2 && values.root().children().size() <= 8)) {
                tree.expandRow(i);
            }
        }
        updateFooter();
    }

    private static void addChildren(DefaultMutableTreeNode into, ValueTree.Node n, String q) {
        for (ValueTree.Node c : n.children()) {
            if (!q.isEmpty() && !matches(c, q)) {
                continue;
            }
            DefaultMutableTreeNode t = new DefaultMutableTreeNode(c);
            into.add(t);
            addChildren(t, c, q.isEmpty() || c.path().toLowerCase(Locale.ROOT).contains(q) ? "" : q);
        }
    }

    private static boolean matches(ValueTree.Node n, String q) {
        if (n.path().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (ValueTree.Node c : n.children()) {
            if (matches(c, q)) {
                return true;
            }
        }
        return false;
    }

    private static ValueTree.Node node(TreePath p) {
        return p != null && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ValueTree.Node n
                ? n : null;
    }

    private void toggle(ValueTree.Node n) {
        if (n == null || values == null) {
            return;
        }
        values.setShown(n, values.state(n) != ValueTree.State.ALL);
        changed();
    }

    private void showOnly(ValueTree.Node n) {
        if (n == null || values == null) {
            return;
        }
        values.showOnly(n);
        changed();
    }

    private void popup(MouseEvent e, TreePath p) {
        ValueTree.Node n = node(p);
        if (n == null) {
            return;
        }
        tree.setSelectionPath(p);
        JPopupMenu m = new JPopupMenu();
        JMenuItem only = new JMenuItem("Show Only " + n.path());
        only.addActionListener(a -> showOnly(n));
        JMenuItem hide = new JMenuItem("Hide " + n.path());
        hide.addActionListener(a -> {
            values.setShown(n, false);
            changed();
        });
        JMenuItem all = new JMenuItem("Show All");
        all.addActionListener(a -> {
            values.showAll();
            changed();
        });
        JMenuItem expand = new JMenuItem("Expand All");
        expand.addActionListener(a -> {
            for (int i = 0; i < tree.getRowCount(); i++) {
                tree.expandRow(i);
            }
        });
        JMenuItem collapse = new JMenuItem("Collapse All");
        collapse.addActionListener(a -> {
            for (int i = tree.getRowCount() - 1; i >= 0; i--) {
                tree.collapseRow(i);
            }
        });
        m.add(only);
        m.add(hide);
        m.add(all);
        m.addSeparator();
        m.add(expand);
        m.add(collapse);
        m.show(tree, e.getX(), e.getY());
    }

    private void changed() {
        hidden = values.hidden();
        hiddenColumn = (String) column.getSelectedItem();
        tree.repaint();
        updateFooter();
        listener.hiddenChanged(this);
    }

    private void updateFooter() {
        if (values == null) {
            return;
        }
        LogFormat f = format;
        String what = f != null && f.isCsv() && hiddenColumn != null
                ? (values.valueCount() == 1 ? "value in " : "values in ") + f.displayName(hiddenColumn)
                : title(hiddenColumn == null ? "value" : hiddenColumn).toLowerCase(Locale.ROOT);
        footer.setText(hidden.isEmpty() ? String.format("%,d %s", values.valueCount(), what)
                : String.format("%,d of %,d lines shown", values.shownLines(), values.root().total()));
        showAll.setVisible(!hidden.isEmpty());
    }

    // ---------------------------------------------------------------- for LogTab

    /** The column whose values are hidden, or null. */
    String hiddenColumn() {
        return hidden.isEmpty() ? null : hiddenColumn;
    }

    List<ValueTree.Hidden> hidden() {
        return hidden;
    }

    /** Hides these values again (remembered for the file); applied once counted. */
    void restoreHidden(String col, List<ValueTree.Hidden> h) {
        hiddenColumn = col;
        hidden = List.copyOf(h);
        if (values != null && col.equals(column.getSelectedItem())) {
            restore(values, hidden);
            rebuild();
        } else if (format != null && treeColumns(format).contains(col)) {
            column.setSelectedItem(col);
        }
        listener.hiddenChanged(this);
    }

    void showAllValues() {
        if (values != null && !hidden.isEmpty()) {
            values.showAll();
            changed();
        }
    }

    void dispose() {
        recount.stop();
        generation++;
    }

    // for tests
    JTree treeForTest() {
        return tree;
    }

    ValueTree valuesForTest() {
        return values;
    }

    boolean isCountingForTest() {
        return counting;
    }

    void toggleForTest(String path) {
        toggle(values.find(path));
    }

    void showOnlyForTest(String path) {
        showOnly(values.find(path));
    }

    // ---------------------------------------------------------------- drawing

    private static final int CHECK_WIDTH = 22;

    /** A checkbox (half-checked when part of a branch is hidden), the name and the line count. */
    private final class Renderer extends JPanel implements TreeCellRenderer {
        private final JCheckBox check = new JCheckBox();
        private final JLabel name = new JLabel();
        private final JLabel count = new JLabel();

        Renderer() {
            super(new BorderLayout(4, 0));
            setOpaque(false);
            check.setOpaque(false);
            name.setOpaque(false);
            count.setOpaque(false);
            add(check, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
            add(count, BorderLayout.EAST);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            ValueTree.Node n = value instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof ValueTree.Node x
                    ? x : null;
            if (n == null || values == null) {
                name.setText("");
                count.setText("");
                return this;
            }
            depth = ((DefaultMutableTreeNode) value).getLevel();
            ValueTree.State s = values.state(n);
            check.setSelected(s != ValueTree.State.NONE);
            check.putClientProperty("JButton.selectedState", s == ValueTree.State.SOME ? "indeterminate" : null);
            name.setText(n.label());
            name.setFont(t.getFont().deriveFont(n.isLeaf() ? Font.PLAIN : Font.BOLD));
            Color fg = sel && t.hasFocus() ? Theme.uiColor("Tree.selectionForeground", t.getForeground()) : t.getForeground();
            name.setForeground(s == ValueTree.State.NONE ? Theme.dimmedForeground() : fg);
            count.setText(String.format("%,d", n.total()));
            count.setFont(t.getFont().deriveFont(t.getFont().getSize2D() - 1));
            count.setForeground(sel && t.hasFocus() ? fg : Theme.uiColor("Label.disabledForeground", Color.GRAY));
            setToolTipText(n.path() + (n.isValue() && !n.isLeaf() ? String.format(" (%,d lines of its own)", n.own()) : ""));
            return this;
        }

        private int depth;

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            // as wide as the panel allows, so the counts line up on the right
            int step = 20;
            if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
                step = ui.getLeftChildIndent() + ui.getRightChildIndent();
            }
            int view = tree.getParent() == null ? 0 : tree.getParent().getWidth();
            // rows start one indent in per level (the root is hidden, its children have handles)
            int w = view - depth * step - 6;
            return new Dimension(Math.max(d.width, w), d.height + 2);
        }
    }
}
