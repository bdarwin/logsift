package logsift.chat;

import io.quackjvm.core.Quack;
import io.quackjvm.core.journal.Journal;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.page.Page;
import io.quackjvm.core.page.PageKey;
import io.quackjvm.core.page.Pages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every chat with the assistant, in a DuckDB file of Logsift's own ({@code chats.duckdb} in its
 * folder): the chats, and each one's entries in order. Written through two quackjvm
 * {@link Journal}s (one writer at a time, an entry numbered within its chat, a chat deleted with its
 * entries in one go), and read a page at a time by {@link Pages} (each page found from the row next
 * to it), so a chat of thousands of entries scrolls back as fast as a short one; and searched across
 * all of them.
 *
 * <p>Safe from several threads: an answer streams in on one while the window reads pages.</p>
 */
public final class ChatStore implements AutoCloseable {
    /** A chat as stored (its columns' names and order are the file's). */
    record ChatRow(String id, String title, Long created, Long updated, String model, String session, String context) {}

    /** An entry as stored. */
    record MessageRow(String chat, Long seq, Long time, String kind, String text, String model, String tool, String args,
                      Boolean ok, Long tokens_in, Long tokens_out, Double nano_aiu, Long millis) {}

    private final Quack db;
    private final Path file;
    private final Journal<ChatRow> chats;
    private final Journal<MessageRow> messages;

    private ChatStore(Quack db, Path file) {
        this.db = db;
        this.file = file;
        // the tables made by Logsift before the journals have these columns in this order: they open as they are
        chats = Journal.builder(db, "chat", ColumnarLayout.ofRecord(ChatRow.class)).index("updated", "id").open();
        messages = Journal.builder(db, "message", ColumnarLayout.ofRecord(MessageRow.class))
                .keyColumns("chat", "seq").sequence("seq").within("chat").newestBy("time").searchable("text").open();
    }

    /**
     * Opens (or makes) the chats in {@code dir}. Another program with the file open:
     * {@link io.quackjvm.core.DatabaseInUse}.
     */
    public static ChatStore open(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("chats.duckdb");
        return new ChatStore(Quack.open(f.toString()), f);
    }

    /** Only in memory: for tests, or when the file can't be had. */
    public static ChatStore inMemory() {
        return new ChatStore(Quack.inMemory(), null);
    }

    /** The file, or null in memory. */
    public Path file() {
        return file;
    }

    // ---------------------------------------------------------------- chats

