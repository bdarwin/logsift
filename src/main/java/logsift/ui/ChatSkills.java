package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import logsift.agent.Prompts;
import logsift.agent.Tool;

/**
 * What the assistant can do, at the chat's right: Logsift's skills by what they're for, each with
 * the tools it uses (a click shows them) and a question to start from, and the step-by-step
 * plans. Only words: nothing here is sent to Copilot, so it costs nothing.
 */
final class ChatSkills extends JPanel {

    /**
     * A skill: tools that go together.
     *
     * @param example a question to start from (put in the box, not sent)
     */
    record Skill(String title, String what, String example, List<String> tools) {}

    /** Logsift's skills, in the order people need them. Tools not named here go under "More". */
    static final List<Skill> SKILLS = List.of(
            new Skill("Find and read logs", "Finds your services' logs, opens them, searches and reads lines.",
                    "Where are the logs on this computer?",
                    List.of("list_logs", "open_log", "list_open", "search", "read_lines", "search_folder")),
            new Skill("What happened", "Errors and warnings over time, exceptions by root cause, the most "
                    + "frequent messages, durations.", "What went wrong in the last hour?",
                    List.of("insights", "exceptions", "top_messages", "metrics")),
            new Skill("SQL and pivots", "SQL over a log's columns or a CSV, and pivot tables, as the Query and "
                    + "Pivot tabs do.", "Errors per minute, as a table",
                    List.of("query", "query_examples", "pivot")),
            new Skill("Follow a request", "One request through every log, and logs merged by time.",
                    "Follow the request in the line I chose through all the logs",
                    List.of("follow_request", "merge_logs")),
            new Skill("Compare", "Two logs (a bad run and a good one), two files or two folders, side by side.",
                    "What's different between today's run and yesterday's?",
                    List.of("compare_logs", "compare_files", "compare_folders")),
            new Skill("JVMs", "Thread dumps, GC logs and profiles explained.",
                    "Explain this thread dump",
                    List.of("list_jvms", "thread_dump", "heap_histogram", "analyze_threads", "analyze_gc", "analyze_profile")),
            new Skill("Crashes", "Windows crash dumps and JVM crash files: why it crashed, its threads and modules.",
                    "Why did it crash?", List.of("analyze_crash", "crash_threads", "crash_modules")));

