package logsift.ui;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.PasswordAuthentication;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Signing in to an office's proxy, when it asks for a name and password: the name is remembered,
 * the password only until Logsift quits.
 */
final class ProxySignIn {
    private ProxySignIn() {
    }

    static final String TITLE = "Proxy Sign-In";

    /** The form: what the proxy is, a name, a password. */
    static final class Form extends JPanel {
        final JTextField user = new JTextField(22);
        final JPasswordField password = new JPasswordField(22);

        Form(String proxy, boolean again, String name) {
            super(new GridBagLayout());
            setName("proxySignIn");
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = 0;
            g.gridwidth = 2;
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(0, 0, 10, 0);
            add(new JLabel("<html><div style='width:320px'>" + (again
                    ? "<b>The proxy didn't accept that name and password.</b> Try again."
                    : "<b>Your network's proxy asks you to sign in.</b>")
                    + "<br>" + escape(proxy) + "<br><br>Usually your work sign-in. The password is kept only "
                    + "until Logsift quits.</div></html>"), g);
            g.gridwidth = 1;
            g.insets = new Insets(2, 0, 2, 8);
            g.gridy = 1;
            add(new JLabel("Name:"), g);
            g.gridx = 1;
            add(user, g);
            g.gridx = 0;
            g.gridy = 2;
            add(new JLabel("Password:"), g);
            g.gridx = 1;
            add(password, g);
            user.setName("proxyUser");
            password.setName("proxyPassword");
            user.setText(name == null ? "" : name);
        }

        private static String escape(String t) {
            return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        /** What was typed; null without a name. */
        PasswordAuthentication answer() {
            String u = user.getText().strip();
            return u.isEmpty() ? null : new PasswordAuthentication(u, password.getPassword());
        }
    }

    /** For tests: answers in place of the person (gets the form, says OK or not). */
    static java.util.function.Predicate<Form> answerForTest;

    /** Asks (from any thread, waiting for the answer); null when they cancel. */
    static PasswordAuthentication ask(Component owner, String proxy, boolean again) {
        PasswordAuthentication[] out = new PasswordAuthentication[1];
        Runnable r = () -> {
            Form f = new Form(proxy, again, AppState.prefs().get("proxy.user", System.getProperty("user.name", "")));
            boolean ok;
            if (answerForTest != null) {
                ok = answerForTest.test(f);
            } else {
                SwingUtilities.invokeLater(() -> (f.user.getText().isEmpty() ? f.user : f.password).requestFocusInWindow());
                ok = JOptionPane.showConfirmDialog(owner, f, TITLE, JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
            }
            out[0] = ok ? f.answer() : null;
            if (out[0] != null) {
                AppState.prefs().put("proxy.user", out[0].getUserName());
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (java.lang.reflect.InvocationTargetException e) {
                return null;
            }
        }
        return out[0];
    }
}
