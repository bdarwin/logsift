package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import logsift.core.LogDocument;
import logsift.core.SearchQuery;

/**
 * Every Find All in one window, as foldable groups: a header row per search ("“ERROR” — 1,234
 * lines") with its matching lines below. The newest search is on top and open; older ones fold
 * away but stay. "Search in results" adds a group nested under the selected one.
 */
final class ResultsPanel extends JPanel {

    interface Host {
        void showLine(long line);

        void focusLog();

        /** Groups were added, removed or updated (titles, highlights, marks). */
        void resultsChanged();

        /** Another search was selected. */
        default void currentChanged() {}

        void extendPopup(LogView view, JPopupMenu menu);
    }

    private final LogDocument doc;
    private final Host host;
    private final List<FindGroup> groups = new ArrayList<>(); // in display order
    private final LogView view = new LogView();
    private final JTextField within = new JTextField(22);
    private final JToggleButton matchCase = MainFrame.toggle("Aa", "Match case");
    private final JToggleButton wholeWord = MainFrame.toggle("W", "Whole word");
    private final JToggleButton regex = MainFrame.toggle(".*", "Regular expression");
    private final JLabel target = new JLabel(" ");
    private final Runnable update;
    private int[] firstRow = new int[0];
    private int rowCount;
    private int nextColor;

