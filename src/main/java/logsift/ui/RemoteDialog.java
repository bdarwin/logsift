package logsift.ui;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import logsift.core.RemoteMirror;

/** Asks where a remote log is (host, user, path) and how to log in; remembers all but secrets. */
final class RemoteDialog {
    private RemoteDialog() {}

    /** What the user chose: where, and how much to load (0 = the whole file). */
    record Choice(RemoteMirror.Target target, long lastBytes) {}

    static Choice ask(java.awt.Component parent) {
        Preferences p = AppState.prefs();
        JTextField host = new JTextField(p.get("remote.host", ""), 24);
        JTextField port = new JTextField(p.get("remote.port", "22"), 5);
        JTextField user = new JTextField(p.get("remote.user", System.getProperty("user.name")), 14);
        JTextField path = new JTextField(p.get("remote.path", "/var/log/"), 30);
        JRadioButton usePassword = new JRadioButton("Password");
        JRadioButton useKey = new JRadioButton("Private key");
        ButtonGroup g = new ButtonGroup();
        g.add(usePassword);
        g.add(useKey);
        JPasswordField password = new JPasswordField(18);
        String defaultKey = defaultKey();
        JTextField key = new JTextField(p.get("remote.key", defaultKey), 24);
        JPasswordField passphrase = new JPasswordField(18);
        passphrase.putClientProperty("JTextField.placeholderText", "passphrase, if the key has one");
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser ch = new JFileChooser(Path.of(System.getProperty("user.home"), ".ssh").toFile());
            ch.setFileHidingEnabled(false);
            if (ch.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                key.setText(ch.getSelectedFile().toString());
            }
        });
        boolean keyFirst = p.getBoolean("remote.useKey", !defaultKey.isEmpty());
        (keyFirst ? useKey : usePassword).setSelected(true);
        JRadioButton tail = new JRadioButton("Last", true);
        JRadioButton whole = new JRadioButton("Whole file");
        ButtonGroup g2 = new ButtonGroup();
        g2.add(tail);
        g2.add(whole);
        JSpinner mb = new JSpinner(new SpinnerNumberModel(p.getInt("remote.lastMb", 50), 1, 100_000, 10));
        if (p.getBoolean("remote.whole", false)) {
            whole.setSelected(true);
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        row = addRow(form, c, row, "Host", host, new JLabel("Port"), port);
        row = addRow(form, c, row, "User", user);
        row = addRow(form, c, row, "Log file", path);
        row = addRow(form, c, row, "Log in with", usePassword, password);
        row = addRow(form, c, row, "", useKey, key, browse);
        row = addRow(form, c, row, "", new JLabel(" "), passphrase);
        addRow(form, c, row, "Load", tail, mb, new JLabel("MB"), whole);
        host.putClientProperty("JTextField.placeholderText", "server name or address");
        path.putClientProperty("JTextField.placeholderText", "/var/log/app/app.log");

        int r = JOptionPane.showConfirmDialog(parent, form, "Open Remote Log (SFTP)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION || host.getText().isBlank() || path.getText().isBlank()) {
            return null;
        }
        int portNo;
        try {
            portNo = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            portNo = 22;
        }
        p.put("remote.host", host.getText().trim());
        p.put("remote.port", Integer.toString(portNo));
        p.put("remote.user", user.getText().trim());
        p.put("remote.path", path.getText().trim());
        p.put("remote.key", key.getText().trim());
        p.putBoolean("remote.useKey", useKey.isSelected());
        p.putInt("remote.lastMb", (Integer) mb.getValue());
        p.putBoolean("remote.whole", whole.isSelected());
        RemoteMirror.Target t = new RemoteMirror.Target(host.getText().trim(), portNo, user.getText().trim(),
                path.getText().trim(),
                usePassword.isSelected() ? new String(password.getPassword()) : null,
                useKey.isSelected() && !key.getText().isBlank() ? Path.of(key.getText().trim()) : null,
                useKey.isSelected() && passphrase.getPassword().length > 0 ? new String(passphrase.getPassword()) : null);
        long lastBytes = whole.isSelected() ? 0 : (Integer) mb.getValue() * 1024L * 1024L;
        return new Choice(t, lastBytes);
    }

    private static int addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent... parts) {
        c.gridy = row;
        c.gridx = 0;
        form.add(new JLabel(label), c);
        for (int i = 0; i < parts.length; i++) {
            c.gridx = i + 1;
            form.add(parts[i], c);
        }
        return row + 1;
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
}
