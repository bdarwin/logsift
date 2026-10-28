package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.diff.FolderDiff;

/**
 * Two folders (or zips) side by side, one row per file or folder: its name, size and time on
 * each side, and in the middle whether it is the same (=), different (≠), or only on one side.
 * Folders open and close; Show keeps only the differences (at first) or what you choose.
 * Double-click two files to compare them, one file to open it.
 */
final class FolderCompareTab extends JPanel {

    interface Host {
        /** Compares two files (their paths, inside zips too) in a new Compare tab. */
        void compareFiles(Path left, Path right);

        /** Opens a file as a log. */
        void openFile(Path file);

        default void titleChanged(FolderCompareTab tab) {}

        /** The file browsers Choose Files shows, or null. */
        default JComponent drawer() {
            return null;
        }

        default void drawerShown(boolean on) {}

        default boolean compareChosen() {
            return false;
        }
    }

    private static final String[] SHOW = {"Differences", "Everything", "Same", "Different", "Only left", "Only right"};

    private final Host host;
    private final Path[] roots = new Path[2];
    private FolderDiff.Result result;
    private FolderDiff.Options options = FolderDiff.Options.DEFAULT;
    private final Set<String> open = new HashSet<>(); // folders opened (by path)
    private final List<FolderDiff.Node> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JComboBox<String> show = new JComboBox<>(SHOW);
    private final JTextField search = new JTextField(14);
    private final JToggleButton contents = new JToggleButton("Compare Contents", true);
    private final JLabel summary = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar();
    private final JLabel[] names = {new JLabel(), new JLabel()};
    private final javax.swing.JToggleButton filesButton = new javax.swing.JToggleButton();
    private final JButton compare = new JButton("Compare", Icons.of("git-compare"));
    private final java.awt.CardLayout views = new java.awt.CardLayout();
    private final JPanel viewCards = new JPanel(views);
    private boolean drawerOpen;
    private AtomicBoolean running;

    FolderCompareTab(Path left, Path right, Host host) {
        super(new BorderLayout());
        this.host = host;
        roots[0] = left;
        roots[1] = right;
        add(toolbar(), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout());
        center.add(fileRow(), BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(table.getRowHeight() + 4);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                int c = table.columnAtPoint(e.getPoint());
                if (r >= 0 && (c == 0 || c == 4) && rows.get(r).isFolder() && e.getClickCount() == 1
                        && SwingUtilities.isLeftMouseButton(e)) {
                    java.awt.Rectangle cell = table.getCellRect(r, c, false);
                    int indent = 6 + depth(rows.get(r)) * 16;
                    if (e.getX() - cell.x < indent + 14) {
                        toggle(rows.get(r));
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
                    int r = table.rowAtPoint(e.getPoint());
                    if (r >= 0) {
                        activate(rows.get(r), table.columnAtPoint(e.getPoint()) >= 4 ? 1 : 0);
                    }
                }
            }
        });
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        viewCards.add(sp, "table");
        center.add(viewCards, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        sizeColumns();
        start();
    }

