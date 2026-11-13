package logsift.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import logsift.jvm.HeapHistogram;
import logsift.jvm.JThread;
import logsift.jvm.ThreadAnalysis;
import logsift.jvm.ThreadDump;

/**
 * A look into a running JVM: a thread dump, a heap histogram and a heap dump, as the person's
 * rules say ({@link JvmRules}: asked by default, a heap dump always), and as a short peek (the whole
 * dump or histogram is kept as a log to read if needed). A histogram never runs a full GC; a
 * histogram or heap dump is refused on a heap bigger than the person allows.
 */
public final class JvmTools {
    private JvmTools() {}

    public static List<Tool> all() {
        return List.of(LIST_JVMS, THREAD_DUMP, HEAP_HISTOGRAM, HEAP_DUMP);
    }

    private record Def(String name, String title, String description, Map<String, Object> inputSchema, Body body)
            implements Tool {
        @Override
        public String run(Args args, Workspace w) throws Exception {
            return body.run(args, w);
        }
    }

    @FunctionalInterface
    private interface Body {
        String run(Tool.Args a, Workspace w) throws Exception;
    }

    static final Tool LIST_JVMS = new Def("list_jvms", "Running JVMs",
            "The JVMs to look into: where the person said each service's JVM runs (this computer, a server over SSH, a "
                    + "JMX port), and those running on this computer. Gives handles (jvm:1) for thread_dump, heap_histogram and "
                    + "heap_dump.",
            new Tool.Schema().string("service", "only this service's JVMs", false).build(),
            (a, w) -> {
                List<JvmReach.Place> l = w.jvms().list(a.string("service", null));
                if (l.isEmpty()) {
                    return "No JVMs: none described for the services (Log Locations → its JVMs) and none running here.";
                }
                StringBuilder b = new StringBuilder();
                for (JvmReach.Place p : l) {
                    b.append(p.id()).append("  ").append(p.label()).append(" · ").append(p.from()).append('\n');
                }
                b.append("thread_dump, heap_histogram or heap_dump with jvm=<handle>, as the person allows: ")
                        .append(allows(w.rules())).append('.');
                return b.toString();
            });

    static final Tool THREAD_DUMP = new Def("thread_dump", "Take a thread dump",
            "Takes a thread dump of a running JVM (after the person says yes) and says what its threads do: states, "
                    + "deadlocks, threads blocked and on what, the biggest groups doing the same. The whole dump becomes a "
                    + "log to read or analyze_threads.",
            new Tool.Schema().string("jvm", "a handle from list_jvms, a service or a program", true).build(),
            (a, w) -> {
                JvmReach.Place p = w.jvms().find(a.string("jvm"));
                w.approve(JvmRules.Action.THREAD_DUMP, "take a thread dump of " + p.label(), "jvm:dump:" + p.id(),
                        "The JVM pauses for a moment while its threads are listed. Nothing is changed.");
                JvmReach.Target t = w.jvms().connect(p);
                ThreadDump d = t.dump();
                Path file = Files.createTempFile("logsift-threads-", ".txt");
                file.toFile().deleteOnExit();
                Files.writeString(file, logsift.jvm.DumpText.write(List.of(d)), StandardCharsets.UTF_8);
                String h = w.openGenerated(file);
                return w.finish(peek(t.label(), d) + "\nThe whole dump: " + h
                        + " (read_lines, search, or analyze_threads log=" + h + ").", 4_000);
            });