    /** A new chat. */
    public synchronized ChatInfo create(String title, String model, String context) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        chats.append(new ChatRow(id, title, now, now, model, null, context));
        return new ChatInfo(id, title, now, now, model, null, context, 0, 0);
    }

    public synchronized void rename(String id, String title) {
        chats.update(id, Map.of("title", title));
    }

    /** The model now chosen for it. */
    public synchronized void setModel(String id, String model) {
        chats.update(id, Map.of("model", model));
    }

    /** The assistant's own id for the chat, to go on with it later. */
    public synchronized void setSession(String id, String session) {
        chats.update(id, Map.of("session", session));
    }

    /** Removes a chat and its entries (only ever when the person asks). */
    public synchronized void delete(String id) {
        messages.deleteWhere("chat = ?", id);
        chats.delete(id);
    }

    private static final String CHAT_COLUMNS = """
            c.id, c.title, c.created, c.updated, c.model, c.session, c.context,
            (SELECT count(*) FROM message m WHERE m.chat = c.id) AS n,
            (SELECT coalesce(sum(m.nano_aiu), 0) FROM message m WHERE m.chat = c.id) AS cost""";

    public synchronized ChatInfo chat(String id) {
        List<ChatInfo> l = chats("SELECT " + CHAT_COLUMNS + " FROM chat c WHERE c.id = ?", id);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * Chats, newest first: the first {@code n}, or (with {@code after}, the last one shown) the
     * {@code n} after it.
     */
    public synchronized List<ChatInfo> chats(ChatInfo after, int n) {
        // in order of (updated, id): the newest are the last page, and going on is going back
        Pages pages = Pages.of(db, "SELECT " + CHAT_COLUMNS + " FROM chat c").orderBy("updated", "id").size(n);
        Page p = after == null ? pages.last() : pages.before(PageKey.of(after.updated(), after.id()));
        List<ChatInfo> out = new ArrayList<>(p.size());
        for (int i = p.size() - 1; i >= 0; i--) {
            out.add(new ChatInfo((String) p.get(i, "id"), (String) p.get(i, "title"), num(p.get(i, "created")),
                    num(p.get(i, "updated")), (String) p.get(i, "model"), (String) p.get(i, "session"),
                    (String) p.get(i, "context"), num(p.get(i, "n")), dbl(p.get(i, "cost"))));
        }
        return out;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static double dbl(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    /** How many chats there are. */
    public synchronized long chatCount() {
        return db.query("SELECT count(*) FROM chat").scalar(Long.class);
    }

    private List<ChatInfo> chats(String sql, Object... params) {
        List<ChatInfo> out = new ArrayList<>();
        db.query(sql, params).forEachRow(r -> out.add(new ChatInfo(r.getString("id"), r.getString("title"),
                r.getLong("created"), r.getLong("updated"), r.getString("model"), r.getString("session"),
                r.getString("context"), r.getLong("n"), r.getDouble("cost"))));
        return out;
    }

    // ---------------------------------------------------------------- entries

    /** Adds an entry at the end of its chat; gives it with its place. */
    public synchronized ChatMessage append(ChatMessage m) {
        // numbered by the journal: one more than the chat's last entry, under its writer's turn
        MessageRow stored = messages.appendAndGet(new MessageRow(m.chat(), null, m.time(), m.kind().name(), m.text(),
                m.model(), m.tool(), m.args(), m.ok(), m.tokensIn(), m.tokensOut(), m.nanoAiu(), m.millis()));
        touch(m.chat());
        return m.withSeq(stored.seq());
    }

    /** Stores an entry again as it is now (an answer that has streamed further, a tool that answered). */
    public synchronized void replace(ChatMessage s) {
        Map<String, Object> now = new java.util.HashMap<>();
        now.put("text", s.text());
        now.put("model", s.model());
        now.put("ok", s.ok());
        now.put("tokens_in", s.tokensIn());
        now.put("tokens_out", s.tokensOut());
        now.put("nano_aiu", s.nanoAiu());
        now.put("millis", s.millis());
        messages.update(PageKey.of(s.chat(), s.seq()), now);
        touch(s.chat());
    }

    private void touch(String chat) {
        chats.update(chat, Map.of("updated", System.currentTimeMillis()));
    }

    /** How many entries a chat has. */
    public synchronized long count(String chat) {
        return db.query("SELECT count(*) FROM message WHERE chat = ?", chat).scalar(Long.class);
    }

    /** A chat's entries, in order, a page of {@code n} at a time. */
    private Pages entries(String chat, int n) {
        return Pages.of(db, "SELECT * FROM message WHERE chat = ?", chat).orderBy("seq").size(Math.max(1, n));
    }

    /** The last {@code n} entries of a chat, in order. */
    public synchronized List<ChatMessage> last(String chat, int n) {
        return messages(entries(chat, n).last());
    }

    /** The {@code n} entries before {@code seq} (not counting it), in order. */
    public synchronized List<ChatMessage> before(String chat, long seq, int n) {
        return messages(entries(chat, n).before(PageKey.of(seq)));
    }

    /** The {@code n} entries after {@code seq} (not counting it), in order. */
    public synchronized List<ChatMessage> after(String chat, long seq, int n) {
        return messages(entries(chat, n).after(PageKey.of(seq)));
    }

    /** {@code seq} and up to {@code n} entries each side of it, in order: where a search hit is. */
    public synchronized List<ChatMessage> around(String chat, long seq, int n) {
        return messages(entries(chat, n).around(PageKey.of(seq), n));
    }

    private static List<ChatMessage> messages(Page p) {
        List<ChatMessage> out = new ArrayList<>(p.size());
        for (int i = 0; i < p.size(); i++) {
            Object ok = p.get(i, "ok");
            out.add(new ChatMessage((String) p.get(i, "chat"), num(p.get(i, "seq")), num(p.get(i, "time")),
                    ChatMessage.Kind.valueOf((String) p.get(i, "kind")), (String) p.get(i, "text"),
                    (String) p.get(i, "model"), (String) p.get(i, "tool"), (String) p.get(i, "args"),
                    ok == null ? null : (Boolean) ok, num(p.get(i, "tokens_in")), num(p.get(i, "tokens_out")),
                    dbl(p.get(i, "nano_aiu")), num(p.get(i, "millis"))));
        }
        return out;
    }

    // ---------------------------------------------------------------- search and usage

    /**
     * A place where the words were found.
     *
     * @param chat    the chat
     * @param title   its name
     * @param seq     the entry (0: the chat's name matched)
     * @param kind    what the entry is
     * @param snippet the words around the match
     * @param time    when
     */
    public record Hit(String chat, String title, long seq, ChatMessage.Kind kind, String snippet, long time) {}

    /** Entries (and chat names) with {@code words} in them, newest first, at most {@code n}. */
    public synchronized List<Hit> search(String words, int n) {
        String w = words.strip();
        List<Hit> out = new ArrayList<>();
        if (w.isEmpty()) {
            return out;
        }
        String like = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        db.query("""
                SELECT * FROM (
                  SELECT c.id AS chat, c.title, 0 AS seq, 'NOTE' AS kind, c.title AS text, c.updated AS time
                    FROM chat c WHERE c.title ILIKE ? ESCAPE '\\'
                  UNION ALL
                  SELECT m.chat, c.title, m.seq, m.kind, m.text, m.time
                    FROM message m JOIN chat c ON c.id = m.chat WHERE m.text ILIKE ? ESCAPE '\\')
                ORDER BY time DESC, seq DESC LIMIT ?""", like, like, n)
                .forEachRow(r -> out.add(new Hit(r.getString("chat"), r.getString("title"), r.getLong("seq"),
                        ChatMessage.Kind.valueOf(r.getString("kind")), snippet(r.getString("text"), w),
                        r.getLong("time"))));
        return out;
    }

    /** The words around the first place {@code w} is in {@code text}, on one line. */
    static String snippet(String text, String w) {
        if (text == null) {
            return "";
        }
        int at = text.toLowerCase(java.util.Locale.ROOT).indexOf(w.toLowerCase(java.util.Locale.ROOT));
        int from = Math.max(0, at - 40);
        int to = Math.min(text.length(), (at < 0 ? 0 : at + w.length()) + 80);
        String s = text.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "…" : "") + s + (to < text.length() ? "…" : "");
    }

    /**
     * What one model cost.
     *
     * @param model     its id
     * @param answers   answers it gave
     * @param tokensIn  tokens read
     * @param tokensOut tokens written
     * @param nanoAiu   billionths of an AI credit
     */
    public record Usage(String model, long answers, long tokensIn, long tokensOut, double nanoAiu) {
        public double credits() {
            return nanoAiu / 1e9;
        }
    }

    /** What each model cost since {@code since} (ms), the dearest first. */
    public synchronized List<Usage> usage(long since) {
        List<Usage> out = new ArrayList<>();
        db.query("""
                SELECT model, count(*) AS n, sum(tokens_in) AS i, sum(tokens_out) AS o, sum(nano_aiu) AS c
                FROM message WHERE kind = 'ASSISTANT' AND time >= ? AND model IS NOT NULL
                GROUP BY model ORDER BY c DESC, model""", since)
                .forEachRow(r -> out.add(new Usage(r.getString("model"), r.getLong("n"), r.getLong("i"), r.getLong("o"),
                        r.getDouble("c"))));
        return out;
    }

    /** For tests: when a chat last changed. */
    synchronized void updatedForTest(String id, long ms) {
        chats.update(id, Map.of("updated", ms));
    }

    @Override
    public synchronized void close() {
        db.close();
    }
}
