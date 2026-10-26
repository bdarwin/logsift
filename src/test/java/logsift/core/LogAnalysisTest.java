package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A log's analyses with no window at all, as tools and agents will ask for them. */
class LogAnalysisTest {
    @TempDir
    Path dir;

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** An hour of a service: heartbeats, an error burst at 10:20, exceptions with stack traces, timed jobs. */
    private Path serviceLog() throws IOException {
        Path f = dir.resolve("orders.log");
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
                if (i % 240 == 0) {
                    line(w, t, "WARN ", "Slow response from inventory service: " + (900 + i % 300) + " ms");
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

    @Test
    void explainsALogWithoutAWindow() throws Exception {
        try (LogAnalysis a = LogAnalysis.open(serviceLog(), List.of())) {
            assertTrue(a.awaitReady(() -> false));
            assertNotNull(a.formats().times(), "timestamps found");
            assertNotNull(a.formats().entries(), "entries found, so stack traces stay with their line");

            Exceptions.Result ex = a.exceptions(() -> false, null);
            assertEquals(1, ex.groups().size(), ex.toString());
            assertEquals(9, ex.exceptions());

            Insights.Report r = a.insights(() -> false);
            String all = r.findings().stream().map(x -> x.kind() + " | " + x.title() + " | " + x.detail())
                    .collect(Collectors.joining("\n"));
            assertTrue(r.summary().startsWith("10:00:01 → 11:00:00"), r.summary());
            assertEquals(90 + 9, r.errors(), all);
            assertEquals(15, r.warnings(), all);
            assertTrue(all.contains("Error burst 10:20:00 – 10:22:58: 91 errors"), all); // one pricing error falls in it
            assertTrue(all.contains("NullPointerException"), all);
            assertSame(r, a.insights(() -> false), "made once");

            Metrics me = a.metrics(() -> false);
            assertNotNull(me);
            assertTrue(me.toString().contains("Job"), me.toString());
        }
    }

    @Test
    void stopsWhenAsked() throws Exception {
        try (LogAnalysis a = LogAnalysis.open(serviceLog(), List.of())) {
            assertNull(a.insights(() -> true));
            assertNotNull(a.insights(() -> false), "and can be asked again");
        }
    }

    @Test
    void refusesWhatItCannotRead() {
        assertThrows(IOException.class, () -> LogAnalysis.open(dir.resolve("missing.log"), List.of()));
    }

    @Test
    void levelWordsAreTheUsualOnes() {
        assertEquals(List.of("ERROR", "FATAL", "SEVERE", "CRITICAL"), Levels.words(Levels.ERROR));
        assertEquals(List.of("WARN", "WARNING"), Levels.words(Levels.WARN));
        assertEquals(List.of(), Levels.words("NOPE"));
    }
}
