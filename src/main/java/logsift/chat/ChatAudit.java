package logsift.chat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assistant's audit: each question asked, what answering it took (the model's calls, the
 * tools, the tokens, the time, the cost), what looks wrong or wasted, and what the person thought
 * of the answer. Read from the chats as they are stored: no model is asked anything.
 */
public final class ChatAudit {
    private ChatAudit() {
    }

    // ---------------------------------------------------------------- settings

    /**
     * Settings → Assistant → Audit.
     *
     * @param keepSent     keep what went with each question (the time, the log looked at) and the
     *                     instructions the assistant had
     * @param bigResultKb  a tool's answer bigger than this (KB) is flagged: the model read it all
     * @param costlyTokens a question that took more tokens than this is flagged
     * @param slowSeconds  a question that took longer than this is flagged
     * @param manyTools    a question that called more tools than this is flagged
     */
    public record Settings(boolean keepSent, int bigResultKb, int costlyTokens, int slowSeconds, int manyTools) {
        public static final Settings DEFAULT = new Settings(true, 20, 100_000, 120, 12);

        public Settings {
            bigResultKb = Math.max(1, bigResultKb);
            costlyTokens = Math.max(1_000, costlyTokens);
            slowSeconds = Math.max(5, slowSeconds);
            manyTools = Math.max(1, manyTools);
        }

        static Path file(Path home) {
            return home.resolve("chat").resolve("audit.properties");
        }

        public static Settings load(Path home) {
            Properties p = new Properties();
            Path f = file(home);
            if (Files.isRegularFile(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    p.load(r);
                } catch (IOException | IllegalArgumentException e) {
                    p.clear();
                }
            }
            Settings d = DEFAULT;
            return new Settings(!"false".equals(p.getProperty("keepSent")), num(p, "bigResultKb", d.bigResultKb),
                    num(p, "costlyTokens", d.costlyTokens), num(p, "slowSeconds", d.slowSeconds),
                    num(p, "manyTools", d.manyTools));
        }

        private static int num(Properties p, String key, int otherwise) {
            try {
                String v = p.getProperty(key);
                return v == null ? otherwise : Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                return otherwise;
            }
        }

        public void save(Path home) throws IOException {
            Properties p = new Properties();
            p.setProperty("keepSent", String.valueOf(keepSent));
            p.setProperty("bigResultKb", String.valueOf(bigResultKb));
            p.setProperty("costlyTokens", String.valueOf(costlyTokens));
            p.setProperty("slowSeconds", String.valueOf(slowSeconds));
            p.setProperty("manyTools", String.valueOf(manyTools));
            Path f = file(home);
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                p.store(w, "Logsift's assistant: what its audit keeps and flags");
            }
        }

        public Settings withKeepSent(boolean v) {
            return new Settings(v, bigResultKb, costlyTokens, slowSeconds, manyTools);
        }

        public Settings withBigResultKb(int v) {
            return new Settings(keepSent, v, costlyTokens, slowSeconds, manyTools);
        }

        public Settings withCostlyTokens(int v) {
            return new Settings(keepSent, bigResultKb, v, slowSeconds, manyTools);
        }

        public Settings withSlowSeconds(int v) {
            return new Settings(keepSent, bigResultKb, costlyTokens, v, manyTools);
        }

