package logsift.crash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import logsift.crash.CrashReport.Fact;
import logsift.crash.CrashReport.Finding;
import logsift.crash.CrashReport.FrameKind;
import logsift.crash.CrashReport.Severity;

/**
 * Explains a Windows dump: what the exception means, the code to blame (the first frame that is
 * the program's own, not Windows'), what every thread was doing, and what else stands out
 * (memory, handles, unloaded DLLs, a Java or .NET process).
 */
public final class WindowsCrash {
    private WindowsCrash() {}

    /** Frames walked per thread; the crashed thread gets more (a stack overflow is deep). */
    static final int FRAMES = 64;
    static final int CRASH_FRAMES = 512;

    /** Windows and the C and C++ runtimes: a crash reported there is usually caused by the caller. */
    private static final Set<String> RUNTIME = Set.of("ntdll", "kernelbase", "kernel32", "ucrtbase", "ucrtbased",
            "msvcrt", "vcruntime140", "vcruntime140_1", "vcruntime140d", "msvcp140", "msvcp140d", "combase", "rpcrt4",
            "user32", "win32u", "gdi32", "gdi32full", "sechost", "advapi32", "ole32", "oleaut32", "ws2_32", "mswsock",
            "wow64", "wow64win", "wow64cpu", "verifier", "apphelp");

    public static CrashReport analyze(Minidump d, Symbols s, BooleanSupplier stop) {
        Minidump.ExceptionInfo ex = d.exception();
        StackWalk walker = new StackWalk(d, s, s::isThreadStart);
        List<CrashReport.Thread> threads = new ArrayList<>();
        List<CrashReport.Finding> findings = new ArrayList<>();
        CrashReport.Thread crashed = null;
        List<StackWalk.Frame> crashedFrames = List.of();
        boolean jvm = hasModule(d, "jvm");
        boolean clr = hasModule(d, "coreclr") || hasModule(d, "clr") || hasModule(d, "mscorwks");
        for (Minidump.Thread t : d.threads()) {
            if (stop != null && stop.getAsBoolean()) {
                break;
            }
            boolean isCrash = ex != null && ex.threadId() == t.id();
            byte[] ctx = isCrash && ex.context().length > 0 ? ex.context() : t.context();
            Registers r = Registers.of(d.system().arch(), ctx);
            long high = stackHigh(d, t);
            List<StackWalk.Frame> fs = walker.walk(r, t.stackStart(), high, isCrash ? CRASH_FRAMES : FRAMES);
            List<CrashReport.Frame> frames = new ArrayList<>();
            for (int i = 0; i < fs.size(); i++) {
                frames.add(frame(d, s, fs.get(i), i > 0, jvm, clr));
            }
            String state = isCrash ? "crashed" : state(frames);
            Minidump.ThreadTimes tt = d.threadTimes(t.id());
            String cpu = tt == null ? null : cpu(tt.userTime() + tt.kernelTime());
            CrashReport.Thread th = new CrashReport.Thread(String.valueOf(t.id()), d.threadName(t.id()), state, isCrash,
                    cpu, frames);
            if (isCrash) {
                crashed = th;
                crashedFrames = fs;
                threads.add(0, th);
            } else {
                threads.add(th);
            }
        }

        String title;
        String where = null;
        String summary;
        CrashReport.Frame blame = crashed == null ? null : blame(crashed.frames());
        if (ex == null) {
            title = "No crash: the dump was taken while the program ran";
            summary = "This dump has no exception: someone (Task Manager, a debugger, a monitoring tool) wrote it "
                    + "while the program was running, usually because it hung or was slow. Look at what the threads "
                    + "were doing: the first thread is usually the program's main (or window) thread.";
            hang(threads, findings);
        } else {
            Explained e = explain(d, s, ex, crashed, crashedFrames);
            title = e.title;
            summary = e.summary;
            if (blame != null) {
                where = "in " + blame.text() + (blame.source() == null ? "" : " (" + blame.source() + ")");
                CrashReport.Frame top = crashed.frames().isEmpty() ? null : crashed.frames().get(0);
                if (top != null && top != blame && top.kind() == FrameKind.SYSTEM) {
                    where += ", which called " + top.text();
                }
            } else if (crashed != null && !crashed.frames().isEmpty()) {
                CrashReport.Frame top = crashed.frames().get(0);
                where = "in " + top.text() + (top.source() == null ? "" : " (" + top.source() + ")");
            }
            findings.addAll(e.findings);
            if (title.startsWith("No crash") && crashed != null) {
                CrashReport.Thread c = crashed;
                crashed = new CrashReport.Thread(c.id(), c.name(), "stopped here by the debugger", true, c.cpu(), c.frames());
                threads.set(0, crashed);
            }
            if (blame != null && blame.kind() == FrameKind.OWN && !e.handled) {
                findings.add(new Finding(Severity.ERROR, "The code to look at: " + blame.text(),
                        blame.source() != null ? "Source: " + blame.source() + "." : "The first frame of the crashed "
                                + "thread that is the program's own code, not Windows' or the C runtime's.",
                        blame.source() == null ? "Put the program's .pdb files next to the dump (or add their folder "
                                + "in Settings → Crash Dumps) to see function names and source lines." : null));
            }
        }
        others(d, s, threads, findings, jvm, clr);

        List<Fact> facts = facts(d);
        List<CrashReport.Module> modules = modules(d, s, crashed);
        List<CrashReport.Section> sections = new ArrayList<>();
        if (!d.comments().isEmpty()) {
            sections.add(new CrashReport.Section("Comment in the dump", String.join("\n\n", d.comments())));
        }
        if (crashed != null && ex != null) {
            Registers r = Registers.of(d.system().arch(), ex.context().length > 0 ? ex.context() : null);
            if (r != null) {
                StringBuilder b = new StringBuilder();
                int i = 0;
                for (Map.Entry<String, Long> e : r.named().entrySet()) {
                    b.append(String.format(Locale.ROOT, "%-4s %s", e.getKey(), hex(e.getValue(), d)))
                            .append(++i % 3 == 0 ? "\n" : "   ");
                }
                sections.add(new CrashReport.Section("Registers of the crashed thread", b.toString().strip()));
            }
        }
        return new CrashReport(CrashReport.Kind.WINDOWS_DUMP, title, where, summary, findings, facts, threads, modules,
                sections);
    }

