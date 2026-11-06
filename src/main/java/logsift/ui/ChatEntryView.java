package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import logsift.chat.ChatMessage;

/**
 * One entry of a chat: a question, an answer (Markdown, its line links clickable, with the
 * model, what it cost and how long it took), a tool the assistant called (Logsift's answer
 * one click away, and a way to see the same in the window), an error or a note.
 */
final class ChatEntryView extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ChatMessage m;
    private final ChatTranscript.Actions actions;
    private final JLabel header = new JLabel();
    private final JEditorPane body = new JEditorPane();
    private JPanel toolDetails;
    private boolean expanded;
    private boolean marked;
    /** An answer arriving now (else one read from the store: never "answering…"). */
    private boolean live;
    private final Timer render;
    private String rendered;

    ChatEntryView(ChatMessage m, ChatTranscript.Actions actions) {
        super(new BorderLayout(0, 3));
        this.m = m;
        this.actions = actions;
        setOpaque(false);
        header.setForeground(Theme.dimmedForeground());
        header.setFont(header.getFont().deriveFont(header.getFont().getSize2D() - 1f));
        body.setContentType("text/html");
        body.setEditable(false);
        body.setOpaque(false);
        body.setBorder(null);
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        body.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                actions.link(m, e.getDescription());
            }
        });
        render = new Timer(80, e -> renderNow());
        render.setRepeats(false);
        build();
    }

    ChatMessage message() {
        return m;
    }

    /** Sized again (the assistant's text size): the answer is laid out in the new size. */
    @Override
    public void setFont(Font f) {
        boolean changed = f != null && !f.equals(getFont());
        super.setFont(f);
        if (changed && render != null && rendered != null) {
            rendered = null;
            renderNow();
        }
    }

    /** The entry as it is now (more of an answer, a tool's result). */
    void update(ChatMessage now) {
        boolean kindChanged = now.kind() != m.kind();
        m = now;
        if (kindChanged || m.kind() == ChatMessage.Kind.TOOL) {
            build();
        } else {
            header.setText(headerText());
            if (!render.isRunning()) {
                render.start(); // words stream in faster than they need showing
            }
        }
        revalidate();
        repaint();
    }

    void setLive(boolean b) {
        live = b;
        header.setText(headerText());
    }

    /** Marked: the entry a search found. */
    void setMarked(boolean b) {
        marked = b;
        repaint();
    }

    private void build() {
        removeAll();
        toolDetails = null;
        switch (m.kind()) {
            case USER -> {
                setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                header.setText(headerText());
                add(header, BorderLayout.NORTH);
                add(body, BorderLayout.CENTER);
                renderNow();
            }
            case ASSISTANT -> {
                setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
                header.setText(headerText());
                header.setIcon(Icons.of("sparkles", ChatColors.accent(), 12));
                add(header, BorderLayout.NORTH);
                add(body, BorderLayout.CENTER);
                renderNow();
            }
            case TOOL -> buildTool();
            case ERROR -> {
                setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                header.setText("Something went wrong");
                header.setIcon(Icons.of("circle-alert", Theme.errorMark(), 12));
                add(header, BorderLayout.NORTH);
                add(body, BorderLayout.CENTER);
                renderNow();
            }
            case NOTE -> {
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                JLabel l = new JLabel(m.text(), JLabel.CENTER);
                l.setForeground(Theme.dimmedForeground());
                l.setFont(l.getFont().deriveFont(Font.ITALIC, l.getFont().getSize2D() - 1f));
                add(l, BorderLayout.CENTER);
            }
        }
    }

    private void renderNow() {
        String text = m.text() == null ? "" : m.text();
        String md = switch (m.kind()) {
            case USER, ERROR -> plain(text);
            default -> text.isEmpty() ? "*…*" : text;
        };
        if (md.equals(rendered)) {
            return;
        }
        rendered = md;
        body.setText(ChatMarkdown.page(md, getFont()));
        revalidate();
        repaint();
        if (getParent() instanceof ChatTranscript t && t.atEnd()) {
            javax.swing.SwingUtilities.invokeLater(t::scrollToEnd);
        }
    }

    /** Text the person typed, shown as typed: Markdown's marks are only letters here. */
    static String plain(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            if (b.length() > 0) {
                b.append("  \n"); // a line break
            }
            b.append(line.replaceAll("([\\\\`*_{}\\[\\]()#+\\-.!|<>~])", "\\\\$1"));
        }
        return b.toString();
    }

    String headerText() {
        String when = TIME.format(Instant.ofEpochMilli(m.time()).atZone(ZoneId.systemDefault()));
        return switch (m.kind()) {
            case USER -> "You · " + when;
            case ASSISTANT -> {
                StringBuilder b = new StringBuilder(m.model() == null ? "Assistant" : modelName(m.model()));
                if (m.millis() == 0) {
                    if (live) {
                        b.append(" · answering…");
                    }
                } else {
                    if (m.nanoAiu() > 0) {
                        b.append(" · ").append(logsift.chat.ChatCost.format(m.model(), m.nanoAiu()));
                    }
                    if (m.tokensIn() + m.tokensOut() > 0) {
                        b.append(" · ").append(tokens(m.tokensIn() + m.tokensOut())).append(" tokens");
                    }
                    b.append(" · ").append(seconds(m.millis()));
                }
                yield b.toString();
            }
            default -> when;
        };
    }

    /** "claude-haiku-5.5" as people write it, when the window has no better name. */
    static java.util.function.Function<String, String> modelNames = id -> id;

    private static String modelName(String id) {
        String n = modelNames.apply(id);
        return n == null || n.equals(id) ? logsift.chat.ChatCost.name(id) : n;
    }

    static String credits(double c) {
        if (c < 0.01) {
            return "<0.01 credits";
        }
        return (c < 10 ? String.format("%.2f", c) : String.format("%,.0f", c)) + (c == 1 ? " credit" : " credits");
    }

    static String tokens(long n) {
        if (n < 1000) {
            return Long.toString(n);
        }
        if (n < 100_000) {
            return String.format("%.1fk", n / 1000.0);
        }
        return String.format("%,dk", n / 1000);
    }

    static String seconds(long ms) {
        return ms < 1000 ? ms + " ms" : ms < 60_000 ? String.format("%.1f s", ms / 1000.0)
                : String.format("%d min %d s", ms / 60_000, (ms / 1000) % 60);
    }

    // ---------------------------------------------------------------- tools

    private void buildTool() {
        setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        JLabel what = new JLabel(toolSummary());
        ChatColors.Look look = ChatColors.tool(m.tool());
        what.setIcon(Icons.of(look.icon(), look.color(), 13));
        what.setForeground(Theme.dimmedForeground());
        what.setFont(what.getFont().deriveFont(what.getFont().getSize2D() - 1f));
        row.add(what, BorderLayout.CENTER);
        JLabel status = new JLabel(toolStatus());
        status.setFont(what.getFont());
        status.setForeground(Boolean.FALSE.equals(m.ok()) ? Theme.errorMark()
                : Boolean.TRUE.equals(m.ok()) ? ChatColors.ok() : Theme.dimmedForeground());
        status.setHorizontalTextPosition(JLabel.LEADING);
        status.setIconTextGap(4);
        row.add(status, BorderLayout.EAST);
        toolArrow = status;
        arrow();
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                expanded = !expanded;
                buildTool2();
            }
        };
        // on the labels too: a label with a tooltip takes the clicks itself, and the row never got them
        for (javax.swing.JComponent c : new javax.swing.JComponent[] {row, what, status}) {
            c.addMouseListener(click);
            c.setToolTipText(TOOL_TIP);
        }
        add(row, BorderLayout.NORTH);
        buildTool2();
        if (Boolean.TRUE.equals(m.ok()) && !inlineAsked) {
            inlineAsked = true; // made once: the row is built again as it changes
            inline = actions.inline(m);
        }
        if (inline != null) {
            add(inline, BorderLayout.SOUTH);
        }
    }

    /** What the window shows under this tool's row (a heap chart under analyze_gc), or null. */
    private javax.swing.JComponent inline;
    private boolean inlineAsked;

    javax.swing.JComponent inlineForTest() {
        return inline;
    }

    static final String TOOL_TIP = "Show what Logsift answered";
    private JLabel toolArrow;

    private void arrow() {
        if (toolArrow != null) {
            toolArrow.setIcon(Icons.of(expanded ? "chevron-up" : "chevron-down", Theme.dimmedForeground(), 12));
        }
    }

    /** The details below the row, when open. */
    private void buildTool2() {
        if (toolDetails != null) {
            remove(toolDetails);
            toolDetails = null;
        }
        arrow();
        if (expanded) {
            toolDetails = new JPanel(new BorderLayout(0, 4));
            toolDetails.setOpaque(false);
            String said = m.text() == null || m.text().isEmpty()
                    ? (m.ok() == null ? "Still working…" : "Logsift's answer wasn't kept.") : firstLines(m.text(), 60);
            javax.swing.JTextArea t = new javax.swing.JTextArea(said);
            t.setEditable(false);
            t.setLineWrap(true);
            t.setWrapStyleWord(false);
            t.setFont(Theme.monoFont(getFont().getSize2D() - 1f));
            t.setBackground(Theme.pick(new Color(0xF2F3F5), new Color(0x2B2D30)));
            t.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            toolDetails.add(t, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            buttons.setOpaque(false);
            for (String a : toolActions()) {
                JButton b = new JButton(a);
                b.putClientProperty("JButton.buttonType", "toolBarButton");
                b.addActionListener(e -> actions.action(m, a));
                buttons.add(b);
            }
            toolDetails.add(buttons, BorderLayout.SOUTH);
            add(toolDetails, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
        }
    }

    private static String firstLines(String s, int n) {
        int at = -1;
        for (int i = 0; i < n; i++) {
            at = s.indexOf('\n', at + 1);
            if (at < 0) {
                return s;
            }
        }
        return s.substring(0, at) + "\n… (" + (s.split("\n", -1).length - n) + " more lines)";
    }

    /** What the window can do with a tool's answer. */
    java.util.List<String> toolActions() {
        java.util.List<String> l = new java.util.ArrayList<>();
        Map<String, Object> a = args();
        if ("read_lines".equals(m.tool()) && a.get("from") != null) {
            l.add(SHOW_LINES);
        } else if ("search".equals(m.tool()) && a.get("query") != null) {
            l.add(FIND_IN_LOG);
        } else if ("query".equals(m.tool()) && a.get("sql") != null && !a.containsKey("tables")) {
            l.add(OPEN_QUERY);
        } else if ("analyze_gc".equals(m.tool()) && a.get("log") != null) {
            l.add(SHOW_GC);
        }
        l.add(COPY);
        return l;
    }

    static final String SHOW_LINES = "Show these lines";
    static final String FIND_IN_LOG = "Find in the log";
    static final String OPEN_QUERY = "Open in Query";
    static final String SHOW_GC = "Show GC Charts";
    static final String COPY = "Copy";

    Map<String, Object> args() {
        return args(m);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> args(ChatMessage m) {
        if (m.args() == null) {
            return Map.of();
        }
        Object v = logsift.core.Json.parseValue(m.args(), null);
        return v instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The call in a few words: "search “timed out” in log:1". */
    String toolSummary() {
        Map<String, Object> a = args();
        String log = a.get("log") == null ? "" : " in " + a.get("log");
        String s = switch (m.tool() == null ? "" : m.tool()) {
            case "read_lines" -> "read lines " + num(a.get("from"))
                    + (a.get("count") != null ? "–" + (toLong(a.get("from")) + toLong(a.get("count")) - 1) : "") + log;
            case "search" -> "search “" + cut(String.valueOf(a.get("query")), 40) + "”" + log;
            case "query" -> "SQL: " + cut(String.valueOf(a.get("sql")).replaceAll("\\s+", " "), 60) + log;
            case "insights" -> "insights" + log;
            case "exceptions" -> "exceptions" + log;
            case "list_logs" -> "list the logs";
            case "open_log" -> "open " + cut(String.valueOf(a.getOrDefault("path", a.get("log"))), 50);
            default -> m.tool() + (log.isEmpty() ? "" : log);
        };
        return s;
    }

    private String toolStatus() {
        if (m.ok() == null) {
            return "…";
        }
        if (!m.ok()) {
            return "✗ " + seconds(m.millis());
        }
        return "✓ " + seconds(m.millis());
    }

    private static String num(Object o) {
        return o == null ? "?" : String.format("%,d", toLong(o));
    }

    static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    // ---------------------------------------------------------------- size and paint

    /** How tall it is at width {@code w}. */
    int heightFor(int w) {
        Insets in = getInsets();
        int inner = Math.max(40, w - in.left - in.right);
        int h = in.top + in.bottom;
        for (java.awt.Component c : getComponents()) {
            if (c == body) {
                body.setSize(inner, Short.MAX_VALUE);
                h += body.getPreferredSize().height;
            } else if (c instanceof javax.swing.JTextArea || c == toolDetails) {
                c.setSize(inner, Short.MAX_VALUE);
                if (c instanceof JPanel p) {
                    for (java.awt.Component k : p.getComponents()) {
                        k.setSize(inner, Short.MAX_VALUE);
                    }
                }
                h += c.getPreferredSize().height;
            } else {
                h += c.getPreferredSize().height;
            }
            h += 3;
        }
        return h;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Color fill = switch (m.kind()) {
            case USER -> Theme.pick(new Color(0xEEF3FB), new Color(0x2A3240));
            case ERROR -> Theme.pick(new Color(0xFDECEC), new Color(0x3F2A2A));
            case TOOL -> Theme.pick(new Color(0xF6F7F8), new Color(0x26282B));
            default -> null;
        };
        if (marked) {
            fill = Theme.findMark();
        }
        if (fill != null) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            if (m.kind() == ChatMessage.Kind.TOOL && !marked) {
                g.setColor(ChatColors.tool(m.tool()).color()); // the tool's color down its edge
                g.fillRoundRect(0, 3, 3, getHeight() - 6, 3, 3);
            }
            g.dispose();
        }
        super.paintComponent(g0);
    }

    // ---------------------------------------------------------------- for tests

    String bodyTextForTest() {
        try {
            return body.getDocument().getText(0, body.getDocument().getLength());
        } catch (javax.swing.text.BadLocationException e) {
            return "";
        }
    }

    String htmlForTest() {
        return body.getText();
    }

    void renderForTest() {
        render.stop();
        renderNow();
    }

    boolean expandedForTest() {
        return expanded;
    }

    void toggleForTest() {
        expanded = !expanded;
        buildTool2();
    }

    /** Clicks the tool's summary, as the person does. */
    void clickSummaryForTest() {
        JPanel row = (JPanel) ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.NORTH);
        java.awt.Component what = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        for (java.awt.event.MouseListener l : what.getMouseListeners()) {
            l.mouseClicked(new MouseEvent(what, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, 4, 1, false));
        }
    }

    String detailsForTest() {
        if (toolDetails == null) {
            return null;
        }
        return ((javax.swing.JTextArea) ((BorderLayout) toolDetails.getLayout()).getLayoutComponent(BorderLayout.CENTER)).getText();
    }
}
