package logsift.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import logsift.agent.Tool;
import logsift.agent.Workspace;

/**
 * The assistant's audit for coding agents (over MCP): the questions asked in Logsift's window,
 * what answering each took and what looks wrong or wasted, with the person's verdicts, to find
 * what to change. Read only. Logsift not running: read from its chats file; running: it has the
 * file, so it's asked (over its own private local connection) and answers the same.
 */
public final class AuditTools {
    private AuditTools() {
    }

    /** The chats, however they can be had. */
    public interface Source {
        /** {@code ask} answered from the chats; for a tool's arguments. */
        String answer(String tool, Map<String, Object> args) throws Exception;
    }

    /** What each question can be listed by. */
    static final List<String> SHOWS = List.of("flagged", "all", "bad", "good", "unrated");

    public static final String LIST = "assistant_audit";
    public static final String QUESTION = "assistant_audit_question";

    /** The tools, answered from {@code source}. */
    public static List<Tool> all(Source source) {
        return List.of(new Def(LIST, "Assistant audit",
                "The audit of Logsift's in-window assistant: the questions people asked it, newest first, with what "
                        + "answering each took (model, tokens, model calls, each tool called with its arguments and the size "
                        + "of its answer, time, cost) and what looks wrong or wasted: errors, no answer, failed tools, the "
                        + "same call made again, big tool answers, many tools, many tokens, slow answers, line links that "
                        + "can't open, answers the person rated bad (with their note). Use it to find why the assistant's "
                        + "answers go wrong or cost too much, and what to change. Each question has an id for "
                        + QUESTION + ".",
                new Tool.Schema().integer("days", "how far back, in days (default 7; 0: all)", false)
                        .choice("show", "which questions (default flagged)", false, SHOWS.toArray(new String[0]))
                        .integer("limit", "at most this many (default 20, at most 200)", false)
                        .bool("details", "also what went with each question, the start of each tool's answer and the "
                                + "instructions (much longer)").build(),
                source), new Def(QUESTION, "One question of the assistant's audit",
                "One question from " + LIST + " in full: what went with it (the time, what the person was looking at), "
                        + "each step with the start of each tool's answer, what was flagged and why, the answer, and the "
                        + "person's verdict. With instructions=true, also all the instructions the assistant had.",
                new Tool.Schema().string("id", "the question's id from " + LIST + " (chat:seq)", true)
                        .bool("instructions", "also the instructions the assistant had (a few thousand tokens)").build(),
                source));
    }

    private record Def(String name, String title, String description, Map<String, Object> inputSchema, Source source)
            implements Tool {
        @Override
        public String run(Args args, Workspace w) throws Exception {
            // the arguments as given, checked here so a mistake is said before anyone is asked
            Map<String, Object> a = new java.util.LinkedHashMap<>();
            if (name.equals(LIST)) {
                a.put("days", args.number("days", 7, 0, 3650));
                String show = args.string("show", "flagged").toLowerCase(java.util.Locale.ROOT);
                if (!SHOWS.contains(show)) {
                    throw new Failure("show must be one of " + String.join(", ", SHOWS) + ", not " + show);
                }
                a.put("show", show);
                a.put("limit", args.number("limit", 20, 1, 200));
                a.put("details", args.bool("details", false));
            } else {
                a.put("id", args.string("id"));
                a.put("instructions", args.bool("instructions", false));
            }
            return source.answer(name, a);
        }
    }

    // ---------------------------------------------------------------- answering from the chats

    /** The answer to {@code tool} with {@code args} (as {@link Def#run} checked them) from {@code store}. */
    public static String answer(ChatStore store, ChatAudit.Settings s, String tool, Map<String, Object> args)
            throws Tool.Failure {
        if (store == null) {
            return "No chats yet: the assistant (⌘I or Ctrl+I in Logsift) hasn't been asked anything.";
        }
        if (LIST.equals(tool)) {
            long days = num(args.get("days"), 7);
            String show = String.valueOf(args.getOrDefault("show", "flagged"));
            int limit = (int) Math.max(1, Math.min(200, num(args.get("limit"), 20)));
            boolean details = "true".equals(String.valueOf(args.get("details")));
            long since = days == 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
            List<ChatAudit.Turn> picked = new ArrayList<>();
            ChatAudit.Turn last = null;
            int read = 0;
            while (picked.size() < limit && read < 20_000) {
                List<ChatAudit.Turn> p = ChatAudit.page(store, since, last, ChatAudit.PAGE, s);
                if (p.isEmpty()) {
                    break;
                }
                read += p.size();
                for (ChatAudit.Turn t : p) {
                    if (picked.size() < limit && shows(show, t)) {
                        picked.add(t);
                    }
                }
                last = p.get(p.size() - 1);
            }
            ChatStore.Totals totals = store.totals(since);
            String head = totals.questions() + " questions asked " + (days == 0 ? "in all" : "in the last " + days
                    + (days == 1 ? " day" : " days")) + "; " + picked.size() + " " + (show.equals("all") ? "" : show + " ")
                    + "shown" + (picked.size() == limit ? " (the newest " + limit + ")" : "") + ".\n\n";
            if (picked.isEmpty()) {
                return head + (show.equals("flagged") ? "Nothing flagged: try show=all, or more days." : "None.");
            }
            String r = ChatAudit.report(store, picked, details);
            return head + r.substring(r.indexOf('\n') + 1).strip()
                    + "\n\nOne in full: " + QUESTION + " with its id.";
        }
        String id = String.valueOf(args.get("id"));
        int colon = id.lastIndexOf(':');
        long seq;
        try {
            seq = colon < 0 ? -1 : Long.parseLong(id.substring(colon + 1).strip());
        } catch (NumberFormatException e) {
            seq = -1;
        }
        if (seq < 0) {
            throw new Tool.Failure("id is a question's id from " + LIST + ", chat:seq, not " + id);
        }
        String chat = id.substring(0, colon).strip();
        ChatAudit.Turn t = ChatAudit.turn(store, chat, seq, s);
        if (t == null) {
            throw new Tool.Failure("No question " + id + ": the chat was deleted, or it isn't a question's id.");
        }
        StringBuilder b = new StringBuilder("# ").append(ChatAudit.when(t.time())).append(" · ").append(t.title())
                .append("\n\n").append(ChatAudit.details(store, t).strip());
        if ("true".equals(String.valueOf(args.get("instructions")))) {
            String told = store.turn(chat, seq).instructions();
            b.append("\n\n## Instructions\n\n").append(told == null ? "Not kept (Settings → Assistant → Audit)." : told);
        }
        return b.toString();
    }

