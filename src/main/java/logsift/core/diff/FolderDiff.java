package logsift.core.diff;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import logsift.core.Archives;

/**
 * Two folders compared (or two zips, or a folder and a zip): every file and folder by its path
 * inside, the same, different, or only on one side. Files of the same size are compared byte by
 * byte (or, if asked, by size and time only). A folder is the same when everything in it is.
 */
public final class FolderDiff {
    private FolderDiff() {}

    public enum Status {
        SAME("same"), DIFFERENT("different"), LEFT_ONLY("only left"), RIGHT_ONLY("only right");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** A file or folder on one side: its size (files) and when it was changed (-1: unknown). */
    public record Info(boolean folder, long size, long modified) {}

    /** What counts. */
    public record Options(boolean contents, boolean hidden, List<String> skip) {
        /** Contents compared, hidden files left out, the usual tool folders skipped. */
        public static final Options DEFAULT = new Options(true, false, List.of(".git", ".svn", ".hg", ".idea",
                "node_modules", ".DS_Store", "Thumbs.db"));
    }

    /** The most files read from one side. */
    public static final int MAX_FILES = 200_000;

    /** A file or folder in the comparison, with what is inside (folders). */
    public static final class Node {
        final String name;
        final String path;
        final Info left;
        final Info right;
        Status status;
        final List<Node> kids = new ArrayList<>();
        int differences; // files that differ (or are on one side) in it

        Node(String name, String path, Info left, Info right) {
            this.name = name;
            this.path = path;
            this.left = left;
            this.right = right;
        }

        public String name() {
            return name;
        }

        /** Its path inside the folders ("" for the top), with / between the parts. */
        public String path() {
            return path;
        }

        public Info left() {
            return left;
        }

        public Info right() {
            return right;
        }

        public Status status() {
            return status;
        }

        public List<Node> kids() {
            return kids;
        }

        public boolean isFolder() {
            return left != null ? left.folder() : right != null && right.folder();
        }

        /** Files below that differ or are on one side only. */
        public int differences() {
            return differences;
        }

        /** Which side changed it last: 0 the left, 1 the right, -1 unknown or the same time. */
        public int newer() {
            if (left == null || right == null || left.modified() < 0 || right.modified() < 0) {
                return -1;
            }
            long d = left.modified() - right.modified();
            return Math.abs(d) < 2000 ? -1 : d > 0 ? 0 : 1;
        }
    }

    /** What a comparison found. */
    public record Result(Path left, Path right, Node root, int files, int same, int different, int leftOnly,
                         int rightOnly, boolean capped, long millis) {}

    /** One side: a folder on disk, or a zip (whose zips inside count as folders). */
    interface Side {
        Map<String, Info> list(Options o, BooleanSupplier cancelled) throws IOException;

        InputStream open(String path) throws IOException;
    }

    static Side of(Path p) {
        return Files.isDirectory(p) ? new Disk(p) : new Zip(p);
    }

    private static final class Disk implements Side {
        private final Path root;

        Disk(Path root) {
            this.root = root;
        }

