package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

/** Metrics of only the lines the filters keep, picked from the whole log's measurements. */
class FilteredMetricsTest {

    private static Spans.Kind jobs() {
        List<Spans.Span> spans = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // job i starts on line 10*i, ends 100 ms * (i + 1) later on line 10*i + 5
            spans.add(new Spans.Span(10L * i, 10L * i + 5, 1000L * i, 1000L * i + 100L * (i + 1), "job-" + i));
        }
        spans.add(new Spans.Span(200, -1, 20_000, TimeFormat.NONE, "job-hung"));
        return Spans.Kind.of("job", null, null, "by its job", spans);
    }

    @Test
    void spansAreKeptByTheirStartLine() {
        Spans.Kind k = jobs();
        assertEquals(10, k.count());
        assertEquals(1, k.unfinishedCount());
        // keep the lines of the even jobs' starts only: their ends (line 10*i + 5) don't pass, they still count
        Spans.Kind even = k.keep(line -> line % 20 == 0);
        assertEquals(5, even.count());
        assertEquals(1, even.unfinishedCount(), "line 200 starts the one that hung");
        assertEquals(900, even.max(), "job-8: 900 ms");
        assertEquals(List.of("job-8", "job-6", "job-4", "job-2", "job-0"),
                even.slowest().stream().map(Spans.Span::key).toList());
        // the end lines alone keep nothing: a span goes with its start
        assertNull(k.keep(line -> line % 10 == 5));
    }

    private static Numbers.Series series() {
        long[] lines = {1, 2, 3, 4, 5, 6};
        long[] times = {10, 20, 30, 40, 50, 60};
        double[] v = {1, 2, 3, 4, 5, 6};
        return new Numbers.Series("x", null, 0, Numbers.Unit.NONE, lines, times, v, 12, Numbers.Source.MESSAGE, "");
    }

    @Test
    void numbersAreKeptByTheirLine() {
        Numbers.Series s = series().keep(line -> line % 2 == 0);
        assertEquals(3, s.size());
        assertEquals(List.of(2L, 4L, 6L), java.util.Arrays.stream(s.lines()).boxed().toList());
        assertEquals(6, s.count(), "every other line of a series sampled 1 in 2: about half its count");
        assertEquals(6.0, s.max(), 1e-9);
        assertNull(series().keep(line -> false));
        assertEquals(12, series().keep(line -> true).count(), "all kept: the same count");
    }

    @Test
    void metricsKeepWhatTheLinesMeasure() {
        Metrics all = new Metrics(new Spans.Result(List.of(jobs())), List.of(series()));
        Metrics some = all.keep(line -> line == 0 || line == 2);
        assertTrue(some.filtered());
        assertEquals(1, some.spans().kinds().get(0).count());
        assertEquals(1, some.numbers().get(0).size());
        Metrics none = all.keep(line -> line > 1000);
        assertTrue(none.isEmpty(), "measurements with nothing left are left out");
        assertTrue(!all.filtered());
    }

    @Test
    void columnsWithTheLinesKept() {
        List<String> lines = new ArrayList<>();
        lines.add("time,host,duration_ms");
        for (int i = 0; i < 600; i++) {
            lines.add(String.format("2026-10-21 10:%02d:%02d,web-%d,%d", i / 60, i % 60, i % 3 + 1, 10 + (i * 37) % 300));
        }
        Formats.Detected d = Formats.detect(lines.subList(0, 200), List.of(), "x.csv", null, 2026);
        ColumnMetrics.Data data = ColumnMetrics.collect(d.columns(), lines.size(), l -> lines.get((int) l), d.times(),
                () -> false);
        List<Numbers.Series> whole = data.series(null);
        assertEquals(List.of("duration_ms", "host = web-1", "host = web-2", "host = web-3"),
                whole.stream().map(Numbers.Series::name).toList());
        assertEquals(whole.stream().map(Numbers.Series::name).toList(),
                ColumnMetrics.analyze(d.columns(), lines.size(), l -> lines.get((int) l), d.times(), () -> false)
                        .stream().map(Numbers.Series::name).toList(), "the same as before");
        // only web-1's rows (line i + 1 has web-(i % 3 + 1))
        LongPredicate web1 = line -> line >= 1 && (line - 1) % 3 == 0;
        List<Numbers.Series> kept = data.series(web1);
        assertEquals(List.of("duration_ms", "host = web-1"), kept.stream().map(Numbers.Series::name).toList(),
                "the other hosts have no rows left");
        assertEquals(200, kept.get(0).size());
        assertEquals(200, (long) java.util.Arrays.stream(kept.get(1).values()).sum(), "web-1's rows per stretch");
    }

    @Test
    void theCacheIsWorkedOutOncePerVersion() {
        WholeLogCache<String> cache = new WholeLogCache<>();
        int[] runs = {0};
        assertEquals("v1", cache.get("gen1", () -> "v" + ++runs[0]));
        // only the filters change: the same version, nothing read again
        assertEquals("v1", cache.get("gen1", () -> "v" + ++runs[0]));
        assertEquals("v1", cache.peek("gen1"));
        assertEquals(1, cache.computations());
        // the file grew: worked out again
        assertNull(cache.peek("gen2"));
        assertEquals("v2", cache.get("gen2", () -> "v" + ++runs[0]));
        assertEquals(2, cache.computations());
        assertNull(cache.get("gen3", () -> null), "a cancelled run isn't kept");
        assertEquals("v2", cache.peek("gen2"));
        cache.clear();
        assertNull(cache.peek("gen2"));
    }
}