    ResultsPanel(LogDocument doc, Host host) {
        super(new BorderLayout());
        this.doc = doc;
        this.host = host;
        AtomicBoolean pending = new AtomicBoolean();
        update = () -> {
            if (pending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    pending.set(false);
                    refresh();
                });
            }
        };

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 6));
        within.putClientProperty("JTextField.placeholderText", "Search in these results (Enter)");
        within.putClientProperty("JTextField.showClearButton", true);
        within.setMaximumSize(new Dimension(340, 30));
        JToolBar inField = new JToolBar();
        inField.setFloatable(false);
        inField.setOpaque(false);
        inField.add(matchCase);
        inField.add(wholeWord);
        inField.add(regex);
        within.putClientProperty("JTextField.trailingComponent", inField);
        within.addActionListener(e -> searchWithin());
        target.setForeground(Theme.gutterForeground());
        bar.add(within);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(target);
        bar.add(Box.createHorizontalGlue());
        bar.add(small("Fold all", "Fold every search to its header", () -> setAllExpanded(false)));
        bar.add(small("Unfold all", "Open every search", () -> setAllExpanded(true)));
        bar.add(small("Close", "Close the selected search (Shift+Esc)", this::closeCurrent));
        bar.add(small("Close all", "Close every search", this::closeAll));
        add(bar, BorderLayout.NORTH);

        view.setSource(new Rows());
        view.addListener(new LogView.Listener() {
            @Override
            public void foldToggled(LogView v, int row) {
                FindGroup g = headerOf(row);
                if (g != null) {
                    toggle(g);
                }
            }

            @Override
            public void rowActivated(LogView v, int row) {
                FindGroup g = headerOf(row);
                if (g != null) {
                    return; // handled as a click
                }
                show(row);
                host.focusLog();
            }

            @Override
            public void selectionChanged(LogView v, int row) {
                show(row);
                updateTarget();
            }
        });
        view.setPopupExtender((v, menu) -> extendPopup(menu));
        add(view, BorderLayout.CENTER);
        updateTarget();
    }

    private static JButton small(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    LogView view() {
        return view;
    }

    List<FindGroup> groups() {
        return List.copyOf(groups);
    }

    // ---------------------------------------------------------------- adding and closing

    /**
     * Adds a search on top (open; the others fold). The same search again moves to the top.
     *
     * @throws PatternSyntaxException for an invalid regex
     */
    FindGroup add(SearchQuery q) {
        return add(q, null);
    }

    /**
     * Adds a search that finds only in the lines {@code within} accepts (the filtered lines), or
     * in the whole file when it is null.
     */
    FindGroup add(SearchQuery q, java.util.function.LongPredicate within) {
        for (FindGroup g : groups) {
            if (g.parent == null && g.query.equals(q) && (g.within != null) == (within != null)) {
                moveToTop(g);
                return g;
            }
        }
        FindGroup g = new FindGroup(doc, q, null, within, nextColor++, update);
        for (FindGroup other : groups) {
            if (other.parent == null) {
                other.expanded = false; // older searches fold away
            }
        }
        groups.add(0, g);
        changed(g);
        return g;
    }

    /** Searches within a group's results; the new group goes right under it. */
    FindGroup addWithin(FindGroup parent, SearchQuery q) {
        FindGroup g = new FindGroup(doc, q, parent, nextColor++, update);
        int at = groups.indexOf(parent) + 1;
        while (at < groups.size() && isUnder(groups.get(at), parent)) {
            at++;
        }
        parent.expanded = true;
        groups.add(at, g);
        changed(g);
        return g;
    }

    private static boolean isUnder(FindGroup g, FindGroup ancestor) {
        for (FindGroup x = g.parent; x != null; x = x.parent) {
            if (x == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void moveToTop(FindGroup g) {
        List<FindGroup> family = new ArrayList<>();
        family.add(g);
        for (FindGroup x : groups) {
            if (isUnder(x, g)) {
                family.add(x);
            }
        }
        groups.removeAll(family);
        for (FindGroup other : groups) {
            if (other.parent == null) {
                other.expanded = false;
            }
        }
        g.expanded = true;
        groups.addAll(0, family);
        changed(g);
    }

    private void changed(FindGroup select) {
        refresh();
        if (select != null) {
            int i = groups.indexOf(select);
            if (i >= 0) {
                view.goToRow(firstRow[i], false);
            }
        }
        updateTarget();
    }

    void close(FindGroup g) {
        for (FindGroup x : List.copyOf(groups)) {
            if (x == g || isUnder(x, g)) {
                x.close();
                groups.remove(x);
            }
        }
        refresh();
        updateTarget();
    }

    void closeCurrent() {
        FindGroup g = current();
        if (g != null) {
            close(g);
        }
    }

    void closeAll() {
        for (FindGroup g : groups) {
            g.close();
        }
        groups.clear();
        refresh();
        updateTarget();
    }

    void setAllExpanded(boolean on) {
        for (FindGroup g : groups) {
            g.expanded = on;
        }
        refresh();
    }

    void toggle(FindGroup g) {
        g.expanded = !g.expanded;
        refresh();
        int i = groups.indexOf(g);
        if (i >= 0) {
            view.goToRow(firstRow[i], false);
        }
    }

    // ---------------------------------------------------------------- the current group

    /** The search the selection is in (or the top one). */
    FindGroup current() {
        int row = view.selectedRow();
        if (row >= 0 && row < rowCount) {
            return groups.get(groupIndex(row));
        }
        return groups.isEmpty() ? null : groups.get(0);
    }

    private void updateTarget() {
        FindGroup g = current();
        host.currentChanged();
        within.setEnabled(g != null);
        target.setText(g == null ? "Find All results appear here (newest on top)"
                : "in “" + g.title() + "”");
    }

    /** Searches within the current group with the text in the box. */
    void searchWithin() {
        FindGroup g = current();
        String t = within.getText();
        if (g == null || t.isEmpty()) {
            return;
        }
        SearchQuery q = new SearchQuery(t, matchCase.isSelected(), regex.isSelected(), wholeWord.isSelected());
        try {
            q.toPattern();
            within.putClientProperty("JComponent.outline", null);
        } catch (PatternSyntaxException e) {
            within.putClientProperty("JComponent.outline", "error");
            return;
        }
        within.setText("");
        addWithin(g, q);
    }

    void setWithinText(String t) {
        within.setText(t);
    }

    // ---------------------------------------------------------------- rows

    /** Recomputes refined groups and the rows; called when searches update. */
    void refresh() {
        for (FindGroup g : groups) {
            g.refresh(); // parents come before their children
        }
        firstRow = new int[groups.size()];
        int row = 0;
        for (int i = 0; i < groups.size(); i++) {
            firstRow[i] = row;
            FindGroup g = groups.get(i);
            row += 1 + (g.expanded && !hiddenByParent(g) ? g.count() : 0);
            if (hiddenByParent(g)) {
                row--; // inside a folded parent: not shown at all
            }
        }
        rowCount = row;
        List<LogView.Highlight> hl = new ArrayList<>();
        for (FindGroup g : groups) {
            hl.add(g.highlight());
        }
        view.setHighlights(hl);
        view.rowsChanged();
        host.resultsChanged();
    }

    private boolean hiddenByParent(FindGroup g) {
        for (FindGroup x = g.parent; x != null; x = x.parent) {
            if (!x.expanded) {
                return true;
            }
        }
        return false;
    }

    private int groupIndex(int row) {
        int i = java.util.Arrays.binarySearch(firstRow, row);
        if (i < 0) {
            i = -i - 2;
        }
        // several groups can start at the same row when some are hidden: take the last shown
        while (i + 1 < firstRow.length && firstRow[i + 1] == firstRow[i] && hiddenOrEmpty(i)) {
            i++;
        }
        return Math.max(0, i);
    }

    private boolean hiddenOrEmpty(int i) {
        return hiddenByParent(groups.get(i));
    }

    /** The group whose header is this row, or null for a result row. */
    private FindGroup headerOf(int row) {
        if (row < 0 || row >= rowCount) {
            return null;
        }
        int i = groupIndex(row);
        return row == firstRow[i] ? groups.get(i) : null;
    }

    private void show(int row) {
        if (row < 0 || row >= rowCount) {
            return;
        }
        int i = groupIndex(row);
        int k = row - firstRow[i] - 1;
        FindGroup g = groups.get(i);
        if (k >= 0 && k < g.count()) {
            host.showLine(g.lineAt(k));
        }
    }

    /** Selects a search's k-th result (for F3). */
    void selectResult(FindGroup g, int k) {
        int i = groups.indexOf(g);
        if (i < 0) {
            return;
        }
        if (!g.expanded) {
            g.expanded = true;
            refresh();
        }
        view.goToRow(firstRow[i] + 1 + k, false);
    }

    private final class Rows implements LogView.RowSource {
        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public long lineNumber(int row) {
            if (row >= rowCount) {
                return -1;
            }
            int i = groupIndex(row);
            int k = row - firstRow[i] - 1;
            FindGroup g = groups.get(i);
            return k >= 0 && k < g.count() ? g.lineAt(k) : -1;
        }

        @Override
        public long maxLineNumber() {
            long max = 0;
            for (FindGroup g : groups) {
                if (g.count() > 0) {
                    max = Math.max(max, g.lineAt(g.count() - 1));
                }
            }
            return max;
        }

        @Override
        public void texts(int first, int count, String[] out) {
            texts(first, count, out, false);
        }

        @Override
        public void textsNow(int first, int count, String[] out) {
            texts(first, count, out, true);
        }

        /** {@code now}: for painting, never wait for the disk (lines come later). */
        private void texts(int first, int count, String[] out, boolean now) {
            String[] one = new String[1];
            for (int j = 0; j < count; j++) {
                int row = first + j;
                if (row >= rowCount) {
                    out[j] = null;
                    continue;
                }
                int i = groupIndex(row);
                int k = row - firstRow[i] - 1;
                FindGroup g = groups.get(i);
                if (k < 0 || k >= g.count()) {
                    out[j] = k < 0 ? g.header() : null;
                } else if (now) {
                    doc.readLinesNow(g.lineAt(k), 1, one, new LogTab.LoadKey(view, count == 1 ? -1 : j),
                            () -> SwingUtilities.invokeLater(view::textsArrived));
                    out[j] = one[0];
                } else {
                    out[j] = doc.line(g.lineAt(k));
                }
            }
        }

        @Override
        public Color marker(int row) {
            FindGroup g = headerOf(row);
            return g == null ? null : g.colors()[2];
        }

        @Override
        public Boolean folded(int row) {
            FindGroup g = headerOf(row);
            return g == null ? null : !g.expanded;
        }

        @Override
        public Color tag(int row) {
            return row < rowCount ? groups.get(groupIndex(row)).colors()[2] : null;
        }
    }

    private void extendPopup(JPopupMenu menu) {
        FindGroup g = current();
        if (g != null) {
            menu.addSeparator();
            JMenuItem within2 = new JMenuItem("Search in “" + g.title() + "”…");
            within2.addActionListener(e -> within.requestFocusInWindow());
            menu.add(within2);
            JMenuItem close = new JMenuItem("Close This Search");
            close.addActionListener(e -> close(g));
            menu.add(close);
            JMenuItem closeAll = new JMenuItem("Close All Searches");
            closeAll.addActionListener(e -> closeAll());
            menu.add(closeAll);
            JMenuItem fold = new JMenuItem("Fold All");
            fold.addActionListener(e -> setAllExpanded(false));
            menu.add(fold);
            JMenuItem unfold = new JMenuItem("Unfold All");
            unfold.addActionListener(e -> setAllExpanded(true));
            menu.add(unfold);
        }
        host.extendPopup(view, menu);
    }

    void closeAllSessions() {
        for (FindGroup g : groups) {
            g.close();
        }
    }
}
