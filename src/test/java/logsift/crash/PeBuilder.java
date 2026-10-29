package logsift.crash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny x64 (or x86) PE image as it lies in memory: headers, .text (0x1000–0x3000, filled with
 * int3) and .rdata (from 0x3000) with the unwind table, unwind infos and exports.
 */
final class PeBuilder {
    static final int TEXT = 0x1000;
    static final int RDATA = 0x3000;
    static final int PDATA_AT = 0x3000;
    static final int UNWIND_AT = 0x3400;
    static final int EXPORT_AT = 0x3800;
    /** Free room for test data. */
    static final int FREE_AT = 0x4000;

    record Fn(int begin, int end, byte[] unwind) {}

    private final int size;
    private final boolean x64;
    private int timeDateStamp = 0x5f000000;
    private final List<Fn> fns = new ArrayList<>();
    private final Map<String, Integer> exports = new LinkedHashMap<>();
    private final Map<Integer, byte[]> writes = new LinkedHashMap<>();
    private final List<int[]> calls = new ArrayList<>();

    PeBuilder(int size, boolean x64) {
        this.size = size;
        this.x64 = x64;
    }

    PeBuilder stamp(int t) {
        timeDateStamp = t;
        return this;
    }

    /** A function in the unwind table; unwind null: a function with no unwind codes. */
    PeBuilder fn(int begin, int end, byte[] unwind) {
        fns.add(new Fn(begin, end, unwind == null ? unwind(0, 0, 0, 0) : unwind));
        return this;
    }

    PeBuilder export(String name, int rva) {
        exports.put(name, rva);
        return this;
    }

    /** A call rel32 ending at a return address, so a stack scan accepts it. */
    PeBuilder callBefore(int returnRva) {
        calls.add(new int[] {returnRva});
        return this;
    }

    PeBuilder write(int rva, byte[] b) {
        writes.put(rva, b);
        return this;
    }

    // ---------------------------------------------------------------- unwind infos

    /** One unwind code slot. */
    static int code(int offset, int op, int info) {
        return (offset & 0xff) | ((op | info << 4) & 0xff) << 8;
    }

    /** UNWIND_INFO: version 1, flags, prolog size, frame register and its offset/16, then slots. */
    static byte[] unwind(int flags, int prolog, int frameReg, int frameOff16, int... slots) {
        return unwindChained(flags, prolog, frameReg, frameOff16, null, slots);
    }

    static byte[] unwindChained(int flags, int prolog, int frameReg, int frameOff16, int[] chain, int... slots) {
        LeBytes b = new LeBytes();
        b.u8(1 | flags << 3).u8(prolog).u8(slots.length).u8(frameReg | frameOff16 << 4);
        for (int s : slots) {
            b.u16(s);
        }
        if (slots.length % 2 != 0) {
            b.u16(0);
        }
        if (chain != null) {
            b.u32(chain[0]).u32(chain[1]).u32(chain[2]);
        }
        return b.toArray();
    }

    /** An unwind info with a bad version: unwinding must fail. */
    static byte[] badUnwind() {
        return new byte[] {7, 0, 0, 0};
    }

    // ---------------------------------------------------------------- the image

    byte[] build() {
        byte[] img = new byte[size];
        Arrays.fill(img, TEXT, RDATA, (byte) 0xCC);
        img[0] = 'M';
        img[1] = 'Z';
        LeBytes.put32(img, 0x3c, 0x80);
        int pe = 0x80;
        img[pe] = 'P';
        img[pe + 1] = 'E';
        LeBytes.put16(img, pe + 4, x64 ? 0x8664 : 0x14c);
        LeBytes.put16(img, pe + 6, 2);
        LeBytes.put32(img, pe + 8, timeDateStamp);
        int optSize = x64 ? 240 : 224;
        LeBytes.put16(img, pe + 20, optSize);
        LeBytes.put16(img, pe + 24, x64 ? 0x20b : 0x10b);
        int dirs = pe + 24 + (x64 ? 112 : 96);
        // sections
        int sec = pe + 24 + optSize;
        section(img, sec, ".text", TEXT, RDATA - TEXT, 0x60000020);
        section(img, sec + 40, ".rdata", RDATA, size - RDATA, 0x40000040);
        // unwind table
        List<Fn> sorted = new ArrayList<>(fns);
        sorted.sort((x, y) -> Integer.compare(x.begin(), y.begin()));
        int u = UNWIND_AT;
        for (int i = 0; i < sorted.size(); i++) {
            Fn f = sorted.get(i);
            System.arraycopy(f.unwind(), 0, img, u, f.unwind().length);
            int at = PDATA_AT + i * 12;
            LeBytes.put32(img, at, f.begin());
            LeBytes.put32(img, at + 4, f.end());
            LeBytes.put32(img, at + 8, u);
            u = (u + f.unwind().length + 3) & ~3;
        }
        if (!sorted.isEmpty()) {
            LeBytes.put32(img, dirs + 24, PDATA_AT);
            LeBytes.put32(img, dirs + 28, sorted.size() * 12);
        }
        // exports
        if (!exports.isEmpty()) {
            int n = exports.size();
            int funcs = EXPORT_AT + 40;
            int names = funcs + n * 4;
            int ords = names + n * 4;
            int str = ords + n * 2;
            LeBytes.put32(img, EXPORT_AT + 16, 1);
            LeBytes.put32(img, EXPORT_AT + 20, n);
            LeBytes.put32(img, EXPORT_AT + 24, n);
            LeBytes.put32(img, EXPORT_AT + 28, funcs);
            LeBytes.put32(img, EXPORT_AT + 32, names);
            LeBytes.put32(img, EXPORT_AT + 36, ords);
            int i = 0;
            for (Map.Entry<String, Integer> e : exports.entrySet()) {
                LeBytes.put32(img, funcs + i * 4, e.getValue());
                LeBytes.put32(img, names + i * 4, str);
                LeBytes.put16(img, ords + i * 2, i);
                byte[] nb = e.getKey().getBytes(StandardCharsets.ISO_8859_1);
                System.arraycopy(nb, 0, img, str, nb.length);
                str += nb.length + 1;
                i++;
            }
            LeBytes.put32(img, dirs, EXPORT_AT);
            LeBytes.put32(img, dirs + 4, str - EXPORT_AT);
        }
        for (int[] c : calls) {
            img[c[0] - 5] = (byte) 0xE8;
            LeBytes.put32(img, c[0] - 4, 0);
        }
        for (Map.Entry<Integer, byte[]> w : writes.entrySet()) {
            System.arraycopy(w.getValue(), 0, img, w.getKey(), w.getValue().length);
        }
        return img;
    }

    private static void section(byte[] img, int at, String name, int rva, int vsize, int characteristics) {
        byte[] nb = name.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(nb, 0, img, at, nb.length);
        LeBytes.put32(img, at + 8, vsize);
        LeBytes.put32(img, at + 12, rva);
        LeBytes.put32(img, at + 16, vsize);
        LeBytes.put32(img, at + 20, rva);
        LeBytes.put32(img, at + 36, characteristics);
    }
}
