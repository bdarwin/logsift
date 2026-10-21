package logmaster.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
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
import javax.swing.Timer;

/**
 * Virtual list of log lines. Only visible rows are read and painted, and the scrollbar counts
 * rows (not pixels), so files with hundreds of millions of lines scroll smoothly. Swing's own
 * JList/JTable measure in pixels and overflow around 100M rows.
 */
public final class LogView extends JPanel {

    /** Supplies the rows to show. Row numbers are 0-based. */
    public interface RowSource {
        int rowCount();

        /** 0-based line number in the file, shown in the gutter. */
        long lineNumber(int row);

        /** Fills {@code out[0..count)} with the text of rows {@code first..}; null if missing. */
        void texts(int first, int count, String[] out);

        /** Rows drawn grayed out (for example, lines the filters remove). */
        default boolean dimmed(int row) {
            return false;
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
    private static LogView lastFocused;

    private final Canvas canvas = new Canvas();
    private final JScrollBar vbar = new JScrollBar(JScrollBar.VERTICAL);
    private final JScrollBar hbar = new JScrollBar(JScrollBar.HORIZONTAL);
    private final OverviewBar overview = new OverviewBar(this);
    private final List<Listener> listeners = new ArrayList<>();
    private RowSource source = emptySource();

    private Font font;
    private int rowHeight;
    private int charWidth;
    private int ascent;

    private int topRow;
    private int hOffset;
    private int maxColumns;
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
    private java.util.function.BiConsumer<LogView, JPopupMenu> popupExtender;

    public LogView() {
        super(new BorderLayout());
        add(canvas, BorderLayout.CENTER);
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
                updateFollowFromPosition();
                canvas.repaint();
            }
        });
        hbar.addAdjustmentListener(e -> {
            if (!adjusting) {
                hOffset = e.getValue();
                canvas.repaint();
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

    /** Lets the owner add items to the right-click menu. */
    public void setPopupExtender(java.util.function.BiConsumer<LogView, JPopupMenu> extender) {
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
        FontMetrics fm = getFontMetrics(font);
        rowHeight = fm.getHeight() + 2;
        charWidth = Math.max(1, fm.charWidth('m'));
        ascent = fm.getAscent() + 1;
        vbar.setUnitIncrement(1);
        rowsChanged();
    }

    public float fontSize() {
        return font.getSize2D();
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
        if (follow) {
            topRow = Math.max(0, n - visibleRows());
        }
        updateScrollbars();
        canvas.repaint();
    }

    public int selectedRow() {
        return lead;
    }

    public int topRow() {
        return topRow;
    }

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
            setTop(row - visibleRows() / 2);
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
        return (row - topRow) * rowHeight + rowHeight / 2;
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

    // ---------------------------------------------------------------- scrolling

    private void scrollToBottom() {
        setTop(source.rowCount() - visibleRows());
    }

    private void setTop(int row) {
        int max = Math.max(0, source.rowCount() - visibleRows());
        topRow = Math.max(0, Math.min(max, row));
        updateScrollbars();
        canvas.repaint();
    }

    private void ensureVisible(int row) {
        int vis = visibleRows();
        if (row < topRow) {
            setTop(row);
        } else if (row >= topRow + vis) {
            setTop(row - vis + 1);
        }
    }

    private boolean atBottom() {
        return topRow + visibleRows() >= source.rowCount();
    }

    /** After a user scroll: being at the bottom turns follow on, scrolling up turns it off. */
    private void updateFollowFromPosition() {
        boolean bottom = atBottom();
        if (follow && !bottom) {
            follow = false;
            listeners.forEach(l -> l.followChanged(this, false));
        }
    }

    private void updateScrollbars() {
        adjusting = true;
        try {
            int n = source.rowCount();
            int vis = visibleRows();
            int max = Math.max(0, n - vis);
            topRow = Math.max(0, Math.min(max, topRow));
            vbar.setValues(topRow, Math.min(vis, Math.max(n, 1)), 0, Math.max(n, 1));
            vbar.setBlockIncrement(Math.max(1, vis - 1));
            vbar.setEnabled(n > vis);

            int textWidth = canvas.getWidth() - gutterWidth();
            int contentWidth = (maxColumns + 2) * charWidth;
            int hmax = Math.max(0, contentWidth - textWidth);
            hOffset = Math.max(0, Math.min(hmax, hOffset));
            hbar.setValues(hOffset, Math.max(1, textWidth), 0, Math.max(textWidth, contentWidth));
            hbar.setBlockIncrement(Math.max(charWidth, textWidth - 4 * charWidth));
            hbar.setVisible(hmax > 0);
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
            int target = dragDirection < 0 ? topRow - 1 : topRow + visibleRows();
            setTop(topRow + dragDirection);
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

    /** Character index in {@code row} nearest to canvas x (a caret position). */
    private int colAt(int row, int x) {
        String raw = textOf(row);
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        int[] colOf = raw.indexOf('\t') >= 0 ? new int[raw.length() + 1] : null;
        String text = expandTabs(raw, colOf);
        int rel = x - (gutterWidth() - hOffset);
        int dcol;
        if (hasWideChars(text)) {
            FontMetrics fm = canvas.getFontMetrics(font);
            dcol = text.length();
            int prev = 0;
            for (int i = 0; i < text.length(); i++) {
                int next = fm.stringWidth(text.substring(0, i + 1));
                if (rel < (prev + next) / 2) {
                    dcol = i;
                    break;
                }
                prev = next;
            }
        } else {
            dcol = Math.round((float) rel / charWidth);
        }
        dcol = Math.max(0, Math.min(text.length(), dcol));
        int idx = dcol;
        if (colOf != null) {
            idx = raw.length();
            for (int i = 0; i <= raw.length(); i++) {
                if (colOf[i] >= dcol) {
                    // nearer of the boundaries before and at dcol (tabs are several columns wide)
                    idx = i > 0 && dcol - colOf[i - 1] < colOf[i] - dcol ? i - 1 : i;
                    break;
                }
            }
        }
        if (idx > 0 && idx < raw.length() && Character.isLowSurrogate(raw.charAt(idx))) {
            idx--; // never split a surrogate pair
        }
        return idx;
    }

    private boolean inGutter(int x) {
        return x < gutterWidth() - 6;
    }

    /** Row under y, clamped to the existing rows; -1 if there are none. */
    private int rowAtClamped(int y) {
        int n = source.rowCount();
        if (n == 0) {
            return -1;
        }
        return Math.min(n - 1, topRow + Math.max(0, y) / rowHeight);
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
                int row = rowAt(e.getY());
                boolean belowLast = row < 0;
                row = rowAtClamped(e.getY());
                if (row < 0) {
                    return;
                }
                int clicks = e.getClickCount();
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
                    int[] w = wordAt(t == null ? "" : t, colAt(row, e.getX()));
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
                    int col = belowLast ? Integer.MAX_VALUE : colAt(row, e.getX());
                    selectTo(row, col, e.isShiftDown());
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                canvas.setCursor(java.awt.Cursor.getPredefinedCursor(inGutter(e.getX())
                        ? java.awt.Cursor.DEFAULT_CURSOR : java.awt.Cursor.TEXT_CURSOR));
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
                    if (dragUnit != BY_LINE) {
                        // scroll sideways when dragging past the left or right edge
                        if (e.getX() > canvas.getWidth() - charWidth) {
                            hbarScroll(charWidth * 2);
                        } else if (e.getX() < gutterWidth() && hOffset > 0) {
                            hbarScroll(-charWidth * 2);
                        }
                    }
                    int row = rowAt(e.getY());
                    if (row >= 0) {
                        dragTo(row, colAt(row, e.getX()));
                    } else if (source.rowCount() > 0) {
                        dragTo(source.rowCount() - 1, Integer.MAX_VALUE);
                    }
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double rot = e.getPreciseWheelRotation();
                if (e.isShiftDown()) {
                    hbarScroll((int) Math.round(rot * charWidth * 3));
                    return;
                }
                wheelRemainder += rot * 3;
                int rows = (int) wheelRemainder;
                wheelRemainder -= rows;
                if (rows != 0) {
                    setTop(topRow + rows);
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
        canvas.repaint();
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

    private int rowAt(int y) {
        int row = topRow + Math.max(0, y) / rowHeight;
        return row < source.rowCount() ? row : -1;
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
        int vis = visibleRows();
        int offsetInView = (lead >= 0 ? lead : topRow) - topRow;
        setTop(topRow + dir * (vis - 1));
        moveLead(topRow + Math.max(0, Math.min(vis - 1, offsetInView)), extend);
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
        int n = source.rowCount();
        long maxLine = n == 0 ? 1 : source.lineNumber(n - 1) + 1;
        int digits = Math.max(4, Long.toString(maxLine).length());
        return digits * charWidth + 16;
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

    /** X position of the boundary before char {@code c} of a (tab-expanded) line. */
    private int xOfChar(int c, int[] colOf, String text, boolean wide, FontMetrics fm, int textX) {
        int col = colOf == null ? c : colOf[c];
        col = Math.min(col, text.length());
        return wide ? textX + fm.stringWidth(text.substring(0, col)) : textX + col * charWidth;
    }

    private final class Canvas extends JComponent {
        private String[] texts = new String[0];

        Canvas() {
            setOpaque(true);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (follow) {
                        topRow = Math.max(0, source.rowCount() - visibleRows());
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
                paintRows(g);
            } finally {
                g.dispose();
            }
            overview.repaint(); // the view position marker follows scrolling
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
            FontMetrics fm = g.getFontMetrics();
            int w = getWidth();
            int h = getHeight();
            int gutter = gutterWidth();
            Color bg = Theme.background();
            Color fg = Theme.foreground();
            g.setColor(bg);
            g.fillRect(0, 0, w, h);
            g.setColor(Theme.gutterBackground());
            g.fillRect(0, 0, gutter - 6, h);

            int n = source.rowCount();
            int count = Math.max(0, Math.min(h / rowHeight + 1, n - topRow));
            if (texts.length < count) {
                texts = new String[count];
            }
            source.texts(topRow, count, texts);

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

            for (int i = 0; i < count; i++) {
                int row = topRow + i;
                int y = i * rowHeight;
                String raw = texts[i];
                if (raw == null) {
                    continue;
                }
                // line colors from the first matching rule
                Color lineFg = fg;
                Color lineBg = null;
                boolean dim = source.dimmed(row);
                for (int r = 0; r < rm.length && !dim; r++) {
                    if (rm[r].reset(raw).find()) {
                        HighlightRule rule = rules.get(r);
                        if (rule.foreground() != null) {
                            lineFg = rule.foreground();
                        }
                        lineBg = rule.background();
                        break;
                    }
                }
                if (dim) {
                    lineFg = Theme.dimmedForeground();
                }
                boolean inSel = anySel && row >= sel[0] && row <= sel[2];
                if (inSel && lineMode) {
                    lineBg = selColor;
                } else if (lineBg == null && row == lead && !anySel) {
                    lineBg = Theme.currentLine();
                }
                if (lineBg != null) {
                    g.setColor(lineBg);
                    g.fillRect(gutter - 6, y, w, rowHeight);
                }
                if (row == lead) {
                    g.setColor(Theme.marker());
                    g.fillRect(gutter - 6, y, 3, rowHeight);
                }

                int[] colOf = raw.indexOf('\t') >= 0 ? new int[raw.length() + 1] : null;
                String text = expandTabs(raw, colOf);
                newMaxCols = Math.max(newMaxCols, text.length());
                boolean wide = hasWideChars(text);
                int textX = gutter - hOffset;
                Graphics2D gt = (Graphics2D) g.create(gutter - 4, 0, w - gutter + 4, h);
                gt.translate(-(gutter - 4), 0);
                try {
                    // search matches
                    if (mm != null) {
                        mm.reset(raw);
                        gt.setColor(Theme.match());
                        int guard = 0;
                        while (mm.find() && guard++ < 1000) {
                            int s = mm.start();
                            int e = Math.max(mm.end(), s + 1);
                            int cs = colOf == null ? s : colOf[s];
                            int ce = colOf == null ? Math.min(e, raw.length()) : colOf[Math.min(e, raw.length())];
                            int x0;
                            int x1;
                            if (wide) {
                                x0 = textX + fm.stringWidth(text.substring(0, Math.min(cs, text.length())));
                                x1 = textX + fm.stringWidth(text.substring(0, Math.min(ce, text.length())));
                            } else {
                                x0 = textX + cs * charWidth;
                                x1 = textX + ce * charWidth;
                            }
                            gt.fillRoundRect(x0, y + 1, Math.max(2, x1 - x0), rowHeight - 2, 4, 4);
                            if (mm.end() == mm.start() && mm.end() >= raw.length()) {
                                break;
                            }
                        }
                    }
                    // selected characters
                    if (inSel && !lineMode) {
                        int c0 = row == sel[0] ? Math.min(sel[1], raw.length()) : 0;
                        int c1 = row == sel[2] ? Math.min(sel[3], raw.length()) : raw.length();
                        int x0 = xOfChar(c0, colOf, text, wide, fm, textX);
                        int x1 = xOfChar(c1, colOf, text, wide, fm, textX);
                        if (row < sel[2]) {
                            x1 += charWidth / 2; // the line break is selected too
                        }
                        if (x1 > x0) {
                            gt.setColor(selColor);
                            gt.fillRect(x0, y, x1 - x0, rowHeight);
                        }
                    }
                    gt.setColor(lineFg);
                    if (wide) {
                        gt.drawString(text, textX, y + ascent);
                    } else if (firstCol < text.length()) {
                        int end = Math.min(text.length(), firstCol + visibleCols);
                        gt.drawString(text.substring(firstCol, end), textX + firstCol * charWidth,
                                y + ascent);
                    }
                } finally {
                    gt.dispose();
                }

                // gutter line number
                String num = Long.toString(source.lineNumber(row) + 1);
                g.setColor(row == lead ? fg : Theme.gutterForeground());
                g.drawString(num, gutter - 12 - fm.stringWidth(num), y + ascent);
            }
            if (newMaxCols != maxColumns) {
                maxColumns = newMaxCols;
                javax.swing.SwingUtilities.invokeLater(LogView.this::updateScrollbars);
            }
        }
    }
}
