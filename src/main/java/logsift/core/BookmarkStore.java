package logsift.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Where bookmarks are kept: a file per log under {@code bookmarks/} in Logsift's folder, with
 * the log's size when they were saved (a log that got smaller was truncated or replaced, so its
 * line numbers point at other lines and its bookmarks are dropped). Lines are 0-based. Shared by
 * the window and the agents' tools.
 */
public final class BookmarkStore {
    private BookmarkStore() {}

    /** A short stable name for a file path (for per-file storage). */
    public static String key(Path file) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(
                    file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The file that holds {@code log}'s bookmarks. */
    public static Path file(Path home, Path log) {
        return home.resolve("bookmarks").resolve(key(log) + ".properties");
    }

    /**
     * {@code log}'s bookmarks (line → note), none if there are none or the log is now smaller than
     * when they were saved (then their file is dropped).
     *
     * @param size the log's size now
     */
    public static TreeMap<Long, String> load(Path home, Path log, long size) {
        TreeMap<Long, String> marks = new TreeMap<>();
        Path p = file(home, log);
        if (!Files.exists(p)) {
            return marks;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
            long savedSize = Long.parseLong(props.getProperty("size", "0"));
            if (size < savedSize) {
                Files.deleteIfExists(p); // truncated or replaced: the lines moved
                return marks;
            }
            for (String k : props.stringPropertyNames()) {
                if (k.startsWith("line.")) {
                    marks.put(Long.parseLong(k.substring(5)), props.getProperty(k));
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: no bookmarks
        }
        return marks;
    }

    /** Saves {@code log}'s bookmarks (none: their file is dropped). */
    public static void save(Path home, Path log, long size, Map<Long, String> marks) throws IOException {
        Path p = file(home, log);
        if (marks.isEmpty()) {
            Files.deleteIfExists(p);
            return;
        }
        Files.createDirectories(p.getParent());
        Properties props = new Properties();
        props.setProperty("file", log.toAbsolutePath().toString());
        props.setProperty("size", Long.toString(size));
        for (Map.Entry<Long, String> e : marks.entrySet()) {
            props.setProperty("line." + e.getKey(), e.getValue());
        }
        try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            props.store(w, "Logsift bookmarks");
        }
    }

    /** A log with bookmarks, and how many. */
    public record Marked(Path log, int count) {}

    /** Every log with bookmarks kept, by path. */
    public static List<Marked> all(Path home) {
        List<Marked> out = new ArrayList<>();
        Path dir = home.resolve("bookmarks");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.properties")) {
            for (Path f : ds) {
                Properties props = new Properties();
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    props.load(r);
                } catch (IOException | RuntimeException e) {
                    continue;
                }
                String log = props.getProperty("file");
                if (log == null) {
                    continue;
                }
                int n = (int) props.stringPropertyNames().stream().filter(k -> k.startsWith("line.")).count();
                try {
                    out.add(new Marked(Path.of(log), n));
                } catch (RuntimeException e) {
                    // a path from another computer
                }
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(java.util.Comparator.comparing(m -> m.log().toString()));
        return out;
    }
}
