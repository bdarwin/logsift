package logsift.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import logsift.core.Archives;
import logsift.core.ColumnProfile;
import logsift.core.CsvMerger;
import logsift.core.CsvRows;
import logsift.core.EntryFormat;
import logsift.core.FolderListing;
import logsift.core.Json;
import logsift.core.LogAnalysis;
import logsift.core.LogDocument;
import logsift.core.LogFormat;
import logsift.core.LogMerger;
import logsift.core.Numbers;

/**
 * Files and tables: what a folder or zip holds, what each column of a table holds (the Profile
 * tab), CSV files merged into one table, and one entry in full (the Details pane). Read-only: a
 * merged table is written to Logsift's own temporary folder, never next to the files.
 */
public final class FileTools {
    private FileTools() {}

    public static List<Tool> all() {
        return List.of(LIST_FILES, PROFILE_COLUMNS, MERGE_TABLES, SHOW_ENTRY);
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

    private static String n(long v) {
        return LogTools.n(v);
    }

    // ---------------------------------------------------------------- list_files

    /** How much of a file is read to tell what it is. */
    static final int HEAD = 128 * 1024;
    /** At most this many files are opened to tell what they are (the rest: by name). */
    static final int MAX_LOOKED_AT = 200;

    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    static final Tool LIST_FILES = new Def("list_files", "List a folder's or zip's files",
            "Lists the files in a folder (and its subfolders) or inside a .zip (zips inside zips too), as Logsift's "
                    + "folder and zip tabs show them: each file's path, size, last change and what it is (log, table "
                    + "(CSV), GC log, thread dump, crash file, heap dump, profile, zip), with the tool to use on it. "
                    + "Use it to find which file to open before open_log, analyze_gc, analyze_crash and so on; use "
                    + "search_folder instead to find TEXT inside many files, and list_logs for the services the "
                    + "person described. Example: list_files(path=\"~/logs\", pattern=\"*.log\", sort=\"newest\"). "
                    + "A file inside a zip is opened by the path this tool shows for it, like "
                    + "\"/data/bundle.zip/logs/app.log\". .tar and .tgz files can't be listed (unpack them first).",
            new Tool.Schema().string("path", "The folder or .zip file, like ~/logs or /data/bundle.zip. A zip inside "
                            + "a zip: /data/bundle.zip/inner.zip.", true)
                    .string("pattern", "Only files whose name matches this glob, ignoring case: *.log, app*.gz, "
                            + "*.{csv,tsv}. With a / it matches the path below the folder: **/gc/*.log.", false)
                    .choice("sort", "Order: by path (default), newest first, or largest first.", false, "name",
                            "newest", "largest")
                    .integer("limit", "Most files listed (default 100, at most 2000).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                String given = a.string("path");
                Path p = Workspace.path(given);
                int limit = (int) a.number("limit", 100, 1, 2000);
                String sort = a.string("sort", "name");
                Pattern glob = a.has("pattern") ? glob(a.string("pattern")) : null;
                boolean onPath = a.has("pattern") && a.string("pattern").contains("/");
                Archives.Located in = Files.exists(p) ? null : Archives.locate(p);
                if (in != null) {
                    // a zip inside a zip: list the outer one's entries under that name
                    Path zip = w.check(in.zip(), "list the zip");
                    String prefix = in.entry().replace("/", Archives.NESTED);
                    List<Archives.Entry> all = w.await("list:" + zip + ":" + Files.getLastModifiedTime(zip).toMillis(),
                            "listing " + zip.getFileName(), () -> Archives.list(zip, LogTools.INTERRUPTED));
                    List<Archives.Entry> inside = new ArrayList<>();
                    for (Archives.Entry e : all) {
                        if (e.name().startsWith(prefix + Archives.NESTED)) {
                            inside.add(new Archives.Entry(e.name().substring(prefix.length() + Archives.NESTED.length()),
                                    e.size()));
                        }
                    }
                    if (inside.isEmpty()) {
                        throw new Tool.Failure("No zip " + in.entry() + " in " + zip + ". Call list_files(path=\""
                                + zip + "\") to see what is in it.");
                    }
                    return w.finish(zipAnswer(p, inside, glob, onPath, sort, limit), LogTools.maxChars(a));
                }
                if (!Files.exists(p)) {
                    throw new Tool.Failure("Nothing at " + p.toAbsolutePath().normalize()
                            + ". Give a folder or a .zip file that exists (list_logs names the person's log folders).");
                }
                Path real = w.check(p, Files.isDirectory(p) ? "list the folder" : "list the zip");
                String name = real.getFileName() == null ? "" : real.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(real) && Archives.isZip(real)) {
                    List<Archives.Entry> all = w.await("list:" + real + ":" + Files.getLastModifiedTime(real).toMillis(),
                            "listing " + real.getFileName(), () -> Archives.list(real, LogTools.INTERRUPTED));
                    return w.finish(zipAnswer(real, all, glob, onPath, sort, limit), LogTools.maxChars(a));
                }
                if (Files.isRegularFile(real)) {
                    if (name.endsWith(".tar") || name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
                        throw new Tool.Failure(real.getFileName() + " is a tar archive: Logsift reads .zip and .gz, "
                                + "not .tar. Ask the person to unpack it, then list the folder.");
                    }
                    throw new Tool.Failure(real.getFileName() + " is a file, not a folder or zip. Open it with "
                            + toolFor(kind(real.getFileName().toString(), head(real))) + ".");
                }
                FolderListing.Result r = w.await("list:" + real, "listing " + real,
                        () -> FolderListing.list(real, FolderListing.MAX_FILES, LogTools.INTERRUPTED));
                return w.finish(folderAnswer(real, r, glob, onPath, sort, limit), LogTools.maxChars(a));
            });

