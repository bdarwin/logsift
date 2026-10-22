package logsift.jvm;

import java.util.List;
import java.util.Locale;

/** Writes dumps back as text in the JVM's own layout ({@code jstack -l}), which reads back the same. */
public final class DumpText {
    private DumpText() {
    }

    /** All dumps, one after the other. */
    public static String write(List<ThreadDump> dumps) {
        StringBuilder sb = new StringBuilder();
        for (ThreadDump d : dumps) {
            write(d, sb);
            sb.append('\n');
        }
        return sb.toString();
    }

    public static void write(ThreadDump d, StringBuilder sb) {
        if (d.time() != ThreadDump.NO_TIME) {
            sb.append(d.dateTime()).append('\n');
        }
        sb.append("Full thread dump ").append(d.vm().isEmpty() ? "Java" : d.vm()).append(":\n\n");
        for (JThread t : d.threads()) {
            thread(t, sb);
            sb.append('\n');
        }
    }

    /** One thread: header, state, frames with their locks, ownable synchronizers. */
    public static String thread(JThread t) {
        StringBuilder sb = new StringBuilder();
        thread(t, sb);
        return sb.toString();
    }

    public static void thread(JThread t, StringBuilder sb) {
        sb.append('"').append(t.name()).append('"');
        if (t.id() >= 0) {
            sb.append(" #").append(t.id());
        }
        if (t.daemon()) {
            sb.append(" daemon");
        }
        if (t.virtual()) {
            sb.append(" virtual");
        }
        if (t.priority() >= 0) {
            sb.append(" prio=").append(t.priority());
        }
        if (t.cpuMs() >= 0) {
            sb.append(String.format(Locale.ROOT, " cpu=%.2fms", t.cpuMs()));
        }
        if (t.elapsedMs() >= 0) {
            sb.append(String.format(Locale.ROOT, " elapsed=%.2fs", t.elapsedMs() / 1000));
        }
        if (!t.nid().isEmpty()) {
            sb.append(" nid=").append(t.nid());
        } else if (t.id() < 0 && t.priority() < 0 && t.cpuMs() < 0 && !t.daemon() && !t.virtual()) {
            sb.append(" nid=-"); // the header needs one field to be read back
        }
        if (t.frames().isEmpty() && !t.stateDetail().isEmpty()) {
            sb.append(' ').append(t.stateDetail());
        }
        sb.append('\n');
        if (t.state() != JThread.State.UNKNOWN && !(t.frames().isEmpty() && t.id() < 0)) {
            sb.append("   java.lang.Thread.State: ").append(t.state().name());
            if (!t.stateDetail().isEmpty() && !t.frames().isEmpty()) {
                sb.append(" (").append(t.stateDetail()).append(')');
            }
            sb.append('\n');
        }
        for (JThread.Frame f : t.frames()) {
            sb.append("\tat ").append(f.method());
            if (!f.location().isEmpty() || !f.module().isEmpty()) {
                sb.append('(');
                if (!f.module().isEmpty()) {
                    sb.append(f.module()).append('/');
                }
                sb.append(f.location()).append(')');
            }
            sb.append('\n');
            for (JThread.Note n : f.notes()) {
                sb.append("\t- ").append(n.kind().text).append(' ');
                if (n.lock() == null) {
                    sb.append("<no object reference available>");
                } else {
                    sb.append(n.lock());
                }
                sb.append('\n');
            }
        }
        if (!t.owned().isEmpty()) {
            sb.append("\n   Locked ownable synchronizers:\n");
            for (JThread.Lock l : t.owned()) {
                sb.append("\t- ").append(l).append('\n');
            }
        }
    }
}
