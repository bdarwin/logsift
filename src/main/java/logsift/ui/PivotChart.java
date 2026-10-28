package logsift.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;

/**
 * A chart of a pivot table: categories (the table's rows) along one axis and a series per column
 * group or value, as columns, bars, lines, areas or a pie. Hover for the numbers; a click picks
 * the category (its row in the table).
 */
final class PivotChart extends JComponent {

    enum Type {
        COLUMN("Column"), STACKED_COLUMN("Stacked Column"), PERCENT_COLUMN("100% Stacked Column"), BAR("Bar"),
        STACKED_BAR("Stacked Bar"), LINE("Line"), AREA("Area"), STACKED_AREA("Stacked Area"), PIE("Pie"),
        DOUGHNUT("Doughnut");

        final String label;

        Type(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        boolean stacked() {
            return this == STACKED_COLUMN || this == PERCENT_COLUMN || this == STACKED_BAR || this == STACKED_AREA;
        }

        boolean horizontal() {
            return this == BAR || this == STACKED_BAR;
        }

        boolean round() {
            return this == PIE || this == DOUGHNUT;
        }
    }

    /** What the chart shows: categories, and a series of values per name (NaN: none). */
    record Data(String title, List<String> categories, List<String> series, double[][] values, boolean percent) {
        int size() {
            return categories.size();
        }
    }

    /** Colours that tell apart well, in light and dark mode. */
    static final Color[] PALETTE = {
        new Color(0x4E79A7), new Color(0xF28E2B), new Color(0x59A14F), new Color(0xE15759), new Color(0x76B7B2),
        new Color(0xEDC948), new Color(0xB07AA1), new Color(0xFF9DA7), new Color(0x9C755F), new Color(0xBAB0AC)
    };

    static Color color(int i) {
        Color c = PALETTE[i % PALETTE.length];
        if (i >= PALETTE.length) {
            c = i / PALETTE.length % 2 == 1 ? c.darker() : c.brighter();
        }
        return c;
    }

    private Data data = new Data("", List.of(), List.of(), new double[0][], false);
    private Type type = Type.COLUMN;
    private int hoverCat = -1;
    private int hoverSeries = -1;
    private final List<Hit> hits = new ArrayList<>();
    private IntConsumer onPick = i -> { };
    private String empty = "Add fields to Rows and Values to see a chart";

    private record Hit(Shape shape, int category, int series) {}

