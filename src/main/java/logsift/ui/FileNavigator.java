package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import logsift.core.Archives;
import logsift.core.FileBrowse;

/**
 * A file browser as a tree: the home folder and the disks, opened a level at a time (in the
 * background, so a slow network drive never stops the window). Zips open like folders, and so
 * do the zips inside them. A path can be typed (or pasted) above the tree, and a file dropped on
 * it is shown and selected. One side of a comparison; folder comparisons will use it too.
 */
final class FileNavigator extends JPanel {

    /** A node of the tree: an item, or (no item) the "Loading…" or "Can't read" line under one. */
    static final class Node extends DefaultMutableTreeNode {
        final FileBrowse.Item item;
        final String note;
        FileBrowse.ZipTree zip; // the zip it is in (or, for a zip on disk, once it is read)
        boolean loaded;
        boolean loading;

        Node(FileBrowse.Item item) {
            super(item);
            this.item = item;
            this.note = null;
            setAllowsChildren(item == null || item.kind() != FileBrowse.Kind.FILE);
        }

        Node(String note) {
            super(note);
            this.item = null;
            this.note = note;
            setAllowsChildren(false);
        }

        @Override
        public String toString() {
            return item != null ? item.name() : note != null ? note : "";
        }
    }

    /** Windows' file names don't care about case (a typed c:\\users finds C:\\Users). */
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private final Node root = new Node((FileBrowse.Item) null);
    private final DefaultTreeModel model = new DefaultTreeModel(root, true);
    private final JTree tree = new JTree(model);
    private final JTextField pathField = new JTextField();
    private final JLabel info = new JLabel(" ");
    private final List<AtomicBoolean> work = new ArrayList<>();
    private Consumer<FileBrowse.Item> onSelect = i -> { };
    private Consumer<FileBrowse.Item> onOpen = i -> { };
    private FileBrowse.Item selected;
    /** Where a reveal is going (a newer one replaces it). */
    private int revealing;

