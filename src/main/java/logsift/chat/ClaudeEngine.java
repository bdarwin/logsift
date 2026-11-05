package logsift.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import logsift.agent.JsonWriter;
import logsift.core.Json;

/**
 * Claude, through the person's own Claude Code program ({@code claude}), signed in with their
 * Claude subscription (or a Console account). Each question runs {@code claude -p} once, going on
 * with the conversation by its session id; the answer streams back as JSON lines.
 *
 * <p>It gets only Logsift's tools, and none of its own (no files, no shell, no web): they come
 * from a {@link ToolBridge} on this computer, so they run in the window, with what it has open and
 * what it asks first, as they do for Copilot. Logsift's instructions replace Claude Code's own,
 * and no settings, skills or slash commands of the person's are loaded.
 */
public final class ClaudeEngine implements ChatEngine {
    /** Model ids of this engine start with it: what they cost is in dollars, not AI credits. */
    public static final String PREFIX = "claude-code/";

    private final Path cli;
    private final Path work;
    /** Where Claude Code keeps its chats (it says when asked who is signed in); null: its usual place. */
    private volatile Path projects;

    /**
     * @param cli  the {@code claude} program
     * @param work the folder it works in (Logsift's own; made when a chat starts)
     */
    public ClaudeEngine(Path cli, Path work) {
        this(cli, work, null);
    }

    /** With Claude Code's folder of chats given (tests). */
    ClaudeEngine(Path cli, Path work, Path projects) {
        this.cli = cli;
        this.work = work;
        this.projects = projects;
    }

    @Override
    public String name() {
        return "Claude Code";
    }

    @Override
    public void start() throws Exception {
        Run r = run(List.of("--version"), 30);
        if (r.exit != 0) {
            throw new Exception("Claude Code didn't start: " + r.firstLine());
        }
    }

    @Override
    public String user() throws Exception {
        Run r = run(List.of("auth", "status", "--json"), 30);
        Map<String, Object> s = Json.parseObject(r.out);
        if (s != null && s.get("projectsDirectory") != null) {
            projects = Path.of(s.get("projectsDirectory").toString());
        }
        if (s == null || !Boolean.TRUE.equals(s.get("loggedIn"))) {
            return null;
        }
        return account(s);
    }

