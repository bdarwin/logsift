package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** This session's errors: added, counted once each, cleared (no screen needed). */
class SessionErrorsTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 28, 10, 0);

    private static Throwable bug(String m) {
        Throwable t = new IllegalStateException(m);
        t.setStackTrace(new StackTraceElement[] {new StackTraceElement("logsift.ui.LogTab", "x", "LogTab.java", 120)});
        return t;
    }

    @Test
    void addCountAndClear() {
        SessionErrors s = new SessionErrors();
        AtomicInteger changes = new AtomicInteger();
        s.addListener(changes::incrementAndGet);
        assertEquals(0, s.count());
        assertTrue(s.add(bug("one"), "details 1", T));
        assertTrue(s.add(bug("two"), "details 2", T.plusSeconds(5)));
        assertEquals(2, s.count());
        assertEquals("IllegalStateException: one (in LogTab.java:120)", s.list().get(0).summary());
        assertEquals("details 2", s.list().get(1).details());
        // the same error again: not counted twice
        assertTrue(!s.add(bug("one"), "details 1 again", T.plusSeconds(9)));
        assertEquals(2, s.count());
        assertEquals(2, changes.get());
        // Clear hides them until the next one
        s.clear();
        assertEquals(0, s.count());
        assertEquals(2, s.total(), "the session still had them");
        assertTrue(s.add(bug("three"), "d3", T.plusSeconds(20)));
        assertEquals(1, s.count());
        assertTrue(!s.add(bug("two"), "d2", T), "cleared, but already reported this session");
        s.reset();
        assertEquals(0, s.total());
    }

    @Test
    void words() {
        assertEquals("1 error", SessionErrors.countText(1));
        assertEquals("3 errors", SessionErrors.countText(3));
        assertTrue(SessionErrors.popsUp(bug("first"), 0), "the first error of a session pops up");
        assertTrue(!SessionErrors.popsUp(bug("later"), 1), "later ones only count");
        assertTrue(SessionErrors.popsUp(new OutOfMemoryError("Java heap space"), 4), "out of memory always");
        Throwable noTrace = new NullPointerException();
        noTrace.setStackTrace(new StackTraceElement[0]);
        assertEquals("NullPointerException", SessionErrors.summary(noTrace));
        assertTrue(SessionErrors.summary(new IllegalStateException("x".repeat(500))).length() < 200);
    }
}