    private JComponent toolbar() {
        JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 3));
        p.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        p.add(new JLabel("Show"));
        show.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean sel,
                                                          boolean f) {
                int at = i >= 0 ? i : show.getSelectedIndex();
                String n = countText(at);
                return super.getListCellRendererComponent(l, n.isEmpty() ? v : v + "  " + n, i, sel, f);
            }
        });
        show.setPrototypeDisplayValue("Only right  1,234,567");
        show.addActionListener(e -> rebuild());
        p.add(show);
        search.putClientProperty("JTextField.placeholderText", "find a name");
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
            public void changedUpdate(DocumentEvent e) {}
        });
        p.add(search);
        JButton expand = new JButton(Icons.of("chevron-down"));
        expand.setToolTipText("Open every folder");
        expand.putClientProperty("JButton.buttonType", "toolBarButton");
        expand.addActionListener(e -> expandAll());
        JButton collapse = new JButton(Icons.of("chevron-up"));
        collapse.setToolTipText("Close every folder");
        collapse.putClientProperty("JButton.buttonType", "toolBarButton");
        collapse.addActionListener(e -> {
            open.clear();
            rebuild();
        });
        p.add(expand);
        p.add(collapse);
        contents.setToolTipText("<html>On: files of the same size are compared byte by byte.<br>"
                + "Off: the same size and time count as the same (quicker for big folders)</html>");
        contents.addActionListener(e -> {
            options = new FolderDiff.Options(contents.isSelected(), options.hidden(), options.skip());
            start();
        });
        p.add(contents);
        JButton refresh = new JButton(Icons.reload());
        refresh.setToolTipText("Compare again (the folders may have changed)");
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.addActionListener(e -> start());
        p.add(refresh);
        compare.setVisible(false);
        compare.addActionListener(e -> {
            if (!host.compareChosen()) {
                showDrawer(false);
            }
        });
        p.add(compare);
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(80, 10));
        bar.setVisible(false);
        p.add(bar);
        summary.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        p.add(summary);
        return p;
    }

    private JComponent fileRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 1, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)), BorderFactory.createEmptyBorder(2, 0, 2, 6)));
        JPanel halves = new JPanel(new java.awt.GridLayout(1, 2, CsvCompareTab.DRAWER_GAP, 0));
        for (int s = 0; s < 2; s++) {
            names[s].setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, s == 0 ? new Color(0xDC2626) : new Color(0x16A34A)),
                    BorderFactory.createEmptyBorder(1, 6, 1, 4)));
            halves.add(names[s]);
        }
        row.add(halves, BorderLayout.CENTER);
        filesButton.setToolTipText("Choose other folders: a file browser for each side");
        filesButton.addActionListener(e -> showDrawer(filesButton.isSelected()));
        filesButton.setVisible(host.drawer() != null);
        row.add(filesButton, BorderLayout.EAST);
        showDrawerButton();
        showNames();
        return row;
    }

    private void showNames() {
        for (int s = 0; s < 2; s++) {
            String dim = String.format("#%06x", Theme.uiColor("Label.disabledForeground", Color.GRAY).getRGB() & 0xFFFFFF);
            Path p = roots[s];
            names[s].setText("<html><b>" + (s == 0 ? "Left: " : "Right: ") + esc(p.getFileName() == null ? p.toString()
                    : p.getFileName().toString()) + "</b>&nbsp;&nbsp;<font color='" + dim + "'>" + esc(p.toString())
                    + "</font></html>");
            names[s].setToolTipText(p.toString());
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    // ---------------------------------------------------------------- the file browsers

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
        compare.setVisible(on);
        views.show(viewCards, on ? "files" : "table");
        if (!on) {
            CsvCompareTab.highlightCompare(compare, false);
            showSummary();
        }
        host.drawerShown(on);
    }

    private void showDrawerButton() {
        filesButton.setSelected(drawerOpen);
        filesButton.setPreferredSize(null);
        int w = 0;
        for (boolean o : new boolean[] {false, true}) {
            filesButton.setText(o ? "Close Files" : "Choose Files");
            filesButton.setIcon(Icons.of(o ? "x" : "folder-open"));
            w = Math.max(w, filesButton.getPreferredSize().width);
        }
        filesButton.setText(drawerOpen ? "Close Files" : "Choose Files");
        filesButton.setIcon(Icons.of(drawerOpen ? "x" : "folder-open"));
        filesButton.setPreferredSize(new Dimension(w, filesButton.getPreferredSize().height));
    }

    void filesChosen(boolean other, String text) {
        if (!drawerOpen) {
            return;
        }
        CsvCompareTab.highlightCompare(compare, other);
        summary.setText(text);
    }

    boolean isDrawerOpen() {
        return drawerOpen;
    }

    javax.swing.JToggleButton filesButtonForTest() {
        return filesButton;
    }

    boolean compareHighlightedForTest() {
        return CsvCompareTab.isHighlighted(compare);
    }

    // ---------------------------------------------------------------- comparing

    void start() {
        if (running != null) {
            running.set(true);
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        bar.setVisible(true);
        summary.setText("Comparing…");
        Path l = roots[0];
        Path r = roots[1];
        FolderDiff.Options o = options;
        Thread t = new Thread(() -> {
            FolderDiff.Result got = null;
            String error = null;
            try {
                got = FolderDiff.compare(l, r, o, cancel::get, text -> SwingUtilities.invokeLater(() -> {
                    if (running == cancel) {
                        summary.setText(text);
                    }
                }));
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            FolderDiff.Result done = got;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                bar.setVisible(false);
                if (failed != null || done == null) {
                    summary.setText(failed != null ? "Couldn't compare: " + failed : " ");
                    return;
                }
                result = done;
                if (open.isEmpty()) {
                    // the first time: folders with differences open
                    for (FolderDiff.Node n : FolderDiff.all(done.root())) {
                        if (n.isFolder() && n.status() == FolderDiff.Status.DIFFERENT) {
                            open.add(n.path());
                        }
                    }
                }
                rebuild();
                show.repaint();
            });
        }, "logsift-folder-compare");
        t.setDaemon(true);
        t.start();
    }

    boolean isRunning() {
        return running != null;
    }

    FolderDiff.Result result() {
        return result;
    }

    private void showSummary() {
        FolderDiff.Result r = result;
        if (r == null) {
            return;
        }
        summary.setText(r.different() + r.leftOnly() + r.rightOnly() == 0
                ? String.format(Locale.ROOT, "The same: %,d files · %,d ms", r.files(), r.millis())
                : String.format(Locale.ROOT, "%,d files: %,d same, %,d different, %,d only left, %,d only right · %,d ms%s",
                        r.files(), r.same(), r.different(), r.leftOnly(), r.rightOnly(), r.millis(),
                        r.capped() ? " · the first 200,000 files of a side" : ""));
    }

    private String countText(int show) {
        FolderDiff.Result r = result;
        if (r == null) {
            return "";
        }
        int n = switch (show) {
            case 0 -> r.different() + r.leftOnly() + r.rightOnly();
            case 1 -> r.files();
            case 2 -> r.same();
            case 3 -> r.different();
            case 4 -> r.leftOnly();
            case 5 -> r.rightOnly();
            default -> 0;
        };
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Whether a file (not a folder) is shown by Show and the search. */
    private boolean wanted(FolderDiff.Node n) {
        String q = search.getText().strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty() && !n.name().toLowerCase(Locale.ROOT).contains(q)) {
            return false;
        }
        return switch (show.getSelectedIndex()) {
            case 0 -> n.status() != FolderDiff.Status.SAME;
            case 2 -> n.status() == FolderDiff.Status.SAME;
            case 3 -> n.status() == FolderDiff.Status.DIFFERENT;
            case 4 -> n.status() == FolderDiff.Status.LEFT_ONLY;
            case 5 -> n.status() == FolderDiff.Status.RIGHT_ONLY;
            default -> true;
        };
    }

    /** Whether a folder has something shown in it. */
    private boolean hasWanted(FolderDiff.Node n) {
        for (FolderDiff.Node k : n.kids()) {
            if (k.isFolder() ? hasWanted(k) || wanted(k) && k.kids().isEmpty() : wanted(k)) {
                return true;
            }
        }
        return false;
    }

    private void rebuild() {
        rows.clear();
        FolderDiff.Result r = result;
        if (r != null) {
            boolean searching = !search.getText().isBlank();
            walk(r.root(), searching);
        }
        model.fireTableDataChanged();
        showSummary();
    }

    private void walk(FolderDiff.Node n, boolean searching) {
        for (FolderDiff.Node k : n.kids()) {
            if (k.isFolder()) {
                if (hasWanted(k) || k.kids().isEmpty() && wanted(k)) {
                    rows.add(k);
                    if (open.contains(k.path()) || searching) {
                        walk(k, searching);
                    }
                }
            } else if (wanted(k)) {
                rows.add(k);
            }
        }
    }

    private void toggle(FolderDiff.Node n) {
        if (!open.remove(n.path())) {
            open.add(n.path());
        }
        int at = table.getSelectedRow();
        rebuild();
        if (at >= 0 && at < rows.size()) {
            table.setRowSelectionInterval(at, at);
        }
    }

    private void expandAll() {
        if (result == null) {
            return;
        }
        for (FolderDiff.Node n : FolderDiff.all(result.root())) {
            if (n.isFolder()) {
                open.add(n.path());
            }
        }
        rebuild();
    }

    private static int depth(FolderDiff.Node n) {
        int d = 0;
        for (int i = 0; i < n.path().length(); i++) {
            if (n.path().charAt(i) == '/') {
                d++;
            }
        }
        return d;
    }

    /** Where a node is on a side: a file on disk, or inside the zip. */
    Path pathOf(FolderDiff.Node n, int side) {
        return roots[side].resolve(n.path());
    }

    /** A double-click: two files compared, one opened, a folder opened or closed. */
    private void activate(FolderDiff.Node n, int side) {
        if (n.isFolder()) {
            toggle(n);
        } else if (n.left() != null && n.right() != null) {
            host.compareFiles(pathOf(n, 0), pathOf(n, 1));
        } else {
            host.openFile(pathOf(n, n.left() != null ? 0 : 1));
        }
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int r = table.rowAtPoint(e.getPoint());
        if (r < 0) {
            return;
        }
        table.setRowSelectionInterval(r, r);
        FolderDiff.Node n = rows.get(r);
        JPopupMenu m = new JPopupMenu();
        if (!n.isFolder()) {
            JMenuItem cmp = new JMenuItem("Compare These Files", Icons.of("git-compare"));
            cmp.setEnabled(n.left() != null && n.right() != null);
            cmp.addActionListener(a -> host.compareFiles(pathOf(n, 0), pathOf(n, 1)));
            m.add(cmp);
            for (int s = 0; s < 2; s++) {
                int side = s;
                boolean has = s == 0 ? n.left() != null : n.right() != null;
                JMenuItem o = new JMenuItem(s == 0 ? "Open the Left File" : "Open the Right File",
                        Icons.of("file-text"));
                o.setEnabled(has);
                o.addActionListener(a -> host.openFile(pathOf(n, side)));
                m.add(o);
            }
            m.addSeparator();
        }
        JMenuItem copy = new JMenuItem("Copy the Path", Icons.of("copy"));
        copy.addActionListener(a -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(n.path()), null));
        m.add(copy);
        m.show(table, e.getX(), e.getY());
    }

    // ---------------------------------------------------------------- the table

    private static final String[] COLUMNS = {"Left", "Size", "Changed", "", "Right", "Size", "Changed"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Object getValueAt(int row, int col) {
            return text(rows.get(row), col);
        }
    }

    /** A cell's text. */
    String text(FolderDiff.Node n, int col) {
        if (col == 3) {
            return switch (n.status()) {
                case SAME -> "=";
                case DIFFERENT -> "≠";
                case LEFT_ONLY -> "◀";
                case RIGHT_ONLY -> "▶";
            };
        }
        int side = col < 3 ? 0 : 1;
        FolderDiff.Info info = side == 0 ? n.left() : n.right();
        if (info == null) {
            return "";
        }
        return switch (col % 4 == 0 ? 0 : col < 3 ? col : col - 4) {
            case 0 -> n.name() + (n.isFolder() && n.differences() > 0 && side == 0
                    ? "" : "");
            case 1 -> n.isFolder() ? "" : info.size() < 0 ? "?" : MainFrame.humanBytes(info.size());
            default -> info.modified() < 0 ? "" : TIME.format(Instant.ofEpochMilli(info.modified())
                    .atZone(ZoneId.systemDefault()));
        };
    }

    private void sizeColumns() {
        int[] w = {280, 80, 130, 30, 280, 80, 130};
        for (int c = 0; c < w.length; c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(w[c]);
        }
        table.getColumnModel().getColumn(3).setMaxWidth(36);
    }

    private final class Renderer extends DefaultTableCellRenderer {
        private int depth;
        private boolean folder;
        private boolean isOpen;
        private boolean nameCell;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            FolderDiff.Node n = rows.get(row);
            super.getTableCellRendererComponent(t, v, sel, false, row, col);
            int side = col < 3 ? 0 : 1;
            boolean has = col == 3 || (side == 0 ? n.left() != null : n.right() != null);
            nameCell = col == 0 || col == 4;
            depth = depth(n);
            folder = n.isFolder();
            isOpen = open.contains(n.path());
            Color bg = row % 2 == 1 ? Theme.stripe() : Theme.background();
            Color fg = Theme.foreground();
            switch (n.status()) {
                case DIFFERENT -> {
                    fg = CsvCompareTab.changedColor();
                    if (!folder) {
                        bg = Theme.pick(new Color(0xFFF7E6), new Color(0x2E2A1C));
                    }
                }
                case LEFT_ONLY -> {
                    fg = CsvCompareTab.removedColor();
                    bg = side == 0 || col == 3 ? Theme.pick(new Color(0xFEF2F2), new Color(0x33201F)) : bg;
                }
                case RIGHT_ONLY -> {
                    fg = CsvCompareTab.addedColor();
                    bg = side == 1 || col == 3 ? Theme.pick(new Color(0xECFDF3), new Color(0x1B2D21)) : bg;
                }
                default -> fg = Theme.foreground();
            }
            if (!has) {
                bg = Theme.pick(new Color(0xF2F3F5), new Color(0x27292C));
            }
            if (!sel) {
                setBackground(bg);
                setForeground(col == 1 || col == 2 || col == 5 || col == 6 ? Theme.gutterForeground() : fg);
            }
            boolean newer = (col == 2 && n.newer() == 0) || (col == 6 && n.newer() == 1);
            setFont(folder && nameCell || col == 3 || newer ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
            setHorizontalAlignment(col == 3 ? SwingConstants.CENTER : col == 1 || col == 5 ? SwingConstants.TRAILING
                    : SwingConstants.LEADING);
            int indent = nameCell ? 6 + depth * 16 + 16 : 6;
            setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 6));
            setIcon(nameCell && has ? Icons.of(folder ? "folder" : "file-text") : null);
            String tip = n.path() + " · " + n.status().label + (folder && n.differences() > 0
                    ? String.format(Locale.ROOT, " · %,d files differ in it", n.differences()) : "");
            setToolTipText(newer ? "Changed later than the other side" : tip);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (nameCell && folder && getIcon() != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.uiColor("Label.disabledForeground", Color.GRAY));
                int x = 6 + depth * 16 + 2;
                int y = getHeight() / 2;
                java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
                if (isOpen) {
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

    // ---------------------------------------------------------------- the rest

    String title() {
        return "Compare: " + name(roots[0]) + " ↔ " + name(roots[1]);
    }

    private static String name(Path p) {
        return p.getFileName() == null ? p.toString() : p.getFileName().toString();
    }

    String statusText() {
        return summary.getText();
    }

    Path rootForTest(int side) {
        return roots[side];
    }

    /** The rows shown, as "path status". */
    List<String> rowsForTest() {
        return rows.stream().map(n -> n.path() + " " + text(n, 3)).toList();
    }

    void setShowForTest(int i) {
        show.setSelectedIndex(i);
    }

    void setSearchForTest(String s) {
        search.setText(s);
    }

    void activateForTest(String path) {
        for (FolderDiff.Node n : rows) {
            if (n.path().equals(path)) {
                activate(n, 0);
                return;
            }
        }
        throw new IllegalArgumentException("no row " + path);
    }

    void expandAllForTest() {
        expandAll();
    }

    String showTextForTest(int i) {
        return SHOW[i] + "  " + countText(i);
    }
}
