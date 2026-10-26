package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import logsift.core.Archives;

/**
 * A zip file as a tab: its folders and files, and the zips inside it, as a tree. Files open as
 * logs (with everything a log has); several can be merged by time, or all searched.
 */
final class ArchiveTab extends JPanel {

    /** What the tab asks of the window. */
    interface Host {
        void openEntries(Path zip, List<Archives.Entry> entries);

        void mergeEntries(Path zip, List<Archives.Entry> entries);

        /** Searches every file of the zip for {@code text} (empty: just opens the search). */
        void searchArchive(Path zip, String text);
    }

    /** A folder, a zip inside the zip, or a file. */
    private record Item(String name, Archives.Entry entry, boolean zip) {
        boolean isFile() {
            return entry != null && !zip;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Path zip;
    private final Host host;
    private final JTextField filter = new JTextField(22);
    private final JTextField find = new JTextField(28);
    private final JTree tree = new JTree(new DefaultMutableTreeNode());
    private final JLabel summary = new JLabel(" ");
    private final JButton open = new JButton("Open");
    private final JButton merge = new JButton("Merge by Time");
    private List<Archives.Entry> entries = List.of();

    ArchiveTab(Path zip, Host host) {
        super(new BorderLayout());
        this.zip = zip;
        this.host = host;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(0);
        tree.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (e.getClickCount() == 2 && p != null && item(p).isFile()) {
                    openSelected();
                }
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        tree.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        tree.addTreeSelectionListener(e -> updateButtons());

        filter.putClientProperty("JTextField.placeholderText", "Filter files by name");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuild();
            }
        });
        filter.addActionListener(e -> {
            List<Archives.Entry> files = shownFiles();
            if (files.size() == 1) {
                host.openEntries(zip, files);
            }
        });

        open.setToolTipText("Open the selected files as logs (Enter, or double-click a file)");
        open.addActionListener(e -> openSelected());
        merge.setToolTipText("Merge the selected logs into one, in time order");
        merge.addActionListener(e -> {
            List<Archives.Entry> sel = selectedFiles();
            if (sel.size() > 1) {
                host.mergeEntries(zip, sel);
            }
        });
        find.putClientProperty("JTextField.placeholderText", "Find in all files (Enter)");
        find.putClientProperty("JTextField.showClearButton", true);
        find.putClientProperty("JTextField.leadingIcon", Icons.find());
        find.setToolTipText("Search every file in this zip, also in zips and .gz files inside it ("
                + (MainFrame.isMac() ? "⌘F" : "Ctrl+F") + ")");
        find.addActionListener(e -> host.searchArchive(zip, find.getText()));
        JButton search = new JButton("Search");
        search.setToolTipText(find.getToolTipText());
        search.addActionListener(e -> host.searchArchive(zip, find.getText()));

