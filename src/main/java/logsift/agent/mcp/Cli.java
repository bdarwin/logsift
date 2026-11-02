package logsift.agent.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import logsift.agent.AgentSettings;
import logsift.agent.LogTools;
import logsift.agent.Prompts;
import logsift.agent.Skill;
import logsift.agent.Tool;
import logsift.agent.Workspace;
import logsift.core.Json;

/**
 * {@code logsift-mcp} from a terminal, for agents that can't use MCP servers (a skill tells them
 * how): one tool per run, with the same masking, folders and audit log.
 *
 * <pre>
 * logsift-mcp run TOOL [name=value]... [-] [--args FILE]   one tool; arguments also as JSON on standard input (-) or in FILE
 * logsift-mcp tools [TOOL]                     the tools, or one tool's arguments
 * logsift-mcp prompt NAME [name=value]...      a ready-made task's steps
 * logsift-mcp skill [--install [DIR]]          the skill (SKILL.md); --install saves it in DIR/logsift
 * </pre>
 */
final class Cli {
    private Cli() {}

    static final Set<String> COMMANDS = Set.of("run", "tools", "prompt", "skill");
    /** Options followed by a value. */
    private static final Set<String> WITH_VALUE = Set.of("--root", "--mask", "--max-chars", "--logs");

    static final String USAGE = """
            usage: logsift-mcp run TOOL [name=value]... [--args FILE] [-]
                                                              run one tool; arguments as JSON in FILE, or on standard input (-)
                   logsift-mcp tools [TOOL]                   list the tools, or show one tool's arguments
                   logsift-mcp prompt NAME [name=value]...    print a ready-made task's steps
                   logsift-mcp skill [--install [DIR]]        print the agent skill, or save it in DIR/logsift/SKILL.md
                                                              (default DIR: ~/.copilot/skills)""";

    /** Whether the command line asks for a terminal command rather than the MCP server. */
    static boolean isCommand(String[] args) {
        List<String> words;
        try {
            words = split(args).words;
        } catch (IllegalArgumentException e) {
            return true; // a terminal command with a mistake: said by main
        }
        return !words.isEmpty() && COMMANDS.contains(words.get(0));
    }

    private record Split(List<String> words, List<String> options, String install, List<String> argFiles) {}

