package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
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
import logsift.core.ColumnStats;
import logsift.core.LogFormat;

/**
 * The values of one column (threads, loggers, hosts, status codes...) with how often each
 * occurs. Double-click a value to show only its lines; "Hide" removes them.
 */
final class ValuesDialog {
    private ValuesDialog() {}

    private static ValuesDialog.Last last;

    /** The dialog opened most recently (for tests). */
    record Last(JDialog dialog, ColumnStats.Result[] result) {}

    static Last lastForTest() {
        return last;
    }

    static void show(LogTab tab, LogFormat format, String column) {
        Window owner = SwingUtilities.getWindowAncestor(tab);
        String title = column.substring(0, 1).toUpperCase(Locale.ROOT) + column.substring(1);
        JDialog dlg = new JDialog(owner, title + " values", java.awt.Dialog.ModalityType.MODELESS);
        JLabel status = new JLabel("Counting…");
        JProgressBar busy = new JProgressBar();
        busy.setIndeterminate(true);
        JTextField search = new JTextField(20);
        search.putClientProperty("JTextField.placeholderText", "search values");
        search.putClientProperty("JTextField.showClearButton", true);
        List<ColumnStats.Value> shown = new ArrayList<>();
        ColumnStats.Result[] result = new ColumnStats.Result[1];
        AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return shown.size();
            }

            @Override
            public int getColumnCount() {
                return 3;
            }

            @Override
            public String getColumnName(int c) {
                return new String[] {title, "Lines", "%"}[c];
            }

            @Override
            public Object getValueAt(int r, int c) {
                ColumnStats.Value v = shown.get(r);
                return switch (c) {
                    case 0 -> v.value();
                    case 1 -> String.format("%,d", v.count());
                    default -> result[0] == null || result[0].lines() == 0 ? ""
                            : String.format("%.1f%%", 100.0 * v.count() / result[0].lines());
                };
            }
        };
        Runnable refilter = () -> {
            shown.clear();
            if (result[0] != null) {
                String q = search.getText().toLowerCase(Locale.ROOT);
                for (ColumnStats.Value v : result[0].values()) {
                    if (q.isEmpty() || v.value().toLowerCase(Locale.ROOT).contains(q)) {
                        shown.add(v);
                    }
                }
            }
            model.fireTableDataChanged();
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    tab.filterByValue(shown.get(table.getSelectedRow()).value(), FilterDef.Action.SHOW);
                }
            }
        });
        JButton showOnly = new JButton("Show Only");
        showOnly.setToolTipText("Show only lines with this value (adds a filter)");
        showOnly.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                tab.filterByValue(shown.get(r).value(), FilterDef.Action.SHOW);
            }
        });
        JButton hide = new JButton("Hide");
        hide.setToolTipText("Hide lines with this value (adds a filter)");
        hide.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                tab.filterByValue(shown.get(r).value(), FilterDef.Action.HIDE);
            }
        });

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(search, BorderLayout.CENTER);
        top.add(status, BorderLayout.EAST);
        JPanel buttons = new JPanel();
        buttons.add(showOnly);
        buttons.add(hide);
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(top, BorderLayout.NORTH);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(busy, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(560, 520));
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        last = new Last(dlg, result);

        AtomicBoolean cancel = new AtomicBoolean();
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                cancel.set(true);
            }
        });
        Thread t = new Thread(() -> {
            ColumnStats.Result r = ColumnStats.count(tab.document(), format, column, cancel::get);
            SwingUtilities.invokeLater(() -> {
                result[0] = r;
                busy.setVisible(false);
                status.setText(String.format("%,d values in %,d lines (%,d ms)%s", r.values().size(),
                        r.lines(), r.millis(), r.truncated() ? ", some not counted" : ""));
                refilter.run();
            });
        }, "logsift-values");
        t.setDaemon(true);
        t.start();
    }
}
