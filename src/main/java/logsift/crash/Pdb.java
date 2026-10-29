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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Microsoft PDB file (MSF 7.00): the function names of a module and, when the PDB has them,
 * source files and line numbers. Public PDBs (like Windows' own) have names only.
 */
public final class Pdb implements AutoCloseable {

    private static final byte[] MAGIC = "Microsoft C/C++ MSF 7.00\r\n\u001aDS\0\0\0".getBytes(StandardCharsets.ISO_8859_1);

    public record Symbol(long rva, long size, String name) {}

    public record Line(String file, int line) {}

    private final FileChannel ch;
    private final int blockSize;
    private final int[] streamSizes;
    private final int[][] streamBlocks;
    private String guid = "";
    private int age;
    private final List<long[]> sections = new ArrayList<>(); // rva, size (from the PDB's section headers)
    private Symbol[] symbols = new Symbol[0];
    private final List<int[]> modules = new ArrayList<>(); // stream, symBytes, c11Bytes, c13Bytes
    private final Map<String, Integer> namedStreams = new HashMap<>();
    // lines, loaded when first asked: sorted by rva
    private long[] lineRva;
    private int[] lineNo;
    private String[] lineFile;

    private Pdb(FileChannel ch) throws IOException {
        this.ch = ch;
        ByteBuffer sb = read(0, 56);
        byte[] m = new byte[MAGIC.length];
        sb.get(0, m);
        if (!Arrays.equals(m, MAGIC)) {
            throw new IOException("not an MSF 7.00 PDB");
        }
        blockSize = sb.getInt(32);
        int numDirBytes = sb.getInt(44);
        int blockMapAddr = sb.getInt(52);
        if (blockSize < 512 || blockSize > 65536 || numDirBytes <= 0 || numDirBytes > (64 << 20)) {
            throw new IOException("damaged PDB header");
        }
        int dirBlocks = (numDirBytes + blockSize - 1) / blockSize;
        ByteBuffer map = read((long) blockMapAddr * blockSize, dirBlocks * 4);
        byte[] dir = new byte[numDirBytes];
        for (int i = 0; i < dirBlocks; i++) {
            ByteBuffer blk = read((long) map.getInt(i * 4) * blockSize, blockSize);
            int n = Math.min(blockSize, numDirBytes - i * blockSize);
            blk.get(0, dir, i * blockSize, Math.min(n, blk.limit()));
        }
        ByteBuffer d = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN);
        int numStreams = d.getInt(0);
        if (numStreams <= 0 || numStreams > 1_000_000) {
            throw new IOException("damaged PDB directory");
        }
        streamSizes = new int[numStreams];
        streamBlocks = new int[numStreams][];
        int at = 4;
        for (int i = 0; i < numStreams; i++) {
            int s = d.getInt(at);
            streamSizes[i] = s == -1 ? 0 : s;
            at += 4;
        }
        for (int i = 0; i < numStreams; i++) {
            int n = (streamSizes[i] + blockSize - 1) / blockSize;
            int[] blocks = new int[n];
            for (int k = 0; k < n; k++) {
                blocks[k] = d.getInt(at);
                at += 4;
            }
            streamBlocks[i] = blocks;
        }
    }

    public static Pdb open(Path file) throws IOException {
        FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
        try {
            Pdb p = new Pdb(ch);
            p.readInfo();
            p.readDbi();
            return p;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("damaged PDB: " + e, e);
        }
    }

    private ByteBuffer read(long offset, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        long pos = offset;
        while (b.hasRemaining()) {
            int r = ch.read(b, pos);
            if (r < 0) {
                break;
            }
            pos += r;
        }
        b.flip();
        return b;
    }

    /** A whole stream; empty if it doesn't exist. */
    ByteBuffer stream(int index) throws IOException {
        if (index < 0 || index >= streamSizes.length || index == 0xffff) {
            return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        }
        int size = streamSizes[index];
        byte[] out = new byte[size];
        int[] blocks = streamBlocks[index];
        for (int i = 0; i < blocks.length; i++) {
            int n = Math.min(blockSize, size - i * blockSize);
            ByteBuffer blk = read((long) blocks[i] * blockSize, n);
            blk.get(0, out, i * blockSize, Math.min(n, blk.limit()));
        }
        return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void readInfo() throws IOException {
        ByteBuffer s = stream(1);
        if (s.limit() < 28) {
            return;
        }
        age = s.getInt(8);
        byte[] g = new byte[16];
        s.get(12, g);
        guid = Minidump.guidText(g);
        // the named streams ("/names" holds the source file names)
        try {
            int at = 28;
            int strLen = s.getInt(at);
            int strAt = at + 4;
            at = strAt + strLen;
            int size = s.getInt(at);
            at += 8; // size, capacity
            int presentWords = s.getInt(at);
            int[] present = new int[presentWords];
            for (int i = 0; i < presentWords; i++) {
                present[i] = s.getInt(at + 4 + i * 4);
            }
            at += 4 + presentWords * 4;
            int deletedWords = s.getInt(at);
            at += 4 + deletedWords * 4;
            int found = 0;
            for (int bit = 0; bit < presentWords * 32 && found < size; bit++) {
                if ((present[bit / 32] & (1 << (bit % 32))) == 0) {
                    continue;
                }
                int key = s.getInt(at);
                int value = s.getInt(at + 4);
                at += 8;
                found++;
                namedStreams.put(cString(s, strAt + key), value);
            }
        } catch (RuntimeException e) {
            // no named streams: names only, no source files
        }
    }

    private static String cString(ByteBuffer b, int at) {
        int end = at;
        while (end < b.limit() && b.get(end) != 0) {
            end++;
        }
        byte[] a = new byte[end - at];
        b.get(at, a);
        return new String(a, StandardCharsets.UTF_8);
    }

    private void readDbi() throws IOException {
        ByteBuffer d = stream(3);
        if (d.limit() < 64) {
            return;
        }
        int symRecords = d.getShort(20) & 0xffff;
        int modInfoSize = d.getInt(24);
        int secContr = d.getInt(28);
        int secMap = d.getInt(32);
        int srcInfo = d.getInt(36);
        int tsMap = d.getInt(40);
        int optDbg = d.getInt(48);
        int ec = d.getInt(52);
        // modules
        int at = 64;
        int end = 64 + modInfoSize;
        while (at + 64 <= end) {
            int stream = d.getShort(at + 34) & 0xffff;
            int symBytes = d.getInt(at + 36);
            int c11 = d.getInt(at + 40);
            int c13 = d.getInt(at + 44);
            modules.add(new int[] {stream, symBytes, c11, c13});
            int p = at + 64;
            while (p < end && d.get(p) != 0) {
                p++;
            }
            p++;
            while (p < end && d.get(p) != 0) {
                p++;
            }
            p++;
            at = (p + 3) & ~3;
        }
        // section headers, to turn segment:offset into an address in the image
        int dbgAt = 64 + modInfoSize + secContr + secMap + srcInfo + tsMap + ec;
        if (optDbg >= 12 && dbgAt + 12 <= d.limit()) {
            int secStream = d.getShort(dbgAt + 10) & 0xffff;
            ByteBuffer s = stream(secStream);
            for (int i = 0; i + 40 <= s.limit(); i += 40) {
                sections.add(new long[] {Integer.toUnsignedLong(s.getInt(i + 12)), Integer.toUnsignedLong(s.getInt(i + 8)),
                        s.getInt(i + 36)});
            }
        }
        Map<Long, Symbol> byRva = new HashMap<>();
        // public names
        ByteBuffer recs = stream(symRecords);
        forRecords(recs, 0, recs.limit(), (kind, p, len) -> {
            if (kind == 0x110E) { // S_PUB32
                int flags = recs.getInt(p);
                long rva = rva(recs.getShort(p + 8) & 0xffff, Integer.toUnsignedLong(recs.getInt(p + 4)));
                if (rva >= 0 && ((flags & 2) != 0 || isCode(recs.getShort(p + 8) & 0xffff))) {
                    byRva.putIfAbsent(rva, new Symbol(rva, 0, undecorate(cString(recs, p + 10))));
                }
            }
        });
        // functions from each module (sizes, and names already undecorated)
        for (int[] m : modules) {
            if (m[0] == 0xffff || m[1] <= 4) {
                continue;
            }
            ByteBuffer ms = stream(m[0]);
            forRecords(ms, 4, Math.min(m[1], ms.limit()), (kind, p, len) -> {
                if (kind == 0x1110 || kind == 0x110F || kind == 0x1147 || kind == 0x1146) {
                    long size = Integer.toUnsignedLong(ms.getInt(p + 12));
                    long rva = rva(ms.getShort(p + 32) & 0xffff, Integer.toUnsignedLong(ms.getInt(p + 28)));
                    if (rva >= 0) {
                        byRva.put(rva, new Symbol(rva, size, cString(ms, p + 35)));
                    }
                }
            });
        }
        List<Symbol> all = new ArrayList<>(byRva.values());
        all.sort((x, y) -> Long.compare(x.rva(), y.rva()));
        symbols = all.toArray(new Symbol[0]);
    }

    private boolean isCode(int segment) {
        return segment >= 1 && segment <= sections.size() && (sections.get(segment - 1)[2] & 0x20000000L) != 0;
    }

    private long rva(int segment, long offset) {
        if (segment < 1 || segment > sections.size()) {
            return -1;
        }
        return sections.get(segment - 1)[0] + offset;
    }

    @FunctionalInterface
    private interface RecordVisitor {
        void visit(int kind, int dataAt, int len);
    }

    private static void forRecords(ByteBuffer b, int from, int to, RecordVisitor v) {
        int at = from;
        while (at + 4 <= to) {
            int len = b.getShort(at) & 0xffff;
            int kind = b.getShort(at + 2) & 0xffff;
            if (len < 2 || at + 2 + len > to) {
                break;
            }
            try {
                v.visit(kind, at + 4, len - 2);
            } catch (RuntimeException e) {
                // a damaged record: skip it
            }
            at += 2 + len;
        }
    }

    /** "ABCD…" (32 hex digits, as in the module's CodeView record). */
    public String guid() {
        return guid;
    }

    public int age() {
        return age;
    }

    public int symbolCount() {
        return symbols.length;
    }

    public boolean hasLines() {
        loadLines();
        return lineRva.length > 0;
    }

    /** The function holding an address (relative to the image), or null. */
    public Symbol symbolAt(long rva) {
        int lo = 0;
        int hi = symbols.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (symbols[mid].rva() <= rva) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (hi < 0) {
            return null;
        }
        Symbol s = symbols[hi];
        if (s.size() > 0 && rva >= s.rva() + s.size()) {
            return null;
        }
        return s;
    }

    /** The source line of an address, or null. */
    public synchronized Line lineAt(long rva) {
        loadLines();
        int i = Arrays.binarySearch(lineRva, rva);
        if (i < 0) {
            i = -i - 2;
        }
        if (i < 0) {
            return null;
        }
        Symbol s = symbolAt(rva);
        if (s != null && lineRva[i] < s.rva()) {
            return null; // the nearest line is in another function
        }
        return new Line(lineFile[i], lineNo[i]);
    }

    private synchronized void loadLines() {
        if (lineRva != null) {
            return;
        }
        List<long[]> rows = new ArrayList<>(); // rva, line, file string index
        List<String> files = new ArrayList<>();
        Map<String, Integer> fileIndex = new HashMap<>();
        try {
            Integer namesIndex = namedStreams.get("/names");
            ByteBuffer names = namesIndex == null ? null : stream(namesIndex);
            for (int[] m : modules) {
                if (m[0] == 0xffff || m[3] <= 0) {
                    continue;
                }
                ByteBuffer ms = stream(m[0]);
                int c13 = m[1] + m[2];
                int end = Math.min(ms.limit(), c13 + m[3]);
                // the file checksums first: line blocks point into them
                Map<Integer, String> fileAt = new HashMap<>();
                int at = c13;
                while (at + 8 <= end) {
                    int kind = ms.getInt(at) & 0x7fffffff;
                    int len = ms.getInt(at + 4);
                    if (len < 0 || at + 8 + len > end) {
                        break;
                    }
                    if (kind == 0xF4) {
                        int p = at + 8;
                        while (p + 6 <= at + 8 + len) {
                            int nameOff = ms.getInt(p);
                            int sumLen = ms.get(p + 4) & 0xff;
                            String f = names != null && names.limit() >= 12 ? cString(names, 12 + nameOff) : "?";
                            fileAt.put(p - (at + 8), f);
                            p = (p + 6 + sumLen + 3) & ~3;
                        }
                    }
                    at = (at + 8 + len + 3) & ~3;
                }
                at = c13;
                while (at + 8 <= end) {
                    int kind = ms.getInt(at) & 0x7fffffff;
                    int len = ms.getInt(at + 4);
                    if (len < 0 || at + 8 + len > end) {
                        break;
                    }
                    if (kind == 0xF2 && len >= 12) {
                        int s = at + 8;
                        long base = rva(ms.getShort(s + 4) & 0xffff, Integer.toUnsignedLong(ms.getInt(s)));
                        boolean columns = (ms.getShort(s + 6) & 1) != 0;
                        int p = s + 12;
                        while (base >= 0 && p + 12 <= s + len) {
                            int nameIndex = ms.getInt(p);
                            int count = ms.getInt(p + 4);
                            int blockSize = ms.getInt(p + 8);
                            String f = fileAt.getOrDefault(nameIndex, "?");
                            Integer fi = fileIndex.get(f);
                            if (fi == null) {
                                fi = files.size();
                                files.add(f);
                                fileIndex.put(f, fi);
                            }
                            for (int k = 0; k < count && p + 12 + k * 8 + 8 <= s + len; k++) {
                                int off = ms.getInt(p + 12 + k * 8);
                                int line = ms.getInt(p + 16 + k * 8) & 0xffffff;
                                if (line != 0xfeefee && line != 0xf00f00 && line > 0) {
                                    rows.add(new long[] {base + Integer.toUnsignedLong(off), line, fi});
                                }
                            }
                            p += Math.max(blockSize, 12 + count * (columns ? 12 : 8));
                        }
                    }
                    at = (at + 8 + len + 3) & ~3;
                }
            }
        } catch (IOException | RuntimeException e) {
            // lines are a bonus
        }
        rows.sort((x, y) -> Long.compare(x[0], y[0]));
        lineRva = new long[rows.size()];
        lineNo = new int[rows.size()];
        lineFile = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            lineRva[i] = rows.get(i)[0];
            lineNo[i] = (int) rows.get(i)[1];
            lineFile[i] = files.get((int) rows.get(i)[2]);
        }
    }

    // ---------------------------------------------------------------- names

    /**
     * A readable name from a decorated one, for the common cases: {@code ?run@Worker@app@@QEAAXXZ}
     * → {@code app::Worker::run}; {@code _foo@8} → {@code foo}. Anything harder stays as it is.
     */
    static String undecorate(String n) {
        if (n == null || n.isEmpty()) {
            return n;
        }
        if (n.charAt(0) == '?') {
            if (n.contains("?$")) {
                return n; // templates: too hard to do well here
            }
            String body = n.substring(1);
            String special = null;
            if (body.startsWith("?0")) {
                special = "ctor";
                body = body.substring(2);
            } else if (body.startsWith("?1")) {
                special = "dtor";
                body = body.substring(2);
            } else if (body.startsWith("?")) {
                return n; // operators and other special names
            }
            int end = body.indexOf("@@");
            if (end <= 0) {
                return n;
            }
            String[] parts = body.substring(0, end).split("@");
            StringBuilder b = new StringBuilder();
            for (int i = parts.length - 1; i >= (special == null ? 0 : 0); i--) {
                if (parts[i].isEmpty() || !parts[i].matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    return n;
                }
                if (b.length() > 0) {
                    b.append("::");
                }
                b.append(parts[i]);
            }
            if (special != null) {
                String cls = parts[0];
                b.append("::").append(special.equals("dtor") ? "~" : "").append(cls);
            }
            return b.toString();
        }
        if ((n.charAt(0) == '_' || n.charAt(0) == '@') && n.matches("[_@][A-Za-z_][A-Za-z0-9_]*@\\d+")) {
            return n.substring(1, n.lastIndexOf('@')); // stdcall and fastcall
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
