package logsift.embed;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import logsift.agent.JsonWriter;
import logsift.agent.LogCatalog;
import logsift.agent.Tool;
import logsift.agent.ToolCall;
import logsift.agent.Workspace;
import logsift.agent.mcp.McpMain;
import logsift.agent.mcp.McpServer;

/**
 * Logsift's tools for a program that embeds Logsift: the same tools, arguments and answers as
 * {@code logsift-mcp}, called in the same Java process. Put the jars from Logsift's {@code lib}
 * folder (the "any Java" zip, or an installed Logsift's {@code app} folder) on the class path.
 *
 * <pre>{@code
 * try (LogsiftTools logsift = LogsiftTools.builder()
 *         .root(Path.of("/var/log/orders"))       // folders the tools may read (default: home folder)
 *         .allowChanges(true)                    // tools that save new files, bookmarks, formats…
 *         .approver((what, detail) -> askThePerson(what, detail))  // thread dumps, heap dumps…
 *         .build()) {
 *     for (LogsiftTools.ToolInfo t : logsift.tools()) {
 *         registerWithYourModel(t.name(), t.description(), t.inputSchemaJson());
 *     }
 *     LogsiftTools.Result r = logsift.call("search", Map.of("log", "/var/log/orders/app.log", "query", "timed out"));
 *     giveToYourModel(r.text(), r.error());
 * }
 * }</pre>
 *
 * Give your model {@link #guide()} as instructions: it says which tool to use for what, with
 * examples. One instance keeps open logs (handles like {@code log:1}) between calls; it is safe to
 * call from several threads at once.
 */
public final class LogsiftTools implements AutoCloseable {

    /** Asks the person using the host program. True: go ahead. */
    @FunctionalInterface
    public interface Approver {
        /**
         * @param what   in a few words: "thread dump of orders (pid 4242)", "save the lines to errors.log"
         * @param detail what it does, for the person (may be empty)
         */
        boolean approve(String what, String detail) throws InterruptedException;
    }

    /**
     * One tool, as a model needs it.
     *
     * @param inputSchemaJson the arguments as JSON Schema, ready for a model's function definition
     * @param changesThings   whether it changes something (offered only when changes are allowed)
     */
    public record ToolInfo(String name, String title, String description, Map<String, Object> inputSchema,
                           String inputSchemaJson, boolean changesThings) {}

    /**
     * A tool's answer.
     *
     * @param text    for the model (masked, cut to size); a mistake's text says how to fix the call
     * @param error   whether the call failed (a wrong path, a missing argument, refused)
     * @param outcome ok, still working (call again with the same arguments), refused, cancelled, failed
     */
    public record Result(String text, boolean error, String outcome) {}

    /** Builds a {@link LogsiftTools}. */
    public static final class Builder {
        private Path home;
        private final List<Path> roots = new ArrayList<>();
        private final List<Path> logLocations = new ArrayList<>();
        private Boolean changes;
        private Boolean secrets;
        private boolean emails;
        private boolean ips;
        private final List<String> masks = new ArrayList<>();
        private Integer maxChars;
        private Long waitMillis;
        private Approver approver;
        private String client = "embedded";
        private Path workingDir;

        private Builder() {}

        /** Logsift's folder: settings, log formats, log locations, the audit log (default {@code ~/.logsift}). */
        public Builder home(Path dir) {
            this.home = dir;
            return this;
        }

        /** A folder the tools may read (and save new files in, when changes are allowed). Default: the home folder. */
        public Builder root(Path folder) {
            roots.add(folder);
            return this;
        }

        /** A {@code logs.json} describing where services' logs are (as a project's {@code .logsift/logs.json}). */
        public Builder logLocations(Path logsJson) {
            logLocations.add(logsJson);
            return this;
        }

        /** Whether tools that change something are offered (default: Settings → Agents in Logsift, off). */
        public Builder allowChanges(boolean allowed) {
            this.changes = allowed;
            return this;
        }

        /** Masking of secrets, emails and IP addresses in answers (default: Settings → Agents; secrets on). */
        public Builder masking(boolean secrets, boolean emails, boolean ips) {
            this.secrets = secrets;
            this.emails = emails;
            this.ips = ips;
            return this;
        }

        /** Also masks what this regular expression matches (customer numbers, host names…). */
        public Builder mask(String regex) {
            masks.add(regex);
            return this;
        }

        /** The longest answer in characters (default 24,000; at most 80,000). */
        public Builder maxChars(int chars) {
            this.maxChars = chars;
            return this;
        }

        /**
         * How long a call waits for slow work (a first look at a multi-GB log) before answering
         * "still working" (default 25 s). Calling again with the same arguments picks it up.
         */
        public Builder waitMillis(long millis) {
            this.waitMillis = millis;
            return this;
        }

        /**
         * Who to ask before something that touches a running program (thread dump, heap
         * histogram, heap dump, recording) or changes something. Without one, the JVM tools are
         * refused, and changes go ahead when allowed.
         */
        public Builder approver(Approver a) {
            this.approver = a;
            return this;
        }

        /** The host's name, for Logsift's audit log ("office assistant 2.1"). */
        public Builder client(String name) {
            this.client = name;
            return this;
        }

        /** The folder relative paths are read from (default: the current folder). */
        public Builder workingDir(Path dir) {
            this.workingDir = dir;
            return this;
        }

