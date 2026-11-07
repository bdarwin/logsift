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

    /**
     * A question's audit: what went to the model with it, how often the model was called, and what
     * the person thought of the answer. Keyed by the question's place in its chat.
     */
    record TurnRow(String chat, Long seq, Long time, String sent, String prompt, Long calls, Long cached,
                   Long rating, String note) {}

    /** Instructions an assistant was given, kept once however many questions went with them. */
    record PromptRow(String id, String text, Long time) {}

    private final Quack db;
    private final Path file;
    private final Journal<ChatRow> chats;
    private final Journal<MessageRow> messages;
    private final Journal<TurnRow> turns;
    private final Journal<PromptRow> prompts;
    private final boolean readOnly;
    /** Whether the file has the audit's tables (made by Logsift 1.4.1 on): read only, it may not. */
    private final boolean hasTurns;

    private ChatStore(Quack db, Path file) {
        this(db, file, false);
    }

    /** Read only: no journals (they may make their tables); only what the audit reads. */
    private ChatStore(Quack db, Path file, boolean readOnly) {
        this.db = db;
        this.file = file;
        this.readOnly = readOnly;
        if (readOnly) {
            chats = null;
            messages = null;
            turns = null;
            prompts = null;
            hasTurns = db.query("SELECT count(*) FROM information_schema.tables WHERE table_name = 'turn'")
                    .scalar(Long.class) > 0;
            return;
        }
        hasTurns = true;
        // the tables made by Logsift before the journals have these columns in this order: they open as they are
        chats = Journal.builder(db, "chat", ColumnarLayout.ofRecord(ChatRow.class)).index("updated", "id").open();
        messages = Journal.builder(db, "message", ColumnarLayout.ofRecord(MessageRow.class))
                .keyColumns("chat", "seq").sequence("seq").within("chat").newestBy("time").searchable("text").open();
        turns = Journal.builder(db, "turn", ColumnarLayout.ofRecord(TurnRow.class)).keyColumns("chat", "seq").open();
        prompts = Journal.builder(db, "prompt", ColumnarLayout.ofRecord(PromptRow.class)).open();
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

    /**
     * Opens the chats in {@code dir} to read only (an agent reading the audit, Logsift not
     * running), or null when there are none yet. Logsift running has them:
     * {@link io.quackjvm.core.DatabaseInUse}.
     */
    public static ChatStore openReadOnly(Path dir) {
        Path f = dir.resolve("chats.duckdb");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        return new ChatStore(Quack.builder(f.toString()).readOnly(true).open(), f, true);
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
        turns.deleteWhere("chat = ?", id);
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

    // ---------------------------------------------------------------- the audit

    /**
     * What one question went with, for the audit.
     *
     * @param sent         what went before the question (the time now, the log looked at), or null
     * @param instructions the instructions the assistant had, or null
     * @param calls        how many times the model was called to answer it
     * @param cached       of the tokens read, how many came from the cache
     * @param rating       the person's verdict: 1 good, -1 bad, 0 none
     * @param note         what they wrote about it, or ""
     */
    public record TurnInfo(String sent, String instructions, long calls, long cached, int rating, String note) {
        public static final TurnInfo NONE = new TurnInfo(null, null, 0, 0, 0, "");
    }

    /** A question was asked: what went with it is kept (null: nothing kept, only its counts later). */
    public synchronized void turnAsked(String chat, long seq, String sent, String instructions) {
        String prompt = null;
        if (instructions != null && !instructions.isEmpty()) {
            prompt = promptId(instructions);
            if (prompts.get(prompt).isEmpty()) {
                prompts.append(new PromptRow(prompt, instructions, System.currentTimeMillis()));
            }
        }
        turns.append(new TurnRow(chat, seq, System.currentTimeMillis(), sent, prompt, 0L, 0L, 0L, ""));
    }

    /** The answer is done: how many times the model was called, and how much of what it read was cached. */
    public synchronized void turnAnswered(String chat, long seq, long calls, long cached) {
        if (!turns.update(PageKey.of(chat, seq), Map.of("calls", calls, "cached", cached))) {
            turns.append(new TurnRow(chat, seq, System.currentTimeMillis(), null, null, calls, cached, 0L, ""));
        }
    }

    /** The person's verdict on an answer (to the question at {@code seq}) and what they wrote. */
    public synchronized void rate(String chat, long seq, int rating, String note) {
        long r = Integer.signum(rating);
        String n = note == null ? "" : note.strip();
        if (!turns.update(PageKey.of(chat, seq), Map.of("rating", r, "note", n))) {
            turns.append(new TurnRow(chat, seq, System.currentTimeMillis(), null, null, 0L, 0L, r, n));
        }
    }

    /** The question an entry answers (the last one asked before it, or it), or 0. */
    public synchronized long questionOf(String chat, long seq) {
        Long q = db.query("SELECT max(seq) FROM message WHERE chat = ? AND seq <= ? AND kind = 'USER'", chat, seq)
                .scalar(Long.class);
        return q == null ? 0 : q;
    }

    /** What the question at {@code seq} went with; {@link TurnInfo#NONE} when nothing was kept. */
    public synchronized TurnInfo turn(String chat, long seq) {
        if (!hasTurns) {
            return TurnInfo.NONE;
        }
        TurnInfo[] out = {TurnInfo.NONE};
        db.query("""
                SELECT t.sent, p.text AS instructions, coalesce(t.calls, 0) AS calls, coalesce(t.cached, 0) AS cached,
                       coalesce(t.rating, 0) AS rating, coalesce(t.note, '') AS note
                FROM turn t LEFT JOIN prompt p ON p.id = t.prompt WHERE t.chat = ? AND t.seq = ?""", chat, seq)
                .forEachRow(r -> out[0] = new TurnInfo(r.getString("sent"), r.getString("instructions"),
                        r.getLong("calls"), r.getLong("cached"), (int) r.getLong("rating"), r.getString("note")));
        return out[0];
    }

    /** Every question's counts and verdict since {@code since} (ms), by chat, then question (what was sent: {@link #turn}). */
    public synchronized Map<String, Map<Long, TurnInfo>> turns(long since) {
        Map<String, Map<Long, TurnInfo>> out = new java.util.HashMap<>();
        if (!hasTurns) {
            return out;
        }
        db.query("SELECT chat, seq, coalesce(calls, 0) AS calls, coalesce(cached, 0) AS cached, "
                + "coalesce(rating, 0) AS rating, coalesce(note, '') AS note FROM turn WHERE time >= ?", since)
                .forEachRow(r -> out.computeIfAbsent(r.getString("chat"), k -> new java.util.HashMap<>())
                        .put(r.getLong("seq"), new TurnInfo(null, null, r.getLong("calls"), r.getLong("cached"),
                                (int) r.getLong("rating"), r.getString("note"))));
        return out;
    }

    /** An entry as the audit lists it: a tool's answer by its size only (the words are read when it's opened). */
    public record Brief(String chat, String title, long seq, long time, ChatMessage.Kind kind, String text,
                        String model, String tool, String args, Boolean ok, long chars, long tokensIn, long tokensOut,
                        double nanoAiu, long millis) {}

    /**
     * A page of the questions asked since {@code since} (ms), newest first: {@code n} of them, the
     * newest or (with {@code after}, the last one shown) the ones before it; each with all that
     * answering it took, by chat and then in order, tools' answers by size.
     */
    public synchronized List<Brief> questions(long since, Brief after, int n) {
        Pages pages = Pages.of(db, "SELECT chat, seq, time FROM message WHERE kind = 'USER' AND time >= ?", since)
                .orderBy("time", "chat", "seq").size(Math.max(1, n));
        Page p = after == null ? pages.last() : pages.before(PageKey.of(after.time(), after.chat(), after.seq()));
        if (p.size() == 0) {
            return List.of();
        }
        StringBuilder in = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
            params.add(p.get(i, "chat") + ":" + num(p.get(i, "seq")));
        }
        List<Brief> out = new ArrayList<>();
        db.query("""
                WITH q AS (SELECT chat, seq, lead(seq) OVER (PARTITION BY chat ORDER BY seq) AS nxt
                           FROM message WHERE kind = 'USER')
                SELECT m.chat, c.title, m.seq, m.time, m.kind,
                       CASE WHEN m.kind = 'TOOL' THEN NULL ELSE m.text END AS text,
                       m.model, m.tool, m.args, m.ok, length(coalesce(m.text, '')) AS chars,
                       coalesce(m.tokens_in, 0) AS tokens_in, coalesce(m.tokens_out, 0) AS tokens_out,
                       coalesce(m.nano_aiu, 0) AS nano_aiu, coalesce(m.millis, 0) AS millis
                FROM q JOIN message m ON m.chat = q.chat AND m.seq >= q.seq AND (q.nxt IS NULL OR m.seq < q.nxt)
                       JOIN chat c ON c.id = m.chat
                WHERE q.chat || ':' || q.seq IN (""" + in + ") ORDER BY m.chat, m.seq", params.toArray())
                .forEachRow(r -> out.add(new Brief(r.getString("chat"), r.getString("title"), r.getLong("seq"),
                        r.getLong("time"), ChatMessage.Kind.valueOf(r.getString("kind")), r.getString("text"),
                        r.getString("model"), r.getString("tool"), r.getString("args"), (Boolean) r.get("ok"),
                        r.getLong("chars"), r.getLong("tokens_in"), r.getLong("tokens_out"), r.getDouble("nano_aiu"),
                        r.getLong("millis"))));
        return out;
    }

    /**
     * All the questions since {@code since} (ms): how many, and the tokens and cost of their answers.
     *
     * @param questions how many were asked
     * @param tokens    the tokens read and written
     * @param nanoAiu   what they cost
     */
    public record Totals(long questions, long tokens, double nanoAiu) {}

    public synchronized Totals totals(long since) {
        long[] q = new long[2];
        double[] c = new double[1];
        db.query("""
                SELECT count(*) FILTER (WHERE kind = 'USER') AS q,
                       coalesce(sum(tokens_in + tokens_out) FILTER (WHERE kind = 'ASSISTANT'), 0) AS t,
                       coalesce(sum(nano_aiu) FILTER (WHERE kind = 'ASSISTANT'), 0) AS c
                FROM message WHERE time >= ?""", since).forEachRow(r -> {
                    q[0] = r.getLong("q");
                    q[1] = r.getLong("t");
                    c[0] = r.getDouble("c");
                });
        return new Totals(q[0], q[1], c[0]);
    }

    /** The entries from {@code from} up to (not counting) {@code to} (0: the end), in order, whole. */
    public synchronized List<ChatMessage> between(String chat, long from, long to) {
        List<ChatMessage> out = new ArrayList<>();
        Page p;
        Pages all = Pages.of(db, "SELECT * FROM message WHERE chat = ? AND seq >= ? AND (? = 0 OR seq < ?)",
                chat, from, to, to).orderBy("seq").size(500);
        p = all.first();
        while (p != null && p.size() > 0) {
            out.addAll(messages(p));
            if (p.size() < 500) {
                break;
            }
            p = all.after(PageKey.of(num(p.get(p.size() - 1, "seq"))));
        }
        return out;
    }

    static String promptId(String text) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
