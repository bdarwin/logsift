package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void findsMeasuredParts() {
        assertEquals(List.of(2), cols("INFO [<*>] c.a.Shop - order <*> placed in <*> ms"), "not the thread or order");
        assertEquals(List.of(0), cols("queue size=<*>"));
        assertEquals(List.of(0), cols("request took <*>"));
        assertEquals(List.of(0), cols("disk at <*>% full"));
        assertEquals(List.of(1), cols("upload <*> of <*> MB"));
        assertEquals(List.of(), cols("user <*> logged in from <*>"));
    }

    @Test
    void readsKeyValuePairs() {
        // the next key is not the unit of this number: latency isn't a size because "bytes=" follows
        Numbers.Options known = Numbers.Options.DEFAULT;
        assertEquals(List.of(1, 2), cols("GET /orders status=<*> latency=<*> bytes=<*> user=<*> order_id=<*>", known));
        assertEquals(1, Numbers.measuredColumns("GET /orders status=<*> latency=<*> bytes=<*>", known).get(1)[1],
                "bytes is a size");
        assertEquals(2, Numbers.measuredColumns("GET /orders status=<*> latency=<*> bytes=<*>", known).get(0)[1],
                "latency without a unit: a plain number");
        assertEquals(List.of(0, 1, 2, 3), cols("cache stats hits=<*> misses=<*> size=<*> evictions=<*>", known));
        assertEquals(List.of(1, 2, 3), cols("payment done amount=<*> items=<*> retries=<*> duration_ms=<*>", known));
        assertEquals(List.of(0, 1), cols("queue depth: <*>, active threads: <*>", known));
        assertEquals(List.of(0, 1, 2), cols("GET latency_ms=<*> body_bytes=<*> timeout_s=<*> status=<*>", known));

        Numbers.Options all = new Numbers.Options(Numbers.KeyMode.ALL_BUT_IDS, java.util.Set.of(), java.util.Set.of(), 10);
        assertEquals(List.of(0, 1, 2, 3), cols("payment done amount=<*> items=<*> retries=<*> duration_ms=<*>", all));
        assertEquals(List.of(1, 2), cols("GET /orders status=<*> latency=<*> bytes=<*> user=<*> order_id=<*>", all),
                "ids, users and status codes never count");
        Numbers.Options only = new Numbers.Options(Numbers.KeyMode.ONLY, java.util.Set.of("Amount"), java.util.Set.of(), 10);
        assertEquals(List.of(0), cols("payment done amount=<*> items=<*> retries=<*> duration_ms=<*>", only));
        Numbers.Options never = new Numbers.Options(Numbers.KeyMode.ALL_BUT_IDS, java.util.Set.of(),
                java.util.Set.of("retries"), 10);
        assertEquals(List.of(0, 1, 3), cols("payment done amount=<*> items=<*> retries=<*> duration_ms=<*>", never));
        // the old cases read the same
        assertEquals(List.of(2), cols("INFO [<*>] c.a.Shop - order <*> placed in <*> ms", all));
        assertEquals(List.of(), cols("user <*> logged in from <*>", all));
    }

    @Test
    void explainsALine() {
        List<Numbers.Explained> e = Numbers.explain("GET /orders status=200 latency=35ms bytes=512 user=u12",
                Numbers.Options.DEFAULT);
        assertEquals(List.of("status", "latency", "bytes"), e.stream().map(Numbers.Explained::key).toList(),
                "u12 isn't a number");
        assertEquals(List.of(false, true, true), e.stream().map(Numbers.Explained::measured).toList());
        assertEquals("millis", e.get(1).unit());
        assertEquals("bytes", e.get(2).unit());
    }

    @Test
    void optionsAreSavedAndRead(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        assertEquals(Numbers.Options.DEFAULT, Numbers.Options.load(home));
        Numbers.Options o = new Numbers.Options(Numbers.KeyMode.ONLY, java.util.Set.of("latency", "items"),
                java.util.Set.of("shard"), 25);
        o.save(home);
        assertEquals(o, Numbers.Options.load(home));
        assertEquals(java.util.Set.of("a", "b", "c"), Numbers.Options.split("a, b;c  "));
        assertEquals(100, new Numbers.Options(Numbers.KeyMode.KNOWN, java.util.Set.of(), java.util.Set.of(), 500)
                .maxSeries(), "at most 100");
    }

    private static List<Integer> cols(String t, Numbers.Options o) {
        return Numbers.measuredColumns(t, o).stream().map(c -> c[0]).toList();
    }

    private static List<Integer> cols(String t) {
        return Numbers.measuredColumns(t).stream().map(c -> c[0]).toList();
    }

    @Test
    void readsValuesInOneUnit() {
        Numbers.Unit[] u = {Numbers.Unit.NONE};
        assertEquals(1500.0, Numbers.parse("1.5s", "", u));
        assertEquals(Numbers.Unit.MILLIS, u[0]);
        assertEquals(345.0, Numbers.parse("345", "ms", u));
        assertEquals(30000.0, Numbers.parse("30000ms.", "", u));
        assertEquals(2.5 * 1024 * 1024, Numbers.parse("2,5", "MB", u));
        assertEquals(Numbers.Unit.BYTES, u[0]);
        assertTrue(Double.isNaN(Numbers.parse("r-12", "", u)));
        assertEquals("8.10 s", Numbers.Unit.MILLIS.format(8100));
        assertEquals("345 ms", Numbers.Unit.MILLIS.format(345));
        assertEquals("12.0 MB", Numbers.Unit.BYTES.format(12 * 1024 * 1024));
        assertEquals("42", Numbers.Unit.NONE.format(42));
    }

    @Test
    void measuresOverTimeAndFindsSpikes() {
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            int ms = i >= 300 && i < 330 ? 4000 + i : 100 + i % 50; // slow for half a minute
            log.add(String.format("2026-10-21 10:%02d:%02d.000 INFO  [w-%d] c.a.Shop - order %d placed in %d ms",
                    i / 60, i % 60, i % 4, 1000 + i, ms));
        }
        MessageStats.Template t = new MessageStats.Template("INFO [<*>] c.a.Shop - order <*> placed in <*> ms", "INFO",
                600, 0, 599);
        TimeFormat tf = TimeFormat.detect(log, 2026);
        RequestTrace.Reader r = RequestTrace.reader(l -> log.get((int) l), tf, null);
        List<Numbers.Series> s = Numbers.analyze(List.of(t), (x, c) -> java.util.stream.LongStream.range(0, 600).toArray(),
                r, () -> false);
        assertEquals(1, s.size());
        Numbers.Series ser = s.get(0);
        assertEquals("order … placed in # ms", ser.name());
        assertEquals(Numbers.Unit.MILLIS, ser.unit());
        assertEquals(600, ser.size());
        assertEquals(4329.0, ser.max());
        assertEquals(329, ser.largest(1)[0]);
        List<Numbers.Spike> spikes = Numbers.spikes(ser);
        assertEquals(1, spikes.size());
        Numbers.Spike sp = spikes.get(0);
        assertTrue(sp.median() > 4000 && sp.usual() < 200, sp.toString());
        assertEquals(tf.parse(log.get(300)), sp.from());
        assertEquals(tf.parse(log.get(329)), sp.to());
        assertEquals(329, sp.line());
        assertTrue(Pattern.compile(t.regex()).matcher(log.get(0)).find());
    }
}
