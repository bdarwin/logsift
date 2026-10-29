package logsift.crash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds a thread's call stack from its registers and the stack memory in the dump. On x64 it
 * follows the unwind tables of the loaded images (as Windows does), so it needs no symbol files;
 * on x86 and ARM64 it follows frame pointers. Where that fails it scans the stack for return
 * addresses, and marks those frames as guessed.
 */
public final class StackWalk {

    /** How a frame was found, best first. */
    public enum How {
        /** The thread's own registers. */
        CONTEXT,
        /** The image's unwind table. */
        UNWIND,
        /** The frame pointer chain. */
        FRAME_POINTER,
        /** A return address found by scanning the stack: likely, not certain. */
        SCAN
    }

    public record Frame(long pc, long sp, How how) {}

    /** Gives a module's image (from the dump or from disk); null if it can't be read. */
    public interface Images {
        PeImage image(Minidump.Module m);
    }

    private final Minidump dump;
    private final Images images;
    private final int ptr;

    private final java.util.function.LongPredicate last;

    /**
     * @param last  says whether a frame is where a thread begins (RtlUserThreadStart): nothing
     *              above it is real, so the walk stops there; null: never
     */
    public StackWalk(Minidump dump, Images images, java.util.function.LongPredicate last) {
        this.dump = dump;
        this.images = images;
        this.ptr = dump.system().arch().pointerSize();
        this.last = last;
    }

    /** The frames of a thread, at most max; empty if its registers can't be read. */
    public List<Frame> walk(Registers start, long stackLow, long stackHigh, int max) {
        List<Frame> out = new ArrayList<>();
        if (start == null) {
            return out;
        }
        Registers r = start.copy();
        out.add(new Frame(r.pc, r.sp(), How.CONTEXT));
        // the stack's top end: what the thread's stack record covers, or a generous guess
        long high = Long.compareUnsigned(stackHigh, r.sp()) > 0 ? stackHigh : r.sp() + (1L << 20);
        boolean firstFrame = true;
        int scanned = 0;
        while (out.size() < max && (last == null || !last.test(r.pc))) {
            long oldSp = r.sp();
            Registers next = r.copy(); // a failed step leaves r as it was
            How how = step(next, firstFrame);
            firstFrame = false;
            if (how != null && plausible(next, oldSp, high) && !(next.pc == r.pc && next.sp() == oldSp)) {
                r = next;
                out.add(new Frame(r.pc, r.sp(), how));
                scanned = 0;
                continue;
            }
            // lost: scan upward from the last good stack pointer (past copies of the current address)
            Registers s = scan(oldSp, high);
            while (s != null && s.pc == r.pc && scanned < 8) {
                scanned++;
                s = scan(s.sp() - ptr, high);
            }
            if (s == null || ++scanned > 8) {
                break; // nothing, or only guesses for a while: the rest would be noise
            }
            r.pc = s.pc;
            r.setSp(s.sp());
            if (start.arch != Minidump.Arch.AMD64) {
                r.r[start.arch == Minidump.Arch.ARM64 ? 29 : 5] = s.fp(); // a frame pointer to try next
            }
            out.add(new Frame(r.pc, r.sp(), How.SCAN));
        }
        return out;
    }