    private record Row(String name, long size, long modified, String kind, Path open) {}

    private static String folderAnswer(Path dir, FolderListing.Result r, Pattern glob, boolean onPath, String sort,
                                       int limit) {
        List<Row> rows = new ArrayList<>();
        long total = 0;
        for (Archives.Entry e : r.files()) {
            if (!matches(glob, onPath, e.name())) {
                continue;
            }
            Path f = dir.resolve(e.name());
            long modified;
            try {
                modified = Files.getLastModifiedTime(f).toMillis();
            } catch (IOException ex) {
                modified = -1;
            }
            rows.add(new Row(e.name(), e.size(), modified, null, f));
            total += e.size();
        }
        sort(rows, sort);
        StringBuilder b = new StringBuilder();
        b.append(dir).append(": ").append(n(rows.size())).append(rows.size() == 1 ? " file" : " files")
                .append(" (").append(Numbers.Unit.BYTES.format(total)).append(")");
        if (r.capped()) {
            b.append(", more not listed (the folder is too big: give a subfolder or a pattern)");
        }
        if (r.skipped() > 0) {
            b.append(", ").append(r.skipped()).append(" folders couldn't be read");
        }
        if (rows.isEmpty()) {
            return b.append(glob == null ? ". The folder has no files." : ". No file matches the pattern.").toString();
        }
        b.append(rows.size() > limit ? ", the first " + limit + " " + sortWords(sort) : "").append(":\n");
        for (int i = 0; i < rows.size() && i < limit; i++) {
            Row row = rows.get(i);
            String kind = i < MAX_LOOKED_AT ? kind(row.name(), head(row.open())) : kind(row.name(), null);
            b.append("- ").append(row.name()).append("  ").append(Numbers.Unit.BYTES.format(row.size()));
            if (row.modified() >= 0) {
                b.append("  ").append(MODIFIED.format(Instant.ofEpochMilli(row.modified()).atZone(ZoneId.systemDefault())));
            }
            b.append("  ").append(kind).append(" → ").append(toolFor(kind)).append('\n');
        }
        if (rows.size() > limit) {
            b.append("… ").append(n(rows.size() - limit)).append(" more: raise limit, or narrow with pattern.\n");
        }
        b.append("Paths are below ").append(dir).append("; pass the full path to the tool.");
        return b.toString();
    }

