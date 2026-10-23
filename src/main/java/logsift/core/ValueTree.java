package logsift.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The values of a column (loggers, classes, components...) as a tree split at {@code .}, {@code /}
 * and {@code \}: {@code com.acme.db.Pool} is under {@code com.acme} › {@code db}. Each node can
 * be shown or hidden; the hidden ones become a filter on that column.
 */
public final class ValueTree {

    /** Whether all, none or some of a node's lines are shown. */
    public enum State { ALL, NONE, SOME }

    public static final class Node {
        private String label;         // the part shown in the tree ("db", or "com.acme" when compacted)
        private final String path;    // the value up to here ("com.acme.db")
        private Node parent;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private long own;             // lines whose value is exactly the path
        private long total;           // lines at or under this node
        private boolean exact;        // the path itself is a value
        private boolean shown = true; // its own lines are shown

        Node(String label, String path, Node parent) {
            this.label = label;
            this.path = path;
            this.parent = parent;
        }

        public String label() {
            return label;
        }

        public String path() {
            return path;
        }

        public Node parent() {
            return parent;
        }

        /** Children, most lines first. */
        public List<Node> children() {
            List<Node> out = new ArrayList<>(children.values());
            out.sort(Comparator.comparingLong((Node n) -> -n.total).thenComparing(n -> n.label));
            return out;
        }

        public long total() {
            return total;
        }

        public long own() {
            return own;
        }

        /** Whether the path itself is a value (a logger named like a package, say). */
        public boolean isValue() {
            return exact;
        }

        public boolean isLeaf() {
            return children.isEmpty();
        }
    }

    private final Node root = new Node("", "", null);
    private int values;

    private static final Pattern SEP = Pattern.compile("[./\\\\]");

    /** A tree of these values and their line counts. */
    public static ValueTree of(List<ColumnStats.Value> values) {
        ValueTree t = new ValueTree();
        for (ColumnStats.Value v : values) {
            if (v.value() == null || v.value().isEmpty()) {
                continue;
            }
            t.add(v.value(), v.count());
        }
        t.compact(t.root);
        return t;
    }

    private void add(String value, long count) {
        values++;
        Node n = root;
        n.total += count;
        int start = 0;
        var m = SEP.matcher(value);
        while (true) {
            boolean more = m.find();
            int end = more ? m.start() : value.length();
            if (end > start || !more) {
                String part = value.substring(start, end);
                String path = value.substring(0, end);
                Node p = n;
                n = n.children.computeIfAbsent(part, k -> new Node(k, path, p));
                n.total += count;
            }
            if (!more) {
                break;
            }
            start = m.end();
        }
        n.own += count;
        n.exact = true;
    }

    /** Joins chains like com › acme into one node "com.acme" (only one child, no lines of its own). */
    private void compact(Node n) {
        for (String key : List.copyOf(n.children.keySet())) {
            Node c = n.children.get(key);
            while (c.children.size() == 1 && !c.exact) {
                Node only = c.children.values().iterator().next();
                String sep = only.path.substring(c.path.length(), only.path.length() - only.label.length());
                only.label = c.label + sep + only.label;
                only.parent = n;
                c = only;
            }
            n.children.put(key, c);
            compact(c);
        }
    }

    public Node root() {
        return root;
    }

    /** How many different values there are. */
    public int valueCount() {
        return values;
    }

    // ---------------------------------------------------------------- showing and hiding

    public State state(Node n) {
        boolean any = false;
        boolean all = true;
        if (n.exact) {
            any = n.shown;
            all = n.shown;
        }
        for (Node c : n.children.values()) {
            State s = state(c);
            any |= s != State.NONE;
            all &= s == State.ALL;
        }
        return all ? State.ALL : any ? State.SOME : State.NONE;
    }

    /** Shows or hides a node and everything under it. */
    public void setShown(Node n, boolean on) {
        n.shown = on;
        for (Node c : n.children.values()) {
            setShown(c, on);
        }
    }

    /** Shows only this node (and what is under it). */
    public void showOnly(Node n) {
        setShown(root, false);
        setShown(n, true);
    }

    public void showAll() {
        setShown(root, true);
    }

    /** Shows or hides just the lines whose value is exactly this node's path. */
    public void setOwnShown(Node n, boolean on) {
        n.shown = on;
    }

    /** Lines shown now. */
    public long shownLines() {
        return shownLines(root);
    }

