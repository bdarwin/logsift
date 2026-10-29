package logsift.crash;

import static logsift.crash.PeBuilder.code;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ready-made Windows dumps for tests outside this package (the visual tests). */
public final class TestDumps {
    private TestDumps() {}

    /** A module of exported functions, each 0x100 bytes from 0x1000, with simple unwind infos. */
    private static final class Mod {
        final String path;
        final long base;
        final int verMs;
        final int verLs;
        final Map<String, Integer> fns = new LinkedHashMap<>(); // name → stack it allocates (0: a stub)

        Mod(String path, long base, int verMs, int verLs) {
            this.path = path;
            this.base = base;
            this.verMs = verMs;
            this.verLs = verLs;
        }

        Mod fn(String name, int alloc) {
            fns.put(name, alloc);
            return this;
        }

        int begin(String name) {
            int i = 0;
            for (String n : fns.keySet()) {
                if (n.equals(name)) {
                    return 0x1000 + i * 0x100;
                }
                i++;
            }
            throw new IllegalArgumentException(name);
        }

        byte[] image() {
            PeBuilder p = new PeBuilder(Scenario.IMAGE, true);
            for (Map.Entry<String, Integer> e : fns.entrySet()) {
                int b = begin(e.getKey());
                int a = e.getValue();
                p.fn(b, b + 0x100, a == 0 ? null : PeBuilder.unwind(0, 4, 0, 0, code(4, 2, (a - 8) / 8)));
                p.export(e.getKey(), b);
                p.callBefore(b + 0x41);
            }
            return p.build();
        }
    }

    private record Call(Mod mod, String fn) {}

    private static final long STACKS = 0x000000A0_00000000L;
    private static final long STACK = 0x2000;

    /** Lays out a thread's stack for these frames, innermost first; gives its CONTEXT. */
    private static byte[] stack(DumpBuilder.Block block, List<Call> calls) {
        long sp = block.start + 0x800;
        long first = sp;
        for (int i = 0; i + 1 < calls.size(); i++) {
            Call c = calls.get(i);
            int alloc = c.mod().fns.get(c.fn());
            Call next = calls.get(i + 1);
            block.p64(sp + alloc, next.mod().base + next.mod().begin(next.fn()) + 0x41);
            sp += alloc + 8;
        }
        Call top = calls.get(0);
        int off = top.mod().fns.get(top.fn()) == 0 ? 0x14 : 0x1f;
        return DumpBuilder.amd64(top.mod().base + top.mod().begin(top.fn()) + off, first, 0);
    }

