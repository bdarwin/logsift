package logmaster.ui;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import logmaster.core.FilterResult;
import logmaster.core.TimeIndex;

/**
 * A strip above the log showing how much was logged over time, with errors (red) and warnings
 * (amber) on top, and where the view is. Click to jump to a time; drag to show only a time
 * range.
 */
final class TimelineBar extends JComponent {

    interface Model {
        TimeIndex.Samples samples();

        long lineCount();

        FilterResult errors();

        FilterResult warnings();

        /** First and last visible file line, or null. */
        long[] viewLines();

        /** The selected time range {from, to}, or null. */
        long[] timeRange();

        void goToTime(long time);

        void selectTimeRange(long from, long to);

        void clearTimeRange();
    }

    private static final int HEIGHT = 66;
    private static final int AXIS = 15;
    private static final int PAD = 8;
    private static final int BAR = 3;
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lm-timeline");
                t.setDaemon(true);
                return t;
            });

    private final Model model;
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile Histogram histogram;
    private int dragFrom = -1;
    private int dragTo = -1;
    private Rectangle clearBox;

    /** Line counts per time bucket. */
    record Histogram(long t0, long t1, int buckets, long[] volume, long[] errors, long[] warnings,
                     long maxVolume, long maxErrors, long maxWarnings) {
        long bucketMillis() {
            return Math.max(1, (t1 - t0 + buckets - 1) / buckets);
        }
    }

    TimelineBar(Model model) {
        this.model = model;
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                    popup(e);
                    return;
                }
                if (clearBox != null && clearBox.contains(e.getPoint())) {
                    model.clearTimeRange();
                    return;
                }
                dragFrom = e.getX();
                dragTo = -1;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom >= 0) {
                    dragTo = e.getX();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup(e);
                    return;
                }
                Histogram h = histogram;
                if (dragFrom < 0 || h == null) {
                    return;
                }
                if (dragTo < 0 || Math.abs(dragTo - dragFrom) < 4) {
                    model.goToTime(timeAt(h, dragFrom));
                } else {
                    long a = timeAt(h, Math.min(dragFrom, dragTo));
                    long b = timeAt(h, Math.max(dragFrom, dragTo));
                    model.selectTimeRange(a, b);
                }
                dragFrom = dragTo = -1;
                repaint();
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, HEIGHT);
    }

    private void popup(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem("Show All Times");
        clear.setEnabled(model.timeRange() != null);
        clear.addActionListener(a -> model.clearTimeRange());
        menu.add(clear);
        menu.show(this, e.getX(), e.getY());
    }

    /** Recomputes the bars in the background (coalesced). */
    void refresh() {
        if (pending.compareAndSet(false, true)) {
            int width = Math.max(100, getWidth());
            WORKER.execute(() -> {
                pending.set(false);
                Histogram h = compute(width);
                SwingUtilities.invokeLater(() -> {
                    histogram = h;
                    repaint();
                });
            });
        }
    }

    Histogram histogramForTest() {
        return histogram;
    }

    private Histogram compute(int width) {
        TimeIndex.Samples s = model.samples();
        if (s == null || s.n() == 0) {
            return null;
        }
        long t0 = s.minTime();
        long t1 = s.maxTime() + 1;
        int buckets = Math.max(10, (width - 2 * PAD) / BAR);
        long[] vol = new long[buckets];
        long[] err = new long[buckets];
        long[] warn = new long[buckets];
        long span = Math.max(1, t1 - t0);
        long lines = model.lineCount();
        for (int i = 0; i < s.n(); i++) {
            long next = i + 1 < s.n() ? s.lines()[i + 1] : lines;
            vol[bucket(s.times()[i], t0, span, buckets)] += Math.max(1, next - s.lines()[i]);
        }
        count(model.errors(), s, err, t0, span);
        count(model.warnings(), s, warn, t0, span);
        return new Histogram(t0, t1, buckets, vol, err, warn, max(vol), max(err), max(warn));
    }

    private static void count(FilterResult r, TimeIndex.Samples s, long[] out, long t0, long span) {
        if (r == null || !r.isFiltering()) {
            return;
        }
        long n = Math.min(r.count(), 5_000_000);
        int buckets = out.length;
        for (long row = 0; row < n; row++) {
            long t = s.timeOf(r.lineAt(row));
            if (t != logmaster.core.TimeFormat.NONE) {
                out[bucket(t, t0, span, buckets)]++;
            }
        }
    }

    private static int bucket(long t, long t0, long span, int buckets) {
        long b = (t - t0) * buckets / span;
        return (int) Math.max(0, Math.min(buckets - 1, b));
    }

    private static long max(long[] a) {
        long m = 0;
        for (long v : a) {
            m = Math.max(m, v);
        }
        return m;
    }

    private int plotWidth() {
        return Math.max(1, getWidth() - 2 * PAD);
    }

    private long timeAt(Histogram h, int x) {
        double f = (double) (Math.max(PAD, Math.min(getWidth() - PAD, x)) - PAD) / plotWidth();
        return h.t0 + (long) (f * (h.t1 - h.t0));
    }

    private int xOf(Histogram h, long t) {
        double f = (double) (t - h.t0) / Math.max(1, h.t1 - h.t0);
        return PAD + (int) Math.round(Math.max(0, Math.min(1, f)) * plotWidth());
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Histogram h = histogram;
        if (h == null) {
            return null;
        }
        if (clearBox != null && clearBox.contains(e.getPoint())) {
            return "Show all times again";
        }
        int b = (int) Math.max(0, Math.min(h.buckets - 1,
                (long) (e.getX() - PAD) * h.buckets / plotWidth()));
        long from = h.t0 + b * h.bucketMillis();
        long to = from + h.bucketMillis();
        return String.format("<html><b>%s – %s</b><br>%,d lines · <span style='color:#E5484D'>%,d errors</span>"
                        + " · <span style='color:#D08A00'>%,d warnings</span><br>"
                        + "Click to go there · drag to show only a time range</html>",
                format(from, h, true), format(to, h, false), h.volume[b], h.errors[b], h.warnings[b]);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int hgt = getHeight();
            g.setColor(Theme.gutterBackground());
            g.fillRect(0, 0, w, hgt);
            g.setColor(Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY));
            g.drawLine(0, hgt - 1, w, hgt - 1);
            Histogram h = histogram;
            if (h == null) {
                g.setColor(Theme.gutterForeground());
                g.drawString("Reading timestamps…", PAD, hgt / 2 + 4);
                return;
            }
            int plotTop = 4;
            int plotH = hgt - AXIS - plotTop - 2;
            int base = plotTop + plotH;
            double bw = (double) plotWidth() / h.buckets;
            Color volume = Theme.pick(new Color(0xB4C3DA), new Color(0x46526A));
            for (int b = 0; b < h.buckets; b++) {
                int x = PAD + (int) (b * bw);
                int bwPx = Math.max(1, (int) ((b + 1) * bw) - (int) (b * bw) - 1);
                if (h.volume[b] > 0) {
                    int vh = Math.max(1, (int) (plotH * h.volume[b] / Math.max(1, h.maxVolume)));
                    g.setColor(volume);
                    g.fillRect(x, base - vh, bwPx, vh);
                }
                if (h.warnings[b] > 0) {
                    int ah = Math.max(2, (int) (plotH * 0.7 * h.warnings[b] / Math.max(1, h.maxWarnings)));
                    g.setColor(Theme.warningMark());
                    g.fillRect(x, base - ah, bwPx, ah);
                }
                if (h.errors[b] > 0) {
                    int eh = Math.max(2, (int) (plotH * 0.9 * h.errors[b] / Math.max(1, h.maxErrors)));
                    g.setColor(Theme.errorMark());
                    g.fillRect(x, base - eh, Math.max(1, bwPx - (bwPx > 2 ? 1 : 0)), eh);
                }
            }
            paintAxis(g, h, base);

            // selected time range
            long[] range = model.timeRange();
            clearBox = null;
            Color accent = Theme.marker();
            if (range != null) {
                int x0 = xOf(h, range[0]);
                int x1 = Math.max(x0 + 2, xOf(h, range[1]));
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45));
                g.fillRect(x0, 0, x1 - x0, base);
                g.setColor(accent);
                g.drawLine(x0, 0, x0, base);
                g.drawLine(x1, 0, x1, base);
                String label = format(range[0], h, true) + " – " + format(range[1], h, false) + "  ✕";
                Font f = getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1);
                g.setFont(f);
                FontMetrics fm = g.getFontMetrics();
                int lw = fm.stringWidth(label) + 10;
                int lx = Math.max(2, Math.min(w - lw - 2, x0 + (x1 - x0 - lw) / 2));
                g.setColor(accent);
                g.fillRoundRect(lx, 2, lw, fm.getHeight() + 2, 8, 8);
                g.setColor(Color.WHITE);
                g.drawString(label, lx + 5, 2 + fm.getAscent() + 1);
                int xw = fm.stringWidth("✕");
                clearBox = new Rectangle(lx + lw - xw - 10, 0, xw + 10, fm.getHeight() + 6);
            }
            // drag preview
            if (dragFrom >= 0 && dragTo >= 0) {
                int a = Math.min(dragFrom, dragTo);
                int b = Math.max(dragFrom, dragTo);
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 70));
                g.fillRect(a, 0, b - a, base);
            }
            // where the view is
            long[] view = model.viewLines();
            TimeIndex.Samples s = model.samples();
            if (view != null && s != null && s.n() > 0) {
                int x0 = xOf(h, s.timeOf(view[0]));
                int x1 = Math.max(x0 + 2, xOf(h, s.timeOf(view[1])));
                Color fg = Theme.foreground();
                g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 35));
                g.fillRect(x0 - 1, 0, x1 - x0 + 2, base);
                g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
                g.drawRect(x0 - 1, 0, x1 - x0 + 1, base - 1);
            }
        } finally {
            g.dispose();
        }
    }

    private static final long[] STEPS = {1, 10, 100, 1000, 5000, 10_000, 30_000, 60_000, 300_000,
        600_000, 1_800_000, 3_600_000, 3 * 3_600_000L, 6 * 3_600_000L, 12 * 3_600_000L, 86_400_000L,
        7 * 86_400_000L, 30 * 86_400_000L};

    private void paintAxis(Graphics2D g, Histogram h, int base) {
        g.setFont(getFont().deriveFont(getFont().getSize2D() - 2));
        FontMetrics fm = g.getFontMetrics();
        long span = Math.max(1, h.t1 - h.t0);
        long step = STEPS[STEPS.length - 1];
        int maxTicks = Math.max(2, plotWidth() / 110);
        for (long st : STEPS) {
            if (span / st <= maxTicks) {
                step = st;
                break;
            }
        }
        g.setColor(Theme.gutterForeground());
        long first = (h.t0 + step - 1) / step * step;
        for (long t = first; t <= h.t1; t += step) {
            int x = xOf(h, t);
            g.drawLine(x, base, x, base + 3);
            String label = formatTick(t, step, span);
            int lx = x - fm.stringWidth(label) / 2;
            if (lx > 0 && lx + fm.stringWidth(label) < getWidth()) {
                g.drawString(label, lx, base + 3 + fm.getAscent());
            }
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SEC = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter MS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String formatTick(long t, long step, long span) {
        var dt = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC);
        if (span >= 2 * 86_400_000L) {
            return DAY.format(dt);
        }
        if (step >= 60_000) {
            return MIN.format(dt);
        }
        if (step >= 1000) {
            return SEC.format(dt);
        }
        return MS.format(dt);
    }

    /** Formats a time for labels; with the date when the log spans more than a day. */
    static String format(long t, Histogram h, boolean withDate) {
        var dt = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC);
        long span = h.t1 - h.t0;
        if (withDate && span >= 86_400_000L) {
            return FULL.format(dt);
        }
        return span < 60_000 ? MS.format(dt) : SEC.format(dt);
    }

    /** Full date and time, for the status bar and dialogs. */
    static String formatFull(long t) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .format(Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC));
    }

    /** "10:05", "10:05:30.250" (on the date of {@code ref}) or "2026-10-21 10:05[:30]". */
    static long parseUserTime(String s, long ref) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:(\\d{4})-(\\d{1,2})-(\\d{1,2})[ T])?(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,3}))?)?")
                .matcher(s);
        if (!m.matches()) {
            return logmaster.core.TimeFormat.NONE;
        }
        long day;
        if (m.group(1) != null) {
            day = java.time.LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))).toEpochDay();
        } else {
            day = Math.floorDiv(ref, 86_400_000L);
        }
        long ms = m.group(7) == null ? 0 : Long.parseLong((m.group(7) + "00").substring(0, 3));
        long secs = Integer.parseInt(m.group(4)) * 3600L + Integer.parseInt(m.group(5)) * 60L
                + (m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
        return day * 86_400_000L + secs * 1000 + ms;
    }
}