    FileNavigator(String title, Color accent) {
        super(new BorderLayout(0, 4));
        JLabel head = new JLabel(title);
        head.setFont(head.getFont().deriveFont(Font.BOLD));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        pathField.putClientProperty("JTextField.placeholderText", "a path: type or paste it, or drop a file here");
        pathField.setToolTipText("Press Enter to go to this folder or file");
        pathField.addActionListener(e -> {
            String t = pathField.getText().strip();
            if (!t.isEmpty()) {
                reveal(Path.of(expandHome(t)));
            }
        });
        top.add(pathField, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
                BorderFactory.createEmptyBorder(4, 8, 4, 4)));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(0);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                load((Node) e.getPath().getLastPathComponent(), null);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {}
        });
        tree.addTreeSelectionListener(e -> {
            TreePath p = tree.getSelectionPath();
            Node n = p == null ? null : (Node) p.getLastPathComponent();
            select(n == null ? null : n.item);
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && selected != null && selected.isFile()) {
                    onOpen.accept(selected);
                }
            }
        });
        JScrollPane sp = new JScrollPane(tree);
        add(sp, BorderLayout.CENTER);
        info.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        add(info, BorderLayout.SOUTH);

        for (Path p : roots()) {
            Node n = new Node(new FileBrowse.Item(rootName(p), p, FileBrowse.Kind.FOLDER, -1, false));
            root.add(n);
        }
        model.reload();
    }

    /** The home folder, then each disk. */
    static List<Path> roots() {
        List<Path> out = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null && Files.isDirectory(Path.of(home))) {
            out.add(Path.of(home).toAbsolutePath().normalize());
        }
        for (File r : File.listRoots()) {
            Path p = r.toPath();
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static String rootName(Path p) {
        String home = System.getProperty("user.home");
        if (home != null && p.equals(Path.of(home).toAbsolutePath().normalize())) {
            return "Home (" + (p.getFileName() == null ? p : p.getFileName()) + ")";
        }
        return p.toString();
    }

    private static String expandHome(String t) {
        String home = System.getProperty("user.home");
        return home != null && (t.equals("~") || t.startsWith("~/")) ? home + t.substring(1) : t;
    }

    /** A size for people (as the window's status bar writes it), without needing the window. */
    static String bytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.2f %s", v, u[i]);
    }

    /** Called with the item selected (null: none). */
    void onSelect(Consumer<FileBrowse.Item> c) {
        onSelect = c;
    }

    /** Called when a file is double-clicked. */
    void onOpen(Consumer<FileBrowse.Item> c) {
        onOpen = c;
    }

    /** The item selected, or null. */
    FileBrowse.Item selected() {
        return selected;
    }

    private void select(FileBrowse.Item item) {
        selected = item;
        if (item != null) {
            if (!pathField.isFocusOwner()) { // never over what someone is typing
                pathField.setText(item.path().toString());
            }
            info.setText(item.kind() == FileBrowse.Kind.FILE
                    ? (item.size() >= 0 ? bytes(item.size()) : "") + (item.inZip() ? " · in a zip" : "")
                    : item.kind() == FileBrowse.Kind.ZIP ? "a zip: open it to choose a file in it" : "a folder");
        } else {
            info.setText(" ");
        }
        onSelect.accept(item);
    }

    // ---------------------------------------------------------------- loading levels

    /** Lists a node's children (once), then runs {@code then} on the window's thread. */
    private void load(Node n, Runnable then) {
        if (n.item == null || n.loaded || !n.getAllowsChildren()) {
            if (then != null) {
                then.run();
            }
            return;
        }
        if (n.loading) {
            if (then != null) {
                pending.computeIfAbsent(n, k -> new ArrayList<>()).add(then);
            }
            return;
        }
        n.loading = true;
        if (then != null) {
            pending.computeIfAbsent(n, k -> new ArrayList<>()).add(then);
        }
        n.removeAllChildren();
        n.add(new Node("Loading…"));
        model.nodeStructureChanged(n);
        AtomicBoolean stop = new AtomicBoolean();
        work.add(stop);
        FileBrowse.Item item = n.item;
        FileBrowse.ZipTree inZip = n.zip;
        Thread t = new Thread(() -> {
            List<FileBrowse.Item> kids;
            FileBrowse.ZipTree zt = inZip;
            String error = null;
            try {
                if (item.inZip()) {
                    kids = zt.children(item);
                } else if (item.kind() == FileBrowse.Kind.ZIP) {
                    zt = FileBrowse.ZipTree.read(item.path(), stop::get);
                    kids = zt.children("");
                } else {
                    kids = FileBrowse.folder(item.path(), false, stop::get);
                }
            } catch (IOException | RuntimeException e) {
                kids = List.of();
                error = "Can't read it: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            List<FileBrowse.Item> got = kids;
            FileBrowse.ZipTree tz = zt;
            String err = error;
            SwingUtilities.invokeLater(() -> {
                work.remove(stop);
                if (stop.get()) {
                    return;
                }
                n.loading = false;
                n.loaded = true;
                n.zip = tz;
                n.removeAllChildren();
                for (FileBrowse.Item k : got) {
                    Node c = new Node(k);
                    c.zip = k.inZip() ? tz : null;
                    n.add(c);
                }
                if (err != null) {
                    n.add(new Node(err));
                } else if (got.isEmpty()) {
                    n.add(new Node("(empty)"));
                }
                model.nodeStructureChanged(n);
                List<Runnable> next = pending.remove(n);
                if (next != null) {
                    next.forEach(Runnable::run);
                }
            });
        }, "logsift-browse");
        t.setDaemon(true);
        t.start();
    }

    private final java.util.Map<Node, List<Runnable>> pending = new java.util.HashMap<>();

    // ---------------------------------------------------------------- going to a path

    /**
     * Opens the tree down to {@code p} (a folder, a file, or a file in a zip as
     * {@link Archives#pathOf} names it) and selects it; a path that isn't there selects the
     * nearest folder above it.
     */
    void reveal(Path p) {
        Path target = p.toAbsolutePath().normalize();
        int gen = ++revealing;
        Node best = null;
        int bestLen = -1;
        for (int i = 0; i < root.getChildCount(); i++) {
            Node r = (Node) root.getChildAt(i);
            if (target.startsWith(r.item.path()) && r.item.path().getNameCount() > bestLen) {
                best = r;
                bestLen = r.item.path().getNameCount();
            }
        }
        if (best == null) {
            pathField.setText(target.toString());
            info.setText("Not on this computer's disks");
            return;
        }
        List<String> rest = new ArrayList<>();
        for (Path part : best.item.path().relativize(target)) {
            if (!part.toString().isEmpty()) {
                rest.add(part.toString());
            }
        }
        step(best, rest, 0, gen);
    }

    private void step(Node at, List<String> rest, int i, int gen) {
        if (gen != revealing) {
            return;
        }
        if (i == rest.size()) {
            show(at);
            return;
        }
        load(at, () -> {
            if (gen != revealing) {
                return;
            }
            for (int k = 0; k < at.getChildCount(); k++) {
                Node c = (Node) at.getChildAt(k);
                if (c.item != null && (c.item.name().equals(rest.get(i))
                        || WINDOWS && !c.item.inZip() && c.item.name().equalsIgnoreCase(rest.get(i)))) {
                    tree.expandPath(new TreePath(at.getPath()));
                    step(c, rest, i + 1, gen);
                    return;
                }
            }
            Node hidden = hiddenChild(at, rest.get(i));
            if (hidden != null) {
                tree.expandPath(new TreePath(at.getPath()));
                step(hidden, rest, i + 1, gen);
            } else {
                show(at); // not there: the nearest folder
            }
        });
    }

    /**
     * A hidden folder or file asked for by its path (Windows' AppData, ~/.config): added to the
     * level it is in, so a path typed or dropped is shown even there. Null if it isn't on disk.
     */
    private Node hiddenChild(Node at, String name) {
        if (at.item == null || at.item.inZip() || at.item.kind() != FileBrowse.Kind.FOLDER) {
            return null;
        }
        Path p = at.item.path().resolve(name);
        FileBrowse.Kind kind = Files.isDirectory(p) ? FileBrowse.Kind.FOLDER
                : Files.isRegularFile(p) ? (Archives.isZip(name) ? FileBrowse.Kind.ZIP : FileBrowse.Kind.FILE) : null;
        if (kind == null) {
            return null;
        }
        long size = -1;
        if (kind != FileBrowse.Kind.FOLDER) {
            try {
                size = Files.size(p);
            } catch (IOException e) {
                // shown without a size
            }
        }
        if (at.getChildCount() == 1 && ((Node) at.getChildAt(0)).item == null) {
            at.removeAllChildren(); // "(empty)"
        }
        Node n = new Node(new FileBrowse.Item(name, p, kind, size, false));
        at.add(n);
        model.nodeStructureChanged(at);
        return n;
    }

    private void show(Node n) {
        TreePath tp = new TreePath(n.getPath());
        if (n.getParent() != null && n.getParent() != root) {
            tree.expandPath(tp.getParentPath());
        }
        tree.setSelectionPath(tp);
        tree.scrollPathToVisible(tp);
        if (n.item != null && n.item.kind() != FileBrowse.Kind.FILE) {
            tree.expandPath(tp);
        }
    }

    /** Scrolls the tree to the item selected. */
    void scrollToSelected() {
        TreePath p = tree.getSelectionPath();
        if (p != null) {
            tree.scrollPathToVisible(p);
        }
    }

    /** Stops listings going on (the tab is closed). */
    void close() {
        revealing++;
        work.forEach(w -> w.set(true));
        work.clear();
        pending.clear();
    }

    JTree treeForTest() {
        return tree;
    }

    JTextField pathFieldForTest() {
        return pathField;
    }

    /** Whether a listing is going on. */
    boolean isLoadingForTest() {
        return !work.isEmpty();
    }

    /** The names shown under the selected node (or the top), for tests. */
    List<String> childrenForTest() {
        TreePath p = tree.getSelectionPath();
        Node n = p == null ? root : (Node) p.getLastPathComponent();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n.getChildCount(); i++) {
            out.add(n.getChildAt(i).toString());
        }
        return out;
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded, boolean leaf,
                                                      int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, focus);
            Node n = (Node) value;
            setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
            if (n.item == null) {
                setIcon(null);
                if (!sel) {
                    setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
                }
                return this;
            }
            setIcon(switch (n.item.kind()) {
                case FOLDER -> Icons.of(expanded ? "folder-open" : "folder");
                case ZIP -> Icons.of("folder-archive");
                case FILE -> Icons.of("file-text");
            });
            if (n.item.isFile() && n.item.size() >= 0) {
                String dim = sel ? "" : " style='color:#858b94'";
                setText("<html>" + esc(n.item.name()) + "&nbsp;&nbsp;<span" + dim + ">"
                        + bytes(n.item.size()) + "</span></html>");
            }
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
