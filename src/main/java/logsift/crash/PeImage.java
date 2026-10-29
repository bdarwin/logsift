package logsift.crash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Windows program image (an exe or dll) as loaded: its sections, exported function names, and
 * unwind table (.pdata, x64). Read from the dump's memory when the dump holds it (full dumps do),
 * or from the file on disk.
 */
public final class PeImage {

    /** Bytes of the image by their address relative to its base (RVA). */
    interface Source {
        /** As many of n bytes at rva as can be read; null if none. */
        byte[] read(long rva, int n) throws IOException;
    }

    public record Section(String name, long rva, long size, int characteristics) {
        public boolean executable() {
            return (characteristics & 0x20000000) != 0;
        }
    }

    /** An x64 function's range and unwind info, all relative to the image base. */
    record Function(long begin, long end, long unwind) {}

    private final Source src;
    private final boolean pe32plus;
    private final int machine;
    private final int timeDateStamp;
    private final List<Section> sections = new ArrayList<>();
    private long exportDirRva;
    private long exportDirSize;
    private long exceptionRva;
    private long exceptionSize;
    private long[] exportRva;
    private String[] exportName;
    private Function[] functions;

    private PeImage(Source src) throws IOException {
        this.src = src;
        byte[] dos = src.read(0, 64);
        if (dos == null || dos.length < 64 || dos[0] != 'M' || dos[1] != 'Z') {
            throw new IOException("no MZ header");
        }
        long peAt = Integer.toUnsignedLong(le(dos).getInt(0x3c));
        if (peAt > 4096) {
            throw new IOException("bad PE offset");
        }
        byte[] head = src.read(peAt, 24 + 240);
        if (head == null || head.length < 24 + 96 || head[0] != 'P' || head[1] != 'E' || head[2] != 0 || head[3] != 0) {
            throw new IOException("no PE header");
        }
        ByteBuffer h = le(head);
        machine = h.getShort(4) & 0xffff;
        timeDateStamp = h.getInt(8);
        int nSections = h.getShort(6) & 0xffff;
        int optSize = h.getShort(20) & 0xffff;
        int magic = h.getShort(24) & 0xffff;
        pe32plus = magic == 0x20b;
        int dirs = 24 + (pe32plus ? 112 : 96);
        if (head.length >= dirs + 8) {
            exportDirRva = Integer.toUnsignedLong(h.getInt(dirs));
            exportDirSize = Integer.toUnsignedLong(h.getInt(dirs + 4));
        }
        if (head.length >= dirs + 32) {
            exceptionRva = Integer.toUnsignedLong(h.getInt(dirs + 24));
            exceptionSize = Integer.toUnsignedLong(h.getInt(dirs + 28));
        }
        long secAt = peAt + 24 + optSize;
        byte[] secs = src.read(secAt, Math.min(nSections, 96) * 40);
        if (secs != null) {
            ByteBuffer s = le(secs);
            for (int i = 0; i + 40 <= secs.length; i += 40) {
                int end = i;
                while (end < i + 8 && secs[end] != 0) {
                    end++;
                }
                String name = new String(secs, i, end - i, StandardCharsets.ISO_8859_1);
                sections.add(new Section(name, Integer.toUnsignedLong(s.getInt(i + 12)),
                        Integer.toUnsignedLong(s.getInt(i + 8)), s.getInt(i + 36)));
            }
        }
    }