    private static boolean hasModule(Minidump d, String shortName) {
        for (Minidump.Module m : d.modules()) {
            if (Symbols.shortName(m).equalsIgnoreCase(shortName)) {
                return true;
            }
        }
        return false;
    }

    /** The top of a thread's stack: from its TEB when the dump holds it, else what the dump saved. */
    private static long stackHigh(Minidump d, Minidump.Thread t) {
        long saved = t.stackStart() + t.stackSize();
        try {
            if (t.teb() != 0) {
                Long base = d.pointer(t.teb() + d.system().arch().pointerSize()); // NT_TIB.StackBase
                if (base != null && Long.compareUnsigned(base, saved) > 0
                        && Long.compareUnsigned(base - t.stackStart(), 256L << 20) < 0) {
                    return base;
                }
            }
        } catch (IOException e) {
            // what was saved, then
        }
        return saved;
    }

    static CrashReport.Frame frame(Minidump d, Symbols s, StackWalk.Frame f, boolean returnAddress, boolean jvm,
                                   boolean clr) {
        Symbols.Name n = s.name(f.pc(), returnAddress);
        FrameKind kind;
        String text;
        if (n.module() == null) {
            Minidump.Region reg = d.regionAt(f.pc());
            boolean generated = reg != null && reg.executable() && reg.type() == Minidump.Region.MEM_PRIVATE;
            if (generated || jvm || clr) {
                kind = FrameKind.GENERATED;
                text = (jvm ? "Java code compiled while running" : clr ? ".NET code compiled while running"
                        : "code generated while running") + " @ " + hex(f.pc(), d);
            } else {
                kind = FrameKind.UNKNOWN;
                text = "? " + hex(f.pc(), d) + " (in no module)";
            }
        } else {
            Minidump.Module m = d.moduleAt(f.pc());
            boolean system = m == null || m.isSystem() || RUNTIME.contains(Symbols.shortName(m).toLowerCase(Locale.ROOT))
                    || Symbols.shortName(m).equalsIgnoreCase("jvm") || Symbols.shortName(m).equalsIgnoreCase("coreclr")
                    || Symbols.shortName(m).equalsIgnoreCase("clr");
            kind = system ? FrameKind.SYSTEM : FrameKind.OWN;
            text = n.text();
        }
        return new CrashReport.Frame(text, n.where(), kind, f.how() == StackWalk.How.SCAN);
    }

    static String hex(long v, Minidump d) {
        return d.system().arch().pointerSize() == 4 ? String.format("0x%08x", v) : String.format("0x%016x", v);
    }

    private static String cpu(long hundredNanos) {
        double s = hundredNanos / 1e7;
        return s < 1 ? String.format(Locale.ROOT, "%.0f ms", s * 1000) : s < 120 ? String.format(Locale.ROOT, "%.1f s", s)
                : String.format(Locale.ROOT, "%.0f min", s / 60);
    }

