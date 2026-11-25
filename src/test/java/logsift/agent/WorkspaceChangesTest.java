package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Tools that change something are off until the person (Settings → Agents) or the host program
 * (--allow-changes) turns them on; even then they only save new files inside the folders agents
 * may read, and never replace one.
 */
class WorkspaceChangesTest {
    @TempDir
    Path dir;
    Path logs;
    Workspace w;

    /** A tool that saves a new file: what export-like tools do. */
    static final Tool SAVE = new Tool() {
        public String name() {
            return "save_note";
        }

        public String title() {
            return "Save a note";
        }

        public String description() {
            return "Saves a note.";
        }

        public Map<String, Object> inputSchema() {
            return new Tool.Schema().string("to", "Where.", true).build();
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        public String run(Args a, Workspace w) throws Exception {
            Path p = w.checkNew(Workspace.path(a.string("to")), "save the note to");
            Files.writeString(p, "note");
            return "Saved " + p;
        }
    };

    @BeforeEach
    void start() throws IOException {
        logs = Files.createDirectories(dir.resolve("logs"));
        w = workspace(false);
    }

    private Workspace workspace(boolean changes) {
        return new Workspace(new Workspace.Options(new Scope(List.of(logs), dir), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 30_000, List.of(), null, dir, changes));
    }

    @AfterEach
    void stop() {
        w.close();
    }

    @Test
    void offAToolThatChangesThingsIsNeitherOfferedNorRun() {
        List<Tool> all = new ArrayList<>(LogTools.all());
        all.add(SAVE);
        assertFalse(ToolCall.offered(all, w).contains(SAVE));
        assertEquals(LogTools.all().stream().filter(Tool::readOnly).toList(), ToolCall.offered(all, w),
                "every reading tool still offered");
        ToolCall c = ToolCall.run(SAVE, new Tool.Args(Map.of("to", logs.resolve("n.txt").toString())), w);
        assertTrue(c.failed() && c.text().contains("Let agents change things"), c.text());
        assertFalse(Files.exists(logs.resolve("n.txt")));
    }

    @Test
    void onItSavesNewFilesInsideTheFoldersOnlyAndNeverReplacesOne() throws IOException {
        w.close();
        w = workspace(true);
        assertTrue(ToolCall.offered(List.of(SAVE), w).contains(SAVE));
        ToolCall ok = ToolCall.run(SAVE, new Tool.Args(Map.of("to", logs.resolve("n.txt").toString())), w);
        assertFalse(ok.failed(), ok.text());
        assertEquals("note", Files.readString(logs.resolve("n.txt")));

        Files.writeString(logs.resolve("keep.txt"), "mine");
        ToolCall again = ToolCall.run(SAVE, new Tool.Args(Map.of("to", logs.resolve("keep.txt").toString())), w);
        assertTrue(again.failed() && again.text().contains("never replaces"), again.text());
        assertEquals("mine", Files.readString(logs.resolve("keep.txt")), "left as it was");

        Path outside = Files.createDirectories(dir.resolve("elsewhere"));
        ToolCall out = ToolCall.run(SAVE, new Tool.Args(Map.of("to", outside.resolve("n.txt").toString())), w);
        assertTrue(out.failed(), out.text());
        assertFalse(Files.exists(outside.resolve("n.txt")), "not outside the folders agents may read");

        ToolCall noFolder = ToolCall.run(SAVE, new Tool.Args(Map.of("to", logs.resolve("no/n.txt").toString())), w);
        assertTrue(noFolder.failed() && noFolder.text().contains("No such folder"), noFolder.text());
    }

    @Test
    void theWindowCanTurnItOnAndOffAndAskFirst() {
        List<String> asked = new ArrayList<>();
        w.setGate(new Workspace.Gate() {
            public void allow(String what, Path where) {
            }

            @Override
            public void allowChange(String what, Path where) throws Tool.Failure {
                asked.add(what);
                if (what.contains("no.txt")) {
                    throw new Tool.Failure("The person said no.");
                }
            }
        });
        w.setChangesAllowed(true);
        assertFalse(ToolCall.run(SAVE, new Tool.Args(Map.of("to", logs.resolve("yes.txt").toString())), w).failed());
        ToolCall no = ToolCall.run(SAVE, new Tool.Args(Map.of("to", logs.resolve("no.txt").toString())), w);
        assertTrue(no.failed() && no.text().contains("said no"), no.text());
        assertFalse(Files.exists(logs.resolve("no.txt")));
        assertEquals(List.of("save the note to yes.txt", "save the note to no.txt"), asked);
        w.setChangesAllowed(false);
        assertTrue(ToolCall.run(SAVE, new Tool.Args(Map.of("to", logs.resolve("later.txt").toString())), w).failed());
    }

    @Test
    void theSettingIsKeptAndTheCommandLineCanTurnItOn() throws IOException {
        Path home = Files.createDirectories(dir.resolve("home"));
        assertFalse(AgentSettings.load(home).allowChanges(), "off by default");
        assertFalse(logsift.agent.mcp.McpMain.options(new String[0], home, dir).allowChanges());
        assertTrue(logsift.agent.mcp.McpMain.options(new String[] {"--allow-changes"}, home, dir).allowChanges());
        AgentSettings.DEFAULT.withChanges(true).save(home);
        assertTrue(AgentSettings.load(home).allowChanges());
        assertTrue(logsift.agent.mcp.McpMain.options(new String[0], home, dir).allowChanges());
    }
}
