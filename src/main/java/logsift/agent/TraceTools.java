package logsift.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import logsift.core.Archives;
import logsift.core.FolderSearch;
import logsift.core.HitList;
import logsift.core.LogAnalysis;
import logsift.core.LogDocument;
import logsift.core.LogFormat;
import logsift.core.LogMerger;
import logsift.core.RequestIds;
import logsift.core.RequestTrace;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;
import logsift.core.TimeFormat;
import logsift.jvm.Profile;
import logsift.jvm.Profiler;

/**
 * Following and joining: one request through logs or a folder, several logs merged by time,
 * and profiles (JFR recordings, collapsed stacks) explained. Read-only, like every tool: a
 * merge is written to Logsift's own temporary folder, never next to the logs.
 */
public final class TraceTools {
    private TraceTools() {}

    public static List<Tool> all() {
        return List.of(FOLLOW_REQUEST, MERGE_LOGS, ANALYZE_PROFILE);
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

    private static final BooleanSupplier INTERRUPTED = () -> Thread.currentThread().isInterrupted();

    private static int maxChars(Tool.Args a) throws Tool.Failure {
        return (int) a.number("max_chars", Workspace.DEFAULT_MAX_CHARS, 500, Workspace.HARD_MAX_CHARS);
    }

    /** "log:1, /var/log/b.log" → the items. */
    private static List<String> list(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    /** A log, opened and read (its format known). */
    private static LogAnalysis ready(Workspace w, String handleOrPath) throws Exception {
        LogAnalysis log = w.log(handleOrPath);
        String h = w.handleOf(log);
        w.await(h + ":ready", "reading " + log.path().getFileName(), () -> log.awaitReady(INTERRUPTED));
        return log;
    }

    /** "+850 ms", "+2.1 s", "+3 min". */
    static String gap(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        }
        if (ms < 3_600_000) {
            return String.format(Locale.ROOT, "%.1f min", ms / 60_000.0);
        }
        return String.format(Locale.ROOT, "%.1f h", ms / 3_600_000.0);
    }

    // ---------------------------------------------------------------- follow a request

    static final Tool FOLLOW_REQUEST = new Def("follow_request", "Follow a request",
            "Follows one request, trace, order or job id through a log, several logs, or every log in a folder "
                    + "(zips too): its lines in time order with the time between them, the threads it passed "
                    + "through, its errors, and the longest wait (where the time went). Use an id found by search, "
                    + "insights or exceptions.",
            new Tool.Schema().string("id", "The id to follow, as written in the logs.", true)
                    .string("log", "A log's handle or path, or several separated by commas.", false)
                    .string("folder", "A folder (or a service's name from list_logs) to look through instead.", false)
                    .bool("threads", "Also the lines its threads wrote in between that don't name the id "
                            + "(logs only; default true).")
                    .integer("limit", "Most lines shown (default 100).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                String id = a.string("id").strip();
                if (id.length() < 3) {
                    throw new Tool.Failure("An id of at least 3 characters, please: a shorter one matches too much.");
                }
                boolean threads = a.bool("threads", true);
                int limit = (int) a.number("limit", 100, 1, RequestTrace.MAX_STEPS);
                List<RequestTrace.Part> parts = new ArrayList<>();
                if (a.has("log")) {
                    for (String item : list(a.string("log"))) {
                        LogAnalysis log = ready(w, item);
                        String h = w.handleOf(log);
                        Path shown = w.shownPath(h);
                        RequestTrace.Part p = w.await(h + ":follow:" + id + ":" + threads + ":" + log.document().lineCount(),
                                "following " + id + " in " + shown.getFileName(), () -> partInLog(log, shown, id, threads));
                        parts.add(p);
                    }
                } else if (a.has("folder")) {
                    String arg = a.string("folder");
                    List<Path> folders = new ArrayList<>();
                    LogCatalog.Entry service = w.catalog().entry(arg);
                    if (service != null && !LogTools.looksLikePath(arg)) {
                        folders.addAll(service.folders());
                    } else {
                        folders.add(Path.of(arg));
                    }
                    for (Path f : folders) {
                        Path folder = w.options().scope().check(f);
                        if (!Files.isDirectory(folder)) {
                            throw new Tool.Failure("Not a folder: " + folder + ". Give log for a file.");
                        }
                        parts.addAll(w.await("follow-folder:" + folder + ":" + id, "following " + id + " through "
                                + folder, () -> partsInFolder(w, folder, id)));
                    }
                } else {
                    throw new Tool.Failure("Give log (a handle, path, or several separated by commas) or folder.");
                }
                RequestTrace.Trace t = RequestTrace.combine(parts);
                return w.finish(traceText(id, t, parts, limit), maxChars(a));
            });

    private static RequestTrace.Part partInLog(LogAnalysis log, Path shown, String id, boolean threads)
            throws InterruptedException {
        LogDocument doc = log.document();
        SearchQuery q = new SearchQuery(RequestIds.regex(id), true, true, false);
        SearchSession s = new SearchSession(doc, q).start();
        try {
            while (!s.awaitIdle(200)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            }
            HitList hits = s.hits();
            int n = hits.size();
            long[] lines = new long[Math.min(n, RequestTrace.MAX_STEPS)];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = hits.get(i);
            }
            var f = log.formats();
            RequestTrace.Reader r;
            if (f == null || f.times() == null) {
                // too few lines to know the log's layout: read it from the request's own lines
                List<String> texts = new ArrayList<>();
                for (long l : lines) {
                    texts.add(doc.line(l) == null ? "" : doc.line(l));
                }
                RequestTrace.Reader fromHits = readerOf(texts);
                r = new RequestTrace.Reader() {
                    @Override
                    public String text(long line) {
                        return doc.line(line);
                    }

                    @Override
                    public long time(String text) {
                        return fromHits.time(text);
                    }

                    @Override
                    public String thread(String text) {
                        return fromHits.thread(text);
                    }

                    @Override
                    public String level(String text) {
                        return fromHits.level(text);
                    }
                };
            } else {
                r = RequestTrace.reader(doc::line, f.times(), f.columns());
            }
            List<RequestTrace.Step> steps = RequestTrace.steps(lines, n, r, threads, INTERRUPTED);
            return new RequestTrace.Part(shown.getFileName().toString(), shown, steps, n > lines.length);
        } finally {
            s.close();
        }
    }

