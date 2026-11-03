package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQL over a big table from its cache gives what SQL over the whole table loaded gives, and
 * stores only the columns a query reads.
 */
class TableQueryTest {
    @TempDir
    Path dir;

    /** Rows read from the file, to see whether a query read it. */
    static final class Counting implements ColumnProfile.Lines {
        final ColumnProfile.Lines lines;
        final AtomicLong read = new AtomicLong();

        Counting(ColumnProfile.Lines lines) {
            this.lines = lines;
        }

        @Override
        public void read(long first, int count, String[] into) {
            read.addAndGet(count);
            lines.read(first, count, into);
        }
    }

    /** A result as text, numbers to 9 digits (sums add up in another order). */
    static String text(Sql.Result r) {
        StringBuilder b = new StringBuilder(String.join(" | ", r.columns())).append('\n')
                .append(String.join(" | ", r.types())).append('\n');
        for (Object[] row : r.rows()) {
            for (Object v : row) {
                b.append(v instanceof Double d ? String.format(Locale.ROOT, "%.9g", d) : String.valueOf(v)).append(" | ");
            }
            b.append('\n');
        }
        return b.append(r.more() ? "more" : "all").toString();
    }

    static final List<String> QUERIES = List.of(
            "SELECT count(*) FROM t",
            "SELECT host, count(*), sum(ms), avg(bytes), min(ms), max(bytes) FROM t GROUP BY host ORDER BY host",
            "SELECT line, host, note FROM t WHERE note IS NOT NULL ORDER BY line LIMIT 60",
            "SELECT status, count(*), min(\"at\"), max(\"at\") FROM t GROUP BY status ORDER BY status NULLS FIRST",
            "SELECT date_trunc('hour', \"at\") AS h, count(*) FROM t GROUP BY h ORDER BY h NULLS FIRST LIMIT 40",
            "SELECT * FROM t ORDER BY line LIMIT 25",
            "SELECT * EXCLUDE (note) FROM t WHERE ms > 400 ORDER BY line LIMIT 25",
            "WITH x AS (SELECT \"user\", ms FROM t WHERE ms > 100) SELECT \"user\", quantile_cont(ms, 0.95) AS p95 "
                    + "FROM x GROUP BY \"user\" ORDER BY \"user\" LIMIT 30",
            "SELECT a.host, count(*) FROM t a JOIN t b ON a.line = b.line - 1 WHERE b.status = 'error' GROUP BY 1 ORDER BY 1",
            "SELECT host, ms, row_number() OVER (PARTITION BY host ORDER BY ms DESC, line) AS r FROM t "
                    + "QUALIFY r <= 2 ORDER BY host, r",
            "SELECT host FROM t a WHERE ms > (SELECT avg(ms) FROM t b WHERE b.host = a.host) ORDER BY line LIMIT 20",
            "SELECT note, count(*) FROM t GROUP BY ALL ORDER BY 2 DESC, 1 NULLS FIRST",
            "SELECT count(DISTINCT \"user\"), count(note), count(*) FILTER (WHERE status IS NULL) FROM t",
            "FROM t SELECT bytes WHERE line BETWEEN 100 AND 140 ORDER BY line",
            "SELECT COLUMNS('^(ms|bytes)$') FROM t ORDER BY line LIMIT 10",
            "SELECT host, list(status ORDER BY line)[1:3] FROM t GROUP BY host ORDER BY host",
            "SELECT max(line), min(line) FROM t",
            "DESCRIBE t",
            "SELECT host FROM t WHERE false",
            "SELECT 1 + 1 AS two",
            "SELECT host, (SELECT max(note) FROM t WHERE false) AS none FROM t ORDER BY line LIMIT 5",
            "SELECT line FROM t ORDER BY line");

