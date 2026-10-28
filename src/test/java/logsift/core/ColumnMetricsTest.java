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
                    1000 + (i * 7919) % 5000, i));
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
                    i % 5, i / 60, i % 60, i, i % 10 == 0 ? 404 : 200, 500 + (i * 37) % 900));
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
    void variablePartsStopAtSeparators() {
        // the capture regex is the same for every log: a variable part stops at , ; | =
        Matcher m = Spans.capturing("took <*>, retry <*>").matcher("took 12ms, retry 3");
        assertTrue(m.find());
        assertEquals("12ms", m.group(1));
        assertEquals("3", m.group(2));
        Matcher m2 = Spans.capturing("queue size=<*>").matcher("queue size=42, more");
        assertTrue(m2.find());
        assertEquals("42", m2.group(1));
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    private static String orders() {
        StringBuilder csv = new StringBuilder("Timestamp,Level,Host,User,Message\n");
        for (int i = 0; i < 400; i++) {
            csv.append(String.format("2026-10-21 10:%02d:%02d,%s,web-%d,u%d,\"%s\"\n", i / 60, i % 60,
                    i % 25 == 0 ? "ERROR" : i % 7 == 0 ? "WARN" : "INFO", i % 3 + 1, i % 40,
                    i % 25 == 0 ? "Payment failed, card declined" : "Request " + i + " took " + (i * 7 % 300) + " ms"));
        }
        return csv.toString();
    }

    @Test
    void csvMessagesAreTheMessageColumn() throws Exception {
        java.nio.file.Path f = dir.resolve("orders.csv");
        java.nio.file.Files.writeString(f, orders());
        try (LogDocument doc = LogDocument.open(f)) {
            assertTrue(doc.awaitIdle(10_000));
            List<String> sample = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                sample.add(doc.line(i));
            }
            Formats.Detected d = Formats.detect(sample, List.of(), "orders.csv", null, 2026);
            MessageStats.Result r = MessageStats.analyze(doc, d.entries(), d.columns(), 0, Long.MAX_VALUE, null,
                    () -> false, null);
            assertEquals(List.of("Request <*> took <*> ms", "Payment failed, card declined"),
                    r.templates().stream().map(MessageStats.Template::text).toList(), "one per message, not per level");
            assertEquals(List.of("WARN", "ERROR"), r.templates().stream().map(MessageStats.Template::level).toList(),
                    "the most severe level of its rows");
            assertEquals(384, r.templates().get(0).count());
            assertEquals(400, r.lines(), "rows, not the header");
            // its numbers, read from the rows
            Metrics m = Metrics.analyze(r.templates(), (t, c) -> {
                try (SearchSession s = new SearchSession(doc, new SearchQuery(t.regex(), true, true, false)).start()) {
                    s.awaitIdle(10_000);
                    long[] out = new long[s.hits().size()];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = s.hits().get(i);
                    }
                    return out;
                }
            }, RequestTrace.reader(doc::line, d.times(), d.columns()), () -> false);
            assertEquals(List.of("Request … took # ms"), m.numbers().stream().map(Numbers.Series::name).toList());
            assertEquals(384, m.numbers().get(0).size());
        }
    }

    @Test
    void otherLogsKeepTheirMessages() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            log.append(String.format("2026-10-21 10:%02d:%02d.000 %s [worker-%d] c.a.Shop - order %d placed in %d ms%n",
                    i / 60, i % 60, i % 9 == 0 ? "WARN " : "INFO ", i % 4, i, i * 3));
        }
        java.nio.file.Path f = dir.resolve("shop.log");
        java.nio.file.Files.writeString(f, log);
        try (LogDocument doc = LogDocument.open(f)) {
            assertTrue(doc.awaitIdle(10_000));
            List<String> sample = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                sample.add(doc.line(i));
            }
            Formats.Detected d = Formats.detect(sample, List.of(), "shop.log", null, 2026);
            List<MessageStats.Template> before = MessageStats.analyze(doc, d.entries(), 0, Long.MAX_VALUE, null,
                    () -> false, null).templates();
            List<MessageStats.Template> now = MessageStats.analyze(doc, d.entries(), d.columns(), 0, Long.MAX_VALUE,
                    null, () -> false, null).templates();
            assertEquals(before, now, "a layout's lines are counted as before");
            assertEquals(2, now.size(), "still one per level, as for every log");
        }
    }

    /** A column series, one value a second. */
    private static Numbers.Series column(String name, double[] v) {
        long[] lines = new long[v.length];
        long[] times = new long[v.length];
        for (int i = 0; i < v.length; i++) {
            lines[i] = i + 1;
            times[i] = 1_792_000_000_000L + i * 1000L;
        }
        return new Numbers.Series(name, null, 0, Numbers.Unit.NONE, lines, times, v, v.length,
                Numbers.Source.COLUMN, "column");
    }

    @Test
    void columnsThatOnlyGoUpAreNoMeasurementsAndNoSpikes() {
        List<String> lines = new ArrayList<>();
        lines.add("time,row,offset_bytes,epoch_ms,latency_ms");
        long offset = 0;
        for (int i = 0; i < 600; i++) {
            offset += (long) Math.pow(1.02, i); // grows faster and faster
            lines.add(String.format("2026-10-21 10:%02d:%02d,%d,%d,%d,%d", i / 60, i % 60, i, offset,
                    1_792_000_000_000L + i * 1000L, 20 + i % 7));
        }
        assertEquals(List.of("latency_ms"), names(analyze(lines, "rows.csv")),
                "not a row number, an offset or a clock, even with a unit in the name");
        // and Insights finds no spike in a column that only goes up, however it jumps
        double[] rising = new double[600];
        for (int i = 0; i < rising.length; i++) {
            rising[i] = i < 450 ? i : 100_000 + i;
        }
        Metrics m = new Metrics(Spans.Result.EMPTY, List.of(column("offset", rising)));
        assertTrue(Insights.measurements(m, true).isEmpty());
        // the same jump in a real measurement is one
        double[] jump = new double[600];
        for (int i = 0; i < jump.length; i++) {
            jump[i] = (i >= 300 && i < 360 ? 500 : 20) + i % 5;
        }
        List<Insights.Finding> f = Insights.measurements(new Metrics(Spans.Result.EMPTY, List.of(column("latency", jump))),
                true);
        assertEquals(1, f.size());
        assertEquals(Insights.Kind.SPIKE, f.get(0).kind());
    }
}
