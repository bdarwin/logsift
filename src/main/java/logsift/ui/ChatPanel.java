package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import logsift.chat.ChatEngine;
import logsift.chat.ChatInfo;
import logsift.chat.ChatMessage;
import logsift.chat.ChatService;
import logsift.chat.ChatSession;
import logsift.chat.ChatStore;
import logsift.chat.ChatTools;

/**
 * The assistant beside the logs: ask about what's open, and GitHub Copilot answers with
 * Logsift's own tools, citing lines that are one click away. The model is chosen here, what each
 * answer cost is shown with it, and what's left of the allowance is always in view. Every chat
 * is kept, scrolls back as far as it goes, and can be searched.
 */
final class ChatPanel extends JPanel {

    /** What the window gives the assistant. */
    interface Host {
        /** The logs open in the window, the one shown first. */
        List<OpenLog> openLogs();

        /** The lines chosen in the shown log (1-based, first and last), or null. */
        long[] chosenLines();

        void showLines(String handle, long from, long to);

        void find(String handle, String text);

        void query(String handle, String sql);

        logsift.agent.Workspace workspace();

        logsift.agent.LogCatalog catalog();

        void closeChat();

        /** Whether the chat is a tab of its own (else beside the logs). */
        default boolean inTab() {
            return false;
        }

        /** Moves the chat to a tab of its own, or back beside the logs. */
        default void moveChat() {
        }

        /** Opens Settings → Assistant. */
        default void openSettings() {
        }

        /** Opens Log Locations for Agents. */
        default void openLocations() {
        }

        /** Opens a file in a tab of its own, at a line (1-based; 0: where it opens). */
        default void openFile(java.nio.file.Path file, long line) {
        }
    }

    /**
     * A log open in the window, as the assistant knows it.
     *
     * @param handle its handle ({@code log:1})
     * @param name   its file's name
     * @param where  its path or address
     * @param lines  how many lines
     * @param table  whether it's a table (CSV)
     */
    record OpenLog(String handle, String name, String where, long lines, boolean table) {}

    private final Host host;
    private final ChatService service;
    private final ChatStore store;
    private final ChatTranscript transcript;
    private final JLabel title = new JLabel("New chat");
    private final JLabel status = new JLabel(" ");
    private final JLabel credits = new JLabel(" ");
    private final JTextArea input = new JTextArea(3, 20);
    private final JButton send = new JButton(Icons.of("send"));
    private final JComboBox<ChatEngine.Model> model = new JComboBox<>();
    private final JComboBox<String> effort = new JComboBox<>();
    private final JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JButton newer = new JButton("Newest ↓");
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel message = new JPanel(new BorderLayout());
    private final JPanel suggestions = AssistantPanel.column();
    private final ChatHistory history;
    private final Timer contextTimer;

    private ChatService.State state;
    private ChatSession session;
    private ChatInfo chat;
    private boolean busy;
    private boolean sendShown = true;
    private boolean chooseSilently;
    /** The context the person took away from the next question (by its chip). */
    private boolean dropLog;
    private boolean dropLines;
    private String lastContextKey = "";

