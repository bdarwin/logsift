package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import logsift.core.diff.Edit;
import logsift.core.diff.TextDiff;

/**
 * Two text files side by side, as a code review shows them: lines that match on one row, a line
 * only one file has facing an empty row, changed blocks coloured (blue changed, green added,
 * gray removed) and the words that differ marked stronger. F7 goes from change to change;
 * unchanged stretches can be folded away; » and « copy a block to the other side, and the
 * files can be saved.
 */
final class TextCompareTab extends JPanel {

    interface Host {
        /** Opens a file at a line (0-based). */
        void openLine(Path file, long line);

        /** The tab's title changed (a file was saved or swapped). */
        default void titleChanged(TextCompareTab tab) {}

        /** The file browsers Choose Files shows in the panes' place, or null. */
        default JComponent drawer() {
            return null;
        }

        default void drawerShown(boolean on) {}

        /** Compare clicked while the browsers are shown: true if the host takes it. */
        default boolean compareChosen() {
            return false;
        }

        /** Compare as Table: the files compared as CSV tables instead; false if it can't. */
        default boolean asTable() {
            return false;
        }
    }

    /** A file compared: where it is read from, where it is, its name; its lines, encoding and line ends. */
    static final class Doc {
        final CsvCompareTab.Side side;
        final Charset charset;
        final String eol;
        final boolean endsWithEol;
        List<String> lines;
        boolean edited;

        Doc(CsvCompareTab.Side side, List<String> lines, Charset charset, String eol, boolean endsWithEol) {
            this.side = side;
            this.lines = new ArrayList<>(lines);
            this.charset = charset;
            this.eol = eol;
            this.endsWithEol = endsWithEol;
        }

        /** Whether it is a file of its own on disk (not in a zip, not unpacked), so it can be saved. */
        boolean writable() {
            Path p = side.open();
            return p.equals(side.read()) && Files.isRegularFile(p) && Files.isWritable(p);
        }

        /** Reads a text file: its encoding, lines and line ends. */
        static Doc read(CsvCompareTab.Side side) throws IOException {
            byte[] b = Files.readAllBytes(side.read());
            int n = Math.min(b.length, 8192);
            Charset cs = logsift.core.Encoding.detect(b, n);
            if (!logsift.core.Encoding.isUtf16(cs)) {
                for (int i = 0; i < n; i++) {
                    if (b[i] == 0) {
                        throw new IOException("binary");
                    }
                }
            }
            String s = new String(b, cs);
            if (!s.isEmpty() && s.charAt(0) == '﻿') {
                s = s.substring(1);
            }
            String eol = s.contains("\r\n") ? "\r\n" : "\n";
            boolean ends = s.endsWith("\n");
            List<String> lines = new ArrayList<>(List.of(s.split("\r?\n", -1)));
            if (ends) {
                lines.remove(lines.size() - 1);
            }
            return new Doc(side, lines, cs, eol, ends);
        }

        void save() throws IOException {
            String text = String.join(eol, lines) + (endsWithEol && !lines.isEmpty() ? eol : "");
            Files.write(side.open(), text.getBytes(charset));
            edited = false;
        }
    }

    private final Host host;
    private final Doc[] docs = new Doc[2];
    private TextDiff.Options options = TextDiff.Options.DEFAULT;
    private TextDiff.Result result;
    private boolean fold = AppState.prefs().getBoolean("textCompareFold", false);
    private final Set<Integer> unfolded = new HashSet<>(); // folds opened (by their first result row)
    /** The rows shown: a result row, or -(n + 1) for a fold of n unchanged rows starting at {@link #foldStart}. */
    private int[] shown = new int[0];
    private int[] foldStart = new int[0];
    private final DiffPane[] panes = new DiffPane[2];
    private final JScrollPane[] scrolls = new JScrollPane[2];
    private final Gutter gutter = new Gutter();
    private final Overview overview = new Overview();
    private final JLabel summary = new JLabel(" ");
    private final JLabel position = new JLabel(" ");
    private final JButton[] save = {new JButton("Save Left"), new JButton("Save Right")};
    private final JButton undo = new JButton(Icons.of("rotate-ccw"));
    private final Deque<Runnable> undos = new ArrayDeque<>();
    private final JToggleButton foldButton = new JToggleButton("Fold Unchanged");
    private final JLabel[] names = {new JLabel(), new JLabel()};
    private final JLabel[] infos = {new JLabel(), new JLabel()};
    private final javax.swing.JToggleButton filesButton = new javax.swing.JToggleButton();
    private final JButton compare = new JButton("Compare", Icons.of("git-compare"));
    private final JPanel fileRow = new JPanel();
    private final java.awt.CardLayout views = new java.awt.CardLayout();
    private final JPanel viewCards = new JPanel(views);
    private final JPanel body = new JPanel(new BodyLayout());
    private boolean drawerOpen;
    private int current = -1; // the change selected (index into the result's blocks)

