package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import logsift.core.UrlFetch;

/**
 * Asks for one or more web addresses of logs (and how to sign in), to download and open them,
 * or search them together.
 */
final class UrlDialog extends JDialog {
    private static final Preferences PREFS = AppState.prefs();

    private final JTextArea urls = new JTextArea(5, 50);
    private final JRadioButton none = new JRadioButton("None", true);
    private final JRadioButton basic = new JRadioButton("User and password");
    private final JRadioButton token = new JRadioButton("Token");
    private final JTextField user = new JTextField(16);
    private final JPasswordField password = new JPasswordField(16);
    private final JPasswordField tokenField = new JPasswordField(30);
    private final JLabel error = new JLabel(" ");
    private final BiConsumer<List<URI>, UrlFetch.Auth> go;

    /** @param go gets the addresses and the sign-in, when the user presses Open */
    UrlDialog(Window owner, BiConsumer<List<URI>, UrlFetch.Auth> go) {
        super(owner, "Open URL", ModalityType.MODELESS);
        this.go = go;
        urls.setLineWrap(false);
        urls.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                error.setText(" ");
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                error.setText(" ");
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        urls.setText(PREFS.get("url.last", ""));
        urls.selectAll();
        user.setText(PREFS.get("url.user", ""));
        ButtonGroup g = new ButtonGroup();
        g.add(none);
        g.add(basic);
        g.add(token);
        String mode = PREFS.get("url.auth", "none");
        basic.setSelected(mode.equals("basic"));
        token.setSelected(mode.equals("token"));
        none.setSelected(!basic.isSelected() && !token.isSelected());

        JPanel cards = new JPanel(new CardLayout());
        cards.add(new JPanel(), "none");
        JPanel up = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        up.add(new JLabel("User"));
        up.add(user);
        up.add(new JLabel("Password"));
        up.add(password);
        cards.add(up, "basic");
        JPanel tk = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tk.add(new JLabel("Token"));
        tk.add(tokenField);
        cards.add(tk, "token");
        Runnable showCard = () -> ((CardLayout) cards.getLayout()).show(cards,
                basic.isSelected() ? "basic" : token.isSelected() ? "token" : "none");
        none.addActionListener(e -> showCard.run());
        basic.addActionListener(e -> showCard.run());
        token.addActionListener(e -> showCard.run());
        showCard.run();
        tokenField.setToolTipText("Sent as \"Authorization: Bearer <token>\" (or as given, if it starts with a scheme)");

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 0);
        form.add(new JLabel("<html>Addresses of log files, one per line (http or https).<br>"
                + "<span style='color:gray'>One opens in a tab; several are downloaded together and searched."
                + " A .zip or .gz is unpacked.</span></html>"), c);
        c.gridy++;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        form.add(new JScrollPane(urls), c);
        c.gridy++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weighty = 0;
        c.insets = new Insets(8, 0, 0, 0);
        JPanel auth = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        auth.add(new JLabel("Sign in:"));
        auth.add(none);
        auth.add(basic);
        auth.add(token);
        form.add(auth, c);
        c.gridy++;
        c.insets = new Insets(4, 0, 0, 0);
        form.add(cards, c);
        c.gridy++;
        error.setForeground(Theme.uiColor("Component.error.focusedBorderColor", java.awt.Color.RED));
        form.add(error, c);

        JButton open = new JButton("Open");
        open.addActionListener(e -> submit());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(open);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(form, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        setContentPane(p);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        // Enter in the list adds a line; Ctrl/Cmd+Enter opens
        getRootPane().registerKeyboardAction(e -> submit(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(open);
        pack();
        setLocationRelativeTo(owner);
    }

    /** The addresses typed, checked; the first problem is shown and null returned. */
    List<URI> addresses() {
        List<URI> out = new ArrayList<>();
        for (String line : urls.getText().split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            try {
                out.add(UrlFetch.parse(t));
            } catch (IOException e) {
                error.setText(e.getMessage());
                return null;
            }
        }
        if (out.isEmpty()) {
            error.setText("Type the address of a log file");
            return null;
        }
        return out;
    }

    UrlFetch.Auth auth() {
        if (basic.isSelected()) {
            return new UrlFetch.Auth(user.getText().trim(), new String(password.getPassword()), null);
        }
        if (token.isSelected()) {
            return new UrlFetch.Auth(null, null, new String(tokenField.getPassword()));
        }
        return UrlFetch.Auth.NONE;
    }

    void submit() {
        List<URI> list = addresses();
        if (list == null) {
            return;
        }
        PREFS.put("url.last", urls.getText().trim());
        PREFS.put("url.user", user.getText().trim());
        PREFS.put("url.auth", basic.isSelected() ? "basic" : token.isSelected() ? "token" : "none");
        UrlFetch.Auth a = auth();
        dispose();
        go.accept(list, a);
    }

    // for tests
    void setForTest(String text, String userName, String pw) {
        urls.setText(text);
        if (userName != null) {
            basic.doClick();
            user.setText(userName);
            password.setText(pw);
        } else {
            none.doClick();
        }
    }

    String errorForTest() {
        return error.getText();
    }
}
