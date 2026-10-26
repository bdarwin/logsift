package logsift.heap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What an object holds, as nested boxes for a map of the heap: through the insides of collections
 * (their tables and entries) to what is kept in them, objects of one class together
 * ("20,000 × Session"), the biggest apart, three levels deep.
 */
public final class HeapMap {
    private HeapMap() {
    }

    /** Children shown at the first level. */
    static final int FIRST = 60;

    /** One box: an object (node), many of one class ({@code count} > 1, node: one of them), or the rest (-1). */
    public static final class Box {
        private final int node;
        private final long bytes;
        private final String label;
        private final String tip;
        private final long count;
        private final List<Box> inside;

        public Box(int node, long bytes, String label, String tip, long count, List<Box> inside) {
            this.node = node;
            this.bytes = bytes;
            this.label = label;
            this.tip = tip;
            this.count = count;
            this.inside = inside;
        }

        public int node() {
            return node;
        }

        public long bytes() {
            return bytes;
        }

        public String label() {
            return label;
        }

        /** Tooltip text (HTML), or null. */
        public String tip() {
            return tip;
        }

        public long count() {
            return count;
        }

        public List<Box> inside() {
            return inside;
        }
    }

    /** Objects looked at for one box's contents, at the most. */
    static final int BUDGET = 20_000_000;

    /** The objects of one class among what a box holds. */
    private static final class Group {
        final String cls;
        long count;
        long bytes;
        final int[] top = {-1, -1, -1}; // the biggest three, biggest first
        final long[] topBytes = {-1, -1, -1};

        Group(String cls) {
            this.cls = cls;
        }

        void add(int v, long b) {
            count++;
            bytes += b;
            for (int i = 0; i < 3; i++) {
                if (b > topBytes[i]) {
                    for (int j = 2; j > i; j--) {
                        top[j] = top[j - 1];
                        topBytes[j] = topBytes[j - 1];
                    }
                    top[i] = v;
                    topBytes[i] = b;
                    return;
                }
            }
        }
    }

    private static boolean isReferenceArray(String cls) {
        return cls.endsWith("[]") && !cls.matches("(boolean|byte|char|short|int|long|float|double)(\\[])+");
    }

    /** The focus with what it holds, and what the biggest of those hold. */
    public static Box read(HeapDump d, int focus) throws java.io.IOException {
        List<Box> kids = contents(d, focus, 2);
        return new Box(focus, d.retained(focus), focus == d.root() ? "The heap" : d.describe(focus), null, 1, kids);
    }