    /** The words (command, tool, name=value) apart from the options. */
    private static Split split(String[] args) {
        List<String> words = new ArrayList<>();
        List<String> options = new ArrayList<>();
        String install = null;
        List<String> argFiles = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--args")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--args needs a JSON file");
                }
                argFiles.add(args[++i]);
            } else if (a.equals("--install")) {
                boolean dir = i + 1 < args.length && !args[i + 1].startsWith("-") && !args[i + 1].contains("=");
                install = dir ? args[++i] : "";
            } else if (a.startsWith("--")) {
                options.add(a);
                if (WITH_VALUE.contains(a) && i + 1 < args.length) {
                    options.add(args[++i]);
                }
            } else {
                words.add(a);
            }
        }
        return new Split(words, options, install, argFiles);
    }

    /** Runs the command and gives the exit code: 0 done, 1 the tool refused or failed, 2 a wrong command line. */
    static int main(String[] args, InputStream in, PrintStream out, PrintStream err, Path logsiftHome, Path cwd,
                    List<Tool> tools) {
        Split s;
        try {
            s = split(args);
        } catch (IllegalArgumentException e) {
            err.println("logsift-mcp: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
        if (s.words.isEmpty()) {
            err.println(USAGE);
            return 2;
        }
        String command = s.words.get(0);
        List<String> rest = s.words.subList(1, s.words.size());
        try {
            return switch (command) {
                case "tools" -> tools(rest, tools, out, err);
                case "prompt" -> prompt(rest, out, err);
                case "skill" -> skill(s.install, tools, out, err);
                default -> run(rest, s.options, s.argFiles, in, out, err, logsiftHome, cwd, tools);
            };
        } catch (IllegalArgumentException e) {
            err.println("logsift-mcp: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
    }

    static int main(String[] args, InputStream in, PrintStream out, PrintStream err, Path logsiftHome, Path cwd) {
        return main(args, in, out, err, logsiftHome, cwd, LogTools.all());
    }

    // ---------------------------------------------------------------- run

    private static int run(List<String> words, List<String> optionArgs, List<String> argFiles, InputStream in,
                           PrintStream out, PrintStream err, Path logsiftHome, Path cwd, List<Tool> tools) {
        if (words.isEmpty()) {
            throw new IllegalArgumentException("run needs a tool: " + names(tools));
        }
        Tool tool = find(tools, words.get(0));
        Map<String, Object> args = new LinkedHashMap<>();
        for (String f : argFiles) {
            Path file = cwd.resolve(logsift.agent.Workspace.path(f));
            try {
                args.putAll(jsonArgs(java.nio.file.Files.readString(file, StandardCharsets.UTF_8), "--args " + f));
            } catch (IOException e) {
                throw new IllegalArgumentException("can't read --args " + f + ": " + e.getMessage());
            }
        }
        for (String w : words.subList(1, words.size())) {
            if (w.equals("-")) {
                args.putAll(stdinArgs(in));
                continue;
            }
            int eq = w.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("arguments are name=value, not " + w + " (" + tool.name()
                        + " takes " + String.join(", ", Skill.properties(tool).keySet()) + ")");
            }
            args.put(w.substring(0, eq), w.substring(eq + 1));
        }
        for (String name : args.keySet()) {
            if (!Skill.properties(tool).containsKey(name)) {
                throw new IllegalArgumentException(tool.name() + " has no argument " + name + "; it takes "
                        + String.join(", ", Skill.properties(tool).keySet()));
            }
        }
        Workspace.Options o = McpMain.options(optionArgs.toArray(String[]::new), logsiftHome, cwd);
        // a terminal waits for slow work rather than being told to ask again
        o = new Workspace.Options(o.scope(), o.masking(), o.maxChars(), WAIT_MILLIS, o.formats(), o.auditLog(),
                o.logsiftHome());
        List<Path> logFiles = McpMain.logFiles(optionArgs.toArray(String[]::new));
        Tool.Args a = new Tool.Args(args);
        try (Workspace w = new Workspace(o)) {
            w.setClient("terminal (skill)");
            w.setCatalog(() -> logsift.agent.LogCatalog.load(logsiftHome, cwd, logFiles));
            String text;
            try {
                text = tool.run(a, w);
            } catch (Workspace.StillWorking e) {
                w.audit(tool.name(), a.brief(), 0, "still working");
                err.println(e.getMessage().replace("Call the same tool again in a few seconds.",
                        "Run the same command again."));
                return 3;
            } catch (Tool.Failure | IOException e) {
                String m = w.masking().mask(String.valueOf(e.getMessage()), null);
                w.audit(tool.name(), a.brief(), m.length(), "refused");
                err.println(m);
                return 1;
            } catch (Exception | Error e) {
                String m = w.masking().mask("Logsift failed: " + e, null);
                w.audit(tool.name(), a.brief(), m.length(), "failed");
                err.println(m);
                return 1;
            }
            w.audit(tool.name(), a.brief(), text.length(), "ok");
            out.println(text);
            out.flush();
            return 0;
        }
    }

    /** How long one run waits for slow work. */
    static final long WAIT_MILLIS = 15 * 60_000;

    private static Map<String, Object> stdinArgs(InputStream in) {
        String text;
        try {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("can't read standard input: " + e.getMessage());
        }
        return jsonArgs(text, "with -, standard input");
    }

    /** One JSON object of arguments (a byte order mark first, as Windows PowerShell writes, is fine). */
    private static Map<String, Object> jsonArgs(String text, String where) {
        String t = text.strip();
        if (t.startsWith("\uFEFF")) {
            t = t.substring(1).strip();
        }
        Object v = Json.parseValue(t, Cli.class);
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(where + " must be one JSON object, like "
                    + "{\"log\": \"app.log\", \"sql\": \"SELECT …\"}");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, val) -> out.put(String.valueOf(k), val));
        return out;
    }

    // ---------------------------------------------------------------- tools, prompt, skill

    private static int tools(List<String> words, List<Tool> tools, PrintStream out, PrintStream err) {
        if (words.isEmpty()) {
            for (Tool t : tools) {
                out.println(t.name() + "  " + t.title());
            }
            out.println();
            out.println("logsift-mcp tools NAME shows what one does and its arguments.");
            return 0;
        }
        Tool t = find(tools, words.get(0));
        out.println(t.name() + ": " + t.title());
        out.println();
        out.println(t.description().strip());
        out.println();
        List<String> required = Skill.required(t);
        for (Map.Entry<String, Map<String, Object>> a : Skill.properties(t).entrySet()) {
            Object values = a.getValue().get("enum");
            out.println("  " + a.getKey() + "=" + a.getValue().get("type")
                    + (required.contains(a.getKey()) ? " (required)" : "")
                    + (values instanceof List<?> l ? " one of " + l : "")
                    + "  " + a.getValue().get("description"));
        }
        return 0;
    }

    private static int prompt(List<String> words, PrintStream out, PrintStream err) {
        List<String> names = Prompts.all().stream().map(Prompts.Prompt::name).toList();
        if (words.isEmpty()) {
            throw new IllegalArgumentException("prompt needs a name: " + String.join(", ", names));
        }
        Prompts.Prompt p = Prompts.all().stream().filter(x -> x.name().equals(words.get(0))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no prompt " + words.get(0) + "; there are "
                        + String.join(", ", names)));
        Map<String, String> args = new LinkedHashMap<>();
        for (String w : words.subList(1, words.size())) {
            int eq = w.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("arguments are name=value, not " + w);
            }
            args.put(w.substring(0, eq), w.substring(eq + 1));
        }
        for (Prompts.Arg a : p.args()) {
            if (a.required() && (args.get(a.name()) == null || args.get(a.name()).isBlank())) {
                throw new IllegalArgumentException(p.name() + " needs " + a.name() + "=… (" + a.description() + ")");
            }
        }
        out.println(p.text().text(args) + "\n\nRun each tool as: logsift-mcp run TOOL name=value…");
        return 0;
    }

    private static int skill(String install, List<Tool> tools, PrintStream out, PrintStream err) {
        String text = Skill.text(tools, AgentSettings.launcher(), Skill.windows());
        if (install == null) {
            out.print(text);
            return 0;
        }
        Path dir = install.isEmpty() ? Skill.personalDir() : Path.of(install).toAbsolutePath();
        try {
            Path f = Skill.install(dir, text);
            out.println("Saved the Logsift skill as " + f);
            return 0;
        } catch (IOException e) {
            err.println("logsift-mcp: can't save the skill in " + dir + ": " + e.getMessage());
            return 1;
        }
    }

    /**
     * Logsift's own symbols as plain ASCII, for a Windows terminal: PowerShell reads a program's
     * output in the console's code page (437, 850…), where “quotes”, ‹masked› and → aren't.
     */
    static String plain(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '“', '”', '„' -> b.append('"');
                case '‘', '’' -> b.append('\'');
                case '‹' -> b.append('<');
                case '›' -> b.append('>');
                case '…' -> b.append("...");
                case '→' -> b.append("->");
                case '←' -> b.append("<-");
                case '–', '—', '−' -> b.append('-');
                case '×' -> b.append('x');
                case '≈' -> b.append('~');
                case '≥' -> b.append(">=");
                case '≤' -> b.append("<=");
                case '•', '·' -> b.append('*');
                case '✓' -> b.append("ok");
                case '\u00A0', '\u202F', '\u2009' -> b.append(' ');
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** A stream that writes {@link #plain} text. */
    static PrintStream plain(PrintStream to) {
        return new PrintStream(new java.io.OutputStream() {
            private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

            @Override
            public void write(int b) {
                line.write(b);
                if (b == '\n') {
                    flush();
                }
            }

            @Override
            public void flush() {
                if (line.size() > 0) {
                    to.print(plain(line.toString(StandardCharsets.UTF_8)));
                    line.reset();
                }
                to.flush();
            }

            @Override
            public void close() {
                flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    private static Tool find(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no tool " + name + "; there are " + names(tools)));
    }

    private static String names(List<Tool> tools) {
        return String.join(", ", tools.stream().map(Tool::name).toList());
    }
}
