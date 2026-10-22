package logsift.jvm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a JVM spent its time (or memory, or lock waits): stacks merged into a tree, each node
 * with the samples (or bytes, or nanoseconds) in it and below it. Read from async-profiler's
 * "collapsed" text ({@code a;b;c 42} per line) or built from Flight Recorder events.
 */
public final class Profile {

    /** What a frame is: Java (compiled, inlined, interpreted, C1), native code, the kernel. */
    public enum Kind { JAVA, INLINED, INTERPRETED, C1, NATIVE, KERNEL, THREAD }

    /** One frame in the tree. */
    public static final class Node {
        private final String name;
        private final Kind kind;
        private final Node parent;
        private long total;
        private long self;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private List<Node> sorted;

        Node(String name, Kind kind, Node parent) {
            this.name = name;
            this.kind = kind;
            this.parent = parent;
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        public Node parent() {
            return parent;
        }

        public long total() {
            return total;
        }

        public long self() {
            return self;
        }

        /** Children, biggest first. */
        public synchronized List<Node> children() {
            if (sorted == null) {
                List<Node> l = new ArrayList<>(children.values());
                l.sort(Comparator.comparingLong(Node::total).reversed());
                sorted = List.copyOf(l);
            }
            return sorted;
        }

        public int depth() {
            int d = 0;
            for (Node n = parent; n != null; n = n.parent) {
                d++;
            }
            return d;
        }

        Node child(String name, Kind kind) {
            return children.computeIfAbsent(name, k -> new Node(name, kind, this));
        }
    }

    /** A method and the time in it: its own ({@code self}) and with what it called ({@code total}). */
    public record Method(String name, Kind kind, long self, long total) {}

    private final String title;
    private final String unit;
    private final Node root = new Node("all", Kind.THREAD, null);
    private final List<String> warnings = new ArrayList<>();

    /**
     * @param title what was profiled ("CPU of orders-api for 30 s")
     * @param unit  "samples", "bytes" or "ns"
     */
    public Profile(String title, String unit) {
        this.title = title;
        this.unit = unit;
    }

    public String title() {
        return title;
    }

    public String unit() {
        return unit;
    }

    public Node root() {
        return root;
    }

    public long total() {
        return root.total;
    }

    public List<String> warnings() {
        return warnings;
    }

    public void warn(String w) {
        if (!warnings.contains(w)) {
            warnings.add(w);
        }
    }

    /** Adds one stack (outermost frame first) with its weight. */
    public void add(List<String> frames, long weight) {
        if (weight <= 0) {
            return;
        }
        Node n = root;
        n.total += weight;
        n.sorted = null;
        for (String raw : frames) {
            Kind k = kindOf(raw);
            n = n.child(clean(raw), k);
            n.total += weight;
            n.sorted = null;
        }
        n.self += weight;
    }

    /** Reads async-profiler's collapsed output ({@code frame;frame;frame count} per line). */
    public static Profile fromCollapsed(String text, String title, String unit) {
        Profile p = new Profile(title, unit);
        for (String line : text.split("\n")) {
            String l = line.strip();
            int sp = l.lastIndexOf(' ');
            if (sp <= 0) {
                continue;
            }
            long w;
            try {
                w = Long.parseLong(l.substring(sp + 1));
            } catch (NumberFormatException e) {
                continue; // "Profiling for 30 seconds", "Done"
            }
            p.add(List.of(l.substring(0, sp).split(";")), w);
        }
        return p;
    }

    /** The profile as collapsed text (it reads back the same). */
    public String toCollapsed() {
        StringBuilder sb = new StringBuilder();
        List<String> path = new ArrayList<>();
        write(root, path, sb);
        return sb.toString();
    }

    private static void write(Node n, List<String> path, StringBuilder sb) {
        if (n.self > 0 && !path.isEmpty()) {
            sb.append(String.join(";", path)).append(' ').append(n.self).append('\n');
        }
        for (Node c : n.children.values()) {
            path.add(c.name + suffix(c.kind));
            write(c, path, sb);
            path.remove(path.size() - 1);
        }
    }

