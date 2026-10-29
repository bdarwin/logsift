package logsift.crash;

import java.util.List;

/**
 * What a crash file says, in plain words: what happened and where, what to try, the threads
 * with their stacks, the modules, and facts about the process and the system. Made from a
 * Windows dump ({@link WindowsCrash}) or a Java hs_err file ({@link HsErr}); shown by the crash
 * tab and given to agents.
 *
 * @param kind      what the file is
 * @param title     what happened, one line ("Access violation: reading address 0x0, a null pointer")
 * @param where     where, one line ("in app!Order::total+0x1f (order.cpp:142)"); null if not known
 * @param summary   a few sentences for people: what it means
 * @param findings  what stands out, most important first, each with what to try
 * @param facts     the process and the system: label and value
 * @param threads   every thread, the crashed one first
 * @param modules   the loaded modules (libraries)
 * @param sections  more text worth reading, by name (the hs_err file's VM arguments, heap, events)
 */
public record CrashReport(Kind kind, String title, String where, String summary, List<Finding> findings,
                          List<Fact> facts, List<Thread> threads, List<Module> modules, List<Section> sections) {

    public enum Kind {
        WINDOWS_DUMP("Windows dump"), JAVA_CRASH("Java crash report");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Severity {
        ERROR, WARN, INFO
    }

    /** Something that stands out, with what to try (null if nothing in particular). */
    public record Finding(Severity severity, String title, String detail, String tryThis) {}

    public record Fact(String label, String value) {}

    /** What a frame is, for colouring and for finding the code to blame. */
    public enum FrameKind {
        /** The program's own code (or a library it brought). */
        OWN,
        /** Windows, the C runtime, the JVM: rarely where the bug is. */
        SYSTEM,
        /** Java code (interpreted or compiled). */
        JAVA,
        /** Code generated while the program ran, in no module (a JIT's). */
        GENERATED,
        /** Not known. */
        UNKNOWN
    }

    /**
     * One frame of a stack.
     *
     * @param text     "kernelbase!RaiseException+0x6c" or "com.acme.Order.total()V+12"
     * @param source   "order.cpp:142", or null
     * @param kind     what it is
     * @param guessed  found by scanning the stack: likely, not certain
     */
    public record Frame(String text, String source, FrameKind kind, boolean guessed) {}

    /**
     * @param id       the thread's id as shown ("8124", "0x1f03")
     * @param name     its name, or null
     * @param state    what it was doing, in words ("waiting for a lock", "running")
     * @param crashed  the thread the crash happened on
     * @param cpu      CPU time used, as text, or null
     */
    public record Thread(String id, String name, String state, boolean crashed, String cpu, List<Frame> frames) {
        /** The first frames' text, to group threads with the same stack. */
        public String signature(int depth) {
            StringBuilder b = new StringBuilder(state == null ? "" : state);
            int n = 0;
            for (Frame f : frames) {
                if (n >= depth) {
                    break;
                }
                if (!f.guessed()) { // guesses differ from thread to thread; the stack is the same
                    b.append('\n').append(f.text().replaceAll("\\+0x[0-9a-f]+$", ""));
                    n++;
                }
            }
            return b.toString();
        }
    }

    /**
     * @param name     "kernelbase.dll", "libjvm.so"
     * @param version  "10.0.19041.1466", or ""
     * @param path     where it was loaded from
     * @param range    its addresses, as text
     * @param symbols  what names Logsift has for it ("PDB", "exported names only"), or ""
     * @param note     why it is worth a look ("the crash is here", "unloaded"), or ""
     */
    public record Module(String name, String version, String path, String range, String symbols, String note) {}

    public record Section(String title, String text) {}

    /** The thread the crash happened on; null if none (a dump of a program that hung). */
    public Thread crashedThread() {
        for (Thread t : threads) {
            if (t.crashed()) {
                return t;
            }
        }
        return null;
    }
}
