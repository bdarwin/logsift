package logsift.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import logsift.core.Exceptions;
import logsift.core.FolderSearch;
import logsift.core.HitList;
import logsift.core.Insights;
import logsift.core.Levels;
import logsift.core.LogAnalysis;
import logsift.core.LogDocument;
import logsift.core.MessageStats;
import logsift.core.Metrics;
import logsift.core.Numbers;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;
import logsift.core.Spans;
import logsift.core.TimeFormat;
import logsift.jvm.DumpParser;
import logsift.jvm.GcLog;
import logsift.jvm.GcReport;
import logsift.jvm.ThreadAnalysis;
import logsift.jvm.ThreadDump;

/**
 * The read-only tools: logs, folders, GC logs and thread dumps. Answers are short summaries with
 * line numbers (1-based, as Logsift shows them) the agent can come back to, never whole files.
 */
public final class LogTools {
    private LogTools() {}

    /** Every tool, in a fixed order (agents cache the list): these, then the crash tools. */
    public static List<Tool> all() {
        List<Tool> all = new ArrayList<>(List.of(LIST_LOGS, OPEN_LOG, LIST_OPEN, INSIGHTS, EXCEPTIONS, SEARCH,
                READ_LINES, TOP_MESSAGES, METRICS, QUERY, SEARCH_FOLDER, ANALYZE_GC, ANALYZE_THREADS));
        all.addAll(CrashTools.all());
        return List.copyOf(all);
    }

    /** A tool from its parts. */
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

    private static final String LOG_ARG = "A handle from open_log (like log:1), or the log file's path.";

    // ---------------------------------------------------------------- words

