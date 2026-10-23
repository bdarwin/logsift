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
