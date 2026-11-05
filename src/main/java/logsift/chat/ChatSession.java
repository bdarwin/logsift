package logsift.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One chat as it goes: what the person asks goes to the assistant, and everything that comes
 * back (the answer as it streams, each tool it calls and Logsift's answer, what it cost) is
 * stored as it happens and told to the window. Engine-neutral; the window shows it.
 */
public final class ChatSession implements AutoCloseable {

    /** What the window hears: entries added or changed, and when the assistant is busy. */
    public interface View {
        /** An entry was added ({@code added}) or changed (the answer streamed on, a tool answered). */
        void changed(ChatMessage m, boolean added);

        /** The assistant started or finished answering. */
        void busy(boolean busy);

        /** The chat got a name. */
        default void titled(String title) {
        }
    }

    /** How often a streaming answer is written to the store (it's always written at the end). */
    static final long SAVE_MILLIS = 750;

    private final ChatStore store;
    private final ChatEngine engine;
    private final List<ChatEngine.ToolSpec> tools;
    private final String instructions;
    private final View view;
    private ChatInfo chat;
    private ChatEngine.Conversation conversation;
    private String model;
    private String effort;

    // the turn under way
    private ChatMessage answer;
    private String answerMessage;
    private long turnStarted;
    private long lastSaved;
    private boolean busy;
    private boolean titled;
    private final Map<String, ChatMessage> toolCalls = new ConcurrentHashMap<>();
    private final Map<String, String> toolResults = new ConcurrentHashMap<>();
    private final List<ChatEvent.Usage> pendingUsage = new ArrayList<>();

    /**
     * @param chat a chat from the store, new or to go on with
     */
    public ChatSession(ChatStore store, ChatEngine engine, ChatInfo chat, List<ChatEngine.ToolSpec> tools,
                       String instructions, String model, String effort, View view) {
        this.store = store;
        this.engine = engine;
        this.chat = chat;
        this.instructions = instructions;
        this.model = model;
        this.effort = effort;
        this.view = view;
        this.titled = chat.messages() > 0;
        // each tool's answer is kept, to store with the call
        List<ChatEngine.ToolSpec> wrapped = new ArrayList<>();
        for (ChatEngine.ToolSpec t : tools) {
            wrapped.add(new ChatEngine.ToolSpec(t.name(), t.description(), t.schema(), (id, args) -> {
                ChatEngine.ToolHandler.Result r = t.handler().call(id, args);
                if (id != null) {
                    toolResults.put(id, r.text() == null ? "" : r.text());
                }
                return r;
            }, t.often()));
        }
        this.tools = List.copyOf(wrapped);
    }

    public ChatInfo chat() {
        return store.chat(chat.id()) instanceof ChatInfo c ? (chat = c) : chat;
    }

    public synchronized boolean isBusy() {
        return busy;
    }

    public synchronized String model() {
        return model;
    }

    /** Another model (and effort) from the next question on. */
    public void setModel(String m, String e) throws Exception {
        ChatEngine.Conversation c;
        synchronized (this) {
            if (m.equals(model) && java.util.Objects.equals(e, effort)) {
                return;
            }
            model = m;
            effort = e;
            c = conversation;
        }
        store.setModel(chat.id(), m);
        if (c != null) {
            c.setModel(m, e);
        }
    }

    /**
     * Asks. {@code context} (what the person is looking at: the log, the lines chosen) goes with
     * the question to the model but is stored on its own, so the chat shows the question as asked.
     */
    public void ask(String question, String context) throws Exception {
        ask(question, context, null);
    }

    /**
     * Asks, with the window's logs by handle ({@code log:1} → its path), kept with the question so
     * its answer's links open those files later on.
     */
    public void ask(String question, String context, java.util.Map<String, String> logs) throws Exception {
        ChatEngine.Conversation c;
        synchronized (this) {
            if (busy) {
                throw new IllegalStateException("Still answering");
            }
            busy = true;
            answer = null;
            answerMessage = null;
            pendingUsage.clear();
            turnStarted = System.currentTimeMillis();
        }
        view.busy(true);
        ChatMessage q = store.append(ChatMessage.user(chat.id(), question, ChatHandles.record(logs)));
        view.changed(q, true);
        if (!titled) {
            titled = true;
            String t = title(question);
            store.rename(chat.id(), t);
            view.titled(t);
        }
        try {
            c = conversation();
            c.send(context == null || context.isBlank() ? question : context + "\n\n" + question);
        } catch (Exception e) {
            fail(CopilotEngine.why(e));
            finish(false);
            throw e;
        }
    }

    /** A first name for a chat: its first question, short. */
    public static String title(String question) {
        String s = question.strip().replaceAll("\\s+", " ");
        return s.length() <= 60 ? s : s.substring(0, 57).replaceAll("\\s\\S*$", "") + "…";
    }

    private ChatEngine.Conversation conversation() throws Exception {
        synchronized (this) {
            if (conversation != null) {
                return conversation;
            }
        }
        ChatEngine.Conversation c;
        String resume = chat.session();
        try {
            c = engine.open(new ChatEngine.Setup(model, effort, instructions, tools, resume), this::on);
        } catch (Exception e) {
            if (resume == null) {
                throw e;
            }
            // the assistant no longer has it: a new conversation, told what was said
            c = engine.open(new ChatEngine.Setup(model, effort, instructions + recap(), tools, null), this::on);
            ChatMessage n = store.append(ChatMessage.note(chat.id(),
                    engine.name() + " no longer had this chat, so it was started again with what was said before."));
            view.changed(n, true);
        }
        synchronized (this) {
            conversation = c;
        }
        keepSession(c);
        return c;
    }

    /** The assistant's id for the chat, saved to go on with it later (Claude Code's comes with its first answer). */
    private String keptSession;

    private void keepSession(ChatEngine.Conversation c) {
        String id = c == null ? null : c.id();
        synchronized (this) {
            if (id == null || id.equals(keptSession)) {
                return;
            }
            keptSession = id;
        }
        store.setSession(chat.id(), id);
    }

    /** What was said in this chat, for an assistant that has forgotten it. */
    private String recap() {
        StringBuilder b = new StringBuilder("\n\nEarlier in this chat (you said and found this before):\n");
        for (ChatMessage m : store.last(chat.id(), 40)) {
            switch (m.kind()) {
                case USER -> b.append("\nThe person: ").append(cut(m.text(), 1500));
                case ASSISTANT -> b.append("\nYou: ").append(cut(m.text(), 3000));
                default -> {
                }
            }
        }
        return b.toString();
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** Stops the answer under way. */
    public void stop() {
        ChatEngine.Conversation c;
        synchronized (this) {
            c = conversation;
        }
        if (c != null) {
            c.abort();
        }
    }

    /** An event of the conversation. */
    void on(ChatEvent e) {
        switch (e) {
            case ChatEvent.Text t -> text(t);
            case ChatEvent.Thinking t -> {
                // not stored: shown while it thinks, then the answer
            }
            case ChatEvent.ToolStarted t -> {
                ChatMessage m = store.append(ChatMessage.tool(chat.id(), t.tool(), t.args()));
                toolCalls.put(t.callId(), m);
                synchronized (this) {
                    answer = null; // text after a tool is a new answer
                    answerMessage = null;
                }
                view.changed(m, true);
            }
            case ChatEvent.ToolFinished t -> {
                ChatMessage m = toolCalls.remove(t.callId());
                if (m != null) {
                    String r = toolResults.remove(t.callId());
                    ChatMessage done = m.withResult(r == null ? "" : r, t.ok(), System.currentTimeMillis() - m.time());
                    store.replace(done);
                    view.changed(done, false);
                }
            }
            case ChatEvent.Usage u -> usage(u);
            case ChatEvent.Title t -> {
                store.rename(chat.id(), t.title());
                view.titled(t.title());
            }
            case ChatEvent.Failed f -> fail(f.message());
            case ChatEvent.Idle i -> {
                if (i.aborted()) {
                    ChatMessage n = store.append(ChatMessage.note(chat.id(), "Stopped."));
                    view.changed(n, true);
                }
                finish(true);
            }
        }
    }

    private void text(ChatEvent.Text t) {
        ChatMessage m;
        boolean added = false;
        boolean save;
        synchronized (this) {
            if (answer == null || (t.message() != null && answerMessage != null && !t.message().equals(answerMessage))) {
                answer = store.append(ChatMessage.assistant(chat.id(), model));
                answerMessage = t.message();
                added = true;
            }
            if (answerMessage == null) {
                answerMessage = t.message();
            }
            answer = answer.withText(answer.text() + t.delta());
            m = answer;
            long now = System.currentTimeMillis();
            save = now - lastSaved >= SAVE_MILLIS;
            if (save) {
                lastSaved = now;
            }
        }
        if (save) {
            store.replace(m);
        }
        view.changed(m, added);
    }

    private void usage(ChatEvent.Usage u) {
        synchronized (this) {
            pendingUsage.add(u);
        }
    }

    private void fail(String message) {
        ChatMessage m = store.append(ChatMessage.error(chat.id(), message));
        view.changed(m, true);
    }

    /** The turn is over: the answer stored whole, with what the turn cost. */
    private void finish(boolean ok) {
        ChatEngine.Conversation c;
        ChatMessage m;
        List<ChatEvent.Usage> used;
        synchronized (this) {
            if (!busy) {
                return;
            }
            busy = false;
            c = conversation;
            m = answer;
            used = new ArrayList<>(pendingUsage);
            pendingUsage.clear();
            answer = null;
            answerMessage = null;
        }
        if (m == null && !used.isEmpty()) {
            // no words, but the model was called (only tools, or stopped): the cost is still shown
            m = store.append(ChatMessage.assistant(chat.id(), model));
            view.changed(m, true);
        }
        if (m != null) {
            for (ChatEvent.Usage u : used) {
                m = m.plusUsage(u.model(), u.tokensIn(), u.tokensOut(), u.nanoAiu());
            }
            m = m.withMillis(System.currentTimeMillis() - turnStarted);
            store.replace(m);
            view.changed(m, false);
        }
        keepSession(c);
        view.busy(false);
    }

    @Override
    public void close() {
        ChatEngine.Conversation c;
        synchronized (this) {
            c = conversation;
            conversation = null;
        }
        if (c != null) {
            c.close();
        }
    }
}
