package logsift.chat;

import java.util.List;
import java.util.Map;

/**
 * An assistant the window can chat with (GitHub Copilot first): its models, what's left of the
 * person's allowance, and conversations in which it calls Logsift's tools. Kept general so that
 * other assistants can be added the same way.
 */
public interface ChatEngine extends AutoCloseable {

    /** Its name for people ("GitHub Copilot"). */
    String name();

    /** Connects (slow the first time: it starts the assistant's program). Throws why it can't. */
    void start() throws Exception;

    /** Who is signed in ("bdarwin"), or null if no one. */
    String user() throws Exception;

    /** The models the person may choose from. */
    List<Model> models() throws Exception;

    /** What's left of the person's allowance, or null when it can't be told. */
    Quota quota() throws Exception;

    /**
     * Starts a conversation, or goes on with one ({@link Setup#resume}). Events come on the
     * engine's threads.
     */
    Conversation open(Setup setup, Listener listener) throws Exception;

    @Override
    void close();

    /**
     * A model.
     *
     * @param id          what the engine calls it
     * @param name        its name for people
     * @param inputPrice  AI credits a million tokens read cost (0: unknown)
     * @param outputPrice AI credits a million tokens written cost (0: unknown)
     * @param context     the most tokens it can read at once (0: unknown)
     * @param efforts     how hard it may think (low, medium, high…), empty if it can't be told
     * @param effort      its usual effort, or null
     * @param enabled     whether the person may use it now (an organization may have it off)
     */
    record Model(String id, String name, double inputPrice, double outputPrice, long context, List<String> efforts,
                 String effort, boolean enabled) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * What's left of the person's allowance of premium requests.
     *
     * @param used      used this period
     * @param allowed   allowed this period (0 with {@code unlimited})
     * @param unlimited no limit
     * @param leftPct   % left
     * @param resets    when it starts again (ISO text), or null
     */
    record Quota(long used, long allowed, boolean unlimited, double leftPct, String resets) {}

    /**
     * What a conversation starts with.
     *
     * @param model        the model's id
     * @param effort       how hard it thinks, or null for the model's own
     * @param instructions what it's told before anything (who it is, how to answer)
     * @param tools        what it may ask Logsift
     * @param resume       the engine's id of a conversation to go on with, or null
     */
    record Setup(String model, String effort, String instructions, List<ToolSpec> tools, String resume) {}

    /**
     * A tool the model may call.
     *
     * @param name        its name
     * @param description what it does, for the model
     * @param schema      its arguments, as a JSON Schema object
     * @param handler     runs it
     * @param often       one most questions need: always offered whole. Others the model finds when it
     *                    needs them (their descriptions aren't sent with every question, which costs less)
     */
    record ToolSpec(String name, String description, Map<String, Object> schema, ToolHandler handler, boolean often) {
        public ToolSpec(String name, String description, Map<String, Object> schema, ToolHandler handler) {
            this(name, description, schema, handler, true);
        }
    }

    /** Runs a tool: the answer for the model; an exception is an error the model reads. */
    interface ToolHandler {
        Result call(String callId, Map<String, Object> args) throws Exception;

        /**
         * A tool's answer.
         *
         * @param text   what the model reads
         * @param failed whether it is an error
         */
        record Result(String text, boolean failed) {}
    }

    /** One conversation. */
    interface Conversation extends AutoCloseable {
        /** The engine's id for it (to go on with later). */
        String id();

        /** Asks; the answer comes as events, ending with {@link ChatEvent.Idle}. */
        void send(String text) throws Exception;

        /** Stops the answer under way. */
        void abort();

        /** Another model from the next question on. */
        void setModel(String model, String effort) throws Exception;

        @Override
        void close();
    }

    /** Hears what happens in a conversation. */
    interface Listener {
        void on(ChatEvent e);
    }
}
