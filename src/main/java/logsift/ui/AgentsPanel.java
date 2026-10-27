package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import logsift.agent.AgentSettings;
import logsift.agent.Masking;

/**
 * Settings → Agents: how to let a coding agent (GitHub Copilot…) use Logsift's analyses, what
 * is masked in what goes to it, and which folders it may read.
 */
final class AgentsPanel extends JPanel {
    private final JCheckBox secrets = new JCheckBox("Secrets: passwords, tokens, keys, card numbers");
    private final JCheckBox emails = new JCheckBox("Email addresses");
    private final JCheckBox ips = new JCheckBox("IP addresses");
    private final JTextArea custom = new JTextArea(3, 30);
    private final DefaultListModel<Path> roots = new DefaultListModel<>();
    private final JComboBox<AgentSettings.Agent> agent = new JComboBox<>(AgentSettings.Agent.values());
    private final JTextArea snippet = new JTextArea(11, 50);
    private final JLabel where = new JLabel();
    private final JLabel saveError = new JLabel(" ");
    private final Path launcher;
    private final Runnable openActivity;

    /**
     * @param launcher     {@code logsift-mcp} on this computer, or null (not installed: a build)
     * @param openActivity opens the log of what agents asked
     */
    AgentsPanel(Path launcher, Runnable openActivity) {
        super(new BorderLayout(0, 10));
        this.launcher = launcher;
        this.openActivity = openActivity;
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        JLabel intro = new JLabel("<html>Coding agents such as <b>GitHub Copilot</b> can use Logsift: you ask in the "
                + "agent's chat (<i>why is orders.log full of timeouts?</i>), the agent calls Logsift, and Logsift "
                + "reads the logs, GC logs and thread dumps on this computer and answers with short summaries and "
                + "line numbers. Agents can only read: they can't change files or touch a running service.<br><br>"
                + "<b>What Logsift answers goes to the agent's AI provider</b>, under your agreement with them. "
                + "Mask what must not leave this computer below.</html>");
        add(intro, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(setup());
        center.add(Box.createVerticalStrut(12));
        center.add(rules());
        add(new JScrollPane(center, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            {
                setBorder(BorderFactory.createEmptyBorder());
            }
        }, BorderLayout.CENTER);
        load();
    }

    // ---------------------------------------------------------------- set up an agent

    private JPanel setup() {
        JPanel p = section("Set up your agent");
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(new JLabel("Agent"), BorderLayout.WEST);
        row.add(agent, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        p.add(row);
        p.add(Box.createVerticalStrut(6));
        where.setAlignmentX(LEFT_ALIGNMENT);
        p.add(where);
        p.add(Box.createVerticalStrut(6));
        snippet.setEditable(false);
        snippet.setFont(new Font(Font.MONOSPACED, Font.PLAIN, snippet.getFont().getSize()));
        snippet.setLineWrap(false);
        JScrollPane sp = new JScrollPane(snippet);
        sp.setAlignmentX(LEFT_ALIGNMENT);
        p.add(sp);
        p.add(Box.createVerticalStrut(6));
        JButton copy = new JButton("Copy", Icons.of("copy"));
        copy.setName("agentCopy");
        copy.setEnabled(launcher != null);
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(snippet.getText()), null));
        JButton activity = new JButton("What Agents Asked…");
        activity.setToolTipText("Opens the list of every call agents made: when, which agent, which tool, how big "
                + "the answer was and how much was masked (never the answers themselves)");
        activity.addActionListener(e -> openActivity.run());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(copy, BorderLayout.WEST);
        buttons.add(activity, BorderLayout.EAST);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        p.add(buttons);
        agent.addActionListener(e -> showSnippet());
        showSnippet();
        return p;
    }

    private void showSnippet() {
        AgentSettings.Agent a = (AgentSettings.Agent) agent.getSelectedItem();
        if (launcher == null) {
            where.setText("<html>logsift-mcp comes with the installed app (and the zip that runs on any Java). "
                    + "This Logsift runs from a build, so there is nothing to point an agent at.</html>");
            snippet.setText("");
            return;
        }
        where.setText("<html>" + AgentSettings.where(a) + "</html>");
        snippet.setText(AgentSettings.snippet(a, launcher));
        snippet.setCaretPosition(0);
    }

