package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import logmaster.tools.LogGenerator;
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
}
