package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InsightsTest {
    @TempDir
    Path dir;

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Two hours of an app's log: a heartbeat every second, a few payment errors, a database
     * burst at 10:42, a new cache error from 11:00, ten quiet minutes, and a restart.
     */
    private Path appLog() throws Exception {
        Path f = dir.resolve("app.log");
        try (BufferedWriter w = Files.newBufferedWriter(f)) {
            LocalDateTime t = LocalDateTime.of(2026, 10, 21, 10, 0, 0);
            LocalDateTime end = t.plusHours(2);
            line(w, t, "INFO ", "Started DemoApp in 2.31 seconds (process running for 2.9)");
            int i = 0;
            while (t.isBefore(end)) {
                t = t.plusSeconds(1);
                i++;
                if (!t.isBefore(LocalDateTime.of(2026, 10, 21, 11, 20)) && t.isBefore(LocalDateTime.of(2026, 10, 21, 11, 30))) {
                    continue; // silence
                }
                if (t.equals(LocalDateTime.of(2026, 10, 21, 11, 30))) {
                    line(w, t, "INFO ", "Started DemoApp in 2.47 seconds (process running for 3.1)");
                }
                line(w, t, "INFO ", "Heartbeat ok, queue size " + (i % 17));
                if (i % 2 == 0) {
                    line(w, t, "DEBUG", "Polled 3 messages from topic orders in " + (i % 40) + " ms");
                }
                if (i % 300 == 0) {
                    line(w, t, "ERROR", "Payment declined for order " + i);
                }
                if (i % 120 == 0) {
                    line(w, t, "WARN ", "Slow response from inventory service: " + (900 + i % 300) + " ms");
                }
                boolean burst = !t.isBefore(LocalDateTime.of(2026, 10, 21, 10, 42))
                        && t.isBefore(LocalDateTime.of(2026, 10, 21, 10, 45));
                if (burst && i % 2 == 0) {
                    line(w, t, "ERROR", "Database connection lost after " + (20000 + i) + " ms");
                }
                if (!t.isBefore(LocalDateTime.of(2026, 10, 21, 11, 0)) && i % 60 == 0) {
                    line(w, t, "ERROR", "Cache unreachable at cache-" + (i % 3) + ".internal:6379");
                }
            }
        }
        return f;
    }

    private static void line(BufferedWriter w, LocalDateTime t, String level, String msg) throws Exception {
        w.write(F.format(t) + " " + level + " [main] com.acme.App - " + msg + "\n");
    }

    @Test
    void findsWhatHappenedInALog() throws Exception {
        Path f = appLog();
        try (LogDocument doc = LogDocument.open(f)) {
            assertTrue(doc.awaitIdle(30_000));
            long n = doc.lineCount();
            String[] sample = new String[200];
            doc.readLines(0, sample.length, sample);
            TimeFormat tf = TimeFormat.detect(List.of(sample), 2026);
            try (TimeIndex ti = new TimeIndex(doc, tf).start()) {
                assertTrue(ti.awaitIdle(30_000));
                TimeIndex.Samples s = ti.samples();
                List<Long> errors = new ArrayList<>();
                List<Long> warnings = new ArrayList<>();
                String[] buf = new String[4096];
                for (long first = 0; first < n; first += buf.length) {
                    int k = (int) Math.min(buf.length, n - first);
                    doc.readLines(first, k, buf);
                    for (int i = 0; i < k; i++) {
                        if (buf[i].contains(" ERROR ")) {
                            errors.add(first + i);
                        } else if (buf[i].contains(" WARN ")) {
                            warnings.add(first + i);
                        }
                    }
                }
                MessageStats.Result messages = MessageStats.analyze(doc, null, 0, Long.MAX_VALUE, null, () -> false, p -> {});
                long lastTime = tf.parse(doc.line(n - 1));
                Insights.Report r = Insights.analyze(new Insights.Input(n, s.lines(), s.times(), s.n(), lastTime,
                        errors.stream().mapToLong(Long::longValue).toArray(), errors.size(),
                        warnings.stream().mapToLong(Long::longValue).toArray(), warnings.size(), messages,
                        line -> tf.parse(doc.line(line))));
                String all = r.findings().stream().map(x -> x.kind() + " | " + x.title() + " | " + x.detail())
                        .collect(Collectors.joining("\n"));

                assertTrue(r.summary().startsWith("10:00:00 → 12:00:00  (2 h)"), r.summary());
                assertTrue(r.summary().contains(String.format("%,d errors", errors.size())), r.summary());

                Insights.Finding burst = r.findings().get(0);
                assertEquals(Insights.Kind.BURST, burst.kind(), all);
                assertTrue(burst.title().startsWith("Error burst 10:42:00 – 10:44:58: 90 errors"), all);
                assertTrue(burst.detail().contains("× the usual rate"), all);

                assertTrue(all.contains("NEW_ERROR | Cache unreachable at … | New: began at 11:00:00"), all);
                assertTrue(all.contains("NEW_ERROR | Database connection lost after … ms | New: began at 10:42:00, stopped at 10:44:58"), all);
                assertTrue(all.contains("ERROR_KIND | Payment declined for order … | 22 times   ·   10:05:00 → 12:00:00"), all);
                assertTrue(all.contains("SILENCE | No lines for 10 min | 11:19:59 → 11:30:00"), all);
                assertTrue(all.contains("RESTART | Started 2 times | Last start at 11:30:00"), all);
                assertTrue(all.contains("WARNING_KIND | Slow response from inventory service: … ms"), all);
                assertTrue(all.contains("NOISE | One message is"), all);
                // errors first, then warnings, then the rest
                assertEquals(Insights.Severity.INFO, r.findings().get(r.findings().size() - 1).severity());
            }
        }
    }

    @Test
    void aQuietLogIsCalm() {
        long[] lines = new long[100];
        long[] times = new long[100];
        for (int i = 0; i < 100; i++) {
            lines[i] = i * 64L;
            times[i] = 1_000_000L + i * 1000L;
        }
        Insights.Report r = Insights.analyze(new Insights.Input(6400, lines, times, 100, TimeFormat.NONE,
                new long[0], 0, new long[0], 0, null, null));
        assertEquals(Insights.Kind.CALM, r.findings().get(0).kind());
        assertEquals("Nothing unusual", r.findings().get(0).title());
    }

    @Test
    void formatsDurationsAndMessages() {
        assertEquals("45 s", Insights.duration(45_000));
        assertEquals("4 min 12 s", Insights.duration(252_000));
        assertEquals("1 h 58 min", Insights.duration(7_080_000));
        assertEquals("3 days 2 h", Insights.duration((74L * 3600) * 1000));
        assertEquals("Database connection lost after … ms",
                Insights.display("<*> <*> ERROR [<*>] com.acme.db.Pool - Database connection lost after <*> ms"));
        assertEquals("Started DemoApp in … seconds",
                Insights.display("<*> <*> INFO <*> --- [ main] c.a.DemoApp : Started DemoApp in <*> seconds"));
        assertEquals("no prefix here", Insights.display("no prefix here"));
    }
}
