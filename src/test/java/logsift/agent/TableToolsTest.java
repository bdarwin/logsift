package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pivot tool as an agent calls it: the same totals as the Pivot tab. */
class TableToolsTest {

    @TempDir
    Path dir;
    Path logs;
    Workspace w;

    @BeforeEach
    void start() throws IOException {
        logs = Files.createDirectories(dir.resolve("logs"));
        Path home = Files.createDirectories(dir.resolve("home"));
        w = new Workspace(new Workspace.Options(new Scope(List.of(logs), home), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 30_000, List.of(), null));
    }

    @AfterEach
    void stop() {
        w.close();
    }

    private String run(Object... kv) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return TableTools.PIVOT.run(new Tool.Args(m), w);
    }

    private String fail(Object... kv) {
        return assertThrows(Tool.Failure.class, () -> run(kv)).getMessage();
    }

    /** Five sales: two regions and a blank one, over three hours; 1,250.50 is a number. */
    private Path sales() throws IOException {
        return Files.writeString(logs.resolve("sales.csv"), """
                time,region,status,ms,user,cpu_a,cpu_b
                2026-10-28 10:05:00,North,ok,100,u1,1,2
                2026-10-28 10:20:00,North,err,300,u2,3,4
                2026-10-28 11:10:00,South,ok,"1,250.50",u1,5,6
                2026-10-28 11:40:00,South,ok,50,u3,,7
                2026-10-28 12:00:00,,ok,10,u1,1,1
                """);
    }

    /** The cells of the line that starts with {@code label}. */
    private static List<String> row(String answer, String label) {
        for (String l : answer.split("\n")) {
            List<String> cells = List.of(l.split(" \\| ", -1));
            if (cells.get(0).equals(label)) {
                return cells.subList(1, cells.size());
            }
        }
        throw new AssertionError("no line " + label + " in:\n" + answer);
    }

    @Test
    void countsRowsByAField() throws Exception {
        String a = run("log", sales().toString(), "rows", "region");
        assertTrue(a.startsWith("5 rows in "), a);
        assertEquals("region | Count of rows", a.split("\n")[1]);
        assertEquals(List.of("2"), row(a, "North"));
        assertEquals(List.of("2"), row(a, "South"));
        assertEquals(List.of("1"), row(a, "(blank)"), "a blank cell groups as (blank)");
        assertEquals(List.of("5"), row(a, "Grand Total"));
    }

    @Test
    void totalsValuesAsPivotReadsThem() throws Exception {
        String a = run("log", sales().toString(), "rows", "region",
                "values", "sum(ms), avg(ms), max(ms), min(ms), count_distinct(user), median(ms), count");
        assertEquals("region | Sum of ms | Average of ms | Max of ms | Min of ms | Count distinct of user | Median of ms "
                + "| Count of rows", a.split("\n")[1]);
        assertEquals(List.of("400", "200", "300", "100", "2", "200", "2"), row(a, "North"));
        assertEquals(List.of("1,300.5", "650.25", "1,250.5", "50", "2", "650.25", "2"), row(a, "South"),
                "\"1,250.50\" is a number");
        assertEquals(List.of("1,710.5", "342.1", "1,250.5", "10", "3", "100", "5"), row(a, "Grand Total"));
        // a field alone: summed when it holds numbers, counted when it doesn't
        assertEquals(List.of("400", "2"), row(run("log", sales().toString(), "rows", "region", "values", "ms, user"),
                "North"));
    }

    @Test
    void groupsTimesAndSpreadsColumns() throws Exception {
        String a = run("log", sales().toString(), "rows", "time:hour", "columns", "status", "values", "sum(ms)");
        String[] lines = a.split("\n");
        assertEquals("time (Hour) | err | ok | Total", lines[1]);
        assertEquals(List.of("300", "100", "400"), row(a, lines[2].split(" \\| ")[0]), "10:00: " + a);
        assertTrue(lines[2].startsWith("2026-10-28 10"), a);
        assertEquals(List.of("300", "1,410.5", "1,710.5"), row(a, "Grand Total"));
        String two = run("log", sales().toString(), "rows", "region, status", "values", "count");
        assertEquals("region › status | Count of rows", two.split("\n")[1]);
        assertEquals(List.of("1"), row(two, "North › err"), "an inner group after its outer one: " + two);
        assertEquals(List.of("2"), row(two, "North"), "the outer group's subtotal");
    }

    @Test
    void manyFieldsAsOneValue() throws Exception {
        String a = run("log", sales().toString(), "rows", "region", "values", "sum(cpu_*)");
        assertEquals("region | Sum of cpu_* (2 fields)", a.split("\n")[1]);
        assertEquals(List.of("10"), row(a, "North"), "1+2+3+4");
        assertEquals(List.of("18"), row(a, "South"), "5+6+7, the blank skipped");
        assertEquals(List.of("30"), row(a, "Grand Total"));
    }

    @Test
    void filtersShowsAsPercentAndSorts() throws Exception {
        Path f = sales();
        String kept = run("log", f.toString(), "rows", "region", "values", "sum(ms)", "filters", "status=ok; region=South|North");
        assertTrue(kept.startsWith("3 rows kept by the filters in "), kept);
        assertEquals(List.of("100"), row(kept, "North"));
        assertEquals(List.of("1,300.5"), row(kept, "South"));
        assertFalse(kept.contains("(blank)"), kept);
        String pct = run("log", f.toString(), "rows", "region", "values", "count", "show", "pct_total");
        assertEquals(List.of("40 %"), row(pct, "North"));
        assertEquals(List.of("20 %"), row(pct, "(blank)"));
        assertEquals(List.of("100 %"), row(pct, "Grand Total"));
        String largest = run("log", f.toString(), "rows", "region", "values", "sum(ms)", "sort", "largest");
        assertTrue(largest.split("\n")[2].startsWith("South | "), largest);
        assertTrue(largest.split("\n")[4].startsWith("(blank) | "), largest);
        String smallest = run("log", f.toString(), "rows", "region", "values", "sum(ms)", "sort", "smallest");
        assertTrue(smallest.split("\n")[2].startsWith("(blank) | "), smallest);
        String some = run("log", f.toString(), "rows", "region", "limit", 1);
        assertEquals(3, some.split("\n").length - 2, "one group, the grand total and the note: " + some);
        assertTrue(some.contains("(2 more groups not listed; raise limit, filter, or group by fewer fields.)"), some);
    }

    @Test
    void saysWhatItDoesNotKnow() throws Exception {
        String f = sales().toString();
        String noField = fail("log", f, "rows", "regoin");
        assertTrue(noField.startsWith("There is no field “regoin”. Fields: time (time), region, status, ms (number)"),
                noField);
        assertTrue(fail("log", f, "rows", "region:hour").startsWith("“region” isn't a time, so it can't be grouped by hour."
                + " Time fields: time."));
        assertTrue(fail("log", f, "values", "total(ms)").startsWith("“total” isn't a total Pivot knows."));
        assertTrue(fail("log", f, "values", "sum()").startsWith("sum needs a field"));
        assertTrue(fail("log", f, "values", "sum(gpu_*)").startsWith("No field matches “gpu_*”."));
        assertTrue(fail("log", f, "filters", "region").startsWith("A filter is field=value"));
        assertTrue(fail("log", f, "rows", "region, REGION").contains("is there twice"));
        assertEquals(List.of("2"), row(run("log", f, "rows", "REGION"), "North"), "names ignore case");
    }

    @Test
    void pivotsALogsColumnsToo() throws Exception {
        Path f = Files.writeString(logs.resolve("app.log"), """
                2026-10-28 10:00:00.000 INFO  [http-1] c.a.Api - received
                2026-10-28 10:00:01.000 ERROR [http-2] c.a.Pay - declined
                2026-10-28 10:00:02.000 INFO  [http-1] c.a.Api - answered
                2026-10-28 10:30:02.000 WARN  [http-1] c.a.Api - slow
                """);
        String a = run("log", f.toString(), "rows", "level");
        assertEquals(List.of("2"), row(a, "INFO"), a);
        assertEquals(List.of("1"), row(a, "ERROR"), a);
        assertEquals(List.of("4"), row(a, "Grand Total"), a);
        Path plain = Files.writeString(logs.resolve("notes.txt"), "just\nsome words\n");
        assertTrue(fail("log", plain.toString()).startsWith("This log has no columns to group by"));
    }

    @Test
    void aWideTableByPattern() throws Exception {
        // 2,000 columns: a value over 1,000 of them, as one
        StringBuilder b = new StringBuilder("host");
        for (int c = 0; c < 2000; c++) {
            b.append(',').append(c < 1000 ? "cpu_" : "mem_").append(c);
        }
        b.append('\n');
        for (int r = 0; r < 300; r++) {
            b.append("web-").append(r % 3);
            for (int c = 0; c < 2000; c++) {
                b.append(',').append(c % 7);
            }
            b.append('\n');
        }
        Path f = Files.writeString(logs.resolve("wide.csv"), b);
        long expected = 0;
        for (int c = 0; c < 1000; c++) {
            expected += c % 7;
        }
        String a = run("log", f.toString(), "rows", "host", "values", "sum(cpu_*), count");
        assertEquals(List.of(String.format(java.util.Locale.ROOT, "%,d", expected * 100), "100"), row(a, "web-0"), a);
        assertEquals(List.of(String.format(java.util.Locale.ROOT, "%,d", expected * 300), "300"), row(a, "Grand Total"));
        String hint = fail("log", f.toString(), "rows", "gpu");
        assertTrue(hint.contains("… and 1,961 more (a pattern like cpu_* names many)."), hint);
    }

    @Test
    void splitsListsOutsideQuotesAndBrackets() {
        assertEquals(List.of("sum(a,b)", "count"), TableTools.split("sum(a,b), count", ','));
        assertEquals(List.of("\"x, y\"", "z"), TableTools.split("\"x, y\" ,z", ','));
        assertEquals(new ArrayList<String>(), TableTools.split("  ", ','));
    }
}