    private static String zipAnswer(Path zip, List<Archives.Entry> entries, Pattern glob, boolean onPath, String sort,
                                    int limit) {
        List<Row> rows = new ArrayList<>();
        long total = 0;
        for (Archives.Entry e : entries) {
            String rel = e.name().replace(Archives.NESTED, "/");
            if (!matches(glob, onPath, rel)) {
                continue;
            }
            rows.add(new Row(e.name(), e.size(), -1, null, Archives.pathOf(zip, e.name())));
            total += Math.max(0, e.size());
        }
        sort(rows, sort.equals("newest") ? "name" : sort);
        StringBuilder b = new StringBuilder();
        b.append(zip).append(" (zip): ").append(n(rows.size())).append(rows.size() == 1 ? " file" : " files")
                .append(" (").append(Numbers.Unit.BYTES.format(total)).append(" unpacked)");
        if (rows.isEmpty()) {
            return b.append(glob == null ? ". The zip has no files." : ". No file matches the pattern.").toString();
        }
        b.append(rows.size() > limit ? ", the first " + limit : "").append(":\n");
        for (int i = 0; i < rows.size() && i < limit; i++) {
            Row row = rows.get(i);
            String kind = kind(Archives.baseName(row.name()), null);
            b.append("- ").append(row.name().replace(Archives.NESTED, " › ")).append("  ")
                    .append(row.size() < 0 ? "size unknown" : Numbers.Unit.BYTES.format(row.size())).append("  ")
                    .append(kind).append(" → ").append(toolFor(kind)).append(" with path=\"").append(row.open())
                    .append("\"\n");
        }
        if (rows.size() > limit) {
            b.append("… ").append(n(rows.size() - limit)).append(" more: raise limit, or narrow with pattern.\n");
        }
        b.append("What a file is was told from its name (files in a zip aren't unpacked to look). Times aren't "
                + "listed for files in a zip.");
        return b.toString();
    }

    private static void sort(List<Row> rows, String sort) {
        switch (sort) {
            case "newest" -> rows.sort(Comparator.comparingLong(Row::modified).reversed().thenComparing(Row::name));
            case "largest" -> rows.sort(Comparator.comparingLong(Row::size).reversed().thenComparing(Row::name));
            default -> rows.sort(Comparator.comparing(Row::name));
        }
    }

    private static String sortWords(String sort) {
        return switch (sort) {
            case "newest" -> "newest";
            case "largest" -> "largest";
            default -> "by path";
        };
    }

    private static boolean matches(Pattern glob, boolean onPath, String rel) {
        if (glob == null) {
            return true;
        }
        String s = onPath ? rel : rel.substring(rel.lastIndexOf('/') + 1);
        return glob.matcher(s).matches();
    }

