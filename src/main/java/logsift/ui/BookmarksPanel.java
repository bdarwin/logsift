package logsift.ui;

import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;

/** The list of bookmarks: line, time, note (editable) and the line's text. */
final class BookmarksPanel extends JPanel {
    private final Bookmarks marks;
    private final LongFunction<String> timeOf;
    private final LongFunction<String> textOf;
    private final List<Long> lines = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    BookmarksPanel(Bookmarks marks, LongFunction<String> timeOf, LongFunction<String> textOf,
                   LongConsumer goTo) {
        super(new BorderLayout());
        this.marks = marks;
        this.timeOf = timeOf;
        this.textOf = textOf;
        JLabel hint = new JLabel("Ctrl+F2 bookmarks the current line · F2 / Shift+F2 go to the next / "
                + "previous · double-click to go there · type a note in the Note column");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        hint.setForeground(Theme.gutterForeground());
        add(hint, BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        int[] widths = {80, 160, 260, 900};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = table.getSelectedRow();
                if (e.getClickCount() == 2 && r >= 0 && table.columnAtPoint(e.getPoint()) != 2) {
                    goTo.accept(lines.get(r));
                }
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "remove");
        table.getActionMap().put("remove", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int[] rows = table.getSelectedRows();
                for (int i = rows.length - 1; i >= 0; i--) {
                    marks.remove(lines.get(rows[i]));
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        marks.addListener(this::refresh);
        refresh();
    }

    void refresh() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        lines.clear();
        lines.addAll(marks.all().keySet());
        model.fireTableDataChanged();
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return lines.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Line", "Time", "Note", "Text"}[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 2;
        }

        @Override
        public Object getValueAt(int r, int c) {
            long line = lines.get(r);
            return switch (c) {
                case 0 -> String.format("%,d", line + 1);
                case 1 -> timeOf.apply(line);
                case 2 -> marks.all().getOrDefault(line, "");
                default -> textOf.apply(line);
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 2 && r < lines.size()) {
                marks.set(lines.get(r), String.valueOf(v));
            }
        }
    }

    Map<Long, String> forTest() {
        return marks.all();
    }
}