    /**
     * What an object holds, as boxes: through the insides of collections (their tables and entries)
     * to what is kept in them, objects of one class together ("20,000 × Session"), the biggest
     * apart; {@code deeper}: how many levels of the biggest boxes' own contents to read.
     */
    private static List<Box> contents(HeapDump d, int v, int deeper) throws java.io.IOException {
        long total = d.retained(v) - d.shallow(v);
        // by class name (one String per class): its group, and what kind of class it is
        Map<String, Group> groups = new java.util.IdentityHashMap<>();
        Map<String, Integer> kinds = new java.util.IdentityHashMap<>(); // 1: a collection's insides, 2: an array of references
        java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
        todo.add(v);
        int[] seen = {0};
        while (!todo.isEmpty()) {
            int x = todo.poll();
            boolean top = x == v;
            d.eachHeld(x, k -> {
                String cls = d.className(k);
                Integer known = kinds.get(cls);
                if (known == null) {
                    known = HeapFindings.isInternal(cls) ? 1 : isReferenceArray(cls) ? 2 : 0;
                    kinds.put(cls, known);
                }
                int kind = known;
                boolean expand = seen[0] < BUDGET && (kind == 1 || kind == 2 && top && v != d.root());
                seen[0]++;
                if (expand) {
                    todo.add(k);
                } else {
                    Group g = groups.get(cls);
                    if (g == null) {
                        g = new Group(cls);
                        groups.put(cls, g);
                    }
                    g.add(k, d.retained(k));
                }
            });
        }
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        List<Box> out = new ArrayList<>();
        long shown = 0;
        long small = 0;
        for (Group g : sorted) {
            if (out.size() >= FIRST || total > 0 && g.bytes < total / 1000) {
                small += g.bytes;
                continue;
            }
            long rest = g.bytes;
            int apart = 0; // the biggest, shown on their own
            while (apart < 3 && g.top[apart] >= 0) {
                long b = g.topBytes[apart];
                if (g.count - apart > 1 && b < total * 0.03) {
                    break; // the rest go together
                }
                List<Box> inside = deeper > 0 && total > 0 && b >= total / 100
                        ? contents(d, g.top[apart], deeper - 1) : java.util.Collections.<Box>emptyList();
                out.add(box(d, g.top[apart], b, 1, inside));
                rest -= b;
                shown += b;
                apart++;
            }
            long left = g.count - apart;
            if (left == 1 && apart < 3) {
                out.add(box(d, g.top[apart], rest, 1, java.util.Collections.<Box>emptyList())); // the last one: as itself
                shown += rest;
            } else if (left > 0) {
                out.add(group(d, g.cls, left, rest, apart < 3 ? g.top[apart] : g.top[0]));
                shown += rest;
            }
        }
        long structure = total - shown - small;
        if (small > 0) {
            out.add(new Box(-1, small, "smaller objects", "<html>Smaller objects: " + HeapFindings.bytes(small)
                    + "</html>", 0, java.util.Collections.<Box>emptyList()));
        }
        if (v != d.root() && structure > total / 200 && structure > 0) {
            String kind = shortName(d.describe(v));
            int sp = kind.indexOf(' ');
            kind = sp < 0 ? kind : kind.substring(0, sp);
            out.add(new Box(-1, structure, kind + "'s own parts", "<html>The " + esc(kind)
                    + "'s own parts (its table, its entries): " + HeapFindings.bytes(structure) + "</html>", 0,
                    java.util.Collections.<Box>emptyList()));
        }
        out.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
        return out;
    }

    private static Box box(HeapDump d, int v, long bytes, long count, List<Box> inner) throws java.io.IOException {
        String what = d.describe(v);
        String where = HeapFindings.where(d, v);
        String tip = "<html><b>" + esc(what) + "</b><br>" + esc(where) + "<br>holds " + HeapFindings.bytes(bytes)
                + "</html>";
        return new Box(v, bytes, shortName(what), tip, count, inner);
    }

    private static Box group(HeapDump d, String cls, long count, long bytes, int sample) throws java.io.IOException {
        String name = shortName(cls);
        String tip = "<html><b>" + String.format("%,d", count) + " × " + esc(cls) + "</b>, "
                + HeapFindings.bytes(bytes / Math.max(1, count)) + " each on average, with what they hold<br>"
                + esc(HeapFindings.where(d, sample)) + "<br>hold " + HeapFindings.bytes(bytes)
                + "<br>Click: why the biggest is alive. Double-click: go into it.</html>";
        return new Box(sample, bytes, String.format("%,d", count) + " × " + name, tip, count, java.util.Collections.<Box>emptyList());
    }

    /** "java.util.HashMap (7,000,000 entries)" → "HashMap (7,000,000 entries)". */
    public static String shortName(String described) {
        if (described.startsWith("class ")) {
            return "class " + shortName(described.substring(6));
        }
        int sp = described.indexOf(' ');
        String cls = sp < 0 ? described : described.substring(0, sp);
        String rest = sp < 0 ? "" : described.substring(sp);
        int bracket = cls.indexOf('[');
        String base = bracket < 0 ? cls : cls.substring(0, bracket);
        int dot = base.lastIndexOf('.');
        return (dot < 0 ? base : base.substring(dot + 1)).replace('$', '.') + (bracket < 0 ? "" : cls.substring(bracket))
                + rest;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
