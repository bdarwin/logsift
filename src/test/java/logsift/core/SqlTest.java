package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SQL over tables and logs: views, loaded logs, read-only, the sandbox. */
class SqlTest {

    @TempDir
    Path dir;

    @BeforeEach
    void duckdb() {
        assertTrue(Sql.available(), "DuckDB runs on every system Logsift is built for: " + Sql.unavailableReason());
    }

    private Sql open() throws Exception {
        return Sql.open(new Sql.Options(List.of(dir), dir.resolve("tmp")));
    }

    @Test
    void csvIsAViewWithLineNumbers() throws Exception {
        Path f = dir.resolve("orders.csv");
        Files.writeString(f, "order_id,region,amount\n1,North,12.5\n2,South,3\n3,North,4.5\n");
        try (Sql sql = open()) {
            sql.csv("t", f, ',', true, java.nio.charset.StandardCharsets.UTF_8);
            Sql.Result r = sql.query("SELECT region, sum(amount) AS total, min(line) AS first FROM t GROUP BY region "
                    + "ORDER BY region", 100, null);
            assertEquals(List.of("region", "total", "first"), r.columns());
            assertEquals(2, r.rows().size());
            assertEquals("North", r.rows().get(0)[0]);
            assertEquals(17.0, ((Number) r.rows().get(0)[1]).doubleValue(), 1e-9);
            assertEquals(2L, ((Number) r.rows().get(0)[2]).longValue(), "the first row is the file's line 2");
            assertFalse(r.more());
        }
    }

    @Test
    void csvWithMixedLineEnds() throws Exception {
        Path f = dir.resolve("mixed.csv");
        Files.writeString(f, "order_id,region,amount\n1,North,12.5\r\n2,South,3\r\n3,North,4.5\r\n");
        try (Sql sql = open()) {
            sql.csv("t", f, ',', true, java.nio.charset.StandardCharsets.UTF_8);
            Sql.Result r = sql.query("SELECT count(*), sum(amount) FROM t", 10, null);
            assertEquals(3L, ((Number) r.rows().get(0)[0]).longValue());
            assertEquals(20.0, ((Number) r.rows().get(0)[1]).doubleValue(), 1e-9);
        }
    }

    @Test
    void csvWithAColumnNoRowFills() throws Exception {
        Path f = dir.resolve("extra.csv");
        Files.writeString(f, "order id,customer name,amount,notes,exported by\n1,Ann Lee,10.5,,\n2,Bob,,late\n"
                + "3,Cy Doe,7,\n4,Di,3,gift\n");
        try (Sql sql = open()) {
            sql.csv("t", f, ',', true, java.nio.charset.StandardCharsets.UTF_8);
            Sql.Result r = sql.query("SELECT count(*), sum(amount), count(\"exported by\") FROM t", 10, null);
            assertEquals(4L, ((Number) r.rows().get(0)[0]).longValue());
            assertEquals(20.5, ((Number) r.rows().get(0)[1]).doubleValue(), 1e-9);
            assertEquals(0L, ((Number) r.rows().get(0)[2]).longValue());
            Sql.Result late = sql.query("SELECT line FROM t WHERE notes = 'late'", 10, null);
            assertEquals(3L, ((Number) late.rows().get(0)[0]).longValue());
        }
        Path g = dir.resolve("more.csv");
        Files.writeString(g, "id,name,amount\n1,Ann,2,\n2,Bob,3,x\n3,Cy,4,\n");
        try (Sql sql = open()) {
            sql.csv("t", g, ',', LogFormat.detectCsv(Files.readAllLines(g), true).titles(),
                    java.nio.charset.StandardCharsets.UTF_8);
            Sql.Result r = sql.query("SELECT count(*), sum(amount) FROM t", 10, null);
            assertEquals(3L, ((Number) r.rows().get(0)[0]).longValue());
            assertEquals(9.0, ((Number) r.rows().get(0)[1]).doubleValue(), 1e-9);
            Sql.Result x = sql.query("SELECT line, \"column 4\" FROM t WHERE name = 'Bob'", 10, null);
            assertEquals(3L, ((Number) x.rows().get(0)[0]).longValue());
            assertEquals("x", x.rows().get(0)[1]);
        }
    }

