package logsift.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import logsift.crash.CrashFiles;
import logsift.crash.CrashReport;
import logsift.crash.CrashText;

/**
 * Crash files for agents: Windows dumps (.dmp, .mdmp) and Java hs_err_pid files, explained as
 * the crash tab explains them. Only the explanation goes back: stacks, modules and facts, never
 * the process's memory.
 */
public final class CrashTools {
    private CrashTools() {}

    public static List<Tool> all() {
        return List.of(ANALYZE_CRASH, CRASH_THREADS, CRASH_MODULES);
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

    private static final String PATH_ARG = "The crash file's path: a Windows dump (.dmp, .mdmp) or a Java "
            + "hs_err_pid*.log.";

    private static int maxChars(Tool.Args a) throws Tool.Failure {
        return (int) a.number("max_chars", Workspace.DEFAULT_MAX_CHARS, 500, Workspace.HARD_MAX_CHARS);
    }

    /** The report of a file, worked out once per session (big dumps take a while). */
    private static CrashReport report(Tool.Args a, Workspace w) throws Exception {
        Path file = w.options().scope().check(Path.of(a.string("path")).toAbsolutePath().normalize());
        if (!Files.isRegularFile(file)) {
            throw new Tool.Failure("No such file: " + file);
        }
        if (CrashFiles.kindOf(file) == null) {
            throw new Tool.Failure("Not a crash file: " + CrashFiles.whyNot(file) + " Use open_log for logs.");
        }
        long stamp = Files.getLastModifiedTime(file).toMillis();
        Path home = w.options().logsiftHome();
        CrashFiles.Options o = home == null ? new CrashFiles.Options(logsift.crash.CrashSettings.DEFAULT, null, false)
                : CrashFiles.Options.of(home);
        return w.await("crash:" + file + ":" + stamp, "reading " + file.getFileName(),
                () -> CrashFiles.analyze(file, o, () -> Thread.currentThread().isInterrupted()));
    }

    static final Tool ANALYZE_CRASH = new Def("analyze_crash", "Explain a crash",
            "Explains a crash file: a Windows dump (full or mini, .dmp/.mdmp: the exception in plain words, the "
                    + "code to blame, call stacks rebuilt from the dump, every thread's state, memory, handles) or a "
                    + "Java hs_err_pid file (how the JVM crashed: native code, the JVM, JIT, GC, out of memory; the "
                    + "code to blame; which other threads and native libraries may have caused it; what to try). "
                    + "Start here for crashes.",
            new Tool.Schema().string("path", PATH_ARG, true)
                    .integer("frames", "Frames shown of the crashed thread (default 30).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                CrashReport r = report(a, w);
                int frames = (int) a.number("frames", 30, 1, 500);
                return w.finish(CrashText.summary(r, frames) + "\nNext: crash_threads for all stacks, crash_modules "
                        + "for the libraries.", maxChars(a));
            });

    static final Tool CRASH_THREADS = new Def("crash_threads", "Threads of a crash",
            "The threads of a crash file with their stacks, threads with the same stack grouped, the crashed one "
                    + "first. Filter by id, name, state or a function in the stack.",
            new Tool.Schema().string("path", PATH_ARG, true)
                    .string("filter", "Only threads whose id, name, state or frames contain this.", false)
                    .integer("frames", "Frames per group (default 15).", false)
                    .integer("limit", "Most groups (default 30).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                CrashReport r = report(a, w);
                return w.finish(CrashText.threads(r, a.string("filter", null), (int) a.number("frames", 15, 1, 500),
                        (int) a.number("limit", 30, 1, 1000)), maxChars(a));
            });

    static final Tool CRASH_MODULES = new Def("crash_modules", "Modules of a crash",
            "The modules (DLLs, shared libraries) loaded in the crashed process, with versions, paths and whether "
                    + "Logsift has names for them; the crash's module and libraries not from the system first.",
            new Tool.Schema().string("path", PATH_ARG, true)
                    .string("filter", "Only modules whose name or path contains this.", false)
                    .integer("limit", "Most modules (default 100).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                CrashReport r = report(a, w);
                return w.finish(CrashText.modules(r, a.string("filter", null), (int) a.number("limit", 100, 1, 5000)),
                        maxChars(a));
            });
}
