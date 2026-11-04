package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window's assistant asks before it reads what the person didn't open or name, and its tools'
 * answers are kept short: reading what wasn't asked about is what costs.
 */
class WorkspaceGateTest {
    @TempDir
    Path dir;
    Path logs;
    Workspace w;
    final List<String> asked = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        logs = Files.createDirectories(dir.resolve("logs"));
        Files.writeString(logs.resolve("a.log"), "2026-11-04 10:00:00 ERROR one\n2026-11-04 10:00:01 INFO two\n");
        Files.writeString(logs.resolve("b.log"), "2026-11-04 10:00:00 INFO three\n");
        w = new Workspace(new Workspace.Options(new Scope(List.of(dir), dir), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 30_000, List.of(), null));
    }

    @AfterEach
    void stop() {
        w.close();
    }

    private String call(Tool t, Map<String, Object> args) {
        ToolCall c = ToolCall.run(t, new Tool.Args(args), w);
        return (c.failed() ? "FAILED " : "") + c.text();
    }

    private Tool tool(String name) {
        return LogTools.all().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void withoutAGateNothingIsAsked() {
        String r = call(tool("open_log"), Map.of("path", logs.resolve("a.log").toString()));
        assertTrue(r.contains("log:1"), r);
    }

    @Test
    void asksForAFileNotOpenOrNamedAndANoIsAnAnswerForTheModel() {
        w.setGate((what, where) -> {
            asked.add(what + " | " + where.getFileName());
            throw new Tool.Failure("The person said no to: " + what);
        });
        String r = call(tool("open_log"), Map.of("path", logs.resolve("a.log").toString()));
        assertTrue(r.startsWith("FAILED") && r.contains("The person said no to: open a.log"), r);
        assertEquals(List.of("open a.log | a.log"), asked);
        String folder = call(tool("search_folder"), Map.of("folder", logs.toString(), "query", "ERROR"));
        assertTrue(folder.contains("said no to: search the folder logs"), folder);
    }

    @Test
    void filesNamedOrInAFolderNamedAreReadWithoutAsking() {
        w.setGate((what, where) -> {
            asked.add(what);
            throw new Tool.Failure("no");
        });
        w.know(logs.resolve("a.log"));
        assertTrue(call(tool("open_log"), Map.of("path", logs.resolve("a.log").toString())).contains("log:"));
        assertTrue(call(tool("open_log"), Map.of("path", logs.resolve("b.log").toString())).startsWith("FAILED"),
                "a file beside it is not named");
        w.know(logs);
        assertTrue(call(tool("open_log"), Map.of("path", logs.resolve("b.log").toString())).contains("log:"),
                "a folder named covers its files");
        assertEquals(List.of("open b.log"), asked);
    }

    @Test
    void anAllowedFileIsAskedForOnce() throws Exception {
        w.setGate((what, where) -> asked.add(what));
        String h = w.open(logs.resolve("a.log"));
        assertEquals(h, w.open(logs.resolve("a.log")), "open now: not asked again");
        assertEquals(1, asked.size());
    }

    @Test
    void answersAreCutToTheCap() {
        w.setAnswerCap(600);
        String r = w.finish("x".repeat(5_000), Workspace.DEFAULT_MAX_CHARS);
        assertTrue(r.length() < 800 && r.contains("cut here"), r.length() + " " + r);
        assertEquals(600, w.answerCap());
    }
}
