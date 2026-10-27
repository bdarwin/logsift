package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/** Measurements from columns: numbers over time, and rows per minute for each of a few values. */
class ColumnMetricsTest {

    private static List<Numbers.Series> analyze(List<String> lines, String name) {
        Formats.Detected d = Formats.detect(lines.subList(0, Math.min(200, lines.size())), List.of(), name, null, 2026);
        return ColumnMetrics.analyze(d.columns(), lines.size(), l -> lines.get((int) l), d.times(), () -> false);
    }

    private static List<String> names(List<Numbers.Series> s) {
        return s.stream().map(Numbers.Series::name).toList();
    }

    private static List<String> shop() {
        List<String> out = new ArrayList<>();
        out.add("time,seq,request_id,host,status,duration_ms,bytes,message");
        for (int i = 0; i < 600; i++) {
            out.add(String.format("2026-10-21 10:%02d:%02d,%d,%d,web-%d,%d,%d,%d,\"GET /orders/%d\"", i / 60, i % 60,
                    i, 900_000 + (i * 7919) % 100_000, i % 3 + 1, i % 20 == 0 ? 500 : 200, 40 + (i * 37) % 300,
                    1000 + i * 13, i));
        }
        return out;
    }

    @Test
    void numbersAndFewValues() {
        List<Numbers.Series> s = analyze(shop(), "shop.csv");
        assertEquals(List.of("duration_ms", "bytes", "status = 200", "status = 500", "host = web-1", "host = web-2",
                "host = web-3"), names(s), "not seq (a counter) nor request_id (an id)");
        Numbers.Series d = s.get(0);
        assertEquals(Numbers.Source.COLUMN, d.source());
        assertEquals(Numbers.Unit.MILLIS, d.unit(), "ms from the name");
        assertEquals(600, d.size());
        assertEquals(40 + (599 * 37) % 300, d.values()[599], 1e-9);
        assertEquals(600, d.lines()[599], "lines of the file: the header is line 0");
        assertEquals(Numbers.Unit.BYTES, s.get(1).unit());
        Numbers.Series errors = s.get(3);
        assertEquals(Numbers.Source.ROWS, errors.source());
        assertEquals("rows per 10 s", errors.how(), "10 minutes in about 60 stretches");
        assertEquals(30, errors.count());
        assertEquals(30, (long) java.util.Arrays.stream(errors.values()).sum());
        assertEquals(60, errors.size(), "every stretch, with 0 where there were none");
    }

    @Test
    void unitsInValuesAndNoTimes() {
        List<String> lines = new ArrayList<>();
        lines.add("op;took;queue");
        for (int i = 0; i < 100; i++) {
            lines.add("call-" + i + ";" + (i % 2 == 0 ? (i * 10) + " ms" : (i / 10.0) + " s") + ";" + (i % 4));
        }
        List<Numbers.Series> s = analyze(lines, "calls.csv");
        assertEquals(List.of("took"), names(s), "no times: no rows per minute; queue has few values");
        assertEquals(1500.0, s.get(0).values()[15], 1e-9, "1.5 s in ms");
        assertEquals(20.0, s.get(0).values()[2], 1e-9);
    }

    @Test
    void regexLayoutColumns() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            lines.add(String.format("10.0.0.%d - - [21/Oct/2026:10:%02d:%02d +0000] \"GET /a/%d HTTP/1.1\" %d %d",
                    i % 5, i / 60, i % 60, i, i % 10 == 0 ? 404 : 200, 500 + i * 11));
        }
        List<Numbers.Series> s = analyze(lines, "access.log");
        assertTrue(names(s).contains("size"), names(s).toString());
        assertTrue(names(s).contains("status = 200") && names(s).contains("status = 404"), names(s).toString());
        assertEquals(Numbers.Unit.BYTES, s.get(names(s).indexOf("size")).unit());
    }

    @Test
    void unitsFromNames() {
        assertEquals("ms", ColumnMetrics.unitOf("duration_ms"));
        assertEquals("ms", ColumnMetrics.unitOf("latency"));
        assertEquals("ms", ColumnMetrics.unitOf("Response Time (ms)"));
        assertEquals("s", ColumnMetrics.unitOf("elapsed_s"));
        assertEquals("b", ColumnMetrics.unitOf("bytes"));
        assertEquals("b", ColumnMetrics.unitOf("response_bytes"));
        assertEquals("kb", ColumnMetrics.unitOf("size_kb"));
        assertEquals("%", ColumnMetrics.unitOf("cpu_pct"));
        assertEquals("", ColumnMetrics.unitOf("items"));
        assertEquals("", ColumnMetrics.unitOf("status"));
        assertEquals("minute", ColumnMetrics.per(60_000));
        assertEquals("15 s", ColumnMetrics.per(15_000));
        assertEquals("5 min", ColumnMetrics.per(300_000));
    }

    @Test
    void numbersInCsvMessages() {
        // a CSV row's other fields run together into one variable part: "web-2,u1"
        String template = "INFO,<*>,\"Request <*> took <*> ms\"";
        Matcher m = Spans.capturing(template).matcher("2026-10-21 10:00:01,INFO,web-2,u1,\"Request 1 took 7 ms\"");
        assertTrue(m.find());
        assertEquals("web-2,u1", m.group(1));
        assertEquals("7", m.group(3));
        // elsewhere a variable part still stops at a comma
        Matcher m2 = Spans.capturing("took <*>, retry <*>").matcher("took 12ms, retry 3");
        assertTrue(m2.find());
        assertEquals("12ms", m2.group(1));
        assertEquals("3", m2.group(2));
        Matcher m3 = Spans.capturing("queue size=<*>").matcher("queue size=42, more");
        assertTrue(m3.find());
        assertEquals("42", m3.group(1));
    }
}
