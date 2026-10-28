package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.Metrics;
import logsift.core.Numbers;
import logsift.core.Spans;
import logsift.core.TimeFormat;

/**
 * What the log measures: how long things that start and end took (jobs, requests, imports), with
 * the ones that never ended, and the numbers its messages report (latencies, queue sizes), each
 * as a chart over time with its slowest or largest lines.
 */
final class MetricsPanel extends JPanel {

    interface Host {
        /** Works out the measurements (on a background thread); null if cancelled. */
        Metrics analyze(AtomicBoolean cancel, java.util.function.Consumer<String> step);

        void goToLine(long line);

        /** "10:02:13" for a line, or its number when the log has no times. */
        String describeLine(long line);
    }

    /** One row on the left: a kind of span or a series of numbers. */
    record Item(String name, String how, Spans.Kind span, Numbers.Series series) {
        int count() {
            return span != null ? span.count() : series.size();
        }
    }

    /** One row on the right: an example line (slowest, largest, never ended). */
    record Example(long line, long time, String value, String what) {}

    private final Host host;
    private final JLabel summary = new JLabel("Durations and numbers the log reports, over time.");
    private final JButton refresh = new JButton("Refresh", Icons.reload());
    private final JProgressBar bar = new JProgressBar();
    private final List<Item> items = new ArrayList<>();
    private final List<Example> examples = new ArrayList<>();
    private final ItemModel itemModel = new ItemModel();
    private final JTable itemTable = new JTable(itemModel);
    private final ExampleModel exampleModel = new ExampleModel();
    private final JTable exampleTable = new JTable(exampleModel);
    private final MetricChart chart;
    private final JLabel exampleTitle = new JLabel(" ");
    private Metrics metrics;
    private AtomicBoolean running;
    private String computedFor; // the filters the shown result is for (null: not known)
    private String wanted = "";  // the filters now
    private final javax.swing.Timer later = new javax.swing.Timer(400, e -> start());

    MetricsPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        later.setRepeats(false);
        chart = new MetricChart(host::goToLine);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        top.add(summary);
        top.add(Box.createHorizontalGlue());
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(100, 10));
        bar.setMaximumSize(new Dimension(100, 10));
        bar.setVisible(false);
        top.add(bar);
        top.add(Box.createHorizontalStrut(8));
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.setToolTipText("Look through the log again (it may have grown)");
        refresh.addActionListener(e -> start());
        top.add(refresh);
        add(top, BorderLayout.NORTH);

        for (JTable t : List.of(itemTable, exampleTable)) {
            t.setFillsViewportHeight(true);
            t.setShowHorizontalLines(false);
            t.setShowVerticalLines(false);
            t.setRowHeight(t.getRowHeight() + 4);
            t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        }
        int[] widths = {270, 56, 70, 70, 70, 56};
        for (int i = 0; i < widths.length; i++) {
            itemTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int i = 1; i < widths.length; i++) {
            itemTable.getColumnModel().getColumn(i).setCellRenderer(right);
        }
        itemTable.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        itemTable.getColumnModel().getColumn(5).setCellRenderer(new OpenRenderer());
        itemTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        int[] ew = {100, 90, 400};
        for (int i = 0; i < ew.length; i++) {
            exampleTable.getColumnModel().getColumn(i).setPreferredWidth(ew[i]);
        }
        exampleTable.getColumnModel().getColumn(1).setCellRenderer(right);
        exampleTable.getSelectionModel().addListSelectionListener(e -> {
            int row = exampleTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0 && row < examples.size()) {
                host.goToLine(examples.get(row).line());
            }
        });
        exampleTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = exampleTable.rowAtPoint(e.getPoint());
                if (row >= 0 && row == exampleTable.getSelectedRow() && row < examples.size()) {
                    host.goToLine(examples.get(row).line());
                }
            }
        });
        JPanel exPanel = new JPanel(new BorderLayout());
        exampleTitle.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        exampleTitle.setForeground(Theme.dimmedForeground());
        exPanel.add(exampleTitle, BorderLayout.NORTH);
        exPanel.add(new JScrollPane(exampleTable), BorderLayout.CENTER);
        JSplitPane right2 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chart, exPanel);
        right2.setResizeWeight(0.6);
        right2.setBorder(null);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(itemTable), right2);
        split.setResizeWeight(0.38);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        chart.clear("Click Refresh to look for durations and numbers in the log.");
    }

    Metrics metrics() {
        return metrics;
    }

    boolean isRunning() {
        return running != null;
    }

    JTable itemsForTest() {
        return itemTable;
    }

    JTable examplesForTest() {
        return exampleTable;
    }

    List<Item> itemListForTest() {
        return List.copyOf(items);
    }

    String summaryForTest() {
        return summary.getText();
    }

    /**
     * The filters changed ({@code key} says how): the measurements are filtered again when the
     * tab is on screen, a moment after the filters settle (the whole log isn't read again).
     */
    void filtersChanged(String key) {
        wanted = key;
        if (metrics != null && !key.equals(computedFor) && isShowing()) {
            later.restart();
        }
    }

    /** The tab is shown: measures, or filters again if the filters changed meanwhile. */
    void showing() {
        if (running == null && (metrics == null || !wanted.equals(computedFor))) {
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
        bar.setVisible(true);
        refresh.setEnabled(false);
        summary.setText("Looking for durations and numbers…");
        Thread t = new Thread(() -> {
            Metrics r = null;
            String error = null;
            try {
                r = host.analyze(cancel, s -> SwingUtilities.invokeLater(() -> {
                    if (running == cancel) {
                        summary.setText(s);
                    }
                }));
            } catch (RuntimeException e) {
                logsift.core.Problems.unexpected(e); // a bug, if not a read or connection failure
                error = e.getMessage();
            }
            Metrics done = r;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                bar.setVisible(false);
                refresh.setEnabled(true);
                if (failed != null) {
                    summary.setText("Could not look through the log: " + failed);
                } else if (done != null) {
                    useResult(done);
                    computedFor = key;
                    if (!wanted.equals(computedFor) && isShowing()) {
                        later.restart(); // the filters changed meanwhile
                    }
                }
            });
        }, "logsift-metrics");
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

    /** Shows measurements found elsewhere (by Insights). */
    void useResult(Metrics m) {
        if (running != null) {
            return;
        }
        metrics = m;
        computedFor = null; // set by start() when it knows the filters
        items.clear();
        for (Spans.Kind k : m.spans().kinds()) {
            items.add(new Item(k.name(), "took (" + k.matchedBy() + ")", k, null));
        }
        for (Numbers.Series s : m.numbers()) {
            items.add(new Item(s.name(), s.how(), null, s));
        }
        itemModel.fireTableDataChanged();
        int spans = m.spans().kinds().size();
        int nums = m.numbers().size();
        long open = m.spans().kinds().stream().mapToLong(Spans.Kind::unfinishedCount).sum();
        summary.setText(m.isEmpty() ? "No durations or numbers found in the messages or columns."
                : (spans > 0 ? spans + (spans == 1 ? " kind of thing that starts and ends" : " kinds of things that start and end") : "")
                        + (spans > 0 && nums > 0 ? "   ·   " : "")
                        + (nums > 0 ? nums + (nums == 1 ? " number" : " numbers") + " over time" : "")
                        + (open > 0 ? "   ·   " + open + " never ended" : ""));
        if (m.filtered() && m.isEmpty()) {
            summary.setText("Nothing measured in the lines the filters keep."); // the strip above says the rest
        }
        if (!items.isEmpty()) {
            itemTable.setRowSelectionInterval(0, 0);
        } else {
            chart.clear("Nothing measured: no messages that start and end, or report a duration, size or count,"
                    + " and no columns of numbers.");
            examples.clear();
            exampleModel.fireTableDataChanged();
        }
    }

    /** Selects the measurement with this line (a finding of Insights is about it). */
    void select(long line) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            boolean has = it.span() != null
                    ? it.span().unfinished().stream().anyMatch(x -> x.startLine() == line)
                            || it.span().slowest().stream().anyMatch(x -> x.startLine() == line)
                    : java.util.Arrays.binarySearch(it.series().lines(), line) >= 0;
            if (has) {
                itemTable.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void showSelected() {
        int row = itemTable.getSelectedRow();
        examples.clear();
        if (row < 0 || row >= items.size()) {
            exampleModel.fireTableDataChanged();
            return;
        }
        Item it = items.get(row);
        if (it.span() != null) {
            Spans.Kind k = it.span();
            List<Spans.Span> done = k.slowest();
            long[] t = new long[done.size()];
            double[] v = new double[done.size()];
            long[] l = new long[done.size()];
            List<Spans.Span> byTime = new ArrayList<>(done);
            byTime.sort(java.util.Comparator.comparingLong(Spans.Span::startLine));
            for (int i = 0; i < byTime.size(); i++) {
                t[i] = byTime.get(i).start();
                v[i] = byTime.get(i).millis();
                l[i] = byTime.get(i).startLine();
            }
            long[] ot = k.unfinished().stream().mapToLong(Spans.Span::start).toArray();
            long[] ol = k.unfinished().stream().mapToLong(Spans.Span::startLine).toArray();
            chart.show(t, v, l, Numbers.Unit.MILLIS, ot, ol);
            for (Spans.Span s : k.unfinished()) {
                examples.add(new Example(s.startLine(), s.start(), "never ended", s.key() == null ? "" : s.key()));
            }
            for (Spans.Span s : done.subList(0, Math.min(50, done.size()))) {
                examples.add(new Example(s.startLine(), s.start(), Numbers.Unit.MILLIS.format(s.millis()),
                        s.key() == null ? "" : s.key()));
            }
            exampleTitle.setText(k.unfinishedCount() > 0
                    ? k.unfinishedCount() + " never ended, then the slowest (the " + done.size() + " slowest are charted)"
                    : "The slowest" + (done.size() < k.count() ? " (the " + done.size() + " slowest are charted)" : ""));
        } else {
            Numbers.Series s = it.series();
            chart.show(s.times(), s.values(), s.lines(), s.unit(), new long[0], new long[0]);
            for (int i : s.largest(50)) {
                String v = s.unit().format(s.values()[i]);
                examples.add(new Example(s.lines()[i], s.times()[i],
                        s.source() == Numbers.Source.ROWS ? v + (v.equals("1") ? " row" : " rows") : v, ""));
            }
            exampleTitle.setText(s.source() == Numbers.Source.ROWS ? "The busiest (" + s.how() + ")"
                    : "The largest" + (s.size() < s.count() ? " (every " + (s.count() / s.size())
                    + "th line is charted)" : ""));
        }
        exampleModel.fireTableDataChanged();
    }

    private final class ItemModel extends AbstractTableModel {
        private final String[] names = {"Measurement", "Count", "Median", "95%", "Max", "No end"};

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Object getValueAt(int row, int col) {
            Item it = items.get(row);
            if (it.span() != null) {
                Spans.Kind k = it.span();
                return switch (col) {
                    case 0 -> it;
                    case 1 -> String.format("%,d", k.count());
                    case 2 -> k.median() < 0 ? "" : Numbers.Unit.MILLIS.format(k.median());
                    case 3 -> k.p95() < 0 ? "" : Numbers.Unit.MILLIS.format(k.p95());
                    case 4 -> k.max() < 0 ? "" : Numbers.Unit.MILLIS.format(k.max());
                    default -> k.unfinishedCount() == 0 ? "" : String.format("%,d", k.unfinishedCount());
                };
            }
            Numbers.Series s = it.series();
            return switch (col) {
                case 0 -> it;
                case 1 -> String.format("%,d", s.count());
                case 2 -> s.unit().format(s.percentile(0.5));
                case 3 -> s.unit().format(s.percentile(0.95));
                case 4 -> s.unit().format(s.max());
                default -> "";
            };
        }
    }

    private final class ExampleModel extends AbstractTableModel {
        private final String[] names = {"Time", "Value", "Which"};

        @Override
        public int getRowCount() {
            return examples.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Object getValueAt(int row, int col) {
            Example e = examples.get(row);
            return switch (col) {
                case 0 -> e.time() == TimeFormat.NONE ? host.describeLine(e.line())
                        : TimelineBar.formatFull(e.time()).substring(11, 23);
                case 1 -> e.value();
                default -> e.what();
            };
        }
    }

    /** "⏱ job  took (by its job)" or "📈 order … placed in # ms". */
    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Item it = (Item) v;
            String name = it.name().replace("&", "&amp;").replace("<", "&lt;").replace("#",
                    "<b>#</b>");
            String how = it.how().isEmpty() ? "" : " <span style='color:#8A8F98'>" + it.how() + "</span>";
            super.getTableCellRendererComponent(t, "<html><nobr>" + (it.span() != null ? "<b>" + name + "</b>" : name)
                    + how + "</nobr></html>", sel, focus, row, col);
            setIcon(Icons.of(it.span() != null ? "timer"
                    : it.series().source() == Numbers.Source.ROWS ? "chart-column"
                    : it.series().source() == Numbers.Source.COLUMN ? "columns-3" : "activity",
                    Theme.gutterForeground(), 14));
            setToolTipText(it.span() != null ? "From “" + logsift.core.Insights.display(it.span().start().text())
                    + "” to “" + logsift.core.Insights.display(it.span().end().text()) + "”" : it.name());
            return this;
        }
    }

    private static final class OpenRenderer extends DefaultTableCellRenderer {
        OpenRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            if (!sel) {
                setForeground(String.valueOf(v).isEmpty() ? t.getForeground() : Theme.errorMark());
            }
            setFont(getFont().deriveFont(Font.BOLD));
            return this;
        }
    }
}
