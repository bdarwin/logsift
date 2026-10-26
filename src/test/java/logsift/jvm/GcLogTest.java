package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * GC logs written by real JVMs (Java 8, 11, 17 and 25; every collector), read: every pause is
 * found, with its heap and time, whatever the format.
 */
class GcLogTest {

    static List<String> sample(String name) throws IOException {
        try (InputStream in = new GZIPInputStream(GcLogTest.class.getResourceAsStream("/gc/" + name + ".log.gz"))) {
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
    }

    // what counts as a pause line, counted here the simple way
    private static final Pattern UNIFIED_PAUSE = Pattern.compile("\\[gc\\s*\\] GC\\(\\d+\\) Pause .* [\\d.]+ms$");
    private static final Pattern Z_PAUSE = Pattern.compile("\\[gc,phases\\s*\\] GC\\(\\d+\\) (?:[YO]: )?Pause .* [\\d.]+ms$");
    private static final Pattern OLD_PAUSE = Pattern.compile("^(?:\\S+: )?\\d+\\.\\d+: \\[(GC (?!concurrent)|Full GC)");

    @ParameterizedTest
    @CsvSource({
        "j8-parallel, Parallel, 1.8.0_392-b08, true",
        "j8-g1, G1, 1.8.0_392-b08, true",
        "j8-cms, CMS, 1.8.0_392-b08, true",
        "j8-serial, Serial, 1.8.0_392-b08, false",
        "j11-g1, G1, '', false",
        "j17-g1-time, G1, 17.0.2+8-LTS-86, true",
        "j17-zgc, ZGC, 17.0.2+8-LTS-86, false",
        "j17-serial-gconly, Serial, '', false",
        "j25-g1, G1, 25.0.2, true",
        "j25-zgc, ZGC, 25.0.2, false",
        "j25-shen, Shenandoah, 25.0.2, false",
        "j25-parallel, Parallel, 25.0.2, false"})
    void everyPauseIsRead(String name, String collector, String version, boolean dates) throws IOException {
        List<String> lines = sample(name);
        GcLog g = GcLog.parse(lines);
        assertEquals(collector, g.collector(), name);
        assertEquals(version == null ? "" : version, g.jvmVersion(), name);
        assertEquals(!name.startsWith("j8"), g.isUnified(), name);
        assertEquals(dates, g.wallOf(1) != Long.MIN_VALUE, name + ": dates");
        if (!name.equals("j17-serial-gconly") && !name.equals("j11-g1")) {
            assertEquals(256L << 20, g.maxHeap(), name);
        }
        Pattern p = name.startsWith("j8") ? OLD_PAUSE : collector.equals("ZGC") ? Z_PAUSE : UNIFIED_PAUSE;
        long expected = lines.stream().filter(l -> p.matcher(l).find()).count();
        long pauses = g.events().stream().filter(e -> e.stops() && e.kind() != GcLog.Kind.STALL).count();
        assertTrue(expected > 50, name + ": a real sample");
        assertEquals(expected, pauses, name + ": pauses");
        for (GcLog.Event e : g.events()) {
            assertTrue(e.ms() >= 0 && e.time() >= -0.001, name + ": " + e);
            assertTrue(e.line() >= 0 && e.line() < lines.size());
            boolean cutShort = e.line() > lines.size() - 40; // the samples are the logs' first lines
            if (!cutShort && (e.kind() == GcLog.Kind.YOUNG || e.kind() == GcLog.Kind.MIXED
                    || e.kind() == GcLog.Kind.FULL && !e.name().contains("Degenerated"))) {
                assertTrue(e.before() > 0 && e.after() >= 0 && e.capacity() > 0, name + ": heap of " + e);
                assertTrue(e.ms() > 0, name + ": time of " + e);
            }
        }
        for (int i = 1; i < g.events().size(); i++) {
            assertTrue(g.events().get(i).time() >= g.events().get(i - 1).time(), "in time order");
        }
        assertTrue(GcLog.looksLikeGcLog(String.join("\n", lines.subList(0, Math.min(lines.size(), 400)))), name);
    }

    @Test
    void java8DetailsAreRead() throws IOException {
        GcLog g = GcLog.parse(sample("j8-parallel"));
        GcLog.Event first = g.events().get(0);
        // 2026-10-26T16:39:50.616+0800: 0.254: [GC (Allocation Failure) … [PSYoungGen: 65536K->10738K(76288K)]
        //   65536K->46998K(251392K), 0.0452875 secs] [Times: user=0.00 sys=0.03, real=0.05 secs]
        assertEquals(GcLog.Kind.YOUNG, first.kind());
        assertEquals("Allocation Failure", first.cause());
        assertEquals(65536L << 10, first.before());
        assertEquals(46998L << 10, first.after());
        assertEquals(251392L << 10, first.capacity());
        assertEquals(45.2875, first.ms(), 1e-6);
        assertEquals(0.05, first.real(), 1e-9);
        assertEquals(0.254, first.time(), 1e-9);
        assertTrue(g.stops().size() > 100, "stopped times");
        assertTrue(g.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.FULL && e.cause().equals("Ergonomics")));

        GcLog cms = GcLog.parse(sample("j8-cms"));
        assertTrue(cms.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.INITIAL_MARK));
        assertTrue(cms.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.REMARK));
        assertTrue(cms.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.CONCURRENT && e.name().equals("Concurrent mark")));
        assertTrue(cms.events().stream().anyMatch(e -> e.trouble().equals("concurrent mode failure")
                && e.kind() == GcLog.Kind.FULL), "a young collection that failed became a full one");

        GcLog g1 = GcLog.parse(sample("j8-g1"));
        assertTrue(g1.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.MIXED));
        assertTrue(g1.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.REMARK));
        assertTrue(g1.events().stream().anyMatch(e -> e.cause().equals("G1 Humongous Allocation")));
    }

    @Test
    void java9AndLaterDetailsAreRead() throws IOException {
        GcLog g = GcLog.parse(sample("j25-g1"));
        // [2026-10-26T16:39:50.441+0800][0.071s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 48M->25M(256M) 15.766ms
        GcLog.Event first = g.events().stream().filter(GcLog.Event::stops).findFirst().orElseThrow();
        assertEquals(0, first.id());
        assertEquals("Pause Young (Normal)", first.name());
        assertEquals("G1 Evacuation Pause", first.cause());
        assertEquals(48L << 20, first.before());
        assertEquals(25L << 20, first.after());
        assertEquals(15.766, first.ms(), 1e-9);
        assertEquals(0.071 - 0.015766, first.time(), 1e-9, "it started before it was logged");
        assertTrue(first.real() >= 0, "the CPU line joined to it");
        assertTrue(g.events().stream().anyMatch(e -> e.trouble().startsWith("Evacuation Failure")));
        assertTrue(g.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.CONCURRENT && e.name().equals("Concurrent Mark Cycle")));

        GcLog z = GcLog.parse(sample("j17-zgc"));
        assertTrue(z.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.STALL && e.cause().equals("main")));
        assertTrue(z.events().stream().anyMatch(e -> e.name().equals("Garbage Collection") && e.after() >= 0));

        GcLog gz = GcLog.parse(sample("j25-zgc"));
        assertTrue(gz.events().stream().anyMatch(e -> e.name().equals("Minor Collection") && e.ms() > 0));
        assertTrue(gz.events().stream().anyMatch(e -> e.name().startsWith("Pause Mark Start") && e.name().endsWith("(young)")));

        GcLog sh = GcLog.parse(sample("j25-shen"));
        assertTrue(sh.events().stream().anyMatch(e -> e.kind() == GcLog.Kind.INITIAL_MARK && e.name().equals("Pause Init Mark")));
        assertTrue(sh.events().stream().anyMatch(e -> e.name().equals("Concurrent cleanup") && e.after() >= 0));

        GcLog sp = GcLog.parse(sample("j17-g1-time"));
        assertTrue(sp.stops().size() > 100, "safepoints");
        assertTrue(sp.stops().stream().allMatch(s -> s.totalMs() >= 0 && s.reachMs() >= 0));
    }

    @Test
    void gcLinesInsideOtherOutputAndOtherDecorations() {
        List<String> lines = List.of(
                "Starting the app",
                "[0.010s][info][gc] Using G1",
                "12:00:01 INFO something else happened",
                "[2.000s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->4M(256M) 3.500ms",
                "[1234ms][info][gc] GC(1) Pause Full (System.gc()) 30M->10M(256M) 20.000ms",
                "[2026-10-21T10:00:05.000+0000][info][gc] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 24M->5M(256M) 1.000ms",
                "[0.3s][info][gc   ] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 1,5M->1M(256M) 0,5ms");
        GcLog g = GcLog.parse(lines);
        assertEquals(4, g.events().size(), g.events().toString());
        GcLog.Event full = g.events().stream().filter(e -> e.kind() == GcLog.Kind.FULL).findFirst().orElseThrow();
        assertEquals("System.gc()", full.cause());
        assertEquals(1.234 - 0.02, full.time(), 1e-9, "uptime in ms");
        assertEquals("G1", g.collector());
        assertTrue(GcLog.looksLikeGcLog(String.join("\n", lines)));
        assertFalse(GcLog.looksLikeGcLog("12:00 INFO a\n12:01 INFO [gc] mentioned\n12:02 WARN GC(1) maybe"));
    }

    @Test
    void aMergedLogsLabelsAreSkipped() throws IOException {
        List<String> lines = sample("j25-g1");
        GcLog plain = GcLog.parse(lines);
        GcLog merged = GcLog.parse(lines.stream().map(l -> "<gc.log.1> " + l).toList());
        assertEquals(plain.events().size(), merged.events().size());
        assertEquals(plain.events().get(5), merged.events().get(5));
        List<String> old = sample("j8-parallel");
        assertEquals(GcLog.parse(old).events().size(), GcLog.parse(old.stream().map(l -> "<a> " + l).toList()).events().size());
    }

    @Test
    void sizesAndParentheses() {
        assertEquals(24L << 20, GcLog.bytes("24M"));
        assertEquals(65536L << 10, GcLog.bytes("65536K"));
        assertEquals(Math.round(24576.0 * 1024), GcLog.bytes("24576.0K"));
        assertEquals(0, GcLog.bytes("0.0B"));
        assertEquals(1L << 30, GcLog.bytes("1G"));
        assertEquals("System.gc()", GcLog.firstParens("Full GC (System.gc()) x"));
        assertEquals(List.of("Normal", "G1 Evacuation Pause"), GcLog.parens(" (Normal) (G1 Evacuation Pause)"));
        assertNotEquals(Long.MIN_VALUE, GcLog.wall("2026-10-26T16:39:50.378+0800"));
    }
}
