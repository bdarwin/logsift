package logsift.heap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * A Java heap dump (.hprof), read into an object graph: which object references which, which
 * object holds which (dominators: freeing the holder frees everything it holds), how many bytes
 * each object keeps alive (retained size), and the shortest chain of references from a GC root
 * to each object (why it is alive).
 *
 * <p>Weak, soft and phantom references don't keep objects alive here (as in the JVM when memory
 * runs low); what only they reach is counted as not strongly reachable.
 *
 * <p>Sizes are what the JVM uses: object headers of 12 bytes and references of 4 (compressed
 * pointers, the default for heaps under 32 GB), objects aligned to 8 bytes.
 */
public final class HeapDump implements AutoCloseable {
    static final int T_OBJECT = 2;
    static final int T_BOOLEAN = 4;
    static final int T_CHAR = 5;
    static final int T_FLOAT = 6;
    static final int T_DOUBLE = 7;
    static final int T_BYTE = 8;
    static final int T_SHORT = 9;
    static final int T_INT = 10;
    static final int T_LONG = 11;

    private static final String[] PRIM_NAMES = {null, null, null, null, "boolean", "char", "float", "double", "byte",
        "short", "int", "long"};

    /** Reports what is being done and how far it is (0..1). */
    public interface Listener {
        void progress(String stage, double fraction);
    }

    /** Where a GC root comes from. */
    enum RootKind { UNKNOWN, JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS, THREAD_BLOCK,
        MONITOR, THREAD_OBJECT }

    record Root(RootKind kind, int threadSerial, int frame) {}

    /** A class in the dump (or a made-up one for primitive arrays). */
    static final class ClassInfo {
        long id;
        String name;
        long superId;
        long loaderId;
        long[] staticNames = new long[0];
        int[] staticTypes = new int[0];
        long[] staticValues = new long[0];
        long[] fieldNames = new long[0];
        int[] fieldTypes = new int[0];
        int superIdx = -1;
        int node = -1;
        int primType; // for primitive arrays
        // the layout of an instance's data in the dump (this class's fields first, then its super's)
        int[] refPos;
        String[] refNames;
        int shallow;
        int referentPos = -1; // java.lang.ref.Reference.referent, not a strong reference
        long count;
        long shallowSum;
        long retainedSum;

        boolean isArray() {
            return name.endsWith("[]");
        }
    }

    record Frame(String method, String className, String file, int line) {}

    /** One class: its objects and their sizes. */
    public record ClassStat(String name, long count, long shallow, long retained) {}

    /** One link in a chain of references: an object and how the previous one refers to it. */
    public record Step(int node, String via) {}

    private final Path file;
    private final Store store;
    private final FileChannel channel;
    private int idSize;
    private long timestamp;
    private boolean compressed = true;
    private int header = 12;
    private int arrayHeader = 16; // before the elements (for 8-byte elements: rounded up to 8)
    private int refSize = 4;
    // the first objects in dump order, to work out the JVM's object layout: id, and what it is
    // (an instance: its class id; an array: -(type << 33 | length)); 0: a class in between
    private long[] sampleIds = new long[1 << 20];
    private long[] sampleKinds = new long[1 << 20];
    private int samples;
    private Layout layout = new Layout(true, 12, 16, 4);

    private final Map<Long, String> strings = new HashMap<>();
    private final Map<Long, Long> classNameIds = new HashMap<>();
    private final Map<Integer, Long> classSerials = new HashMap<>();
    private final Map<Long, Frame> frameById = new HashMap<>();
    private final Map<Integer, long[]> traces = new HashMap<>();
    private final Map<Integer, long[]> threads = new HashMap<>(); // serial -> {object id, stack serial}
    final List<ClassInfo> classes = new ArrayList<>();
    private final Map<Long, Integer> classById = new HashMap<>();
    private final Map<Integer, ClassInfo> classByNode = new HashMap<>();
    private final int[] primClass = new int[12];
    private int classClass = -1;
    private final List<long[]> segments = new ArrayList<>();
    private final List<Long> rootIds = new ArrayList<>();
    private final List<Root> rootInfos = new ArrayList<>();
    private final Map<Integer, Root> rootOf = new HashMap<>();

    int n; // objects; node n is the made-up root that refers to every GC root
    private Store.Longs ids; // while loading
    private Store.Rising offsets;
    private Store.Ints classOf;
    private Store.Ints shallow;
    private Store.Rising succStart;
    private Store.Ints succ;
    private Store.Ints idom;
    private Store.Sizes retained;
    private Store.Ints bfsParent; // node + 1; 0: not reached
    private Store.Ints domStart;
    private Store.Ints domKids;
    private long totalBytes;
    private long reachableBytes;
    private int reachableCount;
    private IdIndex idMap;
    private long minId = Long.MAX_VALUE;
    private long maxId;
    private HprofReader random;

    private HeapDump(Path file, Store store) throws IOException {
        this.file = file;
        this.store = store;
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
    }

    /**
     * Reads a heap dump and works out who holds what. With {@code workDir}, the index goes to
     * files there (for dumps too big for memory); null: in memory.
     */
    public static HeapDump open(Path file, Path workDir, Listener listener, BooleanSupplier cancel)
            throws IOException {
        Store store = workDir == null ? Store.inMemory() : Store.onDisk(workDir);
        HeapDump d = new HeapDump(file, store);
        try {
            d.load(throttled(listener), cancel == null ? () -> false : cancel);
            return d;
        } catch (IOException | RuntimeException e) {
            d.close();
            throw e;
        }
    }

    /** Passes progress on at most every 0.5% (or when the stage changes). */
    private static Listener throttled(Listener l) {
        if (l == null) {
            return (s, f) -> { };
        }
        return new Listener() {
            private String stage;
            private double last = -1;

            @Override
            public void progress(String s, double f) {
                if (!s.equals(stage) || f - last >= 0.005 || f >= 1) {
                    stage = s;
                    last = f;
                    l.progress(s, f);
                }
            }
        };
    }

    /** True when the index of a dump this big had better be on disk than in memory. */
    public static boolean needsDisk(long fileBytes) {
        if (Boolean.getBoolean("logsift.heap.onDisk")) {
            return true;
        }
        // at most about a byte of index per byte of dump
        return fileBytes > Runtime.getRuntime().maxMemory() / 3;
    }

    /** Disk space for the index of a dump this big, at the most (12 GB of dump took 11 GB). */
    public static long diskNeeded(long fileBytes) {
        return fileBytes + (256L << 20);
    }

