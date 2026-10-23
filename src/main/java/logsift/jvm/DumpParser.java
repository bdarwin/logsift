package logsift.jvm;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import logsift.core.Json;

/**
 * Reads thread dumps: {@code jstack}, {@code jcmd Thread.print} and {@code kill -3} output (also
 * in the middle of a log, even with a time in front of each line), and the plain and JSON files
 * of {@code jcmd Thread.dump_to_file}. Several dumps in one file (taken some seconds apart) are
 * read one after the other.
 *
 * <p>Feed lines with {@link #line} and call {@link #finish}, or use {@link #parse(String)}.
 */
public final class DumpParser {

    private static final String START = "Full thread dump";
    private static final Pattern HEADER = Pattern.compile(
            "^\"(.*)\"((?: #\\d+| \\[\\d+\\]| daemon| virtual| prio=| os_prio=| cpu=| tid=| nid=).*)$");
    private static final Pattern ID = Pattern.compile("(?:^| )#(\\d+)");
    private static final Pattern PRIO = Pattern.compile(" prio=(\\d+)");
    private static final Pattern CPU = Pattern.compile(" cpu=([\\d.]+)ms");
    private static final Pattern ELAPSED = Pattern.compile(" elapsed=([\\d.]+)s");
    private static final Pattern NID = Pattern.compile(" nid=(\\S+)");
    private static final Pattern PLAIN_HEADER = Pattern.compile(
            "^#(\\d+) \"(.*)\"( virtual)?(?: ([A-Z_]+))?(?: \\S+)?$");
    private static final Pattern NOTE = Pattern.compile(
            "^- (locked|waiting to lock|waiting on|parking to wait for|waiting to re-lock in wait\\(\\)|eliminated)"
                    + "\\s+<([^>]*)>(?:\\s*\\(a ([^)]+)\\))?");
    private static final Pattern OWNED = Pattern.compile("^- <([^>]+)>(?:\\s*\\(a ([^)]+)\\))?");
    private static final Pattern LOCAL_TIME = Pattern.compile("^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d$");
    private static final Pattern ISO_TIME = Pattern.compile("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?Z$");
    private static final Pattern VERSION = Pattern.compile("^\\d+(?:\\.\\d+)*(?:[+-]\\S*)?$");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private enum Mode { NONE, HOTSPOT, PLAIN }

    private final List<ThreadDump> out = new ArrayList<>();
    private Mode mode = Mode.NONE;
    private boolean closed;            // past the threads (JNI refs, deadlock report)
    private long dumpTime = ThreadDump.NO_TIME;
    private long dumpLine = -1;
    private String vm = "";
    private final List<JThread> threads = new ArrayList<>();
    private Builder cur;
    private Pattern prefix;            // a log's time in front of each line, cut off
    private long timeLine = Long.MIN_VALUE / 2;
    private long time = ThreadDump.NO_TIME;
    private String version = "";
    private long versionLine = Long.MIN_VALUE / 2;

