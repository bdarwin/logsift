package logsift.agent;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import logsift.core.BookmarkStore;
import logsift.core.CustomFormat;
import logsift.core.EntryFormat;
import logsift.core.HitList;
import logsift.core.LineExporter;
import logsift.core.LogAnalysis;
import logsift.core.LogDocument;
import logsift.core.LogFormat;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;
import logsift.core.diff.CsvFile;
import logsift.core.diff.DiffStatus;
import logsift.core.diff.Merge3;
import logsift.core.diff.TableDiffExport;
import logsift.core.diff.TableDiffResult;
import logsift.core.diff.TextDiff;

/**
 * The tools that change something: save lines, a comparison or a merge as a new file; keep
 * bookmarks, log formats and log locations. They run only when changes are allowed (Settings →
 * Agents → Let agents change things, or logsift-mcp --allow-changes), save only new files inside
 * the folders agents may read, and never replace or remove a person's file. Each comes with a
 * reading tool where there is something to look at first (list_bookmarks, list_log_formats,
 * merge3).
 */
public final class ChangeTools {
    private ChangeTools() {}

    public static List<Tool> all() {
        return List.of(EXPORT_LINES, EXPORT_DIFF, MERGE3, SAVE_MERGE3, LIST_BOOKMARKS, SET_BOOKMARK, LIST_LOG_FORMATS,
                SAVE_LOG_FORMAT, SAVE_LOG_LOCATION);
    }

    /** A tool from its parts; {@code changes}: it changes something (off unless allowed). */
    private record Def(String name, String title, String description, Map<String, Object> inputSchema, boolean changes,
                       Body body) implements Tool {
        @Override
        public boolean readOnly() {
            return !changes;
        }

        @Override
        public String run(Args args, Workspace w) throws Exception {
            return body.run(args, w);
        }
    }

    @FunctionalInterface
    private interface Body {
        String run(Tool.Args a, Workspace w) throws Exception;
    }

    /** Said first in each changing tool's description. */
    private static final String CHANGES = "CHANGES SOMETHING: only offered when the person turned on \"Let agents "
            + "change things\" (Settings → Agents in Logsift) or the host started Logsift's tools with "
            + "--allow-changes. ";

    private static final String NEW_FILE = "A new file: the full path of a file that is not there yet, in a folder "
            + "that exists and that agents may read, like /home/me/logs/errors-only.log. Logsift never replaces "
            + "or removes a file: if the name is taken, choose another.";

    // ---------------------------------------------------------------- export lines

    static final Tool EXPORT_LINES = new Def("export_lines", "Save lines of a log to a new file",
            CHANGES + "Saves chosen lines of a log, as they are in the file (same bytes, line ends and encoding), to a "
                    + "NEW file: all lines, a range of lines (from_line, to_line), and/or only the lines that match a "
                    + "search (query, like search). Use it when the person wants a smaller file to keep, send or "
                    + "open elsewhere: \"save the errors of last night to a file\". To only look at lines, use "
                    + "search or read_lines instead: they change nothing.\n"
                    + "Examples: {log: \"log:1\", save_to: \"/home/me/logs/app-errors.log\", query: \"ERROR\"}; "
                    + "{log: \"/var/log/app.log\", save_to: \"/tmp/part.log\", from_line: 1200, to_line: 1800}; "
                    + "{log: \"log:2\", save_to: \"/home/me/logs/timeouts.log\", query: \"timed? out\", regex: true, "
                    + "whole_entries: true}.\n"
                    + "Answer: the new file's path, how many lines and bytes were written, and what was chosen. The "
                    + "saved file can be opened with open_log.\n"
                    + "Mistakes: save_to an existing file (refused: pick a new name); save_to the log itself "
                    + "(refused); a folder that doesn't exist (refused: use one that does).",
            new Tool.Schema().string("log", LogTools.LOG_ARG, true)
                    .string("save_to", NEW_FILE, true)
                    .integer("from_line", "First line to save (1-based, default 1).", false)
                    .integer("to_line", "Last line to save (1-based, included; default: the end).", false)
                    .string("query", "Only lines that have this text (plain text unless regex is true). Leave out "
                            + "to save every line in the range.", false)
                    .bool("regex", "query is a Java regular expression (default false).")
                    .bool("match_case", "Upper and lower case must match (default false).")
                    .bool("whole_word", "query matches whole words only (default false).")
                    .bool("whole_entries", "With query: save each matching line with the rest of its entry (the "
                            + "stack trace or continuation lines after it, up to the next entry), as the window "
                            + "shows entries (default false: only the matching lines).")
                    .build(), true,
            (a, w) -> {
                LogAnalysis log = LogTools.ready(a, w);
                LogDocument doc = log.document();
                String key = "export_lines:" + a.brief();
                Path out = newFile(w, key, a.string("save_to"), "save lines to");
                if (out.equals(log.path().toAbsolutePath().normalize())) {
                    throw new Tool.Failure("save_to is the log itself: choose a new file.");
                }
                long count = doc.lineCount();
                long from = a.number("from_line", 1, 1, Long.MAX_VALUE) - 1;
                long to = Math.min(count, a.number("to_line", count, 1, Long.MAX_VALUE)); // exclusive, 0-based
                if (from >= count) {
                    throw new Tool.Failure("from_line is after the end: the log has " + LogTools.n(count) + " lines.");
                }
                if (to <= from) {
                    throw new Tool.Failure("to_line must be at or after from_line.");
                }
                String what;
                LineExporter.Runs runs;
                if (a.has("query")) {
                    HitList hits = search(a, w, log);
                    EntryFormat entries = a.bool("whole_entries", false) && log.formats() != null
                            ? log.formats().entries() : null;
                    what = "the lines matching “" + a.string("query") + "”" + (entries != null ? " with their entries" : "");
                    int first = hits.firstAtOrAfter(from);
                    if (first >= hits.size() || hits.get(first) >= to) {
                        return "No lines to save: none of " + what + " in lines " + LogTools.line(from) + "–"
                                + LogTools.n(to) + ". Nothing was written.";
                    }
                    runs = hitRuns(doc, hits, from, to, entries);
                } else {
                    long f = from;
                    long t = to;
                    runs = o -> o.run(f, t);
                    what = "every line";
                }
                long written = w.await(key, "saving lines to " + out.getFileName(),
                        () -> LineExporter.export(doc, runs, out, null, LogTools.INTERRUPTED, true));
                return "Saved " + LogTools.n(written) + (written == 1 ? " line" : " lines") + " ("
                        + LogTools.size(Files.size(out)) + ") to " + out + ": " + what + " in lines "
                        + LogTools.line(from) + "–" + LogTools.n(to) + " of " + log.path().getFileName()
                        + ". Open it with open_log to read it.";
            });