    private static List<RequestTrace.Part> partsInFolder(Workspace w, Path folder, String id) throws Exception {
        SearchQuery q = new SearchQuery(RequestIds.regex(id), true, true, false);
        List<RequestTrace.Part> parts = new ArrayList<>();
        try (FolderSearch fs = new FolderSearch(new FolderSearch.Options(folder, "", true, true, q)).start()) {
            while (!fs.awaitDone(200)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            }
            for (FolderSearch.FileResult r : fs.results()) {
                if (r.hitCount() == 0 || !w.options().scope().allows(r.file())) {
                    continue;
                }
                Path file = r.entry() == null ? r.file() : Archives.pathOf(r.file(), r.entry());
                List<String> texts = new ArrayList<>();
                for (FolderSearch.Hit h : r.hits()) {
                    texts.add(h.text() == null ? "" : h.text());
                }
                long[] lines = new long[r.hits().size()];
                for (int i = 0; i < lines.length; i++) {
                    lines[i] = r.hits().get(i).line();
                }
                String in = r.file().equals(folder) ? "" : folder.relativize(r.file()).toString();
                String label = r.entry() == null ? in
                        : (in.isEmpty() ? "" : in + "/") + r.entry().replace(Archives.NESTED, "/");
                parts.add(new RequestTrace.Part(label.isEmpty() ? file.getFileName().toString() : label, file,
                        RequestTrace.steps(lines, lines.length, linesReader(r.hits(), texts), false, INTERRUPTED),
                        r.hitCount() > lines.length));
            }
        }
        return parts;
    }

