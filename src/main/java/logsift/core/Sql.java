package logsift.core;

import io.quackjvm.agent.file.FileTable;
import io.quackjvm.agent.file.FileTables;
import io.quackjvm.agent.file.RowParser;
import io.quackjvm.agent.file.RowSource;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.session.QuerySession;
import io.quackjvm.core.duckdb.ColumnDef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * SQL over tables and logs, with DuckDB, in a quackjvm {@link QuerySession}: every table by its
 * name, joined at will. A CSV or TSV file is a view read in place; a log is a quackjvm
 * {@link FileTable} (Logsift's own reading: its line numbers, times and columns), kept up to date
 * as it grows, and kept on disk between runs when a cache folder is given. Everything is
 * read-only: DuckDB's parser (through quackjvm's guard) lets only a single query through, and it
 * runs as {@code SELECT * FROM (query) LIMIT n}, so nothing else (COPY, ATTACH, INSTALL, SET, a
 * WITH that deletes…) can run; files can be read only in the folders given, and nothing on the
 * network.
 */
public final class Sql implements AutoCloseable {

    private static volatile String unavailable; // null: not tried yet; "": works
    private static final Object TRY = new Object();

    /** Whether DuckDB works here (its native library for this system loads). */
    public static boolean available() {
        if (unavailable == null) {
            synchronized (TRY) {
                if (unavailable == null) {
                    try {
                        Class.forName("org.duckdb.DuckDBDriver");
                        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
                            c.isValid(1);
                        }
                        unavailable = "";
                    } catch (Throwable e) { // UnsatisfiedLinkError: no library for this system
                        unavailable = "DuckDB can't run on this system (" + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage().lines().findFirst().orElse("")) + ")";
                    }
                }
            }
        }
        return unavailable.isEmpty();
    }

    /**
     * Whether DuckDB works, if that's known yet (loading its library takes a moment): true,
     * false, or null while not tried. Never waits.
     */
    public static Boolean availableIfKnown() {
        String u = unavailable;
        return u == null ? null : u.isEmpty();
    }

    /** Finds out whether DuckDB works, on a background thread; then runs {@code then} there. */
    public static void checkInBackground(Runnable then) {
        Thread t = new Thread(() -> {
            available();
            if (then != null) {
                then.run();
            }
        }, "logsift-sql-check");
        t.setDaemon(true);
        t.start();
    }

    /** Why SQL isn't available; "" when it is. */
    public static String unavailableReason() {
        available();
        return unavailable;
    }

    /**
     * @param readable   folders whose files queries may read (the log's, the user's home)
     * @param temp       where DuckDB may spill to disk when a query needs more memory
     * @param cache      where logs are kept between runs, or null to keep them in memory only
     * @param cacheLimit how much room the caches in that folder may take
     */
    public record Options(List<Path> readable, Path temp, Path cache, long cacheLimit) {
        public Options(List<Path> readable, Path temp) {
            this(readable, temp, null, 0);
        }
    }

    /** A query's answer: column names and types, rows (at most the limit), and whether there were more. */
    public record Result(List<String> columns, List<String> types, List<Object[]> rows, boolean more, long millis) {}

    private final QuerySession session;
    private final Path temp;
    private final Path cache;
    private final long cacheLimit;
    private final List<LogTable> tables = new ArrayList<>();

    private Sql(QuerySession session, Path temp, Path cache, long cacheLimit) {
        this.session = session;
        this.temp = temp;
        this.cache = cache;
        this.cacheLimit = cacheLimit;
    }

    /** What a query must be: one that only reads (PIVOT too, which DuckDB's parser can't vouch for). */
    static final io.quackjvm.agent.guard.QueryPolicy CHECK = io.quackjvm.agent.guard.QueryPolicy.readOnly()
            .allowUnverified(io.quackjvm.agent.guard.QueryPolicy.PIVOT);

    /**
     * SQL in a session of its own, hardened from the moment it opens: files only in the folders
     * given (not the spill folder, which DuckDB uses anyway), no network, no extensions, no settings
     * changed.
     */
    public static Sql open(Options o) throws SQLException, IOException {
        if (!available()) {
            throw new SQLException(unavailableReason());
        }
        Path temp = o.temp() == null ? Files.createTempDirectory("logsift-sql-") : Files.createDirectories(o.temp());
        long max = physicalMemory();
        long limit = Math.max(256L << 20, Math.min(4L << 30, max > 0 ? max * 3 / 10 : 1L << 30));
        io.quackjvm.agent.guard.Hardening.Options h = io.quackjvm.agent.guard.Hardening.options()
                .tempFolder(temp.toString()).memoryLimit((limit >> 20) + "MB")
                .threads(Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors() - 1)));
        for (Path p : o.readable()) {
            h = h.allowedFolders(p.toString());
        }
        try {
            return new Sql(QuerySession.open(h, CHECK), temp, o.cache(), o.cacheLimit());
        } catch (RuntimeException e) {
            throw new SQLException("SQL couldn't be locked down, so it isn't opened: " + e.getMessage(), e);
        }
    }

    private static long physicalMemory() {
        try {
            return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** A string for inside '…' in SQL. */
    static String quote(String s) {
        return s.replace("'", "''");
    }

    /** A name for SQL: "order id" → "order id" in double quotes. */
    public static String ident(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // ---------------------------------------------------------------- tables

    /**
     * A CSV or TSV file as a view, read in place by DuckDB (with {@code line}: the file's line
     * number of each row, as Logsift shows it).
     */
    public void csv(String name, Path file, char delimiter, boolean header, java.nio.charset.Charset charset)
            throws SQLException {
        csv(name, file, delimiter, header ? List.of() : null, charset);
    }

    /**
     * A CSV file as a view; {@code columns} are its header's names as Logsift found them (null
     * for no header; empty to leave them to DuckDB), used when DuckDB cannot read the header.
     */
    public synchronized void csv(String name, Path file, char delimiter, List<String> columns,
                                 java.nio.charset.Charset charset) throws SQLException {
        boolean header = columns != null;
        String enc = charset == null ? "utf-8" : switch (charset.name().toUpperCase(Locale.ROOT)) {
            case "ISO-8859-1", "WINDOWS-1252", "US-ASCII" -> charset.name().equalsIgnoreCase("US-ASCII") ? "utf-8" : "latin-1";
            case "UTF-16", "UTF-16LE", "UTF-16BE" -> "utf-16";
            default -> "utf-8";
        };
        String src = "read_csv('" + quote(file.toAbsolutePath().toString()) + "', delim='" + quote(String.valueOf(delimiter))
                + "', header=" + header + ", encoding='" + enc + "', sample_size=20480, strict_mode=false";
        // not strict: real files mix line ends (\n and \r\n), which the strict reader refuses
        String view = "SELECT row_number() OVER () + " + (header ? 1 : 0) + " AS line, * FROM ";
        try {
            session.register(name, view + src + ")");
            io.quackjvm.agent.guard.GuardedResult r = session.run("SELECT * FROM " + ident(name) + " LIMIT 0");
            if (!readsHeader(r.getColumns(), columns)) {
                throw new SQLException("the header was read as values");
            }
        } catch (SQLException | RuntimeException e) {
            // rows shorter or longer than the header (a column no row fills, a separator more at
            // each row's end): padded with nulls. Only then, as that reads on one thread
            String names = "";
            if (columns != null && !columns.isEmpty()) {
                // DuckDB takes a header shorter or longer than the rows for a row of values:
                // the header is skipped and its names given instead
                StringBuilder sb = new StringBuilder(", header=false, skip=1, names=[");
                java.util.Set<String> used = new java.util.HashSet<>();
                for (int i = 0; i < columns.size(); i++) {
                    String c = columns.get(i);
                    for (int k = 2; !used.add(c.toLowerCase(Locale.ROOT)); k++) {
                        c = columns.get(i) + "_" + k;
                    }
                    sb.append(i == 0 ? "" : ", ").append('\'').append(quote(c)).append('\'');
                }
                names = sb.append(']').toString();
            }
            try {
                session.register(name, view + (names.isEmpty() ? src : src.replace(", header=true", names))
                        + ", null_padding=true)");
            } catch (RuntimeException again) {
                throw failure(again);
            }
        }
    }

    /** Whether DuckDB found the header's names (it takes a header of another width for values). */
    private static boolean readsHeader(List<String> found, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return true;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 1; i < found.size(); i++) {
            names.add(found.get(i).strip().toLowerCase(Locale.ROOT));
        }
        return found.size() - 1 == columns.size()
                && columns.stream().allMatch(c -> names.contains(c.strip().toLowerCase(Locale.ROOT))
                        || c.matches("column \\d+"));
    }

    /** A log or table loaded by Logsift's own reading, by its name in the session; {@link #update} adds what was written since. */
    public final class LogTable {
        final String name;
        final LogFormat format;
        final List<String> columns;
        /** A table's (CSV's) column kinds; null for a log. */
        final Pivot.Fields fields;
        final FileTable table;

        LogTable(String name, LogFormat format, List<String> columns, Pivot.Fields fields, FileTable table) {
            this.name = name;
            this.format = format;
            this.columns = columns;
            this.fields = fields;
            this.table = table;
        }

        public String name() {
            return name;
        }

        /** The layout the table was made with: another one needs a new table. */
        public LogFormat format() {
            return format;
        }

        /** The table's columns, as SQL sees them. */
        public List<String> sqlColumns() {
            if (fields != null) {
                List<String> out = new ArrayList<>(List.of("line"));
                out.addAll(columns);
                return out;
            }
            List<String> out = new ArrayList<>(List.of("line", "starts", "time"));
            out.addAll(columns);
            out.add("text");
            return out;
        }

        /** The file's lines read into it so far. */
        public long loaded() {
            return table.rowsLoaded();
        }

        /**
         * Brings the table up to date: the lines written since, or all of them again if the file
         * was replaced. Returns false if stopped (what was read by then is kept, and the next update
         * goes on from there).
         */
        public boolean update(BooleanSupplier stop, DoubleConsumer progress) throws SQLException {
            try {
                table.update(stop == null ? null : CancelSignal.from(stop), progress);
                return true;
            } catch (QueryCancelled e) {
                return false;
            } catch (RuntimeException e) {
                throw failure(e);
            }
        }
    }

    /** A document's lines, for a table over them: whole lines, and its generation, which changes when the file is replaced. */
    private record Lines(LogDocument doc, ColumnProfile.Lines lines) implements RowSource {
        @Override
        public long rowCount() {
            return doc.lineCount();
        }

        @Override
        public void read(long first, int n, String[] into) {
            lines.read(first, n, into);
        }

        @Override
        public Object identity() {
            return doc.generation();
        }
    }

    /** Changes when a log's stored layout changes, so older caches aren't read. */
    static final String LOG_VERSION = "logsift-log-1";

    /**
     * A log as a table {@code name}: {@code line} (1-based, as Logsift shows it), {@code starts}
     * (the line starts an entry: continuation lines of a stack trace don't), {@code time} (as
     * Logsift reads it), the format's columns ({@code level}, {@code thread}, {@code logger},
     * {@code message}…), and {@code text}, the whole line. Every line is a row, a blank one too.
     */
    public synchronized LogTable log(String name, LogDocument doc, LogFormat format, TimeFormat times, EntryFormat entries)
            throws SQLException {
        List<String> cols = new ArrayList<>();
        if (format != null) {
            for (String c : format.columns()) {
                if (!c.equals("time") && !c.equals("line") && !c.equals("text") && !c.equals("starts")) {
                    cols.add(c);
                }
            }
        }
        List<ColumnDef> defs = new ArrayList<>(List.of(new ColumnDef("line", Long.class),
                new ColumnDef("starts", Boolean.class), new ColumnDef("time", LocalDateTime.class)));
        for (String c : cols) {
            defs.add(new ColumnDef(c, String.class));
        }
        defs.add(new ColumnDef("text", String.class));
        int[] index = new int[cols.size()];
        for (int i = 0; i < index.length; i++) {
            index[i] = format.columns().indexOf(cols.get(i));
        }
        RowParser parser = (raw, rowNo, cells) -> {
            String text = raw == null ? "" : raw;
            String[] split = format == null ? null : format.split(text, null);
            long ms = times == null ? TimeFormat.NONE : times.parse(text);
            cells[0] = rowNo + 1;
            cells[1] = entries == null || entries.startsEntry(text);
            cells[2] = ms == TimeFormat.NONE ? null : LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000),
                    Math.floorMod(ms, 1000) * 1_000_000, ZoneOffset.UTC);
            for (int i = 0; i < index.length; i++) {
                int c = index[i];
                cells[3 + i] = split == null || c < 0 || c >= split.length ? null : split[c];
            }
            cells[cells.length - 1] = text;
        };
        String version = LOG_VERSION + "|" + (format == null ? "" : format.name() + "|" + format.regex() + "|"
                + format.columns() + "|" + (int) format.delimiter()) + "|" + (times == null ? "" : times.name()) + "|"
                + (entries == null ? "" : entries.name());
        FileTable.Builder b = FileTables.builder(doc.path().toAbsolutePath().normalize(), defs,
                new Lines(doc, doc::readLines), parser).name(name).version(version);
        if (cache != null) {
            b.cacheDir(cache).roomLimit(cacheLimit).inMemoryBelow(Long.getLong("logsift.logCacheMinBytes",
                    LOG_IN_MEMORY_BELOW));
        }
        return register(new LogTable(name, format, cols, null, open(b)));
    }

    /** A log smaller than this is kept in memory, not in the cache folder: quick enough to read again. */
    static final long LOG_IN_MEMORY_BELOW = 32L << 20;

    /**
     * A table (CSV, TSV) loaded by Logsift's own reading, for when DuckDB may not read files
     * itself (agents): {@code line}, then the columns by their titles, numbers as numbers and
     * times as times (as Pivot reads them). The header and the lines inside a value that spans
     * lines aren't rows.
     */
    public synchronized LogTable table(String name, LogDocument doc, LogFormat format, Pivot.Fields fields)
            throws SQLException {
        FileTable.Builder b = FileTables.builder(doc.path().toAbsolutePath().normalize(), TableQuery.columns(fields),
                new Lines(doc, CsvRows.reader(doc, format)), TableQuery.parser(format, fields)).name(name)
                .version(TableQuery.VERSION + "|" + fields.kinds() + "|" + String.join("\u0001", format.columns()));
        return register(new LogTable(name, format, tableColumns(fields.titles()), fields, open(b)));
    }

    private static FileTable open(FileTable.Builder b) throws SQLException {
        try {
            return b.open();
        } catch (RuntimeException e) {
            throw new SQLException("The table couldn't be made: " + e.getMessage(), e);
        }
    }

    private LogTable register(LogTable t) throws SQLException {
        try {
            session.register(t.name, t.table);
        } catch (RuntimeException e) {
            t.table.close();
            throw failure(e);
        }
        tables.add(t);
        return t;
    }

    /**
     * A table's columns as SQL names them, by the titles: a name that comes again (in any case)
     * gets {@code _2}, {@code _3}…, and none is {@code line}, the first column.
     */
    public static List<String> tableColumns(List<String> titles) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>(java.util.Set.of("line"));
        for (String title : titles) {
            String n = title;
            for (int k = 2; !used.add(n.toLowerCase(Locale.ROOT)); k++) {
                n = title + "_" + k; // two columns with one name
            }
            out.add(n);
        }
        return out;
    }

    /** A table column's SQL type: numbers, times and text as Pivot reads them. */
    static String sqlType(Pivot.Kind kind) {
        return switch (kind) {
            case NUMBER -> "DOUBLE";
            case TIME -> "TIMESTAMP";
            case TEXT -> "VARCHAR";
        };
    }

    /** The table {@code name} of a CSV: {@code line}, then its columns, typed. */
    static String tableDdl(String name, Pivot.Fields fields) {
        StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE " + ident(name) + " (line BIGINT");
        List<String> names = tableColumns(fields.titles());
        for (int c = 0; c < names.size(); c++) {
            ddl.append(", ").append(ident(names.get(c))).append(' ').append(sqlType(fields.kind(c)));
        }
        return ddl.append(")").toString();
    }

    /** Makes {@code view} show a table: queries say {@code log} or {@code t} whichever file they are about. */
    /**
     * The name a file goes by as a table beside {@code t} or {@code log}: its name without the
     * extension, as SQL writes a name bare ({@code orders-2026.csv} → {@code orders_2026}), and not
     * one of {@code taken} (then {@code orders_2}).
     */
    public static String tableName(Path file, java.util.Set<String> taken) {
        String n = file.getFileName().toString();
        for (String ext : List.of(".gz", ".csv", ".tsv", ".txt", ".log")) {
            if (n.toLowerCase(Locale.ROOT).endsWith(ext) && n.length() > ext.length()) {
                n = n.substring(0, n.length() - ext.length());
            }
        }
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (n.isEmpty() || Character.isDigit(n.charAt(0))) {
            n = "table_" + n;
        }
        String name = n;
        for (int k = 2; taken.contains(name) || name.equals("t") || name.equals("log"); k++) {
            name = n + "_" + k;
        }
        return name;
    }

    /** Takes a table away, and the memory it held (its file was closed). */
    public synchronized void drop(LogTable t) throws SQLException {
        try {
            session.unregister(t.name);
        } catch (RuntimeException e) {
            throw failure(e);
        } finally {
            t.table.close();
            tables.remove(t);
        }
    }

    /** Takes a view away (a table no longer open). */
    public synchronized void dropView(String name) throws SQLException {
        try {
            session.unregister(name);
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    /** As {@link #query(String, LogTable, String, int, BooleanSupplier)}, with several tables by name. */
    public synchronized Result query(java.util.Map<String, LogTable> views, String sql, int maxRows, BooleanSupplier stop)
            throws SQLException {
        for (var e : views.entrySet()) {
            alias(e.getKey(), e.getValue());
        }
        return query(sql, maxRows, stop);
    }

    /** Another name for a table ({@code t}, {@code log}): a query says that whichever file it is about. */
    public synchronized void alias(String view, LogTable t) throws SQLException {
        if (view.equalsIgnoreCase(t.name)) {
            return;
        }
        try {
            session.alias(view, t.name);
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    /**
     * The query as one statement, or an exception saying why not: only one, and the wrapping
     * (see {@link #query}) makes anything that isn't a query fail.
     */
    static String single(String sql) throws SQLException {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.isEmpty()) {
            throw new SQLException("Write a query, for example: SELECT level, count(*) FROM log GROUP BY level");
        }
        String first = firstWord(s);
        if (!QUERY_WORDS.contains(first)) {
            throw new SQLException("Only queries can run here (SELECT, WITH, FROM, PIVOT, SUMMARIZE, DESCRIBE): "
                    + "Logsift only reads" + (first.isEmpty() ? "." : ", and " + first + " isn't a query."));
        }
        // a ';' outside quotes and comments starts a second statement
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!single && !dbl && c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                int nl = s.indexOf('\n', i);
                if (nl < 0) {
                    break;
                }
                i = nl;
                continue;
            }
            if (!single && !dbl && c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 1;
                continue;
            }
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (c == ';' && !single && !dbl) {
                throw new SQLException("One query at a time, please (there is a ';' in the middle).");
            }
        }
        return s;
    }

    /**
     * The query as one statement that only reads, or an exception saying why not: Logsift's own
     * words first, then DuckDB's parser (through quackjvm's guard), which also finds what words
     * can't, such as a {@code WITH} whose part deletes.
     */
    static String check(Connection conn, String sql) throws SQLException {
        String q = single(sql);
        String why;
        try {
            why = io.quackjvm.agent.guard.GuardedQuery.on(conn, io.quackjvm.agent.guard.QueryPolicy.readOnly()
                    .allowUnverified(io.quackjvm.agent.guard.QueryPolicy.PIVOT)).whyRejected(q);
        } catch (RuntimeException e) {
            // not known to only read: not run
            throw new SQLException("The query couldn't be checked: " + e.getMessage(), e);
        }
        if (why != null) {
            throw new SQLException(why.startsWith("Only one statement") ? "One query at a time, please."
                    : "Only queries can run here (SELECT, WITH, FROM, PIVOT, SUMMARIZE, DESCRIBE): Logsift only reads.");
        }
        return q;
    }

    private static final java.util.Set<String> QUERY_WORDS = java.util.Set.of("SELECT", "WITH", "FROM", "PIVOT", "UNPIVOT",
            "SUMMARIZE", "DESCRIBE", "SHOW", "VALUES", "TABLE", "(");

    /** The first word of a statement after comments ("(" for a parenthesized query). */
    static String firstWord(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("--", i)) {
                int nl = s.indexOf('\n', i);
                i = nl < 0 ? s.length() : nl + 1;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
            } else {
                break;
            }
        }
        if (i < s.length() && s.charAt(i) == '(') {
            return "(";
        }
        int j = i;
        while (j < s.length() && Character.isLetter(s.charAt(j))) {
            j++;
        }
        return s.substring(i, j).toUpperCase(Locale.ROOT);
    }

    /**
     * Runs a query, read-only, giving at most {@code maxRows} rows. {@code stop} cancels it
     * (checked while it runs).
     */
    public synchronized Result query(String view, LogTable table, String sql, int maxRows, BooleanSupplier stop)
            throws SQLException {
        alias(view, table);
        return query(sql, maxRows, stop);
    }

    public Result query(String sql, int maxRows, BooleanSupplier stop) throws SQLException {
        String q = single(sql);
        String why;
        try {
            why = session.whyRejected(q);
        } catch (RuntimeException e) {
            throw new SQLException("The query couldn't be checked: " + e.getMessage(), e);
        }
        if (why != null) {
            throw new SQLException(why.startsWith("Only one statement") ? "One query at a time, please."
                    : "Only queries can run here (SELECT, WITH, FROM, PIVOT, SUMMARIZE, DESCRIBE): Logsift only reads.");
        }
        // Stop, polled while it runs; already pressed, nothing runs
        CancelSignal signal = stop == null ? new CancelSignal() : CancelSignal.from(stop);
        try {
            return result(session.run(q, signal, policy(maxRows)));
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    /** The table's description as quackjvm's catalog gives it (for the examples). */
    public io.quackjvm.agent.catalog.TableInfo describe(String name) throws SQLException {
        try {
            return session.describe(name);
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    /**
     * Runs a query on {@code conn}, read-only, giving at most {@code maxRows} rows. {@code stop}
     * cancels it (checked while it runs).
     */
    static Result run(Connection conn, String sql, int maxRows, BooleanSupplier stop) throws SQLException {
        String q = check(conn, sql);
        // Stop, polled while it runs; already pressed, nothing runs
        io.quackjvm.agent.guard.CancelSignal signal = stop == null ? new io.quackjvm.agent.guard.CancelSignal()
                : io.quackjvm.agent.guard.CancelSignal.from(stop);
        try {
            return result(io.quackjvm.agent.guard.GuardedQuery.on(conn, policy(maxRows)).run(q, signal));
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    /** A guarded query's answer as the Query tab and agents show it: types in lower case, values as people read them. */
    static Result result(io.quackjvm.agent.guard.GuardedResult r) {
        List<String> types = new ArrayList<>();
        for (String type : r.getColumnTypes()) {
            types.add(type.toLowerCase(Locale.ROOT));
        }
        List<Object[]> rows = new ArrayList<>(r.getRows().size());
        for (Object[] row : r.getRows()) {
            for (int k = 0; k < row.length; k++) {
                row[k] = value(row[k]);
            }
            rows.add(row);
        }
        return new Result(r.getColumns(), types, rows, r.isTruncated(), r.getTook().toMillis());
    }

    /** Why a guarded query failed, in the words the Query tab shows ("Stopped." when it was stopped). */
    static SQLException failure(RuntimeException e) {
        if (e instanceof io.quackjvm.agent.guard.QueryCancelled) {
            return new SQLException("Stopped.", e);
        }
        if (e instanceof io.quackjvm.agent.guard.QueryFailed) {
            // DuckDB's whole message, its lines numbered as written
            return new SQLException(e.getMessage(), e);
        }
        if (e instanceof io.quackjvm.agent.guard.QueryRejected) {
            return new SQLException("Only queries can run here (SELECT, WITH, FROM, PIVOT, SUMMARIZE, DESCRIBE): "
                    + "Logsift only reads.", e);
        }
        return new SQLException(e.getMessage() == null ? "The query failed." : e.getMessage(), e);
    }

    /**
     * At most {@code maxRows} rows, saying when there were more; no cap on their size, as before;
     * and no time limit worth the name: Stop ends a query (an agent's has its own time).
     */
    static io.quackjvm.agent.guard.QueryPolicy policy(int maxRows) {
        return io.quackjvm.agent.guard.QueryPolicy.readOnly()
                .allowUnverified(io.quackjvm.agent.guard.QueryPolicy.PIVOT)
                .maxRows(Math.max(1, maxRows)).maxBytes(0)
                .whenOver(io.quackjvm.agent.guard.QueryPolicy.Over.TRUNCATE)
                .timeout(java.time.Duration.ofDays(1));
    }

    private static final java.time.format.DateTimeFormatter SECONDS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final java.time.format.DateTimeFormatter MILLIS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** Values as people read them: times without a "T", nothing exotic. */
    private static Object value(Object o) {
        if (o instanceof java.sql.Timestamp ts) {
            LocalDateTime t = ts.toLocalDateTime();
            return t.format(t.getNano() % 1_000_000_000 == 0 ? SECONDS : MILLIS);
        }
        if (o instanceof LocalDateTime t) {
            return t.format(t.getNano() == 0 ? SECONDS : MILLIS);
        }
        if (o instanceof java.time.temporal.TemporalAccessor ta) {
            return o.toString().replace('T', ' ');
        }
        if (o instanceof java.sql.Array || o instanceof java.sql.Struct || o instanceof java.util.Map<?, ?>
                || o instanceof java.util.Collection<?>) {
            return String.valueOf(o);
        }
        return o;
    }

    @Override
    public synchronized void close() {
        try {
            session.close();
        } catch (RuntimeException e) {
            // closing anyway
        }
        for (LogTable t : tables) {
            t.table.close();
        }
        tables.clear();
        try (var walk = Files.walk(temp)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // left for the system's temp cleanup
                }
            });
        } catch (IOException e) {
            // nothing to clean
        }
    }
}
