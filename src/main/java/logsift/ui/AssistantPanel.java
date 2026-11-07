package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import logsift.agent.LogCatalog;
import logsift.chat.ChatInstructions;
import logsift.chat.ChatService;

/**
 * Settings → Assistant: where the chat opens, and what it's told before the first question,
 * part by part, with what each costs in tokens and the words themselves.
 */
final class AssistantPanel extends JPanel {
    private final ChatService service;
    private final Supplier<LogCatalog> catalog;
    private final Runnable openLocations;
    private final Runnable placed;
    private final JRadioButton inTab = new JRadioButton("In a tab of its own, beside the logs' tabs");
    private final JRadioButton beside = new JRadioButton("At the side of the window, next to the log shown");
    private final JRadioButton window = new JRadioButton("In a window of its own, to put beside Logsift or on another screen");
    private final JCheckBox atStart = new JCheckBox("Open the assistant when Logsift starts");
    private final javax.swing.JSpinner textSize = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
            ChatInstructions.Options.TEXT_SIZE, ChatInstructions.Options.MIN_TEXT, ChatInstructions.Options.MAX_TEXT, 1));
    private final JRadioButton copilot = new JRadioButton("GitHub Copilot: your Copilot subscription pays, in AI credits");
    private final JRadioButton claude = new JRadioButton("Claude Code: your Claude subscription (or Console account) pays");
    private final JCheckBox askFiles = new JCheckBox("Ask me before it opens other files or folders");
    private final JTextArea own = new JTextArea(3, 40);
    private final JPanel parts = new JPanel();
    private final JLabel total = new JLabel();
    private final List<JCheckBox> boxes = new ArrayList<>();
    private boolean loading;

    /**
     * @param openLocations opens Log Locations for Agents
     * @param placed        called when the chat should move to where it now opens
     */
    AssistantPanel(ChatService service, Supplier<LogCatalog> catalog, Runnable openLocations, Runnable placed) {
        super(new BorderLayout(0, 10));
        this.service = service;
        this.catalog = catalog;
        this.openLocations = openLocations;
        this.placed = placed;
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        JLabel intro = new JLabel("<html>The <b>assistant</b> (" + (MainFrame.isMac() ? "⌘I" : "Ctrl+I") + ") is "
                + "GitHub Copilot or Claude Code in Logsift's window: it reads the open logs, and your services' logs, "
                + "with Logsift's tools, and only reads. Your own subscription pays for what it reads and writes, in "
                + "tokens.</html>");
        add(intro, BorderLayout.NORTH);
        JPanel center = new Fit();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(who());
        center.add(Box.createVerticalStrut(12));
        center.add(where());
        center.add(Box.createVerticalStrut(12));
        center.add(spending());
        center.add(Box.createVerticalStrut(12));
        center.add(told());
        center.add(Box.createVerticalStrut(12));
        center.add(audit());
        add(new JScrollPane(center, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            {
                setBorder(BorderFactory.createEmptyBorder());
                getVerticalScrollBar().setUnitIncrement(16);
            }
        }, BorderLayout.CENTER);
        load();
    }

    /** Who answers: Copilot or Claude Code, each through the person's own program and sign-in. */
    private JPanel who() {
        JPanel p = section("Who answers");
        ButtonGroup g = new ButtonGroup();
        g.add(copilot);
        g.add(claude);
        copilot.setName("engineCopilot");
        claude.setName("engineClaude");
        copilot.setToolTipText("The copilot program (npm install -g @github/copilot), signed in to GitHub");
        claude.setToolTipText("The claude program, signed in with claude auth login. It gets Logsift's tools only: "
                + "no files, shell or web of its own");
        JLabel note = new JLabel("<html>Each uses its own program on this computer, signed in as you. Chats go on "
                + "with the other one, told what was said.</html>");
        note.setForeground(Theme.dimmedForeground());
        for (JComponent c : List.of(copilot, claude, note)) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            p.add(c);
        }
        boolean isClaude = logsift.chat.ChatSettings.CLAUDE.equals(service.settings().engineOrDefault());
        (isClaude ? claude : copilot).setSelected(true);
        copilot.addActionListener(e -> service.saveSettings(service.settings().withEngine(logsift.chat.ChatSettings.COPILOT)));
        claude.addActionListener(e -> service.saveSettings(service.settings().withEngine(logsift.chat.ChatSettings.CLAUDE)));
        return p;
    }

    JRadioButton claudeForTest() {
        return claude;
    }

    JRadioButton copilotForTest() {
        return copilot;
    }

    private JPanel where() {
        JPanel p = section("Where it opens");
        ButtonGroup g = new ButtonGroup();
        g.add(inTab);
        g.add(beside);
        g.add(window);
        JPanel size = new JPanel();
        size.setLayout(new BoxLayout(size, BoxLayout.X_AXIS));
        size.setOpaque(false);
        size.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0)); // in line with the boxes' text
        size.add(new JLabel("Text size:"));
        size.add(Box.createHorizontalStrut(6));
        textSize.setMaximumSize(textSize.getPreferredSize());
        size.add(textSize);
        size.add(Box.createHorizontalStrut(6));
        JLabel keys = new JLabel("points; " + (MainFrame.isMac() ? "⌘+ and ⌘-" : "Ctrl+= and Ctrl+-")
                + " in the assistant change it too");
        keys.setForeground(Theme.dimmedForeground());
        size.add(keys);
        size.setMaximumSize(new Dimension(Integer.MAX_VALUE, size.getPreferredSize().height));
        for (JComponent c : List.of(inTab, beside, window, atStart, size)) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            p.add(c);
        }
        textSize.setName("assistantTextSize");
        textSize.setToolTipText("The size of the assistant's answers and questions, apart from the logs' font");
        textSize.addChangeListener(e -> {
            if (!loading) {
                ChatFonts.set((Integer) textSize.getValue());
            }
        });
        inTab.setName("assistantInTab");
        beside.setName("assistantBeside");
        window.setName("assistantWindow");
        atStart.setName("assistantAtStart");
        atStart.setToolTipText("It connects when you first look at it, so an unseen tab costs nothing");
        inTab.addActionListener(e -> {
            save();
            placed.run();
        });
        beside.addActionListener(e -> {
            save();
            placed.run();
        });
        window.addActionListener(e -> {
            save();
            placed.run();
        });
        atStart.addActionListener(e -> save());
        return p;
    }

    /** What keeps it from spending more than needed. */
    private JPanel spending() {
        JPanel p = section("What it reads");
        askFiles.setName("assistantAskFiles");
        askFiles.setAlignmentX(LEFT_ALIGNMENT);
        askFiles.addActionListener(e -> save());
        p.add(askFiles);
        p.add(wrapped("The logs you have open, and files you name with @ in a question, it reads without asking. "
                + "Opening anything else, or searching a folder, it asks first in the chat: reading what you didn't ask "
                + "about is where answers get expensive. Each tool's answer is also kept to "
                + String.format("%,d", ChatPanel.ANSWER_CAP) + " characters (about " + String.format("%,d", ChatPanel.ANSWER_CAP / 4)
                + " tokens); the assistant asks for less, or a narrower range, to see more.", true));
        return p;
    }

    JCheckBox askFilesForTest() {
        return askFiles;
    }

    private JPanel told() {
        JPanel p = section("What it's told");
        p.add(wrapped("Before the first question of each chat, the assistant reads these. Each question is read with "
                + "them again, so what's on here is part of what every answer costs. Changes apply to chats started or "
                + "opened from now on.", false));
        p.add(Box.createVerticalStrut(8));
        parts.setLayout(new BoxLayout(parts, BoxLayout.Y_AXIS));
        parts.setAlignmentX(LEFT_ALIGNMENT);
        parts.setOpaque(false);
        p.add(parts);
        p.add(Box.createVerticalStrut(8));
        JLabel ownLabel = new JLabel("Your own instructions:");
        ownLabel.setAlignmentX(LEFT_ALIGNMENT);
        p.add(ownLabel);
        own.setName("assistantOwn");
        own.setLineWrap(true);
        own.setWrapStyleWord(true);
        own.putClientProperty("JTextField.placeholderText",
                "For example: “login” is the auth service. Its logs are in UTC. Answer in short bullets.");
        own.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                save();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                save();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                save();
            }
        });
        JScrollPane sp = new JScrollPane(own);
        sp.setAlignmentX(LEFT_ALIGNMENT);
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        p.add(sp);
        p.add(Box.createVerticalStrut(8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        JButton locations = new JButton("Log Locations…", Icons.of("folder-open"));
        locations.setToolTipText("Tell it where each service writes its logs, and what each file holds");
        locations.addActionListener(e -> {
            openLocations.run();
            showParts();
        });
        buttons.add(locations);
        buttons.add(Box.createHorizontalStrut(6));
        JButton all = new JButton("Show Everything It's Told…");
        all.setName("assistantPreviewAll");
        all.addActionListener(e -> preview("What the assistant is told",
                ChatInstructions.text(service.options(), catalog.get())));
        buttons.add(all);
        p.add(buttons);
        p.add(Box.createVerticalStrut(6));
        total.setAlignmentX(LEFT_ALIGNMENT);
        total.setFont(total.getFont().deriveFont(total.getFont().getSize2D() - 1f));
        total.setForeground(Theme.dimmedForeground());
        p.add(total);
        return p;
    }

    private void load() {
        loading = true;
        ChatInstructions.Options o = service.options();
        inTab.setSelected(o.where() == ChatInstructions.Where.TAB);
        beside.setSelected(o.where() == ChatInstructions.Where.SIDE);
        window.setSelected(o.where() == ChatInstructions.Where.WINDOW);
        atStart.setSelected(o.atStart());
        textSize.setValue(o.textSize());
        askFiles.setSelected(o.askFiles());
        own.setText(o.own());
        loadAudit();
        loading = false;
        showParts();
    }

    /** One row per part: on or off, what it is, its size, and a look at its words. */
    private void showParts() {
        parts.removeAll();
        boxes.clear();
        for (ChatInstructions.Part part : ChatInstructions.parts(service.options(), catalog.get())) {
            if (part.key().equals("own")) {
                continue; // the box below
            }
            JPanel row = row();
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            JCheckBox on = new JCheckBox(part.title(), part.on());
            on.setName("part:" + part.key());
            on.setEnabled(!part.fixed());
            on.setToolTipText(part.fixed() ? "Always: without it the assistant can't use Logsift's tools well" : null);
            on.setFont(on.getFont().deriveFont(Font.BOLD));
            on.addActionListener(e -> {
                save();
                showParts();
            });
            boxes.add(on);
            JPanel text = new JPanel(new BorderLayout());
            text.setOpaque(false);
            text.add(on, BorderLayout.NORTH);
            JTextArea what = wrapped(part.what(), true);
            what.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));
            text.add(what, BorderLayout.CENTER);
            row.add(text, BorderLayout.CENTER);
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            east.setOpaque(false);
            JLabel size = new JLabel(tokens(part.tokens()));
            size.setName("tokens:" + part.key());
            size.setForeground(Theme.dimmedForeground());
            east.add(size);
            JButton look = new JButton("Show…");
            look.setName("show:" + part.key());
            look.addActionListener(e -> preview(part.title(), part.text()));
            east.add(look);
            row.add(east, BorderLayout.EAST);
            parts.add(row);
            parts.add(Box.createVerticalStrut(6));
        }
        int n = ChatInstructions.parts(service.options(), catalog.get()).stream().filter(ChatInstructions.Part::on)
                .mapToInt(ChatInstructions.Part::tokens).sum();
        total.setText("All that's on: " + tokens(n) + ", read with each question (the assistant also reads its tools' "
                + "descriptions).");
        parts.revalidate();
        parts.repaint();
    }

    static String tokens(int n) {
        return "≈ " + (n < 1000 ? n : String.format("%.1fk", n / 1000.0)) + " tokens";
    }

    private void save() {
        if (loading) {
            return;
        }
        ChatInstructions.Options o = service.options()
                .withWhere(window.isSelected() ? ChatInstructions.Where.WINDOW
                        : beside.isSelected() ? ChatInstructions.Where.SIDE : ChatInstructions.Where.TAB).withAtStart(atStart.isSelected())
                .withAskFiles(askFiles.isSelected()).withOwn(own.getText());
        for (JCheckBox b : boxes) {
            switch (b.getName()) {
                case "part:locations" -> o = o.withLocations(b.isSelected());
                case "part:plans" -> o = o.withPlans(b.isSelected());
                case "part:now" -> o = o.withNow(b.isSelected());
                default -> {
                }
            }
        }
        service.saveOptions(o);
        if (total.isShowing()) {
            int n = ChatInstructions.parts(o, catalog.get()).stream().filter(ChatInstructions.Part::on)
                    .mapToInt(ChatInstructions.Part::tokens).sum();
            total.setText("All that's on: " + tokens(n) + ", read with each question (the assistant also reads its tools' "
                    + "descriptions).");
        }
    }

    private void preview(String title, String text) {
        JTextArea a = new JTextArea(text.isEmpty() ? "(nothing)" : text, 30, 90);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setCaretPosition(0);
        a.setFont(Theme.monoFont(12f));
        javax.swing.JOptionPane.showMessageDialog(this, new JScrollPane(a), title + " (" + tokens((text.length() + 3) / 4)
                + ")", javax.swing.JOptionPane.PLAIN_MESSAGE);
    }

    /** A row in a column: as tall as it needs, never stretched. */
    static JPanel row() {
        return new JPanel(new BorderLayout(8, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
    }

    /** A panel as wide as the scroll pane's view (text wraps), and as tall as it needs. */
    static JPanel column() {
        return new Fit();
    }

    /** Text that wraps to the width it's given, as a label. */
    static JTextArea wrapped(String text, boolean dim) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setFocusable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setBorder(null);
        a.setFont(javax.swing.UIManager.getFont("Label.font"));
        a.setForeground(dim ? Theme.dimmedForeground() : javax.swing.UIManager.getColor("Label.foreground"));
        a.setAlignmentX(LEFT_ALIGNMENT);
        if (a.getCaret() instanceof javax.swing.text.DefaultCaret c) {
            c.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE); // filling it never scrolls the panel
        }
        return a;
    }

    /** A panel as wide as the scroll pane's view, so text wraps instead of running off. */
    static final class Fit extends JPanel implements javax.swing.Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(16, r.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // ---------------------------------------------------------------- the audit

    private final JCheckBox keepSent = new JCheckBox("Keep what went with each question, and the instructions it had");
    private final javax.swing.JSpinner bigResult = spinner(20, 1, 10_000, 5);
    private final javax.swing.JSpinner costly = spinner(100_000, 1_000, 50_000_000, 10_000);
    private final javax.swing.JSpinner slow = spinner(120, 5, 3_600, 10);
    private final javax.swing.JSpinner manyTools = spinner(12, 1, 500, 1);

    private static javax.swing.JSpinner spinner(int v, int min, int max, int step) {
        javax.swing.JSpinner s = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(v, min, max, step));
        s.setMaximumSize(s.getPreferredSize());
        return s;
    }

    /** What the audit (View → Assistant Audit) keeps, and what it flags. */
    private JPanel audit() {
        JPanel p = section("Audit");
        p.add(wrapped("View → Assistant Audit lists every question with what answering it took: the model's calls, "
                + "each tool and the size of its answer, the tokens, the time and the cost. It flags what looks wrong or "
                + "wasted, and keeps your verdict on each answer (the buttons under it). It reads the chats as they are "
                + "kept: it costs no tokens.", false));
        p.add(Box.createVerticalStrut(6));
        keepSent.setName("auditKeepSent");
        keepSent.setToolTipText("The time and the log you were looking at, sent before each question; the instructions "
                + "are kept once, however many questions went with them");
        keepSent.setAlignmentX(LEFT_ALIGNMENT);
        p.add(keepSent);
        p.add(Box.createVerticalStrut(4));
        JLabel flag = new JLabel("Flag a question when:");
        flag.setAlignmentX(LEFT_ALIGNMENT);
        flag.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 0));
        p.add(flag);
        bigResult.setName("auditBigResult");
        costly.setName("auditCostly");
        slow.setName("auditSlow");
        manyTools.setName("auditManyTools");
        p.add(limit("a tool answers with more than", bigResult, "KB (the model reads all of it)"));
        p.add(limit("it takes more than", costly, "tokens"));
        p.add(limit("it takes longer than", slow, "seconds"));
        p.add(limit("it calls more than", manyTools, "tools"));
        JLabel always = new JLabel("<html>Always flagged: an error, no answer, a tool that failed, the same call made "
                + "again, a line link that can't open, and an answer you rated bad.</html>");
        always.setForeground(Theme.dimmedForeground());
        always.setAlignmentX(LEFT_ALIGNMENT);
        always.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));
        p.add(always);
        keepSent.addActionListener(e -> saveAudit());
        for (javax.swing.JSpinner s : List.of(bigResult, costly, slow, manyTools)) {
            s.addChangeListener(e -> saveAudit());
        }
        return p;
    }

    private static JPanel limit(String before, javax.swing.JSpinner s, String after) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(2, 16, 2, 0));
        row.add(new JLabel(before));
        row.add(Box.createHorizontalStrut(6));
        row.add(s);
        row.add(Box.createHorizontalStrut(6));
        JLabel a = new JLabel(after);
        a.setForeground(Theme.dimmedForeground());
        row.add(a);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private void loadAudit() {
        logsift.chat.ChatAudit.Settings a = service.audit();
        keepSent.setSelected(a.keepSent());
        bigResult.setValue(a.bigResultKb());
        costly.setValue(a.costlyTokens());
        slow.setValue(a.slowSeconds());
        manyTools.setValue(a.manyTools());
    }

    private void saveAudit() {
        if (loading) {
            return;
        }
        service.saveAudit(new logsift.chat.ChatAudit.Settings(keepSent.isSelected(), (Integer) bigResult.getValue(),
                (Integer) costly.getValue(), (Integer) slow.getValue(), (Integer) manyTools.getValue()));
        ChatAuditWindow.refreshOpen();
    }

    JCheckBox keepSentForTest() {
        return keepSent;
    }

    javax.swing.JSpinner bigResultForTest() {
        return bigResult;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        return p;
    }

    // ---------------------------------------------------------------- for tests

    JCheckBox partForTest(String key) {
        return boxes.stream().filter(b -> b.getName().equals("part:" + key)).findFirst().orElse(null);
    }

    JTextArea ownForTest() {
        return own;
    }

    javax.swing.JSpinner textSizeForTest() {
        return textSize;
    }

    JRadioButton windowForTest() {
        return window;
    }

    JRadioButton inTabForTest() {
        return inTab;
    }

    JRadioButton besideForTest() {
        return beside;
    }

    JCheckBox atStartForTest() {
        return atStart;
    }

    String totalForTest() {
        return total.getText();
    }
}
