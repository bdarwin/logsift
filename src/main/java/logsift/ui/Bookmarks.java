package logsift.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Bookmarked lines of one file, each with an optional note. Saved per file under
 * {@code ~/.logsift/bookmarks}, and dropped when the file was truncated (line numbers would
 * point at other lines).
 */
final class Bookmarks {
    private final Path file;
    private final java.util.function.LongSupplier size;
    private final TreeMap<Long, String> marks = new TreeMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    /**
     * @param shownSize the size of what is shown: used when the file is not on disk as such (a
     *     file in a zip, shown from an unpacked copy)
     */
    Bookmarks(Path file, java.util.function.LongSupplier shownSize) {
        this.file = file;
        this.size = () -> {
            try {
                return Files.size(file);
            } catch (IOException e) {
                return shownSize.getAsLong();
            }
        };
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

    private void load() {
        marks.putAll(logsift.core.BookmarkStore.load(AppState.home(), file, size.getAsLong()));
    }

    private void save() {
        try {
            logsift.core.BookmarkStore.save(AppState.home(), file, size.getAsLong(), marks);
        } catch (IOException | RuntimeException e) {
            // not fatal: bookmarks just won't be remembered
        }
    }
}
