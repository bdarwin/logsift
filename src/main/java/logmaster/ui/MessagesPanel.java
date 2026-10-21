package logmaster.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logmaster.core.MessageStats;

/**
 * "Top messages": every kind of log message with how often it occurs, most first. Variable
 * parts (numbers, IDs, names) are shown as ∗. Double-click to find all its lines; right-click
 * to show only these lines or hide them.
 */
final class MessagesPanel extends JPanel {

    interface Host {
        /** Runs the analysis (in the background) over the chosen lines. */
        MessageStats.Result analyze(boolean onlyFiltered, AtomicBoolean cancel,
                                    java.util.function.DoubleConsumer progress);

        /** "10:02:13" for a line, or its number when the log has no timestamps. */
        String describeLine(long line);

        void findAll(MessageStats.Template t);

        void filter(List<MessageStats.Template> ts, FilterDef.Action action);
    }

    private final Host host;
    private final JButton analyze = new JButton("Analyze");
    private final JComboBox<String> scope = new JComboBox<>(new String[] {"Whole file",
        "Lines shown by filters"});
    private final JComboBox<String> levels = new JComboBox<>(new String[] {"All levels",
        "Errors", "Warnings", "Errors and warnings"});
    private final JTextField search = new JTextField(18);
    private final JLabel status = new JLabel("Click Analyze to group the log's messages.");
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private MessageStats.Result result;
    private AtomicBoolean running;

    MessagesPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        analyze.addActionListener(e -> start());
        analyze.putClientProperty("JButton.buttonType", "default");
        scope.setMaximumSize(scope.getPreferredSize());
        scope.setToolTipText("Count all lines, or only the lines the filters and time range show");
        scope.addActionListener(e -> start());
        levels.setMaximumSize(levels.getPreferredSize());
        levels.addActionListener(e -> model.refilter());
        search.putClientProperty("JTextField.placeholderText", "search messages");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setMaximumSize(new Dimension(260, 30));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                model.refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                model.refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        bar.add(analyze);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(scope);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(levels);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(search);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(status);
        bar.add(Box.createHorizontalGlue());
        add(bar, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(false);
        int[] widths = {110, 56, 70, 900, 110, 110};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setCellRenderer(new CountRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(right());
        table.getColumnModel().getColumn(2).setCellRenderer(new LevelRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new MessageRenderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    MessageStats.Template t = selected().stream().findFirst().orElse(null);
                    if (t != null) {
                        host.findAll(t);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private static DefaultTableCellRenderer right() {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.RIGHT);
        return r;
    }

    MessageStats.Result result() {
        return result;
    }

    JTable tableForTest() {
        return table;
    }

    List<MessageStats.Template> shownTemplates() {
        return List.copyOf(model.rows);
    }

    boolean isRunning() {
        return running != null;
    }

    /** Analyzes again (in the background). */
    void start() {
        if (running != null) {
            running.set(true); // cancel the one in progress
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        boolean onlyFiltered = scope.getSelectedIndex() == 1;
        analyze.setEnabled(false);
        status.setText("Analyzing…");
        Thread t = new Thread(() -> {
            MessageStats.Result r = host.analyze(onlyFiltered, cancel, p -> SwingUtilities.invokeLater(() -> {
                if (running == cancel) {
                    status.setText(String.format("Analyzing… %d%%", (int) (p * 100)));
                }
            }));
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                analyze.setEnabled(true);
                analyze.setText("Analyze again");
                result = r;
                model.refilter();
                status.setText(String.format("%,d kinds of messages in %,d lines (%,d ms)%s",
                        r.templates().size(), r.lines(), r.millis(),
                        r.truncated() ? " · too many kinds, some were not counted" : ""));
            });
        }, "lm-messages");
        t.setDaemon(true);
        t.start();
    }

    void cancel() {
        if (running != null) {
            running.set(true);
        }
    }

    private List<MessageStats.Template> selected() {
        List<MessageStats.Template> out = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            out.add(model.rows.get(row));
        }
        return out;
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        List<MessageStats.Template> sel = selected();
        if (sel.isEmpty()) {
            return;
        }
        JPopupMenu m = new JPopupMenu();
        String what = sel.size() == 1 ? "These Lines" : "These " + sel.size() + " Messages";
        m.add(item("Find All", () -> host.findAll(sel.get(0)), sel.size() == 1));
        m.add(item("Show Only " + what, () -> host.filter(sel, FilterDef.Action.SHOW), true));
        m.add(item("Hide " + what, () -> host.filter(sel, FilterDef.Action.HIDE), true));
        m.add(item("Mark " + what, () -> host.filter(sel, FilterDef.Action.MARK), true));
        m.addSeparator();
        m.add(item("Copy Message", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(String.join(System.lineSeparator(),
                        sel.stream().map(MessageStats.Template::text).toList())), null), true));
        m.show(table, e.getX(), e.getY());
    }

