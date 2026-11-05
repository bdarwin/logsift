package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import logsift.core.Json;
import org.junit.jupiter.api.Test;

/** The tools a program-run assistant (Claude Code) calls, served on this computer only, to whoever has the token. */
class ToolBridgeTest {
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final ChatEngine.ToolSpec echo = new ChatEngine.ToolSpec("echo", "Says it back.",
            Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))), (id, args) -> {
                calls.add(id + " " + args.get("text"));
                if ("boom".equals(args.get("text"))) {
                    throw new IllegalStateException("it broke");
                }
                return new ChatEngine.ToolHandler.Result("pong " + args.get("text"), false);
            });

    private static HttpResponse<String> post(ToolBridge b, String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(b.url())).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (token != null) {
            r.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void onlyThisComputerWithTheTokenIsAnswered() throws Exception {
        try (ToolBridge b = ToolBridge.start(List.of(echo), name -> null)) {
            assertTrue(b.url().startsWith("http://127.0.0.1:") && b.url().endsWith("/mcp"), b.url());
            String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            assertEquals(401, post(b, null, list).statusCode());
            assertEquals(401, post(b, "wrong", list).statusCode());
            assertEquals(200, post(b, b.token(), list).statusCode());
            HttpResponse<String> get = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(b.url()))
                    .header("Authorization", "Bearer " + b.token()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode(), "no stream of its own");
            assertTrue(b.token().length() >= 32);
            // the settings Claude Code is given: this server only, with its token
            Map<String, Object> cfg = Json.parseObject(b.mcpConfig());
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) ((Map<String, Object>) cfg.get("mcpServers")).get("logsift");
            assertEquals("http", s.get("type"));
            assertEquals(b.url(), s.get("url"));
            assertEquals(Map.of("Authorization", "Bearer " + b.token()), s.get("headers"));
        }
    }

    @Test
    void itListsAndRunsTheConversationsTools() throws Exception {
        try (ToolBridge b = ToolBridge.start(List.of(echo), name -> null)) {
            Map<String, Object> init = Json.parseObject(post(b, b.token(),
                    "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}").body());
            assertEquals("2025-06-18", ((Map<?, ?>) init.get("result")).get("protocolVersion"));
            assertEquals(202, post(b, b.token(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode());

            Map<String, Object> list = Json.parseObject(post(b, b.token(),
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").body());
            List<?> tools = (List<?>) ((Map<?, ?>) list.get("result")).get("tools");
            assertEquals(1, tools.size());
            assertEquals("echo", ((Map<?, ?>) tools.get(0)).get("name"));
            assertEquals("object", ((Map<?, ?>) ((Map<?, ?>) tools.get(0)).get("inputSchema")).get("type"));

            // Claude Code says which of its calls this is: the window ties the answer to it
            Map<String, Object> ok = Json.parseObject(post(b, b.token(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"},\"_meta\":{\"claudecode/toolUseId\":\"toolu_1\"}}}").body());
            Map<?, ?> r = (Map<?, ?>) ok.get("result");
            assertEquals(false, r.get("isError"));
            assertEquals("pong hi", ((Map<?, ?>) ((List<?>) r.get("content")).get(0)).get("text"));
            assertEquals(List.of("toolu_1 hi"), calls);

            // a failing tool is an error the model reads, not a failed request
            Map<String, Object> bad = Json.parseObject(post(b, b.token(), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"boom\"}}}").body());
            assertEquals(true, ((Map<?, ?>) bad.get("result")).get("isError"));
            Map<String, Object> none = Json.parseObject(post(b, b.token(), "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}").body());
            assertEquals(true, ((Map<?, ?>) none.get("result")).get("isError"));
            Map<String, Object> unknown = Json.parseObject(post(b, b.token(),
                    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}").body());
            assertEquals("-32601", String.valueOf(((Map<?, ?>) unknown.get("error")).get("code")));
        }
    }

    @Test
    void aCallWithoutItsIdIsTiedToTheOneAnnounced() throws Exception {
        try (ToolBridge b = ToolBridge.start(List.of(echo), name -> name.equals("echo") ? "toolu_announced" : null)) {
            post(b, b.token(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\","
                    + "\"arguments\":{\"text\":\"a\"}}}");
            assertEquals(List.of("toolu_announced a"), calls);
        }
        calls.clear();
        try (ToolBridge b = ToolBridge.start(List.of(echo), name -> null)) {
            post(b, b.token(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\","
                    + "\"arguments\":{\"text\":\"b\"}}}");
            assertEquals(1, calls.size());
            assertFalse(calls.get(0).startsWith("null"), "never without an id: " + calls);
        }
    }
}
