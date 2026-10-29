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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Windows dump file (.dmp, "MDMP"): a minidump or a full process dump, as written by Windows
 * Error Reporting, Task Manager or a debugger. Reads the streams a crash report needs (threads,
 * modules, the exception, the system, memory) with positional reads, so dumps of many gigabytes
 * are never loaded whole.
 */
public final class Minidump implements AutoCloseable {

    /** "MDMP", little endian. */
    public static final int SIGNATURE = 0x504d444d;

    // stream types
    static final int THREAD_LIST = 3;
    static final int MODULE_LIST = 4;
    static final int MEMORY_LIST = 5;
    static final int EXCEPTION = 6;
    static final int SYSTEM_INFO = 7;
    static final int MEMORY64_LIST = 9;
    static final int COMMENT_A = 10;
    static final int COMMENT_W = 11;
    static final int HANDLE_DATA = 12;
    static final int UNLOADED_MODULE_LIST = 14;
    static final int MISC_INFO = 15;
    static final int MEMORY_INFO_LIST = 16;
    static final int THREAD_INFO_LIST = 17;
    static final int THREAD_NAMES = 24;

    /** Dump flags (MINIDUMP_TYPE) that matter here. */
    public static final long WITH_FULL_MEMORY = 0x2;

    public enum Arch {
        X86, AMD64, ARM, ARM64, OTHER;

        static Arch of(int code) {
            return switch (code) {
                case 0 -> X86;
                case 9 -> AMD64;
                case 5 -> ARM;
                case 12 -> ARM64;
                default -> OTHER;
            };
        }

        public int pointerSize() {
            return this == X86 || this == ARM ? 4 : 8;
        }
    }

    public record SystemInfo(Arch arch, int processors, int productType, int major, int minor, int build,
                             String servicePack) {
        /** "Windows 11 (build 22631)", "Windows Server 2019 (build 17763)". */
        public String osName() {
            String name;
            boolean server = productType != 1;
            if (major == 10 && minor == 0) {
                if (server) {
                    name = build >= 26100 ? "Windows Server 2025" : build >= 20348 ? "Windows Server 2022"
                            : build >= 17763 ? "Windows Server 2019" : "Windows Server 2016";
                } else {
                    name = build >= 22000 ? "Windows 11" : "Windows 10";
                }
            } else if (major == 6 && minor == 3) {
                name = server ? "Windows Server 2012 R2" : "Windows 8.1";
            } else if (major == 6 && minor == 2) {
                name = server ? "Windows Server 2012" : "Windows 8";
            } else if (major == 6 && minor == 1) {
                name = server ? "Windows Server 2008 R2" : "Windows 7";
            } else if (major == 5 && minor == 1) {
                name = "Windows XP";
            } else {
                name = "Windows " + major + "." + minor;
            }
            return name + " (build " + build + (servicePack == null || servicePack.isBlank() ? "" : ", " + servicePack) + ")";
        }
    }

    /** One thread: its stack range and its saved registers (the raw CONTEXT). */
    public record Thread(int id, int suspendCount, int priority, long teb, long stackStart, long stackSize,
                         byte[] context) {}

    /** A loaded module (an exe or a dll). */
    public record Module(long base, long size, int timeDateStamp, int checksum, String path, String version,
                         String pdbName, String pdbId) {
        public String name() {
            int i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            return path.substring(i + 1);
        }

        public boolean contains(long address) {
            return Long.compareUnsigned(address, base) >= 0 && Long.compareUnsigned(address, base + size) < 0;
        }

        /** A Windows file (under \Windows\), not the program's own. */
        public boolean isSystem() {
            String p = path.toLowerCase(java.util.Locale.ROOT).replace('/', '\\');
            return p.contains("\\windows\\") || p.startsWith("\\systemroot\\");
        }
    }

    public record UnloadedModule(long base, long size, String path) {
        public String name() {
            int i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            return path.substring(i + 1);
        }
    }

    /** The exception that made the dump: code, address, its parameters, and the thread. */
    public record ExceptionInfo(int threadId, int code, int flags, long address, long[] parameters, byte[] context) {}

    /** A region of the address space (MINIDUMP_MEMORY_INFO). */
    public record Region(long base, long allocationBase, long size, int state, int protect, int type) {
        public static final int MEM_COMMIT = 0x1000;
        public static final int MEM_RESERVE = 0x2000;
        public static final int MEM_FREE = 0x10000;
        public static final int MEM_PRIVATE = 0x20000;
        public static final int MEM_MAPPED = 0x40000;
        public static final int MEM_IMAGE = 0x1000000;

