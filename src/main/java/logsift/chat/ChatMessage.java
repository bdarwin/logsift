package logsift.chat;

/**
 * One entry in a chat, as stored: what the person asked, the assistant's answer, a tool it
 * called (with the arguments and Logsift's answer), an error or a note.
 *
 * @param chat      the chat's id
 * @param seq       its place in the chat, from 1 (0 before it is stored)
 * @param time      when, in milliseconds since 1970
 * @param kind      what it is
 * @param text      the words (for a tool, Logsift's answer)
 * @param model     the model that answered (assistant), or null
 * @param tool      a tool's name, or null
 * @param args      a tool's arguments as JSON, or null
 * @param ok        whether a tool answered (false: it refused or failed), or null
 * @param tokensIn  tokens read (prompt, cached included) for an answer
 * @param tokensOut tokens written for an answer
 * @param nanoAiu   what the answer cost, in billionths of an AI credit (0 when unknown)
 * @param millis    how long it took
 */
public record ChatMessage(String chat, long seq, long time, Kind kind, String text, String model, String tool,
                          String args, Boolean ok, long tokensIn, long tokensOut, double nanoAiu, long millis) {

    public enum Kind { USER, ASSISTANT, TOOL, ERROR, NOTE }

    public static ChatMessage user(String chat, String text) {
        return new ChatMessage(chat, 0, System.currentTimeMillis(), Kind.USER, text, null, null, null, null, 0, 0, 0, 0);
    }

    public static ChatMessage note(String chat, String text) {
        return new ChatMessage(chat, 0, System.currentTimeMillis(), Kind.NOTE, text, null, null, null, null, 0, 0, 0, 0);
    }

    public static ChatMessage error(String chat, String text) {
        return new ChatMessage(chat, 0, System.currentTimeMillis(), Kind.ERROR, text, null, null, null, null, 0, 0, 0, 0);
    }

    public static ChatMessage assistant(String chat, String model) {
        return new ChatMessage(chat, 0, System.currentTimeMillis(), Kind.ASSISTANT, "", model, null, null, null, 0, 0, 0, 0);
    }

    public static ChatMessage tool(String chat, String name, String args) {
        return new ChatMessage(chat, 0, System.currentTimeMillis(), Kind.TOOL, "", null, name, args, null, 0, 0, 0, 0);
    }

    public ChatMessage withSeq(long s) {
        return new ChatMessage(chat, s, time, kind, text, model, tool, args, ok, tokensIn, tokensOut, nanoAiu, millis);
    }

    public ChatMessage withText(String t) {
        return new ChatMessage(chat, seq, time, kind, t, model, tool, args, ok, tokensIn, tokensOut, nanoAiu, millis);
    }

    public ChatMessage withResult(String t, boolean answered, long ms) {
        return new ChatMessage(chat, seq, time, kind, t, model, tool, args, answered, tokensIn, tokensOut, nanoAiu, ms);
    }

    /** The same with this call's usage added (a turn may call the model several times). */
    public ChatMessage plusUsage(String m, long in, long out, double nano) {
        return new ChatMessage(chat, seq, time, kind, text, m != null ? m : model, tool, args, ok, tokensIn + in,
                tokensOut + out, nanoAiu + nano, millis);
    }

    public ChatMessage withMillis(long ms) {
        return new ChatMessage(chat, seq, time, kind, text, model, tool, args, ok, tokensIn, tokensOut, nanoAiu, ms);
    }

    /** AI credits, from billionths. */
    public double credits() {
        return nanoAiu / 1e9;
    }
}
