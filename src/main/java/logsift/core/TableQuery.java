package logsift.core;

import io.quackjvm.agent.catalog.QueryColumns;
import io.quackjvm.agent.guard.Hardening;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * SQL over a big table from its cache: the table {@code t} ({@code line}, then the columns by
 * their titles; numbers, times and text as Pivot reads them, as {@link Sql#table} makes it), of
 * which only the columns a query reads are stored, the first time one does. A query over 3 of
 * 6,000 columns then reads those 3, in milliseconds, instead of the whole file each time.
 *
 * <p>Which columns a query reads comes from DuckDB's plan of it (quackjvm's {@link QueryColumns}),
 * made over an empty table of the same columns, so nothing is read to find out. A query whose
 * columns can't be told (a {@code PIVOT}, one reading another file, a mistake DuckDB can't plan)
 * or that reads more than {@link #MAX_NEW_COLUMNS} columns not stored yet is left to the usual
 * way: {@link #query} gives null.
 */
public final class TableQuery implements AutoCloseable {
    /** The most columns one query may have stored; one that reads more is answered the usual way. */
    public static final int MAX_NEW_COLUMNS = 64;

    private final Pivot.Fields fields;
    private final List<String> names;
    /** An empty table {@code t} with the columns and types, for checking and planning queries. */
    private final Connection schema;
    private final QueryColumns planner;

    public TableQuery(Pivot.Fields fields) throws SQLException {
        this.fields = fields;
        this.names = Sql.tableColumns(fields.titles());
        Connection c = DriverManager.getConnection("jdbc:duckdb:", Hardening.options().properties());
        try {
            try (Statement s = c.createStatement()) {
                s.execute(Sql.tableDdl("t", fields));
            }
            this.planner = QueryColumns.of(c);
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
        this.schema = c;
    }

    /** The fields it was made for: other ones (the file changed) need another. */
    public Pivot.Fields fields() {
        return fields;
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
     * {@code rows} gives each row by its first line); null when it can't come from there and the
     * table should be queried the usual way. Throws for what isn't a query (nothing is read) and
     * for a query DuckDB refuses; "Stopped." when stopped.
     */
    public Sql.Result query(PivotCache cache, String sql, int maxRows, ColumnProfile.Lines rows, long lineCount,
                            BooleanSupplier stop, DoubleConsumer progress, Consumer<String> say) throws SQLException {
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
        List<Pivot.Field> missing = new ArrayList<>();
        for (int c : read.columns()) {
            if (!cache.hasColumn(c)) {
                missing.add(new Pivot.Field(c));
            }
        }
        if (missing.size() > MAX_NEW_COLUMNS) {
            return null;
        }
        if (!missing.isEmpty() || !cache.hasLines()) {
            if (say != null) {
                say.accept("Preparing " + (missing.size() == 1 ? "this column" : "these " + missing.size() + " columns")
                        + " for quick queries (once, for this file)…");
            }
            Pivot.Spec store = new Pivot.Spec(missing, List.of(), List.of(), Map.of());
            if (!cache.build(store, fields, rows, lineCount, stop, progress)) {
                throw new SQLException("Stopped.");
            }
            if (say != null) {
                say.accept("Running…");
            }
        }
        return cache.query(q, read.columns(), names, fields, maxRows, stop);
    }

    /**
     * Why {@link #query} gives null for this query (it would be answered the usual way), in words
     * for whoever wrote it; null when the cache can answer it.
     */
    public String whyNot(PivotCache cache, String sql) throws SQLException {
        String q;
        synchronized (this) {
            q = Sql.check(schema, sql);
        }
        Read read = columnsRead(q);
        if (read == null) {
            return "DuckDB can't tell which columns it reads (a PIVOT, or a query over something other than t)";
        }
        long missing = read.columns().stream().filter(c -> !cache.hasColumn(c)).count();
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

    @Override
    public synchronized void close() {
        planner.close();
        try {
            schema.close();
        } catch (SQLException e) {
            // closing anyway
        }
    }
}
