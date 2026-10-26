package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.RemoteBrowser;
import logsift.core.RemoteMirror;

/**
 * Open a log on another machine: connect (host, user, password or key), then pick the file in
 * a browser: type a folder like {@code /var/log} in the address bar and press Enter, or
 * double-click folders. Remembers everything but passwords, and the last folder per server.
 */
final class RemoteDialog extends JDialog {

    /** What the user chose: where, and how much to load (0 = the whole file). */
    record Choice(RemoteMirror.Target target, long lastBytes) {}

    private final Preferences p = AppState.prefs();
    private final Predicate<String> trustHost;
    private final Path knownHosts;
    private final Consumer<Choice> onChosen;

    private final JTextField host = new JTextField(p.get("remote.host", ""), 22);
    private final JTextField port = new JTextField(p.get("remote.port", "22"), 4);
    private final JTextField user = new JTextField(p.get("remote.user", System.getProperty("user.name")), 12);
    private final JRadioButton usePassword = new JRadioButton("Password");
    private final JRadioButton useKey = new JRadioButton("Private key");
    private final JPasswordField password = new JPasswordField(16);
    private final JTextField key = new JTextField(24);
    private final JPasswordField passphrase = new JPasswordField(16);
    private final JButton connect = new JButton("Connect");
    private final JLabel connectStatus = new JLabel(" ");

    private final JTextField address = new JTextField(40);
    private final JButton up = new JButton(Icons.up());
    private final JLabel listStatus = new JLabel(" ");
    private final List<RemoteBrowser.Entry> entries = new ArrayList<>();
    private final EntryModel model = new EntryModel();
    private final JTable table = new JTable(model);

    private final JRadioButton tail = new JRadioButton("Last", true);
    private final JRadioButton whole = new JRadioButton("Whole file");
    private final JSpinner mb = new JSpinner(new SpinnerNumberModel(p.getInt("remote.lastMb", 50), 1, 100_000, 10));
    private final JButton open = new JButton("Open");

    /** What the dialog is for. */
    enum Mode { LOG, HEAP_DUMP, FOLDER }

    private final Mode mode;
    private final boolean heapDump;
    private RemoteBrowser browser;
    private RemoteMirror.Target connected;
    private String currentDir;

    RemoteDialog(Window owner, Path knownHosts, Predicate<String> trustHost, Consumer<Choice> onChosen) {
        this(owner, knownHosts, trustHost, onChosen, false);
    }

    /** {@code heapDump}: for choosing a heap dump to read on the server (nothing is loaded here). */
    RemoteDialog(Window owner, Path knownHosts, Predicate<String> trustHost, Consumer<Choice> onChosen,
                 boolean heapDump) {
        this(owner, knownHosts, trustHost, onChosen, heapDump ? Mode.HEAP_DUMP : Mode.LOG, null);
    }

    /** {@code title}: for {@link Mode#FOLDER} (choosing a folder on a server; the choice's path is it). */
    RemoteDialog(Window owner, Path knownHosts, Predicate<String> trustHost, Consumer<Choice> onChosen, Mode mode,
                 String title) {
        super(owner, title != null ? title : mode == Mode.HEAP_DUMP ? "Open Heap Dump on a Server (SSH)"
                : "Open Remote Log (SFTP)", ModalityType.MODELESS);
        this.mode = mode;
        this.heapDump = mode != Mode.LOG;
        this.knownHosts = knownHosts;
        this.trustHost = trustHost;
        this.onChosen = onChosen;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // ---- connection
        ButtonGroup g = new ButtonGroup();
        g.add(usePassword);
        g.add(useKey);
        String defaultKey = defaultKey();
        key.setText(p.get("remote.key", defaultKey));
        (p.getBoolean("remote.useKey", !defaultKey.isEmpty()) ? useKey : usePassword).setSelected(true);
        passphrase.putClientProperty("JTextField.placeholderText", "key passphrase, if any");
        host.putClientProperty("JTextField.placeholderText", "server name or address");
        JButton browseKey = new JButton("…");
        browseKey.setToolTipText("Choose a private key file");
        browseKey.addActionListener(e -> {
            JFileChooser ch = new JFileChooser(Path.of(System.getProperty("user.home"), ".ssh").toFile());
            ch.setFileHidingEnabled(false);
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                key.setText(ch.getSelectedFile().toString());
                useKey.setSelected(true);
            }
        });
        whole.setToolTipText("<html>Reads all of it from the server first: for a log of many GB, that is a big read"
                + "<br>of the server's disk and network. The last few MB are usually enough.</html>");
        connect.putClientProperty("JButton.buttonType", "default");
        connect.addActionListener(e -> connect());
        host.addActionListener(e -> connect());
        password.addActionListener(e -> connect());

