package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.Archives;

/**
 * What is in a zip file (zips inside it too): pick files to open, or search them all.
 */
final class ArchiveDialog extends JDialog {
    private final Consumer<List<Archives.Entry>> open;
    private final JTextField filter = new JTextField(24);
    private final JLabel status = new JLabel("Reading the zip…");
    private final JButton openButton = new JButton("Open");
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private List<Archives.Entry> all = List.of();

    /**
     * @param open gets the files chosen (zips inside are not offered; their files are)
     * @param search searches every file in the zip
     */
    ArchiveDialog(Window owner, Path zip, Consumer<List<Archives.Entry>> open, Runnable search) {
        super(owner, zip.getFileName().toString(), ModalityType.MODELESS);
        this.open = open;
        filter.putClientProperty("JTextField.placeholderText", "Filter by name");
        filter.putClientProperty("JTextField.showClearButton", true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(520);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value instanceof Long l ? (l < 0 ? "" : MainFrame.humanBytes(l)) : String.valueOf(value));
            }
        };
        right.setHorizontalAlignment(DefaultTableCellRenderer.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        table.getRowSorter().toggleSortOrder(0);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) {
                    openSelected();
                }
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        table.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> openButton.setEnabled(table.getSelectedRowCount() > 0));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
        filter.addActionListener(e -> {
            if (table.getSelectedRowCount() == 0 && table.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
            }
            openSelected();
        });

        openButton.setEnabled(false);
        openButton.addActionListener(e -> openSelected());
        JButton searchButton = new JButton("Search in It…");
        searchButton.setToolTipText("Search every file in this zip (zips inside it too)");
        searchButton.addActionListener(e -> {
            dispose();
            search.run();
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(new JLabel("<html>Files in <b>" + esc(zip.getFileName().toString()) + "</b></html>"), BorderLayout.WEST);
        top.add(filter, BorderLayout.EAST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(searchButton);
        buttons.add(close);
        buttons.add(openButton);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.WEST);
        bottom.add(buttons, BorderLayout.EAST);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(top, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(680, 360));
        p.add(sp, BorderLayout.CENTER);
        p.add(bottom, BorderLayout.SOUTH);
        setContentPane(p);
        getRootPane().setDefaultButton(openButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    void setEntries(List<Archives.Entry> entries) {
        List<Archives.Entry> files = new ArrayList<>();
        for (Archives.Entry e : entries) {
            if (!Archives.isZip(e.fileName())) {
                files.add(e); // a zip inside: its files are listed
            }
        }
        all = files;
        refilter();
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
        filter.requestFocusInWindow();
    }

    private void refilter() {
        String f = filter.getText().trim().toLowerCase(Locale.ROOT);
        List<Archives.Entry> shown = new ArrayList<>();
        for (Archives.Entry e : all) {
            if (f.isEmpty() || e.label().toLowerCase(Locale.ROOT).contains(f)) {
                shown.add(e);
            }
        }
        model.set(shown);
        status.setText(all.isEmpty() ? "No files in it" : shown.size() == all.size()
                ? String.format("%,d files", all.size()) : String.format("%,d of %,d files", shown.size(), all.size()));
    }

    void openSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        List<Archives.Entry> chosen = new ArrayList<>();
        for (int r : rows) {
            chosen.add(model.rows.get(table.convertRowIndexToModel(r)));
        }
        dispose();
        SwingUtilities.invokeLater(() -> open.accept(chosen));
    }

    // for tests
    List<String> shownForTest() {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < table.getRowCount(); r++) {
            out.add(model.rows.get(table.convertRowIndexToModel(r)).name());
        }
        return out;
    }

    void selectForTest(String name) {
        for (int r = 0; r < table.getRowCount(); r++) {
            if (model.rows.get(table.convertRowIndexToModel(r)).name().equals(name)) {
                table.setRowSelectionInterval(r, r);
                return;
            }
        }
        throw new IllegalArgumentException(name);
    }

    void setFilterForTest(String text) {
        filter.setText(text);
    }

    String statusForTest() {
        return status.getText();
    }

    private static final class Model extends AbstractTableModel {
        private List<Archives.Entry> rows = List.of();

        void set(List<Archives.Entry> r) {
            rows = r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Name" : "Size";
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? String.class : Long.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Archives.Entry e = rows.get(r);
            return c == 0 ? e.label() : (Object) e.size();
        }
    }
}
