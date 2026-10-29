package logsift.crash;

import static logsift.crash.Scenario.APP;
import static logsift.crash.Scenario.NTDLL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Names for addresses: exports only where they begin the function, else module+offset. */
class SymbolsTest {
    @TempDir
    Path dir;

    @Test
    void exportsNameTheFunctionsTheyBegin() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.unloaded.add(new DumpBuilder.Unloaded(0x150000000L, 0x10000, "C:\\plugins\\plugin.dll"));
        try (Minidump d = Minidump.open(b.write(dir.resolve("t.dmp")))) {
            Symbols s = new Symbols(d, List.of(), null);
            assertEquals("ntdll!NtWaitForSingleObject+0x14", s.name(NTDLL + Scenario.WAIT + 0x14, false).text());
            assertEquals("ntdll!DbgBreakPoint", s.name(NTDLL + Scenario.BREAK, false).text());
            // a return address is looked up at the call before it
            assertEquals("ntdll!RtlUserThreadStart+0x21", s.name(NTDLL + Scenario.START_RET, true).text());
            // in a function that isn't exported: the export before it is not its name
            assertEquals("ntdll+0x1550", s.name(NTDLL + Scenario.HIDDEN + 0x50, false).text());
            assertEquals("app+0x1005", s.name(APP + 0x1005, false).text());
            Symbols.Name u = s.name(0x150001234L, false);
            assertEquals("plugin.dll (unloaded)+0x1234", u.text());
            assertNull(s.name(0x9990000L, false).module());
            assertEquals("?+0x9990000", s.name(0x9990000L, false).text());
            assertTrue(s.isThreadStart(NTDLL + Scenario.START_RET));
            assertFalse(s.isThreadStart(NTDLL + Scenario.WAIT + 0x14));
            assertEquals("exported names only", s.symbolState(d.moduleAt(NTDLL)));
        }
    }

    @Test
    void shortNames() {
        Minidump.Module m = new Minidump.Module(0, 1, 0, 0, "C:\\Windows\\System32\\KERNELBASE.dll", "", null, null);
        assertEquals("KERNELBASE", Symbols.shortName(m));
        assertEquals("app", Symbols.shortName(new Minidump.Module(0, 1, 0, 0, "/x/app.exe", "", null, null)));
        assertEquals("tool", Symbols.shortName(new Minidump.Module(0, 1, 0, 0, "tool", "", null, null)));
    }
}