    PivotChart() {
        setOpaque(true);
        setPreferredSize(new Dimension(500, 300));
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Hit h = hitAt(e.getX(), e.getY());
                int c = h == null ? -1 : h.category;
                int s = h == null ? -1 : h.series;
                if (c != hoverCat || s != hoverSeries) {
                    hoverCat = c;
                    hoverSeries = s;
                    repaint();
                }
                setCursor(java.awt.Cursor.getPredefinedCursor(h == null ? java.awt.Cursor.DEFAULT_CURSOR
                        : java.awt.Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverCat = -1;
                hoverSeries = -1;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                Hit h = hitAt(e.getX(), e.getY());
                if (h != null) {
                    onPick.accept(h.category);
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    void setData(Data d) {
        data = d;
        hoverCat = -1;
        hoverSeries = -1;
        repaint();
    }

    Data data() {
        return data;
    }

    void setType(Type t) {
        type = t;
        repaint();
    }

    Type type() {
        return type;
    }

    void setEmptyText(String text) {
        empty = text;
        repaint();
    }

    /** Called with a category's index when it is clicked. */
    void onPick(IntConsumer pick) {
        onPick = pick;
    }

    private Hit hitAt(int x, int y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).shape.contains(x, y)) {
                return hits.get(i);
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Hit h = hitAt(e.getX(), e.getY());
        if (h == null) {
            return null;
        }
        StringBuilder b = new StringBuilder("<html><b>").append(esc(data.categories().get(h.category))).append("</b>");
        int ns = data.series().size();
        if (type.round()) {
            double total = 0;
            for (int c = 0; c < data.size(); c++) {
                total += positive(data.values()[0][c]);
            }
            double v = data.values()[0][h.category];
            b.append("<br>").append(esc(data.series().get(0))).append(": ").append(number(v))
                    .append(total > 0 ? String.format(Locale.ROOT, " (%.1f %%)", positive(v) * 100 / total) : "");
        } else {
            for (int s = 0; s < ns; s++) {
                double v = data.values()[s][h.category];
                String line = esc(data.series().get(s)) + ": " + number(v);
                b.append("<br>").append(s == h.series ? "<b>" + line + "</b>" : line);
            }
        }
        return b.append("</html>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    String number(double v) {
        if (Double.isNaN(v)) {
            return "–";
        }
        String s = PivotPanel.format(v);
        return data.percent() ? s + " %" : s;
    }

    private static double positive(double v) {
        return Double.isNaN(v) || v < 0 ? 0 : v;
    }

    /** The chart as a picture, for saving or copying. */
    BufferedImage image(int w, int h) {
        BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        paintChart(g, w, h);
        g.dispose();
        return img;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        paintChart(g, getWidth(), getHeight());
        g.dispose();
    }

    private void paintChart(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.background());
        g.fillRect(0, 0, w, h);
        hits.clear();
        Font base = getFont() != null ? getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        g.setFont(base);
        if (data.size() == 0 || data.series().isEmpty()) {
            g.setColor(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(empty, (w - fm.stringWidth(empty)) / 2, h / 2);
            return;
        }
        // the title
        g.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1f));
        g.setColor(Theme.foreground());
        FontMetrics tf = g.getFontMetrics();
        String title = clip(data.title(), tf, w - 24);
        g.drawString(title, (w - tf.stringWidth(title)) / 2, 8 + tf.getAscent());
        int top = 16 + tf.getHeight();
        g.setFont(base);
        // the legend, at the bottom
        int legendH = legend(g, w, h, true);
        Rectangle area = new Rectangle(12, top, w - 24, h - top - legendH - 8);
        if (type.round()) {
            round(g, area);
        } else {
            axes(g, area);
        }
        legend(g, w, h, false);
    }

    /** Draws the legend (or measures it): one swatch and name per series (pie: per category). */
    private int legend(Graphics2D g, int w, int h, boolean measure) {
        List<String> names = type.round() ? data.categories() : data.series();
        if (names.size() <= 1 && !type.round()) {
            return 0;
        }
        FontMetrics fm = g.getFontMetrics();
        int rowH = fm.getHeight() + 4;
        int x = 12;
        int rows = 1;
        int shown = Math.min(names.size(), type.round() ? 24 : 40);
        List<int[]> at = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            String n = clip(names.get(i), fm, 180);
            int iw = 14 + fm.stringWidth(n) + 14;
            if (x + iw > w - 12 && x > 12) {
                x = 12;
                rows++;
            }
            at.add(new int[] {x, rows - 1});
            x += iw;
        }
        int height = rows * rowH + 6;
        if (measure) {
            return Math.min(height, h / 3);
        }
        int y0 = h - height;
        for (int i = 0; i < shown; i++) {
            int[] p = at.get(i);
            int y = y0 + p[1] * rowH;
            if (y + rowH > h) {
                break;
            }
            boolean hot = type.round() ? hoverCat == i : hoverSeries == i;
            g.setColor(color(i));
            g.fillRoundRect(p[0], y + (rowH - 10) / 2, 10, 10, 3, 3);
            g.setColor(hot ? Theme.foreground() : Theme.uiColor("Label.disabledForeground", Color.GRAY).darker());
            g.drawString(clip(names.get(i), fm, 180), p[0] + 14, y + (rowH + fm.getAscent() - fm.getDescent()) / 2);
        }
        return height;
    }

    private void round(Graphics2D g, Rectangle area) {
        double[] v = data.values()[0];
        double total = 0;
        for (double x : v) {
            total += positive(x);
        }
        int d = Math.min(area.width, area.height) - 8;
        if (d < 20 || total <= 0) {
            return;
        }
        double cx = area.getCenterX();
        double cy = area.getCenterY();
        double start = 90;
        for (int c = 0; c < v.length; c++) {
            double ext = -positive(v[c]) * 360 / total;
            if (ext == 0) {
                continue;
            }
            double off = c == hoverCat ? 6 : 0;
            double mid = Math.toRadians(start + ext / 2);
            double ox = Math.cos(mid) * off;
            double oy = -Math.sin(mid) * off;
            Arc2D arc = new Arc2D.Double(cx - d / 2.0 + ox, cy - d / 2.0 + oy, d, d, start, ext, Arc2D.PIE);
            g.setColor(color(c));
            g.fill(arc);
            g.setColor(Theme.background());
            g.setStroke(new BasicStroke(1.5f));
            g.draw(arc);
            hits.add(new Hit(arc, c, 0));
            // a label on slices big enough
            double share = positive(v[c]) / total;
            if (share >= 0.05) {
                String label = String.format(Locale.ROOT, "%.0f%%", share * 100);
                double r = type == Type.DOUGHNUT ? d * 0.38 : d * 0.33;
                FontMetrics fm = g.getFontMetrics();
                g.setColor(Color.WHITE);
                g.drawString(label, (float) (cx + ox + Math.cos(mid) * r - fm.stringWidth(label) / 2.0),
                        (float) (cy + oy - Math.sin(mid) * r + fm.getAscent() / 2.0 - 1));
            }
            start += ext;
        }
        if (type == Type.DOUGHNUT) {
            double hole = d * 0.55;
            g.setColor(Theme.background());
            g.fill(new Ellipse2D.Double(cx - hole / 2, cy - hole / 2, hole, hole));
            g.setColor(Theme.foreground());
            g.setFont(g.getFont().deriveFont(Font.BOLD, g.getFont().getSize2D() + 3f));
            FontMetrics fm = g.getFontMetrics();
            String t = number(total);
            g.drawString(t, (float) (cx - fm.stringWidth(t) / 2.0), (float) (cy + fm.getAscent() / 2.0 - 2));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, g.getFont().getSize2D() - 3f));
        }
    }

