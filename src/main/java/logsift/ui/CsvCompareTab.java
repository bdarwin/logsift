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
 * Two CSV files compared as tables: rows matched by the key columns chosen (one is suggested),
 * whatever their order; what was added, removed and changed, value by value, with changed values
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
    }

    private static final String[] SHOW = {"All differences", "Changed", "Added", "Removed", "Duplicate keys",
        "Same", "Everything"};

    private final Host host;
    private final Side[] sides = new Side[2];
    private final CsvFile[] files = new CsvFile[2];
    private final List<CsvFile> toClose = new ArrayList<>();
    private ColumnMap map;
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
    private final Tile[] tiles = new Tile[5];
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
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));

        JPanel filesRow = new JPanel(new GridLayout(1, 2, 12, 0));
        filesRow.add(fileBox(0));
        filesRow.add(fileBox(1));
        filesRow.setAlignmentX(LEFT_ALIGNMENT);
        top.add(filesRow);
        top.add(Box.createVerticalStrut(6));

        keyChips.setAlignmentX(LEFT_ALIGNMENT);
        top.add(keyChips);
        suggestion.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        suggestion.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 0));
        suggestion.setAlignmentX(LEFT_ALIGNMENT);
        top.add(suggestion);

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
        JButton columns = new JButton("Columns…", Icons.of("columns-3"));
        columns.setToolTipText("Which column is compared with which, and which are not compared");
        columns.addActionListener(e -> columnsDialog());
        compare.setToolTipText("Compare the files with these keys and options");
        compare.addActionListener(e -> runCompare());
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
        options.add(columns);
        options.add(compare);
        options.add(progress);
        options.add(cancel);
        options.add(status);
        top.add(options);

        JPanel tileRow = new JPanel(new GridLayout(1, 0, 10, 0));
        tileRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        tileRow.setAlignmentX(LEFT_ALIGNMENT);
        tiles[0] = new Tile(DiffStatus.ADDED, "added (only right)", new Color(0x16A34A), 2);
        tiles[1] = new Tile(DiffStatus.REMOVED, "removed (only left)", new Color(0xDC2626), 3);
        tiles[2] = new Tile(DiffStatus.CHANGED, "changed", new Color(0xD97706), 1);
        tiles[3] = new Tile(DiffStatus.SAME, "same", new Color(0x9CA3AF), 5);
        tiles[4] = new Tile(DiffStatus.DUPLICATE, "duplicate keys", new Color(0x8B5CF6), 4);
        for (Tile t : tiles) {
            tileRow.add(t);
        }
        top.add(tileRow);
        notes.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        notes.setAlignmentX(LEFT_ALIGNMENT);
        top.add(notes);
        return top;
    }

    private JComponent fileBox(int side) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, side == 0 ? new Color(0xDC2626) : new Color(0x16A34A)),
                BorderFactory.createEmptyBorder(2, 8, 2, 4)));
        fileName[side].setFont(fileName[side].getFont().deriveFont(Font.BOLD, fileName[side].getFont().getSize2D() + 1));
        fileInfo[side].setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.add(fileName[side]);
        text.add(fileInfo[side]);
        p.add(text, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton change = new JButton("Change…");
        change.setToolTipText("Compare another file on this side (or drop one here)");
        change.addActionListener(e -> chooseFile(side));
        buttons.add(change);
        if (side == 1) {
            JButton swap = new JButton("⇄");
            swap.setToolTipText("Swap left and right");
            swap.addActionListener(e -> swap());
            buttons.add(swap, 0);
        }
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    private JComponent center() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        show.setToolTipText("Which rows to show");
        show.addActionListener(e -> refilter());
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
        bar.add(new JLabel("Show"));
        bar.add(show);
        bar.add(search);
        bar.add(prev);
        bar.add(next);
        bar.add(position);
        bar.add(export);

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(table.getRowHeight() + 4);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updatePosition());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && table.getSelectedRow() >= 0) {
                    openRow(table.getSelectedRow(), -1);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        JPanel right = new JPanel(new BorderLayout());
        right.add(bar, BorderLayout.NORTH);
        right.add(tableScroll, BorderLayout.CENTER);

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

    /** A number with what it counts; a click shows only those rows. */
    private final class Tile extends JPanel {
        final DiffStatus status;
        final JLabel big = new JLabel("–");
        final JLabel small = new JLabel();
        final String label;

        Tile(DiffStatus status, String label, Color line, int showIndex) {
            super(new BorderLayout());
            this.status = status;
            this.label = label;
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, line),
                    BorderFactory.createEmptyBorder(2, 8, 2, 4)));
            big.setFont(big.getFont().deriveFont(Font.BOLD, big.getFont().getSize2D() + 5f));
            small.setText(status.symbol() + " " + label);
            small.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            add(big, BorderLayout.NORTH);
            add(small, BorderLayout.SOUTH);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Show only these rows");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    show.setSelectedIndex(showIndex);
                }
            });
        }

        void set(TableDiffResult r) {
            if (r == null) {
                big.setText("–");
                return;
            }
            if (status == DiffStatus.DUPLICATE) {
                big.setText(String.format(Locale.ROOT, "%,d", r.duplicateKeys()));
                small.setText(status.symbol() + " " + label + (r.count(status) > 0
                        ? String.format(Locale.ROOT, " · %,d rows", r.count(status)) : ""));
            } else {
                big.setText(String.format(Locale.ROOT, "%,d", r.count(status)));
            }
        }
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
            fileInfo[s].setText(rows + f.header().size() + " columns · " + sep + " · " + f.charset().displayName()
                    + (size >= 0 ? " · " + MainFrame.humanBytes(size) : ""));
        }
        host.titleChanged(this);
    }

    /** New files (or columns): match columns by name, suggest a key, then compare. */
    private void prepare() {
        map = ColumnMap.byName(files[0].header(), files[1].header());
        keys.clear();
        ignored.clear();
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
                suggested = got.pairs().stream().map(p -> m.pairs().get(p).left()).toList();
                suggestionReason = got.reason();
                keys.addAll(suggested);
                showKeys();
                runCompare();
            });
        }, "logsift-diff-keys");
        t.setDaemon(true);
        t.start();
    }

    private void showKeys() {
        keyChips.removeAll();
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
                stale();
            });
            keyChips.add(chip);
        }
        if (keys.isEmpty()) {
            JLabel none = new JLabel("none: rows are lined up in file order");
            none.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            keyChips.add(none);
        }
        JButton add = new JButton("+ Key ▾");
        add.setToolTipText("Add a key column (several make a key together)");
        add.addActionListener(e -> addKeyMenu(add));
        keyChips.add(add);
        if (!suggested.isEmpty() && !suggested.equals(keys)) {
            JButton use = new JButton("Use Suggested");
            use.setToolTipText("Match rows by the suggested key again");
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
        ignoreOrder.setEnabled(keys.isEmpty());
        keyChips.revalidate();
        keyChips.repaint();
    }

    private void addKeyMenu(JButton from) {
        List<Integer> options = new ArrayList<>();
        for (ColumnMap.Pair p : map.pairs()) {
            if (!keys.contains(p.left())) {
                options.add(p.left());
            }
        }
        if (options.isEmpty()) {
            return;
        }
        if (options.size() <= 25) {
            JPopupMenu m = new JPopupMenu();
            for (int c : options) {
                JMenuItem it = new JMenuItem(files[0].header().get(c));
                it.addActionListener(e -> addKey(c));
                m.add(it);
            }
            m.show(from, 0, from.getHeight());
            return;
        }
        JList<String> list = new JList<>(options.stream().map(c -> files[0].header().get(c)).toArray(String[]::new));
        list.setVisibleRowCount(16);
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(list), "Add a Key Column", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION && list.getSelectedIndex() >= 0) {
            addKey(options.get(list.getSelectedIndex()));
        }
    }

    private void addKey(int leftColumn) {
        if (!keys.contains(leftColumn)) {
            keys.add(leftColumn);
            showKeys();
            stale();
        }
    }

    /** Settings changed since the last comparison. */
    private void stale() {
        if (running == null && result != null) {
            status.setText("Changed: press Compare");
            status.setForeground(Theme.uiColor("Component.accentColor", new Color(0x2563EB)));
        }
        ignoreOrder.setEnabled(keys.isEmpty());
    }

    private void chooseFile(int side) {
        FileDialog fd = new FileDialog(SwingUtilities.getWindowAncestor(this) instanceof java.awt.Frame f ? f : null,
                "Compare With", FileDialog.LOAD);
        Path dir = sides[side].open().getParent();
        if (dir != null) {
            fd.setDirectory(dir.toString());
        }
        fd.setVisible(true);
        if (fd.getFile() != null) {
            replace(List.of(Path.of(fd.getDirectory(), fd.getFile())), side);
        }
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

    private AtomicBoolean startWork(String what) {
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
        for (CsvFile f : toClose) {
            if (f != files[0] && f != files[1]) {
                f.close();
            }
        }
        toClose.clear();
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText(String.format(Locale.ROOT, "%s · %,d ms", r.matchedBy(), r.millis()));
        for (Tile t : tiles) {
            t.set(r);
        }
        List<String> n = r.notes();
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
        refilter();
        if (shown.length > 0) {
            table.setRowSelectionInterval(0, 0);
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
        shown = items;
        model.fireTableDataChanged();
        updatePosition();
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
            table.setRowSelectionInterval(to, to);
            table.scrollRectToVisible(table.getCellRect(to, 0, true));
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

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        TableDiffResult r = result;
        if (row < 0 || r == null) {
            return;
        }
        table.setRowSelectionInterval(row, row);
        int item = shown[row];
        JPopupMenu m = new JPopupMenu();
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
        DefaultTableModel m = new DefaultTableModel(new Object[] {"Compare", "Left column", "Right column"}, 0) {
            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return c != 1;
            }
        };
        for (int i = 0; i < ln.size(); i++) {
            int p = map.pairOfLeft(i);
            m.addRow(new Object[] {p >= 0 && !ignored.contains(i), ln.get(i), p >= 0 ? rn.get(map.pairs().get(p).right()) : none});
        }
        JTable t = new JTable(m);
        JComboBox<String> rights = new JComboBox<>();
        rights.addItem(none);
        rn.forEach(rights::addItem);
        t.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(rights));
        t.getColumnModel().getColumn(0).setMaxWidth(80);
        t.setRowHeight(t.getRowHeight() + 4);
        List<String> rightOnly = map.rightOnly().stream().map(rn::get).toList();
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html>Columns are matched by name (case and spaces don't count). Pick another right column to"
                + "<br>compare a column with, or untick it to leave it out.</html>"), BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(560, Math.min(420, 60 + ln.size() * t.getRowHeight())));
        p.add(sp, BorderLayout.CENTER);
        if (!rightOnly.isEmpty()) {
            p.add(new JLabel("Only in the right file: " + String.join(", ", rightOnly)), BorderLayout.SOUTH);
        }
        if (JOptionPane.showConfirmDialog(this, p, "Columns", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (t.isEditing()) {
            t.getCellEditor().stopCellEditing();
        }
        List<ColumnMap.Pair> pairs = new ArrayList<>();
        Set<Integer> usedRight = new HashSet<>();
        ignored.clear();
        for (int i = 0; i < ln.size(); i++) {
            String right = String.valueOf(m.getValueAt(i, 2));
            int ri = rn.indexOf(right);
            if (ri >= 0 && usedRight.add(ri)) {
                pairs.add(new ColumnMap.Pair(i, ri));
                if (!Boolean.TRUE.equals(m.getValueAt(i, 0))) {
                    ignored.add(i);
                }
            }
        }
        setColumns(new ColumnMap(ln, rn, pairs));
    }

    /** New column pairs (from the Columns window or a test); keys not paired any more are dropped. */
    void setColumns(ColumnMap m) {
        map = m;
        keys.removeIf(k -> map.pairOfLeft(k) < 0);
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
        private TableDiffResult columnsOf;

        void setColumns(TableDiffResult r) {
            columnsOf = r;
            List<Integer> p = new ArrayList<>(r.spec().keys());
            keyCount = p.size();
            for (int c : r.compared()) {
                p.add(c);
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
            return c - 2 < keyCount ? n + " (key)" : n;
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
                    if (!sel) {
                        bg = Theme.pick(new Color(0xFFF4DB), new Color(0x3A3018));
                    }
                } else {
                    String a = r.leftRow(item) >= 0 ? r.leftValue(item, pair) : r.rightValue(item, pair);
                    text = shortText(a);
                    if (s == DiffStatus.ADDED) {
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
            if (!sel) {
                setBackground(bg);
                setForeground(fg != null ? fg : Theme.foreground());
            }
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
        return String.format(Locale.ROOT, "%,d added · %,d removed · %,d changed · %,d same · %,d duplicate keys",
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

    String tileTextForTest(DiffStatus s) {
        for (Tile t : tiles) {
            if (t.status == s) {
                return t.big.getText() + " " + t.small.getText();
            }
        }
        return null;
    }

    void clickTileForTest(DiffStatus s) {
        for (Tile t : tiles) {
            if (t.status == s) {
                for (java.awt.event.MouseListener l : t.getMouseListeners()) {
                    l.mouseClicked(new MouseEvent(t, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false));
                }
            }
        }
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

    Side sideForTest(int s) {
        return sides[s];
    }

    JComboBox<String> showForTest() {
        return show;
    }
}
