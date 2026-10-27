package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import logsift.jvm.GcLog;
import logsift.jvm.GcReport;

/**
 * A GC log, explained: how much of the time the program stood still, the longest pauses and what
 * caused them, and what went wrong, in sentences with what to try; then the heap, the pauses,
 * allocation and time in GC over time as charts, and every collection in a table. Clicking a
 * point or a row shows its line in the log.
 */
final class GcTab extends JPanel {

    private final GcReport report;
    private final String name;
    private final LongConsumer showLine;
    private final JTabbedPane views = new JTabbedPane();
    private final GcChart heap = new GcChart();
    private final GcChart pauses = new GcChart();
    private final GcChart alloc = new GcChart();
    private final GcChart gcTime = new GcChart();
    private final JTable events = new JTable();
    private final DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private final DateTimeFormatter clockMs = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    /** {@code showLine}: shows a line of the log (0-based) in its tab. */
    GcTab(String name, GcReport report, LongConsumer showLine) {
        super(new BorderLayout());
        this.name = name;
        this.report = report;
        this.showLine = showLine;
        add(header(), BorderLayout.NORTH);
        views.addTab("Summary", summary());
        views.addTab("Heap", chartPanel(heap, null));
        JCheckBox log = new JCheckBox("Log scale", true);
        log.addActionListener(e -> pauses.setLogScale(log.isSelected()));
        views.addTab("Pauses", pausesPanel(log));
        views.addTab("Allocation & GC Time", twoCharts(alloc, gcTime));
        views.addTab("By Kind", new JScrollPane(groupsTable()));
        views.addTab("All Collections", eventsPanel());
        add(views, BorderLayout.CENTER);
        fillCharts();
        pauses.setLogScale(true);
    }

    String title() {
        return "GC: " + name;
    }

    String statusText() {
        GcReport.Stats p = report.pauses();
        return String.format(Locale.ROOT, "%s · %,d pauses · stopped %s of the time · longest %s",
                report.log().collector().isEmpty() ? "GC log" : report.log().collector(), p.count(),
                GcReport.pct(report.pausedPercent()), GcReport.ms(p.max()));
    }

    GcReport report() {
        return report;
    }

    JTabbedPane viewsForTest() {
        return views;
    }

    GcChart heapChartForTest() {
        return heap;
    }

    GcChart pauseChartForTest() {
        return pauses;
    }

    JTable eventsTableForTest() {
        return events;
    }

    // ---------------------------------------------------------------- header and summary

    private JComponent header() {
        GcLog g = report.log();
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        List<String> about = new ArrayList<>();
        if (!g.collector().isEmpty()) {
            about.add(g.collector());
        }
        if (!g.jvmVersion().isEmpty()) {
            about.add("Java " + g.jvmVersion());
        }
        if (report.heapSize() > 0) {
            about.add("heap " + GcReport.size(report.heapSize()));
        }
        about.add(GcReport.duration(report.seconds()) + " of log");
        if (g.wallOf(report.start()) != Long.MIN_VALUE) {
            about.add("from " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(g.wallOf(report.start()))));
        }
        JLabel t = new JLabel("<html><b style='font-size:1.15em'>" + esc(name) + "</b>&nbsp;&nbsp;<span style='color:#6b7280'>"
                + esc(String.join(" · ", about)) + "</span></html>", Icons.of("gauge"), SwingConstants.LEFT);
        t.setIconTextGap(8);
        // what the tiles, colours and findings mean: the GC page of the user guide
        JButton help = new JButton("What do these mean?", Icons.of("book-open"));
        help.putClientProperty("JButton.buttonType", "toolBarButton");
        help.setToolTipText("Opens the GC page of the user guide in your browser");
        help.addActionListener(e -> HelpSupport.browse(this, HelpSupport.guide("gc")));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(t, BorderLayout.CENTER);
        top.add(help, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);

