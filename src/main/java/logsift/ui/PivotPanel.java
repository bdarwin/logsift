package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.ColumnFilter;
import logsift.core.Pivot;

/**
 * A pivot table and chart of a table's rows, as in a spreadsheet: drag fields (the columns) to
 * Rows, Columns, Values and Filters, or tick them; the table groups rows with subtotals and
 * totals, sums, counts or averages the values, and the chart shows it. Double-click a cell to see
 * its rows in the log.
 */
final class PivotPanel extends JPanel {

    interface Host {
        /** What the columns hold (on a background thread; waits for the file); null if cancelled. */
        Pivot.Fields fields(AtomicBoolean cancel);

        /** Computes the pivot (on a background thread); null if cancelled. */
        Pivot.Result compute(Pivot.Spec spec, Pivot.Fields fields, AtomicBoolean cancel, DoubleConsumer progress);

        /** Shows only the rows where a column has this value (a cell was opened). */
        void filter(String column, ColumnFilter.Op op, String text);

        /** A name for this file, to remember its layout by; null: not remembered. */
        default String rememberKey() {
            return null;
        }
    }

    /** The four areas fields go to. */
    enum Area {
        FILTERS("Filters"), COLUMNS("Columns"), ROWS("Rows"), VALUES("Values");

        final String label;

        Area(String label) {
            this.label = label;
        }
    }

    /** A field in an area, with how it groups (rows, columns), sums up (values) or filters. */
    static final class Item {
        final int column;
        Pivot.Group group = Pivot.Group.NONE;
        Pivot.Agg agg = Pivot.Agg.SUM;
        Pivot.ShowAs showAs = Pivot.ShowAs.VALUE;
        Set<String> keep; // filters: the values kept (null: all)

        Item(int column) {
            this.column = column;
        }
    }

    private final Host host;
    private Pivot.Fields fields;
    private Pivot.Result result;
    private final Map<Area, DefaultListModel<Item>> areas = new HashMap<>();
    private final Map<Area, JList<Item>> areaLists = new HashMap<>();
    private final DefaultListModel<Integer> fieldModel = new DefaultListModel<>();
    private final JList<Integer> fieldList = new JList<>(fieldModel);
    private final JTextField fieldFilter = new JTextField(10);
    private final JLabel status = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JToggleButton tableButton = new JToggleButton("Table");
    private final JToggleButton chartButton = new JToggleButton("Chart");
    private final JToggleButton bothButton = new JToggleButton("Both");
    private final JComboBox<PivotChart.Type> chartType = new JComboBox<>(PivotChart.Type.values());
    private final JToggleButton heat = new JToggleButton("Color Scale");
    private final JToggleButton subtotals = new JToggleButton("Subtotals", true);
    private final JToggleButton totals = new JToggleButton("Totals", true);
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final PivotChart chart = new PivotChart();
    private final CardLayout views = new CardLayout();
    private final JPanel viewCards = new JPanel(views);
    private final JPanel emptyPanel = new JPanel(new java.awt.GridBagLayout());
    private final JSplitPane both;
    private final JPanel tableHolder = new JPanel(new BorderLayout());
    private final JPanel chartHolder = new JPanel(new BorderLayout());
    private final JLabel empty = new JLabel();
    private final JButton quickStart = new JButton();
    private final Set<List<String>> collapsed = new HashSet<>();
    private int sortValue = -1;      // -1: by label
    private int sortCol = -1;        // the column group sorted by (-1: the total)
    private boolean sortDesc;
    private AtomicBoolean running;
    private String computedFor = "";
    private String wanted = "";
    private boolean dirty = true;
    private final Timer later = new Timer(250, e -> start());
    private String view = AppState.prefs().get("pivot.view", "both");

    PivotPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        later.setRepeats(false);
        for (Area a : Area.values()) {
            areas.put(a, new DefaultListModel<>());
        }
        add(toolbar(), BorderLayout.NORTH);

