package logsift.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import logsift.agent.JsonWriter;
import logsift.core.Json;

/**
 * A conversation's tools for an assistant that runs as a program of its own (Claude Code): an MCP
 * server over HTTP on this computer only, for as long as the conversation is open. The tools run
 * here, in the window, with what it has open and what it asks the person first, as they do for
 * Copilot. Only a caller with the secret token is answered.
 */
final class ToolBridge implements AutoCloseable {
    /** What the assistant calls this server: its tools are {@code mcp__logsift__<tool>}. */
    static final String NAME = "logsift";

    private final Map<String, ChatEngine.ToolSpec> tools = new LinkedHashMap<>();
    private final HttpServer server;
    private final ExecutorService calls;
    private final String token;
    /** The assistant's id of a call, by the tool's name, when the call itself doesn't carry it. */
    private final java.util.function.Function<String, String> ids;
    private final java.util.concurrent.atomic.AtomicLong unnamed = new java.util.concurrent.atomic.AtomicLong();

    private ToolBridge(List<ChatEngine.ToolSpec> tools, java.util.function.Function<String, String> ids) throws IOException {
        this.ids = ids;
        for (ChatEngine.ToolSpec t : tools) {
            this.tools.put(t.name(), t);
        }
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        token = HexFormat.of().formatHex(secret);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        calls = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "logsift-tool-bridge");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(calls);
        server.createContext("/mcp", this::exchange);
        // its dispatcher thread takes after the thread that starts it: a daemon, so it never keeps Logsift running
        Thread starter = new Thread(server::start, "logsift-tool-bridge-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop(0);
            throw new IOException("Interrupted while starting the tools' server");
        }
    }

    /** Serves these tools until {@link #close}. */
    static ToolBridge start(List<ChatEngine.ToolSpec> tools, java.util.function.Function<String, String> ids)
            throws IOException {
        return new ToolBridge(tools, ids);
    }

    String url() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort() + "/mcp";
    }

    String token() {
        return token;
    }

    /** The assistant's MCP settings: this server only, with its token. */
    String mcpConfig() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "http");
        s.put("url", url());
        s.put("headers", Map.of("Authorization", "Bearer " + token));
        return JsonWriter.write(Map.of("mcpServers", Map.of(NAME, s)));
    }

    @Override
    public void close() {
        server.stop(0);
        calls.shutdownNow();
    }

    private void exchange(HttpExchange x) throws IOException {
        try (x) {
            if (!("Bearer " + token).equals(x.getRequestHeaders().getFirst("Authorization"))) {
                send(x, 401, null);
                return;
            }
            if (!"POST".equals(x.getRequestMethod())) {
                send(x, 405, null); // no stream of its own to listen to: every answer comes with its request
                return;
            }
            String body;
            try (InputStream in = x.getRequestBody()) {
                body = new String(in.readNBytes(8 << 20), StandardCharsets.UTF_8);
            }
            Map<String, Object> answer = answer(body);
            send(x, answer == null ? 202 : 200, answer == null ? null : JsonWriter.write(answer));
        }
    }

    private static void send(HttpExchange x, int code, String json) throws IOException {
        if (json == null) {
            x.sendResponseHeaders(code, -1);
            return;
        }
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }

    /** The answer to one JSON-RPC message, or null for a notification. */
    Map<String, Object> answer(String text) {
        Object v = Json.parseValue(text, Fail.FAIL);
        if (!(v instanceof Map<?, ?> raw)) {
            return error(null, v == Fail.FAIL ? -32700 : -32600, v == Fail.FAIL ? "Not JSON" : "One request at a time");
        }
        Object id = raw.get("id");
        if (!(raw.get("method") instanceof String method)) {
            return null;
        }
        if (!raw.containsKey("id")) {
            return null; // notifications/initialized, cancelled…
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = raw.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        return switch (method) {
            case "initialize" -> reply(id, Map.of(
                    "protocolVersion", params.getOrDefault("protocolVersion", "2025-06-18"),
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", NAME, "version", "1")));
            case "ping" -> reply(id, Map.of());
            case "tools/list" -> reply(id, Map.of("tools", list()));
            case "tools/call" -> reply(id, call(params));
            default -> error(id, -32601, "Unknown method: " + method);
        };
    }

    private List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatEngine.ToolSpec t : tools.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("inputSchema", t.schema());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> call(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        ChatEngine.ToolSpec t = tools.get(name);
        ChatEngine.ToolHandler.Result r;
        if (t == null) {
            r = new ChatEngine.ToolHandler.Result("No tool " + name, true);
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
            Object meta = params.get("_meta");
            String callId = meta instanceof Map<?, ?> mm && mm.get("claudecode/toolUseId") != null
                    ? String.valueOf(mm.get("claudecode/toolUseId")) : null;
            if (callId == null) {
                callId = ids.apply(name); // the call the assistant said it was making
            }
            if (callId == null) {
                callId = "bridge-" + unnamed.incrementAndGet();
            }
            try {
                r = t.handler().call(callId, args);
            } catch (Exception e) {
                r = new ChatEngine.ToolHandler.Result(e.getMessage() == null ? e.toString() : e.getMessage(), true);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", r.text() == null ? "" : r.text())));
        m.put("isError", r.failed());
        return m;
    }

    private static Map<String, Object> reply(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    private enum Fail { FAIL }
}
