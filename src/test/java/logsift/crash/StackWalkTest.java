package logsift.crash;

import static logsift.crash.Scenario.APP;
import static logsift.crash.Scenario.NTDLL;
import static logsift.crash.Scenario.S;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stacks rebuilt from registers and stack memory: x64 unwind codes, frame pointers, scanning. */
class StackWalkTest {
    @TempDir
    Path dir;

    private Minidump open(DumpBuilder b) throws Exception {
        return Minidump.open(b.write(dir.resolve("t.dmp")));
    }

    private static List<StackWalk.Frame> walk(Minidump d, int threadIndex) {
        Symbols s = new Symbols(d, List.of(), null);
        Minidump.Thread t = d.threads().get(threadIndex);
        Registers r = Registers.of(d.system().arch(), t.context());
        return new StackWalk(d, s, s::isThreadStart).walk(r, t.stackStart(), t.stackStart() + t.stackSize(), 64);
    }

    @Test
    void x64LeafThenUnwindCodesThenChainedEntryThenThreadStart() throws Exception {
        try (Minidump d = open(Scenario.standard())) {
            List<StackWalk.Frame> f = walk(d, 0);
            assertEquals(4, f.size(), f.toString());
            assertEquals(new StackWalk.Frame(APP + Scenario.LEAF + 5, S + 0x100, StackWalk.How.CONTEXT), f.get(0));
            // the leaf: its return address on top
            assertEquals(new StackWalk.Frame(APP + Scenario.A_RET, S + 0x108, StackWalk.How.UNWIND), f.get(1));
            // A: alloc 0x28, pop rbx, return
            assertEquals(new StackWalk.Frame(APP + Scenario.C_RET, S + 0x140, StackWalk.How.UNWIND), f.get(2));
            // C, chained to B: rbp - 0x10, alloc 0x20, pop rbp, return
            assertEquals(new StackWalk.Frame(NTDLL + Scenario.START_RET, S + 0x190, StackWalk.How.UNWIND), f.get(3));
        }
    }

    @Test
    void unwindCodesRestoreTheSavedRegisters() throws Exception {
        try (Minidump d = open(Scenario.standard())) {
            Symbols s = new Symbols(d, List.of(), null);
            StackWalk w = new StackWalk(d, s, null);
            Registers r = Registers.of(Minidump.Arch.AMD64, Scenario.crashContext());
            assertTrue(w.unwindAmd64(r, true)); // leaf
            assertTrue(w.unwindAmd64(r, false)); // A
            assertEquals(0x1111, r.r[3], "rbx, pushed by A");
            assertTrue(w.unwindAmd64(r, false)); // C → B
            assertEquals(0x2222, r.r[6], "rsi, saved by the fragment");
            assertEquals(0x3333, r.r[5], "rbp, pushed by B");
            assertEquals(NTDLL + Scenario.START_RET, r.pc());
            assertEquals(S + 0x190, r.sp());
        }
    }

    @Test
    void inThePrologOnlyTheCodesThatRanAreUndone() throws Exception {
        try (Minidump d = open(Scenario.standard())) {
            Symbols s = new Symbols(d, List.of(), null);
            StackWalk w = new StackWalk(d, s, null);
            // at A + 3: rbx is pushed (offset 1), the alloc (offset 6) hasn't run yet
            long[] regs = new long[16];
            regs[4] = S + 0x130;
            Registers r = new Registers(Minidump.Arch.AMD64, regs, APP + Scenario.A + 3);
            assertTrue(w.unwindAmd64(r, true));
            assertEquals(0x1111, r.r[3]);
            assertEquals(APP + Scenario.C_RET, r.pc());
            assertEquals(S + 0x140, r.sp());
        }
    }

    @Test
    void idleThreadsWalkToTheirStart() throws Exception {
        try (Minidump d = open(Scenario.standard())) {
            List<StackWalk.Frame> f = walk(d, 1);
            assertEquals(2, f.size());
            assertEquals(NTDLL + Scenario.START_RET, f.get(1).pc());
            assertEquals(StackWalk.How.UNWIND, f.get(1).how());
        }
    }

    @Test
    void brokenUnwindInfoFallsBackToScanningForReturnAddressesAfterCalls() throws Exception {
        try (Minidump d = open(Scenario.standard())) {
            List<StackWalk.Frame> f = walk(d, 3);
            assertEquals(2, f.size(), f.toString());
            assertEquals(StackWalk.How.SCAN, f.get(1).how());
            assertEquals(NTDLL + Scenario.START_RET, f.get(1).pc(), "code without a call before it and an image's "
                    + "headers are passed over");
            assertEquals(Scenario.U + 0x120, f.get(1).sp());
        }
    }

    @Test
    void x86FollowsTheFramePointerChain() throws Exception {
        DumpBuilder b = new DumpBuilder();
        b.arch = 0;
        b.module(0x400000, 0x10000, "c:\\test\\app.exe");
        long st = 0x12f000;
        b.mem(new DumpBuilder.Block(st, 0x1000)
                .p32(0x12f120, 0x12f140).p32(0x12f124, 0x401200)
                .p32(0x12f140, 0).p32(0x12f144, 0x4053ec));
        b.thread(7, 0, st, 0x1000, DumpBuilder.x86(0x40129e, 0x12f100, 0x12f120));
        try (Minidump d = open(b)) {
            assertEquals(Minidump.Arch.X86, d.system().arch());
            List<StackWalk.Frame> f = walk(d, 0);
            assertEquals(3, f.size(), f.toString());
            assertEquals(0x401200, f.get(1).pc());
            assertEquals(StackWalk.How.FRAME_POINTER, f.get(1).how());
            assertEquals(0x12f128, f.get(1).sp());
            assertEquals(0x4053ec, f.get(2).pc());
        }
    }

    @Test
    void noRegistersNoFrames() throws Exception {
        try (Minidump d = open(Scenario.standard())) {
            assertTrue(new StackWalk(d, new Symbols(d, List.of(), null), null).walk(null, 0, 0, 10).isEmpty());
        }
    }

    @Test
    void registersFromContexts() {
        Registers x64 = Registers.of(Minidump.Arch.AMD64, DumpBuilder.amd64(0x1234, 0x5000, 0x6000));
        assertNotNull(x64);
        assertEquals(0x1234, x64.pc());
        assertEquals(0x5000, x64.sp());
        assertEquals(0x6000, x64.fp());
        assertEquals(0x1234L, x64.named().get("rip"));
        Registers x86 = Registers.of(Minidump.Arch.X86, DumpBuilder.x86(0x401000, 0x12f000, 0x12f010));
        assertNotNull(x86);
        assertEquals(0x401000, x86.pc());
        assertEquals(0x12f010, x86.fp());
        assertEquals(0x12f000L, x86.named().get("esp"));
        byte[] arm = new byte[0x390];
        LeBytes.put64(arm, 0xf0, 0x7000); // fp
        LeBytes.put64(arm, 0x100, 0x8000); // sp
        LeBytes.put64(arm, 0x108, 0x9000); // pc
        Registers a = Registers.of(Minidump.Arch.ARM64, arm);
        assertNotNull(a);
        assertEquals(0x9000, a.pc());
        assertEquals(0x8000, a.sp());
        assertEquals(0x7000, a.fp());
        assertEquals(null, Registers.of(Minidump.Arch.AMD64, new byte[16]), "too short");
        assertEquals(null, Registers.of(Minidump.Arch.OTHER, new byte[0x1000]));
        assertFalse(x64.named().isEmpty());
    }
}