        // the table
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(table.getRowHeight() + 4);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.getTableHeader().setDefaultRenderer(new HeaderRenderer(table.getTableHeader().getDefaultRenderer()));
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = table.columnAtPoint(e.getPoint());
                if (col >= 0 && SwingUtilities.isLeftMouseButton(e)
                        && table.getTableHeader().getCursor().getType() != Cursor.E_RESIZE_CURSOR) {
                    sortBy(col);
                }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 0 && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    Line l = model.line(row);
                    java.awt.Rectangle r = table.getCellRect(row, 0, false);
                    int indent = 6 + (l.node.depth() - 1) * 16;
                    if (l.node != null && !l.node.isLeaf() && l.node.depth() > 0
                            && e.getX() - r.x < indent + 16) {
                        toggle(l.node);
                    }
                }
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    int col = table.columnAtPoint(e.getPoint());
                    if (row >= 0 && col > 0) {
                        showRows(row, col);
                    } else if (row >= 0) {
                        Line l = model.line(row);
                        if (l.node != null && !l.node.isLeaf() && l.node.depth() > 0) {
                            toggle(l.node);
                        }
                    }
                }
            }
        });
        table.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copy(false);
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        tableHolder.add(tableScroll, BorderLayout.CENTER);
        chart.onPick(this::pickCategory);
        chartHolder.add(chart, BorderLayout.CENTER);
        both = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        both.setResizeWeight(0.5);
        both.setBorder(BorderFactory.createEmptyBorder());
        both.setDividerSize(6);

        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JPanel emptyBox = new JPanel();
        emptyBox.setLayout(new BoxLayout(emptyBox, BoxLayout.Y_AXIS));
        empty.setAlignmentX(CENTER_ALIGNMENT);
        quickStart.setAlignmentX(CENTER_ALIGNMENT);
        quickStart.putClientProperty("JButton.buttonType", "default");
        quickStart.setVisible(false);
        quickStart.addActionListener(e -> quickStart());
        emptyBox.add(empty);
        emptyBox.add(Box.createVerticalStrut(10));
        emptyBox.add(quickStart);
        emptyPanel.add(emptyBox);
        viewCards.add(emptyPanel, "empty");
        viewCards.add(new JPanel(), "view"); // replaced in showView

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewCards, fieldPanel());
        split.setResizeWeight(1);
        split.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean placed;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (!placed && split.getWidth() > 0) {
                    placed = true;
                    split.setDividerLocation(Math.max(200, split.getWidth() - 270));
                }
            }
        });
        add(split, BorderLayout.CENTER);
        showEmpty("Reading the columns…");
    }

    // ---------------------------------------------------------------- the toolbar

    private JComponent toolbar() {
        JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 3));
        p.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        JToggleButton[] seg = {tableButton, bothButton, chartButton};
        String[] keys = {"table", "both", "chart"};
        String[] tips = {"The pivot table", "The table above, its chart below", "The chart"};
        JPanel viewSwitch = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (int i = 0; i < seg.length; i++) {
            g.add(seg[i]);
            seg[i].putClientProperty("JButton.buttonType", "segmented");
            seg[i].putClientProperty("JButton.segmentPosition", i == 0 ? "first" : i == seg.length - 1 ? "last"
                    : "middle");
            seg[i].setToolTipText(tips[i]);
            String k = keys[i];
            seg[i].addActionListener(e -> setView(k));
            viewSwitch.add(seg[i]);
        }
        p.add(viewSwitch);
        chartType.setSelectedItem(chartTypeSaved());
        chartType.setToolTipText("The kind of chart");
        chartType.addActionListener(e -> {
            PivotChart.Type t = (PivotChart.Type) chartType.getSelectedItem();
            chart.setType(t);
            AppState.prefs().put("pivot.chart", t.name());
            updateChart();
        });
        chart.setType(chartTypeSaved());
        p.add(chartType);
        for (JToggleButton b : List.of(subtotals, totals, heat)) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            p.add(b);
        }
        subtotals.setToolTipText("A total on each group's row");
        totals.setToolTipText("Grand totals: a last row, and a total column per value");
        heat.setToolTipText("Colour each cell by its value, from low to high");
        subtotals.addActionListener(e -> model.rebuild());
        totals.addActionListener(e -> model.rebuild());
        heat.addActionListener(e -> table.repaint());
        JButton expand = new JButton(Icons.of("chevron-down"));
        expand.setToolTipText("Expand all groups");
        expand.putClientProperty("JButton.buttonType", "toolBarButton");
        expand.addActionListener(e -> {
            collapsed.clear();
            model.rebuild();
        });
        JButton collapse = new JButton(Icons.of("chevron-up"));
        collapse.setToolTipText("Collapse all groups (the outermost stay)");
        collapse.putClientProperty("JButton.buttonType", "toolBarButton");
        collapse.addActionListener(e -> collapseAll());
        p.add(expand);
        p.add(collapse);
        JButton export = new JButton("Export ▾", Icons.of("download"));
        export.addActionListener(e -> exportMenu(export));
        p.add(export);
        JButton refresh = new JButton(Icons.reload());
        refresh.setToolTipText("Read the file again (it may have grown)");
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.addActionListener(e -> {
            dirty = true;
            start();
        });
        p.add(refresh);
        bar.setPreferredSize(new Dimension(90, 10));
        bar.setVisible(false);
        p.add(bar);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        p.add(status);
        return p;
    }

    private static PivotChart.Type chartTypeSaved() {
        try {
            return PivotChart.Type.valueOf(AppState.prefs().get("pivot.chart", "COLUMN"));
        } catch (IllegalArgumentException e) {
            return PivotChart.Type.COLUMN;
        }
    }

    private void setView(String v) {
        view = v;
        try {
            AppState.prefs().put("pivot.view", v);
        } catch (RuntimeException e) {
            // remembered next time if it can be
        }
        showView();
    }

    /** Shows the table, the chart or both (or what to do, without fields). */
    private void showView() {
        (view.equals("table") ? tableButton : view.equals("chart") ? chartButton : bothButton).setSelected(true);
        chartType.setVisible(!view.equals("table"));
        if (result == null || areas.get(Area.ROWS).isEmpty() && areas.get(Area.COLUMNS).isEmpty()
                && areas.get(Area.VALUES).isEmpty()) {
            return;
        }
        Component current = null;
        for (Component c : viewCards.getComponents()) {
            if (c.isVisible()) {
                current = c;
            }
        }
        JComponent want = switch (view) {
            case "table" -> tableHolder;
            case "chart" -> chartHolder;
            default -> both;
        };
        if (want == both && (both.getTopComponent() != tableHolder || both.getBottomComponent() != chartHolder)) {
            both.setTopComponent(tableHolder);
            both.setBottomComponent(chartHolder);
            tableHolder.setVisible(true); // a card hidden while it was one of the cards
            chartHolder.setVisible(true);
            current = null; // placed again: the divider goes to the middle
        }
        // Putting the table or chart into the split takes it out of the cards, so the cards may
        // hold just the empty panel here: place what is wanted by its parent, not by position.
        if (want.getParent() != viewCards) {
            for (Component c : viewCards.getComponents()) {
                if (c != emptyPanel) {
                    viewCards.remove(c);
                }
            }
            viewCards.add(want, "view");
        }
        views.show(viewCards, "view");
        if (want == both && current != both) {
            SwingUtilities.invokeLater(() -> both.setDividerLocation(0.5));
        }
        revalidate();
        repaint();
    }

    private void showEmpty(String text) {
        empty.setText(text);
        views.show(viewCards, "empty");
    }

    // ---------------------------------------------------------------- the fields and areas

    private JComponent fieldPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        p.setMinimumSize(new Dimension(200, 100));
        JPanel head = new JPanel(new BorderLayout(0, 4));
        JLabel title = new JLabel("Fields");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        head.add(title, BorderLayout.NORTH);
        fieldFilter.putClientProperty("JTextField.placeholderText", "find a field");
        fieldFilter.putClientProperty("JTextField.showClearButton", true);
        fieldFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fillFields();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fillFields();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        head.add(fieldFilter, BorderLayout.CENTER);
        p.add(head, BorderLayout.NORTH);

        fieldList.setCellRenderer(new FieldRenderer());
        fieldList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fieldList.setDragEnabled(true);
        fieldList.setTransferHandler(new Dnd(null));
        fieldList.setDropMode(DropMode.ON);
        fieldList.setToolTipText("Tick a field, or drag it to an area below");
        fieldList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int i = fieldList.locationToIndex(e.getPoint());
                if (i < 0 || !fieldList.getCellBounds(i, i).contains(e.getPoint())) {
                    return;
                }
                if (e.getX() < 24 || e.getClickCount() == 2) {
                    tickField(fieldModel.get(i));
                }
            }
        });
        JScrollPane fs = new JScrollPane(fieldList);
        fs.setPreferredSize(new Dimension(220, 160));
        JLabel hint = new JLabel("Drag fields between the areas:");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JPanel grid = new JPanel(new GridLayout(2, 2, 6, 6));
        for (Area a : List.of(Area.FILTERS, Area.COLUMNS, Area.ROWS, Area.VALUES)) {
            grid.add(areaBox(a));
        }
        JPanel lower = new JPanel(new BorderLayout(0, 4));
        lower.add(hint, BorderLayout.NORTH);
        lower.add(grid, BorderLayout.CENTER);
        JSplitPane s = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fs, lower);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.setResizeWeight(0.45);
        s.setDividerSize(6);
        p.add(s, BorderLayout.CENTER);
        return p;
    }

    private JComponent areaBox(Area a) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        JLabel l = new JLabel(switch (a) {
            case FILTERS -> "▼ Filters";
            case COLUMNS -> "▥ Columns";
            case ROWS -> "▤ Rows";
            case VALUES -> "Σ Values";
        });
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() - 1f));
        p.add(l, BorderLayout.NORTH);
        JList<Item> list = new JList<>(areas.get(a));
        list.setCellRenderer(new ItemRenderer(a));
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new Dnd(a));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.pick(new Color(0xF7F8FA), new Color(0x26282C)));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    itemMenu(a, i, list, e.getX(), e.getY());
                }
            }
        });
        areaLists.put(a, list);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(Theme.uiColor("Component.borderColor", new Color(0xD1D5DB))));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /** The column's title. */
    private String title(int column) {
        return fields == null || column < 0 || column >= fields.titles().size() ? "?" : fields.titles().get(column);
    }

    private void fillFields() {
        fieldModel.clear();
        if (fields == null) {
            return;
        }
        String q = fieldFilter.getText().strip().toLowerCase(Locale.ROOT);
        for (int c = 0; c < fields.titles().size(); c++) {
            if (q.isEmpty() || fields.titles().get(c).toLowerCase(Locale.ROOT).contains(q)) {
                fieldModel.addElement(c);
            }
        }
    }

    private boolean used(int column) {
        for (DefaultListModel<Item> m : areas.values()) {
            for (int i = 0; i < m.size(); i++) {
                if (m.get(i).column == column) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ticking a field: numbers to Values (Sum), times to Rows by month, text to Rows; unticking takes it out. */
    void tickField(int column) {
        if (used(column)) {
            for (DefaultListModel<Item> m : areas.values()) {
                for (int i = m.size() - 1; i >= 0; i--) {
                    if (m.get(i).column == column) {
                        m.remove(i);
                    }
                }
            }
        } else {
            Pivot.Kind k = fields.kind(column);
            Area a = k == Pivot.Kind.NUMBER ? Area.VALUES : Area.ROWS;
            areas.get(a).addElement(newItem(column, a));
        }
        changed();
    }

    private Item newItem(int column, Area a) {
        Item it = new Item(column);
        Pivot.Kind k = fields == null ? Pivot.Kind.TEXT : fields.kind(column);
        if (a == Area.VALUES) {
            it.agg = k == Pivot.Kind.NUMBER ? Pivot.Agg.SUM : Pivot.Agg.COUNT;
        }
        if ((a == Area.ROWS || a == Area.COLUMNS) && k == Pivot.Kind.TIME) {
            it.group = Pivot.Group.MONTH;
        }
        return it;
    }

    /** Puts a field in an area (tests and quick start). */
    void addField(Area a, int column) {
        areas.get(a).addElement(newItem(column, a));
        changed();
    }

    /** The menu of a field in an area: how it groups or sums up, where it goes, its filter. */
    private void itemMenu(Area a, int index, JComponent over, int x, int y) {
        DefaultListModel<Item> m = areas.get(a);
        Item it = m.get(index);
        JPopupMenu menu = new JPopupMenu();
        if (a == Area.VALUES) {
            JMenu sum = new JMenu("Summarize by");
            javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
            for (Pivot.Agg agg : Pivot.Agg.values()) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(agg.label, it.agg == agg);
                r.setEnabled(!agg.numeric() || fields.kind(it.column) == Pivot.Kind.NUMBER);
                r.addActionListener(e -> {
                    it.agg = agg;
                    changed();
                });
                g.add(r);
                sum.add(r);
            }
            menu.add(sum);
            JMenu show = new JMenu("Show values as");
            javax.swing.ButtonGroup g2 = new javax.swing.ButtonGroup();
            for (Pivot.ShowAs s : Pivot.ShowAs.values()) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(s.label, it.showAs == s);
                r.addActionListener(e -> {
                    it.showAs = s;
                    changed();
                });
                g2.add(r);
                show.add(r);
            }
            menu.add(show);
        }
        if ((a == Area.ROWS || a == Area.COLUMNS) && fields.kind(it.column) == Pivot.Kind.TIME) {
            JMenu group = new JMenu("Group by");
            javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
            for (Pivot.Group gr : Pivot.Group.values()) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(gr.label, it.group == gr);
                r.addActionListener(e -> {
                    it.group = gr;
                    changed();
                });
                g.add(r);
                group.add(r);
            }
            menu.add(group);
        }
        if (a == Area.FILTERS) {
            JMenuItem choose = new JMenuItem("Choose Values…");
            choose.addActionListener(e -> chooseValues(it, over));
            menu.add(choose);
            JMenuItem all = new JMenuItem("Keep All Values");
            all.setEnabled(it.keep != null);
            all.addActionListener(e -> {
                it.keep = null;
                changed();
            });
            menu.add(all);
        }
        if (menu.getComponentCount() > 0) {
            menu.addSeparator();
        }
        JMenuItem up = new JMenuItem("Move Up");
        up.setEnabled(index > 0);
        up.addActionListener(e -> {
            m.remove(index);
            m.add(index - 1, it);
            changed();
        });
        JMenuItem down = new JMenuItem("Move Down");
        down.setEnabled(index < m.size() - 1);
        down.addActionListener(e -> {
            m.remove(index);
            m.add(index + 1, it);
            changed();
        });
        menu.add(up);
        menu.add(down);
        for (Area to : Area.values()) {
            if (to != a) {
                JMenuItem mv = new JMenuItem("Move to " + to.label);
                mv.addActionListener(e -> {
                    m.remove(index);
                    areas.get(to).addElement(newItem(it.column, to));
                    changed();
                });
                menu.add(mv);
            }
        }
        menu.addSeparator();
        JMenuItem remove = new JMenuItem("Remove");
        remove.addActionListener(e -> {
            m.remove(index);
            changed();
        });
        menu.add(remove);
        menu.show(over, x, y);
    }

    /** A filter's values, ticked in a list (with a filter box). */
    private void chooseValues(Item it, JComponent over) {
        List<String> values = result == null ? List.of() : result.filterValues(it.column);
        List<Integer> ticked = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (it.keep == null || it.keep.contains(values.get(i))) {
                ticked.add(i);
            }
        }
        ColumnPicker p = new ColumnPicker("Keep rows where " + title(it.column) + " is", "Untick values to leave out",
                values, ticked, got -> {
                    Set<String> keep = new LinkedHashSet<>();
                    for (int i : got) {
                        keep.add(values.get(i));
                    }
                    it.keep = keep.size() == values.size() ? null : keep;
                    changed();
                });
        p.show(over);
    }

    /** Something in the areas changed: compute again soon, remember the layout. */
    private void changed() {
        dirty = true;
        fieldList.repaint();
        for (JList<Item> l : areaLists.values()) {
            l.repaint();
        }
        remember();
        later.restart();
    }

    /** Drag and drop of fields: from the list to an area, between areas, back out to remove. */
    private final class Dnd extends TransferHandler {
        private final Area area; // null: the field list

        Dnd(Area area) {
            this.area = area;
        }

        @Override
        public int getSourceActions(JComponent c) {
            return area == null ? COPY : MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            if (area == null) {
                Integer col = fieldList.getSelectedValue();
                return col == null ? null : new StringSelection("field:" + col);
            }
            int i = areaLists.get(area).getSelectedIndex();
            return i < 0 ? null : new StringSelection("item:" + area.name() + ":" + i);
        }

        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport s) {
            String data;
            try {
                data = (String) s.getTransferable().getTransferData(DataFlavor.stringFlavor);
            } catch (Exception e) {
                return false;
            }
            int at = -1;
            if (s.isDrop() && s.getDropLocation() instanceof JList.DropLocation dl) {
                at = dl.getIndex();
            }
            return drop(data, area, at);
        }
    }

    /** A field (or an area's item) dropped on an area, or (to == null) back on the field list. */
    boolean drop(String data, Area to, int at) {
        if (fields == null) {
            return false;
        }
        String[] p = data.split(":");
        Item moved;
        if (p[0].equals("field") && p.length == 2) {
            if (to == null) {
                return false;
            }
            int col = Integer.parseInt(p[1]);
            moved = newItem(col, to);
            if (to != Area.VALUES) {
                // a field is in one of the other areas at most: it moves
                for (Area a : List.of(Area.FILTERS, Area.COLUMNS, Area.ROWS)) {
                    DefaultListModel<Item> m = areas.get(a);
                    for (int i = m.size() - 1; i >= 0; i--) {
                        if (m.get(i).column == col) {
                            m.remove(i);
                        }
                    }
                }
            }
        } else if (p[0].equals("item") && p.length == 3) {
            Area from = Area.valueOf(p[1]);
            int i = Integer.parseInt(p[2]);
            DefaultListModel<Item> m = areas.get(from);
            if (i < 0 || i >= m.size()) {
                return false;
            }
            moved = m.remove(i);
            if (to == null) {
                changed(); // dragged out: removed
                return true;
            }
            if (from == to && at > i) {
                at--;
            }
            if (from != to) {
                Item fresh = newItem(moved.column, to);
                if (to != Area.VALUES && from != Area.VALUES) {
                    fresh.group = moved.group;
                }
                moved = fresh;
            }
        } else {
            return false;
        }
        DefaultListModel<Item> m = areas.get(to);
        if (at < 0 || at > m.size()) {
            at = m.size();
        }
        m.add(at, moved);
        changed();
        return true;
    }

    // ---------------------------------------------------------------- computing

    /** The filters of the log changed (Matching only): compute again when shown. */
    void filtersChanged(String key) {
        wanted = key;
        if (!key.equals(computedFor) && isShowing()) {
            later.restart();
        }
    }

    /** The tab is shown: reads the fields, or computes again if something changed. */
    void showing() {
        if (running == null && (fields == null || dirty || !wanted.equals(computedFor))) {
            start();
        }
    }

    void cancel() {
        later.stop();
        if (running != null) {
            running.set(true);
            running = null;
        }
    }

    boolean isRunning() {
        return running != null || later.isRunning();
    }

    Pivot.Result result() {
        return result;
    }

    /** What the areas ask for. */
    Pivot.Spec spec() {
        List<Pivot.Field> rows = new ArrayList<>();
        List<Pivot.Field> cols = new ArrayList<>();
        List<Pivot.Value> vals = new ArrayList<>();
        Map<Integer, Set<String>> filters = new HashMap<>();
        DefaultListModel<Item> r = areas.get(Area.ROWS);
        for (int i = 0; i < r.size(); i++) {
            rows.add(new Pivot.Field(r.get(i).column, r.get(i).group));
        }
        DefaultListModel<Item> c = areas.get(Area.COLUMNS);
        for (int i = 0; i < c.size(); i++) {
            cols.add(new Pivot.Field(c.get(i).column, c.get(i).group));
        }
        DefaultListModel<Item> v = areas.get(Area.VALUES);
        for (int i = 0; i < v.size(); i++) {
            vals.add(new Pivot.Value(v.get(i).column, v.get(i).agg, v.get(i).showAs));
        }
        DefaultListModel<Item> f = areas.get(Area.FILTERS);
        for (int i = 0; i < f.size(); i++) {
            filters.put(f.get(i).column, f.get(i).keep);
        }
        return new Pivot.Spec(rows, cols, vals, filters);
    }

    void start() {
        later.stop();
        if (running != null) {
            running.set(true);
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        String key = wanted;
        Pivot.Spec spec = fields == null ? null : spec();
        bar.setValue(0);
        bar.setVisible(true);
        status.setText(fields == null ? "Reading the columns…" : "Counting…");
        Pivot.Fields known = fields;
        Thread t = new Thread(() -> {
            Pivot.Fields f = known;
            Pivot.Result r = null;
            String error = null;
            try {
                if (f == null) {
                    f = host.fields(cancel);
                }
                if (f != null && !cancel.get()) {
                    Pivot.Spec s = spec != null ? spec : new Pivot.Spec(List.of(), List.of(), List.of(), Map.of());
                    if (spec == null) {
                        s = restoredSpec(f);
                    }
                    r = host.compute(s, f, cancel, p -> SwingUtilities.invokeLater(() -> bar.setValue((int) (p * 100))));
                }
            } catch (RuntimeException e) {
                logsift.core.Problems.unexpected(e);
                error = e.getMessage();
            }
            Pivot.Fields gotFields = f;
            Pivot.Result got = r;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                bar.setVisible(false);
                if (failed != null) {
                    status.setText("Couldn't read the file: " + failed);
                    return;
                }
                if (gotFields == null || got == null) {
                    status.setText(" ");
                    return;
                }
                boolean first = fields == null;
                fields = gotFields;
                if (first) {
                    fillFields();
                    restoreAreas(got.spec());
                }
                computedFor = key;
                dirty = false;
                show(got);
            });
        }, "logsift-pivot");
        t.setDaemon(true);
        t.start();
    }

    private void show(Pivot.Result r) {
        result = r;
        status.setText(String.format(Locale.ROOT, "%,d rows · %,d ms%s", r.rows(), r.millis(),
                r.rowsCapped() || r.colsCapped() ? " · too many groups: the rest are in (other)" : ""));
        if (sortValue >= r.spec().cellValues().size()) {
            sortValue = -1;
        }
        r.sort(sortValue, sortCol, sortDesc);
        boolean nothing = areas.get(Area.ROWS).isEmpty() && areas.get(Area.COLUMNS).isEmpty()
                && areas.get(Area.VALUES).isEmpty();
        if (nothing) {
            Pivot.Fields f = fields;
            int text = -1;
            int num = -1;
            for (int c = 0; c < f.titles().size(); c++) {
                if (text < 0 && f.kind(c) == Pivot.Kind.TEXT) {
                    text = c;
                }
                if (num < 0 && f.kind(c) == Pivot.Kind.NUMBER) {
                    num = c;
                }
            }
            showEmpty("<html><div style='text-align:center'>Tick fields on the right, or drag them to <b>Rows</b>,"
                    + " <b>Columns</b> and <b>Values</b>.<br>Text and times group the rows; numbers are summed up."
                    + String.format(Locale.ROOT, "<br>%,d rows.</div></html>", r.rows()));
            quickStart.setVisible(text >= 0);
            quickStart.setText(text < 0 ? "" : num >= 0 ? "Sum of " + f.titles().get(num) + " by " + f.titles().get(text)
                    : "Count of rows by " + f.titles().get(text));
            model.rebuild();
            return;
        }
        quickStart.setVisible(false);
        model.rebuild();
        showView();
    }

    /** The quick start: the first text field down the side, the first number summed (or rows counted). */
    private void quickStart() {
        Pivot.Fields f = fields;
        for (int c = 0; c < f.titles().size(); c++) {
            if (f.kind(c) == Pivot.Kind.TEXT) {
                areas.get(Area.ROWS).addElement(newItem(c, Area.ROWS));
                break;
            }
        }
        for (int c = 0; c < f.titles().size(); c++) {
            if (f.kind(c) == Pivot.Kind.NUMBER) {
                areas.get(Area.VALUES).addElement(newItem(c, Area.VALUES));
                break;
            }
        }
        changed();
    }

    // ---------------------------------------------------------------- remembering the layout

    private void remember() {
        String key = host.rememberKey();
        if (key == null || fields == null) {
            return;
        }
        StringBuilder b = new StringBuilder();
        for (Area a : Area.values()) {
            DefaultListModel<Item> m = areas.get(a);
            for (int i = 0; i < m.size(); i++) {
                Item it = m.get(i);
                b.append(a.name().charAt(0)).append(':').append(fields.titles().get(it.column)).append(':')
                        .append(it.group.name()).append(':').append(it.agg.name()).append(':').append(it.showAs.name())
                        .append('\n');
            }
        }
        try {
            String k = "pivot." + Integer.toHexString(key.hashCode());
            if (b.length() == 0) {
                AppState.prefs().remove(k);
            } else if (b.length() < 8000) {
                AppState.prefs().put(k, b.toString());
            }
        } catch (RuntimeException e) {
            // not remembered
        }
    }

    /** The layout last used for this file (by column title), or nothing. */
    private Pivot.Spec restoredSpec(Pivot.Fields f) {
        List<Pivot.Field> rows = new ArrayList<>();
        List<Pivot.Field> cols = new ArrayList<>();
        List<Pivot.Value> vals = new ArrayList<>();
        Map<Integer, Set<String>> filters = new HashMap<>();
        String key = host.rememberKey();
        String saved = key == null ? null : AppState.prefs().get("pivot." + Integer.toHexString(key.hashCode()), null);
        if (saved != null) {
            for (String line : saved.split("\n")) {
                String[] p = line.split(":", -1);
                if (p.length < 5) {
                    continue;
                }
                int col = f.titles().indexOf(p[1]);
                if (col < 0) {
                    continue;
                }
                try {
                    Pivot.Group g = Pivot.Group.valueOf(p[2]);
                    switch (p[0]) {
                        case "R" -> rows.add(new Pivot.Field(col, g));
                        case "C" -> cols.add(new Pivot.Field(col, g));
                        case "V" -> vals.add(new Pivot.Value(col, Pivot.Agg.valueOf(p[3]), Pivot.ShowAs.valueOf(p[4])));
                        case "F" -> filters.put(col, null);
                        default -> { }
                    }
                } catch (IllegalArgumentException e) {
                    // an old layout: skip the field
                }
            }
        }
        return new Pivot.Spec(rows, cols, vals, filters);
    }

    private void restoreAreas(Pivot.Spec s) {
        for (DefaultListModel<Item> m : areas.values()) {
            m.clear();
        }
        for (Pivot.Field f : s.rows()) {
            Item it = new Item(f.column());
            it.group = f.group();
            areas.get(Area.ROWS).addElement(it);
        }
        for (Pivot.Field f : s.cols()) {
            Item it = new Item(f.column());
            it.group = f.group();
            areas.get(Area.COLUMNS).addElement(it);
        }
        for (Pivot.Value v : s.values()) {
            Item it = new Item(v.column());
            it.agg = v.agg();
            it.showAs = v.showAs();
            areas.get(Area.VALUES).addElement(it);
        }
        for (Integer c : s.filters().keySet()) {
            areas.get(Area.FILTERS).addElement(new Item(c));
        }
        fieldList.repaint();
    }

    // ---------------------------------------------------------------- the table

    /** A line of the table: a group (with its subtotals), or the grand total. */
    record Line(Pivot.Node node, boolean grand) {}

    private final class Model extends AbstractTableModel {
        final List<Line> lines = new ArrayList<>();
        /** Per table column (after the labels): the column group (-1: the total) and the value. */
        final List<int[]> cols = new ArrayList<>();

        Line line(int row) {
            return lines.get(row);
        }

        void rebuild() {
            lines.clear();
            cols.clear();
            Pivot.Result r = result;
            if (r != null && !(areas.get(Area.ROWS).isEmpty() && areas.get(Area.COLUMNS).isEmpty()
                    && areas.get(Area.VALUES).isEmpty())) {
                int nv = r.spec().cellValues().size();
                boolean hasCols = !r.colKeys().isEmpty();
                for (int g = 0; g < r.colKeys().size(); g++) {
                    for (int v = 0; v < nv; v++) {
                        cols.add(new int[] {g, v});
                    }
                }
                if (!hasCols || totals.isSelected()) {
                    for (int v = 0; v < nv; v++) {
                        cols.add(new int[] {-1, v});
                    }
                }
                if (r.spec().rows().isEmpty()) {
                    lines.add(new Line(r.root(), true));
                } else {
                    walk(r.root());
                    if (totals.isSelected()) {
                        lines.add(new Line(r.root(), true));
                    }
                }
            }
            fireTableStructureChanged();
            sizeColumns();
            updateChart();
        }

        private void walk(Pivot.Node n) {
            for (Pivot.Node k : n.kids()) {
                boolean group = !k.isLeaf();
                if (!group || subtotals.isSelected() || collapsed.contains(k.path())) {
                    lines.add(new Line(k, false));
                } else {
                    lines.add(new Line(k, false)); // a header line for the group (its subtotal is blank)
                }
                if (group && !collapsed.contains(k.path())) {
                    walk(k);
                }
            }
        }

        @Override
        public int getRowCount() {
            return lines.size();
        }

        @Override
        public int getColumnCount() {
            return result == null || lines.isEmpty() ? 0 : 1 + cols.size();
        }

        @Override
        public String getColumnName(int c) {
            Pivot.Result r = result;
            if (c == 0) {
                List<String> names = new ArrayList<>();
                for (Pivot.Field f : r.spec().rows()) {
                    names.add(r.fieldTitle(f));
                }
                return names.isEmpty() ? "" : String.join(" › ", names);
            }
            int[] gv = cols.get(c - 1);
            String value = r.valueTitle(gv[1]);
            boolean many = r.spec().cellValues().size() > 1;
            if (gv[0] < 0) {
                return r.colKeys().isEmpty() ? value : "Total" + (many ? " · " + value : "");
            }
            String group = String.join(" · ", r.colKeys().get(gv[0]));
            return many ? group + " · " + value : group;
        }

        /** The column's two-line title: the column group, then the value (when there are several). */
        String[] title(int c) {
            Pivot.Result r = result;
            if (c == 0) {
                return new String[] {getColumnName(0), ""};
            }
            int[] gv = cols.get(c - 1);
            String value = r.valueTitle(gv[1]);
            if (r.colKeys().isEmpty()) {
                return new String[] {value, ""};
            }
            String group = gv[0] < 0 ? "Total" : String.join(" · ", r.colKeys().get(gv[0]));
            return new String[] {group, r.spec().cellValues().size() > 1 || gv[0] < 0 ? value : ""};
        }

        @Override
        public Object getValueAt(int row, int col) {
            Line l = lines.get(row);
            Pivot.Result r = result;
            if (col == 0) {
                return l.grand ? "Grand Total" : l.node.label();
            }
            int[] gv = cols.get(col - 1);
            if (!l.grand && !l.node.isLeaf() && !subtotals.isSelected() && !collapsed.contains(l.node.path())) {
                return null; // no subtotal on the group's line
            }
            return r.value(l.node, gv[0], gv[1]);
        }

        /** The text of a cell, as shown. */
        String text(int row, int col) {
            Object v = getValueAt(row, col);
            if (col == 0) {
                return (String) v;
            }
            if (!(v instanceof Double d) || Double.isNaN(d)) {
                return "";
            }
            int[] gv = cols.get(col - 1);
            boolean pct = result.spec().cellValues().get(gv[1]).showAs() != Pivot.ShowAs.VALUE;
            Line l = lines.get(row);
            boolean atLeast = result.distinctCapped(l.node, gv[0], gv[1]);
            return (atLeast ? "≥ " : "") + format(d) + (pct ? " %" : "");
        }
    }

    private void sizeColumns() {
        // titles of two lines (a column group, then the value) need a taller header
        boolean two = false;
        for (int c = 0; c < table.getColumnCount(); c++) {
            two |= !model.title(c)[1].isEmpty();
        }
        javax.swing.table.JTableHeader h = table.getTableHeader();
        int line = table.getFontMetrics(table.getFont()).getHeight();
        h.setPreferredSize(null);
        h.setPreferredSize(new Dimension(h.getPreferredSize().width, (two ? 2 * line : line) + 10));
        for (int c = 0; c < table.getColumnCount(); c++) {
            int w = c == 0 ? 220 : 110;
            String[] t = model.title(c);
            java.awt.FontMetrics fm = table.getFontMetrics(table.getFont().deriveFont(Font.BOLD));
            w = Math.max(w, Math.min(320, Math.max(fm.stringWidth(t[0]), fm.stringWidth(t[1])) + 24));
            table.getColumnModel().getColumn(c).setPreferredWidth(w);
        }
    }

    /** A number for people: 1,234 or 1,234.57 (at most 2 decimals; small ones keep 3 significant digits). */
    static String format(double v) {
        if (Double.isNaN(v)) {
            return "";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "∞" : "-∞";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%,d", (long) v);
        }
        double a = Math.abs(v);
        String s = a >= 1 ? String.format(Locale.ROOT, "%,.2f", v)
                : new java.math.BigDecimal(v).round(new java.math.MathContext(3)).toPlainString();
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    private void toggle(Pivot.Node n) {
        List<String> p = n.path();
        if (!collapsed.remove(p)) {
            collapsed.add(p);
        }
        int at = table.getSelectedRow();
        model.rebuild();
        if (at >= 0 && at < table.getRowCount()) {
            table.setRowSelectionInterval(at, at);
        }
    }

    private void collapseAll() {
        Pivot.Result r = result;
        if (r == null) {
            return;
        }
        collapsed.clear();
        for (Pivot.Node n : r.level(1)) {
            if (!n.isLeaf()) {
                collapsed.add(n.path());
            }
        }
        model.rebuild();
    }

    /** A click on a column's title: sort by it (the labels A→Z, a value largest first); again: the other way. */
    private void sortBy(int col) {
        Pivot.Result r = result;
        if (r == null) {
            return;
        }
        int value = col == 0 ? -1 : model.cols.get(col - 1)[1];
        int group = col == 0 ? -1 : model.cols.get(col - 1)[0];
        if (value == sortValue && group == sortCol) {
            sortDesc = !sortDesc;
        } else {
            sortValue = value;
            sortCol = group;
            sortDesc = value >= 0;
        }
        r.sort(sortValue, sortCol, sortDesc);
        model.rebuild();
        table.getTableHeader().repaint();
    }

    private final class HeaderRenderer implements javax.swing.table.TableCellRenderer {
        private final javax.swing.table.TableCellRenderer base;

        HeaderRenderer(javax.swing.table.TableCellRenderer base) {
            this.base = base;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            String[] title = model.title(col);
            int value = col == 0 ? -1 : model.cols.get(col - 1)[1];
            int group = col == 0 ? -1 : model.cols.get(col - 1)[0];
            String arrow = value == sortValue && group == sortCol ? (sortDesc ? " ▼" : " ▲") : "";
            String text = "<html><b>" + esc(title[0]) + arrow + "</b>" + (title[1].isEmpty() ? ""
                    : "<br><font color='" + hex(Theme.uiColor("Label.disabledForeground", Color.GRAY)) + "'>"
                    + esc(title[1]) + "</font>") + "</html>";
            Component c = base.getTableCellRendererComponent(t, text, sel, focus, row, col);
            if (c instanceof JLabel l) {
                l.setHorizontalAlignment(col == 0 ? SwingConstants.LEADING : SwingConstants.TRAILING);
                l.setToolTipText("Click to sort by this column; again for the other way");
            }
            return c;
        }
    }

    private final class CellRenderer extends DefaultTableCellRenderer {
        private int depth;
        private boolean group;
        private boolean open;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Line l = model.line(row);
            String text = model.text(row, col);
            super.getTableCellRendererComponent(t, text, sel, focus, row, col);
            depth = col == 0 && !l.grand ? l.node.depth() : 0;
            group = col == 0 && !l.grand && !l.node.isLeaf();
            open = group && !collapsed.contains(l.node.path());
            boolean bold = l.grand || !l.node.isLeaf();
            setFont(bold ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
            setHorizontalAlignment(col == 0 ? SwingConstants.LEADING : SwingConstants.TRAILING);
            int indent = col == 0 ? 6 + Math.max(0, depth - 1) * 16 + (group || depth > 0 ? 16 : 0) : 6;
            setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 8));
            Color bg = l.grand ? Theme.pick(new Color(0xEEF2F7), new Color(0x2B2F36))
                    : !l.node.isLeaf() ? Theme.pick(new Color(0xF5F7FA), new Color(0x26292E))
                    : row % 2 == 1 ? Theme.stripe() : Theme.background();
            if (heat.isSelected() && col > 0 && !l.grand && v instanceof Double d && !Double.isNaN(d)) {
                bg = heatColor(col, d, bg);
            }
            boolean cell = t.isCellSelected(row, col);
            setBackground(CsvCompareTab.selectedBackground(bg, cell, false));
            setForeground(Theme.foreground());
            if (col == 0) {
                setToolTipText(l.grand ? null : String.join(" › ", l.node.path()) + String.format(Locale.ROOT,
                        " · %,d rows", l.node.rows()));
            } else {
                setToolTipText(text.isEmpty() ? null : model.getColumnName(col) + ": " + text
                        + " · double-click to see these rows");
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (group) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.uiColor("Label.disabledForeground", Color.GRAY));
                int x = 6 + Math.max(0, depth - 1) * 16 + 4;
                int y = getHeight() / 2;
                java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
                if (open) {
                    p.moveTo(x, y - 2);
                    p.lineTo(x + 8, y - 2);
                    p.lineTo(x + 4, y + 3);
                } else {
                    p.moveTo(x + 2, y - 4);
                    p.lineTo(x + 7, y);
                    p.lineTo(x + 2, y + 4);
                }
                p.closePath();
                g2.fill(p);
                g2.dispose();
            }
        }
    }

    /** A cell's colour on the colour scale: its column's lowest values pale, the highest green. */
    private Color heatColor(int col, double v, Color bg) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < model.getRowCount(); r++) {
            Line l = model.line(r);
            if (l.grand || !l.node.isLeaf()) {
                continue;
            }
            if (model.getValueAt(r, col) instanceof Double d && !Double.isNaN(d)) {
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
        }
        if (!(hi > lo)) {
            return bg;
        }
        double f = (v - lo) / (hi - lo);
        Color to = Theme.pick(new Color(0x63BE7B), new Color(0x2F7A45));
        Color from = Theme.pick(new Color(0xF8696B), new Color(0x8A3436));
        Color mid = Theme.pick(new Color(0xFFEB84), new Color(0x7A6A2A));
        Color a = f < 0.5 ? from : mid;
        Color b = f < 0.5 ? mid : to;
        double k = f < 0.5 ? f * 2 : (f - 0.5) * 2;
        Color c = new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * k),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * k), (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * k));
        // softened, so the numbers stay easy to read
        double s = Theme.isDark() ? 0.55 : 0.45;
        return new Color((int) (bg.getRed() * (1 - s) + c.getRed() * s), (int) (bg.getGreen() * (1 - s) + c.getGreen() * s),
                (int) (bg.getBlue() * (1 - s) + c.getBlue() * s));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- the chart

    /** The chart of what the table shows: its rows (without totals) and a series per value column. */
    private void updateChart() {
        Pivot.Result r = result;
        if (r == null || model.lines.isEmpty()) {
            chart.setData(new PivotChart.Data("", List.of(), List.of(), new double[0][], false));
            return;
        }
        List<Integer> catRows = new ArrayList<>();
        for (int i = 0; i < model.lines.size(); i++) {
            Line l = model.lines.get(i);
            if (r.spec().rows().isEmpty() ? l.grand : !l.grand && (l.node.isLeaf() || collapsed.contains(l.node.path()))) {
                catRows.add(i);
            }
        }
        if (catRows.size() > 200) {
            catRows = catRows.subList(0, 200);
        }
        List<Integer> seriesCols = new ArrayList<>();
        boolean hasCols = !r.colKeys().isEmpty();
        for (int c = 0; c < model.cols.size(); c++) {
            if (!hasCols || model.cols.get(c)[0] >= 0) {
                seriesCols.add(c);
            }
        }
        if (seriesCols.size() > 40) {
            seriesCols = seriesCols.subList(0, 40);
        }
        List<String> cats = new ArrayList<>();
        for (int i : catRows) {
            Line l = model.lines.get(i);
            cats.add(l.grand ? "Total" : String.join(" · ", l.node.path()));
        }
        List<String> series = new ArrayList<>();
        double[][] values = new double[seriesCols.size()][catRows.size()];
        for (int s = 0; s < seriesCols.size(); s++) {
            int c = seriesCols.get(s);
            series.add(model.getColumnName(c + 1));
            for (int k = 0; k < catRows.size(); k++) {
                Line l = model.lines.get(catRows.get(k));
                int[] gv = model.cols.get(c);
                values[s][k] = r.value(l.node, gv[0], gv[1]);
            }
        }
        boolean pct = r.spec().cellValues().stream().allMatch(v -> v.showAs() != Pivot.ShowAs.VALUE);
        String by = r.spec().rows().isEmpty() ? "" : " by " + String.join(" and ",
                r.spec().rows().stream().map(r::fieldTitle).toList());
        String across = r.spec().cols().isEmpty() ? "" : " and " + String.join(" and ",
                r.spec().cols().stream().map(r::fieldTitle).toList());
        String what = r.spec().cellValues().size() == 1 ? r.valueTitle(0) : "Values";
        chart.setData(new PivotChart.Data(what + by + across, cats, series, values, pct));
    }

    /** A category clicked in the chart: its row in the table. */
    private void pickCategory(int category) {
        int seen = -1;
        for (int i = 0; i < model.lines.size(); i++) {
            Line l = model.lines.get(i);
            boolean cat = result.spec().rows().isEmpty() ? l.grand
                    : !l.grand && (l.node.isLeaf() || collapsed.contains(l.node.path()));
            if (cat && ++seen == category) {
                table.changeSelection(i, 0, false, false);
                table.setColumnSelectionInterval(0, table.getColumnCount() - 1);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- the rows of a cell, copying, export

    /** A cell opened: the log shows only its rows (a filter per group it is in). */
    private void showRows(int row, int col) {
        Pivot.Result r = result;
        Line l = model.line(row);
        if (r == null || l.grand && col == 0) {
            return;
        }
        List<String> path = l.grand ? List.of() : l.node.path();
        List<String[]> conditions = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Pivot.Field f = r.spec().rows().get(i);
            if (f.group() != Pivot.Group.NONE || path.get(i).equals(Pivot.OTHER)) {
                continue;
            }
            conditions.add(new String[] {r.titles().get(f.column()), path.get(i)});
        }
        if (col > 0) {
            int g = model.cols.get(col - 1)[0];
            if (g >= 0) {
                List<String> key = r.colKeys().get(g);
                for (int i = 0; i < key.size(); i++) {
                    Pivot.Field f = r.spec().cols().get(i);
                    if (f.group() == Pivot.Group.NONE && !key.get(i).equals(Pivot.OTHER)) {
                        conditions.add(new String[] {r.titles().get(f.column()), key.get(i)});
                    }
                }
            }
        }
        for (String[] c : conditions) {
            host.filter(c[0], ColumnFilter.Op.EQUALS, c[1].equals(Pivot.BLANK) ? "" : c[1]);
        }
        status.setText(conditions.isEmpty() ? "No groups to filter by (times grouped by period aren't filtered)"
                : "The log shows the rows where " + String.join(" and ",
                        conditions.stream().map(c -> c[0] + " = " + c[1]).toList()));
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        int col = table.columnAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        if (!table.isCellSelected(row, Math.max(0, col))) {
            table.changeSelection(row, Math.max(0, col), false, false);
        }
        JPopupMenu m = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Copy", Icons.of("copy"));
        copy.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copy.addActionListener(a -> copy(false));
        m.add(copy);
        JMenuItem copyAll = new JMenuItem("Copy the Whole Table");
        copyAll.addActionListener(a -> copy(true));
        m.add(copyAll);
        m.addSeparator();
        JMenuItem rows = new JMenuItem("Show These Rows in the Log", Icons.of("filter"));
        rows.setEnabled(col > 0 || !model.line(row).grand);
        rows.addActionListener(a -> showRows(row, Math.max(0, col)));
        m.add(rows);
        Line l = model.line(row);
        if (!l.grand && !l.node.isLeaf()) {
            JMenuItem t = new JMenuItem(collapsed.contains(l.node.path()) ? "Expand" : "Collapse");
            t.addActionListener(a -> toggle(l.node));
            m.add(t);
        }
        m.show(table, e.getX(), e.getY());
    }

    /** The table (or the cells selected) as tab-separated text, with the titles. */
    String tableText(boolean whole) {
        int[] rows = whole || table.getSelectedRowCount() == 0 ? java.util.stream.IntStream.range(0,
                table.getRowCount()).toArray() : table.getSelectedRows();
        int[] cols = whole || table.getSelectedColumnCount() == 0 ? java.util.stream.IntStream.range(0,
                table.getColumnCount()).toArray() : table.getSelectedColumns();
        StringBuilder b = new StringBuilder();
        if (whole || rows.length > 1 || cols.length > 1) {
            for (int i = 0; i < cols.length; i++) {
                b.append(i > 0 ? "\t" : "").append(model.getColumnName(cols[i]));
            }
            b.append('\n');
        }
        for (int r : rows) {
            for (int i = 0; i < cols.length; i++) {
                String t = model.text(r, cols[i]);
                if (cols[i] == 0 && !model.line(r).grand) {
                    t = "  ".repeat(Math.max(0, model.line(r).node.depth() - 1)) + t;
                }
                b.append(i > 0 ? "\t" : "").append(t.replace('\t', ' '));
            }
            b.append('\n');
        }
        return b.toString();
    }

    private void copy(boolean whole) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(tableText(whole)), null);
            status.setText(whole ? "Copied the table" : "Copied");
        } catch (RuntimeException e) {
            // no clipboard here
        }
    }

    private void exportMenu(JComponent under) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem csv = new JMenuItem("Save the Table as CSV…");
        csv.addActionListener(e -> saveCsv());
        JMenuItem copy = new JMenuItem("Copy the Table");
        copy.addActionListener(e -> copy(true));
        JMenuItem png = new JMenuItem("Save the Chart as PNG…");
        png.addActionListener(e -> saveChart());
        JMenuItem copyChart = new JMenuItem("Copy the Chart");
        copyChart.addActionListener(e -> copyChart());
        for (JMenuItem it : List.of(csv, copy, png, copyChart)) {
            it.setEnabled(result != null && model.getRowCount() > 0);
            m.add(it);
        }
        m.show(under, 0, under.getHeight());
    }

    /** The table as CSV (with its titles; groups indented by their place in the first column). */
    String csvText() {
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < model.getColumnCount(); c++) {
            b.append(c > 0 ? "," : "").append(logsift.core.Csv.quote(model.getColumnName(c)));
        }
        b.append('\n');
        for (int r = 0; r < model.getRowCount(); r++) {
            for (int c = 0; c < model.getColumnCount(); c++) {
                String t = model.text(r, c);
                if (c > 0) {
                    t = t.replace(",", "").replace(" %", "").replace("≥ ", "");
                }
                b.append(c > 0 ? "," : "").append(logsift.core.Csv.quote(t));
            }
            b.append('\n');
        }
        return b.toString();
    }

    private void saveCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("pivot.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Files.writeString(fc.getSelectedFile().toPath(), csvText(), StandardCharsets.UTF_8);
            status.setText("Saved " + fc.getSelectedFile().getName());
        } catch (IOException e) {
            status.setText("Couldn't save: " + e.getMessage());
        }
    }

    private void saveChart() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("pivot-chart.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Path p = fc.getSelectedFile().toPath();
            javax.imageio.ImageIO.write(chart.image(Math.max(640, chart.getWidth()), Math.max(400, chart.getHeight())),
                    "png", p.toFile());
            status.setText("Saved " + p.getFileName());
        } catch (IOException e) {
            status.setText("Couldn't save: " + e.getMessage());
        }
    }

    private void copyChart() {
        java.awt.image.BufferedImage img = chart.image(Math.max(640, chart.getWidth()), Math.max(400, chart.getHeight()));
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {DataFlavor.imageFlavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return DataFlavor.imageFlavor.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) {
                    return img;
                }
            }, null);
            status.setText("Copied the chart");
        } catch (RuntimeException e) {
            // no clipboard here
        }
    }

    // ---------------------------------------------------------------- the lists' look

    private final class FieldRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
            int col = (Integer) v;
            Pivot.Kind k = fields == null ? Pivot.Kind.TEXT : fields.kind(col);
            String kind = switch (k) {
                case NUMBER -> "#";
                case TIME -> "◷";
                case TEXT -> "Aa";
            };
            boolean on = used(col);
            String box = on ? "☑" : "☐";
            String dim = hex(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            super.getListCellRendererComponent(l, "<html>" + box + "&nbsp;&nbsp;<font color='" + dim + "'>" + kind
                    + "</font>&nbsp;&nbsp;" + (on ? "<b>" + esc(title(col)) + "</b>" : esc(title(col))) + "</html>",
                    i, sel, focus);
            setToolTipText(switch (k) {
                case NUMBER -> "Numbers: summed up in Values (or group rows)";
                case TIME -> "Times: group rows by year, month, day or hour";
                case TEXT -> "Text: groups rows, or is counted in Values";
            });
            return this;
        }
    }

    private final class ItemRenderer extends javax.swing.DefaultListCellRenderer {
        private final Area area;

        ItemRenderer(Area area) {
            this.area = area;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
            Item it = (Item) v;
            String text = switch (area) {
                case VALUES -> it.agg.label + " of " + title(it.column) + (it.showAs != Pivot.ShowAs.VALUE ? " (%)" : "");
                case FILTERS -> title(it.column) + (it.keep == null ? ": all" : ": " + it.keep.size() + " values");
                default -> title(it.column) + (it.group != Pivot.Group.NONE ? " (" + it.group.label + ")" : "");
            };
            super.getListCellRendererComponent(l, text + "  ▾", i, sel, false);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(2, 3, 2, 3),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Theme.uiColor("Component.borderColor", new Color(0xC8C8C8)),
                                    1, true), BorderFactory.createEmptyBorder(1, 6, 1, 6))));
            setBackground(sel ? Theme.uiColor("List.selectionBackground", new Color(0xDCE6F5)) : l.getBackground());
            setToolTipText("Click for options; drag to another area, or out to remove");
            return this;
        }
    }

    // ---------------------------------------------------------------- tests

    JTable tableForTest() {
        return table;
    }

    PivotChart chartForTest() {
        return chart;
    }

    List<String> fieldsForTest() {
        return fields == null ? List.of() : fields.titles();
    }

    int columnForTest(String title) {
        return fields.titles().indexOf(title);
    }

    /** The items of an area, as shown. */
    List<String> areaForTest(Area a) {
        List<String> out = new ArrayList<>();
        JList<Item> l = areaLists.get(a);
        for (int i = 0; i < l.getModel().getSize(); i++) {
            Component c = l.getCellRenderer().getListCellRendererComponent(l, l.getModel().getElementAt(i), i, false,
                    false);
            out.add(((JLabel) c).getText().replace("  ▾", ""));
        }
        return out;
    }

    void setAggForTest(int valueIndex, Pivot.Agg agg, Pivot.ShowAs showAs) {
        Item it = areas.get(Area.VALUES).get(valueIndex);
        it.agg = agg;
        it.showAs = showAs;
        changed();
    }

    void setGroupForTest(Area a, int index, Pivot.Group g) {
        areas.get(a).get(index).group = g;
        changed();
    }

    void keepForTest(int filterIndex, Set<String> keep) {
        areas.get(Area.FILTERS).get(filterIndex).keep = keep;
        changed();
    }

    /** What is on screen: "table", "chart", "both" or "empty". */
    String shownViewForTest() {
        if (emptyPanel.isVisible()) {
            return "empty";
        }
        boolean t = tableHolder.isShowing();
        boolean c = chartHolder.isShowing();
        return t && c ? "both" : t ? "table" : c ? "chart" : "nothing";
    }


    void setViewForTest(String v) {
        setView(v);
    }

    void setChartTypeForTest(PivotChart.Type t) {
        chartType.setSelectedItem(t);
    }

    void sortByForTest(int col) {
        sortBy(col);
    }

    void toggleForTest(int row) {
        toggle(model.line(row).node);
    }

    void showRowsForTest(int row, int col) {
        showRows(row, col);
    }

    void clickQuickStartForTest() {
        quickStart.doClick();
    }

    String quickStartForTest() {
        return quickStart.isVisible() ? quickStart.getText() : null;
    }

    String cellTextForTest(int row, int col) {
        return model.text(row, col);
    }

    String statusForTest() {
        return status.getText();
    }

    void setHeatForTest(boolean on) {
        heat.setSelected(on);
        table.repaint();
    }
}
