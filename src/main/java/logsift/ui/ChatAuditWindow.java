package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import logsift.chat.ChatAudit;
import logsift.chat.ChatService;
import logsift.chat.ChatStore;

/**
 * The assistant's audit, in a window of its own: every question asked, newest first, a page at a
 * time, with what answering it took and what looks wrong or wasted; one opened shows each step,
 * what went with it and the answer, and takes the person's verdict. Read from the chats as stored:
 * no model is asked anything.
 */
final class ChatAuditWindow extends JFrame {
    private static final Preferences PREFS = AppState.prefs().node("chat").node("audit");
    private static ChatAuditWindow open;

    /** Opens it (one for the app), or brings it forward. {@code toChat} opens a question in the assistant. */
    static ChatAuditWindow open(Component near, ChatStore store, OpenChat toChat, Runnable openSettings) {
        if (open == null || !open.isDisplayable()) {
            open = new ChatAuditWindow(store, toChat, openSettings);
            open.setLocationRelativeTo(near == null ? null : SwingUtilities.getWindowAncestor(near));
        }
        open.setVisible(true);
        open.toFront();
        open.reload();
        return open;
    }

    /** Shows what changed (a verdict given in a chat), when it's open. */
    static void refreshOpen() {
        if (open != null && open.isDisplayable()) {
            open.reload();
        }
    }

    /** For tests: the window, when open. */
    static ChatAuditWindow openForTest() {
        return open != null && open.isDisplayable() ? open : null;
    }

    /** Opens a question in its chat. */
    interface OpenChat {
        void open(String chat, long seq);
    }

    /** How far back the list goes. */
    enum Period {
        TODAY("Today", 1), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), ALL("All time", 0);

        final String label;
        final int days;

        Period(String label, int days) {
            this.label = label;
            this.days = days;
        }

        long since() {
            if (days == 0) {
                return 0;
            }
            java.time.ZonedDateTime start = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault());
            return start.minusDays(days - 1L).toInstant().toEpochMilli();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Which questions the list shows. */
    enum Show {
        ALL("All questions"), FLAGGED("Flagged"), BAD("Rated bad"), GOOD("Rated good"), UNRATED("Not rated");

        final String label;

        Show(String label) {
            this.label = label;
        }

        boolean shows(ChatAudit.Turn t) {
            return switch (this) {
                case ALL -> true;
                case FLAGGED -> !t.flags().isEmpty();
                case BAD -> t.rating() < 0;
                case GOOD -> t.rating() > 0;
                case UNRATED -> t.rating() == 0;
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ChatStore store;
    private final OpenChat toChat;
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "logsift-audit");
        t.setDaemon(true);
        return t;
    });

    /** What's loaded: the newest pages since the period's start (before the table, which reads it). */
    private final List<ChatAudit.Turn> turns = new ArrayList<>();
    private final JComboBox<Period> period = new JComboBox<>(Period.values());
    private final JComboBox<Show> show = new JComboBox<>(Show.values());
    private final JLabel summary = new JLabel(" ");
    private final Turns model = new Turns();
    private final JTable table = new JTable(model);
    private final TableRowSorter<Turns> sorter = new TableRowSorter<>(model);
    private final JButton more = new JButton("Load more");
    private final JEditorPane details = new JEditorPane();
    private final JButton good = new JButton("Good", Icons.of("thumbs-up"));
    private final JButton bad = new JButton("Bad", Icons.of("thumbs-down"));
    private final JTextField note = new JTextField();
    private final JButton saveNote = new JButton("Save Note");
    private final JButton inChat = new JButton("Open in Chat", Icons.of("message-square"));
    private final JButton instructions = new JButton("Instructions…", Icons.of("file-text"));
    private final JLabel flagsHint = new JLabel(" ");

    private boolean moreToLoad;
    private boolean loading;
    private int generation;
    private ChatAudit.Turn shown;