    /** Reads the lines a folder search kept, with the format and times they show. */
    private static RequestTrace.Reader linesReader(List<FolderSearch.Hit> hits, List<String> texts) {
        Map<Long, String> byLine = new HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            byLine.put(hits.get(i).line(), texts.get(i));
        }
        RequestTrace.Reader r = readerOf(texts);
        return new RequestTrace.Reader() {
            @Override
            public String text(long line) {
                return byLine.get(line);
            }

            @Override
            public long time(String text) {
                return r.time(text);
            }

            @Override
            public String thread(String text) {
                return r.thread(text);
            }

            @Override
            public String level(String text) {
                return r.level(text);
            }
        };
    }

    /** Times, threads and levels read with the layout a few lines show. */
    private static RequestTrace.Reader readerOf(List<String> texts) {
        List<String> sample = new ArrayList<>(texts); // a file may have just one or two lines of the request
        while (!texts.isEmpty() && sample.size() < 10) {
            sample.addAll(texts);
        }
        TimeFormat tf = TimeFormat.detect(sample, java.time.Year.now().getValue());
        LogFormat lf = texts.isEmpty() ? null : LogFormat.detect(sample);
        return RequestTrace.reader(l -> null, tf, lf);
    }

    static String traceText(String id, RequestTrace.Trace t, List<RequestTrace.Part> parts, int limit) {
        List<RequestTrace.Step> steps = t.steps();
        if (steps.isEmpty()) {
            return "No line names " + id + " in " + (parts.isEmpty() ? "the folder" : parts.size() == 1
                    ? parts.get(0).source() : parts.size() + " logs") + ".";
        }
        long files = parts.stream().filter(p -> !p.steps().isEmpty()).count();
        StringBuilder b = new StringBuilder();
        b.append(id).append(": ").append(LogTools.n(steps.size())).append(steps.size() == 1 ? " line" : " lines")
                .append(t.cut() ? " (more than shown)" : "");
        if (files > 1) {
            b.append(" in ").append(files).append(" files");
        }
        if (t.first() != TimeFormat.NONE) {
            b.append(", ").append(LogTools.time(t.first())).append(" → ").append(LogTools.time(t.last()));
            if (t.duration() >= 0) {
                b.append(" (").append(gap(t.duration())).append(')');
            }
        }
        b.append(".\n");
        if (!t.threads().isEmpty()) {
            b.append("Threads: ").append(LogTools.cut(String.join(", ", t.threads()), 400)).append(".\n");
        }
        if (t.errors() > 0 || t.warnings() > 0) {
            b.append(t.errors()).append(t.errors() == 1 ? " error, " : " errors, ").append(t.warnings())
                    .append(t.warnings() == 1 ? " warning.\n" : " warnings.\n");
        }
        if (t.longest() >= 0) {
            RequestTrace.Step s = steps.get(t.longest());
            b.append("The longest wait: ").append(gap(s.gap())).append(" before line ").append(LogTools.line(s.line()))
                    .append(s.source() == null ? "" : " of " + s.source()).append(": ")
                    .append(LogTools.cut(s.text().strip(), 200)).append('\n');
        }
        b.append('\n');
        for (int i = 0; i < steps.size() && i < limit; i++) {
            RequestTrace.Step s = steps.get(i);
            b.append(s.source() == null ? "" : "[" + s.source() + "] ").append(LogTools.line(s.line())).append("  ")
                    .append(s.gap() > 0 ? "(+" + gap(s.gap()) + ")  " : "")
                    .append(s.withId() ? "" : "(same thread) ")
                    .append(LogTools.cut(s.text().strip(), 300)).append('\n');
        }
        if (steps.size() > limit) {
            b.append("… ").append(LogTools.n(steps.size() - limit)).append(" more lines (raise limit).\n");
        }
        b.append("Line numbers are each file's own; open_log and read_lines show the lines around them.");
        return b.toString();
    }

    // ---------------------------------------------------------------- merge logs

    static final Tool MERGE_LOGS = new Def("merge_logs", "Merge logs by time",
            "Merges several logs into one in time order (each line labelled with its file), so one request or "
                    + "incident can be read across services. Gives a handle (log:N) the other tools take: insights, "
                    + "search, read_lines, query. Entries (a line and its stack trace) stay together. The merge is "
                    + "written to Logsift's own temporary folder, never next to the logs.",
            new Tool.Schema().string("logs", "Two or more logs: handles or paths, separated by commas.", true)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                List<String> items = list(a.string("logs"));
                if (items.size() < 2) {
                    throw new Tool.Failure("Give two or more logs, separated by commas.");
                }
                List<LogAnalysis> logs = new ArrayList<>();
                List<Path> shown = new ArrayList<>();
                for (String item : items) {
                    LogAnalysis log = ready(w, item);
                    logs.add(log);
                    shown.add(w.shownPath(w.handleOf(log)));
                }
                List<String> labels = LogMerger.labels(shown);
                List<LogMerger.Source> sources = new ArrayList<>();
                for (int i = 0; i < logs.size(); i++) {
                    sources.add(new LogMerger.Source(logs.get(i).path(), labels.get(i)));
                }
                String key = "merge:" + String.join("|", shown.stream().map(Path::toString).toList());
                String h = w.await(key, "merging " + items.size() + " logs", () -> {
                    Path dir = Files.createTempDirectory("logsift-agent-merge-");
                    Path out = dir.resolve("merged.log");
                    try (LogMerger m = LogMerger.merge(sources, out, null, INTERRUPTED)) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                        m.linesWritten();
                    } catch (java.io.IOException e) {
                        Files.deleteIfExists(out);
                        Files.deleteIfExists(dir);
                        throw new Tool.Failure("Couldn't merge: " + e.getMessage());
                    }
                    return w.openGenerated(out);
                });
                LogAnalysis merged = ready(w, h);
                StringBuilder b = new StringBuilder();
                b.append("Merged ").append(items.size()).append(" logs by time into ").append(h).append(":\n");
                for (int i = 0; i < shown.size(); i++) {
                    b.append("- <").append(labels.get(i)).append("> ").append(shown.get(i)).append('\n');
                }
                b.append("Each line starts with its file's label, like <").append(labels.get(0)).append(">.\n");
                b.append(LogTools.describe(h, merged, w.shownPath(h)).replace("insights(log=\"" + h + "\")",
                        "insights(log=\"" + h + "\") or search(log=\"" + h + "\")"));
                return w.finish(b.toString(), maxChars(a));
            });

    // ---------------------------------------------------------------- profiles

    static final Tool ANALYZE_PROFILE = new Def("analyze_profile", "Explain a profile",
            "Explains a profile file: a Java Flight Recorder recording (.jfr, also async-profiler's) or collapsed "
                    + "stacks (async-profiler or flame graph text: frame;frame;frame count). For each kind it holds "
                    + "(CPU, wall clock, allocations, locks…): the total, the methods with the most time of their own "
                    + "and with what they call (with %), the hottest stacks, and which are the program's own code.",
            new Tool.Schema().string("path", "The profile file's path.", true)
                    .integer("limit", "Most methods per list (default 15).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                Path file = w.options().scope().check(Path.of(a.string("path")));
                if (Files.isDirectory(file)) {
                    throw new Tool.Failure("That is a folder: " + file + ". Give a .jfr or collapsed-stack file.");
                }
                int limit = (int) a.number("limit", 15, 1, 200);
                List<Profile> profiles;
                try {
                    profiles = w.await("profile:" + file + ":" + Files.getLastModifiedTime(file).toMillis(),
                            "reading " + file.getFileName(), () -> Profiler.open(file));
                } catch (java.io.IOException e) {
                    throw new Tool.Failure(e.getMessage());
                }
                StringBuilder b = new StringBuilder();
                for (Profile p : profiles) {
                    profileText(b, p, limit);
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    /** A method of the JDK, the JVM or the system rather than the program's own. */
    static boolean system(Profile.Method m) {
        if (m.kind() == Profile.Kind.NATIVE || m.kind() == Profile.Kind.KERNEL || m.kind() == Profile.Kind.THREAD) {
            return true;
        }
        String n = m.name();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
                || n.startsWith("com.sun.") || n.startsWith("kotlin.") || n.startsWith("scala.");
    }

    private static String pct(long v, long total) {
        return total <= 0 ? "" : String.format(Locale.ROOT, "%.1f%%", 100.0 * v / total);
    }

    static void profileText(StringBuilder b, Profile p, int limit) {
        long total = p.total();
        b.append(p.title()).append(": ").append(p.amount(total)).append(".\n");
        for (String warn : p.warnings()) {
            b.append("(").append(warn).append(")\n");
        }
        List<Profile.Method> all = p.hottest(Integer.MAX_VALUE);
        b.append("Most time of their own:\n");
        int shown = 0;
        for (Profile.Method m : all) {
            if (m.self() <= 0 || shown >= limit) {
                break;
            }
            shown++;
            b.append(String.format(Locale.ROOT, "%2d. %s  %s (%s)", shown, LogTools.cut(m.name(), 200), p.amount(m.self()),
                    pct(m.self(), total))).append(system(m) ? "" : "  ← own code").append('\n');
        }
        List<Profile.Method> byTotal = new ArrayList<>(all);
        byTotal.sort(Comparator.comparingLong(Profile.Method::total).reversed());
        b.append("Most time with what they call (own code):\n");
        shown = 0;
        for (Profile.Method m : byTotal) {
            if (shown >= limit) {
                break;
            }
            if (system(m) || m.total() <= 0) {
                continue;
            }
            shown++;
            b.append(String.format(Locale.ROOT, "%2d. %s  %s (%s)", shown, LogTools.cut(m.name(), 200), p.amount(m.total()),
                    pct(m.total(), total))).append('\n');
        }
        if (shown == 0) {
            b.append("   (no frames of the program's own code)\n");
        }
        record Stack(List<String> frames, long weight) {}
        List<Stack> stacks = new ArrayList<>();
        p.forEachStack((path, w) -> stacks.add(new Stack(path.stream().map(Profile.Node::name).toList(), w)));
        stacks.sort(Comparator.comparingLong(Stack::weight).reversed());
        b.append("Hottest stacks (innermost frame first):\n");
        for (int i = 0; i < stacks.size() && i < Math.min(5, limit); i++) {
            Stack s = stacks.get(i);
            List<String> f = new ArrayList<>(s.frames());
            java.util.Collections.reverse(f);
            b.append(String.format(Locale.ROOT, "%d. %s (%s)\n", i + 1, p.amount(s.weight()), pct(s.weight(), total)));
            for (int k = 0; k < f.size() && k < 8; k++) {
                b.append("     ").append(LogTools.cut(f.get(k), 200)).append('\n');
            }
            if (f.size() > 8) {
                b.append("     … ").append(f.size() - 8).append(" more frames\n");
            }
        }
        b.append('\n');
    }
}
