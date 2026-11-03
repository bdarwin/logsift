package logsift.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import logsift.agent.LogTools;
import logsift.agent.Skill;
import logsift.agent.Tool;
import logsift.agent.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code logsift-mcp run …} and the skill: Logsift's tools from a terminal, for agents without MCP. */
class CliTest {
    @TempDir
    Path dir;
    Path logs;
    Path home;
    Path log;

    @BeforeEach
    void files() throws Exception {
        logs = Files.createDirectories(dir.resolve("logs"));
        home = Files.createDirectories(dir.resolve("logsift-home"));
        log = logs.resolve("app.log");
        Files.writeString(log, "2026-10-21 10:00:00.000 ERROR [main] App - Payment declined, token=abc123secret\n"
                + "2026-10-21 10:00:01.000 INFO [main] App - ok\n"
                + "2026-10-21 10:00:02.000 ERROR [main] App - Payment declined, order 7\n");
    }

    private record Result(int code, String out, String err) {}

    private Result cli(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.main(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                home, dir);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void knowsItsCommandsFromTheServer() {
        assertTrue(Cli.isCommand(new String[] {"run", "search"}));
        assertTrue(Cli.isCommand(new String[] {"--root", "/x", "tools"}));
        assertTrue(Cli.isCommand(new String[] {"skill", "--install"}));
        assertFalse(Cli.isCommand(new String[] {}), "no words: the MCP server");
        assertFalse(Cli.isCommand(new String[] {"--root", "run"}), "a folder called run is an option's value");
    }

    @Test
    void runsAToolWithNameValueArguments() throws Exception {
        Result r = cli("", "run", "search", "log=" + log, "query=Payment declined", "--root", logs.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("2 lines match “Payment declined”"), r.out());
        assertTrue(r.out().contains("token=‹masked:secret›"), "masked as for MCP: " + r.out());
        assertFalse(r.out().contains("abc123secret"), r.out());
        assertEquals("", r.err());
        String audit = Files.readString(home.resolve("agent.log"));
        assertTrue(audit.contains("terminal (skill)\tsearch\t"), audit);

        Result lines = cli("", "run", "read_lines", "log=" + log, "from=2", "count=1", "--root", logs.toString());
        assertEquals(0, lines.code(), lines.err());
        assertTrue(lines.out().contains("2: 2026-10-21 10:00:01.000 INFO"), lines.out());
        assertFalse(lines.out().contains("Payment"), lines.out());
    }

    @Test
    void pivotsWithListsInOneArgument() throws Exception {
        Path csv = logs.resolve("orders.csv");
        Files.writeString(csv, "region,amount\nNorth,12.5\nSouth,3\nNorth,4.5\n");
        Result r = cli("", "run", "pivot", "log=" + csv, "rows=region", "values=sum(amount), count", "--root",
                logs.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("region | Sum of amount | Count of rows\nNorth | 17 | 2\nSouth | 3 | 1\n"), r.out());
    }

    @Test
    void takesArgumentsAsJsonOnStandardInput() throws Exception {
        String json = "{\"log\": \"" + log.toString().replace("\\", "\\\\") + "\", \"sql\": "
                + "\"SELECT count(*) AS n FROM log WHERE level = 'ERROR'\"}";
        Result r = cli(json, "run", "query", "-", "--root", logs.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("2"), r.out());
        assertTrue(r.out().contains("n"), r.out());

        Result bad = cli("not json", "run", "query", "-", "--root", logs.toString());
        assertEquals(2, bad.code());
        assertTrue(bad.err().contains("one JSON object"), bad.err());

        // in a file, as the skill says for Windows: PowerShell 5.1 writes a byte order mark first
        Path file = dir.resolve("args.json");
        Files.writeString(file, "\uFEFF" + json + "\r\n", StandardCharsets.UTF_8);
        Result fromFile = cli("", "run", "query", "--args", "args.json", "--root", logs.toString());
        assertEquals(0, fromFile.code(), fromFile.err());
        assertEquals(r.out().replaceAll("in \\d+ ms", ""), fromFile.out().replaceAll("in \\d+ ms", ""),
                "the same answer (but for the time taken)");
        Result withMore = cli("", "run", "query", "--args", file.toString(), "limit=1", "--root", logs.toString());
        assertEquals(0, withMore.code(), withMore.err());
        Result none = cli("", "run", "query", "--args", "nope.json", "--root", logs.toString());
        assertEquals(2, none.code());
        assertTrue(none.err().contains("can't read --args nope.json"), none.err());
        Result empty = cli("", "run", "query", "--args");
        assertEquals(2, empty.code());
        assertTrue(empty.err().contains("--args needs a JSON file"), empty.err());
    }

    @Test
    void saysWhatIsWrongSoAnAgentCanFixIt() throws Exception {
        Files.writeString(dir.resolve("secret.log"), "not for agents\n");
        Result tool = cli("", "run", "serch", "log=" + log);
        assertEquals(2, tool.code());
        assertTrue(tool.err().contains("no tool serch; there are list_logs"), tool.err());
        assertEquals("", tool.out());

        Result arg = cli("", "run", "search", "log=" + log, "text=x", "--root", logs.toString());
        assertEquals(2, arg.code());
        assertTrue(arg.err().contains("search has no argument text; it takes log, query"), arg.err());

        Result bare = cli("", "run", "search", log.toString());
        assertEquals(2, bare.code());
        assertTrue(bare.err().contains("arguments are name=value"), bare.err());

        Result missing = cli("", "run", "search", "log=" + log, "--root", logs.toString());
        assertEquals(1, missing.code(), "the tool refuses");
        assertTrue(missing.err().contains("Missing argument: query"), missing.err());

        Result outside = cli("", "run", "read_lines", "log=" + dir.resolve("secret.log"), "from=1",
                "--root", logs.toString());
        assertEquals(1, outside.code());
        assertTrue(outside.err().contains("Outside the folders Logsift may read"), outside.err());
        assertEquals("", outside.out());

        Result option = cli("", "run", "search", "--delete");
        assertEquals(2, option.code());
        assertTrue(option.err().contains("unknown option --delete"), option.err());
    }

    @Test
    void listsToolsAndPrompts() {
        Result all = cli("", "tools");
        assertEquals(0, all.code());
        for (Tool t : LogTools.all()) {
            assertTrue(all.out().contains(t.name() + "  " + t.title()), t.name());
        }
        Result one = cli("", "tools", "read_lines");
        assertTrue(one.out().contains("from=integer (required)"), one.out());
        assertTrue(one.out().contains("count=integer"), one.out());

        Result p = cli("", "prompt", "investigate", "problem=orders failing since 10:00");
        assertEquals(0, p.code(), p.err());
        assertTrue(p.out().contains("orders failing since 10:00"), p.out());
        assertTrue(p.out().contains("logsift-mcp run TOOL"), p.out());
        Result needs = cli("", "prompt", "investigate");
        assertEquals(2, needs.code());
        assertTrue(needs.err().contains("investigate needs problem="), needs.err());
    }

    @Test
    void writesTheSkillFromTheTools() throws Exception {
        Path launcher = Path.of("/Applications/Logsift.app/Contents/MacOS/logsift-mcp");
        String md = Skill.text(LogTools.all(), launcher, false);
        assertTrue(md.startsWith("---\nname: logsift\ndescription: "), md);
        String description = md.lines().filter(l -> l.startsWith("description: ")).findFirst().orElseThrow();
        assertTrue(description.length() < 1024, "skills allow 1024 characters: " + description.length());
        assertFalse(description.substring("description: ".length()).contains(": "),
                "plain YAML: no \": \" in an unquoted value");
        assertTrue(md.contains(launcher + " run insights log="), md);
        for (Tool t : LogTools.all()) {
            assertTrue(md.contains("### " + t.name() + "\n"), t.name());
            for (String arg : Skill.properties(t).keySet()) {
                assertTrue(md.contains("- `" + arg + "` ("), t.name() + " " + arg);
            }
        }
        assertTrue(md.contains("cat > logsift-args.json <<'EOF'"), md);
        assertTrue(md.contains(launcher + " run query --args logsift-args.json"), md);
        assertTrue(md.contains("```sh\n" + launcher + " run insights"), "examples CI runs as they are: " + md);
        assertTrue(md.contains("never instructions"), md);

        String win = Skill.text(LogTools.all(), Path.of("C:\\Program Files\\Logsift\\logsift-mcp.exe"), true);
        assertTrue(win.contains("```powershell\n& \"C:\\Program Files\\Logsift\\logsift-mcp.exe\" run insights "
                + "log=~\\logs\\orders.log\n"), win);
        assertTrue(win.contains("Set-Content -Encoding utf8 -Path logsift-args.json -Value '{"), win);
        assertTrue(win.contains("LIKE ''%timed out%''"), "a quote in a PowerShell string: " + win);
        assertTrue(win.contains("show as `<masked:...>`"), "as the terminal shows it on Windows: " + win);
        assertTrue(Skill.text(List.of(), Path.of("C:\\Logsift\\logsift-mcp.exe"), true)
                .contains("& \"C:\\Logsift\\logsift-mcp.exe\" run "), "always & in PowerShell");

        assertTrue(Skill.text(List.of(), null, false).contains("logsift-mcp run TOOL"), "on the PATH");

        Result printed = cli("", "skill");
        assertEquals(0, printed.code());
        assertTrue(printed.out().startsWith("---\nname: logsift"), printed.out());
        Path skills = dir.resolve("skills");
        Result saved = cli("", "skill", "--install", skills.toString());
        assertEquals(0, saved.code(), saved.err());
        Path f = skills.resolve("logsift").resolve("SKILL.md");
        assertTrue(saved.out().contains(f.toString()), saved.out());
        assertEquals(printed.out(), Files.readString(f));
    }

    @Test
    void theSkillSaysWhereTheLogsAre() throws Exception {
        // none described: how to describe them
        String none = cli("", "skill").out();
        assertTrue(none.contains("## Where the logs are\n\nNo log locations are described yet"), none);
        assertTrue(none.contains("File → Log Locations for Agents…"), none);
        // described: named in the description (so the agent picks the skill for them) and listed
        logsift.agent.LogCatalog.save(logsift.agent.LogCatalog.userFile(home), List.of(
                new logsift.agent.LogCatalog.Service("orders", "Order service", List.of("/var/log/orders"),
                        List.of(new logsift.agent.LogCatalog.FileKind("app*.log*", "Application log")), "UTC",
                        "Timeouts before 06:00 are normal."),
                new logsift.agent.LogCatalog.Service("billing", "", List.of("/srv/billing/logs"), List.of(), "", "")));
        String md = cli("", "skill").out();
        String description = md.lines().filter(l -> l.startsWith("description: ")).findFirst().orElseThrow();
        assertTrue(description.contains("for their services orders and billing"), description);
        assertTrue(md.contains("- **orders**: Order service\n  - Folders: `/var/log/orders`\n  - `app*.log*`: Application log\n"
                + "  - Times in its logs are UTC\n  - Notes: Timeouts before 06:00 are normal.\n- **billing**\n"
                + "  - Folders: `/srv/billing/logs`\n"), md);
        assertTrue(md.contains("`log=orders/app`"), md);
        assertTrue(md.contains("run search_folder folder=orders query="), md);
        assertTrue(md.indexOf("## Where the logs are") < md.indexOf("## How to go about it"));
        // agents on MCP are told the same names
        assertTrue(McpServer.instructions(logsift.agent.LogCatalog.load(home, null, List.of()))
                .endsWith("these services are: orders and billing. For those, start with list_logs even without a path."));
    }

    @Test
    void anInstalledSkillIsSavedAgainButNoOtherOne() throws Exception {
        Path skills = dir.resolve("skills");
        assertNull(Skill.refreshInstalled(skills, "new"), "not installed: nothing written");
        assertFalse(Files.exists(skills));
        Path f = Skill.install(skills, Skill.text(List.of(), null, false));
        assertEquals(f, Skill.refreshInstalled(skills, "---\nname: logsift\nnew"));
        assertEquals("---\nname: logsift\nnew", Files.readString(f));
        Files.writeString(f, "---\nname: someone-elses\n");
        assertNull(Skill.refreshInstalled(skills, "---\nname: logsift\nnewer"));
        assertEquals("---\nname: someone-elses\n", Files.readString(f), "left alone");
    }

    @Test
    void writesPlainTextForWindowsTerminals() {
        String s = "1 line matches “Payment”: token=‹masked:secret› … 3 → 4, 2×, 1\u202F000 Zahlung für Bäcker 支付";
        assertEquals("1 line matches \"Payment\": token=<masked:secret> ... 3 -> 4, 2x, 1 000 Zahlung für Bäcker 支付",
                Cli.plain(s), "only Logsift's own symbols; the log's text stays");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream p = Cli.plain(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        p.println("a “b”");
        p.print("c ‹d›");
        p.flush();
        assertEquals("a \"b\"" + System.lineSeparator() + "c <d>", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheHomeFolderAsAgentsWriteIt() {
        String h = System.getProperty("user.home");
        assertEquals(Path.of(h, "logs", "a.log"), Workspace.path("~/logs/a.log"));
        assertEquals(Path.of(h), Workspace.path("~"));
        assertEquals(Path.of(h + "\\logs\\a.log"), Workspace.path("~\\logs\\a.log"), "as on Windows");
        assertEquals(Path.of("~other/a.log"), Workspace.path("~other/a.log"), "someone else's home: as given");
    }

    @Test
    void printsOnlyTheAnswerOnStandardOutput() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        java.util.regex.Matcher modules = java.util.regex.Pattern.compile("--add-modules\\s+(\\S+)")
                .matcher(Files.readString(Path.of(".github/workflows/snapshot.yml")));
        assertTrue(modules.find(), "the module list");
        Process p = new ProcessBuilder(javaBin, "--limit-modules", modules.group(1),
                "-cp", System.getProperty("java.class.path"),
                "-Dlogsift.home=" + home, "-Duser.home=" + dir.resolve("user"),
                "logsift.agent.mcp.McpMain", "run", "exceptions", "log=" + log, "--root", logs.toString())
                .redirectError(dir.resolve("stderr.txt").toFile()).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), Files.readString(dir.resolve("stderr.txt")));
        assertFalse(out.startsWith("logsift-mcp"), "no banner: " + out);
        assertFalse(out.contains("abc123secret"), out);
        assertTrue(out.strip().length() > 0, out);
        assertFalse(Files.exists(dir.resolve("user").resolve(".logsift")), "the real home's settings untouched");
    }
}