    @Test
    void theSameAnswersAsTheWholeTableLoaded() throws Exception {
        Path f = PivotCacheTest.table(dir.resolve("t.csv"), 5000, 11);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        try (Sql sql = Sql.open(new Sql.Options(List.of(), null));
             PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT);
             TableQuery tq = new TableQuery(o.fields())) {
            Sql.LogTable whole = sql.table("t", o.doc(), o.format(), o.fields());
            assertTrue(whole.update(() -> false, null));
            for (String q : QUERIES) {
                Sql.Result expected = sql.query(q, 1000, null);
                Sql.Result cached = tq.query(cache, q, 1000, o.lines(), o.doc().lineCount(), () -> false, null, null);
                assertNotNull(cached, q);
                assertEquals(text(expected), text(cached), q);
            }
            // a few rows of many: "more" as well
            Sql.Result some = tq.query(cache, "SELECT line FROM t", 10, o.lines(), o.doc().lineCount(), () -> false, null, null);
            assertEquals(10, some.rows().size());
            assertTrue(some.more());
        }
        o.doc().close();
    }

    @Test
    void storesOnlyTheColumnsAQueryReads() throws Exception {
        Path f = PivotCacheTest.table(dir.resolve("t.csv"), 2000, 12);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        Counting rows = new Counting(o.lines());
        try (PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT);
             TableQuery tq = new TableQuery(o.fields())) {
            Sql.Result r = tq.query(cache, "SELECT host, sum(ms) FROM t GROUP BY host", 100, rows, o.doc().lineCount(),
                    () -> false, null, null);
            assertNotNull(r);
            assertTrue(rows.read.get() > 0, "the file is read once");
            assertTrue(cache.hasColumn(0));
            assertTrue(cache.hasColumn(3));
            for (int c : List.of(1, 2, 4, 5, 6)) {
                assertFalse(cache.hasColumn(c), "column " + c + " isn't read");
            }
            // the same columns again, or fewer, or none: the file isn't read
            rows.read.set(0);
            List<String> again = List.of("SELECT host, avg(ms), max(ms) FROM t WHERE ms > 3 GROUP BY 1",
                    "SELECT count(*) FROM t", "DESCRIBE t", "SELECT ms FROM t ORDER BY ms DESC LIMIT 3");
            for (String q : again) {
                assertNotNull(tq.query(cache, q, 100, rows, o.doc().lineCount(), () -> false, null, null), q);
            }
            assertEquals(0, rows.read.get());
            // one more column: read once more, for that one only
            List<String> said = new ArrayList<>();
            tq.query(cache, "SELECT status, count(*) FROM t GROUP BY 1", 100, rows, o.doc().lineCount(), () -> false, null,
                    said::add);
            assertTrue(rows.read.get() > 0);
            assertTrue(cache.hasColumn(1));
            assertFalse(cache.hasColumn(2));
            assertTrue(said.get(0).startsWith("Preparing this column"), said.toString());
        }
        o.doc().close();
    }

    @Test
    void whatItCantTellIsLeftToTheUsualWay() throws Exception {
        Path f = PivotCacheTest.table(dir.resolve("t.csv"), 500, 13);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        Counting rows = new Counting(o.lines());
        try (PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT);
             TableQuery tq = new TableQuery(o.fields())) {
            for (String q : List.of("PIVOT t ON status USING sum(ms)", "SELECT * FROM read_csv('" + f + "')",
                    "SELECT hots FROM t")) {
                assertNull(tq.query(cache, q, 100, rows, o.doc().lineCount(), () -> false, null, null), q);
            }
            assertEquals(0, rows.read.get(), "nothing read for them");
            // a mistake is told from the empty table, with DuckDB's hint
            SQLException typo = assertThrows(SQLException.class, () -> tq.tryOnEmpty("SELECT hots FROM t"));
            assertTrue(typo.getMessage().contains("\"host\""), typo.getMessage());
            tq.tryOnEmpty("PIVOT t ON status USING sum(ms)"); // fine: it's only that its columns can't be told
            // what isn't a query is refused before anything is read
            for (String q : List.of("DELETE FROM t", "SELECT 1; DROP TABLE t", "COPY t TO 'x.csv'")) {
                assertThrows(SQLException.class,
                        () -> tq.query(cache, q, 100, rows, o.doc().lineCount(), () -> false, null, null), q);
            }
            assertEquals(0, rows.read.get());
            // stopped while storing: nothing kept
            SQLException stopped = assertThrows(SQLException.class, () -> tq.query(cache, "SELECT sum(bytes) FROM t", 100,
                    rows, o.doc().lineCount(), () -> true, null, null));
            assertEquals("Stopped.", stopped.getMessage());
            assertFalse(cache.hasColumn(4));
        }
        o.doc().close();
    }

    @Test
    void tooManyColumnsAtOnceAreLeftToTheUsualWay() throws Exception {
        int n = TableQuery.MAX_NEW_COLUMNS + 6;
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < n; c++) {
            b.append(c == 0 ? "" : ",").append("m").append(c);
        }
        b.append('\n');
        for (int r = 0; r < 300; r++) {
            for (int c = 0; c < n; c++) {
                b.append(c == 0 ? "" : ",").append(r * c);
            }
            b.append('\n');
        }
        Path f = Files.writeString(dir.resolve("wide.csv"), b.toString());
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        try (PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT);
             TableQuery tq = new TableQuery(o.fields())) {
            assertNull(tq.query(cache, "SELECT * FROM t LIMIT 3", 100, o.lines(), o.doc().lineCount(), () -> false, null,
                    null), "every column at once");
            assertNotNull(tq.query(cache, "SELECT m1, m69 FROM t LIMIT 3", 100, o.lines(), o.doc().lineCount(),
                    () -> false, null, null));
            assertTrue(cache.hasColumn(1) && cache.hasColumn(69));
            assertFalse(cache.hasColumn(2));
        }
        o.doc().close();
    }

    @Test
    void sqlInTheCacheReachesNoFile() throws Exception {
        Path f = PivotCacheTest.table(dir.resolve("t.csv"), 200, 14);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        try (PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT);
             TableQuery tq = new TableQuery(o.fields())) {
            assertNotNull(tq.query(cache, "SELECT count(*) FROM t", 10, o.lines(), o.doc().lineCount(), () -> false, null,
                    null));
            List<String> names = Sql.tableColumns(o.fields().titles());
            for (String q : List.of("SELECT count(*) FROM read_csv('" + f + "')",
                    "SELECT * FROM read_text('" + f + "')", "SELECT * FROM glob('" + dir + "/*')")) {
                assertThrows(SQLException.class, () -> cache.query(q, Set.of(), names, o.fields(), 10, null), q);
            }
        }
        o.doc().close();
    }
}
