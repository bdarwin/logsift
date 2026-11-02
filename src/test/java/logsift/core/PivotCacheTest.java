package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Pivot cache gives what Pivot gives reading the file, and is made again when the file changes. */
class PivotCacheTest {
    @TempDir
    Path dir;

    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** host, status, user, ms, bytes, at (a time), note (blanks, quotes, odd numbers), and a trailing comma. */
    static Path table(Path f, int n, long seed) throws Exception {
        Random rnd = new Random(seed);
        String[] st = {"ok", "ok", "slow", "error", ""};
        String[] notes = {"", "\"a, quoted\"", "7 ms", "$12", "n/a", "  padded  ", "\"say \"\"hi\"\"\""};
        StringBuilder b = new StringBuilder("host,status,user,ms,bytes,at,note,\r\n");
        for (int i = 0; i < n; i++) {
            b.append("web-").append(rnd.nextInt(5)).append(',').append(st[rnd.nextInt(st.length)]).append(',')
                    .append("u").append(rnd.nextInt(300)).append(',')
                    .append(rnd.nextInt(10) == 0 ? "" : String.valueOf(rnd.nextInt(5000) / 10.0)).append(',')
                    .append(rnd.nextInt(1_000_000)).append(',')
                    .append(rnd.nextInt(30) == 0 ? "never" : String.format("2026-%02d-%02d %02d:%02d:00",
                            1 + rnd.nextInt(12), 1 + rnd.nextInt(28), rnd.nextInt(24), rnd.nextInt(60))).append(',')
                    .append(notes[rnd.nextInt(notes.length)]).append(",\r\n");
            if (i % 997 == 5) {
                b.append("\r\n"); // a blank line now and then
            }
        }
        Files.writeString(f, b.toString());
        return f;
    }

    record Opened(LogDocument doc, LogFormat format, ColumnProfile.Lines lines, Pivot.Fields fields) {}

    static Opened open(Path f) throws Exception {
        LogDocument doc = LogDocument.open(f);
        while (doc.isIndexing()) {
            Thread.sleep(5);
        }
        String[] s = new String[100];
        doc.readLines(0, 100, s);
        List<String> sample = new ArrayList<>();
        for (String x : s) {
            if (x != null) {
                sample.add(x);
            }
        }
        LogFormat fmt = LogFormat.detectCsv(sample, true);
        ColumnProfile.Lines lines = CsvRows.reader(doc, fmt);
        return new Opened(doc, fmt, lines, Pivot.fields(fmt, doc.lineCount(), lines, 2026));
    }

    /** A result written out in full: every group, column group and value, in order. */
    static String dump(Pivot.Result r) {
        StringBuilder b = new StringBuilder();
        b.append("rows ").append(r.rows()).append(" cols ").append(r.colKeys()).append('\n');
        for (int c : r.spec().filters().keySet()) {
            b.append("filter ").append(c).append(' ').append(r.filterValues(c)).append('\n');
        }
        dump(r, r.root(), b);
        return b.toString();
    }

    private static void dump(Pivot.Result r, Pivot.Node n, StringBuilder b) {
        b.append(n.path()).append(" (").append(n.rows()).append(")");
        int nv = r.spec().cellValues().size();
        for (int col = -1; col < r.colGroups(); col++) {
            for (int v = 0; v < nv; v++) {
                double x = r.value(n, col, v);
                b.append(' ').append(Double.isNaN(x) ? "-" : String.format(java.util.Locale.ROOT, "%.6g", x));
            }
            b.append(" |");
        }
        b.append('\n');
        for (Pivot.Node k : n.kids()) {
            dump(r, k, b);
        }
    }

    /** The same groups, labels and counts, and the same values (to 1e-9 of their size). */
    static void assertSame(Pivot.Result a, Pivot.Result b, String what) {
        assertEquals(dump(a).replaceAll(" [-0-9.e+]+", " #"), dump(b).replaceAll(" [-0-9.e+]+", " #"), what);
        assertEquals(a.rows(), b.rows(), what);
        assertEquals(a.colKeys(), b.colKeys(), what);
        sameValues(a, a.root(), b, b.root(), what);
    }

