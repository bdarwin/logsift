package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.Insights;
import logsift.core.RequestTrace;
import logsift.core.TimeFormat;

/**
 * One request followed through the log: its lines in order with the time since the line before,
 * the threads it went through, and the longest wait marked, so it is clear where the time went.
 * Click a line to go there.
 */
final class RequestPanel extends JPanel {

    interface Host {
        /** Finds the request's lines and builds it (on a background thread); null if cancelled. */
        RequestTrace.Trace follow(String value, boolean withThreads, AtomicBoolean cancel);

        void goToLine(long line);

        void showOnly(String value);

        void findAll(String value);

        /** The message part of a line (the whole line when the log has no columns). */
        String message(String line);
    }

    private static final Color AMBER = new Color(0xD97706);

    private final Host host;
    private final JLabel title = new JLabel("Follow a request");
    private final JLabel summary = new JLabel(" ");
    private final JCheckBox withThreads = new JCheckBox("Lines of its threads in between");
    private final JButton showOnly = new JButton("Show Only These Lines", Icons.of("list-filter"));
    private final JButton findAll = new JButton("Find All", Icons.of("search"));
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private String name;
    private String value;
    private RequestTrace.Trace trace;
    private AtomicBoolean running;

    RequestPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        summary.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        bar.add(title);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(summary);
        bar.add(Box.createHorizontalGlue());
        withThreads.setToolTipText("<html>Logs often name the ID on only some lines of a request.<br>"
                + "This adds the lines its threads wrote between its first and last line.</html>");
        withThreads.setFocusable(false);
        withThreads.addActionListener(e -> {
            if (value != null) {
                follow(name, value);
            }
        });
        for (JButton b : List.of(showOnly, findAll)) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            b.setEnabled(false);
        }
        showOnly.setToolTipText("Filter the log to the lines with this ID");
        findAll.setToolTipText("Find all lines with this ID");
        showOnly.addActionListener(e -> host.showOnly(value));
        findAll.addActionListener(e -> host.findAll(value));
        bar.add(withThreads);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(findAll);
        bar.add(showOnly);
        add(bar, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {100, 70, 130, 60, 900};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setCellRenderer(new StepRenderer(SwingConstants.LEFT, false));
        table.getColumnModel().getColumn(1).setCellRenderer(new StepRenderer(SwingConstants.RIGHT, false));
        table.getColumnModel().getColumn(2).setCellRenderer(new StepRenderer(SwingConstants.LEFT, false));
        table.getColumnModel().getColumn(3).setCellRenderer(new MessagesPanel.LevelRenderer());
        table.getColumnModel().getColumn(4).setCellRenderer(new StepRenderer(SwingConstants.LEFT, true));
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0 && trace != null && row < trace.steps().size()) {
                host.goToLine(trace.steps().get(row).line());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && trace != null && row == table.getSelectedRow()) {
                    host.goToLine(trace.steps().get(row).line()); // again, after scrolling away
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        summary.setText("Right-click a line with a trace or request ID → Follow, or press Ctrl+Shift+R.");
    }

    String value() {
        return value;
    }

    RequestTrace.Trace trace() {
        return trace;
    }

    boolean isRunning() {
        return running != null;
    }

    JTable tableForTest() {
        return table;
    }

    String summaryForTest() {
        return summary.getText();
    }

    JCheckBox withThreadsForTest() {
        return withThreads;
    }

    /** Follows the lines with this ID (in the background). */
    void follow(String name, String value) {
        if (running != null) {
            running.set(true);
        }
        this.name = name;
        this.value = value;
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        title.setText(name + " " + value);
        title.setToolTipText(name + " " + value);
        summary.setText("Finding its lines…");
        showOnly.setEnabled(true);
        findAll.setEnabled(true);
        boolean threads = withThreads.isSelected();
        Thread t = new Thread(() -> {
            RequestTrace.Trace r = null;
            String error = null;
            try {
                r = host.follow(value, threads, cancel);
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            RequestTrace.Trace done = r;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                if (failed != null) {
                    summary.setText("Could not follow it: " + failed);
                } else if (done != null) {
                    show(done);
                }
            });
        }, "logsift-request");
        t.setDaemon(true);
        t.start();
    }

    void cancel() {
        if (running != null) {
            running.set(true);
            running = null;
        }
    }

    private void show(RequestTrace.Trace t) {
        trace = t;
        model.fireTableDataChanged();
        summary.setText(describe(t));
        if (t.longest() >= 0) { // where the time went
            table.scrollRectToVisible(table.getCellRect(t.longest(), 0, true));
        }
    }

    /** "14 lines · 3 threads · 10:02:13.120 → 10:02:21.400 (8.3 s) · 1 error · longest wait 8.0 s". */
    static String describe(RequestTrace.Trace t) {
        StringBuilder sb = new StringBuilder();
        int n = t.steps().size();
        sb.append(String.format("%,d line%s%s", n, n == 1 ? "" : "s", t.cut() ? " (the first)" : ""));
        if (!t.threads().isEmpty()) {
            sb.append("   ·   ").append(t.threads().size()).append(t.threads().size() == 1 ? " thread" : " threads");
        }
        if (t.duration() >= 0 && n > 1) {
            sb.append("   ·   ").append(clock(t.first())).append(" → ").append(clock(t.last()))
                    .append(" (").append(span(t.duration())).append(")");
        }
        if (t.errors() > 0) {
            sb.append("   ·   ").append(t.errors()).append(t.errors() == 1 ? " error" : " errors");
        }
        if (t.warnings() > 0) {
            sb.append("   ·   ").append(t.warnings()).append(t.warnings() == 1 ? " warning" : " warnings");
        }
        if (t.longest() >= 0 && t.duration() > 0) {
            long gap = t.steps().get(t.longest()).gap();
            if (gap * 3 >= t.duration() && gap >= 100) {
                sb.append("   ·   longest wait ").append(span(gap)).append(" (marked)");
            }
        }
        if (n == 0) {
            return "No lines with this ID.";
        }
        return sb.toString();
    }

    /** "8.3 s", "412 ms", "2 min 5 s". */
    static String span(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        if (ms < 60_000) {
            return String.format(java.util.Locale.ROOT, ms < 10_000 ? "%.2f s" : "%.1f s", ms / 1000.0);
        }
        return Insights.duration(ms);
    }

    /** "10:02:13.120" (UTC wall clock, as written). */
    static String clock(long t) {
        if (t == TimeFormat.NONE) {
            return "";
        }
        long ms = Math.floorMod(t, 86_400_000L);
        return String.format("%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Time", "After", "Thread", "Level", "Message"};

        @Override
        public int getRowCount() {
            return trace == null ? 0 : trace.steps().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Object getValueAt(int row, int col) {
            RequestTrace.Step s = trace.steps().get(row);
            return switch (col) {
                case 0 -> clock(s.time());
                case 1 -> s.gap() < 0 ? "" : "+" + span(s.gap());
                case 2 -> s.thread() == null ? "" : s.thread();
                case 3 -> s.level() == null ? "" : logsift.core.Levels.normalize(s.level());
                default -> host.message(s.text());
            };
        }
    }

    /** Lines without the ID are dimmed; the step after the longest wait is marked. */
    private final class StepRenderer extends DefaultTableCellRenderer {
        private final boolean mono;

        StepRenderer(int align, boolean mono) {
            this.mono = mono;
            setHorizontalAlignment(align);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            setFont(mono ? Theme.monoFont(t.getFont().getSize2D()) : t.getFont());
            RequestTrace.Step s = trace == null || row >= trace.steps().size() ? null : trace.steps().get(row);
            boolean longest = trace != null && row == trace.longest() && trace.duration() > 0
                    && s != null && s.gap() * 3 >= trace.duration() && s.gap() >= 100;
            if (!sel) {
                setForeground(longest && col == 1 ? AMBER
                        : s != null && !s.withId() ? Theme.dimmedForeground() : t.getForeground());
            }
            if (longest && col == 1) {
                setFont(getFont().deriveFont(Font.BOLD));
                setToolTipText("The longest wait in this request: where the time went");
            } else {
                setToolTipText(s != null && !s.withId() ? "A line of the same thread, without the ID" : null);
            }
            return this;
        }
    }
}
