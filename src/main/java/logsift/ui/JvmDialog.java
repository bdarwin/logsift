package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import logsift.core.RemoteMirror;
import logsift.jvm.JmxJvms;
import logsift.jvm.JvmLink;
import logsift.jvm.JvmProcess;
import logsift.jvm.LocalJvms;
import logsift.jvm.SshJvms;
import logsift.jvm.ThreadDump;

/**
 * Pick a running JVM to look at: one on this computer, or one on a server over SSH. Attaching
 * takes a first thread dump and opens a Threads tab, which can take more.
 */
final class JvmDialog extends JDialog {

    /** Where the chosen JVM goes: its first dump, how to take more, and its link. */
    interface Host {
        void attached(JvmConnector.Connection c);
    }

    /**
     * What to offer first: where the JVM probably is, and which one (from a log's link or what
     * the log says about itself).
     */
    record Suggestion(JvmLink.Kind kind, String host, int port, String user, long pid, String program) {
        boolean matches(JvmProcess p) {
            return pid >= 0 && p.pid() == pid && (program.isEmpty() || JvmLink.sameProgram(program, p.name()));
        }
    }

    private final Preferences p = AppState.prefs();
    private final Host host;
    private final Path knownHosts;
    private final Predicate<String> trustHost;
    private final JTabbedPane where = new JTabbedPane();

    // ---- this computer
    private final List<JvmProcess> local = new ArrayList<>();
    private final Model localModel = new Model(local, false);
    private final JTable localTable = new JTable(localModel);
    private final JTextField localJdk = new JTextField(p.get("jvm.localJdk", ""), 28);
    private final JLabel localStatus = new JLabel(" ");

    // ---- a server
    private final JTextField sshHost = new JTextField(p.get("remote.host", ""), 20);
    private final JTextField sshPort = new JTextField(p.get("remote.port", "22"), 4);
    private final JTextField sshUser = new JTextField(p.get("remote.user", System.getProperty("user.name")), 10);
    private final JRadioButton usePassword = new JRadioButton("Password");
    private final JRadioButton useKey = new JRadioButton("Private key");
    private final JPasswordField password = new JPasswordField(14);
    private final JTextField key = new JTextField(22);
    private final JPasswordField passphrase = new JPasswordField(12);
    private final JTextField remoteJdk = new JTextField(24);
    private final JCheckBox sudo = new JCheckBox("Use sudo for JVMs of other users", p.getBoolean("jvm.sudo", true));
    private final JPasswordField sudoPassword = new JPasswordField(12);
    private final JButton connect = new JButton("Connect");
    private final JLabel sshStatus = new JLabel(" ");
    private final List<JvmProcess> remote = new ArrayList<>();
    private final Model remoteModel = new Model(remote, true);
    private final JTable remoteTable = new JTable(remoteModel);
    private SshJvms ssh;
    private RemoteMirror.Target sshTarget;

    // ---- a JMX port
    private final JTextField jmxHost = new JTextField(p.get("jvm.jmx.host", ""), 20);
    private final JTextField jmxPort = new JTextField(p.get("jvm.jmx.port", "9010"), 5);
    private final JTextField jmxUser = new JTextField(p.get("jvm.jmx.user", ""), 10);
    private final JPasswordField jmxPassword = new JPasswordField(12);
    private final JCheckBox jmxSsl = new JCheckBox("SSL", p.getBoolean("jvm.jmx.ssl", false));
    private final JLabel jmxStatus = new JLabel(" ");

    private final JButton attach = new JButton("Attach");
    private final JvmConnector connector;
    private Suggestion suggestion;
    private final JLabel heading = new JLabel();

    JvmDialog(Window owner, Path knownHosts, Predicate<String> trustHost, Host host) {
        this(owner, knownHosts, trustHost, host, null, null);
    }

