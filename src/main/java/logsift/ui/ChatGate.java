package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import logsift.agent.Tool;
import logsift.agent.Workspace;

/**
 * The question above the box when the assistant wants something the person didn't ask for: a file
 * or folder they didn't open or name (reading those is where answers get expensive), a thread
 * dump… They answer **Allow**, **Allow for This Chat** or **Deny**; the model reads a no as a
 * tool's answer, and changes course.
 */
final class ChatGate extends JPanel {
    enum Answer { DENY, ONCE, CHAT }

    private final JLabel icon = new JLabel();
    private final javax.swing.JTextArea text = AssistantPanel.wrapped("", false);
    private final javax.swing.JTextArea detail = AssistantPanel.wrapped("", true);
    private final JButton once = new JButton("Allow");
    private final JButton chat = new JButton("Allow for This Chat");
    private final JButton deny = new JButton("Deny");
    private final Supplier<String> chatId;
    private final BooleanSupplier asking;
    private final Consumer<String> note;
    /** Who asks: the assistant answering now (GitHub Copilot, Claude Code). */
    private final Supplier<String> who;
    /** Per chat: what was allowed for all of it (a folder covers its files). */
    private final Map<String, Set<String>> allowed = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Answer> pending;

    /**
     * @param chatId the chat asked in now
     * @param asking whether to ask (Settings → Assistant), else everything is allowed
     * @param note   writes what was decided in the chat
     */
    ChatGate(Supplier<String> chatId, BooleanSupplier asking, Consumer<String> note) {
        this(chatId, asking, note, () -> "The assistant");
    }

