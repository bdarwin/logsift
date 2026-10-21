package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import logsift.tools.LogGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimeFormatTest {
    @TempDir
    Path dir;

    private static long wall(int y, int mo, int d, int h, int mi, int s, int ms) {
        return LocalDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void daysFromCivilMatchesJavaTime() {
        Random rnd = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            LocalDate d = LocalDate.ofEpochDay(rnd.nextInt(200_000) - 100_000);
            assertEquals(d.toEpochDay(), TimeFormat.daysFromCivil(d.getYear(), d.getMonthValue(),
                    d.getDayOfMonth()), d.toString());
        }
    }

    @Test
    void parsesCommonFormats() {
        long t = wall(2026, 10, 21, 10, 2, 13, 123);
        long t0 = wall(2026, 10, 21, 10, 2, 13, 0);
        check("2026-10-21 10:02:13.123 INFO x", "2026-10-21 10:02:13", t);
        check("2026-10-21T10:02:13,123Z x", "2026-10-21 10:02:13", t);
        check("[2026-10-21 10:02:13.123] x", "2026-10-21 10:02:13", t);
        check("2026/10/21 10:02:13.123 x", "2026/10/21 10:02:13", t);
        check("21-10-2026 10:02:13.123 x", "21-10-2026 10:02:13", t);
        check("21.10.2026 10:02:13.123 x", "21-10-2026 10:02:13", t);
        check("21-Oct-2026 10:02:13.123 x", "21-Oct-2026 10:02:13", t);
        check("Oct 21 10:02:13 host app[1]: x", "Oct 21 10:02:13", t0);
        check("1.2.3.4 - - [21/Oct/2026:10:02:13 +0000] \"GET /\" 200", "[21/Oct/2026:10:02:13]", t0);
        check("{\"level\":\"INFO\",\"ts\":\"2026-10-21T10:02:13.123Z\",\"msg\":\"x\"}",
                "2026-10-21T10:02:13 (inside the line)", t);
        check(t0 / 1000 + ".123 x", "epoch 1729500000", t);
        check("10:02:13.123 [main] x", "10:02:13", t % 86_400_000L);
    }

    private static void check(String line, String formatName, long expected) {
        List<String> sample = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sample.add(line);
            sample.add("\tat continuation line");
        }
        TimeFormat f = TimeFormat.detect(sample, 2026);
        assertEquals(formatName, f == null ? null : f.name(), line);
        assertEquals(expected, f.parse(line), line);
        assertEquals(TimeFormat.NONE, f.parse("\tat continuation line"));
    }

    @Test
    void timeIndexSamplesMatchTheLines() throws Exception {
        Path f = dir.resolve("t.log");
        LogGenerator.generate(f, 3 << 20, 5);
        List<String> lines = Files.readAllLines(f);
        TimeFormat fmt = TimeFormat.detect(lines.subList(0, 500), 2026);
        assertEquals("2026-10-21 10:02:13", fmt.name());
        try (LogDocument doc = LogDocument.open(f, 256 * 1024);
             TimeIndex ti = new TimeIndex(doc, fmt).start()) {
            assertTrue(doc.awaitIdle(10_000));
            assertTrue(ti.awaitIdle(10_000));
            TimeIndex.Samples s = ti.samples();
            assertTrue(s.n() >= lines.size() / 64 - 2, "about one sample per 64 lines: " + s.n());
            for (int i = 0; i < s.n(); i++) {
                long line = s.lines()[i];
                assertEquals(fmt.parse(lines.get((int) line)), s.times()[i], "line " + line);
                if (i > 0) {
                    assertTrue(s.lines()[i] > s.lines()[i - 1], "sorted");
                }
            }
            // finding a time: start before the first line with that time, never after it
            Random rnd = new Random(2);
            for (int k = 0; k < 200; k++) {
                int i = 1 + rnd.nextInt(s.n() - 1);
                long t = s.times()[i];
                long from = s.lineBefore(t);
                long firstAtT = -1;
                for (int l = 0; l < lines.size() && firstAtT < 0; l++) {
                    long lt = fmt.parse(lines.get(l));
                    if (lt != TimeFormat.NONE && lt >= t) {
                        firstAtT = l;
                    }
                }
                assertTrue(from <= firstAtT, "start " + from + " is not after " + firstAtT);
                assertTrue(firstAtT - from <= 2 * LineIndex.SAMPLE + 64, "and close to it");
            }
            assertTrue(s.minTime() <= s.maxTime());
        }
    }
}
