package logsift.agent;

import static logsift.agent.LogTools.INTERRUPTED;
import static logsift.agent.LogTools.LOG_ARG;
import static logsift.agent.LogTools.cut;
import static logsift.agent.LogTools.line;
import static logsift.agent.LogTools.maxChars;
import static logsift.agent.LogTools.n;
import static logsift.agent.LogTools.ready;
import static logsift.agent.LogTools.time;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import logsift.core.ColumnFilter;
import logsift.core.FilterEngine;
import logsift.core.FilterResult;
import logsift.core.Formula;
import logsift.core.Levels;
import logsift.core.LogAnalysis;
import logsift.core.LogDocument;
import logsift.core.LogFormat;
import logsift.core.SearchQuery;
import logsift.core.TimeFormat;
import logsift.core.TimeIndex;

/**
 * What the window's Filters panel, timeline and Follow do, as tools: keep only some lines
 * (terms, a formula, levels, columns, a time range, with context and whole stack traces), count
 * lines over time, and read what a growing log added. Filters run on the window's own engine
 * ({@link FilterEngine}), built as the window builds them, so both keep the same lines.
 */
public final class FilterTools {
    private FilterTools() {}

    public static List<Tool> all() {
        return List.of(FILTER, TIMELINE, FOLLOW);
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

    /** An input schema with list arguments too (Tool.Schema has none). */
    private static final class Schema {
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        Schema prop(String name, String type, String description, boolean req) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            p.put("description", description);
            props.put(name, p);
            if (req) {
                required.add(name);
            }
            return this;
        }

        Schema string(String name, String description, boolean req) {
            return prop(name, "string", description, req);
        }

        Schema integer(String name, String description) {
            return prop(name, "integer", description, false);
        }

        Schema bool(String name, String description) {
            return prop(name, "boolean", description, false);
        }

        Schema list(String name, String description) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "array");
            p.put("items", Map.of("type", "string"));
            p.put("description", description);
            props.put(name, p);
            return this;
        }

        Map<String, Object> build() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", props);
            if (!required.isEmpty()) {
                s.put("required", required);
            }
            s.put("additionalProperties", false);
            return s;
        }
    }

    // ---------------------------------------------------------------- building filters

    /**
     * What to keep, as the window's Filters panel builds it: the filters' searches and how they
     * combine, plus a time range as lines.
     *
     * @param what  the filters in words, for the answer
     * @param from  first line of the time range (0: from the start)
     * @param to    end of the time range, exclusive (Long.MAX_VALUE: to the end)
     */
    record Built(List<FilterEngine.Spec> specs, Formula formula, List<String> what, long from, long to) {
        boolean windowed() {
            return from > 0 || to != Long.MAX_VALUE;
        }

        /** A key that changes with anything that changes the answer. */
        String key() {
            StringBuilder b = new StringBuilder();
            for (FilterEngine.Spec s : specs) {
                b.append(s).append(';');
            }
            return b.append(formula).append('|').append(from).append('-').append(to).toString();
        }
    }

    static final String TERMS_HELP = "Use show/hide for simple cases. For anything else, give terms and a "
            + "formula: terms are named A, B, C… in the order given, and the formula combines them with AND, OR, "
            + "NOT and parentheses, like (A OR B) AND NOT C.";

    /** One letter for each of {@code n} terms. */
    private static char letter(int i) {
        return (char) ('A' + i);
    }

    /** The search for one term, with the call's regex / match_case / whole_word. */
    private static SearchQuery query(String text, Tool.Args a, String arg) throws Tool.Failure {
        SearchQuery q = new SearchQuery(text, a.bool("match_case", false), a.bool("regex", false),
                a.bool("whole_word", false));
        try {
            q.toPattern();
        } catch (RuntimeException e) {
            throw new Tool.Failure("Not a valid regular expression in " + arg + ": “" + text + "” (" + e.getMessage()
                    + "). Fix it, or leave regex off to search for the text as written.");
        }
        return q;
    }

    private static Formula and(Formula a, Formula b) {
        return a == null ? b : b == null ? a : new Formula.And(a, b);
    }

    /** Levels as given ("ERROR,WARN", ["error", "warn"]) as ERROR, WARN, INFO, DEBUG or TRACE. */
    static List<String> levels(Tool.Args a, String arg) throws Tool.Failure {
        List<String> out = new ArrayList<>();
        for (String item : a.list(arg)) {
            for (String part : item.split("[,;\\s]+")) {
                if (part.isBlank()) {
                    continue;
                }
                String l = Levels.normalize(part);
                if (l == null) {
                    throw new Tool.Failure("Not a level: “" + part + "”. Use ERROR, WARN, INFO, DEBUG or TRACE "
                            + "(like levels=[\"ERROR\", \"WARN\"]); other spellings such as E, ERR, FATAL, SEVERE "
                            + "or WARNING are read as these.");
                }
                if (!out.contains(l)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /**
     * Level filters, as the window's level chips: with a format that spells levels its own way, by
     * its level column; else by the usual words (whole word, exact case). Any of the levels.
     */
    static Formula levelFilter(List<String> levels, LogFormat format, List<FilterEngine.Spec> specs, char base) {
        if (levels.isEmpty()) {
            return null;
        }
        List<Character> ids = new ArrayList<>();
        String regex = Levels.lineRegex(format, levels);
        if (regex != null) {
            specs.add(new FilterEngine.Spec(base, new SearchQuery(regex, true, true, false)));
            ids.add(base);
        } else {
            for (String level : levels) {
                for (String word : Levels.words(level)) {
                    char id = (char) (base + ids.size());
                    specs.add(new FilterEngine.Spec(id, new SearchQuery(word, true, false, true)));
                    ids.add(id);
                }
            }
        }
        return Formula.auto(ids, List.of());
    }

    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*(.+?)\\s*(?i:(\\bnot\\s+contains\\b|\\bcontains\\b|\\bbetween\\b)|(==|!=|=|>|<))\\s*(.*?)\\s*$");

    /** A column filter as written ("status = 500", "duration > 2 s", "user contains bob"). */
    static Formula columnFilter(String text, LogFormat format, Tool.Args a, List<FilterEngine.Spec> specs, char id)
            throws Tool.Failure {
        Matcher m = COLUMN.matcher(text);
        if (!m.matches() || m.group(4).isEmpty()) {
            throw new Tool.Failure("Not a column filter: “" + text + "”. Write COLUMN OP VALUE with OP one of "
                    + "contains, not contains, =, !=, >, <, between: like \"status = 500\", \"duration > 2 s\", "
                    + "\"user contains bob\", \"latency between 10..20\".");
        }
        String word = m.group(2) != null ? m.group(2).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") : m.group(3);
        boolean not = word.equals("!=") || word.equals("not contains");
        ColumnFilter.Op op = switch (word) {
            case "contains", "not contains" -> ColumnFilter.Op.CONTAINS;
            case "between" -> ColumnFilter.Op.BETWEEN;
            case ">" -> ColumnFilter.Op.MORE;
            case "<" -> ColumnFilter.Op.LESS;
            default -> ColumnFilter.Op.EQUALS;
        };
        String column = columnName(m.group(1).strip(), format);
        String value = m.group(4);
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new Tool.Failure("No value in the column filter “" + text + "”.");
        }
        SearchQuery q = new SearchQuery(value, a.bool("match_case", false),
                op == ColumnFilter.Op.CONTAINS && a.bool("regex", false),
                op == ColumnFilter.Op.CONTAINS && a.bool("whole_word", false));
        ColumnFilter cf;
        try {
            cf = new ColumnFilter(format, column, op, q);
        } catch (IllegalArgumentException e) {
            throw new Tool.Failure(e.getMessage() + " (in the column filter “" + text + "”).");
        }
        specs.add(new FilterEngine.Spec(id, q, cf));
        Formula f = new Formula.Ref(id);
        return not ? new Formula.Not(f) : f;
    }

    /** A column as the agent named it (its own name or the header's, any case), as the format names it. */
    static String columnName(String given, LogFormat format) throws Tool.Failure {
        for (String c : format.columns()) {
            if (c.equalsIgnoreCase(given) || format.displayName(c).equalsIgnoreCase(given)) {
                return c;
            }
        }
        List<String> names = format.columns().stream().map(format::displayName).toList();
        throw new Tool.Failure("This log has no column “" + given + "”. Its columns: " + String.join(", ", names)
                + ".");
    }

    /** How precise a written time is, in milliseconds: "10:05" is a minute, "10:05:07" a second. */
    static long precision(String at) {
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})([.,]\\d+)?)?(?!.*\\d{1,2}:\\d{2})").matcher(at);
        if (!m.find()) {
            return 1;
        }
        return m.group(3) == null ? 60_000 : m.group(4) == null ? 1_000 : 1;
    }

    /** The time range as lines: [first line at or after from, first line after to). */
    static long[] window(LogAnalysis log, String from, String to) throws Exception {
        long first = 0;
        long end = Long.MAX_VALUE;
        if (from != null && !from.isBlank()) {
            first = LogTools.lineAtOrAfter(log, LogTools.timeOf(log, from.strip()));
        }
        if (to != null && !to.isBlank()) {
            long t = LogTools.timeOf(log, to.strip()) + precision(to.strip());
            end = LogTools.lineAtOrAfter(log, t);
            if (end >= log.document().lineCount()) {
                end = Long.MAX_VALUE; // to the end, including lines added later
            }
        }
        if (end != Long.MAX_VALUE && end <= first) {
            throw new Tool.Failure("Nothing was logged from " + from + " to " + to + " (to_time must be after "
                    + "from_time).");
        }
        return new long[] {first, end};
    }

    /** The filters of a call to {@code filter}. */
    static Built build(LogAnalysis log, Tool.Args a) throws Exception {
        LogFormat columns = log.formats() == null ? null : log.formats().columns();
        List<FilterEngine.Spec> specs = new ArrayList<>();
        List<String> what = new ArrayList<>();
        List<String> show = a.list("show");
        List<String> hide = a.list("hide");
        List<String> terms = a.list("terms");
        String formulaText = a.string("formula", "").strip();
        Formula f;
        if (!formulaText.isEmpty() || !terms.isEmpty()) {
            if (!show.isEmpty() || !hide.isEmpty()) {
                throw new Tool.Failure("Give either show/hide, or terms with a formula, not both. " + TERMS_HELP);
            }
            if (terms.isEmpty()) {
                throw new Tool.Failure("A formula needs terms: terms=[\"timeout\", \"refused\"] are A and B. "
                        + TERMS_HELP);
            }
            if (terms.size() > 26) {
                throw new Tool.Failure("At most 26 terms (A to Z).");
            }
            for (int i = 0; i < terms.size(); i++) {
                specs.add(new FilterEngine.Spec(letter(i), query(terms.get(i), a, "terms")));
            }
            if (formulaText.isEmpty()) {
                List<Character> ids = new ArrayList<>();
                for (int i = 0; i < terms.size(); i++) {
                    ids.add(letter(i));
                }
                f = Formula.auto(ids, List.of());
                formulaText = Formula.format(f);
            } else {
                try {
                    f = Formula.parse(formulaText);
                } catch (IllegalArgumentException e) {
                    throw new Tool.Failure("Not a valid formula: " + e.getMessage() + ". " + TERMS_HELP);
                }
                for (char c : f.refs()) {
                    if (c - 'A' < 0 || c - 'A' >= terms.size()) {
                        throw new Tool.Failure("The formula uses " + c + " but there " + (terms.size() == 1
                                ? "is 1 term (A)" : "are " + terms.size() + " terms (A to " + letter(terms.size() - 1)
                                + ")") + ". " + TERMS_HELP);
                    }
                }
            }
            StringBuilder named = new StringBuilder();
            for (int i = 0; i < terms.size(); i++) {
                named.append(i == 0 ? "" : ", ").append(letter(i)).append(" = “").append(terms.get(i)).append('”');
            }
            what.add(formulaText + " with " + named);
        } else {
            List<Character> shows = new ArrayList<>();
            List<Character> hides = new ArrayList<>();
            for (String s : show) {
                char id = letter(specs.size());
                specs.add(new FilterEngine.Spec(id, query(s, a, "show")));
                shows.add(id);
            }
            for (String s : hide) {
                char id = letter(specs.size());
                specs.add(new FilterEngine.Spec(id, query(s, a, "hide")));
                hides.add(id);
            }
            if (specs.size() > 26) {
                throw new Tool.Failure("At most 26 show and hide terms together.");
            }
            f = Formula.auto(shows, hides);
            if (!show.isEmpty()) {
                what.add("showing " + quoted(show));
            }
            if (!hide.isEmpty()) {
                what.add("hiding " + quoted(hide));
            }
        }
        List<String> columnFilters = a.list("columns");
        if (!columnFilters.isEmpty()) {
            if (columns == null || columns.columns().isEmpty()) {
                throw new Tool.Failure("This log has no columns (no format with fields was found for it), so "
                        + "column filters can't be used. Use show/hide, or query with SQL.");
            }
            for (int i = 0; i < columnFilters.size(); i++) {
                f = and(f, columnFilter(columnFilters.get(i), columns, a, specs, (char) (0x100 + i)));
            }
            what.add("where " + String.join(" and ", columnFilters));
        }
        List<String> levels = levels(a, "levels");
        Formula lf = levelFilter(levels, columns, specs, (char) 0x200);
        if (lf != null) {
            f = and(f, lf);
            what.add("levels " + String.join(", ", levels));
        }
        String from = a.string("from_time", null);
        String to = a.string("to_time", null);
        long[] win = {0, Long.MAX_VALUE};
        if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
            win = window(log, from, to);
            what.add("logged " + (from == null || from.isBlank() ? "up to " + to.strip()
                    : to == null || to.isBlank() ? "from " + from.strip() : from.strip() + " … " + to.strip()));
        }
        if (f == null && win[0] == 0 && win[1] == Long.MAX_VALUE) {
            throw new Tool.Failure("Say which lines to keep: show, hide, terms with a formula, levels, columns, "
                    + "or from_time/to_time.");
        }
        return new Built(specs, f, what, win[0], win[1]);
    }

    private static String quoted(List<String> s) {
        return String.join(" or ", s.stream().map(x -> "“" + x + "”").toList());
    }

    /**
     * Runs the filters to the end on the window's engine and gives the result (a snapshot).
     *
     * @param entries keep whole entries (stack traces with their line), as Keep stack traces
     */
    static FilterResult run(LogAnalysis log, Built b, int context, boolean entries) throws InterruptedException {
        LogDocument doc = log.document();
        try (FilterEngine e = new FilterEngine(doc)) {
            if (entries && log.formats() != null) {
                e.setEntryFormat(log.formats().entries());
            }
            e.setContext(context);
            if (b.windowed()) {
                e.setWindow(b.from(), b.to());
            }
            e.configure(b.specs(), b.formula());
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (e.awaitIdle(200)) {
                    FilterResult r = e.result();
                    if (!e.isRunning() && (r.isComplete() || !r.isFiltering())) {
                        return r;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- filter

    static final Tool FILTER = new Def("filter", "Filter a log",
            """
            Keeps only some lines of a log, as Logsift's Filters panel does, and gives how many lines are \
            kept and the first of them with their 1-based line numbers. Use it when one search isn't enough: \
            lines with any of several words (show), without some words (hide), a combination (terms + formula), \
            only some levels (levels), only one column's values (columns, for logs and CSV tables with fields), \
            only a time range (from_time/to_time), or with lines around each match (context). For one word or \
            phrase, search is enough; for exact counts or groups (per hour, per user), use query (SQL); for counts \
            over time, use timeline.
            Examples:
            - errors and warnings between two times: levels=["ERROR","WARN"], from_time="2026-11-04 10:00", \
            to_time="2026-11-04 10:30"
            - lines with "timeout" or "refused" but not "health": show=["timeout","refused"], hide=["health"]
            - (timeout or refused) and order, not retry: terms=["timeout","refused","order","retry"], \
            formula="(A OR B) AND C AND NOT D"
            - slow requests in a log with columns: columns=["duration > 2 s"]; HTTP 500s: columns=["status = 500"]
            Several kinds of filter (show/hide or terms, columns, levels, time) all have to hold.
            The answer: "N lines match …", then lines as "12: text" (a match) and "13- text" (context or the \
            rest of a stack trace, only with context or whole_entries). When there are more, it ends with \
            "Next: from_line=N": call again with the same arguments and that from_line.
            Mistakes to avoid: show and hide take lists, not one string with commas; don't mix show/hide with \
            terms/formula; formula letters must match terms in order (A is the first term); levels are ERROR, \
            WARN, INFO, DEBUG or TRACE; times are as the log writes them (no time zone conversion).""",
            new Schema().string("log", LOG_ARG, true)
                    .list("show", "Keep lines with any of these (text, or regular expressions with regex=true). "
                            + "Example: [\"timeout\", \"connection refused\"].")
                    .list("hide", "Drop lines with any of these. Example: [\"health check\", \"DEBUG\"].")
                    .list("terms", "Instead of show/hide: terms named A, B, C… in this order, combined by "
                            + "formula. Example: [\"timeout\", \"order\"].")
                    .string("formula", "How terms combine: letters with AND, OR, NOT and parentheses. Example: "
                            + "\"(A OR B) AND NOT C\". Without a formula, terms are combined with OR.", false)
                    .list("levels", "Only lines of these levels: ERROR, WARN, INFO, DEBUG, TRACE. Example: "
                            + "[\"ERROR\", \"WARN\"]. ERROR also covers FATAL, SEVERE and CRITICAL.")
                    .list("columns", "Only lines whose column value matches, each as COLUMN OP VALUE with OP "
                            + "contains, not contains, =, !=, >, <, between. Examples: [\"status = 500\"], "
                            + "[\"duration > 2 s\", \"user contains bob\"], [\"latency between 10..20\"]. "
                            + "Numbers may have units (7 ms, 1.5 s, 2 KB). open_log lists a log's columns.")
                    .string("from_time", "Only lines logged at or after this time, as the log writes times: "
                            + "\"2026-11-04 10:00\", \"2026-11-04 10:00:05.250\", or \"10:00\" on the log's "
                            + "first day.", false)
                    .string("to_time", "Only lines logged up to and including this time: \"2026-11-04 10:30\" "
                            + "keeps all of minute 10:30.", false)
                    .bool("regex", "show/hide/terms (and contains in columns) are Java regular expressions "
                            + "(default false: plain text).")
                    .bool("match_case", "Upper and lower case must match (default false).")
                    .bool("whole_word", "Only whole words (default false).")
                    .integer("context", "Also show this many lines before and after each match, marked with - "
                            + "(0 to 50, default 0).")
                    .bool("whole_entries", "A match anywhere in an entry keeps the whole entry, such as an "
                            + "exception with its stack trace (default true, as in the window).")
                    .integer("from_line", "Show kept lines from this line on (1-based, for the next page; "
                            + "default 1). Counts are always for the whole log.")
                    .integer("limit", "Most lines shown (default 50, at most 500).")
                    .integer("max_chars", "Longest answer (default 24000).").build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                Built b = build(log, a);
                int context = (int) a.number("context", 0, 0, 50);
                boolean entries = a.bool("whole_entries", true);
                long from = a.number("from_line", 1, 1, Long.MAX_VALUE) - 1;
                int limit = (int) a.number("limit", 50, 1, 500);
                LogDocument doc = log.document();
                String key = w.handleOf(log) + ":filter:" + b.key() + ":" + context + ":" + entries + ":"
                        + doc.generation() + ":" + doc.lineCount();
                FilterResult r = w.await(key, "filtering " + log.path().getFileName(),
                        () -> run(log, b, context, entries));
                long matches = r.matchCount();
                long shown = r.count();
                StringBuilder out = new StringBuilder();
                out.append(n(matches)).append(matches == 1 ? " line matches" : " lines match");
                out.append(" (").append(String.join("; ", b.what())).append(')');
                if (shown != matches) {
                    out.append(", ").append(n(shown)).append(" shown with ");
                    List<String> with = new ArrayList<>();
                    if (context > 0) {
                        with.add(context + (context == 1 ? " line" : " lines") + " of context");
                    }
                    if (entries) {
                        with.add("whole entries");
                    }
                    out.append(String.join(" and ", with));
                }
                out.append(" of ").append(n(doc.lineCount())).append(" lines");
                long firstRow = r.rowAtOrAfter(from);
                if (shown == 0) {
                    return w.finish(out.append('.').toString(), maxChars(a));
                }
                if (firstRow >= shown) {
                    return w.finish(out.append(". None at or after line ").append(line(from)).append('.').toString(),
                            maxChars(a));
                }
                out.append(from > 0 ? ", from line " + line(from) + ":\n" : ":\n");
                long endRow = Math.min(shown, firstRow + limit);
                int k = (int) (endRow - firstRow);
                for (long row = firstRow; row < endRow; row++) {
                    long l = r.lineAt(row);
                    out.append(line(l)).append(r.isMatch(l) ? ": " : "- ").append(cut(doc.line(l), 300)).append('\n');
                }
                if (endRow < shown) {
                    out.append("… ").append(n(shown - endRow)).append(" more. Next: from_line=")
                            .append(line(r.lineAt(endRow))).append('\n');
                }
                if (k == 0) {
                    out.append('\n');
                }
                return w.finish(out.toString().stripTrailing(), maxChars(a));
            });

    // ---------------------------------------------------------------- timeline

    /** Bucket sizes to choose from, smallest first. */
    static final long[] NICE = {1_000, 5_000, 10_000, 30_000, 60_000, 300_000, 600_000, 900_000, 1_800_000,
        3_600_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000, 86_400_000, 604_800_000};

    static final int MAX_BUCKETS = 500;

    /** A bucket size as written: 30s, 5m, 1h, 1d (or 90 seconds, 2 hours); 0 for auto. */
    static long bucket(String s) throws Tool.Failure {
        if (s == null || s.isBlank() || s.strip().equalsIgnoreCase("auto")) {
            return 0;
        }
        Matcher m = Pattern.compile("(?i)^\\s*(\\d+)\\s*(ms|s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?"
                + "|d|days?|w|weeks?)\\s*$").matcher(s);
        if (!m.matches()) {
            throw new Tool.Failure("Not a bucket size: “" + s + "”. Use a number and a unit: 30s, 1m, 5m, 15m, 1h, "
                    + "6h, 1d, or auto.");
        }
        long v = Long.parseLong(m.group(1));
        String u = m.group(2).toLowerCase(Locale.ROOT);
        long unit = u.equals("ms") ? 1 : u.startsWith("s") ? 1_000 : u.startsWith("m") ? 60_000
                : u.startsWith("h") ? 3_600_000 : u.startsWith("d") ? 86_400_000 : 604_800_000;
        long b = v * unit;
        if (b < 1_000) {
            throw new Tool.Failure("Buckets of at least 1s.");
        }
        return b;
    }

    /** A bucket size for people: 5 minutes, 1 hour. */
    static String bucketName(long ms) {
        long[] units = {604_800_000, 86_400_000, 3_600_000, 60_000, 1_000};
        String[] names = {"week", "day", "hour", "minute", "second"};
        for (int i = 0; i < units.length; i++) {
            if (ms % units[i] == 0) {
                long v = ms / units[i];
                return v == 1 ? "1 " + names[i] : v + " " + names[i] + "s";
            }
        }
        return ms + " ms";
    }

    /** The first time logged in lines [from, to), or NONE. */
    private static long firstTime(LogDocument doc, TimeFormat tf, long from, long to) {
        for (long l = from; l < Math.min(to, from + 10_000); l++) {
            String text = doc.line(l);
            long t = text == null ? TimeFormat.NONE : tf.parse(text);
            if (t != TimeFormat.NONE) {
                return t;
            }
        }
        return TimeFormat.NONE;
    }

    /** Rows of {@code r} (a result without context) in lines [from, to). */
    private static long countIn(FilterResult r, long from, long to) {
        return r == null ? 0 : r.rowAtOrAfter(to) - r.rowAtOrAfter(from);
    }

    static final Tool TIMELINE = new Def("timeline", "Lines over time",
            """
            Counts a log's lines per time bucket (per minute, per hour…), with errors and warnings in each, \
            like Logsift's timeline bar but as numbers. Use it to see when a problem started, when traffic \
            dropped or peaked, and which minutes to read next (then read_lines with at, or filter with \
            from_time/to_time). With query (and optionally level) it also counts the lines that match it in \
            each bucket, for example how often "timeout" happened per minute. For exact grouping by anything \
            else (user, status), use query (SQL).
            Examples: bucket="5m"; bucket="1h", from_time="2026-11-04 00:00", to_time="2026-11-04 23:59"; \
            query="timed out", bucket="1m".
            The answer: one row per bucket (its start time, lines, errors, warnings, and matching when asked), \
            runs of empty buckets shown as one row, then the busiest bucket and the one with most errors. Times \
            are as the log writes them. Lines without a time of their own (stack traces) count in the bucket of \
            the line before them.
            Mistakes to avoid: buckets are a number and a unit (30s, 5m, 1h, 1d), not "minute"; at most 500 \
            buckets: pick a bigger bucket or a shorter time range.""",
            new Schema().string("log", LOG_ARG, true)
                    .string("bucket", "Bucket size: 30s, 1m, 5m, 15m, 1h, 6h, 1d… (default auto: about 30 "
                            + "buckets over the time range).", false)
                    .string("from_time", "Start at this time, as the log writes times (\"2026-11-04 10:00\"; "
                            + "default: the first time in the log).", false)
                    .string("to_time", "End at this time, included (default: the last time in the log).", false)
                    .string("query", "Also count lines with this text (or regular expression with regex=true) in "
                            + "each bucket. Example: \"timed out\".", false)
                    .bool("regex", "query is a Java regular expression (default false).")
                    .bool("match_case", "Upper and lower case must match in query (default false).")
                    .string("level", "Also count lines of this level in each bucket (ERROR, WARN, INFO, DEBUG, "
                            + "TRACE); with query: lines of this level with the query.", false)
                    .integer("max_chars", "Longest answer (default 24000).").build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                LogDocument doc = log.document();
                TimeFormat tf = log.formats() == null ? null : log.formats().times();
                if (tf == null) {
                    throw new Tool.Failure("This log has no timestamps, so it has no timeline. Use search or "
                            + "filter by line numbers instead.");
                }
                long total = doc.lineCount();
                long first = a.has("from_time") ? LogTools.timeOf(log, a.string("from_time").strip())
                        : firstTime(doc, tf, 0, total);
                long last = a.has("to_time")
                        ? LogTools.timeOf(log, a.string("to_time").strip()) + precision(a.string("to_time").strip()) - 1
                        : LogAnalysis.lastTime(doc, tf, total);
                if (first == TimeFormat.NONE || last == TimeFormat.NONE) {
                    throw new Tool.Failure("No times found in this log.");
                }
                if (last < first) {
                    throw new Tool.Failure("to_time must be after from_time.");
                }
                long size = bucket(a.string("bucket", null));
                long span = last - first + 1;
                if (size == 0) {
                    size = NICE[NICE.length - 1];
                    for (long s : NICE) {
                        if (Math.ceilDiv(span, s) <= 36) {
                            size = s;
                            break;
                        }
                    }
                }
                long t0 = Math.floorDiv(first, size) * size; // whole minutes, hours… (as the log writes times)
                int buckets = (int) Math.min(Integer.MAX_VALUE, Math.floorDiv(last - t0, size) + 1);
                if (buckets > MAX_BUCKETS) {
                    throw new Tool.Failure(n(buckets) + " buckets of " + bucketName(size) + " is too many (at most "
                            + MAX_BUCKETS + "). Use a bigger bucket or a shorter from_time … to_time.");
                }
                // what to count too: lines with the query and/or of the level
                String query = a.string("query", null);
                String level = a.string("level", null);
                FilterResult matching = null;
                String matchName = null;
                if ((query != null && !query.isBlank()) || (level != null && !level.isBlank())) {
                    List<FilterEngine.Spec> specs = new ArrayList<>();
                    Formula f = null;
                    List<String> names = new ArrayList<>();
                    if (query != null && !query.isBlank()) {
                        specs.add(new FilterEngine.Spec('A', query(query, a, "query")));
                        f = new Formula.Ref('A');
                        names.add("“" + query + "”");
                    }
                    if (level != null && !level.isBlank()) {
                        List<String> lv = levels(new Tool.Args(Map.of("level", level)), "level");
                        f = and(f, levelFilter(lv, log.formats().columns(), specs, (char) 0x200));
                        names.add(String.join(", ", lv));
                    }
                    Built b = new Built(specs, f, names, 0, Long.MAX_VALUE);
                    String key = w.handleOf(log) + ":timeline:" + b.key() + ":" + doc.generation() + ":" + total;
                    matching = w.await(key, "counting " + String.join(" ", names), () -> run(log, b, 0, false));
                    matchName = String.join(" ", names);
                }
                FilterResult errors = log.errorLines();
                FilterResult warnings = log.warningLines();
                long[] at = new long[buckets + 1]; // first line of each bucket, and the end
                for (int i = 0; i <= buckets; i++) {
                    long t = t0 + i * size;
                    at[i] = i == 0 && !a.has("from_time") ? 0
                            : i == buckets && !a.has("to_time") ? total : LogTools.lineAtOrAfter(log, t);
                    if (i > 0 && at[i] < at[i - 1]) {
                        at[i] = at[i - 1]; // times that go back a little: never a negative count
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                }
                boolean oneDay = Instant.ofEpochMilli(t0).atZone(ZoneOffset.UTC).toLocalDate()
                        .equals(Instant.ofEpochMilli(t0 + (long) buckets * size - 1).atZone(ZoneOffset.UTC).toLocalDate());
                StringBuilder out = new StringBuilder();
                out.append("Lines per ").append(bucketName(size)).append(", ").append(stamp(t0, size)).append(" … ")
                        .append(stamp(t0 + (long) (buckets - 1) * size, size)).append(" (")
                        .append(buckets).append(buckets == 1 ? " bucket" : " buckets").append(", times as the log writes them)")
                        .append(oneDay ? ", on " + Instant.ofEpochMilli(t0).atZone(ZoneOffset.UTC).toLocalDate() : "")
                        .append(":\n");
                out.append("time | lines | errors | warnings").append(matchName == null ? "" : " | " + matchName).append('\n');
                long busiest = -1;
                long busiestN = -1;
                long worst = -1;
                long worstN = 0;
                int emptyFrom = -1;
                for (int i = 0; i <= buckets; i++) {
                    long lines = i < buckets ? at[i + 1] - at[i] : 0;
                    long mt = i < buckets && matching != null ? countIn(matching, at[i], at[i + 1]) : 0;
                    boolean empty = i < buckets && lines == 0;
                    if (empty) {
                        if (emptyFrom < 0) {
                            emptyFrom = i;
                        }
                        continue;
                    }
                    if (emptyFrom >= 0) {
                        int nEmpty = i - emptyFrom;
                        out.append(label(t0 + emptyFrom * size, oneDay, size));
                        if (nEmpty > 1) {
                            out.append(" … ").append(label(t0 + (i - 1) * size, oneDay, size));
                        }
                        out.append(" | nothing logged").append(nEmpty > 1 ? " (" + nEmpty + " buckets)" : "").append('\n');
                        emptyFrom = -1;
                    }
                    if (i == buckets) {
                        break;
                    }
                    long er = countIn(errors, at[i], at[i + 1]);
                    long wa = countIn(warnings, at[i], at[i + 1]);
                    out.append(label(t0 + i * size, oneDay, size)).append(" | ").append(n(lines)).append(" | ").append(n(er))
                            .append(" | ").append(n(wa));
                    if (matching != null) {
                        out.append(" | ").append(n(mt));
                    }
                    out.append('\n');
                    if (lines > busiestN) {
                        busiestN = lines;
                        busiest = i;
                    }
                    if (er > worstN) {
                        worstN = er;
                        worst = i;
                    }
                }
                if (busiest >= 0) {
                    out.append("Busiest: ").append(label(t0 + busiest * size, oneDay, size)).append(" (").append(n(busiestN))
                            .append(" lines, from line ").append(line(at[(int) busiest])).append(").");
                }
                if (worst >= 0) {
                    out.append(" Most errors: ").append(label(t0 + worst * size, oneDay, size)).append(" (").append(n(worstN))
                            .append(", from line ").append(line(at[(int) worst])).append(").");
                } else {
                    out.append(" No errors.");
                }
                return w.finish(out.toString().stripTrailing(), maxChars(a));
            });

    /** A bucket's start: the time alone when every bucket is on one day. */
    private static String label(long t, boolean oneDay, long size) {
        String s = stamp(t, size);
        return oneDay && s.length() > 11 ? s.substring(11) : s;
    }

    /** A bucket's start, without the seconds when buckets are whole minutes. */
    private static String stamp(long t, long size) {
        String s = time(t);
        return size % 60_000 == 0 && s.endsWith(":00") && s.length() == 19 ? s.substring(0, 16) : s;
    }

    // ---------------------------------------------------------------- follow

    /** The generation of each document when an agent last followed it (a new one: replaced or cut). */
    private static final Map<LogDocument, Integer> SEEN = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static final Tool FOLLOW = new Def("follow", "New lines of a growing log",
            """
            Reads what a growing log (one a program is still writing) added after a line you already have, \
            like tail -f: Logsift reads the file again, and can wait for new lines. Use it to watch a service \
            while something is being tried, or to check whether an error comes back. Without after_line it \
            gives the last lines and the line count, to start from.
            How to use it: call follow with log only; note the "after_line=N" it ends with; later call \
            follow with that after_line (and wait_seconds=30 to wait for something to happen); repeat with the \
            new after_line each answer gives.
            The answer: the new lines with their 1-based numbers, the log's line count now, and "Next: \
            after_line=N". If the file was replaced or cut since (log rotation), it says so and starts again \
            from line 1 of the new file.
            Mistakes to avoid: after_line is the last line you have (the answer starts at after_line + 1), not \
            the first one you want; for a log that isn't growing, use read_lines.""",
            new Schema().string("log", LOG_ARG, true)
                    .integer("after_line", "The last line you have already (1-based): the answer starts after it. "
                            + "Leave it out to get the last lines. Example: 1200.")
                    .integer("wait_seconds", "If nothing new is there yet, wait this many seconds for new lines "
                            + "(0 to 60, default 0: answer at once).")
                    .integer("limit", "Most lines shown (default 100 with after_line, 20 without; at most 500).")
                    .integer("max_chars", "Longest answer (default 24000).").build(),
            (a, w) -> {
                LogAnalysis log = ready(a, w);
                LogDocument doc = log.document();
                int wait = (int) a.number("wait_seconds", 0, 0, 60);
                boolean given = a.has("after_line");
                long after = given ? a.number("after_line", 0, 0, Long.MAX_VALUE) : 0;
                int limit = (int) a.number("limit", given ? 100 : 20, 1, 500);
                Integer seen = SEEN.get(doc);
                refresh(doc);
                long deadline = System.currentTimeMillis() + wait * 1000L;
                while (given && doc.lineCount() <= after && doc.generation() == (seen == null ? doc.generation() : seen)
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250);
                    refresh(doc);
                }
                log.awaitReady(INTERRUPTED);
                long total = doc.lineCount();
                StringBuilder out = new StringBuilder();
                boolean replaced = seen != null && seen != doc.generation();
                SEEN.put(doc, doc.generation());
                long from;
                if (!given) {
                    from = Math.max(0, total - limit);
                    out.append("The log has ").append(n(total)).append(" lines now");
                    out.append(total == 0 ? ".\n" : "; the last ones:\n");
                } else if (replaced || after > total) {
                    from = 0;
                    out.append("The file was replaced or cut since line ").append(n(after))
                            .append(" (log rotation, or it was emptied): it has ").append(n(total))
                            .append(" lines now, so this starts again from line 1.\n");
                } else if (after == total) {
                    out.append("No new lines after line ").append(n(after));
                    if (wait > 0) {
                        out.append(" (waited ").append(wait).append(" s)");
                    }
                    return w.finish(out.append(". The log has ").append(n(total))
                            .append(" lines. Next: after_line=").append(total).toString(), maxChars(a));
                } else {
                    from = after;
                    out.append(n(total - after)).append(total - after == 1 ? " new line" : " new lines")
                            .append(" after line ").append(n(after)).append(" (the log has ").append(n(total))
                            .append(" lines now):\n");
                }
                int k = (int) Math.min(limit, total - from);
                String[] lines = new String[k];
                doc.readLines(from, k, lines);
                for (int i = 0; i < k; i++) {
                    out.append(line(from + i)).append(": ").append(cut(lines[i], 300)).append('\n');
                }
                long end = from + k;
                if (end < total) {
                    out.append("… ").append(n(total - end)).append(" more. Next: after_line=").append(end);
                } else {
                    out.append("Next: after_line=").append(end);
                }
                return w.finish(out.toString().stripTrailing(), maxChars(a));
            });

    /** Reads the file again now (growth, or a new file in its place) and waits for that. */
    private static void refresh(LogDocument doc) throws InterruptedException {
        doc.requestRefresh();
        doc.awaitIdle(5_000);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }
}
