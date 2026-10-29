package logsift.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny MSF 7.00 PDB: the info stream (GUID, age, "/names"), a DBI stream with one module and
 * the section headers, public names, the module's functions and its line numbers.
 */
final class PdbWriter {
    private static final int BLOCK = 512;

    record Pub(String name, int segment, int offset, boolean function) {}

    record Proc(String name, int segment, int offset, int size) {}

    record LineRow(int offset, int line) {}

    byte[] guid = Scenario.GUID;
    int age = 1;
    final List<Pub> publics = new ArrayList<>();
    final List<Proc> procs = new ArrayList<>();
    String file = "C:\\src\\order.cpp";
    int linesSegment = 1;
    int linesOffset;
    final List<LineRow> lines = new ArrayList<>();

    Path write(Path out) throws IOException {
        List<byte[]> streams = new ArrayList<>();
        streams.add(new byte[0]); // 0: old directory
        streams.add(info()); // 1
        streams.add(new byte[0]); // 2: types
        streams.add(null); // 3: DBI, below
        streams.add(new byte[0]); // 4: ids
        streams.add(symbols()); // 5: symbol records
        byte[] module = module();
        streams.add(module); // 6
        streams.add(sections()); // 7
        streams.add(names()); // 8: /names
        streams.set(3, dbi(module));

        // blocks: 0 superblock, 1–2 free block maps, then the streams, the directory, its block map
        LeBytes file = new LeBytes();
        file.zeros(BLOCK * 3);
        List<int[]> blocks = new ArrayList<>();
        for (byte[] s : streams) {
            int n = (s.length + BLOCK - 1) / BLOCK;
            int[] bl = new int[n];
            for (int i = 0; i < n; i++) {
                bl[i] = file.pos() / BLOCK;
                byte[] chunk = new byte[BLOCK];
                System.arraycopy(s, i * BLOCK, chunk, 0, Math.min(BLOCK, s.length - i * BLOCK));
                file.bytes(chunk);
            }
            blocks.add(bl);
        }
        LeBytes dir = new LeBytes();
        dir.u32(streams.size());
        for (byte[] s : streams) {
            dir.u32(s.length);
        }
        for (int[] bl : blocks) {
            for (int x : bl) {
                dir.u32(x);
            }
        }
        byte[] d = dir.toArray();
        int dirBlocks = (d.length + BLOCK - 1) / BLOCK;
        int[] dirAt = new int[dirBlocks];
        for (int i = 0; i < dirBlocks; i++) {
            dirAt[i] = file.pos() / BLOCK;
            byte[] chunk = new byte[BLOCK];
            System.arraycopy(d, i * BLOCK, chunk, 0, Math.min(BLOCK, d.length - i * BLOCK));
            file.bytes(chunk);
        }
        int mapAt = file.pos() / BLOCK;
        byte[] map = new byte[BLOCK];
        for (int i = 0; i < dirBlocks; i++) {
            LeBytes.put32(map, i * 4, dirAt[i]);
        }
        file.bytes(map);
        byte[] all = file.toArray();
        byte[] magic = "Microsoft C/C++ MSF 7.00\r\n\u001aDS\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(magic, 0, all, 0, magic.length);
        LeBytes.put32(all, 32, BLOCK);
        LeBytes.put32(all, 36, 1);
        LeBytes.put32(all, 40, all.length / BLOCK);
        LeBytes.put32(all, 44, d.length);
        LeBytes.put32(all, 52, mapAt);
        Files.write(out, all);
        return out;
    }

    private byte[] info() {
        LeBytes b = new LeBytes();
        b.u32(20000404).u32(0x12345678).u32(age).bytes(guid);
        byte[] strings = "/names\0".getBytes(StandardCharsets.ISO_8859_1);
        b.u32(strings.length).bytes(strings);
        b.u32(1).u32(1); // size, capacity
        b.u32(1).u32(1); // present: one word, bit 0
        b.u32(0); // deleted: no words
        b.u32(0).u32(8); // "/names" → stream 8
        return b.toArray();
    }

    private byte[] symbols() {
        LeBytes b = new LeBytes();
        for (Pub p : publics) {
            byte[] n = (p.name() + "\0").getBytes(StandardCharsets.UTF_8);
            int len = 2 + 4 + 4 + 2 + n.length;
            int pad = (4 - (len + 2) % 4) % 4;
            b.u16(len + pad).u16(0x110E).u32(p.function() ? 2 : 0).u32(p.offset()).u16(p.segment()).bytes(n).zeros(pad);
        }
        return b.toArray();
    }

    int symBytes;
    int c13Bytes;

    private byte[] module() {
        LeBytes b = new LeBytes();
        b.u32(4);
        for (Proc p : procs) {
            byte[] n = (p.name() + "\0").getBytes(StandardCharsets.UTF_8);
            int len = 2 + 35 + n.length;
            int pad = (4 - (len + 2) % 4) % 4;
            b.u16(len + pad).u16(0x1110).u32(0).u32(0).u32(0).u32(p.size()).u32(0).u32(0).u32(0).u32(p.offset())
                    .u16(p.segment()).u8(0).bytes(n).zeros(pad);
        }
        symBytes = b.pos();
        int c13 = b.pos();
        // file checksums: one file, its name at offset 1 of /names
        b.u32(0xF4).u32(8).u32(1).u8(0).u8(0).u16(0);
        // lines
        int count = lines.size();
        b.u32(0xF2).u32(12 + 12 + count * 8);
        b.u32(linesOffset).u16(linesSegment).u16(0).u32(0x100);
        b.u32(0).u32(count).u32(12 + count * 8);
        for (LineRow l : lines) {
            b.u32(l.offset()).u32(l.line() | 0x80000000L);
        }
        c13Bytes = b.pos() - c13;
        return b.toArray();
    }

    private static byte[] sections() {
        LeBytes b = new LeBytes();
        b.bytes(".text\0\0\0".getBytes(StandardCharsets.ISO_8859_1)).u32(0x2000).u32(0x1000).u32(0x2000).u32(0x400)
                .zeros(12).u32(0x60000020);
        b.bytes(".rdata\0\0".getBytes(StandardCharsets.ISO_8859_1)).u32(0x2000).u32(0x3000).u32(0x2000).u32(0x2400)
                .zeros(12).u32(0x40000040);
        return b.toArray();
    }

    private byte[] names() {
        LeBytes b = new LeBytes();
        byte[] buf = ("\0" + file + "\0").getBytes(StandardCharsets.UTF_8);
        b.u32(0xEFFEEFFEL).u32(1).u32(buf.length).bytes(buf);
        return b.toArray();
    }

    private byte[] dbi(byte[] module) {
        LeBytes mi = new LeBytes();
        mi.u32(0).zeros(28).u16(0).u16(6).u32(symBytes).u32(0).u32(c13Bytes).u16(1).u16(0).u32(0).u32(0).u32(0)
                .cstring("obj\\order.obj").cstring("obj\\order.obj").align(4);
        byte[] mods = mi.toArray();
        LeBytes b = new LeBytes();
        b.u32(-1).u32(19990903).u32(age).u16(0xffff).u16(0).u16(0xffff).u16(0).u16(5).u16(0)
                .u32(mods.length).u32(0).u32(0).u32(0).u32(0).u32(0).u32(22).u32(0).u16(0).u16(0x8664).u32(0);
        b.bytes(mods);
        // the optional debug header: 11 stream numbers, the section headers at 5
        for (int i = 0; i < 11; i++) {
            b.u16(i == 5 ? 7 : 0xffff);
        }
        return b.toArray();
    }
}