        @Override
        public Map<String, Info> list(Options o, BooleanSupplier cancelled) throws IOException {
            Map<String, Info> out = new HashMap<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    if (cancelled.getAsBoolean() || out.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!dir.equals(root)) {
                        if (skipped(dir.getFileName().toString(), o)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        out.put(rel(dir), new Info(true, 0, a.lastModifiedTime().toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (out.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (a.isRegularFile() && !skipped(f.getFileName().toString(), o)) {
                        out.put(rel(f), new Info(false, a.size(), a.lastModifiedTime().toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE; // unreadable: left out
                }
            });
            return out;
        }

        private String rel(Path p) {
            return root.relativize(p).toString().replace('\\', '/');
        }

        @Override
        public InputStream open(String path) throws IOException {
            return Files.newInputStream(root.resolve(path));
        }
    }

    private static final class Zip implements Side {
        private final Path zip;
        private final Map<String, String> entries = new HashMap<>(); // path inside → the zip's entry name

        Zip(Path zip) {
            this.zip = zip;
        }

        @Override
        public Map<String, Info> list(Options o, BooleanSupplier cancelled) throws IOException {
            Map<String, Info> out = new HashMap<>();
            for (Archives.Entry e : Archives.list(zip, cancelled)) {
                String path = e.name().replace(Archives.NESTED, "/");
                if (Archives.isZip(e.fileName()) || out.size() >= MAX_FILES) {
                    continue; // a zip inside is a folder: its files are listed
                }
                String[] parts = path.split("/");
                boolean skip = false;
                for (String part : parts) {
                    skip |= skipped(part, o);
                }
                if (skip) {
                    continue;
                }
                out.put(path, new Info(false, e.size(), -1));
                entries.put(path, e.name());
                // its folders
                for (int i = path.indexOf('/'); i > 0; i = path.indexOf('/', i + 1)) {
                    out.putIfAbsent(path.substring(0, i), new Info(true, 0, -1));
                }
            }
            return out;
        }

        @Override
        public InputStream open(String path) throws IOException {
            String entry = entries.get(path);
            if (entry == null) {
                throw new IOException("No " + path + " in " + zip.getFileName());
            }
            return Archives.open(zip, entry);
        }
    }

    private static boolean skipped(String name, Options o) {
        return !o.hidden() && name.startsWith(".") || o.skip().contains(name);
    }

    /** Compares two folders (or zips); null if cancelled. */
    public static Result compare(Path left, Path right, Options o, BooleanSupplier cancelled,
                                 Consumer<String> progress) throws IOException {
        long t0 = System.nanoTime();
        Side ls = of(left);
        Side rs = of(right);
        progress.accept("Listing " + left.getFileName() + "…");
        Map<String, Info> lm = ls.list(o, cancelled);
        progress.accept("Listing " + right.getFileName() + "…");
        Map<String, Info> rm = rs.list(o, cancelled);
        if (cancelled.getAsBoolean()) {
            return null;
        }
        boolean capped = lm.size() >= MAX_FILES || rm.size() >= MAX_FILES;
        // every path, in order: folders by their parts, case-insensitive names
        TreeMap<String, String> paths = new TreeMap<>(FolderDiff::comparePaths);
        for (String p : lm.keySet()) {
            paths.put(p, p);
        }
        for (String p : rm.keySet()) {
            paths.put(p, p);
        }
        Node root = new Node("", "", new Info(true, 0, -1), new Info(true, 0, -1));
        Map<String, Node> nodes = new HashMap<>();
        nodes.put("", root);
        int[] counts = new int[5]; // files, same, different, left only, right only
        int done = 0;
        for (String p : paths.keySet()) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            Info l = lm.get(p);
            Info r = rm.get(p);
            int slash = p.lastIndexOf('/');
            String parentPath = slash < 0 ? "" : p.substring(0, slash);
            Node parent = nodes.get(parentPath);
            if (parent == null) {
                parent = root; // a file in a folder that was left out: shown at the top
            }
            // a file on one side and a folder on the other: two rows
            if (l != null && r != null && l.folder() != r.folder()) {
                Node a = new Node(p.substring(slash + 1), p, l, null);
                Node b = new Node(p.substring(slash + 1), p, null, r);
                parent.kids.add(a);
                parent.kids.add(b);
                if (l.folder()) {
                    nodes.put(p, a);
                } else {
                    nodes.put(p, b);
                }
                continue;
            }
            Node n = new Node(p.substring(slash + 1), p, l, r);
            parent.kids.add(n);
            if ((l != null && l.folder()) || (r != null && r.folder())) {
                nodes.put(p, n);
                continue;
            }
            counts[0]++;
            if (l == null) {
                n.status = Status.RIGHT_ONLY;
                counts[4]++;
            } else if (r == null) {
                n.status = Status.LEFT_ONLY;
                counts[3]++;
            } else if (l.size() >= 0 && r.size() >= 0 && l.size() != r.size()) {
                n.status = Status.DIFFERENT;
                counts[2]++;
            } else if (!o.contents()) {
                boolean sameTime = l.modified() < 0 || r.modified() < 0 || Math.abs(l.modified() - r.modified()) < 2000;
                n.status = sameTime ? Status.SAME : Status.DIFFERENT;
                counts[sameTime ? 1 : 2]++;
            } else {
                boolean same = sameBytes(ls, rs, p, cancelled);
                n.status = same ? Status.SAME : Status.DIFFERENT;
                counts[same ? 1 : 2]++;
            }
            if (++done % 200 == 0) {
                progress.accept(String.format(Locale.ROOT, "Compared %,d files…", done));
            }
        }
        settle(root);
        return new Result(left, right, root, counts[0], counts[1], counts[2], counts[3], counts[4], capped,
                (System.nanoTime() - t0) / 1_000_000);
    }

    /** Folders get their status from what is in them; kids sorted folders first, then by name. */
    private static void settle(Node n) {
        n.kids.sort((a, b) -> a.isFolder() != b.isFolder() ? (a.isFolder() ? -1 : 1)
                : a.name.compareToIgnoreCase(b.name) != 0 ? a.name.compareToIgnoreCase(b.name) : a.name.compareTo(b.name));
        if (!n.isFolder() && n.status != null) {
            n.differences = n.status == Status.SAME ? 0 : 1;
            return;
        }
        int diffs = 0;
        for (Node k : n.kids) {
            settle(k);
            diffs += k.differences;
        }
        n.differences = diffs;
        if (n.left == null) {
            n.status = Status.RIGHT_ONLY;
            n.differences = Math.max(1, diffs);
        } else if (n.right == null) {
            n.status = Status.LEFT_ONLY;
            n.differences = Math.max(1, diffs);
        } else {
            n.status = diffs == 0 ? Status.SAME : Status.DIFFERENT;
        }
    }

    /** Paths in order: part by part, ignoring case. */
    static int comparePaths(String a, String b) {
        String[] x = a.split("/");
        String[] y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = x[i].compareToIgnoreCase(y[i]);
            if (c == 0) {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static boolean sameBytes(Side a, Side b, String path, BooleanSupplier cancelled) {
        try (InputStream x = a.open(path); InputStream y = b.open(path)) {
            byte[] bx = new byte[1 << 16];
            byte[] by = new byte[1 << 16];
            while (true) {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                int nx = x.readNBytes(bx, 0, bx.length);
                int ny = y.readNBytes(by, 0, by.length);
                if (nx != ny || !Arrays.equals(bx, 0, nx, by, 0, ny)) {
                    return false;
                }
                if (nx < bx.length) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false; // unreadable: counted as different
        }
    }

    /** Every node below {@code n} (depth first), for tests and searching. */
    public static List<Node> all(Node n) {
        List<Node> out = new ArrayList<>();
        for (Node k : n.kids) {
            out.add(k);
            out.addAll(all(k));
        }
        return out;
    }
}