    @Test
    void aLogIsLoadedWithItsLinesTimesAndColumns() throws Exception {
        Path f = dir.resolve("app.log");
        Files.writeString(f, """
                2026-10-28 10:00:00,123 INFO  [main] c.a.App - started
                2026-10-28 10:00:01,000 ERROR [http-1] c.a.Orders - order 7 failed
                java.lang.IllegalStateException: no stock
                \tat c.a.Orders.place(Orders.java:42)

                2026-10-28 10:00:02,500 WARN  [http-2] c.a.Orders - slow: 1200 ms
                """);
        try (LogAnalysis la = LogAnalysis.open(f, List.of()); Sql sql = open()) {
            la.awaitReady(() -> false);
            Sql.LogTable t = sql.log("log", la.document(), la.formats().columns(), la.formats().times(),
                    la.formats().entries());
            assertTrue(t.update(() -> false, null));
            assertEquals(6, t.loaded());
            assertTrue(t.sqlColumns().containsAll(List.of("line", "starts", "time", "level", "thread", "logger", "message",
                    "text")), t.sqlColumns().toString());
            Sql.Result r = sql.query("SELECT line, level, logger, time, starts FROM log ORDER BY line", 100, null);
            assertEquals(6, r.rows().size(), "every line, the blank one too");
            assertEquals(List.of(1L, "INFO", "c.a.App", "2026-10-28 10:00:00.123", true), List.of(r.rows().get(0)));
            assertEquals(2L, r.rows().get(1)[0]);
            assertEquals("ERROR", r.rows().get(1)[1]);
            assertNull(r.rows().get(2)[1], "a stack trace line has no level");
            assertEquals(false, r.rows().get(2)[4], "and doesn't start an entry");
            assertEquals("2026-10-28 10:00:02.500", r.rows().get(5)[3]);

            // the log grows: only the new lines are added
            Files.writeString(f, "2026-10-28 10:00:03,000 ERROR [http-3] c.a.Pay - card declined\n",
                    java.nio.file.StandardOpenOption.APPEND);
            long deadline = System.currentTimeMillis() + 10_000;
            while (la.document().lineCount() < 7 && System.currentTimeMillis() < deadline) {
                la.document().reload();
                Thread.sleep(50);
            }
            assertTrue(t.update(() -> false, null));
            Sql.Result e = sql.query("SELECT count(*) FROM log WHERE level = 'ERROR'", 10, null);
            assertEquals(2L, ((Number) e.rows().get(0)[0]).longValue());
            Sql.Result n = sql.query("SELECT count(*), max(line) FROM log", 10, null);
            assertEquals(7L, ((Number) n.rows().get(0)[0]).longValue(), "no line twice");
        }
    }

    @Test
    void aTableIsLoadedTypedByLogsiftsOwnReading() throws Exception {
        Path f = dir.resolve("orders.csv");
        Files.writeString(f, "order_id,placed_at,amount,region\n1,2026-10-21 10:00:00,\"1,250.50\",North\n"
                + "2,2026-10-21 10:05:00,3,South\n3,2026-10-21 11:00:00,,North\n");
        try (LogAnalysis la = LogAnalysis.open(f, List.of()); Sql sql = Sql.open(new Sql.Options(List.of(), dir.resolve("t2")))) {
            la.awaitReady(() -> false);
            LogFormat fmt = la.formats().columns();
            Pivot.Fields fields = Pivot.fields(fmt, la.document().lineCount(), la.document()::readLines, 2026);
            Sql.LogTable t = sql.table("orders", la.document(), fmt, fields);
            assertTrue(t.update(() -> false, null));
            Sql.Result r = sql.query("t", t, "SELECT region, sum(amount) AS total, count(amount) AS n, max(placed_at) AS last, "
                    + "min(line) AS first FROM t GROUP BY region ORDER BY region", 10, null);
            assertEquals(List.of("North", 1250.5, 1L, "2026-10-21 11:00:00", 2L), List.of(r.rows().get(0)),
                    "1,250.50 read as Pivot reads it; a blank amount is null; the header isn't a row");
            assertEquals(List.of("line", "order_id", "placed_at", "amount", "region"), t.sqlColumns());
            assertThrows(SQLException.class, () -> sql.query("SELECT * FROM read_csv('" + f + "')", 10, null),
                    "this session reads no files itself");
        }
    }