    /** @param who the assistant answering now, by name */
    ChatGate(Supplier<String> chatId, BooleanSupplier asking, Consumer<String> note, Supplier<String> who) {
        super(new BorderLayout(8, 6));
        this.who = who;
        this.chatId = chatId;
        this.asking = asking;
        this.note = note;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 10));
        icon.setIcon(Icons.of("circle-alert", Theme.pick(new Color(0xD97706), new Color(0xFBBF24)), 16));
        icon.setVerticalAlignment(JLabel.TOP);
        add(icon, BorderLayout.WEST);
        JPanel words = new JPanel();
        words.setLayout(new javax.swing.BoxLayout(words, javax.swing.BoxLayout.Y_AXIS));
        words.setOpaque(false);
        text.setFont(text.getFont().deriveFont(java.awt.Font.BOLD));
        words.add(text);
        words.add(detail);
        add(words, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        b.setOpaque(false);
        once.setName("allowOnce");
        chat.setName("allowChat");
        deny.setName("deny");
        once.putClientProperty("JButton.buttonType", "default");
        b.add(deny);
        b.add(chat);
        b.add(once);
        add(b, BorderLayout.SOUTH);
        once.addActionListener(e -> answer(Answer.ONCE));
        chat.addActionListener(e -> answer(Answer.CHAT));
        deny.addActionListener(e -> answer(Answer.DENY));
        setVisible(false);
    }

    /** The gate for the workspace's tools: files and folders. */
    Workspace.Gate files() {
        return new Workspace.Gate() {
            @Override
            public void allow(String what, Path where) throws Tool.Failure, InterruptedException {
                if (asking.getAsBoolean()) {
                    ask(what, where.toString(), "file:" + key(where),
                            who.get() + " wants to " + what + ", which you didn't open or name.", shortPath(where),
                            "Work with the logs that are open or that the person named; say what else you would need and why.");
                }
            }

            @Override
            public void allowUrl(String what, String url) throws Tool.Failure, InterruptedException {
                if (asking.getAsBoolean()) {
                    String host = java.net.URI.create(url).getHost();
                    ask(what, url, "url:" + host, who.get() + " wants to " + what + ", which you didn't open or name.", url,
                            "Work with the logs that are open or that the person named.");
                }
            }

            @Override
            public void approve(String what, String key, String detail) throws Tool.Failure, InterruptedException {
                // a running program: always asked, whatever the setting for files
                ask(what, what, key, who.get() + " wants to " + what + ".", detail,
                        "Don't ask again for it in this answer; say what it would have shown, or work from the logs.");
            }
        };
    }

    /** A long path as its start and its last parts: "/Users/…/build/visual/elsewhere/billing.log". */
    static String shortPath(Path p) {
        String s = p.toString();
        if (s.length() <= 60 || p.getNameCount() < 5) {
            return s;
        }
        Path root = p.getRoot();
        String head = (root == null ? "" : root.toString()) + p.getName(0);
        String tail = p.subpath(p.getNameCount() - 3, p.getNameCount()).toString();
        return head + java.io.File.separator + "…" + java.io.File.separator + tail;
    }

    /** A folder covers its files: "Allow for This Chat" on a file allows its folder. */
    private static String key(Path where) {
        Path abs = where.toAbsolutePath().normalize();
        Path dir = Files.isDirectory(abs) || abs.getParent() == null ? abs : abs.getParent();
        return dir.toString();
    }

    /**
     * Asks (from a tool's thread) and waits for the answer. Throws {@link Tool.Failure} with a no.
     *
     * @param what  what it wants, in a few words
     * @param where where, in words
     * @param key   what "for this chat" allows: anything whose key starts with it
     * @param question the question, in a line
     * @param shown    under it, dimmed: where, or what it costs
     * @param instead what the model should do instead, on a no
     */
    void ask(String what, String where, String key, String question, String shown, String instead)
            throws Tool.Failure, InterruptedException {
        String id = chatId.get();
        Set<String> ok = id == null ? Set.of() : allowed.getOrDefault(id, Set.of());
        for (String k : ok) {
            if (key.equals(k) || key.startsWith(k + "/") || key.startsWith(k + "\\")) {
                return; // the same, or in a folder allowed
            }
        }
        CompletableFuture<Answer> f = new CompletableFuture<>();
        synchronized (this) {
            CompletableFuture<Answer> before = pending;
            if (before != null) {
                before.complete(Answer.DENY); // one question at a time: the newest
            }
            pending = f;
        }
        SwingUtilities.invokeLater(() -> show(question, shown));
        Answer a;
        try {
            a = f.get();
        } catch (ExecutionException e) {
            a = Answer.DENY;
        } finally {
            SwingUtilities.invokeLater(() -> {
                if (pending == f || pending == null || pending.isDone()) {
                    setVisible(false);
                }
            });
        }
        switch (a) {
            case DENY -> {
                note.accept("You said no: " + what + ".");
                throw new Tool.Failure("The person said no to: " + what + " (" + where + "). " + instead);
            }
            case ONCE -> note.accept("You allowed: " + what + ".");
            case CHAT -> {
                if (id != null) {
                    allowed.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(key);
                }
                note.accept("You allowed for this chat: " + what + ".");
            }
        }
    }

    private void show(String question, String shown) {
        text.setText(question);
        detail.setText(shown == null ? "" : shown);
        detail.setVisible(shown != null && !shown.isEmpty());
        setVisible(true);
        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
        }
        once.requestFocusInWindow();
    }

    private void answer(Answer a) {
        CompletableFuture<Answer> f;
        synchronized (this) {
            f = pending;
            pending = null;
        }
        setVisible(false);
        if (f != null) {
            f.complete(a);
        }
    }

    /** The turn stopped, or the chat changed: a question still open is a no. */
    void cancel() {
        answer(Answer.DENY);
    }

    boolean isAsking() {
        return isVisible() && pending != null;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.pick(new Color(0xFFF7E6), new Color(0x3A3222)));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
        g.setColor(Theme.pick(new Color(0xF5C26B), new Color(0x8A6A2A)));
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
        g.dispose();
        super.paintComponent(g0);
    }

    // ---------------------------------------------------------------- for tests

    String textForTest() {
        return text.getText() + "\n" + detail.getText();
    }

    void answerForTest(Answer a) {
        answer(a);
    }
}