        GcReport.Stats ps = report.pauses();
        JPanel tiles = new JPanel(new GridLayout(1, 0, 10, 0));
        tiles.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        tiles.add(tile(GcReport.pct(report.pausedPercent()), "of the time stopped for GC",
                report.pausedPercent() >= 10 ? Bad.BAD : report.pausedPercent() >= 5 ? Bad.WARN : Bad.OK));
        tiles.add(tile(GcReport.ms(ps.max()), "longest pause",
                ps.max() >= 1000 ? Bad.BAD : ps.max() >= 200 ? Bad.WARN : Bad.OK));
        tiles.add(tile(GcReport.ms(ps.p99()), "99% of pauses are shorter", Bad.NONE));
        tiles.add(tile(String.format(Locale.ROOT, "%,d", ps.count()), "pauses, " + GcReport.ms(ps.total()) + " in all",
                Bad.NONE));
        tiles.add(tile(String.format(Locale.ROOT, "%,d", report.fullCollections().count()), "full collections",
                report.fullCollections().count() > 0 ? Bad.WARN : Bad.OK));
        if (report.stalls().count() > 0) {
            tiles.add(tile(String.format(Locale.ROOT, "%,d", report.stalls().count()), "allocation stalls", Bad.BAD));
        }
        tiles.add(tile(report.allocationRate() > 0 ? GcReport.size(report.allocationRate()) + "/s" : "–",
                "allocated", Bad.NONE));
        tiles.add(tile(report.peakAfterGc() >= 0 ? GcReport.size(report.peakAfterGc()) : "–", "most left after GC",
                Bad.NONE));
        p.add(tiles, BorderLayout.CENTER);
        return p;
    }

    private enum Bad { BAD, WARN, OK, NONE }

    private JComponent tile(String big, String small, Bad bad) {
        JPanel t = new JPanel(new BorderLayout());
        Color line = switch (bad) {
            case BAD -> new Color(0xDC2626);
            case WARN -> new Color(0xD97706);
            case OK -> new Color(0x16A34A);
            case NONE -> Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY);
        };
        t.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, line),
                BorderFactory.createEmptyBorder(2, 8, 2, 4)));
        JLabel b = new JLabel(big);
        b.setFont(b.getFont().deriveFont(Font.BOLD, b.getFont().getSize2D() + 5f));
        JLabel s = new JLabel(small);
        s.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        t.add(b, BorderLayout.NORTH);
        t.add(s, BorderLayout.SOUTH);
        return t;
    }

    private JComponent summary() {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        for (GcReport.Finding f : report.findings()) {
            list.add(card(f));
            list.add(Box.createVerticalStrut(10));
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(list, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(top);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    private JPanel card(GcReport.Finding f) {
        JPanel c = new JPanel(new BorderLayout(0, 6));
        Color line = switch (f.severity()) {
            case BAD -> new Color(0xDC2626);
            case WARN -> new Color(0xD97706);
            case GOOD -> new Color(0x16A34A);
            case INFO -> new Color(0x9CA3AF);
        };
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, line),
                BorderFactory.createEmptyBorder(6, 12, 6, 6)));
        c.setAlignmentX(LEFT_ALIGNMENT);
        String advice = f.advice().isEmpty() ? "" : "<br><span style='color:#2563eb'>What to try: </span>" + esc(f.advice());
        JLabel t = new JLabel("<html><div style='width:640px'><b style='font-size:1.15em'>" + esc(f.title())
                + "</b><br><span style='color:#6b7280'>" + esc(f.detail()) + "</span>" + advice + "</div></html>");
        c.add(t, BorderLayout.CENTER);
        if (f.line() >= 0) {
            JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JButton show = new JButton("Show in Log");
            show.addActionListener(e -> showLine.accept(f.line()));
            b.add(show);
            c.add(b, BorderLayout.SOUTH);
        }
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    // ---------------------------------------------------------------- charts

    private static final Color BEFORE = new Color(0xF59E0B);
    private static final Color AFTER = new Color(0x2563EB);
    private static final Color SIZE = new Color(0x9CA3AF);
    private static final Color FULL = new Color(0xDC2626);
    private static final Color YOUNG = new Color(0x3B82F6);
    private static final Color MIXED = new Color(0x0D9488);
    private static final Color MARK = new Color(0x8B5CF6);
    private static final Color STALL = new Color(0xEA580C);
    private static final Color OTHER = new Color(0x6B7280);

    private JComponent chartPanel(JComponent c, JComponent extra) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(c, BorderLayout.CENTER);
        JLabel hint = new JLabel("Drag across a chart to zoom in on that time, double-click to see it all."
                + " Click a point to see it in the log.");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JPanel south = new JPanel(new BorderLayout());
        south.add(hint, BorderLayout.WEST);
        if (extra != null) {
            south.add(extra, BorderLayout.EAST);
        }
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent pausesPanel(JCheckBox log) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(chartPanel(pauses, log), BorderLayout.CENTER);
        Histogram hist = new Histogram(report.buckets());
        hist.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        p.add(hist, BorderLayout.SOUTH);
        return p;
    }

    private JComponent twoCharts(GcChart a, GcChart b) {
        JPanel p = new JPanel(new GridLayout(2, 1, 0, 8));
        p.add(a);
        p.add(b);
        return chartPanel(p, null);
    }

    /** Times on the axis: the clock if the log has dates (tenths of a second for a short log), else since the start. */
    private DoubleFunction<String> timeLabel() {
        GcLog g = report.log();
        boolean dates = g.wallOf(report.start()) != Long.MIN_VALUE;
        boolean shortLog = report.seconds() < 60;
        DateTimeFormatter tenths = DateTimeFormatter.ofPattern("HH:mm:ss.S", Locale.ROOT).withZone(ZoneId.systemDefault());
        return x -> dates ? (shortLog ? tenths : clock).format(Instant.ofEpochMilli(g.wallOf(x)))
                : shortLog ? String.format(Locale.ROOT, "%.1f s", x) : GcReport.duration(x);
    }

    private void fillCharts() {
        double from = report.start();
        double to = Math.max(from + 0.001, report.end());
        DoubleFunction<String> tx = timeLabel();
        LongConsumer go = showLine;

        // heap
        List<GcLog.Event> h = report.withHeap();
        int n = h.size();
        double[] bx = new double[n];
        double[] by = new double[n];
        double[] ax = new double[n];
        double[] ay = new double[n];
        double[] cy = new double[n];
        long[] hl = new long[n];
        List<Double> fx = new ArrayList<>();
        List<Double> fy = new ArrayList<>();
        List<Long> fl = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GcLog.Event e = h.get(i);
            bx[i] = e.time();
            by[i] = e.before();
            ax[i] = e.end();
            ay[i] = e.after();
            cy[i] = e.capacity() >= 0 ? e.capacity() : Double.NaN;
            hl[i] = e.line();
            if (e.kind() == GcLog.Kind.FULL) {
                fx.add(e.end());
                fy.add((double) e.after());
                fl.add(e.line());
            }
        }
        heap.setTitle("Heap in use, before and after each collection");
        heap.setLabels(tx, y -> GcReport.size(y));
        heap.setYUnit(1 << 20);
        heap.setEmptyText("This log doesn't say how much heap was in use.");
        heap.setSeries(List.of(
                new GcChart.Series("Heap size", SIZE, GcChart.Style.LINE, ax, cy, null),
                new GcChart.Series("Before GC", BEFORE, GcChart.Style.DOTS, bx, by, hl),
                new GcChart.Series("After GC", AFTER, GcChart.Style.LINE, ax, ay, hl),
                new GcChart.Series("After a full GC", FULL, GcChart.Style.DOTS, toArray(fx), toArray(fy),
                        toLongs(fl))), from, to);
        heap.onPoint(go);

        // pauses, by kind
        List<GcChart.Series> ps = new ArrayList<>();
        String[] names = {"Young", "Mixed", "Full", "Marking", "Other", "Allocation stall"};
        Color[] colors = {YOUNG, MIXED, FULL, MARK, OTHER, STALL};
        for (int k = 0; k < names.length; k++) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            List<Long> ls = new ArrayList<>();
            for (GcLog.Event e : report.log().events()) {
                if (e.stops() && group(e.kind()) == k) {
                    xs.add(e.time());
                    ys.add(e.ms());
                    ls.add(e.line());
                }
            }
            if (!xs.isEmpty()) {
                ps.add(new GcChart.Series(names[k], colors[k], GcChart.Style.DOTS, toArray(xs), toArray(ys), toLongs(ls)));
            }
        }
        pauses.setTitle("Pauses: how long the program stood still");
        pauses.setLabels(tx, GcReport::ms);
        pauses.setEmptyText("No pauses in this log.");
        pauses.setSeries(ps, from, to);
        pauses.onPoint(go);

        // allocation and time stopped, per stretch of time
        double win = barSeconds(report.seconds());
        double[] a = report.allocatedPerWindow(win);
        double[] axs = new double[a.length];
        double[] ays = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            axs[i] = from + i * win;
            ays[i] = a[i] / win;
        }
        alloc.setTitle("Allocation: memory the program asked for, per second");
        alloc.setLabels(tx, y -> GcReport.size(y) + "/s");
        alloc.setYUnit(1 << 20);
        alloc.setEmptyText("This log doesn't say how much heap was in use.");
        alloc.setSeries(n < 2 ? List.of() : List.of(new GcChart.Series("Allocated per second", AFTER,
                GcChart.Style.BARS, axs, ays, null)), from, to);

        double[] w = report.pausedPerWindow(win);
        double[] wx = new double[w.length];
        double[] wy = new double[w.length];
        for (int i = 0; i < w.length; i++) {
            wx[i] = from + i * win;
            wy[i] = Math.min(100, w[i] / 10 / win);
        }
        gcTime.setTitle("Time stopped for GC, per " + GcReport.duration(win));
        gcTime.setLabels(tx, GcReport::pct);
        gcTime.setSeries(List.of(new GcChart.Series("Share of the time in pauses", FULL, GcChart.Style.BARS, wx, wy,
                null)), from, to);

        GcChart.link(heap, pauses, alloc, gcTime);
    }

    /** About 60 bars over the log, in a round number of seconds. */
    static double barSeconds(double span) {
        double want = span / 60;
        for (double s : new double[] {0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600}) {
            if (s >= want) {
                return s;
            }
        }
        return Math.ceil(want / 3600) * 3600;
    }

    /** Which pause series a kind is drawn in. */
    private static int group(GcLog.Kind k) {
        return switch (k) {
            case YOUNG -> 0;
            case MIXED -> 1;
            case FULL -> 2;
            case REMARK, CLEANUP, INITIAL_MARK -> 3;
            case STALL -> 5;
            default -> 4;
        };
    }

    private static double[] toArray(List<Double> l) {
        return l.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static long[] toLongs(List<Long> l) {
        return l.stream().mapToLong(Long::longValue).toArray();
    }

    /** Pauses by length, as bars. */
    private static final class Histogram extends JComponent {
        private final List<GcReport.Bucket> buckets;

        private static String label(GcReport.Bucket b) {
            return b.toMs() == Double.MAX_VALUE ? GcReport.ms(b.fromMs()) + " or more"
                    : b.fromMs() == 0 ? "under " + GcReport.ms(b.toMs())
                    : GcReport.ms(b.fromMs()) + " – " + GcReport.ms(b.toMs());
        }

        Histogram(List<GcReport.Bucket> buckets) {
            this.buckets = buckets;
            setPreferredSize(new Dimension(600, 30 + 20 * Math.max(1, buckets.size())));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                java.awt.Insets in = getInsets();
                Color fg = Theme.uiColor("Label.foreground", Color.BLACK);
                Color dim = Theme.uiColor("Label.disabledForeground", Color.GRAY);
                g.setFont(getFont().deriveFont(Font.BOLD));
                g.setColor(fg);
                g.drawString("How long the pauses were", in.left, in.top + 14);
                g.setFont(getFont());
                java.awt.FontMetrics fm = g.getFontMetrics();
                int max = 1;
                for (GcReport.Bucket b : buckets) {
                    max = Math.max(max, b.count());
                }
                int labelW = 0;
                for (GcReport.Bucket b : buckets) {
                    labelW = Math.max(labelW, fm.stringWidth(label(b)));
                }
                labelW += 14;
                int barW = Math.max(40, getWidth() - in.left - in.right - labelW - 230);
                int y = in.top + 24;
                for (GcReport.Bucket b : buckets) {
                    String label = label(b);
                    g.setColor(dim);
                    g.drawString(label, in.left, y + 13);
                    int w = (int) Math.round((double) b.count() / max * barW);
                    g.setColor(b.fromMs() >= 1000 ? FULL : b.fromMs() >= 200 ? STALL : YOUNG);
                    g.fillRoundRect(in.left + labelW, y + 3, Math.max(b.count() > 0 ? 2 : 0, w), 13, 4, 4);
                    g.setColor(fg);
                    g.drawString(String.format(Locale.ROOT, "%,d  (%s in all)", b.count(), GcReport.ms(b.total())),
                            in.left + labelW + w + 8, y + 13);
                    y += 20;
                }
            } finally {
                g.dispose();
            }
        }
    }

    // ---------------------------------------------------------------- tables

    /** A number shown as text and sorted as a number. */
    private record Num(double v, String text) implements Comparable<Num> {
        @Override
        public int compareTo(Num o) {
            return Double.compare(v, o.v);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private JTable groupsTable() {
        List<GcReport.Group> gs = report.groups();
        double total = Math.max(1e-9, report.pauses().total());
        String[] cols = {"Pause", "Why", "How many", "In all", "Share of pause time", "Average", "Longest"};
        AbstractTableModel m = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return gs.size();
            }

            @Override
            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int c) {
                return cols[c];
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return c < 2 ? String.class : Num.class;
            }

            @Override
            public Object getValueAt(int r, int c) {
                GcReport.Group g = gs.get(r);
                return switch (c) {
                    case 0 -> g.name();
                    case 1 -> g.cause();
                    case 2 -> new Num(g.count(), String.format(Locale.ROOT, "%,d", g.count()));
                    case 3 -> new Num(g.total(), GcReport.ms(g.total()));
                    case 4 -> new Num(g.total() / total, GcReport.pct(100 * g.total() / total));
                    case 5 -> new Num(g.total() / g.count(), GcReport.ms(g.total() / g.count()));
                    default -> new Num(g.max(), GcReport.ms(g.max()));
                };
            }
        };
        JTable t = new JTable(m);
        t.setAutoCreateRowSorter(true);
        t.setRowHeight(t.getRowHeight() + 4);
        rightAlign(t, 2);
        t.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = t.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && r >= 0) {
                    showLine.accept(gs.get(t.convertRowIndexToModel(r)).worstLine());
                }
            }
        });
        t.setToolTipText("Double-click a row to see its longest pause in the log");
        return t;
    }

    private JComponent eventsPanel() {
        List<GcLog.Event> all = report.log().events();
        GcLog g = report.log();
        boolean dates = g.wallOf(report.start()) != Long.MIN_VALUE;
        String[] cols = {"Time", "GC", "Kind", "What", "Why", "Before", "After", "Heap size", "Took", "Trouble"};
        AbstractTableModel m = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return all.size();
            }

            @Override
            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int c) {
                return cols[c];
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 || c == 1 || c >= 5 && c <= 8 ? Num.class : String.class;
            }

            @Override
            public Object getValueAt(int r, int c) {
                GcLog.Event e = all.get(r);
                return switch (c) {
                    case 0 -> new Num(e.time(), dates ? clockMs.format(Instant.ofEpochMilli(g.wallOf(e.time())))
                            : String.format(Locale.ROOT, "%.3f s", e.time()));
                    case 1 -> new Num(e.id(), e.id() < 0 ? "" : String.valueOf(e.id()));
                    case 2 -> e.kind().label();
                    case 3 -> e.name();
                    case 4 -> e.cause();
                    case 5 -> bytes(e.before());
                    case 6 -> bytes(e.after());
                    case 7 -> bytes(e.capacity());
                    case 8 -> new Num(e.ms(), e.ms() > 0 || e.stops() ? GcReport.ms(e.ms()) : "");
                    default -> e.trouble();
                };
            }
        };
        events.setModel(m);
        TableRowSorter<AbstractTableModel> sorter = new TableRowSorter<>(m);
        events.setRowSorter(sorter);
        events.setRowHeight(events.getRowHeight() + 2);
        rightAlign(events, 5);
        events.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = events.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && r >= 0) {
                    showLine.accept(all.get(events.convertRowIndexToModel(r)).line());
                }
            }
        });
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(events), BorderLayout.CENTER);
        JLabel hint = new JLabel(String.format(Locale.ROOT, "%,d collections, pauses and stalls. Click a column to sort;"
                + " double-click a row to see it in the log.", all.size()));
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        hint.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    private static Num bytes(long b) {
        return new Num(b, b < 0 ? "" : GcReport.size(b));
    }

    private static void rightAlign(JTable t, int fromCol) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = fromCol; c < t.getColumnCount(); c++) {
            if (t.getColumnClass(c) == Num.class) {
                t.getColumnModel().getColumn(c).setCellRenderer(r);
            }
        }
        if (t.getColumnClass(0) == Num.class) {
            t.getColumnModel().getColumn(0).setCellRenderer(r);
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
