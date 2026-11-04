package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.rpc.ModelInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        SessionEvent e = JSON.readValue("{\"type\":\"" + type + "\",\"id\":\"" + UUID.randomUUID()
                + "\",\"timestamp\":\"2026-11-04T10:00:00Z\",\"data\":" + data + "}", SessionEvent.class);
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
        assertFalse(Files.exists(dir.resolve("home")));
    }

    @Test
    void theAnswerStreamsAsText() throws Exception {
        ChatEvent e = event("assistant.message_delta", "{\"messageId\":\"m1\",\"deltaContent\":\"There are 17\"}");
        assertEquals(new ChatEvent.Text("m1", "There are 17"), e);
        // a subagent's words aren't the answer
        assertNull(event("assistant.message_delta",
                "{\"messageId\":\"m2\",\"deltaContent\":\"x\",\"parentToolCallId\":\"t9\"}"));
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
    void aModelsPricesAreCreditsPerMillionTokens() {
        ModelInfo m = new ModelInfo().setId("claude-haiku-5.5").setName("Claude Haiku 5.5")
                .setPolicy(new com.github.copilot.rpc.ModelPolicy().setState("enabled"))
                .setCapabilities(new com.github.copilot.rpc.ModelCapabilities()
                        .setLimits(new com.github.copilot.rpc.ModelLimits().setMaxContextWindowTokens(1_000_000)))
                .setBilling(new com.github.copilot.rpc.ModelBilling().setTokenPrices(
                        new com.github.copilot.generated.rpc.ModelBillingTokenPrices(10.0, 50.0, 1.0, null, null, null,
                                1_000_000L, null, null, null)))
                .setSupportedReasoningEfforts(List.of("low", "medium", "high")).setDefaultReasoningEffort("medium");
        ChatEngine.Model x = CopilotEngine.model(m);
        assertEquals("Claude Haiku 5.5", x.name());
        assertEquals(10.0, x.inputPrice());
        assertEquals(50.0, x.outputPrice());
        assertEquals(1_000_000, x.context());
        assertEquals(List.of("low", "medium", "high"), x.efforts());
        assertEquals("medium", x.effort());
        assertTrue(x.enabled());
        ChatEngine.Model off = CopilotEngine.model(new ModelInfo().setId("grok-4.6")
                .setPolicy(new com.github.copilot.rpc.ModelPolicy().setState("disabled")));
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
}
