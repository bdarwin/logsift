package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestTraceTest {

    private static final List<String> LOG = List.of(
            "2026-10-21 10:02:13.100 INFO  [http-1] c.a.Api - GET /orders requestId=r1",          // 0
            "2026-10-21 10:02:13.110 INFO  [http-2] c.a.Api - GET /health requestId=r2",          // 1
            "2026-10-21 10:02:13.150 DEBUG [http-1] c.a.Repo - query orders",                    // 2: r1's thread, no id
            "2026-10-21 10:02:13.200 INFO  [pool-3] c.a.Pay - charging requestId=r1",             // 3
            "2026-10-21 10:02:21.400 ERROR [pool-3] c.a.Pay - card declined requestId=r1",        // 4: 8.2 s later
            "2026-10-21 10:02:21.410 DEBUG [http-2] c.a.Api - health ok",                         // 5
            "2026-10-21 10:02:21.420 WARN  [http-1] c.a.Api - 402 for requestId=r1",              // 6
            "2026-10-21 10:02:21.500 INFO  [http-1] c.a.Api - after the request");                // 7

    private static final LogFormat FORMAT = new LogFormat("test",
            "^(?<time>\\S+ \\S+) (?<level>\\w+)\\s+\\[(?<thread>[^\\]]+)\\] (?<logger>\\S+) - (?<message>.*)$");

    private static RequestTrace.Reader reader() {
        TimeFormat tf = TimeFormat.detect(LOG, 2026);
        return RequestTrace.reader(l -> l < LOG.size() ? LOG.get((int) l) : null, tf, FORMAT);
    }

    private static long[] linesWith(String id) {
        Pattern p = Pattern.compile(RequestIds.regex(id));
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < LOG.size(); i++) {
            if (p.matcher(LOG.get(i)).find()) {
                out.add((long) i);
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    @Test
    void followsOneRequestAcrossThreads() {
        long[] lines = linesWith("r1");
        RequestTrace.Trace t = RequestTrace.build(lines, lines.length, reader(), false, () -> false);
        assertEquals(List.of(0L, 3L, 4L, 6L), t.steps().stream().map(RequestTrace.Step::line).toList());
        assertEquals(List.of("http-1", "pool-3"), t.threads());
        assertEquals(1, t.errors());
        assertEquals(1, t.warnings());
        assertEquals(8320, t.duration());
        assertEquals(2, t.longest(), "the wait before 'card declined'");
        assertEquals(8200, t.steps().get(2).gap());
        assertEquals(-1, t.steps().get(0).gap());
        assertEquals(100, t.steps().get(1).gap());
        assertFalse(t.cut());
    }

    @Test
    void addsLinesOfItsThreadsInBetween() {
        long[] lines = linesWith("r1");
        RequestTrace.Trace t = RequestTrace.build(lines, lines.length, reader(), true, () -> false);
        // line 2: http-1 between its lines 0 and 6; not 1 or 5 (another thread) or 7 (after the request)
        assertEquals(List.of(0L, 2L, 3L, 4L, 6L), t.steps().stream().map(RequestTrace.Step::line).toList());
        assertFalse(t.steps().get(1).withId());
        assertTrue(t.steps().get(2).withId());
    }

    @Test
    void handlesLogsWithoutTimesOrColumns() {
        List<String> plain = List.of("start job=j-1", "other", "end job=j-1");
        RequestTrace.Reader r = RequestTrace.reader(l -> plain.get((int) l), null, null);
        RequestTrace.Trace t = RequestTrace.build(new long[] {0, 2}, 2, r, true, () -> false);
        assertEquals(2, t.steps().size());
        assertEquals(-1, t.duration());
        assertEquals(-1, t.longest());
        assertEquals(List.of(), t.threads());
    }

    @Test
    void cutsVeryLongRequests() {
        int n = RequestTrace.MAX_STEPS + 10;
        long[] lines = new long[n];
        for (int i = 0; i < n; i++) {
            lines[i] = i;
        }
        RequestTrace.Trace t = RequestTrace.build(lines, n, RequestTrace.reader(l -> "x id=1", null, null), false,
                () -> false);
        assertEquals(RequestTrace.MAX_STEPS, t.steps().size());
        assertTrue(t.cut());
    }

    @Test
    void followsInARealDocument(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("app.log");
        Files.write(f, LOG);
        try (LogDocument doc = LogDocument.open(f)) {
            doc.awaitIdle(10_000);
            try (SearchSession s = new SearchSession(doc,
                    new SearchQuery(RequestIds.regex("r1"), true, true, false)).start()) {
                assertTrue(s.awaitIdle(10_000));
                assertEquals(4, s.hits().size());
                assertEquals(3, s.hits().get(1));
            }
        }
    }
}
