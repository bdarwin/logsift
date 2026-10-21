package logmaster.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;

/**
 * The highlight bar beside the scrollbar: the whole file squeezed into the bar's
 * height, with a colored mark wherever errors, warnings, filter matches or Find All hits are.
 * Clicking a mark jumps to that line.
 *
 * <p>Each pixel row of the bar stands for a range of lines; a binary search per pixel row and
 * mark kind tells whether to draw a mark there. So painting costs the same for 10 or 10 million
 * hits.
 */
final class OverviewBar extends JComponent {

    /** Sorted line numbers of one kind of mark. */
    interface Marks {
        /** First marked line at or after {@code line}, or -1. */
        long firstAtOrAfter(long line);

        /** Last marked line before {@code line}, or -1. */
        long lastBefore(long line);
    }

    record MarkSet(String name, Color color, Marks marks) {}

    /** What to show and how view rows map to file lines. */
    interface Model {
        /** Lanes from left to right; within a lane, earlier sets win. */
        List<List<MarkSet>> lanes();

        /** File line shown in view row {@code row}. */
        long lineOfRow(long row);

        /** End of the lines the view covers (exclusive). */
        long lineLimit();

        /** Whether the view shows this line (false for lines the filters hide). */
        boolean visible(long line);

        String lineText(long line);

        void goToLine(long line);
    }

    static final int WIDTH = 18;
    private static final int PAD = 2;
    private static final int SNAP_PIXELS = 4;

    private final LogView view;
    private Model model;

    OverviewBar(LogView view) {
        this.view = view;
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (model == null) {
                    return;
                }
                Hit hit = nearestMark(e.getY());
                if (hit != null) {
                    model.goToLine(hit.line);
                } else {
                    jumpTo(e.getY());
                }
                view.focusView();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (model != null) {
                    jumpTo(e.getY());
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    void setModel(Model model) {
        this.model = model;
        setVisible(model != null);
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, 100);
    }

    /** Bar y position of a view row (for tests). */
    int yOfRow(long row) {
        int n = view.source().rowCount();
        return PAD + (int) (row * barHeight() / Math.max(1, n));
    }

    private int barHeight() {
        return Math.max(1, getHeight() - 2 * PAD);
    }

    private void jumpTo(int y) {
        int n = view.source().rowCount();
        if (n > 0) {
            long row = (long) Math.max(0, Math.min(barHeight() - 1, y - PAD)) * n / barHeight();
            view.goToRow((int) row, true);
        }
    }

    /** Lines {@code [from, to)} covered by pixel row {@code y} of the bar; null if none. */
    private long[] linesAt(int y, int n, int h) {
        if (y < 0 || y >= h) {
            return null;
        }
        long r0 = (long) y * n / h;
        long r1 = Math.max(r0 + 1, (long) (y + 1) * n / h);
        if (r0 >= n) {
            return null;
        }
        long l0 = model.lineOfRow(r0);
        long l1 = r1 >= n ? model.lineLimit() : model.lineOfRow(r1);
        return new long[] {l0, Math.max(l0 + 1, l1)};
    }

    /** A visible marked line in {@code [from, to)}, or -1. */
    private long visibleMark(Marks m, long from, long to) {
        long l = m.firstAtOrAfter(from);
        for (int tries = 0; l >= 0 && l < to && tries < 16; tries++) {
            if (model.visible(l)) {
                return l;
            }
            l = m.firstAtOrAfter(l + 1);
        }
        return -1;
    }

    private record Hit(long line, MarkSet set) {}

    /** The mark closest to bar position y (within a few pixels), by lane order. */
    private Hit nearestMark(int y) {
        int n = view.source().rowCount();
        if (model == null || n == 0) {
            return null;
        }
        int h = barHeight();
        List<List<MarkSet>> lanes = model.lanes();
        for (int d = 0; d <= SNAP_PIXELS; d++) {
            for (int yy : d == 0 ? new int[] {y - PAD} : new int[] {y - PAD - d, y - PAD + d}) {
                long[] range = linesAt(yy, n, h);
                if (range == null) {
                    continue;
                }
                for (List<MarkSet> lane : lanes) {
                    for (MarkSet s : lane) {
                        long l = visibleMark(s.marks(), range[0], range[1]);
                        if (l >= 0) {
                            return new Hit(l, s);
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Hit hit = nearestMark(e.getY());
        if (hit == null) {
            return "<html>Highlight bar: the whole file at a glance.<br>"
                    + "<span style='color:#E5484D'>■</span> errors &nbsp;"
                    + "<span style='color:#F5A524'>■</span> warnings &nbsp;"
                    + "colored: filters and Find All hits.<br>Click a mark to jump to it.</html>";
        }
        String text = model.lineText(hit.line);
        if (text == null) {
            text = "";
        }
        if (text.length() > 160) {
            text = text.substring(0, 160) + "…";
        }
        text = text.replace("&", "&amp;").replace("<", "&lt;");
        return String.format("<html><b>%s</b> — line %,d<br><tt>%s</tt></html>",
                hit.set.name(), hit.line + 1, text);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            int w = getWidth();
            g.setColor(Theme.gutterBackground());
            g.fillRect(0, 0, w, getHeight());
            g.setColor(Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY));
            g.drawLine(0, 0, 0, getHeight());
            int n = view.source().rowCount();
            if (model == null || n == 0) {
                return;
            }
            int h = barHeight();
            List<List<MarkSet>> lanes = model.lanes().stream().filter(l -> !l.isEmpty()).toList();
            int laneCount = Math.max(1, lanes.size());
            int laneW = Math.max(2, (w - 3) / laneCount);
            for (int y = 0; y < h; y++) {
                long[] range = linesAt(y, n, h);
                if (range == null) {
                    break;
                }
                for (int li = 0; li < lanes.size(); li++) {
                    for (MarkSet s : lanes.get(li)) {
                        if (visibleMark(s.marks(), range[0], range[1]) >= 0) {
                            g.setColor(s.color());
                            g.fillRect(2 + li * laneW, PAD + y, laneW - 1, 2);
                            break;
                        }
                    }
                }
            }
            // where the view is
            int top = (int) ((long) view.topRow() * h / n);
            int size = Math.max(4, (int) ((long) view.visibleRows() * h / n));
            Color fg = Theme.foreground();
            g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 40));
            g.fillRect(1, PAD + top, w - 1, size);
            g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 110));
            g.drawRect(1, PAD + top, w - 2, size - 1);
        } finally {
            g.dispose();
        }
    }

    /** Marks from a growing hit list. */
    static Marks of(logmaster.core.HitList hits) {
        return new Marks() {
            @Override
            public long firstAtOrAfter(long line) {
                int i = hits.firstAtOrAfter(line);
                return i < hits.size() ? hits.get(i) : -1;
            }

            @Override
            public long lastBefore(long line) {
                int i = hits.firstAtOrAfter(line) - 1;
                return i >= 0 && i < hits.size() ? hits.get(i) : -1;
            }
        };
    }

    /** Marks from a filter result (the passing lines). */
    static Marks of(java.util.function.Supplier<logmaster.core.FilterResult> result) {
        return new Marks() {
            @Override
            public long firstAtOrAfter(long line) {
                logmaster.core.FilterResult r = result.get();
                long row = r.rowAtOrAfter(line);
                return row < r.count() ? r.lineAt(row) : -1;
            }

            @Override
            public long lastBefore(long line) {
                logmaster.core.FilterResult r = result.get();
                long row = r.rowAtOrAfter(line) - 1;
                return row >= 0 && row < r.count() ? r.lineAt(row) : -1;
            }
        };
    }
}