    /** A thread dump in a few lines: states, findings, the biggest groups. */
    static String peek(String label, ThreadDump d) {
        StringBuilder b = new StringBuilder("Thread dump of ").append(label).append(", ").append(d.threads().size())
                .append(" threads:");
        d.stateCounts().entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> b.append(' ').append(e.getKey()).append(' ').append(e.getValue()).append(','));
        b.setLength(b.length() - 1);
        b.append('\n');
        ThreadAnalysis.Report r = ThreadAnalysis.analyze(List.of(d));
        r.findings().stream().limit(6).forEach(f -> b.append("- ").append(f.severity()).append(": ").append(f.title())
                .append(f.detail().isEmpty() ? "" : " — " + cut(f.detail(), 200)).append('\n'));
        List<ThreadAnalysis.Group> same = ThreadAnalysis.sameStacks(d);
        same.stream().filter(g -> g.threads().size() > 1).limit(5).forEach(g -> {
            JThread t = g.threads().get(0);
            b.append("- ").append(g.threads().size()).append(" × ").append(t.state()).append(": ")
                    .append(cut(ThreadAnalysis.doing(t), 160)).append('\n');
        });
        return b.toString().stripTrailing();
    }

    static final Tool HEAP_HISTOGRAM = new Def("heap_histogram", "Count objects on the heap",
            "Counts a running JVM's objects by class (as the person allows: asked by default), without a full GC: what "
                    + "takes the memory. Refused on a heap bigger than the person allows (8 GB unless they changed it).",
            new Tool.Schema().string("jvm", "a handle from list_jvms, a service or a program", true)
                    .integer("top", "how many classes (default 20, at most 50)", false).build(),
            (a, w) -> {
                JvmReach.Place p = w.jvms().find(a.string("jvm"));
                int top = (int) a.number("top", 20, 1, 50);
                JvmRules rules = w.rules();
                if (rules.histogram() == JvmRules.Rule.NEVER) {
                    w.approve(JvmRules.Action.HISTOGRAM, "count the objects on the heap of " + p.label(), null, "");
                }
                JvmReach.Target t = w.jvms().connect(p);
                long used = heapUsed(t);
                if (used > rules.maxHeapBytes()) {
                    throw new Tool.Failure("The heap of " + t.label() + " is " + gb(used) + " in use, over the "
                            + rules.maxHeapGb() + " GB the person lets agents count (" + Workspace.JVM_SETTING
                            + "): counting would pause it too long. The person can take a histogram in Logsift's Heap tab.");
                }
                w.approve(JvmRules.Action.HISTOGRAM, "count the objects on the heap of " + p.label()
                        + (used > 0 ? " (" + gb(used) + " in use)" : ""), "jvm:histogram:" + p.id(),
                        "The JVM pauses while it counts, about a second per GB of heap. No full GC.");
                String text = t.diagnostic("GC.class_histogram", "-all");
                if (!text.contains("Total")) {
                    throw new Tool.Failure("This JVM can't count objects without a full GC; not done. ("
                            + text.lines().findFirst().orElse("") + ")");
                }
                HeapHistogram hh = HeapHistogram.parse(text, System.currentTimeMillis());
                Path file = Files.createTempFile("logsift-histogram-", ".txt");
                file.toFile().deleteOnExit();
                Files.writeString(file, text, StandardCharsets.UTF_8);
                String h = w.openGenerated(file);
                StringBuilder b = new StringBuilder("Heap of ").append(t.label()).append(": ")
                        .append(String.format("%,d", hh.instances())).append(" objects, ").append(gb(hh.bytes()))
                        .append(used > 0 ? " (" + gb(used) + " in use)" : "").append(". Largest by size:\n")
                        .append("| class | objects | size |\n|---|---|---|\n");
                hh.rows().stream().sorted((x, y) -> Long.compare(y.bytes(), x.bytes())).limit(top)
                        .forEach(r -> b.append("| ").append(HeapHistogram.readable(r.className())).append(" | ")
                                .append(String.format("%,d", r.instances())).append(" | ").append(mb(r.bytes())).append(" |\n"));
                b.append("All classes: ").append(h).append('.');
                return w.finish(b.toString(), 6_000);
            });

    static final Tool HEAP_DUMP = new Def("heap_dump", "Take a heap dump",
            "Takes a heap dump (.hprof) of a running JVM, always asking the person first (unless they turned heap dumps "
                    + "off): the live objects, after a full GC. The JVM pauses while it's written, several seconds per GB. "
                    + "Copied to this computer (from a server over SSH), then analyze_heap_dump explains it. Refused on a "
                    + "heap bigger than the person allows, or when the JVM's machine hasn't room for it. Prefer "
                    + "heap_histogram first: it's quicker and often enough.",
            new Tool.Schema().string("jvm", "a handle from list_jvms, a service or a program", true).build(),
            (a, w) -> {
                JvmReach.Place p = w.jvms().find(a.string("jvm"));
                JvmRules rules = w.rules();
                if (rules.heapDump() == JvmRules.Rule.NEVER) {
                    w.approve(JvmRules.Action.HEAP_DUMP, "take a heap dump of " + p.label(), null, "");
                }
                JvmReach.Target t = w.jvms().connect(p);
                long used = heapUsed(t);
                if (used > rules.maxHeapBytes()) {
                    throw new Tool.Failure("The heap of " + t.label() + " is " + gb(used) + " in use, over the "
                            + rules.maxHeapGb() + " GB the person lets agents dump (" + Workspace.JVM_SETTING
                            + "). The person can take one in Logsift's Heap tab.");
                }
                Path dir = logsift.core.CacheSettings.home().resolve("heap-dumps");
                Files.createDirectories(dir);
                Path file = dir.resolve(safe(p.program().isEmpty() ? "jvm" : p.program()) + "-"
                        + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".hprof");
                long free = dir.toFile().getUsableSpace();
                if (used > 0 && free > 0 && free < used + (256L << 20)) {
                    throw new Tool.Failure("Not enough room on this computer for the dump: " + gb(free) + " free in "
                            + dir + ", it needs about " + gb(used) + ".");
                }
                w.approve(JvmRules.Action.HEAP_DUMP, "take a heap dump of " + p.label()
                        + (used > 0 ? " (about " + gb(used) + ")" : ""), "jvm:heapdump:" + p.id(),
                        "The JVM runs a full GC, then pauses while the dump is written (several seconds per GB). "
                                + (p.kind() == logsift.jvm.JvmLink.Kind.LOCAL ? "Saved in " + dir + "."
                                : "Written on its machine, then copied to " + dir + "."));
                String note = t.heapDump(file);
                if (!Files.isRegularFile(file)) {
                    // written where the JVM runs, and left there
                    return note == null ? "The heap dump wasn't written." : note;
                }
                w.made(file);
                return "Heap dump of " + t.label() + ": " + file + " (" + gb(Files.size(file)) + ")."
                        + (note == null ? "" : " " + note)
                        + "\nanalyze_heap_dump path=" + file + " explains it. Tell the person where it is: it stays "
                        + "until they remove it.";
            });

    private static long heapUsed(JvmReach.Target t) {
        try {
            return HeapHistogram.heapUsed(t.diagnostic("GC.heap_info"));
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static String safe(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "jvm" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** "thread dumps asked, histograms allowed (heaps up to 8 GB), heap dumps never". */
    static String allows(JvmRules r) {
        return "thread dumps " + said(r.threadDump()) + ", histograms " + said(r.histogram()) + ", heap dumps "
                + said(r.heapDump()) + " (heaps up to " + r.maxHeapGb() + " GB)";
    }

    private static String said(JvmRules.Rule r) {
        return switch (r) {
            case ASK -> "asked each time";
            case ALLOW -> "allowed";
            case NEVER -> "never";
        };
    }

    static String gb(long bytes) {
        return bytes >= 1L << 30 ? String.format("%.1f GB", bytes / (double) (1L << 30)) : mb(bytes);
    }

    static String mb(long bytes) {
        return bytes >= 1 << 20 ? String.format("%.1f MB", bytes / (double) (1 << 20))
                : String.format("%,d KB", Math.max(1, bytes / 1024));
    }

    private static String cut(String s, int n) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= n ? t : t.substring(0, n - 1) + "…";
    }
}
