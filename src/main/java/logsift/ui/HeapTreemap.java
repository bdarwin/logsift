package logsift.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import logsift.heap.HeapFindings;
import logsift.heap.HeapMap;
import logsift.heap.HeapMap.Box;
import logsift.heap.HeapModel;

/**
 * The heap as a treemap: each box is an object, as big as what it holds (its retained size), with
 * the biggest objects it holds inside it. Click a box to see why it is alive; double-click to go
 * into it; the path at the top goes back out.
 */
final class HeapTreemap extends JPanel {
    private final HeapModel dump;
    private final IntConsumer select;
    private final List<Integer> path = new ArrayList<>(); // the nodes zoomed into, from the root
    private final JPanel crumbs = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
    private final Canvas canvas = new Canvas();
    private Box shown; // the focus and what it holds, or null while it is read
    private int selected = -1;
    private int generation;

    HeapTreemap(HeapModel dump, IntConsumer select) {
        super(new BorderLayout());
        this.dump = dump;
        this.select = select;
        crumbs.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        JLabel hint = new JLabel("Each box holds what is inside it. Click: why it is alive. Double-click: go in.");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        JPanel top = new JPanel(new BorderLayout());
        top.add(crumbs, BorderLayout.NORTH);
        top.add(hint, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        canvas.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "out");
        canvas.getActionMap().put("out", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing() && path.size() > 1) {
                    zoom(path.subList(0, path.size() - 1));
                }
            }
        });
        // read when first shown, not with the dump
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && path.isEmpty()) {
                zoom(List.of(dump.root()));
            }
        });
    }

    /** Shows what {@code chain}'s last node holds. */
    private void zoom(List<Integer> chain) {
        List<Integer> c = new ArrayList<>(chain);
        path.clear();
        path.addAll(c);
        shown = null;
        updateCrumbs();
        canvas.repaint();
        int gen = ++generation;
        int focus = c.get(c.size() - 1);
        Thread t = new Thread(() -> {
            Box b;
            try {
                b = dump.map(focus);
            } catch (Exception e) {
                b = new Box(focus, dump.retained(focus), "Could not read it: " + e.getMessage(), null, 1, List.of());
            }
            Box fb = b;
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) {
                    shown = fb;
                    canvas.repaint();
                }
            });
        }, "logsift-heap-map");
        t.setDaemon(true);
        t.start();
    }

    private void updateCrumbs() {
        crumbs.removeAll();
        for (int i = 0; i < path.size(); i++) {
            int v = path.get(i);
            String name = v == dump.root() ? "The heap" : HeapMap.shortName(dump.describe(v));
            if (i == path.size() - 1) {
                JLabel l = new JLabel(name + "  ·  " + HeapFindings.bytes(dump.retained(v)));
                l.setFont(l.getFont().deriveFont(Font.BOLD));
                crumbs.add(l);
            } else {
                JButton b = new JButton(name);
                b.putClientProperty("JButton.buttonType", "borderless");
                b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                List<Integer> to = new ArrayList<>(path.subList(0, i + 1));
                b.addActionListener(e -> zoom(to));
                crumbs.add(b);
                crumbs.add(new JLabel("›"));
            }
        }
        crumbs.revalidate();
        crumbs.repaint();
    }

    // ---------------------------------------------------------------- laying out

    /** Where a box was drawn. */
    private record Placed(Box box, Rectangle2D.Double r, int depth) { }

    private final List<Placed> placed = new ArrayList<>();

    /**
     * Squarified treemap (Bruls, Huizing, van Wijk): rows of boxes along the shorter side, each row
     * kept while it makes its boxes more square.
     */
    static List<Rectangle2D.Double> squarify(List<Long> sizes, Rectangle2D.Double area) {
        List<Rectangle2D.Double> out = new ArrayList<>();
        double sum = 0;
        for (long s : sizes) {
            sum += s;
        }
        if (sum <= 0 || area.width <= 0 || area.height <= 0) {
            for (int i = 0; i < sizes.size(); i++) {
                out.add(new Rectangle2D.Double(area.x, area.y, 0, 0));
            }
            return out;
        }
        double scale = area.width * area.height / sum;
        double x = area.x;
        double y = area.y;
        double w = area.width;
        double h = area.height;
        int i = 0;
        while (i < sizes.size()) {
            double side = Math.min(w, h);
            int end = i;
            double row = 0;
            double best = Double.MAX_VALUE;
            while (end < sizes.size()) {
                double a = sizes.get(end) * scale;
                double r = row + a;
                double worst = worst(sizes, i, end + 1, r, side, scale);
                if (worst > best) {
                    break;
                }
                best = worst;
                row = r;
                end++;
            }
            double thick = row / side;
            double at = 0;
            for (int k = i; k < end; k++) {
                double len = sizes.get(k) * scale / thick;
                if (w >= h) { // a column on the left
                    out.add(new Rectangle2D.Double(x, y + at, thick, len));
                } else { // a row at the top
                    out.add(new Rectangle2D.Double(x + at, y, len, thick));
                }
                at += len;
            }
            if (w >= h) {
                x += thick;
                w -= thick;
            } else {
                y += thick;
                h -= thick;
            }
            i = end;
        }
        return out;
    }

    private static double worst(List<Long> sizes, int from, int to, double row, double side, double scale) {
        double max = 0;
        double min = Double.MAX_VALUE;
        for (int k = from; k < to; k++) {
            double a = sizes.get(k) * scale;
            max = Math.max(max, a);
            min = Math.min(min, a);
        }
        double s2 = side * side;
        double r2 = row * row;
        return Math.max(s2 * max / r2, r2 / (s2 * min));
    }

    private void layoutBoxes(Box b, Rectangle2D.Double area, int depth) {
        List<Long> sizes = new ArrayList<>();
        for (Box k : b.inside()) {
            sizes.add(k.bytes());
        }
        List<Rectangle2D.Double> rs = squarify(sizes, area);
        for (int i = 0; i < rs.size(); i++) {
            Box k = b.inside().get(i);
            Rectangle2D.Double r = rs.get(i);
            placed.add(new Placed(k, r, depth));
            if (depth < 2 && !k.inside().isEmpty() && r.width > 60 && r.height > 50) {
                double head = 18;
                layoutBoxes(k, new Rectangle2D.Double(r.x + 3, r.y + head, r.width - 6, r.height - head - 3), 1);
            }
        }
    }

    /** The box a drawn box is inside, or null at the first level. */
    private Placed parentOf(Placed p) {
        for (Placed q : placed) {
            if (q.depth() == p.depth() - 1 && q.box().inside().contains(p.box()) && q.r().contains(p.r().getCenterX(),
                    p.r().getCenterY())) {
                return q;
            }
        }
        return null;
    }

    private Placed at(int x, int y) {
        Placed hit = null;
        for (Placed p : placed) {
            if (p.r().contains(x, y)) {
                hit = p; // the deepest one (drawn after its parent)
            }
        }
        return hit;
    }

    // ---------------------------------------------------------------- drawing

    private static Color color(int node, String label, int depth) {
        boolean dark = Theme.isDark();
        if (node < 0) {
            return dark ? new Color(0x3A3D42) : new Color(0xE5E7EB);
        }
        String cls = label.replaceFirst("^[\\d,]+ × ", "");
        cls = cls.indexOf(' ') < 0 ? cls : cls.substring(0, cls.indexOf(' '));
        float hue = (cls.hashCode() & 0xFFFF) / 65535f;
        float sat = dark ? 0.45f : 0.38f;
        float bri = dark ? 0.40f + 0.08f * depth : 0.78f + 0.07f * depth;
        return Color.getHSBColor(hue, sat, bri);
    }

    private final class Canvas extends JComponent {
        Canvas() {
            setPreferredSize(new Dimension(600, 400));
            setToolTipText("");
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Placed p = at(e.getX(), e.getY());
                    if (p == null || p.box().node() < 0) {
                        return;
                    }
                    if (e.getClickCount() >= 2) {
                        List<Integer> to = new ArrayList<>(path);
                        List<Integer> chain = new ArrayList<>();
                        for (Placed c = p; c != null; c = parentOf(c)) {
                            chain.add(0, c.box().node());
                        }
                        to.addAll(chain);
                        zoom(to);
                    } else {
                        selected = p.box().node();
                        repaint();
                        select.accept(selected);
                    }
                }
            };
            addMouseListener(m);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            Placed p = at(e.getX(), e.getY());
            return p == null ? null : p.box().tip();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Theme.background());
            g.fillRect(0, 0, getWidth(), getHeight());
            placed.clear();
            Box b = shown;
            if (b == null) {
                g.setColor(Theme.dimmedForeground());
                g.drawString("Reading…", 12, 20);
                g.dispose();
                return;
            }
            if (b.inside().isEmpty()) {
                g.setColor(Theme.dimmedForeground());
                g.drawString("It holds nothing else.", 12, 20);
                g.dispose();
                return;
            }
            layoutBoxes(b, new Rectangle2D.Double(4, 4, getWidth() - 8, getHeight() - 8), 0);
            Font f0 = getFont().deriveFont(Font.BOLD, 12f);
            Font f1 = getFont().deriveFont(11f);
            Color text = Theme.isDark() ? new Color(0xF4F4F5) : new Color(0x1F2328);
            Color line = Theme.background();
            for (Placed p : placed) {
                Rectangle2D.Double r = p.r();
                int x = (int) Math.round(r.x);
                int y = (int) Math.round(r.y);
                int w = (int) Math.round(r.x + r.width) - x;
                int h = (int) Math.round(r.y + r.height) - y;
                if (w <= 0 || h <= 0) {
                    continue;
                }
                g.setColor(color(p.box().node(), p.box().label(), p.depth()));
                g.fillRect(x, y, w, h);
                g.setColor(line);
                g.setStroke(new BasicStroke(1));
                g.drawRect(x, y, w, h);
                if (p.box().node() >= 0 && p.box().node() == selected) {
                    g.setColor(Theme.isDark() ? new Color(0xFBBF24) : new Color(0xD97706));
                    g.setStroke(new BasicStroke(2));
                    g.drawRect(x + 1, y + 1, w - 2, h - 2);
                }
                g.setFont(p.depth() == 0 ? f0 : f1);
                FontMetrics fm = g.getFontMetrics();
                if (w > 40 && h > fm.getHeight() + 2) {
                    g.setColor(text);
                    String size = HeapFindings.bytes(p.box().bytes());
                    String l = fit(p.box().label(), fm, w - 8);
                    g.drawString(l, x + 4, y + fm.getAscent() + 2);
                    boolean nested = p.depth() < 2 && !p.box().inside().isEmpty() && w > 60 && h > 50;
                    if (!nested && h > 2 * fm.getHeight() + 4) {
                        g.drawString(fit(size, fm, w - 8), x + 4, y + fm.getAscent() + fm.getHeight() + 2);
                    } else if (nested) {
                        String withSize = fit(p.box().label() + "  " + size, fm, w - 8);
                        g.setColor(color(p.box().node(), p.box().label(), p.depth()));
                        g.fillRect(x + 1, y + 1, w - 2, 16);
                        g.setColor(text);
                        g.drawString(withSize, x + 4, y + fm.getAscent() + 2);
                    }
                }
            }
            g.dispose();
        }

        private static String fit(String s, FontMetrics fm, int w) {
            if (fm.stringWidth(s) <= w) {
                return s;
            }
            for (int n = s.length() - 1; n > 0; n--) {
                String t = s.substring(0, n) + "…";
                if (fm.stringWidth(t) <= w) {
                    return t;
                }
            }
            return "";
        }
    }

    // ---------------------------------------------------------------- tests

    boolean isReadForTest() {
        return shown != null;
    }

    List<String> boxesForTest() {
        List<String> out = new ArrayList<>();
        Map<Box, Boolean> seen = new HashMap<>();
        if (shown != null) {
            for (Box b : shown.inside()) {
                if (seen.put(b, true) == null) {
                    out.add(b.label() + " " + HeapFindings.bytes(b.bytes()));
                }
            }
        }
        return out;
    }

    /** The labels inside the first box. */
    List<String> insideFirstForTest() {
        List<String> out = new ArrayList<>();
        for (Box b : shown.inside().get(0).inside()) {
            out.add(b.label() + " " + HeapFindings.bytes(b.bytes()));
        }
        return out;
    }

    void zoomIntoForTest(int node) {
        List<Integer> to = new ArrayList<>(path);
        to.add(node);
        zoom(to);
    }

    void zoomOutForTest() {
        zoom(path.subList(0, 1));
    }

    List<Integer> pathForTest() {
        return List.copyOf(path);
    }
}
