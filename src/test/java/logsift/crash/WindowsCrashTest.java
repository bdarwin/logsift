package logsift.crash;

import static logsift.crash.Scenario.APP;
import static logsift.crash.Scenario.NTDLL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import logsift.crash.CrashReport.FrameKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a Windows dump means: exceptions, the code to blame, thread states, facts. */
class WindowsCrashTest {
    @TempDir
    Path dir;

    private CrashReport analyze(DumpBuilder b) throws Exception {
        try (Minidump d = Minidump.open(b.write(dir.resolve("t.dmp")))) {
            return WindowsCrash.analyze(d, new Symbols(d, List.of(), null), null);
        }
    }

    private static boolean hasFinding(CrashReport r, String titlePart) {
        return r.findings().stream().anyMatch(f -> f.title().contains(titlePart));
    }

    @Test
    void accessViolationNearNullIsANullPointerInTheProgramsCode() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.exception(100, 0xC0000005, APP + 0x1005, Scenario.crashContext(), 0, 0x18);
        CrashReport r = analyze(b);
        assertEquals(CrashReport.Kind.WINDOWS_DUMP, r.kind());
        assertEquals("Access violation: reading address 0x0000000000000018, a null pointer (a field at offset 0x18 "
                + "of a null object)", r.title());
        assertEquals("in app+0x1005", r.where());
        CrashReport.Thread c = r.crashedThread();
        assertNotNull(c);
        assertEquals("100", c.id());
        assertEquals("crashed", c.state());
        assertEquals(r.threads().get(0), c, "the crashed thread comes first");
        assertEquals(List.of("app+0x1005", "app+0x1150", "app+0x1450", "ntdll!RtlUserThreadStart+0x21"),
                c.frames().stream().map(CrashReport.Frame::text).toList());
        assertEquals(FrameKind.OWN, c.frames().get(0).kind());
        assertEquals(FrameKind.SYSTEM, c.frames().get(3).kind());
        assertTrue(hasFinding(r, "The code to look at: app+0x1005"), r.findings().toString());
        assertTrue(r.sections().stream().anyMatch(s -> s.title().startsWith("Registers")));
        // the module the crash is in, and the program
        assertEquals("the crash is here", r.modules().get(0).note());
    }

    @Test
    void runningCodeInAnUnloadedDll() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.unloaded.add(new DumpBuilder.Unloaded(0x150000000L, 0x10000, "C:\\plugins\\plugin.dll"));
        b.exception(100, 0xC0000005, 0x150001234L, Scenario.crashContext(), 8, 0x150001234L);
        CrashReport r = analyze(b);
        assertEquals("Access violation: running code at address 0x0000000150001234, in plugin.dll, which had been "
                + "unloaded", r.title());
        assertTrue(r.summary().contains("after it was unloaded"), r.summary());
        assertTrue(hasFinding(r, "1 DLL was unloaded"));
        assertTrue(r.modules().stream().anyMatch(m -> m.name().equals("plugin.dll") && m.note().equals("unloaded")));
    }

    @Test
    void writingToFreedMemory() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.regions.add(new DumpBuilder.RegionSpec(0x60000000L, 0x10000, Minidump.Region.MEM_FREE, 1, 0));
        b.exception(100, 0xC0000005, APP + 0x1005, Scenario.crashContext(), 1, 0x60000010L);
        CrashReport r = analyze(b);
        assertTrue(r.title().startsWith("Access violation: writing address 0x0000000060000010, memory that isn't "
                + "allocated"), r.title());
    }

    @Test
    void stackOverflowFastFailHeapAndInvalidParameter() throws Exception {
        DumpBuilder so = Scenario.standard();
        so.exception(100, 0xC00000FD, APP + 0x1005, Scenario.crashContext(), 1, 0x10000000L);
        CrashReport r = analyze(so);
        assertEquals("Stack overflow", r.title());
        assertTrue(hasFinding(r, "The stack ran out"));

        DumpBuilder ff = Scenario.standard();
        ff.exception(100, 0xC0000409, APP + 0x1005, Scenario.crashContext(), 2);
        r = analyze(ff);
        assertEquals("Fast fail (security check or abort): a stack buffer overrun: a function wrote past the end of "
                + "a local array", r.title());

        DumpBuilder heap = Scenario.standard();
        heap.exception(100, 0xC0000374, NTDLL + 0x1550, Scenario.crashContext());
        r = analyze(heap);
        assertEquals("Heap corruption", r.title());
        assertTrue(hasFinding(r, "The heap was damaged earlier"));

        DumpBuilder ip = Scenario.standard();
        ip.exception(100, 0xC000000D, APP + 0x1005, Scenario.crashContext());
        assertEquals("Invalid argument to a function (usually the C runtime)", analyze(ip).title());
    }

    @Test
    void cppExceptionTypeAndMessageFromTheThrowInformation() throws Exception {
        // app.exe again, with throw information in its .rdata (RVAs, as on x64)
        int ti = PeBuilder.FREE_AT;
        int cta = ti + 0x40;
        int ct1 = ti + 0x80;
        int ct2 = ti + 0xc0;
        int td1 = ti + 0x100;
        int td2 = ti + 0x180;
        LeBytes rd = new LeBytes();
        rd.zeros(0x200);
        byte[] data = rd.toArray();
        LeBytes.put32(data, 12, cta);
        LeBytes.put32(data, cta - ti, 2);
        LeBytes.put32(data, cta - ti + 4, ct1);
        LeBytes.put32(data, cta - ti + 8, ct2);
        LeBytes.put32(data, ct1 - ti + 4, td1);
        LeBytes.put32(data, ct2 - ti + 4, td2);
        byte[] n1 = Scenario.ascii(".?AVruntime_error@std@@");
        byte[] n2 = Scenario.ascii(".?AVexception@std@@");
        System.arraycopy(n1, 0, data, td1 - ti + 16, n1.length);
        System.arraycopy(n2, 0, data, td2 - ti + 16, n2.length);
        DumpBuilder b = Scenario.standard();
        // replace app.exe's memory with one that holds the throw information
        b.memory.set(0, new DumpBuilder.Mem(APP, withData(Scenario.appImage(), ti, data)));
        long obj = 0x30000000L;
        b.mem(new DumpBuilder.Block(obj, 0x200).p64(obj + 8, obj + 0x100).ascii(obj + 0x100, "disk full"));
        b.exception(100, 0xE06D7363, NTDLL + 0x1550, Scenario.crashContext(), 0x19930520L, obj, APP + ti, APP);
        CrashReport r = analyze(b);
        assertEquals("Unhandled C++ exception: std::runtime_error (\"disk full\")", r.title());
        assertTrue(hasFinding(r, "std::runtime_error"));
        assertTrue(r.findings().stream().noneMatch(f -> f.title().startsWith("The code to look at")),
                "a thrown exception names its type, not a frame to blame");
    }

    private static byte[] withData(byte[] image, int at, byte[] data) {
        System.arraycopy(data, 0, image, at, data.length);
        return image;
    }

    @Test
    void aDebuggerBreakIsNotACrash() throws Exception {
        DumpBuilder b = Scenario.standard();
        long st = 0x10030000L;
        b.mem(new DumpBuilder.Block(st, 0x1000).p64(st + 0x100, NTDLL + Scenario.START_RET));
        b.thread(400, 0, st, 0x1000, DumpBuilder.amd64(NTDLL + Scenario.BREAK, st + 0x100, 0));
        b.exception(400, 0x80000003, NTDLL + Scenario.BREAK, DumpBuilder.amd64(NTDLL + Scenario.BREAK, st + 0x100, 0));
        CrashReport r = analyze(b);
        assertEquals("No crash: a debugger stopped the program", r.title());
        assertEquals("stopped here by the debugger", r.crashedThread().state());
        assertEquals("ntdll!DbgBreakPoint", r.crashedThread().frames().get(0).text());
        assertTrue(CrashText.summary(r, 10).contains("Thread where it stopped: 400"));
    }

    @Test
    void aHangDumpHasNoExceptionAndLooksAtTheThreads() throws Exception {
        DumpBuilder b = new DumpBuilder();
        DumpBuilder s = Scenario.standard();
        // the idle thread first: it is the "main" thread here
        b.modules.addAll(s.modules);
        b.memory.addAll(s.memory);
        b.threads.add(s.threads.get(1));
        b.threads.add(s.threads.get(2));
        b.threads.add(s.threads.get(0));
        CrashReport r = analyze(b);
        assertEquals("No crash: the dump was taken while the program ran", r.title());
        assertNull(r.crashedThread());
        assertTrue(hasFinding(r, "The first thread is waiting for an event or handle"), r.findings().toString());
        assertTrue(hasFinding(r, "A minidump"));
    }

    @Test
    void factsFromTheProcessParametersAndTimes() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.pid = 4242;
        b.createTime = b.timeDateStamp - 3600;
        b.userTime = 5;
        b.kernelTime = 2;
        b.handleTypes.addAll(List.of("Event", "Event", "File"));
        b.threadNames.put(100, "main-loop");
        b.threadTimes.put(200, new long[] {5_000_000, 20_000_000});
        b.exception(100, 0xC0000005, APP + 0x1005, Scenario.crashContext(), 0, 0);
        CrashReport r = analyze(b);
        Map<String, String> f = r.facts().stream().collect(Collectors.toMap(CrashReport.Fact::label,
                CrashReport.Fact::value));
        assertEquals("app.exe 1.2.3.4", f.get("Program"));
        assertEquals("C:\\app\\app.exe --serve", f.get("Command line"));
        assertEquals("C:\\work\\", f.get("Working folder"));
        assertEquals("4242", f.get("Process id"));
        assertEquals("60 min", f.get("Running for"));
        assertEquals("7 s", f.get("CPU time"));
        assertEquals("Windows 10 (build 19045)", f.get("Windows"));
        assertEquals("x64, 8 logical processors", f.get("Processor"));
        assertEquals("3 (Event 2, File 1)", f.get("Handles"));
        assertEquals("4", f.get("Threads"));
        assertEquals("Access violation: reading address 0x0000000000000000, a null pointer", r.title());
        assertEquals("main-loop", r.crashedThread().name());
        CrashReport.Thread idle = r.threads().stream().filter(t -> t.id().equals("200")).findFirst().orElseThrow();
        assertEquals("2.5 s", idle.cpu());
        assertEquals("waiting for an event or handle", idle.state());
    }

    @Test
    void manyHandlesAJavaProcessAndGeneratedCode() throws Exception {
        DumpBuilder b = Scenario.standard();
        for (int i = 0; i < 10_001; i++) {
            b.handleTypes.add("Event");
        }
        b.module(0x180000000L, 0x10000, "C:\\jdk\\bin\\server\\jvm.dll");
        b.regions.add(new DumpBuilder.RegionSpec(0x50000000L, 0x1000, Minidump.Region.MEM_COMMIT, 0x20,
                Minidump.Region.MEM_PRIVATE));
        b.exception(100, 0xC0000005, APP + 0x1005, Scenario.crashContext(), 0, 0);
        CrashReport r = analyze(b);
        assertTrue(hasFinding(r, "10,001 open handles"), r.findings().toString());
        assertTrue(hasFinding(r, "A Java program"));
        try (Minidump d = Minidump.open(dir.resolve("t.dmp"))) {
            Symbols s = new Symbols(d, List.of(), null);
            CrashReport.Frame jit = WindowsCrash.frame(d, s, new StackWalk.Frame(0x50000010L, 0, StackWalk.How.CONTEXT),
                    false, true, false);
            assertEquals(FrameKind.GENERATED, jit.kind());
            assertTrue(jit.text().startsWith("Java code compiled while running @ 0x0000000050000010"), jit.text());
            CrashReport.Frame none = WindowsCrash.frame(d, s, new StackWalk.Frame(0x70000000L, 0, StackWalk.How.SCAN),
                    true, false, false);
            assertEquals(FrameKind.UNKNOWN, none.kind());
            assertTrue(none.guessed());
        }
    }

    @Test
    void scannedFramesAreMarkedGuessed() throws Exception {
        DumpBuilder b = Scenario.standard();
        b.exception(300, 0xC0000005, APP + Scenario.D + 0x10, DumpBuilder.amd64(APP + Scenario.D + 0x10,
                Scenario.U + 0x100, 0), 0, 0);
        CrashReport.Thread c = analyze(b).crashedThread();
        assertEquals(2, c.frames().size());
        assertTrue(c.frames().get(1).guessed());
        assertEquals("ntdll!RtlUserThreadStart+0x21", c.frames().get(1).text());
    }

    // ---------------------------------------------------------------- pieces

    private static List<CrashReport.Frame> frames(String... texts) {
        List<CrashReport.Frame> out = new ArrayList<>();
        for (String t : texts) {
            out.add(new CrashReport.Frame(t, null, t.startsWith("app") ? FrameKind.OWN : FrameKind.SYSTEM, false));
        }
        return out;
    }

    @Test
    void threadStatesFromTheFunctionsOnTop() {
        assertEquals("waiting for a lock (critical section)", WindowsCrash.state(frames("ntdll!NtWaitForAlertByThreadId+0x14",
                "ntdll!RtlpWaitOnCriticalSection+0x1a0", "ntdll!RtlEnterCriticalSection+0x40", "app+0x10")));
        assertEquals("waiting for a lock (SRW lock)", WindowsCrash.state(frames("ntdll!NtWaitForAlertByThreadId+0x14",
                "ntdll!RtlAcquireSRWLockExclusive+0x20")));
        assertEquals("waiting for window messages (idle)", WindowsCrash.state(frames("win32u!NtUserGetMessage+0x14",
                "user32!GetMessageW+0x2e", "app+0x10")));
        assertEquals("sleeping", WindowsCrash.state(frames("ntdll!NtDelayExecution+0x14", "kernelbase!SleepEx+0x9e")));
        assertEquals("idle (a thread-pool thread waiting for work)", WindowsCrash.state(frames(
                "ntdll!NtWaitForWorkViaWorkerFactory+0x14", "ntdll!TppWorkerThread+0x2f7")));
        assertEquals("waiting for the network or a device", WindowsCrash.state(frames("ntdll!NtDeviceIoControlFile+0x14",
                "mswsock!WSPRecv+0x2a")));
        assertEquals("reading or writing a file or pipe", WindowsCrash.state(frames("ntdll!NtReadFile+0x14",
                "kernelbase!ReadFile+0x73")));
        assertEquals("waiting for several events or handles", WindowsCrash.state(frames(
                "ntdll!NtWaitForMultipleObjects+0x14")));
        assertEquals("in Windows (NtQueryInformationProcess)", WindowsCrash.state(frames(
                "ntdll!NtQueryInformationProcess+0x14")));
        assertEquals("in Windows code (no names for it)", WindowsCrash.state(frames("ntdll+0x9d844")));
        assertEquals("running", WindowsCrash.state(frames("app+0x10", "app+0x20")));
        assertEquals("unknown", WindowsCrash.state(List.of()));
        assertEquals("total", WindowsCrash.function("app!app::Order::total+0x1f"));
        assertEquals("", WindowsCrash.function("app+0x10"));
    }

    @Test
    void recursionOfAStackOverflow() {
        List<CrashReport.Frame> alt = new ArrayList<>();
        List<CrashReport.Frame> same = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            alt.add(new CrashReport.Frame(i % 2 == 0 ? "app!f+0x10" : "app!g+0x20", null, FrameKind.OWN, false));
            same.add(new CrashReport.Frame("app!walk+0x" + Integer.toHexString(0x10 + i % 1), null, FrameKind.OWN, false));
        }
        assertEquals("app!f → app!g → …", WindowsCrash.recursion(new CrashReport.Thread("1", null, "crashed", true, null,
                alt)));
        assertEquals("app!walk calls itself", WindowsCrash.recursion(new CrashReport.Thread("1", null, "crashed", true,
                null, same)));
        assertNull(WindowsCrash.recursion(new CrashReport.Thread("1", null, "crashed", true, null, alt.subList(0, 5))));
    }

    @Test
    void codesAndTypeNames() {
        assertEquals("Access violation", WindowsCrash.codeName(0xC0000005));
        assertEquals("Exception 0x12345678", WindowsCrash.codeName(0x12345678));
        assertTrue(WindowsCrash.fastFail(7).contains("abort()"));
        assertTrue(WindowsCrash.fastFail(10).contains("Control Flow Guard"));
        assertEquals("fast-fail code 99", WindowsCrash.fastFail(99));
        assertEquals("std::runtime_error", WindowsCrash.typeName(".?AVruntime_error@std@@"));
        assertEquals("app::net::Timeout", WindowsCrash.typeName(".?AUTimeout@net@app@@"));
        assertEquals("int", WindowsCrash.typeName(".H"));
        assertEquals("const char *", WindowsCrash.typeName(".PEBD"));
        assertEquals(".?AV?$vector@H@std@@", WindowsCrash.typeName(".?AV?$vector@H@std@@"), "templates stay as written");
        assertEquals("1.5 KB", WindowsCrash.size(1536));
        assertEquals("12 MB", WindowsCrash.size(12L << 20));
        assertEquals("90 s", WindowsCrash.duration(90));
        assertEquals("3.0 hours", WindowsCrash.duration(3 * 3600));
        assertEquals("2.0 days", WindowsCrash.duration(2 * 86400));
    }

    @Test
    void blameIsTheFirstFrameOfTheProgramsOwn() {
        assertEquals("app+0x30", WindowsCrash.blame(frames("ntdll!RtlRaiseException+0x10", "kernelbase!RaiseException+0x6c",
                "app+0x30", "app+0x40")).text());
        assertNull(WindowsCrash.blame(frames("ntdll!NtWaitForSingleObject+0x14")));
    }
}
