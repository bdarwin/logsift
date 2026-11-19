package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.generated.SessionEvent;
import com.microsoft.copilot.generated.rpc.Model;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What Copilot's events and models mean in the window. Events are read from JSON as Copilot
 * sends them, by the SDK's own classes, so a change on its side shows up here.
 */
class CopilotEngineTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static ChatEvent event(String type, String data) throws Exception {
        return event(type, data, "");
    }

    /** {@code more}: fields of the event itself, such as the subagent it comes from. */
    private static ChatEvent event(String type, String data, String more) throws Exception {
        SessionEvent e = JSON.readValue("{\"type\":\"" + type + "\",\"id\":\"" + UUID.randomUUID()
                + "\",\"timestamp\":\"2026-11-04T10:00:00Z\"" + more + ",\"data\":" + data + "}", SessionEvent.class);
        return CopilotEngine.event(e);
    }

    @Test
    void itUsesThePersonsOwnCopilotFolderWhereTheirSignInIs(@TempDir Path dir) throws Exception {
        // signed in with copilot's /login, the sign-in is in ~/.copilot: a folder of Logsift's
        // own left the window saying 'not signed in'
        Path cli = Files.createFile(dir.resolve("copilot"));
        ChatEngine e = ChatService.COPILOT.make(new ChatSettings(null, null, cli.toString()), dir.resolve("home"));
        CopilotEngine c = assertInstanceOf(CopilotEngine.class, e);
        assertNull(c.home());
        // and works in a folder of Logsift's, made when a chat starts (with no home of its own,
        // starting a chat failed on a null folder)
        assertFalse(Files.exists(dir.resolve("home")));
        assertEquals(dir.resolve("home").resolve("chat").resolve("work"), c.work());
        assertTrue(Files.isDirectory(c.work()));
    }

    @Test
    void theAnswerStreamsAsText() throws Exception {
        ChatEvent e = event("assistant.message_delta", "{\"messageId\":\"m1\",\"deltaContent\":\"There are 17\"}");
        assertEquals(new ChatEvent.Text("m1", "There are 17"), e);
        // a subagent's words aren't the answer (protocol 4 says whose they are on the event)
        assertNull(event("assistant.message_delta", "{\"messageId\":\"m2\",\"deltaContent\":\"x\"}",
                ",\"agentId\":\"explore-1\""));
    }

    @Test
    void toolsAreSeenStartingAndFinishing() throws Exception {
        ChatEvent s = event("tool.execution_start",
                "{\"toolCallId\":\"t1\",\"toolName\":\"search\",\"arguments\":{\"log\":\"log:1\",\"query\":\"timed \\\"out\\\"\"}}");
        ChatEvent.ToolStarted t = assertInstanceOf(ChatEvent.ToolStarted.class, s);
        assertEquals("t1", t.callId());
        assertEquals("search", t.tool());
        assertEquals("{\"log\": \"log:1\", \"query\": \"timed \\\"out\\\"\"}", t.args());
        assertEquals(new ChatEvent.ToolFinished("t1", true),
                event("tool.execution_complete", "{\"toolCallId\":\"t1\",\"success\":true}"));
        assertEquals(new ChatEvent.ToolFinished("t2", false),
                event("tool.execution_complete", "{\"toolCallId\":\"t2\",\"success\":false}"));
    }

    @Test
    void usageCountsEveryTokenReadAndTheCreditsItCost() throws Exception {
        // as Copilot reported a real call: 4 new tokens, 3,519 written to its cache, 50 out
        ChatEvent e = event("assistant.usage", """
                {"model":"claude-haiku-5.5","inputTokens":4,"outputTokens":50,"cacheReadTokens":0,
                 "cacheWriteTokens":3519,"duration":1200,"cost":1.0,
                 "copilotUsage":{"tokenDetails":[],"totalNanoAiu":46527500}}""");
        ChatEvent.Usage u = assertInstanceOf(ChatEvent.Usage.class, e);
        assertEquals("claude-haiku-5.5", u.model());
        assertEquals(3523, u.tokensIn());
        assertEquals(50, u.tokensOut());
        assertEquals(0, u.cached());
        assertEquals(46527500, u.nanoAiu(), 0.1);
        assertEquals(1200, u.millis());
    }

    @Test
    void theTurnEndsAnsweredOrStopped() throws Exception {
        assertEquals(new ChatEvent.Idle(true), event("session.idle", "{\"aborted\":true}"));
        assertEquals(new ChatEvent.Idle(false), event("session.idle", "{}"));
        assertEquals(new ChatEvent.Failed("rate limited"), event("session.error",
                "{\"errorType\":\"rate_limit\",\"message\":\"rate limited\"}"));
        assertEquals(new ChatEvent.Title("Checkout timeouts"), event("session.title_changed",
                "{\"title\":\"Checkout timeouts\"}"));
        assertEquals(new ChatEvent.Thinking("Let me look"), event("assistant.reasoning_delta",
                "{\"reasoningId\":\"r1\",\"deltaContent\":\"Let me look\"}"));
    }

    @Test
    void aModelsPricesAreCreditsPerMillionTokens() throws Exception {
        // as Copilot lists them
        Model m = JSON.readValue("""
                {"id":"claude-haiku-5.5","name":"Claude Haiku 5.5","policy":{"state":"enabled"},
                 "capabilities":{"supports":{},"limits":{"max_context_window_tokens":1000000}},
                 "billing":{"tokenPrices":{"inputPrice":10.0,"outputPrice":50.0,"cacheReadPrice":1.0,"batchSize":1000000}},
                 "supportedReasoningEfforts":["low","medium","high"],"defaultReasoningEffort":"medium"}""", Model.class);
        ChatEngine.Model x = CopilotEngine.model(m);
        assertEquals("Claude Haiku 5.5", x.name());
        assertEquals(10.0, x.inputPrice());
        assertEquals(50.0, x.outputPrice());
        assertEquals(1_000_000, x.context());
        assertEquals(List.of("low", "medium", "high"), x.efforts());
        assertEquals("medium", x.effort());
        assertTrue(x.enabled());
        ChatEngine.Model off = CopilotEngine.model(JSON.readValue(
                "{\"id\":\"grok-4.6\",\"policy\":{\"state\":\"disabled\"}}", Model.class));
        assertFalse(off.enabled());
        assertEquals("grok-4.6", off.name());
        assertEquals(0, off.inputPrice());
    }

    @Test
    void anErrorSaysWhyWithoutTheFuturesWrapping() {
        Exception e = new java.util.concurrent.ExecutionException(new java.util.concurrent.CompletionException(
                new IllegalStateException("Not signed in to GitHub")));
        assertEquals("Not signed in to GitHub", CopilotEngine.why(e));
        assertEquals("no answer in time", CopilotEngine.why(new java.util.concurrent.TimeoutException()));
    }

    @Test
    void signInStepsAreReadAsCopilotSendsThem() throws Exception {
        // the first step comes untyped (a map); later ones through the SDK's classes when it can read them
        java.util.Map<String, Object> open = new java.util.LinkedHashMap<>();
        open.put("kind", "open-url");
        open.put("url", "https://github.com/login/oauth/authorize?client_id=x");
        assertEquals("open-url", CopilotEngine.stepKind(open));
        assertEquals("https://github.com/login/oauth/authorize?client_id=x", CopilotEngine.stepField(open, "url"));
        assertEquals("error", CopilotEngine.stepKind(java.util.Map.of("kind", "error", "message", "denied")));
        assertEquals("denied", CopilotEngine.stepField(java.util.Map.of("kind", "error", "message", "denied"), "message"));
        assertEquals("declined", CopilotEngine.stepStatus(java.util.Map.of("kind", "completed",
                "result", java.util.Map.of("status", "declined"))));
        assertNull(CopilotEngine.stepStatus(java.util.Map.of("kind", "awaiting")));
        assertEquals("none", CopilotEngine.stepKind(null));

        com.microsoft.copilot.generated.rpc.AuthLoginStepOpenUrl typed = new com.microsoft.copilot.generated.rpc.AuthLoginStepOpenUrl();
        typed.setUrl("https://github.com/login");
        assertEquals("https://github.com/login", CopilotEngine.stepField(typed, "url"));
        com.microsoft.copilot.generated.rpc.AuthLoginStepCompleted done = new com.microsoft.copilot.generated.rpc.AuthLoginStepCompleted();
        done.setResult(new com.microsoft.copilot.generated.rpc.AuthLoginResultDto(
                com.microsoft.copilot.generated.rpc.AuthLoginResultStatus.COMPLETED, "github.com", "bdarwin", List.of()));
        assertEquals("completed", CopilotEngine.stepKind(done));
        assertEquals("completed", CopilotEngine.stepStatus(done));
    }

    @Test
    void copilotCanBeSignedInFromLogsiftAndAnotherFolderCanBeTried(@TempDir Path dir) throws Exception {
        Path cli = Files.createFile(dir.resolve("copilot"));
        ChatEngine e = ChatService.COPILOT.make(new ChatSettings(null, null, cli.toString()), dir.resolve("home"));
        assertTrue(e.canSignIn());
        String old = System.getProperty("logsift.copilotHome");
        try {
            System.setProperty("logsift.copilotHome", dir.resolve("try").toString());
            CopilotEngine c = (CopilotEngine) ChatService.COPILOT.make(new ChatSettings(null, null, cli.toString()),
                    dir.resolve("home"));
            assertEquals(dir.resolve("try"), c.home(), "a sign-in to try, apart from the person's own");
        } finally {
            if (old == null) {
                System.clearProperty("logsift.copilotHome");
            } else {
                System.setProperty("logsift.copilotHome", old);
            }
        }
    }

    @Test
    void logsiftsOwnCopilotAnswersUnlessAProgramIsChosen(@TempDir Path dir) throws Exception {
        // a Copilot program people install changes its protocol (CLI 1.0.113 broke SDK 1.x), so
        // the one that comes with Logsift answers
        CopilotEngine own = assertInstanceOf(CopilotEngine.class,
                ChatService.COPILOT.make(new ChatSettings(null, null, null), dir.resolve("home")));
        assertNull(own.cli());
        assertEquals(Path.of(System.getProperty("user.home"), ".copilot"), own.copilotHome(),
                "the person's own Copilot folder, where its sign-in is");
        Path cli = Files.createFile(dir.resolve("copilot"));
        CopilotEngine chosen = assertInstanceOf(CopilotEngine.class,
                ChatService.COPILOT.make(new ChatSettings(null, null, cli.toString()), dir.resolve("home")));
        assertEquals(cli, chosen.cli());
        assertNull(ChatService.COPILOT.make(new ChatSettings(null, null, dir.resolve("gone").toString()), dir.resolve("home")),
                "a chosen program that isn't there anymore");
    }

    @Test
    void aCopilotOfAnotherProtocolSaysWhatToDo() {
        Exception mismatch = new java.util.concurrent.ExecutionException(new RuntimeException(
                "SDK protocol version mismatch: SDK supports versions 4-4, but server reports version 3. Please update"));
        Path program = Path.of("/usr/local/bin/copilot");
        String chosen = CopilotEngine.startFailed(mismatch, program);
        // the path as this system writes it (backslashes on Windows)
        assertTrue(chosen.contains(program.toString()) &&chosen.contains("Use Logsift's Copilot")
                && chosen.contains("copilot update"), chosen);
        assertFalse(chosen.contains("SDK"), "no SDK talk: " + chosen);
        assertTrue(CopilotEngine.startFailed(mismatch, null).contains("Install Logsift again"));
        assertEquals("Copilot didn't start: no answer in time",
                CopilotEngine.startFailed(new java.util.concurrent.TimeoutException(), null));
    }

    @Test
    void logsiftsOwnCopilotStartsWithNothingInstalledAndNoSignIn(@TempDir Path dir) throws Exception {
        // the real runtime, on each system the build runs on: started, asked who's signed in (no one:
        // every way to a sign-in points at empty folders), stopped. No model, no tokens.
        Path empty = Files.createDirectories(dir.resolve("nobody"));
        Map<String, String> noSignIn = new java.util.HashMap<>();
        for (String k : List.of("HOME", "USERPROFILE", "GH_CONFIG_DIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "APPDATA",
                "LOCALAPPDATA")) {
            noSignIn.put(k, empty.toString());
        }
        for (String k : List.of("GH_TOKEN", "GITHUB_TOKEN", "COPILOT_GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN",
                "GITHUB_ENTERPRISE_TOKEN")) {
            noSignIn.put(k, null);
        }
        CopilotEngine e = new CopilotEngine(null, dir.resolve("copilot-home"), dir.resolve("work"))
                .withEnvironmentChanges(noSignIn);
        // the SDK unpacks the runtime under user.home: the build's folder, not the person's
        String userHome = System.getProperty("user.home");
        Path unpacked = Path.of(System.getProperty("logsift.home", dir.toString())).resolveSibling("test-copilot-runtime");
        try {
            System.setProperty("user.home", unpacked.toString());
            e.start();
        } finally {
            System.setProperty("user.home", userHome);
        }
        try {
            assertNull(e.user(), "signed out");
        } finally {
            e.close();
        }
    }
}
