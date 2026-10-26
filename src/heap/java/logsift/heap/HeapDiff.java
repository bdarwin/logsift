package logsift.heap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Two heap dumps of the same program compared: what grew, and what holds the growth. Objects
 * move between dumps, so they are matched by where they are kept (the chain of fields from a GC
 * root, such as {@code static App.CACHE}), not by address.
 */
public final class HeapDiff {
    private HeapDiff() {
    }

    /** One place that holds memory, in both dumps ({@code before}: 0 when it is new). */
    public static final class Place {
        private final String where;
        private final String before;
        private final String after;
        private final long beforeBytes;
        private final long afterBytes;
        private final int node;
        private final String contents;

        public Place(String where, String before, String after, long beforeBytes, long afterBytes, int node, String contents) {
            this.where = where;
            this.before = before;
            this.after = after;
            this.beforeBytes = beforeBytes;
            this.afterBytes = afterBytes;
            this.node = node;
            this.contents = contents;
        }

        public String where() {
            return where;
        }

        public String before() {
            return before;
        }

        public String after() {
            return after;
        }

        public long beforeBytes() {
            return beforeBytes;
        }

        public long afterBytes() {
            return afterBytes;
        }

        public int node() {
            return node;
        }

        public String contents() {
            return contents;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Place)) {
                return false;
            }
            Place x = (Place) o;
            return java.util.Objects.equals(where, x.where)
                    && java.util.Objects.equals(before, x.before)
                    && java.util.Objects.equals(after, x.after)
                    && beforeBytes == x.beforeBytes
                    && afterBytes == x.afterBytes
                    && node == x.node
                    && java.util.Objects.equals(contents, x.contents);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(where, before, after, beforeBytes, afterBytes, node, contents);
        }

        @Override
        public String toString() {
            return "Place[" + "where=" + where + ", " + "before=" + before + ", " + "after=" + after + ", " + "beforeBytes=" + beforeBytes + ", " + "afterBytes=" + afterBytes + ", " + "node=" + node + ", " + "contents=" + contents + "]";
        }

        public long growth() {
            return afterBytes - beforeBytes;
        }
    }

    /** One class in both dumps. */
    public static final class ClassDelta {
        private final String name;
        private final long beforeCount;
        private final long afterCount;
        private final long beforeBytes;
        private final long afterBytes;

        public ClassDelta(String name, long beforeCount, long afterCount, long beforeBytes, long afterBytes) {
            this.name = name;
            this.beforeCount = beforeCount;
            this.afterCount = afterCount;
            this.beforeBytes = beforeBytes;
            this.afterBytes = afterBytes;
        }

        public String name() {
            return name;
        }

        public long beforeCount() {
            return beforeCount;
        }

        public long afterCount() {
            return afterCount;
        }

        public long beforeBytes() {
            return beforeBytes;
        }

        public long afterBytes() {
            return afterBytes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ClassDelta)) {
                return false;
            }
            ClassDelta x = (ClassDelta) o;
            return java.util.Objects.equals(name, x.name)
                    && beforeCount == x.beforeCount
                    && afterCount == x.afterCount
                    && beforeBytes == x.beforeBytes
                    && afterBytes == x.afterBytes;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, beforeCount, afterCount, beforeBytes, afterBytes);
        }

        @Override
        public String toString() {
            return "ClassDelta[" + "name=" + name + ", " + "beforeCount=" + beforeCount + ", " + "afterCount=" + afterCount + ", " + "beforeBytes=" + beforeBytes + ", " + "afterBytes=" + afterBytes + "]";
        }

        public long growth() {
            return afterBytes - beforeBytes;
        }
    }

    public static final class Report {
        private final long beforeBytes;
        private final long afterBytes;
        private final long beforeTime;
        private final long afterTime;
        private final List<Place> places;
        private final List<ClassDelta> classes;

        public Report(long beforeBytes, long afterBytes, long beforeTime, long afterTime, List<Place> places, List<ClassDelta> classes) {
            this.beforeBytes = beforeBytes;
            this.afterBytes = afterBytes;
            this.beforeTime = beforeTime;
            this.afterTime = afterTime;
            this.places = places;
            this.classes = classes;
        }

        public long beforeBytes() {
            return beforeBytes;
        }

        public long afterBytes() {
            return afterBytes;
        }

        public long beforeTime() {
            return beforeTime;
        }

        public long afterTime() {
            return afterTime;
        }

        public List<Place> places() {
            return places;
        }

        public List<ClassDelta> classes() {
            return classes;
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
            return beforeBytes == x.beforeBytes
                    && afterBytes == x.afterBytes
                    && beforeTime == x.beforeTime
                    && afterTime == x.afterTime
                    && java.util.Objects.equals(places, x.places)
                    && java.util.Objects.equals(classes, x.classes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(beforeBytes, afterBytes, beforeTime, afterTime, places, classes);
        }

        @Override
        public String toString() {
            return "Report[" + "beforeBytes=" + beforeBytes + ", " + "afterBytes=" + afterBytes + ", " + "beforeTime=" + beforeTime + ", " + "afterTime=" + afterTime + ", " + "places=" + places + ", " + "classes=" + classes + "]";
        }

        public long growth() {
            return afterBytes - beforeBytes;
        }
    }

    /** A place holds at least this share of its heap to be compared. */
    static final double SHARE = 0.002;
    private static final int MAX_PLACES = 3000;

    public static Report of(HeapDump before, HeapDump after, BooleanSupplier cancel) throws IOException {
        BooleanSupplier c = cancel == null ? () -> false : cancel;
        Map<String, Integer> a = places(before, c);
        Map<String, Integer> b = places(after, c);
        List<Place> grew = new ArrayList<>();
        for (Map.Entry<String, Integer> e : b.entrySet()) {
            int v = e.getValue();
            Integer u = a.get(e.getKey());
            long was = u == null ? 0 : before.retained(u);
            long now = after.retained(v);
            if (now > was) {
                grew.add(new Place(where(after, v), u == null ? "" : before.describe(u), after.describe(v), was, now,
                        v, ""));
            }
        }
        grew.sort((x, y) -> Long.compare(y.growth(), x.growth()));
        // a place whose growth is mostly a place inside it: show the inner one (where it piles up)
        List<Place> shown = new ArrayList<>();
        for (Place p : grew) {
            if (shown.size() >= 50) {
                break;
            }
            boolean inner = false;
            for (int i = 0; i < shown.size(); i++) {
                Place s = shown.get(i);
                if (holds(after, s.node(), p.node()) && p.growth() >= s.growth() * 0.8) {
                    shown.set(i, p); // the deeper one says more
                    inner = true;
                    break;
                }
                if (holds(after, p.node(), s.node())) {
                    inner = true; // already shown deeper
                    break;
                }
            }
            if (!inner) {
                shown.add(p);
            }
        }
        List<Place> out = new ArrayList<>();
        for (Place p : shown) {
            if (p.growth() <= 0) {
                continue;
            }
            out.add(new Place(p.where(), p.before(), p.after(), p.beforeBytes(), p.afterBytes(), p.node(),
                    HeapFindings.contents(after, p.node())));
        }
        out.sort((x, y) -> Long.compare(y.growth(), x.growth()));
        return new Report(before.reachableBytes(), after.reachableBytes(), before.timestamp(), after.timestamp(),
                out, classes(before, after));
    }

    /** True when {@code outer} holds (dominates) {@code inner}. */
    private static boolean holds(HeapDump d, int outer, int inner) {
        for (int x = d.holder(inner); x >= 0; x = d.holder(x)) {
            if (x == outer) {
                return true;
            }
        }
        return false;
    }

    /** The objects that hold a share of the heap, by where they are kept. */
    static Map<String, Integer> places(HeapDump d, BooleanSupplier cancel) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        long min = Math.max(1, (long) (d.reachableBytes() * SHARE));
        List<Integer> todo = new ArrayList<>();
        todo.add(d.root());
        while (!todo.isEmpty() && out.size() < MAX_PLACES) {
            int v = todo.remove(todo.size() - 1);
            for (int k : d.held(v, 200)) {
                if (d.retained(k) < min) {
                    break; // biggest first
                }
                todo.add(k);
                String cls = d.className(k);
                if (cls.endsWith("[]") || HeapFindings.isInternal(cls)) {
                    continue; // a collection's insides: the collection is the place
                }
                out.putIfAbsent(key(d, k), k);
            }
            if (cancel.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
        }
        return out;
    }

    /** Where an object is kept, the same in both dumps: the GC root and the fields to it (indexes left out). */
    static String key(HeapDump d, int v) throws IOException {
        List<HeapDump.Step> path = d.pathFromRoot(v);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            HeapDump.Step s = path.get(i);
            String via = s.via().startsWith("[") ? "[]" : s.via();
            if (i == 0) {
                sb.append(d.isClassObject(s.node()) ? d.describe(s.node()) : via);
            } else {
                sb.append(" → ").append(via);
            }
            sb.append(" : ").append(d.className(s.node()));
        }
        return sb.toString();
    }

    /** Where, in words: "LeakApp.CACHE (static)", or the root and the last fields. */
    static String where(HeapDump d, int v) throws IOException {
        String w = HeapFindings.where(d, v);
        return w.startsWith("in ") ? w.substring(3) : w;
    }

    private static List<ClassDelta> classes(HeapDump before, HeapDump after) {
        Map<String, long[]> by = new HashMap<>();
        for (HeapDump.ClassStat s : before.classes()) {
            by.computeIfAbsent(s.name(), k -> new long[4])[0] += s.count();
            by.get(s.name())[2] += s.shallow();
        }
        for (HeapDump.ClassStat s : after.classes()) {
            by.computeIfAbsent(s.name(), k -> new long[4])[1] += s.count();
            by.get(s.name())[3] += s.shallow();
        }
        List<ClassDelta> out = new ArrayList<>();
        by.forEach((name, x) -> out.add(new ClassDelta(name, x[0], x[1], x[2], x[3])));
        out.sort((x, y) -> Long.compare(y.growth(), x.growth()));
        return out;
    }
}
