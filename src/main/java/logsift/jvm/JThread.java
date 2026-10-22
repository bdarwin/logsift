package logsift.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One thread in a thread dump: its name, state, stack, and the locks it holds or waits for.
 *
 * @param id        the Java thread id ({@code #12}), or -1 when the dump doesn't say
 * @param nid       the OS thread id as written ({@code 0x5f03}, {@code 26371}), or ""
 * @param cpuMs     CPU time used so far in ms ({@code cpu=}), or -1
 * @param elapsedMs time since the thread started in ms ({@code elapsed=}), or -1
 * @param owned     ownable synchronizers it holds (ReentrantLock and the like)
 */
public record JThread(String name, long id, String nid, boolean daemon, boolean virtual, int priority,
                      State state, String stateDetail, List<Frame> frames, List<Lock> owned,
                      double cpuMs, double elapsedMs) {

    /** The states of {@link Thread.State}, and UNKNOWN for JVM threads that don't say. */
    public enum State {
        RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, NEW, TERMINATED, UNKNOWN;

        public static State parse(String s) {
            if (s == null) {
                return UNKNOWN;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }

        /** "Timed waiting" and so on. */
        public String label() {
            String s = name().replace('_', ' ').toLowerCase(Locale.ROOT);
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    /** An object used as a lock: its identity ({@code 0x00000007155ed9b8}) and class. */
    public record Lock(String id, String type) {
        /** The class without its package: {@code ReentrantLock$NonfairSync}. */
        public String shortType() {
            if (type == null || type.isEmpty()) {
                return "object";
            }
            return type.substring(type.lastIndexOf('.') + 1);
        }

        @Override
        public String toString() {
            return "<" + id + "> (a " + type + ")";
        }
    }

    /** What a stack frame says about a lock. */
    public enum NoteKind {
        LOCKED("locked"), WAITING_TO_LOCK("waiting to lock"), WAITING_ON("waiting on"),
        PARKING("parking to wait for"), WAITING_TO_RELOCK("waiting to re-lock in wait()"),
        ELIMINATED("eliminated");

        public final String text;

        NoteKind(String text) {
            this.text = text;
        }
    }

    public record Note(NoteKind kind, Lock lock) {}

    /**
     * A stack frame: {@code method} like {@code java.lang.Thread.sleep}, {@code location} like
     * {@code Thread.java:509} or {@code Native Method}, and the module ({@code java.base@21}) or "".
     */
    public record Frame(String method, String location, String module, List<Note> notes) {
        public Frame {
            notes = List.copyOf(notes);
        }

        /** {@code java.lang.Thread.sleep(Thread.java:509)} */
        public String text() {
            return location.isEmpty() ? method : method + "(" + location + ")";
        }

        /** {@code Thread.sleep} */
        public String shortMethod() {
            int dot = method.lastIndexOf('.');
            int cls = dot > 0 ? method.lastIndexOf('.', dot - 1) : -1;
            return method.substring(cls + 1);
        }

        /** A frame of the JDK itself (java.*, jdk.*, sun.*) rather than of the application. */
        public boolean isJdk() {
            return method.startsWith("java.") || method.startsWith("javax.") || method.startsWith("jdk.")
                    || method.startsWith("sun.") || method.startsWith("com.sun.");
        }
    }

    public JThread {
        frames = List.copyOf(frames);
        owned = List.copyOf(owned);
        name = name == null ? "" : name;
        nid = nid == null ? "" : nid;
        stateDetail = stateDetail == null ? "" : stateDetail;
    }

    /** The lock it waits to get: a monitor ({@code synchronized}) or a parked-on lock, or null. */
    public Lock blockedOn() {
        for (Frame f : frames) {
            for (Note n : f.notes()) {
                if (n.lock() != null && (n.kind() == NoteKind.WAITING_TO_LOCK || n.kind() == NoteKind.PARKING
                        || n.kind() == NoteKind.WAITING_TO_RELOCK)) {
                    return n.lock();
                }
            }
            if (!f.notes().isEmpty()) {
                return null; // only the top frames with notes say what it waits for
            }
        }
        return null;
    }

    /** The object it waits on with {@code Object.wait()} (the lock is let go meanwhile), or null. */
    public Lock waitingOn() {
        for (Frame f : frames) {
            for (Note n : f.notes()) {
                if (n.kind() == NoteKind.WAITING_ON && n.lock() != null) {
                    return n.lock();
                }
            }
        }
        return null;
    }

    /** Locks it holds: monitors it entered (minus one it waits on) and ownable synchronizers. */
    public List<Lock> held() {
        Lock waiting = waitingOn();
        List<Lock> out = new ArrayList<>();
        for (Frame f : frames) {
            for (Note n : f.notes()) {
                if (n.kind() == NoteKind.LOCKED && n.lock() != null && !n.lock().equals(waiting)
                        && !out.contains(n.lock())) {
                    out.add(n.lock());
                }
            }
        }
        for (Lock l : owned) {
            if (!out.contains(l)) {
                out.add(l);
            }
        }
        return out;
    }

    /** The first frame, or null for threads without a Java stack. */
    public Frame top() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    /** The first frame of the application's own code (not the JDK's), or the top frame. */
    public Frame appFrame() {
        for (Frame f : frames) {
            if (!f.isJdk()) {
                return f;
            }
        }
        return top();
    }

    /** The same for the same thread in another dump: the Java id, else name and OS id. */
    public String key() {
        return id >= 0 ? "#" + id : name + "|" + nid;
    }

    /** A JVM thread without a Java stack (GC, compiler, VM threads). */
    public boolean isJvmInternal() {
        return frames.isEmpty() && (id < 0 || name.contains("Compiler") || name.equals("Signal Dispatcher")
                || name.equals("Attach Listener") || name.equals("Service Thread")
                || name.equals("Notification Thread") || name.startsWith("C1 ") || name.startsWith("C2 "));
    }

    /** Text that is the same for threads doing the same thing (state and every frame). */
    public String stackKey() {
        StringBuilder sb = new StringBuilder(state.name());
        for (Frame f : frames) {
            sb.append('\n').append(f.method()).append('(').append(f.location()).append(')');
            for (Note n : f.notes()) {
                // the same kind of lock, not the same object
                sb.append(' ').append(n.kind()).append(n.lock() == null ? "" : n.lock().type());
            }
        }
        return sb.toString();
    }

    /** The name shown: virtual threads often have none. */
    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return virtual ? "virtual #" + id : "#" + id;
    }

    /**
     * The pool (or family) a thread belongs to, from its name: the last number becomes ∗, as
     * in {@code http-nio-8080-exec-∗}, {@code pool-3-thread-∗}, {@code GC Thread#∗}.
     */
    public static String family(String name) {
        if (name == null || name.isEmpty()) {
            return "(unnamed)";
        }
        int end = name.length();
        // a closing part like "GC task thread#0 (ParallelGC)" keeps its tail
        int i = end - 1;
        while (i >= 0 && !Character.isDigit(name.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return name;
        }
        int j = i;
        while (j >= 0 && Character.isDigit(name.charAt(j))) {
            j--;
        }
        String tail = name.substring(i + 1);
        if (tail.length() > 0 && tail.chars().anyMatch(Character::isLetter) && !tail.startsWith(" (")) {
            return name; // digits in the middle of a word ("ip6tables", "http2Handler") aren't a counter
        }
        return name.substring(0, j + 1) + "∗" + tail;
    }

    /** A thread that waits for work (an idle pool thread, a selector, a sleeping timer). */
    public boolean isIdle() {
        if (frames.isEmpty()) {
            return state != State.BLOCKED;
        }
        if (state == State.BLOCKED) {
            return false;
        }
        for (JThread.Frame f : frames) {
            // the thread taking this very dump (over JMX or the attach mechanism)
            if (f.method().endsWith("DiagnosticCommandImpl.executeDiagnosticCommand")
                    || f.method().endsWith("ThreadImpl.dumpThreads0") || f.method().startsWith("jdk.internal.vm.ThreadDumper")) {
                return true;
            }
        }
        for (int k = 0; k < Math.min(frames.size(), 14); k++) {
            String m = frames.get(k).method();
            if (IDLE.stream().anyMatch(m::endsWith)) {
                return true;
            }
        }
        if (state == State.RUNNABLE) {
            return false;
        }
        Frame t = top();
        return t != null && (t.method().endsWith("Object.wait") || t.method().endsWith("Object.wait0"))
                && frames.stream().limit(6).anyMatch(f -> f.method().contains("Reference")
                || f.method().contains("Timer") || f.method().contains("CleanerImpl"));
    }

    /** Frames where a thread is waiting for work rather than doing it. */
    private static final List<String> IDLE = List.of(
            "ThreadPoolExecutor.getTask", "ForkJoinPool.awaitWork", "ForkJoinPool.runWorker",
            "LinkedBlockingQueue.take", "LinkedBlockingQueue.poll", "ArrayBlockingQueue.take",
            "ArrayBlockingQueue.poll", "SynchronousQueue.take", "SynchronousQueue.poll",
            "LinkedTransferQueue.take", "LinkedTransferQueue.poll", "DelayedWorkQueue.take",
            "DelayedWorkQueue.poll", "PriorityBlockingQueue.take", "DelayQueue.take",
            "EPoll.wait", "EPoll.epollWait", "EPollArrayWrapper.epollWait", "KQueue.poll",
            "KQueue.kevent0", "KQueueArrayWrapper.kevent0", "WEPoll.wait", "WindowsSelectorImpl$SubSelector.poll0",
            "Net.accept", "PlainSocketImpl.socketAccept", "ServerSocket.accept", "ServerSocketChannelImpl.accept",
            "Net.poll", "Reference.waitForReferencePendingList", "TimerThread.mainLoop",
            "CleanerImpl.run", "ReferenceQueue.remove", "Selector.select", "SelectorImpl.select",
            "FileInputStream.readBytes", "FileInputStream.read0", "ConsoleInputStream.read");
}