    private static JMenuItem item(String name, Runnable r, boolean enabled) {
        JMenuItem mi = new JMenuItem(name);
        mi.setEnabled(enabled);
        mi.addActionListener(e -> r.run());
        return mi;
    }

    // ---------------------------------------------------------------- table

    private final class Model extends AbstractTableModel {
        final List<MessageStats.Template> rows = new ArrayList<>();
        long maxCount = 1;

        void refilter() {
            rows.clear();
            MessageStats.Result r = result;
            if (r != null) {
                String q = search.getText().toLowerCase(java.util.Locale.ROOT);
                int lv = levels.getSelectedIndex();
                for (MessageStats.Template t : r.templates()) {
                    String l = t.level();
                    boolean levelOk = switch (lv) {
                        case 1 -> l.equals("ERROR") || l.equals("FATAL");
                        case 2 -> l.equals("WARN");
                        case 3 -> l.equals("ERROR") || l.equals("FATAL") || l.equals("WARN");
                        default -> true;
                    };
                    if (levelOk && (q.isEmpty() || t.text().toLowerCase(java.util.Locale.ROOT).contains(q))) {
                        rows.add(t);
                    }
                }
            }
            maxCount = rows.isEmpty() ? 1 : rows.get(0).count();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 6;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Count", "%", "Level", "Message", "First", "Last"}[c];
        }

        @Override
        public Object getValueAt(int row, int c) {
            MessageStats.Template t = rows.get(row);
            return switch (c) {
                case 0 -> t.count();
                case 1 -> result == null || result.lines() == 0 ? ""
                        : String.format("%.1f%%", 100.0 * t.count() / result.lines());
                case 2 -> t.level();
                case 3 -> t.text();
                case 4 -> host.describeLine(t.firstLine());
                default -> host.describeLine(t.lastLine());
            };
        }
    }

    /** A count with a bar behind it, relative to the most frequent message. */
    private final class CountRenderer extends DefaultTableCellRenderer {
        private double fraction;

        CountRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                       int row, int col) {
            long count = (Long) v;
            fraction = (double) count / Math.max(1, model.maxCount);
            return super.getTableCellRendererComponent(t, String.format("%,d", count), sel, focus, row, col);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            Color bar = Theme.pick(new Color(0xD6E2F5), new Color(0x2B3D5C));
            g.setColor(bar);
            g.fillRect(0, 3, (int) (getWidth() * fraction), getHeight() - 6);
            setOpaque(false);
            super.paintComponent(g);
            setOpaque(true);
        }
    }

    static final class LevelRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            setFont(getFont().deriveFont(Font.BOLD));
            String l = String.valueOf(v);
            if (!sel) {
                setForeground(switch (l) {
                    case "ERROR", "FATAL" -> Theme.errorMark();
                    case "WARN" -> new Color(0xC27C00);
                    default -> Theme.gutterForeground();
                });
            }
            return this;
        }
    }

    /** The message in the log font, with the variable parts as a subtle ∗. */
    private static final class MessageRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                       int row, int col) {
            String text = String.valueOf(v).replace("&", "&amp;").replace("<", "&lt;")
                    .replace("&lt;*>", "<span style='color:#8A8F98'>∗</span>");
            super.getTableCellRendererComponent(t, "<html><nobr>" + text + "</nobr></html>", sel,
                    focus, row, col);
            setFont(Theme.monoFont(t.getFont().getSize2D()));
            return this;
        }
    }
}
