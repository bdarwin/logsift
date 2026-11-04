package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Column filters from the cache of a table's columns match the very lines reading the file does. */
class FilterCacheTest {
    @TempDir
    Path dir;

    /**
     * A table made to be awkward: quoted values over several lines, CRLF, padded and blank cells,
     * numbers with units, short rows, other alphabets, and no line end at the end.
     */
    static Path awkward(Path f, int n, long seed) throws Exception {
        Random rnd = new Random(seed);
        String[] hosts = {"web-1", "web-2", "WEB-1", " web-1 ", "web-10", "", "wéb-ü"};
        String[] took = {"7 ms", "1.5 s", "250", "2 KB", "", "n/a", "-3", "12.5ms", "1,250"};
        String[] notes = {"plain", "\"a, quoted\"", "\"over\r\ntwo lines\"", "\"three\nlines\nhere\"", "",
                "\"say \"\"hi\"\"\"", "web-1 too"};
        StringBuilder b = new StringBuilder("host,took,note,code\r\n");
        for (int i = 0; i < n; i++) {
            if (i % 211 == 7) {
                b.append("short\r\n"); // a row of one field: no columns to filter
                continue;
            }
            b.append(hosts[rnd.nextInt(hosts.length)]).append(',').append(took[rnd.nextInt(took.length)])
                    .append(',').append(notes[rnd.nextInt(notes.length)]).append(',').append(rnd.nextInt(40));
            b.append(i % 3 == 0 ? "\n" : "\r\n");
            if (i % 503 == 11) {
                b.append("\r\n"); // a blank line
            }
        }
        b.append("web-2,9 ms,last,1"); // no line end
        Files.writeString(f, b.toString());
        return f;
    }

    /** The lines a filter matches through the engine, with {@code hits} as where column filters may come from. */
    static List<Long> lines(LogDocument doc, ColumnFilter cf, FilterEngine.ColumnHits hits) throws Exception {
        try (FilterEngine e = new FilterEngine(doc)) {
            e.setColumnHits(hits);
            e.configure(List.of(new FilterEngine.Spec('A', cf.query(), cf)), new Formula.Ref('A'));
            assertTrue(e.awaitIdle(60_000));
            FilterResult r = e.result();
            List<Long> out = new ArrayList<>();
            for (long i = 0; i < r.count(); i++) {
                out.add(r.lineAt(i));
            }
            return out;
        }
    }

