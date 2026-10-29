package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Following a request, merging logs, explaining profiles: the tools as an agent calls them. */
class TraceToolsTest {

    @TempDir
    Path dir;
    Path logs;
    Workspace w;

    @BeforeEach
    void start() throws IOException {
        logs = Files.createDirectories(dir.resolve("logs"));
        Path home = Files.createDirectories(dir.resolve("home"));
        w = new Workspace(new Workspace.Options(new Scope(List.of(logs), home), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 30_000, List.of(), null));
    }

    @AfterEach
    void stop() {
        w.close();
    }

    private String run(Tool t, Object... kv) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return t.run(new Tool.Args(m), w);
    }

    /** Orders: ORD-4711 goes through http-1, waits 2.5 s, fails on pay-3, is answered on http-1. */
    private Path ordersLog() throws IOException {
        return Files.writeString(logs.resolve("api.log"), """
                2026-10-28 10:00:00.000 INFO  [http-1] c.a.Api - received order=ORD-4711
                2026-10-28 10:00:00.100 INFO  [http-2] c.a.Api - received order=ORD-1000
                2026-10-28 10:00:00.200 INFO  [http-1] c.a.Stock - checking stock
                2026-10-28 10:00:02.700 INFO  [pay-3] c.a.Pay - charging order=ORD-4711
                2026-10-28 10:00:02.900 ERROR [pay-3] c.a.Pay - card declined for order=ORD-4711
                2026-10-28 10:00:03.000 INFO  [http-1] c.a.Api - answered order=ORD-4711
                2026-10-28 10:00:03.100 INFO  [http-2] c.a.Api - answered order=ORD-1000
                """);
    }

    @Test
    void followsARequestAcrossThreads() throws Exception {
        Path f = ordersLog();
        String a = run(TraceTools.FOLLOW_REQUEST, "id", "ORD-4711", "log", f.toString());
        assertTrue(a.startsWith("ORD-4711: 5 lines, 2026-10-28 10:00:00 → 2026-10-28 10:00:03 (3.0 s)."), a);
        assertTrue(a.contains("Threads: http-1, pay-3."), a);
        assertTrue(a.contains("1 error, 0 warnings."), a);
        assertTrue(a.contains("The longest wait: 2.5 s before line 4: "), "where the time went: " + a);
        assertTrue(a.contains("3  (+200 ms)  (same thread) 2026-10-28 10:00:00.200 INFO  [http-1] c.a.Stock - checking stock"), "its thread in between: " + a);
        assertFalse(a.contains("ORD-1000"), a);
        String plain = run(TraceTools.FOLLOW_REQUEST, "id", "ORD-4711", "log", f.toString(), "threads", false);
        assertTrue(plain.startsWith("ORD-4711: 4 lines"), plain);
        assertTrue(run(TraceTools.FOLLOW_REQUEST, "id", "ORD-9999", "log", f.toString()).startsWith("No line names ORD-9999"));
    }

    @Test
    void followsThroughSeveralLogsAndAFolder() throws Exception {
        ordersLog();
        Files.writeString(logs.resolve("pay.log"), """
                2026-10-28 10:00:02.600 INFO  [pay-worker] c.a.PayGateway - request order=ORD-4711
                2026-10-28 10:00:02.850 WARN  [pay-worker] c.a.PayGateway - gateway said no order=ORD-4711
                """);
        String a = run(TraceTools.FOLLOW_REQUEST, "id", "ORD-4711", "log", logs.resolve("api.log") + ", "
                + logs.resolve("pay.log"));
        assertTrue(a.startsWith("ORD-4711: 7 lines in 2 files"), a);
        int gw = a.indexOf("[pay.log] 1  ");
        int charge = a.indexOf("[api.log] 4  ");
        assertTrue(gw > 0 && charge > gw, "in time order across the files: " + a);
        String folder = run(TraceTools.FOLLOW_REQUEST, "id", "ORD-4711", "folder", logs.toString());
        assertTrue(folder.startsWith("ORD-4711: 6 lines in 2 files"), "a folder: the lines naming it: " + folder);
    }

    @Test
    void mergesLogsByTimeIntoAHandleOtherToolsTake() throws Exception {
        StringBuilder a1 = new StringBuilder("""
                2026-10-28 10:00:00.000 INFO  [main] c.a.A - a first
                2026-10-28 10:00:02.000 INFO  [main] c.a.A - a second
                """);
        StringBuilder b1 = new StringBuilder("""
                2026-10-28 10:00:01.000 INFO  [main] c.a.B - b first
                2026-10-28 10:00:03.000 ERROR [main] c.a.B - b failed
                java.lang.IllegalStateException: no
                \tat c.a.B.run(B.java:1)
                """);
        for (int i = 10; i < 30; i++) { // enough lines to know their layout
            a1.append(String.format("2026-10-28 10:00:%02d.000 INFO  [main] c.a.A - a tick %d%n", i, i));
            b1.append(String.format("2026-10-28 10:00:%02d.500 INFO  [main] c.a.B - b tick %d%n", i, i));
        }
        Files.writeString(logs.resolve("a.log"), a1);
        Files.writeString(logs.resolve("b.log"), b1);
        String a = run(TraceTools.MERGE_LOGS, "logs", logs.resolve("a.log") + "," + logs.resolve("b.log"));
        assertTrue(a.startsWith("Merged 2 logs by time into log:3:"), a);
        assertTrue(a.contains("- <a> " + logs.resolve("a.log")), a);
        String h = "log:3";
        String lines = run(LogTools.READ_LINES, "log", h, "from", 1, "count", 10);
        List<String> order = lines.lines().filter(l -> l.matches("\\d+: .*")).toList();
        assertTrue(order.get(0).contains("a first") && order.get(1).contains("b first") && order.get(2).contains("a second")
                && order.get(3).contains("b failed"), lines);
        assertTrue(order.get(4).contains("IllegalStateException"), "the stack trace stays with its line: " + lines);
        String found = run(LogTools.SEARCH, "log", h, "query", "failed");
        assertTrue(found.startsWith("1 line matches"), found);
        Path merged = w.shownPath(h);
        assertFalse(merged.startsWith(logs), "written in Logsift's temporary folder, not next to the logs: " + merged);
        assertEquals(List.of("a.log", "b.log"), listNames(logs), "nothing written next to the logs");
        w.close();
        assertFalse(Files.exists(merged), "removed when the session ends");
    }

    private static List<String> listNames(Path d) throws IOException {
        try (var s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void explainsACollapsedProfile() throws Exception {
        Path p = Files.writeString(logs.resolve("cpu.collapsed"), """
                java.lang.Thread.run;com.acme.App.main;com.acme.Pricing.compute 70
                java.lang.Thread.run;com.acme.App.main;java.util.HashMap.get 20
                java.lang.Thread.run;com.acme.App.main;com.acme.Db.query;java.net.SocketInputStream.read 10
                """);
        String a = run(TraceTools.ANALYZE_PROFILE, "path", p.toString());
        assertTrue(a.startsWith("cpu.collapsed: 100 samples."), a);
        assertTrue(a.contains(" 1. com.acme.Pricing.compute  70 samples (70.0%)  ← own code"), a);
        assertTrue(a.contains(" 2. java.util.HashMap.get  20 samples (20.0%)\n"), "the JDK's isn't own code: " + a);
        assertTrue(a.contains("Most time with what they call (own code):\n 1. com.acme.App.main  100 samples (100.0%)"), a);
        assertTrue(a.contains("1. 70 samples (70.0%)\n     com.acme.Pricing.compute\n     com.acme.App.main"), a);
        Path text = Files.writeString(logs.resolve("notes.txt"), "just text\n");
        Tool.Failure f = assertThrows(Tool.Failure.class, () -> run(TraceTools.ANALYZE_PROFILE, "path", text.toString()));
        assertTrue(f.getMessage().contains("is not a profile"), f.getMessage());
    }

    @Test
    void explainsAFlightRecording() throws Exception {
        // some machines (virtual ARM ones) take a while to give CPU samples: record until there are some
        Path jfr = logs.resolve("cpu.jfr");
        long samples = 0;
        for (int round = 0; round < 5 && samples == 0; round++) {
            try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
                r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
                r.start();
                long end = System.nanoTime() + 1_000_000_000L;
                double x = 0;
                while (System.nanoTime() < end) {
                    x += Math.sqrt(x + 1);
                }
                assertTrue(x > 0);
                r.stop();
                r.dump(jfr);
            }
            samples = jdk.jfr.consumer.RecordingFile.readAllEvents(jfr).stream()
                    .filter(e -> e.getEventType().getName().equals("jdk.ExecutionSample")).count();
        }
        if (samples == 0) {
            // this machine gives no samples at all: the tool says so plainly
            Exception e = assertThrows(Exception.class, () -> run(TraceTools.ANALYZE_PROFILE, "path", jfr.toString()));
            assertTrue(e.getMessage().contains("no samples"), e.getMessage());
            return;
        }
        String a = run(TraceTools.ANALYZE_PROFILE, "path", jfr.toString());
        assertTrue(a.contains("samples."), a);
        assertTrue(a.contains("Most time of their own:\n 1. "), a);
    }

    @Test
    void readsOnlyInsideTheFoldersAllowed() throws Exception {
        Path outside = Files.writeString(dir.resolve("secret.log"), "2026-10-28 10:00:00.000 INFO [m] c.X - order=ORD-4711\n");
        assertThrows(Exception.class, () -> run(TraceTools.FOLLOW_REQUEST, "id", "ORD-4711", "log", outside.toString()));
        assertThrows(Exception.class, () -> run(TraceTools.FOLLOW_REQUEST, "id", "ORD-4711", "folder", dir.toString()));
        Path a = ordersLog();
        assertThrows(Exception.class, () -> run(TraceTools.MERGE_LOGS, "logs", a + "," + outside));
        assertThrows(Exception.class, () -> run(TraceTools.ANALYZE_PROFILE, "path", outside.toString()));
    }

    @Test
    void saysWhatIsMissing() throws Exception {
        Path f = ordersLog();
        assertTrue(assertThrows(Tool.Failure.class, () -> run(TraceTools.FOLLOW_REQUEST, "log", f.toString()))
                .getMessage().contains("id"));
        assertTrue(assertThrows(Tool.Failure.class, () -> run(TraceTools.FOLLOW_REQUEST, "id", "ORD-4711"))
                .getMessage().startsWith("Give log"));
        assertTrue(assertThrows(Tool.Failure.class, () -> run(TraceTools.FOLLOW_REQUEST, "id", "ab", "log", f.toString()))
                .getMessage().startsWith("An id of at least 3"));
        assertTrue(assertThrows(Tool.Failure.class, () -> run(TraceTools.MERGE_LOGS, "logs", f.toString()))
                .getMessage().startsWith("Give two or more logs"));
        assertTrue(assertThrows(Tool.Failure.class, () -> run(TraceTools.ANALYZE_PROFILE))
                .getMessage().contains("path"));
        assertEquals(List.of("follow_request", "merge_logs", "analyze_profile"),
                TraceTools.all().stream().map(Tool::name).toList());
    }
}