    /** Told the chat whose verdict was changed here, so its answers show it. */
    static final List<java.util.function.Consumer<String>> VERDICTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ChatAuditWindow(ChatStore store, OpenChat toChat, Runnable openSettings) {
        super("Assistant Audit · " + logsift.core.Channel.appName());
        this.store = store;
        this.toChat = toChat;
        setName("auditWindow");
        setIconImages(logsift.tools.IconGenerator.windowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(new Dimension(1100, 720));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.background());
        setContentPane(root);

        // ---- what the list shows
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel choose = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        choose.setOpaque(false);
        period.setName("auditPeriod");
        show.setName("auditShow");
        period.setSelectedItem(Period.valueOf(PREFS.get("period", Period.WEEK.name())));
        show.setSelectedItem(Show.valueOf(PREFS.get("show", Show.ALL.name())));
        period.addActionListener(e -> {
            PREFS.put("period", ((Period) period.getSelectedItem()).name());
            reload();
        });
        show.addActionListener(e -> {
            PREFS.put("show", ((Show) show.getSelectedItem()).name());
            filter();
        });
        choose.add(period);
        choose.add(show);
        JButton refresh = toolButton("refresh-cw", "Read the chats again", e -> reload());
        choose.add(refresh);
        top.add(choose, BorderLayout.WEST);
        summary.setName("auditSummary");
        summary.setForeground(Theme.dimmedForeground());
        top.add(summary, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        JButton export = new JButton("Export…", Icons.of("download"));
        export.setName("auditExport");
        export.setToolTipText("Save the questions listed as a Markdown report, to read or to give to an agent");
        export.addActionListener(e -> exportMenu(export));
        right.add(export);
        right.add(toolButton("settings", "Settings → Assistant → Audit: what it keeps and flags",
                e -> openSettings.run()));
        top.add(right, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        // ---- the questions
        table.setName("auditTable");
        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        int[] widths = {95, 330, 150, 130, 65, 95, 60, 45, 150, 60};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        sorter.setComparator(Turns.TOKENS, java.util.Comparator.comparingLong(o -> (Long) o));
        sorter.setComparator(Turns.TIME, java.util.Comparator.comparingLong(o -> (Long) o));
        sorter.setComparator(Turns.TOOLS, java.util.Comparator.comparingLong(o -> (Long) o));
        sorter.setComparator(Turns.WHEN, java.util.Comparator.comparingLong(o -> (Long) o));
        table.setDefaultRenderer(Long.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object v) {
                setHorizontalAlignment(RIGHT);
                setText(v == null ? "" : String.format("%,d", (Long) v));
            }
        });
        table.getColumnModel().getColumn(Turns.WHEN).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object v) {
                setText(v == null ? "" : WHEN.format(java.time.Instant.ofEpochMilli((Long) v)
                        .atZone(java.time.ZoneId.systemDefault())));
            }
        });
        table.getColumnModel().getColumn(Turns.TIME).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object v) {
                setHorizontalAlignment(RIGHT);
                setText(v == null ? "" : ChatEntryView.seconds((Long) v));
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && shown != null) {
                    toChat.open(shown.chat(), shown.seq());
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(table);
        listScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            javax.swing.JScrollBar b = listScroll.getVerticalScrollBar();
            if (!e.getValueIsAdjusting() && b.getValue() + b.getVisibleAmount() >= b.getMaximum() - 40) {
                loadMore(); // near the end: the next page
            }
        });
        JPanel list = new JPanel(new BorderLayout());
        list.add(listScroll, BorderLayout.CENTER);
        JPanel moreRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
        more.setName("auditMore");
        more.putClientProperty("JButton.buttonType", "toolBarButton");
        more.addActionListener(e -> loadMore());
        more.setVisible(false);
        moreRow.add(more);
        list.add(moreRow, BorderLayout.SOUTH);

        // ---- one question
        details.setName("auditDetails");
        details.setContentType("text/html");
        details.setEditable(false);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        JPanel one = new JPanel(new BorderLayout());
        one.add(new JScrollPane(details), BorderLayout.CENTER);
        JPanel verdict = new JPanel(new BorderLayout(6, 0));
        verdict.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel judge = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        good.setName("auditGood");
        bad.setName("auditBad");
        good.setToolTipText("A good answer");
        bad.setToolTipText("A bad answer: say what went wrong in the note");
        good.addActionListener(e -> rate(shown != null && shown.rating() > 0 ? 0 : 1));
        bad.addActionListener(e -> rate(shown != null && shown.rating() < 0 ? 0 : -1));
        judge.add(good);
        judge.add(bad);
        judge.add(new JLabel("Note:"));
        verdict.add(judge, BorderLayout.WEST);
        note.setName("auditNote");
        note.setToolTipText("What went wrong, or what it should have done: kept with the question");
        note.addActionListener(e -> rate(shown == null ? 0 : shown.rating()));
        verdict.add(note, BorderLayout.CENTER);
        JPanel go = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        saveNote.setName("auditSaveNote");
        saveNote.addActionListener(e -> rate(shown == null ? 0 : shown.rating()));
        go.add(saveNote);
        inChat.setName("auditOpenChat");
        inChat.setToolTipText("Open this question in its chat");
        inChat.addActionListener(e -> {
            if (shown != null) {
                toChat.open(shown.chat(), shown.seq());
            }
        });
        go.add(instructions);
        instructions.setName("auditInstructions");
        instructions.setToolTipText("What the assistant was told before this question");
        instructions.addActionListener(e -> showInstructions());
        go.add(inChat);
        verdict.add(go, BorderLayout.EAST);
        one.add(verdict, BorderLayout.SOUTH);
        enableVerdict(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, one);
        split.setName("auditSplit");
        split.setResizeWeight(0.5);
        split.setDividerLocation(330);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        flagsHint.setForeground(Theme.dimmedForeground());
        flagsHint.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        root.add(flagsHint, BorderLayout.SOUTH);

        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
        ChatFonts.follow(root);
    }

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.ENGLISH);

    private static JButton toolButton(String icon, String tip, java.awt.event.ActionListener a) {
        JButton b = new JButton(Icons.of(icon));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(a);
        return b;
    }

    @Override
    public void dispose() {
        super.dispose();
        work.shutdown();
        if (open == this) {
            open = null;
        }
    }

    private ChatAudit.Settings settings() {
        return ChatService.get().audit();
    }

    // ---------------------------------------------------------------- the list

    /** Reads the newest page again, keeping the question shown. */
    void reload() {
        int gen = ++generation;
        loading = true;
        long since = ((Period) period.getSelectedItem()).since();
        ChatAudit.Settings s = settings();
        ChatAudit.Turn keep = shown;
        int want = Math.max(ChatAudit.PAGE, turns.size());
        work.submit(() -> {
            List<ChatAudit.Turn> l;
            ChatStore.Totals totals;
            try {
                l = ChatAudit.turns(store, since, want, s);
                totals = store.totals(since);
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (gen == generation) {
                        loading = false;
                        summary.setText("The chats couldn't be read: " + ex.getMessage());
                    }
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                loading = false;
                turns.clear();
                turns.addAll(l);
                moreToLoad = l.size() >= want && totals.questions() > l.size();
                model.fireTableDataChanged();
                filter();
                showTotals(totals);
                select(keep);
            });
        });
    }

    /** The next page, below the last one loaded. */
    void loadMore() {
        if (loading || !moreToLoad || turns.isEmpty()) {
            return;
        }
        int gen = generation;
        loading = true;
        long since = ((Period) period.getSelectedItem()).since();
        ChatAudit.Settings s = settings();
        ChatAudit.Turn last = turns.get(turns.size() - 1);
        work.submit(() -> {
            List<ChatAudit.Turn> l;
            try {
                l = ChatAudit.page(store, since, last, ChatAudit.PAGE, s);
            } catch (RuntimeException ex) {
                l = List.of();
            }
            List<ChatAudit.Turn> page = l;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                loading = false;
                int from = turns.size();
                turns.addAll(page);
                moreToLoad = page.size() >= ChatAudit.PAGE;
                if (!page.isEmpty()) {
                    model.fireTableRowsInserted(from, turns.size() - 1);
                }
                more.setVisible(moreToLoad);
                showTotals(null);
            });
        });
    }

    private ChatStore.Totals lastTotals;

    private void showTotals(ChatStore.Totals t) {
        if (t != null) {
            lastTotals = t;
        }
        more.setVisible(moreToLoad);
        if (lastTotals == null) {
            return;
        }
        long flagged = turns.stream().filter(x -> !x.flags().isEmpty()).count();
        StringBuilder b = new StringBuilder();
        b.append(String.format("%,d", lastTotals.questions())).append(lastTotals.questions() == 1 ? " question" : " questions")
                .append(" · ").append(ChatEntryView.tokens(lastTotals.tokens())).append(" tokens");
        b.append(" · ").append(flagged).append(" flagged");
        if (turns.size() < lastTotals.questions()) {
            b.append(" of the ").append(turns.size()).append(" loaded");
        }
        summary.setText(b.toString());
        java.util.Map<ChatAudit.Flag, Long> counts = ChatAudit.flagCounts(turns);
        if (counts.isEmpty()) {
            flagsHint.setText("Nothing flagged. Rate an answer good or bad below, or with the buttons under it in the chat.");
        } else {
            List<String> parts = new ArrayList<>();
            counts.forEach((f, n) -> parts.add(f.label() + " " + n));
            flagsHint.setText("Flagged: " + String.join(" · ", parts));
        }
    }

    private void filter() {
        Show s = (Show) show.getSelectedItem();
        sorter.setRowFilter(s == Show.ALL ? null : new RowFilter<Turns, Integer>() {
            @Override
            public boolean include(Entry<? extends Turns, ? extends Integer> e) {
                return s.shows(turns.get(e.getIdentifier()));
            }
        });
    }

    private void select(ChatAudit.Turn t) {
        if (t != null) {
            for (int i = 0; i < turns.size(); i++) {
                ChatAudit.Turn x = turns.get(i);
                if (x.chat().equals(t.chat()) && x.seq() == t.seq()) {
                    int v = table.convertRowIndexToView(i);
                    if (v >= 0) {
                        table.getSelectionModel().setSelectionInterval(v, v);
                        table.scrollRectToVisible(table.getCellRect(v, 0, true));
                        showSelected();
                        return;
                    }
                }
            }
        }
        if (table.getRowCount() > 0 && table.getSelectedRow() < 0) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        } else {
            showSelected();
        }
    }

    /** The questions as the table shows them. */
    private final class Turns extends AbstractTableModel {
        static final int WHEN = 0;
        static final int QUESTION = 1;
        static final int CHAT = 2;
        static final int MODEL = 3;
        static final int TOKENS = 4;
        static final int COST = 5;
        static final int TIME = 6;
        static final int TOOLS = 7;
        static final int FLAGS = 8;
        static final int VERDICT = 9;
        private final String[] names = {"When", "Question", "Chat", "Model", "Tokens", "Cost", "Time", "Tools", "Flags",
                "Verdict"};

        @Override
        public int getRowCount() {
            return turns.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == TOKENS || c == TOOLS ? Long.class : c == WHEN || c == TIME ? Long.class : String.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            ChatAudit.Turn t = turns.get(r);
            return switch (c) {
                case WHEN -> t.time();
                case QUESTION -> t.question().replaceAll("\\s+", " ");
                case CHAT -> t.title();
                case MODEL -> t.model() == null ? "" : ChatEntryView.modelName(t.model());
                case TOKENS -> t.tokens();
                case COST -> t.cost();
                case TIME -> t.millis();
                case TOOLS -> t.toolCalls();
                case FLAGS -> String.join(", ", t.flags().stream().filter(f -> f != ChatAudit.Flag.RATED_BAD)
                        .map(ChatAudit.Flag::label).toList());
                case VERDICT -> t.rating() > 0 ? "Good" : t.rating() < 0 ? "Bad" : "";
                default -> "";
            };
        }
    }

    // ---------------------------------------------------------------- one question

    private void showSelected() {
        int v = table.getSelectedRow();
        ChatAudit.Turn t = v < 0 ? null : turns.get(table.convertRowIndexToModel(v));
        shown = t;
        enableVerdict(t != null);
        if (t == null) {
            details.setText(ChatMarkdown.page("*Choose a question to see what answering it took.*", details.getFont()));
            note.setText("");
            return;
        }
        note.setText(t.note());
        showRating(t.rating());
        ChatAudit.Turn asked = t;
        work.submit(() -> {
            String md;
            boolean told;
            try {
                md = ChatAudit.details(store, asked);
                told = store.turn(asked.chat(), asked.seq()).instructions() != null;
            } catch (RuntimeException ex) {
                md = "The question couldn't be read: " + ex.getMessage();
                told = false;
            }
            String text = md;
            boolean hasInstructions = told;
            SwingUtilities.invokeLater(() -> {
                if (shown == asked) {
                    details.setText(ChatMarkdown.page(text, details.getFont()));
                    details.setCaretPosition(0);
                    instructions.setEnabled(hasInstructions);
                }
            });
        });
    }

    private void enableVerdict(boolean on) {
        for (JComponent c : new JComponent[] {good, bad, note, saveNote, inChat, instructions}) {
            c.setEnabled(on);
        }
    }

    private void showRating(int r) {
        good.setSelected(r > 0);
        bad.setSelected(r < 0);
        good.setIcon(Icons.of("thumbs-up", r > 0 ? ChatColors.ok() : Theme.dimmedForeground(), 14));
        bad.setIcon(Icons.of("thumbs-down", r < 0 ? Theme.errorMark() : Theme.dimmedForeground(), 14));
    }

    /** Keeps the verdict (and the note) on the question shown. */
    void rate(int rating) {
        ChatAudit.Turn t = shown;
        if (t == null) {
            return;
        }
        String n = note.getText();
        try {
            store.rate(t.chat(), t.seq(), rating, n);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "The verdict couldn't be kept: " + ex.getMessage());
            return;
        }
        showRating(rating);
        reload();
        VERDICTS.forEach(v -> v.accept(t.chat()));
    }

    private void showInstructions() {
        ChatAudit.Turn t = shown;
        if (t == null) {
            return;
        }
        String text = store.turn(t.chat(), t.seq()).instructions();
        if (text == null) {
            return;
        }
        javax.swing.JTextArea a = new javax.swing.JTextArea(text);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(Theme.monoFont(a.getFont().getSize2D() - 1f));
        a.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(a);
        sp.setPreferredSize(new Dimension(760, 520));
        JOptionPane.showMessageDialog(this, sp, "Instructions (~" + String.format("%,d", (text.length() + 3) / 4)
                + " tokens)", JOptionPane.PLAIN_MESSAGE);
    }

    // ---------------------------------------------------------------- the report

    private void exportMenu(JButton from) {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem brief = new javax.swing.JMenuItem("Report of the Questions Listed…");
        brief.addActionListener(e -> export(false));
        javax.swing.JMenuItem full = new javax.swing.JMenuItem("Report with Details…");
        full.setToolTipText("Also the start of each tool's answer, what went with each question and the instructions");
        full.addActionListener(e -> export(true));
        m.add(brief);
        m.add(full);
        m.show(from, 0, from.getHeight());
    }

    /** The questions listed (as filtered), in the order shown. */
    List<ChatAudit.Turn> listed() {
        List<ChatAudit.Turn> l = new ArrayList<>();
        for (int v = 0; v < table.getRowCount(); v++) {
            l.add(turns.get(table.convertRowIndexToModel(v)));
        }
        return l;
    }

    private void export(boolean full) {
        List<ChatAudit.Turn> l = listed();
        if (l.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No questions are listed.");
            return;
        }
        FileDialog fd = new FileDialog(this, "Export Audit", FileDialog.SAVE);
        fd.setFile("assistant-audit-" + java.time.LocalDate.now() + ".md");
        fd.setVisible(true);
        if (fd.getFile() == null) {
            return;
        }
        Path out = Path.of(fd.getDirectory(), fd.getFile());
        work.submit(() -> {
            String done;
            try {
                Files.writeString(out, ChatAudit.report(store, l, full), StandardCharsets.UTF_8);
                done = "Saved " + l.size() + (l.size() == 1 ? " question to " : " questions to ") + out;
            } catch (Exception ex) {
                done = "Couldn't save the report: " + ex.getMessage();
            }
            String said = done;
            SwingUtilities.invokeLater(() -> flagsHint.setText(said));
        });
    }

    // ---------------------------------------------------------------- for tests

    List<ChatAudit.Turn> turnsForTest() {
        return List.copyOf(turns);
    }

    JButton goodForTest() {
        return good;
    }

    JButton badForTest() {
        return bad;
    }

    JButton openChatForTest() {
        return inChat;
    }

    JTable tableForTest() {
        return table;
    }

    JComboBox<Show> showForTest() {
        return show;
    }

    JComboBox<Period> periodForTest() {
        return period;
    }

    JTextField noteForTest() {
        return note;
    }

    JEditorPane detailsForTest() {
        return details;
    }

    JLabel summaryForTest() {
        return summary;
    }

    JButton moreForTest() {
        return more;
    }

    boolean loadingForTest() {
        return loading;
    }

    ChatAudit.Turn shownForTest() {
        return shown;
    }
}
