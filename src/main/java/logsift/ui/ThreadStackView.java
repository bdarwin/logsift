package logsift.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JEditorPane;
import javax.swing.event.HyperlinkEvent;
import logsift.jvm.JThread;
import logsift.jvm.ThreadAnalysis;
import logsift.jvm.ThreadDump;

/**
 * One thread's stack, readable: its state, the lock it waits for and who holds it, the locks it
 * holds and who waits for them (as links to those threads), then the frames with the
 * application's own frames in full color and the JDK's dimmed.
 */
final class ThreadStackView extends JEditorPane {
    private final Consumer<String> showThread;
    private ThreadDump dump;
    private JThread thread;

    /** {@code showThread} is called with a thread's key when its link is clicked. */
    ThreadStackView(Consumer<String> showThread) {
        super("text/html", "");
        this.showThread = showThread;
        setEditable(false);
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null
                    && e.getDescription().startsWith("thread:")) {
                showThread.accept(e.getDescription().substring("thread:".length()));
            }
        });
        clear("Click a thread to see its stack.");
    }

    JThread thread() {
        return thread;
    }

    void clear(String message) {
        dump = null;
        thread = null;
        setText("<html><body style='margin:8px'><span style='color:" + hex(Theme.gutterForeground()) + "'>"
                + esc(message) + "</span></body></html>");
    }

    void show(ThreadDump d, JThread t) {
        dump = d;
        thread = t;
        if (t == null) {
            clear("Click a thread to see its stack.");
            return;
        }
        StringBuilder sb = new StringBuilder("<html><body style='margin:6px'>");
        sb.append("<div><b>").append(esc(t.displayName())).append("</b>&nbsp;&nbsp;<b style='color:")
                .append(hex(stateColor(t.state()))).append("'>").append(t.state().label()).append("</b>");
        if (!t.stateDetail().isEmpty()) {
            sb.append(" (").append(esc(t.stateDetail())).append(')');
        }
        List<String> facts = new ArrayList<>();
        if (t.virtual()) {
            facts.add("virtual");
        }
        if (t.daemon()) {
            facts.add("daemon");
        }
        if (t.id() >= 0) {
            facts.add("#" + t.id());
        }
        if (t.cpuMs() >= 0) {
            facts.add("CPU " + cpu(t.cpuMs()));
        }
        if (t.elapsedMs() >= 0) {
            facts.add("running " + ThreadAnalysis.duration((long) t.elapsedMs()));
        }
        if (t.isIdle()) {
            facts.add("idle");
        }
        if (!facts.isEmpty()) {
            sb.append("<span style='color:").append(hex(Theme.gutterForeground())).append("'>&nbsp;&nbsp;· ")
                    .append(String.join(" · ", facts)).append("</span>");
        }
        sb.append("</div>");

        JThread.Lock wants = t.blockedOn();
        if (wants != null) {
            JThread holder = d.holderOf(wants);
            sb.append("<div style='margin-top:4px'>Waits for ").append(lock(wants));
            if (holder != null && holder != t) {
                sb.append(" held by ").append(link(holder)).append(" (")
                        .append(esc(ThreadAnalysis.doing(holder))).append(')');
            } else if (t.state() == JThread.State.BLOCKED) {
                sb.append(" (no thread in this dump holds it)");
            }
            sb.append("</div>");
        }
        JThread.Lock waitObj = t.waitingOn();
        if (waitObj != null && wants == null) {
            sb.append("<div style='margin-top:4px'>Waits to be notified on ").append(lock(waitObj)).append("</div>");
        }
        for (JThread.Lock l : t.held()) {
            List<JThread> waiters = new ArrayList<>();
            for (JThread o : d.threads()) {
                if (o != t && l.equals(o.blockedOn())) {
                    waiters.add(o);
                }
            }
            sb.append("<div style='margin-top:4px'>Holds ").append(lock(l));
            if (!waiters.isEmpty()) {
                sb.append(" — <b style='color:").append(hex(stateColor(JThread.State.BLOCKED))).append("'>")
                        .append(waiters.size()).append(waiters.size() == 1 ? " thread waits" : " threads wait")
                        .append("</b> for it: ");
                for (int i = 0; i < Math.min(8, waiters.size()); i++) {
                    sb.append(i == 0 ? "" : ", ").append(link(waiters.get(i)));
                }
                if (waiters.size() > 8) {
                    sb.append(", …");
                }
            }
            sb.append("</div>");
        }

        sb.append("<pre style='font-family:").append(esc(Theme.monoFont(12).getFamily()))
                .append("; margin-top:8px'>");
        if (t.frames().isEmpty()) {
            sb.append("<span style='color:").append(hex(Theme.gutterForeground()))
                    .append("'>(no Java stack: a thread of the JVM itself)</span>");
        }
        String dim = hex(Theme.gutterForeground());
        for (JThread.Frame f : t.frames()) {
            String color = f.isJdk() ? dim : hex(Theme.foreground());
            sb.append("<span style='color:").append(color).append("'>at ").append(esc(f.method()));
            if (!f.location().isEmpty()) {
                sb.append('(').append(esc(f.location())).append(')');
            }
            sb.append("</span>\n");
            for (JThread.Note n : f.notes()) {
                Color c = switch (n.kind()) {
                    case WAITING_TO_LOCK, WAITING_TO_RELOCK -> stateColor(JThread.State.BLOCKED);
                    case PARKING, WAITING_ON -> new Color(0xD97706);
                    default -> Theme.marker();
                };
                sb.append("    <span style='color:").append(hex(c)).append("'>- ").append(n.kind().text).append(' ')
                        .append(n.lock() == null ? "(unknown object)" : esc(n.lock().toString())).append("</span>\n");
            }
        }
        sb.append("</pre></body></html>");
        setText(sb.toString());
        setCaretPosition(0);
    }

    private static String lock(JThread.Lock l) {
        return "<b>" + esc(l.shortType()) + "</b> <span style='color:" + hex(Theme.gutterForeground()) + "'>&lt;"
                + esc(l.id()) + "&gt;</span>";
    }

    private static String link(JThread t) {
        return "<a href='thread:" + esc(t.key()).replace("'", "&#39;") + "'>" + esc(t.displayName()) + "</a>";
    }

    static String cpu(double ms) {
        if (ms < 1000) {
            return String.format(Locale.ROOT, "%.0f ms", ms);
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1000);
        }
        return ThreadAnalysis.duration((long) ms);
    }

    /** Green running, red blocked, amber/gray waiting. */
    static Color stateColor(JThread.State s) {
        boolean dark = Theme.isDark();
        return switch (s) {
            case RUNNABLE -> dark ? new Color(0x4ADE80) : new Color(0x16A34A);
            case BLOCKED -> dark ? new Color(0xF87171) : new Color(0xDC2626);
            case WAITING -> dark ? new Color(0x94A3B8) : new Color(0x64748B);
            case TIMED_WAITING -> dark ? new Color(0x7DD3FC) : new Color(0x0284C7);
            default -> dark ? new Color(0x71717A) : new Color(0xA1A1AA);
        };
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