    /** The first frame that is the program's own code; else the first that isn't Windows'. */
    static CrashReport.Frame blame(List<CrashReport.Frame> frames) {
        for (CrashReport.Frame f : frames) {
            if (f.kind() == FrameKind.OWN) {
                return f;
            }
            if (f.kind() == FrameKind.GENERATED || f.kind() == FrameKind.JAVA) {
                return f;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- thread states

    /** What a thread was doing, from the functions on top of its stack. */
    static String state(List<CrashReport.Frame> frames) {
        String top = "";
        for (int i = 0; i < frames.size() && i < 8; i++) {
            top += " " + function(frames.get(i).text());
        }
        if (frames.isEmpty()) {
            return "unknown";
        }
        if (has(top, "RtlpWaitOnCriticalSection", "RtlEnterCriticalSection", "RtlpEnterCriticalSectionContended")) {
            return "waiting for a lock (critical section)";
        }
        if (has(top, "RtlAcquireSRWLockExclusive", "RtlAcquireSRWLockShared", "RtlpAcquireSRWLock", "AcquireSRWLock")) {
            return "waiting for a lock (SRW lock)";
        }
        if (has(top, "SleepConditionVariable", "RtlSleepConditionVariable")) {
            return "waiting on a condition variable";
        }
        if (has(top, "RtlWaitOnAddress", "WaitOnAddress", "RtlpWaitOnAddress")) {
            return "waiting on an address (a lock or an event)";
        }
        if (has(top, "NtWaitForWorkViaWorkerFactory", "ZwWaitForWorkViaWorkerFactory")) {
            return "idle (a thread-pool thread waiting for work)";
        }
        if (has(top, "NtRemoveIoCompletion", "ZwRemoveIoCompletion", "GetQueuedCompletionStatus")) {
            return "idle (waiting for I/O completion work)";
        }
        if (has(top, "NtUserGetMessage", "GetMessageW", "GetMessageA", "NtUserMsgWaitForMultipleObjectsEx",
                "MsgWaitForMultipleObjects", "NtUserWaitMessage", "WaitMessage")) {
            return "waiting for window messages (idle)";
        }
        if (has(top, "NtDelayExecution", "ZwDelayExecution", "SleepEx", "Sleep")) {
            return "sleeping";
        }
        if (has(top, "NtAlpcSendWaitReceivePort", "ZwAlpcSendWaitReceivePort", "NdrClientCall", "NtRequestWaitReplyPort")) {
            return "waiting for another process (RPC or COM)";
        }
        if (has(top, "NtDeviceIoControlFile", "ZwDeviceIoControlFile", "WSPRecv", "WSPSelect", "recv", "select",
                "WSARecv", "connect")) {
            return "waiting for the network or a device";
        }
        if (has(top, "NtReadFile", "ZwReadFile", "NtWriteFile", "ZwWriteFile", "ReadFile", "WriteFile",
                "NtFlushBuffersFile")) {
            return "reading or writing a file or pipe";
        }
        if (has(top, "NtWaitForMultipleObjects", "ZwWaitForMultipleObjects", "WaitForMultipleObjects")) {
            return "waiting for several events or handles";
        }
        if (has(top, "NtWaitForSingleObject", "ZwWaitForSingleObject", "WaitForSingleObject", "NtWaitForAlertByThreadId",
                "ZwWaitForAlertByThreadId")) {
            return "waiting for an event or handle";
        }
        if (has(top, "NtSuspendThread", "ZwSuspendThread")) {
            return "suspended";
        }
        String f = function(frames.get(0).text());
        if (f.startsWith("Nt") || f.startsWith("Zw")) {
            return "in Windows (" + f + ")";
        }
        if (frames.get(0).kind() == FrameKind.SYSTEM && f.isEmpty()) {
            return "in Windows code (no names for it)";
        }
        return "running";
    }

    private static boolean has(String top, String... names) {
        for (String n : names) {
            if (top.contains(" " + n + " ") || top.endsWith(" " + n)) {
                return true;
            }
        }
        return false;
    }

    /** "ntdll!NtWaitForSingleObject+0x14" → "NtWaitForSingleObject". */
    static String function(String frame) {
        int bang = frame.indexOf('!');
        if (bang < 0) {
            return "";
        }
        String f = frame.substring(bang + 1);
        int plus = f.lastIndexOf("+0x");
        f = plus >= 0 ? f.substring(0, plus) : f;
        int colons = f.lastIndexOf("::");
        return colons >= 0 ? f.substring(colons + 2) : f;
    }

    private static void hang(List<CrashReport.Thread> threads, List<Finding> findings) {
        if (threads.isEmpty()) {
            return;
        }
        CrashReport.Thread main = threads.get(0);
        findings.add(new Finding(main.state().startsWith("waiting for a lock") ? Severity.ERROR : Severity.INFO,
                "The first thread is " + main.state(),
                main.frames().isEmpty() ? null : "At " + firstOwnOrTop(main.frames()) + ".",
                main.state().startsWith("waiting for a lock")
                        ? "Find the thread that holds the lock (often one also waiting for a lock: a deadlock)." : null));
        int locks = 0;
        for (CrashReport.Thread t : threads) {
            if (t.state().startsWith("waiting for a lock")) {
                locks++;
            }
        }
        if (locks >= 2) {
            findings.add(new Finding(Severity.WARN, locks + " threads are waiting for a lock",
                    "Threads that wait for each other's locks never go on (a deadlock).",
                    "Compare their stacks (Threads): two threads taking the same two locks in the opposite order is "
                            + "the usual cause."));
        }
    }

    private static String firstOwnOrTop(List<CrashReport.Frame> frames) {
        CrashReport.Frame b = blame(frames);
        return (b != null ? b : frames.get(0)).text();
    }

    // ---------------------------------------------------------------- exceptions

    private record Explained(String title, String summary, List<Finding> findings, boolean handled) {}

    static String codeName(int code) {
        return switch (code) {
            case 0xC0000005 -> "Access violation";
            case 0xC00000FD -> "Stack overflow";
            case 0xC0000409 -> "Fast fail (security check or abort)";
            case 0xC0000374 -> "Heap corruption";
            case 0xE06D7363 -> "C++ exception";
            case 0xE0434352, 0xE0434F4D -> ".NET exception";
            case 0x80000003 -> "Breakpoint";
            case 0x4000001F -> "Breakpoint (32-bit program)";
            case 0x80000004 -> "Single step";
            case 0xC0000094 -> "Integer division by zero";
            case 0xC0000095 -> "Integer overflow";
            case 0xC000008E -> "Floating-point division by zero";
            case 0xC0000090 -> "Floating-point invalid operation";
            case 0xC000001D -> "Illegal instruction";
            case 0xC0000096 -> "Privileged instruction";
            case 0xC0000006 -> "In-page error (the file behind memory couldn't be read)";
            case 0xC0000017 -> "Out of memory";
            case 0xC000000D -> "Invalid parameter";
            case 0xC0000008 -> "Invalid handle";
            case 0xC0000135 -> "DLL not found";
            case 0xC0000142 -> "DLL failed to initialize";
            case 0xC0000420 -> "Assertion failure";
            case 0xC0000602 -> "Fail fast";
            case 0xC000041D -> "Exception in a user callback";
            case 0x40010005 -> "Ctrl+C";
            case 0xC000013A -> "Ctrl+C (the program was stopped)";
            case 0xC0000026 -> "Invalid disposition";
            case 0xC0000025 -> "Exception that couldn't be continued";
            default -> String.format("Exception 0x%08X", code);
        };
    }

    static String fastFail(long code) {
        return switch ((int) code) {
            case 0 -> "a /GS stack check failed (old style)";
            case 1 -> "a virtual function table check failed";
            case 2 -> "a stack buffer overrun: a function wrote past the end of a local array";
            case 3 -> "a damaged linked list (memory overwritten)";
            case 4 -> "the stack was found in a bad state";
            case 5 -> "a C runtime function got an invalid argument";
            case 6 -> "the stack-check cookie wasn't set up";
            case 7 -> "the program called abort() (or a fatal error handler)";
            case 8 -> "a range check failed";
            case 9 -> "an unsafe registry access";
            case 10 -> "Control Flow Guard stopped a call through a damaged function pointer";
            case 11 -> "a write check failed";
            case 12 -> "an invalid fiber switch";
            case 13 -> "an invalid thread context";
            case 14 -> "a reference count went wrong (freed too often)";
            default -> "fast-fail code " + code;
        };
    }

    private static Explained explain(Minidump d, Symbols s, Minidump.ExceptionInfo ex, CrashReport.Thread crashed,
                                     List<StackWalk.Frame> frames) {
        List<Finding> f = new ArrayList<>();
        long[] p = ex.parameters();
        int code = ex.code();
        String name = codeName(code);
        switch (code) {
            case 0xC0000005 -> {
                long kind = p.length > 0 ? p[0] : -1;
                long addr = p.length > 1 ? p[1] : 0;
                String verb = kind == 0 ? "reading" : kind == 1 ? "writing" : kind == 8 ? "running code at" : "using";
                String what = address(d, addr, kind == 8);
                String title = name + ": " + verb + " address " + hex(addr, d) + (what.isEmpty() ? "" : ", " + what);
                String summary = "The program " + (kind == 8 ? "jumped to" : kind == 1 ? "wrote to" : "read from")
                        + " memory it may not use, and Windows stopped it. " + accessAdvice(d, addr, kind);
                return new Explained(title, summary, f, false);
            }
            case 0xC00000FD -> {
                String rec = recursion(crashed);
                f.add(new Finding(Severity.ERROR, rec == null ? "The stack ran out" : "Endless recursion: " + rec,
                        "Each thread has a fixed stack (1 MB by default). It ran out, almost always because a function "
                                + "calls itself without end, or a very large local array.",
                        "Look at the repeating frames under Threads; check the recursion's end condition."));
                return new Explained(name, "The thread used up its stack.", f, false);
            }
            case 0xC0000409 -> {
                String why = p.length > 0 ? fastFail(p[0]) : "a security check failed";
                f.add(new Finding(Severity.ERROR, "Stopped on purpose: " + why,
                        "The program (or the C runtime) found something wrong and ended itself at once, without "
                                + "running handlers, so damage couldn't spread.",
                        p.length > 0 && p[0] == 2 ? "Look for a buffer overflow in the function shown (strcpy, "
                                + "sprintf, an index past the end of an array)."
                                : p.length > 0 && p[0] == 7 ? "Look at what called abort(): an unhandled C++ "
                                + "exception, a failed assert, or std::terminate." : null));
                return new Explained(name + ": " + why, "The program stopped itself: " + why + ".", f, false);
            }
            case 0xC0000374 -> {
                f.add(new Finding(Severity.ERROR, "The heap was damaged earlier",
                        "Windows found its heap damaged: memory was written past the end of an allocation, freed "
                                + "twice, or used after it was freed. The damage happened before this point, often "
                                + "in other code.",
                        "Run the program with full page heap (gflags /p /enable program.exe /full, from the Windows "
                                + "debugging tools) or Application Verifier: it stops at the bad write."));
                return new Explained(name, "Windows found the heap damaged and stopped the program.", f, false);
            }
            case 0xE06D7363 -> {
                String[] cpp = cppException(d, p);
                String type = cpp[0];
                String msg = cpp[1];
                String title = "Unhandled C++ exception" + (type == null ? "" : ": " + type)
                        + (msg == null || msg.isBlank() ? "" : " (\"" + cut(msg, 160) + "\")");
                f.add(new Finding(Severity.ERROR, title,
                        "A C++ exception was thrown and nothing caught it, so the program ended."
                                + (type == null ? " Its type isn't in the dump (a full dump has it)." : ""),
                        "Look at the frame that threw it (under the throw functions on the crashed thread) and add "
                                + "a handler, or fix what makes it throw."));
                return new Explained(title, "A C++ exception wasn't caught.", f, true);
            }
            case 0xE0434352, 0xE0434F4D -> {
                f.add(new Finding(Severity.ERROR, "An unhandled .NET exception",
                        "The .NET runtime ended the program on an exception nothing caught. Its type, message and "
                                + "managed stack are kept by the runtime and need a .NET-aware debugger to read.",
                        "Open the dump in Visual Studio (Debug with Managed Only) to see the .NET exception and "
                                + "stack; the event log (Application, source .NET Runtime) often has them too."));
                return new Explained(name, "A .NET exception wasn't caught.", f, true);
            }
            case 0x80000003, 0x4000001F -> {
                String top = crashed == null || crashed.frames().isEmpty() ? "" : crashed.frames().get(0).text();
                boolean debugger = top.contains("DbgBreakPoint") || top.contains("DbgUiRemoteBreakin")
                        || top.contains("LdrpDoDebuggerBreak");
                if (debugger) {
                    f.add(new Finding(Severity.INFO, "Not a crash: a debugger stopped the program",
                            "A debugger (or a dump tool) broke into the program to write this dump. The threads show "
                                    + "what it was doing at that moment.", null));
                    return new Explained("No crash: a debugger stopped the program",
                            "The dump was written while a debugger held the program. Look at the threads.", f, true);
                }
                f.add(new Finding(Severity.ERROR, "A breakpoint was hit with no debugger",
                        "The code ran a breakpoint instruction (__debugbreak, DebugBreak, an assert in a debug "
                                + "build), and no debugger was attached.", null));
                return new Explained(name, "The program hit a breakpoint and no debugger caught it.", f, false);
            }
            case 0xC000000D -> {
                f.add(new Finding(Severity.ERROR, "A function got an invalid argument",
                        "Usually the C runtime: a function such as strcpy_s, fopen or printf got a null pointer, a "
                                + "bad file handle or a bad format string, and the runtime's invalid-parameter "
                                + "handler ended the program.",
                        "Look at the program's own frame on the crashed thread: what it passed to the runtime."));
                return new Explained("Invalid argument to a function (usually the C runtime)",
                        "A function was called with an invalid argument, and the program was ended.", f, false);
            }
            case 0xC0000006 -> {
                f.add(new Finding(Severity.ERROR, "A file behind memory couldn't be read",
                        "Code or data was mapped from a file (the program itself, a DLL, a mapped file) and reading "
                                + "it failed: a network share or USB disk went away, or the disk failed.",
                        "Run the program from a local disk; check the disk and the network."));
                return new Explained(name, "Windows couldn't read a page of memory from its file.", f, false);
            }
            case 0xC0000017 -> {
                f.add(new Finding(Severity.ERROR, "Out of memory", "Windows couldn't give the program the memory "
                        + "it asked for.", "See Memory below: for a 32-bit program, the 2 to 4 GB of addresses "
                        + "may be used up."));
                return new Explained(name, "The program ran out of memory.", f, false);
            }
            default -> {
                return new Explained(name + (ex.address() != 0 ? " at " + hex(ex.address(), d) : ""),
                        "The program stopped on " + name.toLowerCase(Locale.ROOT) + ".", f, false);
            }
        }
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** What an address is: "a null pointer", "in an unloaded DLL", … ("" if nothing to say). */
    static String address(Minidump d, long addr, boolean execute) {
        if (Long.compareUnsigned(addr, 0x10000) < 0) {
            return addr == 0 ? "a null pointer" : "a null pointer (a field at offset " + hex16(addr) + " of a null object)";
        }
        if (d.system().arch() == Minidump.Arch.AMD64 && addr == -1L) {
            return "a damaged pointer (not a valid address on x64)";
        }
        Minidump.UnloadedModule u = d.unloadedAt(addr);
        if (u != null) {
            return "in " + u.name() + ", which had been unloaded";
        }
        Minidump.Module m = d.moduleAt(addr);
        if (m != null) {
            return execute ? "in " + m.name() + " (code that may not run)" : "in " + m.name() + " (read-only data or code)";
        }
        Minidump.Region r = d.regionAt(addr);
        if (r != null) {
            if (r.state() == Minidump.Region.MEM_FREE) {
                return "memory that isn't allocated (freed, or never was)";
            }
            if (r.state() == Minidump.Region.MEM_RESERVE) {
                return "memory reserved but not committed";
            }
            if ((r.protect() & 0x100) != 0) {
                return "a guard page";
            }
        }
        if (execute) {
            return "where there is no code";
        }
        return "";
    }

    private static String hex16(long v) {
        return "0x" + Long.toHexString(v);
    }

    private static String accessAdvice(Minidump d, long addr, long kind) {
        if (Long.compareUnsigned(addr, 0x10000) < 0) {
            return "The address is (near) zero: a pointer that was null was used. Look at the code to blame below "
                    + "for a pointer or object that wasn't set.";
        }
        if (d.unloadedAt(addr) != null) {
            return "Code called into a DLL after it was unloaded: a callback, thread or hook it left behind.";
        }
        if (kind == 8) {
            return "Code jumped to an address with no code: a damaged function pointer, an object used after it "
                    + "was freed (a damaged vtable), or a stack overwrite.";
        }
        Minidump.Region r = d.regionAt(addr);
        if (r != null && r.state() == Minidump.Region.MEM_FREE) {
            return "That memory isn't allocated: likely an object used after it was freed, or a damaged pointer.";
        }
        return "Likely a damaged or stale pointer, or an object used after it was freed.";
    }

    /** The repeating part of a stack overflow, as "a → b → a"; null if none. */
    static String recursion(CrashReport.Thread t) {
        if (t == null || t.frames().size() < 20) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (CrashReport.Frame f : t.frames()) {
            names.add(f.text().replaceAll("\\+0x[0-9a-f]+$", ""));
        }
        for (int period = 1; period <= 8; period++) {
            int start = names.size() / 2;
            boolean repeats = true;
            for (int i = start; i + period < names.size() && i < start + 4 * period; i++) {
                if (!names.get(i).equals(names.get(i + period))) {
                    repeats = false;
                    break;
                }
            }
            if (repeats) {
                List<String> loop = names.subList(start, start + period);
                return loop.size() == 1 ? loop.get(0) + " calls itself" : String.join(" → ", loop) + " → …";
            }
        }
        return null;
    }

    /**
     * The type and message of a C++ exception from its throw information: {type, message},
     * either null when the dump doesn't hold them.
     */
    static String[] cppException(Minidump d, long[] p) {
        String[] out = new String[2];
        if (p.length < 3 || (p[0] != 0x19930520L && p[0] != 0x19930521L && p[0] != 0x19930522L)) {
            return out;
        }
        try {
            int ptr = d.system().arch().pointerSize();
            long base = ptr == 8 && p.length >= 4 ? p[3] : 0;
            long throwInfo = p[2];
            byte[] ti = d.memory(throwInfo, 16);
            if (ti == null || ti.length < 16) {
                return out;
            }
            long cta = Integer.toUnsignedLong(le(ti).getInt(12)) + base;
            byte[] ca = d.memory(cta, 8);
            if (ca == null || ca.length < 8) {
                return out;
            }
            int n = le(ca).getInt(0);
            boolean isStd = false;
            String first = null;
            for (int i = 0; i < n && i < 16; i++) {
                byte[] e = d.memory(cta + 4 + i * 4L, 4);
                if (e == null || e.length < 4) {
                    break;
                }
                long ct = Integer.toUnsignedLong(le(e).getInt(0)) + base;
                byte[] c = d.memory(ct, 8);
                if (c == null || c.length < 8) {
                    break;
                }
                long td = Integer.toUnsignedLong(le(c).getInt(4)) + base;
                byte[] nm = d.memory(td + 2L * ptr, 256);
                if (nm == null) {
                    break;
                }
                int end = 0;
                while (end < nm.length && nm[end] != 0) {
                    end++;
                }
                String type = typeName(new String(nm, 0, end, StandardCharsets.ISO_8859_1));
                if (first == null) {
                    first = type;
                }
                if ("std::exception".equals(type)) {
                    isStd = true;
                }
            }
            out[0] = first;
            if (isStd && p[1] != 0) {
                Long what = d.pointer(p[1] + ptr); // std::exception: vtable, then the message
                if (what != null && what != 0) {
                    byte[] m = d.memory(what, 512);
                    if (m != null) {
                        int end = 0;
                        while (end < m.length && m[end] != 0) {
                            end++;
                        }
                        out[1] = new String(m, 0, end, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // what was found so far
        }
        return out;
    }

    private static ByteBuffer le(byte[] a) {
        return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** ".?AVruntime_error@std@@" → "std::runtime_error"; ".H" → "int". */
    static String typeName(String decorated) {
        String s = decorated;
        if (s.startsWith(".?AV") || s.startsWith(".?AU")) {
            s = s.substring(4);
            int end = s.indexOf("@@");
            if (end > 0 && !s.contains("?$")) {
                String[] parts = s.substring(0, end).split("@");
                StringBuilder b = new StringBuilder();
                for (int i = parts.length - 1; i >= 0; i--) {
                    b.append(b.length() > 0 ? "::" : "").append(parts[i]);
                }
                return b.toString();
            }
            return decorated;
        }
        return switch (s) {
            case ".H" -> "int";
            case ".I" -> "unsigned int";
            case ".J" -> "long";
            case ".K" -> "unsigned long";
            case ".D" -> "char";
            case ".N" -> "double";
            case ".M" -> "float";
            case ".PEAD", ".PAD" -> "char *";
            case ".PEBD", ".PBD" -> "const char *";
            case ".PEA_W", ".PA_W" -> "wchar_t *";
            case ".PEB_W", ".PB_W" -> "const wchar_t *";
            default -> decorated;
        };
    }

    // ---------------------------------------------------------------- the rest of the process

    private static void others(Minidump d, Symbols s, List<CrashReport.Thread> threads, List<Finding> findings,
                               boolean jvm, boolean clr) {
        if (jvm) {
            Path dir = d.path().toAbsolutePath().getParent();
            Path hsErr = dir == null || d.processId() < 0 ? null : dir.resolve("hs_err_pid" + d.processId() + ".log");
            boolean found = hsErr != null && Files.isRegularFile(hsErr);
            findings.add(new Finding(Severity.INFO, "A Java program",
                    "The JVM (jvm.dll) is loaded. Java frames show as code compiled while running; the JVM's own "
                            + "crash report explains the Java side." + (found ? " It is next to the dump: " + hsErr + "." : ""),
                    found ? "Open " + hsErr.getFileName() + " in Logsift." : "Look for hs_err_pid" + (d.processId() < 0 ? "<pid>"
                            : String.valueOf(d.processId())) + ".log in the program's working folder."));
        }
        if (clr) {
            findings.add(new Finding(Severity.INFO, "A .NET program",
                    "The .NET runtime is loaded. Managed frames show as code compiled while running.",
                    "For the managed stacks and objects, open the dump in Visual Studio."));
        }
        if (hasModule(d, "wow64") && d.system().arch() == Minidump.Arch.AMD64) {
            findings.add(new Finding(Severity.WARN, "A 32-bit program in a 64-bit dump",
                    "The dump was written by a 64-bit tool, so the stacks show Windows' 32-bit layer (WOW64) "
                            + "rather than the program's code.",
                    "Write the dump with the 32-bit Task Manager (C:\\Windows\\SysWOW64\\taskmgr.exe) or a 32-bit "
                            + "dump tool."));
        }
        if (!d.unloadedModules().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Minidump.UnloadedModule u : d.unloadedModules()) {
                if (!names.contains(u.name()) && names.size() < 6) {
                    names.add(u.name());
                }
            }
            int n = d.unloadedModules().size();
            findings.add(new Finding(Severity.INFO, n + (n == 1 ? " DLL was" : " DLLs were") + " unloaded before the dump",
                    String.join(", ", names) + (d.unloadedModules().size() > names.size() ? ", …" : "") + ".", null));
        }
        if (d.exception() != null) {
            List<String> waiting = new ArrayList<>();
            for (CrashReport.Thread t : threads) {
                if (!t.crashed() && t.state().startsWith("waiting for a lock")) {
                    waiting.add(t.name() == null ? t.id() : t.name());
                }
            }
            if (waiting.size() >= 2) {
                findings.add(new Finding(Severity.INFO, waiting.size() + " threads were waiting for a lock",
                        String.join(", ", waiting.subList(0, Math.min(8, waiting.size())))
                                + (waiting.size() > 8 ? ", …" : "") + ". If the crashed thread held it, they would "
                                + "have waited for ever; if not, the program may also have been stuck.", null));
            }
        }
        if (threads.size() >= 1000) {
            findings.add(new Finding(Severity.WARN, threads.size() + " threads",
                    "That many threads usually means threads are started and never end (a thread leak).",
                    "Group them by stack (Threads) to see what most of them are doing."));
        }
        if (d.handleTotal() >= 10_000) {
            Minidump.HandleCount top = d.handles().isEmpty() ? null : d.handles().get(0);
            findings.add(new Finding(Severity.WARN, String.format(Locale.ROOT, "%,d open handles", d.handleTotal()),
                    top == null ? null : String.format(Locale.ROOT, "Most are %s (%,d).", top.type(), top.count()),
                    "Handles that keep growing are a leak: something opens files, events or threads and never "
                            + "closes them."));
        }
        Memory mem = memory(d);
        if (mem != null && mem.limit > 0 && mem.used > mem.limit * 0.85) {
            findings.add(new Finding(Severity.WARN, "The 32-bit address space is nearly full",
                    String.format(Locale.ROOT, "%s of %s used; largest free block %s.", size(mem.used), size(mem.limit),
                            size(mem.largestFree)),
                    "A 32-bit program can't use more memory than this: make it 64-bit, or find what holds the memory."));
        }
        if (!d.isFull()) {
            findings.add(new Finding(Severity.INFO, "A minidump: most of the memory isn't in it",
                    "Stacks may be shorter and some details (C++ exception messages, the command line) missing.",
                    "For more, write a full dump (Task Manager → Create dump file, or Windows Error Reporting with "
                            + "DumpType 2)."));
        }
    }

    // ---------------------------------------------------------------- facts

    private record Memory(long committed, long image, long mapped, long priv, long reserved, long used, long limit,
                          long largestFree) {}

    private static Memory memory(Minidump d) {
        if (d.regions().isEmpty()) {
            return null;
        }
        long committed = 0;
        long image = 0;
        long mapped = 0;
        long priv = 0;
        long reserved = 0;
        long largestFree = 0;
        long top = 0;
        for (Minidump.Region r : d.regions()) {
            if (r.state() == Minidump.Region.MEM_COMMIT) {
                committed += r.size();
                if (r.type() == Minidump.Region.MEM_IMAGE) {
                    image += r.size();
                } else if (r.type() == Minidump.Region.MEM_MAPPED) {
                    mapped += r.size();
                } else {
                    priv += r.size();
                }
            } else if (r.state() == Minidump.Region.MEM_RESERVE) {
                reserved += r.size();
            } else if (r.state() == Minidump.Region.MEM_FREE) {
                largestFree = Math.max(largestFree, r.size());
            }
            top = Math.max(top, r.base() + r.size());
        }
        long limit = d.system().arch().pointerSize() == 4 ? (top > 0x80000000L ? 4L << 30 : 2L << 30) : 0;
        return new Memory(committed, image, mapped, priv, reserved, committed + reserved, limit, largestFree);
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v < 10 ? "%.1f %s" : "%.0f %s", v, u[i]);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);

    private static String time(long epochSeconds) {
        return TIME.format(Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC));
    }

    static String duration(long seconds) {
        if (seconds < 120) {
            return seconds + " s";
        }
        if (seconds < 7200) {
            return seconds / 60 + " min";
        }
        if (seconds < 172800) {
            return String.format(Locale.ROOT, "%.1f hours", seconds / 3600.0);
        }
        return String.format(Locale.ROOT, "%.1f days", seconds / 86400.0);
    }

    private static List<Fact> facts(Minidump d) {
        Map<String, String> f = new LinkedHashMap<>();
        Minidump.Module exe = d.mainModule();
        if (exe != null) {
            f.put("Program", exe.name() + (exe.version().isEmpty() ? "" : " " + exe.version()));
            f.put("Program path", exe.path());
        }
        String[] peb = processParameters(d);
        if (peb[0] != null && !peb[0].isBlank()) {
            f.put("Command line", peb[0]);
        }
        if (peb[1] != null && !peb[1].isBlank()) {
            f.put("Working folder", peb[1]);
        }
        if (d.processId() >= 0) {
            f.put("Process id", String.valueOf(d.processId()));
        }
        if (d.time() > 0) {
            f.put("Dump written", time(d.time()));
        }
        if (d.processCreateTime() > 0) {
            f.put("Process started", time(d.processCreateTime()));
            if (d.time() > d.processCreateTime()) {
                f.put("Running for", duration(d.time() - d.processCreateTime()));
            }
        }
        if (d.processUserSeconds() >= 0) {
            f.put("CPU time", duration(d.processUserSeconds() + Math.max(0, d.processKernelSeconds())));
        }
        f.put("Windows", d.system().osName());
        f.put("Processor", d.system().arch().name().toLowerCase(Locale.ROOT).replace("amd64", "x64")
                + (d.system().processors() > 0 ? ", " + d.system().processors()
                + (d.system().processors() == 1 ? " logical processor" : " logical processors") : ""));
        f.put("Dump", (d.isFull() ? "full dump" : "minidump") + ", " + size(d.fileSize()) + ", "
                + size(d.memoryBytes()) + " of memory in it");
        f.put("Threads", String.valueOf(d.threads().size()));
        f.put("Modules", String.valueOf(d.modules().size()));
        if (d.handleTotal() >= 0) {
            StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%,d", d.handleTotal()));
            List<Minidump.HandleCount> hs = d.handles();
            for (int i = 0; i < hs.size() && i < 5; i++) {
                b.append(i == 0 ? " (" : ", ").append(hs.get(i).type()).append(' ')
                        .append(String.format(Locale.ROOT, "%,d", hs.get(i).count()));
            }
            f.put("Handles", b + (hs.isEmpty() ? "" : ")"));
        }
        Memory m = memory(d);
        if (m != null) {
            f.put("Memory", size(m.committed) + " committed (" + size(m.priv) + " private, " + size(m.image)
                    + " program files, " + size(m.mapped) + " mapped files), " + size(m.reserved) + " reserved"
                    + (m.limit > 0 ? "; " + size(m.used) + " of " + size(m.limit) + " addresses used" : ""));
        }
        List<Fact> out = new ArrayList<>();
        f.forEach((k, v) -> out.add(new Fact(k, v)));
        return out;
    }

    /** The command line and working folder, from the process's PEB (in full dumps); nulls if not there. */
    static String[] processParameters(Minidump d) {
        String[] out = new String[2];
        if (d.threads().isEmpty()) {
            return out;
        }
        try {
            boolean x64 = d.system().arch().pointerSize() == 8;
            long teb = d.threads().get(0).teb();
            Long peb = d.pointer(teb + (x64 ? 0x60 : 0x30));
            if (peb == null || peb == 0) {
                return out;
            }
            Long params = d.pointer(peb + (x64 ? 0x20 : 0x10));
            if (params == null || params == 0) {
                return out;
            }
            out[0] = unicodeString(d, params + (x64 ? 0x70 : 0x40), x64);
            out[1] = unicodeString(d, params + (x64 ? 0x38 : 0x24), x64);
        } catch (IOException | RuntimeException e) {
            // not in the dump
        }
        return out;
    }

    private static String unicodeString(Minidump d, long at, boolean x64) throws IOException {
        byte[] h = d.memory(at, x64 ? 16 : 8);
        if (h == null || h.length < (x64 ? 16 : 8)) {
            return null;
        }
        ByteBuffer b = le(h);
        int len = b.getShort(0) & 0xffff;
        long buf = x64 ? b.getLong(8) : Integer.toUnsignedLong(b.getInt(4));
        if (len == 0 || buf == 0) {
            return null;
        }
        byte[] s = d.memory(buf, len);
        return s == null ? null : new String(s, StandardCharsets.UTF_16LE);
    }

    private static List<CrashReport.Module> modules(Minidump d, Symbols s, CrashReport.Thread crashed) {
        List<CrashReport.Module> out = new ArrayList<>();
        Minidump.ExceptionInfo ex = d.exception();
        Minidump.Module crashMod = ex == null ? null : d.moduleAt(ex.address());
        for (Minidump.Module m : d.modules()) {
            String note = m == crashMod ? "the crash is here" : m == d.mainModule() ? "the program" : "";
            String range = hex(m.base(), d) + " – " + hex(m.base() + m.size(), d);
            out.add(new CrashReport.Module(m.name(), m.version(), m.path(), range, s.symbolState(m), note));
        }
        for (Minidump.UnloadedModule u : d.unloadedModules()) {
            out.add(new CrashReport.Module(u.name(), "", u.path(), hex(u.base(), d) + " – " + hex(u.base() + u.size(), d),
                    "", "unloaded"));
        }
        return out;
    }
}
