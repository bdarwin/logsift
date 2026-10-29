package logsift.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ready-made dump for the visual tests reads as it should. */
class TestDumpsTest {
    @TempDir
    Path dir;

    @Test
    void ordersServiceCrash() throws Exception {
        Path f = TestDumps.crashDump(dir);
        assertTrue(Files.size(f) < 1 << 20, Files.size(f) + " bytes");
        try (Minidump d = Minidump.open(f)) {
            assertTrue(d.isFull());
            CrashReport r = WindowsCrash.analyze(d, new Symbols(d, List.of(), null), null);
            assertEquals("Access violation: reading address 0x0000000000000018, a null pointer (a field at offset 0x18 "
                    + "of a null object)", r.title());
            assertEquals("in OrdersService!Order::total+0x1f", r.where());
            CrashReport.Thread c = r.threads().get(0);
            assertTrue(c.crashed());
            assertEquals("worker-2", c.name());
            assertEquals(List.of("OrdersService!Order::total+0x1f", "OrdersService!processOrder+0x41",
                    "OrdersService!workerLoop+0x41", "kernel32!BaseThreadInitThunk+0x41", "ntdll!RtlUserThreadStart+0x41"),
                    c.frames().stream().map(CrashReport.Frame::text).toList());
            assertEquals(9, r.threads().size());
            assertEquals("waiting for window messages (idle)", state(r, "6512"));
            assertEquals("waiting for a lock (critical section)", state(r, "7088"));
            assertEquals("idle (a thread-pool thread waiting for work)", state(r, "7200"));
            assertEquals("sleeping", state(r, "7300"));
            assertTrue(r.facts().stream().anyMatch(x -> x.label().equals("Command line") && x.value().contains("orders.yaml")));
            assertTrue(r.facts().stream().anyMatch(x -> x.label().equals("Memory")));
            String text = CrashText.threads(r, null, 8, 20);
            assertTrue(text.contains("4 threads: 7200, 7204, 7208, 7212"), text);
            assertTrue(text.contains("2 threads: 7088 \"worker-1\", 7120 \"worker-3\""), text);
        }
    }

    private static String state(CrashReport r, String id) {
        return r.threads().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow().state();
    }
}
