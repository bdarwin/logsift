package logsift.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.LongConsumer;
import javax.swing.JComponent;

/**
 * A chart over time with several series (dots, lines or bars), as GC logs need: the heap before
 * and after each collection, pause times, allocation rates. Hover for a value; click a point to
 * go to its line in the log; drag across the chart to zoom in on that time, double-click to see
 * it all again. Charts in a group zoom together.
 */
final class GcChart extends JComponent {

    enum Style { DOTS, LINE, BARS }

    /**
     * One series.
     *
     * @param lines the log line of each point (clicking goes there), or null
     */
    record Series(String name, Color color, Style style, double[] x, double[] y, long[] lines) {}

    private static final int LEFT = 70;
    private static final int RIGHT = 14;
    private static final int TOP = 24;
    private static final int BOTTOM = 24;

    private final List<Series> series = new ArrayList<>();
    private String title = "";
    private DoubleFunction<String> xLabel = x -> String.format("%.0f s", x);
    private DoubleFunction<String> yLabel = y -> String.format("%.0f", y);
    private double fullFrom;
    private double fullTo = 1;
    private double from;
    private double to = 1;
    private double yMax = 1;
    private double yMin;
    private boolean logY;
    private double yUnit = 1; // nice steps are multiples of this (1 MB for sizes)
    private int dragFrom = -1;
    private int dragTo = -1;
    private LongConsumer goTo = l -> { };
    private final List<GcChart> group = new ArrayList<>();
    private String empty = "Nothing to show.";