    /**
     * Null when there is room in {@code dir}'s disk for the indexes of dumps this big; else what to
     * tell the person.
     */
    public static String roomProblem(Path dir, long... fileBytes) {
        long need = 0;
        for (long b : fileBytes) {
            if (needsDisk(b)) {
                need += diskNeeded(b);
            }
        }
        if (need == 0) {
            return null;
        }
        try {
            Path p = dir;
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            if (p == null) {
                return null;
            }
            long free = Files.getFileStore(p).getUsableSpace();
            if (free >= need) {
                return null;
            }
            return "Reading this needs about " + HeapFindings.bytes(need) + " of free disk space for its index (in "
                    + dir + "), and there is only " + HeapFindings.bytes(free) + " free. Free up some space and try"
                    + " again.";
        } catch (IOException e) {
            return null; // can't tell: try
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            // closing
        }
        store.close();
    }

    // ---------------------------------------------------------------- loading

    private void load(Listener l, BooleanSupplier cancel) throws IOException {
        long size = channel.size();
        HprofReader r = new HprofReader(channel, 0);
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = r.u1()) != 0) {
            head.append((char) c);
            if (head.length() > 40) {
                throw new IOException("not a heap dump (.hprof)");
            }
        }
        if (!head.toString().startsWith("JAVA PROFILE")) {
            throw new IOException("not a heap dump (.hprof): it starts with \"" + head + "\"");
        }
        idSize = r.u4();
        if (idSize != 4 && idSize != 8) {
            throw new IOException("not a heap dump: ids of " + idSize + " bytes");
        }
        r.idSize = idSize;
        timestamp = r.u8();
        if (idSize == 4) { // a 32-bit JVM
            compressed = false;
            header = 8;
            arrayHeader = 12;
            refSize = 4;
        }
        ids = store.longs("ids");
        offsets = store.rising("offsets");

        // pass 1: names, classes, roots; number the objects
        l.progress("Reading the heap dump", 0);
        while (!r.atEnd()) {
            int tag = r.u1();
            r.u4(); // time
            long len = r.u4u();
            long start = r.position();
            switch (tag) {
                case 0x01 -> {
                    long id = r.id();
                    byte[] b = new byte[(int) (len - idSize)];
                    r.bytes(b, 0, b.length);
                    strings.put(id, new String(b, StandardCharsets.UTF_8));
                }
                case 0x02 -> {
                    int serial = r.u4();
                    long classId = r.id();
                    r.u4();
                    classNameIds.put(classId, r.id());
                    classSerials.put(serial, classId);
                }
                case 0x04 -> {
                    long frameId = r.id();
                    long method = r.id();
                    r.id(); // signature
                    long source = r.id();
                    int classSerial = r.u4();
                    int line = r.u4();
                    Long cid = classSerials.get(classSerial);
                    frameById.put(frameId, new Frame(name(method), cid == null ? "?" : javaName(cid),
                            source == 0 ? null : strings.get(source), line));
                }
                case 0x05 -> {
                    int serial = r.u4();
                    r.u4(); // thread serial
                    int k = r.u4();
                    long[] frames = new long[k];
                    for (int i = 0; i < k; i++) {
                        frames[i] = r.id();
                    }
                    traces.put(serial, frames);
                }
                case 0x0C, 0x1C -> {
                    segments.add(new long[] {start, len});
                    scanSegment(r, start + len, cancel);
                }
                default -> r.skip(len);
            }
            r.seek(start + len);
            l.progress("Reading the heap dump", (double) r.position() / size);
        }
        if (n == 0) {
            throw new IOException("this .hprof file has no heap in it");
        }
        // class names and layouts
        for (ClassInfo ci : classes) {
            Long nameId = classNameIds.get(ci.id);
            ci.name = nameId == null ? "class@" + Long.toHexString(ci.id) : readable(name(nameId));
        }
        for (int i = 0; i < classes.size(); i++) {
            ClassInfo ci = classes.get(i);
            Integer s = classById.get(ci.superId);
            ci.superIdx = s == null ? -1 : s;
            if (ci.name.equals("java.lang.Class")) {
                classClass = i;
            }
        }
        if (idSize == 8) {
            objectLayout();
        }
        dropSamples();
        for (ClassInfo ci : classes) {
            layout(ci);
            classByNode.put(ci.node, ci);
        }

        // id → node
        l.progress("Linking objects", 0);
        idMap = IdIndex.of(store, ids, n, cancel);
        ids.free();
        ids = null;

        // pass 2: the references between objects
        classOf = store.ints("class-of").size(n + 1);
        shallow = store.ints("shallow").size(n + 1);
        succStart = store.rising("succ-start").size(n + 2);
        succ = store.ints("succ");
        long[] edges = {0};
        int[] node = {0};
        long done = 0;
        long all = 0;
        for (long[] seg : segments) {
            all += seg[1];
        }
        for (long[] seg : segments) {
            r.seek(seg[0]);
            long end = seg[0] + seg[1];
            linkSegment(r, end, node, edges, cancel, f -> { });
            done += seg[1];
            l.progress("Linking objects", (double) done / all);
        }
        // the made-up root: every class, and every GC root
        int root = n;
        succStart.set(root, edges[0]);
        BitSet seen = new BitSet(n);
        for (ClassInfo ci : classes) {
            if (ci.node >= 0 && !seen.get(ci.node)) {
                seen.set(ci.node);
                succ.set(edges[0]++, ci.node);
            }
        }
        for (int i = 0; i < rootIds.size(); i++) {
            int v = idMap.get(rootIds.get(i));
            if (v < 0) {
                continue;
            }
            rootOf.putIfAbsent(v, rootInfos.get(i));
            if (!seen.get(v)) {
                seen.set(v);
                succ.set(edges[0]++, v);
            }
        }
        succStart.set(root + 1, edges[0]);
        idMap.free();
        idMap = null;
        for (int v = 0; v < n; v++) {
            totalBytes += shallow.get(v);
        }

        // predecessors
        l.progress("Finding what holds what", 0);
        Store.Ints count = store.ints("pred-count").size(n + 1);
        for (long e = 0; e < edges[0]; e++) {
            count.add(succ.get(e), 1);
        }
        Store.Rising predStart = store.rising("pred-start").size(n + 2);
        long sum = 0;
        for (int v = 0; v <= n; v++) {
            predStart.set(v, sum);
            sum += count.get(v);
            count.set(v, 0); // from now: how many are filled in
        }
        predStart.set(n + 1, sum);
        Store.Ints pred = store.ints("pred").size(Math.max(1, edges[0]));
        for (int v = 0; v <= n; v++) {
            for (long e = succStart.get(v), end = succStart.get(v + 1); e < end; e++) {
                int w = succ.get(e);
                int k = count.get(w);
                pred.set(predStart.get(w) + k, v);
                count.set(w, k + 1);
            }
            check(cancel, v);
        }
        count.free();

        // dominators and retained sizes
        Dominators.Result dom = Dominators.compute(store, n + 1, root, succStart, succ, predStart, pred,
                f -> l.progress("Finding what holds what", f * 0.9));
        pred.free();
        predStart.free();
        idom = dom.idom;
        reachableCount = dom.reached - 1;
        retained = store.sizes("retained").size(n + 1);
        for (int i = dom.reached - 1; i > 0; i--) {
            int v = dom.order.get(i);
            long rv = retained.get(v) + shallow.get(v);
            retained.set(v, rv);
            retained.add(idom.get(v), rv);
        }
        reachableBytes = retained.get(root);
        // the dominator tree, as lists of children
        domStart = store.ints("dom-start").size(n + 2);
        for (int v = 0; v < n; v++) {
            int d = idom.get(v);
            if (d >= 0) {
                domStart.add(d + 1, 1);
            }
        }
        for (int v = 1; v <= n + 1; v++) {
            domStart.add(v, domStart.get(v - 1));
        }
        domKids = store.ints("dom-kids").size(Math.max(1, reachableCount));
        Store.Ints at = store.ints("dom-fill").size(n + 1);
        for (int v = 0; v <= n; v++) {
            at.set(v, domStart.get(v));
        }
        for (int v = 0; v < n; v++) {
            int d = idom.get(v);
            if (d >= 0) {
                int k = at.get(d);
                domKids.set(k, v);
                at.set(d, k + 1);
            }
        }
        at.free();
        dom.order.free();

        // shortest paths from the roots (why an object is alive)
        l.progress("Finding why objects are alive", 0);
        bfsParent = store.ints("bfs-parent").size(n + 1);
        Store.Ints queue = store.ints("bfs-queue").size(n + 1);
        long head0 = 0;
        long tail = 0;
        queue.set(tail++, root);
        bfsParent.set(root, root + 1);
        while (head0 < tail) {
            int v = queue.get(head0++);
            for (long e = succStart.get(v), end = succStart.get(v + 1); e < end; e++) {
                int w = succ.get(e);
                if (bfsParent.get(w) == 0) {
                    bfsParent.set(w, v + 1);
                    queue.set(tail++, w);
                }
            }
            if ((head0 & 0xFFFFF) == 0) {
                check(cancel, 0);
                l.progress("Finding why objects are alive", (double) head0 / n);
            }
        }
        queue.free();

        // per class
        for (int v = 0; v < n; v++) {
            ClassInfo ci = classes.get(classOf.get(v));
            ci.count++;
            ci.shallowSum += shallow.get(v);
            int d = idom.get(v);
            // retained by the class: objects not held by another object of the same class (a
            // class's static fields count for that class, not for java.lang.Class)
            if (d >= 0 && (d == root || classOf.get(d) != classOf.get(v)) && !classByNode.containsKey(v)) {
                ci.retainedSum += retained.get(v);
            }
        }
        random = new HprofReader(channel, 0, 64 << 10);
        random.idSize = idSize;
        l.progress("Done", 1);
    }

    private static void check(BooleanSupplier cancel, long i) {
        if ((i & 0xFFFF) == 0 && cancel.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    /** Pass 1 over a heap segment: classes, roots, and a node number for every object. */
    private void scanSegment(HprofReader r, long end, BooleanSupplier cancel) throws IOException {
        while (r.position() < end) {
            long at = r.position();
            int sub = r.u1();
            switch (sub) {
                case 0xFF -> root(r.id(), RootKind.UNKNOWN, 0, 0);
                case 0x01 -> {
                    root(r.id(), RootKind.JNI_GLOBAL, 0, 0);
                    r.id();
                }
                case 0x02 -> root(r.id(), RootKind.JNI_LOCAL, r.u4(), r.u4());
                case 0x03 -> root(r.id(), RootKind.JAVA_FRAME, r.u4(), r.u4());
                case 0x04 -> root(r.id(), RootKind.NATIVE_STACK, r.u4(), -1);
                case 0x05 -> root(r.id(), RootKind.STICKY_CLASS, 0, 0);
                case 0x06 -> root(r.id(), RootKind.THREAD_BLOCK, r.u4(), -1);
                case 0x07 -> root(r.id(), RootKind.MONITOR, 0, 0);
                case 0x08 -> {
                    long id = r.id();
                    int serial = r.u4();
                    int stack = r.u4();
                    threads.put(serial, new long[] {id, stack});
                    root(id, RootKind.THREAD_OBJECT, serial, -1);
                }
                case 0x20 -> {
                    ClassInfo ci = readClass(r);
                    ci.node = n;
                    classById.put(ci.id, classes.size());
                    classes.add(ci);
                    sample(ci.id, 0);
                    object(ci.id, at);
                }
                case 0x21 -> {
                    long id = r.id();
                    r.u4();
                    long cid = r.id();
                    r.skip(r.u4u());
                    sample(id, cid);
                    object(id, at);
                }
                case 0x22 -> {
                    long id = r.id();
                    r.u4();
                    long len = r.u4u();
                    r.id();
                    r.skip(len * idSize);
                    sample(id, -((long) T_OBJECT << 33 | len));
                    object(id, at);
                }
                case 0x23 -> {
                    long id = r.id();
                    r.u4();
                    long len = r.u4u();
                    int type = r.u1();
                    r.skip(len * sizeOf(type, idSize));
                    sample(id, -((long) type << 33 | len));
                    object(id, at);
                }
                default -> throw new IOException("unsupported heap dump record 0x" + Integer.toHexString(sub)
                        + " at byte " + at);
            }
            check(cancel, n);
        }
    }

    /** An array's size in the JVM. */
    long arraySize(int type, long len) {
        if (idSize == 4) {
            return align(arrayHeader + len * (type == T_OBJECT ? refSize : sizeOf(type, idSize)));
        }
        return layout.array(type, len);
    }

    private void sample(long id, long kind) {
        if (sampleIds != null && samples < sampleIds.length) {
            sampleIds[samples] = id;
            sampleKinds[samples++] = kind;
        }
    }

    /** One way the JVM lays out objects. */
    private record Layout(boolean compressed, int header, int arrayHeader, int refSize) {
        long instance(long refs, long prims) {
            return align(header + refs * refSize + prims);
        }

        long array(int type, long len) {
            int each = type == T_OBJECT ? refSize : sizeOf(type, 8);
            int at = each >= 8 ? (arrayHeader + 7) & ~7 : arrayHeader;
            return align(at + len * each);
        }
    }

    /**
     * Works out how the JVM laid out objects (compressed pointers or not, the header size) from
     * the dump itself: objects next to each other in dump order are mostly next to each other in
     * memory, so the step from one address to the next is an object's size. The layout that gives
     * those steps most often wins.
     */
    private void objectLayout() {
        Layout[] all = {
            new Layout(true, 12, 16, 4), // compressed pointers (heaps under 32 GB)
            new Layout(false, 12, 16, 8), // no compressed pointers (big heaps, ZGC), compressed class pointers (JDK 15+)
            new Layout(false, 16, 24, 8), // no compressed pointers at all (JDK 8 and 11 big heaps)
            new Layout(true, 8, 12, 4), // compact object headers (JDK 24+)
            new Layout(false, 8, 12, 8),
        };
        Map<Long, long[]> fields = new HashMap<>(); // class id → {references, other bytes}
        for (ClassInfo ci : classes) {
            long refs = 0;
            long prims = 0;
            for (ClassInfo c = ci; c != null; c = c.superIdx < 0 ? null : classes.get(c.superIdx)) {
                for (int t : c.fieldTypes) {
                    if (t == T_OBJECT) {
                        refs++;
                    } else {
                        prims += sizeOf(t, idSize);
                    }
                }
            }
            fields.put(ci.id, new long[] {refs, prims});
        }
        int[] score = new int[all.length];
        for (int i = 0; i + 1 < samples; i++) {
            long kind = sampleKinds[i];
            long step = sampleIds[i + 1] - sampleIds[i];
            if (kind == 0 || sampleKinds[i + 1] == 0 || step <= 0 || step > 1 << 20) {
                continue;
            }
            long[] f = kind > 0 ? fields.get(kind) : null;
            if (kind > 0 && f == null) {
                continue;
            }
            for (int k = 0; k < all.length; k++) {
                long size = kind > 0 ? all[k].instance(f[0], f[1])
                        : all[k].array((int) ((-kind) >>> 33), (-kind) & ((1L << 33) - 1));
                if (size == step) {
                    score[k]++;
                }
            }
        }
        int best = 0;
        for (int k = 1; k < all.length; k++) {
            if (score[k] > score[best]) {
                best = k;
            }
        }
        if (score[best] < Math.max(10, samples / 100)) {
            // too few steps to tell: objects over more than 32 GB of addresses, or far up (ZGC),
            // can't use compressed pointers
            best = maxId - minId > 32L << 30 || minId >= 1L << 40 ? 1 : 0;
        }
        Layout l = all[best];
        compressed = l.compressed();
        header = l.header();
        arrayHeader = l.arrayHeader();
        refSize = l.refSize();
        layout = l;
    }

    private void dropSamples() {
        sampleIds = null;
        sampleKinds = null;
    }

    private void object(long id, long at) {
        ids.set(n, id);
        if (id < minId) {
            minId = id;
        }
        if (id > maxId) {
            maxId = id;
        }
        offsets.set(n, at);
        n++;
        if (n == Integer.MAX_VALUE) {
            throw new IllegalStateException("more than 2 billion objects");
        }
    }

    private void root(long id, RootKind kind, int thread, int frame) {
        rootIds.add(id);
        rootInfos.add(new Root(kind, thread, frame));
    }

    private ClassInfo readClass(HprofReader r) throws IOException {
        ClassInfo ci = new ClassInfo();
        ci.id = r.id();
        r.u4();
        ci.superId = r.id();
        ci.loaderId = r.id();
        r.id(); // signers
        r.id(); // protection domain
        r.id();
        r.id();
        r.u4(); // instance size (as in the dump)
        int cp = r.u2();
        for (int i = 0; i < cp; i++) {
            r.u2();
            r.value(r.u1());
        }
        int ns = r.u2();
        ci.staticNames = new long[ns];
        ci.staticTypes = new int[ns];
        ci.staticValues = new long[ns];
        for (int i = 0; i < ns; i++) {
            ci.staticNames[i] = r.id();
            ci.staticTypes[i] = r.u1();
            ci.staticValues[i] = r.value(ci.staticTypes[i]);
        }
        int nf = r.u2();
        ci.fieldNames = new long[nf];
        ci.fieldTypes = new int[nf];
        for (int i = 0; i < nf; i++) {
            ci.fieldNames[i] = r.id();
            ci.fieldTypes[i] = r.u1();
        }
        return ci;
    }

    /** Where the references are in an instance's data, and the instance's size in the JVM. */
    private void layout(ClassInfo ci) {
        List<Integer> pos = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int at = 0;
        int jvm = 0;
        boolean reference = false;
        for (ClassInfo c = ci; c != null; c = c.superIdx < 0 ? null : classes.get(c.superIdx)) {
            if (c.name.equals("java.lang.ref.Reference")) {
                reference = true;
            }
            for (int i = 0; i < c.fieldNames.length; i++) {
                int t = c.fieldTypes[i];
                if (t == T_OBJECT) {
                    String fname = name(c.fieldNames[i]);
                    if (reference && c.name.equals("java.lang.ref.Reference") && fname.equals("referent")) {
                        ci.referentPos = at;
                    } else {
                        pos.add(at);
                        names.add(fname);
                    }
                    jvm += refSize;
                } else {
                    jvm += sizeOf(t, idSize);
                }
                at += sizeOf(t, idSize);
            }
        }
        ci.refPos = pos.stream().mapToInt(Integer::intValue).toArray();
        ci.refNames = names.toArray(new String[0]);
        ci.shallow = align(header + jvm);
    }

    private int classObjectSize(ClassInfo ci) {
        int base = classClass >= 0 ? classes.get(classClass).shallow : 96;
        int statics = 0;
        for (int t : ci.staticTypes) {
            statics += t == T_OBJECT ? refSize : sizeOf(t, idSize);
        }
        return align(base + statics);
    }

    private static int align(long bytes) {
        return (int) Math.min(Integer.MAX_VALUE & ~7, (bytes + 7) & ~7L);
    }

    private interface Prog {
        void at(double f);
    }

    /** Pass 2 over a heap segment: each object's class, size and references. */
    private void linkSegment(HprofReader r, long end, int[] node, long[] edges, BooleanSupplier cancel, Prog p)
            throws IOException {
        byte[] data = new byte[256];
        while (r.position() < end) {
            int sub = r.u1();
            switch (sub) {
                case 0xFF, 0x05, 0x07 -> r.id();
                case 0x01 -> {
                    r.id();
                    r.id();
                }
                case 0x02, 0x03 -> {
                    r.id();
                    r.u4();
                    r.u4();
                }
                case 0x04, 0x06 -> {
                    r.id();
                    r.u4();
                }
                case 0x08 -> {
                    r.id();
                    r.u4();
                    r.u4();
                }
                case 0x20 -> {
                    ClassInfo ci = readClass(r);
                    int v = node[0]++;
                    classOf.set(v, classClass >= 0 ? classClass : classById.get(ci.id));
                    ClassInfo me = classes.get(classById.get(ci.id));
                    shallow.set(v, classObjectSize(me));
                    succStart.set(v, edges[0]);
                    for (int i = 0; i < ci.staticTypes.length; i++) {
                        if (ci.staticTypes[i] == T_OBJECT) {
                            edge(v, ci.staticValues[i], edges);
                        }
                    }
                    edge(v, ci.superId, edges);
                    edge(v, ci.loaderId, edges);
                }
                case 0x21 -> {
                    r.id();
                    r.u4();
                    long cid = r.id();
                    int len = (int) r.u4u();
                    if (len > data.length) {
                        data = new byte[Math.max(len, data.length * 2)];
                    }
                    r.bytes(data, 0, len);
                    int v = node[0]++;
                    Integer cix = classById.get(cid);
                    if (cix == null) {
                        throw new IOException("an object of a class that isn't in the dump (0x" + Long.toHexString(cid) + ")");
                    }
                    ClassInfo ci = classes.get(cix);
                    classOf.set(v, cix);
                    shallow.set(v, ci.shallow);
                    succStart.set(v, edges[0]);
                    for (int p0 : ci.refPos) {
                        if (p0 + idSize <= len) {
                            edge(v, idAt(data, p0), edges);
                        }
                    }
                }
                case 0x22 -> {
                    r.id();
                    r.u4();
                    long len = r.u4u();
                    long cid = r.id();
                    int v = node[0]++;
                    Integer cix = classById.get(cid);
                    classOf.set(v, cix == null ? primClass(T_OBJECT) : cix);
                    shallow.set(v, (int) arraySize(T_OBJECT, len));
                    succStart.set(v, edges[0]);
                    for (long i = 0; i < len; i++) {
                        edge(v, r.id(), edges);
                    }
                }
                case 0x23 -> {
                    r.id();
                    r.u4();
                    long len = r.u4u();
                    int type = r.u1();
                    r.skip(len * sizeOf(type, idSize));
                    int v = node[0]++;
                    classOf.set(v, primClass(type));
                    shallow.set(v, (int) arraySize(type, len));
                    succStart.set(v, edges[0]);
                }
                default -> throw new IOException("unsupported heap dump record 0x" + Integer.toHexString(sub));
            }
            check(cancel, node[0]);
        }
    }

    private void edge(int from, long id, long[] edges) {
        if (id == 0) {
            return;
        }
        int to = idMap.get(id);
        if (to >= 0 && to != from) {
            succ.set(edges[0]++, to);
        }
    }

    private long idAt(byte[] b, int p) {
        long v = 0;
        for (int i = 0; i < idSize; i++) {
            v = (v << 8) | (b[p + i] & 0xFF);
        }
        return v;
    }

    /** The made-up class of primitive arrays of this type (or Object[] when a class is missing). */
    private int primClass(int type) {
        if (primClass[type] == 0) {
            ClassInfo ci = new ClassInfo();
            ci.name = type == T_OBJECT ? "java.lang.Object[]" : PRIM_NAMES[type] + "[]";
            ci.primType = type;
            ci.refPos = new int[0];
            ci.refNames = new String[0];
            classes.add(ci);
            primClass[type] = classes.size(); // 1-based
        }
        return primClass[type] - 1;
    }

    static int sizeOf(int type, int idSize) {
        return switch (type) {
            case T_OBJECT -> idSize;
            case T_BOOLEAN, T_BYTE -> 1;
            case T_CHAR, T_SHORT -> 2;
            case T_FLOAT, T_INT -> 4;
            case T_DOUBLE, T_LONG -> 8;
            default -> throw new IllegalArgumentException("unknown value type " + type);
        };
    }

    private String name(long id) {
        String s = strings.get(id);
        return s == null ? "?" : s;
    }

    private String javaName(long classId) {
        Long nameId = classNameIds.get(classId);
        return nameId == null ? "?" : readable(name(nameId));
    }

    /** "java/util/HashMap" → "java.util.HashMap", "[[I" → "int[][]", "[Ljava/lang/String;" → "java.lang.String[]". */
    static String readable(String internal) {
        int dims = 0;
        while (dims < internal.length() && internal.charAt(dims) == '[') {
            dims++;
        }
        String base = internal.substring(dims);
        if (dims > 0) {
            base = switch (base) {
                case "Z" -> "boolean";
                case "C" -> "char";
                case "F" -> "float";
                case "D" -> "double";
                case "B" -> "byte";
                case "S" -> "short";
                case "I" -> "int";
                case "J" -> "long";
                default -> base.startsWith("L") && base.endsWith(";") ? base.substring(1, base.length() - 1) : base;
            };
        }
        return base.replace('/', '.') + "[]".repeat(dims);
    }

    // ---------------------------------------------------------------- what the UI asks

    public Path file() {
        return file;
    }

    /** When the dump was taken (ms since 1970). */
    public long timestamp() {
        return timestamp;
    }

    public boolean isOnDisk() {
        return store.isOnDisk();
    }

    /** True when sizes assume compressed pointers (12-byte headers, 4-byte references). */
    public boolean compressedPointers() {
        return compressed;
    }

    public int objectCount() {
        return n;
    }

    public int reachableCount() {
        return reachableCount;
    }

    /** Bytes of all objects in the dump. */
    public long totalBytes() {
        return totalBytes;
    }

    /** Bytes of the objects reachable through strong references (what can't be freed). */
    public long reachableBytes() {
        return reachableBytes;
    }

    /** The made-up node that refers to all GC roots. */
    public int root() {
        return n;
    }

    public long shallow(int node) {
        return node == n ? 0 : shallow.get(node);
    }

    public long retained(int node) {
        return retained.get(node);
    }

    /** The object that holds this one (its immediate dominator), or -1. */
    public int holder(int node) {
        return node == n ? -1 : idom.get(node);
    }

    public boolean isReachable(int node) {
        return node == n || bfsParent.get(node) != 0;
    }

    public long objectId(int node) {
        if (node == n) {
            return 0;
        }
        try {
            synchronized (random) {
                random.seek(offsets.get(node));
                random.u1(); // the record's kind
                return random.id();
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public String className(int node) {
        return node == n ? "" : classes.get(classOf.get(node)).name;
    }

    public boolean isClassObject(int node) {
        return classByNode.containsKey(node);
    }

    /** The objects this one holds (dominates), the biggest first; at most {@code max}. */
    public int[] held(int node, int max) {
        long a = domStart.get(node);
        long b = domStart.get(node + 1);
        int k = (int) (b - a);
        Integer[] kids = new Integer[k];
        for (int i = 0; i < k; i++) {
            kids[i] = domKids.get(a + i);
        }
        java.util.Arrays.sort(kids, (x, y) -> Long.compare(retained.get(y), retained.get(x)));
        int m = Math.min(max, k);
        int[] out = new int[m];
        for (int i = 0; i < m; i++) {
            out[i] = kids[i];
        }
        return out;
    }

    /** Totals for one class among the objects an object holds. */
    public static final class Tally {
        public long count;
        /** Bytes of the objects not held by another object of the same class (no double counting). */
        public long retained;
        public long shallow;
        public int sample = -1;
    }

    /**
     * Everything this object holds (its dominator subtree), by class; stops after {@code limit}
     * objects (returns null then).
     */
    public Map<String, Tally> heldByClass(int node, long limit) {
        Map<Integer, Tally> by = new HashMap<>();
        Store.Ints stack = store.ints("subtree-stack");
        try {
            long sp = 0;
            for (long e = domStart.get(node), end = domStart.get(node + 1); e < end; e++) {
                stack.set(sp++, domKids.get(e));
            }
            long seen = 0;
            while (sp > 0) {
                int v = stack.get(--sp);
                if (++seen > limit) {
                    return null;
                }
                int c = classOf.get(v);
                Tally t = by.computeIfAbsent(c, k -> new Tally());
                t.count++;
                t.shallow += shallow.get(v);
                int h = idom.get(v);
                if (h == node || classOf.get(h) != c) {
                    t.retained += retained.get(v);
                    if (t.sample < 0 || retained.get(v) > retained.get(t.sample)) {
                        t.sample = v;
                    }
                }
                for (long e = domStart.get(v), end = domStart.get(v + 1); e < end; e++) {
                    stack.set(sp++, domKids.get(e));
                }
            }
        } finally {
            stack.free();
        }
        Map<String, Tally> out = new HashMap<>();
        by.forEach((c, t) -> out.merge(classes.get(c).name, t, (a, b) -> {
            a.count += b.count;
            a.retained += b.retained;
            a.shallow += b.shallow;
            return a;
        }));
        return out;
    }

    /** How many objects this one holds directly (dominator tree children). */
    public int heldCount(int node) {
        return domStart.get(node + 1) - domStart.get(node);
    }

    /** Every class with objects, the most retained first. */
    public List<ClassStat> classes() {
        List<ClassStat> out = new ArrayList<>();
        for (ClassInfo ci : classes) {
            if (ci.count > 0) {
                out.add(new ClassStat(ci.name, ci.count, ci.shallowSum, ci.retainedSum));
            }
        }
        out.sort((x, y) -> Long.compare(y.retained(), x.retained()));
        return out;
    }

    /** The objects of a class, the biggest (retained) first; at most {@code max}. */
    public int[] objectsOf(String className, int max) {
        int cix = -1;
        for (int i = 0; i < classes.size(); i++) {
            if (classes.get(i).name.equals(className)) {
                cix = i;
                break;
            }
        }
        if (cix < 0) {
            return new int[0];
        }
        java.util.PriorityQueue<Integer> top = new java.util.PriorityQueue<>(
                (x, y) -> Long.compare(retained.get(x), retained.get(y)));
        for (int v = 0; v < n; v++) {
            if (classOf.get(v) == cix && !classByNode.containsKey(v)) {
                top.add(v);
                if (top.size() > max) {
                    top.poll();
                }
            }
        }
        int[] out = new int[top.size()];
        for (int i = out.length - 1; i >= 0; i--) {
            out[i] = top.poll();
        }
        return out;
    }

    /** The class whose Class object this node is, or null. */
    private ClassInfo classOfClassObject(int node) {
        return classByNode.get(node);
    }

    /**
     * Why this object is alive: the shortest chain of strong references from a GC root to it.
     * The first step is the root (its {@code via} says what kind of root); empty when nothing
     * strong reaches it.
     */
    public List<Step> pathFromRoot(int node) throws IOException {
        List<Integer> chain = new ArrayList<>();
        int v = node;
        if (bfsParent.get(v) == 0) {
            return List.of();
        }
        while (v != n) {
            chain.add(v);
            v = bfsParent.get(v) - 1;
            if (chain.size() > 100_000) {
                break;
            }
        }
        java.util.Collections.reverse(chain);
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            int w = chain.get(i);
            out.add(new Step(w, i == 0 ? rootText(w) : via(chain.get(i - 1), w)));
        }
        return out;
    }

    /** What kind of GC root this is, in words. */
    public String rootText(int node) {
        ClassInfo c = classOfClassObject(node);
        if (c != null) {
            return "a loaded class (its static fields)";
        }
        Root r = rootOf.get(node);
        if (r == null) {
            return "a GC root";
        }
        return switch (r.kind()) {
            case JAVA_FRAME -> {
                Frame f = frame(r.threadSerial(), r.frame());
                yield "a local variable" + (f == null ? "" : " in " + shortClass(f.className()) + "." + f.method() + "()")
                        + " of thread " + threadName(r.threadSerial());
            }
            case THREAD_OBJECT -> "a running thread";
            case JNI_GLOBAL -> "a JNI global reference (native code)";
            case JNI_LOCAL -> "a JNI local reference in thread " + threadName(r.threadSerial());
            case NATIVE_STACK -> "native code in thread " + threadName(r.threadSerial());
            case THREAD_BLOCK -> "thread " + threadName(r.threadSerial());
            case MONITOR -> "a lock in use (synchronized)";
            case STICKY_CLASS -> "a class the JVM keeps";
            default -> "the JVM";
        };
    }

    private Frame frame(int threadSerial, int frameNo) {
        long[] t = threads.get(threadSerial);
        if (t == null || frameNo < 0) {
            return null;
        }
        long[] frames = traces.get((int) t[1]);
        if (frames == null || frameNo >= frames.length) {
            return null;
        }
        return frameById.get(frames[frameNo]);
    }

    private String threadName(int serial) {
        long[] t = threads.get(serial);
        if (t == null) {
            return "#" + serial;
        }
        try {
            int v = nodeOf(t[0]);
            String name = v < 0 ? null : stringField(v, "name");
            return name == null ? "#" + serial : "\"" + name + "\"";
        } catch (IOException e) {
            return "#" + serial;
        }
    }

    /** How many threads the dump has (with their stacks). */
    public int threadCount() {
        return threads.size();
    }

    /**
     * The dump's threads as a thread dump (jstack's format): names, states and stacks. A heap dump
     * doesn't say which locks threads hold, so there are none.
     */
    public String threadDumpText() throws IOException {
        StringBuilder sb = new StringBuilder();
        if (timestamp > 0) {
            sb.append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())))
                    .append('\n');
        }
        sb.append("Full thread dump (the threads in ").append(file.getFileName()).append("):\n\n");
        List<Integer> serials = new ArrayList<>(threads.keySet());
        java.util.Collections.sort(serials);
        for (int serial : serials) {
            long[] t = threads.get(serial);
            int v = nodeOf(t[0]);
            Map<String, Long> f = v < 0 ? Map.of() : primitiveFields(v);
            Map<String, Long> h = f;
            if (v >= 0 && !f.containsKey("threadStatus")) { // JDK 19 and later: in Thread.holder
                int holderNode = refField(v, "holder");
                if (holderNode >= 0) {
                    h = primitiveFields(holderNode);
                }
            }
            String name = v < 0 ? null : stringField(v, "name");
            long tid = f.getOrDefault("tid", (long) serial);
            sb.append('"').append(name == null ? "Thread-" + serial : name).append("\" #").append(tid);
            if (h.getOrDefault("daemon", 0L) != 0) {
                sb.append(" daemon");
            }
            sb.append(" prio=").append(h.getOrDefault("priority", 5L)).append(" tid=0x0 nid=0x0\n");
            sb.append("   java.lang.Thread.State: ").append(state(h.getOrDefault("threadStatus", 5L))).append('\n');
            long[] frames = traces.get((int) t[1]);
            if (frames != null) {
                for (long fid : frames) {
                    Frame fr = frameById.get(fid);
                    if (fr == null) {
                        continue;
                    }
                    sb.append("\tat ").append(fr.className()).append('.').append(fr.method()).append('(');
                    if (fr.line() == -3) {
                        sb.append("Native Method");
                    } else if (fr.file() == null || fr.file().isEmpty()) {
                        sb.append("Unknown Source");
                    } else {
                        sb.append(fr.file());
                        if (fr.line() > 0) {
                            sb.append(':').append(fr.line());
                        }
                    }
                    sb.append(")\n");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** A thread's state from its threadStatus (JVMTI flags), as Thread.State names it. */
    static String state(long status) {
        if ((status & 0x4) != 0) {
            return "RUNNABLE";
        }
        if ((status & 0x400) != 0) {
            return "BLOCKED (on object monitor)";
        }
        if ((status & 0x10) != 0) {
            return (status & 0x200) != 0 ? "WAITING (parking)" : "WAITING (on object monitor)";
        }
        if ((status & 0x20) != 0) {
            return (status & 0x40) != 0 ? "TIMED_WAITING (sleeping)"
                    : (status & 0x200) != 0 ? "TIMED_WAITING (parking)" : "TIMED_WAITING (on object monitor)";
        }
        if ((status & 0x2) != 0) {
            return "TERMINATED";
        }
        return (status & 0x1) == 0 ? "NEW" : "RUNNABLE";
    }

    private int nodeOf(long id) {
        // ids are in node order only by chance: search the thread objects' roots instead
        for (Map.Entry<Integer, Root> e : rootOf.entrySet()) {
            if (objectId(e.getKey()) == id) {
                return e.getKey();
            }
        }
        return -1;
    }

    static String shortClass(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    /** How {@code from} refers to {@code to}: a field name, "[3]", "static NAME". */
    public String via(int from, int to) throws IOException {
        long target = objectId(to);
        synchronized (random) {
            random.seek(offsets.get(from));
            int sub = random.u1();
            switch (sub) {
                case 0x20 -> {
                    ClassInfo ci = readClass(random);
                    for (int i = 0; i < ci.staticNames.length; i++) {
                        if (ci.staticTypes[i] == T_OBJECT && ci.staticValues[i] == target) {
                            return "static " + name(ci.staticNames[i]);
                        }
                    }
                    if (ci.superId == target) {
                        return "super class";
                    }
                    return "class loader";
                }
                case 0x21 -> {
                    random.id();
                    random.u4();
                    long cid = random.id();
                    int len = (int) random.u4u();
                    byte[] data = new byte[len];
                    random.bytes(data, 0, len);
                    ClassInfo ci = classes.get(classById.get(cid));
                    for (int i = 0; i < ci.refPos.length; i++) {
                        if (idAt(data, ci.refPos[i]) == target) {
                            return ci.refNames[i];
                        }
                    }
                    return "?";
                }
                case 0x22 -> {
                    random.id();
                    random.u4();
                    long len = random.u4u();
                    random.id();
                    for (long i = 0; i < len; i++) {
                        if (random.id() == target) {
                            return "[" + i + "]";
                        }
                    }
                    return "[?]";
                }
                default -> {
                    return "?";
                }
            }
        }
    }

    /**
     * A short description: "HashMap (20,000 entries)", "String \"hello\"", "byte[512]",
     * "class LeakApp".
     */
    public String describe(int node) {
        if (node == n) {
            return "GC roots";
        }
        try {
            ClassInfo c = classOfClassObject(node);
            if (c != null) {
                return "class " + c.name;
            }
            String cls = className(node);
            if (cls.equals("java.lang.String")) {
                String s = stringValue(node, 80);
                return s == null ? cls : "String \"" + s + "\"";
            }
            if (cls.endsWith("[]")) {
                long len = arrayLength(node);
                return cls.substring(0, cls.length() - 2) + "[" + String.format("%,d", len) + "]";
            }
            Map<String, Long> f = primitiveFields(node);
            Long size = f.get("size");
            if (size == null) {
                size = f.get("count");
            }
            if (size != null && f.containsKey("size")) {
                return cls + " (" + String.format("%,d", size.intValue()) + (cls.contains("Map") ? " entries)" : " items)");
            }
            if (cls.equals("java.lang.Thread")) {
                String name = stringField(node, "name");
                if (name != null) {
                    return "Thread \"" + name + "\"";
                }
            }
            return cls;
        } catch (IOException e) {
            return className(node);
        }
    }

    private long arrayLength(int node) throws IOException {
        synchronized (random) {
            random.seek(offsets.get(node));
            random.u1();
            random.id();
            random.u4();
            return random.u4u();
        }
    }

    /** The int, long, short, byte, char and boolean fields of an instance, by name. */
    Map<String, Long> primitiveFields(int node) throws IOException {
        Map<String, Long> out = new HashMap<>();
        synchronized (random) {
            random.seek(offsets.get(node));
            if (random.u1() != 0x21) {
                return out;
            }
            random.id();
            random.u4();
            long cid = random.id();
            random.u4();
            for (ClassInfo c = classes.get(classById.get(cid)); c != null;
                 c = c.superIdx < 0 ? null : classes.get(c.superIdx)) {
                for (int i = 0; i < c.fieldNames.length; i++) {
                    long val = random.value(c.fieldTypes[i]);
                    if (c.fieldTypes[i] != T_OBJECT) {
                        out.putIfAbsent(name(c.fieldNames[i]), val);
                    }
                }
            }
        }
        return out;
    }

    /** The object a reference field points to, or -1. */
    int refField(int node, String field) throws IOException {
        long target = 0;
        synchronized (random) {
            random.seek(offsets.get(node));
            if (random.u1() != 0x21) {
                return -1;
            }
            random.id();
            random.u4();
            long cid = random.id();
            random.u4();
            outer:
            for (ClassInfo c = classes.get(classById.get(cid)); c != null;
                 c = c.superIdx < 0 ? null : classes.get(c.superIdx)) {
                for (int i = 0; i < c.fieldNames.length; i++) {
                    long val = random.value(c.fieldTypes[i]);
                    if (c.fieldTypes[i] == T_OBJECT && name(c.fieldNames[i]).equals(field)) {
                        target = val;
                        break outer;
                    }
                }
            }
        }
        if (target == 0) {
            return -1;
        }
        // the referenced object is one of this node's successors
        for (long e = succStart.get(node), end = succStart.get(node + 1); e < end; e++) {
            int w = succ.get(e);
            if (objectId(w) == target) {
                return w;
            }
        }
        return -1;
    }

    private String stringField(int node, String field) throws IOException {
        int s = refField(node, field);
        return s < 0 ? null : stringValue(s, 200);
    }

    /** The text of a java.lang.String (Latin-1 or UTF-16 bytes, or chars), cut at {@code max}. */
    public String stringValue(int node, int max) throws IOException {
        int arr = refField(node, "value");
        if (arr < 0) {
            return null;
        }
        Long coder = primitiveFields(node).get("coder");
        synchronized (random) {
            random.seek(offsets.get(arr));
            if (random.u1() != 0x23) {
                return null;
            }
            random.id();
            random.u4();
            long len = random.u4u();
            int type = random.u1();
            StringBuilder sb = new StringBuilder();
            if (type == T_CHAR) {
                for (long i = 0; i < len && sb.length() < max; i++) {
                    sb.append((char) random.u2());
                }
            } else if (type == T_BYTE && coder != null && coder == 1) { // UTF-16, little-endian on x86 and ARM
                for (long i = 0; i + 1 < len && sb.length() < max; i += 2) {
                    int lo = random.u1();
                    int hi = random.u1();
                    sb.append((char) (lo | hi << 8));
                }
            } else if (type == T_BYTE) {
                for (long i = 0; i < len && sb.length() < max; i++) {
                    sb.append((char) random.u1());
                }
            }
            if (sb.length() >= max) {
                sb.append('…');
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- for reports in this package

    Store store() {
        return store;
    }

    int arrayHeader() {
        return arrayHeader;
    }

    int refSize() {
        return refSize;
    }

    /** The index of a node's class in {@link #classes}. */
    int classIndex(int node) {
        return classOf.get(node);
    }

    /** The index of the class with this name, or -1. */
    int classIndex(String name) {
        for (int i = 0; i < classes.size(); i++) {
            if (name.equals(classes.get(i).name)) {
                return i;
            }
        }
        return -1;
    }

    /** True when the class (by index) is this class or extends it. */
    boolean extendsClass(int classIdx, String name) {
        for (int c = classIdx; c >= 0; c = classes.get(c).superIdx) {
            if (name.equals(classes.get(c).name)) {
                return true;
            }
        }
        return false;
    }

    /** The objects this one refers to (strongly). */
    int[] refs(int node) {
        long a = succStart.get(node);
        long b = succStart.get(node + 1);
        int[] out = new int[(int) (b - a)];
        for (int i = 0; i < out.length; i++) {
            out[i] = succ.get(a + i);
        }
        return out;
    }

    /** The object through which the shortest path from a GC root reaches this one; root() for a root, -1 when unreachable. */
    int parent(int node) {
        return node == n ? -1 : bfsParent.get(node) - 1;
    }

    /** Where a field of this type is in an instance's data (as in the dump), or -1. */
    int fieldPos(int classIdx, String field, int type) {
        int at = 0;
        for (int c = classIdx; c >= 0; c = classes.get(c).superIdx) {
            ClassInfo ci = classes.get(c);
            for (int i = 0; i < ci.fieldNames.length; i++) {
                if (ci.fieldTypes[i] == type && name(ci.fieldNames[i]).equals(field)) {
                    return at;
                }
                at += sizeOf(ci.fieldTypes[i], idSize);
            }
        }
        return -1;
    }

    /**
     * Called for each wanted object in a pass over the dump, with the reader at the object's data:
     * an instance's field values ({@code length} bytes), or an array's elements ({@code length} of them).
     */
    interface Visitor {
        void visit(int node, int record, HprofReader r, long length) throws IOException;
    }

    /** One pass over the dump's objects, front to back, for the nodes in {@code wanted}. */
    void scan(BitSet wanted, Visitor visitor, Listener l, BooleanSupplier cancel) throws IOException {
        Listener listener = throttled(l);
        HprofReader r = new HprofReader(channel, 0);
        r.idSize = idSize;
        int v = 0;
        long all = 0;
        long done = 0;
        for (long[] seg : segments) {
            all += seg[1];
        }
        for (long[] seg : segments) {
            r.seek(seg[0]);
            long end = seg[0] + seg[1];
            while (r.position() < end) {
                int sub = r.u1();
                switch (sub) {
                    case 0xFF, 0x05, 0x07 -> r.id();
                    case 0x01 -> r.skip(2L * idSize);
                    case 0x02, 0x03, 0x08 -> r.skip(idSize + 8L);
                    case 0x04, 0x06 -> r.skip(idSize + 4L);
                    case 0x20 -> {
                        readClass(r);
                        v++;
                    }
                    case 0x21 -> {
                        r.id();
                        r.u4();
                        r.id();
                        long len = r.u4u();
                        long next = r.position() + len;
                        if (wanted.get(v)) {
                            visitor.visit(v, sub, r, len);
                        }
                        r.seek(next);
                        v++;
                    }
                    case 0x22 -> {
                        r.id();
                        r.u4();
                        long len = r.u4u();
                        r.id();
                        long next = r.position() + len * idSize;
                        if (wanted.get(v)) {
                            visitor.visit(v, sub, r, len);
                        }
                        r.seek(next);
                        v++;
                    }
                    case 0x23 -> {
                        r.id();
                        r.u4();
                        long len = r.u4u();
                        int type = r.u1();
                        long next = r.position() + len * sizeOf(type, idSize);
                        if (wanted.get(v)) {
                            visitor.visit(v, sub, r, len * sizeOf(type, idSize));
                        }
                        r.seek(next);
                        v++;
                    }
                    default -> throw new IOException("unsupported heap dump record 0x" + Integer.toHexString(sub));
                }
                check(cancel, v);
                if ((v & 0xFFFF) == 0) {
                    listener.progress("", (double) (done + r.position() - seg[0]) / all);
                }
            }
            done += seg[1];
        }
    }

    /** Reads an int (4 bytes, big-endian) at {@code p} of an instance's data. */
    static int intAt(byte[] b, int p) {
        return (b[p] & 0xFF) << 24 | (b[p + 1] & 0xFF) << 16 | (b[p + 2] & 0xFF) << 8 | (b[p + 3] & 0xFF);
    }

    /** Deletes index folders left by earlier runs (a crash, or Windows keeping mapped files). */
    public static void cleanUp(Path parent) {
        if (!Files.isDirectory(parent)) {
            return;
        }
        try (var s = Files.list(parent)) {
            for (Path d : s.toList()) {
                try (var w = Files.walk(d)) {
                    for (Path p : w.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException e) {
                    // in use by another window
                }
            }
        } catch (IOException e) {
            // nothing to clean
        }
    }
}
