package logsift.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a synthetic Windows dump (MDMP) with just the streams a test asks for. */
final class DumpBuilder {

    record ThreadSpec(int id, long teb, long stackStart, long stackSize, byte[] context) {}

    record ModuleSpec(long base, long size, int stamp, String path, int verMs, int verLs, String pdbName, byte[] guid,
                      int age) {}

    record Mem(long start, byte[] bytes) {}

    record RegionSpec(long base, long size, int state, int protect, int type) {}

    record Unloaded(long base, long size, String path) {}

    int arch = 9; // AMD64
    int productType = 1;
    int major = 10;
    int minor = 0;
    int build = 19045;
    int processors = 8;
    String servicePack = "";
    long timeDateStamp = 1_700_000_000L;
    long flags;
    boolean memory64;
    final List<ThreadSpec> threads = new ArrayList<>();
    final List<ModuleSpec> modules = new ArrayList<>();
    final List<Mem> memory = new ArrayList<>();
    final List<RegionSpec> regions = new ArrayList<>();
    final List<Unloaded> unloaded = new ArrayList<>();
    final Map<Integer, String> threadNames = new LinkedHashMap<>();
    final Map<Integer, long[]> threadTimes = new LinkedHashMap<>(); // id → kernel, user (100 ns)
    final List<String> handleTypes = new ArrayList<>();
    // exception
    Integer exceptionThread;
    int exceptionCode;
    long exceptionAddress;
    long[] exceptionParams = new long[0];
    byte[] exceptionContext;
    // misc
    int pid = -1;
    long createTime;
    long userTime;
    long kernelTime;
    String comment;

    DumpBuilder thread(int id, long teb, long stackStart, long stackSize, byte[] ctx) {
        threads.add(new ThreadSpec(id, teb, stackStart, stackSize, ctx));
        return this;
    }

    DumpBuilder module(long base, long size, String path) {
        modules.add(new ModuleSpec(base, size, 0x5f000000, path, 0, 0, null, null, 0));
        return this;
    }

    DumpBuilder module(ModuleSpec m) {
        modules.add(m);
        return this;
    }

    DumpBuilder mem(long start, byte[] bytes) {
        memory.add(new Mem(start, bytes));
        return this;
    }

    DumpBuilder exception(int threadId, int code, long address, byte[] ctx, long... params) {
        exceptionThread = threadId;
        exceptionCode = code;
        exceptionAddress = address;
        exceptionContext = ctx;
        exceptionParams = params;
        return this;
    }

    // ---------------------------------------------------------------- contexts

    /** An x64 CONTEXT: registers in unwind order (rax rcx rdx rbx rsp rbp rsi rdi r8…r15). */
    static byte[] amd64(long rip, long rsp, long rbp) {
        byte[] c = new byte[0x4d0];
        LeBytes.put32(c, 0x30, 0x10001f);
        LeBytes.put64(c, 0x78 + 4 * 8, rsp);
        LeBytes.put64(c, 0x78 + 5 * 8, rbp);
        LeBytes.put64(c, 0xf8, rip);
        return c;
    }

    static byte[] x86(long eip, long esp, long ebp) {
        byte[] c = new byte[0x2cc];
        LeBytes.put32(c, 0, 0x1003f);
        LeBytes.put32(c, 0xb8, eip);
        LeBytes.put32(c, 0xc4, esp);
        LeBytes.put32(c, 0xb4, ebp);
        return c;
    }

    // ---------------------------------------------------------------- writing

    private static int string(LeBytes b, String s) {
        int at = b.pos();
        byte[] u = s.getBytes(StandardCharsets.UTF_16LE);
        b.u32(u.length).bytes(u).u16(0);
        return at;
    }

