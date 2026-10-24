package logsift.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeapDumpTest {
    @TempDir
    static Path tmp;
    private Path file;
    private HeapDump dump;
    /** class name → {instances, bytes} from the JVM itself. */
    private final Map<String, long[]> histogram = new HashMap<>();

    @BeforeAll
    void dumpAndRead() throws Exception {
        file = tmp.resolve("leak.hprof");
        String hist = LeakApp.run(file, tmp.resolve("histogram.txt"));
        for (String line : hist.split("\n")) {
            String[] f = line.trim().split("\\s+");
            // "   1:         20000       12345678  logsift.heap.LeakApp$Session"
            if (f.length >= 4 && f[0].endsWith(":")) {
                histogram.put(f[3], new long[] {Long.parseLong(f[1]), Long.parseLong(f[2])});
            }
        }
        dump = HeapDump.open(file, null, null, null);
    }

    @AfterAll
    void close() {
        dump.close();
    }

    private HeapDump.ClassStat stat(String name) {
        return dump.classes().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void countsAndSizesMatchTheJvm() {
        HeapDump.ClassStat s = stat("logsift.heap.LeakApp$Session");
        assertEquals(LeakApp.SESSIONS, s.count());
        long[] jvm = histogram.get("logsift.heap.LeakApp$Session");
        assertEquals(jvm[1] / jvm[0], s.shallow() / s.count(), "bytes per Session, as the JVM counts them");
        long[] node = histogram.get("java.util.HashMap$Node");
        HeapDump.ClassStat n = stat("java.util.HashMap$Node");
        assertEquals(node[1] / node[0], n.shallow() / n.count(), "bytes per HashMap$Node");
        long[] rec = histogram.get("logsift.heap.LeakApp$Order");
        assertEquals(LeakApp.ORDERS, stat("logsift.heap.LeakApp$Order").count());
        assertEquals(rec[1], stat("logsift.heap.LeakApp$Order").shallow(), "all the orders' bytes");
        // the whole heap: within 3% of the JVM's own count (the dump is taken a moment later)
        long jvmTotal = histogram.values().stream().mapToLong(v -> v[1]).sum();
        assertTrue(Math.abs(dump.totalBytes() - jvmTotal) < jvmTotal * 0.03,
                "total " + dump.totalBytes() + " vs the JVM's " + jvmTotal);
    }

    @Test
    void theCacheHoldsTheSessions() throws Exception {
        int[] maps = dump.objectsOf("java.util.HashMap", 1);
        int map = maps[0];
        long perSession = 528 + stat("logsift.heap.LeakApp$Session").shallow() / LeakApp.SESSIONS;
        assertTrue(dump.retained(map) > LeakApp.SESSIONS * perSession,
                "the biggest HashMap holds the sessions: " + dump.retained(map));
        assertTrue(dump.retained(map) < dump.reachableBytes());
        assertEquals("java.util.HashMap (20,000 entries)", dump.describe(map));
        List<HeapDump.Step> path = dump.pathFromRoot(map);
        assertEquals("a loaded class (its static fields)", path.get(0).via());
        assertEquals("class logsift.heap.LeakApp", dump.describe(path.get(0).node()));
        assertEquals("static CACHE", path.get(1).via());
        assertEquals(map, path.get(path.size() - 1).node());
    }

    @Test
    void aSessionIsAliveThroughTheCache() throws Exception {
        int s = dump.objectsOf("logsift.heap.LeakApp$Session", 1)[0];
        List<HeapDump.Step> path = dump.pathFromRoot(s);
        String chain = path.stream().map(HeapDump.Step::via).toList().toString();
        assertTrue(chain.contains("static CACHE") && chain.contains("table") && chain.contains("value"), chain);
        int id = dump.refField(s, "id");
        assertTrue(dump.describe(id).startsWith("String \"session-"), dump.describe(id));
    }

    @Test
    void aLocalVariableIsARoot() throws Exception {
        int list = -1;
        for (int v : dump.objectsOf("java.util.ArrayList", 50)) {
            if (dump.describe(v).equals("java.util.ArrayList (1,000 items)")) {
                list = v;
            }
        }
        assertTrue(list >= 0, "the orders list");
        String root = dump.pathFromRoot(list).get(0).via();
        assertTrue(root.startsWith("a local variable in LeakApp.main() of thread \"main\""), root);
    }

    @Test
    void softlyReachableIsNotCounted() {
        assertTrue(dump.totalBytes() - dump.reachableBytes() >= 5 << 20,
                "the 5 MB behind a soft reference isn't strongly reachable");
    }

    @Test
    void wasteIsFoundAndPlaced() throws Exception {
        HeapWaste.Report w = HeapWaste.of(dump, null, null);
        HeapWaste.Duplicate active = w.strings().stream().filter(x -> x.text().equals("ACTIVE")).findFirst()
                .orElseThrow(() -> new AssertionError(w.strings().toString()));
        assertTrue(active.copies() >= LeakApp.SESSIONS, "copies: " + active.copies());
        assertEquals("LeakApp.Session.status", active.where());
        assertEquals(active.wasted(), w.strings().get(0).wasted(), "the biggest waste of strings");
        assertTrue(w.strings().stream().anyMatch(x -> x.text().startsWith("customer-") && x.copies() == 20
                && x.where().equals("LeakApp.Order.customer")), "the orders' customers: 1,000 strings, 50 texts");
        HeapWaste.Collections tags = w.collections().stream()
                .filter(c -> c.what().equals("Empty ArrayList") && c.where().equals("LeakApp.Session.tags")).findFirst()
                .orElseThrow(() -> new AssertionError(w.collections().toString()));
        assertEquals(LeakApp.SESSIONS, tags.count());
        HeapWaste.Collections emptied = w.collections().stream()
                .filter(c -> c.where().equals("LeakApp.EMPTIED (static)")).findFirst()
                .orElseThrow(() -> new AssertionError(w.collections().toString()));
        assertEquals("HashMap mostly empty (under a quarter used)", emptied.what());
        assertEquals((2048 - 10) * 4, emptied.wasted());
        assertTrue(w.total() > LeakApp.SESSIONS * 24L * 2, "total " + w.total());
    }

    @Test
    void theThreadsReadAsAThreadDump() throws Exception {
        List<logsift.jvm.ThreadDump> dumps = logsift.jvm.DumpParser.parse(dump.threadDumpText());
        assertEquals(1, dumps.size());
        logsift.jvm.JThread main = dumps.get(0).threads().stream().filter(t -> t.name().equals("main")).findFirst()
                .orElseThrow();
        assertEquals(logsift.jvm.JThread.State.RUNNABLE, main.state());
        assertTrue(main.frames().stream().anyMatch(f -> f.text().contains("logsift.heap.LeakApp.main(LeakApp.java:")),
                main.frames().toString());
        logsift.jvm.JThread ref = dumps.get(0).threads().stream().filter(t -> t.name().equals("Reference Handler"))
                .findFirst().orElseThrow();
        assertTrue(ref.daemon());
        assertEquals(dump.threadCount(), dumps.get(0).threads().size());
    }

    @Test
    void comparedWithAnEarlierDumpTheCacheGrew() throws Exception {
        Path early = tmp.resolve("early.hprof");
        LeakApp.run(early, tmp.resolve("early-histogram.txt"), 5_000);
        try (HeapDump before = HeapDump.open(early, null, null, null)) {
            HeapDiff.Report r = HeapDiff.of(before, dump, null);
            HeapDiff.Place top = r.places().get(0);
            assertEquals("static LeakApp.CACHE", top.where(), r.places().toString());
            assertEquals("java.util.HashMap (5,000 entries)", top.before());
            assertEquals("java.util.HashMap (20,000 entries)", top.after());
            assertTrue(top.growth() > 15_000L * 600, "growth " + top.growth());
            assertTrue(top.contents().startsWith("Mostly 20,000 × LeakApp.Session"), top.contents());
            HeapDiff.ClassDelta s = r.classes().stream().filter(c -> c.name().equals("logsift.heap.LeakApp$Session"))
                    .findFirst().orElseThrow();
            assertEquals(5_000, s.beforeCount());
            assertEquals(20_000, s.afterCount());
            assertTrue(r.places().stream().noneMatch(p -> p.where().contains("EMPTIED")), "what didn't grow");
        }
    }

    @Test
    void theIndexOnDiskGivesTheSameAnswers() throws Exception {
        // not in the JUnit temp folder: Windows can't delete files that are still mapped
        Path index = Files.createTempDirectory("logsift-heap-test").resolve("index");
        try (HeapDump disk = HeapDump.open(file, index, null, null)) {
            assertTrue(disk.isOnDisk());
            assertEquals(dump.reachableBytes(), disk.reachableBytes());
            int map = disk.objectsOf("java.util.HashMap", 1)[0];
            assertEquals(dump.retained(dump.objectsOf("java.util.HashMap", 1)[0]), disk.retained(map));
            assertEquals(dump.describe(dump.objectsOf("java.util.HashMap", 1)[0]), disk.describe(map));
            assertEquals(HeapWaste.of(dump, null, null), HeapWaste.of(disk, null, null));
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertTrue(!Files.exists(index), "the index files are removed");
        }
    }
}
