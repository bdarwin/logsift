package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agents' SQL over a big table comes from the cache the app uses, with the same answers, and a
 * table too wide to load whole is read by the columns a query names.
 */
class BigTableToolsTest {

    @TempDir
    Path dir;
    Path logs;
    String home;

    @BeforeEach
    void start() throws IOException {
        logs = Files.createDirectories(dir.resolve("logs"));
        home = System.getProperty("logsift.home");
        System.setProperty("logsift.home", Files.createDirectories(dir.resolve("logsift")).toString());
    }

    @AfterEach
    void stop() {
        System.clearProperty("logsift.pivotCacheMinBytes");
        if (home == null) {
            System.clearProperty("logsift.home");
        } else {
            System.setProperty("logsift.home", home);
        }
    }

    private Workspace workspace() {
        return new Workspace(new Workspace.Options(new Scope(List.of(logs), dir), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 60_000, List.of(), null));
    }

    private static String query(Workspace w, Path f, String sql, int limit) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("log", f.toString());
        m.put("sql", sql);
        m.put("limit", limit);
        return LogTools.QUERY.run(new Tool.Args(m), w);
    }

    private static String fail(Workspace w, Path f, String sql) {
        return assertThrows(Tool.Failure.class, () -> query(w, f, sql, 100)).getMessage();
    }

    /** The rows, without the line that says how long it took. */
    private static String rows(String answer) {
        return answer.substring(answer.indexOf('\n') + 1);
    }

    /** host, status, ms, bytes, at, note: blanks, quotes, a blank line now and then. */
    private Path sales(int n) throws IOException {
        Random rnd = new Random(7);
        String[] st = {"ok", "ok", "slow", "error", ""};
        String[] notes = {"", "\"a, quoted\"", "7 ms", "n/a"};
        StringBuilder b = new StringBuilder("host,status,ms,bytes,at,note\n");
        for (int i = 0; i < n; i++) {
            b.append("web-").append(rnd.nextInt(4)).append(',').append(st[rnd.nextInt(st.length)]).append(',')
                    .append(rnd.nextInt(10) == 0 ? "" : String.valueOf(rnd.nextInt(5000) / 10.0)).append(',')
                    .append(rnd.nextInt(100_000)).append(',')
                    .append(String.format("2026-10-%02d %02d:%02d:00", 1 + rnd.nextInt(28), rnd.nextInt(24),
                            rnd.nextInt(60))).append(',')
                    .append(notes[rnd.nextInt(notes.length)]).append('\n');
            if (i % 500 == 7) {
                b.append('\n');
            }
        }
        return Files.writeString(logs.resolve("sales.csv"), b.toString());
    }

    static final List<String> QUERIES = List.of(
            "SELECT host, count(*), sum(ms), avg(bytes) FROM t GROUP BY host ORDER BY host",
            "SELECT date_trunc('day', \"at\") AS d, count(*) FROM t WHERE status = 'error' GROUP BY d ORDER BY d",
            "SELECT line, host, note FROM t WHERE note IS NOT NULL ORDER BY line LIMIT 20",
            "SELECT count(*) FROM t",
            "SELECT * FROM t ORDER BY line LIMIT 5",
            "SELECT host FROM t WHERE false");

    @Test
    void answersFromTheCacheAreThoseOfTheWholeTable() throws Exception {
        Path f = sales(3000);
        Map<String, String> whole = new LinkedHashMap<>();
        try (Workspace w = workspace()) { // small: the whole table is loaded
            for (String q : QUERIES) {
                whole.put(q, rows(query(w, f, q, 100)));
            }
        }
        Path caches = dir.resolve("logsift").resolve("cache").resolve("pivot");
        assertTrue(!Files.exists(caches) || logsift.core.PivotCache.size(caches) == 0, "no cache for a small file");
        System.setProperty("logsift.pivotCacheMinBytes", "1"); // big
        try (Workspace w = workspace()) {
            for (String q : QUERIES) {
                assertEquals(whole.get(q), rows(query(w, f, q, 100)), q);
            }
            String some = query(w, f, "SELECT line FROM t", 3);
            assertTrue(some.startsWith("3 rows (the first 3; raise limit or narrow the query)"), some);
            String typo = fail(w, f, "SELECT hots FROM t");
            assertTrue(typo.contains("\"host\""), "DuckDB's hint: " + typo);
            assertTrue(fail(w, f, "DELETE FROM t").startsWith("Only queries can run here"));
        }
        assertTrue(logsift.core.PivotCache.size(caches) > 0, "kept in the app's cache");
    }

    @Test
    void aTableTooWideToLoadIsReadByTheColumnsNamed() throws Exception {
        int n = 120;
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < n; c++) {
            b.append(c == 0 ? "" : ",").append("cpu_").append(c);
        }
        b.append('\n');
        for (int r = 0; r < 400; r++) {
            for (int c = 0; c < n; c++) {
                b.append(c == 0 ? "" : ",").append((r * 7 + c) % 50);
            }
            b.append('\n');
        }
        Path f = Files.writeString(logs.resolve("wide.csv"), b.toString());
        System.setProperty("logsift.pivotCacheMinBytes", "1");
        try (Workspace w = workspace()) {
            String sum = query(w, f, "SELECT sum(cpu_3), max(cpu_119), count(*) FROM t", 10);
            assertEquals("sum(cpu_3) | max(cpu_119) | count_star()\n9800.0 | 49.0 | 400", rows(sum));
            String all = fail(w, f, "SELECT * FROM t LIMIT 2");
            assertTrue(all.contains("120 columns") && all.contains("Name the columns you need"), all);
            String pivot = fail(w, f, "PIVOT t ON cpu_1 USING sum(cpu_2)");
            assertTrue(pivot.contains("pivot tool"), pivot);
            // many alike by a pattern: at most 64 at once, then more later
            String many = query(w, f, "SELECT sum(COLUMNS('^cpu_[1-5]$')) FROM t", 10);
            assertTrue(many.startsWith("1 row in "), many);
        }
    }
}