    static String n(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    /** A 0-based line as people see it. */
    static String line(long l) {
        return n(l + 1);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** A log time as written in the log (Logsift keeps them as UTC, without zones). */
    static String time(long t) {
        if (t == TimeFormat.NONE) {
            return "?";
        }
        String s = TIME.format(LocalDateTime.ofEpochSecond(Math.floorDiv(t, 1000), Math.floorMod(t, 1000) * 1_000_000,
                ZoneOffset.UTC));
        return s.endsWith(".000") ? s.substring(0, s.length() - 4) : s;
    }

    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "… (" + n(s.length() - max) + " more characters)";
    }

    private static int maxChars(Tool.Args a) throws Tool.Failure {
        return (int) a.number("max_chars", Workspace.DEFAULT_MAX_CHARS, 500, Workspace.HARD_MAX_CHARS);
    }

    private static final BooleanSupplier INTERRUPTED = () -> Thread.currentThread().isInterrupted();

    /** The log, read and its format known (waits a while; "still working" for big files). */
    private static LogAnalysis ready(Tool.Args a, Workspace w) throws Exception {
        LogAnalysis log = w.log(a.string("log"));
        String h = w.handleOf(log);
        w.await(h + ":ready", "reading " + log.path().getFileName() + " (" + n(log.document().lineCount())
                + " lines so far)", () -> log.awaitReady(INTERRUPTED));
        return log;
    }

    // ---------------------------------------------------------------- open and list

    static final Tool OPEN_LOG = new Def("open_log", "Open a log",
            "Opens a log file (plain, .gz, or any text log; big files are fine) and says what it is: size, lines, "
                    + "format, time range. Gives a handle (log:1) for the other tools. Start here, then call "
                    + "insights for what happened in it. Without a path, call list_logs: it names the person's "
                    + "services and their logs, and a name from it (\"orders/app\") opens the newest file of that log.",
            new Tool.Schema().string("path", "The log file's path, or a name from list_logs (\"orders/app\").", true)
                    .build(),
            (a, w) -> {
                String h = w.open(w.pathOf(a.string("path")));
                LogAnalysis log = w.log(h);
                w.await(h + ":ready", "reading " + log.path().getFileName() + " (" + n(log.document().lineCount())
                        + " lines so far)", () -> log.awaitReady(INTERRUPTED));
                return describe(h, log, w.shownPath(h));
            });

    static String describe(String h, LogAnalysis log, Path shown) {
        LogDocument doc = log.document();
        StringBuilder b = new StringBuilder();
        b.append(h).append("  ").append(shown).append('\n');
        b.append(size(doc.fileSize())).append(", ").append(n(doc.lineCount())).append(" lines, ")
                .append(logsift.core.Encoding.label(doc.charset())).append(".\n");
        var f = log.formats();
        if (f != null) {
            b.append("Format: ").append(f.columns() != null ? f.columns().name() : "plain text");
            if (f.columns() != null && !f.columns().columns().isEmpty()) {
                b.append(" (columns: ").append(String.join(", ", f.columns().columns())).append(')');
            }
            b.append(f.entries() != null ? "; entries start with " + f.entries().name()
                    + ", so stack traces stay with their line" : "; every line is an entry").append(".\n");
            if (f.times() != null) {
                long first = TimeFormat.NONE;
                for (long l = 0; l < Math.min(200, doc.lineCount()) && first == TimeFormat.NONE; l++) {
                    String t = doc.line(l);
                    first = t == null ? TimeFormat.NONE : f.times().parse(t);
                }
                long last = LogAnalysis.lastTime(doc, f.times(), doc.lineCount());
                b.append("Times: ").append(time(first)).append(" → ").append(time(last)).append(".\n");
            } else {
                b.append("No timestamps found.\n");
            }
        }
        b.append("Next: insights(log=\"").append(h).append("\") for what happened, exceptions, search, read_lines.");
        return b.toString();
    }

    static String size(long bytes) {
        return GcReport.size(bytes);
    }

    static final Tool LIST_LOGS = new Def("list_logs", "Where the logs are",
            "The person's services and where their logs are, as they described them in Logsift: each service's "
                    + "folders, its kinds of log (with rotated copies counted together), what each holds, sizes, "
                    + "when they last changed, time zone and notes. Call this first when you don't have a path; "
                    + "open a log by its name (\"orders/app\") or search a whole service with search_folder.",
            new Tool.Schema().string("service", "Only this service, with every file (default: all, briefly).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> w.finish(catalogText(w, a.string("service", null)), maxChars(a)));

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static String when(long millis) {
        return millis <= 0 ? "?" : WHEN.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()));
    }

    static String catalogText(Workspace w, String only) {
        return catalogText(w.catalog(), w.options().scope(), w.options().formats(), only);
    }

    /** What list_logs answers, for these locations (the window shows it as a preview). */
    public static String catalogText(LogCatalog c, Scope scope, List<logsift.core.CustomFormat> formats, String only) {
        StringBuilder b = new StringBuilder();
        if (c.isEmpty()) {
            b.append("No log locations are described yet. Ask the person to add them in Logsift (File → Log Locations "
                    + "for Agents…, or Describe for Agents… in a folder's tab), or to add a .logsift/logs.json to the "
                    + "project. Until then, open_log needs a path.");
            for (String p : c.problems()) {
                b.append("\n(Couldn't read ").append(p).append(".)");
            }
            return b.toString();
        }
        List<LogCatalog.Entry> entries = new ArrayList<>();
        for (LogCatalog.Entry e : c.entries()) {
            if (only == null || e.service().name().equalsIgnoreCase(only.strip())) {
                entries.add(e);
            }
        }
        if (entries.isEmpty()) {
            List<String> names = new ArrayList<>();
            c.entries().forEach(e -> names.add(e.service().name()));
            return "No service called " + only + ". The services: " + String.join(", ", names) + ".";
        }
        boolean detail = only != null;
        b.append(entries.size()).append(entries.size() == 1 ? " service" : " services").append(":\n");
        for (LogCatalog.Entry e : entries) {
            LogCatalog.Service s = e.service();
            b.append('\n').append(s.name());
            if (!s.description().isEmpty()) {
                b.append(" — ").append(s.description());
            }
            b.append(e.project() ? "  (from the project's " + e.file() + ")" : "").append('\n');
            if (!s.timeZone().isEmpty()) {
                b.append("  Times in its logs: ").append(s.timeZone()).append('\n');
            }
            if (!s.notes().isEmpty()) {
                b.append("  Notes: ").append(s.notes().replace("\n", " ")).append('\n');
            }
            List<LogCatalog.Group> groups = new ArrayList<>();
            for (Path f : e.folders()) {
                if (!scope.allows(f)) {
                    b.append("  ").append(f).append("  (agents may not read this folder: the person can allow it in "
                            + "Settings → Agents)\n");
                } else if (!Files.isDirectory(f) && !Files.isRegularFile(f)) {
                    b.append("  ").append(f).append("  (not found on this computer)\n");
                } else {
                    b.append("  ").append(f).append('\n');
                }
            }
            LogCatalog.Entry readable = new LogCatalog.Entry(new LogCatalog.Service(s.name(), s.description(),
                    e.folders().stream().filter(f -> scope.allows(f)).map(Path::toString).toList(),
                    s.files(), s.timeZone(), s.notes()), e.file(), false);
            groups.addAll(LogCatalog.scan(readable));
            int max = detail ? 200 : 15;
            int guessed = 0;
            for (int i = 0; i < groups.size() && i < max; i++) {
                LogCatalog.Group g = groups.get(i);
                String what = g.description();
                if (what == null || what.isBlank()) {
                    String gs = guessed++ < 15 ? LogCatalog.guess(g.current(), formats) : null;
                    what = gs == null ? "(not described)" : "(guessed) " + gs;
                }
                b.append("  - ").append(s.name()).append('/').append(LogCatalog.stem(g.name())).append(": ")
                        .append(g.name()).append(" — ").append(what).append('\n');
                b.append("      ").append(g.files().size() == 1 ? "1 file" : n(g.files().size()) + " files (" + g.pattern()
                        + ")").append(", ").append(size(g.bytes())).append(", last changed ").append(when(g.newest()));
                if (g.files().size() > 1) {
                    b.append(", oldest ").append(when(g.oldest()));
                }
                b.append('\n');
                if (detail && g.files().size() > 1) {
                    for (int k = 0; k < g.files().size() && k < 10; k++) {
                        b.append("        ").append(g.files().get(k)).append('\n');
                    }
                    if (g.files().size() > 10) {
                        b.append("        … ").append(g.files().size() - 10).append(" more\n");
                    }
                }
            }
            if (groups.size() > max) {
                b.append("  … and ").append(groups.size() - max).append(" more kinds of file (list_logs with service=\"")
                        .append(s.name()).append("\" lists them all).\n");
            }
            if (groups.isEmpty()) {
                b.append("  (no files found)\n");
            }
        }
        b.append("\nOpen one with open_log(path=\"service/name\"): the newest file of that log. Search a service's "
                + "folders with search_folder(folder=\"service\").");
        for (String p : c.problems()) {
            b.append("\n(Couldn't read ").append(p).append(".)");
        }
        return b.toString();
    }

    static final Tool LIST_OPEN = new Def("list_open", "Open logs",
            "Lists the logs this session has open, with their handles.",
            new Tool.Schema().build(),
            (a, w) -> {
                Map<String, LogAnalysis> logs = w.logs();
                if (logs.isEmpty()) {
                    return "No logs open yet. Use open_log.";
                }
                StringBuilder b = new StringBuilder();
                logs.forEach((h, log) -> b.append(h).append("  ").append(w.shownPath(h)).append("  ")
                        .append(n(log.document().lineCount())).append(" lines\n"));
                return b.toString().stripTrailing();
            });

    // ---------------------------------------------------------------- insights

    static final Tool INSIGHTS = new Def("insights", "What happened in a log",
            "The log's story in a few findings, most important first: error bursts, new errors and when they "
                    + "began, known problems with what to try, exceptions by root cause, things that never "
                    + "finished or got slow, silences, restarts, noisy messages. Each with a line number.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .integer("limit", "Most findings (default 15).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                int limit = (int) a.number("limit", 15, 1, 100);
                Insights.Report r = w.await(w.handleOf(log) + ":insights", "working out the insights",
                        () -> log.insights(INTERRUPTED));
                if (r == null) {
                    throw new Tool.Failure("Stopped before the insights were ready.");
                }
                StringBuilder b = new StringBuilder();
                b.append(log.path().getFileName()).append(": ").append(r.summary()).append('\n');
                int i = 0;
                for (Insights.Finding f : r.findings()) {
                    if (i++ >= limit) {
                        b.append("… and ").append(r.findings().size() - limit).append(" more (raise limit).\n");
                        break;
                    }
                    b.append(i).append(". [").append(f.severity()).append("] ").append(f.title());
                    if (f.detail() != null && !f.detail().isBlank()) {
                        b.append(" — ").append(f.detail().replace('\n', ' '));
                    }
                    if (f.line() >= 0) {
                        b.append(" (line ").append(line(f.line())).append(')');
                    }
                    b.append('\n');
                    if (f.known() != null) {
                        b.append("   Why: ").append(f.known().why()).append('\n');
                        b.append("   Try: ").append(f.known().tryThis()).append('\n');
                    }
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    // ---------------------------------------------------------------- exceptions

    static final Tool EXCEPTIONS = new Def("exceptions", "Exceptions by root cause",
            "Groups the log's exceptions by root cause (Java, Python, .NET, JavaScript stack traces): how many, "
                    + "first and last line, what wrapped them, and a sample stack with the program's own frames "
                    + "marked.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .integer("limit", "Most groups (default 10).", false)
                    .integer("frames", "Stack frames shown per group (default 8).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                int limit = (int) a.number("limit", 10, 1, 200);
                int frames = (int) a.number("frames", 8, 0, 100);
                Exceptions.Result r = w.await(w.handleOf(log) + ":exceptions", "grouping the exceptions",
                        () -> log.exceptions(INTERRUPTED, null));
                if (r == null) {
                    throw new Tool.Failure("Stopped before the exceptions were grouped.");
                }
                if (r.groups().isEmpty()) {
                    return "No exceptions (stack traces) in " + log.path().getFileName() + ".";
                }
                StringBuilder b = new StringBuilder();
                b.append(n(r.exceptions())).append(" exceptions in ").append(r.groups().size())
                        .append(r.groups().size() == 1 ? " group" : " groups").append(" by root cause")
                        .append(r.truncated() ? " (too many to read them all; the first ones counted)" : "")
                        .append(":\n");
                int i = 0;
                for (Exceptions.Group g : r.groups()) {
                    if (i++ >= limit) {
                        b.append("… and ").append(r.groups().size() - limit).append(" more groups (raise limit).\n");
                        break;
                    }
                    Exceptions.Thrown root = g.root();
                    b.append('\n').append(i).append(". ").append(n(g.count())).append(" × ").append(root.type());
                    if (root.message() != null && !root.message().isBlank()) {
                        b.append(": ").append(cut(root.message(), 300));
                    }
                    b.append("\n   lines ").append(line(g.firstLine())).append(" … ").append(line(g.lastLine()));
                    if (!g.wrappers().isEmpty()) {
                        b.append("; wrapped by ").append(String.join(" ← ", g.wrappers()));
                    }
                    b.append('\n');
                    int k = 0;
                    for (Exceptions.Frame f : root.frames()) {
                        if (k++ >= frames) {
                            b.append("   … ").append(root.frames().size() - frames).append(" more frames\n");
                            break;
                        }
                        b.append("   at ").append(cut(f.text().strip(), 300)).append(f.own() ? "   ← own code" : "")
                                .append('\n');
                    }
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    // ---------------------------------------------------------------- search and read

    /** The hits of a search, kept while the agent pages through them. */
    private record Found(HitList hits, boolean limited) {}

    static final Tool SEARCH = new Def("search", "Search a log",
            "Finds lines in a log: plain text (default) or a regular expression, optionally within a line range. "
                    + "Gives the total and the first matching lines with their numbers. Fast on gigabytes.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .string("query", "What to find.", true)
                    .bool("regex", "query is a Java regular expression (default false).")
                    .bool("match_case", "Upper and lower case must match (default false).")
                    .bool("whole_word", "Only whole words (default false).")
                    .integer("from_line", "First line to look at (1-based, default 1).", false)
                    .integer("to_line", "Last line to look at (default: the end).", false)
                    .integer("limit", "Most lines shown (default 50).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                SearchQuery q = new SearchQuery(a.string("query"), a.bool("match_case", false), a.bool("regex", false),
                        a.bool("whole_word", false));
                try {
                    q.toPattern();
                } catch (RuntimeException e) {
                    throw new Tool.Failure("Not a valid regular expression: " + e.getMessage());
                }
                long from = a.number("from_line", 1, 1, Long.MAX_VALUE) - 1;
                long to = a.number("to_line", Long.MAX_VALUE, 1, Long.MAX_VALUE); // exclusive, 0-based
                int limit = (int) a.number("limit", 50, 1, 500);
                LogDocument doc = log.document();
                Found found = w.await(w.handleOf(log) + ":search:" + q, "searching for “" + q.text() + "”", () -> {
                    SearchSession s = new SearchSession(doc, q).start();
                    try {
                        while (!s.awaitIdle(200)) {
                            if (Thread.currentThread().isInterrupted()) {
                                return null;
                            }
                        }
                        return new Found(s.hits(), s.isLimitReached());
                    } finally {
                        s.close();
                    }
                });
                if (found == null) {
                    throw new Tool.Failure("Stopped before the search finished.");
                }
                HitList h = found.hits();
                int first = h.firstAtOrAfter(from);
                int end = to == Long.MAX_VALUE ? h.size() : h.firstAtOrAfter(to);
                int count = Math.max(0, end - first);
                StringBuilder b = new StringBuilder();
                b.append(n(count)).append(found.limited() ? "+" : "").append(count == 1 ? " line matches" : " lines match")
                        .append(" “").append(q.text()).append('”');
                if (from > 0 || to != Long.MAX_VALUE) {
                    b.append(" in lines ").append(line(from)).append(" … ")
                            .append(to == Long.MAX_VALUE ? "end" : n(to));
                }
                b.append(count == 0 ? "." : ":").append('\n');
                for (int i = first; i < end && i < first + limit; i++) {
                    long l = h.get(i);
                    b.append(line(l)).append(": ").append(cut(doc.line(l), 300)).append('\n');
                }
                if (count > limit) {
                    long next = h.get(first + limit);
                    b.append("… ").append(n(count - limit)).append(" more. Next: from_line=").append(line(next)).append('\n');
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    static final Tool READ_LINES = new Def("read_lines", "Read lines",
            "Reads lines of a log by number (at most 200 at a time), for example around a finding or a search hit.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .integer("from", "First line (1-based).", true)
                    .integer("count", "How many lines (default 50, at most 200).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                LogDocument doc = log.document();
                long total = doc.lineCount();
                long from = a.number("from", 1, 1, Long.MAX_VALUE) - 1;
                int count = (int) a.number("count", 50, 1, 200);
                if (from >= total) {
                    throw new Tool.Failure("The log has " + n(total) + " lines.");
                }
                int k = (int) Math.min(count, total - from);
                String[] out = new String[k];
                doc.readLines(from, k, out);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < k; i++) {
                    b.append(line(from + i)).append(": ").append(cut(out[i], 2000)).append('\n');
                }
                if (from + k < total) {
                    b.append("(").append(n(total - from - k)).append(" more lines after these.)");
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    // ---------------------------------------------------------------- SQL

    static final Tool QUERY = new Def("query", "SQL over a log or table",
            "Runs a read-only SQL query (DuckDB) over one log or CSV/TSV file, for exact counts, groups, time "
                    + "buckets, percentiles and top-N lists. A log is the table log (line, starts, time, then its "
                    + "columns such as level, thread, logger, message, and text: one row per line, line numbers as in "
                    + "the other tools; starts is false for continuation lines like stack traces). A CSV/TSV file is "
                    + "the table t (line, then its columns, numbers and times typed). Example: SELECT "
                    + "date_trunc('minute', time) AS minute, count(*) FROM log WHERE level = 'ERROR' GROUP BY minute "
                    + "ORDER BY minute. Only queries run (no COPY, ATTACH, SET…), and SQL can't read files itself.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .string("sql", "One SELECT (or WITH, FROM, PIVOT, SUMMARIZE, DESCRIBE) query over log or t.", true)
                    .integer("limit", "Most rows shown (default 100, at most 1000).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                if (!logsift.core.Sql.available()) {
                    throw new Tool.Failure("SQL isn't available here: " + logsift.core.Sql.unavailableReason());
                }
                LogAnalysis log = ready(a, w);
                String h = w.handleOf(log);
                String q = a.string("sql");
                int limit = (int) a.number("limit", 100, 1, 1000);
                LogDocument doc = log.document();
                var f = log.formats();
                boolean table = f != null && f.columns() != null && f.columns().isCsv();
                logsift.core.Sql.LogTable t = w.sqlTable(h, log, sql -> {
                    String name = "file_" + h.replaceAll("\\W", "_");
                    if (table) {
                        logsift.core.Pivot.Fields fields = logsift.core.Pivot.fields(f.columns(), doc.lineCount(),
                                doc::readLines, java.time.Year.now().getValue());
                        return sql.table(name, doc, f.columns(), fields);
                    }
                    return sql.log(name, doc, f == null ? null : f.columns(), f == null ? null : f.times(),
                            f == null ? null : f.entries());
                });
                w.await(h + ":sql:" + doc.generation() + ":" + doc.lineCount(), "getting " + log.path().getFileName()
                        + " ready for SQL (once; new lines are added as it grows)", () -> t.update(INTERRUPTED, null));
                logsift.core.Sql.Result r;
                try {
                    r = w.await("sql:" + h + ":" + t.loaded() + ":" + q + ":" + limit, "running the query",
                            () -> w.sql().query(table ? "t" : "log", t, q, limit, INTERRUPTED));
                } catch (java.sql.SQLException e) {
                    throw new Tool.Failure(e.getMessage());
                }
                return w.finish(sqlText(r, limit, table ? "t" : "log", t), maxChars(a));
            });

    static String sqlText(logsift.core.Sql.Result r, int limit, String table, logsift.core.Sql.LogTable t) {
        StringBuilder b = new StringBuilder();
        b.append(n(r.rows().size())).append(r.rows().size() == 1 ? " row" : " rows")
                .append(r.more() ? " (the first " + n(limit) + "; raise limit or narrow the query)" : "")
                .append(" in ").append(n(r.millis())).append(" ms:\n");
        b.append(String.join(" | ", r.columns())).append('\n');
        for (Object[] row : r.rows()) {
            for (int i = 0; i < row.length; i++) {
                b.append(i == 0 ? "" : " | ").append(row[i] == null ? "null" : cut(String.valueOf(row[i]), 300)
                        .replace('\n', ' '));
            }
            b.append('\n');
        }
        if (r.rows().isEmpty()) {
            b.append("(no rows) Columns of ").append(table).append(": ").append(String.join(", ", t.sqlColumns()))
                    .append('.');
        }
        return b.toString().stripTrailing();
    }

    // ---------------------------------------------------------------- messages and metrics

    static final Tool TOP_MESSAGES = new Def("top_messages", "Most frequent messages",
            "The log's messages grouped by kind (numbers, ids and times blanked as …), most frequent first, with "
                    + "their level and first and last line. Shows what the log is mostly about, and what is noise.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .choice("level", "Only this level.", false, "ERROR", "WARN", "INFO", "DEBUG", "TRACE")
                    .integer("limit", "Most kinds (default 20).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                String level = a.has("level") ? Levels.normalize(a.string("level")) : null;
                int limit = (int) a.number("limit", 20, 1, 500);
                MessageStats.Result r = w.await(w.handleOf(log) + ":messages", "grouping the messages",
                        () -> log.messages(INTERRUPTED, null));
                if (r == null) {
                    throw new Tool.Failure("Stopped before the messages were grouped.");
                }
                List<MessageStats.Template> ts = new ArrayList<>();
                for (MessageStats.Template t : r.templates()) {
                    if (level == null || level.equals(Levels.normalize(t.level()))) {
                        ts.add(t);
                    }
                }
                ts.sort((x, y) -> Long.compare(y.count(), x.count()));
                StringBuilder b = new StringBuilder();
                b.append(n(ts.size())).append(" kinds of ").append(level == null ? "" : level + " ")
                        .append("messages in ").append(n(r.lines())).append(" lines")
                        .append(r.truncated() ? " (too many kinds: the first ones)" : "").append(":\n");
                for (int i = 0; i < ts.size() && i < limit; i++) {
                    MessageStats.Template t = ts.get(i);
                    b.append(n(t.count())).append(" × ").append(t.level() == null || t.level().isEmpty() ? "" : t.level() + " ")
                            .append(cut(Insights.display(t.text()), 300)).append("  (lines ").append(line(t.firstLine()))
                            .append(" … ").append(line(t.lastLine())).append(")\n");
                }
                if (ts.size() > limit) {
                    b.append("… and ").append(n(ts.size() - limit)).append(" more kinds.");
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    static final Tool METRICS = new Def("metrics", "Durations and numbers",
            "How long things that start and end took (jobs, requests: count, median, 95th percentile, slowest, "
                    + "never finished), and numbers in messages over time (min, median, max).",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .integer("limit", "Most of each (default 10).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                int limit = (int) a.number("limit", 10, 1, 100);
                Metrics m = w.await(w.handleOf(log) + ":metrics", "measuring durations and numbers",
                        () -> log.metrics(INTERRUPTED));
                if (m == null) {
                    throw new Tool.Failure("Stopped before the measuring finished.");
                }
                StringBuilder b = new StringBuilder();
                List<Spans.Kind> kinds = m.spans() == null ? List.of() : m.spans().kinds();
                if (kinds.isEmpty()) {
                    b.append("No things that start and end were found.\n");
                } else {
                    b.append("Durations (start → end):\n");
                    for (int i = 0; i < kinds.size() && i < limit; i++) {
                        Spans.Kind k = kinds.get(i);
                        b.append("- ").append(k.name()).append(": ").append(n(k.count())).append(" done, median ")
                                .append(GcReport.ms(k.median())).append(", p95 ").append(GcReport.ms(k.p95()))
                                .append(", max ").append(GcReport.ms(k.max()));
                        if (k.unfinishedCount() > 0) {
                            b.append(", ").append(n(k.unfinishedCount())).append(" never finished (first at line ")
                                    .append(line(k.unfinished().get(0).startLine())).append(')');
                        }
                        b.append("  [").append(k.matchedBy()).append("]\n");
                        for (int j = 0; j < k.slowest().size() && j < 3; j++) {
                            Spans.Span s = k.slowest().get(j);
                            b.append("    slow: ").append(s.millis() < 0 ? "?" : GcReport.ms(s.millis())).append(" lines ")
                                    .append(line(s.startLine())).append(" → ").append(s.endLine() < 0 ? "?" : line(s.endLine()))
                                    .append('\n');
                        }
                    }
                }
                List<Numbers.Series> nums = m.numbers() == null ? List.of() : m.numbers();
                if (!nums.isEmpty()) {
                    b.append("Numbers in messages:\n");
                    for (int i = 0; i < nums.size() && i < limit; i++) {
                        Numbers.Series s = nums.get(i);
                        b.append("- ").append(cut(Insights.display(s.name()), 200)).append(": ").append(n(s.count()))
                                .append(" values, median ").append(num(s.percentile(0.5))).append(", p95 ")
                                .append(num(s.percentile(0.95))).append(", max ").append(num(s.max()));
                        int[] top = s.largest(1);
                        if (top.length > 0) {
                            b.append(" (line ").append(line(s.lines()[top[0]])).append(')');
                        }
                        b.append('\n');
                    }
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    private static String num(double v) {
        if (Double.isNaN(v)) {
            return "?";
        }
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? n((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    // ---------------------------------------------------------------- folders

    static final Tool SEARCH_FOLDER = new Def("search_folder", "Search a folder",
            "Searches every log in a folder and its subfolders, inside .zip and .gz files too, without opening "
                    + "them. Gives matching files with their hit counts and first hits. The folder can be a "
                    + "service's name from list_logs: all its folders are searched.",
            new Tool.Schema().string("folder", "The folder's path, or a service's name from list_logs.", true)
                    .string("query", "What to find.", true)
                    .bool("regex", "query is a Java regular expression (default false).")
                    .bool("match_case", "Upper and lower case must match (default false).")
                    .string("names", "File names to search, like *.log;*.txt (default: all).", false)
                    .bool("archives", "Look inside .zip and .gz files (default true).")
                    .integer("limit_files", "Most files listed (default 50).", false)
                    .integer("hits_per_file", "Lines shown per file (default 5).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                String arg = a.string("folder");
                List<Path> folders = new ArrayList<>();
                LogCatalog.Entry service = w.catalog().entry(arg);
                if (service != null && !looksLikePath(arg)) {
                    folders.addAll(service.folders());
                } else {
                    folders.add(Path.of(arg));
                }
                List<Path> checked = new ArrayList<>();
                for (Path f : folders) {
                    Path c = w.options().scope().check(f);
                    if (!Files.isDirectory(c)) {
                        throw new Tool.Failure("Not a folder: " + c + ". Use open_log and search for a file.");
                    }
                    checked.add(c);
                }
                SearchQuery q = new SearchQuery(a.string("query"), a.bool("match_case", false), a.bool("regex", false),
                        false);
                try {
                    q.toPattern();
                } catch (RuntimeException e) {
                    throw new Tool.Failure("Not a valid regular expression: " + e.getMessage());
                }
                String names = a.string("names", "");
                boolean archives = a.bool("archives", true);
                int limitFiles = (int) a.number("limit_files", 50, 1, 1000);
                int perFile = (int) a.number("hits_per_file", 5, 0, 100);
                List<FolderSearch.FileResult> results = new ArrayList<>();
                long done = 0;
                long skipped = 0;
                int hidden = 0;
                for (Path folder : checked) {
                    FolderSearch fs = w.await("folder:" + folder + ":" + names + ":" + archives + ":" + q,
                            "searching " + folder, () -> {
                                FolderSearch s = new FolderSearch(new FolderSearch.Options(folder, names, true, archives,
                                        q)).start();
                                try {
                                    while (!s.awaitDone(1000)) {
                                        // until every file is read
                                    }
                                } catch (InterruptedException e) {
                                    s.close();
                                    throw e;
                                }
                                return s;
                            });
                    done += fs.filesDone();
                    skipped += fs.filesSkipped();
                    for (FolderSearch.FileResult r : fs.results()) {
                        if (r.hitCount() == 0) {
                            continue;
                        }
                        if (!w.options().scope().allows(r.file())) {
                            hidden++;
                            continue;
                        }
                        results.add(r);
                    }
                }
                results.sort((x, y) -> Long.compare(y.hitCount(), x.hitCount()));
                long total = results.stream().mapToLong(FolderSearch.FileResult::hitCount).sum();
                StringBuilder b = new StringBuilder();
                b.append(n(total)).append(" matching lines in ").append(n(results.size())).append(" of ")
                        .append(n(done)).append(" files searched");
                if (checked.size() > 1) {
                    b.append(" in ").append(checked.size()).append(" folders");
                }
                if (skipped > 0) {
                    b.append(" (").append(n(skipped)).append(" skipped)");
                }
                b.append(":\n");
                for (int i = 0; i < results.size() && i < limitFiles; i++) {
                    FolderSearch.FileResult r = results.get(i);
                    b.append('\n').append(r.file()).append(r.entry() == null ? "" : " → " + r.entry()).append(": ")
                            .append(n(r.hitCount())).append(r.hitCount() == 1 ? " line\n" : " lines\n");
                    List<FolderSearch.Hit> hits = r.hits();
                    for (int j = 0; j < hits.size() && j < perFile; j++) {
                        b.append("  ").append(line(hits.get(j).line())).append(": ").append(cut(hits.get(j).text(), 300))
                                .append('\n');
                    }
                }
                if (results.size() > limitFiles) {
                    b.append("… and ").append(n(results.size() - limitFiles)).append(" more files.\n");
                }
                if (hidden > 0) {
                    b.append("(").append(hidden).append(" files that hold keys or passwords are left out.)\n");
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    /** A path, not a name: has a separator other than the one in "service/log", or starts like a path. */
    static boolean looksLikePath(String s) {
        return s.startsWith("/") || s.startsWith("~") || s.startsWith(".") || s.contains("\\") || s.matches("^[A-Za-z]:.*");
    }

    // ---------------------------------------------------------------- JVM files

    static final Tool ANALYZE_GC = new Def("analyze_gc", "Explain a GC log",
            "Explains a Java GC log (Java 8 to 25; G1, Parallel, Serial, CMS, ZGC, Shenandoah): how much of the "
                    + "time the program stood still, the longest pauses and why, allocation, whether memory after "
                    + "GC keeps growing (a leak), and what went wrong with what to try.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = w.log(a.string("log"));
                LogDocument doc = log.document();
                GcReport r = w.await(w.handleOf(log) + ":gc", "reading the GC log", () -> {
                    while (doc.isIndexing()) {
                        Thread.sleep(50);
                    }
                    return GcReport.of(GcLog.parse(GcLog.of(doc), null, INTERRUPTED));
                });
                if (r.log().events().isEmpty()) {
                    throw new Tool.Failure("There are no garbage collections in " + log.path().getFileName()
                            + " that Logsift can read.");
                }
                return w.finish(gcText(r), maxChars(a));
            });

    static String gcText(GcReport r) {
        GcLog g = r.log();
        StringBuilder b = new StringBuilder();
        b.append(g.collector().isEmpty() ? "GC log" : g.collector()).append(g.jvmVersion().isEmpty() ? "" : ", Java "
                + g.jvmVersion()).append(", ").append(GcReport.duration(r.seconds())).append(" of the program, ")
                .append(n(g.events().size())).append(" events.\n");
        b.append("Stood still for GC: ").append(GcReport.pct(r.pausedPercent())).append(" of the time; worst ")
                .append(GcReport.duration(r.windowSeconds())).append(": ").append(GcReport.pct(r.worstWindowPercent()))
                .append(".\n");
        GcReport.Stats p = r.pauses();
        b.append("Pauses: ").append(n(p.count())).append(", median ").append(GcReport.ms(p.p50())).append(", p95 ")
                .append(GcReport.ms(p.p95())).append(", p99 ").append(GcReport.ms(p.p99())).append(", max ")
                .append(GcReport.ms(p.max())).append(", total ").append(GcReport.ms(p.total())).append(".\n");
        if (r.fullCollections().count() > 0) {
            b.append("Full collections: ").append(n(r.fullCollections().count())).append(", longest ")
                    .append(GcReport.ms(r.fullCollections().max())).append(".\n");
        }
        if (r.stalls().count() > 0) {
            b.append("Allocation stalls: ").append(n(r.stalls().count())).append(", longest ")
                    .append(GcReport.ms(r.stalls().max())).append(".\n");
        }
        if (r.heapSize() > 0) {
            b.append("Heap: ").append(GcReport.size(r.heapSize()));
            if (r.peakAfterGc() > 0) {
                b.append(", most left after GC ").append(GcReport.size(r.peakAfterGc()));
            }
            if (r.allocationRate() > 0) {
                b.append(", allocation ").append(GcReport.size(r.allocationRate())).append("/s (peak ")
                        .append(GcReport.size(r.peakAllocationRate())).append("/s)");
            }
            b.append(".\n");
        }
        if (!r.findings().isEmpty()) {
            b.append("\nFindings:\n");
            int i = 0;
            for (GcReport.Finding f : r.findings()) {
                b.append(++i).append(". [").append(f.severity()).append("] ").append(f.title());
                if (f.detail() != null && !f.detail().isBlank()) {
                    b.append(" — ").append(f.detail().replace('\n', ' '));
                }
                if (f.line() >= 0) {
                    b.append(" (line ").append(line(f.line())).append(')');
                }
                b.append('\n');
                if (f.advice() != null && !f.advice().isBlank()) {
                    b.append("   Try: ").append(f.advice().replace('\n', ' ')).append('\n');
                }
            }
        }
        if (!r.groups().isEmpty()) {
            b.append("\nPauses by kind and cause:\n");
            List<GcReport.Group> gs = new ArrayList<>(r.groups());
            gs.sort((x, y) -> Double.compare(y.total(), x.total()));
            for (int i = 0; i < gs.size() && i < 10; i++) {
                GcReport.Group x = gs.get(i);
                b.append("- ").append(x.name()).append(x.cause().isEmpty() ? "" : " (" + x.cause() + ")").append(": ")
                        .append(n(x.count())).append(", total ").append(GcReport.ms(x.total())).append(", max ")
                        .append(GcReport.ms(x.max())).append(" (line ").append(line(x.worstLine())).append(")\n");
            }
        }
        return b.toString().stripTrailing();
    }

    static final Tool ANALYZE_THREADS = new Def("analyze_threads", "Explain thread dumps",
            "Reads the Java (jstack, kill -3) or Python thread dumps in a file or log and says what is wrong: "
                    + "deadlocks, threads waiting for a lock and who holds it, stuck or busy threads, full pools, "
                    + "growth across dumps.",
            new Tool.Schema().string("log", LOG_ARG, true)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = w.log(a.string("log"));
                LogDocument doc = log.document();
                List<ThreadDump> dumps = w.await(w.handleOf(log) + ":threads", "reading the thread dumps", () -> {
                    while (doc.isIndexing()) {
                        Thread.sleep(50);
                    }
                    return DumpParser.scan(doc, null, INTERRUPTED);
                });
                if (dumps.isEmpty()) {
                    throw new Tool.Failure("There is no thread dump in " + log.path().getFileName() + ".");
                }
                ThreadAnalysis.Report r = ThreadAnalysis.analyze(dumps);
                StringBuilder b = new StringBuilder();
                b.append(dumps.size()).append(dumps.size() == 1 ? " thread dump" : " thread dumps").append(". ")
                        .append(r.summary()).append('\n');
                int i = 0;
                for (ThreadAnalysis.Finding f : r.findings()) {
                    b.append(++i).append(". [").append(f.severity()).append("] ").append(f.title());
                    if (f.detail() != null && !f.detail().isBlank()) {
                        b.append(" — ").append(f.detail().replace('\n', ' '));
                    }
                    if (!f.keys().isEmpty()) {
                        b.append("\n   threads: ").append(cut(String.join(", ", f.keys()), 500));
                    }
                    b.append('\n');
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });
}