    @Test
    void onlyQueriesRun() throws Exception {
        try (Sql sql = open()) {
            for (String bad : List.of("COPY (SELECT 1) TO '" + dir.resolve("out.csv") + "'",
                    "ATTACH '" + dir.resolve("x.db") + "'", "INSTALL httpfs", "SET enable_external_access=true",
                    "CREATE TABLE x AS SELECT 1", "DROP TABLE IF EXISTS x", "PRAGMA version")) {
                SQLException e = assertThrows(SQLException.class, () -> sql.query(bad, 10, null), bad);
                assertTrue(e.getMessage().startsWith("Only queries can run here") || e.getMessage().contains("syntax"),
                        bad + ": " + e.getMessage());
            }
            assertFalse(Files.exists(dir.resolve("out.csv")), "nothing written");
            assertFalse(Files.exists(dir.resolve("x.db")), "nothing written");
            SQLException two = assertThrows(SQLException.class, () -> sql.query("SELECT 1; SELECT 2", 10, null));
            assertTrue(two.getMessage().startsWith("One query at a time"));
            assertEquals(1, sql.query("SELECT 'a;b' AS x; ", 10, null).rows().size(), "a ';' in a string is fine");
            assertEquals(1, sql.query("SELECT 1 -- a comment; with a semicolon\n", 10, null).rows().size());
        }
    }

    @Test
    void writesHiddenInAQueryAreRefused() throws Exception {
        Path f = dir.resolve("orders.csv");
        Files.writeString(f, "order_id,amount,region\n1,10,North\n2,20,South\n3,30,North\n");
        try (LogAnalysis la = LogAnalysis.open(f, List.of()); Sql sql = Sql.open(new Sql.Options(List.of(), dir.resolve("t3")))) {
            la.awaitReady(() -> false);
            LogFormat fmt = la.formats().columns();
            Pivot.Fields fields = Pivot.fields(fmt, la.document().lineCount(), la.document()::readLines, 2026);
            Sql.LogTable t = sql.table("orders", la.document(), fmt, fields);
            assertTrue(t.update(() -> false, null));
            // the first word is WITH, a query's: only DuckDB's parser sees what is inside
            for (String bad : List.of("WITH d AS (DELETE FROM orders RETURNING *) SELECT * FROM d",
                    "WITH i AS (INSERT INTO orders (line) VALUES (99) RETURNING *) SELECT * FROM i",
                    "WITH u AS (UPDATE orders SET amount = 0 RETURNING *) SELECT count(*) FROM u",
                    "(SELECT 1); DROP TABLE orders", "SELECT 1 /* ; */ ; DELETE FROM orders")) {
                SQLException e = assertThrows(SQLException.class, () -> sql.query("t", t, bad, 10, null), bad);
                assertTrue(e.getMessage().startsWith("Only queries can run here") || e.getMessage().startsWith("One query"),
                        bad + ": " + e.getMessage());
            }
            Sql.Result r = sql.query("t", t, "SELECT count(*), sum(amount) FROM orders", 10, null);
            assertEquals(List.of(3L, 60.0), List.of(r.rows().get(0)), "nothing changed");
            // what reads still runs
            assertEquals(2, sql.query("t", t, "SELECT region, sum(amount) FROM t GROUP BY ALL", 10, null).rows().size());
            assertEquals(1, sql.query("t", t, "PIVOT (SELECT region, amount FROM t) ON region USING sum(amount)", 10, null)
                    .rows().size());
            assertEquals(4, sql.query("t", t, "DESCRIBE t", 10, null).rows().size());
            assertEquals(4, sql.query("t", t, "SUMMARIZE t", 10, null).rows().size());
            assertEquals(3, sql.query("t", t, "FROM t SELECT order_id", 10, null).rows().size());
            assertEquals(1, sql.query("t", t, "-- a comment first\nWITH x AS (SELECT 1 AS a) SELECT * FROM x", 10, null)
                    .rows().size());
        }
    }

