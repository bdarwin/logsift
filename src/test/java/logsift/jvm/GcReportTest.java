package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a GC log says, worked out from logs of real JVMs: a program that leaks, and one that is fine. */
class GcReportTest {
    @TempDir
    Path dir;

    private Process run(Path log, String heap, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                heap, "-XX:+UseG1GC", "-Xlog:gc*:file=" + log + ":time,uptime,level,tags",
                "-cp", System.getProperty("java.class.path"), GcLoadApp.class.getName()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve(log.getFileName() + ".out").toFile())
                .start();
    }

    private static String titles(GcReport r) {
        StringBuilder sb = new StringBuilder();
        r.findings().forEach(f -> sb.append(f.severity()).append(": ").append(f.title()).append('\n'));
        return sb.toString();
    }

    @Test
    void aLeakAndAHealthyProgram() throws Exception {
        Path leaky = dir.resolve("leaky.log");
        Path fine = dir.resolve("fine.log");
        Process a = run(leaky, "-Xmx128m", "8", "leak");
        Process b = run(fine, "-Xmx512m", "6");
        assertEquals(0, a.waitFor(), Files.readString(dir.resolve("leaky.log.out")));
        assertEquals(0, b.waitFor(), Files.readString(dir.resolve("fine.log.out")));

        GcReport bad = GcReport.of(GcLog.parse(Files.readAllLines(leaky)));
        String t = titles(bad);
        assertEquals("G1", bad.log().collector());
        assertEquals(128L << 20, bad.log().maxHeap());
        assertTrue(bad.seconds() > 5, "most of the 8 s: " + bad.seconds());
        assertTrue(t.contains("BAD: The memory left after GC keeps growing") || t.contains("still"), t);
        assertTrue(t.contains("Something called System.gc() once"), t);
        assertTrue(t.contains("GC stopped the program"), t);
        assertTrue(bad.pausedPercent() > 5, "a small heap that fills: " + bad.pausedPercent());
        assertTrue(bad.allocationRate() > 50e6, "allocates fast: " + bad.allocationRate());
        assertEquals(bad.findings().get(0).severity(), GcReport.Severity.BAD, "the worst first");
        assertTrue(bad.findings().stream().filter(f -> f.title().contains("System.gc()")).allMatch(f -> f.line() >= 0),
                "shows its line");
        double total = 0;
        for (double w : bad.pausedPerWindow(1)) {
            total += w;
        }
        assertEquals(bad.pauses().total(), total, 1, "windows add up to all the pauses");
        assertTrue(bad.buckets().stream().mapToInt(GcReport.Bucket::count).sum() == bad.pauses().count());

        GcReport good = GcReport.of(GcLog.parse(Files.readAllLines(fine)));
        String g = titles(good);
        assertTrue(good.pauses().count() > 0, g);
        assertFalse(g.contains("BAD"), g);
        assertTrue(good.pausedPercent() < 5, g);
        assertEquals(0, good.fullCollections().count(), g);
    }

    @Test
    void words() {
        assertEquals("12 ms", GcReport.ms(12.3));
        assertEquals("1.5 s", GcReport.ms(1500));
        assertEquals("0.05 ms", GcReport.ms(0.05));
        assertEquals("1.2 GB", GcReport.size(1.2 * (1L << 30)));
        assertEquals("2 min 5 s", GcReport.duration(125));
        assertEquals("4.2%", GcReport.pct(4.2));
        assertEquals("once", GcReport.times(1));
        assertEquals("3 times", GcReport.times(3));
    }

    @Test
    void anEmptyLogSaysHowToWriteOne() {
        GcReport r = GcReport.of(GcLog.parse(List.of("nothing here")));
        assertEquals(1, r.findings().size());
        assertTrue(r.findings().get(0).advice().contains("-Xlog:gc*"));
    }
}
