package logsift.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import logsift.core.diff.ChangeNavigator;
import logsift.core.diff.ColumnMap;
import logsift.core.diff.CsvFile;
import logsift.core.diff.DiffOptions;
import logsift.core.diff.DiffStatus;
import logsift.core.diff.KeySuggester;
import logsift.core.diff.TableDiff;
import logsift.core.diff.TableDiffExport;
import logsift.core.diff.TableDiffResult;

/**
 * Two CSV files compared as tables: rows matched by the key columns chosen (none at first: rows
 * are lined up in file order; a key is suggested, one click to use it), whatever their order; what was added, removed and changed, value by value, with changed values
 * as old → new. Drop a file on a side to compare another one.
 */
final class CsvCompareTab extends JPanel {

    /** A file to compare: the one read, the one to open at a row (a zip's entry), and its name. */
    record Side(Path read, Path open, String name) {
        static Side of(Path p) {
            Path abs = p.toAbsolutePath().normalize();
            return new Side(abs, abs, abs.getFileName() == null ? abs.toString() : abs.getFileName().toString());
        }
    }

    interface Host {
        /** Opens a file (or selects its tab) at a line (0-based). */
        void openLine(Path file, long line);

        /** The files changed: the tab's title is new. */
        void titleChanged(CsvCompareTab tab);

        /**
         * How a dropped file is read (a .gz unpacked first), on a background thread; throws with
         * why it can't be compared.
         */
        default Side sideFor(Path dropped) throws IOException {
            return Side.of(dropped);
        }

        /** The file browsers Choose Files shows in place of the table (the Compare tab's), or null. */
        default JComponent drawer() {
            return null;
        }

        /** The file browsers were shown or hidden. */
        default void drawerShown(boolean on) {}

        /**
         * Compare clicked while the file browsers are shown: true if the host takes it (other files
         * were chosen, and are being read).
         */
        default boolean compareChosen() {
            return false;
        }

        /** Compare as Text: the same files line by line. */
        default void asText() {}
    }

    private static final String[] SHOW = {"All differences", "Changed", "Added", "Removed", "Key repeats",
        "Same", "Everything"};

    private final Host host;
    private final Side[] sides = new Side[2];
    private final CsvFile[] files = new CsvFile[2];
    private final List<CsvFile> toClose = new ArrayList<>();
    private ColumnMap map;
    private List<String> keyHeader;                    // the left header the keys index
    private final List<Integer> keys = new ArrayList<>();      // left columns
    private final Set<Integer> ignored = new HashSet<>();      // left columns
    private List<Integer> suggested = List.of();               // left columns
    private String suggestionReason = "";
    private volatile TableDiffResult result;
    private int generation;
    private AtomicBoolean running;
    private int hover = -1;

    // the files
    private final JLabel[] fileName = {new JLabel(), new JLabel()};
    private final JLabel[] fileInfo = {new JLabel(" "), new JLabel(" ")};
    // keys
    private final JPanel keyChips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    // columns left out of the comparison
    private final JPanel ignoreChips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    // columns not shown in the table (by name; they are still compared)
    private final Set<String> hiddenColumns = new HashSet<>();
    // the view: one table, or the two files side by side
    private final javax.swing.JToggleButton unifiedButton = new javax.swing.JToggleButton("Unified");
    private final javax.swing.JToggleButton sideButton = new javax.swing.JToggleButton("Side by Side");
    private final java.awt.CardLayout views = new java.awt.CardLayout();
    private final JPanel viewCards = new JPanel(views);
    private final SideModel[] sideModels = {new SideModel(0), new SideModel(1)};
    private final JTable[] sideTables = {new JTable(sideModels[0]), new JTable(sideModels[1])};
    private boolean sideBySide = AppState.prefs().getBoolean("compareSideBySide", false);
    // what Show is set to in each view: side by side shows every row unless people choose otherwise
    private int unifiedShow;
    private int sideShow = Math.max(0, Math.min(SHOW.length - 1, AppState.prefs().getInt("compareSideShow", SHOW.length - 1)));
    private final JLabel suggestion = new JLabel(" ");
    // options
    private final JCheckBox trim = new JCheckBox("Trim spaces", true);
    private final JCheckBox ignoreCase = new JCheckBox("Ignore case");
    private final JCheckBox numbers = new JCheckBox("Numbers as numbers, within", true);
    private final JTextField tolerance = new JTextField("0", 4);
    private final JComboBox<String> toleranceUnit = new JComboBox<>(new String[] {"absolute", "%"});
    private final JCheckBox emptyMissing = new JCheckBox("Empty = missing", true);
    private final JCheckBox ignoreOrder = new JCheckBox("Ignore row order");
    private final JButton compare = new JButton("Compare", Icons.of("git-compare"));
    private final JButton cancel = new JButton("Cancel");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    // what it found
    // the files, above the table, and the file browsers in the table's place
    private final JPanel fileRow = new JPanel();
    private final javax.swing.JToggleButton filesButton = new javax.swing.JToggleButton();
    private boolean drawerOpen;
    private JSplitPane sideSplit;
    private boolean optionsChanged;
    private String doneText = " ";
    private final JLabel notes = new JLabel(" ");
    private final DefaultListModel<ColumnCount> columnModel = new DefaultListModel<>();
    private final JList<ColumnCount> columnList = new JList<>(columnModel);
    private final JComboBox<String> show = new JComboBox<>(SHOW);
    private final JTextField search = new JTextField(16);
    private final JLabel position = new JLabel(" ");
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private int[] shown = new int[0];
    private int columnFilter = -1;
    private AtomicBoolean searching;
    private final Timer searchTimer = new Timer(300, e -> refilter());

    /** A compared column and how many rows changed in it (pair -1: all columns). */
    private record ColumnCount(int pair, String name, int rows) {
        @Override
        public String toString() {
            return pair < 0 ? name : name + ": " + String.format(Locale.ROOT, "%,d", rows) + (rows == 1 ? " row" : " rows");
        }
    }

    CsvCompareTab(Side left, Side right, CsvFile leftFile, CsvFile rightFile, Host host) {
        super(new BorderLayout());
        this.host = host;
        sides[0] = left;
        sides[1] = right;
        files[0] = leftFile;
        files[1] = rightFile;
        add(top(), BorderLayout.NORTH);
        add(center(), BorderLayout.CENTER);
        FileDrop.install(this, dropTarget, table, (JComponent) table.getParent(), columnList, search, tolerance);
        searchTimer.setRepeats(false);
        showFiles();
        prepare();
    }

    /** Where files dropped on the tab go: two to both sides, one to the half it is dropped on. */
    private final FileDrop.Target dropTarget = new FileDrop.Target() {
        @Override
        public int sideAt(Point p, int count) {
            return count >= 2 ? FileDrop.BOTH : p.x < getWidth() / 2 ? 0 : 1;
        }

        @Override
        public void hover(int side) {
            setHover(side);
        }

        @Override
        public void dropped(List<Path> dropped, int side) {
            drop(dropped, side);
        }
    };

    // ---------------------------------------------------------------- layout

