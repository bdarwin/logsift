package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SpansTest {

    private static final LogFormat FORMAT = new LogFormat("test",
            "^(?<time>\\S+ \\S+) (?<level>\\w+)\\s+\\[(?<thread>[^\\]]+)\\] (?<logger>\\S+) - (?<message>.*)$");

    private static String line(int sec, int ms, String level, String thread, String msg) {
        return String.format("2026-10-21 10:%02d:%02d.%03d %-5s [%s] c.a.Jobs - %s", sec / 60, sec % 60, ms, level,
                thread, msg);
    }

    /** Jobs that start and finish on a pool (by name), one that never finishes, and noise. */
    private static List<String> jobsLog() {
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String job = "sync-" + i;
            log.add(line(i * 10, 0, "INFO", "pool-" + (i % 3), "Started job " + job));
            log.add(line(i * 10, 5, "DEBUG", "main", "heartbeat " + i));
            if (i != 17) { // sync-17 hangs
                int took = i == 30 ? 7900 : 100 + i * 10;
                log.add(line(i * 10 + took / 1000, took % 1000, "INFO", "pool-" + ((i + 1) % 3),
                        "Finished job " + job + " in " + took + " ms"));
            }
        }
        log.sort(String::compareTo);
        return log;
    }

    private static Spans.Result analyze(List<String> log) {
        Pattern lineStart = Pattern.compile("^\\d{4}");
        TestDoc d = new TestDoc(log);
        MessageStats.Result m = d.messages();
        TimeFormat tf = TimeFormat.detect(log, 2026);
        RequestTrace.Reader r = RequestTrace.reader(l -> l < log.size() ? log.get((int) l) : null, tf, FORMAT);
        return Spans.analyze(m.templates(), (t, c) -> {
            Pattern p = Pattern.compile(t.regex());
            return java.util.stream.IntStream.range(0, log.size())
                    .filter(i -> lineStart.matcher(log.get(i)).find() && p.matcher(log.get(i)).find())
                    .mapToLong(i -> i).toArray();
        }, r, () -> false);
    }

    @Test
    void pairsStartsAndEndsByTheirName() {
        List<String> log = jobsLog();
        Spans.Result r = analyze(log);
        assertEquals(1, r.kinds().size(), "" + r.kinds());
        Spans.Kind k = r.kinds().get(0);
        assertEquals("job", k.name());
        assertEquals("by its job", k.matchedBy());
        assertEquals(39, k.count());
        assertEquals(7900, k.max());
        assertEquals("sync-30", k.slowest().get(0).key());
        assertEquals(1, k.unfinishedCount());
        assertEquals("sync-17", k.unfinished().get(0).key());
        assertTrue(k.median() > 200 && k.median() < 400, "" + k.median());
        assertTrue(log.get((int) k.slowest().get(0).endLine()).contains("Finished job sync-30"));
    }

    @Test
    void pairsByThreadWhenNoValueIsShared() {
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String t = "worker-" + (i % 4);
            log.add(line(i * 5, 0, "INFO", t, "Begin import"));
            if (i != 9) {
                log.add(line(i * 5 + 1, 500, "INFO", t, "Import complete"));
            }
        }
        log.sort(String::compareTo);
        Spans.Kind k = analyze(log).kinds().get(0);
        assertEquals("by thread", k.matchedBy());
        assertEquals("import", k.name());
        assertEquals(19, k.count());
        assertEquals(1500, k.median());
        assertEquals(1, k.unfinishedCount());
    }

    @Test
    void wordsAndPairs() {
        Spans.Words w = Spans.words("Started job <*> for tenant <*>");
        assertEquals(List.of("job", "tenant"), w.subject());
        assertTrue(w.starts());
        Spans.Words e = Spans.words("Finished job <*> in <*> ms");
        assertTrue(e.ends());
        assertEquals(List.of("job"), e.subject());
        assertEquals(List.of(), Spans.analyze(List.of(), (t, c) -> new long[0], null, () -> false).kinds());
        java.util.regex.Matcher m = Spans.capturing("INFO [<*>] c.a.Jobs - Finished job <*> in <*> ms")
                .matcher("2026-10-21 10:00:00.100 INFO  [pool-1] c.a.Jobs - Finished job sync-3 in 120 ms");
        assertTrue(m.find());
        assertEquals("sync-3", m.group(2));
        assertNotNull(Spans.Result.EMPTY);
    }

    /** Message kinds of a list of lines, through a real document. */
    private record TestDoc(List<String> lines) {
        MessageStats.Result messages() {
            try {
                java.nio.file.Path f = java.nio.file.Files.createTempFile("spans", ".log");
                java.nio.file.Files.write(f, lines);
                try (LogDocument doc = LogDocument.open(f)) {
                    doc.awaitIdle(10_000);
                    return MessageStats.analyze(doc, EntryFormat.detect(lines), 0, Long.MAX_VALUE, null, () -> false,
                            null);
                } finally {
                    java.nio.file.Files.deleteIfExists(f);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