    /** How the person is signed in, for the header: "Claude Max", or the organization; never the email. */
    static String account(Map<String, Object> status) {
        Object sub = status.get("subscriptionType");
        if (sub != null && !sub.toString().isBlank()) {
            String t = sub.toString();
            return "Claude " + Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        Object org = status.get("orgName");
        if (org != null && !org.toString().isBlank()) {
            return org.toString();
        }
        return "signed in";
    }

    @Override
    public String signInHint() {
        return "Sign in once in a terminal (claude auth login), then try again.";
    }

    @Override
    public List<Model> models() {
        // the program takes aliases for the latest of each; prices are told after each answer, in dollars
        List<String> efforts = List.of("low", "medium", "high", "xhigh", "max");
        return List.of(
                new Model(PREFIX + "haiku", "Claude Haiku", 0, 0, 0, efforts, null, true),
                new Model(PREFIX + "sonnet", "Claude Sonnet", 0, 0, 0, efforts, null, true),
                new Model(PREFIX + "opus", "Claude Opus", 0, 0, 0, efforts, null, true));
    }

    @Override
    public Quota quota() {
        return null; // Claude Code doesn't tell what's left
    }

    @Override
    public Conversation open(Setup setup, Listener listener) throws Exception {
        Files.createDirectories(work);
        if (setup.resume() != null && !kept(setup.resume())) {
            // another assistant's chat, or one Claude Code no longer has: the window starts it again
            throw new Exception("Claude Code has no chat " + setup.resume());
        }
        return new ClaudeConversation(setup, listener);
    }

    @Override
    public void close() {
        // nothing runs between questions
    }

    /** Whether Claude Code still has this chat, to go on with it. */
    boolean kept(String session) {
        if (!session.matches("[0-9a-fA-F-]{36}")) {
            return false;
        }
        Path root = projects != null ? projects
                : Path.of(System.getProperty("user.home"), ".claude", "projects");
        try (var dirs = Files.list(root)) {
            return dirs.anyMatch(d -> Files.isRegularFile(d.resolve(session + ".jsonl")));
        } catch (IOException e) {
            return false;
        }
    }

    /** The command for one question (the question itself goes in on its input). */
    List<String> command(String model, String effort, Path instructions, String mcpConfig, String resume) {
        List<String> c = new ArrayList<>(List.of(cli.toString(), "-p", "--output-format", "stream-json", "--verbose",
                "--include-partial-messages",
                // Logsift's tools only: none of Claude Code's own, nobody else's MCP servers or settings
                "--tools", "", "--strict-mcp-config", "--mcp-config", mcpConfig,
                "--allowedTools", "mcp__" + ToolBridge.NAME, "--setting-sources", "", "--disable-slash-commands",
                "--system-prompt-file", instructions.toString()));
        if (model != null && !model.isBlank()) {
            c.add("--model");
            c.add(model.startsWith(PREFIX) ? model.substring(PREFIX.length()) : model);
        }
        if (effort != null && !effort.isBlank()) {
            c.add("--effort");
            c.add(effort);
        }
        if (resume != null) {
            c.add("--resume");
            c.add(resume);
        }
        return c;
    }

    /** One conversation: a program run per question, the tools served meanwhile. */
    private final class ClaudeConversation implements Conversation {
        private final Setup setup;
        private final Listener listener;
        private final ToolBridge bridge;
        private final Reader reader;
        private final Path instructions;
        private volatile String session;
        private volatile String model;
        private volatile String effort;
        private volatile Process running;
        private volatile boolean aborted;
        /** The answer under way has ended (its program may still be closing). */
        private volatile boolean answered;

        ClaudeConversation(Setup setup, Listener listener) throws IOException {
            this.setup = setup;
            this.listener = listener;
            this.session = setup.resume();
            this.model = setup.model();
            this.effort = setup.effort();
            this.reader = new Reader(listener);
            this.instructions = Files.createTempFile(work, "instructions-", ".md");
            Files.writeString(instructions, setup.instructions() == null ? "" : setup.instructions(),
                    StandardCharsets.UTF_8);
            this.bridge = ToolBridge.start(setup.tools(), reader::pendingId);
        }

        @Override
        public String id() {
            return session;
        }

        @Override
        public synchronized void send(String text) throws Exception {
            Process before = running;
            if (before != null && before.isAlive()) {
                if (!answered) {
                    throw new IllegalStateException("Still answering");
                }
                // it has answered and is closing: the next question goes on with the chat once it's gone
                if (!before.waitFor(10, TimeUnit.SECONDS)) {
                    before.destroy();
                    before.waitFor(5, TimeUnit.SECONDS);
                }
            }
            aborted = false;
            answered = false;
            ProcessBuilder pb = new ProcessBuilder(command(model, effort, instructions, bridge.mcpConfig(), session))
                    .directory(work.toFile());
            pb.environment().put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try (var in = p.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
            }
            running = p;
            Thread t = new Thread(() -> read(p), "logsift-claude");
            t.setDaemon(true);
            t.start();
        }

        private void read(Process p) {
            boolean ended = false;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    Reader.Seen seen = reader.line(line);
                    if (seen.session() != null) {
                        session = seen.session();
                    }
                    if (seen.ended()) {
                        ended = true;
                        answered = true;
                    }
                }
            } catch (IOException e) {
                if (!aborted) {
                    listener.on(new ChatEvent.Failed("Claude Code stopped: " + e.getMessage()));
                }
            }
            try {
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!ended) {
                if (!aborted && p.exitValue() != 0) {
                    listener.on(new ChatEvent.Failed("Claude Code stopped without answering (exit " + p.exitValue() + ")."));
                }
                listener.on(new ChatEvent.Idle(aborted));
            }
            answered = true;
        }

        @Override
        public void abort() {
            Process p = running;
            if (p != null) {
                aborted = true;
                p.destroy();
            }
        }

        @Override
        public void setModel(String model, String effort) {
            this.model = model;
            this.effort = effort;
        }

