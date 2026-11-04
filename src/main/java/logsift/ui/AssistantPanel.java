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
    private final JCheckBox atStart = new JCheckBox("Open the assistant when Logsift starts");
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
                + "GitHub Copilot in Logsift's window: it reads the open logs, and your services' logs, with Logsift's "
                + "tools, and only reads. Your Copilot subscription pays for what it reads and writes, in tokens.</html>");
        add(intro, BorderLayout.NORTH);
        JPanel center = new Fit();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(where());
        center.add(Box.createVerticalStrut(12));
        center.add(told());
        add(new JScrollPane(center, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            {
                setBorder(BorderFactory.createEmptyBorder());
                getVerticalScrollBar().setUnitIncrement(16);
            }
        }, BorderLayout.CENTER);
        load();
    }

    private JPanel where() {
        JPanel p = section("Where it opens");
        ButtonGroup g = new ButtonGroup();
        g.add(inTab);
        g.add(beside);
        for (JComponent c : List.of(inTab, beside, atStart)) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            p.add(c);
        }
        inTab.setName("assistantInTab");
        beside.setName("assistantBeside");
        atStart.setName("assistantAtStart");
        atStart.setToolTipText("It connects to Copilot when you first look at it, so an unseen tab costs nothing");
        inTab.addActionListener(e -> {
            save();
            placed.run();
        });
        beside.addActionListener(e -> {
            save();
            placed.run();
        });
        atStart.addActionListener(e -> save());
        return p;
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
        inTab.setSelected(o.inTab());
        beside.setSelected(!o.inTab());
        atStart.setSelected(o.atStart());
        own.setText(o.own());
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
        total.setText("All that's on: " + tokens(n) + ", read with each question (Copilot also reads its tools' "
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
        ChatInstructions.Options o = service.options().withInTab(inTab.isSelected()).withAtStart(atStart.isSelected())
                .withOwn(own.getText());
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
            total.setText("All that's on: " + tokens(n) + ", read with each question (Copilot also reads its tools' "
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
