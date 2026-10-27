package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CSV files merged into one CSV: one header, a source column, rows by time or one file after another. */
class CsvMergerTest {
    @TempDir
    Path dir;

    private Path file(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private List<LogMerger.Source> sources(Path... files) {
        List<Path> ps = List.of(files);
        List<String> labels = LogMerger.labels(ps);
        List<LogMerger.Source> out = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            out.add(new LogMerger.Source(ps.get(i), labels.get(i)));
        }
        return out;
    }

    /** The merged file's rows, split back into fields (the header first). */
    private static List<List<String>> rows(Path out) throws IOException {
        String all = Files.readString(out);
        List<List<String>> rows = new ArrayList<>();
        StringBuilder rec = new StringBuilder();
        boolean quoted = false;
        for (String line : all.split("\n", -1)) {
            if (rec.length() > 0) {
                rec.append('\n');
            }
            rec.append(line);
            quoted ^= line.chars().filter(c -> c == '"').count() % 2 == 1;
            if (!quoted) {
                if (!rec.isEmpty()) {
                    rows.add(Csv.split(rec.toString(), ',', null));
                }
                rec.setLength(0);
            }
        }
        return rows;
    }

    @Test
    void headersAreJoinedByName() throws IOException {
        Path a = file("eu.csv", "time,host,status\n2026-10-21 10:00:00,web-1,200\n2026-10-21 10:00:02,web-1,500\n");
        Path b = file("us.csv", "Time;Status;HOST;region\n2026-10-21 10:00:01;404;web-9;us-east\n");
        CsvMerger.Plan plan = CsvMerger.plan(sources(a, b));
        assertEquals(List.of("time", "host", "status", "region"), plan.columns(), "first file's order, new ones after");
        assertEquals(CsvMerger.Order.BY_TIME, plan.order());
        Path out = dir.resolve("out.csv");
        try (CsvMerger m = CsvMerger.merge(sources(a, b), out, null, () -> false)) {
            assertEquals(3, m.rowsWritten());
        }
        assertEquals(List.of(
                List.of("source", "time", "host", "status", "region"),
                List.of("eu", "2026-10-21 10:00:00", "web-1", "200", ""),
                List.of("us", "2026-10-21 10:00:01", "web-9", "404", "us-east"),
                List.of("eu", "2026-10-21 10:00:02", "web-1", "500", "")), rows(out), "by time, ; turned into ,");
        // the result is read as an ordinary CSV with a source column
        LogFormat f = Formats.detect(Files.readAllLines(out), List.of(), "out.csv", null, 2026).columns();
        assertTrue(f.isCsv());
        assertEquals(List.of("source", "time", "host", "status", "region"), f.columns());
    }

    @Test
    void withoutTimesOneFileAfterTheOther() throws IOException {
        Path a = file("jan.csv", "id,amount\n1,10\n2,20\n");
        Path b = file("feb.csv", "id,amount,note\n3,30,late\n");
        Path c = file("mar.csv", "amount,id\n40,4\n");
        CsvMerger.Plan plan = CsvMerger.plan(sources(a, b, c));
        assertEquals(CsvMerger.Order.ONE_AFTER_THE_OTHER, plan.order());
        assertEquals(List.of("jan.csv", "feb.csv", "mar.csv"), plan.untimed());
        Path out = dir.resolve("out.csv");
        try (CsvMerger m = CsvMerger.merge(sources(a, b, c), out, null, () -> false)) {
            assertEquals(4, m.rowsWritten());
        }
        assertEquals(List.of(
                List.of("source", "id", "amount", "note"),
                List.of("jan", "1", "10", ""),
                List.of("jan", "2", "20", ""),
                List.of("feb", "3", "30", "late"),
                List.of("mar", "4", "40", "")), rows(out));
    }

    @Test
    void quotedFieldsRoundTrip() throws IOException {
        Path a = file("a.csv", "id;text;n\n"
                + "1;\"a; b, c\";1\n"
                + "2;\"she said \"\"hi\"\"\";2\n"
                + "3;\"two\nlines\";3\n"
                + "4; spaces around ;4\n");
        Path b = file("b.csv", "id,text\n5,\"x,y\"\r\n6,plain\r\n");
        Path out = dir.resolve("out.csv");
        try (CsvMerger m = CsvMerger.merge(sources(a, b), out, null, () -> false)) {
            assertEquals(6, m.rowsWritten());
        }
        List<List<String>> r = rows(out);
        assertEquals(List.of("a", "1", "a; b, c", "1"), r.get(1));
        assertEquals(List.of("a", "2", "she said \"hi\"", "2"), r.get(2));
        assertEquals(List.of("a", "3", "two\nlines", "3"), r.get(3));
        assertEquals(List.of("a", "4", " spaces around ", "4"), r.get(4));
        assertEquals(List.of("b", "5", "x,y", ""), r.get(5));
        assertEquals(List.of("b", "6", "plain", ""), r.get(6));
        assertEquals(7, r.size());
    }

    @Test
    void newRowsAreFollowed() throws Exception {
        Path a = file("a.csv", "time,v\n2026-10-21 10:00:00,1\n");
        Path b = file("b.csv", "time,v,extra\n2026-10-21 10:00:01,2,x\n");
        Path out = dir.resolve("out.csv");
        try (CsvMerger m = CsvMerger.merge(sources(a, b), out, null, () -> false)) {
            m.follow(30);
            Files.writeString(a, "2026-10-21 10:00:05,3\n", java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(b, "2026-10-21 10:00:06,4,\"y\nz\"\n", java.nio.file.StandardOpenOption.APPEND);
            long until = System.currentTimeMillis() + 10_000;
            while (m.rowsWritten() < 4 && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            assertEquals(4, m.rowsWritten());
        }
        List<List<String>> r = rows(out);
        assertEquals(List.of("a", "2026-10-21 10:00:05", "3", ""), r.get(3), "mapped to the merged header");
        assertEquals(List.of("b", "2026-10-21 10:00:06", "4", "y\nz"), r.get(4));
    }

    @Test
    void notCsv() throws IOException {
        Path log = file("app.log", "2026-10-21 10:00:00.000 INFO  [main] a.B - hello\n"
                + "2026-10-21 10:00:01.000 INFO  [main] a.B - again\n2026-10-21 10:00:02.000 INFO  [main] a.B - x\n");
        Path csv = file("t.csv", "a,b\n1,2\n");
        assertTrue(!CsvMerger.allCsv(List.of(log, csv)));
        assertTrue(CsvMerger.someCsv(List.of(log, csv)));
        assertTrue(CsvMerger.allCsv(List.of(csv, csv)));
        assertThrows(IOException.class, () -> CsvMerger.plan(sources(log, csv)));
        assertEquals("\"a,b\"", CsvMerger.quote("a,b"));
        assertEquals("plain", CsvMerger.quote("plain"));
    }
}
