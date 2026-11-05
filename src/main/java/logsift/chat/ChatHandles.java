package logsift.chat;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which file a handle ({@code log:3}) in a chat meant: the logs the window had open with each
 * question, and those the assistant opened itself ({@code open_log} answers {@code log:3  /path}).
 * Read back from the chat, so a link still opens its file after the assistant was closed, Logsift
 * restarted, or the same handle came to mean another log later on.
 */
public final class ChatHandles {
    private ChatHandles() {
    }

    /** A tool's answer naming a log it opened: {@code log:3  /var/log/app.log}, at a line's start. */
    private static final Pattern OPENED = Pattern.compile("(?m)^(log:\\d+) {2}(\\S.*?)\\s*$");
    private static final int PAGE = 200;

    /** What a question was asked with: the window's logs, by handle. Kept with the question. */
    public static String record(Map<String, String> logs) {
        return logs == null || logs.isEmpty() ? null : logsift.agent.JsonWriter.write(Map.of("logs", logs));
    }

    /** The file this entry says the handle is, or null if it doesn't say. */
    static String in(ChatMessage m, String handle) {
        if (m.kind() == ChatMessage.Kind.TOOL && m.text() != null && m.text().contains(handle)) {
            Matcher x = OPENED.matcher(m.text());
            while (x.find()) {
                if (x.group(1).equals(handle)) {
                    return x.group(2);
                }
            }
        }
        if (m.kind() == ChatMessage.Kind.USER && m.args() != null && m.args().contains(handle)) {
            try {
                Map<String, Object> a = logsift.core.Json.parseObject(m.args());
                if (a != null && a.get("logs") instanceof Map<?, ?> logs && logs.get(handle) != null) {
                    return logs.get(handle).toString();
                }
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * The file a handle meant where it's written: the latest entry at or before {@code seq} that
     * says (0: the chat's end). Null when the chat doesn't say (chats from before this was kept).
     */
    public static String find(ChatStore store, String chat, long seq, String handle) {
        List<ChatMessage> page = seq > 0 ? store.before(chat, seq + 1, PAGE) : store.last(chat, PAGE);
        while (!page.isEmpty()) {
            for (int i = page.size() - 1; i >= 0; i--) {
                String p = in(page.get(i), handle);
                if (p != null) {
                    return p;
                }
            }
            page = store.before(chat, page.get(0).seq(), PAGE);
        }
        return null;
    }

    /** A local file to open again: one on disk, or one in a zip that's on disk; null otherwise (a server's log by name). */
    public static java.nio.file.Path openable(String file) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(file).toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(p)) {
                return p;
            }
            logsift.core.Archives.Located in = logsift.core.Archives.locate(p);
            return in != null && java.nio.file.Files.isRegularFile(in.zip()) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
