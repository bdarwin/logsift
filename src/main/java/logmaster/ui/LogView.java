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
    private final List<Listener> listeners = new ArrayList<>();
    private RowSource source = emptySource();

    private Font font;
    private int rowHeight;
    private int charWidth;
    private int ascent;

    private int topRow;
    private int hOffset;
    private int maxColumns;
    private int anchor = -1;
    private int lead = -1;
    private boolean follow;
    private boolean adjusting;
    private Pattern matchPattern;
    private List<HighlightRule> rules = HighlightRule.defaults();
    private double wheelRemainder;
    private int dragDirection;
    private final Timer dragTimer = new Timer(40, e -> dragScroll());

    public LogView() {
        super(new BorderLayout());
        add(canvas, BorderLayout.CENTER);
        add(vbar, BorderLayout.EAST);
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
        rowsChanged();
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
        if (lead >= n) {
            anchor = lead = n - 1;
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

    /** Copies the selected rows to the clipboard. */
    public void copySelection() {
        if (lead < 0) {
            return;
        }
        int from = Math.min(anchor, lead);
        int to = Math.max(anchor, lead);
        int count = Math.min(to - from + 1, MAX_COPY_ROWS);
        StringBuilder sb = new StringBuilder();
        String[] buf = new String[Math.min(count, 4096)];
        for (int done = 0; done < count; done += buf.length) {
            int k = Math.min(buf.length, count - done);
            source.texts(from + done, k, buf);
            for (int i = 0; i < k; i++) {
                if (buf[i] != null) {
                    sb.append(buf[i]).append('\n');
                }
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);
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
        }
        ensureVisible(row);
        updateFollowFromPosition();
        canvas.repaint();
        int r = row;
        listeners.forEach(l -> l.selectionChanged(this, r));
    }

    private void dragScroll() {
        if (dragDirection != 0) {
            int target = dragDirection < 0 ? topRow - 1 : topRow + visibleRows();
            setTop(topRow + dragDirection);
            moveLead(target, true);
        }
    }

    // ---------------------------------------------------------------- input

    private void installMouse() {
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                int row = rowAt(e.getY());
                if (row < 0) {
                    return;
                }
                if (e.isPopupTrigger()) {
                    popup(e);
                    return;
                }
                if (e.getButton() == MouseEvent.BUTTON1) {
                    moveLead(row, e.isShiftDown());
                    if (e.getClickCount() == 2) {
                        listeners.forEach(l -> l.rowActivated(LogView.this, row));
                    }
                }
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
                    int row = rowAt(e.getY());
                    if (row >= 0) {
                        moveLead(row, true);
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
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(a -> copySelection());
        menu.add(copy);
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

            int selFrom = lead < 0 ? -1 : Math.min(anchor, lead);
            int selTo = lead < 0 ? -1 : Math.max(anchor, lead);
            boolean focused = isFocusOwner();
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
                for (int r = 0; r < rm.length; r++) {
                    if (rm[r].reset(raw).find()) {
                        HighlightRule rule = rules.get(r);
                        if (rule.foreground() != null) {
                            lineFg = rule.foreground();
                        }
                        lineBg = rule.background();
                        break;
                    }
                }
                boolean selected = row >= selFrom && row <= selTo;
                if (selected) {
                    lineBg = focused ? Theme.selection() : Theme.selectionInactive();
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
