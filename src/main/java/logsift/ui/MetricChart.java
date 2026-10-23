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
import java.util.Arrays;
import java.util.function.LongConsumer;
import javax.swing.JComponent;
import logsift.core.Numbers;
import logsift.core.TimeFormat;

/**
 * A measurement over time: every value as a dot (far above the median in orange), the 95th
 * percentile of each stretch of time as a line, and things that never ended as red marks along
 * the top. Hover for the value; click a dot to go to its line.
 */
final class MetricChart extends JComponent {

    private long[] times = new long[0];
    private double[] values = new double[0];
    private long[] lines = new long[0];
    private long[] openTimes = new long[0]; // things that started and never ended
    private long[] openLines = new long[0];
    private Numbers.Unit unit = Numbers.Unit.NONE;
    private String empty = "Select a measurement.";
    private double median;
    private double top;
    private long first;
    private long last;
    private boolean byIndex; // no times: by order
    private final LongConsumer goTo;

    private static final int LEFT = 64;
    private static final int RIGHT = 12;
    private static final int TOP = 14;
    private static final int BOTTOM = 22;

    MetricChart(LongConsumer goTo) {
        this.goTo = goTo;
        setPreferredSize(new Dimension(400, 180));
        setToolTipText("");
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = nearest(e.getX(), e.getY());
                if (i >= 0) {
                    goTo.accept(i < values.length ? lines[i] : openLines[i - values.length]);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(java.awt.Cursor.getPredefinedCursor(nearest(e.getX(), e.getY()) >= 0
                        ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    /** Shows values over time ({@code open}: start times of things that never ended, may be empty). */
    void show(long[] times, double[] values, long[] lines, Numbers.Unit unit, long[] open, long[] openLines) {
        this.times = times;
        this.values = values;
        this.lines = lines;
        this.unit = unit;
        this.openTimes = open;
        this.openLines = openLines;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        median = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
        double p99 = sorted.length == 0 ? 1 : sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.995))];
        double max = sorted.length == 0 ? 1 : sorted[sorted.length - 1];
        top = niceCeil(max <= p99 * 1.5 ? max : p99 * 1.2);
        first = Long.MAX_VALUE;
        last = Long.MIN_VALUE;
        for (long[] ts : new long[][] {times, open}) {
            for (long t : ts) {
                if (t != TimeFormat.NONE) {
                    first = Math.min(first, t);
                    last = Math.max(last, t);
                }
            }
        }
        byIndex = first >= last;
        repaint();
    }

    void clear(String message) {
        empty = message;
        show(new long[0], new double[0], new long[0], Numbers.Unit.NONE, new long[0], new long[0]);
    }

    private static double niceCeil(double v) {
        if (v <= 0) {
            return 1;
        }
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[] {1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) {
            if (m * p >= v) {
                return m * p;
            }
        }
        return 10 * p;
    }

    private double x(int i, long t) {
        int w = getWidth() - LEFT - RIGHT;
        if (byIndex) {
            return LEFT + (values.length <= 1 ? w / 2.0 : (double) i / (values.length - 1) * w);
        }
        return LEFT + (double) (t - first) / (last - first) * w;
    }

    private double y(double v) {
        int h = getHeight() - TOP - BOTTOM;
        return TOP + h - Math.min(1, v / top) * h;
    }

    /** The dot (or never-ended mark) under the mouse, or -1. */
    private int nearest(int mx, int my) {
        int best = -1;
        double bestD = 7 * 7;
        for (int i = 0; i < values.length; i++) {
            if (!byIndex && times[i] == TimeFormat.NONE) {
                continue;
            }
            double dx = x(i, times[i]) - mx;
            double dy = y(values[i]) - my;
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (!byIndex) {
            for (int i = 0; i < openTimes.length; i++) {
                double dx = x(0, openTimes[i]) - mx;
                double dy = TOP - my;
                if (dx * dx + dy * dy < bestD) {
                    bestD = dx * dx + dy * dy;
                    best = values.length + i;
                }
            }
        }
        return best;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int i = nearest(e.getX(), e.getY());
        if (i < 0) {
            return null;
        }
        if (i >= values.length) {
            return "Started " + TimelineBar.formatFull(openTimes[i - values.length]).substring(11, 23)
                    + " and never ended (click to go there)";
        }
        String when = times[i] == TimeFormat.NONE ? "" : TimelineBar.formatFull(times[i]).substring(11, 23) + "   ";
        return when + unit.format(values[i]) + "   (click to go to the line)";
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        Color grid = Theme.uiColor("Separator.foreground", new Color(0xDDDDDD));
        Color label = Theme.dimmedForeground();
        g.setFont(getFont().deriveFont(Font.PLAIN, Math.max(10f, getFont().getSize2D() - 2)));
        FontMetrics fm = g.getFontMetrics();
        if (values.length == 0 && openTimes.length == 0) {
            g.setColor(label);
            g.drawString(empty, LEFT, getHeight() / 2);
            g.dispose();
            return;
        }
        int w = getWidth() - LEFT - RIGHT;
        int h = getHeight() - TOP - BOTTOM;
        // value axis
        for (int k = 0; k <= 4; k++) {
            double v = top * k / 4;
            int yy = (int) Math.round(y(v));
            g.setColor(grid);
            g.drawLine(LEFT, yy, LEFT + w, yy);
            g.setColor(label);
            String s = v == 0 ? "0" : unit.format(v);
            g.drawString(s, LEFT - 6 - fm.stringWidth(s), yy + fm.getAscent() / 2 - 1);
        }
        // time axis
        if (!byIndex) {
            for (int k = 0; k <= 4; k++) {
                long t = first + (last - first) * k / 4;
                int xx = (int) Math.round(x(0, t));
                String s = TimelineBar.formatFull(t).substring(11, last - first < 60_000 ? 23 : 19);
                int sw = fm.stringWidth(s);
                g.drawString(s, Math.max(0, Math.min(getWidth() - sw, xx - sw / 2)), TOP + h + fm.getAscent() + 4);
            }
        }
        // the dots
        Color dot = Theme.isDark() ? new Color(96, 165, 250, 150) : new Color(37, 99, 235, 120);
        Color hot = Theme.isDark() ? new Color(251, 146, 60, 200) : new Color(217, 119, 6, 190);
        for (int i = 0; i < values.length; i++) {
            if (!byIndex && times[i] == TimeFormat.NONE) {
                continue;
            }
            double xx = x(i, times[i]);
            double yy = y(values[i]);
            g.setColor(median > 0 && values[i] >= 3 * median ? hot : dot);
            g.fillOval((int) Math.round(xx) - 2, (int) Math.round(yy) - 2, 4, 4);
            if (values[i] > top) { // above the chart: a mark at the top edge
                g.fillPolygon(new int[] {(int) xx - 3, (int) xx + 3, (int) xx}, new int[] {TOP + 4, TOP + 4, TOP - 1}, 3);
            }
        }
        // the 95th percentile over time
        if (!byIndex && values.length >= 40) {
            int buckets = Math.max(10, Math.min(80, w / 12));
            double[] p95 = new double[buckets];
            java.util.List<java.util.List<Double>> by = new java.util.ArrayList<>();
            for (int b = 0; b < buckets; b++) {
                by.add(new java.util.ArrayList<>());
            }
            for (int i = 0; i < values.length; i++) {
                if (times[i] != TimeFormat.NONE) {
                    int b = (int) Math.min(buckets - 1, (times[i] - first) * buckets / Math.max(1, last - first));
                    by.get(b).add(values[i]);
                }
            }
            g.setColor(Theme.isDark() ? new Color(0xF87171) : new Color(0xDC2626));
            g.setStroke(new BasicStroke(1.6f));
            int px = -1;
            int py = -1;
            for (int b = 0; b < buckets; b++) {
                java.util.List<Double> v = by.get(b);
                if (v.size() < 3) {
                    px = -1;
                    continue;
                }
                double[] s = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                p95[b] = s[Math.min(s.length - 1, (int) Math.ceil(s.length * 0.95) - 1)];
                int xx = (int) Math.round(LEFT + (b + 0.5) * w / buckets);
                int yy = (int) Math.round(y(p95[b]));
                if (px >= 0) {
                    g.drawLine(px, py, xx, yy);
                }
                px = xx;
                py = yy;
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(label);
            String legend = "— 95th percentile";
            g.drawString(legend, LEFT + w - fm.stringWidth(legend), TOP + fm.getAscent());
        }
        // things that never ended
        if (!byIndex && openTimes.length > 0) {
            g.setColor(Theme.isDark() ? new Color(0xF87171) : new Color(0xDC2626));
            for (long t : openTimes) {
                int xx = (int) Math.round(x(0, t));
                g.fillRect(xx - 1, TOP - 4, 3, 9);
            }
        }
        g.dispose();
    }
}