        @Override
        public void close() {
            abort();
            bridge.close();
            try {
                Files.deleteIfExists(instructions);
            } catch (IOException e) {
                // a file of Logsift's own, in its folder
            }
        }
    }

    /**
     * Reads Claude Code's JSON lines into the window's events. Kept apart from the program, so the
     * lines it sends can be tested as recorded.
     */
    static final class Reader {
        private final Listener listener;
        /** Tool calls announced and not yet made, by tool name: the bridge asks which is which. */
        private final Map<String, Deque<String>> pending = new HashMap<>();
        /** Ids of tool calls already told as started (a call is seen streaming, then whole). */
        private final java.util.Set<String> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private String message = "m";

        Reader(Listener listener) {
            this.listener = listener;
        }

        /** What a line said that matters beyond its events. */
        record Seen(String session, boolean ended) {}

        private static final Seen NOTHING = new Seen(null, false);

        Seen line(String line) {
            if (line.isBlank()) {
                return NOTHING;
            }
            Map<String, Object> d;
            try {
                d = Json.parseObject(line);
            } catch (RuntimeException e) {
                return NOTHING; // not ours to read
            }
            if (d == null) {
                return NOTHING;
            }
            String type = String.valueOf(d.get("type"));
            switch (type) {
                case "system" -> {
                    if ("init".equals(d.get("subtype")) && d.get("session_id") != null) {
                        return new Seen(d.get("session_id").toString(), false);
                    }
                }
                case "stream_event" -> stream(map(d.get("event")));
                case "assistant" -> {
                    for (Map<String, Object> b : blocks(map(d.get("message")))) {
                        if ("tool_use".equals(b.get("type"))) {
                            toolStarted(str(b.get("id")), str(b.get("name")), b.get("input"));
                        }
                    }
                }
                case "user" -> {
                    for (Map<String, Object> b : blocks(map(d.get("message")))) {
                        if ("tool_result".equals(b.get("type"))) {
                            listener.on(new ChatEvent.ToolFinished(str(b.get("tool_use_id")),
                                    !Boolean.TRUE.equals(b.get("is_error"))));
                        }
                    }
                }
                case "result" -> {
                    result(d);
                    return new Seen(str(d.get("session_id")), true);
                }
                default -> {
                    // rate limits, status: nothing to show
                }
            }
            return NOTHING;
        }

        private void stream(Map<String, Object> e) {
            switch (String.valueOf(e.get("type"))) {
                case "message_start" -> {
                    Object id = map(e.get("message")).get("id");
                    message = id == null ? message + "'" : id.toString();
                }
                case "content_block_start" -> {
                    Map<String, Object> b = map(e.get("content_block"));
                    if ("tool_use".equals(b.get("type"))) {
                        // known before it runs: the bridge can tell the call by it
                        String id = str(b.get("id"));
                        String name = str(b.get("name"));
                        synchronized (pending) {
                            pending.computeIfAbsent(tool(name), k -> new ArrayDeque<>()).add(id);
                        }
                    }
                }
                case "content_block_delta" -> {
                    Map<String, Object> delta = map(e.get("delta"));
                    switch (String.valueOf(delta.get("type"))) {
                        case "text_delta" -> listener.on(new ChatEvent.Text(message, str(delta.get("text"))));
                        case "thinking_delta" -> listener.on(new ChatEvent.Thinking(str(delta.get("thinking"))));
                        default -> {
                            // a tool's arguments as they're written: told whole when the call is
                        }
                    }
                }
                default -> {
                }
            }
        }

        private void toolStarted(String id, String name, Object input) {
            if (id == null || !started.add(id)) {
                return;
            }
            listener.on(new ChatEvent.ToolStarted(id, tool(name), JsonWriter.write(input == null ? Map.of() : input)));
        }

        /** The bridge asks: the oldest call of this tool announced and not yet asked for. */
        String pendingId(String tool) {
            synchronized (pending) {
                Deque<String> q = pending.get(tool);
                return q == null ? null : q.poll();
            }
        }

        private void result(Map<String, Object> d) {
            Map<String, Object> usage = map(d.get("usage"));
            long cachedRead = num(usage.get("cache_read_input_tokens"));
            long in = num(usage.get("input_tokens")) + num(usage.get("cache_creation_input_tokens")) + cachedRead;
            long out = num(usage.get("output_tokens"));
            double usd = dbl(d.get("total_cost_usd"));
            String model = null;
            for (String m : map(d.get("modelUsage")).keySet()) {
                model = m; // the last is the one that answered
            }
            listener.on(new ChatEvent.Usage(model == null ? null : PREFIX + model, in, out, cachedRead, usd * 1e9,
                    num(d.get("duration_ms"))));
            boolean failed = Boolean.TRUE.equals(d.get("is_error")) || !"success".equals(d.get("subtype"));
            if (failed) {
                Object why = d.get("result");
                String sub = str(d.get("subtype"));
                listener.on(new ChatEvent.Failed(why != null && !why.toString().isBlank() ? why.toString()
                        : "error_max_budget_usd".equals(sub) ? "It reached its spending limit."
                        : "error_max_turns".equals(sub) ? "It took too many steps." : "Claude Code failed (" + sub + ")."));
            }
            listener.on(new ChatEvent.Idle(false));
        }

        /** Logsift's name for a tool Claude calls {@code mcp__logsift__<name>}. */
        static String tool(String name) {
            String p = "mcp__" + ToolBridge.NAME + "__";
            return name != null && name.startsWith(p) ? name.substring(p.length()) : name;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object o) {
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> blocks(Map<String, Object> message) {
            List<Map<String, Object>> out = new ArrayList<>();
            if (message.get("content") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        out.add((Map<String, Object>) m);
                    }
                }
            }
            return out;
        }

        private static String str(Object o) {
            return o == null ? null : o.toString();
        }

        private static long num(Object o) {
            return (long) dbl(o);
        }

        /** A number as Logsift's JSON reads it (its text) or as Java has it. */
        private static double dbl(Object o) {
            if (o instanceof Number n) {
                return n.doubleValue();
            }
            if (o instanceof Json.Num n) {
                try {
                    return Double.parseDouble(n.text());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return 0;
        }
    }

    /** A short run of the program (its version, who is signed in). */
    private Run run(List<String> args, int seconds) throws Exception {
        List<String> c = new ArrayList<>();
        c.add(cli.toString());
        c.addAll(args);
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        p.getOutputStream().close();
        var out = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return p.getInputStream().readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new Exception("Claude Code didn't answer in " + seconds + " s");
        }
        return new Run(p.exitValue(), new String(out.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
    }

    private record Run(int exit, String out) {
        String firstLine() {
            return out.lines().findFirst().orElse("exit " + exit);
        }
    }
}
