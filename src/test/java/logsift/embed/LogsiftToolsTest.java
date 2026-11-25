package logsift.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A program that embeds Logsift calls its tools in-process, as logsift-mcp offers them. */
class LogsiftToolsTest {
    @TempDir
    Path dir;
    Path logs;
    Path home;
    Path app;

    @BeforeEach
    void files() throws Exception {
        logs = Files.createDirectories(dir.resolve("logs"));
        home = Files.createDirectories(dir.resolve("home"));
        app = logs.resolve("app.log");
        Files.writeString(app, """
                2026-11-26 10:00:00 INFO  started
                2026-11-26 10:00:01 ERROR order 17 timed out
                2026-11-26 10:00:02 INFO  order 18 done
                2026-11-26 10:00:03 ERROR order 19 timed out
                """);
    }

    private LogsiftTools.Builder builder() {
        return LogsiftTools.builder().home(home).root(logs).workingDir(dir).waitMillis(30_000).client("test host");
    }

    @Test
    void callsToolsByNameWithAMapOrJson() {
        try (LogsiftTools t = builder().build()) {
            LogsiftTools.Result open = t.call("open_log", Map.of("path", app.toString()));
            assertFalse(open.error(), open.text());
            assertTrue(open.text().contains("log:1"), open.text());
            LogsiftTools.Result found = t.callJson("search", "{\"log\": \"log:1\", \"query\": \"timed out\"}");
            assertFalse(found.error(), found.text());
            assertTrue(found.text().startsWith("2 lines match"), found.text());
            assertTrue(found.text().contains("2: 2026-11-26 10:00:01 ERROR order 17 timed out"), found.text());
            assertEquals("ok", found.outcome());

            LogsiftTools.Result missing = t.call("search", Map.of("log", "log:1"));
            assertTrue(missing.error() && missing.text().contains("query"), missing.text());
            LogsiftTools.Result unknown = t.call("make_coffee", Map.of());
            assertTrue(unknown.error() && unknown.text().contains("search"), "names the tools: " + unknown.text());
            assertTrue(t.callJson("search", "[1, 2]").error(), "arguments must be an object");
        }
    }

    @Test
    void readsOnlyInsideItsFolders() throws Exception {
        Path outside = Files.writeString(dir.resolve("secret.log"), "2026-11-26 10:00:00 INFO no\n");
        try (LogsiftTools t = builder().build()) {
            LogsiftTools.Result r = t.call("open_log", Map.of("path", outside.toString()));
            assertTrue(r.error(), r.text());
        }
    }

    @Test
    void offersEveryToolWithItsSchemaAndTheGuideFirst() {
        try (LogsiftTools t = builder().build()) {
            List<LogsiftTools.ToolInfo> tools = t.tools();
            assertEquals("guide", tools.get(0).name());
            assertTrue(tools.stream().anyMatch(x -> x.name().equals("search")));
            assertTrue(tools.stream().noneMatch(LogsiftTools.ToolInfo::changesThings), "changes off by default");
            LogsiftTools.ToolInfo search = tools.stream().filter(x -> x.name().equals("search")).findFirst().orElseThrow();
            assertTrue(search.inputSchemaJson().startsWith("{\"type\":\"object\""), search.inputSchemaJson());
            assertTrue(t.toolsJson().contains("\"input_schema\""));
            String guide = t.guide();
            assertTrue(guide.contains("## The seven rules") && guide.contains("### search\n"), guide);
            assertEquals(guide, t.call("guide", Map.of()).text(), "the guide tool gives the same");
        }
    }

    @Test
    void theHostCanAllowChangesAndAskThePerson() {
        try (LogsiftTools t = builder().allowChanges(true).approver((what, detail) -> false).build()) {
            assertTrue(t.changesAllowed());
            t.setChangesAllowed(false);
            assertFalse(t.changesAllowed());
        }
    }

    @Test
    void speaksMcpOverStreams() throws Exception {
        String in = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LogsiftTools t = builder().build()) {
            t.serveMcp(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), out);
        }
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"id\":1") && text.contains("Call the guide tool first"), text);
        assertTrue(text.contains("\"id\":2") && text.contains("\"name\":\"search\""), text);
    }
}
