package logsift.crash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A crash report as plain text: for agents, and for Copy Summary. */
public final class CrashText {
    private CrashText() {}

    /** The summary: what happened, where, findings, facts, the crashed thread's stack, the threads by state. */
    public static String summary(CrashReport r, int frames) {
        StringBuilder b = new StringBuilder();
        b.append(r.kind().label()).append(": ").append(r.title()).append('\n');
        if (r.where() != null) {
            b.append(cap(r.where())).append('\n');
        }
        if (r.summary() != null && !r.summary().isBlank()) {
            b.append('\n').append(r.summary()).append('\n');
        }
        if (!r.findings().isEmpty()) {
            b.append("\nFindings:\n");
            int i = 0;
            for (CrashReport.Finding f : r.findings()) {
                b.append(++i).append(". [").append(f.severity()).append("] ").append(f.title());
                if (f.detail() != null && !f.detail().isBlank()) {
                    b.append(" — ").append(f.detail());
                }
                b.append('\n');
                if (f.tryThis() != null && !f.tryThis().isBlank()) {
                    b.append("   Try: ").append(f.tryThis()).append('\n');
                }
            }
        }
        CrashReport.Thread c = r.crashedThread();
        if (c != null) {
            b.append(c.state().startsWith("crashed") ? "\nCrashed thread " : "\nThread where it stopped: ").append(c.id()).append(c.name() == null ? "" : " \"" + c.name() + "\"").append(":\n");
            stack(b, c, frames);
        }
        if (!r.facts().isEmpty()) {
            b.append("\nFacts:\n");
            for (CrashReport.Fact f : r.facts()) {
                b.append("- ").append(f.label()).append(": ").append(f.value()).append('\n');
            }
        }
        if (r.threads().size() > (c == null ? 0 : 1)) {
            b.append("\nThreads by what they were doing:\n");
            Map<String, Integer> states = new LinkedHashMap<>();
            for (CrashReport.Thread t : r.threads()) {
                if (!t.crashed()) {
                    states.merge(t.state(), 1, Integer::sum);
                }
            }
            states.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                    .forEach(e -> b.append("- ").append(e.getValue()).append(" × ").append(e.getKey()).append('\n'));
            b.append("(crash_threads lists them with their stacks.)\n");
        }
        return b.toString().stripTrailing();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static void stack(StringBuilder b, CrashReport.Thread t, int frames) {
        int i = 0;
        for (CrashReport.Frame f : t.frames()) {
            if (i >= frames) {
                b.append("   … ").append(t.frames().size() - frames).append(" more frames\n");
                break;
            }
            b.append(String.format(Locale.ROOT, "  %2d ", i++)).append(f.text());
            if (f.source() != null) {
                b.append("  [").append(f.source()).append(']');
            }
            if (f.guessed()) {
                b.append("  (guessed)");
            }
            if (f.kind() == CrashReport.FrameKind.OWN) {
                b.append("  ← own code");
            }
            b.append('\n');
        }
    }

    /** A group of threads with the same state and stack top. */
    public record Group(List<CrashReport.Thread> threads) {}

    /** Threads with the same state and first frames, the biggest group first; the crashed one alone, first. */
    public static List<Group> groups(CrashReport r, int depth) {
        Map<String, List<CrashReport.Thread>> m = new LinkedHashMap<>();
        for (CrashReport.Thread t : r.threads()) {
            String key = t.crashed() ? "\u0000crashed" : t.signature(depth);
            m.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        List<Group> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Group(v)));
        out.sort((x, y) -> {
            boolean cx = x.threads().get(0).crashed();
            boolean cy = y.threads().get(0).crashed();
            if (cx != cy) {
                return cx ? -1 : 1;
            }
            return y.threads().size() - x.threads().size();
        });
        return out;
    }

    /**
     * The threads, grouped by the same stack, with their frames; only those whose id, name, state
     * or frames contain filter (null: all).
     */
    public static String threads(CrashReport r, String filter, int frames, int limit) {
        StringBuilder b = new StringBuilder();
        String f = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
        List<Group> groups = groups(r, 6);
        int shown = 0;
        int matched = 0;
        for (Group g : groups) {
            List<CrashReport.Thread> ts = new ArrayList<>();
            for (CrashReport.Thread t : g.threads()) {
                if (f == null || matches(t, f)) {
                    ts.add(t);
                }
            }
            if (ts.isEmpty()) {
                continue;
            }
            matched++;
            if (shown >= limit) {
                continue;
            }
            shown++;
            CrashReport.Thread t = ts.get(0);
            b.append('\n');
            if (ts.size() == 1) {
                b.append("Thread ").append(t.id()).append(t.name() == null ? "" : " \"" + t.name() + "\"");
            } else {
                b.append(ts.size()).append(" threads: ");
                List<String> ids = new ArrayList<>();
                for (CrashReport.Thread x : ts) {
                    ids.add(x.name() == null ? x.id() : x.id() + " \"" + x.name() + "\"");
                    if (ids.size() == 12) {
                        ids.add("…");
                        break;
                    }
                }
                b.append(String.join(", ", ids));
            }
            b.append(" — ").append(t.state()).append(t.cpu() == null ? "" : ", CPU " + t.cpu()).append('\n');
            stack(b, t, frames);
        }
        String head = matched + (matched == 1 ? " group" : " groups") + " of threads with the same stack"
                + (f == null ? "" : " matching “" + filter + "”") + " (" + r.threads().size() + " threads in all)";
        if (matched > shown) {
            head += "; the first " + shown + " shown (raise limit)";
        }
        return (head + ":" + b).stripTrailing();
    }

    private static boolean matches(CrashReport.Thread t, String f) {
        if (t.id().toLowerCase(Locale.ROOT).contains(f) || t.name() != null && t.name().toLowerCase(Locale.ROOT).contains(f)
                || t.state() != null && t.state().toLowerCase(Locale.ROOT).contains(f)) {
            return true;
        }
        for (CrashReport.Frame fr : t.frames()) {
            if (fr.text().toLowerCase(Locale.ROOT).contains(f)) {
                return true;
            }
        }
        return false;
    }

    /** The modules, those worth a look first; only those whose name or path contains filter (null: all). */
    public static String modules(CrashReport r, String filter, int limit) {
        String f = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
        List<CrashReport.Module> ms = new ArrayList<>();
        for (CrashReport.Module m : r.modules()) {
            if (f == null || m.name().toLowerCase(Locale.ROOT).contains(f) || m.path().toLowerCase(Locale.ROOT).contains(f)) {
                ms.add(m);
            }
        }
        ms.sort((x, y) -> Boolean.compare(y.note().isEmpty() ? false : true, x.note().isEmpty() ? false : true));
        StringBuilder b = new StringBuilder();
        b.append(ms.size()).append(ms.size() == 1 ? " module" : " modules").append(f == null ? "" : " matching “" + filter + "”")
                .append(":\n");
        for (int i = 0; i < ms.size() && i < limit; i++) {
            CrashReport.Module m = ms.get(i);
            b.append("- ").append(m.name()).append(m.version().isEmpty() ? "" : " " + m.version());
            if (!m.note().isEmpty()) {
                b.append("  (").append(m.note()).append(')');
            }
            b.append("\n    ").append(m.path());
            if (!m.range().isEmpty()) {
                b.append("  ").append(m.range());
            }
            if (!m.symbols().isEmpty()) {
                b.append("  ").append(m.symbols());
            }
            b.append('\n');
        }
        if (ms.size() > limit) {
            b.append("… and ").append(ms.size() - limit).append(" more (raise limit, or filter).\n");
        }
        return b.toString().stripTrailing();
    }
}
