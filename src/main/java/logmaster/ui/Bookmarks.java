package logmaster.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Bookmarked lines of one file, each with an optional note. Saved per file under
 * {@code ~/.logmaster/bookmarks}, and dropped when the file was truncated (line numbers would
 * point at other lines).
 */
final class Bookmarks {
    private final Path file;
    private final TreeMap<Long, String> marks = new TreeMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    Bookmarks(Path file) {
        this.file = file;
        load();
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    boolean contains(long line) {
        return marks.containsKey(line);
    }

    int size() {
        return marks.size();
    }

    NavigableMap<Long, String> all() {
        return java.util.Collections.unmodifiableNavigableMap(marks);
    }

    /** Adds or removes a bookmark; returns true if the line is now bookmarked. */
    boolean toggle(long line) {
        boolean on;
        if (marks.containsKey(line)) {
            marks.remove(line);
            on = false;
        } else {
            marks.put(line, "");
            on = true;
        }
        changed();
        return on;
    }

    void set(long line, String note) {
        marks.put(line, note == null ? "" : note);
        changed();
    }

    void remove(long line) {
        if (marks.remove(line) != null) {
            changed();
        }
    }

    void clear() {
        if (!marks.isEmpty()) {
            marks.clear();
            changed();
        }
    }

    /** The next bookmark after {@code line} (wrapping), or -1. */
    long next(long line, boolean forward) {
        if (marks.isEmpty()) {
            return -1;
        }
        Long l = forward ? marks.higherKey(line) : marks.lowerKey(line);
        if (l == null) {
            l = forward ? marks.firstKey() : marks.lastKey();
        }
        return l;
    }

    private void changed() {
        save();
        listeners.forEach(Runnable::run);
    }

    // ---------------------------------------------------------------- storage

    private Path store() {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(
                    file.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            return Path.of(System.getProperty("user.home"), ".logmaster", "bookmarks",
                    HexFormat.of().formatHex(h, 0, 10) + ".properties");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void load() {
        Path p = store();
        if (!Files.exists(p)) {
            return;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
            long savedSize = Long.parseLong(props.getProperty("size", "0"));
            if (Files.size(file) < savedSize) {
                Files.deleteIfExists(p); // truncated or replaced: the lines moved
                return;
            }
            for (String k : props.stringPropertyNames()) {
                if (k.startsWith("line.")) {
                    marks.put(Long.parseLong(k.substring(5)), props.getProperty(k));
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: start without bookmarks
        }
    }

    private void save() {
        Path p = store();
        try {
            if (marks.isEmpty()) {
                Files.deleteIfExists(p);
                return;
            }
            Files.createDirectories(p.getParent());
            Properties props = new Properties();
            props.setProperty("file", file.toAbsolutePath().toString());
            props.setProperty("size", Long.toString(Files.size(file)));
            for (Map.Entry<Long, String> e : marks.entrySet()) {
                props.setProperty("line." + e.getKey(), e.getValue());
            }
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                props.store(w, "LogMaster bookmarks");
            }
        } catch (IOException e) {
            // not fatal: bookmarks just won't be remembered
        }
    }
}
