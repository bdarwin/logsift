package logsift.agent.mcp;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import logsift.agent.AgentSettings;
import logsift.agent.LogTools;
import logsift.agent.Masking;
import logsift.agent.Scope;
import logsift.agent.Workspace;

/**
 * {@code logsift-mcp}: Logsift's tools for coding agents (GitHub Copilot, and any agent that
 * speaks the Model Context Protocol), over standard input and output. No window; read-only.
 *
 * <pre>
 * logsift-mcp [--root FOLDER]... [--mask-emails] [--mask-ips] [--no-mask] [--max-chars N]
 * </pre>
 */
public final class McpMain {
    private McpMain() {}

    public static void main(String[] args) throws Exception {
        logsift.core.Channel.apply();
        System.setProperty("java.awt.headless", "true");
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("-v"))) {
            System.out.println("logsift-mcp " + logsift.core.Version.current());
            return;
        }
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(USAGE);
            System.out.println("Speaks the Model Context Protocol on standard input and output: add it to your coding "
                    + "agent as a stdio MCP server.");
            return;
        }
        // standard output carries the protocol only: anything else printed goes to standard error
        Writer protocol = new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        Workspace.Options options;
        try {
            options = options(args, home(), Path.of("").toAbsolutePath());
        } catch (IllegalArgumentException e) {
            System.err.println("logsift-mcp: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        System.err.println("logsift-mcp " + logsift.core.Version.current() + ": reading under "
                + options.scope().describe() + "; masking " + options.masking().describe());
        logsift.core.Numbers.setOptions(logsift.core.Numbers.Options.load(home()));
        List<Path> logFiles = logFiles(args);
        Path cwd = Path.of("").toAbsolutePath();
        Path home = home();
        try (Workspace w = new Workspace(options)) {
            w.setCatalog(() -> logsift.agent.LogCatalog.load(home, cwd, logFiles));
            new McpServer(LogTools.all(), w, logsift.core.Version.current(), protocol)
                    .serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        }
        System.exit(0);
    }

    static final String USAGE = """
            usage: logsift-mcp [--root FOLDER]... [--mask-emails] [--mask-ips] [--mask REGEX]... [--no-mask] \
            [--max-chars N] [--logs FILE]...
              --root FOLDER   allow reading under FOLDER (default: your home folder and the current folder)
              --mask-emails   also mask email addresses; --mask-ips: IP addresses; --mask REGEX: your own
              --no-mask       don't mask secrets (passwords, tokens, keys) in answers
              --logs FILE     also read log locations from FILE (like a project's .logsift/logs.json)""";

    /** The --logs files. */
    static List<Path> logFiles(String[] args) {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--logs")) {
                out.add(Path.of(args[i + 1]).toAbsolutePath());
            }
        }
        return out;
    }

    /** Logsift's own folder (settings, the audit log). */
    static Path home() {
        String h = System.getProperty("logsift.home");
        return h != null ? Path.of(h) : Path.of(System.getProperty("user.home"), ".logsift");
    }

    /**
     * The options from the command line and {@code agent.properties} in Logsift's folder (the
     * command line wins).
     */
    static Workspace.Options options(String[] args, Path logsiftHome, Path cwd) {
        AgentSettings settings = AgentSettings.load(logsiftHome);
        boolean secrets = settings.masking().secrets();
        boolean emails = settings.masking().emails();
        boolean ips = settings.masking().ips();
        List<String> custom = new ArrayList<>(settings.masking().custom());
        List<Path> roots = new ArrayList<>(settings.roots());
        int maxChars = Workspace.DEFAULT_MAX_CHARS;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--root" -> roots.add(Path.of(value(args, ++i, a)));
                case "--mask-emails" -> emails = true;
                case "--mask-ips" -> ips = true;
                case "--mask" -> custom.add(value(args, ++i, a));
                case "--no-mask" -> secrets = false;
                case "--logs" -> value(args, ++i, a); // read by logFiles
                case "--max-chars" -> {
                    try {
                        maxChars = Integer.parseInt(value(args, ++i, a));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--max-chars needs a number");
                    }
                }
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        if (roots.isEmpty()) {
            roots.add(userHome);
            if (!cwd.startsWith(userHome)) {
                roots.add(cwd);
            }
        }
        Masking.Options masking = new Masking.Options(secrets, emails, ips, custom);
        return new Workspace.Options(new Scope(roots, userHome), masking,
                Math.max(500, Math.min(maxChars, Workspace.HARD_MAX_CHARS)), 25_000,
                logsift.core.CustomFormat.load(logsiftHome.resolve("formats.properties")),
                logsiftHome.resolve("agent.log"), logsiftHome);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
