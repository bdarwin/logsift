package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import logsift.core.Insights;
import logsift.core.TimeFormat;

/**
 * What is going on in the log, at a glance: a one-line summary, then what stands out (error
 * bursts, new errors, silences, restarts, noise), each with buttons to look at it.
 */
final class InsightsPanel extends JPanel {

    interface Host {
        /** Works out the report (on a background thread); null if cancelled. */
        Insights.Report analyze(AtomicBoolean cancel, java.util.function.DoubleConsumer progress,
                                java.util.function.Consumer<String> step);

        void goToLine(long line);

        void showTimeRange(long from, long to);

        void findAll(logsift.core.MessageStats.Template t);

        void showOnly(logsift.core.MessageStats.Template t);

        void hide(logsift.core.MessageStats.Template t);

        /** Shows the exception that starts at this line in the Exceptions tab. */
        default void showException(long line) {}

        /** Shows the measurement with this line in the Metrics tab. */
        default void showMetric(long line) {}

        /** Finds all lines matching a regex. */
        default void findAllRegex(String regex) {}

        /** The report changed (for the tab title). */
        void reportChanged(Insights.Report report);
    }

    private static final Color RED = new Color(0xDC2626);
    private static final Color AMBER = new Color(0xD97706);
    private static final Color BLUE = new Color(0x2563EB);
    private static final Color GREEN = new Color(0x16A34A);

    private final Host host;
    private final JLabel summary = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JButton refresh = new JButton("Refresh", Icons.reload());
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JPanel list = new ListPanel();
    private Insights.Report report;
    private AtomicBoolean running;
    private long analyzedLines = -1;

    InsightsPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 8));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        top.add(summary, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        bar.setPreferredSize(new Dimension(120, 10));
        bar.setVisible(false);
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.setToolTipText("Look through the log again (it may have grown)");
        refresh.addActionListener(e -> start());
        right.add(status);
        right.add(bar);
        right.add(refresh);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        showMessage("Insights show what stands out in the log: error bursts, new errors, silences, restarts.");
    }

    Insights.Report report() {
        return report;
    }

    boolean isRunning() {
        return running != null;
    }

    /** Lines in the log when the report was made (-1: none yet). */
    long analyzedLines() {
        return analyzedLines;
    }

    /** Says the log has more lines than the report covers. */
    void setStale(long newLines) {
        if (report != null && running == null) {
            status.setText(newLines > 0 ? String.format("%,d new lines since", newLines) : " ");
        }
    }

    /** Looks through the log (again). */
    void start() {
        if (running != null) {
            running.set(true);
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        bar.setValue(0);
        bar.setVisible(true);
        refresh.setEnabled(false);
        status.setText("Looking through the log…");
        Thread t = new Thread(() -> {
            Insights.Report r = null;
            Throwable error = null;
            try {
                r = host.analyze(cancel,
                        p -> SwingUtilities.invokeLater(() -> bar.setValue((int) (p * 1000))),
                        s -> SwingUtilities.invokeLater(() -> {
                            if (running == cancel) {
                                status.setText(s);
                            }
                        }));
            } catch (RuntimeException e) {
                error = e;
            }
            Insights.Report done = r;
            Throwable failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return; // a newer run took over
                }
                running = null;
                bar.setVisible(false);
                refresh.setEnabled(true);
                if (failed != null) {
                    status.setText("Could not look through the log: " + failed.getMessage());
                } else if (done != null) {
                    show(done);
                } else {
                    status.setText(" ");
                }
            });
        }, "logsift-insights");
        t.setDaemon(true);
        t.start();
    }

    void cancel() {
        if (running != null) {
            running.set(true);
            running = null;
        }
    }

    /** Forgets the report (the file was replaced). */
    void clear() {
        cancel();
        report = null;
        analyzedLines = -1;
        summary.setText(" ");
        status.setText(" ");
        bar.setVisible(false);
        refresh.setEnabled(true);
        showMessage("The file was replaced. Click Refresh to look through it again.");
        host.reportChanged(null);
    }

    private void show(Insights.Report r) {
        report = r;
        analyzedLines = r.lines();
        summary.setText(r.summary());
        status.setText(" ");
        list.removeAll();
        for (Insights.Finding f : r.findings()) {
            list.add(row(f));
        }
        list.add(Box.createVerticalGlue());
        list.revalidate();
        list.repaint();
        host.reportChanged(r);
    }

    private void showMessage(String text) {
        list.removeAll();
        JLabel l = new JLabel(text);
        l.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        l.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
        l.setAlignmentX(LEFT_ALIGNMENT);
        list.add(l);
        list.revalidate();
        list.repaint();
    }

    // ---------------------------------------------------------------- one finding

    private JPanel row(Insights.Finding f) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(6, 4, 6, 4)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridheight = 2;
        c.anchor = GridBagConstraints.NORTH;
        c.insets = new Insets(1, 2, 0, 10);
        JLabel icon = new JLabel(iconFor(f));
        p.add(icon, c);

        JLabel title = new JLabel(f.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        p.setToolTipText(f.template() == null ? null : f.template().text().replace("<*>", "∗"));
        c.gridx = 1;
        c.gridheight = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 8);
        p.add(title, c);
        JLabel detail = new JLabel(f.detail().isEmpty() ? " " : f.detail());
        detail.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        c.gridy = 1;
        p.add(detail, c);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.setOpaque(false);
        if (f.from() != TimeFormat.NONE && f.to() != TimeFormat.NONE) {
            actions.add(action("Show these minutes", "Show only the lines of this time (clear it with Clear all)",
                    () -> host.showTimeRange(f.from(), f.to())));
        }
        if (f.template() != null && f.kind() != Insights.Kind.NOISE) {
            actions.add(action("Find all", "Find all lines of this message", () -> host.findAll(f.template())));
            actions.add(action("Show only", "Show only lines of this message", () -> host.showOnly(f.template())));
        }
        if (f.template() != null) {
            actions.add(action("Hide", "Hide lines of this message", () -> host.hide(f.template())));
        }
        if (f.kind() == Insights.Kind.UNFINISHED || f.kind() == Insights.Kind.SPIKE) {
            actions.add(action("Show in Metrics", "Its chart over time, and the lines", () -> host.showMetric(f.line())));
        }
        if (f.known() != null) {
            actions.add(action("What to Try", "What this usually means, and what to try",
                    () -> explain(f.known())));
            actions.add(action("Find all", "Find all lines that show it",
                    () -> host.findAllRegex(f.known().pattern().pattern())));
        }
        if (f.kind() == Insights.Kind.EXCEPTION) {
            actions.add(action("Show in Exceptions", "Its stack, where it was thrown, and every time it happened",
                    () -> host.showException(f.line())));
        }
        if (f.line() >= 0) {
            actions.add(action("Go to", "Go to the line", () -> host.goToLine(f.line())));
        }
        c.gridx = 2;
        c.gridy = 0;
        c.gridheight = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        p.add(actions, c);

        if (f.line() >= 0) { // a click on the row goes there
            p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            p.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    host.goToLine(f.line());
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    p.setOpaque(true);
                    p.setBackground(Theme.uiColor("List.hoverBackground",
                            Theme.uiColor("Table.selectionInactiveBackground", new Color(0xEEF2F7))));
                    p.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (!p.contains(e.getPoint())) {
                        p.setOpaque(false);
                        p.repaint();
                    }
                }
            });
        }
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** What a known problem usually means, and what to try. */
    private void explain(logsift.core.KnownProblems.Signature k) {
        javax.swing.JEditorPane text = new javax.swing.JEditorPane("text/html", "<html><body style='width:460px'>"
                + "<p><b>What it usually means</b></p><p>" + esc(k.why()) + "</p>"
                + "<p style='margin-top:10px'><b>What to try</b></p><p>" + esc(k.tryThis()) + "</p></body></html>");
        text.setEditable(false);
        text.setOpaque(false);
        text.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        javax.swing.JOptionPane.showMessageDialog(this, text, k.title(), javax.swing.JOptionPane.INFORMATION_MESSAGE,
                Icons.of("lightbulb", AMBER, 28));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static JButton action(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setToolTipText(tip);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static Icon iconFor(Insights.Finding f) {
        Color color = switch (f.severity()) {
            case ERROR -> RED;
            case WARN -> AMBER;
            case INFO -> BLUE;
            case OK -> GREEN;
        };
        String name = switch (f.kind()) {
            case BURST -> "flame";
            case NEW_ERROR -> "sparkles";
            case EXCEPTION -> "bug";
            case KNOWN -> "lightbulb";
            case UNFINISHED -> "timer";
            case SPIKE -> "activity";
            case ERROR_KIND -> "circle-alert";
            case WARNING_KIND -> "triangle-alert";
            case SILENCE -> "circle-pause";
            case RESTART -> "power";
            case NOISE -> "volume-2";
            case CALM -> "circle-check";
        };
        return Icons.of(name, color, 20);
    }

    /** Test access. */
    JPanel listForTest() {
        return list;
    }

    /** Rows that fill the width and scroll down (not sideways). */
    private static final class ListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(16, o == SwingConstants.VERTICAL ? r.height - 16 : r.width - 16);
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
}
