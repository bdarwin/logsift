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
        List<String> all = new java.util.ArrayList<>(List.of("line"));
        all.addAll(names);
        return QueryExamples.of("t", all, QueryExamples.kinds(names, o.format(), o.lines(), o.doc().lineCount()), Map.of());
    }

    @Test
    void aWideTableGetsExamplesForItsFamiliesOfColumns() throws Exception {
        PivotCacheTest.Opened o = PivotCacheTest.open(metrics(400));
        List<QueryExamples.Example> ex = examples(o);
        List<String> titles = ex.stream().map(QueryExamples.Example::title).toList();
        // quackjvm's examples, for what Logsift found the columns hold
        assertTrue(titles.contains("The mean of each of 40 cpu_N columns"), titles.toString());
        assertTrue(titles.contains("The mean of each of 40 cpu_N columns for each host"), titles.toString());
        assertTrue(titles.contains("The mean of each of 40 cpu_N columns each minute"), titles.toString());
        assertTrue(titles.contains("The total of the 40 cpu_N columns in each row"), titles.toString());
        assertTrue(titles.contains("Each of the 40 cpu_N columns: its mean and highest, highest mean first"),
                titles.toString());
        assertTrue(titles.contains("Rows for each host"), titles.toString());
        assertTrue(titles.contains("Columns whose name has a word in it"), titles.toString());
        assertTrue(ex.stream().anyMatch(e -> e.sql().contains("COLUMNS('^cpu_[0-9]+$')")), "a pattern for the family");
        assertTrue(ex.stream().anyMatch(e -> e.sql().equals(
                "SELECT column_name, column_type FROM (DESCRIBE t) WHERE column_name ILIKE '%cpu%'")));
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
                 TableQuery tq = TableQuery.open(f, o.format(), "", o.fields(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT)) {
                inPlace.csv("t", f, ',', o.format().titles(), StandardCharsets.UTF_8);
                Sql.LogTable whole = loaded.table("t", o.doc(), o.format(), o.fields());
                assertTrue(whole.update(() -> false, null));
                for (QueryExamples.Example e : ex) {
                    Sql.Result read = inPlace.query(e.sql(), 1000, null);
                    assertFalse(read.columns().isEmpty(), e.title());
                    Sql.Result expected = loaded.query(e.sql(), 1000, null);
                    Sql.Result cached = tq.query(e.sql(), 1000, o.lines(), o.doc().lineCount(), () -> false, null,
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
    void theRestOfARowOverTwoLinesIsNotARow() throws Exception {
        // read line by line, before the rows over several lines are known (as on a slower machine)
        Path f = Files.writeString(dir.resolve("multi.csv"), "time,host,note,ms\n2026-10-01 00:00:00,web-1,plain,5\n"
                + "2026-10-01 00:00:01,web-2,\"two\nlines\",7\n2026-10-01 00:00:02,web-1,x,9\n");
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        String[] raw = Files.readString(f).split("\n");
        ColumnProfile.Lines byLine = (first, count, into) -> {
            for (int i = 0; i < count; i++) {
                into[i] = first + i < raw.length ? raw[(int) first + i] : null;
            }
        };
        Map<String, Pivot.Kind> k = QueryExamples.kinds(List.of("time", "host", "note", "ms"), o.format(), byLine, raw.length);
        assertEquals(Pivot.Kind.TIME, k.get("time"), k.toString());
        assertEquals(Pivot.Kind.NUMBER, k.get("ms"), k.toString());
        o.doc().close();
    }

    @Test
    void aLogGetsItsExamples() {
        List<String> titles = QueryExamples.of("log", List.of("line", "starts", "time", "level", "logger", "message", "text"),
                null, Map.of()).stream().map(QueryExamples.Example::title).toList();
        for (String t : List.of("Lines per level", "Errors per minute", "The most frequent errors, numbers blanked",
                "Errors and warnings by logger", "Durations in the messages (\"... 345 ms\"), slowest first",
                "Rows whose message mentions a word", "Rows each hour by time")) {
            assertTrue(titles.contains(t), t + " in " + titles);
        }
        assertTrue(titles.stream().noneMatch(t -> t.equals("The range of line")), "line counts lines: no range of it");
    }

    @Test
    void otherOpenTablesAreJoinedByAColumnTheyShare() {
        List<QueryExamples.Example> ex = QueryExamples.of("t", List.of("line", "order_id", "customer_id", "amount"),
                Map.of("order_id", Pivot.Kind.NUMBER, "customer_id", Pivot.Kind.NUMBER, "amount", Pivot.Kind.NUMBER),
                new java.util.LinkedHashMap<>(Map.of("customers", List.of("line", "customer_id", "name"))));
        assertTrue(ex.stream().anyMatch(e -> e.title().equals("Joined to customers by customer_id")
                && e.sql().equals("SELECT * FROM t JOIN customers USING (customer_id) LIMIT 10")), ex.toString());
        List<QueryExamples.Example> none = QueryExamples.of("log", List.of("line", "time", "message"), null,
                Map.of("prices", List.of("line", "sku", "price")));
        assertTrue(none.stream().noneMatch(e -> e.sql().contains("JOIN")), "only line in common: no join");
    }
}
