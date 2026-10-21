package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.LogCompare;

/**
 * How one log differs from a baseline, by kind of message: new, gone, more or less often.
 * Double-click a row to find that message in the log.
 */
final class CompareTab extends JPanel {

    interface Host {
        /** Shows a message's lines in the log that has them (B if it occurs there, else A). */
        void showDiff(CompareTab tab, LogCompare.Diff diff);
    }

    final LogTab a;
    final LogTab b;
    private final Host host;
    private final JComboBox<String> show = new JComboBox<>(new String[] {"All differences",
        "New in this log", "More often", "Gone from this log", "Less often", "Everything"});
    private final JComboBox<String> levels = new JComboBox<>(new String[] {"All levels", "Errors",
        "Warnings", "Errors and warnings"});
    private final JTextField search = new JTextField(18);
    private final JLabel status = new JLabel("Comparing…");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final List<LogCompare.Diff> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile LogCompare.Result result;

    CompareTab(LogTab a, LogTab b, Host host) {
        super(new BorderLayout());
        this.a = a;
        this.b = b;
        this.host = host;
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel what = new JLabel("<html>Comparing <b>" + esc(b.title()) + "</b> with the baseline <b>"
                + esc(a.title()) + "</b>, by kind of message (counts scaled to the logs' lengths)</html>");
        what.setBorder(BorderFactory.createEmptyBorder(8, 10, 2, 10));
        what.setAlignmentX(LEFT_ALIGNMENT);
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        bar.setAlignmentX(LEFT_ALIGNMENT);
        show.setMaximumSize(show.getPreferredSize());
        levels.setMaximumSize(levels.getPreferredSize());
        search.putClientProperty("JTextField.placeholderText", "search messages");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setMaximumSize(new Dimension(260, 30));
        show.addActionListener(e -> refilter());
        levels.addActionListener(e -> refilter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        progress.setMaximumSize(new Dimension(160, 14));
        bar.add(show);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(levels);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(search);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(status);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(progress);
        bar.add(Box.createHorizontalGlue());
        top.add(what);
        top.add(bar);
        add(top, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setRowHeight(table.getRowHeight() + 4);
        int[] widths = {80, 70, 900, 110, 110};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setCellRenderer(new ChangeRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new MessagesPanel.LevelRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(3).setCellRenderer(right);
        table.getColumnModel().getColumn(4).setCellRenderer(right);
        DefaultTableCellRenderer mono = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                           int row, int col) {
                String text = esc(String.valueOf(v)).replace("&lt;*>", "<span style='color:#8A8F98'>∗</span>");
                super.getTableCellRendererComponent(t, "<html><nobr>" + text + "</nobr></html>", sel, focus, row, col);
                setFont(Theme.monoFont(t.getFont().getSize2D()));
                return this;
            }
        };
        table.getColumnModel().getColumn(2).setCellRenderer(mono);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = table.getSelectedRow();
                if (e.getClickCount() == 2 && r >= 0) {
                    host.showDiff(CompareTab.this, rows.get(r));
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        start();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    String title() {
        return "Compare: " + b.title() + " ↔ " + a.title();
    }

    LogCompare.Result result() {
        return result;
    }

    List<LogCompare.Diff> shownForTest() {
        return List.copyOf(rows);
    }

    private void start() {
        Thread t = new Thread(() -> {
            LogCompare.Result r = LogCompare.compare(a.document(), a.entryFormat(), b.document(),
                    b.entryFormat(), cancel::get,
                    f -> SwingUtilities.invokeLater(() -> progress.setValue((int) (f * 1000))));
            SwingUtilities.invokeLater(() -> {
                result = r;
                progress.setVisible(false);
                long news = r.diffs().stream().filter(d -> d.kind() == LogCompare.Kind.NEW).count();
                long more = r.diffs().stream().filter(d -> d.kind() == LogCompare.Kind.MORE).count();
                long gone = r.diffs().stream().filter(d -> d.kind() == LogCompare.Kind.GONE).count();
                status.setText(String.format("%d new · %d more often · %d gone   (%,d vs %,d entries, %,d ms)",
                        news, more, gone, r.entriesB(), r.entriesA(), r.millis()));
                refilter();
            });
        }, "logsift-compare");
        t.setDaemon(true);
        t.start();
    }

    private void refilter() {
        rows.clear();
        LogCompare.Result r = result;
        if (r != null) {
            String q = search.getText().toLowerCase(Locale.ROOT);
            for (LogCompare.Diff d : r.diffs()) {
                LogCompare.Kind k = d.kind();
                boolean kindOk = switch (show.getSelectedIndex()) {
                    case 0 -> k != LogCompare.Kind.SAME;
                    case 1 -> k == LogCompare.Kind.NEW;
                    case 2 -> k == LogCompare.Kind.MORE;
                    case 3 -> k == LogCompare.Kind.GONE;
                    case 4 -> k == LogCompare.Kind.LESS;
                    default -> true;
                };
                String l = d.level();
                boolean levelOk = switch (levels.getSelectedIndex()) {
                    case 1 -> l.equals("ERROR") || l.equals("FATAL");
                    case 2 -> l.equals("WARN");
                    case 3 -> l.equals("ERROR") || l.equals("FATAL") || l.equals("WARN");
                    default -> true;
                };
                if (kindOk && levelOk && (q.isEmpty() || d.text().toLowerCase(Locale.ROOT).contains(q))) {
                    rows.add(d);
                }
            }
        }
        model.fireTableDataChanged();
    }

    void close() {
        cancel.set(true);
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Change", "Level", "Message", "Baseline", "This log"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            LogCompare.Diff d = rows.get(r);
            return switch (c) {
                case 0 -> d;
                case 1 -> d.level();
                case 2 -> d.text();
                case 3 -> String.format("%,d", d.countA());
                default -> String.format("%,d", d.countB());
            };
        }
    }

    /** NEW (red), ×4.2 (amber), GONE (gray), ÷3 (blue), same. */
    private static final class ChangeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                       int row, int col) {
            LogCompare.Diff d = (LogCompare.Diff) v;
            String text;
            Color c;
            switch (d.kind()) {
                case NEW -> {
                    text = "NEW";
                    c = Theme.errorMark();
                }
                case GONE -> {
                    text = "GONE";
                    c = Theme.gutterForeground();
                }
                case MORE -> {
                    text = String.format("×%.1f", d.ratio());
                    c = new Color(0xC27C00);
                }
                case LESS -> {
                    text = String.format("÷%.1f", 1 / Math.max(1e-9, d.ratio()));
                    c = Theme.marker();
                }
                default -> {
                    text = "same";
                    c = Theme.gutterForeground();
                }
            }
            super.getTableCellRendererComponent(t, text, sel, focus, row, col);
            setFont(getFont().deriveFont(Font.BOLD));
            if (!sel) {
                setForeground(c);
            }
            return this;
        }
    }
}
