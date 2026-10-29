package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A table's rows where they are not its lines: quoted values over line breaks, and very long rows. */
class CsvRowsTest {
    @TempDir
    Path dir;

    /** order,region,note,amount: rows 2 and 5 have a note over two and three lines. */
    private static final String ORDERS = "order,region,note,amount\r\n"
            + "1,North,plain,10\r\n"
            + "2,South,\"line one\r\nline two, with a comma\",20\r\n"
            + "3,North,\"said \"\"hi\"\"\",30\r\n"
            + "4,East,a 5\" screen,40\r\n"
            + "5,West,\"one\nTWO\nthree\",50\r\n"
            + "6,South,last,60\r\n";

    private LogDocument open(String name, String text) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, text);
        LogDocument doc = LogDocument.open(f, 64);
        assertTrue(doc.awaitIdle(10_000));
        return doc;
    }

    private static LogFormat detect(LogDocument doc) {
        return Formats.detect(Arrays.asList(LogAnalysis.sample(doc, doc.lineCount())), List.of(), "x.csv", null, 2026)
                .columns();
    }

    @Test
    void rowsOverSeveralLinesAreReadWhole() throws Exception {
        try (LogDocument doc = open("orders.csv", ORDERS)) {
            assertEquals(10, doc.lineCount());
            LogFormat f = detect(doc);
            assertNotNull(f);
            assertEquals(List.of("order", "region", "note", "amount"), f.columns());
            CsvRows rows = CsvRows.of(doc, f);
            rows.update(() -> false);
            assertTrue(rows.anyBreaks());
            String[] out = new String[10];
            rows.read(0, 10, out);
            assertNull(out[3], "line 3 continues row 2");
            assertNull(out[7]);
            assertNull(out[8]);
            assertEquals("2,South,\"line one\nline two, with a comma\",20", out[2]);
            assertEquals(List.of("2", "South", "line one\nline two, with a comma", "20"), List.of(f.split(out[2], null)));
            assertEquals(List.of("3", "North", "said \"hi\"", "30"), List.of(f.split(out[4], null)));
            assertEquals(List.of("4", "East", "a 5\" screen", "40"), List.of(f.split(out[5], null)),
                    "a quote inside a value doesn't start a quoted value");
            assertEquals(List.of("5", "West", "one\nTWO\nthree", "50"), List.of(f.split(out[6], null)));
            assertEquals(3, rows.span(6));
            assertEquals(1, rows.span(1));
            // read in pieces: a row is read whole even when it goes past the piece
            String[] two = new String[2];
            rows.read(1, 2, two);
            assertEquals(out[2], two[1]);
            // the rows as the database counts them (header 0) and the lines they start at
            assertEquals(0, rows.lineOfRow(0));
            assertEquals(2, rows.lineOfRow(2));
            assertEquals(4, rows.lineOfRow(3));
            assertEquals(6, rows.lineOfRow(5));
            assertEquals(9, rows.lineOfRow(6));
        }
    }

    @Test
    void aFileWithoutBreaksReadsAsItsLines() throws Exception {
        try (LogDocument doc = open("plain.csv", "a,b\n1,2\n3,4\n")) {
            CsvRows rows = CsvRows.of(doc, detect(doc));
            rows.update(() -> false);
            assertFalse(rows.anyBreaks());
            String[] out = new String[3];
            rows.read(0, 3, out);
            assertEquals(List.of("a,b", "1,2", "3,4"), List.of(out));
            assertEquals(2, rows.lineOfRow(2));
        }
    }

    @Test
    void aQuoteLeftOpenByMistakeTakesOnlyItsLine() throws Exception {
        StringBuilder b = new StringBuilder("id,text\n1,\"never closed\n");
        for (int i = 2; i < CsvRows.MAX_SPAN + 50; i++) {
            b.append(i).append(",fine\n");
        }
        try (LogDocument doc = open("stray.csv", b.toString())) {
            CsvRows rows = CsvRows.of(doc, detect(doc));
            assertFalse(rows.continues(2), "the rows after a stray quote are rows");
            assertFalse(rows.continues(500));
            String[] out = new String[3];
            rows.read(1, 3, out);
            assertEquals("1,\"never closed", out[0]);
            assertEquals("2,fine", out[1]);
        }
    }

    @Test
    void aGrowingTableIsScannedAsItGrows() throws Exception {
        try (LogDocument doc = open("grow.csv", "id,text\n1,\"open\n")) {
            CsvRows rows = CsvRows.of(doc, LogFormat.detectCsv(List.of("id,text"), true));
            rows.update(() -> false);
            Files.writeString(doc.path(), "still open\nclosed\",x\n2,b\n", StandardOpenOption.APPEND);
            doc.requestRefresh();
            assertTrue(doc.awaitIdle(10_000));
            rows.update(() -> false);
            assertTrue(rows.continues(2));
            assertTrue(rows.continues(3));
            assertFalse(rows.continues(4));
            assertEquals("1,\"open\nstill open\nclosed\",x", rows.row(1));
        }
    }

    /** A wide export: 2,000 columns of long values, so each row is far longer than a line shown (64 KB). */
    private static String wide(int rows) {
        StringBuilder b = new StringBuilder("region");
        for (int c = 1; c < 2000; c++) {
            b.append(",Customer account balance adjusted for currency ").append(c);
        }
        b.append('\n');
        String[] regions = {"North", "South", "East"};
        for (int r = 0; r < rows; r++) {
            b.append(regions[r % 3]);
            for (int c = 1; c < 2000; c++) {
                b.append(",ACC-").append(String.format("%012d", r * 10_000L + c)).append("-EUR-2026-10-29T10:00:00Z");
            }
            b.append('\n');
        }
        return b.toString();
    }

    @Test
    void aWideTableIsFoundAndReadWithAllItsColumns() throws Exception {
        try (LogDocument doc = open("wide.csv", wide(30))) {
            assertTrue(doc.line(1).length() < 70_000, "a line is cut for showing");
            LogFormat f = detect(doc);
            assertNotNull(f, "a header longer than a line shown is still a CSV's header");
            assertEquals(2000, f.columns().size());
            String[] out = new String[2];
            CsvRows.reader(doc, f).read(1, 2, out);
            assertTrue(out[0].length() > LogDocument.MAX_DISPLAY_BYTES, "the row is read whole: " + out[0].length());
            String[] cells = f.split(out[0], null);
            assertEquals("ACC-000000001999-EUR-2026-10-29T10:00:00Z", cells[1999], "the last column too");

            // the readers of tables: Pivot, Profile, the Values counts
            Pivot.Fields fields = Pivot.fields(f, doc.lineCount(), CsvRows.reader(doc, f), 2026);
            Pivot.Result p = Pivot.compute(f, doc.lineCount(), CsvRows.reader(doc, f), null,
                    new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(), List.of(), Map.of()), fields,
                    ZoneOffset.UTC, () -> false, null);
            assertEquals(30, p.rows());
            assertEquals(List.of("East", "North", "South"), p.root().kids().stream().map(Pivot.Node::label).sorted().toList());
            ColumnProfile.Result prof = ColumnProfile.analyze(f, doc.lineCount(), CsvRows.reader(doc, f), null, 2026,
                    () -> false, null);
            assertEquals(30, prof.rows());
            ColumnStats.Result last = ColumnStats.count(doc, f, f.columns().get(1999), () -> false);
            assertEquals(30, last.values().size(), "the values of the last column: " + last.values().size());
        }
    }

    @Test
    void tablesReadersCountRowsNotLines() throws Exception {
        try (LogDocument doc = open("orders.csv", ORDERS)) {
            LogFormat f = detect(doc);
            Pivot.Fields fields = Pivot.fields(f, doc.lineCount(), CsvRows.reader(doc, f), 2026);
            Pivot.Result p = Pivot.compute(f, doc.lineCount(), CsvRows.reader(doc, f), null,
                    new Pivot.Spec(List.of(new Pivot.Field(1)), List.of(), List.of(new Pivot.Value(3, Pivot.Agg.SUM)),
                            Map.of()), fields, ZoneOffset.UTC, () -> false, null);
            assertEquals(6, p.rows(), "six rows, not ten lines");
            List<String> regions = new ArrayList<>();
            for (Pivot.Node k : p.root().kids()) {
                regions.add(k.label() + "=" + (long) p.value(k, -1, 0));
            }
            assertEquals(List.of("East=40", "North=40", "South=80", "West=50"), regions.stream().sorted().toList());

            ColumnStats.Result st = ColumnStats.count(doc, f, "region", () -> false);
            assertEquals(6, st.values().stream().mapToLong(ColumnStats.Value::count).sum());
            assertEquals(2, st.values().stream().filter(v -> v.value().equals("South")).findFirst().orElseThrow()
                    .count());

            // a filter on a column: the row matches at its first line, never a piece of it
            try (FilterEngine e = new FilterEngine(doc)) {
                SearchQuery q = new SearchQuery("two", false, false, false);
                e.configure(List.of(new FilterEngine.Spec('A', q, new ColumnFilter(f, "note", ColumnFilter.Op.CONTAINS, q))),
                        new Formula.Ref('A'));
                assertTrue(e.awaitIdle(10_000));
                FilterResult r = e.result();
                List<Long> hits = new ArrayList<>();
                for (int i = 0; i < r.count(); i++) {
                    hits.add(r.lineAt(i));
                }
                assertEquals(List.of(2L, 6L), hits, "rows 2 (line two) and 5 (TWO)");
            }
        }
    }

    @Test
    void joinsLinesIntoRowsForDetection() {
        assertEquals(List.of("a,b", "1,\"x\ny\"", "2,z"), CsvRows.join(List.of("a,b", "1,\"x", "y\"", "2,z"), ','));
        assertTrue(CsvRows.open("1,\"x", ','));
        assertFalse(CsvRows.open("1,\"x\"\"\"", ','));
        assertFalse(CsvRows.open("1,5\" x", ','));
        // a header with a quoted name over two lines, and rows that all have a note over two lines
        List<String> lines = new ArrayList<>(List.of("id,\"long", "name\",note"));
        for (int i = 0; i < 20; i++) {
            lines.add(i + ",v,\"first");
            lines.add("second\"");
        }
        LogFormat f = LogFormat.detectCsv(lines, true);
        assertNotNull(f);
        assertEquals(List.of("id", "long\nname", "note"), f.columns());
    }
}
