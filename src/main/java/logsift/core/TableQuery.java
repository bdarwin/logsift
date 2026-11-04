package logsift.core;

import io.quackjvm.agent.catalog.QueryColumns;
import io.quackjvm.agent.file.FileTable;
import io.quackjvm.agent.file.FileTables;
import io.quackjvm.agent.file.RowParser;
import io.quackjvm.agent.file.RowSource;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.core.duckdb.ColumnDef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * SQL over a big table from a cache of it: the table {@code t} ({@code line}, then the columns by
 * their titles; numbers, times and text as Pivot reads them, as {@link Sql#table} makes it), of
 * which only the columns a query reads are stored, the first time one does. A query over 3 of
 * 6,000 columns then reads those 3, in milliseconds, instead of the whole file each time.
 *
 * <p>The cache is a quackjvm {@link FileTable} with lazy columns, kept on disk and following the
 * file as it grows. Which columns a query reads comes from DuckDB's plan of it (quackjvm's
 * {@link QueryColumns}), made over an empty table of the same columns, so nothing is read to find
 * out; they are stored first, with progress to show. A query whose columns can't be told (a
 * {@code PIVOT}, one reading another file, a mistake DuckDB can't plan) or that reads more than
 * {@link #MAX_NEW_COLUMNS} columns not stored yet is left to the usual way: {@link #query} gives
 * null.
 */
public final class TableQuery implements AutoCloseable {
    /** The most columns one query may have stored; one that reads more is answered the usual way. */
    public static final int MAX_NEW_COLUMNS = 64;

    /** Changes when the stored layout changes, so older caches aren't read. */
    static final String VERSION = "logsift-table-1";

    private final Pivot.Fields fields;
    private final List<String> names;
    private final FileTable table;
    private final Feed feed;
    /** An empty table {@code t} with the columns and types, for checking and planning queries. */
    private final Connection schema;
    private final QueryColumns planner;

    private TableQuery(Pivot.Fields fields, FileTable table, Feed feed, Connection schema, QueryColumns planner) {
        this.fields = fields;
        this.names = Sql.tableColumns(fields.titles());
        this.table = table;
        this.feed = feed;
        this.schema = schema;
        this.planner = planner;
    }

    /**
     * The cache of this table, read this way, in {@code dir}: made, or the one kept from before
     * (caches past {@code limitBytes} are removed, the longest unused first).
     */
    public static TableQuery open(Path file, LogFormat format, String read, Pivot.Fields fields, Path dir,
                                  long limitBytes) throws IOException, SQLException {
        Files.createDirectories(dir);
        List<ColumnDef> cols = columns(fields);
        Feed feed = new Feed();
        FileTable t;
        try {
            t = FileTables.builder(file.toAbsolutePath().normalize(), cols, feed, parser(format, fields))
                    .name("t").cacheDir(dir).roomLimit(limitBytes).lazyColumns(true)
                    .maxNewColumnsPerQuery(Integer.MAX_VALUE)
                    .version(VERSION + "|" + read + "|" + (int) format.delimiter() + "|" + fields.kinds() + "|"
                            + String.join("\u0001", format.columns()))
                    .open();
        } catch (RuntimeException e) {
            throw new IOException("The table's cache couldn't be opened: " + e.getMessage(), e);
        }
        Connection c = DriverManager.getConnection("jdbc:duckdb:", Hardening.options().properties());
        try {
            try (Statement s = c.createStatement()) {
                s.execute(Sql.tableDdl("t", fields));
            }
            return new TableQuery(fields, t, feed, c, QueryColumns.of(c));
        } catch (SQLException | RuntimeException e) {
            c.close();
            t.close();
            throw e;
        }
    }

    /** The table's rows as the caller reads them: each row whole by its first line, null for a line inside one. */
    static final class Feed implements RowSource {
        volatile ColumnProfile.Lines lines;
        volatile long count = -1;
        private static final Object SAME = new Object();

        @Override
        public long rowCount() {
            long n = count;
            if (n < 0) {
                throw new IllegalStateException("the table's rows weren't given yet");
            }
            return n;
        }

        @Override
        public void read(long first, int n, String[] into) {
            lines.read(first, n, into);
        }

        @Override
        public Object identity() {
            return SAME; // a file written again is told by its first bytes
        }
    }

    /** A table's columns as {@link Sql#table} makes them: {@code line}, then each by its title, typed by its kind. */
    static List<ColumnDef> columns(Pivot.Fields fields) {
        List<String> names = Sql.tableColumns(fields.titles());
        List<ColumnDef> cols = new ArrayList<>(List.of(new ColumnDef("line", Long.class)));
        for (int c = 0; c < names.size(); c++) {
            cols.add(new ColumnDef(names.get(c), switch (fields.kind(c)) {
                case NUMBER -> Double.class;
                case TIME -> LocalDateTime.class;
                case TEXT -> String.class;
            }));
        }
        return cols;
    }

    /** A row as {@link Sql#table} loads it: its line, then each column as Pivot reads it. */
    static RowParser parser(LogFormat format, Pivot.Fields fields) {
        int n = fields.titles().size();
        return new RowParser() {
            @Override
            public boolean isRow(String raw, long rowNo) {
                return format.isRow(raw == null ? "" : raw);
            }

            @Override
            public void parse(String raw, long rowNo, Object[] cells) {
                BitSet all = new BitSet();
                all.set(0, cells.length);
                parse(raw, rowNo, cells, all);
            }

            @Override
            public void parse(String raw, long rowNo, Object[] cells, BitSet wanted) {
                cells[0] = rowNo + 1;
                boolean[] want = new boolean[Math.max(n, format.columns().size())];
                boolean any = false;
                for (int c = 0; c < n; c++) {
                    if (wanted.get(c + 1)) {
                        want[c] = true;
                        any = true;
                    }
                }
                if (!any) {
                    return;
                }
                String[] split = format.splitSome(raw == null ? "" : raw, want);
                if (split == null) {
                    return;
                }
                for (int c = 0; c < n; c++) {
                    if (want[c]) {
                        cells[c + 1] = cell(fields, c, c < split.length && split[c] != null ? split[c].strip() : "");
                    }
                }
            }
        };
    }

    /** A cell as {@link Sql#table} stores it: a number, a time, or text; null when blank or unreadable. */
    private static Object cell(Pivot.Fields fields, int c, String v) {
        if (v.isEmpty()) {
            return null;
        }
        return switch (fields.kind(c)) {
            case NUMBER -> {
                double d = Pivot.number(v);
                yield Double.isNaN(d) ? null : d;
            }
            case TIME -> {
                TimeFormat tf = c < fields.times().size() ? fields.times().get(c) : null;
                long ms = tf == null ? TimeFormat.NONE : tf.parse(v);
                yield ms == TimeFormat.NONE ? null : LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000),
                        Math.floorMod(ms, 1000) * 1_000_000, ZoneOffset.UTC);
            }
            case TEXT -> v;
        };
    }

    /** The fields it was made for: other ones (the file changed) need another. */
    public Pivot.Fields fields() {
        return fields;
    }

    /** Whether column {@code c} (of the titles) is stored, for every row. */
    public boolean hasColumn(int c) {
        return rowsThere() && table.storedColumns().contains(names.get(c));
    }

    private boolean rowsThere() {
        long n = feed.count;
        return n < 0 ? table.rowsLoaded() > 0 : table.rowsLoaded() >= n;
    }

    /**
     * What a query reads: the columns (by number) and whether it reads {@code t}'s rows at all
     * ({@code count(*)} reads rows but no column; {@code DESCRIBE t} neither); null when it can't
     * be told, or it reads more than {@code t}.
     */
    record Read(Set<Integer> columns, boolean rows) {}

    synchronized Read columnsRead(String query) {
        QueryColumns.Read read = planner.read(query);
        if (!read.isKnown()) {
            return null;
        }
        Set<Integer> out = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : read.columns().entrySet()) {
            if (!e.getKey().equalsIgnoreCase("t")) {
                return null; // there is no other table: not planned as expected
            }
            for (String n : e.getValue()) {
                if (n.equalsIgnoreCase("line")) {
                    continue;
                }
                int c = column(n);
                if (c < 0) {
                    return null;
                }
                out.add(c);
            }
        }
        return new Read(out, !read.columns().isEmpty());
    }

    private int column(String name) {
        int c = names.indexOf(name);
        if (c >= 0) {
            return c;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The query's answer from the cache, storing the columns it reads first (one read of the file:
     * {@code rows} gives each row by its first line), and the rows written since; null when it
     * can't come from there and the table should be queried the usual way. Throws for what isn't a
     * query (nothing is read) and for a query DuckDB refuses; "Stopped." when stopped.
     */
    public Sql.Result query(String sql, int maxRows, ColumnProfile.Lines rows, long lineCount, BooleanSupplier stop,
                            DoubleConsumer progress, Consumer<String> say) throws SQLException {
        String q;
        synchronized (this) {
            q = Sql.check(schema, sql);
        }
        Read read = columnsRead(q);
        if (read == null) {
            return null;
        }
        if (!read.rows()) {
            // reads no row (DESCRIBE t, SELECT 1): the empty table answers it as well
            synchronized (this) {
                return Sql.run(schema, q, maxRows, stop);
            }
        }
        feed.lines = rows;
        feed.count = lineCount;
        Set<String> wanted = new LinkedHashSet<>(List.of("line"));
        long missing = 0;
        for (int c : read.columns()) {
            wanted.add(names.get(c));
            if (!table.storedColumns().contains(names.get(c))) {
                missing++;
            }
        }
        if (missing > MAX_NEW_COLUMNS) {
            return null;
        }
        CancelSignal signal = stop == null ? new CancelSignal() : CancelSignal.from(stop);
        boolean storing = !table.storedColumns().containsAll(wanted);
        if (storing || !rowsThere()) {
            if (say != null && storing) {
                say.accept("Preparing " + (missing <= 1 ? "this column" : "these " + missing + " columns")
                        + " for quick queries (once, for this file)…");
            }
            try {
                if (table.rowsLoaded() == 0) {
                    // nothing read yet: the columns are taken into the first read (let go again if it's stopped)
                    List<String> added = new ArrayList<>(wanted);
                    added.removeAll(table.storedColumns());
                    table.store(wanted, signal, null);
                    try {
                        table.update(signal, progress);
                    } catch (QueryCancelled e) {
                        if (table.rowsLoaded() < lineCount) {
                            table.unstore(added);
                        }
                        throw e;
                    }
                } else {
                    table.update(signal, null);
                    table.store(wanted, signal, progress);
                }
            } catch (QueryCancelled e) {
                throw new SQLException("Stopped.", e);
            } catch (RuntimeException e) {
                throw Sql.failure(e);
            }
            if (say != null && storing) {
                say.accept("Running…");
            }
        }
        try {
            return Sql.result(table.query(q, Sql.policy(maxRows), signal));
        } catch (RuntimeException e) {
            throw Sql.failure(e);
        }
    }

    /**
     * Why {@link #query} gives null for this query (it would be answered the usual way), in words
     * for whoever wrote it; null when the cache can answer it.
     */
    public String whyNot(String sql) throws SQLException {
        String q;
        synchronized (this) {
            q = Sql.check(schema, sql);
        }
        Read read = columnsRead(q);
        if (read == null) {
            return "DuckDB can't tell which columns it reads (a PIVOT, or a query over something other than t)";
        }
        long missing = read.columns().stream().filter(c -> !table.storedColumns().contains(names.get(c))).count();
        if (missing > MAX_NEW_COLUMNS) {
            return "it reads " + String.format(Locale.ROOT, "%,d", missing) + " columns not prepared yet, and at most "
                    + MAX_NEW_COLUMNS + " are prepared at once";
        }
        return null;
    }

    /**
     * Runs a query over the empty table (the same columns and types), reading nothing: throws
     * DuckDB's refusal of a mistake (a column that isn't there, with its hint of the ones that
     * are) as it would over the whole table.
     */
    public synchronized void tryOnEmpty(String sql) throws SQLException {
        Sql.run(schema, sql, 1, null);
    }

    /** SQL straight on the cache (tests: that it reaches no file). */
    Sql.Result onCacheForTest(String sql) throws SQLException {
        try {
            return Sql.result(table.query(sql, Sql.policy(10), null));
        } catch (RuntimeException e) {
            throw Sql.failure(e);
        }
    }

    @Override
    public synchronized void close() {
        planner.close();
        try {
            schema.close();
        } catch (SQLException e) {
            // closing anyway
        }
        table.close();
    }
}
