package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Query tab's examples run as written, on the table read in place and from the cache alike. */
class QueryExamplesTest {
    @TempDir
    Path dir;

    /** time, host, status, ms, then cpu_000…cpu_039 and mem_000…mem_009: a wide table of metrics. */
    private Path metrics(int rows) throws Exception {
        Random rnd = new Random(4);
        StringBuilder b = new StringBuilder("time,host,status,ms");
        for (int i = 0; i < 40; i++) {
            b.append(",cpu_").append(String.format("%03d", i));
        }
        for (int i = 0; i < 10; i++) {
            b.append(",mem_").append(String.format("%03d", i));
        }
        b.append('\n');
        String[] st = {"ok", "ok", "slow", "error"};
        for (int r = 0; r < rows; r++) {
            b.append(String.format("2026-10-28 10:%02d:%02d", r / 60 % 60, r % 60)).append(",web-").append(r % 4).append(',')
                    .append(st[rnd.nextInt(st.length)]).append(',').append(rnd.nextInt(900));
            for (int i = 0; i < 50; i++) {
                b.append(',').append(rnd.nextInt(10_000) / 100.0);
            }
            b.append('\n');
        }
        return Files.writeString(dir.resolve("metrics.csv"), b.toString());
    }

    private static List<QueryExamples.Example> examples(PivotCacheTest.Opened o) {
        List<String> names = Sql.tableColumns(o.fields().titles());
        return QueryExamples.forTable(names, QueryExamples.kinds(names, o.format(), o.lines(), o.doc().lineCount()));
    }

    @Test
    void aWideTableGetsExamplesForItsFamiliesOfColumns() throws Exception {
        PivotCacheTest.Opened o = PivotCacheTest.open(metrics(400));
        List<QueryExamples.Example> ex = examples(o);
        List<String> titles = ex.stream().map(QueryExamples.Example::title).toList();
        assertTrue(titles.contains("The cpu_… columns (40 of them, by a pattern)"), titles.toString());
        assertTrue(titles.contains("Average of each cpu_… column by host"), titles.toString());
        assertTrue(titles.contains("Average of each cpu_… column per minute"), titles.toString());
        assertTrue(titles.contains("Total of the cpu_… columns in each row"), titles.toString());
        assertTrue(titles.contains("The cpu_… columns as rows, highest average first"), titles.toString());
        assertTrue(titles.contains("Count, average and highest ms by host"), titles.toString());
        assertTrue(titles.contains("Find columns by name"), titles.toString());
        assertTrue(ex.stream().anyMatch(e -> e.sql().contains("COLUMNS('^cpu_[0-9]+$')")), "a pattern for the family");
        assertTrue(ex.stream().anyMatch(e -> e.sql().equals("SELECT column_name, column_type\nFROM (DESCRIBE t)\n"
                + "WHERE column_name ILIKE '%cpu%'")));
        o.doc().close();
    }

    @Test
    void everyExampleRunsInPlaceAndFromTheCacheAlike() throws Exception {
        for (Path f : List.of(metrics(600), PivotCacheTest.table(dir.resolve("sales.csv"), 800, 21))) {
            PivotCacheTest.Opened o = PivotCacheTest.open(f);
            List<QueryExamples.Example> ex = examples(o);
            assertTrue(ex.size() >= 5, f + ": " + ex);
            try (Sql inPlace = Sql.open(new Sql.Options(List.of(dir), null));
                 Sql loaded = Sql.open(new Sql.Options(List.of(), null));
                 PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT);
                 TableQuery tq = new TableQuery(o.fields())) {
                inPlace.csv("t", f, ',', o.format().titles(), StandardCharsets.UTF_8);
                Sql.LogTable whole = loaded.table("t", o.doc(), o.format(), o.fields());
                assertTrue(whole.update(() -> false, null));
                for (QueryExamples.Example e : ex) {
                    Sql.Result read = inPlace.query(e.sql(), 1000, null);
                    assertFalse(read.columns().isEmpty(), e.title());
                    Sql.Result expected = loaded.query(e.sql(), 1000, null);
                    Sql.Result cached = tq.query(cache, e.sql(), 1000, o.lines(), o.doc().lineCount(), () -> false, null,
                            null);
                    if (cached != null) { // else left to the usual way (too many columns at once, a PIVOT…)
                        assertEquals(TableQueryTest.text(expected), TableQueryTest.text(cached), f.getFileName() + ": "
                                + e.title());
                    }
                }
            }
            o.doc().close();
        }
    }

    @Test
    void namesAreQuotedOnlyWhereSqlNeedsIt() {
        assertEquals("host", QueryExamples.name("host"));
        assertEquals("cpu_001", QueryExamples.name("cpu_001"));
        assertEquals("\"Host Name\"", QueryExamples.name("Host Name"));
        assertEquals("\"timestamp\"", QueryExamples.name("timestamp"));
        assertEquals("\"order\"", QueryExamples.name("order"));
        assertEquals("\"a\"\"b\"", QueryExamples.name("a\"b"));
        // a family whose name has regex characters in it is matched as written
        QueryExamples.Family f = QueryExamples.family(List.of("a.b(1)", "x.y_1", "x.y_2", "x.y_3", "x.y_4", "x.y_5"),
                Map.of());
        assertEquals("^x\\.y_[0-9]+$", f.regex());
    }

    @Test
    void numbersAndTimesOnlyWhereEveryValueIsOne() throws Exception {
        // as DuckDB reads the file: one "never" among the times makes the column text there
        Path f = Files.writeString(dir.resolve("k.csv"), "n,maybe,t,d,s\n1,2,2026-10-28 10:00:00,2026-10-28,a\n"
                + "2.5,n/a,2026-10-28T10:00:01Z,2026-10-29,b\n,,,,\n-3e2,4,2026-10-28 10:00:02.5,2026-10-30,c\n");
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        assertEquals(Map.of("n", Pivot.Kind.NUMBER, "maybe", Pivot.Kind.TEXT, "t", Pivot.Kind.TIME, "d", Pivot.Kind.TIME,
                "s", Pivot.Kind.TEXT), QueryExamples.kinds(List.of("n", "maybe", "t", "d", "s"), o.format(), o.lines(),
                o.doc().lineCount()));
        o.doc().close();
    }

    @Test
    void aLogKeepsItsExamples() {
        List<String> titles = QueryExamples.forLog(List.of("line", "starts", "time", "level", "logger", "message", "text"))
                .stream().map(QueryExamples.Example::title).toList();
        assertEquals(List.of("Lines per level", "Errors per minute", "The most frequent errors (numbers blanked)",
                "Errors and warnings by logger", "Durations in messages (… 345 ms): slowest first", "Lines with a text",
                "Lines per hour"), titles);
    }
}
