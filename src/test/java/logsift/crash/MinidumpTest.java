package logsift.crash;

import static logsift.crash.Scenario.APP;
import static logsift.crash.Scenario.NTDLL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a Windows dump's streams, its memory, and the images in it. */
class MinidumpTest {
    @TempDir
    Path dir;

    private Minidump open(DumpBuilder b) throws Exception {
        return Minidump.open(b.write(dir.resolve("t.dmp")));
    }

    @Test
    void streams() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.pid = 4242;
        b.createTime = 1_700_000_000L - 3600;
        b.userTime = 5;
        b.kernelTime = 2;
        b.threadNames.put(100, "worker-main");
        b.threadTimes.put(100, new long[] {5_000_000, 20_000_000});
        b.handleTypes.addAll(List.of("Event", "File", "Event"));
        b.unloaded.add(new DumpBuilder.Unloaded(0x150000000L, 0x10000, "C:\\plugins\\plugin.dll"));
        b.comment = "written by a test";
        b.exception(100, 0xC0000005, APP + 0x1005, Scenario.crashContext(), 0, 0x18);
        try (Minidump d = open(b)) {
            assertEquals("Windows 10 (build 19045)", d.system().osName());
            assertEquals(Minidump.Arch.AMD64, d.system().arch());
            assertEquals(8, d.system().processors());
            assertEquals(4, d.threads().size());
            assertEquals(100, d.threads().get(0).id());
            assertEquals(Scenario.TEB, d.threads().get(0).teb());
            assertEquals(2, d.modules().size());
            Minidump.Module app = d.mainModule();
            assertEquals("app.exe", app.name());
            assertEquals("1.2.3.4", app.version());
            assertEquals("D:\\build\\app.pdb", app.pdbName());
            assertEquals("0123456789ABCDEF0123456789ABCDEF1", app.pdbId());
            assertFalse(app.isSystem());
            assertTrue(d.moduleAt(NTDLL + 0x10).isSystem());
            assertEquals(app, d.moduleAt(APP + 0x4fff));
            assertNull(d.moduleAt(APP + 0x5000));
            assertEquals(4242, d.processId());
            assertEquals(1_700_000_000L - 3600, d.processCreateTime());
            assertEquals(5, d.processUserSeconds());
            assertEquals(2, d.processKernelSeconds());
            assertEquals("worker-main", d.threadName(100));
            assertEquals(20_000_000, d.threadTimes(100).userTime());
            assertEquals(3, d.handleTotal());
            assertEquals(new Minidump.HandleCount("Event", 2), d.handles().get(0));
            assertEquals("plugin.dll", d.unloadedModules().get(0).name());
            assertNotNull(d.unloadedAt(0x150001234L));
            assertEquals(List.of("written by a test"), d.comments());
            Minidump.ExceptionInfo e = d.exception();
            assertEquals(100, e.threadId());
            assertEquals(0xC0000005, e.code());
            assertArrayEquals(new long[] {0, 0x18}, e.parameters());
            assertEquals(1_700_000_000L, d.time());
            assertFalse(d.isFull(), "a memory list, not a full dump");
        }
    }

    @Test
    void memoryAcrossRangesAndGaps() throws Exception {
        DumpBuilder b = new DumpBuilder();
        b.memory64 = true;
        byte[] one = new byte[0x100];
        byte[] two = new byte[0x100];
        for (int i = 0; i < 0x100; i++) {
            one[i] = (byte) i;
            two[i] = (byte) (0x80 + i);
        }
        b.mem(0x1000, one).mem(0x1100, two).mem(0x3000, new byte[0x10]);
        try (Minidump d = open(b)) {
            assertTrue(d.isFull(), "a Memory64List is a full dump's");
            assertEquals(0x210, d.memoryBytes());
            byte[] across = d.memory(0x10f8, 16);
            assertEquals(16, across.length);
            assertEquals((byte) 0xf8, across[0]);
            assertEquals((byte) 0x80, across[8], "the next range follows without a gap");
            assertEquals(8, d.memory(0x11f8, 64).length, "stops at the gap");
            assertNull(d.memory(0x2000, 4));
            assertTrue(d.has(0x3000));
            assertFalse(d.has(0x3010));
            assertEquals(Long.valueOf(0x0706050403020100L), d.pointer(0x1000));
        }
    }

    @Test
    void regions() throws Exception {
        DumpBuilder b = new DumpBuilder();
        b.regions.add(new DumpBuilder.RegionSpec(0x60000000L, 0x10000, Minidump.Region.MEM_FREE, 1, 0));
        b.regions.add(new DumpBuilder.RegionSpec(0x50000000L, 0x1000, Minidump.Region.MEM_COMMIT, 0x20,
                Minidump.Region.MEM_PRIVATE));
        try (Minidump d = open(b)) {
            assertEquals(2, d.regions().size());
            assertEquals(0x50000000L, d.regions().get(0).base(), "sorted by address");
            assertTrue(d.regionAt(0x50000fffL).executable());
            assertEquals(Minidump.Region.MEM_FREE, d.regionAt(0x60000010L).state());
            assertNull(d.regionAt(0x70000000L));
        }
    }

    @Test
    void imagesInMemory() throws Exception {
        try (Minidump d = open(Scenario.standard())) {
            PeImage app = PeImage.inDump(d, APP);
            assertNotNull(app);
            assertTrue(app.is64());
            assertEquals(0x8664, app.machine());
            assertEquals(0x5f000000, app.timeDateStamp());
            assertEquals(List.of(".text", ".rdata"), app.sections().stream().map(PeImage.Section::name).toList());
            assertTrue(app.sectionAt(0x1500).executable());
            assertFalse(app.sectionAt(0x3500).executable());
            assertTrue(app.hasUnwindTable());
            assertEquals(Scenario.A, app.functionAt(Scenario.A + 0x50).begin());
            assertNull(app.functionAt(Scenario.LEAF + 5), "a leaf isn't in the table");
            assertEquals(0, app.exportCount());
            PeImage nt = PeImage.inDump(d, NTDLL);
            assertEquals(3, nt.exportCount());
            assertEquals("NtWaitForSingleObject", nt.exportBefore(Scenario.WAIT + 0x14)[1]);
            assertNull(PeImage.inDump(d, 0x9990000L), "not in the dump");
        }
    }

    @Test
    void imageFromAFile() throws Exception {
        Path f = dir.resolve("ntdll.dll");
        Files.write(f, Scenario.ntdllImage());
        PeImage img = PeImage.fromFile(f);
        assertNotNull(img);
        assertEquals(3, img.exportCount());
        assertEquals(Scenario.WAIT, img.functionAt(Scenario.WAIT + 4).begin());
        Files.write(dir.resolve("junk.dll"), new byte[] {1, 2, 3});
        assertNull(PeImage.fromFile(dir.resolve("junk.dll")));
    }

    @Test
    void notADump() throws Exception {
        Path f = dir.resolve("x.dmp");
        Files.writeString(f, "not a dump at all, just some text that is long enough");
        IOException e = assertThrows(IOException.class, () -> Minidump.open(f));
        assertTrue(e.getMessage().contains("Not a Windows dump"), e.getMessage());
        assertTrue(Minidump.looksLike(new byte[] {'M', 'D', 'M', 'P', 1}));
        assertFalse(Minidump.looksLike(new byte[] {'M', 'Z'}));
    }

    @Test
    void saysWhyAFileIsNotADump() throws Exception {
        Path empty = Files.write(dir.resolve("a.mdmp"), new byte[0]);
        assertTrue(CrashFiles.whyNot(empty).contains("is empty"), CrashFiles.whyNot(empty));
        byte[] kernel = new byte[64];
        System.arraycopy("PAGEDU64".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, kernel, 0, 8);
        Path machine = Files.write(dir.resolve("MEMORY.DMP"), kernel);
        assertTrue(CrashFiles.whyNot(machine).contains("memory dump of Windows itself"), CrashFiles.whyNot(machine));
        Path zip = Files.write(dir.resolve("z.mdmp"), new byte[] {'P', 'K', 3, 4, 0, 0});
        assertTrue(CrashFiles.whyNot(zip).contains("zip"));
        Path cut = Files.write(dir.resolve("cut.mdmp"), new byte[] {'M', 'D', 'M', 'P', (byte) 0x93, (byte) 0xA7});
        assertTrue(CrashFiles.whyNot(cut).contains("cut short or damaged"), CrashFiles.whyNot(cut));
        Path other = Files.write(dir.resolve("o.mdmp"), new byte[] {1, 2, 3, (byte) 0xFF});
        assertTrue(CrashFiles.whyNot(other).contains("starts with 01 02 03 FF"), CrashFiles.whyNot(other));
        assertTrue(CrashFiles.whyNot(dir.resolve("gone.mdmp")).contains("can't be read"));
        IOException e = assertThrows(IOException.class,
                () -> CrashFiles.analyze(machine, new CrashFiles.Options(CrashSettings.DEFAULT, null, false), () -> false));
        assertTrue(e.getMessage().contains("Windows itself"), e.getMessage());
    }

    @Test
    void windowsNames() {
        assertEquals("Windows 11 (build 22631)", new Minidump.SystemInfo(Minidump.Arch.AMD64, 8, 1, 10, 0, 22631, "")
                .osName());
        assertEquals("Windows Server 2019 (build 17763)", new Minidump.SystemInfo(Minidump.Arch.AMD64, 8, 3, 10, 0,
                17763, "").osName());
        assertEquals("Windows 7 (build 7601, Service Pack 1)", new Minidump.SystemInfo(Minidump.Arch.X86, 2, 1, 6, 1,
                7601, "Service Pack 1").osName());
        assertEquals(8, Minidump.Arch.ARM64.pointerSize());
        assertEquals(4, Minidump.Arch.X86.pointerSize());
    }

    @Test
    void guidAsTheSymbolServerWritesIt() {
        assertEquals("0123456789ABCDEF0123456789ABCDEF", Minidump.guidText(Scenario.GUID));
    }
}
