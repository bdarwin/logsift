package logsift.ui;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The unexpected errors of this session, for the status bar's "⚠ 2 errors" and the errors
 * window. The same error again (same kind, message and place) is counted once. No Swing here.
 */
final class SessionErrors {

    /** One error: when, a line about it, and the details (a stack trace and versions; no log contents). */
    record Entry(LocalDateTime time, String summary, String details, boolean outOfMemory) {}

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private int total; // every distinct error this session, also cleared ones

    /**
     * Adds an error; returns false if the same one was already added this session (not counted
     * again).
     */
    boolean add(Throwable e, String details, LocalDateTime time) {
        synchronized (this) {
            if (!seen.add(signature(e))) {
                return false;
            }
            entries.add(new Entry(time, summary(e), details, e instanceof OutOfMemoryError));
            total++;
        }
        listeners.forEach(Runnable::run);
        return true;
    }

    /** The errors shown (since the last Clear), oldest first. */
    synchronized List<Entry> list() {
        return List.copyOf(entries);
    }

    /** How many are shown. */
    synchronized int count() {
        return entries.size();
    }

    /** How many different errors this session had, also those cleared. */
    synchronized int total() {
        return total;
    }

    /** Hides the errors until the next one (they stay in errors.log). */
    void clear() {
        synchronized (this) {
            entries.clear();
        }
        listeners.forEach(Runnable::run);
    }

    /** Forgets everything (a new session; for tests). */
    void reset() {
        synchronized (this) {
            entries.clear();
            seen.clear();
            total = 0;
        }
        listeners.forEach(Runnable::run);
    }

    /** Runs (on the calling thread) after every change. */
    void addListener(Runnable r) {
        listeners.add(r);
    }

    /** "NullPointerException: x is null (in LogTab.java:120)". */
    static String summary(Throwable e) {
        String m = e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\s+", " ");
        if (m.length() > 120) {
            m = m.substring(0, 119) + "…";
        }
        StackTraceElement[] st = e.getStackTrace();
        String where = st.length == 0 || st[0].getFileName() == null ? ""
                : " (in " + st[0].getFileName() + ":" + st[0].getLineNumber() + ")";
        return e.getClass().getSimpleName() + m + where;
    }

    /** The same error: its kind, message and the first lines of its stack. */
    static String signature(Throwable e) {
        StringBuilder sb = new StringBuilder(e.getClass().getName()).append('|').append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        for (int i = 0; i < Math.min(3, st.length); i++) {
            sb.append('|').append(st[i]);
        }
        return sb.toString();
    }

    /** "1 error", "3 errors". */
    static String countText(int n) {
        return n + (n == 1 ? " error" : " errors");
    }

    /** Whether an error pops up a window: the first of the session, or running out of memory. */
    static boolean popsUp(Throwable e, int totalBefore) {
        return totalBefore == 0 || e instanceof OutOfMemoryError;
    }

    /** For the list: the errors' lines, newest first. */
    synchronized List<String> summariesNewestFirst() {
        List<String> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            out.add(entries.get(i).summary());
        }
        return out;
    }
}
