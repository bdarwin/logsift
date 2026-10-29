package logsift.crash;

import static logsift.crash.Scenario.APP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A Windows crash report as text: the summary, threads grouped by stack, modules. */
class CrashTextTest {
    @TempDir
    Path dir;

    private CrashReport report() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.threadNames.put(200, "pool-1");
        b.exception(100, 0xC0000005, APP + 0x1005, Scenario.crashContext(), 0, 0x18);
        try (Minidump d = Minidump.open(b.write(dir.resolve("t.dmp")))) {
            return WindowsCrash.analyze(d, new Symbols(d, List.of(), null), null);
        }
    }

    @Test
    void summary() throws Exception {
        String s = CrashText.summary(report(), 20);
        assertTrue(s.startsWith("Windows dump: Access violation: reading address 0x0000000000000018, a null pointer"), s);
        assertTrue(s.contains("\nIn app+0x1005\n"), s);
        assertTrue(s.contains("\nFindings:\n1. [ERROR] The code to look at: app+0x1005"), s);
        assertTrue(s.contains("\nCrashed thread 100:\n   0 app+0x1005  ← own code\n"), s);
        assertTrue(s.contains("   3 ntdll!RtlUserThreadStart+0x21\n"), s);
        assertTrue(s.contains("- Command line: C:\\app\\app.exe --serve\n"), s);
        assertTrue(s.contains("- 2 × waiting for an event or handle\n"), s);
        String short1 = CrashText.summary(report(), 1);
        assertTrue(short1.contains("   … 3 more frames"), short1);
    }

    @Test
    void threadsGroupedByStack() throws Exception {
        CrashReport r = report();
        List<CrashText.Group> g = CrashText.groups(r, 6);
        assertTrue(g.get(0).threads().get(0).crashed(), "the crashed thread first");
        assertEquals(2, g.get(1).threads().size(), "the two idle threads have the same stack");
        String all = CrashText.threads(r, null, 8, 10);
        assertTrue(all.startsWith("3 groups of threads with the same stack (4 threads in all):"), all);
        assertTrue(all.contains("2 threads: 200 \"pool-1\", 201 — waiting for an event or handle"), all);
        assertTrue(all.contains("(guessed)"), "the scanned frame says so");
        String one = CrashText.threads(r, "pool-1", 8, 10);
        assertTrue(one.startsWith("1 group of threads with the same stack matching “pool-1”"), one);
        assertTrue(one.contains("Thread 200 \"pool-1\""), one);
        String limited = CrashText.threads(r, null, 8, 1);
        assertTrue(limited.contains("the first 1 shown (raise limit)"), limited);
    }

    @Test
    void modulesWorthALookFirst() throws Exception {
        String m = CrashText.modules(report(), null, 10);
        assertTrue(m.startsWith("2 modules:\n- app.exe 1.2.3.4  (the crash is here)\n    C:\\app\\app.exe"), m);
        assertTrue(m.contains("- ntdll.dll\n    C:\\Windows\\System32\\ntdll.dll"), m);
        assertTrue(CrashText.modules(report(), "ntdll", 10).startsWith("1 module matching “ntdll”"));
        assertTrue(CrashText.modules(report(), null, 1).contains("… and 1 more"));
    }
}
