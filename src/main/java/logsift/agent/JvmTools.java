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
 * A look into a running JVM: a thread dump and a heap histogram, each only after the person says
 * yes, and as a short peek (the whole dump or histogram is kept as a log to read if needed). Never
 * a heap dump; a histogram never runs a full GC and is refused on a big heap.
 */
public final class JvmTools {
    private JvmTools() {}

    /** A heap above this is too big to count objects in for an agent: the JVM pauses while it counts. */
    public static final long HEAP_LIMIT = 8L << 30;

    public static List<Tool> all() {
        return List.of(LIST_JVMS, THREAD_DUMP, HEAP_HISTOGRAM);
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
                    + "JMX port), and those running on this computer. Gives handles (jvm:1) for thread_dump and heap_histogram.",
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
                b.append("thread_dump or heap_histogram with jvm=<handle>: each asks the person first.");
                return b.toString();
            });

    static final Tool THREAD_DUMP = new Def("thread_dump", "Take a thread dump",
            "Takes a thread dump of a running JVM (after the person says yes) and says what its threads do: states, "
                    + "deadlocks, threads blocked and on what, the biggest groups doing the same. The whole dump becomes a "
                    + "log to read or analyze_threads.",
            new Tool.Schema().string("jvm", "a handle from list_jvms, a service or a program", true).build(),
            (a, w) -> {
                JvmReach.Place p = w.jvms().find(a.string("jvm"));
                w.approve("take a thread dump of " + p.label(), "jvm:dump:" + p.id(),
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
            "Counts a running JVM's objects by class (after the person says yes), without a full GC: what takes the "
                    + "memory. Refused on a heap over 8 GB. Never a heap dump.",
            new Tool.Schema().string("jvm", "a handle from list_jvms, a service or a program", true)
                    .integer("top", "how many classes (default 20, at most 50)", false).build(),
            (a, w) -> {
                JvmReach.Place p = w.jvms().find(a.string("jvm"));
                int top = (int) a.number("top", 20, 1, 50);
                w.approve("count the objects on the heap of " + p.label(), "jvm:histogram:" + p.id(),
                        "The JVM pauses while it counts (about a second per GB of heap; refused over 8 GB). No full GC.");
                JvmReach.Target t = w.jvms().connect(p);
                String info;
                try {
                    info = t.diagnostic("GC.heap_info");
                } catch (java.io.IOException e) {
                    info = "";
                }
                long used = HeapHistogram.heapUsed(info);
                if (used > HEAP_LIMIT) {
                    throw new Tool.Failure("The heap of " + t.label() + " is " + gb(used) + " in use: counting its "
                            + "objects would pause it too long. The person can take a histogram in Logsift's Heap tab.");
                }
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
