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
    void atMostTheRowsAskedFor() throws Exception {
        try (Sql sql = open()) {
            Sql.Result r = sql.query("SELECT * FROM range(1000)", 50, null);
            assertEquals(50, r.rows().size());
            assertTrue(r.more());
        }
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
        }
    }
}
