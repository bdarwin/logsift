package logsift.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import logsift.core.diff.TextDiff;

/**
 * One side of a text comparison: its lines on aligned rows (an empty, shaded row where the other
 * side has a line this one doesn't), with line numbers, the changed blocks coloured (green added,
 * gray removed, blue changed, red conflicts) and the words that differ marked stronger.
 */
final class DiffPane extends JComponent implements Scrollable {

    /** How a row is coloured. */
    enum Kind { SAME, ADDED, REMOVED, CHANGED, CONFLICT, FILLER }

    /** The rows a pane shows. */
    interface Rows {
        int size();

        /** The line number shown (0-based; -1: none, an empty row). */
        int line(int row);

        /** The text, or null for an empty row. */
        String text(int row);

        Kind kind(int row);

        /** The changed words of the row's text. */
        List<TextDiff.Span> words(int row);

        /** Whether a changed block starts at this row (a line is drawn above it). */
        default boolean blockStart(int row) {
            return false;
        }

        /** Whether a changed block ends at this row. */
        default boolean blockEnd(int row) {
            return false;
        }
    }

    private Rows rows;
    private final int[] selection = {-1, -1}; // first and last selected row
    private int anchor = -1;
    private IntConsumer onOpen = r -> { };
    private Runnable onSelect = () -> { };
    private int maxChars = 80;
    private int maxLine = 1; // the largest line number, for the gutter's width

