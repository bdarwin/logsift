package logsift.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void splitsQuotedFields() {
        assertEquals(List.of("a", "b, c", "say \"hi\"", ""), Csv.split("a,\"b, c\",\"say \"\"hi\"\"\",", ',', null));
        assertEquals(List.of("x", "y"), Csv.split("x;y", ';', null));
        assertEquals(List.of(""), Csv.split("", ',', null));
        assertEquals(List.of("a", " b "), Csv.split("a, b ", ',', null), "unquoted spaces are kept");
        assertEquals(List.of("a", "b"), Csv.split("a, \"b\" ", ',', null), "around quotes they are not");
        assertEquals(List.of("1", "two\tthree"), Csv.split("1\t\"two\tthree\"", '\t', null));
        assertEquals(2, Csv.fieldCount("a,\"open, never closed", ','), "an open quote takes the rest");
        int[] starts = new int[3];
        Csv.split("ab,\"cd\",e", ',', starts);
        assertArrayEquals(new int[] {0, 4, 8}, starts);
    }

    @Test
    void detectsByNameAndByContent() {
        List<String> semi = List.of("Timestamp;Severity;Host;Message",
                "2026-10-21 10:00:01;INFO;web-1;started",
                "2026-10-21 10:00:02;ERROR;web-1;\"failed; retrying\"",
                "2026-10-21 10:00:03;WARN;web-2;slow",
                "2026-10-21 10:00:04;INFO;web-2;ok");
        LogFormat f = LogFormat.detectCsv(semi, false);
        assertNotNull(f);
        assertTrue(f.isCsv());
        assertEquals(';', f.delimiter());
        assertEquals(List.of("time", "level", "Host", "message"), f.columns(), "known names are recognized");
        assertEquals("failed; retrying", f.value(semi.get(2), "message"));
        assertEquals("web-2", f.value(semi.get(3), "Host"));
        assertTrue(f.matches(semi.get(1)));
        assertFalse(f.matches("a continuation line"));
        assertFalse(f.matches(semi.get(0)), "the header names the columns; it is not a row");
        assertNull(f.split("\uFEFF" + semi.get(0), null), "also with a byte order mark");

        // a log with commas is not CSV: no header of names, rows differ
        List<String> log = List.of("2026-10-21 10:00:01 INFO a, b, c", "2026-10-21 10:00:02 INFO d",
                "2026-10-21 10:00:03 INFO e, f", "2026-10-21 10:00:04 INFO g", "2026-10-21 10:00:05 INFO h");
        assertNull(LogFormat.detectCsv(log, false));
        // two columns: only when the file is named so
        List<String> two = List.of("name,value", "a,1", "b,2", "c,3", "d,4");
        assertNull(LogFormat.detectCsv(two, false));
        assertNotNull(LogFormat.detectCsv(two, true));
        // a row with a value where a name should be
        assertNull(LogFormat.detectCsv(List.of("1,2,3", "4,5,6", "7,8,9", "1,2,3", "4,5,6"), false));
    }

    @Test
    void namesColumns() {
        assertEquals(List.of("time", "column 2", "date", "x", "x 2"),
                LogFormat.columnNames(List.of("ts", "", "date", "x", "x")),
                "the first time-like name is the time; repeated and empty names get numbers");
    }

    @Test
    void formatsDetectCsvFilesAndNotLogs() {
        List<String> csv = List.of("time,level,user,message", "2026-10-21 10:00:01,INFO,u1,hello",
                "2026-10-21 10:00:02,ERROR,u2,\"bad, very\"", "2026-10-21 10:00:03,INFO,u1,bye");
        Formats.Detected d = Formats.detect(csv, List.of(), "export.csv", null, 2026);
        assertTrue(d.columns() != null && d.columns().isCsv(), String.valueOf(d.columns()));
        assertNotNull(d.times(), "the time column is read");
        assertEquals(LogFormat.CSV, Formats.detect(csv, List.of(), "x.txt", LogFormat.CSV, 2026).columns().name(),
                "chosen by hand");
        List<String> log = List.of("2026-10-21 10:00:01.123 INFO  [main] c.a.App - Started, ok",
                "2026-10-21 10:00:02.123 WARN  [main] c.a.App - slow, again",
                "2026-10-21 10:00:03.123 INFO  [main] c.a.App - done, really");
        assertFalse(Formats.detect(log, List.of(), "app.log", null, 2026).columns().isCsv());
        assertTrue(Formats.isCsvName("a.CSV") && Formats.isCsvName("a.tsv.1") && !Formats.isCsvName("csv.log"));
    }

    @Test
    void hidesRowsByAColumnsValue() {
        LogFormat f = LogFormat.detectCsv(List.of("time,host,message", "t1,web-1,a", "t2,\"web-2\",\"b, c\""), true);
        String re = ValueTree.lineRegex(f, "host", List.of(new ValueTree.Hidden("web-1", false)));
        Pattern p = Pattern.compile(re);
        assertTrue(p.matcher("t1,web-1,a").find());
        assertFalse(p.matcher("t2,\"web-2\",\"b, c\"").find());
        assertFalse(p.matcher("t3,web-10,x").find(), "a value that only starts the same");
        assertTrue(Pattern.compile(ValueTree.lineRegex(f, "host", List.of(new ValueTree.Hidden("web-2", false))))
                .matcher("t2,\"web-2\",\"b, c\"").find(), "quoted");
        String msg = ValueTree.lineRegex(f, "message", List.of(new ValueTree.Hidden("b, c", false)));
        assertTrue(Pattern.compile(msg).matcher("\"t, 2\",web-2,\"b, c\"").find(), "after a quoted field with a comma");
    }

    @Test
    void rowsWithAFewFieldsMoreOrLessAreStillRows() {
        List<String> sample = List.of("id,name,email,city", "1,Ann,a@x.com,Paris,,", "2,Bob,b@x.com,Lyon",
                "3,Cy,c@x.com", "4,Di,d@x.com,Nice");
        LogFormat f = LogFormat.detectCsv(sample, true);
        assertNotNull(f);
        assertArrayEquals(new String[] {"1", "Ann", "a@x.com", "Paris,,"}, f.split("1,Ann,a@x.com,Paris,,", null),
                "extra separators join the last column");
        assertArrayEquals(new String[] {"3", "Cy", "c@x.com", null}, f.split("3,Cy,c@x.com", null), "one missing");
        assertTrue(f.matches("3,Cy,c@x.com"));
        assertEquals("Paris,,", f.value("1,Ann,a@x.com,Paris,,", "city"));
        assertNull(f.split("second line of a value", null), "a piece of a value written over lines");
        assertNull(f.split("x", null));
    }

    @Test
    void theHeaderIsNotARowButShowsInTheColumns() {
        LogFormat f = LogFormat.detectCsv(List.of("\uFEFFtimestamp,level,host", "2026-10-21 10:00:00,INFO,web-1"), true);
        assertNull(f.split("\uFEFFtimestamp,level,host", null), "not a row (no host called host)");
        int[] starts = new int[3];
        assertArrayEquals(new String[] {"timestamp", "level", "host"},
                f.headerCells("\uFEFFtimestamp,level,host", starts));
        assertArrayEquals(new int[] {1, 11, 17}, starts);
        assertNull(f.headerCells("2026-10-21 10:00:00,INFO,web-1", null));
    }
}
