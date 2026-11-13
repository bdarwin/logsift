package logsift.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import logsift.heap.HeapDump;
import logsift.heap.HeapFindings;

/**
 * Heap dump files (.hprof) for agents, explained as the Heap Dump tab explains them: what fills
 * the memory and what keeps it alive, memory spent for nothing, the biggest classes. Only the
 * explanation goes back, never the objects' values (strings in the waste are cut short and masked
 * like any answer). heap_dump takes one, as the person allows.
 */
public final class HeapTools {
    private HeapTools() {}

    public static List<Tool> all() {
        return List.of(ANALYZE_HEAP_DUMP);
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

    static final Tool ANALYZE_HEAP_DUMP = new Def("analyze_heap_dump", "Explain a heap dump",
            "Explains a Java heap dump file (.hprof): what fills the memory, in sentences (\"one HashMap in static "
                    + "SessionCache.CACHE holds 1.2 GB: mostly 20,000 sessions\") with the chain of references that keeps "
                    + "it alive, memory that isn't strongly reachable, memory spent on copies of the same strings and "
                    + "empty collections, and the classes with the most memory. A big dump takes a while to read: call "
                    + "again when told. heap_dump takes one of a running JVM (always asked); heap_histogram is quicker.",
            new Tool.Schema().string("path", "The heap dump's path (.hprof).", true)
                    .integer("classes", "How many of the biggest classes to list (default 12).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                Path file = w.check(Workspace.path(a.string("path")), "read the heap dump");
                if (!Files.isRegularFile(file)) {
                    throw new Tool.Failure("No such file: " + file + ". Give a .hprof file.");
                }
                int classes = (int) a.number("classes", 12, 1, 100);
                String text;
                try {
                    text = w.await("heap:" + file + ":" + Files.getLastModifiedTime(file).toMillis() + ":" + classes,
                            "reading the heap dump " + file.getFileName(), () -> explain(file, classes));
                } catch (java.io.IOException e) {
                    throw new Tool.Failure("Could not read " + file.getFileName() + " as a heap dump: " + e.getMessage());
                }
                return w.finish(text, (int) a.number("max_chars", Workspace.DEFAULT_MAX_CHARS, 500,
                        Workspace.HARD_MAX_CHARS));
            });

    /** Reads the dump, explains it and closes it again (its index goes to a temporary folder when it is big). */
    static String explain(Path file, int classLimit) throws Exception {
        long size = Files.size(file);
        Path work = null;
        if (HeapDump.needsDisk(size)) {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
            String room = HeapDump.roomProblem(tmp, size);
            if (room != null) {
                throw new java.io.IOException(room);
            }
            work = Files.createTempDirectory(tmp, "logsift-heap-");
        }
        try (HeapDump d = HeapDump.open(file, work, null, () -> Thread.currentThread().isInterrupted())) {
            return text(d, classLimit);
        } finally {
            if (work != null) {
                deleteAll(work);
            }
        }
    }

    static String text(HeapDump d, int classLimit) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append(d.fileName()).append(": ").append(HeapFindings.bytes(d.fileBytes())).append(" file");
        if (d.timestamp() > 0) {
            b.append(", taken ").append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                    .format(java.time.Instant.ofEpochMilli(d.timestamp()).atZone(java.time.ZoneId.systemDefault())));
        }
        b.append(", ").append(String.format(Locale.ROOT, "%,d", d.objectCount())).append(" objects, ")
                .append(HeapFindings.bytes(d.reachableBytes())).append(" in use (strongly reachable).\n");
        List<HeapFindings.Finding> findings = new ArrayList<>(d.findings());
        HeapFindings.Finding waste = HeapFindings.waste(d.waste(null, () -> Thread.currentThread().isInterrupted()),
                d.reachableBytes());
        if (waste != null) {
            findings.add(waste);
        }
        b.append("\nWhat fills the heap:\n");
        if (findings.isEmpty()) {
            b.append("Nothing holds a large share of it: the memory is spread over many objects.\n");
        }
        int n = 0;
        for (HeapFindings.Finding f : findings) {
            b.append(++n).append(". ").append(f.title()).append('\n');
            if (!f.detail().isEmpty()) {
                b.append("   ").append(f.detail()).append('\n');
            }
            if (f.kind() == HeapFindings.Kind.HOLDER && f.node() >= 0) {
                b.append("   Kept alive ").append(HeapFindings.where(d, f.node())).append(". To free it, that reference "
                        + "(usually the collection that keeps adding) has to let go.\n");
            }
        }
        List<HeapDump.ClassStat> all = new ArrayList<>(d.classes());
        all.sort(Comparator.comparingLong(HeapDump.ClassStat::shallow).reversed());
        b.append("\nThe classes with the most memory (the objects themselves):\n");
        for (int i = 0; i < Math.min(classLimit, all.size()); i++) {
            HeapDump.ClassStat c = all.get(i);
            b.append("- ").append(c.name()).append(": ").append(HeapFindings.bytes(c.shallow())).append(" in ")
                    .append(String.format(Locale.ROOT, "%,d", c.count())).append(" objects\n");
        }
        return b.toString().stripTrailing();
    }

    private static void deleteAll(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException e) {
            // a temporary folder: the system cleans it up too
        }
    }
}
