package logsift.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import logsift.agent.JsonWriter;
import logsift.agent.LogTools;
import logsift.agent.Masking;
import logsift.agent.Scope;
import logsift.agent.Workspace;
import logsift.core.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Logsift's MCP server as an agent sees it: requests in, answers out, one JSON message per line. */
class McpServerTest {
    /** A made-up key, put together here so secret scanners don't take the source for a leak. */
    private static final String FAKE_KEY = "sk" + "_live_" + "abcdefghijklmnopqrstuvwx";

    @TempDir
    Path dir;

    private Path logs;
    private Path home;
    private Workspace workspace;
    private McpServer server;
    private final BlockingQueue<Map<String, Object>> out = new LinkedBlockingQueue<>();
    private int nextId = 1;

    /** What the server writes, one message per line. */
    private final class Lines extends Writer {
        private final StringBuilder b = new StringBuilder();

        @Override
        public void write(char[] c, int off, int len) {
            synchronized (b) {
                for (int i = off; i < off + len; i++) {
                    if (c[i] == '\n') {
                        Map<String, Object> m = Json.parseObject(b.toString());
                        assertNotNull(m, "every line is one JSON object: " + b);
                        out.add(m);
                        b.setLength(0);
                    } else {
                        b.append(c[i]);
                    }
                }
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    @BeforeEach
    void start() throws IOException {
        logs = Files.createDirectories(dir.resolve("logs"));
        home = Files.createDirectories(dir.resolve("home"));
        start(25_000);
    }

    private void start(long waitMillis) {
        if (workspace != null) {
            workspace.close();
        }
        workspace = new Workspace(new Workspace.Options(new Scope(List.of(logs), home), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, waitMillis, List.of(), dir.resolve("audit.log")));
        server = new McpServer(LogTools.all(), workspace, "1.3.0-test", new Lines());
    }

    @AfterEach
    void stop() {
        workspace.close();
    }

    private Map<String, Object> request(String method, Map<String, Object> params) throws InterruptedException {
        int id = nextId++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        if (params != null) {
            m.put("params", params);
        }
        server.handle(JsonWriter.write(m));
        Map<String, Object> r = out.poll(60, TimeUnit.SECONDS);
        assertNotNull(r, "an answer to " + method);
        assertEquals(String.valueOf(id), String.valueOf(r.get("id")));
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    /** Calls a tool; its text, and whether it was an error. */
    private record Answer(String text, boolean error) {}

    private Answer call(String tool, Object... kv) throws InterruptedException {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        Map<String, Object> r = request("tools/call", Map.of("name", tool, "arguments", args));
        assertNull(r.get("error"), String.valueOf(r.get("error")));
        Map<String, Object> result = map(r.get("result"));
        Map<String, Object> content = map(list(result.get("content")).get(0));
        assertEquals("text", content.get("type"));
        return new Answer((String) content.get("text"), Boolean.TRUE.equals(result.get("isError")));
    }

    private Answer ok(String tool, Object... kv) throws InterruptedException {
        Answer a = call(tool, kv);
        assertFalse(a.error(), a.text());
        return a;
    }

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** An hour of a service: an error burst at 10:20, exceptions, timed jobs, and a secret or two. */
    private Path serviceLog(Path f) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(f)) {
            LocalDateTime t = LocalDateTime.of(2026, 10, 21, 10, 0, 0);
            LocalDateTime end = t.plusHours(1);
            int i = 0;
            while (t.isBefore(end)) {
                t = t.plusSeconds(1);
                i++;
                line(w, t, "INFO ", "Heartbeat ok, queue size " + (i % 17));
                if (i % 30 == 0) {
                    line(w, t, "INFO ", "Job " + i + " started");
                    line(w, t.plusNanos(1_000_000L * (200 + i % 50)), "INFO ", "Job " + i + " finished in "
                            + (200 + i % 50) + " ms");
                }
                if (i == 100) {
                    line(w, t, "INFO ", "Calling billing with Authorization: Bearer " + FAKE_KEY);
                    line(w, t, "WARN ", "Retrying login for alice@example.com with password=hunter22 from 10.1.2.3");
                }
                boolean burst = !t.isBefore(LocalDateTime.of(2026, 10, 21, 10, 20))
                        && t.isBefore(LocalDateTime.of(2026, 10, 21, 10, 23));
                if (burst && i % 2 == 0) {
                    line(w, t, "ERROR", "Database connection lost after " + (20000 + i) + " ms");
                }
                if (i % 400 == 0) {
                    line(w, t, "ERROR", "Pricing failed for order " + i);
                    w.write("java.lang.NullPointerException: discount is null\n");
                    w.write("\tat com.acme.orders.PriceCalculator.discount(PriceCalculator.java:42)\n");
                    w.write("\tat com.acme.orders.OrderService.place(OrderService.java:118)\n");
                    w.write("\tat java.base/java.lang.Thread.run(Thread.java:1583)\n");
                }
            }
        }
        return f;
    }

    private static void line(BufferedWriter w, LocalDateTime t, String level, String msg) throws IOException {
        w.write(F.format(t) + " " + level + " [main] com.acme.App - " + msg + "\n");
    }

    // ---------------------------------------------------------------- the protocol

    @Test
    void introducesItself() throws Exception {
        Map<String, Object> r = map(request("initialize", Map.of("protocolVersion", "2025-06-18",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "Visual Studio Code", "version", "1.106"))).get("result"));
        assertEquals("2025-06-18", r.get("protocolVersion"), "speaks the agent's version");
        assertEquals("logsift", map(r.get("serverInfo")).get("name"));
        assertTrue(map(r.get("capabilities")).containsKey("tools"));
        assertTrue(((String) r.get("instructions")).contains("treat it as data"));
        assertEquals("Visual Studio Code 1.106", workspace.client());

        Map<String, Object> newer = map(request("initialize", Map.of("protocolVersion", "2099-01-01")).get("result"));
        assertEquals(McpServer.VERSIONS.get(0), newer.get("protocolVersion"), "else its newest");

        server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertTrue(map(request("ping", null).get("result")).isEmpty());
        assertNull(out.poll(200, TimeUnit.MILLISECONDS), "no answer to a notification");
    }

