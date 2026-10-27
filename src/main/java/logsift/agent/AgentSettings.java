package logsift.agent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * What the user chose for coding agents (Settings → Agents), kept in {@code agent.properties}
 * in Logsift's folder, and how to set up each agent to start {@code logsift-mcp}.
 *
 * @param masking what is hidden in answers
 * @param roots   folders agents may read (empty: the home folder)
 */
public record AgentSettings(Masking.Options masking, List<Path> roots) {

    public static final AgentSettings DEFAULT = new AgentSettings(Masking.Options.DEFAULT, List.of());

    public AgentSettings {
        roots = List.copyOf(roots);
    }

    public static Path file(Path logsiftHome) {
        return logsiftHome.resolve("agent.properties");
    }

    /** The settings in Logsift's folder (the defaults if there are none, or they can't be read). */
    public static AgentSettings load(Path logsiftHome) {
        Path f = file(logsiftHome);
        if (!Files.isRegularFile(f)) {
            return DEFAULT;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            return DEFAULT;
        }
        List<String> custom = new ArrayList<>();
        for (int i = 1; p.getProperty("mask.custom." + i) != null; i++) {
            custom.add(p.getProperty("mask.custom." + i));
        }
        List<Path> roots = new ArrayList<>();
        for (int i = 1; p.getProperty("root." + i) != null; i++) {
            roots.add(Path.of(p.getProperty("root." + i)));
        }
        return new AgentSettings(new Masking.Options(!Boolean.parseBoolean(p.getProperty("mask.off", "false")),
                Boolean.parseBoolean(p.getProperty("mask.emails", "false")),
                Boolean.parseBoolean(p.getProperty("mask.ips", "false")), custom), roots);
    }

    public void save(Path logsiftHome) throws IOException {
        Properties p = new Properties();
        p.setProperty("mask.off", String.valueOf(!masking.secrets()));
        p.setProperty("mask.emails", String.valueOf(masking.emails()));
        p.setProperty("mask.ips", String.valueOf(masking.ips()));
        for (int i = 0; i < masking.custom().size(); i++) {
            p.setProperty("mask.custom." + (i + 1), masking.custom().get(i));
        }
        for (int i = 0; i < roots.size(); i++) {
            p.setProperty("root." + (i + 1), roots.get(i).toString());
        }
        Files.createDirectories(logsiftHome);
        try (Writer out = Files.newBufferedWriter(file(logsiftHome), StandardCharsets.UTF_8)) {
            p.store(out, "Logsift for coding agents (Settings → Agents). Read when an agent starts logsift-mcp.");
        }
    }

    // ---------------------------------------------------------------- setting up an agent

    /** The agents Logsift says how to set up. */
    public enum Agent {
        VSCODE("VS Code (GitHub Copilot)"),
        COPILOT_CLI("GitHub Copilot CLI"),
        VISUAL_STUDIO("Visual Studio, JetBrains IDEs (GitHub Copilot)"),
        OTHER("Other agents (MCP)");

        private final String label;

        Agent(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * {@code logsift-mcp} next to the running app: in the installed app (next to the Logsift
     * launcher), or in the zip that runs on any Java ({@code bin/}). Null when running from a build.
     */
    public static Path launcher() {
        String forced = System.getProperty("logsift.mcpLauncher"); // tests
        if (forced != null) {
            return Path.of(forced);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        String app = System.getProperty("jpackage.app-path");
        if (app != null) {
            Path c = Path.of(app).toAbsolutePath().getParent().resolve(windows ? "logsift-mcp.exe" : "logsift-mcp");
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        try {
            Path jar = Path.of(AgentSettings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(jar) && jar.getParent() != null && jar.getParent().getParent() != null) {
                Path c = jar.getParent().getParent().resolve("bin").resolve(windows ? "logsift-mcp.bat" : "logsift-mcp");
                if (Files.isRegularFile(c)) {
                    return c;
                }
            }
        } catch (Exception e) {
            // not from a jar
        }
        return null;
    }

    /** Where the setup goes, for people. */
    public static String where(Agent a) {
        return switch (a) {
            case VSCODE -> "Add to .vscode/mcp.json in your project, or to your own mcp.json (Command Palette → "
                    + "MCP: Open User Configuration). Then pick Agent mode in Copilot Chat; Logsift's tools are "
                    + "under the tools button.";
            case COPILOT_CLI -> "Add to ~/.copilot/mcp-config.json (merge with what is there), then start "
                    + "copilot again.";
            case VISUAL_STUDIO -> "Visual Studio: %USERPROFILE%\\.mcp.json, or Chat → Agent → Tools → + . "
                    + "JetBrains: Settings → GitHub Copilot → MCP → Edit in mcp.json.";
            case OTHER -> "Add a local (stdio) MCP server called logsift that runs this command, with no arguments.";
        };
    }

    /** The text to paste for an agent: config for {@code launcher}. */
    public static String snippet(Agent a, Path launcher) {
        String cmd = JsonWriter.write(launcher.toString());
        return switch (a) {
            case VSCODE, VISUAL_STUDIO -> """
                    {
                      "servers": {
                        "logsift": {
                          "type": "stdio",
                          "command": %s
                        }
                      }
                    }""".formatted(cmd);
            case COPILOT_CLI -> """
                    {
                      "mcpServers": {
                        "logsift": {
                          "type": "local",
                          "command": %s,
                          "args": [],
                          "tools": ["*"]
                        }
                      }
                    }""".formatted(cmd);
            case OTHER -> launcher.toString();
        };
    }
}
