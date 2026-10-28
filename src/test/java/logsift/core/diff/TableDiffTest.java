package logsift.core.diff;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Comparing CSV files by key, and in file order without one. */
class TableDiffTest {
    @TempDir
    Path dir;
    private final List<CsvFile> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(CsvFile::close);
    }

    private CsvFile csv(String name, String content) throws IOException {
        return csv(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private CsvFile csv(String name, byte[] content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content);
        CsvFile f = CsvFile.open(p, null);
        open.add(f);
        return f;
    }

    private static TableDiffResult diff(CsvFile a, CsvFile b, DiffOptions o, String... keys) throws IOException {
        ColumnMap m = ColumnMap.byName(a.header(), b.header());
        return diff(a, b, m, o, Set.of(), keys);
    }

    private static TableDiffResult diff(CsvFile a, CsvFile b, ColumnMap m, DiffOptions o, Set<Integer> ignored,
                                        String... keys) throws IOException {
        List<Integer> k = new ArrayList<>();
        for (String n : keys) {
            int p = m.pairNamed(n);
            assertTrue(p >= 0, n);
            k.add(p);
        }
        return TableDiff.compare(a, b, new TableDiff.Spec(m, k, ignored, o), null, null);
    }

    /** status:key, for every item. */
    private static List<String> items(TableDiffResult r) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < r.size(); i++) {
            out.add(r.status(i).name() + ":" + (r.keyed() ? r.keyText(i)
                    : r.leftRow(i) + "/" + r.rightRow(i)));
        }
        return out;
    }

    private static Map<String, String> byKey(TableDiffResult r) {
        Map<String, String> out = new TreeMap<>();
        for (int i = 0; i < r.size(); i++) {
            out.merge(r.keyText(i), r.status(i).name(), (x, y) -> x + "," + y);
        }
        return out;
    }

    @Test
    void shuffledRowsMatchByKey() throws IOException {
        StringBuilder a = new StringBuilder("id,name,price\n");
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            rows.add(i + ",item " + i + "," + (i * 1.25));
        }
        rows.forEach(s -> a.append(s).append('\n'));
        Collections.shuffle(rows, new Random(1));
        StringBuilder b = new StringBuilder("id,name,price\n");
        rows.forEach(s -> b.append(s).append('\n'));
        TableDiffResult r = diff(csv("a.csv", a.toString()), csv("b.csv", b.toString()), DiffOptions.DEFAULT, "id");
        assertEquals(500, r.count(DiffStatus.SAME));
        assertEquals(500, r.size());
        assertEquals(0, r.count(DiffStatus.CHANGED) + r.count(DiffStatus.ADDED) + r.count(DiffStatus.REMOVED));
        // without the key, the same rows in another order are mostly differences
        TableDiffResult inOrder = diff(r.left(), r.right(), DiffOptions.DEFAULT);
        assertTrue(inOrder.count(DiffStatus.SAME) < 100, items(inOrder).toString());
        // ... unless row order is ignored
        TableDiffResult any = diff(r.left(), r.right(), DiffOptions.DEFAULT.withIgnoreRowOrder(true));
        assertEquals(500, any.count(DiffStatus.SAME));
    }

    @Test
    void changedAddedRemoved() throws IOException {
        CsvFile a = csv("a.csv", "id,name,price\n1,apple,1.00\n2,pear,2.00\n3,plum,3.00\n4,fig,4\n");
        CsvFile b = csv("b.csv", "id,name,price\n4,fig,4.0\n2,Pear,2.50\n1,apple,1.0\n5,kiwi,5\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        assertEquals(Map.of("1", "SAME", "2", "CHANGED", "3", "REMOVED", "4", "SAME", "5", "ADDED"), byKey(r));
        assertEquals(1, r.count(DiffStatus.CHANGED));
        int changed = r.filter(EnumSet.of(DiffStatus.CHANGED), -1)[0];
        int name = r.columns().pairNamed("name");
        int price = r.columns().pairNamed("price");
        assertArrayEquals(new int[] {name, price}, r.changedPairs(changed));
        assertEquals("pear", r.leftValue(changed, name));
        assertEquals("Pear", r.rightValue(changed, name));
        assertEquals(1, r.changedRows(price));
        assertEquals(Map.of(name, 1, price, 1), r.changesPerColumn());
        // lines in the files (0-based; the header is line 0)
        assertEquals(2, r.leftLine(changed));
        assertEquals(2, r.rightLine(changed));
        // filters: by column, and by status
        assertEquals(1, r.filter(null, price).length);
        assertEquals(1, r.filter(EnumSet.of(DiffStatus.ADDED), -1).length);
        // order: the left file's, rows only on the right after the row before them there
        // (5 follows 1 on the right: b.csv has 4, 2, 1, 5)
        assertEquals(List.of("SAME:1", "ADDED:5", "CHANGED:2", "REMOVED:3", "SAME:4"), items(r));
        // ignore case: only the price differs
        TableDiffResult ic = diff(a, b, DiffOptions.DEFAULT.withIgnoreCase(true), "id");
        int c2 = ic.filter(EnumSet.of(DiffStatus.CHANGED), -1)[0];
        assertArrayEquals(new int[] {price}, ic.changedPairs(c2));
    }

    @Test
    void duplicateKeysAreListedNotMatched() throws IOException {
        CsvFile a = csv("a.csv", "id,v\n1,a\n2,b\n2,c\n3,d\n4,e\n");
        CsvFile b = csv("b.csv", "id,v\n1,a\n2,b\n3,d\n3,x\n4,e\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        // 2 twice on the left, 3 twice on the right: all their rows, on both sides, are duplicates
        assertEquals(Map.of("1", "SAME", "2", "DUPLICATE,DUPLICATE,DUPLICATE", "3", "DUPLICATE,DUPLICATE,DUPLICATE",
                "4", "SAME"), byKey(r));
        assertEquals(2, r.duplicateKeys());
        assertEquals(6, r.count(DiffStatus.DUPLICATE));
        assertEquals(0, r.count(DiffStatus.ADDED) + r.count(DiffStatus.REMOVED) + r.count(DiffStatus.CHANGED));
    }

    @Test
    void compositeKeys() throws IOException {
        CsvFile a = csv("a.csv", "region,sku,qty\neu,1,10\nus,1,20\neu,2,30\n");
        CsvFile b = csv("b.csv", "region,sku,qty\nus,1,21\neu,2,30\neu,1,10\nus,2,5\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "region", "sku");
        assertEquals(Map.of("eu · 1", "SAME", "us · 1", "CHANGED", "eu · 2", "SAME", "us · 2", "ADDED"), byKey(r));
        // one column alone is not unique: duplicates
        TableDiffResult sku = diff(a, b, DiffOptions.DEFAULT, "sku");
        assertEquals(0, sku.count(DiffStatus.SAME));
        assertEquals(2, sku.duplicateKeys());
    }

    @Test
    void numbersAsNumbersAndTolerance() throws IOException {
        CsvFile a = csv("a.csv", "id,price\n1,1.50\n2,100\n3,0.30\n4,abc\n5,-0\n6,1e3\n");
        CsvFile b = csv("b.csv", "id,price\n1,1.5\n2,101\n3,0.31\n4,ABC\n5,0.00\n6,1000\n");
        TableDiffResult exact = diff(a, b, DiffOptions.DEFAULT, "id");
        assertEquals(Map.of("1", "SAME", "2", "CHANGED", "3", "CHANGED", "4", "CHANGED", "5", "SAME", "6", "SAME"),
                byKey(exact));
        TableDiffResult abs = diff(a, b, DiffOptions.DEFAULT.withNumbers(true, 0.02, false), "id");
        assertEquals("CHANGED", byKey(abs).get("2"));
        assertEquals("SAME", byKey(abs).get("3"));
        TableDiffResult pct = diff(a, b, DiffOptions.DEFAULT.withNumbers(true, 1, true), "id");
        assertEquals("SAME", byKey(pct).get("2"), "1 in 101 is within 1%");
        assertEquals("CHANGED", byKey(pct).get("3"), "0.01 in 0.31 is 3%");
        TableDiffResult text = diff(a, b, DiffOptions.DEFAULT.withNumbers(false, 0, false), "id");
        assertEquals("CHANGED", byKey(text).get("1"), "as text, 1.50 is not 1.5");
    }

    @Test
    void trimCaseAndEmptyOptions() throws IOException {
        CsvFile a = csv("a.csv", "id,name,note\nA1,x  ,\nb2,y,z\nc3,w\n");
        CsvFile b = csv("b.csv", "id,name,note\na1,x,\nB2,y,z\nc3,w,\n");
        // keys are matched as written unless ignore case: A1 and a1 differ
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        assertEquals(2, r.count(DiffStatus.ADDED));
        assertEquals(2, r.count(DiffStatus.REMOVED));
        assertEquals("SAME", byKey(r).get("c3"), "a short row's missing value is empty by default");
        TableDiffResult ic = diff(a, b, DiffOptions.DEFAULT.withIgnoreCase(true), "id");
        assertEquals(3, ic.count(DiffStatus.SAME), "trailing spaces trimmed, case ignored in keys too");
        TableDiffResult noTrim = diff(a, b, DiffOptions.DEFAULT.withIgnoreCase(true).withTrim(false), "id");
        assertEquals("CHANGED", byKey(noTrim).get("A1"));
        TableDiffResult strictEmpty = diff(a, b, DiffOptions.DEFAULT.withEmptyEqualsMissing(false), "id");
        assertEquals("CHANGED", byKey(strictEmpty).get("c3"), "missing is not empty");
    }

    @Test
    void columnOnlyOnOneSideIsReportedOnce() throws IOException {
        CsvFile a = csv("a.csv", "id,price,fax\n1,1,x\n2,2,y\n");
        CsvFile b = csv("b.csv", "ID , Price,region\n2,2,eu\n1,1,us\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        assertEquals(2, r.count(DiffStatus.SAME), "names match whatever their case and spaces");
        assertEquals(List.of("Column fax only in the left file (a.csv)", "Column region only in the right file (b.csv)"),
                r.notes());
    }

    @Test
    void manualMappingAndIgnoredColumns() throws IOException {
        CsvFile a = csv("a.csv", "id,cost,updated\n1,5,mon\n2,6,mon\n");
        CsvFile b = csv("b.csv", "id,price,updated\n1,5,tue\n2,7,tue\n");
        ColumnMap m = ColumnMap.byName(a.header(), b.header()).with(1, 1);
        assertEquals("cost / price", m.name(m.pairOfLeft(1)));
        int updated = m.pairNamed("updated");
        TableDiffResult r = diff(a, b, m, DiffOptions.DEFAULT, Set.of(updated), "id");
        assertEquals(Map.of("1", "SAME", "2", "CHANGED"), byKey(r));
        assertTrue(r.notes().contains("Column updated not compared (ignored)"), r.notes().toString());
        int c = r.filter(EnumSet.of(DiffStatus.CHANGED), -1)[0];
        assertArrayEquals(new int[] {m.pairOfLeft(1)}, r.changedPairs(c));
        // unmapping a column leaves it on one side
        ColumnMap less = m.withoutLeft(1);
        assertEquals(List.of(1), less.leftOnly());
        assertEquals(List.of(1), less.rightOnly());
    }

    @Test
    void noKeysLinesUpInFileOrder() throws IOException {
        CsvFile a = csv("a.csv", "name,qty\napple,1\npear,2\nplum,3\nfig,4\nlime,5\n");
        CsvFile b = csv("b.csv", "name,qty\napple,1\nkiwi,9\npear,2\nplum,30\nfig,4\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT);
        assertEquals(List.of("SAME:0/0", "ADDED:-1/1", "SAME:1/2", "CHANGED:2/3", "SAME:3/4", "REMOVED:4/-1"), items(r));
        assertEquals("lined up in file order", r.matchedBy());
        int c = r.filter(EnumSet.of(DiffStatus.CHANGED), -1)[0];
        assertArrayEquals(new int[] {r.columns().pairNamed("qty")}, r.changedPairs(c));
    }

    @Test
    void quotedAndMultiLineFields() throws IOException {
        CsvFile a = csv("a.csv", "id,text\n\"1\",\"one, two\"\n\"2\",\"line 1\nline 2\"\n3,\"say \"\"hi\"\"\"\n");
        CsvFile b = csv("b.csv", "id,text\n3,\"say \"\"hi\"\"\"\n\"2\",\"line 1\nline 2 changed\"\n1,\"one, two\"\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        assertEquals(Map.of("1", "SAME", "2", "CHANGED", "3", "SAME"), byKey(r));
        int c = r.filter(EnumSet.of(DiffStatus.CHANGED), -1)[0];
        assertEquals("line 1\nline 2", r.leftValue(c, 1));
        assertEquals("line 1\nline 2 changed", r.rightValue(c, 1));
        // the row's line is where it starts; the one after a two-line row is a line further
        int three = -1;
        for (int i = 0; i < r.size(); i++) {
            if (r.keyText(i).equals("3")) {
                three = i;
            }
        }
        assertEquals(4, r.leftLine(three));
        assertEquals(1, r.rightLine(three));
    }

    @Test
    void differentDelimitersEncodingsBomAndCrlf() throws IOException {
        CsvFile a = csv("a.csv", "﻿id,name,city\r\n1,Zoë,Köln\r\n2,Ann,Paris\r\n");
        CsvFile b = csv("b.csv", "id;name;city\n2;Ann;Paris\n1;Zoë;Koeln\n".getBytes(Charset.forName("windows-1252")));
        assertEquals(',', a.delimiter());
        assertEquals(';', b.delimiter());
        assertEquals(List.of("id", "name", "city"), a.header(), "no byte order mark in the first name");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        assertEquals(Map.of("1", "CHANGED", "2", "SAME"), byKey(r));
        int c = r.filter(EnumSet.of(DiffStatus.CHANGED), -1)[0];
        assertEquals("Köln", r.leftValue(c, 2), "no \\r at the end of the last value");
        assertEquals("Koeln", r.rightValue(c, 2));
        assertEquals("Zoë", r.rightValue(c, 1), "Windows-1252 read as such");
        // a tab-separated file, and UTF-16 with a byte order mark
        CsvFile t = csv("c.tsv", "id\tname\tcity\n1\tZoë\tKöln\n2\tAnn\tParis\n");
        CsvFile u = csv("d.csv", "﻿id,name,city\r\n2,Ann,Paris\r\n1,Zoë,Köln\r\n".getBytes(StandardCharsets.UTF_16LE));
        TableDiffResult tu = diff(t, u, DiffOptions.DEFAULT, "id");
        assertEquals(2, tu.count(DiffStatus.SAME));
        assertEquals(List.of("id", "name", "city"), u.header());
        assertEquals(2, tu.rightLine(0), "row 1 is on the UTF-16 file's third line");
        assertEquals("Köln", tu.rightValue(0, 2));
    }

    @Test
    void emptyHeaderOnlyAndNotCsvFiles() throws IOException {
        assertThrows(CsvFile.NotCsvException.class, () -> csv("e.csv", ""));
        assertThrows(CsvFile.NotCsvException.class, () -> csv("blank.csv", "\n\n  \n"));
        CsvFile h = csv("h.csv", "id,name\n");
        CsvFile f = csv("f.csv", "id,name\n1,a\n");
        TableDiffResult r = diff(h, f, DiffOptions.DEFAULT, "id");
        assertEquals(List.of("ADDED:1"), items(r));
        TableDiffResult both = diff(h, h, DiffOptions.DEFAULT, "id");
        assertEquals(0, both.size());
        Path log = dir.resolve("app.log");
        Files.writeString(log, "2026-10-21 10:00:00,123 INFO started\n2026-10-21 10:00:01,456 INFO ready\n"
                + "2026-10-21 10:00:02,789 WARN slow\n2026-10-21 10:00:03,000 INFO done\n2026-10-21 10:00:04,000 INFO x\n");
        CsvFile.NotCsvException e = assertThrows(CsvFile.NotCsvException.class, () -> CsvFile.open(log, null));
        assertTrue(e.getMessage().contains("doesn't look like a CSV file"), e.getMessage());
    }

    @Test
    void veryWideRows() throws IOException {
        StringBuilder head = new StringBuilder("id");
        StringBuilder row1 = new StringBuilder("1");
        StringBuilder row2 = new StringBuilder("1");
        for (int c = 0; c < 2000; c++) {
            head.append(",c").append(c);
            row1.append(',').append("v".repeat(600)).append(c);
            row2.append(',').append("v".repeat(600)).append(c == 1500 ? "changed" : String.valueOf(c));
        }
        CsvFile a = csv("a.csv", head + "\n" + row1 + "\n");
        CsvFile b = csv("b.csv", head + "\n" + row2 + "\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        assertEquals(1, r.count(DiffStatus.CHANGED));
        assertArrayEquals(new int[] {r.columns().pairNamed("c1500")}, r.changedPairs(0));
    }

    @Test
    void searchExportAndSummary() throws IOException {
        CsvFile a = csv("a.csv", "id,name,price\n1,apple,1\n2,pear,2\n3,plum,3\n");
        CsvFile b = csv("b.csv", "id,name,price\n1,apple,1\n2,pear,2.5\n4,\"kiwi, gold\",4\n");
        TableDiffResult r = diff(a, b, DiffOptions.DEFAULT, "id");
        // kiwi follows pear on the right, so it is listed after it
        int[] diffs = r.filter(EnumSet.of(DiffStatus.CHANGED, DiffStatus.ADDED, DiffStatus.REMOVED), -1);
        assertEquals(3, diffs.length);
        assertEquals(1, r.search(diffs, "KIWI", null).length);
        assertEquals(0, r.search(diffs, "apple", null).length);
        StringWriter w = new StringWriter();
        TableDiffExport.writeCsv(r, diffs, w, null);
        assertEquals("status,left line,right line,id,name (left),name (right),price (left),price (right)\n"
                + "changed,3,3,2,pear,,2,2.5\n"
                + "added,,4,4,,\"kiwi, gold\",,4\n"
                + "removed,4,,3,plum,,3,\n", w.toString());
        String s = r.summary();
        assertTrue(s.contains("Compared a.csv (3 rows) with b.csv (3 rows), by key id."), s);
        assertTrue(s.contains("Added: 1   Removed: 1   Changed: 1   Same: 1   Duplicate keys: 0"), s);
        assertTrue(s.contains("Changes per column: price: 1"), s);
    }

    @Test
    void cancel() throws IOException {
        StringBuilder sb = new StringBuilder("id,v\n");
        for (int i = 0; i < 300_000; i++) {
            sb.append(i).append(",x\n");
        }
        CsvFile a = csv("a.csv", sb.toString());
        CsvFile b = csv("b.csv", sb.toString());
        ColumnMap m = ColumnMap.byName(a.header(), b.header());
        int[] calls = {0};
        assertThrows(CancellationException.class, () -> TableDiff.compare(a, b,
                new TableDiff.Spec(m, List.of(0), Set.of(), DiffOptions.DEFAULT), null, () -> ++calls[0] > 3));
        assertTrue(calls[0] > 3);
    }
}
