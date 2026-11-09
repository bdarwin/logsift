package logsift.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import logsift.tools.LogGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFormatTest {
    @TempDir
    Path dir;

    private static LogFormat detect(String line) {
        List<String> sample = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sample.add(line);
            sample.add("\tat com.acme.X.y(X.java:1)");
        }
        return LogFormat.detect(sample);
    }

    @Test
    void detectsCommonLayouts() {
        LogFormat f = detect("2026-10-21 10:02:13.123 ERROR [worker-7] c.a.Service - Database lost");
        assertEquals(List.of("source", "time", "level", "thread", "logger", "message"), f.columns());
        assertArrayEquals(new String[] {null, "2026-10-21 10:02:13.123", "ERROR", "worker-7", "c.a.Service",
            "Database lost"}, f.split("2026-10-21 10:02:13.123 ERROR [worker-7] c.a.Service - Database lost", null));
        assertNull(f.split("\tat com.acme.X.y(X.java:1)", null), "continuation lines don't split");

        f = detect("2026-10-21 10:02:13,123 [main] WARN  com.acme.Foo - slow");
        assertEquals("log4j (time [thread] level logger - message)", f.name());
        f = detect("Oct 21 10:02:13 web1 sshd[812]: Failed password for root");
        assertEquals("syslog (time host process[pid]: message)", f.name());
        String[] v = f.split("Oct 21 10:02:13 web1 sshd[812]: Failed password for root", null);
        assertEquals("web1", v[f.columns().indexOf("host")]);
        assertEquals("812", v[f.columns().indexOf("pid")]);
        f = detect("10.0.0.7 - alice [21/Oct/2026:10:02:13 +0000] \"GET /api HTTP/1.1\" 500 123");
        assertEquals("500", f.split("10.0.0.7 - alice [21/Oct/2026:10:02:13 +0000] \"GET /api HTTP/1.1\" 500 123",
                null)[f.columns().indexOf("status")]);
        f = detect("<node2> 2026-10-21 10:02:13 INFO [main] c.a.X - merged line");
        assertEquals("node2", f.split("<node2> 2026-10-21 10:02:13 INFO [main] c.a.X - merged line", null)[0]);
        assertNull(detect("just some text without structure"));
    }

    @Test
    void detectsDotNetLayouts() {
        LogFormat f = detect("2026-10-21 10:02:14.456 +00:00 [ERR] Acme.Shop.OrderService: Failed to place order 1002");
        assertEquals("Serilog (time [LVL] message)", f.name());
        String line = "2026-10-21 10:02:14.456 +00:00 [ERR] Acme.Shop.OrderService: Failed to place order 1002";
        assertEquals("2026-10-21 10:02:14.456 +00:00", f.value(line, "time"));
        assertEquals("ERR", f.value(line, "level"));
        assertEquals("Acme.Shop.OrderService", f.value(line, "logger"));
        assertEquals("Failed to place order 1002", f.value(line, "message"));
        String plain = "2026-10-21 10:02:13 +00:00 [INF] Order 1001 placed in 45 ms";
        assertNull(f.value(plain, "logger"), "no source context: the first word is the message's");
        assertEquals("Order 1001 placed in 45 ms", f.value(plain, "message"));
        // its levels are found through their own spellings
        java.util.regex.Pattern errors = java.util.regex.Pattern.compile(Levels.lineRegex(f, List.of("ERROR")));
        assertTrue(errors.matcher(line).find());
        assertFalse(errors.matcher(plain).find());

        f = detect("2026-10-21 10:02:14.4567|ERROR|Acme.Shop.OrderService|Failed");
        assertEquals("NLog (time|LEVEL|logger|message)", f.name());
        assertEquals("Acme.Shop.OrderService", f.value("2026-10-21 10:02:14.4567|ERROR|Acme.Shop.OrderService|Failed", "logger"));

        f = detect("2026-10-21 10:02:14,456 [7] ERROR Acme.Shop.OrderService [(null)] - Failed");
        assertEquals("log4net (time [thread] level logger [context] - message)", f.name());
        assertEquals("7", f.value("2026-10-21 10:02:14,456 [7] ERROR Acme.Shop.OrderService [(null)] - Failed", "thread"));

        f = detect("fail: Acme.Shop.OrderService[1001]");
        assertEquals("ASP.NET Core console (level: category[event] message)", f.name());
        assertEquals("1001", f.value("fail: Acme.Shop.OrderService[1001]", "event"));
        errors = java.util.regex.Pattern.compile(Levels.lineRegex(f, List.of("ERROR")));
        assertTrue(errors.matcher("fail: Acme.Shop.OrderService[1001]").find(), "fail is an error");
        assertFalse(errors.matcher("info: Acme.Shop.OrderService[0]").find());
        assertNull(Levels.lineRegex(detect("2026-10-21 10:02:13.123 ERROR [w] c.a.S - x"), List.of("ERROR")),
                "the usual words are searched for as before");
    }

    @Test
    void aspNetCoreConsoleLinesStartEntries() {
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            log.add("info: Acme.Shop.OrdersController[0]");
            log.add("      Order " + i + " placed");
        }
        EntryFormat e = EntryFormat.detect(log);
        assertTrue(e.startsEntry("fail: Acme.Shop.OrderService[1001]"));
        assertFalse(e.startsEntry("      Order 1 placed"));
        assertFalse(e.startsEntry("information about it"), "only the level word with its colon");
    }

    @Test
    void springBootsLayout() {
        String boot3 = "2026-10-22T10:00:00.123+02:00  INFO 4242 --- [orders] [nio-8080-exec-1] c.a.o.Api"
                + "                                : order 7 placed";
        LogFormat f = detect(boot3);
        assertEquals("Spring Boot (time level pid --- [thread] logger : message)", f.name());
        String[] v = f.split(boot3, null);
        assertEquals("4242", v[f.columns().indexOf("pid")]);
        assertEquals("orders", v[f.columns().indexOf("app")]);
        assertEquals("nio-8080-exec-1", v[f.columns().indexOf("thread")]);
        assertEquals("c.a.o.Api", v[f.columns().indexOf("logger")]);
        assertEquals("order 7 placed", v[f.columns().indexOf("message")]);
        String boot2 = "2021-05-01 10:00:00.000  WARN 77 --- [           main] c.a.Billing                      : slow";
        f = detect(boot2);
        v = f.split(boot2, null);
        assertEquals("main", v[f.columns().indexOf("thread")]);
        assertNull(v[f.columns().indexOf("app")]);
        assertEquals("WARN", v[f.columns().indexOf("level")]);
        assertEquals("slow", v[f.columns().indexOf("message")]);
    }

    @Test
    void countsColumnValuesLikeNaive() throws Exception {
        Path p = dir.resolve("c.log");
        LogGenerator.generate(p, 4 << 20, 9);
        List<String> lines = Files.readAllLines(p);
        LogFormat f = LogFormat.detect(lines.subList(0, 300));
        assertTrue(f != null);
        Map<String, Long> naive = new HashMap<>();
        for (String l : lines) {
            String[] v = f.split(l, null);
            if (v != null) {
                naive.merge(v[f.columns().indexOf("thread")], 1L, Long::sum);
            }
        }
        try (LogDocument doc = LogDocument.open(p, 300 * 1024)) {
            assertTrue(doc.awaitIdle(10_000));
            ColumnStats.Result r = ColumnStats.count(doc, f, "thread", () -> false);
            Map<String, Long> got = new HashMap<>();
            r.values().forEach(x -> got.put(x.value(), x.count()));
            assertEquals(naive, got);
            assertTrue(r.values().get(0).count() >= r.values().get(r.values().size() - 1).count(), "sorted");
        }
    }

    @Test
    void thousandsOfColumnsNamedAtOnce() {
        // a wide export: repeated and empty names get numbers, quickly however many there are
        List<String> header = new ArrayList<>();
        for (int i = 0; i < 8000; i++) {
            header.add(i % 2 == 0 ? "value" : i % 3 == 0 ? "" : "m" + i);
        }
        long t0 = System.nanoTime();
        List<String> names = LogFormat.columnNames(header);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(8000, names.size());
        assertEquals(8000, new java.util.HashSet<>(names).size(), "each name once");
        assertEquals(List.of("value", "m1", "value 2", "column 4", "value 3", "m5"), names.subList(0, 6));
        assertTrue(ms < 2000, "named in " + ms + " ms");
    }

    @Test
    void aTableIsFoundByEachSeparator() {
        // a separator the header hasn't is skipped without reading the rows for it
        for (String sep : List.of(",", ";", "\t", "|")) {
            List<String> sample = new ArrayList<>();
            sample.add(String.join(sep, "id", "host", "ms"));
            for (int i = 0; i < 20; i++) {
                sample.add(String.join(sep, String.valueOf(i), "web-" + i % 3, String.valueOf(i * 7)));
            }
            LogFormat f = LogFormat.detectCsv(sample, false);
            assertTrue(f != null && f.isCsv(), sep);
            assertEquals(List.of("id", "host", "ms"), f.columns(), sep);
        }
        // a quoted header (with the separator inside quotes only on another line) is still read
        List<String> quoted = new ArrayList<>(List.of("\"id\";\"note\"", "1;\"a; b\"", "2;c", "3;d", "4;e", "5;f"));
        LogFormat q = LogFormat.detectCsv(quoted, true);
        assertEquals(List.of("id", "note"), q.columns());
    }
}
