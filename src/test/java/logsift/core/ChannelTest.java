package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import logsift.agent.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A beta build keeps its own settings, so it runs next to the normal Logsift. */
class ChannelTest {
    private static final List<String> KEYS = List.of("logsift.channel", "logsift.home", "logsift.prefs", "user.home");
    private final Properties saved = new Properties();

    @TempDir
    Path dir;

    @BeforeEach
    void save() {
        for (String k : KEYS) {
            if (System.getProperty(k) != null) {
                saved.setProperty(k, System.getProperty(k));
            }
            if (!k.equals("user.home")) {
                System.clearProperty(k);
            }
        }
        System.setProperty("user.home", dir.toString());
    }

    @AfterEach
    void restore() {
        for (String k : KEYS) {
            if (saved.getProperty(k) != null) {
                System.setProperty(k, saved.getProperty(k));
            } else {
                System.clearProperty(k);
            }
        }
    }

    @Test
    void normalBuildChangesNothing() {
        Channel.apply();
        assertFalse(Channel.isBeta());
        assertEquals("Logsift", Channel.appName());
        assertNull(System.getProperty("logsift.home"));
        assertNull(System.getProperty("logsift.prefs"));
    }

    @Test
    void betaHasItsOwnFolderAndPreferences() {
        System.setProperty("logsift.channel", "beta");
        Channel.apply();
        assertTrue(Channel.isBeta());
        assertEquals("Logsift Beta", Channel.appName());
        assertEquals(dir.resolve(".logsift-beta").toString(), System.getProperty("logsift.home"));
        assertEquals("/logsift/beta", System.getProperty("logsift.prefs"));
    }

    @Test
    void betaKeepsFoldersSetAlready() {
        System.setProperty("logsift.channel", "beta");
        System.setProperty("logsift.home", dir.resolve("mine").toString());
        System.setProperty("logsift.prefs", "/logsift/test");
        Channel.apply();
        assertEquals(dir.resolve("mine").toString(), System.getProperty("logsift.home"));
        assertEquals("/logsift/test", System.getProperty("logsift.prefs"));
    }

    @Test
    void agentsCantReadTheBetasFolder() throws Exception {
        Path beta = Files.createDirectories(dir.resolve(".logsift-beta"));
        Path log = Files.writeString(beta.resolve("agent.properties"), "mask.off=true\n");
        Path other = Files.writeString(dir.resolve("app.log"), "x\n");
        Scope scope = new Scope(List.of(dir), dir);
        assertFalse(scope.allows(log));
        assertTrue(scope.allows(other));
    }
}