    ChatPanel(Host host, ChatService service) {
        super(new BorderLayout());
        this.host = host;
        this.service = service;
        this.store = service.store();
        setPreferredSize(new Dimension(420, 600));
        setMinimumSize(new Dimension(280, 200));
        setBackground(Theme.background());

        transcript = new ChatTranscript(store, new ChatTranscript.Actions() {
            @Override
            public void link(String href) {
                openLink(href);
            }

            @Override
            public void action(ChatMessage tool, String what) {
                toolAction(tool, what);
            }
        });
        transcript.onEndChanged(atEnd -> newer.setVisible(!atEnd));
        history = new ChatHistory(store, new ChatHistory.Host() {
            @Override
            public void open(String chatId, long seq) {
                openChat(chatId, seq);
            }

            @Override
            public void deleted(String chatId) {
                if (chat != null && chat.id().equals(chatId)) {
                    newChat();
                }
            }

            @Override
            public void back() {
                showCard("chat");
            }
        });

        add(header(), BorderLayout.NORTH);
        JPanel chatCard = new JPanel(new BorderLayout());
        chatCard.setOpaque(false);
        javax.swing.JLayeredPane layered = new javax.swing.JLayeredPane() {
            @Override
            public void doLayout() {
                for (Component c : getComponents()) {
                    if (c == newer) {
                        Dimension d = newer.getPreferredSize();
                        c.setBounds((getWidth() - d.width) / 2, getHeight() - d.height - 8, d.width, d.height);
                    } else {
                        c.setBounds(0, 0, getWidth(), getHeight());
                    }
                }
            }
        };
        layered.add(transcript.scrollPane(), javax.swing.JLayeredPane.DEFAULT_LAYER);
        newer.setVisible(false);
        newer.putClientProperty("JButton.buttonType", "roundRect");
        newer.addActionListener(e -> transcript.jumpToEnd());
        layered.add(newer, javax.swing.JLayeredPane.PALETTE_LAYER);
        chatCard.add(layered, BorderLayout.CENTER);
        center.add(chatCard, "chat");
        center.add(suggestionsCard(), "empty");
        center.add(message, "message");
        center.add(history, "history");
        add(center, BorderLayout.CENTER);
        add(composer(), BorderLayout.SOUTH);

        String problem = service.storeProblem();
        showMessage("Connecting to GitHub Copilot…", null);
        if (problem != null) {
            status.setText(problem);
        }
        contextTimer = new Timer(700, e -> refreshContext());
        contextTimer.start();
        // Copilot starts the first time the chat is seen: a tab in the background costs nothing
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                connect();
            }
        });
    }

    private boolean connecting;

    /** Starts Copilot, once (the first time the chat is shown). */
    void connect() {
        if (connecting) {
            return;
        }
        connecting = true;
        service.state().thenAccept(s -> SwingUtilities.invokeLater(() -> connected(s)));
    }

    // ---------------------------------------------------------------- parts

    private JComponent header() {
        JPanel h = new JPanel(new BorderLayout(6, 2));
        h.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(6, 10, 6, 6)));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setIcon(Icons.of("sparkles"));
        title.setToolTipText("Double-click to rename this chat");
        title.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    rename();
                }
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(toolButton("history", "Chats: every chat, and search them", e -> showHistory()));
        buttons.add(toolButton("square-pen", "New chat", e -> newChat()));
        move.putClientProperty("JButton.buttonType", "toolBarButton");
        move.setFocusable(false);
        move.addActionListener(e -> {
            host.moveChat();
            placed();
        });
        buttons.add(move);
        buttons.add(toolButton("settings", "Settings → Assistant: where it opens, and what it's told", e -> host.openSettings()));
        buttons.add(toolButton("x", "Close (" + (MainFrame.isMac() ? "⌘I" : "Ctrl+I") + ")", e -> host.closeChat()));
        placed();
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(title, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);
        h.add(top, BorderLayout.NORTH);
        JPanel line = new JPanel(new BorderLayout(8, 0));
        line.setOpaque(false);
        status.setForeground(Theme.dimmedForeground());
        status.setFont(status.getFont().deriveFont(status.getFont().getSize2D() - 1f));
        credits.setForeground(Theme.dimmedForeground());
        credits.setFont(status.getFont());
        credits.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        credits.setToolTipText("What the assistant cost: click for each model, today and this month");
        credits.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showUsage();
            }
        });
        line.add(status, BorderLayout.CENTER);
        line.add(credits, BorderLayout.EAST);
        h.add(line, BorderLayout.SOUTH);
        return h;
    }

    private final JButton move = new JButton();

    /** The move button says where the chat goes. */
    void placed() {
        boolean tab = host.inTab();
        move.setIcon(Icons.of(tab ? "panel-right" : "app-window"));
        move.setToolTipText(tab ? "Move beside the logs" : "Move to a tab of its own");
    }

    private static JButton toolButton(String icon, String tip, java.awt.event.ActionListener a) {
        JButton b = new JButton(Icons.of(icon));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(a);
        return b;
    }

    private JComponent composer() {
        JPanel c = new JPanel(new BorderLayout(0, 4));
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(6, 8, 8, 8)));
        chips.setOpaque(false);
        c.add(chips, BorderLayout.NORTH);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.putClientProperty("JTextField.placeholderText", "Ask about the open logs…");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break");
        input.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                sendOrStop();
            }
        });
        JScrollPane sp = new JScrollPane(input, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                int line = input.getFontMetrics(input.getFont()).getHeight();
                int rows = Math.max(3, Math.min(10, input.getLineCount()));
                return new Dimension(d.width, rows * line + 10);
            }
        };
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void grow() {
                SwingUtilities.invokeLater(() -> {
                    sp.revalidate();
                    updateSend();
                });
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                grow();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                grow();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                grow();
            }
        });
        c.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        model.setRenderer(new ModelRenderer());
        model.setToolTipText("The model that answers. Prices are AI credits per million tokens read · written.");
        model.addActionListener(e -> modelChosen());
        effort.setToolTipText("How hard the model thinks: harder is slower and costs more");
        effort.addActionListener(e -> modelChosen());
        left.add(model);
        left.add(effort);
        bottom.add(left, BorderLayout.CENTER);
        send.setToolTipText("Ask (Enter). Shift+Enter: a new line");
        send.addActionListener(e -> sendOrStop());
        bottom.add(send, BorderLayout.EAST);
        c.add(bottom, BorderLayout.SOUTH);
        updateSend();
        return c;
    }

    private JComponent suggestionsCard() {
        suggestions.setLayout(new BoxLayout(suggestions, BoxLayout.Y_AXIS));
        suggestions.setBorder(BorderFactory.createEmptyBorder(24, 16, 16, 16));
        suggestions.setOpaque(false);
        JScrollPane sp = new JScrollPane(suggestions);
        sp.setBorder(null);
        return sp;
    }

    /** The welcome: questions to start with, for what's open and the services described, and what to set up. */
    private void refreshSuggestions() {
        suggestions.removeAll();
        JLabel hello = new JLabel("<html><b>Ask about your logs</b><br>Copilot reads them with Logsift's tools, "
                + "and every line it cites is a click away.</html>");
        hello.setAlignmentX(LEFT_ALIGNMENT);
        suggestions.add(hello);
        suggestions.add(Box.createVerticalStrut(12));
        logsift.agent.LogCatalog catalog = host.catalog();
        List<String> services = catalog == null ? List.of()
                : catalog.entries().stream().map(e -> e.service().name()).filter(n -> !n.isEmpty()).toList();
        for (String q : suggested(host.openLogs(), host.chosenLines() != null, services)) {
            JButton b = new JButton(q);
            b.setName("example");
            b.setAlignmentX(LEFT_ALIGNMENT);
            b.setHorizontalAlignment(JButton.LEFT);
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.setToolTipText("Ask this");
            b.addActionListener(e -> {
                input.setText(q);
                sendOrStop();
            });
            suggestions.add(b);
            suggestions.add(Box.createVerticalStrut(6));
        }
        suggestions.add(Box.createVerticalStrut(14));
        JLabel setUp = new JLabel("<html><b>Set it up</b></html>");
        setUp.setAlignmentX(LEFT_ALIGNMENT);
        suggestions.add(setUp);
        suggestions.add(Box.createVerticalStrut(6));
        for (Setting st : settingsShown(services, service.options(), (ChatEngine.Model) model.getSelectedItem())) {
            suggestions.add(setting(st));
            suggestions.add(Box.createVerticalStrut(6));
        }
        suggestions.revalidate();
        suggestions.repaint();
    }

    /**
     * Something the person can set, as the welcome shows it.
     *
     * @param title  what it is
     * @param now    how it is now
     * @param button the button's words
     * @param action what the button opens: locations, settings, model, program
     */
    record Setting(String title, String now, String button, String action) {}

    static List<Setting> settingsShown(List<String> services, logsift.chat.ChatInstructions.Options o,
                                       ChatEngine.Model m) {
        List<Setting> l = new ArrayList<>();
        l.add(new Setting("Where your logs are", services.isEmpty()
                ? "Not described yet: describe your services' log folders, and ask “what went wrong in orders this "
                + "morning?” without a path."
                : "It knows " + services.size() + (services.size() == 1 ? " service: " : " services: ")
                + String.join(", ", services.subList(0, Math.min(6, services.size())))
                + (services.size() > 6 ? ", …" : "") + ".", "Log Locations…", "locations"));
        List<String> told = new ArrayList<>();
        if (o.locations()) {
            told.add("where your logs are");
        }
        if (o.plans()) {
            told.add("step-by-step plans");
        }
        if (o.now()) {
            told.add("the time now");
        }
        if (!o.own().isEmpty()) {
            told.add("your own instructions");
        }
        l.add(new Setting("What it's told", "Logsift's instructions" + (told.isEmpty() ? "" : ", " + String.join(", ", told))
                + ". Add your own: names you use, what's normal.", "Assistant Settings…", "settings"));
        l.add(new Setting("The model", (m == null ? "Chosen below" : m.name() + ", below")
                + ": each shows its price in AI credits per million tokens. Each answer shows what it cost.",
                "Choose…", "model"));
        return l;
    }

    private JComponent setting(Setting st) {
        JPanel row = AssistantPanel.row();
        row.setName("setting:" + st.action());
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JPanel words = new JPanel(new BorderLayout(0, 2));
        words.setOpaque(false);
        JLabel l = new JLabel(st.title());
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        words.add(l, BorderLayout.NORTH);
        words.add(AssistantPanel.wrapped(st.now(), true), BorderLayout.CENTER);
        row.add(words, BorderLayout.CENTER);
        JButton b = new JButton(st.button());
        b.addActionListener(e -> {
            switch (st.action()) {
                case "locations" -> host.openLocations();
                case "settings" -> host.openSettings();
                case "model" -> {
                    model.requestFocusInWindow();
                    model.showPopup();
                }
                default -> {
                }
            }
            refreshSuggestions();
        });
        JPanel east = new JPanel(new BorderLayout());
        east.setOpaque(false);
        east.add(b, BorderLayout.NORTH);
        row.add(east, BorderLayout.EAST);
        return row;
    }

    static List<String> suggested(List<OpenLog> logs, boolean chosen, List<String> services) {
        List<String> l = new ArrayList<>();
        OpenLog shown = logs.isEmpty() ? null : logs.get(0);
        if (shown != null && shown.table()) {
            l.add("Describe " + shown.name() + ": what each column holds");
            l.add("Which rows or columns of " + shown.name() + " look odd?");
            l.add("Total the biggest numeric column by its main group");
        } else if (shown != null) {
            if (chosen) {
                l.add("Explain the chosen lines");
            }
            l.add("What went wrong in " + shown.name() + "?");
            l.add("Errors and warnings by hour, and what changed");
            l.add("Which exceptions are the root causes?");
            if (logs.size() > 1) {
                l.add("Compare " + shown.name() + " with " + logs.get(1).name());
            }
        }
        if (!services.isEmpty()) {
            String a = services.get(0);
            l.add("What went wrong in " + a + " this morning?");
            if (shown == null) {
                l.add("Errors in " + a + " in the last hour, minute by minute");
                l.add("Was " + a + " slow today? The slowest requests and why");
            }
            if (services.size() > 1) {
                l.add("Which of my services had errors in the last hour?");
            } else {
                l.add("Check the health of " + a);
            }
        } else if (shown == null) {
            l.add("What can you do with my logs?");
            l.add("Where are the logs on this computer?");
        }
        return l;
    }

    // ---------------------------------------------------------------- connecting

    private void connected(ChatService.State s) {
        state = s;
        if (!s.ready()) {
            showProblem(s.problem());
            return;
        }
        ChatEntryView.modelNames = id -> s.models().stream().filter(m -> m.id().equals(id))
                .map(ChatEngine.Model::name).findFirst().orElse(id);
        chooseSilently = true;
        DefaultComboBoxModel<ChatEngine.Model> mm = new DefaultComboBoxModel<>();
        s.models().forEach(mm::addElement);
        model.setModel(mm);
        ChatEngine.Model pick = ChatService.pick(s.models(), service.settings().model());
        if (pick != null) {
            model.setSelectedItem(pick);
        }
        fillEfforts(pick, service.settings().effort());
        chooseSilently = false;
        status.setText(s.engine().name() + " · " + s.user());
        refreshCredits(s.quota());
        if (chat == null) {
            newChat();
        } else {
            showCard(transcript.chat() == null ? "empty" : "chat");
        }
        updateSend();
    }

    private void showProblem(String problem) {
        message.removeAll();
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(24, 16, 16, 16));
        JLabel l = new JLabel("<html><b>The assistant isn't ready</b><br><br>" + ChatMarkdownEsc.esc(problem) + "</html>");
        l.setAlignmentX(LEFT_ALIGNMENT);
        p.add(l);
        p.add(Box.createVerticalStrut(12));
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        b.setOpaque(false);
        b.setAlignmentX(LEFT_ALIGNMENT);
        JButton again = new JButton("Try Again");
        again.addActionListener(e -> {
            showMessage("Connecting to GitHub Copilot…", null);
            service.reconnect().thenAccept(s -> SwingUtilities.invokeLater(() -> connected(s)));
        });
        b.add(again);
        b.add(Box.createHorizontalStrut(6));
        JButton choose = new JButton("Choose Copilot Program…");
        choose.addActionListener(e -> chooseProgram());
        b.add(choose);
        p.add(b);
        p.add(Box.createVerticalStrut(12));
        JLabel help = new JLabel("<html><a href=''>How to set up the assistant</a></html>");
        help.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        help.setAlignmentX(LEFT_ALIGNMENT);
        help.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                HelpSupport.browse(ChatPanel.this, HelpSupport.guide("assistant"));
            }
        });
        p.add(help);
        message.add(p, BorderLayout.NORTH);
        status.setText("Not connected");
        showCard("message");
        updateSend();
    }

    private void chooseProgram() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("The copilot program");
        if (fc.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
            service.saveSettings(service.settings().withCopilot(fc.getSelectedFile().getAbsolutePath()));
            showMessage("Connecting to GitHub Copilot…", null);
            service.state().thenAccept(s -> SwingUtilities.invokeLater(() -> connected(s)));
        }
    }

    private void showMessage(String text, String detail) {
        message.removeAll();
        JLabel l = new JLabel("<html>" + ChatMarkdownEsc.esc(text) + (detail == null ? "" : "<br>" + detail) + "</html>",
                JLabel.CENTER);
        l.setForeground(Theme.dimmedForeground());
        message.add(l, BorderLayout.CENTER);
        showCard("message");
    }

    private void showCard(String name) {
        cards.show(center, name);
        if (name.equals("empty")) {
            refreshSuggestions();
        }
    }

    // ---------------------------------------------------------------- chats

    /** A new chat: stored only once something is asked. */
    void newChat() {
        if (busy) {
            return;
        }
        closeSession();
        chat = null;
        title.setText("New chat");
        transcript.show(null);
        showCard(state != null && state.ready() ? "empty" : "message");
        refreshCredits(null);
        input.requestFocusInWindow();
    }

    private void openChat(String id, long seq) {
        if (busy) {
            return;
        }
        ChatInfo c = store.chat(id);
        if (c == null) {
            return;
        }
        closeSession();
        chat = c;
        title.setText(c.title());
        showCard("chat");
        validate();
        if (seq > 0) {
            transcript.showAround(id, seq);
        } else {
            transcript.show(id);
        }
        if (c.model() != null && state != null) {
            for (int i = 0; i < model.getItemCount(); i++) {
                if (model.getItemAt(i).id().equals(c.model())) {
                    chooseSilently = true;
                    model.setSelectedIndex(i);
                    chooseSilently = false;
                }
            }
        }
        showCard("chat");
        refreshCredits(null);
    }

    private void showHistory() {
        history.refresh();
        showCard("history");
    }

    private void rename() {
        if (chat == null) {
            return;
        }
        String t = (String) javax.swing.JOptionPane.showInputDialog(this, "Name:", "Rename Chat",
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, chat.title());
        if (t != null && !t.isBlank()) {
            store.rename(chat.id(), t.strip());
            chat = store.chat(chat.id());
            title.setText(chat.title());
        }
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    // ---------------------------------------------------------------- asking

    private void sendOrStop() {
        if (busy) {
            if (session != null) {
                session.stop();
            }
            return;
        }
        String q = input.getText().strip();
        if (q.isEmpty() || state == null || !state.ready()) {
            return;
        }
        ChatEngine.Model m = (ChatEngine.Model) model.getSelectedItem();
        if (m == null) {
            return;
        }
        String context = context();
        if (chat == null) {
            List<OpenLog> logs = host.openLogs();
            chat = store.create(ChatSession.title(q), m.id(), logs.isEmpty() ? null : logs.get(0).where());
            title.setText(chat.title());
            transcript.show(chat.id());
        }
        if (session == null) {
            session = new ChatSession(store, state.engine(), chat, ChatTools.of(logsift.agent.LogTools.all(),
                    host.workspace()), logsift.chat.ChatInstructions.text(service.options(), host.catalog()), m.id(), (String) effort.getSelectedItem(),
                    view());
        }
        showCard("chat");
        input.setText("");
        dropLines = false;
        busy = true; // until the session says otherwise
        updateSend();
        ChatSession s = session;
        // starting Copilot's session and sending wait on it: not on the window's thread
        Thread t = new Thread(() -> {
            try {
                s.ask(q, context);
            } catch (Exception e) {
                // shown in the chat as an error, and the turn is over
            }
        }, "logsift-chat-ask");
        t.setDaemon(true);
        t.start();
    }

    private ChatSession.View view() {
        return new ChatSession.View() {
            @Override
            public void changed(ChatMessage m, boolean added) {
                SwingUtilities.invokeLater(() -> transcript.changed(m, added));
            }

            @Override
            public void busy(boolean b) {
                SwingUtilities.invokeLater(() -> {
                    busy = b;
                    updateSend();
                    if (!b) {
                        refreshCredits(null);
                        service.quota().thenAccept(q -> SwingUtilities.invokeLater(() -> refreshCredits(q)));
                    }
                });
            }

            @Override
            public void titled(String t) {
                SwingUtilities.invokeLater(() -> title.setText(t));
            }
        };
    }

    /**
     * What goes before the question for the model: the open logs by handle (the shown one first)
     * and the lines chosen. Not shown in the chat.
     */
    String context() {
        String now = service.options().now() ? logsift.chat.ChatInstructions.now(java.time.ZonedDateTime.now()) : null;
        String window = window();
        return now == null ? window : window == null ? now : now + "\n" + window;
    }

    private String window() {
        List<OpenLog> logs = host.openLogs();
        if (logs.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("[Logsift window] ");
        if (!dropLog) {
            OpenLog s = logs.get(0);
            b.append("Shown: ").append(s.handle()).append(" = ").append(s.name()).append(" (")
                    .append(String.format("%,d", s.lines())).append(s.table() ? " rows, a table" : " lines").append(", ")
                    .append(s.where()).append(").");
        }
        if (logs.size() > (dropLog ? 0 : 1)) {
            b.append(" Also open:");
            for (OpenLog l : logs.subList(dropLog ? 0 : 1, logs.size())) {
                b.append(' ').append(l.handle()).append(" = ").append(l.name()).append(';');
            }
            b.setLength(b.length() - 1);
            b.append('.');
        }
        long[] lines = host.chosenLines();
        if (lines != null && !dropLines && !dropLog) {
            b.append(" The person chose ").append(logs.get(0).handle()).append(" lines ").append(lines[0])
                    .append(lines[1] != lines[0] ? "-" + lines[1] : "").append(" (read them with read_lines).");
        }
        return b.toString();
    }

    private void updateSend() {
        boolean ready = state != null && state.ready();
        send.setEnabled(ready && (busy || !input.getText().isBlank()));
        if (busy != !sendShown) {
            sendShown = !busy;
            send.setIcon(Icons.of(busy ? "square" : "send"));
            send.setToolTipText(busy ? "Stop the answer (Enter)" : "Ask (Enter). Shift+Enter: a new line");
        }
        model.setEnabled(ready);
        effort.setEnabled(ready && effort.getItemCount() > 0);
    }

    // ---------------------------------------------------------------- context chips

    private void refreshContext() {
        if (!isShowing()) {
            return;
        }
        List<OpenLog> logs = host.openLogs();
        long[] lines = host.chosenLines();
        String key = (logs.isEmpty() ? "" : logs.get(0).handle() + logs.get(0).name()) + "|"
                + (lines == null ? "" : lines[0] + "-" + lines[1]) + "|" + dropLog + dropLines;
        if (key.equals(lastContextKey)) {
            return;
        }
        if (!lastContextKey.isEmpty() && !lastContextKey.split("\\|", -1)[0].equals(key.split("\\|", -1)[0])) {
            dropLog = false; // another log shown: it goes with the question again
        }
        lastContextKey = key;
        chips.removeAll();
        if (!logs.isEmpty() && !dropLog) {
            chips.add(chip(logs.get(0).name(), "Copilot is told this log is shown. Click × to leave it out.",
                    () -> dropLog = true));
            if (lines != null && !dropLines) {
                String l = lines[0] == lines[1] ? "line " + String.format("%,d", lines[0])
                        : "lines " + String.format("%,d", lines[0]) + "–" + String.format("%,d", lines[1]);
                chips.add(chip(l, "The chosen lines go with the question", () -> dropLines = true));
            }
        }
        chips.revalidate();
        chips.repaint();
        if (center.isShowing() && chat == null && state != null && state.ready()) {
            refreshSuggestions();
        }
    }

    private JComponent chip(String text, String tip, Runnable drop) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)) {
            @Override
            protected void paintComponent(java.awt.Graphics g0) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.pick(new Color(0xE8EDF5), new Color(0x33383F)));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 2));
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
        l.setToolTipText(tip);
        p.add(l);
        JButton x = new JButton("×");
        x.putClientProperty("JButton.buttonType", "toolBarButton");
        x.setMargin(new java.awt.Insets(0, 2, 0, 2));
        x.setFocusable(false);
        x.setToolTipText("Leave out of the next question");
        x.addActionListener(e -> {
            drop.run();
            refreshContext();
        });
        p.add(x);
        return p;
    }

    // ---------------------------------------------------------------- models and credits

    private void modelChosen() {
        if (chooseSilently || state == null) {
            return;
        }
        ChatEngine.Model m = (ChatEngine.Model) model.getSelectedItem();
        if (m == null) {
            return;
        }
        if (!m.enabled()) {
            status.setText(m.name() + " isn't enabled for your account");
        }
        Object src = effort.getSelectedItem();
        if (!m.efforts().contains(String.valueOf(src))) {
            chooseSilently = true;
            fillEfforts(m, null);
            chooseSilently = false;
        }
        String e = (String) effort.getSelectedItem();
        service.saveSettings(service.settings().withModel(m.id(), e));
        ChatSession s = session;
        if (s != null) {
            Thread t = new Thread(() -> {
                try {
                    s.setModel(m.id(), e);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> status.setText("Couldn't change the model: " + ex.getMessage()));
                }
            }, "logsift-chat-model");
            t.setDaemon(true);
            t.start();
        }
    }

    private void fillEfforts(ChatEngine.Model m, String wanted) {
        DefaultComboBoxModel<String> em = new DefaultComboBoxModel<>();
        if (m != null) {
            m.efforts().forEach(em::addElement);
        }
        effort.setModel(em);
        if (m != null && !m.efforts().isEmpty()) {
            effort.setSelectedItem(wanted != null && m.efforts().contains(wanted) ? wanted
                    : m.effort() != null ? m.effort() : m.efforts().get(0));
        }
        effort.setVisible(em.getSize() > 0);
        effort.setEnabled(em.getSize() > 0);
    }

    private ChatEngine.Quota quota;

    private void refreshCredits(ChatEngine.Quota q) {
        if (q != null) {
            quota = q;
        }
        StringBuilder b = new StringBuilder();
        if (chat != null) {
            ChatInfo c = store.chat(chat.id());
            if (c != null && c.nanoAiu() > 0) {
                b.append("This chat ").append(ChatEntryView.credits(c.credits()));
            }
        }
        if (quota != null && !quota.unlimited() && quota.allowed() > 0) {
            if (b.length() > 0) {
                b.append(" · ");
            }
            b.append(String.format("%.0f%% left", quota.leftPct()));
        }
        credits.setText(b.length() == 0 ? "Usage" : b.toString());
    }

    /** What the assistant cost: this chat, today, this month, by model, and what's left. */
    private void showUsage() {
        JPopupMenu pop = new JPopupMenu();
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        p.add(new JLabel(usageHtml(store, quota, chat == null ? null : store.chat(chat.id()))), BorderLayout.CENTER);
        pop.add(p);
        pop.show(credits, credits.getWidth() - pop.getPreferredSize().width, credits.getHeight());
    }

    static String usageHtml(ChatStore store, ChatEngine.Quota quota, ChatInfo chat) {
        StringBuilder b = new StringBuilder("<html><table cellpadding=2>");
        if (quota != null) {
            b.append("<tr><td><b>Premium requests</b></td><td align=right>");
            if (quota.unlimited()) {
                b.append("unlimited");
            } else {
                b.append(String.format("%,d of %,d used (%.0f%% left)", quota.used(), quota.allowed(), quota.leftPct()));
            }
            b.append("</td></tr>");
            if (quota.resets() != null && quota.resets().length() >= 10) {
                b.append("<tr><td>Starts again</td><td align=right>").append(quota.resets(), 0, 10).append("</td></tr>");
            }
        }
        if (chat != null) {
            b.append("<tr><td><b>This chat</b></td><td align=right>").append(ChatEntryView.credits(chat.credits()))
                    .append("</td></tr>");
        }
        ZoneId z = ZoneId.systemDefault();
        long today = LocalDate.now(z).atStartOfDay(z).toInstant().toEpochMilli();
        long month = LocalDate.now(z).withDayOfMonth(1).atStartOfDay(z).toInstant().toEpochMilli();
        double t = store.usage(today).stream().mapToDouble(ChatStore.Usage::credits).sum();
        b.append("<tr><td><b>Today</b></td><td align=right>").append(ChatEntryView.credits(t)).append("</td></tr>");
        List<ChatStore.Usage> m = store.usage(month);
        b.append("<tr><td><b>This month</b></td><td align=right>")
                .append(ChatEntryView.credits(m.stream().mapToDouble(ChatStore.Usage::credits).sum())).append("</td></tr>");
        for (ChatStore.Usage u : m) {
            b.append("<tr><td>&nbsp;&nbsp;").append(ChatMarkdownEsc.esc(ChatEntryView.modelNames.apply(u.model())))
                    .append("</td><td align=right>").append(ChatEntryView.credits(u.credits())).append(" · ")
                    .append(String.format("%,d", u.answers())).append(u.answers() == 1 ? " answer" : " answers")
                    .append(" · ").append(ChatEntryView.tokens(u.tokensIn() + u.tokensOut())).append(" tokens</td></tr>");
        }
        b.append("</table><br><font size=-1>AI credits as Copilot reports them for each answer.</font></html>");
        return b.toString();
    }

    /** A model as the list shows it: its name, and what it costs. */
    private static final class ModelRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(list, value, index, sel, focus);
            if (value instanceof ChatEngine.Model m) {
                String price = m.inputPrice() > 0 ? price(m.inputPrice()) + " · " + price(m.outputPrice()) : "";
                if (index < 0) {
                    setText(m.name()); // in the box itself: short
                } else {
                    setText("<html>" + ChatMarkdownEsc.esc(m.name()) + (price.isEmpty() ? ""
                            : "&nbsp;&nbsp;<font color='" + ChatMarkdown.hex(Theme.dimmedForeground()) + "'>" + price
                            + "</font>") + "</html>");
                }
                setEnabled(m.enabled());
                setToolTipText(m.inputPrice() > 0 ? price + " AI credits per million tokens read · written"
                        + (m.context() > 0 ? ". Reads up to " + ChatEntryView.tokens(m.context()) + " tokens." : "")
                        : null);
            }
            return this;
        }

        private static String price(double p) {
            return p == Math.rint(p) ? String.format("%.0f", p) : String.format("%.1f", p);
        }
    }

    // ---------------------------------------------------------------- links and tool actions

    private void openLink(String href) {
        ChatMarkdown.LineLink l = ChatMarkdown.LineLink.parse(href);
        if (l != null) {
            host.showLines(l.handle(), l.from(), l.to());
            return;
        }
        ChatMarkdown.FileLink f = ChatMarkdown.FileLink.parse(href);
        if (f != null) {
            if (java.nio.file.Files.isRegularFile(f.path())) {
                host.openFile(f.path(), f.line());
            } else {
                status.setText("Couldn't find " + f.path());
            }
            return;
        }
        if (href != null && ChatMarkdown.allowed(href) && !href.startsWith("logsift:")) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(href));
            } catch (Exception e) {
                status.setText("Couldn't open " + href);
            }
        }
    }

    private void toolAction(ChatMessage tool, String what) {
        var a = ChatEntryView.args(tool);
        String h = String.valueOf(a.getOrDefault("log", ""));
        switch (what) {
            case ChatEntryView.SHOW_LINES -> {
                long from = ChatEntryView.toLong(a.get("from"));
                long count = a.get("count") == null ? 50 : ChatEntryView.toLong(a.get("count"));
                host.showLines(h, from, from + Math.max(1, count) - 1);
            }
            case ChatEntryView.FIND_IN_LOG -> host.find(h, String.valueOf(a.get("query")));
            case ChatEntryView.OPEN_QUERY -> host.query(h, String.valueOf(a.get("sql")));
            case ChatEntryView.COPY -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(tool.text() == null ? "" : tool.text()), null);
            default -> {
            }
        }
    }

    /** Focus on the question box. */
    void focusInput() {
        input.requestFocusInWindow();
    }

    /** Puts a question in the box (from a menu: "Ask about these lines"). */
    void prefill(String q) {
        input.setText(q);
        input.requestFocusInWindow();
    }

    void close() {
        contextTimer.stop();
        closeSession();
    }

    // ---------------------------------------------------------------- for tests

    void linkForTest(String href) {
        openLink(href);
    }

    void toolActionForTest(ChatMessage tool, String what) {
        toolAction(tool, what);
    }

    ChatTranscript transcriptForTest() {
        return transcript;
    }

    ChatHistory historyForTest() {
        return history;
    }

    JTextArea inputForTest() {
        return input;
    }

    void sendForTest() {
        sendOrStop();
    }

    boolean busyForTest() {
        return busy;
    }

    JComboBox<ChatEngine.Model> modelForTest() {
        return model;
    }

    String creditsForTest() {
        return credits.getText();
    }

    String statusForTest() {
        return status.getText();
    }

    String titleForTest() {
        return title.getText();
    }

    JPanel chipsForTest() {
        return chips;
    }

    void showHistoryForTest() {
        showHistory();
    }

    String usageForTest() {
        return usageHtml(store, quota, chat == null ? null : store.chat(chat.id()));
    }

    ChatInfo chatForTest() {
        return chat;
    }

    /** The questions the welcome offers. */
    List<String> examplesForTest() {
        List<String> l = new ArrayList<>();
        for (Component c : suggestions.getComponents()) {
            if (c instanceof JButton b && "example".equals(b.getName())) {
                l.add(b.getText());
            }
        }
        return l;
    }

    /** What the welcome offers to set up. */
    List<String> settingsForTest() {
        List<String> l = new ArrayList<>();
        for (Component c : suggestions.getComponents()) {
            if (c.getName() != null && c.getName().startsWith("setting:")) {
                l.add(c.getName().substring("setting:".length()));
            }
        }
        return l;
    }

    void askExampleForTest(String q) {
        for (Component c : suggestions.getComponents()) {
            if (c instanceof JButton b && q.equals(b.getText())) {
                b.doClick();
                return;
            }
        }
        throw new IllegalArgumentException("no example " + q);
    }

    void refreshContextForTest() {
        lastContextKey = "";
        refreshContext();
    }

    /** HTML escaping for labels. */
    static final class ChatMarkdownEsc {
        static String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