    @Test
    void readsOnlyTheFoldersGiven() throws Exception {
        Path outside = Files.createTempFile("logsift-sql-outside", ".csv");
        try (Sql sql = open()) {
            Files.writeString(outside, "a\n1\n");
            assertThrows(SQLException.class, () -> sql.query("SELECT * FROM read_csv('" + outside + "')", 10, null));
            Path inside = dir.resolve("in.csv");
            Files.writeString(inside, "a\n1\n2\n");
            assertEquals(2, sql.query("SELECT * FROM read_csv('" + inside + "')", 10, null).rows().size());
            assertThrows(SQLException.class, () -> sql.query("SELECT * FROM read_csv('https://example.com/x.csv')", 10, null),
                    "nothing on the network");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void readsAFolderWhoseNameHasABackslash() throws Exception {
        // as a Windows path does (a folder in a folder there; one name elsewhere): DuckDB mustn't
        // lose the backslash and so refuse the folder
        Path odd = Files.createDirectories(dir.resolve("logs\\2026"));
        Path f = Files.writeString(odd.resolve("in.csv"), "a\n1\n2\n");
        try (Sql sql = Sql.open(new Sql.Options(List.of(odd), dir.resolve("tmp")))) {
            assertEquals(2, sql.query("SELECT * FROM read_csv('" + f + "')", 10, null).rows().size());
        }
    }

    @Test
    void atMostTheRowsAskedFor() throws Exception {
        try (Sql sql = open()) {
            Sql.Result r = sql.query("SELECT * FROM range(1000)", 50, null);
            assertEquals(50, r.rows().size());
            assertTrue(r.more());
            Sql.Result exactly = sql.query("SELECT * FROM range(50)", 50, null);
            assertEquals(50, exactly.rows().size());
            assertFalse(exactly.more(), "all of them: none more");
            Sql.Result pivot = sql.query("PIVOT (SELECT range % 7 AS k, range AS v FROM range(100)) ON k USING sum(v)", 1,
                    null);
            assertEquals(1, pivot.rows().size());
            assertEquals(List.of("0", "1", "2", "3", "4", "5", "6"), pivot.columns());
        }
    }

    @Test
    void mistakesAreToldWithDuckDBsWholeMessage() throws Exception {
        Files.writeString(dir.resolve("orders.csv"), "order_id,region,amount\n1,North,12.5\n");
        try (Sql sql = open()) {
            sql.csv("t", dir.resolve("orders.csv"), ',', true, java.nio.charset.StandardCharsets.UTF_8);
            String m = assertThrows(SQLException.class, () -> sql.query("SELECT region,\n  sum(amount)\nFROM t\n"
                    + "WHERE regoin = 'North'\nGROUP BY 1", 10, null)).getMessage();
            assertTrue(m.startsWith("Binder Error"), m);
            assertTrue(m.contains("Candidate bindings") && m.contains("\"region\""), "the columns there are: " + m);
            assertTrue(m.contains("LINE 4:"), "the line as written: " + m);
            assertFalse(m.contains("quackjvm") || m.contains("LIMIT"), "nothing of the wrapping: " + m);
        }
    }

    @Test
    void queriesHaveNoTimeLimitButStop() {
        io.quackjvm.agent.guard.QueryPolicy p = Sql.policy(100);
        assertTrue(p.getTimeout().toHours() >= 1, "a long query runs until Stop: " + p.getTimeout());
        assertEquals(0, p.getMaxBytes(), "no cap on the size of the rows");
        assertEquals(100, p.getMaxRows());
        assertEquals(io.quackjvm.agent.guard.QueryPolicy.Over.TRUNCATE, p.getWhenOver());
    }

    @Test
    void canBeStopped() throws Exception {
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() > 1);
        try (Sql sql = open()) {
            long t0 = System.currentTimeMillis();
            SQLException e = assertThrows(SQLException.class, () -> sql.query(
                    "SELECT count(*) FROM range(100000000000) a", 10, () -> System.currentTimeMillis() - t0 > 300));
            assertEquals("Stopped.", e.getMessage());
            assertTrue(System.currentTimeMillis() - t0 < 10_000);
            // stopped before it starts: nothing runs; and the session goes on
            assertEquals("Stopped.", assertThrows(SQLException.class,
                    () -> sql.query("SELECT 1", 10, () -> true)).getMessage());
            assertEquals(1, sql.query("SELECT 1", 10, null).rows().size());
        }
    }

    @Test
    void otherTablesGoByTheirFilesNames() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        assertEquals("orders", Sql.tableName(Path.of("/x/orders.csv"), taken));
        taken.add("orders");
        assertEquals("orders_2", Sql.tableName(Path.of("/y/Orders.CSV"), taken), "another orders");
        assertEquals("sales_2026_q4", Sql.tableName(Path.of("Sales 2026-Q4.tsv"), taken));
        assertEquals("table_2026", Sql.tableName(Path.of("2026.csv"), taken), "a name SQL can write bare");
        assertEquals("t_2", Sql.tableName(Path.of("t.csv"), taken), "never t or log");
        assertEquals("log_2", Sql.tableName(Path.of("log.csv.gz"), taken));
    }

