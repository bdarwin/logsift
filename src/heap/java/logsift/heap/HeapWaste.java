package logsift.heap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * Memory a heap spends for nothing: strings that are copies of each other, and collections that
 * are empty or mostly empty (their arrays mostly unused slots). Only objects in use count.
 */
public final class HeapWaste {
    /** One text, kept in many String objects. */
    public static final class Duplicate {
        private final String text;
        private final long copies;
        private final long wasted;
        private final int sample;
        private final String where;

        public Duplicate(String text, long copies, long wasted, int sample, String where) {
            this.text = text;
            this.copies = copies;
            this.wasted = wasted;
            this.sample = sample;
            this.where = where;
        }

        public String text() {
            return text;
        }

        public long copies() {
            return copies;
        }

        public long wasted() {
            return wasted;
        }

        public int sample() {
            return sample;
        }

        public String where() {
            return where;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Duplicate)) {
                return false;
            }
            Duplicate x = (Duplicate) o;
            return java.util.Objects.equals(text, x.text)
                    && copies == x.copies
                    && wasted == x.wasted
                    && sample == x.sample
                    && java.util.Objects.equals(where, x.where);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(text, copies, wasted, sample, where);
        }

        @Override
        public String toString() {
            return "Duplicate[" + "text=" + text + ", " + "copies=" + copies + ", " + "wasted=" + wasted + ", " + "sample=" + sample + ", " + "where=" + where + "]";
        }
    }

    /** Collections of one kind in one place: "empty ArrayList" in "Session.tags". */
    public static final class Collections {
        private final String what;
        private final String where;
        private final long count;
        private final long wasted;
        private final int sample;

        public Collections(String what, String where, long count, long wasted, int sample) {
            this.what = what;
            this.where = where;
            this.count = count;
            this.wasted = wasted;
            this.sample = sample;
        }

        public String what() {
            return what;
        }

        public String where() {
            return where;
        }

        public long count() {
            return count;
        }

        public long wasted() {
            return wasted;
        }

        public int sample() {
            return sample;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Collections)) {
                return false;
            }
            Collections x = (Collections) o;
            return java.util.Objects.equals(what, x.what)
                    && java.util.Objects.equals(where, x.where)
                    && count == x.count
                    && wasted == x.wasted
                    && sample == x.sample;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(what, where, count, wasted, sample);
        }

        @Override
        public String toString() {
            return "Collections[" + "what=" + what + ", " + "where=" + where + ", " + "count=" + count + ", " + "wasted=" + wasted + ", " + "sample=" + sample + "]";
        }
    }

    /** What can be saved, and where. */
    public static final class Report {
        private final long stringBytes;
        private final long duplicatedTexts;
        private final List<Duplicate> strings;
        private final long collectionBytes;
        private final List<Collections> collections;

        public Report(long stringBytes, long duplicatedTexts, List<Duplicate> strings, long collectionBytes, List<Collections> collections) {
            this.stringBytes = stringBytes;
            this.duplicatedTexts = duplicatedTexts;
            this.strings = strings;
            this.collectionBytes = collectionBytes;
            this.collections = collections;
        }

        public long stringBytes() {
            return stringBytes;
        }

        public long duplicatedTexts() {
            return duplicatedTexts;
        }

        public List<Duplicate> strings() {
            return strings;
        }

        public long collectionBytes() {
            return collectionBytes;
        }

        public List<Collections> collections() {
            return collections;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Report)) {
                return false;
            }
            Report x = (Report) o;
            return stringBytes == x.stringBytes
                    && duplicatedTexts == x.duplicatedTexts
                    && java.util.Objects.equals(strings, x.strings)
                    && collectionBytes == x.collectionBytes
                    && java.util.Objects.equals(collections, x.collections);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(stringBytes, duplicatedTexts, strings, collectionBytes, collections);
        }

        @Override
        public String toString() {
            return "Report[" + "stringBytes=" + stringBytes + ", " + "duplicatedTexts=" + duplicatedTexts + ", " + "strings=" + strings + ", " + "collectionBytes=" + collectionBytes + ", " + "collections=" + collections + "]";
        }

        public long total() {
            return stringBytes + collectionBytes;
        }
    }

    static final int TOP = 100;
    private static final int SAMPLES = 30;

    private final HeapDump d;
    private final HeapDump.Listener l;
    private final BooleanSupplier cancel;
    private final boolean[] internal;
    private final boolean[] array;

    private HeapWaste(HeapDump d, HeapDump.Listener l, BooleanSupplier cancel) {
        this.d = d;
        this.l = l == null ? (s, f) -> { } : l;
        this.cancel = cancel == null ? () -> false : cancel;
        internal = new boolean[d.classes.size()];
        array = new boolean[d.classes.size()];
        for (int i = 0; i < internal.length; i++) {
            String name = d.classes.get(i).name;
            internal[i] = HeapFindings.isInternal(name);
            array[i] = name.endsWith("[]");
        }
    }

    /** Works out the report: two passes over the dump. */
    public static Report of(HeapDump d, HeapDump.Listener l, BooleanSupplier cancel) throws IOException {
        HeapWaste w = new HeapWaste(d, l, cancel);
        long[] strings = new long[2];
        List<Duplicate> dups = w.strings(strings);
        long[] colls = new long[1];
        List<Collections> cs = w.collections(colls);
        return new Report(strings[0], strings[1], dups, colls[0], cs);
    }

    // ---------------------------------------------------------------- duplicate strings

    /** Content hash → how many strings and arrays have it, and one array. */
    private static final class Table {
        final Store.Longs keys;
        final Store.Ints strings;
        final Store.Ints arrays;
        final Store.Ints sample;
        final long mask;

        Table(Store s, long expected) {
            long cap = Long.highestOneBit(Math.max(16, expected + expected / 2)) << 1;
            mask = cap - 1;
            keys = s.longs("waste-keys").size(cap);
            strings = s.ints("waste-strings").size(cap);
            arrays = s.ints("waste-arrays").size(cap);
            sample = s.ints("waste-sample").size(cap);
        }

        long slot(long h) {
            long k = h == 0 ? 1 : h;
            long i = (k * 0x9E3779B97F4A7C15L >>> 17) & mask;
            while (true) {
                long at = keys.get(i);
                if (at == k) {
                    return i;
                }
                if (at == 0) {
                    keys.set(i, k);
                    return i;
                }
                i = (i + 1) & mask;
            }
        }

        void free() {
            keys.free();
            strings.free();
            arrays.free();
            sample.free();
        }
    }

    private List<Duplicate> strings(long[] totals) throws IOException {
        l.progress("Looking for duplicate strings", 0);
        int stringClass = d.classIndex("java.lang.String");
        if (stringClass < 0) {
            return java.util.Collections.emptyList();
        }
        // the arrays of the strings in use, and how many strings share each
        BitSet values = new BitSet(d.n);
        Map<Integer, Integer> shared = new HashMap<>();
        long strings = 0;
        long stringSize = 0;
        for (int v = 0; v < d.n; v++) {
            if (d.classIndex(v) != stringClass || !d.isReachable(v)) {
                continue;
            }
            int a = value(v);
            if (a < 0) {
                continue;
            }
            strings++;
            stringSize = d.shallow(v);
            if (values.get(a)) {
                shared.merge(a, 2, (x, y) -> x + 1);
            }
            values.set(a);
            check(v);
        }
        if (strings == 0) {
            return java.util.Collections.emptyList();
        }
        int arrays = values.cardinality();
        Table t = new Table(d.store(), arrays);
        // each array's contents, front to back; hashes kept in node order for the second step
        Store.Ints arrNodes = d.store().ints("waste-arr-nodes").size(arrays);
        Store.Longs arrHash = d.store().longs("waste-arr-hash").size(arrays);
        long[] k = {0};
        d.scan(values, (v, rec, r, len) -> {
            long h = r.hash(len);
            long i = t.slot(h);
            t.strings.add(i, shared.getOrDefault(v, 1));
            if (t.arrays.get(i) == 0) {
                t.sample.set(i, v);
            }
            t.arrays.add(i, 1);
            arrNodes.set(k[0], v);
            arrHash.set(k[0]++, h == 0 ? 1 : h);
        }, (s, f) -> l.progress("Looking for duplicate strings", f * 0.8), cancel);

        // the texts that waste the most
        PriorityQueue<long[]> top = new PriorityQueue<>(Comparator.comparingLong(x -> x[1]));
        long wasted = 0;
        long texts = 0;
        for (long i = 0; i <= t.mask; i++) {
            int s = t.strings.get(i);
            if (s < 2) {
                continue;
            }
            int a = t.arrays.get(i);
            long w = (s - 1) * stringSize + (a - 1) * d.shallow(t.sample.get(i));
            wasted += w;
            texts++;
            top.add(new long[] {i, w, s});
            if (top.size() > TOP) {
                top.poll();
            }
        }
        totals[0] = wasted;
        totals[1] = texts;
        Map<Long, long[]> byHash = new HashMap<>(); // hash → {wasted, copies}
        for (long[] e : top) {
            byHash.put(t.keys.get(e[0]), new long[] {e[1], e[2]});
        }
        t.free();

        // a few strings of each, to say where they are kept
        l.progress("Looking for duplicate strings", 0.85);
        Map<Long, List<Integer>> samples = new HashMap<>();
        for (int v = 0; v < d.n && !byHash.isEmpty(); v++) {
            if (d.classIndex(v) != stringClass || !d.isReachable(v)) {
                continue;
            }
            int a = value(v);
            long h = a < 0 ? 0 : hashOf(arrNodes, arrHash, arrays, a);
            if (byHash.containsKey(h)) {
                List<Integer> list = samples.computeIfAbsent(h, x -> new ArrayList<>());
                if (list.size() < SAMPLES) {
                    list.add(v);
                }
            }
            check(v);
        }
        arrNodes.free();
        arrHash.free();
        List<Duplicate> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : byHash.entrySet()) {
            List<Integer> list = samples.getOrDefault(e.getKey(), java.util.Collections.emptyList());
            if (list.isEmpty()) {
                continue;
            }
            String text = d.stringValue(list.get(0), 120);
            out.add(new Duplicate(text == null ? "?" : text, e.getValue()[1], e.getValue()[0], list.get(0),
                    commonWhere(list)));
        }
        out.sort((x, y) -> Long.compare(y.wasted(), x.wasted()));
        l.progress("Looking for duplicate strings", 1);
        return out;
    }

    /** A String's array (its one reference), or -1. */
    private int value(int s) {
        int[] r = d.refs(s);
        return r.length == 1 ? r[0] : -1;
    }

    private static long hashOf(Store.Ints nodes, Store.Longs hashes, int count, int node) {
        int lo = 0;
        int hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int m = nodes.get(mid);
            if (m < node) {
                lo = mid + 1;
            } else if (m > node) {
                hi = mid - 1;
            } else {
                return hashes.get(mid);
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- collections

    /**
     * The collections looked at: the field with their size (null: the references in their array are
     * counted), and how many array slots an entry takes.
     */
    private enum Base {
        ARRAY_LIST("java.util.ArrayList", "size", 1),
        VECTOR("java.util.Vector", "elementCount", 1),
        HASH_MAP("java.util.HashMap", "size", 1),
        HASHTABLE("java.util.Hashtable", "count", 1),
        WEAK_HASH_MAP("java.util.WeakHashMap", "size", 1),
        IDENTITY_HASH_MAP("java.util.IdentityHashMap", "size", 2), // keys and values side by side
        CONCURRENT_HASH_MAP("java.util.concurrent.ConcurrentHashMap", null, 1), // its size is spread out: bins used
        ARRAY_DEQUE("java.util.ArrayDeque", null, 1),
        COPY_ON_WRITE("java.util.concurrent.CopyOnWriteArrayList", null, 1), // no spare room: only empty ones
        LINKED_LIST("java.util.LinkedList", "size", 1), // no array: only empty ones
        TREE_MAP("java.util.TreeMap", "size", 1);

        final String cls;
        final String sizeField;
        final int perEntry;

        Base(String cls, String sizeField, int perEntry) {
            this.cls = cls;
            this.sizeField = sizeField;
            this.perEntry = perEntry;
        }
    }

    private static final class Group {
        final String what;
        long count;
        long wasted;
        final List<Integer> samples = new ArrayList<>();

        Group(String what) {
            this.what = what;
        }
    }

    private List<Collections> collections(long[] total) throws IOException {
        l.progress("Looking for empty collections", 0);
        int classes = d.classes.size();
        int[] sizePos = new int[classes];
        Base[] base = new Base[classes];
        java.util.Arrays.fill(sizePos, -1);
        for (int c = 0; c < classes; c++) {
            if (d.extendsClass(c, "java.util.Properties")) {
                continue; // keeps its entries in a map of its own
            }
            for (Base b : Base.values()) {
                if (d.extendsClass(c, b.cls)) {
                    int pos = b.sizeField == null ? 0 : d.fieldPos(c, b.sizeField, HeapDump.T_INT);
                    if (pos >= 0) {
                        sizePos[c] = pos;
                        base[c] = b;
                    }
                }
            }
        }
        BitSet wanted = new BitSet(d.n);
        for (int v = 0; v < d.n; v++) {
            int c = d.classIndex(v);
            if (base[c] != null && d.isReachable(v) && !d.isClassObject(v)) {
                wanted.set(v);
            }
            check(v);
        }
        Map<String, Group> groups = new HashMap<>();
        long[] wasted = {0};
        byte[][] data = {new byte[64]};
        d.scan(wanted, (v, rec, r, len) -> {
            if (len > data[0].length) {
                data[0] = new byte[(int) len];
            }
            r.bytes(data[0], 0, (int) len);
            int c = d.classIndex(v);
            Base b = base[c];
            if (b.sizeField != null && sizePos[c] + 4 > len) {
                return;
            }
            int arr = -1;
            for (int w : d.refs(v)) {
                if (array[d.classIndex(w)] && d.holder(w) == v) {
                    arr = w;
                }
            }
            long slots = arr < 0 ? 0 : (d.shallow(arr) - d.arrayHeader()) / d.refSize() / b.perEntry;
            long size;
            if (b.sizeField != null) {
                size = HeapDump.intAt(data[0], sizePos[c]);
            } else {
                size = arr < 0 ? 0 : d.refs(arr).length; // the slots in use
            }
            if (b == Base.COPY_ON_WRITE) {
                slots = size; // it is always full
            }
            int subject = v;
            int p = d.parent(v);
            if (p >= 0 && p != d.root() && d.holder(v) == p
                    && (d.extendsClass(d.classIndex(p), "java.util.HashSet")
                    || d.extendsClass(d.classIndex(p), "java.util.TreeSet"))) {
                subject = p; // a set: its map is how it is made
            }
            String kind = simple(d.className(subject));
            long w;
            String what;
            if (size == 0) {
                w = d.shallow(subject) + (subject != v ? d.shallow(v) : 0) + (arr < 0 ? 0 : d.shallow(arr));
                what = "Empty " + kind;
            } else if (arr >= 0 && slots - size >= 8 && size * 4L < slots) {
                w = (slots - size) * d.refSize() * b.perEntry;
                what = kind + " mostly empty (under a quarter used)";
            } else {
                return;
            }
            wasted[0] += w;
            Group g = groups.computeIfAbsent(what + "\u0000" + placeKey(subject), x -> new Group(what));
            g.count++;
            g.wasted += w;
            if (g.samples.size() < SAMPLES) {
                g.samples.add(subject);
            }
        }, (s, f) -> l.progress("Looking for empty collections", f), cancel);
        total[0] = wasted[0];
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((x, y) -> Long.compare(y.wasted, x.wasted));
        // groups in the same place (by field) merge once the field names are known
        Map<String, Collections> out = new java.util.LinkedHashMap<>();
        for (Group g : sorted.subList(0, Math.min(TOP, sorted.size()))) {
            String where = commonWhere(g.samples);
            out.merge(g.what + "\u0000" + where, new Collections(g.what, where, g.count, g.wasted, g.samples.get(0)),
                    (a, b) -> new Collections(a.what(), a.where(), a.count() + b.count(), a.wasted() + b.wasted(),
                            a.sample()));
        }
        List<Collections> list = new ArrayList<>(out.values());
        list.sort((x, y) -> Long.compare(y.wasted(), x.wasted()));
        return list;
    }

    // ---------------------------------------------------------------- where objects are kept

    /** A cheap key for where an object is kept: the class (or class object) that refers to it. */
    private String placeKey(int v) {
        int[] up = up(v);
        int p = up[0];
        if (p < 0 || p == d.root()) {
            return "root";
        }
        return (d.isClassObject(p) ? "static " + p : "c" + d.classIndex(p)) + (up[1] != v ? "+" : "");
    }

    /** {owner, its child on the way}: the first object above {@code v} that isn't a collection's insides. */
    private int[] up(int v) {
        int child = v;
        int p = d.parent(v);
        while (p >= 0 && p != d.root() && (array[d.classIndex(p)] || internal[d.classIndex(p)])
                && !d.isClassObject(p)) {
            child = p;
            p = d.parent(p);
        }
        return new int[] {p, child};
    }

    /** Where an object is kept, in words: "Session.tags", "static App.CACHE", "inside a HashMap in App.CACHE (static)". */
    String where(int v) throws IOException {
        int[] up = up(v);
        int p = up[0];
        int child = up[1];
        if (p < 0) {
            return "nothing strong";
        }
        if (p == d.root()) {
            return d.rootText(child);
        }
        String via = d.via(p, child);
        if (d.isClassObject(p)) {
            String cls = d.describe(p).substring("class ".length());
            return simple(cls) + "." + (via.startsWith("static ") ? via.substring(7) : via) + " (static)";
        }
        if (child != v) { // inside a collection
            return "inside " + aOrAn(simple(d.className(p))) + " in " + whereShort(p);
        }
        return simple(d.className(p)) + "." + via;
    }

    /** Where, one level only (for "items of a HashMap in …"). */
    private String whereShort(int v) throws IOException {
        int[] up = up(v);
        int p = up[0];
        if (p < 0) {
            return "nothing strong";
        }
        if (p == d.root()) {
            return d.rootText(up[1]);
        }
        String via = d.via(p, up[1]);
        if (d.isClassObject(p)) {
            String cls = d.describe(p).substring("class ".length());
            return simple(cls) + "." + (via.startsWith("static ") ? via.substring(7) : via) + " (static)";
        }
        return simple(d.className(p)) + (up[1] != v ? " (inside)" : "." + via);
    }

    /** "com.acme.App$Session" → "App.Session" (as in code); "Foo$1" stays. */
    static String simple(String cls) {
        return HeapDump.shortClass(cls).replaceAll("\\$(?=[A-Za-z_])", ".");
    }

    private static String aOrAn(String word) {
        return ("AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "an " : "a ") + word;
    }

    /** The most common place among some objects: "Session.status", or "mostly Session.status". */
    private String commonWhere(List<Integer> nodes) throws IOException {
        Map<String, Integer> by = new HashMap<>();
        for (int v : nodes) {
            by.merge(where(v), 1, Integer::sum);
        }
        Map.Entry<String, Integer> best = null;
        for (Map.Entry<String, Integer> e : by.entrySet()) {
            if (best == null || e.getValue() > best.getValue()) {
                best = e;
            }
        }
        if (best == null) {
            return "";
        }
        return best.getValue() == nodes.size() ? best.getKey() : "mostly " + best.getKey();
    }

    private void check(int v) {
        if ((v & 0xFFFF) == 0 && cancel.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
    }
}
