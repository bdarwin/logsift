package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filters that look in one column: CSV, regex layouts and JSON lines. */
class ColumnFilterTest {
    @TempDir
    Path dir;

    /** The lines (0-based) a single filter matches, run through the filter engine. */
    private List<Long> run(String content, String name, ColumnFilter.Op op, String column, String text,
                           boolean matchCase, boolean regex) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        try (LogDocument doc = LogDocument.open(f, 4096);
             FilterEngine engine = new FilterEngine(doc)) {
            assertTrue(doc.awaitIdle(10_000));
            List<String> sample = new ArrayList<>();
            for (int i = 0; i < Math.min(200, doc.lineCount()); i++) {
                sample.add(doc.line(i));
            }
            LogFormat format = Formats.detect(sample, List.of(), name, null, 2026).columns();
            SearchQuery q = new SearchQuery(text, matchCase, regex, false);
            engine.configure(List.of(new FilterEngine.Spec('A', q, new ColumnFilter(format, column, op, q))),
                    new Formula.Ref('A'));
            assertTrue(engine.awaitIdle(10_000));
            FilterResult r = engine.result();
            List<Long> out = new ArrayList<>();
            for (int i = 0; i < r.count(); i++) {
                out.add(r.lineAt(i));
            }
            return out;
        }
    }

    private static String csv() {
        StringBuilder sb = new StringBuilder("time,host,status,duration_ms,message\n");
        String[] hosts = {"web-1", "web-2", "web-10"};
        for (int i = 0; i < 300; i++) {
            sb.append(String.format("2026-10-21 10:%02d:%02d,%s,%d,%d,\"call web-1 %d\"%n", i / 60, i % 60,
                    hosts[i % 3], i % 50 == 0 ? 500 : 200, i * 3, i));
        }
        return sb.toString();
    }

    @Test
    void csvValueEquals() throws IOException {
        List<Long> got = run(csv(), "a.csv", ColumnFilter.Op.EQUALS, "host", "web-1", true, false);
        assertEquals(100, got.size(), "web-1 only, not web-10, not the message that names web-1");
        assertEquals(1L, got.get(0), "the header row (line 0) is no row of values");
        assertEquals(4L, got.get(1));
    }

    @Test
    void csvContainsAndRegex() throws IOException {
        assertEquals(200, run(csv(), "b.csv", ColumnFilter.Op.CONTAINS, "host", "WEB-1", false, false).size(),
                "web-1 and web-10, any case");
        assertEquals(0, run(csv(), "c.csv", ColumnFilter.Op.CONTAINS, "host", "WEB-1", true, false).size());
        assertEquals(100, run(csv(), "d.csv", ColumnFilter.Op.CONTAINS, "host", "-1\\d", false, true).size());
    }

    @Test
    void csvNumbers() throws IOException {
        // duration_ms is i * 3: above 600 from i = 201
        assertEquals(99, run(csv(), "e.csv", ColumnFilter.Op.MORE, "duration_ms", "600", false, false).size());
        assertEquals(6, run(csv(), "f.csv", ColumnFilter.Op.MORE, "status", "499", false, false).size());
        assertEquals(10, run(csv(), "g.csv", ColumnFilter.Op.LESS, "duration_ms", "30", false, false).size());
    }

    @Test
    void unitsCompare() throws IOException {
        String log = "time,op,took\n"
                + "2026-10-21 10:00:00,a,800 ms\n"
                + "2026-10-21 10:00:01,b,1.5 s\n"
                + "2026-10-21 10:00:02,c,2s\n"
                + "2026-10-21 10:00:03,d,-\n";
        assertEquals(List.of(2L, 3L), run(log, "u.csv", ColumnFilter.Op.MORE, "took", "1 s", false, false));
        assertEquals(List.of(1L), run(log, "v.csv", ColumnFilter.Op.LESS, "took", "1000", false, false));
    }

    @Test
    void regexLayoutColumnsAndStackTraces() throws IOException {
        String log = """
                2026-10-21 10:00:00.000 INFO  [main] com.acme.App - started on main
                2026-10-21 10:00:01.000 ERROR [worker-1] com.acme.Job - failed in main
                java.lang.IllegalStateException: main
                	at com.acme.Job.run(Job.java:12)
                2026-10-21 10:00:02.000 INFO  [worker-2] com.acme.Job - done
                2026-10-21 10:00:03.000 INFO  [main] com.acme.App - stopping
                """;
        assertEquals(List.of(0L, 5L), run(log, "app.log", ColumnFilter.Op.EQUALS, "thread", "main", true, false),
                "the thread column only, not stack lines or messages naming main");
        assertEquals(List.of(1L, 4L), run(log, "app2.log", ColumnFilter.Op.CONTAINS, "thread", "worker", false, false));
    }

    @Test
    void jsonLines() throws IOException {
        String log = """
                {"@timestamp":"2026-10-21T10:00:00Z","level":"INFO","message":"ERROR in text only"}
                {"@timestamp":"2026-10-21T10:00:01Z","level":"ERROR","message":"failed"}
                {"@timestamp":"2026-10-21T10:00:02Z","level":"INFO","message":"ok"}
                {"@timestamp":"2026-10-21T10:00:03Z","level":"ERROR","message":"failed again"}
                """;
        assertEquals(List.of(1L, 3L), run(log, "app.json", ColumnFilter.Op.EQUALS, "level", "ERROR", true, false));
    }

    @Test
    void refusesWhatCannotWork() {
        LogFormat f = LogFormat.detectCsv(List.of("a,b", "1,2", "3,4"), true);
        SearchQuery q = SearchQuery.literal("x");
        assertThrows(IllegalArgumentException.class, () -> new ColumnFilter(f, "host", ColumnFilter.Op.EQUALS, q));
        assertThrows(IllegalArgumentException.class, () -> new ColumnFilter(f, "a", ColumnFilter.Op.MORE, q));
        assertEquals("A > 5", new ColumnFilter(f, "a", ColumnFilter.Op.MORE, SearchQuery.literal("5")).describe());
        // the same filter keeps its search; another column starts a new one
        assertEquals(new FilterEngine.Spec('A', q, new ColumnFilter(f, "a", ColumnFilter.Op.EQUALS, q)),
                new FilterEngine.Spec('A', q, new ColumnFilter(f, "a", ColumnFilter.Op.EQUALS, q)));
        assertTrue(!new FilterEngine.Spec('A', q, new ColumnFilter(f, "a", ColumnFilter.Op.EQUALS, q))
                .equals(new FilterEngine.Spec('A', q, new ColumnFilter(f, "b", ColumnFilter.Op.EQUALS, q))));
    }

    @Test
    void betweenIncludesOrLeavesOutItsEnd() throws IOException {
        assertEquals(31, run(csv(), "h.csv", ColumnFilter.Op.BETWEEN, "duration_ms", "30..120", false, false).size(),
                "10 to 40 times 3: both ends");
        assertEquals(30, run(csv(), "i.csv", ColumnFilter.Op.BETWEEN, "duration_ms", "30..<120", false, false).size(),
                "120 left out");
        assertThrows(IllegalArgumentException.class, () -> new ColumnFilter(LogFormat.detectCsv(List.of("a,b", "1,2",
                "3,4"), true), "a", ColumnFilter.Op.BETWEEN, SearchQuery.literal("5..<1")));
    }
}
