package logsift.chat;

import io.quackjvm.core.Quack;
import io.quackjvm.core.sql.SqlRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Every chat with the assistant, in a DuckDB file of Logsift's own ({@code chats.duckdb} in its
 * folder): the chats, and each one's entries in order. Read a page at a time, by key, so a chat
 * of thousands of entries scrolls back as fast as a short one, and searched across all of them.
 *
 * <p>Safe from several threads: an answer streams in on one while the window reads pages.</p>
 */
public final class ChatStore implements AutoCloseable {
    /** The layout's version, kept in the file; later versions add to it. */
    static final int VERSION = 1;

    private final Quack db;
    private final Path file;

    private ChatStore(Quack db, Path file) {
        this.db = db;
        this.file = file;
    }

    /**
     * Opens (or makes) the chats in {@code dir}. Another program with the file open:
     * {@link io.quackjvm.core.DatabaseInUse}.
     */
    public static ChatStore open(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("chats.duckdb");
        ChatStore s = new ChatStore(Quack.open(f.toString()), f);
        s.migrate();
        return s;
    }

    /** Only in memory: for tests, or when the file can't be had. */
    public static ChatStore inMemory() {
        ChatStore s = new ChatStore(Quack.inMemory(), null);
        s.migrate();
        return s;
    }

    /** The file, or null in memory. */
    public Path file() {
        return file;
    }

    private void migrate() {
        db.execute("CREATE TABLE IF NOT EXISTS meta (k VARCHAR PRIMARY KEY, v VARCHAR)");
        db.execute("""
                CREATE TABLE IF NOT EXISTS chat (id VARCHAR PRIMARY KEY, title VARCHAR, created BIGINT, updated BIGINT,
                    model VARCHAR, session VARCHAR, context VARCHAR)""");
        db.execute("""
                CREATE TABLE IF NOT EXISTS message (chat VARCHAR, seq BIGINT, time BIGINT, kind VARCHAR, text VARCHAR,
                    model VARCHAR, tool VARCHAR, args VARCHAR, ok BOOLEAN, tokens_in BIGINT, tokens_out BIGINT,
                    nano_aiu DOUBLE, millis BIGINT, PRIMARY KEY (chat, seq))""");
        db.execute("INSERT OR REPLACE INTO meta VALUES ('version', ?)", String.valueOf(VERSION));
    }

    // ---------------------------------------------------------------- chats

    /** A new chat. */
    public synchronized ChatInfo create(String title, String model, String context) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        db.execute("INSERT INTO chat VALUES (?, ?, ?, ?, ?, NULL, ?)", id, title, now, now, model, context);
        return new ChatInfo(id, title, now, now, model, null, context, 0, 0);
    }

    public synchronized void rename(String id, String title) {
        db.execute("UPDATE chat SET title = ? WHERE id = ?", title, id);
    }

    /** The model now chosen for it. */
    public synchronized void setModel(String id, String model) {
        db.execute("UPDATE chat SET model = ? WHERE id = ?", model, id);
    }

    /** The assistant's own id for the chat, to go on with it later. */
    public synchronized void setSession(String id, String session) {
        db.execute("UPDATE chat SET session = ? WHERE id = ?", session, id);
    }

    /** Removes a chat and its entries (only ever when the person asks). */
    public synchronized void delete(String id) {
        db.execute("DELETE FROM message WHERE chat = ?", id);
        db.execute("DELETE FROM chat WHERE id = ?", id);
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
        if (after == null) {
            return chats("SELECT " + CHAT_COLUMNS + " FROM chat c ORDER BY c.updated DESC, c.id DESC LIMIT ?", n);
        }
        return chats("SELECT " + CHAT_COLUMNS + " FROM chat c WHERE (c.updated, c.id) < (?, ?) "
                + "ORDER BY c.updated DESC, c.id DESC LIMIT ?", after.updated(), after.id(), n);
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
        long seq = db.query("SELECT coalesce(max(seq), 0) + 1 FROM message WHERE chat = ?", m.chat()).scalar(Long.class);
        ChatMessage s = m.withSeq(seq);
        db.execute("INSERT INTO message VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", s.chat(), s.seq(), s.time(),
                s.kind().name(), s.text(), s.model(), s.tool(), s.args(), s.ok(), s.tokensIn(), s.tokensOut(),
                s.nanoAiu(), s.millis());
        touch(m.chat());
        return s;
    }

    /** Stores an entry again as it is now (an answer that has streamed further, a tool that answered). */
    public synchronized void replace(ChatMessage s) {
        db.execute("""
                UPDATE message SET text = ?, model = ?, ok = ?, tokens_in = ?, tokens_out = ?, nano_aiu = ?, millis = ?
                WHERE chat = ? AND seq = ?""", s.text(), s.model(), s.ok(), s.tokensIn(), s.tokensOut(), s.nanoAiu(),
                s.millis(), s.chat(), s.seq());
        touch(s.chat());
    }

    private void touch(String chat) {
        db.execute("UPDATE chat SET updated = ? WHERE id = ?", System.currentTimeMillis(), chat);
    }

    /** How many entries a chat has. */
    public synchronized long count(String chat) {
        return db.query("SELECT count(*) FROM message WHERE chat = ?", chat).scalar(Long.class);
    }

    /** The last {@code n} entries of a chat, in order. */
    public synchronized List<ChatMessage> last(String chat, int n) {
        return before(chat, Long.MAX_VALUE, n);
    }

    /** The {@code n} entries before {@code seq} (not counting it), in order. */
    public synchronized List<ChatMessage> before(String chat, long seq, int n) {
        List<ChatMessage> l = messages("SELECT * FROM message WHERE chat = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                chat, seq, n);
        Collections.reverse(l);
        return l;
    }

    /** The {@code n} entries after {@code seq} (not counting it), in order. */
    public synchronized List<ChatMessage> after(String chat, long seq, int n) {
        return messages("SELECT * FROM message WHERE chat = ? AND seq > ? ORDER BY seq LIMIT ?", chat, seq, n);
    }

    /** {@code seq} and up to {@code n} entries each side of it, in order: where a search hit is. */
    public synchronized List<ChatMessage> around(String chat, long seq, int n) {
        List<ChatMessage> l = new ArrayList<>(before(chat, seq, n));
        l.addAll(messages("SELECT * FROM message WHERE chat = ? AND seq >= ? ORDER BY seq LIMIT ?", chat, seq, n + 1));
        return l;
    }

    private List<ChatMessage> messages(String sql, Object... params) {
        List<ChatMessage> out = new ArrayList<>();
        db.query(sql, params).forEachRow(r -> out.add(message(r)));
        return out;
    }

    private static ChatMessage message(SqlRow r) {
        Object ok = r.get("ok");
        return new ChatMessage(r.getString("chat"), r.getLong("seq"), r.getLong("time"),
                ChatMessage.Kind.valueOf(r.getString("kind")), r.getString("text"), r.getString("model"),
                r.getString("tool"), r.getString("args"), ok == null ? null : (Boolean) ok, r.getLong("tokens_in"),
                r.getLong("tokens_out"), r.getDouble("nano_aiu"), r.getLong("millis"));
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

    @Override
    public synchronized void close() {
        db.close();
    }
}
