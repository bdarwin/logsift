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
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;
import logsift.jvm.Profile;

/**
 * A flame graph, top-down: the outermost calls on top, what they call below, each frame as wide
 * as the share of time (or memory, or lock waits) in it. Green is Java code (paler for the
 * JDK's own), yellow native code, orange the kernel; matches of a search are pink. Click a frame
 * to zoom into it; the frames above it stay at the top to zoom back out.
 */
final class FlameGraph extends JComponent implements Scrollable {
    private static final int ROW = 18;

    private Profile profile;
    private Profile.Node zoom;
    private String query = "";
    private String exact;
    private final List<Hit> hits = new ArrayList<>();
    private Consumer<Profile.Node> onZoom = n -> { };

    /** A painted frame, for the mouse. */
    private record Hit(Rectangle r, Profile.Node node) {}

    FlameGraph() {
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Profile.Node n = nodeAt(e.getX(), e.getY());
                if (n != null) {
                    zoomTo(n == zoom ? (n.parent() == null ? n : n.parent()) : n);
                } else if (e.getClickCount() == 2) {
                    zoomTo(null);
                }
            }
        });
    }

    void setProfile(Profile p) {
        profile = p;
        zoom = p == null ? null : p.root();
        rows = -1;
        revalidate();
        repaint();
    }

    Profile profile() {
        return profile;
    }

    void onZoom(Consumer<Profile.Node> c) {
        onZoom = c;
    }

    /** Zooms into a frame (null: everything). */
    void zoomTo(Profile.Node n) {
        if (profile == null) {
            return;
        }
        zoom = n == null ? profile.root() : n;
        rows = -1;
        revalidate();
        repaint();
        onZoom.accept(zoom);
    }

    Profile.Node zoomed() {
        return zoom;
    }

    /** Highlights frames containing the text (case doesn't matter). */
    void setQuery(String q) {
        query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        exact = null;
        repaint();
    }

    /** Highlights one method exactly. */
    void setExact(String method) {
        exact = method;
        query = "";
        repaint();
    }

    /** The share (0–1) of the zoomed frame's total in highlighted frames (not counted twice). */
    double matchedShare() {
        if (profile == null || zoom == null || zoom.total() == 0 || query.isEmpty() && exact == null) {
            return 0;
        }
        return matched(zoom) / (double) zoom.total();
    }

    private long matched(Profile.Node n) {
        if (n != zoom && matches(n)) {
            return n.total();
        }
        long sum = 0;
        for (Profile.Node c : n.children()) {
            sum += matched(c);
        }
        return sum;
    }

    private boolean matches(Profile.Node n) {
        if (exact != null) {
            return n.name().equals(exact);
        }
        return !query.isEmpty() && n.name().toLowerCase(Locale.ROOT).contains(query);
    }

    private int rows = -1; // rows below the zoomed frame (worked out once per zoom)

    private int depthBelow(Profile.Node n) {
        int d = 0;
        for (Profile.Node c : n.children()) {
            d = Math.max(d, 1 + depthBelow(c));
        }
        return d;
    }

    @Override
    public Dimension getPreferredSize() {
        if (profile == null) {
            return new Dimension(400, 200);
        }
        if (rows < 0) {
            rows = zoom.depth() + 1 + depthBelow(zoom);
        }
        return new Dimension(400, rows * ROW + 8);
    }

    private Profile.Node nodeAt(int x, int y) {
        for (Hit h : hits) {
            if (h.r().contains(x, y)) {
                return h.node();
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Profile.Node n = nodeAt(e.getX(), e.getY());
        if (n == null || profile == null) {
            return null;
        }
        long all = profile.total();
        String total = pct(n.total(), all);
        String self = pct(n.self(), all);
        return "<html><b>" + ThreadStackView.esc(n.name()) + "</b><br>"
                + "With what it calls: " + total + " (" + profile.amount(n.total()) + ")<br>"
                + "On its own: " + self + " (" + profile.amount(n.self()) + ")<br>"
                + "<span style='color:gray'>" + kindLabel(n.kind()) + " · click to zoom</span></html>";
    }

    static String pct(long v, long all) {
        if (all <= 0) {
            return "0%";
        }
        double p = 100.0 * v / all;
        return p >= 10 ? String.format("%.0f%%", p) : p >= 0.1 ? String.format("%.1f%%", p) : "<0.1%";
    }

    static String kindLabel(Profile.Kind k) {
        return switch (k) {
            case JAVA -> "Java (compiled)";
            case INLINED -> "Java (inlined)";
            case INTERPRETED -> "Java (interpreted)";
            case C1 -> "Java (C1 compiled)";
            case NATIVE -> "native code";
            case KERNEL -> "the operating system's kernel";
            case THREAD -> "thread";
        };
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        hits.clear();
        if (profile == null || profile.total() == 0) {
            g.dispose();
            return;
        }
        Font f = getFont().deriveFont(Math.max(10f, getFont().getSize2D() - 1));
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int w = getWidth() - 2;
        // the frames above the zoomed one, full width, to zoom back out
        List<Profile.Node> above = new ArrayList<>();
        for (Profile.Node n = zoom.parent(); n != null; n = n.parent()) {
            above.add(0, n);
        }
        int y = 2;
        for (Profile.Node n : above) {
            frame(g, fm, n, 1, y, w, true);
            y += ROW;
        }
        Rectangle clip = g.getClipBounds();
        paint(g, fm, zoom, 1, y, w, clip);
        g.dispose();
    }

    private void paint(Graphics2D g, FontMetrics fm, Profile.Node n, double x, int y, double w, Rectangle clip) {
        if (w < 0.6 || y > clip.y + clip.height) {
            return;
        }
        if (y + ROW >= clip.y) {
            frame(g, fm, n, x, y, w, false);
        }
        long total = Math.max(1, n.total());
        double cx = x;
        for (Profile.Node c : n.children()) {
            double cw = w * c.total() / total;
            paint(g, fm, c, cx, y + ROW, cw, clip);
            cx += cw;
        }
    }

    private void frame(Graphics2D g, FontMetrics fm, Profile.Node n, double x, int y, double w, boolean dim) {
        int ix = (int) Math.round(x);
        int iw = Math.max(1, (int) Math.round(x + w) - ix - 1);
        Color c = matches(n) ? (Theme.isDark() ? new Color(0xC2185B) : new Color(0xEE7AE9)) : color(n);
        if (dim) {
            c = new Color(c.getRed(), c.getGreen(), c.getBlue(), 120);
        }
        g.setColor(c);
        g.fillRect(ix, y, iw, ROW - 1);
        hits.add(new Hit(new Rectangle(ix, y, iw + 1, ROW), n));
        if (iw > 24) {
            String label = n == profile.root() ? "all · " + profile.amount(n.total()) : n.name();
            int avail = iw - 6;
            if (fm.stringWidth(label) > avail) {
                label = shortName(label);
                while (label.length() > 2 && fm.stringWidth(label + "…") > avail) {
                    label = label.substring(0, label.length() - 1);
                }
                label = label.length() <= 2 ? "" : label + "…";
            }
            g.setColor(Theme.isDark() ? new Color(0xF4F4F5) : new Color(0x1F1F1F));
            g.drawString(label, ix + 3, y + ROW - 5);
        }
    }

    /** {@code com.acme.orders.Api.handle} → {@code Api.handle}. */
    static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || name.contains("::")) {
            return name;
        }
        int cls = name.lastIndexOf('.', dot - 1);
        return name.substring(cls + 1);
    }

    private static boolean isJdk(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    private Color color(Profile.Node n) {
        int h = Math.abs(n.name().hashCode());
        int v = h % 40; // a little variety between neighbours
        boolean dark = Theme.isDark();
        return switch (n.kind()) {
            case JAVA, C1, INTERPRETED -> isJdk(n.name())
                    ? (dark ? new Color(0x2E5E3E + v * 0x010101) : new Color(0xA8D8A8 + v / 4 * 0x010101))
                    : (dark ? new Color(0x2F7D46 + v * 0x010000) : new Color(0x5DBB63 + v * 0x010000));
            case INLINED -> dark ? new Color(0x26706B + v * 0x000101) : new Color(0x7FCCC5 + v / 2 * 0x000101);
            case NATIVE -> dark ? new Color(0x8A6D1F + v * 0x010000) : new Color(0xE8C547 + v / 2 * 0x000100);
            case KERNEL -> dark ? new Color(0x9A4A1F + v * 0x000100) : new Color(0xF0955A + v / 2 * 0x000100);
            case THREAD -> dark ? new Color(0x4A4F57) : new Color(0xD4D8DE);
        };
    }

    // ---------------------------------------------------------------- scrolling

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(600, 400);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
        return ROW;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
        return o == SwingConstants.VERTICAL ? Math.max(ROW, r.height - ROW) : r.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
}