        public LogsiftTools build() {
            return new LogsiftTools(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final Path home;
    private final Workspace workspace;
    private final List<Tool> all;

    private LogsiftTools(Builder b) {
        home = b.home != null ? b.home.toAbsolutePath()
                : Path.of(System.getProperty("logsift.home",
                        Path.of(System.getProperty("user.home"), ".logsift").toString()));
        Path cwd = (b.workingDir != null ? b.workingDir : Path.of("")).toAbsolutePath();
        List<String> args = new ArrayList<>();
        for (Path r : b.roots) {
            args.add("--root");
            args.add(r.toAbsolutePath().toString());
        }
        if (Boolean.FALSE.equals(b.secrets)) {
            args.add("--no-mask");
        }
        if (b.emails) {
            args.add("--mask-emails");
        }
        if (b.ips) {
            args.add("--mask-ips");
        }
        for (String m : b.masks) {
            args.add("--mask");
            args.add(m);
        }
        if (b.maxChars != null) {
            args.add("--max-chars");
            args.add(String.valueOf(b.maxChars));
        }
        Workspace.Options o = McpMain.options(args.toArray(String[]::new), home, cwd);
        if (b.changes != null) {
            o = o.withChanges(b.changes);
        }
        if (b.waitMillis != null) {
            o = new Workspace.Options(o.scope(), o.masking(), o.maxChars(), Math.max(0, b.waitMillis), o.formats(),
                    o.auditLog(), o.logsiftHome(), o.allowChanges());
        }
        logsift.core.Numbers.setOptions(logsift.core.Numbers.Options.load(home));
        workspace = new Workspace(o);
        workspace.setClient(b.client);
        List<Path> locations = b.logLocations.stream().map(Path::toAbsolutePath).toList();
        workspace.setCatalog(() -> LogCatalog.load(home, cwd, locations));
        if (b.approver != null) {
            workspace.setGate(gate(b.approver));
        }
        all = McpMain.tools(home);
    }

    private static Workspace.Gate gate(Approver a) {
        return new Workspace.Gate() {
            @Override
            public void allow(String what, Path where) {
                // reading is limited by the folders (roots), as for logsift-mcp
            }

            @Override
            public void approve(String what, String key, String detail) throws Tool.Failure, InterruptedException {
                if (!a.approve(what, detail == null ? "" : detail)) {
                    throw new Tool.Failure("The person said no to: " + what + ". Don't ask again unless they want it.");
                }
            }

            @Override
            public void allowChange(String what, Path where) throws Tool.Failure, InterruptedException {
                if (!a.approve(what, where == null ? "" : where.toString())) {
                    throw new Tool.Failure("The person said no to: " + what + ". Tell them what you would have changed.");
                }
            }
        };
    }

    /** The tools offered now (those that change something only when changes are allowed). */
    public List<ToolInfo> tools() {
        List<ToolInfo> out = new ArrayList<>();
        for (Tool t : ToolCall.offered(all, workspace)) {
            out.add(new ToolInfo(t.name(), t.title(), t.description(), t.inputSchema(), JsonWriter.write(t.inputSchema()),
                    !t.readOnly()));
        }
        return List.copyOf(out);
    }

    /** The tools as JSON: {@code [{"name", "description", "input_schema", …}]}, for a model's tool list. */
    public String toolsJson() {
        List<Object> list = new ArrayList<>();
        for (ToolInfo t : tools()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", t.name());
            d.put("description", t.description());
            d.put("input_schema", t.inputSchema());
            list.add(d);
        }
        return JsonWriter.write(list);
    }

    /** Runs a tool. Never throws for the tool's own failures: they come back as an error {@link Result}. */
    public Result call(String tool, Map<String, ?> args) {
        Tool t = all.stream().filter(x -> x.name().equals(tool)).findFirst().orElse(null);
        if (t == null) {
            return new Result("No tool called " + tool + ". The tools: "
                    + String.join(", ", tools().stream().map(ToolInfo::name).toList()) + ".", true, "refused");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> a = args == null ? Map.of() : (Map<String, Object>) args;
        ToolCall c = ToolCall.run(t, new Tool.Args(a), workspace);
        return new Result(c.text() == null ? "" : c.text(), c.failed(), c.outcome());
    }

    /** Runs a tool with its arguments as a JSON object, as a model gives them. */
    public Result callJson(String tool, String argsJson) {
        Object v = argsJson == null || argsJson.isBlank() ? Map.of() : logsift.core.Json.parseValue(argsJson, null);
        if (!(v instanceof Map<?, ?> m)) {
            return new Result("The arguments must be a JSON object, like {\"log\": \"app.log\"}.", true, "refused");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) m;
        return call(tool, args);
    }

    /** Instructions for the host's model: which tool for what, with examples and rules. */
    public String guide() {
        return logsift.agent.Guide.text(ToolCall.offered(all, workspace), workspace.catalog(), null);
    }

    /** Turns the tools that change something on or off from now. */
    public void setChangesAllowed(boolean allowed) {
        workspace.setChangesAllowed(allowed);
    }

    public boolean changesAllowed() {
        return workspace.changesAllowed();
    }

    /**
     * Speaks MCP (JSON-RPC, one message per line) on these streams until {@code in} ends, with
     * this instance's tools and settings: for a host that talks MCP to its tools over pipes.
     */
    public void serveMcp(InputStream in, OutputStream out) throws java.io.IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        McpServer server = new McpServer(all, workspace, logsift.core.Version.current(), w);
        server.serve(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        w.flush();
    }

    @Override
    public void close() {
        workspace.close();
    }
}