    /**
     * With {@code forLog} (a log's name), the dialog links that log to the JVM chosen, and
     * offers {@code suggestion} first.
     */
    JvmDialog(Window owner, Path knownHosts, Predicate<String> trustHost, Host host, String forLog,
              Suggestion suggestion) {
        super(owner, forLog == null ? "Attach to a JVM" : "Link to a JVM", ModalityType.MODELESS);
        this.host = host;
        this.knownHosts = knownHosts;
        this.trustHost = trustHost;
        this.connector = new JvmConnector(owner, knownHosts, trustHost);
        this.suggestion = suggestion;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        where.addTab("This computer", Icons.of("cpu"), localPanel());
        where.addTab("Server (SSH)", Icons.of("server"), sshPanel());
        where.addTab("JMX port", Icons.of("plug"), jmxPanel());
        where.setSelectedIndex(Math.min(p.getInt("jvm.where", 0), 2));
        where.addChangeListener(e -> {
            p.putInt("jvm.where", where.getSelectedIndex());
            updateAttach();
        });

        attach.putClientProperty("JButton.buttonType", "default");
        attach.addActionListener(e -> attachSelected());
        JButton cancel = new JButton("Close");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JLabel note = new JLabel("Logsift only reads threads; it changes nothing in the JVM.");
        note.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JPanel south = new JPanel(new BorderLayout());
        south.add(note, BorderLayout.WEST);
        buttons.add(cancel);
        buttons.add(attach);
        south.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        if (forLog != null) {
            heading.setText("<html>Which JVM writes <b>" + esc(forLog) + "</b>? Logsift remembers it for this log"
                    + " (and others in its folder), by program, so it finds it again after a restart.</html>");
            root.add(heading, BorderLayout.NORTH);
            attach.setText("Link");
        }
        root.add(where, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(new Dimension(820, 600));
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                closeSsh();
            }
        });
        if (suggestion != null) {
            applySuggestion();
        }
        refreshLocal();
        updateAttach();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /** Fills in where the suggested JVM is, and goes there (connecting at once where no password is needed). */
    private void applySuggestion() {
        Suggestion sg = suggestion;
        switch (sg.kind()) {
            case LOCAL -> where.setSelectedIndex(0);
            case SSH -> {
                where.setSelectedIndex(1);
                if (!sg.host().isEmpty()) {
                    boolean same = sg.host().equalsIgnoreCase(p.get("remote.host", ""));
                    sshHost.setText(sg.host());
                    if (sg.port() > 0) {
                        sshPort.setText(Integer.toString(sg.port()));
                    }
                    if (!sg.user().isEmpty()) {
                        sshUser.setText(sg.user());
                    }
                    if (!same) {
                        password.setText("");
                    }
                    if (useKey.isSelected() && !key.getText().isBlank()) {
                        SwingUtilities.invokeLater(this::connectSsh); // a key: no password to ask for
                    } else {
                        SwingUtilities.invokeLater(() -> (usePassword.isSelected() ? password : sshHost)
                                .requestFocusInWindow());
                    }
                }
            }
            case JMX -> {
                where.setSelectedIndex(2);
                jmxHost.setText(sg.host());
                if (sg.port() > 0) {
                    jmxPort.setText(Integer.toString(sg.port()));
                }
                jmxUser.setText(sg.user());
                if (!sg.user().isEmpty()) {
                    SwingUtilities.invokeLater(jmxPassword::requestFocusInWindow);
                }
            }
        }
    }

    /** Selects the suggested JVM in a list just filled. */
    private void selectSuggested(List<JvmProcess> l, JTable t) {
        Suggestion sg = suggestion;
        if (sg == null) {
            return;
        }
        int best = -1;
        for (int i = 0; i < l.size(); i++) {
            if (sg.matches(l.get(i))) {
                best = i;
                break;
            }
            if (best < 0 && JvmLink.sameProgram(sg.program(), l.get(i).name())) {
                best = i;
            }
        }
        if (best >= 0) {
            t.getSelectionModel().setSelectionInterval(best, best);
            t.scrollRectToVisible(t.getCellRect(best, 0, true));
        }
    }

    private void closeSsh() {
        if (ssh != null) {
            ssh.close();
            ssh = null;
        }
    }

    // ---------------------------------------------------------------- this computer

    private JPanel localPanel() {
        setUpTable(localTable);
        JButton refresh = new JButton("Refresh", Icons.reload());
        refresh.addActionListener(e -> refreshLocal());
        JButton browse = new JButton("…");
        browse.setToolTipText("Choose a JDK folder");
        browse.addActionListener(e -> {
            JFileChooser ch = new JFileChooser();
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                localJdk.setText(ch.getSelectedFile().toString());
            }
        });
        localJdk.putClientProperty("JTextField.placeholderText", "only needed if attaching fails");
        JPanel top = new JPanel(new BorderLayout());
        top.add(line(new JLabel("Java programs running as you on this computer")), BorderLayout.WEST);
        top.add(line(refresh), BorderLayout.EAST);
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.add(line(new JLabel("JDK folder (for jcmd)"), localJdk, browse));
        bottom.add(line(localStatus));
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(localTable), BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    void refreshLocal() {
        localStatus.setText("Looking for Java programs…");
        background(() -> {
            List<JvmProcess> found = LocalJvms.list();
            SwingUtilities.invokeLater(() -> {
                local.clear();
                local.addAll(found);
                localModel.fireTableDataChanged();
                if (!local.isEmpty()) {
                    localTable.getSelectionModel().setSelectionInterval(0, 0);
                    selectSuggested(local, localTable);
                }
                localStatus.setText(found.isEmpty()
                        ? (LocalJvms.canAttach() ? "No other Java programs are running as you."
                        : "This Logsift cannot list Java programs; set a JDK folder.")
                        : found.size() + (found.size() == 1 ? " Java program" : " Java programs"));
                updateAttach();
            });
        }, e -> localStatus.setText("Could not list Java programs: " + e.getMessage()));
    }

    // ---------------------------------------------------------------- a server

    private JPanel sshPanel() {
        ButtonGroup g = new ButtonGroup();
        g.add(usePassword);
        g.add(useKey);
        key.setText(p.get("remote.key", ""));
        (p.getBoolean("remote.useKey", !key.getText().isEmpty()) ? useKey : usePassword).setSelected(true);
        sshHost.putClientProperty("JTextField.placeholderText", "server name or address");
        passphrase.putClientProperty("JTextField.placeholderText", "key passphrase, if any");
        remoteJdk.putClientProperty("JTextField.placeholderText", "found by itself; e.g. /opt/jdk-21");
        sudoPassword.putClientProperty("JTextField.placeholderText", "sudo password, if it asks");
        sudoPassword.setToolTipText("Kept only while Logsift runs");
        connect.addActionListener(e -> connectSsh());
        sshHost.addActionListener(e -> connectSsh());
        password.addActionListener(e -> connectSsh());
        sudo.addActionListener(e -> p.putBoolean("jvm.sudo", sudo.isSelected()));
        JButton browseKey = new JButton("…");
        browseKey.addActionListener(e -> {
            JFileChooser ch = new JFileChooser(Path.of(System.getProperty("user.home"), ".ssh").toFile());
            ch.setFileHidingEnabled(false);
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                key.setText(ch.getSelectedFile().toString());
                useKey.setSelected(true);
            }
        });
        JPanel conn = new JPanel();
        conn.setLayout(new BoxLayout(conn, BoxLayout.Y_AXIS));
        conn.add(line(new JLabel("Host"), sshHost, new JLabel("Port"), sshPort, new JLabel("User"), sshUser));
        conn.add(line(usePassword, password, useKey, key, browseKey, passphrase));
        conn.add(line(new JLabel("JDK folder on the server"), remoteJdk));
        conn.add(line(sudo, sudoPassword));
        conn.add(line(connect, sshStatus));
        setUpTable(remoteTable);
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(conn, BorderLayout.NORTH);
        panel.add(new JScrollPane(remoteTable), BorderLayout.CENTER);
        JLabel how = new JLabel("<html>Dumps are taken with jcmd (from the JDK folder, the PATH or the JVM's own JDK)."
                + " Without a JDK, Logsift copies async-profiler to the server's /tmp and uses that.</html>");
        how.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        panel.add(how, BorderLayout.SOUTH);
        return panel;
    }

    void setSshForTest(String h, int port, String u, String pw) {
        sshHost.setText(h);
        sshPort.setText(String.valueOf(port));
        sshUser.setText(u);
        usePassword.setSelected(true);
        password.setText(pw);
        where.setSelectedIndex(1);
    }

    int whereForTest() {
        return where.getSelectedIndex();
    }

    String sshHostForTest() {
        return sshHost.getText();
    }

    void setRemoteJdkForTest(String jdk) {
        remoteJdk.setText(jdk);
    }

    private RemoteMirror.Target sshTargetFromFields() {
        int port;
        try {
            port = Integer.parseInt(sshPort.getText().trim());
        } catch (NumberFormatException e) {
            port = 22;
        }
        return new RemoteMirror.Target(sshHost.getText().trim(), port, sshUser.getText().trim(), "",
                usePassword.isSelected() ? new String(password.getPassword()) : null,
                useKey.isSelected() && !key.getText().isBlank() ? Path.of(key.getText().trim()) : null,
                useKey.isSelected() && passphrase.getPassword().length > 0 ? new String(passphrase.getPassword()) : null);
    }

    void connectSsh() {
        if (sshHost.getText().isBlank()) {
            sshStatus.setText("Enter the server's name or address.");
            sshHost.requestFocusInWindow();
            return;
        }
        closeSsh();
        RemoteMirror.Target t = sshTargetFromFields();
        connect.setEnabled(false);
        sshStatus.setText("Connecting to " + t.host() + "…");
        background(() -> {
            SshJvms s = SshJvms.connect(t, knownHosts, trustHost);
            List<JvmProcess> found;
            try {
                found = s.list();
            } catch (IOException e) {
                s.close();
                throw e;
            }
            SwingUtilities.invokeLater(() -> {
                ssh = s;
                sshTarget = t;
                connect.setEnabled(true);
                p.put("remote.host", t.host());
                p.put("remote.port", Integer.toString(t.port()));
                p.put("remote.user", t.user());
                p.put("remote.key", key.getText().trim());
                p.putBoolean("remote.useKey", useKey.isSelected());
                if (remoteJdk.getText().isBlank()) {
                    remoteJdk.setText(p.get("jvm.jdk." + t.host(), ""));
                }
                remote.clear();
                remote.addAll(found);
                remoteModel.fireTableDataChanged();
                if (!remote.isEmpty()) {
                    remoteTable.getSelectionModel().setSelectionInterval(0, 0);
                    selectSuggested(remote, remoteTable);
                }
                sshStatus.setText("Connected to " + s.label() + " · " + (found.isEmpty() ? "no Java programs running"
                        : found.size() + (found.size() == 1 ? " Java program" : " Java programs")));
                updateAttach();
            });
        }, e -> {
            connect.setEnabled(true);
            sshStatus.setText("Could not connect: " + e.getMessage());
        });
    }

    // ---------------------------------------------------------------- a JMX port

    private JPanel jmxPanel() {
        jmxHost.putClientProperty("JTextField.placeholderText", "server, or a service:jmx: URL");
        jmxUser.putClientProperty("JTextField.placeholderText", "if it asks");
        jmxPassword.putClientProperty("JTextField.placeholderText", "if it asks");
        jmxPassword.setToolTipText("Kept only while Logsift runs");
        jmxHost.addActionListener(e -> attachSelected());
        jmxPort.addActionListener(e -> attachSelected());
        jmxPassword.addActionListener(e -> attachSelected());
        jmxHost.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateAttach();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateAttach();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateAttach();
            }
        });
        JPanel conn = new JPanel();
        conn.setLayout(new BoxLayout(conn, BoxLayout.Y_AXIS));
        conn.add(line(new JLabel("Host"), jmxHost, new JLabel("Port"), jmxPort, jmxSsl));
        conn.add(line(new JLabel("User"), jmxUser, new JLabel("Password"), jmxPassword));
        conn.add(line(jmxStatus));
        JLabel how = new JLabel("<html>For a JVM started with <tt>-Dcom.sun.management.jmxremote.port=9010</tt>"
                + " (and <tt>-Djava.rmi.server.hostname</tt> set to its address), from anywhere: no SSH needed.<br><br>"
                + "Over JMX, Logsift takes thread dumps and heap histograms, and profiles with Flight Recorder"
                + " (CPU, wall clock, allocations and locks), the recording sent back over the same port."
                + " Heap dumps and async-profiler need SSH or this computer.</html>");
        how.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        how.setBorder(BorderFactory.createEmptyBorder(12, 6, 0, 6));
        how.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(conn, BorderLayout.NORTH);
        panel.add(how, BorderLayout.CENTER);
        return panel;
    }

    void setJmxForTest(String h, int port) {
        jmxHost.setText(h);
        jmxPort.setText(Integer.toString(port));
        jmxUser.setText("");
        where.setSelectedIndex(2);
    }

    private void attachJmx() {
        int port;
        try {
            port = Integer.parseInt(jmxPort.getText().trim());
        } catch (NumberFormatException e) {
            jmxStatus.setText("The port is a number, such as 9010.");
            return;
        }
        JmxJvms.Address a = new JmxJvms.Address(jmxHost.getText(), port, jmxUser.getText(),
                new String(jmxPassword.getPassword()), jmxSsl.isSelected());
        p.put("jvm.jmx.host", a.host());
        p.put("jvm.jmx.port", Integer.toString(port));
        p.put("jvm.jmx.user", a.user());
        p.putBoolean("jvm.jmx.ssl", a.ssl());
        attach.setEnabled(false);
        jmxStatus.setText("Connecting to " + a.label() + "…");
        background(() -> {
            JvmConnector.Connection c = connector.jmx(a);
            SwingUtilities.invokeLater(() -> done(jmxStatus, c));
        }, e -> {
            updateAttach();
            jmxStatus.setText("Could not connect: " + e.getMessage());
        });
    }

    private void done(JLabel status, JvmConnector.Connection c) {
        status.setText(" ");
        updateAttach();
        host.attached(c);
        dispose();
    }

    int remoteCountForTest() {
        return remote.size();
    }

    int localCountForTest() {
        return local.size();
    }

    // ---------------------------------------------------------------- attaching

    private void updateAttach() {
        if (where.getSelectedIndex() == 2) {
            attach.setEnabled(!jmxHost.getText().isBlank());
            return;
        }
        boolean onServer = where.getSelectedIndex() == 1;
        JTable t = onServer ? remoteTable : localTable;
        attach.setEnabled(t.getSelectedRow() >= 0 && (!onServer || ssh != null));
    }

    /** Selects the JVM with this process id (in the tab shown). */
    void selectForTest(long pid) {
        boolean onServer = where.getSelectedIndex() == 1;
        List<JvmProcess> l = onServer ? remote : local;
        JTable t = onServer ? remoteTable : localTable;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).pid() == pid) {
                t.getSelectionModel().setSelectionInterval(i, i);
            }
        }
        updateAttach();
    }

    void attachSelected() {
        if (where.getSelectedIndex() == 2) {
            if (!jmxHost.getText().isBlank()) {
                attachJmx();
            }
            return;
        }
        boolean onServer = where.getSelectedIndex() == 1;
        JTable t = onServer ? remoteTable : localTable;
        int r = t.getSelectedRow();
        if (r < 0) {
            return;
        }
        JvmProcess jvm = (onServer ? remote : local).get(r);
        attach.setEnabled(false);
        JLabel status = onServer ? sshStatus : localStatus;
        status.setText("Attaching to " + jvm.name() + " (" + jvm.pid() + ")…");
        if (onServer) {
            attachRemote(jvm, status);
        } else {
            attachLocal(jvm, status);
        }
    }

    private void attachLocal(JvmProcess jvm, JLabel status) {
        String jdk = localJdk.getText().trim();
        p.put("jvm.localJdk", jdk);
        background(() -> {
            JvmConnector.Connection c = connector.local(jvm, jdk);
            SwingUtilities.invokeLater(() -> done(status, c));
        }, e -> {
            updateAttach();
            status.setText("Could not attach: " + e.getMessage());
        });
    }

    private void attachRemote(JvmProcess jvm, JLabel status) {
        RemoteMirror.Target t = sshTarget;
        String jdk = remoteJdk.getText().trim();
        p.put("jvm.jdk." + t.host(), jdk);
        String sudoPw = sudoPassword.getPassword().length == 0 ? null : new String(sudoPassword.getPassword());
        boolean useSudo = sudo.isSelected();
        background(() -> {
            JvmConnector.Connection c = connector.ssh(t, jvm, new SshJvms.Options(jdk, useSudo, sudoPw));
            SwingUtilities.invokeLater(() -> done(status, c));
        }, e -> {
            updateAttach();
            status.setText("Could not take a dump: " + e.getMessage());
        });
    }

    // ---------------------------------------------------------------- bits

    private void setUpTable(JTable t) {
        t.setFillsViewportHeight(true);
        t.setShowGrid(false);
        t.setRowHeight(t.getRowHeight() + 4);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {70, 260, 90, 100, 300};
        for (int i = 0; i < t.getColumnCount() && i < widths.length; i++) {
            t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        t.getSelectionModel().addListSelectionListener(e -> updateAttach());
        t.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && t.getSelectedRow() >= 0) {
                    attachSelected();
                }
            }
        });
    }

    private static JPanel line(JComponent... parts) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (JComponent c : parts) {
            row.add(c);
        }
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private interface Work {
        void run() throws Exception;
    }

    private static void background(Work w, java.util.function.Consumer<Exception> failed) {
        Thread t = new Thread(() -> {
            try {
                w.run();
            } catch (Exception e) {
                Exception ex = e;
                SwingUtilities.invokeLater(() -> failed.accept(ex));
            }
        }, "logsift-jvm");
        t.setDaemon(true);
        t.start();
    }

    private static final class Model extends AbstractTableModel {
        private final List<JvmProcess> rows;
        private final boolean server;

        Model(List<JvmProcess> rows, boolean server) {
            this.rows = rows;
            this.server = server;
        }

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
            return new String[] {"PID", "Program", "User", "Running for", "Command"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            JvmProcess j = rows.get(r);
            return switch (c) {
                case 0 -> j.pid();
                case 1 -> j.name() + (server && j.container() ? "  (in a container)" : "");
                case 2 -> j.user();
                case 3 -> j.uptime();
                default -> j.command();
            };
        }
    }
}
