package logsift.agent;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import logsift.core.LogAnalysis;
import logsift.core.LogCompare;
import logsift.core.diff.ColumnMap;
import logsift.core.diff.CsvFile;
import logsift.core.diff.DiffOptions;
import logsift.core.diff.DiffStatus;
import logsift.core.diff.Edit;
import logsift.core.diff.FolderDiff;
import logsift.core.diff.KeySuggester;
import logsift.core.diff.TableDiff;
import logsift.core.diff.TableDiffResult;
import logsift.core.diff.TextDiff;

/**
 * Comparing for agents: two files (CSV and TSV files as tables matched by a key, others line by
 * line), two folders (or zips), and a bad run of a log against a good one by kinds of message.
 * Read-only, short answers with line numbers.
 */
public final class CompareTools {
    private CompareTools() {}

    public static List<Tool> all() {
        return List.of(COMPARE_FILES, COMPARE_FOLDERS, COMPARE_LOGS);
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

    /** The most a text compared may have (both sides are read into memory), as in the window. */
    static final long MAX_TEXT_BYTES = 256L << 20;

    private static int maxChars(Tool.Args a) throws Tool.Failure {
        return (int) a.number("max_chars", Workspace.DEFAULT_MAX_CHARS, 500, Workspace.HARD_MAX_CHARS);
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static String when(long millis) {
        return millis < 0 ? "?" : WHEN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /**
     * The file a tool argument means, as read: a path (checked against the folders agents may
     * read), a name from list_logs, a log handle, or a .gz file or a file in a zip (unpacked as
     * open_log does).
     */
    static Path file(Workspace w, String arg) throws Exception {
        String s = arg.strip();
        if (s.matches("log:\\d+")) {
            return w.log(s).path();
        }
        Path p = w.pathOf(s).toAbsolutePath().normalize();
        if (Files.isRegularFile(p) && !logsift.core.Archives.isGzip(p)) {
            Path real = w.options().scope().check(p);
            if (Files.isDirectory(real)) {
                throw new Tool.Failure("That is a folder: " + p + ". Use compare_folders.");
            }
            return real;
        }
        if (Files.isDirectory(p)) {
            throw new Tool.Failure("That is a folder: " + p + ". Use compare_folders.");
        }
        return w.log(w.open(p)).path(); // a .gz, or a file in a zip: its unpacked copy
    }

    // ---------------------------------------------------------------- two files

    static final Tool COMPARE_FILES = new Def("compare_files", "Compare two files",
            "Compares two files. CSV/TSV files are compared as tables: rows matched by a key wherever they are "
                    + "(the key given, else the one Logsift suggests, else row order), with rows added, removed and "
                    + "changed (old → new per column) and line numbers. Other files are compared line by line "
                    + "(times, numbers or IDs can be masked so two runs line up). left is the older file.",
            new Tool.Schema().string("left", "The older file: a path, a log handle (log:1) or a name from list_logs.", true)
                    .string("right", "The newer file.", true)
                    .string("keys", "Tables: the key column(s), comma-separated, like order_id (default: suggested).",
                            false)
                    .bool("ignore_case", "Upper and lower case count as equal (default false).")
                    .string("tolerance", "Tables: numbers this close are equal, like 0.01 or 1% (default: exact).", false)
                    .bool("as_text", "Compare CSV files line by line instead of as tables (default false).")
                    .bool("ignore_spaces", "Text: spaces don't count (default false).")
                    .bool("mask_times", "Text: dates and times count as equal (default false).")
                    .bool("mask_numbers", "Text: every number counts as equal (default false).")
                    .bool("mask_ids", "Text: UUIDs, long hex strings and IDs with digits count as equal (default false).")
                    .integer("limit", "Most rows or changes listed (default 30).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                Path left = file(w, a.string("left"));
                Path right = file(w, a.string("right"));
                int limit = (int) a.number("limit", 30, 1, 1000);
                boolean asText = a.bool("as_text", false);
                String key = "compare:" + left + ":" + Files.getLastModifiedTime(left).toMillis() + ":" + right + ":"
                        + Files.getLastModifiedTime(right).toMillis() + ":" + a.brief();
                String text = w.await(key, "comparing " + left.getFileName() + " with " + right.getFileName(), () -> {
                    if (!asText) {
                        String table = tables(left, right, a, limit);
                        if (table != null) {
                            return table;
                        }
                    }
                    return texts(left, right, a, limit);
                });
                return w.finish(text, maxChars(a));
            });

    /** Two CSV files compared as tables; null if either isn't a CSV file. */
    private static String tables(Path left, Path right, Tool.Args a, int limit) throws Exception {
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
            ColumnMap map = ColumnMap.byName(l.header(), r.header());
            List<Integer> keys = new ArrayList<>();
            String why;
            if (a.has("keys")) {
                for (String k : a.string("keys").split(",")) {
                    if (k.isBlank()) {
                        continue;
                    }
                    int pair = map.pairNamed(k.strip());
                    if (pair < 0) {
                        List<String> names = new ArrayList<>();
                        for (int i = 0; i < map.pairs().size(); i++) {
                            names.add(map.name(i));
                        }
                        throw new Tool.Failure("No column " + k.strip() + " in both files. The columns: "
                                + String.join(", ", names) + ".");
                    }
                    keys.add(pair);
                }
                why = "the key asked for";
            } else {
                KeySuggester.Suggestion s = KeySuggester.suggest(l, r, map, INTERRUPTED);
                if (s.found()) {
                    keys.addAll(s.pairs());
                    why = "suggested: " + s.reason();
                } else {
                    why = "no column (or few together) is a key in both files: " + s.reason();
                }
            }
            DiffOptions o = DiffOptions.DEFAULT.withIgnoreCase(a.bool("ignore_case", false));
            if (a.has("tolerance")) {
                String t = a.string("tolerance").strip();
                boolean pct = t.endsWith("%");
                try {
                    double v = Double.parseDouble(pct ? t.substring(0, t.length() - 1).strip() : t);
                    o = o.withNumbers(true, v, pct);
                } catch (NumberFormatException e) {
                    throw new Tool.Failure("tolerance must be a number like 0.01 or 1%, not " + t);
                }
            }
            TableDiffResult res = TableDiff.compare(l, r, new TableDiff.Spec(map, keys, java.util.Set.of(), o), null,
                    INTERRUPTED);
            StringBuilder b = new StringBuilder();
            b.append("Tables: ").append(res.summary().strip()).append('\n');
            b.append("Rows matched ").append(res.matchedBy()).append(" (").append(why).append(").\n");
            if (res.count(DiffStatus.ADDED) + res.count(DiffStatus.REMOVED) + res.count(DiffStatus.CHANGED)
                    + res.duplicateKeys() == 0) {
                b.append("The tables are the same, row for row.");
                return b.toString();
            }
            list(b, res, DiffStatus.CHANGED, "Changed rows", limit);
            list(b, res, DiffStatus.ADDED, "Added rows (only in " + r.name() + ")", limit);
            list(b, res, DiffStatus.REMOVED, "Removed rows (only in " + l.name() + ")", limit);
            list(b, res, DiffStatus.DUPLICATE, "Rows whose key repeats (not matched)", limit);
            return b.toString().stripTrailing();
        }
    }

    private static void list(StringBuilder b, TableDiffResult res, DiffStatus status, String title, int limit)
            throws IOException {
        int total = res.count(status);
        if (total == 0) {
            return;
        }
        b.append('\n').append(title).append(": ").append(LogTools.n(total)).append('\n');
        int shown = 0;
        for (int i = 0; i < res.size() && shown < limit; i++) {
            if (res.status(i) != status) {
                continue;
            }
            shown++;
            String keyText = res.keyed() ? res.keyText(i) : null;
            switch (status) {
                case CHANGED -> {
                    b.append("- ").append(keyText != null ? "key " + keyText + ", " : "").append("line ")
                            .append(LogTools.line(res.leftLine(i))).append(" → ").append(LogTools.line(res.rightLine(i)))
                            .append(": ");
                    List<String> parts = new ArrayList<>();
                    for (int pair : res.changedPairs(i)) {
                        parts.add(res.columns().name(pair) + ": " + LogTools.cut(res.leftValue(i, pair), 120) + " → "
                                + LogTools.cut(res.rightValue(i, pair), 120));
                    }
                    b.append(String.join("; ", parts)).append('\n');
                }
                case ADDED -> b.append("+ line ").append(LogTools.line(res.rightLine(i))).append(": ")
                        .append(LogTools.cut(String.join(", ", values(res.rightValues(i))), 300)).append('\n');
                case REMOVED -> b.append("- line ").append(LogTools.line(res.leftLine(i))).append(": ")
                        .append(LogTools.cut(String.join(", ", values(res.leftValues(i))), 300)).append('\n');
                default -> {
                    long ll = res.leftRow(i) >= 0 ? res.leftLine(i) : -1;
                    long rl = res.rightRow(i) >= 0 ? res.rightLine(i) : -1;
                    b.append("‼ ").append(keyText != null ? "key " + keyText : "").append(ll >= 0 ? ", left line "
                            + LogTools.line(ll) : "").append(rl >= 0 ? ", right line " + LogTools.line(rl) : "").append('\n');
                }
            }
        }
        if (total > shown) {
            b.append("  … and ").append(LogTools.n(total - shown)).append(" more (raise limit).\n");
        }
    }

    private static List<String> values(String[] v) {
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (String s : v) {
                out.add(s == null ? "" : s);
            }
        }
        return out;
    }

    /** A text file's lines; null if it is binary. */
    private static List<String> lines(Path p) throws IOException {
        long size = Files.size(p);
        if (size > MAX_TEXT_BYTES) {
            throw new IOException(p.getFileName() + " is " + LogTools.size(size) + ": text is compared up to "
                    + LogTools.size(MAX_TEXT_BYTES));
        }
        byte[] bytes = Files.readAllBytes(p);
        int n = Math.min(bytes.length, 8192);
        Charset cs = logsift.core.Encoding.detect(bytes, n);
        if (!logsift.core.Encoding.isUtf16(cs)) {
            for (int i = 0; i < n; i++) {
                if (bytes[i] == 0) {
                    return null;
                }
            }
        }
        String s = new String(bytes, cs);
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }
        List<String> out = new ArrayList<>(List.of(s.split("\r?\n", -1)));
        if (s.endsWith("\n")) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String texts(Path left, Path right, Tool.Args a, int limit) throws Exception {
        List<String> l = lines(left);
        List<String> r = lines(right);
        if (l == null || r == null) {
            long sa = Files.size(left);
            long sb = Files.size(right);
            boolean same = sa == sb && Files.mismatch(left, right) < 0;
            return same ? "The files are the same (binary files, byte for byte)."
                    : "Binary files: they differ (" + LogTools.size(sa) + " and " + LogTools.size(sb)
                            + "); only text is compared line by line.";
        }
        TextDiff.Options o = new TextDiff.Options(a.bool("ignore_spaces", false) ? TextDiff.Spaces.ALL
                : TextDiff.Spaces.EXACT, a.bool("ignore_case", false), a.bool("mask_times", false),
                a.bool("mask_numbers", false), a.bool("mask_ids", false));
        TextDiff.Result res = TextDiff.compare(l, r, o);
        StringBuilder b = new StringBuilder();
        b.append("Text: ").append(left.getFileName()).append(" (").append(LogTools.n(l.size())).append(" lines) with ")
                .append(right.getFileName()).append(" (").append(LogTools.n(r.size())).append(" lines)");
        List<String> how = new ArrayList<>();
        if (o.spaces() != TextDiff.Spaces.EXACT) {
            how.add("spaces ignored");
        }
        if (o.ignoreCase()) {
            how.add("case ignored");
        }
        if (o.maskTimes()) {
            how.add("times masked");
        }
        if (o.maskNumbers()) {
            how.add("numbers masked");
        }
        if (o.maskIds()) {
            how.add("IDs masked");
        }
        b.append(how.isEmpty() ? "" : ", " + String.join(", ", how)).append(".\n");
        if (res.same()) {
            b.append("The texts are the same").append(how.isEmpty() ? "." : " (as compared).");
            return b.toString();
        }
        List<TextDiff.Block> changes = res.changes();
        b.append(LogTools.n(res.linesChanged())).append(" lines changed, ").append(LogTools.n(res.linesAdded()))
                .append(" added, ").append(LogTools.n(res.linesRemoved())).append(" removed, in ")
                .append(LogTools.n(changes.size())).append(changes.size() == 1 ? " place" : " places").append(":\n");
        int shown = 0;
        for (TextDiff.Block c : changes) {
            if (shown++ >= limit) {
                b.append("… and ").append(LogTools.n(changes.size() - limit)).append(" more places (raise limit).\n");
                break;
            }
            String what = c.kind() == Edit.Kind.INSERT ? "added" : c.kind() == Edit.Kind.DELETE ? "removed" : "changed";
            b.append("\n@ left ").append(range(c.l1(), c.l2())).append(", right ").append(range(c.r1(), c.r2()))
                    .append(" (").append(what).append(")\n");
            appendLines(b, "- ", l, c.l1(), c.l2());
            appendLines(b, "+ ", r, c.r1(), c.r2());
        }
        return b.toString().stripTrailing();
    }

    /** "lines 12–14", "line 12", "after line 11" for none. */
    private static String range(int from, int to) {
        if (to <= from) {
            return from == 0 ? "at the start" : "after line " + LogTools.n(from);
        }
        return to - from == 1 ? "line " + LogTools.n(from + 1) : "lines " + LogTools.n(from + 1) + "–" + LogTools.n(to);
    }

    private static void appendLines(StringBuilder b, String mark, List<String> lines, int from, int to) {
        int max = 6;
        for (int i = from; i < to && i < from + max; i++) {
            b.append(mark).append(LogTools.cut(lines.get(i), 300)).append('\n');
        }
        if (to - from > max) {
            b.append(mark).append("… ").append(LogTools.n(to - from - max)).append(" more lines\n");
        }
    }

    // ---------------------------------------------------------------- two folders

    static final Tool COMPARE_FOLDERS = new Def("compare_folders", "Compare two folders",
            "Compares two folders (or zips) file by file: which files are the same, different, or only on one "
                    + "side, with sizes and dates. Files of the same size are compared byte by byte. left is the "
                    + "older one. Then compare_files on a pair for the details.",
            new Tool.Schema().string("left", "The older folder (or zip).", true)
                    .string("right", "The newer folder (or zip).", true)
                    .bool("contents", "Compare files of the same size byte by byte (default true).")
                    .integer("limit", "Most files listed (default 50).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                Path left = w.options().scope().check(Path.of(a.string("left")).toAbsolutePath().normalize());
                Path right = w.options().scope().check(Path.of(a.string("right")).toAbsolutePath().normalize());
                for (Path p : List.of(left, right)) {
                    if (!Files.isDirectory(p) && !logsift.core.Archives.isZip(p)) {
                        throw new Tool.Failure("Not a folder or a zip: " + p + ". Use compare_files for two files.");
                    }
                }
                int limit = (int) a.number("limit", 50, 1, 2000);
                FolderDiff.Options o = new FolderDiff.Options(a.bool("contents", true), FolderDiff.Options.DEFAULT.hidden(),
                        FolderDiff.Options.DEFAULT.skip());
                FolderDiff.Result r = w.await("folders:" + left + ":" + right + ":" + o.contents(), "comparing "
                        + left.getFileName() + " with " + right.getFileName(), () -> FolderDiff.compare(left, right, o,
                        INTERRUPTED, step -> { }));
                if (r == null) {
                    throw new Tool.Failure("Stopped before the folders were compared.");
                }
                StringBuilder b = new StringBuilder();
                b.append(LogTools.n(r.files())).append(" files: ").append(LogTools.n(r.same())).append(" the same, ")
                        .append(LogTools.n(r.different())).append(" different, ").append(LogTools.n(r.leftOnly()))
                        .append(" only in ").append(left.getFileName()).append(", ").append(LogTools.n(r.rightOnly()))
                        .append(" only in ").append(right.getFileName()).append(r.capped() ? " (too many files: the first "
                        + LogTools.n(FolderDiff.MAX_FILES) + ")" : "").append(".\n");
                List<FolderDiff.Node> diff = new ArrayList<>();
                for (FolderDiff.Node n : FolderDiff.all(r.root())) {
                    if (!n.isFolder() && n.status() != null && n.status() != FolderDiff.Status.SAME) {
                        diff.add(n);
                    }
                }
                diff.sort(Comparator.comparing(FolderDiff.Node::path));
                for (int i = 0; i < diff.size() && i < limit; i++) {
                    FolderDiff.Node n = diff.get(i);
                    switch (n.status()) {
                        case DIFFERENT -> b.append("≠ ").append(n.path()).append("  ").append(LogTools.size(n.left().size()))
                                .append(", ").append(when(n.left().modified())).append(" → ")
                                .append(LogTools.size(n.right().size())).append(", ").append(when(n.right().modified()))
                                .append(n.newer() == 1 ? "  (right newer)" : n.newer() == 0 ? "  (left newer)" : "")
                                .append('\n');
                        case LEFT_ONLY -> b.append("◀ ").append(n.path()).append("  only left, ")
                                .append(LogTools.size(n.left().size())).append(", ").append(when(n.left().modified()))
                                .append('\n');
                        case RIGHT_ONLY -> b.append("▶ ").append(n.path()).append("  only right, ")
                                .append(LogTools.size(n.right().size())).append(", ").append(when(n.right().modified()))
                                .append('\n');
                        default -> { }
                    }
                }
                if (diff.size() > limit) {
                    b.append("… and ").append(LogTools.n(diff.size() - limit)).append(" more (raise limit).\n");
                }
                if (diff.isEmpty()) {
                    b.append("No differences.");
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    // ---------------------------------------------------------------- two runs of a log

    private static LogAnalysis ready(Workspace w, String arg) throws Exception {
        LogAnalysis log = w.log(arg);
        String h = w.handleOf(log);
        w.await(h + ":ready", "reading " + log.path().getFileName(), () -> log.awaitReady(INTERRUPTED));
        return log;
    }

    static final Tool COMPARE_LOGS = new Def("compare_logs", "Compare a bad run with a good one",
            "Compares two logs of the same program by kinds of message (numbers, ids and times blanked): which "
                    + "kinds are new in the bad run, gone, or much more or less frequent (per line, so logs of "
                    + "different lengths compare), with counts and the first line of each. Good for \"what is "
                    + "different about today's run?\".",
            new Tool.Schema().string("bad", "The log to explain (a handle like log:1, a path, or a name from list_logs).",
                            true)
                    .string("good", "The baseline: a run that went well.", true)
                    .integer("limit", "Most kinds listed per section (default 15).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis bad = ready(w, a.string("bad"));
                LogAnalysis good = ready(w, a.string("good"));
                int limit = (int) a.number("limit", 15, 1, 500);
                LogCompare.Result r = w.await("compare-logs:" + w.handleOf(good) + ":" + good.document().lineCount() + ":"
                        + w.handleOf(bad) + ":" + bad.document().lineCount(), "comparing the logs", () ->
                        LogCompare.compare(good.document(), good.formats() == null ? null : good.formats().entries(),
                                bad.document(), bad.formats() == null ? null : bad.formats().entries(), INTERRUPTED, null));
                if (r == null) {
                    throw new Tool.Failure("Stopped before the logs were compared.");
                }
                StringBuilder b = new StringBuilder();
                b.append("Bad run ").append(w.handleOf(bad)).append(" (").append(LogTools.n(r.entriesB()))
                        .append(" entries) against good run ").append(w.handleOf(good)).append(" (")
                        .append(LogTools.n(r.entriesA())).append(" entries):\n");
                section(b, r, LogCompare.Kind.NEW, "New in the bad run", limit,
                        Comparator.comparingLong(LogCompare.Diff::countB).reversed());
                section(b, r, LogCompare.Kind.MORE, "Much more often in the bad run", limit,
                        Comparator.comparingDouble(LogCompare.Diff::ratio).reversed());
                section(b, r, LogCompare.Kind.GONE, "Gone from the bad run", limit,
                        Comparator.comparingLong(LogCompare.Diff::countA).reversed());
                section(b, r, LogCompare.Kind.LESS, "Much less often in the bad run", limit,
                        Comparator.comparingDouble(LogCompare.Diff::ratio));
                if (r.diffs().stream().allMatch(d -> d.kind() == LogCompare.Kind.SAME)) {
                    b.append("The same kinds of message, about as often.");
                }
                return w.finish(b.toString().stripTrailing(), maxChars(a));
            });

    private static void section(StringBuilder b, LogCompare.Result r, LogCompare.Kind kind, String title, int limit,
                                Comparator<LogCompare.Diff> order) {
        List<LogCompare.Diff> ds = new ArrayList<>(r.diffs().stream().filter(d -> d.kind() == kind).toList());
        if (ds.isEmpty()) {
            return;
        }
        ds.sort(order);
        b.append('\n').append(title).append(" (").append(LogTools.n(ds.size())).append("):\n");
        for (int i = 0; i < ds.size() && i < limit; i++) {
            LogCompare.Diff d = ds.get(i);
            b.append("- ").append(LogTools.n(d.countB())).append(" × in bad, ").append(LogTools.n(d.countA()))
                    .append(" × in good: ").append(d.level() == null || d.level().isBlank() ? "" : d.level().strip() + " ")
                    .append(LogTools.cut(logsift.core.Insights.display(d.text()), 300));
            List<String> where = new ArrayList<>();
            if (d.firstB() >= 0) {
                where.add("bad line " + LogTools.line(d.firstB()));
            }
            if (d.firstA() >= 0) {
                where.add("good line " + LogTools.line(d.firstA()));
            }
            if (!where.isEmpty()) {
                b.append("  (first: ").append(String.join(", ", where)).append(')');
            }
            b.append('\n');
        }
        if (ds.size() > limit) {
            b.append("  … and ").append(LogTools.n(ds.size() - limit)).append(" more (raise limit).\n");
        }
    }
}