        public boolean executable() {
            return (protect & 0xF0) != 0; // PAGE_EXECUTE*
        }
    }

    /** CPU time and start address of a thread (MINIDUMP_THREAD_INFO). */
    public record ThreadTimes(int id, long createTime, long kernelTime, long userTime, long startAddress) {}

    /** Open handles of one kind ("File": 120). */
    public record HandleCount(String type, int count) {}

    private final Path path;
    private final FileChannel ch;
    private final long fileSize;
    private final int streamCount;
    private final int version;
    private final long flags;
    private final long timeDateStamp;
    private final Map<Integer, long[]> streams = new HashMap<>(); // type → {rva, size}

    private SystemInfo system;
    private List<Thread> threads = List.of();
    private List<Module> modules = List.of();
    private Module mainModule;
    private List<UnloadedModule> unloaded = List.of();
    private ExceptionInfo exception;
    private int processId = -1;
    private long processCreateTime = -1;
    private long processUserTime = -1;
    private long processKernelTime = -1;
    private List<Region> regions = List.of();
    private final Map<Integer, String> threadNames = new HashMap<>();
    private final Map<Integer, ThreadTimes> threadTimes = new HashMap<>();
    private List<HandleCount> handles = List.of();
    private int handleTotal = -1;
    private final List<String> comments = new ArrayList<>();

    // memory: sorted ranges, address → file offset
    private long[] memStart = new long[0];
    private long[] memSize = new long[0];
    private long[] memOffset = new long[0];

    private Minidump(Path path, FileChannel ch) throws IOException {
        this.path = path;
        this.ch = ch;
        this.fileSize = ch.size();
        ByteBuffer h = read(0, 32);
        if (h.remaining() < 32 || h.getInt(0) != SIGNATURE) {
            throw new IOException("Not a Windows dump file (no MDMP signature): " + path.getFileName());
        }
        version = h.getInt(4);
        streamCount = h.getInt(8);
        long dirRva = Integer.toUnsignedLong(h.getInt(12));
        timeDateStamp = Integer.toUnsignedLong(h.getInt(20));
        flags = h.getLong(24);
        if (streamCount < 0 || streamCount > 10_000) {
            throw new IOException("Damaged dump file: " + streamCount + " streams");
        }
        ByteBuffer dir = read(dirRva, streamCount * 12);
        for (int i = 0; i + 12 <= dir.limit(); i += 12) {
            int type = dir.getInt(i);
            long size = Integer.toUnsignedLong(dir.getInt(i + 4));
            long rva = Integer.toUnsignedLong(dir.getInt(i + 8));
            if (type != 0 && !streams.containsKey(type)) {
                streams.put(type, new long[] {rva, size});
            }
        }
    }

    /** Whether the file starts like a Windows dump. */
    public static boolean looksLike(byte[] head) {
        return head.length >= 4 && (head[0] & 0xff) == 'M' && (head[1] & 0xff) == 'D' && (head[2] & 0xff) == 'M'
                && (head[3] & 0xff) == 'P';
    }

