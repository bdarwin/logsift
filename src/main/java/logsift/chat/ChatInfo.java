package logsift.chat;

/**
 * A chat in the list of chats.
 *
 * @param id       its id
 * @param title    its name (the first question, or what the model named it)
 * @param created  when it started (ms)
 * @param updated  when it last changed (ms)
 * @param model    the model last chosen for it
 * @param session  the assistant's own id for it, to go on where it left off (null: none yet)
 * @param context  the log it was about, when it began (a path or name), or null
 * @param messages how many entries it has
 * @param nanoAiu  what it cost in all, in billionths of an AI credit
 */
public record ChatInfo(String id, String title, long created, long updated, String model, String session,
                       String context, long messages, double nanoAiu) {

    public double credits() {
        return nanoAiu / 1e9;
    }
}
