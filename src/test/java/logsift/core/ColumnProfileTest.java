package logsift.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What each column of a table holds, and its numbers and charts. */
class ColumnProfileTest {

    /** A table without times: an id, a category, numbers, text, yes/no, a mostly empty column. */
    static List<String> table(int rows) {
        List<String> out = new ArrayList<>();
        out.add("order_id,country,amount_eur,weight_kg,paid,note,size_bytes,shipped");
        String[] countries = {"DE", "FR", "NL", "DE", "DE", "IT"};
        for (int i = 0; i < rows; i++) {
            out.add(String.format("A-%05d,%s,%d.%02d,%d,%s,%s,%d,%s", i, countries[i % countries.length], 10 + i % 90,
                    i % 100, 1 + i % 7, i % 4 == 0 ? "no" : "yes",
                    i % 10 == 0 ? "\"left at the door, ring twice\"" : "",
                    (long) Math.pow(10, 1 + i % 6) + i, i % 3 == 0 ? "2026-10-" + (10 + i % 20) + " 12:00:00" : ""));
        }
        return out;
    }

    private static ColumnProfile.Result profile(List<String> lines) {
        LogFormat f = LogFormat.detectCsv(lines.subList(0, Math.min(100, lines.size())), true);
        return ColumnProfile.analyze(f, lines.size(), (first, count, out) -> {
            for (int i = 0; i < count; i++) {
                out[i] = first + i < lines.size() ? lines.get((int) (first + i)) : null;
            }
        }, null, 2026, () -> false, null);
    }