    /** All dumps in {@code text} (thread dump text or a JSON thread dump). */
    public static List<ThreadDump> parse(String text) {
        if (!text.contains(START) && PyDumps.looksLikeDump(text.length() > 4000 ? text.substring(0, 4000) : text)) {
            return PyDumps.parse(text);
        }
        String t = text.stripLeading();
        if (t.startsWith("{")) {
            List<ThreadDump> json = parseJson(t);
            if (!json.isEmpty()) {
                return json;
            }
        }
        DumpParser p = new DumpParser();
        long no = 0;
        int from = 0;
        int n = text.length();
        while (from <= n) {
            int nl = text.indexOf('\n', from);
            int end = nl < 0 ? n : nl;
            String line = text.substring(from, end > from && text.charAt(end - 1) == '\r' ? end - 1 : end);
            if (nl >= 0 || !line.isEmpty()) {
                p.line(no++, line);
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return p.finish();
    }

    /** True if the text looks like (the start of) a thread dump file. */
    public static boolean looksLikeDump(String head) {
        if (head.contains(START) || PyDumps.looksLikeDump(head)) {
            return true;
        }
        String t = head.stripLeading();
        if (t.startsWith("{") && t.contains("\"threadDump\"")) {
            return true;
        }
        // jcmd Thread.dump_to_file: pid, time, version, then "#1 "main""
        String[] lines = head.split("\r?\n", 12);
        int threadsSeen = 0;
        boolean timeSeen = false;
        for (String l : lines) {
            if (ISO_TIME.matcher(l).matches()) {
                timeSeen = true;
            } else if (timeSeen && PLAIN_HEADER.matcher(l).matches()) {
                threadsSeen++;
            }
        }
        return threadsSeen > 0;
    }

    /**
     * The thread dumps in an open log (read line by line, in the background), for example
     * {@code kill -3} output in an application's console log.
     */
    public static List<ThreadDump> scan(logsift.core.LogDocument doc, java.util.function.DoubleConsumer progress,
                                        java.util.function.BooleanSupplier cancelled) {
        long n = doc.lineCount();
        String[] buf = new String[4096];
        if (n > 0 && n < 5_000_000 && doc.fileSize() < 256L * 1024 * 1024) {
            String first = doc.line(0);
            if (first != null && first.stripLeading().startsWith("{")) {
                StringBuilder sb = new StringBuilder();
                for (long i = 0; i < n && !cancelled.getAsBoolean(); i += buf.length) {
                    int c = (int) Math.min(buf.length, n - i);
                    doc.readLines(i, c, buf);
                    for (int j = 0; j < c; j++) {
                        sb.append(buf[j] == null ? "" : buf[j]).append('\n');
                    }
                }
                List<ThreadDump> json = parseJson(sb.toString());
                if (!json.isEmpty()) {
                    return json;
                }
            }
        }
        DumpParser p = new DumpParser();
        PyDumps py = new PyDumps();
        for (long i = 0; i < n && !cancelled.getAsBoolean(); i += buf.length) {
            int c = (int) Math.min(buf.length, n - i);
            doc.readLines(i, c, buf);
            for (int j = 0; j < c; j++) {
                if (buf[j] != null) {
                    p.line(i + j, buf[j]);
                    py.line(i + j, buf[j]);
                }
            }
            if (progress != null) {
                progress.accept((double) (i + c) / n);
            }
        }
        List<ThreadDump> jvm = p.finish();
        List<ThreadDump> python = py.finish();
        if (python.isEmpty()) {
            return jvm;
        }
        List<ThreadDump> all = new ArrayList<>(jvm);
        all.addAll(python);
        all.sort(java.util.Comparator.comparingLong(ThreadDump::line));
        return List.copyOf(all);
    }

    /** True if a line starts a thread dump (cheap; for scanning a log). */
    public static boolean startsDump(String line) {
        return line.contains(START) || PyDumps.startsDump(line);
    }

    /** One line of text; {@code no} is its line number (0-based). */
    public void line(long no, String raw) {
        String s = raw;
        if (prefix != null) {
            Matcher m = prefix.matcher(s);
            if (m.lookingAt()) {
                s = s.substring(m.end());
            }
        }
        int fi = s.indexOf(START);
        if (fi >= 0) {
            endDump();
            int rawAt = raw.indexOf(START);
            prefix = rawAt > 0 ? shape(raw.substring(0, rawAt)) : null;
            mode = Mode.HOTSPOT;
            closed = false;
            String v = s.substring(fi + START.length()).trim();
            vm = v.endsWith(":") ? v.substring(0, v.length() - 1) : v;
            boolean timed = timeLine >= no - 3;
            dumpTime = timed ? time : ThreadDump.NO_TIME;
            dumpLine = timed ? timeLine : no;
            return;
        }
        int len = s.length();
        if (len >= 19 && len <= 32 && s.charAt(4) == '-') {
            if (LOCAL_TIME.matcher(s).matches()) {
                time = parseLocal(s);
                timeLine = no;
                if (mode == Mode.PLAIN) {
                    endDump();
                }
                return;
            }
            if (ISO_TIME.matcher(s).matches()) {
                time = parseIso(s);
                timeLine = no;
                if (mode == Mode.PLAIN) {
                    endDump();
                }
                return;
            }
        }
        if (mode == Mode.NONE) {
            if (timeLine >= no - 2 && VERSION.matcher(s).matches()) {
                version = s;
                versionLine = no;
                return;
            }
            if (!s.isEmpty() && s.charAt(0) == '#' && timeLine >= no - 6) {
                Matcher m = PLAIN_HEADER.matcher(s);
                if (m.matches()) {
                    mode = Mode.PLAIN;
                    dumpTime = time;
                    dumpLine = Math.max(0, timeLine - 1);
                    vm = versionLine > timeLine ? "Java " + version : "";
                    cur = plainThread(m);
                }
            }
            return;
        }
        if (mode == Mode.HOTSPOT) {
            hotspot(s);
        } else {
            plain(s);
        }
    }

    /** The dumps read. */
    public List<ThreadDump> finish() {
        endDump();
        return List.copyOf(out);
    }

    private void hotspot(String s) {
        if (closed) {
            return;
        }
        if (s.startsWith("JNI global") || (s.startsWith("Found ") && s.contains("deadlock"))
                || s.equals("Heap")) {
            endThread();
            closed = true;
            return;
        }
        if (s.startsWith("\"")) {
            Matcher m = HEADER.matcher(s);
            endThread();
            if (m.matches()) {
                cur = header(m.group(1), m.group(2));
            }
            return;
        }
        if (cur == null) {
            return;
        }
        String t = s.strip();
        if (t.startsWith("java.lang.Thread.State:")) {
            String st = t.substring("java.lang.Thread.State:".length()).trim();
            int paren = st.indexOf(" (");
            cur.state = JThread.State.parse(paren < 0 ? st : st.substring(0, paren));
            cur.detail = paren < 0 ? "" : st.substring(paren + 2, st.endsWith(")") ? st.length() - 1 : st.length());
        } else if (t.startsWith("at ")) {
            cur.frame(t.substring(3));
        } else if (t.startsWith("- ")) {
            if (cur.inOwned) {
                Matcher m = OWNED.matcher(t);
                if (m.find()) {
                    cur.owned.add(lock(m.group(1), m.group(2)));
                }
            } else {
                cur.note(t);
            }
        } else if (t.equals("Locked ownable synchronizers:")) {
            cur.inOwned = true;
        }
    }

    private void plain(String s) {
        if (s.isEmpty()) {
            return;
        }
        if (s.charAt(0) == '#') {
            Matcher m = PLAIN_HEADER.matcher(s);
            if (m.matches()) {
                endThread();
                cur = plainThread(m);
                return;
            }
        }
        if (Character.isWhitespace(s.charAt(0))) {
            if (cur == null) {
                return;
            }
            String t = s.strip();
            if (t.startsWith("- ")) {
                cur.note(t);
            } else if (!t.isEmpty()) {
                cur.frame(t.startsWith("at ") ? t.substring(3) : t);
            }
            return;
        }
        endDump(); // something else follows the dump
    }

    private Builder plainThread(Matcher m) {
        Builder b = new Builder();
        b.id = Long.parseLong(m.group(1));
        b.name = m.group(2);
        b.virtual = m.group(3) != null;
        b.state = m.group(4) == null ? JThread.State.UNKNOWN : JThread.State.parse(m.group(4));
        return b;
    }

    private static Builder header(String name, String rest) {
        Builder b = new Builder();
        b.name = name;
        Matcher m = ID.matcher(rest);
        if (m.find()) {
            b.id = Long.parseLong(m.group(1));
        }
        int prio = rest.indexOf(" prio=");
        b.daemon = rest.contains(" daemon") && (prio < 0 || rest.indexOf(" daemon") < prio);
        b.virtual = rest.contains(" virtual") && (prio < 0 || rest.indexOf(" virtual") < prio);
        m = PRIO.matcher(rest);
        if (m.find()) {
            b.priority = Integer.parseInt(m.group(1));
        }
        m = CPU.matcher(rest);
        if (m.find()) {
            b.cpu = parseDouble(m.group(1));
        }
        m = ELAPSED.matcher(rest);
        if (m.find()) {
            b.elapsed = parseDouble(m.group(1)) * 1000;
        }
        m = NID.matcher(rest);
        if (m.find()) {
            b.nid = m.group(1).equals("-") ? "" : m.group(1);
            String after = rest.substring(m.end()).trim();
            // JVM threads have no State line: "runnable", "waiting on condition"
            b.state = after.startsWith("runnable") ? JThread.State.RUNNABLE
                    : after.isEmpty() ? JThread.State.UNKNOWN : JThread.State.WAITING;
            int br = after.indexOf(" [");
            b.detail = "";
            b.headerDetail = br < 0 ? after : after.substring(0, br).trim();
        }
        return b;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void endThread() {
        if (cur != null) {
            threads.add(cur.build());
            cur = null;
        }
    }

    private void endDump() {
        endThread();
        if (mode != Mode.NONE && !threads.isEmpty()) {
            out.add(new ThreadDump(dumpTime, vm, threads, dumpLine));
        }
        threads.clear();
        mode = Mode.NONE;
        closed = false;
        vm = "";
    }

    /** A pattern for text shaped like {@code p}: numbers may change, the rest stays. */
    static Pattern shape(String p) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (Character.isDigit(c)) {
                while (i < p.length() && Character.isDigit(p.charAt(i))) {
                    i++;
                }
                sb.append("\\d+");
            } else {
                int j = i;
                while (j < p.length() && !Character.isDigit(p.charAt(j))) {
                    j++;
                }
                sb.append(Pattern.quote(p.substring(i, j)));
                i = j;
            }
        }
        return Pattern.compile(sb.toString());
    }

    private static long parseLocal(String s) {
        try {
            return LocalDateTime.parse(s, LOCAL).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return ThreadDump.NO_TIME;
        }
    }

    private static long parseIso(Object s) {
        if (!(s instanceof String str)) {
            return ThreadDump.NO_TIME;
        }
        try {
            return Instant.parse(str).toEpochMilli();
        } catch (DateTimeParseException e) {
            return ThreadDump.NO_TIME;
        }
    }

    /** A lock as written: {@code 0x00000007155ed9b8} with its type, or {@code java.lang.Object@2cf4f768}. */
    static JThread.Lock lock(String id, String type) {
        if (id == null || id.startsWith("no object") || id.startsWith("owner is")) {
            return null;
        }
        if ((type == null || type.isEmpty()) && id.indexOf('@') > 0) {
            int at = id.lastIndexOf('@');
            return new JThread.Lock(id.substring(at + 1), id.substring(0, at));
        }
        return new JThread.Lock(id, type == null ? "" : type);
    }

    private static JThread.NoteKind noteKind(String s) {
        return switch (s) {
            case "locked" -> JThread.NoteKind.LOCKED;
            case "waiting to lock" -> JThread.NoteKind.WAITING_TO_LOCK;
            case "waiting on" -> JThread.NoteKind.WAITING_ON;
            case "parking to wait for" -> JThread.NoteKind.PARKING;
            case "eliminated" -> JThread.NoteKind.ELIMINATED;
            default -> JThread.NoteKind.WAITING_TO_RELOCK;
        };
    }

    /** {@code java.base@21/java.lang.Thread.sleep(Thread.java:509)} and similar, as a frame. */
    static JThread.Frame frame(String text, List<JThread.Note> notes) {
        String module = "";
        String t = text;
        int paren = t.indexOf('(');
        String method;
        String loc = "";
        if (paren > 0 && t.endsWith(")")) {
            method = t.substring(0, paren);
            loc = t.substring(paren + 1, t.length() - 1);
            int slash = loc.lastIndexOf('/');
            if (slash > 0) {
                module = loc.substring(0, slash);
                loc = loc.substring(slash + 1);
            }
        } else {
            method = t;
        }
        // jcmd Thread.dump_to_file writes the module first: java.base/java.lang.Thread.sleep
        int slash = method.indexOf('/');
        if (slash > 0 && method.lastIndexOf('$', slash) < 0 && !method.startsWith("0x", slash + 1)) {
            // not a hidden class like Fixture$$Lambda/0x0000000800c01440.run
            module = method.substring(0, slash);
            method = method.substring(slash + 1);
        }
        return new JThread.Frame(method, loc, module, notes);
    }

    /** A thread being read. */
    private static final class Builder {
        String name = "";
        long id = -1;
        String nid = "";
        boolean daemon;
        boolean virtual;
        int priority = -1;
        JThread.State state = JThread.State.UNKNOWN;
        String detail = "";
        String headerDetail = "";
        final List<String> frameText = new ArrayList<>();
        final List<List<JThread.Note>> notes = new ArrayList<>();
        final List<JThread.Lock> owned = new ArrayList<>();
        double cpu = -1;
        double elapsed = -1;
        boolean inOwned;

        void frame(String text) {
            frameText.add(text);
            notes.add(new ArrayList<>());
        }

        void note(String t) {
            Matcher m = NOTE.matcher(t);
            if (m.find() && !notes.isEmpty()) {
                notes.get(notes.size() - 1).add(new JThread.Note(noteKind(m.group(1)), lock(m.group(2), m.group(3))));
            }
        }

        JThread build() {
            List<JThread.Frame> frames = new ArrayList<>(frameText.size());
            for (int i = 0; i < frameText.size(); i++) {
                frames.add(DumpParser.frame(frameText.get(i), notes.get(i)));
            }
            boolean virt = virtual || (!frames.isEmpty()
                    && frames.get(frames.size() - 1).method().equals("java.lang.VirtualThread.run"));
            String d = detail.isEmpty() && frames.isEmpty() ? headerDetail : detail;
            return new JThread(name, id, nid, daemon, virt, priority, state, d, frames, owned, cpu, elapsed);
        }
    }

    // ---------------------------------------------------------------- JSON

    /** A JSON thread dump ({@code jcmd <pid> Thread.dump_to_file -format=json}); empty if not one. */
    @SuppressWarnings("unchecked")
    static List<ThreadDump> parseJson(String text) {
        Object v = Json.parseValue(text, null);
        if (!(v instanceof Map<?, ?> root) || !(root.get("threadDump") instanceof Map<?, ?> td)) {
            return List.of();
        }
        long t = parseIso(td.get("time"));
        Object ver = td.get("runtimeVersion");
        String vmName = ver instanceof String s ? "Java " + s : "";
        List<JThread> all = new ArrayList<>();
        if (td.get("threadContainers") instanceof List<?> containers) {
            for (Object c : containers) {
                if (!(c instanceof Map<?, ?> cm) || !(cm.get("threads") instanceof List<?> ts)) {
                    continue;
                }
                for (Object o : ts) {
                    if (o instanceof Map<?, ?> tm) {
                        all.add(jsonThread((Map<String, Object>) tm));
                    }
                }
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        return List.of(new ThreadDump(t, vmName, all, 0));
    }

    private static JThread jsonThread(Map<String, Object> m) {
        String name = str(m.get("name"));
        long id = -1;
        try {
            id = Long.parseLong(str(m.get("tid")));
        } catch (NumberFormatException e) {
            // no id
        }
        List<Object> stack = m.get("stack") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        List<List<JThread.Note>> notes = new ArrayList<>();
        for (int i = 0; i < stack.size(); i++) {
            notes.add(new ArrayList<>());
        }
        if (!stack.isEmpty()) {
            JThread.Lock blocked = lockOf(m.get("blockedOn"));
            if (blocked != null) {
                notes.get(0).add(new JThread.Note(JThread.NoteKind.WAITING_TO_LOCK, blocked));
            }
            JThread.Lock waiting = lockOf(m.get("waitingOn"));
            if (waiting != null) {
                notes.get(0).add(new JThread.Note(JThread.NoteKind.WAITING_ON, waiting));
            }
            if (m.get("parkBlocker") instanceof Map<?, ?> pb) {
                JThread.Lock parked = lockOf(pb.get("object"));
                if (parked != null) {
                    notes.get(0).add(new JThread.Note(JThread.NoteKind.PARKING, parked));
                }
            }
            if (m.get("monitorsOwned") instanceof List<?> owned) {
                for (Object o : owned) {
                    if (o instanceof Map<?, ?> om && om.get("locks") instanceof List<?> locks) {
                        int depth = intOf(om.get("depth"));
                        if (depth >= 0 && depth < notes.size()) {
                            for (Object lk : locks) {
                                JThread.Lock l = lockOf(lk);
                                if (l != null) {
                                    notes.get(depth).add(new JThread.Note(JThread.NoteKind.LOCKED, l));
                                }
                            }
                        }
                    }
                }
            }
        }
        List<JThread.Frame> frames = new ArrayList<>();
        for (int i = 0; i < stack.size(); i++) {
            frames.add(frame(str(stack.get(i)), notes.get(i)));
        }
        boolean virtual = Boolean.TRUE.equals(m.get("virtual"))
                || (!frames.isEmpty() && frames.get(frames.size() - 1).method().equals("java.lang.VirtualThread.run"));
        return new JThread(name, id, "", false, virtual, -1, JThread.State.parse(str(m.get("state"))), "",
                frames, List.of(), -1, -1);
    }

    private static JThread.Lock lockOf(Object o) {
        return o instanceof String s && !s.isEmpty() ? lock(s, null) : null;
    }

    private static int intOf(Object o) {
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o instanceof Json.Num n ? n.text() : String.valueOf(o);
    }
}
