package logsift.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * What a file browser shows, one level at a time: the folders and files in a folder, and what
 * is in a zip (its folders, files, and the zips inside it, which open like folders). A file in a
 * zip has a path like a file on disk ({@link Archives#pathOf}), so it opens and compares like
 * one.
 */
public final class FileBrowse {
    private FileBrowse() {}

    /** What an item is: a folder (on disk or in a zip), a zip (to look into), or a file. */
    public enum Kind { FOLDER, ZIP, FILE }

    /**
     * One item of a level.
     *
     * @param path the folder or file; for something in a zip, {@link Archives#pathOf} its name
     * @param size bytes (unpacked, for a file in a zip), or -1 (folders, or not known)
     * @param inZip whether it is in a zip (its children then come from that zip's {@link ZipTree})
     */
    public record Item(String name, Path path, Kind kind, long size, boolean inZip) {
        public boolean isFile() {
            return kind == Kind.FILE;
        }
    }

    /** Folders first, then zips and files, each by name (case doesn't count). */
    static final Comparator<Item> ORDER = Comparator.<Item>comparingInt(i -> i.kind() == Kind.FOLDER ? 0 : 1)
            .thenComparing(i -> i.name().toLowerCase(Locale.ROOT)).thenComparing(Item::name);

    /**
     * The folders and files in a folder on disk. Hidden ones (a dot first, or hidden on Windows)
     * are left out unless {@code hidden}; links are shown as what they point to.
     */
    public static List<Item> folder(Path dir, boolean hidden, BooleanSupplier cancelled) throws IOException {
        List<Item> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
                if (!hidden && isHidden(p, name)) {
                    continue;
                }
                BasicFileAttributes a;
                try {
                    a = Files.readAttributes(p, BasicFileAttributes.class);
                } catch (IOException e) {
                    try {
                        a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e2) {
                        continue; // gone, or not ours to look at
                    }
                }
                if (a.isDirectory()) {
                    out.add(new Item(name, p, Kind.FOLDER, -1, false));
                } else if (a.isRegularFile()) {
                    out.add(new Item(name, p, Archives.isZip(name) ? Kind.ZIP : Kind.FILE, a.size(), false));
                }
            }
        }
        out.sort(ORDER);
        return out;
    }

    private static boolean isHidden(Path p, String name) {
        if (name.startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(p);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Everything in a zip (zips inside it too, as {@link Archives#list} finds them), by level.
     * Read once; each level is then a lookup.
     */
    public static final class ZipTree {
        private final Path zip;
        private final Map<String, Map<String, Item>> levels = new HashMap<>();

        private ZipTree(Path zip) {
            this.zip = zip;
        }

        public static ZipTree read(Path zip, BooleanSupplier cancelled) throws IOException {
            ZipTree t = new ZipTree(zip);
            for (Archives.Entry e : Archives.list(zip, cancelled)) {
                t.add(e);
            }
            return t;
        }

        /** For tests: a tree from entries, without reading a zip. */
        static ZipTree of(Path zip, List<Archives.Entry> entries) {
            ZipTree t = new ZipTree(zip);
            entries.forEach(t::add);
            return t;
        }

        private void add(Archives.Entry e) {
            // a.zip!/logs/x.log: the levels "a.zip", "logs", "x.log"; folders are implied by the names
            String[] parts = e.name().replace(Archives.NESTED, "/").split("/");
            StringBuilder at = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) {
                    continue;
                }
                String parent = at.toString();
                if (!at.isEmpty()) {
                    at.append('/');
                }
                at.append(part);
                boolean last = i == parts.length - 1;
                Map<String, Item> level = levels.computeIfAbsent(parent, k -> new LinkedHashMap<>());
                Kind kind = last ? (Archives.isZip(part) ? Kind.ZIP : Kind.FILE)
                        : Archives.isZip(part) && isNestedZip(e.name(), at.toString()) ? Kind.ZIP : Kind.FOLDER;
                Item old = level.get(part);
                if (old == null || old.kind() == Kind.FILE && kind == Kind.ZIP) {
                    long size = last ? e.size() : old != null ? old.size() : -1;
                    level.put(part, new Item(part, zip.resolve(at.toString()), kind, size, true));
                }
            }
        }

        /** Whether {@code prefix} (levels joined by /) ends at a zip inside the zip, not a folder named like one. */
        private static boolean isNestedZip(String entryName, String prefix) {
            int n = 0;
            for (String level : entryName.split(java.util.regex.Pattern.quote(Archives.NESTED), -1)) {
                n += level.length();
                if (n == prefix.length()) {
                    return true;
                }
                n += 1; // the / that stands for !/
                if (n > prefix.length()) {
                    return false;
                }
            }
            return false;
        }

        /** The zip read. */
        public Path zip() {
            return zip;
        }

        /** What is at {@code inside} (a path in the zip, levels joined by /; "" the top). */
        public List<Item> children(String inside) {
            Map<String, Item> level = levels.get(inside);
            if (level == null) {
                return List.of();
            }
            List<Item> out = new ArrayList<>(level.values());
            out.sort(ORDER);
            return out;
        }

        /** What is at an item of this zip (a folder, or a zip inside it). */
        public List<Item> children(Item item) {
            return children(inside(item.path()));
        }

        /** The path in the zip (levels joined by /) of something in it. */
        public String inside(Path p) {
            Path rel = zip.relativize(p);
            StringBuilder b = new StringBuilder();
            for (Path part : rel) {
                if (!b.isEmpty()) {
                    b.append('/');
                }
                b.append(part);
            }
            return b.toString();
        }
    }
}