    private static ColumnProfile.Column col(ColumnProfile.Result r, String name) {
        return r.columns().stream().filter(c -> c.title().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void kinds() {
        ColumnProfile.Result r = profile(table(1000));
        assertEquals(1000, r.rows(), "rows, not the header");
        assertTrue(!r.sampled());
        assertEquals(ColumnProfile.Kind.ID, col(r, "order_id").kind());
        assertEquals(ColumnProfile.Kind.CATEGORY, col(r, "country").kind());
        assertEquals(ColumnProfile.Kind.NUMBER, col(r, "amount_eur").kind());
        assertEquals(ColumnProfile.Kind.CATEGORY, col(r, "weight_kg").kind(), "seven values: a category");
        assertEquals(ColumnProfile.Kind.BOOLEAN, col(r, "paid").kind());
        assertEquals(ColumnProfile.Kind.CATEGORY, col(r, "note").kind(), "one note, mostly empty");
        assertEquals(ColumnProfile.Kind.NUMBER, col(r, "size_bytes").kind());
        assertEquals(ColumnProfile.Kind.TIME, col(r, "shipped").kind());
    }

    @Test
    void stats() {
        ColumnProfile.Result r = profile(table(1000));
        ColumnProfile.Column country = col(r, "country");
        assertEquals(1000, country.filled());
        assertEquals(4, country.distinct());
        assertEquals("DE", country.bars().get(0).label());
        assertEquals(500, country.bars().get(0).count());
        ColumnProfile.Column note = col(r, "note");
        assertEquals(100, note.filled());
        assertEquals(900, note.empty());
        ColumnProfile.Column amount = col(r, "amount_eur");
        assertEquals(10.0, amount.stats()[0], 1e-9, "min");
        assertEquals(99.99, amount.stats()[4], 1e-9, "max");
        assertTrue(!amount.logScale());
        assertEquals(1000, amount.bars().stream().mapToLong(ColumnProfile.Bar::count).sum(), "every value in a bar");
        ColumnProfile.Column size = col(r, "size_bytes");
        assertTrue(size.logScale(), "10 B to 1 MB: buckets ×10");
        assertEquals(Numbers.Unit.BYTES, size.unit());
        assertEquals(1000, size.bars().stream().mapToLong(ColumnProfile.Bar::count).sum());
        ColumnProfile.Column shipped = col(r, "shipped");
        assertEquals(334, shipped.filled());
        assertEquals(2, shipped.stats().length, "first and last");
        assertTrue(shipped.stats()[0] < shipped.stats()[1]);
        ColumnProfile.Column id = col(r, "order_id");
        assertEquals(1000, id.distinct());
        assertEquals(7, id.shortest());
        assertEquals(7, id.longest());
    }

    @Test
    void bigFilesAreSampled() {
        ColumnProfile.Result r = profile(table(ColumnProfile.MAX_ROWS * 2));
        assertTrue(r.sampled());
        assertTrue(r.rows() <= ColumnProfile.MAX_ROWS && r.rows() > ColumnProfile.MAX_ROWS / 2, "" + r.rows());
        assertEquals(ColumnProfile.Kind.CATEGORY, col(r, "country").kind());
    }

    @Test
    void onlyTheLinesAsked() {
        List<String> lines = table(600);
        LogFormat f = LogFormat.detectCsv(lines.subList(0, 100), true);
        ColumnProfile.Result r = ColumnProfile.analyze(f, lines.size(), (first, count, out) -> {
            for (int i = 0; i < count; i++) {
                out[i] = first + i < lines.size() ? lines.get((int) (first + i)) : null;
            }
        }, line -> line % 2 == 1, 2026, () -> false, null);
        assertEquals(300, r.rows());
        assertTrue(r.filtered());
    }

    @Test
    void roundEdges() {
        assertArrayEquals(new double[] {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100},
                ColumnProfile.niceEdges(3, 97, 10), 1e-9);
        assertEquals(3, ColumnProfile.countIn(new double[] {1, 2, 2, 3}, 2, 3, true));
        assertEquals(2, ColumnProfile.countIn(new double[] {1, 2, 2, 3}, 2, 3, false));
    }

    @Test
    void rangeFilterReadsItsOwnBounds() {
        LogFormat f = LogFormat.detectCsv(List.of("op,took_ms,size_kb", "a,5,1", "b,7,2"), true);
        SearchQuery q = SearchQuery.literal(ColumnFilter.write(5, Numbers.Unit.MILLIS) + ".."
                + ColumnFilter.write(6.5, Numbers.Unit.MILLIS));
        ColumnFilter cf = new ColumnFilter(f, "took_ms", ColumnFilter.Op.BETWEEN, q);
        assertTrue(cf.lineTest().test("a,5,1"));
        assertTrue(!cf.lineTest().test("b,7,2"));
        // a number without a unit has the column's: 2 in size_kb is 2048 bytes
        ColumnFilter kb = new ColumnFilter(f, "size_kb", ColumnFilter.Op.MORE,
                SearchQuery.literal(ColumnFilter.write(1500, Numbers.Unit.BYTES)));
        assertTrue(kb.lineTest().test("b,7,2"));
        assertTrue(!kb.lineTest().test("a,5,1"));
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    @Test
    void aTableHasNoMessages() throws Exception {
        java.nio.file.Path f = dir.resolve("t.csv");
        java.nio.file.Files.write(f, table(100));
        try (LogDocument doc = LogDocument.open(f)) {
            assertTrue(doc.awaitIdle(10_000));
            LogFormat fmt = LogFormat.detectCsv(table(100).subList(0, 50), true);
            MessageStats.Result r = MessageStats.analyze(doc, null, fmt, 0, Long.MAX_VALUE, null, () -> false, null);
            assertTrue(r.noMessageColumn());
            assertTrue(r.templates().isEmpty());
            // without the format, lines are grouped as before
            assertTrue(!MessageStats.analyze(doc, null, 0, Long.MAX_VALUE, null, () -> false, null).templates().isEmpty());
        }
    }

    @Test
    void aBarsFilterKeepsExactlyItsRows() {
        // values right on the bucket edges, decimals like 0.3, sizes over orders of magnitude, units in values
        List<String> lines = new ArrayList<>();
        lines.add("id,amount,ratio,size_bytes,took,neg");
        java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < 3000; i++) {
            String amount = i % 3 == 0 ? Integer.toString(i % 101) : String.format(java.util.Locale.ROOT, "%.2f",
                    rnd.nextDouble() * 100);
            String ratio = java.math.BigDecimal.valueOf(i % 31).movePointLeft(1).toPlainString(); // 0.0 .. 3.0
            long size = (long) Math.pow(10, i % 7) * (i % 3 + 1); // 1, 2, 3 × a power of ten: on the edges too
            String took = i % 2 == 0 ? (i % 50) * 100 + " ms" : (i % 7) / 2.0 + " s";
            lines.add(i + "," + amount + "," + ratio + "," + size + "," + took + "," + (i % 41 - 20));
        }
        ColumnProfile.Result r = profile(lines);
        LogFormat f = LogFormat.detectCsv(lines.subList(0, 100), true);
        for (String name : List.of("amount", "ratio", "size_bytes", "took", "neg")) {
            ColumnProfile.Column c = col(r, name);
            assertEquals(ColumnProfile.Kind.NUMBER, c.kind(), name);
            long inBars = 0;
            for (int i = 0; i < c.bars().size(); i++) {
                ColumnFilter cf = new ColumnFilter(f, name, ColumnFilter.Op.BETWEEN,
                        SearchQuery.literal(ColumnProfile.filterText(c, i)));
                java.util.function.Predicate<String> test = cf.lineTest();
                long kept = lines.stream().skip(1).filter(test).count();
                assertEquals(c.bars().get(i).count(), kept, name + " bar " + i + " " + ColumnProfile.filterText(c, i));
                inBars += kept;
            }
            assertEquals(3000, inBars, name + ": every row in exactly one bar");
        }
        assertTrue(col(r, "size_bytes").logScale());
        assertTrue(ColumnProfile.filterText(col(r, "amount"), 0).contains("..<"));
        String last = ColumnProfile.filterText(col(r, "amount"), col(r, "amount").bars().size() - 1);
        assertTrue(last.contains("..") && !last.contains("..<"), last);
    }
}