    @Test
    void theSameLinesAsReadingTheFile() throws Exception {
        Path f = awkward(dir.resolve("t.csv"), 6000, 3);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        try (LogDocument doc = o.doc();
             PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), 1L << 30)) {
            LogFormat fmt = o.format();
            List<String> cols = fmt.columns();
            assertTrue(cache.buildValues(List.of(0, 1, 2, 3), CsvRows.reader(doc, fmt), doc.lineCount(), () -> false,
                    null));
            AtomicInteger served = new AtomicInteger();
            FilterEngine.ColumnHits fromCache = (cf, upTo, stop) -> {
                long[] h = cache.matchingLines(cols.indexOf(cf.column()), cf.valueTest(), upTo, stop);
                if (h != null) {
                    served.incrementAndGet();
                }
                return h;
            };
            record F(int col, ColumnFilter.Op op, String text, boolean matchCase, boolean regex, boolean word) {}
            List<F> filters = List.of(
                    new F(0, ColumnFilter.Op.EQUALS, "web-1", false, false, false),
                    new F(0, ColumnFilter.Op.EQUALS, "web-1", true, false, false),
                    new F(0, ColumnFilter.Op.CONTAINS, "web-1", false, false, false),
                    new F(0, ColumnFilter.Op.CONTAINS, "web-1", true, false, true),
                    new F(0, ColumnFilter.Op.CONTAINS, "^ web", false, true, false),
                    new F(0, ColumnFilter.Op.CONTAINS, "web-\\d+$", false, true, false),
                    new F(0, ColumnFilter.Op.CONTAINS, "ü", false, false, false),
                    new F(0, ColumnFilter.Op.EQUALS, " web-1 ", true, false, false),
                    new F(1, ColumnFilter.Op.MORE, "100", false, false, false),
                    new F(1, ColumnFilter.Op.LESS, "10 ms", false, false, false),
                    new F(1, ColumnFilter.Op.BETWEEN, "5..<2000", false, false, false),
                    new F(1, ColumnFilter.Op.CONTAINS, "ms", false, false, false),
                    new F(2, ColumnFilter.Op.CONTAINS, "lines", false, false, false),
                    new F(2, ColumnFilter.Op.CONTAINS, "two", false, false, false),
                    new F(2, ColumnFilter.Op.EQUALS, "say \"hi\"", true, false, false),
                    new F(2, ColumnFilter.Op.CONTAINS, "web-1", false, false, false),
                    new F(3, ColumnFilter.Op.BETWEEN, "10..20", false, false, false),
                    new F(3, ColumnFilter.Op.EQUALS, "1", false, false, false));
            for (F x : filters) {
                SearchQuery q = new SearchQuery(x.text(), x.matchCase(), x.regex(), x.word());
                ColumnFilter cf = new ColumnFilter(fmt, cols.get(x.col()), x.op(), q);
                List<Long> read = lines(doc, cf, null);
                int before = served.get();
                List<Long> cached = lines(doc, cf, fromCache);
                assertEquals(before + 1, served.get(), "from the cache: " + cf.describe());
                assertEquals(read, cached, cf.describe() + " (" + read.size() + " lines reading the file)");
            }
            assertTrue(lines(doc, new ColumnFilter(fmt, cols.get(0), ColumnFilter.Op.EQUALS,
                    new SearchQuery("web-1", false, false, false)), null).size() > 100, "a test that finds lines");
        }
    }

    @Test
    void valuesCountedAsReadingTheFile() throws Exception {
        Path f = awkward(dir.resolve("t.csv"), 6000, 9);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        try (LogDocument doc = o.doc();
             PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), 1L << 30)) {
            LogFormat fmt = o.format();
            assertTrue(cache.buildValues(List.of(0, 1, 2, 3), CsvRows.reader(doc, fmt), doc.lineCount(), () -> false,
                    null));
            long upTo = doc.snapshot().completeLineCount();
            for (int c = 0; c < 4; c++) {
                ColumnStats.Result read = ColumnStats.count(doc, fmt, fmt.columns().get(c), () -> false);
                ColumnStats.Result cached = cache.valueCounts(c, upTo, () -> false);
                assertEquals(read.values(), cached.values(), fmt.columns().get(c));
                assertEquals(read.lines(), cached.lines(), fmt.columns().get(c));
                assertEquals(read.column(), cached.column());
                assertFalse(cached.truncated());
            }
        }
    }

    @Test
    void pastTheMostValuesTheFirstInTheFileAreKept() throws Exception {
        StringBuilder b = new StringBuilder("id,n\n");
        int n = ColumnStats.MAX_VALUES + 20_000;
        for (int i = 0; i < n; i++) {
            b.append("id-").append(i).append(',').append(i % 3).append('\n');
        }
        Path f = Files.writeString(dir.resolve("ids.csv"), b);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        try (LogDocument doc = o.doc();
             PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), 1L << 30)) {
            assertTrue(cache.buildValues(List.of(0), CsvRows.reader(doc, o.format()), doc.lineCount(), () -> false, null));
            ColumnStats.Result read = ColumnStats.count(doc, o.format(), o.format().columns().get(0), () -> false);
            ColumnStats.Result cached = cache.valueCounts(0, doc.snapshot().completeLineCount(), () -> false);
            assertTrue(read.truncated() && cached.truncated());
            assertEquals(read.values().size(), cached.values().size());
            assertEquals(n, cached.lines(), "every row counted");
            assertEquals(read.lines(), cached.lines());
            assertTrue(cached.values().stream().allMatch(v -> v.firstLine() <= ColumnStats.MAX_VALUES),
                    "the first ones in the file");
        }
    }

    @Test
    void storesOnlyWhatItIsAskedForAndCanStop() throws Exception {
        Path f = awkward(dir.resolve("t.csv"), 3000, 5);
        PivotCacheTest.Opened o = PivotCacheTest.open(f);
        try (LogDocument doc = o.doc();
             PivotCache cache = PivotCache.open(f, o.format(), dir.resolve("cache"), 1L << 30)) {
            assertNull(cache.matchingLines(0, v -> true, doc.lineCount(), () -> false), "not stored yet");
            // stopped before the file is read: nothing to answer from
            assertFalse(cache.buildValues(List.of(0), CsvRows.reader(doc, o.format()), doc.lineCount(),
                    () -> true, null), "stopped");
            assertFalse(cache.hasValues(0), "nothing half-made is answered from");
            assertNull(cache.matchingLines(0, v -> true, doc.lineCount(), () -> false));
            assertNull(cache.valueCounts(0, doc.lineCount(), () -> false));
            assertTrue(cache.buildValues(List.of(2), CsvRows.reader(doc, o.format()), doc.lineCount(), () -> false,
                    null));
            assertTrue(cache.hasValues(2));
            assertFalse(cache.hasValues(0));
            long[] all = cache.matchingLines(2, v -> true, doc.lineCount(), () -> false);
            long[] some = cache.matchingLines(2, v -> true, 100, () -> false);
            assertTrue(some.length > 0 && some.length < all.length && some[some.length - 1] < 100, "only before 100");
            assertNull(cache.matchingLines(2, v -> true, doc.lineCount(), () -> true), "stopped: no answer");
        }
        // kept for the file: opened again, it is there
        try (LogDocument doc = PivotCacheTest.open(f).doc();
             PivotCache again = PivotCache.open(f, o.format(), dir.resolve("cache"), 1L << 30)) {
            assertTrue(again.hasValues(2));
            assertEquals(doc.lineCount() > 0, again.matchingLines(2, v -> true, doc.lineCount(), () -> false).length > 0);
        }
    }

    @Test
    void linesWrittenAfterwardAreReadFromTheFile() throws Exception {
        // the cache answers for the file as it was; what is added after is read
        Path f = dir.resolve("grows.csv");
        Files.writeString(f, "host,n\nweb-1,1\nweb-2,2\nweb-1,3\n");
        try (LogDocument doc = LogDocument.open(f)) {
            assertTrue(doc.awaitIdle(10_000));
            LogFormat fmt = LogFormat.detectCsv(List.of(doc.line(0), doc.line(1)), true);
            ColumnFilter cf = new ColumnFilter(fmt, fmt.columns().get(0), ColumnFilter.Op.EQUALS,
                    new SearchQuery("web-1", false, false, false));
            AtomicInteger asked = new AtomicInteger();
            try (FilterEngine e = new FilterEngine(doc)) {
                e.setColumnHits((c, upTo, stop) -> {
                    asked.incrementAndGet();
                    return upTo == 4 ? new long[] {1, 3} : null;
                });
                e.configure(List.of(new FilterEngine.Spec('A', cf.query(), cf)), new Formula.Ref('A'));
                assertTrue(e.awaitIdle(10_000));
                assertEquals(List.of(1L, 3L), List.of(e.result().lineAt(0), e.result().lineAt(1)));
                Files.writeString(f, "web-1,4\nweb-3,5\n", StandardOpenOption.APPEND);
                doc.requestRefresh();
                long end = System.currentTimeMillis() + 10_000;
                while (e.result().count() < 3 && System.currentTimeMillis() < end) {
                    Thread.sleep(20);
                }
                assertTrue(e.awaitIdle(10_000));
                assertEquals(3, e.result().count(), "the new web-1 found by reading");
                assertEquals(4L, e.result().lineAt(2));
                assertEquals(1, asked.get(), "asked once, for the lines there were");
            }
        }
    }

    @Test
    void anAnswerOutOfOrderIsNotUsed() throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "host,n\nweb-1,1\nweb-2,2\nweb-1,3\n");
        try (LogDocument doc = LogDocument.open(f)) {
            assertTrue(doc.awaitIdle(10_000));
            LogFormat fmt = LogFormat.detectCsv(List.of(doc.line(0), doc.line(1)), true);
            ColumnFilter cf = new ColumnFilter(fmt, fmt.columns().get(0), ColumnFilter.Op.EQUALS,
                    new SearchQuery("web-1", false, false, false));
            List<Throwable> reported = new java.util.concurrent.CopyOnWriteArrayList<>();
            Problems.setSink(reported::add);
            try {
                assertEquals(List.of(1L, 3L), lines(doc, cf, (c, upTo, stop) -> new long[] {3, 1}),
                        "the file is read instead");
                assertEquals(1, reported.size(), "and it's reported as a bug: " + reported);
                assertTrue(reported.get(0).getMessage().startsWith("hits out of order"), reported.toString());
                assertEquals(List.of(1L, 3L), lines(doc, cf, (c, upTo, stop) -> {
                    throw new IllegalStateException("broken cache");
                }));
                assertEquals(2, reported.size(), reported.toString());
            } finally {
                Problems.setSink(null);
            }
        }
    }

    @Test
    void aRowIsWhatSplitReadsAsOne() throws Exception {
        // isRow counts fields without copying them: the same rows split gives, header and broken lines not
        for (Path f : List.of(awkward(dir.resolve("a.csv"), 800, 9), PivotCacheTest.table(dir.resolve("b.csv"), 800, 9))) {
            PivotCacheTest.Opened o = PivotCacheTest.open(f);
            try (LogDocument doc = o.doc()) {
                String[] lines = new String[(int) doc.lineCount()];
                doc.readLines(0, lines.length, lines);
                for (String l : lines) {
                    assertEquals(o.format().split(l, null) != null, o.format().isRow(l), l);
                }
                for (String l : List.of("", "x", "a,b", ",,,,,,,", "\"unclosed, quote")) {
                    assertEquals(o.format().split(l, null) != null, o.format().isRow(l), l);
                }
            }
        }
    }
}