    TextCompareTab(Doc left, Doc right, Host host) {
        super(new BorderLayout());
        this.host = host;
        docs[0] = left;
        docs[1] = right;
        for (int s = 0; s < 2; s++) {
            int side = s;
            panes[s] = new DiffPane(rowsOf(s));
            panes[s].onOpen(r -> open(r, side));
            panes[s].onSelect(() -> selected(side));
            panes[s].addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int r = panes[side].rowAt(e.getY());
                    if (r >= 0 && shown[r] < 0) {
                        unfolded.add(foldStart[r]);
                        rebuild();
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    popup(e, side);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    popup(e, side);
                }
            });
            panes[s].getActionMap().put("copy", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    copy(side);
                }
            });
            panes[s].getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copy");
            scrolls[s] = new JScrollPane(panes[s]);
            scrolls[s].setBorder(BorderFactory.createEmptyBorder());
            scrolls[s].getVerticalScrollBar().setUnitIncrement(panes[s].rowHeight());
        }
        // one vertical scroll for both; the right one's bar shows
        scrolls[0].setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        scrolls[1].setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrolls[0].getVerticalScrollBar().setModel(scrolls[1].getVerticalScrollBar().getModel());
        scrolls[1].getVerticalScrollBar().getModel().addChangeListener(e -> {
            gutter.repaint();
            overview.repaint();
        });
        // the wheel on the left scrolls both
        scrolls[0].addMouseWheelListener(e -> {
            for (java.awt.event.MouseWheelListener l : scrolls[1].getMouseWheelListeners()) {
                l.mouseWheelMoved(SwingUtilities.convertMouseEvent(scrolls[0], e, scrolls[1])
                        instanceof java.awt.event.MouseWheelEvent w ? w : e);
            }
        });
        scrolls[0].setWheelScrollingEnabled(false);
        body.add(scrolls[0]);
        body.add(gutter);
        body.add(scrolls[1]);
        body.add(overview);
        viewCards.add(body, "text");

        add(toolbar(), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout());
        center.add(fileRow(), BorderLayout.NORTH);
        center.add(viewCards, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        recompare();
    }

    // ---------------------------------------------------------------- layout

    private JComponent toolbar() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        JButton prev = new JButton(Icons.of("chevron-up"));
        prev.setToolTipText("Previous change (Shift+F7)");
        prev.addActionListener(e -> nextChange(false));
        JButton next = new JButton(Icons.of("chevron-down"));
        next.setToolTipText("Next change (F7)");
        next.addActionListener(e -> nextChange(true));
        bar.add(prev);
        bar.add(next);
        bar.add(CsvCompareTab.fixedWidth(position, 22));
        bar.add(Box.createHorizontalStrut(8));
        JButton ignore = new JButton("Ignore ▾");
        ignore.setToolTipText("What doesn't count as a difference: spaces, case");
        ignore.addActionListener(e -> ignoreMenu(ignore));
        bar.add(ignore);
        JButton mask = new JButton("Mask ▾");
        mask.setToolTipText("Count times, numbers or IDs as equal, so two runs of a job line up");
        mask.addActionListener(e -> maskMenu(mask));
        bar.add(mask);
        foldButton.setSelected(fold);
        foldButton.setToolTipText("Fold stretches of unchanged lines (3 lines of context stay); click a fold to open it");
        foldButton.addActionListener(e -> {
            fold = foldButton.isSelected();
            unfolded.clear();
            try {
                AppState.prefs().putBoolean("textCompareFold", fold);
            } catch (RuntimeException ex) {
                // remembered next time if it can be
            }
            rebuild();
        });
        bar.add(foldButton);
        bar.add(Box.createHorizontalStrut(8));
        undo.setToolTipText("Undo the last copy of a block");
        undo.setEnabled(false);
        undo.addActionListener(e -> undo());
        bar.add(undo);
        for (int s = 0; s < 2; s++) {
            int side = s;
            save[s].setIcon(Icons.of("save"));
            save[s].setEnabled(false);
            save[s].addActionListener(e -> save(side));
            bar.add(save[s]);
        }
        compare.setVisible(false); // shown while the file browsers are
        compare.addActionListener(e -> {
            if (!host.compareChosen()) {
                showDrawer(false);
            }
        });
        bar.add(compare);
        JButton asTable = new JButton("Compare as Table", Icons.of("columns-3"));
        asTable.setToolTipText("CSV and TSV files: rows matched by key columns, value by value");
        asTable.addActionListener(e -> host.asTable());
        String n0 = docs[0].side.name().toLowerCase(Locale.ROOT);
        String n1 = docs[1].side.name().toLowerCase(Locale.ROOT);
        asTable.setVisible((n0.endsWith(".csv") || n0.endsWith(".tsv")) && (n1.endsWith(".csv") || n1.endsWith(".tsv")));
        bar.add(asTable);
        summary.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        bar.add(CsvCompareTab.fixedWidth(summary, 64));
        return bar;
    }

    private JComponent fileRow() {
        fileRow.setLayout(new FileRowLayout());
        fileRow.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 1, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)), BorderFactory.createEmptyBorder(2, 0, 2, 6)));
        for (int s = 0; s < 2; s++) {
            JPanel p = new JPanel(new BorderLayout(6, 0));
            p.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, s == 0 ? new Color(0xDC2626) : new Color(0x16A34A)),
                    BorderFactory.createEmptyBorder(1, 6, 1, 4)));
            names[s].setFont(names[s].getFont().deriveFont(Font.BOLD));
            infos[s].setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            JPanel text = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            text.add(names[s]);
            text.add(Box.createHorizontalStrut(8));
            text.add(infos[s]);
            p.add(text, BorderLayout.CENTER);
            if (s == 1) {
                JButton swap = new JButton("⇄");
                swap.setToolTipText("Swap left and right");
                swap.putClientProperty("JButton.buttonType", "toolBarButton");
                swap.addActionListener(e -> swap());
                p.add(swap, BorderLayout.WEST);
            }
            p.setMinimumSize(new Dimension(0, 0));
            fileRow.add(p);
        }
        filesButton.setToolTipText("Choose other files: a file browser for each side, in the texts' place");
        filesButton.addActionListener(e -> showDrawer(filesButton.isSelected()));
        filesButton.setVisible(host.drawer() != null);
        fileRow.add(filesButton);
        showDrawerButton();
        showNames();
        return fileRow;
    }

    /** The names over the panes: the left one up to the gutter, the right one from it, Choose Files at the end. */
    private final class FileRowLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component c) {}

        @Override
        public void removeLayoutComponent(Component c) {}

        @Override
        public Dimension preferredLayoutSize(Container p) {
            Insets in = p.getInsets();
            int h = 0;
            for (Component c : p.getComponents()) {
                h = Math.max(h, c.getPreferredSize().height);
            }
            return new Dimension(200, h + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container p) {
            return preferredLayoutSize(p);
        }

        @Override
        public void layoutContainer(Container p) {
            Insets in = p.getInsets();
            int w = p.getWidth() - in.left - in.right;
            int h = p.getHeight() - in.top - in.bottom;
            Component button = p.getComponent(2);
            int bw = button.isVisible() ? button.getPreferredSize().width : 0;
            int leftW = drawerOpen ? (w - CsvCompareTab.DRAWER_GAP) / 2 : scrolls[0].getWidth();
            int rightX = drawerOpen ? leftW + CsvCompareTab.DRAWER_GAP : gutter.getX() + gutter.getWidth();
            p.getComponent(0).setBounds(in.left, in.top, Math.max(0, leftW), h);
            int rightEnd = w - (bw > 0 ? bw + 6 : 0);
            p.getComponent(1).setBounds(in.left + rightX, in.top, Math.max(0, rightEnd - rightX), h);
            int bh = button.getPreferredSize().height;
            button.setBounds(in.left + w - bw, in.top + (h - bh) / 2, bw, bh);
        }
    }

    /** The panes take equal halves; the gutter between them, the change marks at the end. */
    private final class BodyLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component c) {}

        @Override
        public void removeLayoutComponent(Component c) {}

        @Override
        public Dimension preferredLayoutSize(Container p) {
            return new Dimension(600, 300);
        }

        @Override
        public Dimension minimumLayoutSize(Container p) {
            return new Dimension(100, 50);
        }

        @Override
        public void layoutContainer(Container p) {
            int w = p.getWidth();
            int h = p.getHeight();
            int gw = Gutter.WIDTH;
            int ow = Overview.WIDTH;
            int half = (w - gw - ow) / 2;
            scrolls[0].setBounds(0, 0, half, h);
            gutter.setBounds(half, 0, gw, h);
            scrolls[1].setBounds(half + gw, 0, w - ow - half - gw, h);
            overview.setBounds(w - ow, 0, ow, h);
            fileRow.revalidate();
        }
    }

    private void showNames() {
        for (int s = 0; s < 2; s++) {
            Doc d = docs[s];
            names[s].setText((s == 0 ? "Left: " : "Right: ") + d.side.name() + (d.edited ? " •" : ""));
            names[s].setToolTipText(d.side.open() + (d.writable() ? "" : " (can't be saved here: in a zip, or read-only)"));
            infos[s].setText(String.format(Locale.ROOT, "%,d lines · %s", d.lines.size(),
                    logsift.core.Encoding.label(d.charset)) + (d.eol.equals("\r\n") ? " · CRLF" : ""));
            save[s].setEnabled(d.edited && d.writable());
        }
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
        views.show(viewCards, on ? "files" : "text");
        if (!on) {
            CsvCompareTab.highlightCompare(compare, false);
            showSummary();
        }
        fileRow.revalidate();
        fileRow.repaint();
        host.drawerShown(on);
    }

    private void showDrawerButton() {
        filesButton.setSelected(drawerOpen);
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

    /** What the browsers have chosen: Compare stands out when it is other files. */
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

    JButton compareButtonForTest() {
        return compare;
    }

    boolean compareHighlightedForTest() {
        return CsvCompareTab.isHighlighted(compare);
    }

    // ---------------------------------------------------------------- comparing

    /** Compares the texts again (the options or a side changed). */
    private void recompare() {
        result = TextDiff.compare(docs[0].lines, docs[1].lines, options);
        unfolded.clear();
        rebuild();
        if (current < 0 || current >= result.blocks().size() || !result.blocks().get(current).isChange()) {
            current = -1;
        }
    }

    /** The rows shown again (folds opened or closed). */
    private void rebuild() {
        List<TextDiff.Row> rows = result.rows();
        List<Integer> out = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        if (!fold) {
            for (int i = 0; i < rows.size(); i++) {
                out.add(i);
                starts.add(-1);
            }
        } else {
            boolean[] near = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                if (result.blocks().get(rows.get(i).block()).isChange()) {
                    for (int k = Math.max(0, i - 3); k <= Math.min(rows.size() - 1, i + 3); k++) {
                        near[k] = true;
                    }
                }
            }
            int i = 0;
            while (i < rows.size()) {
                if (near[i]) {
                    out.add(i);
                    starts.add(-1);
                    i++;
                    continue;
                }
                int j = i;
                while (j < rows.size() && !near[j]) {
                    j++;
                }
                if (j - i < 4 || unfolded.contains(i)) {
                    for (int k = i; k < j; k++) {
                        out.add(k);
                        starts.add(-1);
                    }
                } else {
                    out.add(-(j - i) - 1);
                    starts.add(i);
                }
                i = j;
            }
        }
        shown = out.stream().mapToInt(Integer::intValue).toArray();
        foldStart = starts.stream().mapToInt(Integer::intValue).toArray();
        blockRow = new int[result.blocks().size()];
        java.util.Arrays.fill(blockRow, -1);
        for (int i = shown.length - 1; i >= 0; i--) {
            if (shown[i] >= 0) {
                blockRow[result.rows().get(shown[i]).block()] = i;
            }
        }
        for (int s = 0; s < 2; s++) {
            panes[s].setRows(rowsOf(s));
        }
        showNames();
        showSummary();
        gutter.repaint();
        overview.repaint();
        body.revalidate();
    }

    private void showSummary() {
        TextDiff.Result r = result;
        int changes = r.changes().size();
        if (r.same()) {
            summary.setText(options.equals(TextDiff.Options.DEFAULT) ? "The files are the same"
                    : "The same, as compared (" + describe(options) + ")");
        } else {
            summary.setText(String.format(Locale.ROOT, "%,d %s · %,d lines changed, %,d added, %,d removed · %,d ms",
                    changes, changes == 1 ? "change" : "changes", r.linesChanged(), r.linesAdded(), r.linesRemoved(),
                    r.millis()));
        }
        updatePosition();
    }

    private static String describe(TextDiff.Options o) {
        List<String> what = new ArrayList<>();
        if (o.spaces() != TextDiff.Spaces.EXACT) {
            what.add(o.spaces() == TextDiff.Spaces.ALL ? "spaces ignored" : "spaces at the ends ignored");
        }
        if (o.ignoreCase()) {
            what.add("case ignored");
        }
        if (o.maskTimes()) {
            what.add("times masked");
        }
        if (o.maskNumbers()) {
            what.add("numbers masked");
        }
        if (o.maskIds()) {
            what.add("IDs masked");
        }
        return String.join(", ", what);
    }

    private void updatePosition() {
        List<TextDiff.Block> changes = result.changes();
        int at = current >= 0 ? changes.indexOf(result.blocks().get(current)) : -1;
        position.setText(changes.isEmpty() ? "no changes" : at >= 0
                ? String.format(Locale.ROOT, "change %,d of %,d", at + 1, changes.size())
                : String.format(Locale.ROOT, "%,d changes", changes.size()));
    }

    /** The rows of one side, for its pane. */
    private DiffPane.Rows rowsOf(int side) {
        return new DiffPane.Rows() {
            @Override
            public int size() {
                return shown.length;
            }

            @Override
            public int line(int row) {
                int r = shown[row];
                if (r < 0) {
                    return -1;
                }
                TextDiff.Row x = result.rows().get(r);
                return side == 0 ? x.left() : x.right();
            }

            @Override
            public String text(int row) {
                int r = shown[row];
                if (r < 0) {
                    return side == 0 ? String.format(Locale.ROOT, "⋯ %,d unchanged lines (click to show them)",
                            -r - 1) : "";
                }
                int line = line(row);
                return line < 0 ? null : docs[side].lines.get(line);
            }

            @Override
            public DiffPane.Kind kind(int row) {
                int r = shown[row];
                if (r < 0) {
                    return DiffPane.Kind.SAME;
                }
                TextDiff.Row x = result.rows().get(r);
                TextDiff.Block b = result.blocks().get(x.block());
                return switch (b.kind()) {
                    case EQUAL -> DiffPane.Kind.SAME;
                    case INSERT -> DiffPane.Kind.ADDED;
                    case DELETE -> DiffPane.Kind.REMOVED;
                    case REPLACE -> x.left() < 0 ? DiffPane.Kind.ADDED : x.right() < 0 ? DiffPane.Kind.REMOVED
                            : DiffPane.Kind.CHANGED;
                };
            }

            @Override
            public List<TextDiff.Span> words(int row) {
                int r = shown[row];
                if (r < 0) {
                    return List.of();
                }
                return result.inline(result.rows().get(r)).get(side);
            }

            @Override
            public boolean blockStart(int row) {
                int r = shown[row];
                if (r < 0) {
                    return false;
                }
                TextDiff.Row x = result.rows().get(r);
                return result.blocks().get(x.block()).isChange()
                        && (r == 0 || result.rows().get(r - 1).block() != x.block());
            }

            @Override
            public boolean blockEnd(int row) {
                int r = shown[row];
                if (r < 0) {
                    return false;
                }
                TextDiff.Row x = result.rows().get(r);
                return result.blocks().get(x.block()).isChange()
                        && (r == result.rows().size() - 1 || result.rows().get(r + 1).block() != x.block());
            }
        };
    }

    /** Per block, the shown row where it starts (-1: folded away). */
    private int[] blockRow = new int[0];

    /** The shown row where a block starts, or -1 (folded away). */
    private int rowOfBlock(int block) {
        return block >= 0 && block < blockRow.length ? blockRow[block] : -1;
    }

    /** The block of a shown row, or -1. */
    private int blockOf(int row) {
        return row < 0 || row >= shown.length || shown[row] < 0 ? -1 : result.rows().get(shown[row]).block();
    }

    /** F7 / Shift+F7: the next (or previous) change, selected and scrolled to. */
    void nextChange(boolean forward) {
        List<TextDiff.Block> blocks = result.blocks();
        int from = current >= 0 ? current : forward ? -1 : blocks.size();
        if (current < 0) {
            int[] sel = panes[1].selection()[0] >= 0 ? panes[1].selection() : panes[0].selection();
            if (sel[0] >= 0) {
                int b = blockOf(sel[0]);
                if (b >= 0) {
                    from = b;
                }
            }
        }
        for (int b = forward ? from + 1 : from - 1; b >= 0 && b < blocks.size(); b += forward ? 1 : -1) {
            if (blocks.get(b).isChange()) {
                goTo(b);
                return;
            }
        }
    }

    private void goTo(int block) {
        current = block;
        int row = rowOfBlock(block);
        if (row < 0) {
            // folded: open the folds
            fold = false;
            foldButton.setSelected(false);
            rebuild();
            row = rowOfBlock(block);
        }
        int end = row;
        while (end + 1 < shown.length && blockOf(end + 1) == block) {
            end++;
        }
        for (DiffPane p : panes) {
            p.select(row, end);
        }
        int rh = panes[1].rowHeight();
        int view = scrolls[1].getViewport().getHeight();
        int y = Math.max(0, row * rh - view / 3);
        scrolls[1].getVerticalScrollBar().setValue(y);
        updatePosition();
        gutter.repaint();
    }

    private void selected(int side) {
        int[] sel = panes[side].selection();
        panes[1 - side].select(sel[0], sel[1]);
        int b = blockOf(sel[0]);
        current = b >= 0 && result.blocks().get(b).isChange() ? b : -1;
        updatePosition();
        gutter.repaint();
    }

    private void open(int row, int side) {
        int line = panes[side].rows().line(row);
        if (line >= 0) {
            host.openLine(docs[side].side.open(), line);
        }
    }

    // ---------------------------------------------------------------- copying blocks between the sides

    /** Copies a changed block to the other side ({@code to}: the side that changes). */
    void apply(int block, int to) {
        TextDiff.Block b = result.blocks().get(block);
        if (!b.isChange()) {
            return;
        }
        Doc target = docs[to];
        List<String> before = new ArrayList<>(target.lines);
        boolean wasEdited = target.edited;
        List<String> from = to == 1 ? docs[0].lines.subList(b.l1(), b.l2()) : docs[1].lines.subList(b.r1(), b.r2());
        int start = to == 1 ? b.r1() : b.l1();
        int end = to == 1 ? b.r2() : b.l2();
        List<String> next = new ArrayList<>(target.lines.subList(0, start));
        next.addAll(from);
        next.addAll(target.lines.subList(end, target.lines.size()));
        target.lines = next;
        target.edited = true;
        undos.push(() -> {
            target.lines = before;
            target.edited = wasEdited;
        });
        undo.setEnabled(true);
        current = -1;
        recompare();
        host.titleChanged(this);
    }

    private void undo() {
        Runnable u = undos.poll();
        if (u != null) {
            u.run();
            recompare();
        }
        undo.setEnabled(!undos.isEmpty());
        host.titleChanged(this);
    }

    private void save(int side) {
        try {
            docs[side].save();
            summary.setText("Saved " + docs[side].side.name());
        } catch (IOException e) {
            summary.setText("Couldn't save " + docs[side].side.name() + ": " + e.getMessage());
            summary.setForeground(Theme.errorMark());
            return;
        }
        showNames();
        host.titleChanged(this);
    }

    private void swap() {
        Doc d = docs[0];
        docs[0] = docs[1];
        docs[1] = d;
        current = -1;
        recompare();
        host.titleChanged(this);
    }

    // ---------------------------------------------------------------- options

    private void ignoreMenu(JComponent under) {
        JPopupMenu m = new JPopupMenu();
        ButtonGroup g = new ButtonGroup();
        for (TextDiff.Spaces s : TextDiff.Spaces.values()) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(s.label, options.spaces() == s);
            r.addActionListener(e -> setOptions(new TextDiff.Options(s, options.ignoreCase(), options.maskTimes(),
                    options.maskNumbers(), options.maskIds())));
            g.add(r);
            m.add(r);
        }
        m.addSeparator();
        JCheckBoxMenuItem c = new JCheckBoxMenuItem("Ignore case", options.ignoreCase());
        c.addActionListener(e -> setOptions(new TextDiff.Options(options.spaces(), c.isSelected(), options.maskTimes(),
                options.maskNumbers(), options.maskIds())));
        m.add(c);
        m.show(under, 0, under.getHeight());
    }

    private void maskMenu(JComponent under) {
        JPopupMenu m = new JPopupMenu();
        JCheckBoxMenuItem t = new JCheckBoxMenuItem("Times and dates (2026-10-29 10:02:13,123)", options.maskTimes());
        t.addActionListener(e -> setOptions(new TextDiff.Options(options.spaces(), options.ignoreCase(), t.isSelected(),
                options.maskNumbers(), options.maskIds())));
        JCheckBoxMenuItem n = new JCheckBoxMenuItem("Numbers (took 812 ms)", options.maskNumbers());
        n.addActionListener(e -> setOptions(new TextDiff.Options(options.spaces(), options.ignoreCase(),
                options.maskTimes(), n.isSelected(), options.maskIds())));
        JCheckBoxMenuItem i = new JCheckBoxMenuItem("IDs (UUIDs, hex, req-7f3a9c)", options.maskIds());
        i.addActionListener(e -> setOptions(new TextDiff.Options(options.spaces(), options.ignoreCase(),
                options.maskTimes(), options.maskNumbers(), i.isSelected())));
        m.add(t);
        m.add(n);
        m.add(i);
        m.show(under, 0, under.getHeight());
    }

    void setOptions(TextDiff.Options o) {
        options = o;
        current = -1;
        recompare();
    }

    // ---------------------------------------------------------------- copying text

    private void copy(int side) {
        String t = selectedText(side);
        if (!t.isEmpty()) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t), null);
            } catch (RuntimeException e) {
                // no clipboard here
            }
        }
    }

    /** The selected rows' lines of one side (empty rows left out). */
    String selectedText(int side) {
        int[] sel = panes[side].selection();
        if (sel[0] < 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int r = sel[0]; r <= sel[1]; r++) {
            String t = shown[r] < 0 ? null : panes[side].rows().text(r);
            if (t != null) {
                b.append(t).append('\n');
            }
        }
        return b.toString();
    }

    private void popup(MouseEvent e, int side) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = panes[side].rowAt(e.getY());
        if (row < 0) {
            return;
        }
        int[] sel = panes[side].selection();
        if (row < sel[0] || row > sel[1]) {
            panes[side].select(row, row);
        }
        JPopupMenu m = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Copy", Icons.of("copy"));
        copy.addActionListener(a -> copy(side));
        m.add(copy);
        int line = panes[side].rows().line(row);
        JMenuItem open = new JMenuItem(line < 0 ? "Not in " + docs[side].side.name()
                : "Open " + docs[side].side.name() + " at Line " + String.format(Locale.ROOT, "%,d", line + 1),
                Icons.of("file-text"));
        open.setEnabled(line >= 0);
        open.addActionListener(a -> open(row, side));
        m.add(open);
        int b = blockOf(row);
        if (b >= 0 && result.blocks().get(b).isChange()) {
            m.addSeparator();
            JMenuItem toRight = new JMenuItem("Copy This Change to the Right  »");
            toRight.addActionListener(a -> apply(b, 1));
            JMenuItem toLeft = new JMenuItem("«  Copy This Change to the Left");
            toLeft.addActionListener(a -> apply(b, 0));
            m.add(toRight);
            m.add(toLeft);
        }
        m.show(panes[side], e.getX(), e.getY());
    }

    // ---------------------------------------------------------------- the gutter and the marks

    /** Between the panes: the changed blocks joined, with » and « to copy one to the other side. */
    private final class Gutter extends JComponent {
        static final int WIDTH = 40;
        private int hoverBlock = -1;
        private int hoverSide = -1;

        Gutter() {
            setOpaque(true);
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int[] h = hit(e.getX(), e.getY());
                    if (h[0] != hoverBlock || h[1] != hoverSide) {
                        hoverBlock = h[0];
                        hoverSide = h[1];
                        setCursor(Cursor.getPredefinedCursor(h[0] >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        setToolTipText(h[0] < 0 ? null : h[1] == 1 ? "Copy this change to the right (»)"
                                : "Copy this change to the left («)");
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverBlock = -1;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int[] h = hit(e.getX(), e.getY());
                    if (h[0] >= 0) {
                        apply(h[0], h[1]);
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        }

        private int offset() {
            return scrolls[1].getViewport().getViewPosition().y;
        }

        /** The block and side (1: » to the right, 0: « to the left) under a point, or -1. */
        int[] hit(int x, int y) {
            int rh = panes[1].rowHeight();
            int row = (y + offset()) / rh;
            int b = blockOf(row);
            if (b < 0 || !result.blocks().get(b).isChange() || rowOfBlock(b) != row) {
                return new int[] {-1, -1};
            }
            return new int[] {b, x < WIDTH / 2 ? 1 : 0};
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.gutterBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            int rh = panes[1].rowHeight();
            int off = offset();
            int first = Math.max(0, off / rh);
            int last = Math.min(shown.length - 1, (off + getHeight()) / rh);
            DiffPane.Rows rows = panes[0].rows();
            for (int r = first; r <= last; r++) {
                int y = r * rh - off;
                DiffPane.Kind k = rows.kind(r);
                if (k == DiffPane.Kind.SAME || shown[r] < 0) {
                    continue;
                }
                // the block's band across the gutter
                DiffPane.Kind band = blockKind(blockOf(r));
                g.setColor(DiffPane.background(band, false));
                g.fillRect(0, y, getWidth(), rh);
                int b = blockOf(r);
                if (rowOfBlock(b) == r) {
                    g.setColor(DiffPane.edge(band));
                    g.drawLine(0, y, getWidth(), y);
                    g.setFont(getFont().deriveFont(Font.BOLD, 13f));
                    FontMetrics fm = g.getFontMetrics();
                    for (int side = 1; side >= 0; side--) {
                        String a = side == 1 ? "»" : "«";
                        int x = side == 1 ? 6 : WIDTH - 6 - fm.stringWidth(a);
                        boolean hot = b == hoverBlock && side == hoverSide;
                        if (hot) {
                            g.setColor(DiffPane.edge(band));
                            g.fillRoundRect(x - 3, y + 1, fm.stringWidth(a) + 6, rh - 2, 6, 6);
                        }
                        g.setColor(hot ? Color.WHITE : Theme.pick(new Color(0x3B5BA5), new Color(0x9DB7F0)));
                        g.drawString(a, x, y + fm.getAscent() + 1);
                    }
                }
            }
            g.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(WIDTH, 100);
        }
    }

    /** How a whole block is coloured: changed, added or removed. */
    private DiffPane.Kind blockKind(int block) {
        if (block < 0) {
            return DiffPane.Kind.SAME;
        }
        return switch (result.blocks().get(block).kind()) {
            case EQUAL -> DiffPane.Kind.SAME;
            case INSERT -> DiffPane.Kind.ADDED;
            case DELETE -> DiffPane.Kind.REMOVED;
            case REPLACE -> DiffPane.Kind.CHANGED;
        };
    }

    /** By the scroll bar: a mark for each change, as far down as it is; a click goes there. */
    private final class Overview extends JComponent {
        static final int WIDTH = 12;

        Overview() {
            setOpaque(true);
            setToolTipText("Where the changes are: click to go there");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (shown.length == 0) {
                        return;
                    }
                    int row = (int) ((long) e.getY() * shown.length / Math.max(1, getHeight()));
                    int best = -1;
                    int dist = Integer.MAX_VALUE;
                    for (int b = 0; b < result.blocks().size(); b++) {
                        if (result.blocks().get(b).isChange()) {
                            int r = rowOfBlock(b);
                            if (r >= 0 && Math.abs(r - row) < dist) {
                                dist = Math.abs(r - row);
                                best = b;
                            }
                        }
                    }
                    if (best >= 0) {
                        goTo(best);
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Theme.uiColor("ScrollBar.track", Theme.background()));
            g.fillRect(0, 0, getWidth(), getHeight());
            int n = shown.length;
            if (n == 0) {
                return;
            }
            int h = getHeight();
            // each change from its first shown row, as many rows as it has
            for (int b = 0; b < result.blocks().size(); b++) {
                int r = rowOfBlock(b);
                if (r < 0 || !result.blocks().get(b).isChange()) {
                    continue;
                }
                TextDiff.Block bl = result.blocks().get(b);
                int len = Math.max(bl.l2() - bl.l1(), bl.r2() - bl.r1());
                int y = (int) ((long) r * h / n);
                g.setColor(DiffPane.edge(blockKind(b)));
                g.fillRect(2, y, getWidth() - 4, Math.max(2, (int) ((long) len * h / n)));
            }
            // the part on screen
            int rh = panes[1].rowHeight();
            int total = Math.max(1, n * rh);
            Rectangle v = scrolls[1].getViewport().getViewRect();
            g.setColor(Theme.pick(new Color(0, 0, 0, 60), new Color(255, 255, 255, 70)));
            g.drawRect(0, (int) ((long) v.y * h / total), getWidth() - 1,
                    Math.max(3, (int) ((long) v.height * h / total) - 1));
        }

    }

    // ---------------------------------------------------------------- the rest

    String title() {
        return "Compare: " + docs[0].side.name() + " ↔ " + docs[1].side.name()
                + (docs[0].edited || docs[1].edited ? " •" : "");
    }

    String statusText() {
        return summary.getText();
    }

    CsvCompareTab.Side sideForTest(int s) {
        return docs[s].side;
    }

    TextDiff.Result result() {
        return result;
    }

    /** Whether a side was changed (and not saved). */
    boolean edited(int side) {
        return docs[side].edited;
    }

    List<String> linesForTest(int side) {
        return docs[side].lines;
    }

    DiffPane paneForTest(int side) {
        return panes[side];
    }

    int currentForTest() {
        return current;
    }

    String positionForTest() {
        return position.getText();
    }

    void setFoldForTest(boolean on) {
        foldButton.setSelected(on);
        fold = on;
        unfolded.clear();
        rebuild();
    }

    int shownRowsForTest() {
        return shown.length;
    }

    void undoForTest() {
        undo();
    }

    void saveForTest(int side) {
        save(side);
    }

    boolean saveEnabledForTest(int side) {
        return save[side].isEnabled();
    }

    /** Where the right file's name starts and where the right pane starts, in the tab. */
    int[] fileRowSplitForTest() {
        Component right = fileRow.getComponent(1);
        int nameX = SwingUtilities.convertPoint(fileRow, right.getX(), 0, this).x;
        int paneX = SwingUtilities.convertPoint(body, scrolls[1].getX(), 0, this).x;
        return new int[] {nameX, paneX};
    }
}
