package logsift.ui;

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
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * Where Logsift keeps its settings and memory: preferences (window size, font, toggles,
 * recent files, last session) and per-file state under {@code ~/.logsift} (filters,
 * position, bookmarks). Tests point both elsewhere with the system properties
 * {@code logsift.prefs} and {@code logsift.home}, so they never touch the user's own.
 */
final class AppState {
    private AppState() {}

    private static final int MAX_RECENT = 15;

    static Preferences prefs() {
        return Preferences.userRoot().node(System.getProperty("logsift.prefs", "/logsift/ui"));
    }

    /** The folder for Logsift's files ({@code ~/.logsift}). */
    static Path home() {
        String h = System.getProperty("logsift.home");
        return h != null ? Path.of(h) : Path.of(System.getProperty("user.home"), ".logsift");
    }

    /** A short stable name for a file path (for per-file storage). */
    static String key(Path file) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(
                    file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- log formats

    private static volatile List<logsift.core.CustomFormat> formats;

    /** Where the user's log formats are saved (a file that can be copied to another computer). */
    static Path formatsFile() {
        return home().resolve("formats.properties");
    }

    /** The user's own log formats. */
    static List<logsift.core.CustomFormat> customFormats() {
        List<logsift.core.CustomFormat> f = formats;
        if (f == null) {
            f = List.copyOf(logsift.core.CustomFormat.load(formatsFile()));
            formats = f;
        }
        return f;
    }

    static void saveCustomFormats(List<logsift.core.CustomFormat> list) throws IOException {
        formats = List.copyOf(list);
        logsift.core.CustomFormat.save(formatsFile(), list);
    }

    // ---------------------------------------------------------------- recent files

    static List<Path> recentFiles() {
        List<Path> out = new ArrayList<>();
        for (String s : prefs().get("recent", "").split("\n")) {
            if (!s.isBlank()) {
                out.add(Path.of(s));
            }
        }
        return out;
    }

    static void addRecent(Path file) {
        List<Path> list = recentFiles();
        Path abs = file.toAbsolutePath().normalize();
        list.remove(abs);
        list.add(0, abs);
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        prefs().put("recent", String.join("\n", list.stream().map(Path::toString).toList()));
    }

    static void removeRecent(Path file) {
        List<Path> list = recentFiles();
        if (list.remove(file.toAbsolutePath().normalize())) {
            prefs().put("recent", String.join("\n", list.stream().map(Path::toString).toList()));
        }
    }

    static void clearRecent() {
        prefs().remove("recent");
    }

    // ---------------------------------------------------------------- session

    /** Files open when Logsift was closed, and which one was selected. */
    record Session(List<Path> files, int selected) {}

    static void saveSession(List<Path> files, int selected) {
        prefs().put("session", String.join("\n", files.stream().map(Path::toString).toList()));
        prefs().putInt("sessionSelected", selected);
    }

    static Session lastSession() {
        List<Path> files = new ArrayList<>();
        for (String s : prefs().get("session", "").split("\n")) {
            if (!s.isBlank()) {
                files.add(Path.of(s));
            }
        }
        return new Session(files, prefs().getInt("sessionSelected", 0));
    }

    // ---------------------------------------------------------------- per-file state

    private static Path stateFile(Path file) {
        return home().resolve("files").resolve(key(file) + ".properties");
    }

    /** What was remembered about a file (filters, position...), or null. */
    static Properties loadFileState(Path file) {
        Path p = stateFile(file);
        if (!Files.exists(p)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
            return props;
        } catch (IOException e) {
            return null;
        }
    }

    static void saveFileState(Path file, Properties props) {
        Path p = stateFile(file);
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                props.store(w, "Logsift: " + file.toAbsolutePath());
            }
        } catch (IOException e) {
            // not fatal: the file just won't be remembered
        }
    }
}