    private static void sameValues(Pivot.Result a, Pivot.Node x, Pivot.Result b, Pivot.Node y, String what) {
        assertEquals(x.rows(), y.rows(), what + " " + x.path());
        for (int col = -1; col < a.colGroups(); col++) {
            for (int v = 0; v < a.spec().cellValues().size(); v++) {
                double p = a.value(x, col, v);
                double q = b.value(y, col, v);
                if (Double.isNaN(p) || Double.isNaN(q)) {
                    assertEquals(Double.isNaN(p), Double.isNaN(q), what + " " + x.path() + " col " + col + " value " + v);
                } else {
                    assertEquals(p, q, 1e-9 * Math.max(1, Math.abs(p)), what + " " + x.path() + " col " + col + " value " + v);
                }
            }
        }
        List<Pivot.Node> xs = x.kids();
        List<Pivot.Node> ys = y.kids();
        assertEquals(xs.size(), ys.size(), what + " " + x.path());
        for (int i = 0; i < xs.size(); i++) {
            assertEquals(xs.get(i).label(), ys.get(i).label(), what);
            sameValues(a, xs.get(i), b, ys.get(i), what);
        }
    }

    static Pivot.Value v(int c, Pivot.Agg a) {
        return new Pivot.Value(c, a);
    }

    static Pivot.Value v(int c, Pivot.Agg a, Pivot.ShowAs s) {
        return Pivot.Value.of(List.of(c), null, a, s);
    }