    Path write(Path file) throws IOException {
        LeBytes b = new LeBytes();
        b.zeros(32);
        List<long[]> dir = new ArrayList<>(); // type, size, rva
        // memory first, back to back (a Memory64List needs it in one run)
        List<long[]> memAt = new ArrayList<>(); // start, size, rva
        for (Mem m : memory) {
            memAt.add(new long[] {m.start(), m.bytes().length, b.pos()});
            b.bytes(m.bytes());
        }
        b.align(8);
        // system info
        int csd = string(b, servicePack);
        b.align(4);
        int sys = b.pos();
        b.u16(arch).u16(6).u16(0).u8(processors).u8(productType).u32(major).u32(minor).u32(build).u32(2).u32(csd)
                .u16(0).u16(0).zeros(24);
        dir.add(new long[] {Minidump.SYSTEM_INFO, 56, sys});
        // threads
        List<int[]> ctxAt = new ArrayList<>();
        for (ThreadSpec t : threads) {
            int at = b.pos();
            b.bytes(t.context());
            ctxAt.add(new int[] {t.context().length, at});
        }
        int tl = b.pos();
        b.u32(threads.size());
        for (int i = 0; i < threads.size(); i++) {
            ThreadSpec t = threads.get(i);
            long stackRva = 0;
            for (long[] m : memAt) {
                if (m[0] == t.stackStart()) {
                    stackRva = m[2];
                }
            }
            b.u32(t.id()).u32(0).u32(0x20).u32(0).u64(t.teb()).u64(t.stackStart()).u32(t.stackSize()).u32(stackRva)
                    .u32(ctxAt.get(i)[0]).u32(ctxAt.get(i)[1]);
        }
        dir.add(new long[] {Minidump.THREAD_LIST, b.pos() - tl, tl});
        // modules
        List<int[]> modStrings = new ArrayList<>();
        for (ModuleSpec m : modules) {
            int name = string(b, m.path());
            b.align(4);
            int cv = 0;
            int cvSize = 0;
            if (m.pdbName() != null) {
                cv = b.pos();
                b.bytes("RSDS".getBytes(StandardCharsets.ISO_8859_1)).bytes(m.guid()).u32(m.age()).cstring(m.pdbName());
                cvSize = b.pos() - cv;
                b.align(4);
            }
            modStrings.add(new int[] {name, cv, cvSize});
        }
        int ml = b.pos();
        b.u32(modules.size());
        for (int i = 0; i < modules.size(); i++) {
            ModuleSpec m = modules.get(i);
            int[] s = modStrings.get(i);
            b.u64(m.base()).u32(m.size()).u32(0).u32(m.stamp()).u32(s[0]);
            if (m.verMs() != 0) {
                b.u32(0xFEEF04BDL).u32(0x10000).u32(m.verMs()).u32(m.verLs()).zeros(36);
            } else {
                b.zeros(52);
            }
            b.u32(s[2]).u32(s[1]).zeros(8).zeros(16);
        }
        dir.add(new long[] {Minidump.MODULE_LIST, b.pos() - ml, ml});
        // memory list
        if (!memory.isEmpty()) {
            if (memory64) {
                int at = b.pos();
                b.u64(memAt.size()).u64(memAt.get(0)[2]);
                for (long[] m : memAt) {
                    b.u64(m[0]).u64(m[1]);
                }
                dir.add(new long[] {Minidump.MEMORY64_LIST, b.pos() - at, at});
            } else {
                int at = b.pos();
                b.u32(memAt.size());
                for (long[] m : memAt) {
                    b.u64(m[0]).u32(m[1]).u32(m[2]);
                }
                dir.add(new long[] {Minidump.MEMORY_LIST, b.pos() - at, at});
            }
        }
        // exception
        if (exceptionThread != null) {
            int ctx = b.pos();
            byte[] c = exceptionContext == null ? new byte[0] : exceptionContext;
            b.bytes(c);
            b.align(4);
            int at = b.pos();
            b.u32(exceptionThread).u32(0).u32(exceptionCode).u32(0).u64(0).u64(exceptionAddress)
                    .u32(exceptionParams.length).u32(0);
            for (int i = 0; i < 15; i++) {
                b.u64(i < exceptionParams.length ? exceptionParams[i] : 0);
            }
            b.u32(c.length).u32(c.length == 0 ? 0 : ctx);
            dir.add(new long[] {Minidump.EXCEPTION, 168, at});
        }
        // misc
        if (pid >= 0) {
            int at = b.pos();
            b.u32(24).u32(3).u32(pid).u32(createTime).u32(userTime).u32(kernelTime);
            dir.add(new long[] {Minidump.MISC_INFO, 24, at});
        }
        if (!regions.isEmpty()) {
            int at = b.pos();
            b.u32(16).u32(48).u64(regions.size());
            for (RegionSpec r : regions) {
                b.u64(r.base()).u64(r.base()).u32(r.protect()).u32(0).u64(r.size()).u32(r.state()).u32(r.protect())
                        .u32(r.type()).u32(0);
            }
            dir.add(new long[] {Minidump.MEMORY_INFO_LIST, b.pos() - at, at});
        }
        if (!threadNames.isEmpty()) {
            List<long[]> names = new ArrayList<>();
            for (Map.Entry<Integer, String> e : threadNames.entrySet()) {
                names.add(new long[] {e.getKey(), string(b, e.getValue())});
            }
            b.align(4);
            int at = b.pos();
            b.u32(names.size());
            for (long[] n : names) {
                b.u32(n[0]).u64(n[1]);
            }
            dir.add(new long[] {Minidump.THREAD_NAMES, b.pos() - at, at});
        }
        if (!threadTimes.isEmpty()) {
            int at = b.pos();
            b.u32(12).u32(64).u32(threadTimes.size());
            for (Map.Entry<Integer, long[]> e : threadTimes.entrySet()) {
                b.u32(e.getKey()).u32(0).u32(0).u32(0).u64(0).u64(0).u64(e.getValue()[0]).u64(e.getValue()[1]).u64(0)
                        .u64(0);
            }
            dir.add(new long[] {Minidump.THREAD_INFO_LIST, b.pos() - at, at});
        }
        if (!handleTypes.isEmpty()) {
            Map<String, Integer> typeRva = new LinkedHashMap<>();
            for (String t : handleTypes) {
                typeRva.computeIfAbsent(t, k -> string(b, k));
            }
            b.align(4);
            int at = b.pos();
            b.u32(16).u32(32).u32(handleTypes.size()).u32(0);
            for (int i = 0; i < handleTypes.size(); i++) {
                b.u64(4L * (i + 1)).u32(typeRva.get(handleTypes.get(i))).u32(0).u32(0).u32(0).u32(1).u32(1);
            }
            dir.add(new long[] {Minidump.HANDLE_DATA, b.pos() - at, at});
        }
        if (!unloaded.isEmpty()) {
            List<Integer> names = new ArrayList<>();
            for (Unloaded u : unloaded) {
                names.add(string(b, u.path()));
            }
            b.align(4);
            int at = b.pos();
            b.u32(12).u32(24).u32(unloaded.size());
            for (int i = 0; i < unloaded.size(); i++) {
                Unloaded u = unloaded.get(i);
                b.u64(u.base()).u32(u.size()).u32(0).u32(0).u32(names.get(i));
            }
            dir.add(new long[] {Minidump.UNLOADED_MODULE_LIST, b.pos() - at, at});
        }
        if (comment != null) {
            int at = b.pos();
            byte[] c = comment.getBytes(StandardCharsets.ISO_8859_1);
            b.bytes(c).u8(0);
            dir.add(new long[] {Minidump.COMMENT_A, c.length + 1, at});
        }
        b.align(4);
        int dirRva = b.pos();
        for (long[] d : dir) {
            b.u32(d[0]).u32(d[1]).u32(d[2]);
        }
        b.putU32(0, Minidump.SIGNATURE);
        b.putU32(4, 0xA793);
        b.putU32(8, dir.size());
        b.putU32(12, dirRva);
        b.putU32(20, timeDateStamp);
        b.putU32(24, flags);
        b.putU32(28, flags >>> 32);
        Files.write(file, b.toArray());
        return file;
    }

    // ---------------------------------------------------------------- helpers for memory

    /** A block of memory with pointer-sized values put at offsets. */
    static final class Block {
        final long start;
        final byte[] bytes;

        Block(long start, int size) {
            this.start = start;
            this.bytes = new byte[size];
        }

        Block p64(long address, long value) {
            LeBytes.put64(bytes, (int) (address - start), value);
            return this;
        }

        Block p32(long address, long value) {
            LeBytes.put32(bytes, (int) (address - start), value);
            return this;
        }

        Block p16(long address, int value) {
            LeBytes.put16(bytes, (int) (address - start), value);
            return this;
        }

        Block put(long address, byte[] b) {
            System.arraycopy(b, 0, bytes, (int) (address - start), b.length);
            return this;
        }

        Block utf16(long address, String s) {
            return put(address, s.getBytes(StandardCharsets.UTF_16LE));
        }

        Block ascii(long address, String s) {
            return put(address, (s + "\0").getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    DumpBuilder mem(Block b) {
        return mem(b.start, b.bytes);
    }
}