    public static Minidump open(Path path) throws IOException {
        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            Minidump d = new Minidump(path, ch);
            d.readAll();
            return d;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e instanceof IOException io ? io : new IOException("Damaged dump file: " + e, e);
        }
    }

    private void readAll() throws IOException {
        readSystem();
        readMemory();
        readThreads();
        readModules();
        readUnloaded();
        readException();
        readMisc();
        readRegions();
        readThreadNames();
        readThreadInfo();
        readHandles();
        readComments();
    }

    // ---------------------------------------------------------------- reading

    /** Reads up to n bytes at a file offset (fewer at the end of the file). */
    ByteBuffer read(long offset, int n) throws IOException {
        if (offset < 0 || n < 0 || offset >= fileSize) {
            return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        }
        int len = (int) Math.min(n, fileSize - offset);
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
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

    private ByteBuffer stream(int type) throws IOException {
        long[] s = streams.get(type);
        if (s == null) {
            return null;
        }
        return read(s[0], (int) Math.min(s[1], 64L << 20));
    }

    /** A MINIDUMP_STRING (UTF-16, its length in bytes first). */
    String string(long rva) throws IOException {
        if (rva <= 0) {
            return "";
        }
        ByteBuffer len = read(rva, 4);
        if (len.remaining() < 4) {
            return "";
        }
        int n = len.getInt(0);
        if (n <= 0 || n > 64 * 1024) {
            return "";
        }
        ByteBuffer s = read(rva + 4, n);
        byte[] a = new byte[s.remaining()];
        s.get(a);
        return new String(a, StandardCharsets.UTF_16LE);
    }

    private void readSystem() throws IOException {
        ByteBuffer b = stream(SYSTEM_INFO);
        if (b == null || b.limit() < 32) {
            system = new SystemInfo(Arch.OTHER, 0, 1, 0, 0, 0, "");
            return;
        }
        int arch = b.getShort(0) & 0xffff;
        int procs = b.get(6) & 0xff;
        int product = b.get(7) & 0xff;
        int major = b.getInt(8);
        int minor = b.getInt(12);
        int build = b.getInt(16);
        long csd = Integer.toUnsignedLong(b.getInt(24));
        system = new SystemInfo(Arch.of(arch), procs, product, major, minor, build, string(csd));
    }

    private void readMemory() throws IOException {
        List<long[]> ranges = new ArrayList<>(); // start, size, offset
        ByteBuffer m = stream(MEMORY_LIST);
        if (m != null && m.limit() >= 4) {
            int n = m.getInt(0);
            for (int i = 0; i < n && 4 + i * 16 + 16 <= m.limit(); i++) {
                int at = 4 + i * 16;
                long start = m.getLong(at);
                long size = Integer.toUnsignedLong(m.getInt(at + 8));
                long rva = Integer.toUnsignedLong(m.getInt(at + 12));
                if (size > 0) {
                    ranges.add(new long[] {start, size, rva});
                }
            }
        }
        long[] s64 = streams.get(MEMORY64_LIST);
        if (s64 != null) {
            ByteBuffer h = read(s64[0], 16);
            if (h.limit() == 16) {
                long n = h.getLong(0);
                long offset = h.getLong(8);
                if (n > 0 && n < 50_000_000L) {
                    ByteBuffer d = read(s64[0] + 16, (int) (n * 16));
                    for (int i = 0; i + 16 <= d.limit(); i += 16) {
                        long start = d.getLong(i);
                        long size = d.getLong(i + 8);
                        ranges.add(new long[] {start, size, offset});
                        offset += size;
                    }
                }
            }
        }
        ranges.sort((x, y) -> Long.compareUnsigned(x[0], y[0]));
        memStart = new long[ranges.size()];
        memSize = new long[ranges.size()];
        memOffset = new long[ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
            memStart[i] = ranges.get(i)[0];
            memSize[i] = ranges.get(i)[1];
            memOffset[i] = ranges.get(i)[2];
        }
    }

    private byte[] location(int size, long rva) throws IOException {
        if (size <= 0 || rva <= 0) {
            return new byte[0];
        }
        ByteBuffer b = read(rva, Math.min(size, 8192));
        byte[] a = new byte[b.remaining()];
        b.get(a);
        return a;
    }

    private void readThreads() throws IOException {
        ByteBuffer b = stream(THREAD_LIST);
        if (b == null || b.limit() < 4) {
            return;
        }
        int n = b.getInt(0);
        List<Thread> out = new ArrayList<>();
        for (int i = 0; i < n && 4 + (i + 1) * 48 <= b.limit(); i++) {
            int at = 4 + i * 48;
            int id = b.getInt(at);
            int suspend = b.getInt(at + 4);
            int priority = b.getInt(at + 12);
            long teb = b.getLong(at + 16);
            long stackStart = b.getLong(at + 24);
            long stackSize = Integer.toUnsignedLong(b.getInt(at + 32));
            int ctxSize = b.getInt(at + 40);
            long ctxRva = Integer.toUnsignedLong(b.getInt(at + 44));
            out.add(new Thread(id, suspend, priority, teb, stackStart, stackSize, location(ctxSize, ctxRva)));
        }
        threads = Collections.unmodifiableList(out);
    }

    private void readModules() throws IOException {
        ByteBuffer b = stream(MODULE_LIST);
        if (b == null || b.limit() < 4) {
            return;
        }
        int n = b.getInt(0);
        List<Module> out = new ArrayList<>();
        for (int i = 0; i < n && 4 + (i + 1) * 108 <= b.limit(); i++) {
            int at = 4 + i * 108;
            long base = b.getLong(at);
            long size = Integer.toUnsignedLong(b.getInt(at + 8));
            int checksum = b.getInt(at + 12);
            int stamp = b.getInt(at + 16);
            long nameRva = Integer.toUnsignedLong(b.getInt(at + 20));
            // VS_FIXEDFILEINFO at +24: signature, struct version, file version MS, LS
            String version = "";
            if (b.getInt(at + 24) == 0xFEEF04BD) {
                int ms = b.getInt(at + 32);
                int ls = b.getInt(at + 36);
                version = (ms >>> 16) + "." + (ms & 0xffff) + "." + (ls >>> 16) + "." + (ls & 0xffff);
            }
            int cvSize = b.getInt(at + 76);
            long cvRva = Integer.toUnsignedLong(b.getInt(at + 80));
            String pdbName = null;
            String pdbId = null;
            byte[] cv = location(Math.min(cvSize, 1024), cvRva);
            if (cv.length >= 24 && cv[0] == 'R' && cv[1] == 'S' && cv[2] == 'D' && cv[3] == 'S') {
                ByteBuffer c = ByteBuffer.wrap(cv).order(ByteOrder.LITTLE_ENDIAN);
                byte[] guid = Arrays.copyOfRange(cv, 4, 20);
                int age = c.getInt(20);
                int end = 24;
                while (end < cv.length && cv[end] != 0) {
                    end++;
                }
                pdbName = new String(cv, 24, end - 24, StandardCharsets.UTF_8);
                pdbId = guidText(guid) + Integer.toHexString(age).toUpperCase(java.util.Locale.ROOT);
            }
            out.add(new Module(base, size, stamp, checksum, string(nameRva), version, pdbName, pdbId));
        }
        mainModule = out.isEmpty() ? null : out.get(0); // the program comes first
        out.sort((x, y) -> Long.compareUnsigned(x.base(), y.base()));
        modules = Collections.unmodifiableList(out);
    }

    /** A GUID as the symbol server writes it: 32 hex digits, the first three parts byte-swapped. */
    static String guidText(byte[] g) {
        ByteBuffer b = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder s = new StringBuilder();
        s.append(String.format("%08X%04X%04X", b.getInt(0), b.getShort(4) & 0xffff, b.getShort(6) & 0xffff));
        for (int i = 8; i < 16; i++) {
            s.append(String.format("%02X", g[i] & 0xff));
        }
        return s.toString();
    }

    private void readUnloaded() throws IOException {
        ByteBuffer b = stream(UNLOADED_MODULE_LIST);
        if (b == null || b.limit() < 12) {
            return;
        }
        int header = b.getInt(0);
        int entry = b.getInt(4);
        int n = b.getInt(8);
        List<UnloadedModule> out = new ArrayList<>();
        for (int i = 0; i < n && entry >= 24 && header + (long) (i + 1) * entry <= b.limit(); i++) {
            int at = header + i * entry;
            out.add(new UnloadedModule(b.getLong(at), Integer.toUnsignedLong(b.getInt(at + 8)),
                    string(Integer.toUnsignedLong(b.getInt(at + 20)))));
        }
        unloaded = Collections.unmodifiableList(out);
    }

    private void readException() throws IOException {
        ByteBuffer b = stream(EXCEPTION);
        if (b == null || b.limit() < 168) {
            return;
        }
        int tid = b.getInt(0);
        int code = b.getInt(8);
        int fl = b.getInt(12);
        long address = b.getLong(24);
        int np = Math.min(15, Math.max(0, b.getInt(32)));
        long[] params = new long[np];
        for (int i = 0; i < np; i++) {
            params[i] = b.getLong(40 + i * 8);
        }
        int ctxSize = b.getInt(160);
        long ctxRva = Integer.toUnsignedLong(b.getInt(164));
        exception = new ExceptionInfo(tid, code, fl, address, params, location(ctxSize, ctxRva));
    }

    private void readMisc() throws IOException {
        ByteBuffer b = stream(MISC_INFO);
        if (b == null || b.limit() < 24) {
            return;
        }
        int f = b.getInt(4);
        if ((f & 0x1) != 0) {
            processId = b.getInt(8);
        }
        if ((f & 0x2) != 0) {
            processCreateTime = Integer.toUnsignedLong(b.getInt(12));
            processUserTime = Integer.toUnsignedLong(b.getInt(16));
            processKernelTime = Integer.toUnsignedLong(b.getInt(20));
        }
    }

    private void readRegions() throws IOException {
        long[] s = streams.get(MEMORY_INFO_LIST);
        if (s == null) {
            return;
        }
        ByteBuffer h = read(s[0], 16);
        if (h.limit() < 16) {
            return;
        }
        int header = h.getInt(0);
        int entry = h.getInt(4);
        long n = h.getLong(8);
        if (entry < 48 || n <= 0 || n > 10_000_000) {
            return;
        }
        ByteBuffer b = read(s[0] + header, (int) Math.min(n * entry, 512L << 20));
        List<Region> out = new ArrayList<>();
        for (int at = 0; at + 48 <= b.limit(); at += entry) {
            out.add(new Region(b.getLong(at), b.getLong(at + 8), b.getLong(at + 24), b.getInt(at + 32),
                    b.getInt(at + 36), b.getInt(at + 40)));
        }
        out.sort((x, y) -> Long.compareUnsigned(x.base(), y.base()));
        regions = Collections.unmodifiableList(out);
    }

    private void readThreadNames() throws IOException {
        ByteBuffer b = stream(THREAD_NAMES);
        if (b == null || b.limit() < 4) {
            return;
        }
        int n = b.getInt(0);
        for (int i = 0; i < n && 4 + (i + 1) * 12 <= b.limit(); i++) {
            int at = 4 + i * 12;
            String name = string(b.getLong(at + 4));
            if (!name.isBlank()) {
                threadNames.put(b.getInt(at), name);
            }
        }
    }

    private void readThreadInfo() throws IOException {
        ByteBuffer b = stream(THREAD_INFO_LIST);
        if (b == null || b.limit() < 12) {
            return;
        }
        int header = b.getInt(0);
        int entry = b.getInt(4);
        int n = b.getInt(8);
        for (int i = 0; i < n && entry >= 56 && header + (long) (i + 1) * entry <= b.limit(); i++) {
            int at = header + i * entry;
            int id = b.getInt(at);
            threadTimes.put(id, new ThreadTimes(id, b.getLong(at + 16), b.getLong(at + 32), b.getLong(at + 40),
                    b.getLong(at + 48)));
        }
    }

    private void readHandles() throws IOException {
        ByteBuffer b = stream(HANDLE_DATA);
        if (b == null || b.limit() < 16) {
            return;
        }
        int header = b.getInt(0);
        int desc = b.getInt(4);
        int n = b.getInt(8);
        if (desc < 32) {
            return;
        }
        Map<String, Integer> byType = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        int total = 0;
        for (int i = 0; i < n && header + (long) (i + 1) * desc <= b.limit(); i++) {
            int at = header + i * desc;
            long typeRva = Integer.toUnsignedLong(b.getInt(at + 8));
            String type = names.get(typeRva);
            if (type == null) {
                type = string(typeRva);
                type = type.isBlank() ? "Unknown" : type;
                names.put(typeRva, type);
            }
            byType.merge(type, 1, Integer::sum);
            total++;
        }
        List<HandleCount> out = new ArrayList<>();
        byType.forEach((k, v) -> out.add(new HandleCount(k, v)));
        out.sort((x, y) -> Integer.compare(y.count(), x.count()));
        handles = Collections.unmodifiableList(out);
        handleTotal = total;
    }

    private void readComments() throws IOException {
        ByteBuffer a = stream(COMMENT_A);
        if (a != null && a.limit() > 0) {
            byte[] x = new byte[a.remaining()];
            a.get(x);
            String s = new String(x, StandardCharsets.ISO_8859_1).replace("\0", "").strip();
            if (!s.isEmpty()) {
                comments.add(s);
            }
        }
        ByteBuffer w = stream(COMMENT_W);
        if (w != null && w.limit() > 0) {
            byte[] x = new byte[w.remaining()];
            w.get(x);
            String s = new String(x, StandardCharsets.UTF_16LE).replace("\0", "").strip();
            if (!s.isEmpty()) {
                comments.add(s);
            }
        }
    }

    // ---------------------------------------------------------------- memory

    private int rangeOf(long address) {
        int lo = 0;
        int hi = memStart.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(memStart[mid], address) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        int i = hi;
        if (i >= 0 && Long.compareUnsigned(address - memStart[i], memSize[i]) < 0) {
            return i;
        }
        return -1;
    }

    /** Whether the dump holds the byte at this address. */
    public boolean has(long address) {
        return rangeOf(address) >= 0;
    }

    /**
     * The process's memory at an address, as much of n bytes as the dump holds without a gap;
     * null when the dump holds none of it.
     */
    public byte[] memory(long address, int n) throws IOException {
        int i = rangeOf(address);
        if (i < 0 || n <= 0) {
            return null;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(n, 1 << 16));
        long at = address;
        int left = n;
        while (left > 0 && i >= 0 && i < memStart.length) {
            long off = at - memStart[i];
            if (Long.compareUnsigned(off, memSize[i]) >= 0) {
                break;
            }
            int take = (int) Math.min(left, memSize[i] - off);
            ByteBuffer b = read(memOffset[i] + off, take);
            byte[] a = new byte[b.remaining()];
            b.get(a);
            out.write(a, 0, a.length);
            if (a.length < take) {
                break;
            }
            left -= take;
            at += take;
            i = i + 1 < memStart.length && memStart[i + 1] == at ? i + 1 : -1;
        }
        byte[] r = out.toByteArray();
        return r.length == 0 ? null : r;
    }

    /** A pointer-sized value at an address; null when the dump doesn't hold it. */
    public Long pointer(long address) throws IOException {
        int size = system.arch().pointerSize();
        byte[] a = memory(address, size);
        if (a == null || a.length < size) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
        return size == 4 ? Integer.toUnsignedLong(b.getInt(0)) : b.getLong(0);
    }

    /** How many bytes of the process's memory the dump holds. */
    public long memoryBytes() {
        long t = 0;
        for (long s : memSize) {
            t += s;
        }
        return t;
    }

    // ---------------------------------------------------------------- what was read

    public Path path() {
        return path;
    }

    public long fileSize() {
        return fileSize;
    }

    public int version() {
        return version;
    }

    public long flags() {
        return flags;
    }

    /** A full process dump: all of the process's memory is in it. */
    public boolean isFull() {
        return (flags & WITH_FULL_MEMORY) != 0 || streams.containsKey(MEMORY64_LIST);
    }

    /** When it was written, seconds since 1970 (UTC). */
    public long time() {
        return timeDateStamp;
    }

    public SystemInfo system() {
        return system;
    }

    public List<Thread> threads() {
        return threads;
    }

    public List<Module> modules() {
        return modules;
    }

    /** The program (the .exe); null if the dump lists no modules. */
    public Module mainModule() {
        return mainModule;
    }

    public List<UnloadedModule> unloadedModules() {
        return unloaded;
    }

    /** The exception that made the dump; null when there was none (a dump of a hung program). */
    public ExceptionInfo exception() {
        return exception;
    }

    public int processId() {
        return processId;
    }

    /** When the process started, seconds since 1970; -1 if not known. */
    public long processCreateTime() {
        return processCreateTime;
    }

    public long processUserSeconds() {
        return processUserTime;
    }

    public long processKernelSeconds() {
        return processKernelTime;
    }

    public List<Region> regions() {
        return regions;
    }

    public String threadName(int id) {
        return threadNames.get(id);
    }

    public ThreadTimes threadTimes(int id) {
        return threadTimes.get(id);
    }

    public List<HandleCount> handles() {
        return handles;
    }

    /** All open handles; -1 if the dump doesn't list them. */
    public int handleTotal() {
        return handleTotal;
    }

    public List<String> comments() {
        return comments;
    }

    /** The module an address is in; null if none. */
    public Module moduleAt(long address) {
        int lo = 0;
        int hi = modules.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(modules.get(mid).base(), address) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return hi >= 0 && modules.get(hi).contains(address) ? modules.get(hi) : null;
    }

    public UnloadedModule unloadedAt(long address) {
        for (UnloadedModule u : unloaded) {
            if (Long.compareUnsigned(address, u.base()) >= 0 && Long.compareUnsigned(address, u.base() + u.size()) < 0) {
                return u;
            }
        }
        return null;
    }

    /** The region an address is in; null when the dump has no region list or it isn't in one. */
    public Region regionAt(long address) {
        int lo = 0;
        int hi = regions.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(regions.get(mid).base(), address) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (hi >= 0) {
            Region r = regions.get(hi);
            if (Long.compareUnsigned(address - r.base(), r.size()) < 0) {
                return r;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