    private static long shownLines(Node n) {
        long s = n.shown ? n.own : 0;
        for (Node c : n.children.values()) {
            s += shownLines(c);
        }
        return s;
    }

    /** The node for a value or branch, or null. */
    public Node find(String path) {
        Node n = root;
        while (n != null && !n.path.equals(path)) {
            Node next = null;
            for (Node c : n.children.values()) {
                if (path.equals(c.path) || (path.startsWith(c.path) && path.length() > c.path.length()
                        && "./\\".indexOf(path.charAt(c.path.length())) >= 0)) {
                    next = c;
                    break;
                }
            }
            n = next;
        }
        return n;
    }

    public boolean anyHidden() {
        return state(root) != State.ALL;
    }

    /** What is hidden: a value with everything under it, or just the value itself. */
    public record Hidden(String path, boolean withChildren) {}

    /** The fewest items that describe what is hidden. */
    public List<Hidden> hidden() {
        List<Hidden> out = new ArrayList<>();
        for (Node c : root.children.values()) {
            hidden(c, out);
        }
        return out;
    }

    private void hidden(Node n, List<Hidden> out) {
        State s = state(n);
        if (s == State.ALL) {
            return;
        }
        if (s == State.NONE) {
            out.add(new Hidden(n.path, true));
            return;
        }
        if (n.exact && !n.shown) {
            out.add(new Hidden(n.path, false));
        }
        for (Node c : n.children.values()) {
            hidden(c, out);
        }
    }

    /** CSV: skips the fields before the column (quoted or not), then looks at its value. */
    private static String csvLineRegex(LogFormat format, String column, List<Hidden> hidden) {
        int k = format.columns().indexOf(column);
        if (k < 0) {
            return null;
        }
        String d = String.format("\\x{%x}", (int) format.delimiter());
        String field = "(?:\\s*+\"(?:[^\"]|\"\")*+\"[^" + d + "]*+|[^" + d + "]*+)";
        String end = "(?:\"?\\s*(?:" + d + "|$))";
        List<String> alts = new ArrayList<>();
        for (Hidden h : hidden) {
            alts.add(Pattern.quote(h.path()) + (h.withChildren() ? "(?:" + end + "|[./\\\\])" : end));
        }
        return "^\\x{FEFF}?(?:" + field + d + "){" + k + "}\\s*\"?(?:" + String.join("|", alts) + ")";
    }

    /** Lines (in the format) whose column value is hidden: for a filter that hides them. */
    public static String lineRegex(LogFormat format, String column, List<Hidden> hidden) {
        if (hidden.isEmpty()) {
            return null;
        }
        if (format.isJson()) {
            String key = format.jsonKey(column);
            if (key == null) {
                return null;
            }
            List<String> alts = new ArrayList<>();
            for (Hidden h : hidden) {
                alts.add(Pattern.quote(h.path()) + (h.withChildren() ? "(?:\"|[./\\\\])" : "\""));
            }
            return "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"(?:" + String.join("|", alts) + ")";
        }
        if (format.isCsv()) {
            return csvLineRegex(format, column, hidden);
        }
        String p = format.regex();
        String open = "(?<" + column + ">";
        int at = p.indexOf(open);
        if (at < 0) {
            return null;
        }
        int in = at + open.length();
        int close = groupEnd(p, at);
        String end = terminator(p.substring(in, close));
        List<String> alts = new ArrayList<>();
        for (Hidden h : hidden) {
            alts.add(Pattern.quote(h.path()) + (h.withChildren() ? "(?:" + end + "|[./\\\\])" : end));
        }
        return p.substring(0, in) + "(?=" + String.join("|", alts) + ")" + p.substring(in);
    }

    /** Index of the ) closing the group that opens at {@code open}. */
    private static int groupEnd(String p, int open) {
        int depth = 0;
        boolean inClass = false;
        for (int i = open; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\') {
                i++;
            } else if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return p.length();
    }

    /** What follows a value of a group with this body: where the value ends. */
    private static String terminator(String body) {
        if (body.equals("\\S+") || body.equals("\\S*")) {
            return "(?:\\s|$)";
        }
        var m = Pattern.compile("\\[\\^(\\\\?.)\\][*+]").matcher(body);
        if (m.matches()) {
            String c = m.group(1);
            if (c.length() == 1 && "|.$^?*+()[]{}".contains(c)) {
                c = "\\" + c;
            }
            return "(?:" + c + "|$)";
        }
        if (body.equals(".*")) {
            return "$";
        }
        return "(?![\\w$-])";
    }
}