    /** A glob (*, **, ?, {a,b}) as a pattern, ignoring case. */
    static Pattern glob(String g) throws Tool.Failure {
        StringBuilder re = new StringBuilder();
        boolean inBraces = false;
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < g.length() && g.charAt(i + 1) == '/') {
                            i++;
                            re.append("(?:.*/)?"); // **/ : any folders, or none
                        } else {
                            re.append(".*");
                        }
                    } else {
                        re.append("[^/]*");
                    }
                }
                case '?' -> re.append("[^/]");
                case '{' -> {
                    inBraces = true;
                    re.append("(?:");
                }
                case '}' -> {
                    if (!inBraces) {
                        throw new Tool.Failure("pattern has a } without a {: " + g);
                    }
                    inBraces = false;
                    re.append(')');
                }
                case ',' -> re.append(inBraces ? "|" : ",");
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (inBraces) {
            throw new Tool.Failure("pattern has a { without a }: " + g);
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static byte[] head(Path f) {
        if (f == null || !Files.isRegularFile(f)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(f)) {
            return in.readNBytes(HEAD);
        } catch (IOException e) {
            return null;
        }
    }

    /** What a file is, from its name and (when given) its start, as "Analyze with Logsift" tells. */
    static String kind(String fileName, byte[] head) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".hprof") || startsWith(head, "JAVA PROFILE 1.0")) {
            return "heap dump";
        }
        if (name.endsWith(".jfr") || startsWith(head, "FLR\0")) {
            return "profile";
        }
        if (name.endsWith(".dmp") || name.endsWith(".mdmp") || startsWith(head, "MDMP")) {
            return "crash dump";
        }
        if (name.endsWith(".zip")) {
            return "zip";
        }
        if (name.endsWith(".tar") || name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
            return "tar archive";
        }
        String base = Archives.gunzippedName(name);
        if (base.startsWith("hs_err_pid")) {
            return "crash file (hs_err)";
        }
        if (base.endsWith(".csv") || base.endsWith(".tsv")) {
            return name.endsWith(".gz") ? "table (CSV, gzipped)" : "table (CSV)";
        }
        if (head != null && !name.endsWith(".gz")) {
            if (binary(head)) {
                return "binary";
            }
            String text = new String(head, logsift.core.Encoding.detect(head, head.length));
            if (logsift.crash.HsErr.looksLike(text.substring(0, Math.min(text.length(), 4096)))) {
                return "crash file (hs_err)";
            }
            if (logsift.jvm.DumpParser.looksLikeDump(text)) {
                return "thread dump";
            }
            if (logsift.jvm.GcLog.looksLikeGcLog(text)) {
                return "GC log";
            }
        }
        if (name.endsWith(".gz")) {
            return "log (gzipped)";
        }
        return "log";
    }

    /** The tool for a kind of file. */
    static String toolFor(String kind) {
        return switch (kind) {
            case "heap dump" -> "analyze_heap_dump";
            case "profile" -> "analyze_profile";
            case "crash dump", "crash file (hs_err)" -> "analyze_crash";
            case "zip" -> "list_files";
            case "tar archive" -> "unpack it first";
            case "thread dump" -> "analyze_threads";
            case "GC log" -> "analyze_gc";
            case "table (CSV)", "table (CSV, gzipped)" -> "open_log, then profile_columns, pivot or query";
            case "binary" -> "not a text file";
            default -> "open_log";
        };
    }

    private static boolean startsWith(byte[] b, String magic) {
        if (b == null || b.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (b[i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Many zero bytes: not text (UTF-16 text has some, so a few percent count). */
    private static boolean binary(byte[] head) {
        if (head.length >= 2 && ((head[0] == (byte) 0xFF && head[1] == (byte) 0xFE)
                || (head[0] == (byte) 0xFE && head[1] == (byte) 0xFF))) {
            return false; // UTF-16 with a byte order mark
        }
        int zeros = 0;
        int n = Math.min(head.length, 8192);
        for (int i = 0; i < n; i++) {
            if (head[i] == 0) {
                zeros++;
            }
        }
        return n > 0 && zeros * 3 > n; // more than a third: not even UTF-16
    }

    // ---------------------------------------------------------------- profile_columns

    static final Tool PROFILE_COLUMNS = new Def("profile_columns", "What each column holds",
            "Describes every column of a table (CSV/TSV) or of a log with columns (level, logger, thread…), as "
                    + "Logsift's Profile tab does: its kind (number, time, category, yes/no, ID, text, empty), how many "
                    + "values are filled in and empty, how many are different, numbers' min, median, mean, 95% and "
                    + "max, times' first and last, and the most frequent values. Use it FIRST on a table you don't "
                    + "know, to learn the column names and what is in them before pivot or query (their arguments "
                    + "need the exact column names). Use pivot for totals by group, query for exact counts with "
                    + "conditions. Big files are profiled from a sample spread over the file (the answer says so). "
                    + "Example: profile_columns(log=\"log:1\") or profile_columns(log=\"~/data/orders.csv\", "
                    + "columns=\"status, amount\").",
            new Tool.Schema().string("log", LogTools.LOG_ARG, true)
                    .string("columns", "Only these columns, by name, comma-separated (case doesn't matter): "
                            + "status, amount. Default: every column.", false)
                    .integer("top", "Most frequent values shown per column (default 5, at most 20).", false)
                    .integer("limit", "Most columns described (default 60, at most 1000).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = LogTools.ready(a, w);
                String h = w.handleOf(log);
                LogDocument doc = log.document();
                var formats = log.formats();
                LogFormat f = formats == null ? null : formats.columns();
                if (f == null || f.columns().isEmpty()) {
                    throw new Tool.Failure("This log has no columns (it isn't a table, and no log format with fields "
                            + "was found), so there is nothing to profile. Use insights, top_messages or metrics "
                            + "instead.");
                }
                int top = (int) a.number("top", 5, 1, 20);
                int limit = (int) a.number("limit", 60, 1, 1000);
                List<String> wanted = a.has("columns") ? names(a.string("columns")) : List.of();
                ColumnProfile.Result r = w.await(h + ":profile:" + doc.generation(), "profiling the columns",
                        () -> ColumnProfile.analyze(f, doc.lineCount(), CsvRows.reader(doc, f), null,
                                java.time.Year.now().getValue(), LogTools.INTERRUPTED, null));
                if (r == null) {
                    throw new Tool.Failure("Stopped before the profile finished.");
                }
                List<ColumnProfile.Column> cols = new ArrayList<>();
                List<String> unknown = new ArrayList<>();
                if (wanted.isEmpty()) {
                    cols.addAll(r.columns());
                } else {
                    for (String want : wanted) {
                        ColumnProfile.Column c = r.columns().stream().filter(x -> same(x, want)).findFirst().orElse(null);
                        if (c == null) {
                            unknown.add(want);
                        } else if (!cols.contains(c)) {
                            cols.add(c);
                        }
                    }
                    if (!unknown.isEmpty()) {
                        throw new Tool.Failure("No column " + String.join(", ", unknown) + ". The columns: "
                                + String.join(", ", r.columns().stream().map(ColumnProfile.Column::name).toList()) + ".");
                    }
                }
                StringBuilder b = new StringBuilder();
                b.append(h).append(" (").append(w.shownPath(h).getFileName()).append("): ").append(n(r.columns().size()))
                        .append(r.columns().size() == 1 ? " column, " : " columns, ").append(n(r.rows())).append(" rows");
                if (r.sampled()) {
                    b.append(" in a sample spread over the file (").append(n(r.lineCount()))
                            .append(" lines in all): counts below are the sample's");
                }
                b.append(".\n");
                int shown = 0;
                for (ColumnProfile.Column c : cols) {
                    if (shown++ >= limit) {
                        b.append("… ").append(n(cols.size() - limit)).append(" more columns: raise limit, or name "
                                + "them in columns.\n");
                        break;
                    }
                    column(b, shown, c, top);
                }
                b.append("Use these exact column names in pivot (rows, columns, values) and query (SQL).");
                return w.finish(b.toString(), LogTools.maxChars(a));
            });

    private static boolean same(ColumnProfile.Column c, String want) {
        return c.name().equalsIgnoreCase(want) || (c.title() != null && c.title().equalsIgnoreCase(want));
    }

    private static List<String> names(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    private static void column(StringBuilder b, int i, ColumnProfile.Column c, int top) {
        long all = c.filled() + c.empty();
        b.append(i).append(". ").append(c.name());
        if (c.title() != null && !c.title().isBlank() && !c.title().equals(c.name())) {
            b.append(" (").append(c.title()).append(')');
        }
        b.append(" — ").append(c.kind().label);
        Numbers.Unit u = c.unit() == null ? Numbers.Unit.NONE : c.unit();
        if (c.kind() == ColumnProfile.Kind.NUMBER && u != Numbers.Unit.NONE) {
            b.append(" (").append(u == Numbers.Unit.MILLIS ? "ms" : u == Numbers.Unit.BYTES ? "bytes" : "%").append(')');
        }
        b.append(": ").append(n(c.filled())).append(" values");
        if (c.empty() > 0) {
            b.append(", ").append(n(c.empty())).append(" empty (").append(percent(c.empty(), all)).append(')');
        }
        if (c.kind() != ColumnProfile.Kind.EMPTY) {
            b.append(", ").append(c.distinctCapped() ? "over " : "").append(n(c.distinct())).append(" different");
        }
        b.append('\n');
        double[] s = c.stats();
        switch (c.kind()) {
            case NUMBER -> {
                if (s != null && s.length >= 5) {
                    b.append("   min ").append(u.format(s[0])).append(", median ").append(u.format(s[1]))
                            .append(", mean ").append(u.format(s[2])).append(", 95% ").append(u.format(s[3]))
                            .append(", max ").append(u.format(s[4])).append('\n');
                }
                bars(b, "   spread: ", c.bars(), top, true);
            }
            case TIME -> {
                if (s != null && s.length >= 2) {
                    b.append("   from ").append(LogTools.time((long) s[0])).append(" to ").append(LogTools.time((long) s[1]))
                            .append(" (over ").append(Numbers.Unit.MILLIS.format(s[1] - s[0])).append(")\n");
                }
            }
            case EMPTY -> b.append("   every row is empty here\n");
            default -> {
                bars(b, "   most frequent: ", c.bars(), top, false);
                if (c.kind() == ColumnProfile.Kind.ID || c.kind() == ColumnProfile.Kind.TEXT) {
                    b.append("   ").append(c.shortest() == c.longest() ? c.shortest() + " characters"
                            : c.shortest() + " to " + c.longest() + " characters").append('\n');
                }
            }
        }
    }

    private static void bars(StringBuilder b, String label, List<ColumnProfile.Bar> bars, int top, boolean ranges) {
        if (bars == null || bars.isEmpty()) {
            return;
        }
        List<ColumnProfile.Bar> show = new ArrayList<>();
        for (ColumnProfile.Bar bar : bars) {
            if (bar.count() > 0) {
                show.add(bar);
            }
        }
        if (ranges) {
            // a histogram: the fullest buckets, in order of their values
            List<ColumnProfile.Bar> full = new ArrayList<>(show);
            full.sort(Comparator.comparingLong(ColumnProfile.Bar::count).reversed());
            List<ColumnProfile.Bar> keep = full.subList(0, Math.min(top, full.size()));
            show.removeIf(x -> !keep.contains(x));
        } else if (show.size() > top) {
            show = new ArrayList<>(show.subList(0, top));
        }
        if (show.isEmpty()) {
            return;
        }
        b.append(label);
        for (int i = 0; i < show.size(); i++) {
            ColumnProfile.Bar bar = show.get(i);
            b.append(i == 0 ? "" : ", ").append(LogTools.cut(bar.label(), 60)).append(" (").append(n(bar.count()))
                    .append(')');
        }
        b.append('\n');
    }

    private static String percent(long part, long all) {
        if (all <= 0) {
            return "0%";
        }
        double p = 100.0 * part / all;
        return p >= 10 || p == 0 ? Math.round(p) + "%" : String.format(Locale.ROOT, "%.1f%%", p);
    }

    // ---------------------------------------------------------------- merge_tables

    static final Tool MERGE_TABLES = new Def("merge_tables", "Merge tables into one",
            "Merges two or more CSV/TSV files into ONE table, as File → Merge does for tables: one header with "
                    + "every file's columns (matched by name, ignoring case and spaces), plus a source column saying "
                    + "which file each row came from. When every file has times, rows go in time order; otherwise "
                    + "one file after the other. Gives a handle (log:N) for profile_columns, pivot and query, which "
                    + "then see all the rows together (for example pivot(log=\"log:3\", rows=\"source\")). Use "
                    + "merge_logs for logs (not tables), and query's tables argument when you need a JOIN between "
                    + "different tables rather than stacking similar ones. The merge goes in Logsift's own "
                    + "temporary folder; nothing is written next to the files. Example: "
                    + "merge_tables(files=\"~/data/jan.csv, ~/data/feb.csv\").",
            new Tool.Schema().string("files", "Two or more CSV/TSV files: paths or handles, separated by commas "
                    + "(a JSON list of strings works too).", true)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                List<String> items = items(a.string("files"));
                if (items.size() < 2) {
                    throw new Tool.Failure("Give two or more CSV files, separated by commas.");
                }
                List<LogAnalysis> logs = new ArrayList<>();
                List<Path> shown = new ArrayList<>();
                for (String item : items) {
                    LogAnalysis log = w.log(item);
                    String h = w.handleOf(log);
                    w.await(h + ":ready", "reading " + log.path().getFileName(), () -> log.awaitReady(LogTools.INTERRUPTED));
                    logs.add(log);
                    shown.add(w.shownPath(h));
                }
                List<Path> read = logs.stream().map(LogAnalysis::path).toList();
                for (int i = 0; i < logs.size(); i++) {
                    var d = logs.get(i).formats();
                    if (d == null || d.columns() == null || !d.columns().isCsv()) {
                        throw new Tool.Failure(shown.get(i).getFileName() + " isn't a CSV/TSV table. To merge logs by "
                                + "time, use merge_logs instead.");
                    }
                }
                List<String> labels = LogMerger.labels(shown);
                List<LogMerger.Source> sources = new ArrayList<>();
                for (int i = 0; i < read.size(); i++) {
                    sources.add(new LogMerger.Source(read.get(i), labels.get(i)));
                }
                CsvMerger.Plan plan;
                try {
                    plan = CsvMerger.plan(sources);
                } catch (IOException e) {
                    throw new Tool.Failure("Couldn't merge: " + e.getMessage());
                }
                String key = "merge-tables:" + String.join("|", shown.stream().map(Path::toString).toList());
                long[] rows = {0};
                String h = w.await(key, "merging " + items.size() + " tables", () -> {
                    Path dir = Files.createTempDirectory("logsift-agent-merge-");
                    Path out = dir.resolve("merged.csv");
                    try (CsvMerger m = CsvMerger.merge(sources, out, null, LogTools.INTERRUPTED)) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                        rows[0] = m.rowsWritten();
                    } catch (IOException e) {
                        Files.deleteIfExists(out);
                        Files.deleteIfExists(dir);
                        throw new Tool.Failure("Couldn't merge: " + e.getMessage());
                    }
                    w.made(out);
                    return w.openGenerated(out);
                });
                LogAnalysis merged = w.log(h);
                w.await(h + ":ready", "reading the merged table", () -> merged.awaitReady(LogTools.INTERRUPTED));
                StringBuilder b = new StringBuilder();
                b.append("Merged ").append(items.size()).append(" tables into ").append(h).append(": ")
                        .append(plan.columns().size() + 1).append(" columns (").append(plan.sourceColumn())
                        .append(" first, then ").append(String.join(", ", plan.columns())).append(")");
                if (rows[0] > 0) {
                    b.append(", ").append(n(rows[0])).append(" rows");
                }
                b.append(".\n");
                b.append(plan.order() == CsvMerger.Order.BY_TIME ? "Rows are in time order (every file has times).\n"
                        : "Rows are one file after the other: " + String.join(", ", plan.untimed())
                                + (plan.untimed().size() == 1 ? " has" : " have") + " no times.\n");
                b.append("Each row's ").append(plan.sourceColumn()).append(" column says its file:\n");
                for (int i = 0; i < shown.size(); i++) {
                    List<String> own = logs.get(i).formats().columns().headerNames();
                    List<String> missing = new ArrayList<>();
                    for (String c : plan.columns()) {
                        if (own.stream().noneMatch(o -> key(o).equals(key(c)))) {
                            missing.add(c);
                        }
                    }
                    b.append("- ").append(labels.get(i)).append(": ").append(shown.get(i)).append(" (")
                            .append(own.size()).append(" columns")
                            .append(missing.isEmpty() ? "" : "; empty in its rows: " + String.join(", ", missing))
                            .append(")\n");
                }
                b.append("Next: profile_columns(log=\"").append(h).append("\"), pivot(log=\"").append(h)
                        .append("\", rows=\"").append(plan.sourceColumn()).append("\") or query(log=\"").append(h)
                        .append("\", sql=\"SELECT ...\").");
                return w.finish(b.toString(), LogTools.maxChars(a));
            });

    /** How column names are matched (as {@link CsvMerger} does): case and spaces around don't count. */
    private static String key(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        return n.startsWith("﻿") ? n.substring(1) : n;
    }

    /** "a.csv, b.csv" or ["a.csv", "b.csv"] → the items. */
    static List<String> items(String s) {
        String t = s.strip();
        if (t.startsWith("[") && t.endsWith("]")) {
            Object v = Json.parseValue(t, null);
            if (v instanceof List<?> l) {
                List<String> out = new ArrayList<>();
                for (Object o : l) {
                    if (o != null && !o.toString().isBlank()) {
                        out.add(o.toString().strip());
                    }
                }
                return out;
            }
            t = t.substring(1, t.length() - 1); // [a.csv, b.csv]: a list written as text
        }
        List<String> out = new ArrayList<>();
        for (String p : t.split(",")) {
            String q = p.strip();
            if (q.length() >= 2 && (q.startsWith("\"") && q.endsWith("\"") || q.startsWith("'") && q.endsWith("'"))) {
                q = q.substring(1, q.length() - 1).strip();
            }
            if (!q.isEmpty()) {
                out.add(q);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- show_entry

    static final Tool SHOW_ENTRY = new Def("show_entry", "Show one entry in full",
            "Shows one log entry in full, as Logsift's Details pane does: the line and the lines that belong to "
                    + "it (a stack trace, a wrapped message, a multi-line JSON), the fields the log's format reads "
                    + "from it (time, level, logger…), and any JSON in it pretty-printed (JSON inside strings opened "
                    + "up too). Use it after search, exceptions or read_lines gave a line number and you need the "
                    + "whole entry; read_lines is for a range of lines. Example: show_entry(log=\"log:1\", line=1234).",
            new Tool.Schema().string("log", LogTools.LOG_ARG, true)
                    .integer("line", "The line (1-based, as the other tools give them). Any line of the entry "
                            + "works: Logsift finds where it starts.", true)
                    .integer("max_lines", "Most lines of the entry shown (default 200, at most 1000).", false)
                    .integer("max_chars", "Longest answer (default 24000).", false).build(),
            (a, w) -> {
                LogAnalysis log = LogTools.ready(a, w);
                String h = w.handleOf(log);
                LogDocument doc = log.document();
                long total = doc.lineCount();
                long line = a.number("line", 1, 1, Long.MAX_VALUE) - 1;
                if (line >= total) {
                    throw new Tool.Failure("The log has " + n(total) + " lines; line " + n(line + 1)
                            + " isn't there. Give a line from 1 to " + n(total) + ".");
                }
                int max = (int) a.number("max_lines", 200, 1, 1000);
                var formats = log.formats();
                EntryFormat ef = formats == null ? null : formats.entries();
                EntryFormat.Entry e = EntryFormat.around(doc, ef, line, max);
                if (e.lines().isEmpty()) {
                    throw new Tool.Failure("Line " + n(line + 1) + " couldn't be read.");
                }
                StringBuilder b = new StringBuilder();
                if (e.lines().size() == 1) {
                    b.append(h).append(" line ").append(LogTools.line(e.first())).append(":\n");
                } else {
                    b.append(h).append(" lines ").append(LogTools.line(e.first())).append(" – ")
                            .append(LogTools.line(e.last())).append(": an entry of ").append(n(e.lines().size()))
                            .append(" lines").append(e.first() != line ? " (line " + LogTools.line(line) + " is in it)" : "")
                            .append(":\n");
                }
                int count = Math.min(e.lines().size(), max);
                for (int i = 0; i < count; i++) {
                    b.append(LogTools.line(e.first() + i)).append(": ").append(LogTools.cut(e.lines().get(i), 2000))
                            .append('\n');
                }
                if (e.lines().size() > count) {
                    b.append("… ").append(n(e.lines().size() - count)).append(" more lines: read_lines(log=\"").append(h)
                            .append("\", from=").append(LogTools.line(e.first() + count)).append(")\n");
                }
                LogFormat f = formats == null ? null : formats.columns();
                if (f != null && !f.columns().isEmpty() && !f.isCsv()) {
                    String[] cells = f.split(e.lines().get(0), null);
                    if (cells != null) {
                        b.append("Fields:\n");
                        List<String> cols = f.columns();
                        for (int i = 0; i < cols.size() && i < cells.length; i++) {
                            if (cells[i] != null && !cells[i].isBlank()) {
                                b.append("- ").append(cols.get(i)).append(": ").append(LogTools.cut(cells[i].strip(), 500))
                                        .append('\n');
                            }
                        }
                    }
                }
                String body = String.join("\n", e.lines());
                List<Json.Found> found = body.length() > 2_000_000 ? List.of() : Json.findAll(body);
                int j = 0;
                for (Json.Found jf : found) {
                    if (!(jf.value() instanceof Map<?, ?>) && !(jf.value() instanceof List<?>)) {
                        continue;
                    }
                    if (j++ == 0) {
                        b.append(found.size() == 1 ? "The JSON in it:\n" : "The JSON in it (" + found.size() + " pieces):\n");
                    }
                    b.append(Json.pretty(expand(jf.value(), 0))).append('\n');
                    if (j >= 5) {
                        break;
                    }
                }
                return w.finish(b.toString().stripTrailing(), LogTools.maxChars(a));
            });

    /** Strings that hold JSON themselves ("payload": "{\"id\": 7}") opened up, as the Details pane does. */
    static Object expand(Object v, int depth) {
        if (depth > 20) {
            return v;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), expand(x, depth + 1)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(expand(x, depth + 1)));
            return out;
        }
        if (v instanceof String str && str.length() > 1 && (str.startsWith("{") || str.startsWith("["))) {
            Object inner = Json.parseValue(str, str);
            if (inner instanceof Map || inner instanceof List) {
                return expand(inner, depth + 1);
            }
        }
        return v;
    }
}
