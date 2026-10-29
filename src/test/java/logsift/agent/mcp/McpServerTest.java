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
        assertEquals(List.of("list_logs", "open_log", "list_open", "insights", "exceptions", "search", "read_lines",
                "top_messages", "metrics", "query", "search_folder", "analyze_gc", "analyze_threads", "analyze_crash",
                "crash_threads", "crash_modules"),
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

    /** A Java crash report from Linux: a JNI library crashed in memcpy, with a password on the command line. */
    private static final String HS_ERR = """
            #
            # A fatal error has been detected by the Java Runtime Environment:
            #
            #  SIGSEGV (0xb) at pc=0x00007f1c2a1b2c3d, pid=4242, tid=4250
            #
            # JRE version: OpenJDK Runtime Environment (17.0.9+9) (build 17.0.9+9)
            # Java VM: OpenJDK 64-Bit Server VM (17.0.9+9, mixed mode, sharing, tiered, compressed oops, \
            compressed class ptrs, g1 gc, linux-amd64)
            # Problematic frame:
            # C  [libc.so.6+0x18b8c]  __memmove_avx_unaligned_erms+0x5c
            #

            ---------------  S U M M A R Y ------------

            Command Line: -Xmx2g -Ddb.password=hunter2secret com.acme.ImageServer

            Time: Tue Oct 27 10:15:02 2026 UTC elapsed time: 3600.5 seconds (0d 1h 0m 0s)

            ---------------  T H R E A D  ---------------

            Current thread (0x00007f1c3c0f1000):  JavaThread "image-worker-3" [_thread_in_native, id=4250, \
            stack(0x00007f1c0a000000,0x00007f1c0a100000)]

            Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
            C  [libc.so.6+0x18b8c]  __memmove_avx_unaligned_erms+0x5c
            C  [libturbojpeg.so.0+0x2f1a0]  tjDecompress2+0x120
            j  com.acme.jpeg.Decoder.decode([BI)[B+0
            j  com.acme.ImageServer.handle(Lcom/acme/Request;)V+42
            j  java.lang.Thread.run()V+11 java.base@17.0.9

            siginfo: si_signo: 11 (SIGSEGV), si_code: 1 (SEGV_MAPERR), si_addr: 0x0000000000000000

            ---------------  P R O C E S S  ---------------

            Java Threads: ( => current thread )
              0x00007f1c3c0e1000 JavaThread "main" [_thread_blocked, id=4243, stack(0x1,0x2)]
            =>0x00007f1c3c0f1000 JavaThread "image-worker-3" [_thread_in_native, id=4250, stack(0x3,0x4)]
              0x00007f1c3c0f2000 JavaThread "image-worker-4" [_thread_in_native, id=4251, stack(0x5,0x6)]
            Total: 3

            Dynamic libraries:
            7f1c2a000000-7f1c2a200000 r-xp 00000000 08:01 1001   /usr/lib/x86_64-linux-gnu/libc.so.6
            7f1c2b000000-7f1c2b100000 r-xp 00000000 08:01 1002   /opt/acme/native/libturbojpeg.so.0
            7f1c2c000000-7f1c2d000000 r-xp 00000000 08:01 1003   /usr/lib/jvm/java-17/lib/server/libjvm.so

            VM Arguments:
            jvm_args: -Xmx2g -Ddb.password=hunter2secret
            java_command: com.acme.ImageServer
            Launcher Type: SUN_STANDARD

            END.
            """;

    @Test
    void explainsCrashFiles() throws Exception {
        Path f = logs.resolve("hs_err_pid4242.log");
        Files.writeString(f, HS_ERR);
        Answer a = ok("analyze_crash", "path", f.toString());
        assertTrue(a.text().startsWith("Java crash report: SIGSEGV in native code"), a.text());
        assertTrue(a.text().contains("libturbojpeg.so.0 passed a bad pointer or size to the system"), a.text());
        assertTrue(a.text().contains("image-worker-3"), a.text());
        assertTrue(a.text().contains("com.acme.jpeg.Decoder.decode(byte[], int)"), a.text());
        assertFalse(a.text().contains("hunter2secret"), "secrets on the command line are masked: " + a.text());
        assertTrue(a.text().contains("Logsift masked"), a.text());

        Answer t = ok("crash_threads", "path", f.toString(), "filter", "image-worker");
        assertTrue(t.text().contains("Thread 4250 \"image-worker-3\""), t.text());
        assertTrue(t.text().contains("running native code"), t.text());
        assertFalse(t.text().contains("\"main\""), "filtered out: " + t.text());

        Answer m = ok("crash_modules", "path", f.toString());
        assertTrue(m.text().contains("libturbojpeg.so.0"), m.text());
        assertTrue(m.text().contains("not the JDK's or the system's"), m.text());

        Path plain = logs.resolve("plain.log");
        Files.writeString(plain, "nothing to see\n");
        assertTrue(call("analyze_crash", "path", plain.toString()).text().startsWith("Not a crash file"));
        Path outside = dir.resolve("hs_err_pid1.log");
        Files.writeString(outside, HS_ERR);
        assertTrue(call("analyze_crash", "path", outside.toString()).error(), "outside the folders agents may read");
        Path bad = logs.resolve("broken.dmp");
        Files.write(bad, new byte[] {'M', 'D', 'M', 'P', 1, 2, 3});
        assertTrue(call("analyze_crash", "path", bad.toString()).error(), "a damaged dump is an error, not a crash");
    }

    @Test
    void findsLogsByTheirServiceName() throws Exception {
        assertTrue(ok("list_logs").text().startsWith("No log locations are described yet."));
        Path orders = Files.createDirectories(logs.resolve("srv/orders/logs"));
        serviceLog(orders.resolve("app.log"));
        Files.copy(orders.resolve("app.log"), orders.resolve("app.log.1"));
        Files.writeString(orders.resolve("gc.log"), "[0.015s][info][gc] Using G1\n");
        Path outside = Files.createDirectories(dir.resolve("outside/billing"));
        Files.writeString(outside.resolve("billing.log"), "2026-10-28 10:00:00 INFO billed\n");
        logsift.agent.LogCatalog c = logsift.agent.LogCatalog.of(List.of(
                new logsift.agent.LogCatalog.Entry(new logsift.agent.LogCatalog.Service("orders",
                        "Order service: takes orders", List.of(orders.toString()),
                        List.of(new logsift.agent.LogCatalog.FileKind("app.log*", "Application log")), "UTC",
                        "Timeouts before 06:00 are the nightly maintenance."), dir.resolve("logs.json"), false),
                new logsift.agent.LogCatalog.Entry(new logsift.agent.LogCatalog.Service("billing", "", List.of(
                        outside.toString()), List.of(), "", ""), dir.resolve("logs.json"), false)));
        workspace.setCatalog(() -> c);

        String all = ok("list_logs").text();
        assertTrue(all.startsWith("2 services:"), all);
        assertTrue(all.contains("orders — Order service: takes orders"), all);
        assertTrue(all.contains("Times in its logs: UTC"), all);
        assertTrue(all.contains("Notes: Timeouts before 06:00 are the nightly maintenance."), all);
        assertTrue(all.contains("- orders/app: app.log — Application log"), all);
        assertTrue(all.contains("2 files (app.log*)"), all);
        assertTrue(all.contains("- orders/gc: gc.log — "), all);
        assertTrue(all.contains("(agents may not read this folder"), "billing's folder is outside: " + all);
        assertFalse(all.contains("billing.log"), "nothing listed from a folder agents may not read: " + all);
        String one = ok("list_logs", "service", "orders").text();
        assertTrue(one.startsWith("1 service:"), one);
        // the file's name, not its whole path: a temporary folder's random digits can look like a card number to masking
        assertTrue(one.contains("/app.log.1\n") || one.contains("\\app.log.1\n"),
                "every file of a log, asked for one service: " + one);
        assertTrue(ok("list_logs", "service", "shipping").text().startsWith("No service called shipping."));

        Answer opened = ok("open_log", "path", "orders/app");
        assertTrue(opened.text().startsWith("log:1  " + orders.resolve("app.log")), opened.text());
        Answer found = ok("search_folder", "folder", "orders", "query", "Database connection lost");
        // folder search names files by their real path (on Windows the temporary folder's long name)
        assertTrue(found.text().contains(orders.toRealPath().resolve("app.log").toString()), found.text());
        assertTrue(call("open_log", "path", "billing/billing").error(), "outside the folders agents may read");
    }

    @Test
    void answersSqlReadOnly() throws Exception {
        Path f = serviceLog(logs.resolve("orders.log"));
        long errors = Files.readAllLines(f).stream().filter(l -> l.contains(" ERROR ")).count();
        Answer a = ok("query", "log", f.toString(), "sql", "SELECT level, count(*) AS lines FROM log WHERE starts "
                + "GROUP BY level ORDER BY level");
        assertTrue(a.text().contains("ERROR | " + errors), a.text());
        assertTrue(a.text().startsWith("3 rows in "), a.text());
        Answer burst = ok("query", "log", f.toString(), "sql", "SELECT date_trunc('minute', time) AS minute, count(*) AS e "
                + "FROM log WHERE level = 'ERROR' GROUP BY minute ORDER BY e DESC LIMIT 1");
        assertTrue(burst.text().contains("2026-10-21 10:2"), "the burst's minute: " + burst.text());
        // line numbers as the other tools give them
        Answer first = ok("query", "log", f.toString(), "sql", "SELECT min(line) AS l FROM log WHERE text LIKE '%Pricing failed%'");
        Answer search = ok("search", "log", f.toString(), "query", "Pricing failed", "limit", 1);
        String l = first.text().lines().toList().get(2).strip();
        assertTrue(search.text().contains("\n" + String.format(java.util.Locale.ROOT, "%,d", Long.parseLong(l)) + ": "),
                l + " / " + search.text());

        // a table: numbers and times typed
        Path csv = logs.resolve("orders.csv");
        Files.writeString(csv, "order_id,placed_at,amount,region\n1,2026-10-21 10:00:00,12.50,North\n"
                + "2,2026-10-21 10:05:00,3,South\n3,2026-10-21 11:00:00,4.5,North\n");
        Answer t = ok("query", "log", csv.toString(), "sql", "SELECT region, sum(amount) AS total, "
                + "max(placed_at) AS last FROM t GROUP BY region ORDER BY region");
        assertTrue(t.text().contains("North | 17.0 | 2026-10-21 11:00"), t.text());

        // read-only, and SQL can't read files, even in the folders agents may read
        assertTrue(call("query", "log", f.toString(), "sql", "COPY (SELECT 1) TO '" + logs.resolve("x.csv") + "'")
                .error());
        assertFalse(Files.exists(logs.resolve("x.csv")));
        Answer file = call("query", "log", f.toString(), "sql", "SELECT * FROM read_csv('" + csv + "')");
        assertTrue(file.error(), "no file access from SQL: " + file.text());
        assertTrue(call("query", "log", f.toString(), "sql", "SELECT 1; SELECT 2").text().startsWith("One query at a time"));
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
