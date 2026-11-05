package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import logsift.agent.LogCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the assistant is told, part by part, as Settings → Assistant sets it. */
class ChatInstructionsTest {

    private static LogCatalog login() {
        return LogCatalog.of(List.of(new LogCatalog.Entry(new LogCatalog.Service("login", "Signs people in",
                List.of("/var/log/login"), List.of(new LogCatalog.FileKind("app.log*", "Requests and errors")), "UTC",
                "Restarts at 03:00 every night"), Path.of("/home/me/.logsift/logs.json"), false)));
    }

    @Test
    void byDefaultItIsToldEverythingLogsiftHas() {
        String t = ChatInstructions.text(ChatInstructions.Options.DEFAULT, login());
        assertTrue(t.contains("Logsift reads logs"), "Logsift's own instructions");
        assertTrue(t.contains("[app.log:1204](logsift:log:1:1204)"), "how to cite lines");
        // where the logs are, in full: what "the login service" means, without a path
        assertTrue(t.contains("**login**: Signs people in"), t);
        assertTrue(t.contains("`/var/log/login`") && t.contains("`app.log*`: Requests and errors"), t);
        assertTrue(t.contains("Times in its logs are UTC") && t.contains("Notes: Restarts at 03:00"), t);
        // for an assistant with the tools, not a terminal
        assertTrue(t.contains("`list_logs` tells") && !t.contains(" run list_logs"), t);
        // the step-by-step plans agents get as prompts, every one
        for (String plan : List.of("Investigate a problem", "Explain a crash", "Compare a bad run with a good one",
                "Find what is slow", "Check a service's health")) {
            assertTrue(t.contains("### " + plan), plan);
        }
        assertFalse(t.contains(": ?"), "no empty arguments in the plans");
        assertTrue(t.contains("## Times") && t.contains("this morning"), "reads times against the time now");
        assertFalse(t.contains("own instructions"), "none written");
    }

    @Test
    void eachPartCanBeLeftOutButLogsiftsOwn() {
        var o = ChatInstructions.Options.DEFAULT.withLocations(false).withPlans(false).withNow(false);
        String t = ChatInstructions.text(o, login());
        assertTrue(t.contains("Logsift reads logs"));
        assertFalse(t.contains("/var/log/login") || t.contains("### Investigate") || t.contains("## Times"), t);
        assertTrue(t.contains("login"), "the services' names still come with Logsift's own: " + t);
        List<ChatInstructions.Part> parts = ChatInstructions.parts(o, login());
        assertEquals(List.of("logsift", "locations", "plans", "now", "own"),
                parts.stream().map(ChatInstructions.Part::key).toList());
        assertTrue(parts.get(0).on() && parts.get(0).fixed());
        assertFalse(parts.get(1).on() || parts.get(2).on() || parts.get(3).on() || parts.get(4).on());
        assertTrue(parts.get(2).tokens() > 300, "the plans are a few hundred tokens: " + parts.get(2).tokens());
        assertEquals((parts.get(0).text().length() + 3) / 4, parts.get(0).tokens());
    }

    @Test
    void thePersonsOwnWordsComeLast() {
        String t = ChatInstructions.text(ChatInstructions.Options.DEFAULT.withOwn("  “login” is the auth service.\n"),
                LogCatalog.EMPTY);
        assertTrue(t.endsWith("## The person's own instructions\n\n“login” is the auth service."), t);
        assertTrue(t.contains("No log locations are described yet") && t.contains("ask them where"), t);
    }

    @Test
    void theOptionsAreKeptInLogsiftsFolder(@TempDir Path home) throws Exception {
        assertEquals(ChatInstructions.Options.DEFAULT, ChatInstructions.Options.load(home), "nothing saved yet");
        var o = new ChatInstructions.Options(false, false, true, false, true, "Answer in short bullets.");
        o.save(home);
        assertEquals(o, ChatInstructions.Options.load(home));
        assertEquals("Answer in short bullets.\n", Files.readString(home.resolve("chat/instructions.md")));
        o.withOwn("").save(home);
        assertFalse(Files.exists(home.resolve("chat/instructions.md")), "no words, no file");
        assertEquals(o.withOwn(""), ChatInstructions.Options.load(home));
    }

    @Test
    void itOpensWhereItWasPut(@TempDir Path home) throws Exception {
        for (ChatInstructions.Where w : ChatInstructions.Where.values()) {
            ChatInstructions.Options.DEFAULT.withWhere(w).save(home);
            assertEquals(w, ChatInstructions.Options.load(home).where());
        }
        assertTrue(Files.readString(home.resolve("chat/assistant.properties")).contains("where=window"));
        assertFalse(ChatInstructions.Options.load(home).inTab());
        Files.writeString(home.resolve("chat/assistant.properties"), "where=somewhere\n");
        assertEquals(ChatInstructions.Where.TAB, ChatInstructions.Options.load(home).where(), "unknown: a tab");
        assertEquals(ChatInstructions.Where.SIDE, ChatInstructions.Options.DEFAULT.withInTab(false).where());
    }

    @Test
    void eachQuestionSaysWhenItIs() {
        ZonedDateTime t = ZonedDateTime.of(2026, 11, 4, 9, 42, 10, 0, ZoneId.of("Asia/Singapore"));
        assertEquals("[Now] Wednesday 4 November 2026, 09:42 (Asia/Singapore, UTC+08:00).", ChatInstructions.now(t));
        assertEquals("[Now] Wednesday 4 November 2026, 01:42 (UTC, UTC+00:00).",
                ChatInstructions.now(t.withZoneSameInstant(ZoneId.of("UTC"))));
    }
}
