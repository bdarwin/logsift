package logsift.chat;

/** What happens in a conversation, as it happens. */
public sealed interface ChatEvent {

    /** More of the answer. {@code message} tells answers in one turn apart (text before and after a tool). */
    record Text(String message, String delta) implements ChatEvent {}

    /** More of the model's thinking, shown faintly. */
    record Thinking(String delta) implements ChatEvent {}

    /** The model called a tool. {@code args} is JSON. */
    record ToolStarted(String callId, String tool, String args) implements ChatEvent {}

    /** A tool answered. */
    record ToolFinished(String callId, boolean ok) implements ChatEvent {}

    /**
     * A call of the model finished, and what it cost.
     *
     * @param model     the model
     * @param tokensIn  tokens read, cached ones included
     * @param tokensOut tokens written
     * @param cached    of those read, how many came from the cache (cheaper)
     * @param nanoAiu   billionths of an AI credit (0: unknown)
     * @param millis    how long it took
     */
    record Usage(String model, long tokensIn, long tokensOut, long cached, double nanoAiu, long millis)
            implements ChatEvent {}

    /** The model named the conversation. */
    record Title(String title) implements ChatEvent {}

    /** Something went wrong; the turn may still end with {@link Idle}. */
    record Failed(String message) implements ChatEvent {}

    /** The turn is over: answered, or stopped. */
    record Idle(boolean aborted) implements ChatEvent {}
}