    DiffPane(Rows rows) {
        this.rows = rows;
        for (int i = 0; i < rows.size(); i++) {
            maxLine = Math.max(maxLine, rows.line(i) + 1);
        }
        setOpaque(true);
        setFocusable(true);
        setFont(monoFont());
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int r = rowAt(e.getY());
                if (r < 0) {
                    return;
                }
                if (e.isShiftDown() && anchor >= 0) {
                    pick(anchor, r);
                } else {
                    anchor = r;
                    pick(r, r);
                }
                if (e.getClickCount() == 2) {
                    onOpen.accept(r);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int r = rowAt(e.getY());
                if (r >= 0 && anchor >= 0) {
                    pick(anchor, r);
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    static Font monoFont() {
        String fam = Theme.fontFamily() != null ? Theme.fontFamily() : Theme.autoFontFamily();
        return new Font(fam, Font.PLAIN, 13);
    }

    void setRows(Rows r) {
        rows = r;
        int longest = 10;
        for (int i = 0; i < r.size() && i < 200_000; i++) {
            String t = r.text(i);
            if (t != null) {
                longest = Math.max(longest, t.length());
            }
        }
        maxChars = Math.min(longest, 2000);
        int max = 1;
        for (int i = 0; i < r.size(); i++) {
            max = Math.max(max, r.line(i) + 1);
        }
        maxLine = max;
        if (selection[0] >= r.size()) {
            selection[0] = -1;
            selection[1] = -1;
        }
        revalidate();
        repaint();
    }

    Rows rows() {
        return rows;
    }

    void onOpen(IntConsumer open) {
        onOpen = open;
    }

    void onSelect(Runnable r) {
        onSelect = r;
    }

    int rowHeight() {
        return getFontMetrics(getFont()).getHeight() + 3;
    }

    int rowAt(int y) {
        int r = y / rowHeight();
        return r >= 0 && r < rows.size() ? r : -1;
    }

    /** The first and last selected rows, or -1. */
    int[] selection() {
        return selection.clone();
    }

    /** Selects rows (from the code: the listener isn't told). */
    void select(int a, int b) {
        selection[0] = Math.min(a, b);
        selection[1] = Math.max(a, b);
        repaint();
    }

    /** Selects rows people clicked or dragged over, and tells the listener. */
    private void pick(int a, int b) {
        select(a, b);
        onSelect.run();
    }

    void clearSelection() {
        selection[0] = -1;
        selection[1] = -1;
        anchor = -1;
        repaint();
    }

    /** The gutter: room for the line numbers. */
    int gutterWidth() {
        FontMetrics fm = getFontMetrics(getFont());
        return fm.stringWidth(Integer.toString(maxLine)) + 18;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(gutterWidth() + fm.charWidth('m') * (maxChars + 2) + 8, rows.size() * rowHeight());
    }

    static Color background(Kind k, boolean words) {
        return switch (k) {
            case SAME -> Theme.background();
            case ADDED -> words ? Theme.pick(new Color(0xB3E6C0), new Color(0x2F5A3A))
                    : Theme.pick(new Color(0xE3F6E8), new Color(0x22362A));
            case REMOVED -> words ? Theme.pick(new Color(0xD6D9DE), new Color(0x4A4D52))
                    : Theme.pick(new Color(0xEDEEF1), new Color(0x2E3034));
            case CHANGED -> words ? Theme.pick(new Color(0xBCD2FB), new Color(0x2F4670))
                    : Theme.pick(new Color(0xE3ECFD), new Color(0x252F45));
            case CONFLICT -> words ? Theme.pick(new Color(0xF7B9B9), new Color(0x6A2E2E))
                    : Theme.pick(new Color(0xFDE2E2), new Color(0x3D2525));
            case FILLER -> Theme.pick(new Color(0xF4F5F7), new Color(0x26282B));
        };
    }

    static Color edge(Kind k) {
        return switch (k) {
            case ADDED -> Theme.pick(new Color(0x7FCF95), new Color(0x3E7A50));
            case REMOVED -> Theme.pick(new Color(0xB9BDC4), new Color(0x5A5E64));
            case CHANGED -> Theme.pick(new Color(0x8FB2F2), new Color(0x4A6AA8));
            case CONFLICT -> Theme.pick(new Color(0xE58A8A), new Color(0x9A4444));
            default -> Theme.background();
        };
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle clip = g.getClipBounds();
        if (clip == null) {
            clip = new Rectangle(0, 0, getWidth(), getHeight());
        }
        g.setColor(Theme.background());
        g.fillRect(clip.x, clip.y, clip.width, clip.height);
        FontMetrics fm = g.getFontMetrics(getFont());
        g.setFont(getFont());
        int rh = rowHeight();
        int gw = gutterWidth();
        int first = Math.max(0, clip.y / rh);
        int last = Math.min(rows.size() - 1, (clip.y + clip.height) / rh);
        int cw = fm.charWidth('m');
        Color gutterBg = Theme.gutterBackground();
        Color selBg = Theme.selection();
        for (int r = first; r <= last; r++) {
            int y = r * rh;
            Kind k = rows.kind(r);
            String text = rows.text(r);
            boolean sel = r >= selection[0] && r <= selection[1] && selection[0] >= 0;
            Color bg = text == null ? (k == Kind.SAME ? Theme.background() : background(Kind.FILLER, false))
                    : background(k, false);
            g.setColor(sel ? blend(bg, selBg, 0.55) : bg);
            g.fillRect(gw, y, Math.max(getWidth(), clip.x + clip.width) - gw, rh);
            if (text == null && k != Kind.SAME) {
                // an empty row: faint diagonal lines, like a gap
                g.setColor(Theme.pick(new Color(0xE2E4E8), new Color(0x303236)));
                Graphics2D h = (Graphics2D) g.create(gw, y, Math.max(getWidth(), clip.x + clip.width) - gw, rh);
                for (int x = -rh; x < h.getClipBounds().width; x += 8) {
                    h.drawLine(x, rh, x + rh, 0);
                }
                h.dispose();
            }
            // the gutter
            g.setColor(k == Kind.SAME || text == null ? gutterBg : blend(gutterBg, edge(k), 0.35));
            g.fillRect(0, y, gw, rh);
            if (k != Kind.SAME && text != null) {
                g.setColor(edge(k));
                g.fillRect(gw - 3, y, 3, rh);
            }
            int line = rows.line(r);
            if (line >= 0) {
                String n = Integer.toString(line + 1);
                g.setColor(Theme.gutterForeground());
                g.drawString(n, gw - 8 - fm.stringWidth(n), y + fm.getAscent() + 1);
            }
            if (text != null) {
                int x0 = gw + 6;
                List<TextDiff.Span> words = rows.words(r);
                if (!words.isEmpty()) {
                    g.setColor(background(k, true));
                    for (TextDiff.Span s : words) {
                        int a = Math.min(s.start(), text.length());
                        int b = Math.min(s.end(), text.length());
                        int xa = x0 + width(fm, text, 0, a, cw);
                        int xb = x0 + width(fm, text, 0, b, cw);
                        g.fillRoundRect(xa - 1, y + 1, Math.max(3, xb - xa + 2), rh - 2, 4, 4);
                    }
                }
                g.setColor(Theme.foreground());
                g.drawString(expand(text), x0, y + fm.getAscent() + 1);
            }
            if (rows.blockStart(r)) {
                g.setColor(edge(k == Kind.SAME ? Kind.CHANGED : k));
                g.drawLine(gw, y, getWidth(), y);
            }
            if (rows.blockEnd(r)) {
                g.setColor(edge(k == Kind.SAME ? Kind.CHANGED : k));
                g.drawLine(gw, y + rh - 1, getWidth(), y + rh - 1);
            }
        }
        g.dispose();
    }

    /** Tabs as spaces (4), so columns line up. */
    static String expand(String s) {
        return s.indexOf('\t') < 0 ? s : s.replace("\t", "    ");
    }

    private static int width(FontMetrics fm, String s, int from, int to, int cw) {
        return fm.stringWidth(expand(s.substring(from, to)));
    }

    static Color blend(Color a, Color b, double f) {
        return new Color((int) (a.getRed() * (1 - f) + b.getRed() * f), (int) (a.getGreen() * (1 - f) + b.getGreen() * f),
                (int) (a.getBlue() * (1 - f) + b.getBlue() * f));
    }

    // ---------------------------------------------------------------- Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(400, 300);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? rowHeight() : getFontMetrics(getFont()).charWidth('m') * 4;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? Math.max(rowHeight(), visible.height - rowHeight() * 2)
                : visible.width / 2;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() != null && getParent().getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
}
