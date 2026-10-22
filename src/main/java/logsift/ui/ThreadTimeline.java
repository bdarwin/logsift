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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;
import logsift.jvm.JThread;
import logsift.jvm.ThreadAnalysis;
import logsift.jvm.ThreadDump;

/**
 * Threads over time: a row per thread, a column per dump, each cell colored by the thread's
 * state then (green runnable, red blocked, blue/gray waiting; empty when it didn't exist). A
 * thread stuck in one place shows as a solid stripe; a cell with the same stack as the cell
 * before it has no gap. Click a cell to see that stack.
 */
final class ThreadTimeline extends JComponent implements Scrollable {
    private static final int ROW = 18;
    private static final int HEAD = 22;

    private List<ThreadDump> dumps = List.of();
    private List<String> keys = List.of();
    private List<String> names = List.of();
    private final BiConsumer<String, Integer> onPick;
    private String pickedKey;
    private int pickedDump = -1;
    private int nameWidth = 220;

    /** {@code onPick} gets a thread's key and the dump index when a cell is clicked. */
    ThreadTimeline(BiConsumer<String, Integer> onPick) {
        this.onPick = onPick;
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int[] c = cellAt(e.getX(), e.getY());
                if (c != null) {
                    pick(keys.get(c[0]), c[1]);
                }
            }
        });
    }

    /** Shows these threads (keys, in order) across the dumps. */
    void setData(List<ThreadDump> dumps, List<String> keys) {
        this.dumps = List.copyOf(dumps);
        this.keys = List.copyOf(keys);
        List<String> n = new ArrayList<>(keys.size());
        for (String k : keys) {
            JThread t = null;
            for (int i = dumps.size() - 1; i >= 0 && t == null; i--) {
                t = dumps.get(i).find(k);
            }
            n.add(t == null ? k : t.displayName());
        }
        names = n;
        FontMetrics fm = getFontMetrics(getFont());
        int w = 120;
        for (String s : names) {
            w = Math.max(w, fm.stringWidth(s) + 16);
        }
        nameWidth = Math.min(w, 320);
        revalidate();
        repaint();
    }

    void pick(String key, int dump) {
        pickedKey = key;
        pickedDump = dump;
        repaint();
        int row = keys.indexOf(key);
        if (row >= 0) {
            scrollRectToVisible(new Rectangle(0, HEAD + row * ROW - ROW, 10, ROW * 3));
        }
        onPick.accept(key, dump);
    }

    int rowsForTest() {
        return keys.size();
    }

    private int cellWidth() {
        int n = Math.max(1, dumps.size());
        int avail = Math.max(200, (getParent() == null ? 800 : getParent().getWidth()) - nameWidth - 12);
        return Math.max(14, Math.min(64, avail / n));
    }

    private int[] cellAt(int x, int y) {
        int row = (y - HEAD) / ROW;
        int col = (x - nameWidth) / cellWidth();
        if (y < HEAD || x < nameWidth || row < 0 || row >= keys.size() || col < 0 || col >= dumps.size()) {
            return null;
        }
        return new int[] {row, col};
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int[] c = cellAt(e.getX(), e.getY());
        if (c == null) {
            return null;
        }
        ThreadDump d = dumps.get(c[1]);
        JThread t = d.find(keys.get(c[0]));
        String when = "Dump " + (c[1] + 1) + (d.clock().isEmpty() ? "" : " (" + d.clock() + ")");
        if (t == null) {
            return "<html><b>" + ThreadStackView.esc(names.get(c[0])) + "</b><br>" + when + ": not running</html>";
        }
        return "<html><b>" + ThreadStackView.esc(t.displayName()) + "</b><br>" + when + ": "
                + ThreadStackView.esc(ThreadAnalysis.doing(t)) + "</html>";
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(nameWidth + cellWidth() * Math.max(1, dumps.size()) + 12, HEAD + keys.size() * ROW + 4);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        Rectangle clip = g.getClipBounds();
        Font f = getFont();
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int cw = cellWidth();
        // header: dump numbers and times
        g.setColor(Theme.dimmedForeground());
        g.drawString("Thread", 6, HEAD - 7);
        for (int c = 0; c < dumps.size(); c++) {
            String label = cw >= 56 && !dumps.get(c).clock().isEmpty() ? dumps.get(c).clock() : String.valueOf(c + 1);
            int x = nameWidth + c * cw + (cw - fm.stringWidth(label)) / 2;
            if (fm.stringWidth(label) <= cw || c % Math.max(1, (fm.stringWidth(label) + 6) / cw + 1) == 0) {
                g.drawString(label, x, HEAD - 7);
            }
        }
        int first = Math.max(0, (clip.y - HEAD) / ROW);
        int last = Math.min(keys.size() - 1, (clip.y + clip.height - HEAD) / ROW + 1);
        Color stripe = Theme.stripe();
        for (int r = first; r <= last; r++) {
            int y = HEAD + r * ROW;
            String key = keys.get(r);
            if (key.equals(pickedKey)) {
                g.setColor(Theme.selectionInactive());
                g.fillRect(0, y, getWidth(), ROW);
            } else if (r % 2 == 1 && stripe != null) {
                g.setColor(stripe);
                g.fillRect(0, y, getWidth(), ROW);
            }
            g.setColor(Theme.foreground());
            String name = names.get(r);
            while (fm.stringWidth(name) > nameWidth - 12 && name.length() > 4) {
                name = name.substring(0, name.length() - 2);
            }
            g.drawString(name.equals(names.get(r)) ? name : name + "…", 6, y + ROW - 5);
            String prevStack = null;
            for (int c = 0; c < dumps.size(); c++) {
                JThread t = dumps.get(c).find(key);
                int x = nameWidth + c * cw;
                if (t == null) {
                    prevStack = null;
                    continue;
                }
                String stack = t.stackKey();
                boolean same = stack.equals(prevStack);
                prevStack = stack;
                Color col = ThreadStackView.stateColor(t.state());
                if (t.isIdle()) {
                    col = new Color(col.getRed(), col.getGreen(), col.getBlue(), 110);
                }
                g.setColor(col);
                // joined to the cell before when nothing changed: a stuck thread is one bar
                int x0 = same ? x - 4 : x + 2;
                g.fillRoundRect(x0, y + 3, x + cw - 2 - x0, ROW - 6, 4, 4);
                if (key.equals(pickedKey) && c == pickedDump) {
                    g.setColor(Theme.foreground());
                    g.drawRoundRect(x + 1, y + 1, cw - 3, ROW - 3, 5, 5);
                }
            }
        }
        if (keys.isEmpty()) {
            g.setColor(Theme.dimmedForeground());
            g.drawString(dumps.size() < 2 ? "Take or open several dumps to see threads over time."
                    : "No threads match.", 8, HEAD + 18);
        }
        g.dispose();
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
        return ROW;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
        return o == SwingConstants.VERTICAL ? Math.max(ROW, r.height - ROW) : Math.max(20, r.width - 20);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() != null && getParent().getWidth() >= getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
