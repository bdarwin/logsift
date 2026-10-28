package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Restoring a session: files finish opening in any order, and the tab that was selected when
 * the session was saved ends up selected. (The window itself is checked in VisualTest.)
 */
class SessionRestoreTest {
    private static final Path A = Path.of("/logs/a.log");
    private static final Path B = Path.of("/logs/b.log");
    private static final Path SLOW = Path.of("/logs/archive.zip/older.log");

    /** The window's tabs, as MainFrame adds and selects them. */
    private final List<Path> tabs = new ArrayList<>();
    private final SessionRestore restore = new SessionRestore();
    private Path selected;

    private void opened(Path p) {
        tabs.add(p);
        if (restore.takesSelection(p) || selected == null) { // a first tab is always selected
            selected = p;
        }
        restore.finished(p);
    }

    @Test
    void aFileThatOpensLateDoesNotTakeTheSelection() {
        restore.start(List.of(SLOW, A, B), 1);
        opened(A);
        opened(B);
        opened(SLOW); // unpacked in the background: it finishes last
        assertEquals(A, selected);
        assertTrue(restore.isDone());
    }

    @Test
    void theSelectedFileOpeningLastIsSelected() {
        restore.start(List.of(A, SLOW, B), 1);
        opened(A);
        opened(B);
        opened(SLOW);
        assertEquals(SLOW, selected);
    }

    @Test
    void everyOrderEndsOnTheSavedSelection() {
        List<Path> files = List.of(A, B, SLOW);
        for (int sel = 0; sel < files.size(); sel++) {
            for (List<Path> order : permutations(files)) {
                tabs.clear();
                selected = null;
                restore.start(files, sel);
                for (Path p : order) {
                    opened(p);
                }
                assertEquals(files.get(sel), selected, "selected " + sel + ", opened in order " + order);
                assertTrue(restore.isDone());
            }
        }
    }

    @Test
    void aFileOpenedByHandWhileRestoringIsSelected() {
        Path mine = Path.of("/logs/mine.log");
        restore.start(List.of(A, SLOW), 0);
        opened(A);
        opened(mine);
        assertEquals(mine, selected);
        opened(SLOW);
        assertEquals(mine, selected, "a session file finishing later leaves the user's choice");
    }

    @Test
    void failedFilesEndTheRestoreQuietly() {
        restore.start(List.of(A, SLOW), 1);
        assertTrue(restore.isRestoring(SLOW));
        opened(A);
        assertTrue(restore.finished(SLOW), "a session file: its error is not shown");
        assertTrue(restore.isDone());
        assertFalse(restore.finished(SLOW), "after that, errors for it are shown again");
        assertTrue(restore.takesSelection(SLOW), "opened by hand later, it is selected");
    }

    @Test
    void anEmptyOrOutOfRangeSession() {
        restore.start(List.of(), 0);
        assertTrue(restore.isDone());
        restore.start(List.of(A, B), 5);
        assertFalse(restore.takesSelection(A));
        assertFalse(restore.takesSelection(B));
        opened(A);
        opened(B);
        assertEquals(A, selected, "the first tab, as with no saved selection");
    }

    private static List<List<Path>> permutations(List<Path> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<Path>> out = new ArrayList<>();
        for (Path first : items) {
            List<Path> rest = new ArrayList<>(items);
            rest.remove(first);
            for (List<Path> p : permutations(rest)) {
                List<Path> l = new ArrayList<>();
                l.add(first);
                l.addAll(p);
                out.add(l);
            }
        }
        return out;
    }
}