    @Test
    void aQueryJoinsTablesByName() throws Exception {
        Path orders = Files.writeString(dir.resolve("orders.csv"), "order_id,customer_id,amount\n1,7,10\n2,8,20\n3,7,5\n");
        Path customers = Files.writeString(dir.resolve("customers.csv"), "customer_id,name\n7,Ann\n8,Bo\n");
        try (Sql sql = open()) {
            sql.csv("t", orders, ',', true, java.nio.charset.StandardCharsets.UTF_8);
            sql.csv("customers", customers, ',', true, java.nio.charset.StandardCharsets.UTF_8);
            Sql.Result r = sql.query("SELECT name, sum(amount) AS total FROM t JOIN customers USING (customer_id) "
                    + "GROUP BY name ORDER BY name", 10, null);
            assertEquals(2, r.rows().size());
            assertEquals("Ann", r.rows().get(0)[0]);
            assertEquals(15L, ((Number) r.rows().get(0)[1]).longValue());
            sql.dropView("customers");
            assertThrows(SQLException.class, () -> sql.query("SELECT * FROM customers", 10, null), "gone");
        }
    }

    @Test
    void aLogIsKeptOnDiskBetweenRunsAndOnlyItsNewLinesAreRead() throws Exception {
        Path f = dir.resolve("kept.log");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            b.append(String.format("2026-10-28 10:%02d:%02d,000 %s [main] c.a.App - step %d%n", i / 60 % 60, i % 60,
                    i % 10 == 0 ? "ERROR" : "INFO ", i));
        }
        Files.writeString(f, b);
        Path cache = dir.resolve("cache");
        System.setProperty("logsift.logCacheMinBytes", "1");
        try {
            try (LogAnalysis la = LogAnalysis.open(f, List.of());
                 Sql sql = Sql.open(new Sql.Options(List.of(), dir.resolve("k1"), cache, 1L << 30))) {
                la.awaitReady(() -> false);
                Sql.LogTable t = sql.log("log", la.document(), la.formats().columns(), la.formats().times(),
                        la.formats().entries());
                assertTrue(t.update(() -> false, null));
                assertEquals(2000, t.loaded());
            }
            assertTrue(logsift.core.PivotCache.size(cache) > 0, "kept in the cache folder");
            // the next run: there already, nothing read until the file grows
            Files.writeString(f, "2026-10-28 11:00:00,000 ERROR [main] c.a.App - late\n",
                    java.nio.file.StandardOpenOption.APPEND);
            try (LogAnalysis la = LogAnalysis.open(f, List.of());
                 Sql sql = Sql.open(new Sql.Options(List.of(), dir.resolve("k2"), cache, 1L << 30))) {
                la.awaitReady(() -> false);
                Sql.LogTable t = sql.log("log", la.document(), la.formats().columns(), la.formats().times(),
                        la.formats().entries());
                assertEquals(2000, t.loaded(), "the lines read last time");
                assertTrue(t.update(() -> false, null));
                assertEquals(2001, t.loaded(), "and the one written since");
                Sql.Result r = sql.query("SELECT count(*), max(line) FROM log WHERE level = 'ERROR'", 10, null);
                assertEquals(List.of(201L, 2001L), List.of(r.rows().get(0)[0], r.rows().get(0)[1]));
            }
        } finally {
            System.clearProperty("logsift.logCacheMinBytes");
        }
    }
}
