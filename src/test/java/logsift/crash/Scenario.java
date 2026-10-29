package logsift.crash;

import static logsift.crash.PeBuilder.code;

import java.nio.charset.StandardCharsets;

/**
 * A small x64 process for the tests: app.exe and ntdll.dll in memory (with unwind tables and
 * exports), and stacks laid out so the unwind codes, a chained entry, a leaf and scanning are all
 * needed to walk them.
 */
final class Scenario {
    static final long APP = 0x140000000L;
    static final long NTDLL = 0x7FF800000000L;
    static final int IMAGE = 0x5000;

    // app.exe functions (RVAs)
    static final int LEAF = 0x1000;       // 0x1000–0x1010, not in the unwind table
    static final int A = 0x1100;          // alloc 0x28, push rbx
    static final int B = 0x1200;          // push rbp, alloc 0x20, set rbp = rsp + 0x10
    static final int C = 0x1400;          // a fragment of B: saves rsi, chained to B
    static final int D = 0x1600;          // a broken unwind info
    static final int A_RET = 0x1150;
    static final int C_RET = 0x1450;

    // ntdll.dll functions (RVAs)
    static final int THREAD_START = 0x1100; // RtlUserThreadStart
    static final int WAIT = 0x1200;          // NtWaitForSingleObject
    static final int BREAK = 0x1300;         // DbgBreakPoint
    static final int HIDDEN = 0x1500;        // not exported
    static final int START_RET = 0x1121;

    // the crashed thread's stack
    static final long S = 0x10000000L;
    // other stacks
    static final long T = 0x10010000L;
    static final long U = 0x10020000L;
    static final long TEB = 0x20000000L;

    static final byte[] GUID = {0x67, 0x45, 0x23, 0x01, (byte) 0xAB, (byte) 0x89, (byte) 0xEF, (byte) 0xCD, 0x01, 0x23,
            0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};

    static byte[] appImage() {
        return new PeBuilder(IMAGE, true)
                .fn(A, A + 0x100, PeBuilder.unwind(0, 6, 0, 0, code(6, 2, 4), code(1, 0, 3)))
                .fn(B, B + 0x100, PeBuilder.unwind(0, 8, 5, 1, code(8, 3, 0), code(4, 2, 3), code(1, 0, 5)))
                .fn(C, C + 0x100, PeBuilder.unwindChained(4, 0, 0, 0, new int[] {B, B + 0x100, unwindOf(B)},
                        code(0, 4, 6), 2))
                .fn(D, D + 0x100, PeBuilder.badUnwind())
                .callBefore(A_RET).callBefore(C_RET)
                .build();
    }

    /** Where PeBuilder puts a function's unwind info: in the order of their starts. */
    static int unwindOf(int begin) {
        // A, B, C, D are laid out one after the other: A 8 bytes, B 12, C 20 (padded), D 4
        return switch (begin) {
            case A -> PeBuilder.UNWIND_AT;
            case B -> PeBuilder.UNWIND_AT + 8;
            case C -> PeBuilder.UNWIND_AT + 20;
            default -> throw new IllegalArgumentException();
        };
    }

    static byte[] ntdllImage() {
        return new PeBuilder(IMAGE, true)
                .fn(THREAD_START, THREAD_START + 0x80, null)
                .fn(WAIT, WAIT + 0x20, null)
                .fn(BREAK, BREAK + 0x10, null)
                .fn(HIDDEN, HIDDEN + 0x100, null)
                .export("RtlUserThreadStart", THREAD_START)
                .export("NtWaitForSingleObject", WAIT)
                .export("DbgBreakPoint", BREAK)
                .callBefore(START_RET)
                .build();
    }

    /** The crashed thread: a leaf in app, then A, then the fragment C of B, then the thread's start. */
    static DumpBuilder.Block crashStack() {
        return new DumpBuilder.Block(S, 0x1000)
                .p64(S + 0x100, APP + A_RET)     // the leaf's return address
                .p64(S + 0x130, 0x1111)           // rbx, pushed by A
                .p64(S + 0x138, APP + C_RET)      // A's return address
                .p64(S + 0x150, 0x2222)           // rsi, saved by C at frame + 0x10
                .p64(S + 0x180, 0x3333)           // rbp, pushed by B
                .p64(S + 0x188, NTDLL + START_RET); // B's return address
    }

    static byte[] crashContext() {
        return DumpBuilder.amd64(APP + LEAF + 5, S + 0x100, S + 0x170);
    }

    /** A process: the crashed thread 100, two idle threads 200 and 201, a thread 300 whose unwind fails. */
    static DumpBuilder standard() {
        DumpBuilder d = new DumpBuilder();
        d.module(new DumpBuilder.ModuleSpec(APP, IMAGE, 0x5f000000, "C:\\app\\app.exe", 0x00010002, 0x00030004,
                "D:\\build\\app.pdb", GUID, 1));
        d.module(NTDLL, IMAGE, "C:\\Windows\\System32\\ntdll.dll");
        d.mem(APP, appImage());
        d.mem(NTDLL, ntdllImage());
        d.mem(crashStack());
        d.mem(new DumpBuilder.Block(T, 0x1000).p64(T + 0x100, NTDLL + START_RET));
        d.mem(new DumpBuilder.Block(T + 0x1000, 0x1000).p64(T + 0x1100, NTDLL + START_RET));
        d.mem(new DumpBuilder.Block(U, 0x1000)
                .p64(U + 0x108, APP + A + 0x80)       // code, but no call before it
                .p64(U + 0x110, APP)                  // the image's headers
                .p64(U + 0x118, NTDLL + START_RET));  // after a call: a likely return address
        d.mem(peb());
        d.thread(100, TEB, S, 0x1000, crashContext());
        d.thread(200, 0, T, 0x1000, DumpBuilder.amd64(NTDLL + WAIT + 0x14, T + 0x100, 0));
        d.thread(201, 0, T + 0x1000, 0x1000, DumpBuilder.amd64(NTDLL + WAIT + 0x14, T + 0x1100, 0));
        d.thread(300, 0, U, 0x1000, DumpBuilder.amd64(APP + D + 0x10, U + 0x100, 0));
        return d;
    }

    /** The TEB, PEB and process parameters, with the command line and the working folder. */
    static DumpBuilder.Block peb() {
        long peb = TEB + 0x1000;
        long params = TEB + 0x2000;
        long cmd = TEB + 0x3000;
        long cwd = TEB + 0x3100;
        String c = "C:\\app\\app.exe --serve";
        String w = "C:\\work\\";
        return new DumpBuilder.Block(TEB, 0x4000)
                .p64(TEB + 8, S + 0x1000)
                .p64(TEB + 0x60, peb)
                .p64(peb + 0x20, params)
                .p16(params + 0x38, w.length() * 2).p16(params + 0x3a, w.length() * 2).p64(params + 0x40, cwd)
                .p16(params + 0x70, c.length() * 2).p16(params + 0x72, c.length() * 2).p64(params + 0x78, cmd)
                .utf16(cmd, c)
                .utf16(cwd, w);
    }

    static byte[] ascii(String s) {
        return (s + "\0").getBytes(StandardCharsets.ISO_8859_1);
    }

    private Scenario() {}
}
