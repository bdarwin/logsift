package logsift.crash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A thread's saved registers, read from a Windows CONTEXT record. Integer registers only: what a
 * stack walk and a crash summary need.
 */
public final class Registers {

    /** x64 registers in unwind-code order: rax rcx rdx rbx rsp rbp rsi rdi r8 … r15. */
    static final String[] AMD64_NAMES = {"rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi", "r8", "r9", "r10",
            "r11", "r12", "r13", "r14", "r15"};

    final Minidump.Arch arch;
    /** amd64: 16 general registers; x86: eax ecx edx ebx esp ebp esi edi; arm64: x0..x28, fp, lr, sp. */
    final long[] r;
    long pc;

    Registers(Minidump.Arch arch, long[] r, long pc) {
        this.arch = arch;
        this.r = r;
        this.pc = pc;
    }

    Registers copy() {
        return new Registers(arch, r.clone(), pc);
    }

    /** Reads a CONTEXT; null when it is too short for the processor. */
    public static Registers of(Minidump.Arch arch, byte[] ctx) {
        if (ctx == null) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(ctx).order(ByteOrder.LITTLE_ENDIAN);
        switch (arch) {
            case AMD64 -> {
                if (ctx.length < 0x100) {
                    return null;
                }
                long[] r = new long[16];
                for (int i = 0; i < 16; i++) {
                    r[i] = b.getLong(0x78 + i * 8);
                }
                return new Registers(arch, r, b.getLong(0xf8));
            }
            case X86 -> {
                if (ctx.length < 0xcc) {
                    return null;
                }
                // CONTEXT order: edi esi ebx edx ecx eax ebp eip cs eflags esp
                long eax = u32(b, 0xb0);
                long ecx = u32(b, 0xac);
                long edx = u32(b, 0xa8);
                long ebx = u32(b, 0xa4);
                long esp = u32(b, 0xc4);
                long ebp = u32(b, 0xb4);
                long esi = u32(b, 0xa0);
                long edi = u32(b, 0x9c);
                return new Registers(arch, new long[] {eax, ecx, edx, ebx, esp, ebp, esi, edi}, u32(b, 0xb8));
            }
            case ARM64 -> {
                if (ctx.length < 0x110) {
                    return null;
                }
                long[] r = new long[32];
                for (int i = 0; i < 29; i++) {
                    r[i] = b.getLong(8 + i * 8);
                }
                r[29] = b.getLong(0xf0); // fp
                r[30] = b.getLong(0xf8); // lr
                r[31] = b.getLong(0x100); // sp
                return new Registers(arch, r, b.getLong(0x108));
            }
            default -> {
                return null;
            }
        }
    }

    private static long u32(ByteBuffer b, int at) {
        return Integer.toUnsignedLong(b.getInt(at));
    }

    public long pc() {
        return pc;
    }

    public long sp() {
        return switch (arch) {
            case AMD64, X86 -> r[4];
            case ARM64 -> r[31];
            default -> 0;
        };
    }

    void setSp(long v) {
        switch (arch) {
            case AMD64, X86 -> r[4] = v;
            case ARM64 -> r[31] = v;
            default -> { }
        }
    }

    /** The frame pointer: rbp, ebp or x29. */
    public long fp() {
        return switch (arch) {
            case AMD64, X86 -> r[5];
            case ARM64 -> r[29];
            default -> 0;
        };
    }

    /** Name → value, in the usual order, for showing. */
    public Map<String, Long> named() {
        Map<String, Long> m = new LinkedHashMap<>();
        switch (arch) {
            case AMD64 -> {
                m.put("rip", pc);
                for (int i = 0; i < 16; i++) {
                    m.put(AMD64_NAMES[i], r[i]);
                }
            }
            case X86 -> {
                String[] n = {"eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi"};
                m.put("eip", pc);
                for (int i = 0; i < 8; i++) {
                    m.put(n[i], r[i]);
                }
            }
            case ARM64 -> {
                m.put("pc", pc);
                m.put("sp", r[31]);
                m.put("fp", r[29]);
                m.put("lr", r[30]);
                for (int i = 0; i < 29; i++) {
                    m.put("x" + i, r[i]);
                }
            }
            default -> { }
        }
        return m;
    }
}
