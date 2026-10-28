package logsift.ui;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The files of a session being restored, and which of them gets the selected tab. Files open in
 * the background and finish in any order (a file in a zip or .gz is unpacked first, a file on a
 * network drive can take seconds), so a file that finishes late must not take the selection away
 * from the tab that was selected when the session was saved.
 */
final class SessionRestore {
    private final Set<Path> pending = new HashSet<>();
    private Path selected;

    /** Starts restoring these files; {@code selectedIndex} is the one that was selected. */
    void start(List<Path> files, int selectedIndex) {
        pending.addAll(files);
        selected = selectedIndex >= 0 && selectedIndex < files.size() ? files.get(selectedIndex) : null;
    }

    /** Whether this file is still being reopened from the session. */
    boolean isRestoring(Path file) {
        return pending.contains(file);
    }

    /**
     * Whether the tab of this file, just opened (or found already open), becomes the selected
     * tab: any file the user opens does, of the session's files only the one that was selected.
     */
    boolean takesSelection(Path file) {
        return !pending.contains(file) || file.equals(selected);
    }

    /**
     * This file's tab is open, or opening it failed or was cancelled.
     *
     * @return whether it was one of the session's files
     */
    boolean finished(Path file) {
        boolean was = pending.remove(file);
        if (pending.isEmpty()) {
            selected = null;
        }
        return was;
    }

    /** Whether every file of the session has opened (or failed to). */
    boolean isDone() {
        return pending.isEmpty();
    }
}
