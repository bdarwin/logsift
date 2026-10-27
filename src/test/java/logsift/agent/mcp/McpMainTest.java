package logsift.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import logsift.agent.Workspace;
import logsift.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code logsift-mcp} as an agent starts it: a process speaking JSON on standard input and output. */
class McpMainTest {
    @TempDir
    Path dir;

    @Test
    void speaksOnlyTheProtocolOnStandardOutput() throws Exception {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        Path home = Files.createDirectories(dir.resolve("logsift-home"));
        Path log = logs.resolve("app.log");
        Files.writeString(log, "2026-10-21 10:00:00.000 ERROR [main] App - Payment declined, token=abc123secret\n"
                + "2026-10-21 10:00:01.000 INFO [main] App - ok\n");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // on exactly the Java modules the installed app has
        java.util.regex.Matcher modules = java.util.regex.Pattern.compile("--add-modules\\s+(\\S+)")
                .matcher(Files.readString(Path.of(".github/workflows/snapshot.yml")));
        assertTrue(modules.find(), "the module list");
        Process p = new ProcessBuilder(javaBin, "--limit-modules", modules.group(1),
                "-cp", System.getProperty("java.class.path"),
                "-Dlogsift.home=" + home, "-Duser.home=" + dir.resolve("user"),
                "logsift.agent.mcp.McpMain", "--root", logs.toString())
                .redirectError(dir.resolve("stderr.txt").toFile()).start();
        OutputStream in = p.getOutputStream();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String requests = String.join("\n",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"copilot-test\",\"version\":\"1\"}}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"search\","
                            + "\"arguments\":{\"log\":\"" + log.toString().replace("\\", "\\\\") + "\",\"query\":\"Payment\"}}}",
                    "");
            in.write(requests.getBytes(StandardCharsets.UTF_8));
            in.flush();
            List<Map<String, Object>> answers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String line = out.readLine();
                assertNotNull(line, "answer " + (i + 1) + "; stderr: " + Files.readString(dir.resolve("stderr.txt")));
                Map<String, Object> m = Json.parseObject(line);
                assertNotNull(m, "only JSON on standard output: " + line);
                answers.add(m);
            }
            in.close(); // the agent goes away: the server ends
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "ends when its input ends");
            assertEquals(0, p.exitValue());
            assertEquals(null, out.readLine(), "nothing more on standard output");

            String search = answers.get(2).toString();
            assertTrue(search.contains("1 line matches “Payment”"), search);
            assertTrue(search.contains("token=‹masked:secret›"), search);
            assertFalse(search.contains("abc123secret"), search);
        } finally {
            p.destroyForcibly();
        }
        String err = Files.readString(dir.resolve("stderr.txt"));
        assertTrue(err.startsWith("logsift-mcp "), err);
        String audit = Files.readString(home.resolve("agent.log"));
        assertTrue(audit.contains("copilot-test 1\tsearch\t"), audit);
        assertFalse(Files.exists(dir.resolve("user").resolve(".logsift")), "the real home's settings untouched");
    }

    @Test
    void readsItsOptions() throws Exception {
        Path home = Files.createDirectories(dir.resolve("logsift-home"));
        new logsift.agent.AgentSettings(new logsift.agent.Masking.Options(true, true, false, List.of("cust-\\d+")),
                List.of(dir.resolve("a"))).save(home);
        Workspace.Options o = McpMain.options(new String[] {"--root", dir.resolve("b").toString(), "--mask-ips",
                "--max-chars", "9000"}, home, dir);
        assertTrue(o.masking().secrets() && o.masking().emails() && o.masking().ips());
        assertEquals(List.of("cust-\\d+"), o.masking().custom());
        assertEquals(9000, o.maxChars());
        assertEquals(2, o.scope().roots().size());
        assertEquals(home.resolve("agent.log"), o.auditLog());

        Workspace.Options plain = McpMain.options(new String[] {"--no-mask"}, dir.resolve("none"), dir);
        assertFalse(plain.masking().secrets());
        assertFalse(plain.scope().roots().isEmpty(), "home (and here) by default");

        assertThrows(IllegalArgumentException.class, () -> McpMain.options(new String[] {"--root"}, home, dir));
        assertThrows(IllegalArgumentException.class, () -> McpMain.options(new String[] {"--delete"}, home, dir));
    }
}
