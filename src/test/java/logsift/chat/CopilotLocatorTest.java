package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding the person's copilot program, though an app started from the Dock has no shell PATH. */
class CopilotLocatorTest {
    @TempDir
    Path dir;

    private Path program(Path folder, String name) throws Exception {
        Files.createDirectories(folder);
        Path p = folder.resolve(name);
        Files.writeString(p, "#!/bin/sh\n");
        if (!CopilotLocator.windows()) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return p;
    }

    @Test
    void itIsFoundOnThePathFirst() throws Exception {
        Path onPath = program(dir.resolve("bin"), "copilot");
        program(dir.resolve("home/.local/bin"), "copilot");
        assertEquals(onPath, CopilotLocator.find(null, dir.resolve("nothing") + File.pathSeparator + dir.resolve("bin"),
                dir.resolve("home"), false, null, null));
    }

    @Test
    void withoutAPathTheNewestNvmNodeIsLookedIn() throws Exception {
        Path home = dir.resolve("home");
        program(home.resolve(".nvm/versions/node/v20.19.0/bin"), "copilot");
        Path newest = program(home.resolve(".nvm/versions/node/v22.22.3/bin"), "copilot");
        program(home.resolve(".nvm/versions/node/v9.11.2/bin"), "copilot");
        Path found = CopilotLocator.find(null, null, home, false, null, null);
        // Homebrew's or /usr/local's would come first on a machine that has one
        assertTrue(found != null && (found.equals(newest) || found.startsWith("/opt/homebrew") || found.startsWith("/usr")),
                String.valueOf(found));
        assertTrue(CopilotLocator.candidates(null, home, false, null, null).indexOf(newest)
                < CopilotLocator.candidates(null, home, false, null, null)
                .indexOf(home.resolve(".nvm/versions/node/v20.19.0/bin/copilot")));
    }

    @Test
    void onWindowsNpmAndWingetAreLookedIn() {
        var c = CopilotLocator.candidates(null, dir, true, "C:\\Users\\a\\AppData\\Roaming", "C:\\Users\\a\\AppData\\Local");
        assertTrue(c.stream().anyMatch(p -> p.toString().replace('\\', '/').endsWith("Roaming/npm/copilot.cmd")), c.toString());
        assertTrue(c.stream().anyMatch(p -> p.toString().replace('\\', '/').endsWith("WinGet/Links/copilot.exe")), c.toString());
    }

    @Test
    void thePersonsOwnChoiceWinsAndAMissingOneIsNone() throws Exception {
        Path mine = program(dir.resolve("mine"), "copilot-dev");
        program(dir.resolve("bin"), "copilot");
        assertEquals(mine, CopilotLocator.find(mine.toString(), dir.resolve("bin").toString(), dir, false, null, null));
        assertNull(CopilotLocator.find(dir.resolve("gone").toString(), dir.resolve("bin").toString(), dir, false, null, null));
    }

    @Test
    void itStartsWithItsOwnFolderFirstOnThePathSoNpmFindsNode() throws Exception {
        Path p = program(dir.resolve("node/bin"), "copilot");
        Map<String, String> env = CopilotLocator.environment(p);
        String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElseThrow();
        assertTrue(env.get(key).startsWith(dir.resolve("node/bin").toString()), env.get(key));
        assertEquals("true", env.get("COPILOT_TELEMETRY_DISABLED"));
    }

    @Test
    void nodeVersionsSortAsNumbers() {
        assertTrue(CopilotLocator.version(Path.of("v22.22.3")) > CopilotLocator.version(Path.of("v22.9.10")));
        assertTrue(CopilotLocator.version(Path.of("v10.0.0")) > CopilotLocator.version(Path.of("v9.11.2")));
    }

    @Test
    void claudeIsLookedForWhereItsInstalled() throws Exception {
        // its own installer puts it in ~/.local/bin (and once in ~/.claude/local)
        var c = CopilotLocator.candidates("claude", null, dir, false, null, null);
        assertTrue(c.contains(dir.resolve(".local/bin/claude")), c.toString());
        assertTrue(c.contains(dir.resolve(".claude/local/claude")), c.toString());
        var w = CopilotLocator.candidates("claude", null, dir, true, null, null);
        assertTrue(w.contains(dir.resolve(".local").resolve("bin").resolve("claude.exe")), w.toString());
        Path mine = program(dir.resolve(".local/bin"), "claude");
        assertEquals(mine, CopilotLocator.find("claude", null, null, dir, false, null, null));
    }
}