        // two rows, so nothing is cut off in a narrow window: the zip and the search; then the files
        JPanel top = new JPanel(new java.awt.GridLayout(2, 1, 0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        JPanel first = new JPanel(new BorderLayout(8, 0));
        JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel name = new JLabel(zip.getFileName().toString(), Icons.of("folder-archive"), JLabel.LEFT);
        name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD));
        title.add(name);
        summary.setForeground(Theme.uiColor("Label.disabledForeground", java.awt.Color.GRAY));
        title.add(summary);
        first.add(title, BorderLayout.WEST);
        JPanel searching = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        searching.add(find);
        searching.add(search);
        first.add(searching, BorderLayout.EAST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(filter);
        actions.add(open);
        actions.add(merge);
        top.add(first);
        top.add(actions);

        JLabel hint = new JLabel("Double-click a file to open it as a log. Select several (Ctrl/⌘-click) to open"
                + " them together or merge them by time.");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", java.awt.Color.GRAY));
        hint.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));

        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0,
                Theme.uiColor("Separator.foreground", java.awt.Color.LIGHT_GRAY)));
        add(top, BorderLayout.NORTH);
        add(sp, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);
        updateButtons();
    }

    Path zip() {
        return zip;
    }

    String title() {
        return zip.getFileName().toString();
    }

    void setEntries(List<Archives.Entry> list) {
        entries = List.copyOf(list);
        long files = list.stream().filter(e -> !Archives.isZip(e.fileName())).count();
        long size = list.stream().filter(e -> !Archives.isZip(e.fileName()) && e.size() > 0)
                .mapToLong(Archives.Entry::size).sum();
        summary.setText(String.format("%,d file%s, %s unpacked", files, files == 1 ? "" : "s",
                MainFrame.humanBytes(size)));
        rebuild();
        if (tree.getRowCount() > 0) {
            for (int r = 0; r < tree.getRowCount(); r++) {
                if (item(tree.getPathForRow(r)).isFile()) {
                    tree.setSelectionRow(r);
                    break;
                }
            }
        }
    }

    /** Ctrl+F: the box that finds in all files. */
    void focusFind() {
        find.requestFocusInWindow();
        find.selectAll();
    }

    void setFindForTest(String text) {
        find.setText(text);
    }

    void runFindForTest() {
        find.postActionEvent();
    }

    void focusTree() {
        tree.requestFocusInWindow();
    }

    /** Builds the tree from the entries shown by the filter (all folders open). */
    private void rebuild() {
        String f = filter.getText().trim().toLowerCase(Locale.ROOT);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        Map<String, DefaultMutableTreeNode> nodes = new LinkedHashMap<>();
        for (Archives.Entry e : entries) {
            boolean isZip = Archives.isZip(e.fileName());
            if (!isZip && !f.isEmpty() && !e.label().toLowerCase(Locale.ROOT).contains(f)) {
                continue;
            }
            // the path through each level: zips inside and folders are nodes
            String[] levels = e.name().split(java.util.regex.Pattern.quote(Archives.NESTED), -1);
            DefaultMutableTreeNode parent = root;
            StringBuilder key = new StringBuilder();
            for (int l = 0; l < levels.length; l++) {
                String[] parts = levels[l].split("/");
                for (int i = 0; i < parts.length; i++) {
                    boolean last = l == levels.length - 1 && i == parts.length - 1;
                    key.append(i == 0 && l > 0 ? Archives.NESTED : key.length() == 0 ? "" : "/").append(parts[i]);
                    DefaultMutableTreeNode n = nodes.get(key.toString());
                    if (n == null) {
                        boolean zipNode = i == parts.length - 1 && (l < levels.length - 1 || isZip);
                        Item it = last ? new Item(parts[i], e, isZip) : new Item(parts[i], null, zipNode);
                        n = new DefaultMutableTreeNode(it);
                        nodes.put(key.toString(), n);
                        parent.add(n);
                    } else if (last && ((Item) n.getUserObject()).entry() == null) {
                        n.setUserObject(new Item(parts[i], e, isZip)); // a zip listed after its files
                    }
                    parent = n;
                }
            }
        }
        if (!f.isEmpty()) {
            prune(root); // zips and folders with nothing shown in them
        }
        sort(root);
        tree.setModel(new DefaultTreeModel(root));
        for (int r = 0; r < tree.getRowCount(); r++) {
            tree.expandRow(r);
        }
    }

    /** As a file browser does: folders and zips first, then by name. */
    private static void sort(DefaultMutableTreeNode n) {
        List<DefaultMutableTreeNode> kids = new ArrayList<>();
        for (int i = 0; i < n.getChildCount(); i++) {
            kids.add((DefaultMutableTreeNode) n.getChildAt(i));
        }
        kids.sort(java.util.Comparator.<DefaultMutableTreeNode>comparingInt(k -> ((Item) k.getUserObject()).isFile() ? 1 : 0)
                .thenComparing(k -> ((Item) k.getUserObject()).name(), String.CASE_INSENSITIVE_ORDER));
        n.removeAllChildren();
        for (DefaultMutableTreeNode k : kids) {
            n.add(k);
            sort(k);
        }
    }

    private static boolean prune(DefaultMutableTreeNode n) {
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(i);
            if (!prune(c)) {
                n.remove(i);
            }
        }
        Object o = n.getUserObject();
        return n.getChildCount() > 0 || o instanceof Item it && it.isFile();
    }

    private static Item item(TreePath p) {
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof Item it ? it : new Item("", null, false);
    }

    /** The files selected (a selected folder or zip: the files in it). */
    List<Archives.Entry> selectedFiles() {
        List<Archives.Entry> out = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) {
            return out;
        }
        for (TreePath p : paths) {
            collect((DefaultMutableTreeNode) p.getLastPathComponent(), out);
        }
        return out.stream().distinct().toList();
    }

    private List<Archives.Entry> shownFiles() {
        List<Archives.Entry> out = new ArrayList<>();
        collect((DefaultMutableTreeNode) tree.getModel().getRoot(), out);
        return out;
    }

    private static void collect(DefaultMutableTreeNode n, List<Archives.Entry> out) {
        if (n.getUserObject() instanceof Item it && it.isFile()) {
            out.add(it.entry());
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect((DefaultMutableTreeNode) n.getChildAt(i), out);
        }
    }

    void openSelected() {
        List<Archives.Entry> sel = selectedFiles();
        if (!sel.isEmpty()) {
            host.openEntries(zip, sel);
        }
    }

    private void updateButtons() {
        int n = selectedFiles().size();
        open.setEnabled(n > 0);
        open.setText(n > 1 ? "Open " + n : "Open");
        merge.setEnabled(n > 1);
    }

    // for tests
    List<String> shownForTest() {
        return shownFiles().stream().map(Archives.Entry::name).toList();
    }

    List<String> rowsForTest() {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < tree.getRowCount(); r++) {
            out.add("  ".repeat(tree.getPathForRow(r).getPathCount() - 2) + item(tree.getPathForRow(r)).name());
        }
        return out;
    }

    void selectForTest(String... names) {
        List<TreePath> sel = new ArrayList<>();
        for (int r = 0; r < tree.getRowCount(); r++) {
            Item it = item(tree.getPathForRow(r));
            if (it.entry() != null && List.of(names).contains(it.entry().name())) {
                sel.add(tree.getPathForRow(r));
            }
        }
        if (sel.size() != names.length) {
            throw new IllegalArgumentException(List.of(names) + " in " + rowsForTest());
        }
        tree.setSelectionPaths(sel.toArray(new TreePath[0]));
    }

    void setFilterForTest(String text) {
        filter.setText(text);
    }

    String summaryForTest() {
        return summary.getText();
    }

    JButton mergeButtonForTest() {
        return merge;
    }

    /** Folders, zips and files, each with its icon; files with their size. */
    private static final class Renderer extends DefaultTreeCellRenderer {
        private final javax.swing.Icon zipIcon = Icons.of("folder-archive");
        private final javax.swing.Icon fileIcon = Icons.of("file-text");
        private final javax.swing.Icon folderIcon = Icons.of("folder");

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof Item it) {
                String size = it.entry() != null && it.entry().size() >= 0
                        ? "   " + MainFrame.humanBytes(it.entry().size()) : "";
                String gray = sel ? "" : " style='color:gray'";
                setText("<html>" + esc(it.name()) + (size.isEmpty() ? "" : "<span" + gray + ">" + size + "</span>")
                        + "</html>");
                setIcon(it.zip() ? zipIcon : it.isFile() ? fileIcon : folderIcon);
            }
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }
}