        public Settings withManyTools(int v) {
            return new Settings(keepSent, bigResultKb, costlyTokens, slowSeconds, v);
        }
    }

    // ---------------------------------------------------------------- what is flagged

    /** What looks wrong or wasted in answering a question. */
    public enum Flag {
        ERROR("Error", "The answer stopped with an error."),
        NO_ANSWER("No answer", "The model was called, but wrote no answer."),
        FAILED_TOOL("Tool failed", "A tool refused or failed: often a wrong argument the model guessed."),
        REPEATED("Same call again", "A tool was called again with the same arguments: its answer was read twice."),
        BIG_RESULT("Big tool answer", "A tool answered with more than the size set: the model read all of it."),
        MANY_TOOLS("Many tools", "More tool calls than the number set."),
        COSTLY("Many tokens", "It took more tokens than the number set."),
        SLOW("Slow", "It took longer than the time set."),
        BROKEN_LINK("Broken line link", "The answer cites a line its link can't open (no log handle, or no line)."),
        RATED_BAD("Rated bad", "You marked the answer as bad.");

        private final String label;
        private final String why;

        Flag(String label, String why) {
            this.label = label;
            this.why = why;
        }

        public String label() {
            return label;
        }

        public String why() {
            return why;
        }

        /** By its name, or null. */
        public static Flag of(String name) {
            for (Flag f : values()) {
                if (f.name().equalsIgnoreCase(name) || f.label.equalsIgnoreCase(name)) {
                    return f;
                }
            }
            return null;
        }
    }

    /** A link to a log's line that opens it: {@code logsift:log:1:1204} or {@code logsift:log:1:1204-1230}. */
    private static final Pattern LINK = Pattern.compile("\\]\\(\\s*logsift:log:([^)\\s]*)\\s*\\)");
    private static final Pattern GOOD_LINK = Pattern.compile("\\d+:\\d+(?:-\\d+)?");

    /** The links in an answer that can't open their line, as written ({@code logsift:log:864}). */
    public static List<String> brokenLinks(String answer) {
        List<String> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        Matcher m = LINK.matcher(answer);
        while (m.find()) {
            if (!GOOD_LINK.matcher(m.group(1)).matches()) {
                out.add("logsift:log:" + m.group(1));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- questions

    /**
     * One thing done to answer: a tool called, an answer written, an error.
     *
     * @param seq    its place in the chat
     * @param kind   what it is
     * @param tool   the tool, or null
     * @param args   the tool's arguments (JSON), or null
     * @param ok     whether the tool answered, or null
     * @param chars  how long its words are (a tool's answer: what the model read)
     * @param millis how long it took
     * @param again  the tool was called before with the same arguments in this question
     */
    public record Step(long seq, ChatMessage.Kind kind, String tool, String args, Boolean ok, long chars, long millis,
                       boolean again) {
        /** About how many tokens its words are. */
        public long tokens() {
            return (chars + 3) / 4;
        }
    }

    /**
     * A question and what answering it took.
     *
     * @param chat     the chat
     * @param title    the chat's name
     * @param seq      the question's place in the chat
     * @param next     the next question's place (0: none yet)
     * @param time     when it was asked
     * @param question what was asked
     * @param answer   what was answered (all its parts), or ""
     * @param model    the model that answered, or null
     * @param tokensIn tokens read (cached ones included)
     * @param tokensOut tokens written
     * @param cached   of those read, how many came from the cache (0 when not kept)
     * @param calls    how many times the model was called (0 when not kept)
     * @param nanoAiu  what it cost, in billionths of the engine's unit
     * @param millis   how long it took
     * @param steps    what was done, in order
     * @param flags    what looks wrong or wasted
     * @param rating   the person's verdict: 1 good, -1 bad, 0 none
     * @param note     what they wrote about it
     */
    public record Turn(String chat, String title, long seq, long next, long time, String question, String answer,
                       String model, long tokensIn, long tokensOut, long cached, long calls, double nanoAiu,
                       long millis, List<Step> steps, Set<Flag> flags, int rating, String note) {

        public long tokens() {
            return tokensIn + tokensOut;
        }

        public long toolCalls() {
            return steps.stream().filter(s -> s.kind() == ChatMessage.Kind.TOOL).count();
        }

        public long failedTools() {
            return steps.stream().filter(s -> s.kind() == ChatMessage.Kind.TOOL && Boolean.FALSE.equals(s.ok())).count();
        }

        /** What the tools answered, in characters: all of it went to the model. */
        public long toolChars() {
            return steps.stream().filter(s -> s.kind() == ChatMessage.Kind.TOOL).mapToLong(Step::chars).sum();
        }

        /** What it cost, for people. */
        public String cost() {
            return nanoAiu <= 0 ? "" : ChatCost.format(model, nanoAiu);
        }
    }

    /** How many questions a page of the audit has. */
    public static final int PAGE = 100;

    /**
     * A page of the questions asked since {@code since} (ms), newest first: the newest, or (with
     * {@code after}, the last one shown) the {@code n} before it.
     */
    public static List<Turn> page(ChatStore store, long since, Turn after, int n, Settings s) {
        ChatStore.Brief a = after == null ? null : new ChatStore.Brief(after.chat(), after.title(), after.seq(),
                after.time(), ChatMessage.Kind.USER, null, null, null, null, null, 0, 0, 0, 0, 0);
        List<ChatStore.Brief> entries = store.questions(since, a, n);
        if (entries.isEmpty()) {
            return List.of();
        }
        long oldest = entries.stream().mapToLong(ChatStore.Brief::time).min().orElse(since);
        return group(entries, store.turns(oldest), s);
    }

    /** Every question asked since {@code since} (ms), the newest first, at most {@code max}. */
    public static List<Turn> turns(ChatStore store, long since, int max, Settings s) {
        List<Turn> out = new ArrayList<>();
        Turn last = null;
        while (out.size() < max) {
            List<Turn> p = page(store, since, last, Math.min(PAGE, max - out.size()), s);
            if (p.isEmpty()) {
                break;
            }
            out.addAll(p);
            last = p.get(p.size() - 1);
        }
        return out;
    }

    /** The entries (by chat, in order) made into questions. */
    static List<Turn> group(List<ChatStore.Brief> entries, Map<String, Map<Long, ChatStore.TurnInfo>> kept,
                            Settings s) {
        List<Turn> out = new ArrayList<>();
        List<ChatStore.Brief> one = new ArrayList<>();
        for (ChatStore.Brief b : entries) {
            boolean newChat = !one.isEmpty() && !one.get(0).chat().equals(b.chat());
            if (newChat || b.kind() == ChatMessage.Kind.USER) {
                if (!one.isEmpty()) {
                    out.add(turn(one, newChat ? 0 : b.seq(), kept, s));
                }
                one.clear();
            }
            if (b.kind() == ChatMessage.Kind.USER || !one.isEmpty()) {
                one.add(b); // what came before the first question since then is left out
            }
        }
        if (!one.isEmpty()) {
            out.add(turn(one, 0, kept, s));
        }
        out.sort(Comparator.comparingLong(Turn::time).reversed().thenComparing(Turn::chat).thenComparing(
                Comparator.comparingLong(Turn::seq).reversed()));
        return out;
    }

    private static Turn turn(List<ChatStore.Brief> entries, long next, Map<String, Map<Long, ChatStore.TurnInfo>> kept,
                             Settings s) {
        ChatStore.Brief q = entries.get(0);
        ChatStore.TurnInfo info = kept.getOrDefault(q.chat(), Map.of()).getOrDefault(q.seq(), ChatStore.TurnInfo.NONE);
        StringBuilder answer = new StringBuilder();
        String model = null;
        long in = 0;
        long out = 0;
        double nano = 0;
        long millis = 0;
        boolean error = false;
        List<Step> steps = new ArrayList<>();
        Set<String> calls = new HashSet<>();
        for (ChatStore.Brief b : entries.subList(1, entries.size())) {
            switch (b.kind()) {
                case ASSISTANT -> {
                    if (b.text() != null && !b.text().isBlank()) {
                        answer.append(answer.length() > 0 ? "\n\n" : "").append(b.text().strip());
                    }
                    model = b.model() != null ? b.model() : model;
                    in += b.tokensIn();
                    out += b.tokensOut();
                    nano += b.nanoAiu();
                    millis = Math.max(millis, b.millis());
                }
                case ERROR -> error = true;
                default -> {
                }
            }
            if (b.kind() != ChatMessage.Kind.NOTE) {
                boolean again = b.kind() == ChatMessage.Kind.TOOL && !calls.add(b.tool() + " " + normal(b.args()));
                steps.add(new Step(b.seq(), b.kind(), b.tool(), b.args(), b.ok(), b.chars(), b.millis(), again));
            }
        }
        if (millis == 0) {
            millis = steps.stream().mapToLong(Step::millis).sum();
        }
        Turn t = new Turn(q.chat(), q.title(), q.seq(), next, q.time(), q.text() == null ? "" : q.text(),
                answer.toString(), model, in, out, info.cached(), info.calls(), nano, millis, List.copyOf(steps),
                Set.of(), info.rating(), info.note());
        return withFlags(t, error, s);
    }

    /** The arguments as compared for "the same call": their order and spaces don't count. */
    static String normal(String json) {
        if (json == null) {
            return "";
        }
        try {
            Object o = logsift.core.Json.parseValue(json, json.strip());
            return o instanceof Map<?, ?> m ? new java.util.TreeMap<>(m).toString() : String.valueOf(o);
        } catch (RuntimeException e) {
            return json.strip();
        }
    }

    private static Turn withFlags(Turn t, boolean error, Settings s) {
        EnumSet<Flag> f = EnumSet.noneOf(Flag.class);
        if (error) {
            f.add(Flag.ERROR);
        }
        boolean answered = !t.answer().isBlank();
        if (!answered && (t.tokens() > 0 || t.toolCalls() > 0) && !error) {
            f.add(Flag.NO_ANSWER);
        }
        if (t.failedTools() > 0) {
            f.add(Flag.FAILED_TOOL);
        }
        if (t.steps().stream().anyMatch(Step::again)) {
            f.add(Flag.REPEATED);
        }
        if (t.steps().stream().anyMatch(x -> x.kind() == ChatMessage.Kind.TOOL && x.chars() > s.bigResultKb() * 1024L)) {
            f.add(Flag.BIG_RESULT);
        }
        if (t.toolCalls() > s.manyTools()) {
            f.add(Flag.MANY_TOOLS);
        }
        if (t.tokens() > s.costlyTokens()) {
            f.add(Flag.COSTLY);
        }
        if (t.millis() > s.slowSeconds() * 1000L) {
            f.add(Flag.SLOW);
        }
        if (!brokenLinks(t.answer()).isEmpty()) {
            f.add(Flag.BROKEN_LINK);
        }
        if (t.rating() < 0) {
            f.add(Flag.RATED_BAD);
        }
        return new Turn(t.chat(), t.title(), t.seq(), t.next(), t.time(), t.question(), t.answer(), t.model(),
                t.tokensIn(), t.tokensOut(), t.cached(), t.calls(), t.nanoAiu(), t.millis(), t.steps(),
                f.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(f), t.rating(), t.note());
    }

    /** A question's id, as agents give it back: its chat and its place, {@code chat:seq}. */
    public static String id(Turn t) {
        return t.chat() + ":" + t.seq();
    }

    /** The question at {@code seq} of {@code chat}, or null. */
    public static Turn turn(ChatStore store, String chat, long seq, Settings s) {
        ChatInfo c = store.chat(chat);
        if (c == null) {
            return null;
        }
        List<ChatMessage> l = store.between(chat, seq, 0);
        if (l.isEmpty() || l.get(0).kind() != ChatMessage.Kind.USER) {
            return null;
        }
        List<ChatStore.Brief> b = new ArrayList<>();
        long next = 0;
        for (ChatMessage m : l) {
            if (m.kind() == ChatMessage.Kind.USER && m.seq() != seq) {
                next = m.seq();
                break;
            }
            b.add(new ChatStore.Brief(chat, c.title(), m.seq(), m.time(), m.kind(),
                    m.kind() == ChatMessage.Kind.TOOL ? null : m.text(), m.model(), m.tool(), m.args(), m.ok(),
                    m.text() == null ? 0 : m.text().length(), m.tokensIn(), m.tokensOut(), m.nanoAiu(), m.millis()));
        }
        ChatStore.TurnInfo info = store.turn(chat, seq);
        return turn(b, next, Map.of(chat, Map.of(seq, info)), s);
    }

    // ---------------------------------------------------------------- totals and the report

    /** How many questions, tokens and flags: the line above the list. */
    public static String summary(List<Turn> turns) {
        long tokens = turns.stream().mapToLong(Turn::tokens).sum();
        long flagged = turns.stream().filter(t -> !t.flags().isEmpty()).count();
        long bad = turns.stream().filter(t -> t.rating() < 0).count();
        long good = turns.stream().filter(t -> t.rating() > 0).count();
        return turns.size() + (turns.size() == 1 ? " question" : " questions") + " · " + String.format(Locale.ROOT, "%,d", tokens)
                + " tokens · " + flagged + " flagged" + (good + bad > 0 ? " · " + good + " good, " + bad + " bad" : "");
    }

    /** How often each flag came up, the most first. */
    public static Map<Flag, Long> flagCounts(List<Turn> turns) {
        Map<Flag, Long> n = new java.util.EnumMap<>(Flag.class);
        for (Turn t : turns) {
            for (Flag f : t.flags()) {
                n.merge(f, 1L, Long::sum);
            }
        }
        Map<Flag, Long> out = new LinkedHashMap<>();
        n.entrySet().stream().sorted(Map.Entry.<Flag, Long>comparingByValue().reversed())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);

    static String when(long ms) {
        return WHEN.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    /**
     * The audit as Markdown, to read or give to someone (an agent, say) to find what goes wrong:
     * each question with what answering it took, what was flagged and the person's verdict. With
     * {@code details}, also the start of each tool's answer and what went with the question.
     */
    public static String report(ChatStore store, List<Turn> turns, boolean details) {
        StringBuilder b = new StringBuilder("# Logsift assistant audit\n\n");
        b.append(summary(turns)).append("\n");
        Map<Flag, Long> counts = flagCounts(turns);
        if (!counts.isEmpty()) {
            b.append("\n| Flag | Questions | What it means |\n|---|---:|---|\n");
            counts.forEach((f, n) -> b.append("| ").append(f.label()).append(" | ").append(n).append(" | ")
                    .append(f.why()).append(" |\n"));
        }
        Map<String, String> instructions = new LinkedHashMap<>();
        for (Turn t : turns) {
            b.append("\n## ").append(when(t.time())).append(" · ").append(oneLine(t.title(), 80)).append("\n\n");
            section(b, store, t, details, instructions);
        }
        for (Map.Entry<String, String> e : instructions.entrySet()) {
            b.append("\n## Instructions ").append(e.getKey(), 0, 8).append(" (~")
                    .append((e.getValue().length() + 3) / 4).append(" tokens)\n\n```\n").append(e.getValue())
                    .append("\n```\n");
        }
        return b.toString();
    }

    /** One question's part of the report; its instructions are put in {@code instructions} to go at the end. */
    private static void section(StringBuilder b, ChatStore store, Turn t, boolean details,
                                Map<String, String> instructions) {
        b.append("**Question:** ").append(oneLine(t.question(), 2000)).append("\n\n");
        b.append("- Id: ").append(id(t)).append("\n");
        b.append("- Model: ").append(t.model() == null ? "none" : t.model()).append("; tokens: ")
                .append(t.tokensIn()).append(" in");
        if (t.cached() > 0) {
            b.append(" (").append(t.cached()).append(" cached)");
        }
        b.append(", ").append(t.tokensOut()).append(" out");
        if (t.calls() > 0) {
            b.append("; model calls: ").append(t.calls());
        }
        if (!t.cost().isEmpty()) {
            b.append("; cost: ").append(t.cost());
        }
        b.append("; time: ").append(seconds(t.millis())).append("\n");
        if (!t.flags().isEmpty()) {
            b.append("- Flags: ").append(String.join(", ", t.flags().stream().map(Flag::label).toList())).append("\n");
        }
        if (t.rating() != 0 || !t.note().isEmpty()) {
            b.append("- Verdict: ").append(t.rating() > 0 ? "good" : t.rating() < 0 ? "bad" : "none")
                    .append(t.note().isEmpty() ? "" : " — " + oneLine(t.note(), 2000)).append("\n");
        }
        List<String> broken = brokenLinks(t.answer());
        if (!broken.isEmpty()) {
            b.append("- Broken links: ").append(String.join(", ", broken)).append("\n");
        }
        Map<Long, String> results = new java.util.HashMap<>();
        ChatStore.TurnInfo info = ChatStore.TurnInfo.NONE;
        if (details) {
            for (ChatMessage m : store.between(t.chat(), t.seq(), t.next())) {
                if (m.kind() == ChatMessage.Kind.TOOL) {
                    results.put(m.seq(), m.text());
                }
            }
            info = store.turn(t.chat(), t.seq());
        }
        if (!t.steps().isEmpty()) {
            b.append("\nSteps:\n\n");
            int i = 1;
            for (Step s : t.steps()) {
                b.append(i++).append(". ");
                switch (s.kind()) {
                    case TOOL -> {
                        b.append('`').append(s.tool()).append('`').append(' ')
                                .append(s.args() == null ? "" : "`" + oneLine(s.args(), 400) + "`")
                                .append(" → ").append(Boolean.FALSE.equals(s.ok()) ? "failed, " : "")
                                .append(s.chars()).append(" chars (~").append(s.tokens()).append(" tokens), ")
                                .append(seconds(s.millis())).append(s.again() ? ", same call again" : "");
                        String r = results.get(s.seq());
                        if (r != null && !r.isBlank()) {
                            b.append("\n\n   > ").append(oneLine(r, 300));
                        }
                    }
                    case ASSISTANT -> b.append("answer, ").append(s.chars()).append(" chars");
                    case ERROR -> b.append("error");
                    default -> b.append(s.kind().name().toLowerCase(Locale.ROOT));
                }
                b.append('\n');
            }
        }
        if (details && info.sent() != null && !info.sent().isBlank()) {
            b.append("\nSent with the question:\n\n```\n").append(cut(info.sent(), 2000)).append("\n```\n");
        }
        if (details && info.instructions() != null) {
            String id = ChatStore.promptId(info.instructions());
            if (!instructions.containsKey(id)) {
                instructions.put(id, info.instructions());
            }
            b.append("\nInstructions: ").append(id, 0, 8).append(" (~")
                    .append((info.instructions().length() + 3) / 4).append(" tokens)\n");
        }
        if (!t.answer().isBlank()) {
            b.append("\n**Answer:**\n\n").append(cut(t.answer(), details ? 6000 : 1500)).append("\n");
        }
    }

    /**
     * One question in full, for its details in the audit's window: what went with it, each step with
     * the start of each tool's answer, what was flagged and why, and the answer.
     */
    public static String details(ChatStore store, Turn t) {
        StringBuilder b = new StringBuilder();
        if (!t.flags().isEmpty()) {
            b.append("| Flag | Why |\n|---|---|\n");
            for (Flag f : t.flags()) {
                b.append("| ").append(f.label()).append(" | ").append(f.why()).append(" |\n");
            }
            b.append('\n');
        }
        Map<String, String> instructions = new LinkedHashMap<>();
        section(b, store, t, true, instructions);
        return b.toString();
    }

    static String seconds(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    static String oneLine(String s, int n) {
        return cut(s == null ? "" : s.replaceAll("\\s+", " ").strip(), n);
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