        JPanel conn = new JPanel();
        conn.setLayout(new javax.swing.BoxLayout(conn, javax.swing.BoxLayout.Y_AXIS));
        conn.setBorder(BorderFactory.createTitledBorder("Server"));
        conn.add(line(new JLabel("Host"), host, new JLabel("Port"), port, new JLabel("User"), user));
        conn.add(line(usePassword, password));
        conn.add(line(useKey, key, browseKey, new JLabel("Passphrase"), passphrase));
        conn.add(line(connect, connectStatus));

        // ---- browser
        address.putClientProperty("JTextField.placeholderText", "type a folder or file, like /var/log, and press Enter");
        address.addActionListener(e -> go(address.getText()));
        up.setToolTipText("Up one folder (Backspace)");
        up.addActionListener(e -> go(currentDir == null ? "/" : RemoteBrowser.parent(currentDir)));
        JButton goButton = new JButton("Go");
        goButton.addActionListener(e -> go(address.getText()));
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.add(up, BorderLayout.WEST);
        bar.add(address, BorderLayout.CENTER);
        bar.add(goButton, BorderLayout.EAST);

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 4);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    activate(table.getSelectedRow());
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> updateOpenButton());
        bind(table, KeyEvent.VK_ENTER, () -> activate(table.getSelectedRow()));
        bind(table, KeyEvent.VK_BACK_SPACE, () -> go(currentDir == null ? "/" : RemoteBrowser.parent(currentDir)));
        JPanel files = new JPanel(new BorderLayout(0, 4));
        files.setBorder(BorderFactory.createTitledBorder("Files"));
        files.add(bar, BorderLayout.NORTH);
        files.add(new JScrollPane(table), BorderLayout.CENTER);
        files.add(listStatus, BorderLayout.SOUTH);

        // ---- load and open
        ButtonGroup g2 = new ButtonGroup();
        g2.add(tail);
        g2.add(whole);
        if (p.getBoolean("remote.whole", false)) {
            whole.setSelected(true);
        }
        open.putClientProperty("JButton.buttonType", "default");
        open.addActionListener(e -> openSelected());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel south = new JPanel(new BorderLayout());
        JPanel load = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        load.add(new JLabel("Load"));
        load.add(tail);
        load.add(mb);
        load.add(new JLabel("MB"));
        load.add(whole);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(open);
        if (heapDump) {
            open.setText(mode == Mode.FOLDER ? "Copy Here" : "Read It There…");
            JLabel hint = new JLabel(mode == Mode.FOLDER ? "Choose a folder with room for the dump and its index."
                    : "The dump is read on the server; you see the answers here.");
            hint.setForeground(Theme.uiColor("Label.disabledForeground", java.awt.Color.GRAY));
            south.add(hint, BorderLayout.WEST);
        } else {
            south.add(load, BorderLayout.WEST);
        }
        south.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(conn, BorderLayout.NORTH);
        root.add(files, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(new Dimension(760, 620));
        setLocationRelativeTo(owner);
        setBrowsing(false);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                closeBrowser();
            }
        });
    }

    /** One left-aligned row of the connection form. */
    private static JPanel line(JComponent... parts) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (JComponent c : parts) {
            row.add(c);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private static void bind(JComponent comp, int keyCode, Runnable r) {
        comp.getInputMap().put(KeyStroke.getKeyStroke(keyCode, 0), "k" + keyCode);
        comp.getActionMap().put("k" + keyCode, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }

    private void setBrowsing(boolean on) {
        address.setEnabled(on);
        up.setEnabled(on);
        table.setEnabled(on);
        updateOpenButton();
        if (!on) {
            listStatus.setText("Connect to a server to browse its files.");
        }
    }

    private void updateOpenButton() {
        int r = table.getSelectedRow();
        open.setEnabled(mode == Mode.FOLDER ? browser != null && currentDir != null
                : browser != null && r >= 0 && r < entries.size() && !entries.get(r).dir());
    }

    // ---------------------------------------------------------------- connecting and browsing

    void setConnection(String h, int prt, String u, String pw) { // for tests
        host.setText(h);
        port.setText(Integer.toString(prt));
        user.setText(u);
        usePassword.setSelected(true);
        password.setText(pw);
    }

    private RemoteMirror.Target targetFromFields(String path) {
        int portNo;
        try {
            portNo = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            portNo = 22;
        }
        return new RemoteMirror.Target(host.getText().trim(), portNo, user.getText().trim(), path,
                usePassword.isSelected() ? new String(password.getPassword()) : null,
                useKey.isSelected() && !key.getText().isBlank() ? Path.of(key.getText().trim()) : null,
                useKey.isSelected() && passphrase.getPassword().length > 0 ? new String(passphrase.getPassword()) : null);
    }

    void connect() {
        if (host.getText().isBlank()) {
            connectStatus.setText("Enter the server's name or address.");
            host.requestFocusInWindow();
            return;
        }
        closeBrowser();
        RemoteMirror.Target t = targetFromFields(null);
        connect.setEnabled(false);
        connectStatus.setText("Connecting to " + t.host() + "…");
        background(() -> {
            RemoteBrowser b = RemoteBrowser.connect(t, knownHosts, trustHost);
            String start = p.get("remote.dir." + t.host(), "");
            String dir;
            try {
                dir = b.resolve(start.isEmpty() ? "~" : start);
                if (!b.isDirectory(dir)) {
                    dir = b.home();
                }
            } catch (java.io.IOException e) {
                dir = b.home();
            }
            String startDir = dir;
            SwingUtilities.invokeLater(() -> {
                browser = b;
                connected = t;
                connect.setEnabled(true);
                connectStatus.setText("Connected to " + t.user() + "@" + t.host());
                p.put("remote.host", t.host());
                p.put("remote.port", Integer.toString(t.port()));
                p.put("remote.user", t.user());
                p.put("remote.key", key.getText().trim());
                p.putBoolean("remote.useKey", useKey.isSelected());
                setBrowsing(true);
                go(startDir);
            });
        }, e -> {
            connect.setEnabled(true);
            connectStatus.setText("Could not connect: " + e.getMessage());
        });
    }

    /**
     * Shows a folder; a file path selects it (and Enter on it opens it). Like {@code cd}, a
     * relative path is taken from the folder shown; "~" is the home folder.
     */
    void go(String typed0) {
        RemoteBrowser b = browser;
        if (b == null) {
            return;
        }
        String t0 = typed0 == null ? "" : typed0.trim();
        String typed = !t0.isEmpty() && !t0.startsWith("/") && !t0.startsWith("~") && currentDir != null
                ? RemoteBrowser.join(currentDir, t0) : t0;
        listStatus.setText("Reading…");
        background(() -> {
            String path = b.resolve(typed);
            if (b.isDirectory(path)) {
                List<RemoteBrowser.Entry> list = b.list(path);
                SwingUtilities.invokeLater(() -> show(path, list, null));
            } else {
                String dir = RemoteBrowser.parent(path);
                List<RemoteBrowser.Entry> list = b.list(dir);
                String name = path.substring(path.lastIndexOf('/') + 1);
                SwingUtilities.invokeLater(() -> show(dir, list, name));
            }
        }, e -> listStatus.setText(e.getMessage()));
    }

    private int listings; // for tests: how many listings were shown

    private void show(String dir, List<RemoteBrowser.Entry> list, String select) {
        if (select == null && dir.equals(currentDir)) {
            int r = table.getSelectedRow();
            if (r >= 0 && r < entries.size()) {
                select = entries.get(r).name(); // same folder again: keep the selection
            }
        }
        listings++;
        currentDir = dir;
        address.setText(dir);
        updateOpenButton();
        entries.clear();
        if (!dir.equals("/")) {
            entries.add(new RemoteBrowser.Entry("..", true, 0, 0));
        }
        entries.addAll(list);
        model.fireTableDataChanged();
        long dirs = list.stream().filter(RemoteBrowser.Entry::dir).count();
        listStatus.setText(String.format("%d folders, %d files", dirs, list.size() - dirs));
        p.put("remote.dir." + connected.host(), dir);
        int sel = -1;
        for (int i = 0; i < entries.size() && select != null; i++) {
            if (entries.get(i).name().equals(select)) {
                sel = i;
            }
        }
        if (sel >= 0) {
            table.setRowSelectionInterval(sel, sel);
            table.scrollRectToVisible(table.getCellRect(sel, 0, true));
        }
        table.requestFocusInWindow();
        updateOpenButton();
    }

    private void activate(int row) {
        if (row < 0 || row >= entries.size()) {
            return;
        }
        RemoteBrowser.Entry e = entries.get(row);
        if (e.dir()) {
            go(e.name().equals("..") ? RemoteBrowser.parent(currentDir) : RemoteBrowser.join(currentDir, e.name()));
        } else {
            openSelected();
        }
    }

    /** Opens the selected file (for tests: select it by name first). */
    void select(String name) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals(name)) {
                table.setRowSelectionInterval(i, i);
            }
        }
    }

    void openSelected() {
        if (mode == Mode.FOLDER) {
            if (connected != null && currentDir != null) {
                RemoteMirror.Target t = targetFromFields(currentDir);
                dispose();
                onChosen.accept(new Choice(t, 0));
            }
            return;
        }
        int r = table.getSelectedRow();
        if (connected == null || r < 0 || r >= entries.size() || entries.get(r).dir()) {
            return;
        }
        String path = RemoteBrowser.join(currentDir, entries.get(r).name());
        p.putInt("remote.lastMb", (Integer) mb.getValue());
        p.putBoolean("remote.whole", whole.isSelected());
        RemoteMirror.Target t = targetFromFields(path);
        long lastBytes = heapDump || whole.isSelected() ? 0 : (Integer) mb.getValue() * 1024L * 1024L;
        dispose();
        onChosen.accept(new Choice(t, lastBytes));
    }

    List<String> namesForTest() {
        return entries.stream().map(RemoteBrowser.Entry::name).toList();
    }

    String folderForTest() {
        return currentDir;
    }

    int listingsForTest() {
        return listings;
    }

    private void closeBrowser() {
        RemoteBrowser b = browser;
        browser = null;
        if (b != null) {
            new Thread(b::close, "logsift-ssh-close").start();
        }
    }

    private interface IoTask {
        void run() throws Exception;
    }

    /** Network work off the UI thread; errors come back on it. */
    private static void background(IoTask task, Consumer<Exception> onError) {
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        }, "logsift-ssh");
        t.setDaemon(true);
        t.start();
    }

    /** ~/.ssh/id_ed25519 or id_rsa, if there is one. */
    private static String defaultKey() {
        Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
        for (String n : new String[] {"id_ed25519", "id_ecdsa", "id_rsa"}) {
            if (Files.isRegularFile(ssh.resolve(n))) {
                return ssh.resolve(n).toString();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- the file list

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final class EntryModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Name", "Size", "Modified"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            RemoteBrowser.Entry e = entries.get(r);
            return switch (c) {
                case 0 -> e;
                case 1 -> e.dir() ? "" : MainFrame.humanBytes(e.size());
                default -> e.modified() == 0 ? "" : WHEN.format(Instant.ofEpochMilli(e.modified()));
            };
        }
    }

    /** Folder and file icons next to the names. */
    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                       int row, int col) {
            RemoteBrowser.Entry e = (RemoteBrowser.Entry) v;
            super.getTableCellRendererComponent(t, e.name(), sel, focus, row, col);
            setIcon(e.dir() ? Icons.folder() : Icons.file());
            return this;
        }
    }
}
