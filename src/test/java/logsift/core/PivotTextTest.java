package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pivot as text, with numbers as the Pivot tab shows them. */
class PivotTextTest {

    @TempDir
    Path dir;

    @Test
    void numbersAsPeopleReadThem() {
        assertEquals("1,234", PivotText.number(1234));
        assertEquals("1,234.57", PivotText.number(1234.567));
        assertEquals("2.5", PivotText.number(2.5));
        assertEquals("0.000123", PivotText.number(0.000123456));
        assertEquals("-7", PivotText.number(-7));
        assertEquals("", PivotText.number(Double.NaN));
        assertEquals("∞", PivotText.number(Double.POSITIVE_INFINITY));
    }

    @Test
    void aTableWithColumnGroupsAndTotals() throws Exception {
        Path f = Files.writeString(dir.resolve("t.csv"), "host,status,ms\nweb-1,ok,10\nweb-1,err,5\nweb-2,ok,7\n");
        try (LogDocument doc = LogDocument.open(f)) {
            doc.awaitIdle(10_000);
            LogFormat fmt = LogFormat.detectCsv(List.of(doc.line(0), doc.line(1)), true);
            Pivot.Fields fields = Pivot.fields(fmt, doc.lineCount(), CsvRows.reader(doc, fmt), 2026);
            Pivot.Spec spec = new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(new Pivot.Field(1)),
                    List.of(new Pivot.Value(2, Pivot.Agg.SUM), new Pivot.Value(-1, Pivot.Agg.COUNT)), Map.of());
            Pivot.Result r = Pivot.compute(fmt, doc.lineCount(), CsvRows.reader(doc, fmt), null, spec, fields,
                    ZoneOffset.UTC, () -> false, null);
            assertEquals("""
                    host | err · Sum of ms | err · Count of rows | ok · Sum of ms | ok · Count of rows | Total · Sum of ms | Total · Count of rows
                    web-1 | 5 | 1 | 10 | 1 | 15 | 2
                    web-2 |  | 0 | 7 | 1 | 7 | 1
                    Grand Total | 5 | 1 | 17 | 2 | 22 | 3
                    """, PivotText.table(r, 100));
            assertEquals("""
                    host | err · Sum of ms | err · Count of rows | ok · Sum of ms | ok · Count of rows | Total · Sum of ms | Total · Count of rows
                    web-1 | 5 | 1 | 10 | 1 | 15 | 2
                    Grand Total | 5 | 1 | 17 | 2 | 22 | 3
                    (1 more group not listed; raise limit, filter, or group by fewer fields.)
                    """, PivotText.table(r, 1));
        }
    }
}