    /** Columns, bars, lines and areas: a value axis with nice steps, the categories along the other. */
    private void axes(Graphics2D g, Rectangle area) {
        int ns = data.series().size();
        int nc = data.size();
        double[][] v = data.values();
        boolean pct = type == Type.PERCENT_COLUMN;
        double lo = 0;
        double hi = 0;
        double[] catTotal = new double[nc];
        for (int c = 0; c < nc; c++) {
            double pos = 0;
            double neg = 0;
            for (int s = 0; s < ns; s++) {
                double x = v[s][c];
                if (Double.isNaN(x)) {
                    continue;
                }
                if (type.stacked()) {
                    if (x >= 0) {
                        pos += x;
                    } else {
                        neg += x;
                    }
                } else {
                    hi = Math.max(hi, x);
                    lo = Math.min(lo, x);
                }
            }
            catTotal[c] = pos;
            if (type.stacked()) {
                hi = Math.max(hi, pos);
                lo = Math.min(lo, neg);
            }
        }
        if (pct) {
            hi = 100;
            lo = 0;
        }
        if (hi == lo) {
            hi = lo + 1;
        }
        double[] ticks = niceTicks(lo, hi, 6);
        lo = ticks[0];
        hi = ticks[ticks.length - 1];
        FontMetrics fm = g.getFontMetrics();
        boolean hor = type.horizontal();
        // room for the labels
        int valueLabelW = 0;
        for (double t : ticks) {
            valueLabelW = Math.max(valueLabelW, fm.stringWidth(tickLabel(t, pct)));
        }
        int catLabelW = 0;
        for (String c : data.categories()) {
            catLabelW = Math.max(catLabelW, fm.stringWidth(c));
        }
        catLabelW = Math.min(catLabelW, hor ? 160 : 400);
        int left;
        int bottom;
        boolean slanted = false;
        if (hor) {
            left = area.x + catLabelW + 8;
            bottom = area.y + area.height - fm.getHeight() - 4;
        } else {
            left = area.x + valueLabelW + 8;
            double slot = (double) (area.x + area.width - left) / Math.max(1, nc);
            slanted = catLabelW > slot - 6;
            int labelH = slanted ? (int) Math.min(120, catLabelW * 0.72 + fm.getHeight()) : fm.getHeight() + 4;
            bottom = area.y + area.height - labelH;
        }
        Rectangle plot = new Rectangle(left, area.y + 4, area.x + area.width - left - 4, bottom - area.y - 4);
        if (plot.width < 20 || plot.height < 20) {
            return;
        }
        Color grid = Theme.pick(new Color(0xE5E7EB), new Color(0x3A3D42));
        Color axisText = Theme.uiColor("Label.disabledForeground", Color.GRAY);
        // value grid
        for (double t : ticks) {
            double f = (t - lo) / (hi - lo);
            g.setColor(t == 0 ? Theme.pick(new Color(0x9CA3AF), new Color(0x6B7280)) : grid);
            g.setStroke(new BasicStroke(1f));
            String label = tickLabel(t, pct);
            if (hor) {
                int x = (int) Math.round(plot.x + f * plot.width);
                g.drawLine(x, plot.y, x, plot.y + plot.height);
                g.setColor(axisText);
                g.drawString(label, x - fm.stringWidth(label) / 2, plot.y + plot.height + fm.getAscent() + 4);
            } else {
                int y = (int) Math.round(plot.y + plot.height - f * plot.height);
                g.drawLine(plot.x, y, plot.x + plot.width, y);
                g.setColor(axisText);
                g.drawString(label, plot.x - 6 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
            }
        }
        double slot = (double) (hor ? plot.height : plot.width) / nc;
        // the hovered category's band
        if (hoverCat >= 0 && hoverCat < nc) {
            g.setColor(Theme.pick(new Color(0, 0, 0, 14), new Color(255, 255, 255, 18)));
            if (hor) {
                g.fill(new Rectangle2D.Double(plot.x, plot.y + hoverCat * slot, plot.width, slot));
            } else {
                g.fill(new Rectangle2D.Double(plot.x + hoverCat * slot, plot.y, slot, plot.height));
            }
        }
        // category labels
        g.setColor(axisText);
        int every = Math.max(1, (int) Math.ceil((hor ? fm.getHeight() : slanted ? fm.getHeight() : 1) / slot));
        for (int c = 0; c < nc; c += every) {
            String label = clip(data.categories().get(c), fm, hor ? catLabelW : slanted ? 160 : (int) slot - 4);
            if (hor) {
                int y = (int) Math.round(plot.y + (c + 0.5) * slot);
                g.drawString(label, plot.x - 6 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
            } else if (slanted) {
                Graphics2D r = (Graphics2D) g.create();
                double x = plot.x + (c + 0.5) * slot;
                r.translate(x + fm.getAscent() / 2.0, plot.y + plot.height + 6);
                r.rotate(-Math.PI / 4);
                r.drawString(label, -fm.stringWidth(label), 0);
                r.dispose();
            } else {
                double x = plot.x + (c + 0.5) * slot;
                g.drawString(label, (float) (x - fm.stringWidth(label) / 2.0), plot.y + plot.height + fm.getAscent() + 3);
            }
        }
        double min = lo;
        double max = hi;
        java.util.function.DoubleUnaryOperator pos = x -> hor ? plot.x + (x - min) / (max - min) * plot.width
                : plot.y + plot.height - (x - min) / (max - min) * plot.height;
        double zero = pos.applyAsDouble(Math.max(lo, Math.min(hi, 0)));
        switch (type) {
            case LINE, AREA, STACKED_AREA -> lines(g, plot, slot, pos, zero);
            default -> bars(g, slot, pos, zero, hor, pct, catTotal, plot);
        }
    }

    private void bars(Graphics2D g, double slot, java.util.function.DoubleUnaryOperator pos, double zero, boolean hor,
                      boolean pct, double[] catTotal, Rectangle plot) {
        int ns = data.series().size();
        int nc = data.size();
        double[][] v = data.values();
        double gap = Math.min(slot * 0.2, 14);
        for (int c = 0; c < nc; c++) {
            double start = (hor ? plot.y : plot.x) + c * slot + gap / 2;
            double band = slot - gap;
            double up = 0;
            double down = 0;
            for (int s = 0; s < ns; s++) {
                double x = v[s][c];
                if (Double.isNaN(x)) {
                    continue;
                }
                if (pct) {
                    x = catTotal[c] == 0 ? 0 : Math.max(0, x) * 100 / catTotal[c];
                }
                double a;
                double b;
                double p0;
                double width;
                if (type.stacked()) {
                    double from = x >= 0 ? up : down;
                    double to = from + x;
                    if (x >= 0) {
                        up = to;
                    } else {
                        down = to;
                    }
                    a = pos.applyAsDouble(from);
                    b = pos.applyAsDouble(to);
                    p0 = start;
                    width = band;
                } else {
                    a = zero;
                    b = pos.applyAsDouble(x);
                    width = band / ns;
                    p0 = start + s * width;
                }
                Rectangle2D r = hor ? new Rectangle2D.Double(Math.min(a, b), p0, Math.abs(b - a), Math.max(1, width - 1))
                        : new Rectangle2D.Double(p0, Math.min(a, b), Math.max(1, width - 1), Math.abs(b - a));
                Color col = color(s);
                boolean hot = c == hoverCat && (hoverSeries == s || type.stacked());
                g.setColor(hot ? col.brighter() : col);
                g.fill(r);
                hits.add(new Hit(hor ? new Rectangle2D.Double(r.getX(), p0, Math.max(r.getWidth(), 3), r.getHeight())
                        : new Rectangle2D.Double(p0, r.getY(), r.getWidth(), Math.max(r.getHeight(), 3)), c, s));
            }
        }
    }

    private void lines(Graphics2D g, Rectangle plot, double slot, java.util.function.DoubleUnaryOperator pos,
                       double zero) {
        int ns = data.series().size();
        int nc = data.size();
        double[][] v = data.values();
        double[] base = new double[nc];
        for (int s = 0; s < ns; s++) {
            Path2D line = new Path2D.Double();
            Path2D fill = new Path2D.Double();
            boolean started = false;
            double firstX = 0;
            double lastX = 0;
            List<double[]> lower = new ArrayList<>();
            for (int c = 0; c < nc; c++) {
                double x = v[s][c];
                if (Double.isNaN(x)) {
                    continue;
                }
                double px = plot.x + (c + 0.5) * slot;
                double from = type == Type.STACKED_AREA ? base[c] : 0;
                double py = pos.applyAsDouble(from + x);
                if (!started) {
                    line.moveTo(px, py);
                    fill.moveTo(px, py);
                    firstX = px;
                    started = true;
                } else {
                    line.lineTo(px, py);
                    fill.lineTo(px, py);
                }
                lower.add(new double[] {px, type == Type.STACKED_AREA ? pos.applyAsDouble(from) : zero});
                lastX = px;
                if (type == Type.STACKED_AREA) {
                    base[c] += x;
                }
                hits.add(new Hit(new Rectangle2D.Double(px - slot / 2, plot.y, slot, plot.height), c, s));
            }
            if (!started) {
                continue;
            }
            Color col = color(s);
            if (type != Type.LINE) {
                for (int i = lower.size() - 1; i >= 0; i--) {
                    fill.lineTo(lower.get(i)[0], lower.get(i)[1]);
                }
                fill.closePath();
                g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), type == Type.STACKED_AREA ? 170 : 70));
                g.fill(fill);
            }
            g.setColor(col);
            g.setStroke(new BasicStroke(hoverSeries == s ? 3f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(line);
            if (nc <= 60) {
                for (int c = 0; c < nc; c++) {
                    double x = v[s][c];
                    if (Double.isNaN(x)) {
                        continue;
                    }
                    double from = type == Type.STACKED_AREA ? base[c] - x : 0;
                    double px = plot.x + (c + 0.5) * slot;
                    double py = pos.applyAsDouble(from + x);
                    double r = c == hoverCat ? 4.5 : 3;
                    g.setColor(Theme.background());
                    g.fill(new Ellipse2D.Double(px - r - 1, py - r - 1, 2 * r + 2, 2 * r + 2));
                    g.setColor(col);
                    g.fill(new Ellipse2D.Double(px - r, py - r, 2 * r, 2 * r));
                }
            }
            if (firstX == lastX) {
                g.fill(new Ellipse2D.Double(firstX - 3, pos.applyAsDouble(v[s][0]) - 3, 6, 6));
            }
        }
    }

    private String tickLabel(double t, boolean pct) {
        String s = PivotPanel.format(t);
        return pct || data.percent() ? s + "%" : s;
    }

    /** About {@code count} round steps from {@code lo} to {@code hi} (1, 2, 2.5 or 5 times a power of ten). */
    static double[] niceTicks(double lo, double hi, int count) {
        double range = hi - lo;
        double rough = range / Math.max(1, count - 1);
        double mag = Math.pow(10, Math.floor(Math.log10(rough)));
        double step = mag;
        for (double m : new double[] {1, 2, 2.5, 5, 10}) {
            if (m * mag >= rough) {
                step = m * mag;
                break;
            }
        }
        double first = Math.floor(lo / step) * step;
        double last = Math.ceil(hi / step) * step;
        int n = (int) Math.round((last - first) / step) + 1;
        double[] out = new double[Math.max(2, n)];
        for (int i = 0; i < out.length; i++) {
            out[i] = first + i * step;
            if (Math.abs(out[i]) < step * 1e-9) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String clip(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) {
            return s;
        }
        String e = "…";
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n)) + fm.stringWidth(e) > w) {
            n--;
        }
        return s.substring(0, n) + e;
    }
}
