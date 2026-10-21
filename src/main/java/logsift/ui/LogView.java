package logsift.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Virtual list of log lines. Only visible lines are read and laid out, and the scrollbar counts
 * lines (not pixels), so files with hundreds of millions of lines scroll smoothly. Swing's own
 * JList/JTable measure in pixels and overflow around 100M rows.
 *
 * <p>Each visible line is laid out into one or more <em>segments</em>: one without wrap, several
 * with wrap (continuation segments are indented). Painting, mouse hit-testing and selection all
 * work on that layout. The scroll position is a line plus a segment within it.
 */
public final class LogView extends JPanel {

    /** Supplies the rows to show. Row numbers are 0-based. */
    public interface RowSource {
        int rowCount();

        /** 0-based line number in the file, shown in the gutter. Negative shows blank. */
        long lineNumber(int row);

        /** Fills {@code out[0..count)} with the text of rows {@code first..}; null if missing. */
        void texts(int first, int count, String[] out);

        /** Largest line number shown (sizes the gutter). */
        default long maxLineNumber() {
            int n = rowCount();
            return n == 0 ? 0 : lineNumber(n - 1);
        }

        /** Rows drawn grayed out (for example, context lines or lines the filters remove). */
        default boolean dimmed(int row) {
            return false;
        }

        /** Draw a separator above this row (lines were skipped here). */
        default boolean gapBefore(int row) {
            return false;
        }

        /** A color stripe at the left edge of the row (for example, which file it came from). */
        default Color tag(int row) {
            return null;
        }

        /** A dot in the gutter (for example, a bookmark). */
        default Color marker(int row) {
            return null;
        }
    }

    public interface Listener {
        /** Double-click or Enter on a row. */
        default void rowActivated(LogView view, int row) {}

        /** The selected row changed through user action. */
        default void selectionChanged(LogView view, int row) {}

        /** Follow mode was switched on or off by scrolling. */
        default void followChanged(LogView view, boolean follow) {}
    }

    private static final int MAX_COPY_ROWS = 500_000;
    private static final int TAB = 4;
    /** Continuation segments of a wrapped line are indented by this many columns. */
    private static final int WRAP_INDENT = 2;
    private static final int MAX_SEGMENTS = 2000;
    private static LogView lastFocused;

    private final Canvas canvas = new Canvas();
    private final JScrollBar vbar = new JScrollBar(JScrollBar.VERTICAL);
    private final JScrollBar hbar = new JScrollBar(JScrollBar.HORIZONTAL);
    private final OverviewBar overview = new OverviewBar(this);
    private final List<Listener> listeners = new ArrayList<>();
    private RowSource source = emptySource();

    private Font font;
    private FontMetrics metrics;
    private int rowHeight;
    private int charWidth;
    private int ascent;

    // Scroll position: the top line and the first visible segment of it (wrap only).
    private int topRow;
    private int topSeg;
    private int hOffset;
    private int maxColumns;
    private boolean wrap;

    // Selection runs from (anchor, anchorCol) to (lead, leadCol). Columns are char indexes into
    // the line; Integer.MAX_VALUE means the end of the line. In line mode whole lines are
    // selected. The lead row is also the current line.
    private int anchor = -1;
    private int lead = -1;
    private int anchorCol;
    private int leadCol;
    private boolean lineMode;
    private static final int BY_CHAR = 0;
    private static final int BY_WORD = 1;
    private static final int BY_LINE = 2;
    private int dragUnit = BY_CHAR;
    private int wordRow;
    private int wordStart;
    private int wordEnd;
    private boolean follow;
    private boolean adjusting;
    private Pattern matchPattern;
    private List<HighlightRule> rules = HighlightRule.defaults();
    private double wheelRemainder;
    private int dragDirection;
    private final Timer dragTimer = new Timer(40, e -> dragScroll());
    private BiConsumer<LogView, JPopupMenu> popupExtender;
    private final List<Runnable> paintListeners = new ArrayList<>();

    // Columns view: lines split by a log format into cells under a header.
    private ColumnLayout columns;
    private boolean wrapBeforeColumns;
    private final ColumnHeader header = new ColumnHeader();
    private BiConsumer<String, MouseEvent> columnMenu;

    // The layout of what is on screen: one entry per display row.
    private Line[] shownLines = new Line[0];
    private int[] shownLine = new int[0]; // index into shownLines, per display row
    private int[] shownSeg = new int[0];
    private int shownCount;
    private boolean layoutDirty = true;

