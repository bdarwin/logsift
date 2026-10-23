package logsift.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python thread dumps, read into the same threads as Java's so the Threads view works for them:
 * {@code py-spy dump} output (threads with names, idle or active, holding the GIL) and
 * {@code faulthandler} dumps ({@code faulthandler.dump_traceback}, or what Python writes when it
 * crashes or on {@code faulthandler.register(signal.SIGUSR1)}).
 *
 * <p>A py-spy thread that is idle is shown as waiting, an active one as runnable; frames are
 * innermost first, as in Java.
 */
public final class PyDumps {

    private static final Pattern PYSPY_PYTHON = Pattern.compile("^Python v(\\d+\\.\\d+\\S*)(?: \\((.*)\\))?\\s*$");
    private static final Pattern PYSPY_PROCESS = Pattern.compile("^Process (\\d+): (.*)$");
    private static final Pattern PYSPY_THREAD = Pattern.compile(
            "^Thread (0x[0-9A-Fa-f]+|\\d+) \\(([a-z+]+)\\)(?:: \"(.*)\")?\\s*$");
    private static final Pattern PYSPY_FRAME = Pattern.compile("^ {4}(\\S.*?) \\((.+?):(\\d+)\\)\\s*$");
    private static final Pattern FH_THREAD = Pattern.compile(
            "^(Current thread|Thread) (0x[0-9A-Fa-f]+)(?: \\[([^\\]]*)\\])? \\(most recent call first\\):\\s*$");
    private static final Pattern FH_FRAME = Pattern.compile("^\\s+File \"(.+)\", line (\\d+) in (\\S+)");

    private final List<ThreadDump> out = new ArrayList<>();
    private final List<JThread> threads = new ArrayList<>();
    private Pattern prefix;     // a log's time in front of each line, cut off
    private long dumpLine = -1;
    private String vm = "";
    private boolean faulthandler;
    private long lastLine = -1;
    private String process;     // py-spy's "Process 311: celery …" line, just before "Python v…"
    private long processLine = -1;
    // the thread being read
    private String name;
    private String nid;
    private JThread.State state;
    private String detail;
    private List<JThread.Frame> frames;

    /** True if a line starts a Python thread dump (cheap). */
    public static boolean startsDump(String line) {
        return line.contains("Python v") && PYSPY_PYTHON.matcher(cut(line)).matches()
                || line.contains("(most recent call first):") && FH_THREAD.matcher(cut(line)).matches();
    }

    /** True if the text looks like (the start of) a Python thread dump. */
    public static boolean looksLikeDump(String head) {
        for (String l : head.split("\r?\n", 20)) {
            if (startsDump(l)) {
                return true;
            }
        }
        return false;
    }

    /** All dumps in a text. */
    public static List<ThreadDump> parse(String text) {
        PyDumps p = new PyDumps();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            p.line(i, lines[i]);
        }
        return p.finish();
    }

    /** A line as written, from a log that puts a time in front (the part from the dump's words on). */
    private static String cut(String line) {
        int at = line.indexOf("Python v");
        if (at > 0 && line.startsWith("Python v", at) && PYSPY_PYTHON.matcher(line.substring(at)).matches()) {
            return line.substring(at);
        }
        for (String w : new String[] {"Current thread 0x", "Thread 0x"}) {
            int i = line.indexOf(w);
            if (i > 0 && line.endsWith("(most recent call first):")) {
                return line.substring(i);
            }
        }
        return line;
    }

    /** One line (0-based number). */
    public void line(long no, String raw) {
        String s = raw;
        if (prefix != null && !s.isEmpty()) {
            Matcher m = prefix.matcher(s);
            if (m.lookingAt()) {
                s = s.substring(m.end());
            }
        }
        if (startsDump(raw)) {
            String c = cut(raw);
            int at = raw.indexOf(c);
            Pattern p = at > 0 ? DumpParser.shape(raw.substring(0, at)) : null;
            Matcher py = PYSPY_PYTHON.matcher(c);
            if (py.matches()) {
                endDump();
                prefix = p;
                boolean withProcess = process != null && no - processLine <= 1;
                vm = "Python " + py.group(1) + (withProcess ? " · " + process : "");
                dumpLine = withProcess ? processLine : no;
                process = null;
                faulthandler = false;
                lastLine = no;
                return;
            }
            Matcher fh = FH_THREAD.matcher(c);
            if (fh.matches()) {
                if (!faulthandler || no - lastLine > 3) {
                    endDump();
                    faulthandler = true;
                    vm = "Python";
                    dumpLine = no;
                    prefix = p;
                }
                endThread();
                boolean current = fh.group(1).startsWith("Current");
                nid = fh.group(2);
                name = fh.group(3) != null ? fh.group(3) : (current ? "Current thread " : "Thread ") + nid;
                state = current ? JThread.State.RUNNABLE : JThread.State.UNKNOWN;
                detail = current ? "the thread that dumped (or crashed)" : "";
                frames = new ArrayList<>();
                lastLine = no;
                return;
            }
        }
        if (dumpLine < 0) {
            Matcher pr = PYSPY_PROCESS.matcher(s);
            if (pr.matches()) {
                process = pr.group(2);
                processLine = no;
            }
            return;
        }
        if (faulthandler) {
            Matcher f = FH_FRAME.matcher(s);
            if (f.find() && frames != null) {
                frames.add(new JThread.Frame(f.group(3), f.group(1) + ":" + f.group(2), "", List.of()));
                lastLine = no;
            } else if (!s.isBlank() && no - lastLine > 1) {
                endDump();
            }
            return;
        }
        Matcher pr = PYSPY_PROCESS.matcher(s);
        if (pr.matches()) { // the next dump's first line
            endDump();
            process = pr.group(2);
            processLine = no;
            return;
        }
        Matcher t = PYSPY_THREAD.matcher(s);
        if (t.matches()) {
            endThread();
            nid = t.group(1);
            name = t.group(3) != null ? t.group(3) : "Thread " + nid;
            String st = t.group(2);
            state = st.startsWith("active") ? JThread.State.RUNNABLE : JThread.State.WAITING;
            detail = st.contains("gil") ? "active, holds the GIL" : st.startsWith("active") ? "active" : "idle";
            frames = new ArrayList<>();
            lastLine = no;
            return;
        }
        Matcher f = PYSPY_FRAME.matcher(s);
        if (f.matches() && frames != null) {
            frames.add(new JThread.Frame(f.group(1), f.group(2) + ":" + f.group(3), "", List.of()));
            lastLine = no;
            return;
        }
        if (s.startsWith("        ") && frames != null) { // Arguments: / Locals: under a frame
            lastLine = no;
            return;
        }
        if (s.isBlank() && no - lastLine <= 1) {
            return;
        }
        if (!s.isBlank() && no - lastLine > 1 || !s.isBlank() && !s.startsWith(" ")) {
            endDump();
        }
    }

    private void endThread() {
        if (frames != null) {
            threads.add(new JThread(name, -1, nid == null ? "" : nid, false, false, -1, state, detail,
                    List.copyOf(frames), List.of(), -1, -1));
        }
        frames = null;
    }

    private void endDump() {
        endThread();
        if (!threads.isEmpty()) {
            out.add(new ThreadDump(ThreadDump.NO_TIME, vm, threads, dumpLine));
        }
        threads.clear();
        dumpLine = -1;
        prefix = null;
        faulthandler = false;
    }

    public List<ThreadDump> finish() {
        endDump();
        return List.copyOf(out);
    }
}