    private static ByteBuffer le(byte[] a) {
        return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** The image at base in the dump's memory; null if the dump doesn't hold its headers. */
    static PeImage inDump(Minidump d, long base) {
        if (!d.has(base)) {
            return null;
        }
        try {
            return new PeImage((rva, n) -> d.memory(base + rva, n));
        } catch (IOException e) {
            return null;
        }
    }

    /** The image from its file; the file stays closed between reads (each read opens it). */
    static PeImage fromFile(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            byte[] all = new byte[(int) Math.min(size, 512L << 20)];
            ByteBuffer b = ByteBuffer.wrap(all);
            while (b.hasRemaining() && ch.read(b) > 0) {
                // whole file: images are small next to dumps
            }
            List<long[]> map = new ArrayList<>(); // rva, size, file offset
            Source raw = (off, n) -> off < 0 || off >= all.length ? null
                    : Arrays.copyOfRange(all, (int) off, (int) Math.min(all.length, off + n));
            PeImage headers = new PeImage(raw);
            ByteBuffer le = le(all);
            long peAt = Integer.toUnsignedLong(le.getInt(0x3c));
            int optSize = le.getShort((int) peAt + 20) & 0xffff;
            int secAt = (int) (peAt + 24 + optSize);
            for (int i = 0; i < headers.sections.size(); i++) {
                int at = secAt + i * 40;
                map.add(new long[] {Integer.toUnsignedLong(le.getInt(at + 12)), Integer.toUnsignedLong(le.getInt(at + 16)),
                        Integer.toUnsignedLong(le.getInt(at + 20))});
            }
            Source mapped = (rva, n) -> {
                if (rva < 4096) {
                    return raw.read(rva, n);
                }
                for (long[] m : map) {
                    if (rva >= m[0] && rva < m[0] + m[1]) {
                        long off = m[2] + (rva - m[0]);
                        return raw.read(off, (int) Math.min(n, m[1] - (rva - m[0])));
                    }
                }
                return null;
            };
            return new PeImage(mapped);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public boolean is64() {
        return pe32plus;
    }

    /** The link time stamp: the same build of a file has the same one. */
    public int timeDateStamp() {
        return timeDateStamp;
    }

    public int machine() {
        return machine;
    }

    public List<Section> sections() {
        return sections;
    }

    /** The section an RVA is in; null if none. */
    public Section sectionAt(long rva) {
        for (Section s : sections) {
            if (rva >= s.rva() && rva < s.rva() + Math.max(s.size(), 1)) {
                return s;
            }
        }
        return null;
    }

    byte[] read(long rva, int n) throws IOException {
        return src.read(rva, n);
    }

    // ---------------------------------------------------------------- exports

    private synchronized void loadExports() {
        if (exportRva != null) {
            return;
        }
        exportRva = new long[0];
        exportName = new String[0];
        if (exportDirRva == 0 || exportDirSize < 40) {
            return;
        }
        try {
            byte[] dir = src.read(exportDirRva, 40);
            if (dir == null || dir.length < 40) {
                return;
            }
            ByteBuffer d = le(dir);
            int nFunctions = d.getInt(20);
            int nNames = d.getInt(24);
            long functionsAt = Integer.toUnsignedLong(d.getInt(28));
            long namesAt = Integer.toUnsignedLong(d.getInt(32));
            long ordinalsAt = Integer.toUnsignedLong(d.getInt(36));
            if (nFunctions <= 0 || nFunctions > 200_000 || nNames < 0 || nNames > 200_000) {
                return;
            }
            byte[] fa = src.read(functionsAt, nFunctions * 4);
            byte[] na = src.read(namesAt, nNames * 4);
            byte[] oa = src.read(ordinalsAt, nNames * 2);
            if (fa == null || na == null || oa == null) {
                return;
            }
            ByteBuffer f = le(fa);
            ByteBuffer nb = le(na);
            ByteBuffer o = le(oa);
            List<long[]> list = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < nNames && i * 4 + 4 <= na.length && i * 2 + 2 <= oa.length; i++) {
                int ord = o.getShort(i * 2) & 0xffff;
                if (ord * 4 + 4 > fa.length) {
                    continue;
                }
                long rva = Integer.toUnsignedLong(f.getInt(ord * 4));
                if (rva == 0 || rva >= exportDirRva && rva < exportDirRva + exportDirSize) {
                    continue; // a forwarder ("NTDLL.RtlAllocateHeap"), not code here
                }
                String name = cString(Integer.toUnsignedLong(nb.getInt(i * 4)));
                if (name != null && !name.isEmpty()) {
                    list.add(new long[] {rva, names.size()});
                    names.add(name);
                }
            }
            list.sort((x, y) -> Long.compare(x[0], y[0]));
            long[] r = new long[list.size()];
            String[] n = new String[list.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = list.get(i)[0];
                n[i] = names.get((int) list.get(i)[1]);
            }
            exportRva = r;
            exportName = n;
        } catch (IOException | RuntimeException e) {
            // no names, then
        }
    }

    private String cString(long rva) throws IOException {
        byte[] a = src.read(rva, 256);
        if (a == null) {
            return null;
        }
        int end = 0;
        while (end < a.length && a[end] != 0) {
            end++;
        }
        return new String(a, 0, end, StandardCharsets.ISO_8859_1);
    }

    /** The exported function at or before rva: {rva, name}; null if none. */
    Object[] exportBefore(long rva) {
        loadExports();
        int i = Arrays.binarySearch(exportRva, rva);
        if (i < 0) {
            i = -i - 2;
        }
        return i < 0 ? null : new Object[] {exportRva[i], exportName[i]};
    }

    public int exportCount() {
        loadExports();
        return exportRva.length;
    }

    // ---------------------------------------------------------------- unwind table (x64)

    private synchronized void loadFunctions() {
        if (functions != null) {
            return;
        }
        functions = new Function[0];
        if (exceptionRva == 0 || exceptionSize < 12 || exceptionSize > (64L << 20)) {
            return;
        }
        try {
            byte[] a = src.read(exceptionRva, (int) exceptionSize);
            if (a == null) {
                return;
            }
            ByteBuffer b = le(a);
            List<Function> out = new ArrayList<>(a.length / 12);
            for (int i = 0; i + 12 <= a.length; i += 12) {
                long begin = Integer.toUnsignedLong(b.getInt(i));
                long end = Integer.toUnsignedLong(b.getInt(i + 4));
                long unwind = Integer.toUnsignedLong(b.getInt(i + 8));
                if (begin != 0 && end > begin) {
                    out.add(new Function(begin, end, unwind));
                }
            }
            out.sort((x, y) -> Long.compare(x.begin(), y.begin()));
            functions = out.toArray(new Function[0]);
        } catch (IOException | RuntimeException e) {
            // no table: leaf frames only
        }
    }

    /** The function holding rva, from the unwind table; null if it isn't in it. */
    Function functionAt(long rva) {
        loadFunctions();
        int lo = 0;
        int hi = functions.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (functions[mid].begin() <= rva) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return hi >= 0 && rva < functions[hi].end() ? functions[hi] : null;
    }

    public boolean hasUnwindTable() {
        loadFunctions();
        return functions.length > 0;
    }
}