    GcChart() {
        setPreferredSize(new Dimension(600, 230));
        setToolTipText("");
        setOpaque(true);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getX() >= LEFT) {
                    dragFrom = e.getX();
                    dragTo = -1;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom >= 0) {
                    dragTo = Math.max(LEFT, Math.min(getWidth() - RIGHT, e.getX()));
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragFrom >= 0 && dragTo >= 0 && Math.abs(dragTo - dragFrom) > 6) {
                    double a = xOfPixel(Math.min(dragFrom, dragTo));
                    double b = xOfPixel(Math.max(dragFrom, dragTo));
                    zoomAll(a, b);
                }
                dragFrom = -1;
                dragTo = -1;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    zoomAll(fullFrom, fullTo);
                    return;
                }
                int[] hit = nearest(e.getX(), e.getY());
                if (hit != null) {
                    Series s = series.get(hit[0]);
                    if (s.lines() != null && s.lines()[hit[1]] >= 0) {
                        goTo.accept(s.lines()[hit[1]]);
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int[] hit = nearest(e.getX(), e.getY());
                boolean link = hit != null && series.get(hit[0]).lines() != null;
                setCursor(java.awt.Cursor.getPredefinedCursor(link ? java.awt.Cursor.HAND_CURSOR
                        : java.awt.Cursor.CROSSHAIR_CURSOR));
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    void setTitle(String t) {
        title = t;
        repaint();
    }

    void setEmptyText(String t) {
        empty = t;
    }

    void setLabels(DoubleFunction<String> x, DoubleFunction<String> y) {
        xLabel = x;
        yLabel = y;
    }

    /** Where clicking a point goes (its log line). */
    void onPoint(LongConsumer c) {
        goTo = c;
    }

    /** Steps of the y axis in multiples of this (1 MB for sizes, so 300 MB, not 286 MB). */
    void setYUnit(double unit) {
        yUnit = unit;
    }

    /** A log scale for y (pause times span 0.01 ms to seconds). */
    void setLogScale(boolean on) {
        logY = on;
        rescale();
        repaint();
    }

    /** Charts that zoom together with this one. */
    static void link(GcChart... charts) {
        for (GcChart c : charts) {
            c.group.clear();
            c.group.addAll(List.of(charts));
        }
    }

    void setSeries(List<Series> list, double xFrom, double xTo) {
        series.clear();
        series.addAll(list);
        fullFrom = xFrom;
        fullTo = Math.max(xFrom + 1e-6, xTo);
        from = fullFrom;
        to = fullTo;
        rescale();
        repaint();
    }

    private void zoomAll(double a, double b) {
        List<GcChart> all = group.isEmpty() ? List.of(this) : group;
        for (GcChart c : all) {
            c.from = Math.max(c.fullFrom, a);
            c.to = Math.min(c.fullTo, Math.max(a + 1e-6, b));
            c.rescale();
            c.repaint();
        }
    }

    /** The time shown, for tests. */
    double[] rangeForTest() {
        return new double[] {from, to};
    }

    private void rescale() {
        double max = 0;
        double min = Double.MAX_VALUE;
        for (Series s : series) {
            for (int i = 0; i < s.x().length; i++) {
                if (s.x()[i] >= from && s.x()[i] <= to && !Double.isNaN(s.y()[i])) {
                    max = Math.max(max, s.y()[i]);
                    if (s.y()[i] > 0) {
                        min = Math.min(min, s.y()[i]);
                    }
                }
            }
        }
        if (logY) {
            yMin = min == Double.MAX_VALUE ? 0.01 : Math.pow(10, Math.floor(Math.log10(min)));
            yMax = max <= 0 ? 1 : Math.pow(10, Math.ceil(Math.log10(max)));
            if (yMax <= yMin) {
                yMax = yMin * 10;
            }
        } else {
            yMin = 0;
            yMax = max <= 0 ? yUnit : nice(max * 1.05 / yUnit) * yUnit;
        }
    }

    private static double nice(double v) {
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[] {1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) {
            if (m * p >= v) {
                return m * p;
            }
        }
        return 10 * p;
    }

    private double xOfPixel(int px) {
        int w = Math.max(1, getWidth() - LEFT - RIGHT);
        return from + (px - LEFT) * (to - from) / w;
    }

    private int px(double x) {
        int w = Math.max(1, getWidth() - LEFT - RIGHT);
        return LEFT + (int) Math.round((x - from) / (to - from) * w);
    }

    private int py(double y) {
        int h = Math.max(1, getHeight() - TOP - BOTTOM);
        double f;
        if (logY) {
            double v = Math.max(yMin, y);
            f = (Math.log10(v) - Math.log10(yMin)) / (Math.log10(yMax) - Math.log10(yMin));
        } else {
            f = (y - yMin) / (yMax - yMin);
        }
        return TOP + h - (int) Math.round(Math.max(0, Math.min(1, f)) * h);
    }

    /** {series, index} of the point nearest to the mouse (within 8 pixels), or null. */
    private int[] nearest(int mx, int my) {
        int[] best = null;
        double bestD = 64;
        for (int s = 0; s < series.size(); s++) {
            Series ser = series.get(s);
            for (int i = 0; i < ser.x().length; i++) {
                double x = ser.x()[i];
                if (x < from || x > to || Double.isNaN(ser.y()[i])) {
                    continue;
                }
                double dx = px(x) - mx;
                if (Math.abs(dx) > 8) {
                    continue;
                }
                double dy = ser.style() == Style.BARS ? 0 : py(ser.y()[i]) - my;
                double d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = new int[] {s, i};
                }
            }
        }
        return best;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int[] hit = nearest(e.getX(), e.getY());
        if (hit == null) {
            return null;
        }
        Series s = series.get(hit[0]);
        return "<html><b>" + s.name() + "</b>: " + yLabel.apply(s.y()[hit[1]]) + "<br>"
                + xLabel.apply(s.x()[hit[1]]) + (s.lines() != null ? "<br><i>Click to see it in the log</i>" : "")
                + "</html>";
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Theme.uiColor("Panel.background", Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            Color fg = Theme.uiColor("Label.foreground", Color.BLACK);
            Color dim = Theme.uiColor("Label.disabledForeground", Color.GRAY);
            Color grid = Theme.isDark() ? new Color(0x3A3F45) : new Color(0xE5E7EB);
            Font small = getFont().deriveFont(getFont().getSize2D() - 1f);
            g.setFont(getFont().deriveFont(Font.BOLD));
            g.setColor(fg);
            g.drawString(title, LEFT, 16);
            g.setFont(small);
            FontMetrics fm = g.getFontMetrics();
            int w = getWidth() - LEFT - RIGHT;
            int h = getHeight() - TOP - BOTTOM;
            boolean any = series.stream().anyMatch(s -> s.x().length > 0);
            if (!any || w <= 10 || h <= 10) {
                g.setColor(dim);
                g.drawString(empty, LEFT, TOP + h / 2);
                return;
            }
            // legend
            int lx = getWidth() - RIGHT;
            for (int i = series.size() - 1; i >= 0; i--) {
                Series s = series.get(i);
                if (s.x().length == 0) {
                    continue;
                }
                int tw = fm.stringWidth(s.name());
                lx -= tw;
                g.setColor(dim);
                g.drawString(s.name(), lx, 16);
                lx -= 14;
                g.setColor(s.color());
                g.fillRoundRect(lx, 8, 10, 10, 3, 3);
                lx -= 12;
            }
            // y grid
            g.setStroke(new BasicStroke(1f));
            List<Double> ticks = new ArrayList<>();
            if (logY) {
                for (double v = yMin; v <= yMax * 1.0001; v *= 10) {
                    ticks.add(v);
                }
            } else {
                for (int i = 0; i <= 4; i++) {
                    ticks.add(yMin + (yMax - yMin) * i / 4);
                }
            }
            for (double v : ticks) {
                int y = py(v);
                g.setColor(grid);
                g.drawLine(LEFT, y, LEFT + w, y);
                g.setColor(dim);
                String t = yLabel.apply(v);
                g.drawString(t, LEFT - 6 - fm.stringWidth(t), y + fm.getAscent() / 2 - 1);
            }
            // x labels
            int n = Math.max(2, Math.min(8, w / 110));
            for (int i = 0; i <= n; i++) {
                double x = from + (to - from) * i / n;
                int p = px(x);
                String t = xLabel.apply(x);
                int tx = Math.max(LEFT, Math.min(getWidth() - RIGHT - fm.stringWidth(t), p - fm.stringWidth(t) / 2));
                g.setColor(dim);
                g.drawString(t, tx, getHeight() - 6);
                g.setColor(grid);
                g.drawLine(p, TOP + h, p, TOP + h + 3);
            }
            g.clipRect(LEFT, TOP - 4, w + 1, h + 5);
            for (Series s : series) {
                paintSeries(g, s, w);
            }
            if (dragFrom >= 0 && dragTo >= 0) {
                g.setColor(new Color(0x3B82F6, false).darker());
                Color sel = new Color(59, 130, 246, 50);
                g.setColor(sel);
                int a = Math.min(dragFrom, dragTo);
                g.fillRect(a, TOP, Math.abs(dragTo - dragFrom), h);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintSeries(Graphics2D g, Series s, int w) {
        g.setColor(s.color());
        double[] xs = s.x();
        double[] ys = s.y();
        int base = py(logY ? yMin : 0);
        switch (s.style()) {
            case DOTS -> {
                // many points: one dot per pixel column and height
                java.util.BitSet seen = new java.util.BitSet();
                int hgt = getHeight();
                for (int i = 0; i < xs.length; i++) {
                    if (xs[i] < from || xs[i] > to || Double.isNaN(ys[i])) {
                        continue;
                    }
                    int x = px(xs[i]);
                    int y = py(ys[i]);
                    int key = x * hgt + y;
                    if (key >= 0 && seen.get(key)) {
                        continue;
                    }
                    if (key >= 0) {
                        seen.set(key);
                    }
                    g.fillOval(x - 2, y - 2, 5, 5);
                }
            }
            case LINE -> {
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // per pixel column: the lowest and the highest value, so peaks stay visible
                int lastX = Integer.MIN_VALUE;
                int lo = 0;
                int hi = 0;
                int prevX = -1;
                int prevY = -1;
                for (int i = 0; i < xs.length; i++) {
                    if (Double.isNaN(ys[i]) || xs[i] < from && (i + 1 >= xs.length || xs[i + 1] < from)
                            || xs[i] > to && i > 0 && xs[i - 1] > to) {
                        continue;
                    }
                    int x = px(xs[i]);
                    int y = py(ys[i]);
                    if (x == lastX) {
                        lo = Math.max(lo, y);
                        hi = Math.min(hi, y);
                        g.drawLine(x, hi, x, lo);
                        prevY = y;
                        continue;
                    }
                    if (prevX >= 0) {
                        g.drawLine(prevX, prevY, x, y);
                    }
                    lastX = x;
                    lo = y;
                    hi = y;
                    prevX = x;
                    prevY = y;
                }
            }
            case BARS -> {
                double step = xs.length > 1 ? xs[1] - xs[0] : (to - from);
                for (int i = 0; i < xs.length; i++) {
                    if (xs[i] + step < from || xs[i] > to || Double.isNaN(ys[i]) || ys[i] <= 0) {
                        continue;
                    }
                    int x0 = px(xs[i]);
                    int x1 = Math.max(x0 + 1, px(xs[i] + step) - 1);
                    int y = py(ys[i]);
                    g.fillRect(x0, y, x1 - x0, Math.max(1, base - y));
                }
            }
            default -> { }
        }
        g.setStroke(new BasicStroke(1f));
    }
}