    /** One frame up; the way it was found, or null. */
    private How step(Registers r, boolean first) {
        try {
            return switch (r.arch) {
                case AMD64 -> unwindAmd64(r, first) ? How.UNWIND : null;
                case X86 -> framePointerX86(r) ? How.FRAME_POINTER : null;
                case ARM64 -> framePointerArm64(r, first) ? How.FRAME_POINTER : null;
                default -> null;
            };
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private boolean plausible(Registers r, long oldSp, long high) {
        long sp = r.sp();
        if (Long.compareUnsigned(sp, oldSp) < 0 || Long.compareUnsigned(sp, high) > 0) {
            return false;
        }
        return isCode(r.pc);
    }

    /** Whether an address is in a module's code, or in memory that holds run-time generated code. */
    boolean isCode(long pc) {
        if (pc == 0) {
            return false;
        }
        Minidump.Module m = dump.moduleAt(pc);
        if (m != null) {
            PeImage img = images.image(m);
            if (img == null) {
                return true; // can't tell: a module address is good enough
            }
            PeImage.Section s = img.sectionAt(pc - m.base());
            return s == null || s.executable();
        }
        Minidump.Region reg = dump.regionAt(pc);
        return reg != null && reg.executable() && reg.state() == Minidump.Region.MEM_COMMIT;
    }

    private Long readPtr(long address) throws IOException {
        return dump.pointer(address);
    }

    // ---------------------------------------------------------------- x64: unwind tables

    private static int slots(int op, int info) {
        return switch (op) {
            case 1 -> info == 0 ? 2 : 3;
            case 4, 6, 8 -> 2;
            case 5, 7, 9 -> 3;
            default -> 1;
        };
    }

    boolean unwindAmd64(Registers r, boolean first) throws IOException {
        Minidump.Module m = dump.moduleAt(r.pc);
        PeImage img = m == null ? null : images.image(m);
        if (img == null || !img.hasUnwindTable()) {
            return false;
        }
        long rva = r.pc - m.base();
        PeImage.Function f = img.functionAt(rva);
        if (f == null) {
            // a leaf function: no frame of its own, the return address is on top
            Long ra = readPtr(r.r[4]);
            if (ra == null) {
                return false;
            }
            r.pc = ra;
            r.r[4] += 8;
            return true;
        }
        long offset = rva - f.begin();
        boolean machFrame = false;
        for (int depth = 0; depth < 32; depth++) {
            byte[] head = img.read(f.unwind(), 4);
            if (head == null || head.length < 4) {
                return false;
            }
            int version = head[0] & 7;
            int flags = (head[0] & 0xff) >>> 3;
            int prolog = head[1] & 0xff;
            int count = head[2] & 0xff;
            int frameReg = head[3] & 0x0f;
            long frameOff = ((head[3] & 0xff) >>> 4) * 16L;
            if (version != 1 && version != 2) {
                return false;
            }
            byte[] codes = img.read(f.unwind() + 4, ((count + 1) & ~1) * 2 + 12);
            if (codes == null || codes.length < count * 2) {
                return false;
            }
            ByteBuffer c = ByteBuffer.wrap(codes).order(ByteOrder.LITTLE_ENDIAN);
            long limit = depth == 0 && offset < prolog ? offset : Long.MAX_VALUE; // in the prolog: only what ran
            // the frame base: the frame register once it is set, the stack pointer before
            long frame = r.r[4];
            if (frameReg != 0) {
                for (int i = 0; i < count; i += slots(codes[i * 2 + 1] & 0x0f, (codes[i * 2 + 1] & 0xff) >>> 4)) {
                    if ((codes[i * 2 + 1] & 0x0f) == 3 && (codes[i * 2] & 0xff) <= limit) {
                        frame = r.r[frameReg] - frameOff;
                    }
                }
            }
            for (int i = 0; i < count; ) {
                int codeOffset = codes[i * 2] & 0xff;
                int op = codes[i * 2 + 1] & 0x0f;
                int info = (codes[i * 2 + 1] & 0xff) >>> 4;
                int n = slots(op, info);
                if (codeOffset <= limit) {
                    switch (op) {
                        case 0 -> { // push a register
                            Long v = readPtr(r.r[4]);
                            if (v == null) {
                                return false;
                            }
                            r.r[info] = v;
                            r.r[4] += 8;
                        }
                        case 1 -> r.r[4] += info == 0 ? (c.getShort((i + 1) * 2) & 0xffffL) * 8
                                : Integer.toUnsignedLong(c.getInt((i + 1) * 2));
                        case 2 -> r.r[4] += info * 8L + 8;
                        case 3 -> r.r[4] = frame;
                        case 4 -> {
                            Long v = readPtr(frame + (c.getShort((i + 1) * 2) & 0xffffL) * 8);
                            if (v != null) {
                                r.r[info] = v;
                            }
                        }
                        case 5 -> {
                            Long v = readPtr(frame + Integer.toUnsignedLong(c.getInt((i + 1) * 2)));
                            if (v != null) {
                                r.r[info] = v;
                            }
                        }
                        case 10 -> { // an interrupt or exception frame
                            if (info != 0) {
                                r.r[4] += 8;
                            }
                            Long ip = readPtr(r.r[4]);
                            Long sp = readPtr(r.r[4] + 24);
                            if (ip == null || sp == null) {
                                return false;
                            }
                            r.pc = ip;
                            r.r[4] = sp;
                            machFrame = true;
                        }
                        default -> { } // xmm saves and epilog notes: nothing for integer registers
                    }
                }
                i += n;
            }
            if ((flags & 0x4) == 0) {
                break;
            }
            // chained: the rest of the function's prolog is described by another entry
            int at = ((count + 1) & ~1) * 2;
            if (codes.length < at + 12) {
                return false;
            }
            f = new PeImage.Function(Integer.toUnsignedLong(c.getInt(at)), Integer.toUnsignedLong(c.getInt(at + 4)),
                    Integer.toUnsignedLong(c.getInt(at + 8)));
        }
        if (!machFrame) {
            Long ra = readPtr(r.r[4]);
            if (ra == null) {
                return false;
            }
            r.pc = ra;
            r.r[4] += 8;
        }
        return true;
    }

    // ---------------------------------------------------------------- x86 and ARM64: frame pointers

    private boolean framePointerX86(Registers r) throws IOException {
        long ebp = r.r[5];
        if (ebp == 0 || Long.compareUnsigned(ebp, r.r[4]) < 0) {
            return false;
        }
        Long next = readPtr(ebp);
        Long ra = readPtr(ebp + 4);
        if (next == null || ra == null) {
            return false;
        }
        r.pc = ra;
        r.r[4] = ebp + 8;
        r.r[5] = next;
        return true;
    }

    private boolean framePointerArm64(Registers r, boolean first) throws IOException {
        if (first && isCode(r.r[30]) && r.r[30] != r.pc) {
            // the crash may be in a function that hasn't saved lr yet: try lr first, once
            long fp = r.r[29];
            if (Long.compareUnsigned(fp, r.r[31]) >= 0) {
                Long saved = readPtr(fp + 8);
                if (saved != null && saved == r.r[30]) {
                    return chainArm64(r);
                }
            }
            r.pc = r.r[30]; // a leaf: lr is the caller, the stack is where it was
            return true;
        }
        return chainArm64(r);
    }

    private boolean chainArm64(Registers r) throws IOException {
        long fp = r.r[29];
        if (fp == 0 || Long.compareUnsigned(fp, r.r[31]) < 0) {
            return false;
        }
        Long next = readPtr(fp);
        Long lr = readPtr(fp + 8);
        if (next == null || lr == null) {
            return false;
        }
        r.pc = lr & 0x0000_FFFF_FFFF_FFFFL; // drop pointer authentication bits
        r.r[31] = fp + 16;
        r.r[29] = next;
        return true;
    }

    // ---------------------------------------------------------------- scanning

    /** The next likely return address above sp: registers with pc, sp (just above it) and fp; null if none. */
    private Registers scan(long sp, long high) {
        Minidump.Arch arch = dump.system().arch();
        long from = sp + ptr;
        long to = Math.min(high, from + 64 * 1024);
        long uncheckedTo = Math.min(high, from + 40L * ptr); // where calls can't be checked, look less far
        try {
            int len = (int) Math.max(0, to - from);
            byte[] a = len == 0 ? null : dump.memory(from, len);
            if (a == null) {
                return null;
            }
            ByteBuffer b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i + ptr <= a.length; i += ptr) {
                long v = ptr == 4 ? Integer.toUnsignedLong(b.getInt(i)) : b.getLong(i);
                Minidump.Module vm = dump.moduleAt(v);
                if (vm == null || v - vm.base() < 0x1000 || !isCode(v)) {
                    continue; // not code (an image's headers aren't)
                }
                int call = afterCall(v, arch);
                if (call == 0 || call < 0 && from + i >= uncheckedTo) {
                    continue;
                }
                long at = from + i;
                long fp = arch == Minidump.Arch.X86 ? at - 4 : arch == Minidump.Arch.ARM64 ? at - 8 : 0;
                Long savedFp = fp == 0 ? null : dump.pointer(fp);
                long[] regs = arch == Minidump.Arch.ARM64 ? new long[32] : new long[16];
                Registers r = new Registers(arch, regs, v);
                r.setSp(at + ptr);
                if (savedFp != null) {
                    regs[arch == Minidump.Arch.ARM64 ? 29 : 5] = savedFp;
                }
                return r;
            }
        } catch (IOException | RuntimeException e) {
            // nothing more to find
        }
        return null;
    }

    /** Whether the instruction just before a return address is a call: 1 yes, 0 no, -1 can't tell. */
    private int afterCall(long ra, Minidump.Arch arch) throws IOException {
        if (arch == Minidump.Arch.ARM64) {
            byte[] a = dump.memory(ra - 4, 4);
            if (a == null) {
                Minidump.Module m = dump.moduleAt(ra);
                PeImage img = m == null ? null : images.image(m);
                a = img == null ? null : img.read(ra - 4 - m.base(), 4);
            }
            if (a == null || a.length < 4) {
                return -1;
            }
            int insn = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getInt();
            return (insn & 0xFC000000) == 0x94000000 // bl
                    || (insn & 0xFFFFFC1F) == 0xD63F0000 // blr
                    || (insn & 0xFFFFFC00) == 0xD63F0800 ? 1 : 0; // blraa and friends
        }
        byte[] a = dump.memory(ra - 7, 7);
        if (a == null || a.length < 7) {
            Minidump.Module m = dump.moduleAt(ra);
            PeImage img = m == null ? null : images.image(m);
            a = img == null ? null : img.read(ra - 7 - m.base(), 7);
            if (a == null || a.length < 7) {
                return -1; // the code isn't in the dump
            }
        }
        if ((a[2] & 0xff) == 0xE8) {
            return 1; // call rel32
        }
        for (int i = 2; i <= 7; i++) {
            int op = a[7 - i] & 0xff;
            int modrm = a[7 - i + 1] & 0xff;
            if (op == 0xFF && (modrm & 0x38) == 0x10) {
                return 1; // call r/m
            }
        }
        return 0;
    }
}
