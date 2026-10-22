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
import logsift.jvm.JvmProcess;
import logsift.jvm.LocalJvms;
import logsift.jvm.SshJvms;
import logsift.jvm.ThreadDump;

/**
 * Pick a running JVM to look at: one on this computer, or one on a server over SSH. Attaching
 * takes a first thread dump and opens a Threads tab, which can take more.
 */
final class JvmDialog extends JDialog {

    /** Where the chosen JVM goes: its first dump and how to take more. */
    interface Host {
        void attached(String source, ThreadDump first, ThreadsTab.Capture capture, Profiling profiling);
    }

    /** How to profile the attached JVM (null where it can't be). */
    interface Profiling {
        logsift.jvm.Profiler.Target target();
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

    private final JButton attach = new JButton("Attach");

    JvmDialog(Window owner, Path knownHosts, Predicate<String> trustHost, Host host) {
        super(owner, "Attach to a JVM", ModalityType.MODELESS);
        this.host = host;
        this.knownHosts = knownHosts;
        this.trustHost = trustHost;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        where.addTab("This computer", Icons.of("cpu"), localPanel());
        where.addTab("Server (SSH)", Icons.of("server"), sshPanel());
        where.setSelectedIndex(p.getInt("jvm.where", 0));
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
        refreshLocal();
        updateAttach();
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

    int remoteCountForTest() {
        return remote.size();
    }

    int localCountForTest() {
        return local.size();
    }

    // ---------------------------------------------------------------- attaching

    private void updateAttach() {
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
            LocalJvms.Session s = LocalJvms.attach(jvm.pid(), jdk);
            ThreadDump first;
            try {
                first = s.dump();
            } catch (IOException e) {
                s.close();
                throw e;
            }
            ThreadsTab.Capture c = new ThreadsTab.Capture() {
                @Override
                public String label() {
                    return jvm.name() + " (" + jvm.pid() + ")";
                }

                @Override
                public ThreadDump take() throws Exception {
                    return s.dump();
                }

                @Override
                public void close() {
                    s.close();
                }
            };
            Profiling prof = () -> logsift.jvm.Profiler.local(jvm, s);
            SwingUtilities.invokeLater(() -> {
                status.setText(" ");
                updateAttach();
                host.attached(jvm.name() + " (" + jvm.pid() + ")", first, c, prof);
                dispose();
            });
        }, e -> {
            updateAttach();
            status.setText("Could not attach: " + e.getMessage());
        });
    }

    private void attachRemote(JvmProcess jvm, JLabel status) {
        RemoteMirror.Target t = sshTarget;
        String jdk = remoteJdk.getText().trim();
        p.put("jvm.jdk." + t.host(), jdk);
        String[] sudoPw = {sudoPassword.getPassword().length == 0 ? null : new String(sudoPassword.getPassword())};
        boolean useSudo = sudo.isSelected();
        background(() -> {
            // a connection of its own, for as long as the tab is open
            SshJvms conn = SshJvms.connect(t, knownHosts, trustHost);
            RemoteCapture c = new RemoteCapture(conn, t, jvm, new SshJvms.Options(jdk, useSudo, sudoPw[0]));
            ThreadDump first;
            try {
                first = c.take();
            } catch (Exception e) {
                c.close();
                throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
            }
            Profiling prof = () -> logsift.jvm.Profiler.remote(jvm, c.conn(), c.options());
            SwingUtilities.invokeLater(() -> {
                status.setText(" ");
                updateAttach();
                host.attached(jvm.name() + " on " + t.host(), first, c, prof);
                dispose();
            });
        }, e -> {
            updateAttach();
            status.setText("Could not take a dump: " + e.getMessage());
        });
    }

    /** Dumps of a JVM on a server; reconnects, and asks for the sudo password when needed. */
    private final class RemoteCapture implements ThreadsTab.Capture {
        private SshJvms conn;
        private final RemoteMirror.Target target;
        private final JvmProcess jvm;
        private SshJvms.Options options;

        RemoteCapture(SshJvms conn, RemoteMirror.Target target, JvmProcess jvm, SshJvms.Options options) {
            this.conn = conn;
            this.target = target;
            this.jvm = jvm;
            this.options = options;
        }

        synchronized SshJvms conn() {
            return conn;
        }

        synchronized SshJvms.Options options() {
            return options;
        }

        @Override
        public String label() {
            return jvm.name() + " (" + jvm.pid() + ") on " + target.host();
        }

        @Override
        public synchronized ThreadDump take() throws Exception {
            if (!conn.isConnected()) {
                conn.close();
                conn = SshJvms.connect(target, knownHosts, trustHost);
            }
            for (int tries = 0; ; tries++) {
                try {
                    return conn.dump(jvm, options);
                } catch (SshJvms.SudoPasswordNeeded e) {
                    String pw = askSudoPassword(e.getMessage(), tries > 0);
                    if (pw == null) {
                        throw e;
                    }
                    options = new SshJvms.Options(options.jdkHome(), true, pw);
                }
            }
        }

        @Override
        public synchronized void close() {
            conn.close();
        }
    }

    /** Asks (on the EDT) for the sudo password; null if cancelled. */
    private String askSudoPassword(String why, boolean again) {
        String[] out = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPasswordField f = new JPasswordField(16);
                JPanel pn = new JPanel(new BorderLayout(0, 6));
                pn.add(new JLabel("<html>" + (again ? "That password didn't work. " : "") + why
                        + ".<br>Your sudo password on the server:</html>"), BorderLayout.NORTH);
                pn.add(f, BorderLayout.CENTER);
                if (JOptionPane.showConfirmDialog(getOwner(), pn, "sudo", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    out[0] = new String(f.getPassword());
                }
            });
        } catch (Exception e) {
            return null;
        }
        return out[0];
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