    /**
     * Writes {@code dir/orders-service.dmp}: a full dump of OrdersService.exe that crashed with an
     * access violation reading 0x18 (a field of a null object) in Order::total on thread 7104
     * "worker-2", with eight other threads (the main thread waiting for window messages, two
     * waiting for a lock, thread-pool workers, one sleeping), thread names, memory regions,
     * handles, an unloaded DLL, process times and a command line.
     */
    public static Path crashDump(Path dir) throws IOException {
        Mod app = new Mod("C:\\Program Files\\Acme\\OrdersService.exe", 0x7FF6B2340000L, 0x00020004, 0x00010000)
                .fn("main", 0x48).fn("workerLoop", 0x58).fn("processOrder", 0x38).fn("Order::total", 0x28)
                .fn("Inventory::reserve", 0x38).fn("Metrics::flushLoop", 0x28);
        Mod ntdll = new Mod("C:\\Windows\\System32\\ntdll.dll", 0x7FFB60400000L, 0x000A0000, 0x4A611000)
                .fn("RtlUserThreadStart", 0x48).fn("NtWaitForWorkViaWorkerFactory", 0).fn("TppWorkerThread", 0x78)
                .fn("NtWaitForAlertByThreadId", 0).fn("RtlpWaitOnCriticalSection", 0x48)
                .fn("RtlEnterCriticalSection", 0x28).fn("NtDelayExecution", 0);
        Mod kernel32 = new Mod("C:\\Windows\\System32\\kernel32.dll", 0x7FFB5EE90000L, 0x000A0000, 0x4A611000)
                .fn("BaseThreadInitThunk", 0x28);
        Mod kernelbase = new Mod("C:\\Windows\\System32\\KERNELBASE.dll", 0x7FFB5E100000L, 0x000A0000, 0x4A611000)
                .fn("SleepEx", 0x38);
        Mod win32u = new Mod("C:\\Windows\\System32\\win32u.dll", 0x7FFB5DF00000L, 0x000A0000, 0x4A611000)
                .fn("NtUserGetMessage", 0);
        Mod user32 = new Mod("C:\\Windows\\System32\\user32.dll", 0x7FFB5F600000L, 0x000A0000, 0x4A611000)
                .fn("GetMessageW", 0x38);
        List<Mod> mods = List.of(app, ntdll, kernel32, kernelbase, win32u, user32);

        DumpBuilder b = new DumpBuilder();
        b.memory64 = true;
        b.flags = Minidump.WITH_FULL_MEMORY;
        b.timeDateStamp = 1_761_700_000L;
        b.build = 22631;
        b.processors = 16;
        for (Mod m : mods) {
            b.module(new DumpBuilder.ModuleSpec(m.base, Scenario.IMAGE, 0x67a1b2c3, m.path, m.verMs, m.verLs, null, null, 0));
            b.mem(m.base, m.image());
            b.regions.add(new DumpBuilder.RegionSpec(m.base, Scenario.IMAGE, Minidump.Region.MEM_COMMIT, 0x20,
                    Minidump.Region.MEM_IMAGE));
        }
        Call start = new Call(ntdll, "RtlUserThreadStart");
        Call thunk = new Call(kernel32, "BaseThreadInitThunk");
        Call loop = new Call(app, "workerLoop");
        List<Object[]> threads = new ArrayList<>(); // id, name, calls
        threads.add(new Object[] {6512, "main", List.of(new Call(win32u, "NtUserGetMessage"), new Call(user32, "GetMessageW"),
                new Call(app, "main"), thunk, start)});
        List<Call> lock = List.of(new Call(ntdll, "NtWaitForAlertByThreadId"), new Call(ntdll, "RtlpWaitOnCriticalSection"),
                new Call(ntdll, "RtlEnterCriticalSection"), new Call(app, "Inventory::reserve"), loop, thunk, start);
        threads.add(new Object[] {7088, "worker-1", lock});
        threads.add(new Object[] {7104, "worker-2", List.of(new Call(app, "Order::total"), new Call(app, "processOrder"), loop,
                thunk, start)});
        threads.add(new Object[] {7120, "worker-3", lock});
        List<Call> pool = List.of(new Call(ntdll, "NtWaitForWorkViaWorkerFactory"), new Call(ntdll, "TppWorkerThread"),
                thunk, start);
        for (int id : new int[] {7200, 7204, 7208, 7212}) {
            threads.add(new Object[] {id, null, pool});
        }
        threads.add(new Object[] {7300, "metrics-flush", List.of(new Call(ntdll, "NtDelayExecution"),
                new Call(kernelbase, "SleepEx"), new Call(app, "Metrics::flushLoop"), thunk, start)});

        long teb = 0x000000B0_00000000L;
        byte[] crashCtx = null;
        for (int i = 0; i < threads.size(); i++) {
            Object[] t = threads.get(i);
            int id = (Integer) t[0];
            @SuppressWarnings("unchecked")
            List<Call> calls = (List<Call>) t[2];
            DumpBuilder.Block block = new DumpBuilder.Block(STACKS + i * 0x10000L, (int) STACK);
            byte[] ctx = stack(block, calls);
            b.mem(block);
            b.regions.add(new DumpBuilder.RegionSpec(block.start, STACK, Minidump.Region.MEM_COMMIT, 0x04,
                    Minidump.Region.MEM_PRIVATE));
            b.thread(id, i == 0 ? teb : 0, block.start, STACK, ctx);
            if (t[1] != null) {
                b.threadNames.put(id, (String) t[1]);
            }
            b.threadTimes.put(id, new long[] {1_000_000L * (i + 1), 4_000_000L * (i + 1)});
            if (id == 7104) {
                crashCtx = ctx;
            }
        }
        // the process parameters, from the main thread's TEB
        long peb = teb + 0x1000;
        long params = teb + 0x2000;
        String cmd = "\"C:\\Program Files\\Acme\\OrdersService.exe\" --config C:\\ProgramData\\Acme\\orders.yaml";
        String cwd = "C:\\Program Files\\Acme\\";
        b.mem(new DumpBuilder.Block(teb, 0x4000)
                .p64(teb + 0x60, peb)
                .p64(peb + 0x20, params)
                .p16(params + 0x38, cwd.length() * 2).p16(params + 0x3a, cwd.length() * 2).p64(params + 0x40, teb + 0x3100)
                .p16(params + 0x70, cmd.length() * 2).p16(params + 0x72, cmd.length() * 2).p64(params + 0x78, teb + 0x3000)
                .utf16(teb + 0x3000, cmd)
                .utf16(teb + 0x3100, cwd));
        b.regions.add(new DumpBuilder.RegionSpec(teb, 0x4000, Minidump.Region.MEM_COMMIT, 0x04, Minidump.Region.MEM_PRIVATE));
        b.regions.add(new DumpBuilder.RegionSpec(0x000000C0_00000000L, 0x40000000L, Minidump.Region.MEM_RESERVE, 0x01,
                Minidump.Region.MEM_PRIVATE));
        b.regions.add(new DumpBuilder.RegionSpec(0x1000, 0x7FF0000, Minidump.Region.MEM_FREE, 0x01, 0));
        for (String type : new String[] {"Event", "File", "Key", "Thread", "Semaphore", "ALPC Port"}) {
            int n = switch (type) {
                case "Event" -> 40;
                case "File" -> 12;
                case "Key" -> 6;
                case "Thread" -> 9;
                default -> 3;
            };
            for (int i = 0; i < n; i++) {
                b.handleTypes.add(type);
            }
        }
        b.unloaded.add(new DumpBuilder.Unloaded(0x7FFB40000000L, 0x30000, "C:\\Program Files\\Acme\\plugins\\legacy-export.dll"));
        b.pid = 5832;
        b.createTime = b.timeDateStamp - 2 * 86400 - 3 * 3600;
        b.userTime = 340;
        b.kernelTime = 25;
        long pc = app.base + app.begin("Order::total") + 0x1f;
        b.exception(7104, 0xC0000005, pc, crashCtx, 0, 0x18);
        return b.write(dir.resolve("orders-service.dmp"));
    }
}
