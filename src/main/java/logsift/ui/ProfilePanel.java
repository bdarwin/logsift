package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import logsift.core.ColumnFilter;
import logsift.core.ColumnProfile;
import logsift.core.Numbers;

/**
 * What each column of a table holds (a CSV file, or any log with columns): a card per column with
 * its kind, how many values are filled in and different, its numbers, and a small chart. Clicking
 * a bar or a value adds a filter on that column.
 */
final class ProfilePanel extends JPanel {

    interface Host {
        /** Profiles the columns (on a background thread); null if cancelled. */
        ColumnProfile.Result analyze(AtomicBoolean cancel, java.util.function.DoubleConsumer progress);

        /** Adds a filter on a column (a bar or a value was clicked). */
        void filter(String column, ColumnFilter.Op op, String text);

        /** Shows only this time range, if the column is the log's own time; false if not. */
        default boolean timeRange(String column, long from, long to) {
            return false;
        }

        /** Whether a click on the column's times can pick a time range. */
        default boolean timesClickable(String column) {
            return false;
        }
    }

    private final Host host;
    private final JLabel summary = new JLabel("What each column holds: its kind, values and a chart.");
    private final JButton refresh = new JButton("Refresh", Icons.reload());
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JPanel cards = new Cards();
    private ColumnProfile.Result result;
    private AtomicBoolean running;
    private String computedFor; // the filters the result is for
    private String wanted = "";  // the filters now
    private final javax.swing.Timer later = new javax.swing.Timer(400, e -> start());

    private static final int CARD_WIDTH = 330;

    ProfilePanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        later.setRepeats(false);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        top.add(summary);
        top.add(Box.createHorizontalGlue());
        bar.setPreferredSize(new Dimension(100, 10));
        bar.setMaximumSize(new Dimension(100, 10));
        bar.setVisible(false);
        top.add(bar);
        top.add(Box.createHorizontalStrut(8));
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.setToolTipText("Read the columns again (the file may have grown)");
        refresh.addActionListener(e -> start());
        top.add(refresh);
        add(top, BorderLayout.NORTH);
        cards.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        JScrollPane scroll = new JScrollPane(cards);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutCards();
            }
        });
        add(scroll, BorderLayout.CENTER);
    }

    ColumnProfile.Result result() {
        return result;
    }

    boolean isRunning() {
        return running != null;
    }

    String summaryForTest() {
        return summary.getText();
    }

    List<ColumnCard> cardsForTest() {
        List<ColumnCard> out = new ArrayList<>();
        for (java.awt.Component c : cards.getComponents()) {
            out.add((ColumnCard) c);
        }
        return out;
    }

    /**
     * The filters changed ({@code key} says how): the profile is read again when it is on screen,
     * a moment after they settle.
     */
    void filtersChanged(String key) {
        wanted = key;
        if (result != null && !key.equals(computedFor) && isShowing()) {
            later.restart();
        }
    }

    /** Reads the columns again unless the result is for the filters of now. */
    void showing() {
        if (running == null && (result == null || !wanted.equals(computedFor))) {
            start();
        }
    }

    void start() {
        later.stop();
        if (running != null) {
            running.set(true);
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        String key = wanted;
        bar.setValue(0);
        bar.setVisible(true);
        refresh.setEnabled(false);
        summary.setText("Reading the columns…");
        Thread t = new Thread(() -> {
            ColumnProfile.Result r = null;
            String error = null;
            try {
                r = host.analyze(cancel, p -> SwingUtilities.invokeLater(() -> bar.setValue((int) (p * 100))));
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            ColumnProfile.Result done = r;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                bar.setVisible(false);
                refresh.setEnabled(true);
                if (failed != null) {
                    summary.setText("Could not read the columns: " + failed);
                } else if (done != null) {
                    computedFor = key;
                    show(done);
                    if (!wanted.equals(computedFor) && isShowing()) {
                        later.restart(); // the filters changed meanwhile
                    }
                } else {
                    summary.setText("This log has no columns: its format has none.");
                }
            });
        }, "logsift-profile");
        t.setDaemon(true);
        t.start();
    }

    void cancel() {
        later.stop();
        if (running != null) {
            running.set(true);
            running = null;
        }
    }

    private void show(ColumnProfile.Result r) {
        result = r;
        String rows = String.format("%,d rows", r.rows());
        String text = r.columns().size() + " columns, " + rows;
        if (r.sampled()) {
            text += String.format(" in a sample of %,d of %,d lines (blocks from all over the file)", r.linesRead(),
                    r.lineCount());
        }
        if (r.filtered()) {
            text += " · only the rows the filters keep";
        }
        summary.setText(text);
        cards.removeAll();
        for (ColumnProfile.Column c : r.columns()) {
            cards.add(new ColumnCard(c, r.sampled()));
        }
        layoutCards();
    }

    private void layoutCards() {
        int w = cards.getParent() == null ? getWidth() : cards.getParent().getWidth();
        int cols = Math.max(1, (w - 12) / CARD_WIDTH);
        ((GridLayout) cards.getLayout()).setColumns(cols);
        cards.revalidate();
        cards.repaint();
    }

    /** The cards in a grid as wide as the view (so it scrolls only up and down). */
    private static final class Cards extends JPanel implements Scrollable {
        Cards() {
            super(new GridLayout(0, 3, 8, 8));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return r.height - 24;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    static String title(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    static String percent(long part, long whole) {
        if (whole == 0) {
            return "0%";
        }
        double p = 100.0 * part / whole;
        return p == 0 ? "0%" : p < 1 ? "<1%" : Math.round(p) + "%";
    }

    /** One column: its name, kind, numbers and chart. */
    final class ColumnCard extends JPanel {
        final ColumnProfile.Column column;
        final ProfileChart chart;

        ColumnCard(ColumnProfile.Column c, boolean sampled) {
            super(new BorderLayout(0, 2));
            this.column = c;
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            JLabel name = new JLabel("<html><b>" + esc(c.title()) + "</b> <span style='color:#8A8F98'>"
                    + kindText(c) + "</span></html>");
            name.setIcon(Icons.of(icon(c.kind()), Theme.gutterForeground(), 14));
            add(name, BorderLayout.NORTH);
            chart = new ProfileChart(c, this::clicked, clickTip(c));
            add(chart, BorderLayout.CENTER);
            JLabel facts = new JLabel("<html>" + facts(c, sampled) + "</html>");
            facts.setForeground(Theme.dimmedForeground());
            facts.setFont(facts.getFont().deriveFont(facts.getFont().getSize2D() - 1f));
            add(facts, BorderLayout.SOUTH);
            setPreferredSize(new Dimension(CARD_WIDTH, 250));
        }

        private void clicked(ColumnProfile.Bar b) {
            switch (column.kind()) {
                case NUMBER -> host.filter(column.name(), ColumnFilter.Op.BETWEEN,
                        ColumnFilter.write(b.from(), column.unit()) + ".." + ColumnFilter.write(b.to(), column.unit()));
                case TIME -> host.timeRange(column.name(), (long) b.from(), (long) Math.ceil(b.to()) + 1);
                default -> host.filter(column.name(), ColumnFilter.Op.EQUALS, b.label());
            }
        }

        private String clickTip(ColumnProfile.Column c) {
            return switch (c.kind()) {
                case NUMBER -> "Click to show only these rows (a filter: between)";
                case TIME -> host.timesClickable(c.name()) ? "Click to show only this time range" : null;
                case EMPTY -> null;
                default -> "Click to show only the rows with this value";
            };
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String icon(ColumnProfile.Kind k) {
        return switch (k) {
            case NUMBER -> "chart-column";
            case TIME -> "clock";
            case CATEGORY, BOOLEAN -> "list-filter";
            case ID -> "hash";
            default -> "text-select";
        };
    }

    private static String kindText(ColumnProfile.Column c) {
        String k = c.kind().label;
        if (c.kind() == ColumnProfile.Kind.NUMBER && c.unit() != Numbers.Unit.NONE) {
            k += switch (c.unit()) {
                case MILLIS -> " (time taken)";
                case BYTES -> " (size)";
                case PERCENT -> " (%)";
                default -> "";
            };
        }
        if (c.kind() == ColumnProfile.Kind.NUMBER && c.logScale()) {
            k += " · buckets ×10";
        }
        return k;
    }

    /** "1,000 values · 900 empty (90%) · 4 different", then the kind's own numbers. */
    static String facts(ColumnProfile.Column c, boolean sampled) {
        long all = c.filled() + c.empty();
        StringBuilder sb = new StringBuilder(String.format("%,d values", c.filled()));
        if (c.empty() > 0) {
            sb.append(String.format(" · %,d empty (%s)", c.empty(), percent(c.empty(), all)));
        }
        if (c.kind() != ColumnProfile.Kind.EMPTY) {
            sb.append(String.format(" · %s%,d different", c.distinctCapped() ? "over " : "", c.distinct()));
        }
        if (sampled) {
            sb.append(" (in the sample)");
        }
        double[] s = c.stats();
        Numbers.Unit u = c.unit();
        switch (c.kind()) {
            case NUMBER -> sb.append("<br>min ").append(u.format(s[0])).append(" · median ").append(u.format(s[1]))
                    .append(" · mean ").append(u.format(s[2])).append("<br>95% ").append(u.format(s[3]))
                    .append(" · max ").append(u.format(s[4]));
            case TIME -> sb.append("<br>").append(TimelineBar.formatFull((long) s[0]).substring(0, 19)).append(" → ")
                    .append(TimelineBar.formatFull((long) s[1]).substring(0, 19)).append("<br>over ")
                    .append(Numbers.Unit.MILLIS.format(s[1] - s[0]));
            case ID, TEXT -> sb.append("<br>").append(c.shortest() == c.longest() ? c.shortest() + " characters"
                    : c.shortest() + " to " + c.longest() + " characters").append(" · the most frequent above");
            case EMPTY -> sb.append("<br>Every row is empty here.");
            default -> { }
        }
        return sb.toString();
    }

    /**
     * A column's chart: a histogram (numbers, times), or its most frequent values as bars
     * (categories) or as a list (IDs, text). Hover for the count; click to filter.
     */
    static final class ProfileChart extends JComponent {
        private final ColumnProfile.Column c;
        private final java.util.function.Consumer<ColumnProfile.Bar> click;
        private final String clickTip;
        private int hover = -1;
        private static final int ROW = 17;

        ProfileChart(ColumnProfile.Column c, java.util.function.Consumer<ColumnProfile.Bar> click, String clickTip) {
            this.c = c;
            this.click = click;
            this.clickTip = clickTip;
            setToolTipText("");
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = barAt(e.getX(), e.getY());
                    if (i != hover) {
                        hover = i;
                        repaint();
                    }
                    setCursor(Cursor.getPredefinedCursor(i >= 0 && clickable(i)
                            ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int i = barAt(e.getX(), e.getY());
                    if (i >= 0 && clickable(i)) {
                        click.accept(c.bars().get(i));
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private boolean histogram() {
            return c.kind() == ColumnProfile.Kind.NUMBER || c.kind() == ColumnProfile.Kind.TIME;
        }

        boolean clickable(int i) {
            return clickTip != null && !c.bars().get(i).label().equals(ColumnProfile.OTHER)
                    && c.bars().get(i).count() > 0;
        }

        /** Clicks a bar, as the mouse would. For tests. */
        void clickForTest(int i) {
            if (clickable(i)) {
                click.accept(c.bars().get(i));
            }
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = barAt(e.getX(), e.getY());
            if (i < 0) {
                return null;
            }
            ColumnProfile.Bar b = c.bars().get(i);
            String what = c.kind() == ColumnProfile.Kind.TIME
                    ? TimelineBar.formatFull((long) b.from()).substring(0, 19) + " – "
                            + TimelineBar.formatFull((long) b.to()).substring(0, 19)
                    : b.label();
            return "<html>" + esc(what) + ": <b>" + String.format("%,d", b.count()) + "</b> rows"
                    + (clickable(i) ? "<br>" + clickTip : "") + "</html>";
        }

        private int barAt(int x, int y) {
            List<ColumnProfile.Bar> bars = c.bars();
            if (bars.isEmpty()) {
                return -1;
            }
            if (histogram()) {
                int left = 4;
                int w = getWidth() - 8;
                if (x < left || x >= left + w || y > getHeight() - 16) {
                    return -1;
                }
                return Math.min(bars.size() - 1, (x - left) * bars.size() / w);
            }
            int i = (y - 2) / ROW;
            return i >= 0 && i < bars.size() ? i : -1;
        }

        private static Color barColor() {
            return Theme.isDark() ? new Color(0x60A5FA) : new Color(0x2563EB);
        }

        private static Color hoverColor() {
            return Theme.isDark() ? new Color(0xFB923C) : new Color(0xD97706);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont().deriveFont(getFont().getSize2D() - 1f));
                List<ColumnProfile.Bar> bars = c.bars();
                if (bars.isEmpty()) {
                    g.setColor(Theme.gutterForeground());
                    g.drawString(c.kind() == ColumnProfile.Kind.EMPTY ? "No values" : "One value", 4, 20);
                    return;
                }
                long max = bars.stream().mapToLong(ColumnProfile.Bar::count).max().orElse(1);
                if (histogram()) {
                    paintHistogram(g, bars, Math.max(1, max));
                } else {
                    paintList(g, bars, Math.max(1, max));
                }
            } finally {
                g.dispose();
            }
        }

        private void paintHistogram(Graphics2D g, List<ColumnProfile.Bar> bars, long max) {
            FontMetrics fm = g.getFontMetrics();
            int left = 4;
            int w = getWidth() - 8;
            int base = getHeight() - 16;
            int h = base - 4;
            g.setColor(Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY));
            g.drawLine(left, base, left + w, base);
            for (int i = 0; i < bars.size(); i++) {
                int x0 = left + i * w / bars.size();
                int x1 = left + (i + 1) * w / bars.size();
                long n = bars.get(i).count();
                int bh = n == 0 ? 0 : Math.max(2, (int) (h * n / max));
                g.setColor(i == hover ? hoverColor() : barColor());
                g.fillRect(x0 + 1, base - bh, Math.max(1, x1 - x0 - 2), bh);
            }
            g.setColor(Theme.gutterForeground());
            String a;
            String b;
            if (c.kind() == ColumnProfile.Kind.TIME) {
                a = TimelineBar.formatFull((long) bars.get(0).from()).substring(0, 16);
                b = TimelineBar.formatFull((long) bars.get(bars.size() - 1).to()).substring(0, 16);
            } else {
                a = c.unit().format(bars.get(0).from());
                b = c.unit().format(bars.get(bars.size() - 1).to());
            }
            int y = getHeight() - 3;
            g.drawString(a, left, y);
            g.drawString(b, left + w - fm.stringWidth(b), y);
        }

        private void paintList(Graphics2D g, List<ColumnProfile.Bar> bars, long max) {
            FontMetrics fm = g.getFontMetrics();
            boolean withBars = c.kind() == ColumnProfile.Kind.CATEGORY || c.kind() == ColumnProfile.Kind.BOOLEAN;
            int w = getWidth();
            int labelW = withBars ? w * 2 / 5 : w - 70;
            int countW = 60;
            int barX = labelW + 6;
            int barW = Math.max(10, w - barX - countW - 4);
            for (int i = 0; i < bars.size(); i++) {
                ColumnProfile.Bar b = bars.get(i);
                int y = 2 + i * ROW;
                if (y + ROW > getHeight()) {
                    break;
                }
                boolean other = b.label().equals(ColumnProfile.OTHER);
                if (i == hover && clickable(i)) {
                    g.setColor(Theme.uiColor("List.hoverBackground", Theme.isDark() ? new Color(0x2F343C)
                            : new Color(0xEEF2F7)));
                    g.fillRect(0, y, w, ROW);
                }
                g.setColor(other ? Theme.gutterForeground() : Theme.foreground());
                g.drawString(clip(other ? "other values" : b.label(), fm, labelW), 4, y + ROW - 5);
                if (withBars) {
                    int bw = (int) Math.max(1, (long) barW * b.count() / max);
                    g.setColor(other ? Theme.gutterForeground() : i == hover ? hoverColor() : barColor());
                    g.fillRect(barX, y + 4, bw, ROW - 8);
                }
                String n = String.format("%,d", b.count());
                g.setColor(Theme.gutterForeground());
                g.drawString(n, w - 4 - fm.stringWidth(n), y + ROW - 5);
            }
        }

        private static String clip(String s, FontMetrics fm, int w) {
            if (fm.stringWidth(s) <= w) {
                return s;
            }
            while (s.length() > 1 && fm.stringWidth(s + "…") > w) {
                s = s.substring(0, s.length() - 1);
            }
            return s + "…";
        }
    }
}
