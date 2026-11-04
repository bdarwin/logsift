package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import logsift.chat.ChatInstructions;
import org.junit.jupiter.api.Test;

/** The assistant's welcome: questions to start with, and what can be set up. */
class ChatWelcomeTest {
    private static final ChatPanel.OpenLog LOG = new ChatPanel.OpenLog("log:1", "app.log", "/x/app.log", 10, false);

    @Test
    void examplesNameTheServicesDescribed() {
        assertEquals(List.of("What went wrong in login this morning?", "Errors in login in the last hour, minute by minute",
                "Was login slow today? The slowest requests and why", "Check the health of login"),
                ChatPanel.suggested(List.of(), false, List.of("login")));
        List<String> two = ChatPanel.suggested(List.of(LOG), true, List.of("login", "orders"));
        assertEquals("Explain the chosen lines", two.get(0));
        assertTrue(two.contains("What went wrong in app.log?") && two.contains("What went wrong in login this morning?")
                && two.contains("Which of my services had errors in the last hour?"), two.toString());
        assertEquals(List.of("What can you do with my logs?", "Where are the logs on this computer?"),
                ChatPanel.suggested(List.of(), false, List.of()));
    }

    @Test
    void itShowsWhatCanBeSetUp() {
        List<ChatPanel.Setting> s = ChatPanel.settingsShown(List.of(), ChatInstructions.Options.DEFAULT, null);
        assertEquals(List.of("locations", "settings", "model"), s.stream().map(ChatPanel.Setting::action).toList());
        assertTrue(s.get(0).now().startsWith("Not described yet"), s.get(0).now());
        assertEquals("Logsift's instructions, where your logs are, step-by-step plans, the time now. Add your own: "
                + "names you use, what's normal.", s.get(1).now());
        var less = ChatInstructions.Options.DEFAULT.withPlans(false).withOwn("x");
        List<ChatPanel.Setting> t = ChatPanel.settingsShown(List.of("login", "orders"), less, null);
        assertEquals("It knows 2 services: login, orders.", t.get(0).now());
        assertTrue(t.get(1).now().startsWith("Logsift's instructions, where your logs are, the time now, your own "
                + "instructions."), t.get(1).now());
    }
}