    /** The new file an argument names, checked (changes allowed, in the folders, not there yet). */
    private static Path newFile(Workspace w, String arg, String what) throws Exception {
        return w.checkNew(Workspace.path(arg), what);
    }

    /**
     * The same, unless {@code key}'s work is under way or done already (the agent called again after
     * "still working"): then the file it is writing, without asking again.
     */
    private static Path newFile(Workspace w, String key, String arg, String what) throws Exception {
        if (w.hasWork(key)) {
            return Workspace.path(arg).toAbsolutePath().normalize();
        }
        return newFile(w, arg, what);
    }

    /** The lines of {@code log} matching the call's query, as search finds them. */
    private static HitList search(Tool.Args a, Workspace w, LogAnalysis log) throws Exception {
        SearchQuery q = new SearchQuery(a.string("query"), a.bool("match_case", false), a.bool("regex", false),
                a.bool("whole_word", false));
        try {
            q.toPattern();
        } catch (RuntimeException e) {
            throw new Tool.Failure("Not a valid regular expression: " + e.getMessage());
        }
        LogDocument doc = log.document();
        HitList hits = w.await(w.handleOf(log) + ":search:" + q, "searching for “" + q.text() + "”", () -> {
            SearchSession s = new SearchSession(doc, q).start();
            try {
                while (!s.awaitIdle(200)) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                }
                if (s.isLimitReached()) {
                    throw new Tool.Failure("Too many lines match “" + q.text() + "” to save them all: narrow the "
                            + "search, or save a range of lines without query.");
                }
                return s.hits();
            } finally {
                s.close();
            }
        });
        if (hits == null) {
            throw new Tool.Failure("Stopped before the search finished.");
        }
        return hits;
    }

    /** The most lines an entry takes after its first (a runaway "entry" is cut there). */
    static final int MAX_ENTRY_LINES = 500;

    /** Runs of the hits in {@code [from, to)}, each grown to its whole entry when {@code entries} is given. */
    static LineExporter.Runs hitRuns(LogDocument doc, HitList hits, long from, long to, EntryFormat entries) {
        return out -> {
            long runStart = -1;
            long runEnd = -1;
            int first = hits.firstAtOrAfter(from);
            for (int i = first; i < hits.size(); i++) {
                long l = hits.get(i);
                if (l >= to) {
                    break;
                }
                long end = l + 1;
                if (entries != null) {
                    while (end < to && end - l <= MAX_ENTRY_LINES && !entries.startsEntry(doc.line(end))) {
                        end++;
                    }
                }
                if (runStart >= 0 && l <= runEnd) {
                    runEnd = Math.max(runEnd, end);
                } else {
                    if (runStart >= 0) {
                        out.run(runStart, runEnd);
                    }
                    runStart = l;
                    runEnd = end;
                }
            }
            if (runStart >= 0) {
                out.run(runStart, runEnd);
            }
        };
    }

    // ---------------------------------------------------------------- export a comparison

    static final Tool EXPORT_DIFF = new Def("export_diff", "Save a comparison to a new file",
            CHANGES + "Compares two files as compare_files does and saves the result to a NEW file. Two CSV/TSV "
                    + "tables give a CSV like the window's export: a row per difference with its status (changed, "
                    + "added, removed, key repeats), its line in each file, the key, and each column's left and right "
                    + "value. Other files (or as_text) give a unified diff (---/+++/@@ hunks, - and + lines). Use it "
                    + "when the person wants the differences as a file; to read them, use compare_files.\n"
                    + "Examples: {left: \"/data/prices-old.csv\", right: \"/data/prices-new.csv\", keys: \"sku\", "
                    + "save_to: \"/data/prices-changes.csv\"}; {left: \"/etc/app/a.conf\", right: \"/etc/app/b.conf\", "
                    + "save_to: \"/home/me/conf.diff\"}.\n"
                    + "Answer: the new file's path and how many rows or changes it holds.\n"
                    + "Mistakes: save_to an existing file (refused); keys naming a column that isn't in both tables "
                    + "(the answer lists the columns).",
            new Tool.Schema().string("left", "The older file: a path, a log handle (log:1) or a name from list_logs.", true)
                    .string("right", "The newer file.", true)
                    .string("save_to", NEW_FILE, true)
                    .choice("rows", "Tables: which rows to save: differences (default) or everything (the same rows "
                            + "too).", false, "differences", "everything")
                    .string("keys", "Tables: the key column(s), comma-separated, like order_id (default: suggested).",
                            false)
                    .bool("ignore_case", "Upper and lower case count as equal (default false).")
                    .string("tolerance", "Tables: numbers this close are equal, like 0.01 or 1% (default: exact).", false)
                    .string("where", "Tables: only rows that pass, conditions joined with ' and ': region = EU, "
                            + "amount > 100 (default: every row).", false)
                    .bool("as_text", "Compare CSV files line by line instead of as tables (default false).")
                    .bool("ignore_spaces", "Text: spaces don't count (default false).")
                    .bool("mask_times", "Text: dates and times count as equal (default false).")
                    .bool("mask_numbers", "Text: every number counts as equal (default false).")
                    .bool("mask_ids", "Text: UUIDs, long hex strings and IDs with digits count as equal (default false).")
                    .build(), true,
            (a, w) -> {
                Path left = CompareTools.file(w, a.string("left"));
                Path right = CompareTools.file(w, a.string("right"));
                String key = "export_diff:" + a.brief();
                Path out = newFile(w, key, a.string("save_to"), "save the comparison to");
                if (out.equals(left) || out.equals(right)) {
                    throw new Tool.Failure("save_to is one of the files compared: choose a new file.");
                }
                return w.await(key, "comparing " + left.getFileName() + " with " + right.getFileName(),
                        () -> {
                            if (!a.bool("as_text", false)) {
                                String t = tableExport(left, right, out, a);
                                if (t != null) {
                                    return t;
                                }
                            }
                            return textExport(left, right, out, a);
                        });
            });

    /** Two tables' differences saved as CSV; null if either isn't a table. */
    private static String tableExport(Path left, Path right, Path out, Tool.Args a) throws Exception {
        CsvFile l;
        try {
            l = CsvFile.open(left, null);
        } catch (CsvFile.NotCsvException e) {
            return null;
        }
        CsvFile r;
        try {
            r = CsvFile.open(right, null);
        } catch (CsvFile.NotCsvException e) {
            l.close();
            return null;
        }
        try (l; r) {
            String[] why = new String[1];
            TableDiffResult res = CompareTools.tableResult(l, r, a, why);
            boolean everything = "everything".equals(a.string("rows", "differences"));
            int[] items = everything ? res.filter(null, -1)
                    : res.filter(java.util.EnumSet.of(DiffStatus.CHANGED, DiffStatus.ADDED, DiffStatus.REMOVED,
                            DiffStatus.DUPLICATE), -1);
            try (Writer wr = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                TableDiffExport.writeCsv(res, items, wr, LogTools.INTERRUPTED);
            }
            return "Saved " + LogTools.n(items.length) + (everything ? " rows" : " differing rows") + " ("
                    + LogTools.size(Files.size(out)) + ") to " + out + ". " + res.summary().strip()
                    + " Rows matched " + res.matchedBy() + " (" + why[0] + "). Columns: status, left line, right line, "
                    + "the key, then each column's (left) and (right) value.";
        }
    }

    /** Two texts' differences saved as a unified diff. */
    private static String textExport(Path left, Path right, Path out, Tool.Args a) throws Exception {
        List<String> l = CompareTools.lines(left);
        List<String> r = CompareTools.lines(right);
        if (l == null || r == null) {
            throw new Tool.Failure("A binary file: only text and tables can be saved as a comparison.");
        }
        TextDiff.Options o = new TextDiff.Options(a.bool("ignore_spaces", false) ? TextDiff.Spaces.ALL
                : TextDiff.Spaces.EXACT, a.bool("ignore_case", false), a.bool("mask_times", false),
                a.bool("mask_numbers", false), a.bool("mask_ids", false));
        TextDiff.Result res = TextDiff.compare(l, r, o);
        List<TextDiff.Block> changes = res.changes();
        try (Writer wr = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            wr.write("--- " + left + "\n");
            wr.write("+++ " + right + "\n");
            for (TextDiff.Block c : changes) {
                wr.write("@@ -" + hunk(c.l1(), c.l2()) + " +" + hunk(c.r1(), c.r2()) + " @@\n");
                for (int i = c.l1(); i < c.l2(); i++) {
                    wr.write("-" + l.get(i) + "\n");
                }
                for (int i = c.r1(); i < c.r2(); i++) {
                    wr.write("+" + r.get(i) + "\n");
                }
            }
        }
        return "Saved " + LogTools.n(changes.size()) + (changes.size() == 1 ? " change" : " changes") + " ("
                + LogTools.n(res.linesChanged()) + " lines changed, " + LogTools.n(res.linesAdded()) + " added, "
                + LogTools.n(res.linesRemoved()) + " removed) to " + out + " as a unified diff."
                + (changes.isEmpty() ? " The texts are the same: the file has only the two header lines." : "");
    }

    /** A unified diff's range: start,count (start is the line before, for none). */
    private static String hunk(int from, int to) {
        int n = to - from;
        return (n == 0 ? from : from + 1) + "," + n;
    }

    // ---------------------------------------------------------------- three-way merge

    private static final String MERGE_ARGS = "base is the common ancestor; yours and theirs are the two versions "
            + "changed from it (git mergetool calls them BASE, LOCAL and REMOTE).";

    static final Tool MERGE3 = new Def("merge3", "Show a three-way merge",
            "Merges two versions of a text file that were both changed from the same original (base), as git merge "
                    + "does, and shows the result's conflicts: places where yours and theirs changed the same lines "
                    + "differently. Changes made on one side only are taken by themselves. Reads only: to save a "
                    + "merged file use save_merge3 (with the conflict numbers this tool gives). "
                    + MERGE_ARGS + "\n"
                    + "Example: {base: \"/tmp/settings.base.conf\", yours: \"/tmp/settings.mine.conf\", theirs: "
                    + "\"/tmp/settings.theirs.conf\"}.\n"
                    + "Answer: how many changes and conflicts; each conflict numbered (1, 2, …) with its base lines, "
                    + "your lines and their lines.",
            new Tool.Schema().string("base", "The common original: a path or a name from list_logs.", true)
                    .string("yours", "Your version (git's LOCAL).", true)
                    .string("theirs", "Their version (git's REMOTE).", true)
                    .integer("limit", "Most conflicts shown (default 20).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(), false,
            (a, w) -> {
                Texts t = texts(w, a);
                Merge3.Result m = Merge3.merge(t.base, t.yours, t.theirs, TextDiff.Options.DEFAULT);
                int limit = (int) a.number("limit", 20, 1, 500);
                StringBuilder b = new StringBuilder();
                b.append(summary(m, t)).append('\n');
                int n = 0;
                for (Merge3.Chunk c : m.chunks()) {
                    if (c.kind() != Merge3.Kind.CONFLICT) {
                        continue;
                    }
                    n++;
                    if (n > limit) {
                        b.append("… and ").append(LogTools.n(m.conflicts() - limit)).append(" more conflicts (raise limit).\n");
                        break;
                    }
                    b.append("\nConflict ").append(n).append(": base ").append(range(c.b1(), c.b2())).append(", yours ")
                            .append(range(c.o1(), c.o2())).append(", theirs ").append(range(c.t1(), c.t2())).append('\n');
                    lines(b, "  base   | ", t.base, c.b1(), c.b2());
                    lines(b, "  yours  | ", t.yours, c.o1(), c.o2());
                    lines(b, "  theirs | ", t.theirs, c.t1(), c.t2());
                }
                if (m.conflicts() > 0) {
                    b.append("\nTo save: save_merge3 with prefer (yours, theirs, both, base or markers) for every "
                            + "conflict, or choices like \"1=yours,2=theirs\" for each.");
                } else if (m.changes() > 0) {
                    b.append("No conflicts: save_merge3 saves the merged file as it is.");
                }
                return w.finish(b.toString().stripTrailing(), LogTools.maxChars(a));
            });

    static final Tool SAVE_MERGE3 = new Def("save_merge3", "Save a three-way merge to a new file",
            CHANGES + "Merges two versions of a text file changed from the same original (as merge3 shows it) and "
                    + "saves the result to a NEW file. Changes made on one side are taken; each conflict is resolved "
                    + "by prefer (for all of them) or by choices (per conflict number from merge3). Call merge3 first "
                    + "to see the conflicts and their numbers. " + MERGE_ARGS + "\n"
                    + "Choices for a conflict: yours, theirs, both (yours then theirs), both_theirs_first, base (keep "
                    + "the original), markers (leave git's <<<<<<< ======= >>>>>>> markers for a person to resolve).\n"
                    + "Examples: {base: …, yours: …, theirs: …, save_to: \"/tmp/settings.merged.conf\", prefer: "
                    + "\"theirs\"}; {…, save_to: \"/tmp/merged.conf\", choices: \"1=yours,2=both\", prefer: \"markers\"}.\n"
                    + "Answer: the new file's path, its lines, and what was chosen for each conflict.\n"
                    + "Mistakes: save_to git's MERGED file or any existing file (refused: Logsift never replaces a "
                    + "file; save next to it and tell the person); a conflict number merge3 didn't give.",
            new Tool.Schema().string("base", "The common original.", true)
                    .string("yours", "Your version (git's LOCAL).", true)
                    .string("theirs", "Their version (git's REMOTE).", true)
                    .string("save_to", NEW_FILE, true)
                    .choice("prefer", "For every conflict not in choices (default markers).", false, "yours", "theirs",
                            "both", "both_theirs_first", "base", "markers")
                    .string("choices", "Per conflict, by merge3's numbers: \"1=yours,2=theirs,3=both\".", false)
                    .build(), true,
            (a, w) -> {
                Texts t = texts(w, a);
                Path out = newFile(w, a.string("save_to"), "save the merge to");
                Merge3.Result m = Merge3.merge(t.base, t.yours, t.theirs, TextDiff.Options.DEFAULT);
                Merge3.Choice all = choice(a.string("prefer", "markers"), "prefer");
                Map<Integer, Merge3.Choice> per = new LinkedHashMap<>();
                if (a.has("choices")) {
                    for (String part : a.string("choices").split(",")) {
                        if (part.isBlank()) {
                            continue;
                        }
                        String[] kv = part.split("=", 2);
                        if (kv.length != 2) {
                            throw new Tool.Failure("choices are like 1=yours,2=theirs, not " + part.strip());
                        }
                        int k;
                        try {
                            k = Integer.parseInt(kv[0].strip());
                        } catch (NumberFormatException e) {
                            throw new Tool.Failure("choices are like 1=yours,2=theirs: " + kv[0].strip()
                                    + " isn't a conflict number");
                        }
                        if (k < 1 || k > m.conflicts()) {
                            throw new Tool.Failure("There is no conflict " + k + ": the merge has "
                                    + m.conflicts() + (m.conflicts() == 1 ? " conflict" : " conflicts")
                                    + " (merge3 numbers them).");
                        }
                        per.put(k, choice(kv[1].strip(), "choices"));
                    }
                }
                List<Merge3.Choice> choices = new ArrayList<>();
                List<String> said = new ArrayList<>();
                int n = 0;
                int markers = 0;
                for (Merge3.Chunk c : m.chunks()) {
                    if (c.kind() == Merge3.Kind.CONFLICT) {
                        n++;
                        Merge3.Choice ch = per.getOrDefault(n, all);
                        choices.add(ch);
                        said.add(n + ": " + name(ch));
                        if (ch == Merge3.Choice.NONE) {
                            markers++;
                        }
                    } else {
                        choices.add(m.automatic(c));
                    }
                }
                List<String> merged = m.withMarkers(choices, null, "yours (" + t.yoursName + ")",
                        "theirs (" + t.theirsName + ")");
                String nl = t.crlf ? "\r\n" : "\n";
                StringBuilder text = new StringBuilder();
                for (String l : merged) {
                    text.append(l).append(nl);
                }
                Files.write(out, text.toString().getBytes(t.charset), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                StringBuilder b = new StringBuilder("Saved the merge (" + LogTools.n(merged.size()) + " lines, "
                        + t.charset.name() + (t.crlf ? ", CRLF" : "") + ") to " + out + ". " + summary(m, t));
                if (!said.isEmpty()) {
                    b.append(" Conflicts: ").append(String.join("; ", said)).append('.');
                }
                if (markers > 0) {
                    b.append(' ').append(markers).append(markers == 1 ? " conflict keeps" : " conflicts keep")
                            .append(" git's markers for a person to resolve.");
                }
                return b.toString();
            });

    /** The three texts of a merge, with yours' encoding and line ends (the result is written like it). */
    private record Texts(List<String> base, List<String> yours, List<String> theirs, String yoursName,
                         String theirsName, Charset charset, boolean crlf) {}

    private static Texts texts(Workspace w, Tool.Args a) throws Exception {
        Path base = CompareTools.file(w, a.string("base"));
        Path yours = CompareTools.file(w, a.string("yours"));
        Path theirs = CompareTools.file(w, a.string("theirs"));
        List<String> b = CompareTools.lines(base);
        List<String> y = CompareTools.lines(yours);
        List<String> t = CompareTools.lines(theirs);
        if (b == null || y == null || t == null) {
            throw new Tool.Failure("A binary file: only text files can be merged.");
        }
        byte[] head = readHead(yours);
        Charset cs = logsift.core.Encoding.detect(head, head.length);
        String s = new String(head, cs);
        return new Texts(b, y, t, yours.getFileName().toString(), theirs.getFileName().toString(), cs,
                s.contains("\r\n"));
    }

    private static byte[] readHead(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            return in.readNBytes(65536);
        }
    }

    private static String summary(Merge3.Result m, Texts t) {
        long changes = m.changes();
        long conflicts = m.conflicts();
        return "Merging " + t.yoursName + " (yours) and " + t.theirsName + " (theirs): " + LogTools.n(changes)
                + (changes == 1 ? " change, " : " changes, ") + LogTools.n(conflicts)
                + (conflicts == 1 ? " conflict." : " conflicts.");
    }

    private static Merge3.Choice choice(String s, String arg) throws Tool.Failure {
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "yours", "ours", "mine", "local" -> Merge3.Choice.OURS;
            case "theirs", "remote" -> Merge3.Choice.THEIRS;
            case "both", "yours_then_theirs" -> Merge3.Choice.OURS_THEN_THEIRS;
            case "both_theirs_first", "theirs_then_yours" -> Merge3.Choice.THEIRS_THEN_OURS;
            case "base", "original" -> Merge3.Choice.BASE;
            case "markers", "none" -> Merge3.Choice.NONE;
            default -> throw new Tool.Failure(arg + ": yours, theirs, both, both_theirs_first, base or markers, not " + s);
        };
    }

    private static String name(Merge3.Choice c) {
        return switch (c) {
            case OURS -> "yours";
            case THEIRS -> "theirs";
            case OURS_THEN_THEIRS -> "both (yours first)";
            case THEIRS_THEN_OURS -> "both (theirs first)";
            case BASE -> "the original";
            case NONE -> "markers";
            case EDITED -> "edited";
        };
    }

    private static String range(int from, int to) {
        if (to <= from) {
            return from == 0 ? "none (at the start)" : "none (after line " + LogTools.n(from) + ")";
        }
        return to - from == 1 ? "line " + LogTools.n(from + 1) : "lines " + LogTools.n(from + 1) + "–" + LogTools.n(to);
    }

    private static void lines(StringBuilder b, String mark, List<String> lines, int from, int to) {
        int max = 8;
        for (int i = from; i < to && i < from + max; i++) {
            b.append(mark).append(LogTools.cut(lines.get(i), 240)).append('\n');
        }
        if (to - from > max) {
            b.append(mark).append("… ").append(LogTools.n(to - from - max)).append(" more lines\n");
        }
        if (to <= from) {
            b.append(mark).append("(nothing)\n");
        }
    }

    // ---------------------------------------------------------------- bookmarks

    /** The path the window keeps a log's bookmarks under, and its size as the window counts it. */
    private record Marked(Path key, long size) {}

    private static Marked marked(Workspace w, LogAnalysis log) {
        Path shown = w.shownPath(w.handleOf(log));
        Path key = (shown != null ? shown : log.path()).toAbsolutePath().normalize();
        long size;
        try {
            size = Files.size(key);
        } catch (IOException | RuntimeException e) {
            size = log.document().fileSize();
        }
        return new Marked(key, size);
    }

    static final Tool LIST_BOOKMARKS = new Def("list_bookmarks", "List bookmarks",
            "Lists the bookmarks the person (or an agent) set in a log: each bookmarked line's number, its note and "
                    + "its text. Without log, lists the logs that have bookmarks (inside the folders agents may read). "
                    + "Bookmarks are kept per file by Logsift's window; set_bookmark adds or removes one. Reads only.\n"
                    + "Examples: {log: \"log:1\"}; {} (which logs have bookmarks).",
            new Tool.Schema().string("log", LogTools.LOG_ARG + " Leave out to list the logs with bookmarks.", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(), false,
            (a, w) -> {
                Path home = w.logsiftHome();
                if (!a.has("log")) {
                    List<BookmarkStore.Marked> all = BookmarkStore.all(home);
                    StringBuilder b = new StringBuilder();
                    int shown = 0;
                    for (BookmarkStore.Marked m : all) {
                        if (!w.options().scope().allows(m.log())) {
                            continue;
                        }
                        shown++;
                        b.append("- ").append(m.log()).append(": ").append(m.count())
                                .append(m.count() == 1 ? " bookmark" : " bookmarks").append('\n');
                    }
                    if (shown == 0) {
                        return "No log has bookmarks yet (inside the folders agents may read).";
                    }
                    return w.finish("Logs with bookmarks:\n" + b.toString().stripTrailing()
                            + "\nlist_bookmarks with log= shows a log's bookmarks.", LogTools.maxChars(a));
                }
                LogAnalysis log = LogTools.ready(a, w);
                Marked k = marked(w, log);
                TreeMap<Long, String> marks = BookmarkStore.load(home, k.key(), k.size());
                if (marks.isEmpty()) {
                    return "No bookmarks in " + k.key().getFileName() + ".";
                }
                LogDocument doc = log.document();
                StringBuilder b = new StringBuilder(LogTools.n(marks.size()) + (marks.size() == 1 ? " bookmark" : " bookmarks")
                        + " in " + k.key().getFileName() + ":\n");
                for (Map.Entry<Long, String> e : marks.entrySet()) {
                    long l = e.getKey();
                    b.append(LogTools.line(l)).append(e.getValue().isEmpty() ? "" : " [" + e.getValue() + "]").append(": ")
                            .append(l < doc.lineCount() ? LogTools.cut(doc.line(l), 300) : "(after the end of the log)")
                            .append('\n');
                }
                return w.finish(b.toString().stripTrailing(), LogTools.maxChars(a));
            });

    static final Tool SET_BOOKMARK = new Def("set_bookmark", "Add or remove a bookmark",
            CHANGES + "Bookmarks a line of a log, with an optional note, so the person finds it again in Logsift's "
                    + "window (Bookmarks panel, F2 to go from one to the next); or removes a bookmark (remove: true). "
                    + "Use it to mark what you found for the person: the first error, where a request failed. A note "
                    + "on a line that is bookmarked already replaces its note. Bookmarks are Logsift's own record: "
                    + "the log file itself is not changed.\n"
                    + "Examples: {log: \"log:1\", line: 4182, note: \"first timeout\"}; {log: \"log:1\", line: 4182, "
                    + "remove: true}.\n"
                    + "Answer: what was done and the log's bookmarks now. A log open in the window shows a new "
                    + "bookmark once it is opened again.",
            new Tool.Schema().string("log", LogTools.LOG_ARG, true)
                    .integer("line", "The line (1-based, as search and read_lines give).", true)
                    .string("note", "A few words about the line (optional).", false)
                    .bool("remove", "Remove the bookmark on that line instead (default false).")
                    .build(), true,
            (a, w) -> {
                LogAnalysis log = LogTools.ready(a, w);
                long count = log.document().lineCount();
                long line = a.number("line", 0, 1, Long.MAX_VALUE) - 1;
                if (line >= count) {
                    throw new Tool.Failure("line is after the end: " + log.path().getFileName() + " has "
                            + LogTools.n(count) + " lines.");
                }
                boolean remove = a.bool("remove", false);
                Marked k = marked(w, log);
                Path home = w.logsiftHome();
                TreeMap<Long, String> marks = BookmarkStore.load(home, k.key(), k.size());
                String what;
                if (remove) {
                    if (!marks.containsKey(line)) {
                        return "Line " + LogTools.line(line) + " has no bookmark: nothing to remove. "
                                + (marks.isEmpty() ? "The log has none." : "list_bookmarks shows them.");
                    }
                    w.checkChange("remove the bookmark at line " + LogTools.line(line) + " of " + k.key().getFileName());
                    marks.remove(line);
                    what = "Removed the bookmark at line " + LogTools.line(line);
                } else {
                    String note = a.string("note", "").strip().replace('\n', ' ');
                    w.checkChange("bookmark line " + LogTools.line(line) + " of " + k.key().getFileName()
                            + (note.isEmpty() ? "" : " (" + note + ")"));
                    boolean had = marks.containsKey(line);
                    marks.put(line, note);
                    what = (had ? "Updated the bookmark at line " : "Bookmarked line ") + LogTools.line(line)
                            + (note.isEmpty() ? "" : " [" + note + "]");
                }
                BookmarkStore.save(home, k.key(), k.size(), marks);
                return what + " in " + k.key().getFileName() + ". It has " + LogTools.n(marks.size())
                        + (marks.size() == 1 ? " bookmark" : " bookmarks")
                        + (marks.isEmpty() ? "." : ": lines " + String.join(", ",
                                marks.keySet().stream().map(LogTools::line).toList()) + ".");
            });

    // ---------------------------------------------------------------- log formats

    static final Tool LIST_LOG_FORMATS = new Def("list_log_formats", "List log formats",
            "Lists the log formats Logsift knows: the built-in ones and the person's own (custom) ones, each with its "
                    + "columns; for custom ones also the pattern, time format and the file names it is used for. "
                    + "A log's columns (for query, pivot, metrics) come from its format: open_log says which one it "
                    + "got. Use it before save_log_format, to see what exists. Reads only.",
            new Tool.Schema().bool("builtin", "Also list the built-in formats (default true).")
                    .integer("max_chars", "Longest answer (default 24000).", false).build(), false,
            (a, w) -> {
                List<CustomFormat> mine = CustomFormat.load(formatsFile(w));
                StringBuilder b = new StringBuilder();
                if (mine.isEmpty()) {
                    b.append("No custom log formats yet (save_log_format adds one).\n");
                } else {
                    b.append("Custom formats (").append(mine.size()).append("):\n");
                    for (CustomFormat f : mine) {
                        b.append("- ").append(f.name()).append(": pattern ").append(f.pattern());
                        if (!f.timePattern().isEmpty()) {
                            b.append(", times written ").append(f.timePattern());
                        }
                        if (!f.files().isEmpty()) {
                            b.append(", always for ").append(f.files());
                        }
                        try {
                            b.append("; columns: ").append(String.join(", ", f.compile().columns()));
                        } catch (IllegalArgumentException e) {
                            b.append("; can't be used: ").append(e.getMessage());
                        }
                        b.append('\n');
                    }
                }
                if (a.bool("builtin", true)) {
                    b.append("\nBuilt-in formats (found by themselves; CSV, TSV and JSON lines too):\n");
                    for (LogFormat f : LogFormat.presets()) {
                        b.append("- ").append(f.name()).append(": ").append(String.join(", ", f.columns())).append('\n');
                    }
                }
                return w.finish(b.toString().stripTrailing(), LogTools.maxChars(a));
            });

    static final Tool SAVE_LOG_FORMAT = new Def("save_log_format", "Add or change a log format",
            CHANGES + "Adds a custom log format (or changes the one with that name), so Logsift splits such logs into "
                    + "columns (time, level, thread, logger, message, your own) for query, pivot, metrics and the "
                    + "window. Use it when open_log says a log's format is unknown or its columns are wrong. The "
                    + "format is tried on the sample lines first and only saved when at least one matches; the "
                    + "answer shows how each sample splits. Logs opened afterwards use it.\n"
                    + "pattern: the layout of a line, {name} for a column and everything else as written; any run "
                    + "of spaces matches one or more spaces. {time} takes a timestamp, {level} a level word, {*} "
                    + "skips a part, any other {name} takes everything up to the next character of the pattern, "
                    + "and the last column takes the rest of the line. Or a Java regex with named groups "
                    + "(?<time>…)(?<level>…)(?<message>…).\n"
                    + "Example: line \"2026-11-20 10:00:01,123 [main] ERROR c.a.Orders - timed out\" → pattern "
                    + "\"{time} [{thread}] {level} {logger} - {message}\".\n"
                    + "Example: line \"20.11.2026 10:00:01 E orders: timed out\" → pattern \"{time} {level} "
                    + "{service}: {message}\", time_pattern \"dd.MM.yyyy HH:mm:ss\".\n"
                    + "Mistakes: no sample (required); two columns with nothing between them ({level}{message}); "
                    + "{source} (taken); a time_pattern with letters that aren't Java's DateTimeFormatter's.",
            new Tool.Schema().string("name", "The format's name, shown in menus: \"Orders service\". The same name "
                            + "changes that format.", true)
                    .string("pattern", "The layout of a line, like {time} [{thread}] {level} {logger} - {message}.", true)
                    .string("sample", "One or more real lines of the log (separated by new lines) to try it on.", true)
                    .string("time_pattern", "How times are written when not a common form, like dd.MM.yyyy HH:mm:ss.SSS "
                            + "(Java DateTimeFormatter letters; default: any common timestamp).", false)
                    .string("files", "File names this format is always used for, like \"orders*.log; *.trace\" "
                            + "(default: found by itself when it fits).", false)
                    .build(), true,
            (a, w) -> {
                CustomFormat f = new CustomFormat(a.string("name"), a.string("pattern"), a.string("time_pattern", ""),
                        a.string("files", ""));
                if (f.name().isEmpty()) {
                    throw new Tool.Failure("Missing argument: name");
                }
                LogFormat compiled;
                try {
                    compiled = f.compile();
                } catch (IllegalArgumentException e) {
                    throw new Tool.Failure("The pattern can't be used: " + e.getMessage());
                }
                List<String> sample = new ArrayList<>();
                for (String l : a.string("sample").split("\r?\n")) {
                    if (!l.isBlank()) {
                        sample.add(l);
                    }
                }
                StringBuilder how = new StringBuilder();
                int matched = 0;
                for (String l : sample) {
                    if (compiled.matches(l)) {
                        matched++;
                        List<String> parts = new ArrayList<>();
                        for (String c : compiled.columns()) {
                            String v = compiled.value(l, c);
                            parts.add(c + "=" + (v == null ? "" : LogTools.cut(v, 80)));
                        }
                        how.append("✓ ").append(LogTools.cut(l, 160)).append("\n    ").append(String.join(" | ", parts))
                                .append('\n');
                    } else {
                        how.append("✗ ").append(LogTools.cut(l, 160)).append(" (doesn't match)\n");
                    }
                }
                if (matched == 0) {
                    throw new Tool.Failure("Not saved: no sample line matches the pattern. The regex it makes: "
                            + compiled.regex() + "\n" + how.toString().stripTrailing()
                            + "\nCheck the characters between columns ([ ], -, :) and that {time} and {level} fit.");
                }
                Path file = formatsFile(w);
                List<CustomFormat> all = new ArrayList<>(CustomFormat.load(file));
                int at = -1;
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).name().equalsIgnoreCase(f.name())) {
                        at = i;
                    }
                }
                w.checkChange((at >= 0 ? "change the log format " : "add the log format ") + f.name());
                if (at >= 0) {
                    all.set(at, f);
                } else {
                    all.add(f);
                }
                CustomFormat.save(file, all);
                w.setFormats(all);
                return (at >= 0 ? "Changed" : "Added") + " the log format “" + f.name() + "” (columns: "
                        + String.join(", ", compiled.columns()) + "). " + matched + " of " + sample.size()
                        + " sample lines match:\n" + how.toString().stripTrailing()
                        + "\nLogs opened from now on use it" + (f.files().isEmpty() ? " where it fits" : " for " + f.files())
                        + "; a log already open keeps its format until it is opened again (Logsift's window too, "
                        + "after it reads its formats again).";
            });

    private static Path formatsFile(Workspace w) {
        return w.logsiftHome().resolve("formats.properties");
    }

    // ---------------------------------------------------------------- log locations

    static final Tool SAVE_LOG_LOCATION = new Def("save_log_location", "Add or change a service's log locations",
            CHANGES + "Describes where a service's (an app's) logs are, in the person's Log Locations (logs.json in "
                    + "Logsift's folder), so list_logs, open_log (\"orders/app\"), search_folder and the skill know "
                    + "them. Adds the service, or adds to the one with that name: folders and file kinds are added "
                    + "to what is there, other fields replaced when given. Never removes a service, a folder or a "
                    + "file kind.\n"
                    + "It doesn't let agents read more: a folder outside the folders agents may read stays closed "
                    + "until the person adds it in Settings → Agents (the answer says so).\n"
                    + "Example: {name: \"orders\", description: \"Order service, Spring Boot\", folders: "
                    + "\"/var/log/orders; /srv/orders/logs\", files: \"app.log*=Application log; gc.log*=GC log\", "
                    + "time_zone: \"Europe/Berlin\", notes: \"Rotates at midnight; ERROR 1042 is harmless\"}.\n"
                    + "Answer: the service as saved, and any folder agents can't read yet.\n"
                    + "Mistakes: relative folders (give full paths); a time zone that isn't an IANA name (UTC, "
                    + "Europe/Berlin, America/New_York).",
            new Tool.Schema().string("name", "A short name agents use: \"orders\". The same name (any case) adds to "
                            + "that service.", true)
                    .string("description", "What it is, one line.", false)
                    .string("folders", "Full paths of the folders with its logs, separated by ; or new lines.", false)
                    .string("files", "What its files hold: pattern=description pairs separated by ;, like "
                            + "\"app.log*=Application log; access*.log=HTTP access log\".", false)
                    .string("time_zone", "The zone of the times in its logs: UTC, Europe/Berlin… (IANA names).", false)
                    .string("notes", "Anything an agent should know: rotation, known harmless errors.", false)
                    .build(), true,
            (a, w) -> {
                String name = a.string("name").strip();
                if (!name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]*")) {
                    throw new Tool.Failure("name is a short word, letters, digits, . _ - and spaces: \"orders\", "
                            + "\"billing-api\".");
                }
                List<String> folders = new ArrayList<>();
                for (String f : a.string("folders", "").split("[;\\n]")) {
                    String s = f.strip();
                    if (s.isEmpty()) {
                        continue;
                    }
                    Path p = Workspace.path(s);
                    if (!p.isAbsolute()) {
                        throw new Tool.Failure("folders must be full paths, like /var/log/orders, not " + s);
                    }
                    folders.add(p.toAbsolutePath().normalize().toString());
                }
                List<LogCatalog.FileKind> kinds = new ArrayList<>();
                for (String k : a.string("files", "").split(";")) {
                    if (k.isBlank()) {
                        continue;
                    }
                    String[] pd = k.split("=", 2);
                    String pattern = pd[0].strip();
                    if (pattern.isEmpty()) {
                        throw new Tool.Failure("files are pattern=description pairs like app.log*=Application log, "
                                + "not " + k.strip());
                    }
                    kinds.add(new LogCatalog.FileKind(pattern, pd.length > 1 ? pd[1].strip() : ""));
                }
                String zone = a.string("time_zone", null);
                if (zone != null && !zone.isBlank()) {
                    try {
                        zone = ZoneId.of(zone.strip()).getId();
                    } catch (RuntimeException e) {
                        throw new Tool.Failure("time_zone must be an IANA name like UTC or Europe/Berlin, not " + zone);
                    }
                }
                Path file = LogCatalog.userFile(w.logsiftHome());
                List<LogCatalog.Service> services = new ArrayList<>(Files.isRegularFile(file) ? LogCatalog.read(file)
                        : List.of());
                int at = -1;
                for (int i = 0; i < services.size(); i++) {
                    if (services.get(i).name().equalsIgnoreCase(name)) {
                        at = i;
                    }
                }
                LogCatalog.Service old = at >= 0 ? services.get(at) : null;
                List<String> allFolders = new ArrayList<>(old == null ? List.of() : old.folders());
                for (String f : folders) {
                    if (!allFolders.contains(f)) {
                        allFolders.add(f);
                    }
                }
                List<LogCatalog.FileKind> allKinds = new ArrayList<>(old == null ? List.of() : old.files());
                for (LogCatalog.FileKind k : kinds) {
                    int same = -1;
                    for (int i = 0; i < allKinds.size(); i++) {
                        if (allKinds.get(i).pattern().equals(k.pattern())) {
                            same = i;
                        }
                    }
                    if (same >= 0) {
                        allKinds.set(same, k.description().isEmpty() ? allKinds.get(same) : k);
                    } else {
                        allKinds.add(k);
                    }
                }
                LogCatalog.Service s = new LogCatalog.Service(old == null ? name : old.name(),
                        a.has("description") ? a.string("description") : old == null ? "" : old.description(),
                        allFolders, allKinds,
                        zone != null ? zone : old == null ? "" : old.timeZone(),
                        a.has("notes") ? a.string("notes") : old == null ? "" : old.notes(),
                        old == null ? List.of() : old.jvms());
                if (s.folders().isEmpty()) {
                    throw new Tool.Failure("Give at least one folder (full path) where " + name + "'s logs are.");
                }
                w.checkChange((old == null ? "add the service " : "change the service ") + s.name()
                        + " to Log Locations");
                if (old == null) {
                    services.add(s);
                } else {
                    services.set(at, s);
                }
                LogCatalog.save(file, services);
                StringBuilder b = new StringBuilder((old == null ? "Added " : "Changed ") + s.name()
                        + " in Log Locations (" + file + "):\n");
                if (!s.description().isEmpty()) {
                    b.append("- ").append(s.description()).append('\n');
                }
                List<String> closed = new ArrayList<>();
                for (String f : s.folders()) {
                    boolean readable = w.options().scope().allows(Path.of(f));
                    boolean there = Files.isDirectory(Path.of(f));
                    b.append("- Folder ").append(f).append(there ? "" : " (not on this computer now)")
                            .append(readable ? "" : " (agents can't read it yet)").append('\n');
                    if (!readable) {
                        closed.add(f);
                    }
                }
                for (LogCatalog.FileKind k : s.files()) {
                    b.append("- ").append(k.pattern()).append(k.description().isEmpty() ? "" : ": " + k.description())
                            .append('\n');
                }
                if (!s.timeZone().isEmpty()) {
                    b.append("- Times are ").append(s.timeZone()).append('\n');
                }
                if (!s.notes().isEmpty()) {
                    b.append("- Notes: ").append(s.notes()).append('\n');
                }
                if (!closed.isEmpty()) {
                    b.append("Agents can't read ").append(String.join(", ", closed)).append(": the person must add ")
                            .append(closed.size() == 1 ? "it" : "them")
                            .append(" in Logsift's Settings → Agents (Folders agents may read). Tell them.\n");
                }
                b.append("list_logs shows the service's files now.");
                return b.toString();
            });
}