    String snippetText() {
        return snippet.getText();
    }

    void selectAgent(AgentSettings.Agent a) {
        agent.setSelectedItem(a);
    }

    // ---------------------------------------------------------------- masking and folders

    private JPanel rules() {
        JPanel p = section("What agents get");
        p.add(note("Mask in answers (applies the next time the agent starts Logsift):"));
        for (JCheckBox c : List.of(secrets, emails, ips)) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            c.addActionListener(e -> save());
            p.add(c);
        }
        p.add(Box.createVerticalStrut(4));
        p.add(note("Your own patterns, one regular expression per line (customer numbers, host names…):"));
        custom.setFont(new Font(Font.MONOSPACED, Font.PLAIN, custom.getFont().getSize()));
        custom.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                save();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                save();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });
        JScrollPane cs = new JScrollPane(custom);
        cs.setAlignmentX(LEFT_ALIGNMENT);
        p.add(cs);
        p.add(Box.createVerticalStrut(10));

        p.add(note("Folders agents may read (none listed: your home folder). Keys and passwords (~/.ssh, ~/.aws, "
                + "*.pem…) are never read:"));
        JList<Path> list = new JList<>(roots);
        list.setVisibleRowCount(3);
        JButton add = new JButton("Add Folder…", Icons.of("folder-open"));
        JButton remove = new JButton("Remove");
        remove.setEnabled(false);
        list.addListSelectionListener(e -> remove.setEnabled(list.getSelectedIndex() >= 0));
        add.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("A folder agents may read");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                roots.addElement(fc.getSelectedFile().toPath().toAbsolutePath().normalize());
                save();
            }
        });
        remove.addActionListener(e -> {
            for (Path q : list.getSelectedValuesList()) {
                roots.removeElement(q);
            }
            save();
        });
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.add(add);
        buttons.add(Box.createVerticalStrut(6));
        buttons.add(remove);
        JPanel folders = new JPanel(new BorderLayout(8, 0));
        folders.add(new JScrollPane(list), BorderLayout.CENTER);
        folders.add(buttons, BorderLayout.EAST);
        folders.setAlignmentX(LEFT_ALIGNMENT);
        p.add(folders);
        saveError.setForeground(new Color(0xDC2626));
        saveError.setAlignmentX(LEFT_ALIGNMENT);
        p.add(saveError);
        return p;
    }

    private boolean loading;

    private void load() {
        loading = true;
        try {
            AgentSettings s = AgentSettings.load(AppState.home());
            secrets.setSelected(s.masking().secrets());
            emails.setSelected(s.masking().emails());
            ips.setSelected(s.masking().ips());
            custom.setText(String.join("\n", s.masking().custom()));
            roots.clear();
            s.roots().forEach(roots::addElement);
        } finally {
            loading = false;
        }
    }

    /** Saves the choices in agent.properties (read by logsift-mcp when an agent starts it). */
    private void save() {
        if (loading) {
            return;
        }
        List<String> patterns = new ArrayList<>();
        for (String line : custom.getText().split("\n")) {
            if (!line.isBlank()) {
                patterns.add(line.strip());
            }
        }
        AgentSettings s = new AgentSettings(new Masking.Options(secrets.isSelected(), emails.isSelected(),
                ips.isSelected(), patterns), Collections.list(roots.elements()));
        List<String> bad = new Masking(s.masking()).badPatterns();
        try {
            s.save(AppState.home());
            saveError.setText(bad.isEmpty() ? " " : "Not a valid regular expression (not used): " + bad.get(0));
        } catch (IOException e) {
            saveError.setText("Can't save " + AgentSettings.file(AppState.home()) + ": " + e.getMessage());
        }
    }

    JCheckBox secretsBox() {
        return secrets;
    }

    // ---------------------------------------------------------------- layout

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel note(String text) {
        JLabel l = new JLabel("<html>" + text + "</html>");
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }
}