    private JComponent top() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 2, 10));

        // the keys on the left, the columns left out on the right: one row
        JPanel pick = new JPanel(new GridLayout(1, 2, 12, 0));
        pick.add(keyChips);
        pick.add(ignoreChips);
        pick.setAlignmentX(LEFT_ALIGNMENT);
        top.add(pick);

        JPanel options = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        options.setAlignmentX(LEFT_ALIGNMENT);
        trim.setToolTipText("Spaces at the start or end of a value don't count");
        ignoreCase.setToolTipText("Upper and lower case are the same, in keys too");
        numbers.setToolTipText("<html>Values that are both numbers are compared as numbers: 1.50 = 1.5.<br>"
                + "Within: numbers this far apart are equal (absolute, or a % of the larger)</html>");
        tolerance.setToolTipText(numbers.getToolTipText());
        emptyMissing.setToolTipText("An empty value equals one that isn't there (a row with fewer fields)");
        ignoreOrder.setToolTipText("<html>Without keys: equal rows are matched wherever they are,<br>"
                + "not lined up in file order (with keys, order never matters)</html>");
        for (JCheckBox c : List.of(trim, ignoreCase, numbers, emptyMissing, ignoreOrder)) {
            c.addActionListener(e -> stale());
        }
        toleranceUnit.addActionListener(e -> stale());
        tolerance.getDocument().addDocumentListener(onChange(this::stale));
        toleranceUnit.setMaximumSize(toleranceUnit.getPreferredSize());
        compare.setToolTipText("Compare the files with these keys and options");
        compare.addActionListener(e -> {
            if (drawerOpen && host.compareChosen()) {
                return; // other files: they are read, then compared
            }
            showDrawer(false);
            runCompare();
        });
        cancel.addActionListener(e -> {
            if (running != null) {
                running.set(true);
            }
        });
        cancel.setVisible(false);
        progress.setPreferredSize(new Dimension(140, 12));
        progress.setVisible(false);
        JPanel numbersBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        numbersBox.add(numbers);
        numbersBox.add(tolerance);
        numbersBox.add(toleranceUnit);
        options.add(trim);
        options.add(ignoreCase);
        options.add(numbersBox);
        options.add(emptyMissing);
        options.add(ignoreOrder);
        options.add(compare);
        options.add(progress);
        options.add(cancel);
        options.add(fixedWidth(status, 46));
        top.add(options);

        notes.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        notes.setAlignmentX(LEFT_ALIGNMENT);
        notes.setVisible(false);
        top.add(notes);
        return top;
    }

    /** A file's name and what it is, on one line (the right one starts with ⇄ to swap). */
    private JComponent fileBox(int side) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, side == 0 ? new Color(0xDC2626) : new Color(0x16A34A)),
                BorderFactory.createEmptyBorder(1, 6, 1, 4)));
        fileName[side].setFont(fileName[side].getFont().deriveFont(Font.BOLD));
        fileInfo[side].setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JPanel text = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        text.add(fileName[side]);
        text.add(Box.createHorizontalStrut(8));
        text.add(fileInfo[side]);
        p.add(text, BorderLayout.CENTER);
        if (side == 1) {
            JButton swap = new JButton("⇄");
            swap.setToolTipText("Swap left and right");
            swap.putClientProperty("JButton.buttonType", "toolBarButton");
            swap.addActionListener(e -> swap());
            p.add(swap, BorderLayout.WEST);
            swapButton = swap;
        }
        p.setMinimumSize(new Dimension(0, 0));
        return p;
    }

    private JButton swapButton;

    /**
     * The files' row: the left file's name over the left half of the table (side by side: over
     * the left table, up to the divider), the right one's over the right half, and Choose Files at
     * the end, where it stays when the file browsers are shown.
     */
    private final class FileRowLayout implements java.awt.LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component c) {}

        @Override
        public void removeLayoutComponent(Component c) {}

        @Override
        public Dimension preferredLayoutSize(java.awt.Container p) {
            java.awt.Insets in = p.getInsets();
            int h = 0;
            int w = 0;
            for (Component c : p.getComponents()) {
                if (c.isVisible()) {
                    h = Math.max(h, c.getPreferredSize().height);
                    w += c.getPreferredSize().width;
                }
            }
            return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container p) {
            Dimension d = preferredLayoutSize(p);
            return new Dimension(0, d.height);
        }

        @Override
        public void layoutContainer(java.awt.Container p) {
            java.awt.Insets in = p.getInsets();
            int w = p.getWidth() - in.left - in.right;
            int h = p.getHeight() - in.top - in.bottom;
            Component left = p.getComponent(0);
            Component right = p.getComponent(1);
            Component button = p.getComponent(2);
            int bw = button.isVisible() ? button.getPreferredSize().width : 0;
            int leftW;
            int rightX;
            if (sideBySide && !drawerOpen && sideSplit != null && sideSplit.getWidth() > 0) {
                leftW = sideSplit.getDividerLocation();
                rightX = leftW + sideSplit.getDividerSize();
            } else {
                leftW = (w - DRAWER_GAP) / 2;
                rightX = leftW + DRAWER_GAP;
            }
            left.setBounds(in.left, in.top, Math.max(0, leftW), h);
            int rightEnd = w - (bw > 0 ? bw + 6 : 0);
            right.setBounds(in.left + rightX, in.top, Math.max(0, rightEnd - rightX), h);
            int bh = button.getPreferredSize().height;
            button.setBounds(in.left + w - bw, in.top + (h - bh) / 2, bw, bh);
        }
    }

    /** The gap between the two file browsers (and the files' names over them). */
    static final int DRAWER_GAP = 12;

    private JComponent center() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        show.setToolTipText("Which rows to show, with how many there are");
        show.setRenderer(new ShowRenderer());
        show.addActionListener(e -> {
            if (sideBySide) {
                sideShow = show.getSelectedIndex();
                try {
                    AppState.prefs().putInt("compareSideShow", sideShow);
                } catch (RuntimeException ex) {
                    // remembered next time if it can be
                }
            } else {
                unifiedShow = show.getSelectedIndex();
            }
            show.setToolTipText(show.getSelectedIndex() == 4 && result != null ? repeatsTip(result)
                    : "Which rows to show, with how many there are");
            refilter();
        });
        search.putClientProperty("JTextField.placeholderText", "search in results");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(onChange(searchTimer::restart));
        search.addActionListener(e -> refilter());
        JButton prev = new JButton(Icons.of("chevron-up"));
        prev.setToolTipText("Previous difference (Shift+F7)");
        prev.addActionListener(e -> nextChange(false));
        JButton next = new JButton(Icons.of("chevron-down"));
        next.setToolTipText("Next difference (F7)");
        next.addActionListener(e -> nextChange(true));
        JButton export = new JButton("Export ▾", Icons.of("download"));
        export.addActionListener(e -> exportMenu(export));
        JButton columns = new JButton("Columns ▾", Icons.of("columns-3"));
        columns.setToolTipText("Which columns the table shows (all are still compared), and pairing columns by hand");
        columns.addActionListener(e -> columnsMenu(columns));
        columnsButton = columns;
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        g.add(unifiedButton);
        g.add(sideButton);
        unifiedButton.setToolTipText("One table: a changed value as old → new");
        sideButton.setToolTipText("The left file's rows next to the right file's, matched rows on one line");
        unifiedButton.putClientProperty("JButton.buttonType", "segmented");
        unifiedButton.putClientProperty("JButton.segmentPosition", "first");
        sideButton.putClientProperty("JButton.buttonType", "segmented");
        sideButton.putClientProperty("JButton.segmentPosition", "last");
        unifiedButton.addActionListener(e -> setSideBySide(false));
        sideButton.addActionListener(e -> setSideBySide(true));
        JPanel viewSwitch = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        viewSwitch.add(unifiedButton);
        viewSwitch.add(sideButton);
        bar.add(viewSwitch);
        bar.add(columns);
        bar.add(new JLabel("Show"));
        bar.add(show);
        bar.add(search);
        bar.add(prev);
        bar.add(next);
        bar.add(fixedWidth(position, 40));
        bar.add(export);
        JButton asText = new JButton("Compare as Text", Icons.of("file-text"));
        asText.setToolTipText("The same files line by line, side by side");
        asText.addActionListener(e -> host.asText());
        bar.add(asText);

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(table.getRowHeight() + 4);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().addListSelectionListener(e -> updatePosition());
        table.addMouseListener(rowMouse(table, -1));
        cellSelection(table, -1);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        JPanel unified = new JPanel(new BorderLayout());
        unified.add(tableScroll, BorderLayout.CENTER);
        unified.add(new ChangeStrip(), BorderLayout.EAST);
        tableScroll.getViewport().addChangeListener(e -> strips.forEach(Component::repaint));
        viewCards.add(unified, "unified");
        viewCards.add(sideView(), "side");
        fileRow.setLayout(new FileRowLayout());
        fileRow.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)), BorderFactory.createEmptyBorder(2, 0, 2, 6)));
        fileRow.add(fileBox(0));
        fileRow.add(fileBox(1));
        filesButton.setToolTipText("Choose other files: a file browser for each side, in the table's place");
        filesButton.addActionListener(e -> showDrawer(filesButton.isSelected()));
        filesButton.setVisible(host.drawer() != null);
        fileRow.add(filesButton);
        showDrawerButton();
        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(fileRow, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout());
        right.add(north, BorderLayout.NORTH);
        right.add(viewCards, BorderLayout.CENTER);
        setSideBySide(sideBySide);

        columnList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        columnList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ColumnCount c = columnList.getSelectedValue();
                int pair = c == null ? -1 : c.pair();
                if (pair != columnFilter) {
                    columnFilter = pair;
                    refilter();
                }
            }
        });
        JLabel head = new JLabel("Changes per column");
        head.setFont(head.getFont().deriveFont(Font.BOLD));
        head.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 4));
        head.setToolTipText("Click a column to show only the rows changed in it");
        JPanel left = new JPanel(new BorderLayout());
        left.add(head, BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(columnList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        left.add(listScroll, BorderLayout.CENTER);
        left.setMinimumSize(new Dimension(120, 50));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(210);
        split.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        return split;
    }

    /** Double-click opens the row's line (in {@code side}'s file, -1: the side it is on); right-click offers both. */
    private MouseAdapter rowMouse(JTable t, int side) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && t.getSelectedRow() >= 0) {
                    openRow(t.getSelectedRow(), side);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e, t);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e, t);
            }
        };
    }

    /** The two files side by side: one table each, scrolled and selected together, row for row. */
    private JComponent sideView() {
        JScrollPane[] scrolls = new JScrollPane[2];
        for (int s = 0; s < 2; s++) {
            JTable t = sideTables[s];
            t.setFillsViewportHeight(true);
            t.setShowGrid(false);
            t.setIntercellSpacing(new Dimension(0, 0));
            t.setRowHeight(table.getRowHeight());
            t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            t.setDefaultRenderer(Object.class, new SideRenderer(s));
            t.setSelectionModel(table.getSelectionModel()); // one selection for every view
            t.addMouseListener(rowMouse(t, s));
            cellSelection(t, s);
            scrolls[s] = new JScrollPane(t);
            scrolls[s].setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                    Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        }
        // one vertical scroll for both
        scrolls[1].getVerticalScrollBar().setModel(scrolls[0].getVerticalScrollBar().getModel());
        scrolls[0].setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrolls[1].setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrolls[0], scrolls[1]);
        split.setResizeWeight(0.5);
        sideSplit = split;
        // the files' names follow the divider
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> fileRow.revalidate());
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fileRow.revalidate();
            }
        });
        split.setBorder(BorderFactory.createEmptyBorder());
        scrolls[0].getViewport().addChangeListener(e -> strips.forEach(Component::repaint));
        JPanel view = new JPanel(new BorderLayout());
        view.add(split, BorderLayout.CENTER);
        ChangeStrip strip = new ChangeStrip();
        view.add(strip, BorderLayout.EAST);
        return view;
    }

    private JButton columnsButton;

    // ---------------------------------------------------------------- the file browsers

    /** Shows the file browsers in the table's place (or the table again). */
    void showDrawer(boolean on) {
        JComponent d = host.drawer();
        if (d == null) {
            return;
        }
        if (on && d.getParent() != viewCards) {
            viewCards.add(d, "files");
        }
        drawerOpen = on;
        showDrawerButton();
        views.show(viewCards, on ? "files" : sideBySide ? "side" : "unified");
        if (!on) {
            highlightCompare(compare, optionsChanged);
            if (running == null && result != null) {
                status.setForeground(optionsChanged ? Theme.uiColor("Component.accentColor", new Color(0x2563EB))
                        : Theme.uiColor("Label.disabledForeground", Color.GRAY));
                status.setText(optionsChanged ? "Changed: press Compare" : doneText);
            }
        }
        fileRow.revalidate();
        fileRow.repaint();
        host.drawerShown(on);
    }

    private void showDrawerButton() {
        filesButton.setSelected(drawerOpen);
        // one width for both labels, so the button doesn't move
        filesButton.setPreferredSize(null);
        int w = 0;
        for (boolean open : new boolean[] {false, true}) {
            filesButton.setText(open ? "Close Files" : "Choose Files");
            filesButton.setIcon(Icons.of(open ? "x" : "folder-open"));
            w = Math.max(w, filesButton.getPreferredSize().width);
        }
        filesButton.setText(drawerOpen ? "Close Files" : "Choose Files");
        filesButton.setIcon(Icons.of(drawerOpen ? "x" : "folder-open"));
        filesButton.setPreferredSize(new Dimension(w, filesButton.getPreferredSize().height));
    }

    /**
     * What the file browsers have chosen, while they are shown: Compare stands out when it is
     * other files than these, and the status says what Compare will do.
     */
    void filesChosen(boolean other, String text) {
        if (!drawerOpen || running != null) {
            return;
        }
        highlightCompare(compare, other || optionsChanged);
        status.setForeground(other ? Theme.uiColor("Component.accentColor", new Color(0x2563EB))
                : Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText(text);
    }

    boolean isDrawerOpen() {
        return drawerOpen;
    }

    javax.swing.JToggleButton filesButtonForTest() {
        return filesButton;
    }

    /** Where the right file's name starts, and where the right table starts (side by side), in the tab. */
    int[] fileRowSplitForTest() {
        Component right = fileRow.getComponent(1);
        int nameX = SwingUtilities.convertPoint(fileRow, right.getX(), 0, this).x;
        Component t = sideSplit.getRightComponent();
        int tableX = SwingUtilities.convertPoint(sideSplit, t.getX(), 0, this).x;
        return new int[] {nameX, tableX};
    }

    // ---------------------------------------------------------------- Show, with counts

    /** Show's list: the symbol in its colour, the name, and how many rows. */
    private final class ShowRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean sel, boolean f) {
            int at = i >= 0 ? i : show.getSelectedIndex();
            super.getListCellRendererComponent(l, showHtml(at, sel), i, sel, f);
            setToolTipText(at == 4 && result != null ? repeatsTip(result) : null);
            return this;
        }
    }

    private static final DiffStatus[] SHOW_STATUS = {null, DiffStatus.CHANGED, DiffStatus.ADDED, DiffStatus.REMOVED,
        DiffStatus.DUPLICATE, DiffStatus.SAME, null};
    private static final String[] SHOW_NAME = {"All differences", "Changed", "Added (only right)", "Removed (only left)",
        "Key repeats", "Same", "Everything"};

    /** How many rows a Show entry has, or -1 before a result. */
    private int showCount(int at) {
        TableDiffResult r = result;
        if (r == null || at < 0) {
            return -1;
        }
        return switch (at) {
            case 0 -> r.size() - r.count(DiffStatus.SAME);
            case 6 -> r.size();
            default -> r.count(SHOW_STATUS[at]);
        };
    }

    /** A Show entry as text: "+ Added (only right)  6". */
    String showText(int at) {
        DiffStatus st = SHOW_STATUS[at];
        int n = showCount(at);
        boolean noKey = at == 4 && result != null && result.spec().keys().isEmpty();
        return (st != null ? st.symbol() + " " : "") + SHOW_NAME[at]
                + (noKey ? "  (with a key)" : n >= 0 ? String.format(Locale.ROOT, "  %,d", n) : "");
    }

    private String showHtml(int at, boolean sel) {
        if (at < 0 || at >= SHOW_NAME.length) {
            return "";
        }
        DiffStatus st = SHOW_STATUS[at];
        int n = showCount(at);
        boolean noKey = at == 4 && result != null && result.spec().keys().isEmpty();
        String dim = hex(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        StringBuilder b = new StringBuilder("<html><nobr>");
        if (st != null) {
            Color c = switch (st) {
                case ADDED -> addedColor();
                case REMOVED -> removedColor();
                case CHANGED -> changedColor();
                case DUPLICATE -> duplicateColor();
                case SAME -> Theme.gutterForeground();
            };
            b.append(sel ? "<b>" : "<b><font color='" + hex(c) + "'>").append(esc(st.symbol()))
                    .append(sel ? "</b>" : "</font></b>").append("&nbsp;");
        } else {
            b.append("&nbsp;&nbsp;&nbsp;");
        }
        b.append(noKey && !sel ? "<font color='" + dim + "'>" + SHOW_NAME[at] + "</font>" : SHOW_NAME[at]);
        if (noKey) {
            b.append(sel ? "&nbsp;&nbsp;(with a key)" : "&nbsp;&nbsp;<font color='" + dim + "'>(with a key)</font>");
        } else if (n >= 0) {
            String num = String.format(Locale.ROOT, "%,d", n);
            b.append("&nbsp;&nbsp;").append(sel ? num : "<font color='" + dim + "'>" + num + "</font>");
        }
        return b.append("</nobr></html>").toString();
    }

    // ---------------------------------------------------------------- selecting cells, rows and columns

    /**
     * Cells, rows and columns can be selected as in a spreadsheet: click a cell, drag or
     * Shift-click for a range, Cmd/Ctrl-click to add; a click on the status or line selects whole
     * rows, one on a column's title the whole column. Cmd/Ctrl+C copies what is selected.
     */
    private void cellSelection(JTable t, int side) {
        t.setCellSelectionEnabled(true);
        t.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        t.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        t.getTableHeader().setReorderingAllowed(false);
        t.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int col = t.columnAtPoint(e.getPoint());
                if (col >= 0 && col < rowHeads(t, side)) {
                    selectAllColumns(); // the status or line: whole rows
                } else {
                    other(t).forEach(o -> o.getColumnModel().getSelectionModel().clearSelection());
                }
                repaintTables();
            }
        });
        t.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                int col = t.columnAtPoint(e.getPoint());
                if (col >= 0 && col < rowHeads(t, side) && t.getColumnModel().getSelectionModel().getAnchorSelectionIndex()
                        < rowHeads(t, side)) {
                    selectAllColumns();
                }
            }
        });
        javax.swing.table.JTableHeader header = t.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int col = header.columnAtPoint(e.getPoint());
                if (col < 0 || !SwingUtilities.isLeftMouseButton(e) || t.getRowCount() == 0
                        || header.getCursor().getType() == Cursor.E_RESIZE_CURSOR) {
                    return;
                }
                selectColumn(t, col, e.isShiftDown(), e.isControlDown() || e.isMetaDown());
            }
        });
        header.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                int col = header.columnAtPoint(e.getPoint());
                if (col >= 0 && header.getCursor().getType() != Cursor.E_RESIZE_CURSOR && t.getRowCount() > 0) {
                    selectColumn(t, col, true, false);
                }
            }
        });
        javax.swing.table.TableCellRenderer base = header.getDefaultRenderer();
        header.setDefaultRenderer((tb, v, sel, focus, row, col) -> {
            Component c = base.getTableCellRendererComponent(tb, v, sel, focus, row, col);
            boolean on = tb.isColumnSelected(col) && tb.getSelectedRowCount() > 0;
            boolean whole = on && tb.getSelectedRowCount() == tb.getRowCount();
            c.setForeground(on ? Theme.uiColor("Component.accentColor", new Color(0x2563EB))
                    : Theme.uiColor("TableHeader.foreground", Theme.foreground()));
            c.setFont(whole ? header.getFont().deriveFont(Font.BOLD) : header.getFont());
            return c;
        });
        t.getColumnModel().getSelectionModel().addListSelectionListener(e -> header.repaint());
        t.getSelectionModel().addListSelectionListener(e -> header.repaint());
        t.getActionMap().put("copy", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copySelection(t, side, false);
            }
        });
        t.getActionMap().put("selectAll", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (t.getRowCount() > 0) {
                    t.setRowSelectionInterval(0, t.getRowCount() - 1);
                    selectAllColumns();
                }
            }
        });
    }

    /** The columns that head a row: the status and line(s). */
    private int rowHeads(JTable t, int side) {
        return side < 0 ? 2 : sideModels[side].fixedColumns();
    }

    private List<JTable> other(JTable t) {
        List<JTable> out = new ArrayList<>();
        for (JTable o : List.of(table, sideTables[0], sideTables[1])) {
            if (o != t) {
                out.add(o);
            }
        }
        return out;
    }

    private void selectAllColumns() {
        for (JTable o : List.of(table, sideTables[0], sideTables[1])) {
            if (o.getColumnCount() > 0) {
                o.setColumnSelectionInterval(0, o.getColumnCount() - 1);
            }
        }
        repaintTables();
    }

    private void repaintTables() {
        for (JTable o : List.of(table, sideTables[0], sideTables[1])) {
            o.repaint();
            o.getTableHeader().repaint();
        }
    }

    /** A column's title clicked: the whole column (Shift: up to it, Cmd/Ctrl: added or taken away). */
    private void selectColumn(JTable t, int col, boolean extend, boolean toggle) {
        ListSelectionModel cm = t.getColumnModel().getSelectionModel();
        int lead = table.getSelectionModel().getLeadSelectionIndex();
        t.setRowSelectionInterval(0, t.getRowCount() - 1);
        if (lead >= 0 && lead < t.getRowCount()) {
            // keep the row people were on as the lead, so the table doesn't jump
            table.getSelectionModel().addSelectionInterval(lead, lead);
        }
        int anchor = cm.getAnchorSelectionIndex();
        if (extend && anchor >= 0) {
            cm.setSelectionInterval(anchor, col);
        } else if (toggle) {
            if (cm.isSelectedIndex(col)) {
                cm.removeSelectionInterval(col, col);
            } else {
                cm.addSelectionInterval(col, col);
            }
        } else {
            cm.setSelectionInterval(col, col);
        }
        other(t).forEach(o -> o.getColumnModel().getSelectionModel().clearSelection());
        repaintTables();
    }

    /** Selects a row, every column (F7, the strip, a new result). */
    private void selectRow(int row) {
        table.setRowSelectionInterval(row, row);
        selectAllColumns();
    }

    /**
     * The selected cells as tab-separated text, a line per row, as the table shows them (a changed
     * value as old → new). Whole columns come with their titles.
     */
    String selectionText(JTable t, int side, boolean titles) {
        TableDiffResult r = result;
        int[] rows = t.getSelectedRows();
        int[] cols = t.getSelectedColumns();
        if (r == null || rows.length == 0) {
            return "";
        }
        if (cols.length == 0) {
            cols = java.util.stream.IntStream.range(0, t.getColumnCount()).toArray();
        }
        StringBuilder b = new StringBuilder();
        if (titles || rows.length == t.getRowCount() && rows.length > 1) {
            for (int i = 0; i < cols.length; i++) {
                b.append(i > 0 ? "\t" : "").append(t.getColumnName(cols[i]));
            }
            b.append('\n');
        }
        for (int row : rows) {
            if (row >= shown.length) {
                continue;
            }
            for (int i = 0; i < cols.length; i++) {
                String v = side < 0 ? cellText(row, cols[i]) : sideText(r, side, shown[row], cols[i]);
                b.append(i > 0 ? "\t" : "").append(v.replace('\t', ' ').replace('\n', ' '));
            }
            b.append('\n');
        }
        return b.toString();
    }

    private void copySelection(JTable t, int side, boolean titles) {
        String text = selectionText(t, side, titles);
        if (text.isEmpty()) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            int cells = t.getSelectedRowCount() * Math.max(1, t.getSelectedColumnCount());
            position.setText(String.format(Locale.ROOT, "Copied %,d %s", cells, cells == 1 ? "cell" : "cells"));
        } catch (RuntimeException e) {
            // no clipboard here
        }
    }

    /** How a selected cell looks: its colour shows through a tint of the accent colour. */
    static Color selectedBackground(Color bg, boolean cellSelected, boolean rowOnly) {
        Color a = Theme.uiColor("Component.accentColor", new Color(0x2563EB));
        double f = cellSelected ? (Theme.isDark() ? 0.42 : 0.26) : rowOnly ? (Theme.isDark() ? 0.14 : 0.07) : 0;
        if (f == 0) {
            return bg;
        }
        return new Color((int) Math.round(bg.getRed() * (1 - f) + a.getRed() * f),
                (int) Math.round(bg.getGreen() * (1 - f) + a.getGreen() * f),
                (int) Math.round(bg.getBlue() * (1 - f) + a.getBlue() * f));
    }

    private static javax.swing.border.Border leadBorder() {
        Color a = Theme.uiColor("Component.accentColor", new Color(0x2563EB));
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(a, 1),
                BorderFactory.createEmptyBorder(0, 1, 0, 1));
    }

    private static final javax.swing.border.Border CELL_PAD = BorderFactory.createEmptyBorder(1, 2, 1, 2);

    /** The colours and border of a cell, selected or not (both views). */
    private void paintCell(javax.swing.JLabel c, JTable t, int row, int col, Color bg, Color fg) {
        boolean cell = t.isCellSelected(row, col);
        // the row people are on is tinted faintly (not when whole columns are selected)
        boolean rowSel = t.isRowSelected(row) && t.getSelectedRowCount() < t.getRowCount();
        c.setBackground(selectedBackground(bg, cell, rowSel && !cell));
        c.setForeground(fg != null ? fg : Theme.foreground());
        boolean lead = cell && t.getSelectionModel().getLeadSelectionIndex() == row
                && t.getColumnModel().getSelectionModel().getLeadSelectionIndex() == col
                && t.getColumnModel().getSelectionModel().getMinSelectionIndex()
                    == t.getColumnModel().getSelectionModel().getMaxSelectionIndex()
                && t.getSelectedRowCount() == 1;
        c.setBorder(lead ? leadBorder() : CELL_PAD);
    }

    String selectionTextForTest(int side) {
        JTable t = side < 0 ? table : sideTables[side];
        return selectionText(t, side, false);
    }

    void selectColumnForTest(int side, int col, boolean extend) {
        selectColumn(side < 0 ? table : sideTables[side], col, extend, false);
    }

    void selectCellForTest(int side, int row, int col, boolean extend) {
        JTable t = side < 0 ? table : sideTables[side];
        t.changeSelection(row, col, false, extend);
        if (col < rowHeads(t, side)) {
            selectAllColumns();
        } else {
            other(t).forEach(o -> o.getColumnModel().getSelectionModel().clearSelection());
        }
    }

    JTable sideTableForTest(int side) {
        return sideTables[side];
    }

    private void setSideBySide(boolean on) {
        sideBySide = on;
        int want = on ? sideShow : unifiedShow;
        if (show.getSelectedIndex() != want) {
            show.setSelectedIndex(want); // the selected row stays selected if it is still shown
        }
        (on ? sideButton : unifiedButton).setSelected(true);
        if (!drawerOpen) {
            views.show(viewCards, on ? "side" : "unified");
        }
        fileRow.revalidate();
        try {
            AppState.prefs().putBoolean("compareSideBySide", on);
        } catch (RuntimeException e) {
            // remembered next time if it can be
        }
        int at = table.getSelectedRow();
        if (at >= 0) {
            SwingUtilities.invokeLater(() -> scrollTo(at));
        }
    }

    /** The table rows are shown in now (the left one, side by side). */
    private JTable viewTable() {
        return sideBySide ? sideTables[0] : table;
    }

    private void scrollTo(int row) {
        JTable t = viewTable();
        if (row >= 0 && row < t.getRowCount()) {
            t.scrollRectToVisible(t.getCellRect(row, 0, true));
        }
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        };
    }

    /**
     * What key repeats are, with an example from these files: "SKU-7 is on 2 rows of
     * stock.csv and 1 of stock-today.csv".
     */
    String repeatsTip(TableDiffResult r) {
        String keyNames = String.join(" + ", r.spec().keys().stream().map(p -> r.columns().name(p)).toList());
        StringBuilder b = new StringBuilder("<html>A key value that is on more than one row of a file: those rows can't be"
                + " paired one to one,<br>so they are listed (never paired at random) and not compared.");
        int[] items = r.filter(EnumSet.of(DiffStatus.DUPLICATE), -1);
        if (items.length > 0) {
            String key = r.keyText(items[0]);
            int left = 0;
            int right = 0;
            for (int i : items) {
                if (r.keyText(i).equals(key)) {
                    left += r.leftRow(i) >= 0 ? 1 : 0;
                    right += r.rightRow(i) >= 0 ? 1 : 0;
                }
            }
            b.append("<br><br>For example, ").append(keyNames).append(" <b>").append(esc(key)).append("</b> is on ")
                    .append(left).append(left == 1 ? " row" : " rows").append(" of ").append(esc(sides[0].name()))
                    .append(" and ").append(right).append(right == 1 ? " row" : " rows").append(" of ")
                    .append(esc(sides[1].name())).append('.');
        }
        b.append("<br><br>Usually the key isn't unique: tick another key column too (Choose ▾ next to Keys)"
                + "<br>to tell the rows apart. Or a file has a row twice, say exported twice.");
        if (items.length == 0) {
            b.append("<br><br>None here: every ").append(esc(keyNames)).append(" is on one row in each file.");
        }
        return b.append("</html>").toString();
    }

    String repeatsTipForTest() {
        return repeatsTip(result);
    }

    /** Whether Show offers key repeats (only a key can repeat). */
    boolean repeatsOfferedForTest() {
        return result != null && !result.spec().keys().isEmpty();
    }

    // ---------------------------------------------------------------- the files and keys

    String title() {
        return "Compare: " + sides[0].name() + " ↔ " + sides[1].name();
    }

    private void showFiles() {
        for (int s = 0; s < 2; s++) {
            CsvFile f = files[s];
            fileName[s].setText((s == 0 ? "Left: " : "Right: ") + sides[s].name());
            fileName[s].setToolTipText(sides[s].open().toString());
            String sep = switch (f.delimiter()) {
                case ',' -> "commas";
                case ';' -> "semicolons";
                case '\t' -> "tabs";
                case '|' -> "bars";
                default -> "'" + f.delimiter() + "'";
            };
            long size;
            try {
                size = Files.size(sides[s].read());
            } catch (IOException e) {
                size = -1;
            }
            TableDiffResult r = result;
            String rows = r == null ? "" : String.format(Locale.ROOT, "%,d rows · ", s == 0 ? r.leftRows() : r.rightRows());
            fileInfo[s].setText(rows + f.header().size() + " columns" + (size >= 0 ? " · " + MainFrame.humanBytes(size) : ""));
            fileInfo[s].setToolTipText(sep + " · " + f.charset().displayName());
        }
        fileRow.revalidate();
        host.titleChanged(this);
    }

    /** New files (or columns): match columns by name, suggest a key, then compare. */
    private void prepare() {
        // keys people chose stay when the files change (dropped, swapped), if the new files have them
        // (and so do the columns left out)
        List<String> chosen = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        if (keyHeader != null) {
            for (int k : keys) {
                chosen.add(keyHeader.get(k));
            }
            for (int k : ignored) {
                leftOut.add(keyHeader.get(k));
            }
        }
        map = ColumnMap.byName(files[0].header(), files[1].header());
        keyHeader = files[0].header();
        keys.clear();
        for (String name : chosen) {
            int c = keyHeader.indexOf(name);
            if (c >= 0 && map.pairOfLeft(c) >= 0 && !keys.contains(c)) {
                keys.add(c);
            }
        }
        ignored.clear();
        for (String name : leftOut) {
            int c = keyHeader.indexOf(name);
            if (c >= 0 && map.pairOfLeft(c) >= 0 && !keys.contains(c)) {
                ignored.add(c);
            }
        }
        suggested = List.of();
        suggestionReason = "Looking for a key…";
        showKeys();
        CsvFile a = files[0];
        CsvFile b = files[1];
        ColumnMap m = map;
        int gen = ++generation;
        AtomicBoolean stop = startWork("Looking for a key…");
        Thread t = new Thread(() -> {
            KeySuggester.Suggestion s;
            try {
                s = KeySuggester.suggest(a, b, m, stop::get);
            } catch (IOException | RuntimeException e) {
                s = new KeySuggester.Suggestion(List.of(), "Couldn't look for a key: " + e.getMessage());
            }
            KeySuggester.Suggestion got = s;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                endWork();
                if (stop.get()) {
                    status.setText("Cancelled");
                    return;
                }
                // suggested, not chosen: people pick the key (one click), rows stay in file order until then
                suggested = got.pairs().stream().map(p -> m.pairs().get(p).left()).toList();
                suggestionReason = got.reason();
                showKeys();
                runCompare();
            });
        }, "logsift-diff-keys");
        t.setDaemon(true);
        t.start();
    }

    private JButton useSuggested;

    private void showKeys() {
        keyChips.removeAll();
        useSuggested = null;
        JLabel l = new JLabel("Keys:");
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setToolTipText("<html>Rows with the same key values are matched, wherever they are in the files.<br>"
                + "Without keys, rows are lined up in file order.</html>");
        keyChips.add(l);
        for (int k : keys) {
            JButton chip = new JButton(files[0].header().get(k) + "  ✕");
            chip.putClientProperty("JButton.buttonType", "roundRect");
            chip.setToolTipText("Remove this key column");
            chip.addActionListener(e -> {
                keys.remove(Integer.valueOf(k));
                showKeys();
                runCompare();
            });
            keyChips.add(chip);
        }
        if (keys.isEmpty()) {
            JLabel none = new JLabel("none (rows in file order)");
            none.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            none.setToolTipText("Without keys, rows are lined up in file order");
            keyChips.add(none);
        }
        JButton add = new JButton("Choose ▾");
        add.setToolTipText("Tick the key columns (several make a key together)");
        add.addActionListener(e -> keyPicker(add));
        keysButton = add;
        keyChips.add(add);
        if (!suggested.isEmpty() && !suggested.equals(keys)) {
            String names = String.join(" + ", suggested.stream().map(i -> files[0].header().get(i)).toList());
            JButton use = new JButton("Use " + names + " as Key", Icons.of("sparkles"));
            use.putClientProperty("JButton.buttonType", "default");
            use.setToolTipText("Match rows by the suggested key");
            useSuggested = use;
            use.addActionListener(e -> {
                keys.clear();
                keys.addAll(suggested);
                showKeys();
                runCompare();
            });
            keyChips.add(use);
        }
        String names = String.join(" + ", suggested.stream().map(i -> files[0].header().get(i)).toList());
        suggestion.setText(suggested.isEmpty() ? suggestionReason
                : "Suggested: " + suggestionReason.replaceFirst("^" + java.util.regex.Pattern.quote(names) + ": ", names + " — "));
        // why it was suggested: on the button, or (in use, or none found) a short gray word
        if (useSuggested != null) {
            useSuggested.setToolTipText(suggestion.getText());
        } else if (!suggestionReason.isBlank()) {
            JLabel why = new JLabel(suggested.isEmpty() ? (isBusy() ? "looking for a key…" : "no key suggested")
                    : "✓ suggested");
            why.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            why.setToolTipText(suggestion.getText());
            keyChips.add(why);
        }
        ignoreOrder.setEnabled(keys.isEmpty());
        keyChips.revalidate();
        keyChips.repaint();
        showIgnored();
    }

    /** "Not compared:" the columns left out, each with ✕, and Choose ▾ (a list to tick). */
    private void showIgnored() {
        ignoreChips.removeAll();
        JLabel l = new JLabel("Not compared:");
        l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setToolTipText("<html>Columns left out of the comparison: their values don't make a row changed<br>"
                + "(a last-updated time, a row number). They are still shown.</html>");
        ignoreChips.add(l);
        List<Integer> left = new ArrayList<>(ignored);
        left.sort(null);
        for (int c : left) {
            JButton chip = new JButton(files[0].header().get(c) + "  ✕");
            chip.putClientProperty("JButton.buttonType", "roundRect");
            chip.setToolTipText("Compare this column again");
            chip.addActionListener(e -> {
                ignored.remove(c);
                showIgnored();
                runCompare();
            });
            ignoreChips.add(chip);
        }
        if (ignored.isEmpty()) {
            JLabel none = new JLabel("none");
            none.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            ignoreChips.add(none);
        }
        JButton add = new JButton("Choose ▾");
        add.setToolTipText("Tick the columns to leave out of the comparison");
        add.addActionListener(e -> leaveOutPicker(add));
        leaveOutButton = add;
        ignoreChips.add(add);
        ignoreChips.revalidate();
        ignoreChips.repaint();
    }

    private void ignoreColumn(int leftColumn) {
        if (!keys.contains(leftColumn) && ignored.add(leftColumn)) {
            showIgnored();
            runCompare();
        }
    }

    /** Columns ▾: which columns the table shows (they are all still compared), and pairing by hand. */
    private void columnsMenu(JButton from) {
        TableDiffResult r = result;
        if (r == null) {
            return;
        }
        JPopupMenu m = new JPopupMenu();
        JPanel checks = new JPanel();
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        JLabel head = new JLabel("Show in the table");
        head.setFont(head.getFont().deriveFont(Font.BOLD));
        head.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        checks.add(head);
        List<Integer> cols = new ArrayList<>();
        for (int c : r.compared()) {
            cols.add(c);
        }
        List<Integer> out = new ArrayList<>(r.spec().ignored());
        out.sort(null);
        cols.addAll(out);
        for (int c : cols) {
            String name = r.columns().name(c);
            JCheckBox cb = new JCheckBox(name, !hiddenColumns.contains(name));
            cb.addActionListener(e -> setColumnShown(name, cb.isSelected()));
            checks.add(cb);
        }
        JScrollPane sp = new JScrollPane(checks);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setPreferredSize(new Dimension(Math.max(220, checks.getPreferredSize().width + 24),
                Math.min(360, checks.getPreferredSize().height + 6)));
        m.add(sp);
        m.addSeparator();
        JMenuItem all = new JMenuItem("Show All Columns");
        all.setEnabled(!hiddenColumns.isEmpty());
        all.addActionListener(e -> {
            hiddenColumns.clear();
            refreshColumns();
        });
        m.add(all);
        JMenuItem pair = new JMenuItem("Pair Columns by Hand…");
        pair.setToolTipText("Compare a column with one of another name (cost in the left file with price in the right)");
        pair.addActionListener(e -> columnsDialog());
        m.add(pair);
        m.show(from, 0, from.getHeight());
    }

    private void setColumnShown(String name, boolean on) {
        if (on ? hiddenColumns.remove(name) : hiddenColumns.add(name)) {
            refreshColumns();
        }
    }

    /** The table's columns again (shown or hidden), keeping the row selected. */
    private void refreshColumns() {
        TableDiffResult r = result;
        if (r == null) {
            return;
        }
        int at = table.getSelectedRow();
        model.setColumns(r);
        for (SideModel m : sideModels) {
            m.setColumns(r);
        }
        if (at >= 0 && at < shown.length) {
            table.setRowSelectionInterval(at, at);
        }
    }

    /** The paired columns (left index), in the left file's order. */
    private List<Integer> pairedColumns() {
        List<Integer> out = new ArrayList<>();
        for (ColumnMap.Pair p : map.pairs()) {
            out.add(p.left());
        }
        out.sort(null);
        return out;
    }

    /** Keys ▾: tick the key columns; the comparison runs again when the list closes. */
    private ColumnPicker keyPicker(JButton from) {
        List<Integer> cols = pairedColumns();
        List<Integer> now = new ArrayList<>();
        for (int k : keys) {
            if (cols.contains(k)) {
                now.add(cols.indexOf(k));
            }
        }
        ColumnPicker p = new ColumnPicker("Key columns", "Several ticked make a key together",
                cols.stream().map(c -> map.name(map.pairOfLeft(c))).toList(), now, got -> {
                    keys.clear();
                    for (int i : got) {
                        keys.add(cols.get(i));
                        ignored.remove(cols.get(i));
                    }
                    showKeys();
                    runCompare();
                });
        lastPicker = p;
        p.show(from);
        return p;
    }

    /** Not compared ▾: tick the columns to leave out; the comparison runs again when the list closes. */
    private ColumnPicker leaveOutPicker(JButton from) {
        List<Integer> cols = new ArrayList<>(pairedColumns());
        cols.removeIf(keys::contains);
        List<Integer> now = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            if (ignored.contains(cols.get(i))) {
                now.add(i);
            }
        }
        ColumnPicker p = new ColumnPicker("Leave out of the comparison", "Still shown, in gray",
                cols.stream().map(c -> map.name(map.pairOfLeft(c))).toList(), now, got -> {
                    ignored.clear();
                    for (int i : got) {
                        ignored.add(cols.get(i));
                    }
                    showIgnored();
                    runCompare();
                });
        lastPicker = p;
        p.show(from);
        return p;
    }

    private JButton keysButton;
    private JButton leaveOutButton;
    private ColumnPicker lastPicker;

    ColumnPicker openKeyPickerForTest() {
        return keyPicker(keysButton);
    }

    ColumnPicker openLeaveOutPickerForTest() {
        return leaveOutPicker(leaveOutButton);
    }

    /** Settings changed since the last comparison. */
    private void stale() {
        if (running == null && result != null) {
            optionsChanged = true;
            status.setText("Changed: press Compare");
            status.setForeground(Theme.uiColor("Component.accentColor", new Color(0x2563EB)));
            highlightCompare(compare, true);
        }
        ignoreOrder.setEnabled(keys.isEmpty());
    }

    private void swap() {
        Side s = sides[0];
        sides[0] = sides[1];
        sides[1] = s;
        CsvFile f = files[0];
        files[0] = files[1];
        files[1] = f;
        result = null;
        showFiles();
        prepare();
    }

    // ---------------------------------------------------------------- dropping files

    private void setHover(int side) {
        if (side != hover) {
            hover = side;
            repaint();
        }
    }

    int hoverForTest() {
        return hover;
    }

    void setHoverForTest(int side) {
        setHover(side);
    }

    /** A drop of {@code dropped} at {@code p}, as the tab's drop handler takes it. */
    void dropForTest(List<Path> dropped, Point p) {
        dropTarget.dropped(dropped, dropTarget.sideAt(p, dropped.size()));
    }

    /** Files dropped: two fill both sides; one replaces {@code side}. Then it compares again. */
    void drop(List<Path> dropped, int side) {
        if (dropped.size() >= 2) {
            replace(dropped.subList(0, 2), FileDrop.BOTH);
        } else if (!dropped.isEmpty()) {
            replace(dropped, side == FileDrop.BOTH ? 0 : side);
        }
    }

    /** Reads new files for a side (or both) in the background, then compares them. */
    private void replace(List<Path> paths, int side) {
        int gen = ++generation;
        AtomicBoolean stop = startWork("Reading " + paths.get(0).getFileName() + "…");
        Thread t = new Thread(() -> {
            List<Side> got = new ArrayList<>();
            List<CsvFile> opened = new ArrayList<>();
            String error = null;
            try {
                for (Path p : paths) {
                    Side s = host.sideFor(p);
                    got.add(s);
                    opened.add(CsvFile.open(s.read(), s.name()));
                }
            } catch (IOException | RuntimeException e) {
                error = e instanceof CsvFile.NotCsvException ? e.getMessage()
                        : "Couldn't read " + paths.get(opened.size()).getFileName() + ": " + e.getMessage();
                opened.forEach(CsvFile::close);
            }
            String err = error;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    opened.forEach(CsvFile::close);
                    return;
                }
                endWork();
                if (err != null) {
                    status.setText(err);
                    status.setForeground(Theme.errorMark());
                    if (HeapTab.showNotes) {
                        JOptionPane.showMessageDialog(this, err + (err.contains("CSV") ? "\n\nLogsift compares CSV and"
                                + " TSV files as tables for now; comparing text and logs line by line comes next." : ""),
                                "Compare", JOptionPane.INFORMATION_MESSAGE);
                    }
                    return;
                }
                int[] which = side == FileDrop.BOTH ? new int[] {0, 1} : new int[] {side};
                for (int i = 0; i < which.length; i++) {
                    toClose.add(files[which[i]]);
                    files[which[i]] = opened.get(i);
                    sides[which[i]] = got.get(i);
                }
                result = null;
                showFiles();
                prepare();
            });
        }, "logsift-diff-open");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void paint(Graphics g0) {
        super.paint(g0);
        if (hover < 0) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color accent = Theme.uiColor("Component.accentColor", new Color(0x2563EB));
        int w = getWidth();
        int h = getHeight();
        int[][] boxes = hover == FileDrop.BOTH ? new int[][] {{0, w / 2}, {w / 2, w - w / 2}}
                : hover == 0 ? new int[][] {{0, w / 2}} : new int[][] {{w / 2, w - w / 2}};
        String[] what = {"Drop to compare as the left file", "Drop to compare as the right file"};
        for (int i = 0; i < boxes.length; i++) {
            int x = boxes[i][0];
            int bw = boxes[i][1];
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40));
            g.fillRoundRect(x + 4, 4, bw - 8, h - 8, 14, 14);
            g.setColor(accent);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {10f, 6f}, 0f));
            g.drawRoundRect(x + 4, 4, bw - 8, h - 8, 14, 14);
            int side = hover == FileDrop.BOTH ? i : hover;
            String text = what[side];
            g.setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() + 4));
            int tw = g.getFontMetrics().stringWidth(text);
            int tx = x + (bw - tw) / 2;
            int ty = h / 2;
            g.setColor(Theme.background());
            g.fillRoundRect(tx - 12, ty - g.getFontMetrics().getAscent() - 6, tw + 24, g.getFontMetrics().getHeight() + 12,
                    10, 10);
            g.setColor(accent);
            g.drawString(text, tx, ty);
        }
        g.dispose();
    }

    // ---------------------------------------------------------------- comparing

    /**
     * A label as wide as {@code chars} characters whatever its text (longer text ends in …, the
     * whole of it in the tooltip), so a toolbar doesn't wrap differently, and move what is below
     * it, when the text changes.
     */
    static JLabel fixedWidth(JLabel l, int chars) {
        java.awt.FontMetrics fm = l.getFontMetrics(l.getFont());
        Dimension d = new Dimension(fm.charWidth('n') * chars, fm.getHeight() + 2);
        l.setPreferredSize(d);
        l.setMinimumSize(d);
        l.addPropertyChangeListener("text", e -> {
            String t = l.getText();
            l.setToolTipText(t != null && fm.stringWidth(t) > d.width ? t : null);
        });
        return l;
    }

    /** A Compare button in the accent colour: something changed, a click shows it. */
    static void highlightCompare(JButton b, boolean on) {
        if (on) {
            Color a = Theme.uiColor("Component.accentColor", new Color(0x2563EB));
            String hex = hex(a);
            b.putClientProperty("FlatLaf.style", "background: " + hex + "; foreground: #ffffff; borderColor: " + hex
                    + "; hoverBackground: " + hex(shade(a, 0.9)) + "; pressedBackground: " + hex(shade(a, 0.8)));
        } else {
            b.putClientProperty("FlatLaf.style", null);
        }
        b.putClientProperty("logsift.highlighted", on);
        b.repaint();
    }

    private static Color shade(Color c, double f) {
        return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f));
    }

    static boolean isHighlighted(JButton b) {
        return Boolean.TRUE.equals(b.getClientProperty("logsift.highlighted"));
    }

    boolean compareHighlightedForTest() {
        return isHighlighted(compare);
    }

    /** An option's tick, as a click on it. */
    void clickTrimForTest() {
        trim.doClick();
    }

    private AtomicBoolean startWork(String what) {
        highlightCompare(compare, false);
        if (running != null) {
            running.set(true);
        }
        running = new AtomicBoolean();
        compare.setEnabled(false);
        progress.setValue(0);
        progress.setVisible(true);
        cancel.setVisible(true);
        status.setForeground(Theme.uiColor("Label.foreground", Color.BLACK));
        status.setText(what);
        return running;
    }

    private void endWork() {
        running = null;
        compare.setEnabled(true);
        progress.setVisible(false);
        cancel.setVisible(false);
    }

    /** Whether a comparison (or the key search) is going on. */
    boolean isBusy() {
        return running != null;
    }

    private DiffOptions options() {
        double tol = 0;
        try {
            tol = Double.parseDouble(tolerance.getText().strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            // 0
        }
        return new DiffOptions(trim.isSelected(), ignoreCase.isSelected(), numbers.isSelected(), Math.max(0, tol),
                toleranceUnit.getSelectedIndex() == 1, emptyMissing.isSelected(), ignoreOrder.isSelected());
    }

    void runCompare() {
        List<Integer> keyPairs = new ArrayList<>();
        for (int k : keys) {
            int p = map.pairOfLeft(k);
            if (p >= 0) {
                keyPairs.add(p);
            }
        }
        Set<Integer> ign = new HashSet<>();
        for (int c : ignored) {
            int p = map.pairOfLeft(c);
            if (p >= 0 && !keyPairs.contains(p)) {
                ign.add(p);
            }
        }
        TableDiff.Spec spec = new TableDiff.Spec(map, keyPairs, ign, options());
        CsvFile a = files[0];
        CsvFile b = files[1];
        int gen = ++generation;
        AtomicBoolean stop = startWork("Comparing…");
        Thread t = new Thread(() -> {
            TableDiffResult r = null;
            String error = null;
            try {
                r = TableDiff.compare(a, b, spec, f -> SwingUtilities.invokeLater(() -> progress.setValue((int) (f * 1000))),
                        stop::get);
            } catch (CancellationException e) {
                error = "Cancelled";
            } catch (IOException | RuntimeException e) {
                error = "Couldn't compare: " + e.getMessage();
            }
            TableDiffResult got = r;
            String err = error;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                endWork();
                if (err != null) {
                    status.setText(err);
                    if (!err.equals("Cancelled")) {
                        status.setForeground(Theme.errorMark());
                    }
                    return;
                }
                setResult(got);
            });
        }, "logsift-diff");
        t.setDaemon(true);
        t.start();
    }

    private void setResult(TableDiffResult r) {
        result = r;
        shown = new int[0]; // the rows shown were the old result's: none until it is filtered again
        for (CsvFile f : toClose) {
            if (f != files[0] && f != files[1]) {
                f.close();
            }
        }
        toClose.clear();
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText(String.format(Locale.ROOT, "%s · %,d ms", r.matchedBy(), r.millis()));
        show.repaint();
        doneText = status.getText();
        optionsChanged = false;
        List<String> n = r.notes();
        notes.setVisible(!n.isEmpty());
        notes.setText(n.isEmpty() ? " " : String.join(" · ", n));
        notes.setToolTipText(n.isEmpty() ? null : "<html>" + String.join("<br>", n.stream().map(CsvCompareTab::esc).toList())
                + "</html>");
        columnModel.clear();
        columnModel.addElement(new ColumnCount(-1, "All columns", 0));
        for (Map.Entry<Integer, Integer> e : r.changesPerColumn().entrySet()) {
            columnModel.addElement(new ColumnCount(e.getKey(), r.columns().name(e.getKey()), e.getValue()));
        }
        columnFilter = -1;
        columnList.setSelectedIndex(0);
        showFiles();
        model.setColumns(r);
        for (SideModel m : sideModels) {
            m.setColumns(r);
        }
        refilter();
        if (shown.length > 0) {
            selectRow(0);
        }
    }

    // ---------------------------------------------------------------- the rows shown

    private Set<DiffStatus> statuses() {
        return switch (show.getSelectedIndex()) {
            case 1 -> EnumSet.of(DiffStatus.CHANGED);
            case 2 -> EnumSet.of(DiffStatus.ADDED);
            case 3 -> EnumSet.of(DiffStatus.REMOVED);
            case 4 -> EnumSet.of(DiffStatus.DUPLICATE);
            case 5 -> EnumSet.of(DiffStatus.SAME);
            case 6 -> EnumSet.allOf(DiffStatus.class);
            default -> EnumSet.complementOf(EnumSet.of(DiffStatus.SAME));
        };
    }

    private void refilter() {
        TableDiffResult r = result;
        if (r == null) {
            setShown(new int[0]);
            return;
        }
        int[] base = r.filter(statuses(), columnFilter);
        String q = search.getText().strip();
        if (searching != null) {
            searching.set(true);
            searching = null;
        }
        if (q.isEmpty()) {
            setShown(base);
            return;
        }
        AtomicBoolean stop = new AtomicBoolean();
        searching = stop;
        position.setText("Searching…");
        Thread t = new Thread(() -> {
            int[] hits;
            try {
                hits = r.search(base, q, stop::get);
            } catch (CancellationException | java.io.UncheckedIOException e) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (searching == stop && result == r) {
                    searching = null;
                    setShown(hits);
                }
            });
        }, "logsift-diff-search");
        t.setDaemon(true);
        t.start();
    }

    private void setShown(int[] items) {
        int sel = table.getSelectedRow();
        int keep = sel >= 0 && sel < shown.length ? shown[sel] : -1; // the selected row, if it is still shown
        shown = items;
        model.fireTableDataChanged();
        for (SideModel m : sideModels) {
            m.fireTableDataChanged();
        }
        if (keep >= 0) {
            int at = java.util.Arrays.binarySearch(items, keep);
            if (at >= 0) {
                table.setRowSelectionInterval(at, at);
                SwingUtilities.invokeLater(() -> scrollTo(at));
            }
        }
        for (ChangeStrip c : strips) {
            c.repaint();
        }
        updatePosition();
    }

    private final List<ChangeStrip> strips = new ArrayList<>();

    /**
     * Next to the scroll bar: a mark for each difference in the rows shown (green added, red
     * removed, amber changed, violet key repeats), placed as far down as the row is. A click
     * goes to the row there.
     */
    private final class ChangeStrip extends JComponent {
        ChangeStrip() {
            setPreferredSize(new Dimension(12, 10));
            setToolTipText("Where the differences are: click to go there");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter go = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    jump(e.getY());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    jump(e.getY());
                }
            };
            addMouseListener(go);
            addMouseMotionListener(go);
            strips.add(this);
        }

        private int top() {
            return 2;
        }

        private int span() {
            return Math.max(1, getHeight() - 4);
        }

        private void jump(int y) {
            int n = shown.length;
            if (n == 0) {
                return;
            }
            int row = Math.max(0, Math.min(n - 1, (int) ((long) (y - top()) * n / span())));
            // a mark is a pixel or more: go to the difference nearest the click
            TableDiffResult r = result;
            if (r != null && !r.status(shown[row]).isDifference()) {
                int reach = (int) Math.max(2, (long) n * 3 / span());
                for (int d = 1; d <= reach; d++) {
                    if (row + d < n && r.status(shown[row + d]).isDifference()) {
                        row += d;
                        break;
                    }
                    if (row - d >= 0 && r.status(shown[row - d]).isDifference()) {
                        row -= d;
                        break;
                    }
                }
            }
            selectRow(row);
            scrollTo(row);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Theme.uiColor("ScrollBar.track", Theme.background()));
            g.fillRect(0, 0, getWidth(), getHeight());
            TableDiffResult r = result;
            int[] s = shown;
            int n = s.length;
            if (r == null || n == 0) {
                return;
            }
            int h = span();
            // per pixel, the strongest status there: removed and added over changed over repeats
            byte[] px = new byte[h];
            for (int i = 0; i < n; i++) {
                int rank = switch (r.status(s[i])) {
                    case REMOVED -> 4;
                    case ADDED -> 3;
                    case CHANGED -> 2;
                    case DUPLICATE -> 1;
                    case SAME -> 0;
                };
                if (rank > 0) {
                    int y = (int) ((long) i * h / n);
                    if (rank > px[y]) {
                        px[y] = (byte) rank;
                    }
                }
            }
            int markH = Math.max(2, h / n);
            for (int y = 0; y < h; y++) {
                if (px[y] > 0) {
                    g.setColor(switch (px[y]) {
                        case 4 -> removedColor();
                        case 3 -> addedColor();
                        case 2 -> changedColor();
                        default -> duplicateColor();
                    });
                    g.fillRect(2, top() + y, getWidth() - 4, markH);
                }
            }
            // where the view is now
            JTable t = viewTable();
            if (t.getParent() instanceof javax.swing.JViewport v && t.getHeight() > 0) {
                java.awt.Rectangle vr = v.getViewRect();
                int y0 = top() + (int) ((long) vr.y * h / t.getHeight());
                int y1 = top() + (int) ((long) (vr.y + vr.height) * h / t.getHeight());
                g.setColor(Theme.pick(new Color(0, 0, 0, 60), new Color(255, 255, 255, 70)));
                g.drawRect(0, y0, getWidth() - 1, Math.max(3, y1 - y0 - 1));
            }
        }
    }

    void clickChangeStripForTest(double fraction) {
        ChangeStrip c = strips.get(sideBySide ? 1 : 0);
        c.jump(c.top() + (int) (fraction * c.span()));
    }

    private ChangeNavigator navigator() {
        TableDiffResult r = result;
        int[] s = shown;
        IntPredicate change = i -> r != null && r.status(s[i]).isDifference();
        return ChangeNavigator.of(s.length, change, false);
    }

    /** F7 / Shift+F7: selects the next (or previous) row that differs. */
    void nextChange(boolean forward) {
        if (result == null) {
            return;
        }
        ChangeNavigator nav = navigator();
        int at = table.getSelectedRow();
        int to = forward ? nav.next(at) : nav.previous(at < 0 ? shown.length : at);
        if (to >= 0) {
            selectRow(to);
            scrollTo(to);
        }
        updatePosition();
    }

    private void updatePosition() {
        TableDiffResult r = result;
        if (r == null) {
            position.setText(" ");
            return;
        }
        ChangeNavigator nav = navigator();
        int at = table.getSelectedRow();
        String rows = String.format(Locale.ROOT, "%,d rows", shown.length);
        if (at >= 0 && at < shown.length && r.status(shown[at]).isDifference()) {
            position.setText(String.format(Locale.ROOT, "difference %,d of %,d · %s", nav.ordinal(at), nav.count(), rows));
        } else {
            position.setText(String.format(Locale.ROOT, "%,d differences · %s", nav.count(), rows));
        }
    }

    /** Opens the row's line: {@code side} 0 the left file, 1 the right, -1 the side it is on (the right if both). */
    private void openRow(int viewRow, int side) {
        TableDiffResult r = result;
        if (r == null || viewRow < 0 || viewRow >= shown.length) {
            return;
        }
        int item = shown[viewRow];
        int s = side >= 0 ? side : r.rightRow(item) >= 0 ? 1 : 0;
        long line = s == 0 ? r.leftLine(item) : r.rightLine(item);
        if (line >= 0) {
            host.openLine(sides[s].open(), line);
        }
    }

    private void popup(MouseEvent e, JTable table) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        TableDiffResult r = result;
        if (row < 0 || r == null) {
            return;
        }
        int col = table.columnAtPoint(e.getPoint());
        if (!table.isCellSelected(row, Math.max(0, col))) {
            table.changeSelection(row, Math.max(0, col), false, false);
        }
        int item = shown[row];
        JPopupMenu m = new JPopupMenu();
        int view = table == this.table ? -1 : table == sideTables[0] ? 0 : 1;
        JMenuItem copy = new JMenuItem("Copy", Icons.of("copy"));
        copy.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copy.addActionListener(a -> copySelection(table, view, false));
        m.add(copy);
        JMenuItem copyTitles = new JMenuItem("Copy with Column Titles");
        copyTitles.addActionListener(a -> copySelection(table, view, true));
        m.add(copyTitles);
        m.addSeparator();
        for (int s = 0; s < 2; s++) {
            long line = s == 0 ? r.leftLine(item) : r.rightLine(item);
            JMenuItem it = new JMenuItem(line < 0 ? "Not in " + sides[s].name()
                    : "Open " + sides[s].name() + " at Line " + String.format(Locale.ROOT, "%,d", line + 1),
                    Icons.of("file-text"));
            it.setEnabled(line >= 0);
            int side = s;
            it.addActionListener(a -> openRow(row, side));
            m.add(it);
        }
        m.show(table, e.getX(), e.getY());
    }

    // ---------------------------------------------------------------- columns

    private void columnsDialog() {
        List<String> ln = files[0].header();
        List<String> rn = files[1].header();
        String none = "— not compared —";
        DefaultTableModel m = new DefaultTableModel(new Object[] {"Left column", "Right column"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return c == 1;
            }
        };
        for (int i = 0; i < ln.size(); i++) {
            int p = map.pairOfLeft(i);
            m.addRow(new Object[] {ln.get(i), p >= 0 ? rn.get(map.pairs().get(p).right()) : none});
        }
        JTable t = new JTable(m);
        JComboBox<String> rights = new JComboBox<>();
        rights.addItem(none);
        rn.forEach(rights::addItem);
        t.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(rights));
        t.setRowHeight(t.getRowHeight() + 4);
        List<String> rightOnly = map.rightOnly().stream().map(rn::get).toList();
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html>Columns are matched by name (case and spaces don't count). Pick another right column to"
                + "<br>compare a column with. (To leave a column out: Choose ▾ next to Not compared.)</html>"),
                BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(560, Math.min(420, 60 + ln.size() * t.getRowHeight())));
        p.add(sp, BorderLayout.CENTER);
        if (!rightOnly.isEmpty()) {
            p.add(new JLabel("Only in the right file: " + String.join(", ", rightOnly)), BorderLayout.SOUTH);
        }
        if (JOptionPane.showConfirmDialog(this, p, "Pair Columns", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (t.isEditing()) {
            t.getCellEditor().stopCellEditing();
        }
        List<ColumnMap.Pair> pairs = new ArrayList<>();
        Set<Integer> usedRight = new HashSet<>();
        for (int i = 0; i < ln.size(); i++) {
            String right = String.valueOf(m.getValueAt(i, 1));
            int ri = rn.indexOf(right);
            if (ri >= 0 && usedRight.add(ri)) {
                pairs.add(new ColumnMap.Pair(i, ri));
            }
        }
        setColumns(new ColumnMap(ln, rn, pairs));
    }

    /** New column pairs (from the Columns window or a test); keys not paired any more are dropped. */
    void setColumns(ColumnMap m) {
        map = m;
        keys.removeIf(k -> map.pairOfLeft(k) < 0);
        ignored.removeIf(k -> map.pairOfLeft(k) < 0);
        showKeys();
        runCompare();
    }

    // ---------------------------------------------------------------- export

    private void exportMenu(JButton from) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem csv = new JMenuItem("Differences as CSV…", Icons.of("download"));
        csv.setToolTipText("Every row that differs: status, lines, key, and each column's left and right value");
        csv.addActionListener(e -> exportCsv(false));
        JMenuItem shownCsv = new JMenuItem("Rows Shown as CSV…");
        shownCsv.addActionListener(e -> exportCsv(true));
        JMenuItem copy = new JMenuItem("Copy Summary", Icons.of("copy"));
        copy.addActionListener(e -> {
            if (result != null) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(result.summary()), null);
            }
        });
        JMenuItem save = new JMenuItem("Save Summary…", Icons.of("save"));
        save.addActionListener(e -> {
            Path out = saveAs("compare-summary.txt");
            if (out != null && result != null) {
                try {
                    Files.writeString(out, result.summary());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Couldn't save: " + ex.getMessage(), "Export",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        for (JMenuItem it : List.of(csv, shownCsv, copy, save)) {
            it.setEnabled(result != null);
            m.add(it);
        }
        m.show(from, 0, from.getHeight());
    }

    private Path saveAs(String name) {
        FileDialog fd = new FileDialog(SwingUtilities.getWindowAncestor(this) instanceof java.awt.Frame f ? f : null,
                "Export", FileDialog.SAVE);
        Path dir = sides[1].open().getParent();
        if (dir != null) {
            fd.setDirectory(dir.toString());
        }
        fd.setFile(name);
        fd.setVisible(true);
        return fd.getFile() == null ? null : Path.of(fd.getDirectory(), fd.getFile());
    }

    private void exportCsv(boolean onlyShown) {
        TableDiffResult r = result;
        if (r == null) {
            return;
        }
        Path out = saveAs("differences.csv");
        if (out == null) {
            return;
        }
        int[] items = onlyShown ? shown.clone() : r.filter(EnumSet.complementOf(EnumSet.of(DiffStatus.SAME)), -1);
        ProgressDialog.run(SwingUtilities.getWindowAncestor(this), "Exporting…", (prog, cancelled) -> {
            write(r, items, out, cancelled);
            return out;
        }, p -> status.setText("Saved " + p.getFileName()));
    }

    private static void write(TableDiffResult r, int[] items, Path out, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            TableDiffExport.writeCsv(r, items, w, cancelled);
        }
    }

    /** For tests: the differences to a file, now. */
    void exportForTest(Path out) throws IOException {
        write(result, result.filter(EnumSet.complementOf(EnumSet.of(DiffStatus.SAME)), -1), out, null);
    }

    // ---------------------------------------------------------------- the table

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String shortText(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎');
        return s.length() > 400 ? s.substring(0, 399) + "…" : s;
    }

    /** Status, lines, the key columns, then the compared columns. */
    private final class Model extends AbstractTableModel {
        private int[] pairs = new int[0];
        private int keyCount;
        private final Set<Integer> notCompared = new HashSet<>();
        private TableDiffResult columnsOf;

        void setColumns(TableDiffResult r) {
            columnsOf = r;
            List<Integer> p = new ArrayList<>(r.spec().keys());
            keyCount = p.size();
            for (int c : r.compared()) {
                if (!hiddenColumns.contains(r.columns().name(c))) {
                    p.add(c);
                }
            }
            // columns left out: shown last, gray, for their values
            notCompared.clear();
            List<Integer> out = new ArrayList<>(r.spec().ignored());
            out.sort(null);
            for (int c : out) {
                if (!hiddenColumns.contains(r.columns().name(c))) {
                    p.add(c);
                    notCompared.add(c);
                }
            }
            pairs = p.stream().mapToInt(Integer::intValue).toArray();
            fireTableStructureChanged();
            table.getColumnModel().getColumn(0).setPreferredWidth(120);
            table.getColumnModel().getColumn(1).setPreferredWidth(110);
            for (int i = 2; i < getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(i - 2 < keyCount ? 110 : 150);
            }
        }

        @Override
        public int getRowCount() {
            return columnsOf == null ? 0 : shown.length;
        }

        @Override
        public int getColumnCount() {
            return columnsOf == null ? 0 : 2 + pairs.length;
        }

        @Override
        public String getColumnName(int c) {
            if (c == 0) {
                return "Status";
            }
            if (c == 1) {
                return "Line (left → right)";
            }
            String n = columnsOf.columns().name(pairs[c - 2]);
            return c - 2 < keyCount ? n + " (key)" : notCompared.contains(pairs[c - 2]) ? n + " (not compared)" : n;
        }

        @Override
        public Object getValueAt(int row, int col) {
            return row < shown.length ? shown[row] : -1;
        }

        boolean isKey(int col) {
            return col >= 2 && col - 2 < keyCount;
        }

        int pair(int col) {
            return pairs[col - 2];
        }
    }

    /**
     * One file's half of the side-by-side view: (the status, on the left), its line, the key
     * columns and the compared ones, with that file's values. A row the file doesn't have is empty.
     */
    private final class SideModel extends AbstractTableModel {
        final int side;
        private TableDiffResult columnsOf;

        SideModel(int side) {
            this.side = side;
        }

        void setColumns(TableDiffResult r) {
            columnsOf = r;
            fireTableStructureChanged();
            JTable t = sideTables[side];
            for (int i = 0; i < getColumnCount(); i++) {
                int fixed = fixedColumns();
                t.getColumnModel().getColumn(i).setPreferredWidth(i < fixed ? (side == 0 && i == 0 ? 110 : 70)
                        : model.isKey(i - fixed + 2) ? 110 : 140);
            }
        }

        /** The status (left half only) and the line. */
        int fixedColumns() {
            return side == 0 ? 2 : 1;
        }

        @Override
        public int getRowCount() {
            return columnsOf == null ? 0 : shown.length;
        }

        @Override
        public int getColumnCount() {
            return columnsOf == null ? 0 : fixedColumns() + model.pairs.length;
        }

        @Override
        public String getColumnName(int c) {
            int fixed = fixedColumns();
            if (c < fixed) {
                return side == 0 && c == 0 ? "Status" : "Line";
            }
            int pair = model.pairs[c - fixed];
            ColumnMap m = columnsOf.columns();
            ColumnMap.Pair p = m.pairs().get(pair);
            String n = side == 0 ? m.leftNames().get(p.left()) : m.rightNames().get(p.right());
            return model.isKey(c - fixed + 2) ? n + " (key)" : model.notCompared.contains(pair) ? n + " (not compared)" : n;
        }

        @Override
        public Object getValueAt(int row, int col) {
            return row < shown.length ? shown[row] : -1;
        }

        /** The pair shown in column {@code col}, or -1 for the status and line. */
        int pair(int col) {
            return col < fixedColumns() ? -1 : model.pairs[col - fixedColumns()];
        }
    }

    /** What a cell of a side's table shows (without colours): "" where the file doesn't have the row. */
    private String sideText(TableDiffResult r, int side, int item, int col) {
        SideModel m = sideModels[side];
        boolean has = side == 0 ? r.leftRow(item) >= 0 : r.rightRow(item) >= 0;
        if (side == 0 && col == 0) {
            DiffStatus s = r.status(item);
            return s.symbol() + "  " + s.label();
        }
        if (col < m.fixedColumns()) {
            return has ? line(side == 0 ? r.leftLine(item) : r.rightLine(item)) : "";
        }
        if (!has) {
            return "";
        }
        int pair = m.pair(col);
        return shortText(side == 0 ? r.leftValue(item, pair) : r.rightValue(item, pair));
    }

    private final class SideRenderer extends DefaultTableCellRenderer {
        private final int side;

        SideRenderer(int side) {
            this.side = side;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            try {
                return render(t, v, sel, focus, row, col);
            } catch (java.io.UncheckedIOException e) {
                return super.getTableCellRendererComponent(t, "(can't read the file: " + e.getCause().getMessage() + ")",
                        sel, focus, row, col);
            }
        }

        private Component render(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            TableDiffResult r = result;
            if (r == null || !(v instanceof Integer item) || item < 0 || row >= shown.length) {
                return super.getTableCellRendererComponent(t, "", sel, focus, row, col);
            }
            SideModel m = sideModels[side];
            DiffStatus s = r.status(item);
            boolean has = side == 0 ? r.leftRow(item) >= 0 : r.rightRow(item) >= 0;
            String text = sideText(r, side, item, col);
            Color fg = null;
            Color bg;
            boolean bold = false;
            if (!has && !(side == 0 && col == 0)) {
                bg = Theme.pick(new Color(0xEEF0F3), new Color(0x2A2C30)); // not in this file
            } else {
                bg = switch (s) {
                    case ADDED -> Theme.pick(new Color(0xECFDF3), new Color(0x1B2D21));
                    case REMOVED -> Theme.pick(new Color(0xFEF2F2), new Color(0x33201F));
                    case DUPLICATE -> Theme.pick(new Color(0xF5F3FF), new Color(0x2A2440));
                    case CHANGED -> Theme.pick(new Color(0xFFFBEB), new Color(0x2B271B)); // the whole row, faintly
                    default -> row % 2 == 1 ? Theme.stripe() : Theme.background();
                };
            }
            int align = SwingConstants.LEADING;
            if (side == 0 && col == 0) {
                bold = true;
                fg = switch (s) {
                    case ADDED -> addedColor();
                    case REMOVED -> removedColor();
                    case CHANGED -> changedColor();
                    case DUPLICATE -> duplicateColor();
                    case SAME -> Theme.gutterForeground();
                };
            } else if (col < m.fixedColumns()) {
                fg = Theme.gutterForeground();
                align = SwingConstants.TRAILING;
            } else if (has) {
                int pair = m.pair(col);
                bold = model.isKey(col - m.fixedColumns() + 2);
                if (model.notCompared.contains(pair)) {
                    fg = Theme.gutterForeground();
                } else if (s == DiffStatus.CHANGED && r.isChanged(item, pair)) {
                    bg = Theme.pick(new Color(0xFDE5A8), new Color(0x4A3A12));
                    fg = side == 0 ? removedColor() : addedColor();
                    bold = true;
                } else if (s == DiffStatus.ADDED) {
                    fg = addedColor();
                } else if (s == DiffStatus.REMOVED) {
                    fg = removedColor();
                }
            }
            super.getTableCellRendererComponent(t, text, sel, focus, row, col);
            setHorizontalAlignment(align);
            setFont(bold ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
            paintCell(this, t, row, col, bg, fg);
            setToolTipText(has && text.length() > 30 ? text : null);
            return this;
        }
    }

    /** Text of a cell (what the renderer shows, without colours), for tests and copying. */
    String cellText(int row, int col) {
        TableDiffResult r = result;
        int item = shown[row];
        DiffStatus s = r.status(item);
        if (col == 0) {
            return s.symbol() + " " + s.label();
        }
        if (col == 1) {
            return line(r.leftLine(item)) + " → " + line(r.rightLine(item));
        }
        int pair = model.pair(col);
        String a = r.leftValue(item, pair);
        String b = r.rightValue(item, pair);
        if (s == DiffStatus.CHANGED && r.isChanged(item, pair)) {
            return shortText(a) + " → " + shortText(b);
        }
        return shortText(r.leftRow(item) >= 0 ? a : b);
    }

    private static String line(long l) {
        return l < 0 ? "—" : String.format(Locale.ROOT, "%,d", l + 1);
    }

    private static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    static Color addedColor() {
        return Theme.pick(new Color(0x15803D), new Color(0x4ADE80));
    }

    static Color removedColor() {
        return Theme.pick(new Color(0xB91C1C), new Color(0xF87171));
    }

    static Color changedColor() {
        return Theme.pick(new Color(0xB45309), new Color(0xFBBF24));
    }

    static Color duplicateColor() {
        return Theme.pick(new Color(0x7C3AED), new Color(0xC4B5FD));
    }

    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            try {
                return render(t, v, sel, focus, row, col);
            } catch (java.io.UncheckedIOException e) {
                return super.getTableCellRendererComponent(t, "(can't read the file: " + e.getCause().getMessage() + ")",
                        sel, focus, row, col);
            }
        }

        private Component render(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            TableDiffResult r = result;
            if (r == null || !(v instanceof Integer item) || item < 0 || row >= shown.length) {
                return super.getTableCellRendererComponent(t, "", sel, focus, row, col);
            }
            DiffStatus s = r.status(item);
            String text;
            boolean html = false;
            Color fg = null;
            Color bg = switch (s) {
                case ADDED -> Theme.pick(new Color(0xECFDF3), new Color(0x1B2D21));
                case REMOVED -> Theme.pick(new Color(0xFEF2F2), new Color(0x33201F));
                case DUPLICATE -> Theme.pick(new Color(0xF5F3FF), new Color(0x2A2440));
                case CHANGED -> Theme.pick(new Color(0xFFFBEB), new Color(0x2B271B));
                default -> row % 2 == 1 ? Theme.stripe() : Theme.background();
            };
            int align = SwingConstants.LEADING;
            if (col == 0) {
                text = s.symbol() + "  " + s.label();
                fg = switch (s) {
                    case ADDED -> addedColor();
                    case REMOVED -> removedColor();
                    case CHANGED -> changedColor();
                    case DUPLICATE -> duplicateColor();
                    case SAME -> Theme.gutterForeground();
                };
            } else if (col == 1) {
                text = line(r.leftLine(item)) + " → " + line(r.rightLine(item));
                fg = Theme.gutterForeground();
                align = SwingConstants.TRAILING;
            } else {
                int pair = model.pair(col);
                if (s == DiffStatus.CHANGED && r.isChanged(item, pair)) {
                    html = true;
                    text = "<html><nobr><span style='color:" + hex(removedColor()) + "'><s>"
                            + esc(shortText(r.leftValue(item, pair))) + "</s></span> → <span style='color:"
                            + hex(addedColor()) + "'><b>" + esc(shortText(r.rightValue(item, pair))) + "</b></span></nobr></html>";
                    bg = Theme.pick(new Color(0xFDE5A8), new Color(0x4A3A12));
                } else {
                    String a = r.leftRow(item) >= 0 ? r.leftValue(item, pair) : r.rightValue(item, pair);
                    text = shortText(a);
                    if (model.notCompared.contains(pair)) {
                        fg = Theme.gutterForeground();
                    } else if (s == DiffStatus.ADDED) {
                        fg = addedColor();
                    } else if (s == DiffStatus.REMOVED) {
                        fg = removedColor();
                    }
                }
            }
            super.getTableCellRendererComponent(t, text, sel, focus, row, col);
            setHorizontalAlignment(align);
            setFont(col == 0 ? t.getFont().deriveFont(Font.BOLD) : model.isKey(col) ? t.getFont().deriveFont(Font.BOLD)
                    : t.getFont());
            paintCell(this, t, row, col, bg, fg);
            setToolTipText(html ? "left: " + cellLeft(r, item, col) + "  →  right: " + cellRight(r, item, col) : null);
            return this;
        }

        private String cellLeft(TableDiffResult r, int item, int col) {
            return shortText(r.leftValue(item, model.pair(col)));
        }

        private String cellRight(TableDiffResult r, int item, int col) {
            return shortText(r.rightValue(item, model.pair(col)));
        }
    }

    // ---------------------------------------------------------------- closing and tests

    void close() {
        generation++;
        if (running != null) {
            running.set(true);
        }
        if (searching != null) {
            searching.set(true);
        }
        searchTimer.stop();
        for (CsvFile f : files) {
            f.close();
        }
        toClose.forEach(CsvFile::close);
    }

    String statusText() {
        TableDiffResult r = result;
        if (r == null) {
            return status.getText();
        }
        return String.format(Locale.ROOT, "%,d added · %,d removed · %,d changed · %,d same · %,d key repeats",
                r.count(DiffStatus.ADDED), r.count(DiffStatus.REMOVED), r.count(DiffStatus.CHANGED),
                r.count(DiffStatus.SAME), r.duplicateKeys());
    }

    TableDiffResult result() {
        return result;
    }

    List<String> keysForTest() {
        return keys.stream().map(k -> files[0].header().get(k)).toList();
    }

    String suggestionForTest() {
        return suggestion.getText();
    }

    int[] shownForTest() {
        return shown.clone();
    }

    JTable tableForTest() {
        return table;
    }

    String positionForTest() {
        return position.getText();
    }

    /** Show's entry for these rows, as text: "+ Added (only right)  6". */
    String showTextForTest(int at) {
        return showText(at);
    }

    List<String> columnCountsForTest() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < columnModel.size(); i++) {
            out.add(columnModel.get(i).toString());
        }
        return out;
    }

    void clickColumnForTest(int index) {
        columnList.setSelectedIndex(index);
    }

    void setSearchForTest(String text) {
        search.setText(text);
        searchTimer.stop();
        refilter();
    }

    boolean isSearchingForTest() {
        return searching != null;
    }

    /** The suggestion's button ("Use order_id as Key"), or null when it isn't shown. */
    JButton useSuggestedForTest() {
        return useSuggested;
    }

    /** Clicks a key's ✕. */
    void removeKeyForTest(String name) {
        for (Component c : keyChips.getComponents()) {
            if (c instanceof JButton b && b.getText().equals(name + "  ✕")) {
                b.doClick();
                return;
            }
        }
        throw new IllegalArgumentException("no key " + name);
    }

    void setSideBySideForTest(boolean on) {
        setSideBySide(on);
    }

    boolean isSideBySideForTest() {
        return sideBySide;
    }

    /** A cell of a side's table (side 0: status, line, columns; side 1: line, columns). */
    String sideCellTextForTest(int side, int row, int col) {
        return sideText(result, side, shown[row], col);
    }

    List<String> sideColumnsForTest(int side) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sideModels[side].getColumnCount(); i++) {
            out.add(sideModels[side].getColumnName(i));
        }
        return out;
    }

    List<String> columnsForTest() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < model.getColumnCount(); i++) {
            out.add(model.getColumnName(i));
        }
        return out;
    }

    /** Leaves a column out, as ticking it in the list does. */
    void leaveOutForTest(String name) {
        ignoreColumn(files[0].header().indexOf(name));
    }

    /** The columns under "Not compared", as the chips say. */
    List<String> leftOutForTest() {
        List<String> out = new ArrayList<>();
        for (Component c : ignoreChips.getComponents()) {
            if (c instanceof JButton b && b.getText().endsWith("  ✕")) {
                out.add(b.getText().substring(0, b.getText().length() - 3));
            }
        }
        return out;
    }

    /** ✕ on a left-out column: compared again. */
    void compareAgainForTest(String name) {
        for (Component c : ignoreChips.getComponents()) {
            if (c instanceof JButton b && b.getText().equals(name + "  ✕")) {
                b.doClick();
                return;
            }
        }
        throw new IllegalArgumentException("not left out: " + name);
    }

    /** Columns ▾ → a column's tick. */
    void showColumnForTest(String name, boolean on) {
        setColumnShown(name, on);
    }

    JButton columnsButtonForTest() {
        return columnsButton;
    }

    void setKeysForTest(List<String> names) {
        keys.clear();
        for (String n : names) {
            keys.add(files[0].header().indexOf(n));
        }
        showKeys();
    }

    void openRowForTest(int row) {
        openRow(row, -1);
    }

    JButton compareButtonForTest() {
        return compare;
    }

    void compareAsTextForTest() {
        host.asText();
    }

    Side sideForTest(int s) {
        return sides[s];
    }

    JComboBox<String> showForTest() {
        return show;
    }
}
