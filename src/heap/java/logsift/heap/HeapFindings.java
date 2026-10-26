package logsift.heap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a heap dump says, in sentences: which objects hold most of the heap (and where they are
 * kept), what is in them, and memory that only weak or soft references reach.
 */
public final class HeapFindings {
    private HeapFindings() {
    }

    /** One finding: a sentence, the object it is about (or -1), and the bytes involved. */
    public static final class Finding {
        private final Kind kind;
        private final String title;
        private final String detail;
        private final int node;
        private final long bytes;

        public Finding(Kind kind, String title, String detail, int node, long bytes) {
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.node = node;
            this.bytes = bytes;
        }

        public Kind kind() {
            return kind;
        }

        public String title() {
            return title;
        }

        public String detail() {
            return detail;
        }

        public int node() {
            return node;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Finding)) {
                return false;
            }
            Finding x = (Finding) o;
            return java.util.Objects.equals(kind, x.kind)
                    && java.util.Objects.equals(title, x.title)
                    && java.util.Objects.equals(detail, x.detail)
                    && node == x.node
                    && bytes == x.bytes;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(kind, title, detail, node, bytes);
        }

        @Override
        public String toString() {
            return "Finding[" + "kind=" + kind + ", " + "title=" + title + ", " + "detail=" + detail + ", " + "node=" + node + ", " + "bytes=" + bytes + "]";
        }
    }

    public enum Kind { HOLDER, NOT_STRONG, WASTE, SUMMARY }

    /** A holder must keep at least this share of the heap to be a finding. */
    static final double BIG = 0.10;

    public static List<Finding> of(HeapDump d) throws IOException {
        List<Finding> out = new ArrayList<>();
        long heap = d.reachableBytes();
        if (heap == 0) {
            return out;
        }
        for (int top : d.held(d.root(), 30)) {
            if (d.retained(top) < heap * BIG) {
                break;
            }
            int point = accumulation(d, top);
            int subject = owner(d, point);
            out.add(new Finding(Kind.HOLDER, holderTitle(d, subject, heap), contents(d, point), subject,
                    d.retained(subject)));
        }
        long notStrong = d.totalBytes() - heap;
        if (notStrong > d.totalBytes() * 0.05) {
            out.add(new Finding(Kind.NOT_STRONG, bytes(notStrong) + " (" + pct(notStrong, d.totalBytes())
                    + " of the dump) isn't strongly reachable",
                    "Only weak or soft references (caches) reach it, or it is garbage the JVM hadn't collected yet."
                            + " It is freed when memory runs low, so it isn't a leak.", -1, notStrong));
        }
        return out;
    }

    /** Waste is a finding from this share of the heap. */
    static final double WASTE = 0.03;

    /** A finding about memory spent for nothing (copies of strings, empty collections), or null when there is little. */
    public static Finding waste(HeapWaste.Report w, long heap) {
        if (heap == 0 || w.total() < heap * WASTE) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (w.stringBytes() >= heap * WASTE / 3) {
            parts.add("copies of the same strings");
        }
        if (w.collectionBytes() >= heap * WASTE / 3) {
            parts.add("empty or mostly empty collections");
        }
        String title = bytes(w.total()) + " (" + pct(w.total(), heap) + " of the heap) is spent on "
                + String.join(" and ", parts);
        HeapWaste.Duplicate s = w.strings().isEmpty() ? null : w.strings().get(0);
        HeapWaste.Collections c = w.collections().isEmpty() ? null : w.collections().get(0);
        String detail;
        int node;
        if (s != null && (c == null || s.wasted() >= c.wasted())) {
            detail = String.format("%,d", s.copies()) + " copies of \"" + cut(s.text(), 40) + "\" ("
                    + s.where() + ") alone waste " + bytes(s.wasted()) + ".";
            node = s.sample();
        } else if (c != null) {
            detail = String.format("%,d", c.count()) + " × " + c.what().substring(0, 1).toLowerCase(java.util.Locale.ROOT)
                    + c.what().substring(1) + " in " + c.where() + " waste " + bytes(c.wasted()) + ".";
            node = c.sample();
        } else {
            return null;
        }
        return new Finding(Kind.WASTE, title, detail + " See Wasted memory.", node, w.total());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Where memory piles up under {@code v}: follow the one object that holds most of it, as long
     * as one does (a static field → its map → the map's table).
     */
    static int accumulation(HeapDump d, int v) {
        int cur = v;
        for (int depth = 0; depth < 1000; depth++) {
            int[] kids = d.held(cur, 1);
            if (kids.length == 0 || d.retained(kids[0]) < d.retained(cur) * 0.8) {
                break;
            }
            cur = kids[0];
        }
        return cur;
    }

    /** The collection that owns an array (a HashMap rather than its table), or the object itself. */
    static int owner(HeapDump d, int v) {
        int h = d.holder(v);
        if (d.className(v).endsWith("[]") && h >= 0 && h != d.root() && !d.className(h).endsWith("[]")
                && !d.isClassObject(h) && d.retained(h) <= d.retained(v) * 1.2) {
            return h;
        }
        return v;
    }

    private static String holderTitle(HeapDump d, int subject, long heap) throws IOException {
        return article(d, subject) + " " + where(d, subject) + " holds " + bytes(d.retained(subject)) + " ("
                + pct(d.retained(subject), heap) + " of the heap)";
    }

    /** "One HashMap (20,000 entries)" / "One byte[1,024]". */
    private static String article(HeapDump d, int v) {
        String s = d.describe(v);
        return "One " + shorten(s);
    }

    /** Package names off: "java.util.HashMap (3 entries)" → "HashMap (3 entries)". */
    public static String shorten(String description) {
        int sp = description.indexOf(' ');
        String cls = sp < 0 ? description : description.substring(0, sp);
        String rest = sp < 0 ? "" : description.substring(sp);
        if (cls.equals("class")) {
            return description;
        }
        return HeapWaste.simple(cls.replace("[]", "\u0000")).replace("\u0000", "[]") + rest;
    }

    /** Where an object is kept: "in static LeakApp.CACHE", "in a local variable in Foo.bar() of thread "main"". */
    public static String where(HeapDump d, int v) throws IOException {
        List<HeapDump.Step> path = d.pathFromRoot(v);
        if (path.isEmpty()) {
            return "(not strongly reachable)";
        }
        StringBuilder sb = new StringBuilder();
        HeapDump.Step first = path.get(0);
        int i = 1;
        if (d.isClassObject(first.node()) && path.size() > 1 && path.get(1).via().startsWith("static ")) {
            String cls = d.describe(first.node()).substring("class ".length());
            sb.append("in static ").append(HeapWaste.simple(cls)).append('.')
                    .append(path.get(1).via().substring("static ".length()));
            i = 2;
        } else if (d.isClassObject(first.node())) {
            sb.append("in ").append(shorten(d.describe(first.node())));
        } else {
            sb.append("in ").append(first.via());
            if (first.node() != v) {
                sb.append(" (").append(shorten(d.describe(first.node()))).append(')');
            }
        }
        // the fields after that, as long as they are plain field names
        for (; i < path.size(); i++) {
            String via = path.get(i).via();
            if (via.startsWith("[") || via.equals("?") || sb.length() > 120) {
                sb.append("…");
                break;
            }
            sb.append('.').append(via);
        }
        return sb.toString();
    }

    /** A collection's own parts (entries, tables), not what it holds. */
    /** The insides of a collection (its entries, its table's nodes): not what is kept in it. */
    public static boolean isInternal(String cls) {
        return cls.matches(".*\\$(Node|Entry|TreeNode|TreeBin|Segment|ForwardingNode)(\\[\\])?")
                || cls.equals("java.util.WeakHashMap$Entry[]");
    }

    /** What is in an object: "Mostly 20,000 × Session, 616 bytes each (with what they hold), 90% of it." */
    static String contents(HeapDump d, int v) {
        if (d.heldCount(v) == 0) {
            return "It holds nothing else: the object itself is big.";
        }
        Map<String, HeapDump.Tally> by = d.heldByClass(v, 50_000_000L);
        if (by == null) {
            return "It holds more than 50 million objects.";
        }
        Map.Entry<String, HeapDump.Tally> top = null;
        for (Map.Entry<String, HeapDump.Tally> e : by.entrySet()) {
            if (!isInternal(e.getKey()) && (top == null || e.getValue().retained > top.getValue().retained)) {
                top = e;
            }
        }
        if (top == null) {
            return "";
        }
        HeapDump.Tally t = top.getValue();
        StringBuilder sb = new StringBuilder();
        sb.append(t.count == 1 ? "Mostly one " : "Mostly " + String.format("%,d", t.count) + " × ")
                .append(shorten(top.getKey()));
        if (t.count > 1) {
            sb.append(", ").append(bytes(t.retained / t.count)).append(" each");
            if (t.retained > t.shallow * 1.5) {
                sb.append(" with what they hold");
            }
        }
        sb.append(": ").append(pct(t.retained, d.retained(v))).append(" of it.");
        return sb.toString();
    }

    static String pct(long part, long whole) {
        double p = whole == 0 ? 0 : 100.0 * part / whole;
        return p >= 10 || p == 0 ? Math.round(p) + "%" : String.format("%.1f%%", p);
    }

    /** 1.2 GB, 340 MB, 12 KB, 96 bytes. */
    public static String bytes(long b) {
        if (b < 1024) {
            return b + " bytes";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (v >= 100 ? String.format("%.0f", v) : v >= 10 ? String.format("%.1f", v) : String.format("%.2f", v))
                + " " + units[u];
    }
}