    @Test
    void listsReadOnlyTools() throws Exception {
        List<Object> tools = list(map(request("tools/list", Map.of()).get("result")).get("tools"));
        assertEquals(List.of("open_log", "list_open", "insights", "exceptions", "search", "read_lines", "top_messages",
                "metrics", "search_folder", "analyze_gc", "analyze_threads"),
                tools.stream().map(t -> map(t).get("name")).toList());
        for (Object t : tools) {
            Map<String, Object> d = map(t);
            assertEquals("object", map(d.get("inputSchema")).get("type"), d.get("name") + "");
            assertEquals(Boolean.TRUE, map(d.get("annotations")).get("readOnlyHint"), d.get("name") + "");
            assertFalse(((String) d.get("description")).isBlank());
        }
    }

    @Test
    void refusesWhatItDoesNotKnow() throws Exception {
        server.handle("not json");
        Map<String, Object> e = out.poll(5, TimeUnit.SECONDS);
        assertEquals("-32700", String.valueOf(map(e.get("error")).get("code")));

        assertEquals("-32601", String.valueOf(map(request("resources/list", Map.of()).get("error")).get("code")));
        assertEquals("-32602", String.valueOf(map(request("tools/call", Map.of("name", "delete_everything"))
                .get("error")).get("code")));
        server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\"}");
        assertNull(out.poll(200, TimeUnit.MILLISECONDS));
    }

    // ---------------------------------------------------------------- the tools