    /** A number as given (from JSON, or checked already), or {@code or}. */
    private static long num(Object v, long or) {
        if (v == null) {
            return or;
        }
        try {
            return (long) Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return or;
        }
    }

    static boolean shows(String show, ChatAudit.Turn t) {
        return switch (show) {
            case "flagged" -> !t.flags().isEmpty();
            case "bad" -> t.rating() < 0;
            case "good" -> t.rating() > 0;
            case "unrated" -> t.rating() == 0;
            default -> true;
        };
    }

    // ---------------------------------------------------------------- for logsift-mcp

    /**
     * For an agent's own process (logsift-mcp): the chats file read only when Logsift isn't
     * running, else Logsift asked over its local connection ({@link logsift.core.SingleInstance}).
     */
    public static Source fromHome(Path home) {
        return (tool, args) -> {
            ChatAudit.Settings s = ChatAudit.Settings.load(home);
            ChatStore store;
            try {
                store = ChatStore.openReadOnly(home.resolve("chat"));
            } catch (io.quackjvm.core.DatabaseInUse e) {
                return askRunning(home, tool, args);
            }
            try {
                return answer(store, s, tool, args);
            } finally {
                if (store != null) {
                    store.close(); // at once: Logsift starting now needs the file
                }
            }
        };
    }

    /** Where a running Logsift puts an answer for an agent. */
    static Path answers(Path home) {
        return home.resolve("chat").resolve("audit-answers");
    }

    /** The command a running Logsift is sent: audit, the tool, its arguments as JSON, the answer's file name. */
    static final String COMMAND = "audit";

    static String askRunning(Path home, String tool, Map<String, Object> args) throws Exception {
        String name = java.util.UUID.randomUUID() + ".md";
        String json = logsift.agent.JsonWriter.write(args);
        int code = logsift.core.SingleInstance.command(home, List.of(COMMAND, tool, json, name));
        Path f = answers(home).resolve(name);
        if (code < 0) {
            throw new Tool.Failure("Logsift has the chats open but didn't answer. Try again, or quit Logsift.");
        }
        try {
            String text = Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
            if (code == 2) {
                throw new Tool.Failure(text.isBlank() ? "Logsift couldn't read the audit." : text);
            }
            if (code != 0) {
                throw new IllegalStateException(text.isBlank() ? "Logsift couldn't read the audit." : text);
            }
            return text;
        } finally {
            Files.deleteIfExists(f); // the answer's own file, made for this call
        }
    }

    /**
     * In Logsift: answers an agent's {@code audit} command into a file of its own folder.
     * Gives the exit code: 0 answered, 2 a mistake to tell the agent, 1 it went wrong.
     */
    public static int run(Path home, ChatStore store, List<String> cmd) {
        if (cmd.size() != 4 || !cmd.get(0).equals(COMMAND) || !(cmd.get(1).equals(LIST) || cmd.get(1).equals(QUESTION))
                || !cmd.get(3).matches("[0-9a-f-]{36}\\.md")) {
            return 1; // only the answer's own name: nowhere else is written
        }
        Path f = answers(home).resolve(cmd.get(3));
        int code;
        String text;
        try {
            Object parsed = logsift.core.Json.parseValue(cmd.get(2), null);
            @SuppressWarnings("unchecked")
            Map<String, Object> args = parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            text = answer(store, ChatAudit.Settings.load(home), cmd.get(1), args);
            code = 0;
        } catch (Tool.Failure e) {
            text = e.getMessage();
            code = 2;
        } catch (RuntimeException e) {
            text = "Logsift couldn't read the audit: " + e.getMessage();
            code = 1;
        }
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, text, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return 1;
        }
        return code;
    }
}