    private static String suffix(Kind k) {
        return switch (k) {
            case JAVA -> "_[j]";
            case INLINED -> "_[i]";
            case INTERPRETED -> "_[0]";
            case C1 -> "_[1]";
            case KERNEL -> "_[k]";
            default -> "";
        };
    }

    /** The methods with the most time of their own, with the time below them too. */
    public List<Method> hottest(int max) {
        Map<String, long[]> self = new HashMap<>();
        Map<String, Kind> kinds = new HashMap<>();
        Map<String, Integer> onPath = new HashMap<>();
        walk(root, self, kinds, onPath);
        List<Method> out = new ArrayList<>();
        self.forEach((name, v) -> out.add(new Method(name, kinds.get(name), v[0], v[1])));
        out.sort(Comparator.comparingLong(Method::self).reversed().thenComparing(Comparator.comparingLong(Method::total).reversed()));
        return out.size() > max ? List.copyOf(out.subList(0, max)) : out;
    }

    private static void walk(Node n, Map<String, long[]> acc, Map<String, Kind> kinds, Map<String, Integer> onPath) {
        for (Node c : n.children.values()) {
            long[] v = acc.computeIfAbsent(c.name, k -> new long[2]);
            kinds.putIfAbsent(c.name, c.kind);
            v[0] += c.self;
            int seen = onPath.getOrDefault(c.name, 0);
            if (seen == 0) {
                v[1] += c.total; // a recursive call isn't counted twice
            }
            onPath.put(c.name, seen + 1);
            walk(c, acc, kinds, onPath);
            if (seen == 0) {
                onPath.remove(c.name);
            } else {
                onPath.put(c.name, seen);
            }
        }
    }

    /** All nodes of a method (for highlighting it). */
    public List<Node> find(String name) {
        List<Node> out = new ArrayList<>();
        collect(root.children.values(), name, out);
        return out;
    }

    private static void collect(Collection<Node> nodes, String name, List<Node> out) {
        for (Node n : nodes) {
            if (n.name.equals(name)) {
                out.add(n);
            }
            collect(n.children.values(), name, out);
        }
    }

    static Kind kindOf(String raw) {
        if (raw.endsWith("_[j]")) {
            return Kind.JAVA;
        }
        if (raw.endsWith("_[i]")) {
            return Kind.INLINED;
        }
        if (raw.endsWith("_[0]")) {
            return Kind.INTERPRETED;
        }
        if (raw.endsWith("_[1]")) {
            return Kind.C1;
        }
        if (raw.endsWith("_[k]")) {
            return Kind.KERNEL;
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return Kind.THREAD; // a thread's name ([tid=…] with -t)
        }
        // without annotations: Java methods look like java/lang/Thread.run or java.lang.Thread.run
        if (!raw.contains("::") && (raw.indexOf('/') > 0 && raw.indexOf('.') > 0 || JAVA_NAME.matcher(raw).matches())) {
            return Kind.JAVA;
        }
        return Kind.NATIVE;
    }

    private static final java.util.regex.Pattern JAVA_NAME = java.util.regex.Pattern.compile(
            "[a-z][\\w$]*(?:\\.[\\w$]+)*\\.[A-Z][\\w$]*\\.[\\w$<>]+");

    /** The frame without its annotation, with dots in Java class names. */
    static String clean(String raw) {
        String s = raw.length() > 4 && raw.charAt(raw.length() - 4) == '_' && raw.charAt(raw.length() - 3) == '['
                && raw.endsWith("]") ? raw.substring(0, raw.length() - 4) : raw;
        if (s.indexOf('/') > 0 && !s.contains("::") && !s.startsWith("/")) {
            s = s.replace('/', '.');
        }
        return s;
    }

    /** "1,234 samples", "12.3 MB", "450 ms". */
    public String amount(long v) {
        return switch (unit) {
            case "bytes" -> bytes(v);
            case "ns" -> v >= 1_000_000_000L ? String.format("%.1f s", v / 1e9)
                    : v >= 1_000_000 ? String.format("%.0f ms", v / 1e6) : String.format("%.0f µs", v / 1e3);
            default -> String.format("%,d %s", v, v == 1 ? "sample" : "samples");
        };
    }

    private static String bytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, u[i]);
    }
}