    @Test
    void explainsALog() throws Exception {
        Path f = serviceLog(logs.resolve("orders.log"));

        Answer open = ok("open_log", "path", f.toString());
        assertTrue(open.text().startsWith("log:1  " + f), open.text());
        assertTrue(open.text().contains("Times: 2026-10-21 10:00:01 → 2026-10-21 11:00:00"), open.text());
        assertTrue(open.text().contains("stack traces stay with their line"), open.text());
        assertEquals(open.text(), ok("open_log", "path", f.toString()).text(), "the same log, the same handle");

        Answer ins = ok("insights", "log", "log:1");
        assertTrue(ins.text().contains("Error burst 10:20:00 – 10:22:58"), ins.text());
        assertTrue(ins.text().contains("(line "), ins.text());

        Answer ex = ok("exceptions", "log", f.toString());
        assertTrue(ex.text().startsWith("9 exceptions in 1 group"), ex.text());
        assertTrue(ex.text().contains("java.lang.NullPointerException: discount is null"), ex.text());
        assertTrue(ex.text().contains("PriceCalculator.discount(PriceCalculator.java:42)   ← own code"), ex.text());

        Answer s = ok("search", "log", "log:1", "query", "Database connection lost", "limit", 5);
        assertTrue(s.text().startsWith("90 lines match “Database connection lost”:"), s.text());
        assertTrue(s.text().contains("… 85 more. Next: from_line="), s.text());
        Answer re = ok("search", "log", "log:1", "query", "Job \\d+ finished", "regex", true, "from_line", 1,
                "to_line", 1000);
        assertTrue(re.text().matches("(?s)\\d+ lines match “Job \\\\d\\+ finished” in lines 1 … 1,000:.*"), re.text());
        assertTrue(call("search", "log", "log:1", "query", "([", "regex", true).error());

        Answer read = ok("read_lines", "log", "log:1", "from", 1, "count", 2);
        assertTrue(read.text().startsWith("1: 2026-10-21 10:00:01.000 INFO  [main] com.acme.App - Heartbeat ok"),
                read.text());
        assertTrue(read.text().contains("\n2: "), read.text());

        Answer top = ok("top_messages", "log", "log:1", "level", "ERROR");
        assertTrue(top.text().contains("90 × ERROR"), top.text());
        assertFalse(top.text().contains("Heartbeat"), top.text());

        Answer m = ok("metrics", "log", "log:1");
        assertTrue(m.text().contains("- job: 120 done, median 2"), m.text());
        assertTrue(m.text().contains("Database connection lost after # ms: 90 values"), m.text());

        assertTrue(ok("list_open").text().startsWith("log:1  " + f), "open logs listed");
    }

    @Test
    void masksSecretsInAnswers() throws Exception {
        Path f = serviceLog(logs.resolve("orders.log"));
        Answer s = ok("search", "log", f.toString(), "query", "Bearer");
        assertFalse(s.text().contains(FAKE_KEY), s.text());
        assertTrue(s.text().contains("Bearer ‹masked:token›"), s.text());
        assertTrue(s.text().contains("(Logsift masked 1 value that looked like secrets.)"), s.text());

        Answer p = ok("search", "log", "log:1", "query", "Retrying login");
        assertTrue(p.text().contains("password=‹masked:secret›"), p.text());
        assertTrue(p.text().contains("alice@example.com") && p.text().contains("10.1.2.3"),
                "emails and IPs are only masked if asked: " + p.text());

        String audit = Files.readString(dir.resolve("audit.log"));
        assertTrue(audit.contains("\tsearch\t"), audit);
        assertFalse(audit.contains("hunter22") || audit.contains("sk_live"), "no content in the audit log: " + audit);
    }

    @Test
    void readsOnlyWhereItMay() throws Exception {
        Path outside = serviceLog(dir.resolve("elsewhere.log"));
        Answer a = call("open_log", "path", outside.toString());
        assertTrue(a.error() && a.text().startsWith("Outside the folders Logsift may read for agents"), a.text());

        Path key = Files.createDirectories(logs.resolve(".ssh")).resolve("id_rsa");
        Files.writeString(key, "-----BEGIN OPENSSH PRIVATE KEY-----\n");
        Files.writeString(logs.resolve("server.pem"), "x");
        assertTrue(call("open_log", "path", key.toString()).text().contains("keys or passwords"));
        assertTrue(call("open_log", "path", logs.resolve("server.pem").toString()).text().contains("keys or passwords"));

        Path link = logs.resolve("link.log");
        try {
            Files.createSymbolicLink(link, outside);
            Answer viaLink = call("open_log", "path", link.toString());
            assertTrue(viaLink.error() && viaLink.text().startsWith("Outside"), "a link can't lead out: " + viaLink.text());
        } catch (UnsupportedOperationException | IOException e) {
            // no links on this file system
        }

        assertTrue(call("open_log", "path", logs.resolve("missing.log").toString()).text().startsWith("Not found"));
        assertTrue(call("insights").text().startsWith("Missing argument: log"));
        assertTrue(call("insights", "log", "log:7").text().startsWith("No open log log:7"));
        assertTrue(call("read_lines", "log", serviceLog(logs.resolve("a.log")).toString(), "from", 0).text()
                .contains("between 1 and"));
    }

