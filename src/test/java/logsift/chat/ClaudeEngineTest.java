package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Claude Code as the window's assistant: what its program is told, and what its JSON lines mean.
 * The lines are a real answer, recorded (a tool called, then "done"), with paths taken out.
 */
class ClaudeEngineTest {

    private static List<String> recorded() throws Exception {
        try (InputStream in = ClaudeEngineTest.class.getResourceAsStream("claude-stream.jsonl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    @Test
    void anAnswerIsReadAsTheWindowsEvents() throws Exception {
        List<ChatEvent> events = new ArrayList<>();
        ClaudeEngine.Reader r = new ClaudeEngine.Reader(events::add);
        String session = null;
        boolean ended = false;
        String announced = null;
        for (String line : recorded()) {
            ClaudeEngine.Reader.Seen s = r.line(line);
            if (s.session() != null) {
                session = s.session();
            }
            ended |= s.ended();
            if (announced == null && line.contains("\"content_block_start\"") && line.contains("tool_use")) {
                announced = r.pendingId("echo"); // as the bridge asks, before the call runs
            }
        }
        assertEquals("8a18faf6-7fdf-4e30-af55-9ce809741679", session);
        assertTrue(ended);
        assertEquals("toolu_01Q1APnwjyp5xf4hEqcFx1G2", announced, "the call is known before it's made");
        assertNull(r.pendingId("echo"), "and asked for once");

        List<ChatEvent> tools = events.stream().filter(e -> e instanceof ChatEvent.ToolStarted
                || e instanceof ChatEvent.ToolFinished).toList();
        assertEquals(List.of(new ChatEvent.ToolStarted("toolu_01Q1APnwjyp5xf4hEqcFx1G2", "echo", "{\"text\":\"hi\"}"),
                new ChatEvent.ToolFinished("toolu_01Q1APnwjyp5xf4hEqcFx1G2", true)), tools, "Logsift's name, not mcp__logsift__echo");
        String text = events.stream().filter(e -> e instanceof ChatEvent.Text).map(e -> ((ChatEvent.Text) e).delta())
                .reduce("", String::concat);
        assertEquals("done", text);
        ChatEvent.Usage u = (ChatEvent.Usage) events.stream().filter(e -> e instanceof ChatEvent.Usage).findFirst().orElseThrow();
        assertEquals("claude-code/claude-haiku-5-5", u.model());
        assertEquals(4 + 1216 + 1095, u.tokensIn(), "cache read and written included");
        assertEquals(58, u.tokensOut());
        assertEquals(1095, u.cached());
        assertEquals(0.00028355e9, u.nanoAiu(), 1, "billionths of a dollar");
        assertEquals(2483, u.millis());
        assertEquals(new ChatEvent.Idle(false), events.get(events.size() - 1));
        assertFalse(events.stream().anyMatch(e -> e instanceof ChatEvent.Failed));
    }

    @Test
    void aFailedAnswerSaysWhy() {
        List<ChatEvent> events = new ArrayList<>();
        ClaudeEngine.Reader r = new ClaudeEngine.Reader(events::add);
        r.line("{\"type\":\"result\",\"subtype\":\"error_max_budget_usd\",\"is_error\":true,\"session_id\":\"s\","
                + "\"total_cost_usd\":0.5,\"usage\":{},\"modelUsage\":{}}");
        assertEquals(new ChatEvent.Failed("It reached its spending limit."), events.get(1));
        assertEquals(new ChatEvent.Idle(false), events.get(2));
        events.clear();
        r.line("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Not logged in\",\"usage\":{}}");
        assertEquals(new ChatEvent.Failed("Not logged in"), events.get(1));
        events.clear();
        r.line("not json");
        r.line("");
        assertTrue(events.isEmpty());
    }

    @Test
    void itGetsLogsiftsToolsOnlyAndNoneOfItsOwn(@TempDir Path dir) {
        ClaudeEngine e = new ClaudeEngine(dir.resolve("claude"), dir.resolve("work"));
        List<String> c = e.command("claude-code/haiku", "low", dir.resolve("i.md"), "{}", null);
        // no files, shell or web of its own; nobody else's MCP servers, settings, skills or commands
        assertEquals("", c.get(c.indexOf("--tools") + 1));
        assertTrue(c.contains("--strict-mcp-config"));
        assertEquals("mcp__logsift", c.get(c.indexOf("--allowedTools") + 1));
        assertEquals("", c.get(c.indexOf("--setting-sources") + 1));
        assertTrue(c.contains("--disable-slash-commands"));
        assertEquals(dir.resolve("i.md").toString(), c.get(c.indexOf("--system-prompt-file") + 1), "Logsift's instructions only");
        assertEquals("haiku", c.get(c.indexOf("--model") + 1));
        assertEquals("low", c.get(c.indexOf("--effort") + 1));
        assertFalse(c.contains("--resume"));
        assertEquals("stream-json", c.get(c.indexOf("--output-format") + 1));
        List<String> next = e.command("claude-code/opus", null, dir.resolve("i.md"), "{}", "8a18faf6-7fdf-4e30-af55-9ce809741679");
        assertEquals("8a18faf6-7fdf-4e30-af55-9ce809741679", next.get(next.indexOf("--resume") + 1));
        assertFalse(next.contains("--effort"));
    }

    @Test
    void theHeaderSaysHowYoureSignedInNeverYourEmail() {
        assertEquals("Claude Max", ClaudeEngine.account(Map.of("loggedIn", true, "email", "a@b.c", "subscriptionType", "max")));
        assertEquals("Acme", ClaudeEngine.account(Map.of("loggedIn", true, "email", "a@b.c", "orgName", "Acme")));
        assertEquals("signed in", ClaudeEngine.account(Map.of("loggedIn", true, "email", "a@b.c")));
    }

    @Test
    void aChatItNoLongerHasStartsAgain(@TempDir Path dir) throws Exception {
        // never the person's own ~/.claude: a folder of chats of the test's
        Path projects = Files.createDirectories(dir.resolve("projects").resolve("-tmp-logsift-chat-work"));
        ClaudeEngine e = new ClaudeEngine(dir.resolve("claude"), dir.resolve("work"), projects.getParent());
        assertFalse(e.kept("not-a-session"));
        String id = java.util.UUID.randomUUID().toString();
        assertFalse(e.kept(id));
        String had = java.util.UUID.randomUUID().toString();
        Files.writeString(projects.resolve(had + ".jsonl"), "{}\n");
        assertTrue(e.kept(had), "one it has goes on");
        Exception ex = assertThrows(Exception.class, () -> e.open(new ChatEngine.Setup("claude-code/haiku", null, "",
                List.of(), id), x -> {}));
        assertTrue(ex.getMessage().contains(id), "the window then starts it again, told what was said");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aQuestionRunsTheProgramAndGoesOnWithTheSameChat(@TempDir Path dir) throws Exception {
        // a stand-in for claude: keeps what it was asked and how, and answers as recorded
        Path answer = Files.write(dir.resolve("answer.jsonl"), recorded());
        Path asked = dir.resolve("asked.txt");
        Path args = dir.resolve("args.txt");
        Path fake = dir.resolve("claude");
        // like the real one, it closes a moment after its answer: the next question waits for it, not fails
        Files.writeString(fake, "#!/bin/sh\nprintf '%s\\n' \"$@\" >> '" + args + "'\ncat >> '" + asked + "'\n"
                + "echo >> '" + asked + "'\ncat '" + answer + "'\nsleep 1\n");
        assertTrue(fake.toFile().setExecutable(true));
        ClaudeEngine e = new ClaudeEngine(fake, dir.resolve("work"), Files.createDirectories(dir.resolve("projects")));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        ChatEngine.ToolSpec echo = new ChatEngine.ToolSpec("echo", "Says it back.", Map.of("type", "object"),
                (id, a) -> new ChatEngine.ToolHandler.Result("pong", false));
        ChatEngine.Conversation c = e.open(new ChatEngine.Setup("claude-code/haiku", null, "Be brief.",
                List.of(echo), null), events::add);
        try {
            c.send("Why did checkout fail at 10:42?");
            waitIdle(events, 1);
            assertEquals("8a18faf6-7fdf-4e30-af55-9ce809741679", c.id(), "its chat, to go on with");
            c.send("And before that?");
            waitIdle(events, 2);
            List<String> a = Files.readAllLines(args);
            assertEquals(1, a.stream().filter(x -> x.equals("--resume")).count(), "the second question goes on with the chat");
            assertEquals("8a18faf6-7fdf-4e30-af55-9ce809741679", a.get(a.lastIndexOf("--resume") + 1));
            assertEquals(List.of("Why did checkout fail at 10:42?", "And before that?"), Files.readAllLines(asked),
                    "the question goes in on its input, not its command line");
            Path told = Path.of(a.get(a.indexOf("--system-prompt-file") + 1));
            assertEquals("Be brief.", Files.readString(told));
            c.close();
            assertFalse(Files.exists(told), "Logsift's instructions file goes with the chat");
        } finally {
            c.close();
        }
        assertTrue(events.stream().anyMatch(x -> x instanceof ChatEvent.Text t && t.delta().equals("done")));
    }

    private static void waitIdle(List<ChatEvent> events, int n) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (events.stream().filter(e -> e instanceof ChatEvent.Idle).count() < n) {
            assertTrue(System.nanoTime() < end, "answered: " + events);
            Thread.sleep(20);
        }
    }

    @Test
    void costsAreInEachAssistantsOwnMoney() {
        assertTrue(ChatCost.dollars("claude-code/claude-haiku-5-5"));
        assertFalse(ChatCost.dollars("claude-haiku-5.5"), "Copilot's Claude models are in credits");
        assertEquals("$0.03", ChatCost.format("claude-code/haiku", 0.031e9));
        assertEquals("<$0.01", ChatCost.format("claude-code/haiku", 0.0003e9));
        assertEquals("0.08 credits", ChatCost.format("claude-haiku-5.5", 0.08e9));
        assertEquals("1.00 credit", ChatCost.format(null, 1e9));
        assertEquals("Claude Haiku 5.5", ChatCost.name("claude-code/claude-haiku-5-5"));
        assertEquals("Claude Opus 5", ChatCost.name("claude-code/claude-opus-5-20261001"));
        assertEquals("claude-haiku-5.5", ChatCost.name("claude-haiku-5.5"));
        assertEquals("0.31 credits · $0.04", ChatCost.total(List.of(
                new ChatStore.Usage("claude-haiku-5.5", 2, 10, 10, 0.31e9),
                new ChatStore.Usage("claude-code/claude-haiku-5-5", 1, 10, 10, 0.04e9))));
        assertEquals("$0.04", ChatCost.total(List.of(new ChatStore.Usage("claude-code/x", 1, 1, 1, 0.04e9))));
        assertEquals("<0.01 credits", ChatCost.total(List.of()));
    }

    @Test
    void theChoiceIsKeptAndAnotherAssistantChoosesItsModelAgain(@TempDir Path home) throws Exception {
        ChatSettings s = ChatSettings.DEFAULT.withModel("claude-haiku-5.5", "low");
        assertEquals(ChatSettings.COPILOT, s.engineOrDefault());
        ChatSettings c = s.withEngine(ChatSettings.CLAUDE).withClaude("/opt/claude");
        assertNull(c.model(), "Copilot's model isn't Claude Code's");
        assertEquals("/opt/claude", c.program());
        c.save(home);
        ChatSettings back = ChatSettings.load(home);
        assertEquals(ChatSettings.CLAUDE, back.engineOrDefault());
        assertEquals("/opt/claude", back.claude());
        assertEquals(back, back.withEngine(ChatSettings.CLAUDE), "the same one: nothing changes");
    }

    @Test
    void theChosenAssistantIsMadeAndSaysWhatsMissing(@TempDir Path dir) throws Exception {
        Path claude = Files.createFile(dir.resolve("claude"));
        ChatSettings s = ChatSettings.DEFAULT.withEngine(ChatSettings.CLAUDE).withClaude(claude.toString());
        assertInstanceOf(ClaudeEngine.class, ChatService.CHOSEN.make(s, dir.resolve("home")));
        assertTrue(ChatService.CHOSEN.missing(s).startsWith("Claude Code isn't installed"));
        assertTrue(ChatService.CHOSEN.missing(ChatSettings.DEFAULT).startsWith("GitHub Copilot isn't installed"));
        assertNull(ChatService.CHOSEN.make(s.withClaude(dir.resolve("none").toString()), dir.resolve("home")));
    }
}
