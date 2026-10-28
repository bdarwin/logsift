package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The git settings that make Logsift git's difftool and mergetool. */
class GitSetupTest {

    @Test
    void settings() {
        List<String[]> s = GitSetup.settings("/Applications/Logsift.app/Contents/MacOS/Logsift", true, false);
        assertEquals("difftool.logsift.cmd", s.get(0)[0]);
        assertEquals("\"/Applications/Logsift.app/Contents/MacOS/Logsift\" --diff \"$LOCAL\" \"$REMOTE\"", s.get(0)[1]);
        assertEquals("\"/Applications/Logsift.app/Contents/MacOS/Logsift\" --merge \"$LOCAL\" \"$REMOTE\" \"$BASE\""
                + " \"$MERGED\"", s.get(1)[1]);
        assertEquals(List.of("difftool.logsift.cmd", "mergetool.logsift.cmd", "mergetool.logsift.trustExitCode",
                "diff.tool"), s.stream().map(kv -> kv[0]).toList());
        // Windows paths use forward slashes (git's shell)
        assertTrue(GitSetup.settings("C:\\Program Files\\Logsift\\Logsift.exe", false, false).get(0)[1]
                .startsWith("\"C:/Program Files/Logsift/Logsift.exe\" --diff"));
    }

    @Test
    void commandsToPaste() {
        String c = GitSetup.commands(GitSetup.settings("logsift", false, true));
        assertTrue(c.startsWith("git config --global difftool.logsift.cmd '\"logsift\" --diff \"$LOCAL\" \"$REMOTE\"'\n"),
                c);
        assertTrue(c.contains("git config --global merge.tool 'logsift'\n"), c);
    }
}