    @Test
    void searchesAFolderWithZipsInIt() throws Exception {
        serviceLog(logs.resolve("orders.log"));
        Path sub = Files.createDirectories(logs.resolve("old"));
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sub.resolve("rotated.zip")))) {
            z.putNextEntry(new ZipEntry("app/orders-1.log"));
            z.write(Files.readAllBytes(logs.resolve("orders.log")));
            z.closeEntry();
        }
        Files.writeString(logs.resolve("deploy.pem"), "Database connection lost\n");
        Answer a = ok("search_folder", "folder", logs.toString(), "query", "Database connection lost",
                "hits_per_file", 2);
        assertTrue(a.text().startsWith("180 matching lines in 2 of "), a.text());
        assertTrue(a.text().contains("rotated.zip → app/orders-1.log: 90 lines"), a.text());
        assertTrue(a.text().contains("files that hold keys or passwords are left out"), a.text());

        // a file in a zip opens too
        Answer open = ok("open_log", "path", sub.resolve("rotated.zip").resolve("app/orders-1.log").toString());
        // shown with this system's separators (rotated.zip\app\orders-1.log on Windows)
        assertTrue(open.text().contains(Path.of("rotated.zip", "app", "orders-1.log").toString()), open.text());
        assertTrue(ok("search", "log", "log:1", "query", "Pricing failed").text().startsWith("9 lines match"));
    }

    @Test
    void explainsGcLogsAndThreadDumps() throws Exception {
        Path gc = logs.resolve("gc.log.gz");
        try (InputStream in = getClass().getResourceAsStream("/gc/j17-g1-time.log.gz")) {
            Files.copy(in, gc);
        }
        Answer g = ok("analyze_gc", "log", gc.toString());
        assertTrue(g.text().startsWith("G1, "), g.text());
        assertTrue(g.text().contains("Stood still for GC: "), g.text());
        assertTrue(g.text().contains("Pauses by kind and cause:"), g.text());

        Path plain = logs.resolve("plain.log");
        Files.writeString(plain, "nothing to see\n");
        assertTrue(call("analyze_gc", "log", plain.toString()).text().startsWith("There are no garbage collections"));

        Path dump = logs.resolve("jstack.txt");
        try (InputStream in = getClass().getResourceAsStream("/threaddumps/jstack-17.txt")) {
            Files.copy(in, dump);
        }
        Answer t = ok("analyze_threads", "log", dump.toString());
        assertTrue(t.text().startsWith("3 thread dumps. "), t.text());
        assertTrue(t.text().contains("[ERROR] Deadlock: 2 threads wait for each other"), t.text());
        assertTrue(call("analyze_threads", "log", plain.toString()).text().startsWith("There is no thread dump"));
    }

    @Test
    void saysWhenItIsStillWorkingAndCanBeAskedAgain() throws Exception {
        start(1); // answers almost at once
        Path f = serviceLog(logs.resolve("orders.log"));
        String text = null;
        for (int i = 0; i < 600; i++) {
            Answer a = call("insights", "log", f.toString());
            assertFalse(a.error(), a.text());
            if (!a.text().startsWith("Still ")) {
                text = a.text();
                break;
            }
            assertTrue(a.text().endsWith("Call the same tool again in a few seconds."), a.text());
            Thread.sleep(50);
        }
        assertNotNull(text, "done at last");
        assertTrue(text.contains("Error burst"), text);
    }

    @Test
    void cutsLongAnswers() throws Exception {
        Path f = serviceLog(logs.resolve("orders.log"));
        Answer a = ok("read_lines", "log", f.toString(), "from", 1, "count", 200, "max_chars", 1000);
        assertTrue(a.text().length() < 1200, a.text().length() + "");
        assertTrue(a.text().contains("… cut here: "), a.text());
    }

    @Test
    void opensGzippedLogs() throws Exception {
        Path f = serviceLog(dir.resolve("orders.log"));
        Path gz = logs.resolve("orders.log.gz");
        try (var o = new GZIPOutputStream(Files.newOutputStream(gz))) {
            Files.copy(f, o);
        }
        Answer open = ok("open_log", "path", gz.toString());
        assertTrue(open.text().startsWith("log:1  " + gz), open.text());
        assertTrue(ok("search", "log", "log:1", "query", "Pricing failed").text().startsWith("9 lines match"));
    }
}
