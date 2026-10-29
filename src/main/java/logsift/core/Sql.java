package logsift.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * SQL over tables and logs, with DuckDB. A CSV or TSV file is a view read in place; a log is
 * loaded once into a table (by Logsift's own reading: its line numbers, times and columns) and
 * kept up to date as it grows. Everything is read-only: a query runs as
 * {@code SELECT * FROM (query) LIMIT n}, so nothing else (COPY, ATTACH, INSTALL, SET…) can run;
 * files can be read only in the folders given, and nothing on the network.
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
     * @param readable folders whose files queries may read (the log's, the user's home)
     * @param temp     where DuckDB may spill to disk when a query needs more memory
     */
    public record Options(List<Path> readable, Path temp) {}

    /** A query's answer: column names and types, rows (at most the limit), and whether there were more. */
    public record Result(List<String> columns, List<String> types, List<Object[]> rows, boolean more, long millis) {}

    private final Connection conn;
    private final Path temp;

    private Sql(Connection conn, Path temp) {
        this.conn = conn;
        this.temp = temp;
    }

    public static Sql open(Options o) throws SQLException, IOException {
        if (!available()) {
            throw new SQLException(unavailableReason());
        }
        Path temp = o.temp() == null ? Files.createTempDirectory("logsift-sql-") : Files.createDirectories(o.temp());
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            long max = physicalMemory();
            long limit = Math.max(256L << 20, Math.min(4L << 30, max > 0 ? max * 3 / 10 : 1L << 30));
            s.execute("SET memory_limit='" + (limit >> 20) + "MB'");
            s.execute("SET threads=" + Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors() - 1)));
            s.execute("SET temp_directory='" + quote(temp.toString()) + "'");
            s.execute("SET autoinstall_known_extensions=false");
            s.execute("SET autoload_known_extensions=false");
            StringBuilder dirs = new StringBuilder();
            List<Path> readable = new ArrayList<>(o.readable());
            readable.add(temp);
            for (Path p : readable) {
                dirs.append(dirs.length() == 0 ? "" : ", ").append('\'').append(quote(p.toAbsolutePath().normalize().toString()))
                        .append('\'');
            }
            s.execute("SET allowed_directories=[" + dirs + "]");
            s.execute("SET enable_external_access=false");
            s.execute("SET lock_configuration=true");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return new Sql(c, temp);
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
    public void csv(String name, Path file, char delimiter, List<String> columns, java.nio.charset.Charset charset)
            throws SQLException {
        boolean header = columns != null;
        String enc = charset == null ? "utf-8" : switch (charset.name().toUpperCase(Locale.ROOT)) {
            case "ISO-8859-1", "WINDOWS-1252", "US-ASCII" -> charset.name().equalsIgnoreCase("US-ASCII") ? "utf-8" : "latin-1";
            case "UTF-16", "UTF-16LE", "UTF-16BE" -> "utf-16";
            default -> "utf-8";
        };
        String src = "read_csv('" + quote(file.toAbsolutePath().toString()) + "', delim='" + quote(String.valueOf(delimiter))
                + "', header=" + header + ", encoding='" + enc + "', sample_size=20480, strict_mode=false";
        // not strict: real files mix line ends (\n and \r\n), which the strict reader refuses
        String view = "CREATE OR REPLACE VIEW " + ident(name) + " AS SELECT row_number() OVER () + " + (header ? 1 : 0)
                + " AS line, * FROM ";
        try (Statement s = conn.createStatement()) {
            s.execute(view + src + ")");
            if (!readsHeader(s.executeQuery("SELECT * FROM " + ident(name) + " LIMIT 0"), columns)) {
                throw new SQLException("the header was read as values");
            }
        } catch (SQLException e) {
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
            try (Statement s = conn.createStatement()) {
                s.execute(view + (names.isEmpty() ? src : src.replace(", header=true", names)) + ", null_padding=true)");
            }
        }
    }

    /** Whether DuckDB found the header's names (it takes a header of another width for values). */
    private static boolean readsHeader(ResultSet rs, List<String> columns) throws SQLException {
        try (rs) {
            if (columns == null || columns.isEmpty()) {
                return true;
            }
            ResultSetMetaData md = rs.getMetaData();
            java.util.Set<String> found = new java.util.HashSet<>();
            for (int i = 2; i <= md.getColumnCount(); i++) {
                found.add(md.getColumnLabel(i).strip().toLowerCase(Locale.ROOT));
            }
            return md.getColumnCount() - 1 == columns.size()
                    && columns.stream().allMatch(c -> found.contains(c.strip().toLowerCase(Locale.ROOT))
                            || c.matches("column \\d+"));
        }
    }

    /** A log loaded into a table; {@link #update} adds what was written since. */
    public final class LogTable {
        final String name;
        final LogDocument doc;
        final LogFormat format;
        final TimeFormat times;
        final EntryFormat entries;
        final List<String> columns;
        /** A table's (CSV's) column kinds; null for a log. */
        final Pivot.Fields fields;
        long loaded;
        int generation;

        LogTable(String name, LogDocument doc, LogFormat format, TimeFormat times, EntryFormat entries) {
            this(name, doc, format, times, entries, null);
        }

        LogTable(String name, LogDocument doc, LogFormat format, TimeFormat times, EntryFormat entries,
                 Pivot.Fields fields) {
            this.name = name;
            this.doc = doc;
            this.format = format;
            this.times = times;
            this.entries = entries;
            this.fields = fields;
            List<String> cols = new ArrayList<>();
            if (fields != null) {
                cols.addAll(fields.titles());
            } else if (format != null) {
                for (String c : format.columns()) {
                    if (!c.equals("time") && !c.equals("line") && !c.equals("text") && !c.equals("starts")) {
                        cols.add(c);
                    }
                }
            }
            this.columns = cols;
            this.generation = doc.generation();
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

        public long loaded() {
            return loaded;
        }

        /**
         * Brings the table up to date: the lines written since, or all of them again if the file
         * was replaced. Returns false if stopped.
         */
        public synchronized boolean update(BooleanSupplier stop, DoubleConsumer progress) throws SQLException {
            if (doc.generation() != generation) {
                try (Statement s = conn.createStatement()) {
                    s.execute("DELETE FROM " + ident(name));
                }
                loaded = 0;
                generation = doc.generation();
            }
            long total = doc.lineCount();
            if (total <= loaded) {
                return true;
            }
            boolean done = load(this, loaded, total, stop, progress);
            if (done) {
                loaded = total;
            }
            return done;
        }
    }

    /**
     * A log as a table {@code name}: {@code line} (1-based, as Logsift shows it), {@code starts}
     * (the line starts an entry: continuation lines of a stack trace don't), {@code time} (as
     * Logsift reads it), the format's columns ({@code level}, {@code thread}, {@code logger},
     * {@code message}…), and {@code text}, the whole line.
     */
    public LogTable log(String name, LogDocument doc, LogFormat format, TimeFormat times, EntryFormat entries)
            throws SQLException {
        LogTable t = new LogTable(name, doc, format, times, entries);
        StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE " + ident(name)
                + " (line BIGINT, starts BOOLEAN, time TIMESTAMP");
        for (String c : t.columns) {
            ddl.append(", ").append(ident(c)).append(" VARCHAR");
        }
        ddl.append(", text VARCHAR)");
        try (Statement s = conn.createStatement()) {
            s.execute(ddl.toString());
        }
        return t;
    }

    /**
     * A table (CSV, TSV) loaded by Logsift's own reading, for when DuckDB may not read files
     * itself (agents): {@code line}, then the columns by their titles, numbers as numbers and
     * times as times (as Pivot reads them).
     */
    public LogTable table(String name, LogDocument doc, LogFormat format, Pivot.Fields fields) throws SQLException {
        LogTable t = new LogTable(name, doc, format, null, null, fields);
        StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE " + ident(name) + " (line BIGINT");
        java.util.Set<String> used = new java.util.HashSet<>(java.util.Set.of("line"));
        for (int c = 0; c < fields.titles().size(); c++) {
            String title = fields.titles().get(c);
            String n = title;
            for (int k = 2; !used.add(n.toLowerCase(Locale.ROOT)); k++) {
                n = title + "_" + k; // two columns with one name
            }
            t.columns.set(c, n);
            String type = switch (fields.kind(c)) {
                case NUMBER -> "DOUBLE";
                case TIME -> "TIMESTAMP";
                case TEXT -> "VARCHAR";
            };
            ddl.append(", ").append(ident(n)).append(' ').append(type);
        }
        ddl.append(")");
        try (Statement s = conn.createStatement()) {
            s.execute(ddl.toString());
        }
        return t;
    }

    /** Makes {@code view} show a table: queries say {@code log} or {@code t} whichever file they are about. */
    public void alias(String view, LogTable t) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE OR REPLACE VIEW " + ident(view) + " AS SELECT * FROM " + ident(t.name));
        }
    }

    /** Lines read and appended at a time, each block by one of several threads. */
    static final int BLOCK = 8192;

    private boolean load(LogTable t, long from, long to, BooleanSupplier stop, DoubleConsumer progress)
            throws SQLException {
        int threads = (to - from) < 4L * BLOCK ? 1 : Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        AtomicLong next = new AtomicLong(from);
        AtomicLong done = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        SQLException[] failed = {null};
        RuntimeException[] bug = {null};
        org.duckdb.DuckDBConnection base = conn.unwrap(org.duckdb.DuckDBConnection.class);
        // a table's rows whole (a quoted value may go over lines); a log's lines
        ColumnProfile.Lines lines = t.fields != null ? CsvRows.reader(t.doc, t.format) : t.doc::readLines;
        int[] index = new int[t.columns.size()];
        for (int i = 0; i < index.length; i++) {
            index[i] = t.fields != null ? i : t.format.columns().indexOf(t.columns.get(i));
        }
        for (int w = 0; w < threads; w++) {
            Connection own = base.duplicate();
            Thread th = new Thread(() -> {
                String[] buf = new String[BLOCK];
                try (own; org.duckdb.DuckDBAppender ap = own.unwrap(org.duckdb.DuckDBConnection.class)
                        .createAppender(org.duckdb.DuckDBConnection.DEFAULT_SCHEMA, t.name)) {
                    long first;
                    while ((first = next.getAndAdd(BLOCK)) < to) {
                        if (stop.getAsBoolean()) {
                            return;
                        }
                        int k = (int) Math.min(BLOCK, to - first);
                        lines.read(first, k, buf);
                        for (int i = 0; i < k; i++) {
                            String text = buf[i] == null ? "" : buf[i];
                            String[] cells = t.format == null ? null : t.format.split(text, null);
                            if (t.fields != null) {
                                if (cells != null) {
                                    tableRow(ap, t, first + i + 1, cells);
                                }
                                continue; // the header, or a piece of a value over several lines
                            }
                            long ms = t.times == null ? TimeFormat.NONE : t.times.parse(text);
                            ap.beginRow();
                            ap.append(first + i + 1);
                            ap.append(t.entries == null || t.entries.startsEntry(text));
                            if (ms == TimeFormat.NONE) {
                                ap.appendNull();
                            } else {
                                ap.append(LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000),
                                        Math.floorMod(ms, 1000) * 1_000_000, ZoneOffset.UTC));
                            }
                            for (int c : index) {
                                String v = cells == null || c < 0 || c >= cells.length ? null : cells[c];
                                if (v == null) {
                                    ap.appendNull();
                                } else {
                                    ap.append(v);
                                }
                            }
                            ap.append(text);
                            ap.endRow();
                        }
                        long d = done.addAndGet(k);
                        if (progress != null) {
                            progress.accept((double) d / Math.max(1, to - from));
                        }
                    }
                } catch (SQLException e) {
                    synchronized (failed) {
                        failed[0] = e;
                    }
                } catch (RuntimeException e) {
                    synchronized (failed) {
                        bug[0] = e;
                    }
                }
            }, "logsift-sql-load");
            th.setDaemon(true);
            workers.add(th);
            th.start();
        }
        for (Thread th : workers) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (bug[0] != null) {
            throw bug[0];
        }
        if (failed[0] != null) {
            throw failed[0];
        }
        if (stop.getAsBoolean()) {
            try (Statement s = conn.createStatement()) {
                s.execute("DELETE FROM " + ident(t.name) + " WHERE line > " + from); // a half load is no load
            }
            return false;
        }
        return true;
    }

    private static void tableRow(org.duckdb.DuckDBAppender ap, LogTable t, long line, String[] cells) throws SQLException {
        ap.beginRow();
        ap.append(line);
        for (int c = 0; c < t.columns.size(); c++) {
            String v = c < cells.length && cells[c] != null ? cells[c].strip() : "";
            switch (t.fields.kind(c)) {
                case NUMBER -> {
                    double d = v.isEmpty() ? Double.NaN : Pivot.number(v);
                    if (Double.isNaN(d)) {
                        ap.appendNull();
                    } else {
                        ap.append(d);
                    }
                }
                case TIME -> {
                    TimeFormat tf = c < t.fields.times().size() ? t.fields.times().get(c) : null;
                    long ms = tf == null || v.isEmpty() ? TimeFormat.NONE : tf.parse(v);
                    if (ms == TimeFormat.NONE) {
                        ap.appendNull();
                    } else {
                        ap.append(LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000), Math.floorMod(ms, 1000) * 1_000_000,
                                ZoneOffset.UTC));
                    }
                }
                case TEXT -> {
                    if (v.isEmpty()) {
                        ap.appendNull();
                    } else {
                        ap.append(v);
                    }
                }
            }
        }
        ap.endRow();
    }

    // ---------------------------------------------------------------- queries

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

    public synchronized Result query(String sql, int maxRows, BooleanSupplier stop) throws SQLException {
        String q = single(sql);
        String wrapped = "SELECT * FROM (\n" + q + "\n) AS logsift_query LIMIT " + ((long) maxRows + 1);
        long t0 = System.nanoTime();
        try (Statement st = conn.createStatement()) {
            Thread watcher = null;
            if (stop != null) {
                watcher = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (stop.getAsBoolean()) {
                            try {
                                st.cancel();
                            } catch (SQLException e) {
                                // finished meanwhile
                            }
                            return;
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }, "logsift-sql-cancel");
                watcher.setDaemon(true);
                watcher.start();
            }
            try (ResultSet rs = st.executeQuery(wrapped)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> cols = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    cols.add(md.getColumnLabel(i));
                    types.add(md.getColumnTypeName(i).toLowerCase(Locale.ROOT));
                }
                List<Object[]> rows = new ArrayList<>();
                boolean more = false;
                while (rs.next()) {
                    if (rows.size() >= maxRows) {
                        more = true;
                        break;
                    }
                    Object[] r = new Object[n];
                    for (int i = 0; i < n; i++) {
                        r[i] = value(rs.getObject(i + 1));
                    }
                    rows.add(r);
                }
                return new Result(cols, types, rows, more, (System.nanoTime() - t0) / 1_000_000);
            } finally {
                if (watcher != null) {
                    watcher.interrupt();
                }
            }
        } catch (SQLException e) {
            if (stop != null && stop.getAsBoolean()) {
                throw new SQLException("Stopped.");
            }
            throw friendly(e);
        }
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

    /** DuckDB's message, without the parts about our wrapping. */
    private static SQLException friendly(SQLException e) {
        String m = e.getMessage() == null ? "The query failed." : e.getMessage();
        if (m.contains("logsift_query") || m.contains("syntax error at or near \"COPY\"") || m.matches("(?s).*syntax error at or near \"(?:COPY|ATTACH|INSTALL|LOAD|SET|CREATE|INSERT|UPDATE|DELETE|DROP|EXPORT|IMPORT|PRAGMA|CALL)\".*")) {
            return new SQLException("Only queries can run here (SELECT, WITH, FROM, PIVOT, SUMMARIZE, DESCRIBE): "
                    + "Logsift only reads.", e);
        }
        return new SQLException(m.replace("\nLINE 2:", "\nLINE 1:"), e);
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            // closing anyway
        }
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
