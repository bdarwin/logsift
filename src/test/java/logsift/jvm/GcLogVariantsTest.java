package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * The same GC logs written with other settings (fewer or more time stamps, other decorations,
 * Windows line ends) are read the same: the same collections, the same pauses.
 */
class GcLogVariantsTest {
    @TempDir
    Path dir;

    private static String pauses(GcLog g) {
        return g.events().stream().filter(e -> e.kind() != GcLog.Kind.CONCURRENT)
                .sorted(java.util.Comparator.comparingLong(GcLog.Event::line)) // made-up times may tie
                .map(e -> e.kind() + " " + e.name() + " " + e.ms())
                .collect(Collectors.joining("\n"));
    }

    private static List<String> map(List<String> lines, java.util.function.UnaryOperator<String> f) {
        return lines.stream().map(f).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"j8-parallel", "j8-g1", "j8-cms", "j8-serial"})
    void java8WithDatesOnly(String name) throws Exception {
        // -XX:+PrintGCDateStamps without -XX:+PrintGCTimeStamps
        List<String> orig = GcLogTest.sample(name);
        GcLog a = GcLog.parse(orig);
        GcLog b = GcLog.parse(map(orig, s -> s.replaceAll("(\\d{4}-\\d\\d-\\d\\dT[\\d:.]+[+-]\\d{4}): \\d+\\.\\d+: ", "$1: ")));
        assertTrue(a.events().size() > 10, name);
        assertEquals(pauses(a), pauses(b), name);
        GcLog.Event first = b.events().get(0);
        assertEquals(a.events().get(0).wall(), first.wall(), name + ": times come from the dates");
        assertTrue(b.events().get(b.events().size() - 1).time() > first.time(), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"j11-g1", "j17-g1-time", "j17-serial-gconly", "j25-g1", "j25-zgc", "j25-shen", "j25-parallel"})
    void unifiedWithOnlyTheTime(String name) throws Exception {
        // -Xlog:gc:file=…:time (no level, no tags): "[2026-10-26T14:10:18.830+0800] GC(15414) Pause Young …"
        List<String> orig = GcLogTest.sample(name).stream().filter(s -> s.matches("^\\[.*\\[gc\\s*\\].*")).toList();
        GcLog a = GcLog.parse(orig);
        long[] t = {Instant.parse("2026-10-26T06:10:18.830Z").toEpochMilli()};
        GcLog b = GcLog.parse(map(orig, s -> "[" + STAMP.format(Instant.ofEpochMilli(t[0] += 250)) + "] "
                + s.substring(s.lastIndexOf("] ") + 2)));
        assertTrue(a.events().size() > 5, name);
        assertEquals(pauses(a), pauses(b), name);
        assertEquals(a.collector().isEmpty() ? "" : a.collector(), b.collector(), name);
        assertTrue(b.events().get(b.events().size() - 1).time() > b.events().get(0).time(), name + ": times from the dates");
    }

    @Test
    void unifiedWithOnlyTheTimeAndEveryTag() throws Exception {
        // -Xlog:gc*:file=…:time: the details are there too, and counted once
        for (String name : List.of("j17-g1-time", "j25-g1", "j25-zgc", "j25-shen", "j25-parallel")) {
            List<String> orig = GcLogTest.sample(name);
            GcLog a = GcLog.parse(orig);
            GcLog b = GcLog.parse(map(orig, s -> s.startsWith("[")
                    ? "[2026-10-26T14:10:18.830+0800] " + s.substring(s.lastIndexOf("] ") + 2) : s));
            assertEquals(pauses(a), pauses(b), name);
        }
    }

    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx").withZone(java.time.ZoneOffset.ofHours(8));

    @ParameterizedTest
    @ValueSource(strings = {"j11-g1", "j17-g1-time", "j25-g1", "j25-zgc", "j25-shen", "j25-parallel"})
    void unifiedWithMoreDecorations(String name) throws Exception {
        // -Xlog:gc*:file=…:time,uptime,hostname,pid,tid,level,tags
        List<String> orig = GcLogTest.sample(name);
        GcLog a = GcLog.parse(orig);
        GcLog b = GcLog.parse(map(orig, s -> s.startsWith("[")
                ? "[2026-10-27T10:00:00.000+0800][Prod-App-01.acme.local][4182][4199]" + s.substring(s.indexOf(']') + 1)
                : s));
        assertTrue(a.events().size() > 10, name);
        assertEquals(pauses(a), pauses(b), name);
    }

    @Test
    void windowsLineEnds() throws Exception {
        for (String name : List.of("j8-parallel", "j17-g1-time")) {
            List<String> orig = GcLogTest.sample(name);
            Path f = dir.resolve(name + ".gc");
            Files.writeString(f, String.join("\r\n", orig) + "\r\n");
            try (logsift.core.LogDocument doc = logsift.core.LogDocument.open(f)) {
                assertTrue(doc.awaitIdle(30_000));
                GcLog b = GcLog.parse(new GcLog.Lines() {
                    @Override
                    public long count() {
                        return doc.lineCount();
                    }

                    @Override
                    public void read(long first, int n, String[] out) {
                        doc.readLines(first, n, out);
                    }
                }, null, () -> false);
                assertEquals(pauses(GcLog.parse(orig)), pauses(b), name);
            }
        }
    }

    @Test
    void timeOnlyG1LogAsWrittenInProduction() {
        // -Xlog:gc:file=…:time on a 48 GB G1 heap
        List<String> lines = List.of(
                "[2026-10-26T14:10:18.830+0800] GC(15414) Pause Young (Mixed) (G1 Evacuation Pause) 35720M->31698M(49152M) 46.644ms",
                "[2026-10-26T14:10:34.373+0800] GC(15415) Pause Young (Concurrent Start) (G1 Evacuation Pause) 42258M->32100M(49152M) 72.481ms",
                "[2026-10-26T14:10:34.373+0800] GC(15416) Concurrent Mark Cycle",
                "[2026-10-26T14:10:35.851+0800] GC(15417) Pause Young (Normal) (G1 Evacuation Pause) 42212M->33009M(49152M) 170.193ms",
                "[2026-10-26T14:10:37.188+0800] GC(15418) Pause Young (Normal) (G1 Evacuation Pause) 41489M->33209M(49152M) 160.477ms",
                "[2026-10-26T14:10:40.613+0800] GC(15416) Pause Remark 38715M->33563M(49152M) 11.177ms",
                "[2026-10-26T14:10:42.356+0800] GC(15416) Pause Cleanup 35909M->35909M(49152M) 1.019ms",
                "[2026-10-26T14:10:42.382+0800] GC(15416) Concurrent Mark Cycle 8009.054ms",
                "[2026-10-26T14:11:08.611+0800] GC(15420) Pause Young (Prepare Mixed) (G1 Evacuation Pause) 37410M->28153M(49152M) 37.044ms",
                "[2026-10-26T14:11:08.890+0800] GC(15421) Pause Young (Mixed) (GCLocker Initiated GC) 30242M->26274M(49152M) 113.196ms",
                "[2026-10-26T14:11:10.148+0800] GC(15422) Pause Young (Concurrent Start) (GCLocker Initiated GC) 40921M->28499M(49152M) 289.570ms",
                "[2026-10-26T14:11:29.556+0800] GC(15434) Pause Young (Concurrent Start) (G1 Humongous Allocation) 31814M->29092M(49152M) 16.393ms");
        GcLog g = GcLog.parse(lines);
        assertEquals(11, g.events().size());
        assertEquals("G1", g.collector());
        GcLog.Event mixed = g.events().get(0);
        assertEquals(GcLog.Kind.MIXED, mixed.kind());
        assertEquals("G1 Evacuation Pause", mixed.cause());
        assertEquals(35720L << 20, mixed.before());
        assertEquals(49152L << 20, mixed.capacity());
        assertEquals(java.time.OffsetDateTime.parse("2026-10-26T14:10:18.830+08:00").toInstant().toEpochMilli()
                - 47, g.events().get(0).wall(), 1.0);
        GcLog.Event cycle = g.events().stream().filter(e -> e.kind() == GcLog.Kind.CONCURRENT).findFirst().orElseThrow();
        assertEquals(8009.054, cycle.ms(), 1e-9);
        GcReport r = GcReport.of(g);
        assertTrue(r.toString() != null);
    }
}
