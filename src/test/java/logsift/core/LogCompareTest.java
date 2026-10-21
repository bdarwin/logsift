package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogCompareTest {
    @TempDir
    Path dir;

    private static String log(Random rnd, int n, boolean bad) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("2026-10-21 10:%02d:%02d.%03d ", i / 3600 % 60, i / 60 % 60, i % 1000));
            int r = rnd.nextInt(100);
            if (bad && r < 3) {
                sb.append("ERROR [worker-").append(rnd.nextInt(8)).append("] c.a.Db - Connection refused to db-")
                        .append(rnd.nextInt(3)).append(":5432\n");
            } else if (r < (bad ? 15 : 3)) {
                sb.append("WARN [worker-").append(rnd.nextInt(8)).append("] c.a.Http - Retrying request (attempt ")
                        .append(1 + rnd.nextInt(5)).append("/5)\n");
            } else if (!bad && r < 5) {
                sb.append("INFO [main] c.a.Cache - Cache warmed with ").append(rnd.nextInt(9999)).append(" entries\n");
            } else {
                sb.append("INFO [worker-").append(rnd.nextInt(8)).append("] c.a.Api - request handled in ")
                        .append(rnd.nextInt(300)).append(" ms\n");
            }
        }
        return sb.toString();
    }

    @Test
    void findsNewGoneAndChangedMessages() throws Exception {
        Path good = dir.resolve("good.log");
        Path bad = dir.resolve("bad.log");
        Files.writeString(good, log(new Random(1), 20_000, false));
        Files.writeString(bad, log(new Random(2), 40_000, true)); // twice as long
        try (LogDocument a = LogDocument.open(good); LogDocument b = LogDocument.open(bad)) {
            assertTrue(a.awaitIdle(10_000) && b.awaitIdle(10_000));
            EntryFormat ef = EntryFormat.detect(Files.readAllLines(good).subList(0, 100));
            LogCompare.Result r = LogCompare.compare(a, ef, b, ef, () -> false, null);
            assertEquals(20_000, r.entriesA());
            assertEquals(40_000, r.entriesB());
            List<LogCompare.Diff> d = r.diffs();
            LogCompare.Diff first = d.get(0);
            assertEquals(LogCompare.Kind.NEW, first.kind(), "new errors come first: " + first);
            assertTrue(first.text().contains("Connection refused to"), first.text());
            assertEquals("ERROR", first.level());
            assertEquals(0, first.countA());
            long refused = Files.readAllLines(bad).stream().filter(l -> l.contains("Connection refused")).count();
            assertEquals(refused, first.countB());
            LogCompare.Diff retry = find(d, "Retrying request");
            assertEquals(LogCompare.Kind.MORE, retry.kind());
            assertTrue(retry.ratio() > 3 && retry.ratio() < 6, "about 4 times as often per entry: " + retry.ratio());
            LogCompare.Diff cache = find(d, "Cache warmed");
            assertEquals(LogCompare.Kind.GONE, cache.kind());
            assertEquals(0, cache.countB());
            LogCompare.Diff handled = find(d, "request handled");
            assertEquals(LogCompare.Kind.SAME, handled.kind(), "normal traffic: same rate");
        }
    }

    private static LogCompare.Diff find(List<LogCompare.Diff> d, String text) {
        return d.stream().filter(x -> x.text().contains(text)).findFirst().orElseThrow(() -> new AssertionError(text));
    }
}