    @Test
    void answersAsPivotReadingTheFile() throws Exception {
        Path f = table(dir.resolve("t.csv"), 6000, 1);
        Opened o = open(f);
        Pivot.Field host = new Pivot.Field(0);
        Pivot.Field status = new Pivot.Field(1);
        Pivot.Field user = new Pivot.Field(2);
        Pivot.Field month = new Pivot.Field(5, Pivot.Group.MONTH);
        Pivot.Field weekday = new Pivot.Field(5, Pivot.Group.WEEKDAY);
        Pivot.Field hour = new Pivot.Field(5, Pivot.Group.HOUR_OF_DAY);
        Pivot.Field note = new Pivot.Field(6);
        Pivot.Value pooled = Pivot.Value.of(List.of(3, 4, 6), "pool", Pivot.Agg.SUM, Pivot.ShowAs.VALUE);
        List<Pivot.Spec> specs = List.of(
                new Pivot.Spec(List.of(), List.of(), List.of(), Map.of()),
                new Pivot.Spec(List.of(host), List.of(), List.of(v(3, Pivot.Agg.SUM)), Map.of()),
                new Pivot.Spec(List.of(host, status), List.of(), List.of(v(3, Pivot.Agg.SUM), v(3, Pivot.Agg.AVERAGE),
                        v(4, Pivot.Agg.MIN), v(4, Pivot.Agg.MAX), v(6, Pivot.Agg.COUNT),
                        v(2, Pivot.Agg.COUNT_DISTINCT)), Map.of()),
                new Pivot.Spec(List.of(host), List.of(status), List.of(v(3, Pivot.Agg.SUM), v(-1, Pivot.Agg.COUNT)),
                        Map.of()),
                new Pivot.Spec(List.of(month), List.of(host), List.of(v(3, Pivot.Agg.MEDIAN), v(3, Pivot.Agg.P95),
                        v(4, Pivot.Agg.P99)), Map.of()),
                new Pivot.Spec(List.of(weekday, hour), List.of(), List.of(v(4, Pivot.Agg.SUM, Pivot.ShowAs.PCT_TOTAL)),
                        Map.of()),
                new Pivot.Spec(List.of(status), List.of(host, note), List.of(v(3, Pivot.Agg.SUM, Pivot.ShowAs.PCT_ROW),
                        v(3, Pivot.Agg.COUNT, Pivot.ShowAs.PCT_COLUMN)), Map.of()),
                new Pivot.Spec(List.of(note), List.of(), List.of(v(6, Pivot.Agg.SUM), v(6, Pivot.Agg.COUNT_DISTINCT)),
                        Map.of()),
                new Pivot.Spec(List.of(host), List.of(), List.of(pooled, Pivot.Value.of(List.of(3, 4, 6), "pool",
                        Pivot.Agg.MAX, Pivot.ShowAs.VALUE), Pivot.Value.of(List.of(3, 4, 6), "pool", Pivot.Agg.COUNT,
                        Pivot.ShowAs.VALUE)), Map.of()),
                new Pivot.Spec(List.of(host), List.of(), List.of(v(3, Pivot.Agg.SUM)),
                        Map.of(1, Set.of("ok", Pivot.BLANK), 6, Set.of("a, quoted", "say \"hi\""))),
                new Pivot.Spec(List.of(user), List.of(), List.of(v(4, Pivot.Agg.SUM)), Map.of(0, Set.of())),
                new Pivot.Spec(List.of(user, host), List.of(month), List.of(v(4, Pivot.Agg.AVERAGE)),
                        Map.of(5, Set.of("never"))),
                new Pivot.Spec(List.of(new Pivot.Field(7)), List.of(), List.of(v(7, Pivot.Agg.COUNT)), Map.of()));
        try (PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT)) {
            for (Pivot.Spec spec : specs) {
                Pivot.Result fromFile = Pivot.compute(o.format(), o.doc().lineCount(), o.lines(), null, spec, o.fields(),
                        ZONE, () -> false, null);
                assertTrue(cache.build(spec, o.fields(), o.lines(), o.doc().lineCount(), () -> false, null));
                assertTrue(cache.has(spec));
                Pivot.Result fromCache = cache.compute(spec, o.fields(), ZONE, o.doc().lineCount());
                assertNotNull(fromCache, spec.toString());
                assertSame(fromFile, fromCache, spec.toString());
                assertEquals(fromFile.read(), fromCache.read());
            }
        }
        // and opened again, it has them all without reading the file
        try (PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT)) {
            for (Pivot.Spec spec : specs) {
                assertTrue(cache.has(spec), spec.toString());
            }
        }
        o.doc().close();
    }

    @Test
    void notForWhatItCantAnswer() throws Exception {
        Pivot.Value pooledMedian = Pivot.Value.of(List.of(3, 4), "p", Pivot.Agg.MEDIAN, Pivot.ShowAs.VALUE);
        Pivot.Spec spec = new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(), List.of(pooledMedian), Map.of());
        assertFalse(PivotCache.canCache(spec), "the median of many columns: the file is read");
        Path f = table(dir.resolve("t.csv"), 500, 2);
        Opened o = open(f);
        try (PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), PivotCache.DEFAULT_LIMIT)) {
            assertFalse(cache.build(spec, o.fields(), o.lines(), o.doc().lineCount(), () -> false, null));
            assertNull(cache.compute(spec, o.fields(), ZONE, o.doc().lineCount()));
            // cancelled: nothing half-built is kept
            Pivot.Spec sum = new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(), List.of(v(3, Pivot.Agg.SUM)),
                    Map.of());
            assertFalse(cache.build(sum, o.fields(), o.lines(), o.doc().lineCount(), () -> true, null));
            assertFalse(cache.has(sum));
        }
        o.doc().close();
    }

    @Test
    void aChangedFileGetsANewCacheAndOldOnesGo() throws Exception {
        Path f = table(dir.resolve("t.csv"), 500, 3);
        Opened o = open(f);
        Path cacheDir = dir.resolve("cache");
        String first;
        Pivot.Spec spec = new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(), List.of(v(3, Pivot.Agg.SUM)), Map.of());
        try (PivotCache cache = PivotCache.open(f, o.format(), cacheDir, PivotCache.DEFAULT_LIMIT)) {
            first = cache.key();
            assertTrue(cache.build(spec, o.fields(), o.lines(), o.doc().lineCount(), () -> false, null));
        }
        o.doc().close();
        // rewritten (another time too): a new cache, which has nothing yet
        table(f, 600, 4);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Opened o2 = open(f);
        try (PivotCache cache = PivotCache.open(f, o2.format(), cacheDir, PivotCache.DEFAULT_LIMIT)) {
            assertNotEquals(first, cache.key());
            assertFalse(cache.has(spec));
            assertTrue(cache.build(spec, o2.fields(), o2.lines(), o2.doc().lineCount(), () -> false, null));
            Pivot.Result r = cache.compute(spec, o2.fields(), ZONE, o2.doc().lineCount());
            assertEquals(600, r.rows());
        }
        assertTrue(Files.exists(cacheDir.resolve(first + ".duckdb")), "the old one stays while there is room");
        // no room: the old one goes, the one in use stays
        try (PivotCache cache = PivotCache.open(f, o2.format(), cacheDir, 1)) {
            assertFalse(Files.exists(cacheDir.resolve(first + ".duckdb")));
            assertTrue(cache.has(spec));
        }
        assertTrue(PivotCache.size(cacheDir) > 0);
        PivotCache.clear(cacheDir, Set.of());
        assertEquals(0, PivotCache.size(cacheDir));
        o2.doc().close();
    }
}
