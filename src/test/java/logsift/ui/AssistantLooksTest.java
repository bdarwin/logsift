package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.UIManager;
import logsift.chat.ChatMessage;
import org.junit.jupiter.api.Test;

/** The assistant in light and dark mode, what it shows while it works, and the start page's way in. */
class AssistantLooksTest {

    @Test
    void colorsFollowASwitchBetweenLightAndDark() {
        try {
            Theme.apply(false);
            Color c = Theme.pick(new Color(0xFFFFFF), new Color(0x101010));
            Color look = Theme.uiColor("Panel.background", Color.RED);
            Color missing = Theme.uiColor("No.such.color", Theme.pick(Color.YELLOW, Color.BLUE));
            int lightLook = look.getRGB();
            assertEquals(0xFFFFFFFF, c.getRGB());
            assertEquals(Color.YELLOW.getRGB(), missing.getRGB());
            Theme.apply(true);
            // the same colors, set on a label or border before the switch, are the dark ones now
            assertEquals(0xFF101010, c.getRGB());
            assertEquals(0x10, c.getRed());
            assertEquals(UIManager.getColor("Panel.background").getRGB(), look.getRGB());
            assertNotEquals(lightLook, look.getRGB());
            assertEquals(Color.BLUE.getRGB(), missing.getRGB(), "a fallback that follows too");
            assertEquals(new Color(0x101010), c, "equal to the plain color it is now");
            assertTrue(ChatColors.accent().getRGB() == 0xFFA78BFA, "the assistant's violet, its dark shade");
        } finally {
            Theme.apply(false);
        }
    }

    @Test
    void itSaysWhatItsDoing() {
        ChatMessage tool = ChatMessage.tool("c", "read_lines", "{\"log\": \"log:1\", \"from\": 3, \"count\": 2}");
        assertEquals("Read lines 3–4 in log:1", ChatPanel.doing(tool));
        assertEquals("Search “timeout” in log:2",
                ChatPanel.doing(ChatMessage.tool("c", "search", "{\"log\": \"log:2\", \"query\": \"timeout\"}")));
        assertEquals(ChatWorking.THINKING, ChatPanel.doing(tool.withResult("lines", true, 5)), "the tool answered");
        ChatMessage answer = ChatMessage.assistant("c", "claude-haiku-5.5");
        assertNull(ChatPanel.doing(answer), "no words yet: as it was");
        assertEquals(ChatWorking.WRITING, ChatPanel.doing(answer.withText("The database")));
        assertEquals(ChatWorking.THINKING, ChatPanel.doing(ChatMessage.user("c", "Why?")));
    }

    @Test
    void theWorkingLineCountsTheSeconds() {
        ChatWorking w = new ChatWorking();
        assertNull(w.textForTest(), "hidden until it answers");
        w.start();
        assertEquals("Thinking…  0 s", w.textForTest());
        w.doing("Read lines 3–4 in log:1");
        assertEquals("Read lines 3–4 in log:1…  0 s", w.textForTest());
        w.stop();
        assertNull(w.textForTest());
        w.start();
        assertEquals("Thinking…  0 s", w.textForTest(), "a new answer starts again");
        w.stop();
    }

    @Test
    void theStartPageAsksTheAssistant() {
        AtomicBoolean asked = new AtomicBoolean();
        StartPage p = new StartPage(new StartPage.Host() {
            @Override
            public void openLogs() {
            }

            @Override
            public void openFolder() {
            }

            @Override
            public void openRemote() {
            }

            @Override
            public void attachJvm() {
            }

            @Override
            public void openThreadDump() {
            }

            @Override
            public void openProfile() {
            }

            @Override
            public void openHeapDump() {
            }

            @Override
            public void openUrl() {
            }

            @Override
            public void openRecent(java.nio.file.Path file) {
            }

            @Override
            public void reopenSession() {
            }

            @Override
            public void askAssistant() {
                asked.set(true);
            }
        });
        assertEquals("Ask the Assistant…", p.actionsForTest().get(0), "first, above the rest");
        p.actionForTest("Ask the Assistant…").doClick();
        assertTrue(asked.get());
    }
}