    private final Consumer<String> ask;
    private final JPanel list = AssistantPanel.column();
    private final Set<String> open = new LinkedHashSet<>();
    private JScrollPane sp;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * @param tools what the assistant gets
     * @param ask   puts a question in the box
     * @param close hides the panel
     */
    ChatSkills(List<Tool> tools, Consumer<String> ask, Runnable close) {
        super(new BorderLayout());
        this.ask = ask;
        tools.forEach(t -> this.tools.put(t.name(), t));
        setBackground(ChatHistory.sidebarBackground());
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 4));
        JLabel title = new JLabel("Skills");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setIcon(Icons.of("book-open", ChatColors.accent(), 14));
        top.add(title, BorderLayout.CENTER);
        JButton x = new JButton(Icons.of("panel-right-close"));
        x.setName("collapse");
        x.putClientProperty("JButton.buttonType", "toolBarButton");
        x.setToolTipText("Collapse the skills");
        x.setFocusable(false);
        x.addActionListener(e -> close.run());
        top.add(x, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        sp = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        add(sp, BorderLayout.CENTER);
        setPreferredSize(new Dimension(270, 100));
        fill();
    }

    /** The skills shown: those with a tool the assistant has, and "More" for the rest. */
    List<Skill> shown() {
        List<Skill> out = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        for (Skill s : SKILLS) {
            List<String> have = s.tools().stream().filter(tools::containsKey).toList();
            named.addAll(s.tools());
            if (!have.isEmpty()) {
                out.add(new Skill(s.title(), s.what(), s.example(), have));
            }
        }
        List<String> rest = tools.keySet().stream().filter(n -> !named.contains(n)).toList();
        if (!rest.isEmpty()) {
            out.add(new Skill("More", "Other things Logsift can do.", null, rest));
        }
        return out;
    }

    private void fill() {
        // where it was scrolled stays (the top at first): rebuilding must not move it
        int at = sp == null ? 0 : sp.getVerticalScrollBar().getValue();
        list.removeAll();
        for (Skill s : shown()) {
            list.add(skill(s));
            list.add(Box.createVerticalStrut(6));
        }
        list.add(Box.createVerticalStrut(8));
        JLabel plans = new JLabel("Step-by-step plans");
        plans.setFont(plans.getFont().deriveFont(Font.BOLD));
        plans.setForeground(ChatColors.accent());
        plans.setAlignmentX(LEFT_ALIGNMENT);
        list.add(plans);
        list.add(Box.createVerticalStrut(2));
        JComponent note = AssistantPanel.wrapped("The assistant follows these for the usual jobs.", true);
        list.add(note);
        list.add(Box.createVerticalStrut(6));
        for (Prompts.Prompt p : Prompts.all()) {
            JPanel r = card(null);
            r.setName("plan:" + p.name());
            JLabel t = new JLabel(p.title());
            t.setFont(t.getFont().deriveFont(Font.BOLD));
            r.add(t);
            r.add(AssistantPanel.wrapped(p.description(), true));
            list.add(r);
            list.add(Box.createVerticalStrut(6));
        }
        list.revalidate();
        list.repaint();
        if (sp != null) {
            javax.swing.SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(at));
        }
    }

    int scrolledForTest() {
        return sp.getVerticalScrollBar().getValue();
    }

    private JPanel skill(Skill s) {
        ChatColors.Look look = ChatColors.tool(s.tools().get(0));
        JPanel card = card(look.color());
        card.setName("skill:" + s.title());
        JLabel head = new JLabel(s.title(), Icons.of(look.icon(), look.color(), 14), JLabel.LEFT);
        head.setFont(head.getFont().deriveFont(Font.BOLD));
        boolean expanded = open.contains(s.title());
        JLabel arrow = new JLabel(Icons.of(expanded ? "chevron-up" : "chevron-down", Theme.dimmedForeground(), 12));
        JPanel top = AssistantPanel.row();
        top.setOpaque(false);
        top.add(head, BorderLayout.CENTER);
        top.add(arrow, BorderLayout.EAST);
        top.setAlignmentX(LEFT_ALIGNMENT);
        top.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        top.setToolTipText(expanded ? "Hide its tools" : "Show its tools");
        MouseAdapter toggle = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle(s.title());
            }
        };
        for (JComponent c : new JComponent[] {top, head, arrow}) {
            c.addMouseListener(toggle);
        }
        card.add(top);
        card.add(Box.createVerticalStrut(2));
        card.add(AssistantPanel.wrapped(s.what(), true));
        if (expanded) {
            card.add(Box.createVerticalStrut(4));
            for (String n : s.tools()) {
                Tool t = tools.get(n);
                JComponent line = AssistantPanel.wrapped("• " + t.title() + " (" + n + "): " + firstSentence(t.description()), false);
                line.setName("tool:" + n);
                line.setFont(line.getFont().deriveFont(line.getFont().getSize2D() - 1f));
                card.add(line);
                card.add(Box.createVerticalStrut(2));
            }
        }
        if (s.example() != null) {
            card.add(Box.createVerticalStrut(4));
            JButton b = new JButton(s.example(), Icons.of("message-square", look.color(), 12));
            b.setName("try");
            b.setFocusable(false); // a click doesn't scroll the panel to it
            b.putClientProperty("JButton.buttonType", "borderless");
            b.setHorizontalAlignment(JButton.LEFT);
            b.setAlignmentX(LEFT_ALIGNMENT);
            b.setForeground(look.color());
            b.setToolTipText("Put this question in the box (nothing is sent until you ask)");
            b.addActionListener(e -> ask.accept(s.example()));
            card.add(b);
        }
        return card;
    }

    private void toggle(String title) {
        if (!open.remove(title)) {
            open.add(title);
        }
        fill();
    }

    /** A rounded card, with a stripe of its color at the left. */
    private static JPanel card(Color stripe) {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }

            @Override
            protected void paintComponent(java.awt.Graphics g0) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.background());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                if (stripe != null) {
                    g.setColor(stripe);
                    g.fillRoundRect(0, 4, 3, getHeight() - 8, 3, 3);
                }
                g.dispose();
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 8));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    static String firstSentence(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        int dot = t.indexOf(". ");
        return dot > 0 ? t.substring(0, dot + 1) : t;
    }

    // ---------------------------------------------------------------- for tests

    void toggleForTest(String title) {
        toggle(title);
    }

    JPanel listForTest() {
        return list;
    }
}