    public LogView() {
        super(new BorderLayout());
        JPanel center = new JPanel(new BorderLayout());
        center.add(header, BorderLayout.NORTH);
        center.add(canvas, BorderLayout.CENTER);
        header.setVisible(false);
        add(center, BorderLayout.CENTER);
        JPanel east = new JPanel(new BorderLayout());
        east.add(overview, BorderLayout.WEST);
        east.add(vbar, BorderLayout.CENTER);
        add(east, BorderLayout.EAST);
        overview.setVisible(false);
        add(hbar, BorderLayout.SOUTH);
        setFontSize(13f);
        vbar.addAdjustmentListener(e -> {
            if (!adjusting) {
                topRow = e.getValue();
                topSeg = 0;
                changed();
                updateFollowFromPosition();
            }
        });
        hbar.addAdjustmentListener(e -> {
            if (!adjusting) {
                hOffset = e.getValue();
                changed();
            }
        });
        hbar.setUnitIncrement(24);
        installMouse();
        installKeys();
        canvas.setFocusable(true);
        canvas.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                lastFocused = LogView.this;
                canvas.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                canvas.repaint();
            }
        });
    }

    /** The view that most recently had keyboard focus (for menu commands like Copy). */
    static LogView lastFocused() {
        return lastFocused;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void setSource(RowSource source) {
        this.source = source;
        topRow = 0;
        topSeg = 0;
        hOffset = 0;
        maxColumns = 0;
        anchor = lead = -1;
        anchorCol = leadCol = 0;
        lineMode = false;
        rowsChanged();
    }

    /** Shows the highlight bar beside the scrollbar (null hides it). */
    void setOverview(OverviewBar.Model model) {
        overview.setModel(model);
    }

    /** Repaints the highlight bar (after its marks changed). */
    void overviewChanged() {
        overview.repaint();
    }

    OverviewBar overviewForTest() {
        return overview;
    }

    /** Runs after every repaint (for things that follow the scroll position). */
    void addPaintListener(Runnable r) {
        paintListeners.add(r);
    }

    /** First and last row on screen. */
    int[] shownRows() {
        ensureLayout();
        if (shownCount == 0) {
            return null;
        }
        return new int[] {shownLines[shownLine[0]].row, shownLines[shownLine[shownCount - 1]].row};
    }

    /** Lets the owner add items to the right-click menu. */
    public void setPopupExtender(BiConsumer<LogView, JPopupMenu> extender) {
        this.popupExtender = extender;
    }

    public RowSource source() {
        return source;
    }

    public void setMatchPattern(Pattern p) {
        this.matchPattern = p;
        canvas.repaint();
    }

    public void setHighlightRules(List<HighlightRule> rules) {
        this.rules = List.copyOf(rules);
        canvas.repaint();
    }

    public void setFontSize(float size) {
        font = Theme.monoFont(size);
        metrics = getFontMetrics(font);
        rowHeight = metrics.getHeight() + 2;
        charWidth = Math.max(1, metrics.charWidth('m'));
        ascent = metrics.getAscent() + 1;
        vbar.setUnitIncrement(1);
        rowsChanged();
    }

    public float fontSize() {
        return font.getSize2D();
    }

    public boolean isWrap() {
        return columns == null ? wrap : wrapBeforeColumns;
    }

    /** Shows lines as columns (null: as plain text). Wrap is paused while columns are shown. */
    void setColumns(ColumnLayout layout) {
        if (layout != null && columns == null) {
            wrapBeforeColumns = wrap;
            columns = layout;
            setWrapNow(false);
        } else if (layout == null && columns != null) {
            columns = null;
            setWrapNow(wrapBeforeColumns);
        } else {
            columns = layout;
        }
        header.setVisible(columns != null);
        header.repaint();
        maxColumns = 0;
        updateScrollbars();
        changed();
        revalidate();
    }

    ColumnLayout columns() {
        return columns;
    }

    /** Right-click on a column header (for "Count values" and similar). */
    void setColumnMenu(BiConsumer<String, MouseEvent> menu) {
        this.columnMenu = menu;
    }

    /** Wraps long lines at the window edge instead of scrolling sideways. */
    public void setWrap(boolean on) {
        if (columns != null) {
            wrapBeforeColumns = on; // applies when columns are turned off
            return;
        }
        setWrapNow(on);
    }

    private void setWrapNow(boolean on) {
        if (wrap == on) {
            return;
        }
        wrap = on;
        topSeg = 0;
        hOffset = 0;
        changed();
        updateScrollbars();
        if (follow) {
            scrollToBottom();
        } else if (lead >= 0) {
            ensureVisible(lead);
        }
    }

    public boolean isFollow() {
        return follow;
    }

    /** Follow mode keeps the last row in view as rows are added (tail -f). */
    public void setFollow(boolean on) {
        if (follow != on) {
            follow = on;
            listeners.forEach(l -> l.followChanged(this, on));
        }
        if (on) {
            scrollToBottom();
        }
    }

    /** Call after the row source changed size or content. */
    public void rowsChanged() {
        int n = source.rowCount();
        if (lead >= n || anchor >= n) {
            anchor = lead = n - 1;
            anchorCol = leadCol = 0;
            lineMode = false;
        }
        if (topRow >= n) {
            topRow = Math.max(0, n - 1);
            topSeg = 0;
        }
        changed();
        if (follow) {
            scrollToBottom();
        }
        updateScrollbars();
    }

    public int selectedRow() {
        return lead;
    }

    public int topRow() {
        return topRow;
    }

    /** Display rows that fit on screen. */
    public int visibleRows() {
        return Math.max(1, canvas.getHeight() / rowHeight);
    }

    /** Selects {@code row} and scrolls it into view; centered if {@code center}. */
    public void goToRow(int row, boolean center) {
        int n = source.rowCount();
        if (n == 0) {
            return;
        }
        row = Math.max(0, Math.min(n - 1, row));
        anchor = lead = row;
        anchorCol = leadCol = 0;
        lineMode = false;
        if (center) {
            int[] pos = advance(row, 0, -(visibleRows() / 2));
            setTop(pos[0], pos[1]);
        } else {
            ensureVisible(row);
        }
        updateFollowFromPosition();
        canvas.repaint();
    }

    public void focusView() {
        canvas.requestFocusInWindow();
    }

    /** Copies the selected text to the clipboard; with nothing selected, the current line. */
    public void copySelection() {
        if (lead < 0) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(selectedText()), null);
    }

    /** True if some text (not just a caret) is selected. */
    public boolean hasSelection() {
        return lead >= 0 && (lineMode || anchor != lead || anchorCol != leadCol);
    }

    /** First and last selected row (or the current row twice); null if none. */
    public int[] selectedRows() {
        if (lead < 0) {
            return null;
        }
        return new int[] {Math.min(anchor, lead), Math.max(anchor, lead)};
    }

    /**
     * The selected text, lines joined with the platform line separator. With nothing selected
     * it is the current line. At most {@value #MAX_COPY_ROWS} lines.
     */
    public String selectedText() {
        if (lead < 0) {
            return "";
        }
        if (!hasSelection()) {
            String t = textOf(lead);
            return t == null ? "" : t;
        }
        int[] s = selectionBounds();
        int from = s[0];
        int to = Math.min(s[2], from + MAX_COPY_ROWS - 1);
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        String[] buf = new String[Math.min(to - from + 1, 4096)];
        for (int first = from; first <= to; first += buf.length) {
            int k = Math.min(buf.length, to - first + 1);
            source.texts(first, k, buf);
            for (int i = 0; i < k; i++) {
                int row = first + i;
                String t = buf[i] == null ? "" : buf[i];
                int c0 = row == s[0] ? Math.min(s[1], t.length()) : 0;
                int c1 = row == s[2] ? Math.min(s[3], t.length()) : t.length();
                if (row > from) {
                    sb.append(nl);
                }
                if (c1 > c0) {
                    sb.append(t, c0, c1);
                }
            }
        }
        return sb.toString();
    }

    /** Selected text within one line, for pre-filling Find; null if none or multi-line. */
    public String selectionForFind() {
        if (!hasSelection() || lineMode || anchor != lead) {
            return null;
        }
        String t = selectedText();
        return t.isEmpty() || t.length() > 1000 ? null : t;
    }

    public void selectAll() {
        int n = source.rowCount();
        if (n == 0) {
            return;
        }
        anchor = 0;
        lead = n - 1;
        lineMode = true;
        canvas.repaint();
    }

    /** Whether this view's text area has keyboard focus. */
    public boolean isViewFocused() {
        return canvas.isFocusOwner();
    }

    // For tests: where to click to reach a column of a plain ASCII line without tabs.
    JComponent canvasForTest() {
        return canvas;
    }

    int xForColumnForTest(int col) {
        return gutterWidth() - hOffset + col * charWidth;
    }

    int yForRowForTest(int row) {
        ensureLayout();
        for (int i = 0; i < shownCount; i++) {
            if (shownLines[shownLine[i]].row == row) {
                return i * rowHeight + rowHeight / 2;
            }
        }
        return -1;
    }

    /** Display column where each segment of a row starts. For tests. */
    int[] segmentStartsForTest(int row) {
        return lineAt(row).segStart.clone();
    }

    /** X of a display column on a segment of a row. For tests. */
    int xForSegmentColumnForTest(int row, int seg, int col) {
        Line l = lineAt(row);
        return l.x(seg, col);
    }

    boolean horizontalScrollbarVisibleForTest() {
        return hbar.isVisible();
    }

    /** Number of display rows the given row takes (1 without wrap). For tests. */
    int segmentsForTest(int row) {
        return segCount(row);
    }

    /** {startRow, startCol, endRow, endCol}, start before end; line mode spans whole lines. */
    private int[] selectionBounds() {
        boolean forward = anchor < lead || (anchor == lead && anchorCol <= leadCol);
        int sr = forward ? anchor : lead;
        int sc = forward ? anchorCol : leadCol;
        int er = forward ? lead : anchor;
        int ec = forward ? leadCol : anchorCol;
        if (lineMode) {
            sc = 0;
            ec = Integer.MAX_VALUE;
        }
        return new int[] {sr, sc, er, ec};
    }

    private String textOf(int row) {
        if (row < 0 || row >= source.rowCount()) {
            return null;
        }
        String[] one = new String[1];
        source.texts(row, 1, one);
        return one[0];
    }

    // ---------------------------------------------------------------- layout

    /** One line laid out for display. Columns are positions in the tab-expanded text. */
    private final class Line {
        final int row;
        final String raw;
        final String text;
        final int[] colOf;   // display column of each raw char (null if no tabs)
        final boolean wide;
        final int[] segStart; // display column where each segment starts

        Line(int row, String raw) {
            this.row = row;
            this.raw = raw;
            this.colOf = raw.indexOf('\t') >= 0 ? new int[raw.length() + 1] : null;
            this.text = expandTabs(raw, colOf);
            this.wide = hasWideChars(text);
            this.segStart = wrap ? wrapPoints() : new int[] {0};
        }

        int segCount() {
            return segStart.length;
        }

        int segEnd(int seg) {
            return seg + 1 < segStart.length ? segStart[seg + 1] : text.length();
        }

        /** Display column of raw char index {@code c}. */
        int col(int c) {
            c = Math.min(c, raw.length());
            return colOf == null ? c : colOf[c];
        }

        /** Raw char index of a display column (inverse of col). */
        int rawIndexOfCol(int dcol) {
            if (colOf == null) {
                return Math.min(dcol, raw.length());
            }
            for (int i = 0; i <= raw.length(); i++) {
                if (colOf[i] >= dcol) {
                    return i;
                }
            }
            return raw.length();
        }

        /** X where segment {@code seg} starts. */
        int segX(int seg) {
            int gutter = gutterWidth();
            if (!wrap) {
                return gutter - hOffset;
            }
            return gutter + (seg > 0 ? WRAP_INDENT * charWidth : 0);
        }

        /** X of display column {@code col} within segment {@code seg}. */
        int x(int seg, int col) {
            int s0 = segStart[seg];
            col = Math.max(s0, Math.min(col, text.length()));
            if (wide) {
                return segX(seg) + metrics.stringWidth(text.substring(s0, col));
            }
            return segX(seg) + (col - s0) * charWidth;
        }

        /** Raw char index nearest to x on segment {@code seg} (a caret position). */
        int charAt(int seg, int x) {
            int s0 = segStart[seg];
            int s1 = segEnd(seg);
            int rel = x - segX(seg);
            int dcol;
            if (wide) {
                dcol = s1;
                int prev = 0;
                for (int i = s0; i < s1; i++) {
                    int next = metrics.stringWidth(text.substring(s0, i + 1));
                    if (rel < (prev + next) / 2) {
                        dcol = i;
                        break;
                    }
                    prev = next;
                }
            } else {
                dcol = s0 + Math.round((float) rel / charWidth);
            }
            dcol = Math.max(s0, Math.min(s1, dcol));
            int idx = dcol;
            if (colOf != null) {
                idx = raw.length();
                for (int i = 0; i <= raw.length(); i++) {
                    if (colOf[i] >= dcol) {
                        idx = i > 0 && dcol - colOf[i - 1] < colOf[i] - dcol ? i - 1 : i;
                        break;
                    }
                }
            }
            idx = Math.min(idx, raw.length());
            if (idx > 0 && idx < raw.length() && Character.isLowSurrogate(raw.charAt(idx))) {
                idx--; // never split a surrogate pair
            }
            return idx;
        }

        /** Where to break the text so each segment fits the width, preferring spaces. */
        private int[] wrapPoints() {
            int avail = Math.max(charWidth * 8, canvas.getWidth() - gutterWidth() - charWidth);
            int n = text.length();
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            int pos = 0;
            while (pos < n && starts.size() < MAX_SEGMENTS) {
                int width = avail - (starts.size() > 1 ? WRAP_INDENT * charWidth : 0);
                int end = fitEnd(pos, width);
                if (end >= n) {
                    break;
                }
                // prefer breaking after a space in the last 30% of the segment
                int minBreak = pos + Math.max(1, (end - pos) * 7 / 10);
                for (int i = end; i > minBreak; i--) {
                    if (text.charAt(i - 1) == ' ') {
                        end = i;
                        break;
                    }
                }
                if (end < n && Character.isLowSurrogate(text.charAt(end))) {
                    end--;
                }
                if (end <= pos) {
                    end = pos + 1;
                }
                starts.add(end);
                pos = end;
            }
            int[] out = new int[starts.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = starts.get(i);
            }
            return out;
        }

        /** End (exclusive) of the longest run from {@code pos} that fits {@code width} px. */
        private int fitEnd(int pos, int width) {
            if (!wide) {
                return pos + Math.max(1, width / charWidth);
            }
            int w = 0;
            int i = pos;
            while (i < text.length()) {
                w += metrics.charWidth(text.charAt(i));
                if (w > width && i > pos) {
                    return i;
                }
                i++;
            }
            return i;
        }
    }

    /** Lays out a single row (reads its text). */
    private Line lineAt(int row) {
        String t = textOf(row);
        return new Line(row, t == null ? "" : t);
    }

    private int segCount(int row) {
        if (!wrap) {
            return 1;
        }
        return lineAt(row).segCount();
    }

    /** Recomputes what is on screen, if anything changed since the last time. */
    private void ensureLayout() {
        if (!layoutDirty) {
            return;
        }
        layoutDirty = false;
        int n = source.rowCount();
        int vis = visibleRows() + 1;
        int count = Math.max(0, Math.min(vis, n - topRow));
        String[] texts = new String[count];
        if (count > 0) {
            source.texts(topRow, count, texts);
        }
        List<Line> lines = new ArrayList<>(count);
        int[] li = new int[vis];
        int[] sg = new int[vis];
        int shown = 0;
        for (int i = 0; i < count && shown < vis; i++) {
            Line l = new Line(topRow + i, texts[i] == null ? "" : texts[i]);
            int first = 0;
            if (i == 0) {
                topSeg = Math.min(topSeg, l.segCount() - 1);
                first = topSeg;
            }
            lines.add(l);
            for (int s = first; s < l.segCount() && shown < vis; s++) {
                li[shown] = lines.size() - 1;
                sg[shown] = s;
                shown++;
            }
        }
        shownLines = lines.toArray(new Line[0]);
        shownLine = li;
        shownSeg = sg;
        shownCount = shown;
    }

    /** Marks the layout stale and repaints. */
    private void changed() {
        layoutDirty = true;
        canvas.repaint();
    }

    // ---------------------------------------------------------------- scrolling

    /** Moves (row, seg) by {@code delta} display rows, staying within the file. */
    private int[] advance(int row, int seg, int delta) {
        int n = source.rowCount();
        if (n == 0) {
            return new int[] {0, 0};
        }
        if (!wrap) {
            return new int[] {Math.max(0, Math.min(n - 1, row + delta)), 0};
        }
        while (delta > 0) {
            int segs = segCount(row);
            int step = Math.min(delta, segs - 1 - seg);
            if (step > 0) {
                seg += step;
                delta -= step;
            } else if (row + 1 < n) {
                row++;
                seg = 0;
                delta--;
            } else {
                break;
            }
        }
        while (delta < 0) {
            int step = Math.min(-delta, seg);
            if (step > 0) {
                seg -= step;
                delta += step;
            } else if (row > 0) {
                row--;
                seg = segCount(row) - 1;
                delta++;
            } else {
                break;
            }
        }
        return new int[] {row, seg};
    }

    /** The scroll position that shows the end of the file at the bottom. */
    private int[] maxTop() {
        int n = source.rowCount();
        int vis = visibleRows();
        if (!wrap) {
            return new int[] {Math.max(0, n - vis), 0};
        }
        if (n == 0) {
            return new int[] {0, 0};
        }
        int last = n - 1;
        return advance(last, segCount(last) - 1, -(vis - 1));
    }

    private static boolean after(int r1, int s1, int r2, int s2) {
        return r1 > r2 || (r1 == r2 && s1 > s2);
    }

    private void scrollToBottom() {
        int[] m = maxTop();
        setTop(m[0], m[1]);
    }

    private void setTop(int row, int seg) {
        int n = source.rowCount();
        row = Math.max(0, Math.min(Math.max(0, n - 1), row));
        seg = Math.max(0, seg);
        // only near the end can the position be past the last screenful
        if (row >= n - visibleRows() - 1) {
            int[] m = maxTop();
            if (after(row, seg, m[0], m[1])) {
                row = m[0];
                seg = m[1];
            }
        }
        topRow = row;
        topSeg = seg;
        updateScrollbars();
        changed();
    }

    private void scrollBy(int displayRows) {
        int[] p = advance(topRow, topSeg, displayRows);
        setTop(p[0], p[1]);
    }

    private void ensureVisible(int row) {
        int vis = visibleRows();
        if (row < topRow || (row == topRow && topSeg > 0)) {
            setTop(row, 0);
            return;
        }
        if (!wrap) {
            if (row >= topRow + vis) {
                setTop(row - vis + 1, 0);
            }
            return;
        }
        if (row - topRow >= vis) {
            int[] p = advance(row, segCount(row) - 1, -(vis - 1));
            setTop(p[0], p[1]);
            return;
        }
        // count display rows from the top to the end of this row
        int used = segCount(topRow) - topSeg;
        for (int r = topRow + 1; r <= row && used <= vis; r++) {
            used += segCount(r);
        }
        if (used > vis) {
            int[] p = advance(row, segCount(row) - 1, -(vis - 1));
            setTop(p[0], p[1]);
        }
    }

    private boolean atBottom() {
        int[] m = maxTop();
        return !after(m[0], m[1], topRow, topSeg);
    }

    /** After a user scroll: scrolling up turns follow off. */
    private void updateFollowFromPosition() {
        if (follow && !atBottom()) {
            follow = false;
            listeners.forEach(l -> l.followChanged(this, false));
        }
    }

    private void updateScrollbars() {
        adjusting = true;
        try {
            int n = source.rowCount();
            int vis = visibleRows();
            vbar.setValues(Math.min(topRow, Math.max(0, n - 1)), Math.min(vis, Math.max(n, 1)), 0,
                    Math.max(n, 1));
            vbar.setBlockIncrement(Math.max(1, vis - 1));
            vbar.setEnabled(n > vis || topRow > 0 || topSeg > 0);

            if (wrap) {
                hOffset = 0;
                hbar.setVisible(false);
            } else {
                int textWidth = canvas.getWidth() - gutterWidth();
                int contentWidth = (maxColumns + 2) * charWidth;
                int hmax = Math.max(0, contentWidth - textWidth);
                hOffset = Math.max(0, Math.min(hmax, hOffset));
                hbar.setValues(hOffset, Math.max(1, textWidth), 0, Math.max(textWidth, contentWidth));
                hbar.setBlockIncrement(Math.max(charWidth, textWidth - 4 * charWidth));
                hbar.setVisible(hmax > 0);
            }
        } finally {
            adjusting = false;
        }
    }

    private void moveLead(int row, boolean extend) {
        int n = source.rowCount();
        if (n == 0) {
            return;
        }
        row = Math.max(0, Math.min(n - 1, row));
        lead = row;
        if (!extend || anchor < 0) {
            anchor = row;
            anchorCol = leadCol = 0;
            lineMode = false;
        } else {
            lineMode = true; // keyboard and gutter selection work on whole lines
        }
        ensureVisible(row);
        updateFollowFromPosition();
        canvas.repaint();
        int r = row;
        listeners.forEach(l -> l.selectionChanged(this, r));
    }

    /** Moves the lead (caret) to a character position; extends the selection if asked. */
    private void selectTo(int row, int col, boolean extend) {
        int n = source.rowCount();
        if (n == 0) {
            return;
        }
        row = Math.max(0, Math.min(n - 1, row));
        boolean rowChanged = row != lead;
        lead = row;
        leadCol = col;
        if (!extend || anchor < 0) {
            anchor = row;
            anchorCol = col;
        }
        lineMode = false;
        ensureVisible(row);
        updateFollowFromPosition();
        canvas.repaint();
        if (rowChanged || !extend) {
            int r = row;
            listeners.forEach(l -> l.selectionChanged(this, r));
        }
    }

    /** Extends a selection made by double-click, word by word. */
    private void extendByWord(int row, int col) {
        String t = textOf(row);
        int[] w = wordAt(t == null ? "" : t, col);
        boolean after = row > wordRow || (row == wordRow && col >= wordStart);
        anchor = wordRow;
        anchorCol = after ? wordStart : wordEnd;
        lineMode = false;
        selectTo(row, after ? Math.max(w[1], col) : Math.min(w[0], col), true);
    }

    private void dragTo(int row, int col) {
        switch (dragUnit) {
            case BY_LINE -> moveLead(row, true);
            case BY_WORD -> extendByWord(row, col);
            default -> selectTo(row, col, true);
        }
    }

    private void dragScroll() {
        if (dragDirection != 0) {
            scrollBy(dragDirection);
            ensureLayout();
            int target;
            if (dragDirection < 0) {
                target = Math.max(0, topRow);
            } else {
                target = shownCount == 0 ? topRow
                        : shownLines[shownLine[shownCount - 1]].row;
            }
            dragTo(target, dragDirection < 0 ? 0 : Integer.MAX_VALUE);
        }
    }

    /** Start and end of the word (letters, digits, _) at {@code col}; one char otherwise. */
    static int[] wordAt(String s, int col) {
        int n = s.length();
        if (n == 0) {
            return new int[] {0, 0};
        }
        int c = Math.max(0, Math.min(col, n - 1));
        if (!isWordChar(s.charAt(c)) && c > 0 && col >= n) {
            c--;
        }
        if (!isWordChar(s.charAt(c))) {
            int end = c + 1;
            if (Character.isHighSurrogate(s.charAt(c)) && end < n) {
                end++;
            }
            return new int[] {c, end};
        }
        int a = c;
        while (a > 0 && isWordChar(s.charAt(a - 1))) {
            a--;
        }
        int b = c;
        while (b < n && isWordChar(s.charAt(b))) {
            b++;
        }
        return new int[] {a, b};
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The display row index at y, or -1 below the last one. */
    private int shownAt(int y) {
        ensureLayout();
        int i = Math.max(0, y) / rowHeight;
        return i < shownCount ? i : -1;
    }

    /** Row under y, or -1 below the last row. */
    private int rowAt(int y) {
        int i = shownAt(y);
        return i < 0 ? -1 : shownLines[shownLine[i]].row;
    }

    /** Row under y, clamped to the existing rows; -1 if there are none. */
    private int rowAtClamped(int y) {
        if (source.rowCount() == 0) {
            return -1;
        }
        int r = rowAt(y);
        if (r >= 0) {
            return r;
        }
        return shownCount == 0 ? source.rowCount() - 1 : shownLines[shownLine[shownCount - 1]].row;
    }

    /** Character position under (x, y); end of line below the last row. */
    private int colAt(int x, int y) {
        int i = shownAt(y);
        if (i < 0) {
            return Integer.MAX_VALUE;
        }
        return shownLines[shownLine[i]].charAt(shownSeg[i], x);
    }

    private boolean inGutter(int x) {
        return x < gutterWidth() - 6;
    }

    // ---------------------------------------------------------------- input

    private void installMouse() {
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                if (e.isPopupTrigger()) {
                    popup(e);
                    return;
                }
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                boolean belowLast = rowAt(e.getY()) < 0;
                int row = rowAtClamped(e.getY());
                if (row < 0) {
                    return;
                }
                int clicks = e.getClickCount();
                if (columns != null && clicks < 2) {
                    dragUnit = BY_LINE;
                    moveLead(row, e.isShiftDown());
                    return;
                }
                if (inGutter(e.getX()) || clicks >= 3) {
                    dragUnit = BY_LINE;
                    moveLead(row, e.isShiftDown());
                    if (!e.isShiftDown()) {
                        lineMode = true; // select this whole line
                        canvas.repaint();
                    }
                } else if (clicks == 2) {
                    dragUnit = BY_WORD;
                    String t = textOf(row);
                    int[] w = wordAt(t == null ? "" : t, colAt(e.getX(), e.getY()));
                    wordRow = row;
                    wordStart = w[0];
                    wordEnd = w[1];
                    anchor = row;
                    anchorCol = w[0];
                    selectTo(row, w[1], true);
                    int r = row;
                    listeners.forEach(l -> l.rowActivated(LogView.this, r));
                } else {
                    dragUnit = BY_CHAR;
                    int col = belowLast ? Integer.MAX_VALUE : colAt(e.getX(), e.getY());
                    selectTo(row, col, e.isShiftDown());
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                canvas.setCursor(Cursor.getPredefinedCursor(inGutter(e.getX())
                        ? Cursor.DEFAULT_CURSOR : Cursor.TEXT_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragTimer.stop();
                dragDirection = 0;
                if (e.isPopupTrigger()) {
                    popup(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) {
                    return;
                }
                if (e.getY() < 0) {
                    dragDirection = -1;
                    dragTimer.start();
                } else if (e.getY() > canvas.getHeight()) {
                    dragDirection = 1;
                    dragTimer.start();
                } else {
                    dragDirection = 0;
                    dragTimer.stop();
                    if (dragUnit != BY_LINE && !wrap) {
                        // scroll sideways when dragging past the left or right edge
                        if (e.getX() > canvas.getWidth() - charWidth) {
                            hbarScroll(charWidth * 2);
                        } else if (e.getX() < gutterWidth() && hOffset > 0) {
                            hbarScroll(-charWidth * 2);
                        }
                    }
                    int row = rowAt(e.getY());
                    if (row >= 0) {
                        dragTo(row, colAt(e.getX(), e.getY()));
                    } else if (source.rowCount() > 0) {
                        dragTo(rowAtClamped(e.getY()), Integer.MAX_VALUE);
                    }
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double rot = e.getPreciseWheelRotation();
                if (e.isShiftDown()) {
                    if (!wrap) {
                        hbarScroll((int) Math.round(rot * charWidth * 3));
                    }
                    return;
                }
                wheelRemainder += rot * 3;
                int rows = (int) wheelRemainder;
                wheelRemainder -= rows;
                if (rows != 0) {
                    scrollBy(rows);
                    updateFollowFromPosition();
                }
            }
        };
        canvas.addMouseListener(m);
        canvas.addMouseMotionListener(m);
        canvas.addMouseWheelListener(m);
    }

    private void hbarScroll(int dx) {
        hOffset = Math.max(0, hOffset + dx);
        updateScrollbars();
        changed();
        header.repaint();
    }

    private void popup(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem(hasSelection() ? "Copy" : "Copy Line");
        copy.addActionListener(a -> copySelection());
        menu.add(copy);
        JMenuItem all = new JMenuItem("Select All");
        all.addActionListener(a -> selectAll());
        menu.add(all);
        if (popupExtender != null) {
            popupExtender.accept(this, menu);
        }
        menu.show(canvas, e.getX(), e.getY());
    }

    private void installKeys() {
        InputMap im = canvas.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = canvas.getActionMap();
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(im, am, "up", KeyEvent.VK_UP, 0, () -> moveLead(currentRow() - 1, false));
        bind(im, am, "down", KeyEvent.VK_DOWN, 0, () -> moveLead(currentRow() + 1, false));
        bind(im, am, "pgup", KeyEvent.VK_PAGE_UP, 0, () -> page(-1, false));
        bind(im, am, "pgdn", KeyEvent.VK_PAGE_DOWN, 0, () -> page(1, false));
        bind(im, am, "home", KeyEvent.VK_HOME, 0, () -> moveLead(0, false));
        bind(im, am, "end", KeyEvent.VK_END, 0, this::endKey);
        bind(im, am, "home2", KeyEvent.VK_HOME, menu, () -> moveLead(0, false));
        bind(im, am, "end2", KeyEvent.VK_END, menu, this::endKey);
        bind(im, am, "home3", KeyEvent.VK_UP, menu, () -> moveLead(0, false));
        bind(im, am, "end3", KeyEvent.VK_DOWN, menu, this::endKey);
        int sh = InputEvent.SHIFT_DOWN_MASK;
        bind(im, am, "s-up", KeyEvent.VK_UP, sh, () -> moveLead(currentRow() - 1, true));
        bind(im, am, "s-down", KeyEvent.VK_DOWN, sh, () -> moveLead(currentRow() + 1, true));
        bind(im, am, "s-pgup", KeyEvent.VK_PAGE_UP, sh, () -> page(-1, true));
        bind(im, am, "s-pgdn", KeyEvent.VK_PAGE_DOWN, sh, () -> page(1, true));
        bind(im, am, "s-home", KeyEvent.VK_HOME, sh, () -> moveLead(0, true));
        bind(im, am, "s-end", KeyEvent.VK_END, sh, () -> moveLead(source.rowCount() - 1, true));
        bind(im, am, "left", KeyEvent.VK_LEFT, 0, () -> hbarScroll(-charWidth * 4));
        bind(im, am, "right", KeyEvent.VK_RIGHT, 0, () -> hbarScroll(charWidth * 4));
        bind(im, am, "enter", KeyEvent.VK_ENTER, 0, () -> {
            if (lead >= 0) {
                listeners.forEach(l -> l.rowActivated(this, lead));
            }
        });
        bind(im, am, "copy", KeyEvent.VK_C, menu, this::copySelection);
        bind(im, am, "selectAll", KeyEvent.VK_A, menu, this::selectAll);
    }

    private void endKey() {
        moveLead(source.rowCount() - 1, false);
        setFollow(true);
    }

    private int currentRow() {
        return lead >= 0 ? lead : topRow - 1;
    }

    private void page(int dir, boolean extend) {
        ensureLayout();
        int offset = 0;
        for (int i = 0; i < shownCount; i++) {
            if (shownLines[shownLine[i]].row == lead) {
                offset = i;
                break;
            }
        }
        scrollBy(dir * Math.max(1, visibleRows() - 1));
        ensureLayout();
        int target = shownCount == 0 ? topRow
                : shownLines[shownLine[Math.min(offset, shownCount - 1)]].row;
        if (dir > 0 && atBottom() && shownCount > 0 && offset >= shownCount - 1) {
            target = source.rowCount() - 1;
        }
        moveLead(target, extend);
    }

    private static void bind(InputMap im, ActionMap am, String name, int key, int mods,
                             Runnable r) {
        im.put(KeyStroke.getKeyStroke(key, mods), name);
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    // ---------------------------------------------------------------- painting

    private int gutterWidth() {
        long maxLine = Math.max(1, source.maxLineNumber() + 1);
        int digits = Math.max(4, Long.toString(maxLine).length());
        return digits * charWidth + 20;
    }

    private static RowSource emptySource() {
        return new RowSource() {
            @Override
            public int rowCount() {
                return 0;
            }

            @Override
            public long lineNumber(int row) {
                return row;
            }

            @Override
            public void texts(int first, int count, String[] out) {}
        };
    }

    /** Expands tabs; fills {@code colOf[i]} with the display column of char i (if not null). */
    private static String expandTabs(String s, int[] colOf) {
        if (s.indexOf('\t') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            if (colOf != null) {
                colOf[i] = sb.length();
            }
            char c = s.charAt(i);
            if (c == '\t') {
                int spaces = TAB - sb.length() % TAB;
                sb.append(" ".repeat(spaces));
            } else {
                sb.append(c);
            }
        }
        if (colOf != null) {
            colOf[s.length()] = sb.length();
        }
        return sb.toString();
    }

    private static boolean hasWideChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x1100 && (c <= 0x115F || (c >= 0x2E80 && c <= 0xA4CF)
                    || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60)
                    || (c >= 0xFFE0 && c <= 0xFFE6) || Character.isSurrogate(c))) {
                return true;
            }
        }
        return false;
    }

    private final class Canvas extends JComponent {
        Canvas() {
            setOpaque(true);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    layoutDirty = true;
                    if (follow) {
                        scrollToBottom();
                    }
                    updateScrollbars();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(600, 300);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                layoutDirty = true; // the source may have changed without telling us
                ensureLayout();
                paintRows(g);
            } finally {
                g.dispose();
            }
            overview.repaint(); // the view position marker follows scrolling
            paintListeners.forEach(Runnable::run);
        }

        private void paintRows(Graphics2D g) {
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof Map<?, ?> m) {
                g.addRenderingHints(m);
            } else {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            g.setFont(font);
            int w = getWidth();
            int h = getHeight();
            int gutter = gutterWidth();
            Color fg = Theme.foreground();
            g.setColor(Theme.background());
            g.fillRect(0, 0, w, h);
            g.setColor(Theme.gutterBackground());
            g.fillRect(0, 0, gutter - 6, h);

            boolean anySel = hasSelection();
            int[] sel = anySel ? selectionBounds() : null;
            boolean focused = isFocusOwner();
            Color selColor = focused ? Theme.selection() : Theme.selectionInactive();
            int firstCol = hOffset / charWidth;
            int visibleCols = (w - gutter) / charWidth + 2;
            int newMaxCols = maxColumns;
            Matcher mm = matchPattern == null ? null : matchPattern.matcher("");
            Matcher[] rm = new Matcher[rules.size()];
            for (int i = 0; i < rm.length; i++) {
                rm[i] = rules.get(i).pattern().matcher("");
            }

            // per line: colors and match ranges (in display columns)
            int lineCount = shownLines.length;
            Color[] lineFg = new Color[lineCount];
            Color[] lineBg = new Color[lineCount];
            int[][] matches = new int[lineCount][];
            for (int k = 0; k < lineCount; k++) {
                Line l = shownLines[k];
                newMaxCols = Math.max(newMaxCols, l.text.length());
                Color lf = fg;
                Color lb = null;
                boolean dim = source.dimmed(l.row);
                for (int r = 0; r < rm.length && !dim; r++) {
                    if (rm[r].reset(l.raw).find()) {
                        HighlightRule rule = rules.get(r);
                        if (rule.foreground() != null) {
                            lf = rule.foreground();
                        }
                        lb = rule.background();
                        break;
                    }
                }
                if (dim) {
                    lf = Theme.dimmedForeground();
                }
                boolean inSel = anySel && l.row >= sel[0] && l.row <= sel[2];
                if (inSel && lineMode) {
                    lb = selColor;
                } else if (lb == null && l.row == lead && !anySel) {
                    lb = Theme.currentLine();
                }
                lineFg[k] = lf;
                lineBg[k] = lb;
                if (mm != null) {
                    List<Integer> ranges = new ArrayList<>();
                    mm.reset(l.raw);
                    int guard = 0;
                    while (mm.find() && guard++ < 1000) {
                        int s = mm.start();
                        int e = Math.max(mm.end(), s + 1);
                        ranges.add(l.col(s));
                        ranges.add(l.col(Math.min(e, l.raw.length())));
                        if (mm.end() == mm.start() && mm.end() >= l.raw.length()) {
                            break;
                        }
                    }
                    int[] a = new int[ranges.size()];
                    for (int i = 0; i < a.length; i++) {
                        a[i] = ranges.get(i);
                    }
                    matches[k] = a;
                }
            }

            Graphics2D gt = (Graphics2D) g.create(gutter - 4, 0, w - gutter + 4, h);
            gt.translate(-(gutter - 4), 0);
            try {
                for (int i = 0; i < shownCount; i++) {
                    int k = shownLine[i];
                    int seg = shownSeg[i];
                    Line l = shownLines[k];
                    int y = i * rowHeight;
                    int s0 = l.segStart[seg];
                    int s1 = l.segEnd(seg);
                    if (lineBg[k] != null) {
                        g.setColor(lineBg[k]);
                        g.fillRect(gutter - 6, y, w, rowHeight);
                    }
                    if (seg == 0 && l.row > 0 && source.gapBefore(l.row)) {
                        g.setColor(Theme.gutterForeground());
                        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                1f, new float[] {3f, 3f}, 0f));
                        g.drawLine(0, y, w, y);
                        g.setStroke(new BasicStroke(1f));
                    }
                    if (columns != null) {
                        paintColumnsRow(gt, l, y, lineFg[k], matches[k]);
                        paintGutter(g, l, seg, i, y, gutter, fg);
                        continue;
                    }
                    // search matches
                    if (matches[k] != null) {
                        gt.setColor(Theme.match());
                        int[] a = matches[k];
                        for (int m = 0; m + 1 < a.length; m += 2) {
                            int cs = Math.max(a[m], s0);
                            int ce = Math.min(a[m + 1], s1);
                            if (ce > cs || (a[m] == a[m + 1] && a[m] >= s0 && a[m] < s1)) {
                                int x0 = l.x(seg, cs);
                                int x1 = l.x(seg, Math.max(ce, cs));
                                gt.fillRoundRect(x0, y + 1, Math.max(2, x1 - x0), rowHeight - 2, 4, 4);
                            }
                        }
                    }
                    // selected characters
                    boolean inSel = anySel && l.row >= sel[0] && l.row <= sel[2];
                    if (inSel && !lineMode) {
                        int c0 = l.col(l.row == sel[0] ? Math.min(sel[1], l.raw.length()) : 0);
                        int c1 = l.col(l.row == sel[2] ? Math.min(sel[3], l.raw.length()) : l.raw.length());
                        int a0 = Math.max(c0, s0);
                        int a1 = Math.min(c1, s1);
                        boolean lastSeg = seg == l.segCount() - 1;
                        if (a1 > a0 || (lastSeg && l.row < sel[2] && c1 >= s0)) {
                            int x0 = l.x(seg, a0);
                            int x1 = l.x(seg, Math.max(a0, a1));
                            if (lastSeg && l.row < sel[2]) {
                                x1 += charWidth / 2; // the line break is selected too
                            }
                            if (x1 > x0) {
                                gt.setColor(selColor);
                                gt.fillRect(x0, y, x1 - x0, rowHeight);
                            }
                        }
                    }
                    // text
                    gt.setColor(lineFg[k]);
                    if (l.wide || wrap) {
                        if (s1 > s0) {
                            gt.drawString(l.text.substring(s0, s1), l.segX(seg), y + ascent);
                        }
                    } else if (firstCol < l.text.length()) {
                        int end = Math.min(l.text.length(), firstCol + visibleCols);
                        gt.drawString(l.text.substring(firstCol, end),
                                l.segX(seg) + firstCol * charWidth, y + ascent);
                    }
                    paintGutter(g, l, seg, i, y, gutter, fg);
                }
            } finally {
                gt.dispose();
            }
            if (columns != null) {
                newMaxCols = Math.max(newMaxCols, columns.fixedWidth(charWidth) / charWidth + 200);
            }
            if (newMaxCols != maxColumns && !wrap) {
                maxColumns = newMaxCols;
                SwingUtilities.invokeLater(LogView.this::updateScrollbars);
            }
            header.repaint();
        }

        /** Line number, markers and the current-line bar for one display row. */
        private void paintGutter(Graphics2D g, Line l, int seg, int i, int y, int gutter, Color fg) {
            // gutter: line number on the first segment; a hook on continuations
            if (seg == 0) {
                long lineNo = source.lineNumber(l.row);
                String num = lineNo < 0 ? "" : Long.toString(lineNo + 1);
                g.setColor(l.row == lead ? fg : Theme.gutterForeground());
                g.drawString(num, gutter - 14 - metrics.stringWidth(num), y + ascent);
                Color marker = source.marker(l.row);
                if (marker != null) {
                    g.setColor(marker);
                    int d = Math.max(6, rowHeight - 10);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.fillOval(4, y + (rowHeight - d) / 2, d, d);
                }
            } else {
                g.setColor(Theme.gutterForeground());
                int cx = gutter - 16;
                int cy = y + rowHeight / 2;
                g.drawLine(cx, cy - 4, cx, cy);
                g.drawLine(cx, cy, cx + 5, cy);
                g.drawLine(cx + 3, cy - 2, cx + 5, cy);
                g.drawLine(cx + 3, cy + 2, cx + 5, cy);
            }
            Color tag = source.tag(l.row);
            if (tag != null) {
                g.setColor(tag);
                g.fillRect(0, y, 4, rowHeight);
            }
            if (l.row == lead && seg == 0) {
                g.setColor(Theme.marker());
                g.fillRect(gutter - 6, y, 3, rowHeight * Math.max(1,
                        Math.min(l.segCount(), shownCount - i)));
            }
        }

        /** One line as cells under the column header; other lines start at the message column. */
        private void paintColumnsRow(Graphics2D gt, Line l, int y, Color lineFg, int[] matchCols) {
            ColumnLayout cl = columns;
            int x0 = gutterWidth() - hOffset;
            int[] starts = new int[cl.format().columns().size()];
            String[] cells = cl.format().split(l.raw, starts);
            if (cells == null) {
                // a continuation line (stack trace): under the message
                int x = x0 + cl.messageX(charWidth);
                gt.setColor(lineFg);
                gt.drawString(l.text, x, y + ascent);
                return;
            }
            int x = x0;
            int[] visible = cl.visible();
            for (int c = 0; c < visible.length; c++) {
                int col = visible[c];
                boolean last = c == visible.length - 1;
                int w = last ? Integer.MAX_VALUE / 4 : cl.width(c, charWidth);
                String v = cells[col] == null ? "" : cells[col].replace('\t', ' ');
                Graphics2D cg = (Graphics2D) gt.create();
                try {
                    if (!last) {
                        cg.clipRect(x, y, Math.max(0, w - charWidth), rowHeight);
                    }
                    if (matchCols != null && starts[col] >= 0) {
                        cg.setColor(Theme.match());
                        int s0 = starts[col];
                        int s1 = s0 + v.length();
                        for (int m = 0; m + 1 < matchCols.length; m += 2) {
                            int a = Math.max(l.rawIndexOfCol(matchCols[m]), s0);
                            int b = Math.min(l.rawIndexOfCol(matchCols[m + 1]), s1);
                            if (b > a) {
                                int xa = x + metrics.stringWidth(v.substring(0, a - s0));
                                int xb = x + metrics.stringWidth(v.substring(0, b - s0));
                                cg.fillRoundRect(xa, y + 1, Math.max(2, xb - xa), rowHeight - 2, 4, 4);
                            }
                        }
                    }
                    cg.setColor(cl.isLevel(col) ? levelColor(v, lineFg) : lineFg);
                    cg.drawString(v, x, y + ascent);
                } finally {
                    cg.dispose();
                }
                if (!last) {
                    gt.setColor(Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY));
                    gt.drawLine(x + w - charWidth / 2, y, x + w - charWidth / 2, y + rowHeight);
                }
                x += w;
            }
        }

        private Color levelColor(String level, Color fallback) {
            return switch (level.trim()) {
                case "ERROR", "FATAL", "SEVERE", "CRITICAL" -> Theme.errorMark();
                case "WARN", "WARNING" -> new Color(0xC27C00);
                case "DEBUG", "TRACE" -> Theme.gutterForeground();
                default -> fallback;
            };
        }
    }

    /** The column titles above the rows; drag an edge to resize, right-click for more. */
    private final class ColumnHeader extends JComponent {
        private int dragging = -1;
        private int dragX;
        private int dragWidth;

        ColumnHeader() {
            setOpaque(true);
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (columns == null) {
                        return;
                    }
                    if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                        if (columnMenu != null) {
                            String col = columnAt(e.getX());
                            if (col != null) {
                                columnMenu.accept(col, e);
                            }
                        }
                        return;
                    }
                    dragging = edgeAt(e.getX());
                    if (dragging >= 0) {
                        dragX = e.getX();
                        dragWidth = columns.width(dragging, charWidth);
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging >= 0 && columns != null) {
                        columns.setWidth(dragging, Math.max(charWidth * 3, dragWidth + e.getX() - dragX), charWidth);
                        repaint();
                        changed();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragging = -1;
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(edgeAt(e.getX()) >= 0
                            ? Cursor.E_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            setToolTipText("Drag an edge to resize a column · right-click a column to count its values");
        }

        /** Index of the column whose right edge is near x, or -1. */
        private int edgeAt(int x) {
            if (columns == null) {
                return -1;
            }
            int cx = gutterWidth() - hOffset;
            int[] visible = columns.visible();
            for (int c = 0; c < visible.length - 1; c++) {
                cx += columns.width(c, charWidth);
                if (Math.abs(x - (cx - charWidth / 2)) <= 4) {
                    return c;
                }
            }
            return -1;
        }

        private String columnAt(int x) {
            int cx = gutterWidth() - hOffset;
            int[] visible = columns.visible();
            for (int c = 0; c < visible.length; c++) {
                int w = c == visible.length - 1 ? Integer.MAX_VALUE / 4 : columns.width(c, charWidth);
                if (x >= cx && x < cx + w) {
                    return columns.format().columns().get(visible[c]);
                }
                cx += w;
            }
            return null;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(100, rowHeight + 6);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
                if (hints instanceof Map<?, ?> mm) {
                    g.addRenderingHints(mm);
                }
                g.setColor(Theme.gutterBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY));
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                if (columns == null) {
                    return;
                }
                g.setFont(getFont().deriveFont(Font.BOLD));
                int gutter = gutterWidth();
                g.clipRect(gutter - 4, 0, getWidth(), getHeight());
                int x = gutter - hOffset;
                int[] visible = columns.visible();
                java.awt.FontMetrics fm = g.getFontMetrics();
                for (int c = 0; c < visible.length; c++) {
                    String name = columns.title(visible[c]);
                    g.setColor(Theme.foreground());
                    g.drawString(name, x, (getHeight() + fm.getAscent()) / 2 - 2);
                    if (c < visible.length - 1) {
                        int w = columns.width(c, charWidth);
                        g.setColor(Theme.gutterForeground());
                        g.drawLine(x + w - charWidth / 2, 4, x + w - charWidth / 2, getHeight() - 4);
                        x += w;
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }
}
