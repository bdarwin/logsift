package logsift.core;

import io.quackjvm.agent.file.FileTable;
import io.quackjvm.agent.file.FileTables;
import io.quackjvm.agent.file.RowFunction;
import io.quackjvm.agent.file.RowParser;
import io.quackjvm.agent.file.RowSource;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.session.QuerySession;
import io.quackjvm.agent.session.ResultWindow;
import io.quackjvm.core.duckdb.ColumnDef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * The columns of a big table that Pivot, column filters and value counts have used, kept on disk
 * so the next pivot doesn't read the whole file again: 5 million rows are summed up in milliseconds
 * instead of seconds, and a table of 6,000 columns no longer has every row split.
 *
 * <p>A quackjvm {@link FileTable} over the file, a row per table row (by its first line), with lazy
 * columns: of each of the file's columns, its text ({@code c<i>}: stripped, null when blank), its
 * number ({@code n<i>}), its time ({@code e<i>}, ms) and its value as column filters read it
 * ({@code v<i>}), each stored the first time it's needed; {@code pv} says whether Pivot counts the
 * row (not blank, not the header, not a broken row). "Values as One" over many columns keeps each
 * row's count, numbers, sum, min and max in columns the application computes ({@code p_<key>_…}).
 * DuckDB then groups and totals; the labels and answers are what Pivot gives when it reads the file
 * (median and percentiles exactly, where Pivot samples past a million numbers).
 *
 * <p>The cache follows the file: rows written since are added when it's used, and a file written
 * again from the start is read again (the table sees its first bytes change). Caches past a size
 * limit are removed, the longest unused first, never one in use.
 */
public final class PivotCache implements AutoCloseable {
    /** Changes when the stored layout changes, so older caches aren't read. */
    static final String VERSION = "logsift-pivot-2";
    /** Files smaller than this are pivoted from the file itself, quick enough. */
    public static final long MIN_BYTES = 200L << 20;
    /** The default room for caches. */
    public static final long DEFAULT_LIMIT = 5L << 30;

    /** What SQL calls the table. */
    static final String TABLE = "p";

    private final Path file;
    private final LogFormat format;
    private final String read;
    private final FileTable table;
    private final QuerySession session;
    private final Feed feed;
    /** The times of the columns, as Pivot reads them (from the fields last given). */
    private volatile List<TimeFormat> times = List.of();
    private ZoneId udfZone;
    private boolean testRegistered;
    /** Pools ("Values as One") declared on the table: name to their columns. */
    private final Map<String, int[]> pools = new HashMap<>();

    private PivotCache(Path file, LogFormat format, String read, FileTable table, QuerySession session, Feed feed) {
        this.feed = feed;
        this.file = file;
        this.format = format;
        this.read = read;
        this.table = table;
        this.session = session;
    }

    /**
     * The cache of the file read this way, in {@code dir} (made if need be). Caches past
     * {@code limitBytes} are removed first, the longest unused first.
     */
    public static PivotCache open(Path file, LogFormat format, Path dir, long limitBytes) throws IOException {
        return open(file, format, "", dir, limitBytes);
    }

    /** As {@link #open(Path, LogFormat, Path, long)}; {@code read} tells how the file is read (its encoding). */
    public static PivotCache open(Path file, LogFormat format, String read, Path dir, long limitBytes)
            throws IOException {
        Files.createDirectories(dir);
        Path abs = file.toAbsolutePath().normalize();
        Feed feed = new Feed();
        FileTable t;
        try {
            t = FileTables.builder(abs, columns(format), feed, parser(format, feed)).name(TABLE).cacheDir(dir)
                    .roomLimit(limitBytes).lazyColumns(true).maxNewColumnsPerQuery(Integer.MAX_VALUE)
                    .version(VERSION + "|" + read + "|" + (int) format.delimiter() + "|"
                            + String.join("\u0001", format.columns()))
                    .open();
        } catch (RuntimeException e) {
            throw new IOException("The Pivot cache couldn't be opened: " + e.getMessage(), e);
        }
        QuerySession s;
        try {
            s = QuerySession.open(Hardening.options(), QueryPolicy.readOnly().maxRows(Long.MAX_VALUE));
            s.register(TABLE, t);
        } catch (RuntimeException e) {
            t.close();
            throw new IOException("The Pivot cache couldn't be opened: " + e.getMessage(), e);
        }
        PivotCache c = new PivotCache(abs, format, read, t, s, feed);
        feed.owner = c;
        return c;
    }

    /** The file's rows as the caller reads them (rows whole by their first line; null for a line inside one). */
    static final class Feed implements RowSource {
        volatile ColumnProfile.Lines lines;
        volatile long count = -1;
        PivotCache owner;
        private static final Object SAME = new Object();

        @Override
        public long rowCount() {
            long n = count;
            if (n < 0) {
                throw new IllegalStateException("the file's rows weren't given yet");
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

    /** The table's columns: the row's line, whether Pivot counts it, then text, number, time and value of each. */
    private static List<ColumnDef> columns(LogFormat format) {
        List<ColumnDef> out = new ArrayList<>(List.of(new ColumnDef("line", Long.class),
                new ColumnDef("pv", Boolean.class)));
        for (int c = 0; c < format.columns().size(); c++) {
            out.add(new ColumnDef("c" + c, String.class));
            out.add(new ColumnDef("n" + c, Double.class));
            out.add(new ColumnDef("e" + c, Long.class));
            out.add(new ColumnDef("v" + c, String.class));
        }
        return out;
    }

    /** Where column {@code c}'s text, number, time and value are among the table's columns. */
    private static int text(int c) {
        return 2 + 4 * c;
    }

    /** Whether a row is one Pivot counts: not blank, and a row of the table (not the header, not broken). */
    static boolean pivotRow(LogFormat format, String raw) {
        return raw != null && !raw.isBlank() && format.isRow(raw);
    }

    private static RowParser parser(LogFormat format, Feed feed) {
        int nc = format.columns().size();
        return new RowParser() {
            @Override
            public boolean isRow(String raw, long rowNo) {
                return raw != null; // a line inside a row comes as null
            }

            @Override
            public void parse(String raw, long rowNo, Object[] cells) {
                BitSet all = new BitSet();
                all.set(0, cells.length);
                parse(raw, rowNo, cells, all);
            }

            @Override
            public void parse(String raw, long rowNo, Object[] cells, BitSet wanted) {
                cells[0] = rowNo;
                boolean pivot = pivotRow(format, raw);
                cells[1] = pivot;
                boolean[] want = null;
                boolean anyValue = false;
                for (int c = 0; c < nc; c++) {
                    int at = text(c);
                    if (wanted.get(at) || wanted.get(at + 1) || wanted.get(at + 2)) {
                        if (want == null) {
                            want = new boolean[nc];
                        }
                        want[c] = true;
                    }
                    anyValue |= wanted.get(at + 3);
                }
                if (want != null && pivot) {
                    String[] split = format.splitSome(raw, want);
                    List<TimeFormat> times = feed.owner == null ? List.of() : feed.owner.times;
                    if (split != null) {
                        for (int c = 0; c < nc; c++) {
                            if (!want[c]) {
                                continue;
                            }
                            int at = text(c);
                            String v = PivotCache.text(split, c);
                            cells[at] = v;
                            if (v != null && wanted.get(at + 1)) {
                                double d = Pivot.number(v);
                                cells[at + 1] = Double.isNaN(d) ? null : d;
                            }
                            if (v != null && wanted.get(at + 2) && c < times.size() && times.get(c) != null) {
                                long ms = times.get(c).parse(v);
                                cells[at + 2] = ms == TimeFormat.NONE ? null : ms;
                            }
                        }
                    }
                }
                if (anyValue) {
                    for (int c = 0; c < nc; c++) {
                        if (wanted.get(text(c) + 3)) {
                            cells[text(c) + 3] = format.value(raw, format.columns().get(c));
                        }
                    }
                }
            }
        };
    }

    /** A cell as Pivot reads it: stripped, null when blank. */
    private static String text(String[] cells, int c) {
        String v = c < cells.length && cells[c] != null ? cells[c].strip() : "";
        return v.isEmpty() ? null : v;
    }

    /** The rows as the caller reads them now: rows written since are stored when the cache is next used. */
    public void feed(ColumnProfile.Lines lines, long lineCount) {
        feed.lines = lines;
        feed.count = lineCount;
    }

    /** The source files of caches in use (to keep), as {@link #clear} takes them. */
    public String key() {
        return file.toString();
    }

    /** The cache file (for tests). */
    Path cacheFile() {
        return table.cacheFile();
    }

    /** Whether it is the cache of this file, read this way. */
    public boolean isFor(Path file, LogFormat format, String read) {
        return this.file.equals(file.toAbsolutePath().normalize()) && this.format.equals(format) && this.read.equals(read);
    }

    /** How much room the caches in {@code dir} take. */
    public static long size(Path dir) {
        return Files.isDirectory(dir) ? FileTables.size(dir) : 0;
    }

    /** Removes every cache in {@code dir} but those of files in use ({@code keep}: their {@link #key}s) and those open. */
    public static void clear(Path dir, Set<String> keep) {
        if (Files.isDirectory(dir)) {
            FileTables.clear(dir, keep);
        }
    }

    // ---------------------------------------------------------------- what a pivot needs

    /** The columns a pivot reads, or null when its answer can't come from the cache. */
    private record Needs(Set<String> columns, Map<String, List<Integer>> pools) {}

    private static Needs needs(Pivot.Spec spec) {
        Set<String> cols = new LinkedHashSet<>(List.of("pv"));
        Map<String, List<Integer>> pools = new LinkedHashMap<>();
        for (Integer f : spec.filters().keySet()) {
            cols.add("c" + f);
            cols.add("line");
        }
        for (Pivot.Field f : spec.rows()) {
            field(f, cols);
        }
        for (Pivot.Field f : spec.cols()) {
            field(f, cols);
        }
        for (Pivot.Value v : spec.cellValues()) {
            List<Integer> read = v.read();
            if (read.size() > 1) {
                if (v.agg() == Pivot.Agg.COUNT_DISTINCT || v.agg() == Pivot.Agg.MEDIAN || v.agg() == Pivot.Agg.P95
                        || v.agg() == Pivot.Agg.P99) {
                    return null; // over many columns: the file is read
                }
                String p = poolName(read);
                pools.put(p, read);
                for (String part : POOL_PARTS) {
                    cols.add(p + "_" + part);
                }
            } else if (v.column() >= 0) {
                cols.add("c" + v.column());
                cols.add("n" + v.column());
            }
        }
        return new Needs(cols, pools);
    }

    private static void field(Pivot.Field f, Set<String> cols) {
        cols.add("c" + f.column());
        if (f.group() != Pivot.Group.NONE) {
            cols.add("e" + f.column());
        }
    }

    /** What a pool keeps of each row: cells, numbers among them, their sum, least and most. */
    private static final List<String> POOL_PARTS = List.of("cnt", "nums", "s", "mn", "mx");

    static String poolName(List<Integer> columns) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(columns));
        StringBuilder b = new StringBuilder();
        for (int c : sorted) {
            b.append(c).append(',');
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("p_");
            for (int i = 0; i < 8; i++) {
                out.append(String.format("%02x", h[i]));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Declares a pool's columns (computed from the whole row, stored when first needed). One row's
     * five numbers are worked out once, however many of them are stored at a time.
     */
    private synchronized void declare(String name, List<Integer> read) {
        if (pools.containsKey(name)) {
            return;
        }
        int[] cols = read.stream().mapToInt(Integer::intValue).toArray();
        pools.put(name, cols);
        ThreadLocal<Object[]> last = new ThreadLocal<>();
        for (int part = 0; part < POOL_PARTS.size(); part++) {
            int which = part;
            RowFunction f = (raw, rowNo) -> {
                Object[] memo = last.get();
                if (memo == null || (long) memo[0] != rowNo) {
                    memo = new Object[] {rowNo, pool(raw, cols)};
                    last.set(memo);
                }
                Object[] row = (Object[]) memo[1];
                return row == null ? null : row[which];
            };
            Class<?> type = part < 2 ? Integer.class : Double.class;
            // with a version: kept from one start to the next, as long as this code works them out the same way
            table.column(new ColumnDef(name + "_" + POOL_PARTS.get(part), type), f, VERSION);
        }
    }

    /** A row's count, numbers, sum, min and max over the pool's columns; null for a row Pivot doesn't count. */
    private Object[] pool(String raw, int[] cols) {
        if (!pivotRow(format, raw)) {
            return null;
        }
        boolean[] want = new boolean[format.columns().size()];
        for (int c : cols) {
            if (c >= 0 && c < want.length) {
                want[c] = true;
            }
        }
        String[] cells = format.splitSome(raw, want);
        if (cells == null) {
            return null;
        }
        int cnt = 0;
        int nums = 0;
        double s = 0;
        double mn = Double.POSITIVE_INFINITY;
        double mx = Double.NEGATIVE_INFINITY;
        for (int c : cols) {
            String v = c < 0 ? "" : text(cells, c);
            if (v == null) {
                continue;
            }
            cnt++;
            double d = Pivot.number(v);
            if (!Double.isNaN(d)) {
                nums++;
                s += d;
                mn = Math.min(mn, d);
                mx = Math.max(mx, d);
            }
        }
        return new Object[] {cnt, nums, nums == 0 ? null : s, nums == 0 ? null : mn, nums == 0 ? null : mx};
    }

    /** Whether the rows are all stored: as many as the file has (when told), or some at least. */
    private boolean rowsThere() {
        long n = feed.count;
        return n < 0 ? table.rowsLoaded() > 0 : table.rowsLoaded() >= n;
    }

    private boolean stored(Set<String> cols) {
        return table.storedColumns().containsAll(cols);
    }

    /** Whether column {@code c}'s text is stored, for every row (for tests and agents' notes). */
    public synchronized boolean hasColumn(int c) {
        return rowsThere() && stored(Set.of("c" + c));
    }

    /** Whether this pivot can be answered from the cache, as it is now. */
    public synchronized boolean has(Pivot.Spec spec) {
        Needs n = needs(spec);
        if (n == null) {
            return false;
        }
        n.pools().forEach(this::declare); // after a restart: what was kept comes back as it was
        return rowsThere() && stored(n.columns());
    }

    /** Whether this pivot can be answered from a cache at all (else the file is always read). */
    public static boolean canCache(Pivot.Spec spec) {
        return needs(spec) != null;
    }

    // ---------------------------------------------------------------- storing

    private final java.util.concurrent.locks.ReentrantLock buildLock = new java.util.concurrent.locks.ReentrantLock();

    /**
     * Stores the columns this pivot needs that aren't stored yet, and the rows written since (one
     * read of the file). Gives false when cancelled (nothing half-stored is answered from).
     */
    public boolean build(Pivot.Spec spec, Pivot.Fields fields, ColumnProfile.Lines lines, long lineCount,
                         BooleanSupplier cancelled, DoubleConsumer progress) {
        Needs n = needs(spec);
        if (n == null) {
            return false;
        }
        setTimes(fields);
        feed(lines, lineCount);
        n.pools().forEach(this::declare);
        return ensure(n.columns(), cancelled, progress);
    }

    /** The columns stored, and the rows up to date: one read of the file at most. */
    private boolean ensure(Set<String> cols, BooleanSupplier cancelled, DoubleConsumer progress) {
        buildLock.lock();
        try {
            CancelSignal signal = cancelled == null ? null : CancelSignal.from(cancelled);
            if (table.rowsLoaded() == 0) {
                // nothing read yet: the columns are taken into the first read (let go again if it's stopped)
                List<String> added = new ArrayList<>(cols);
                added.removeAll(table.storedColumns());
                table.store(cols, signal, null);
                try {
                    table.update(signal, progress);
                } catch (QueryCancelled e) {
                    if (table.rowsLoaded() < feed.count) {
                        table.unstore(added);
                    }
                    throw e;
                }
            } else {
                table.update(signal, null);
                table.store(cols, signal, progress);
            }
            return true;
        } catch (QueryCancelled e) {
            return rowsThere() && stored(cols); // stopped after all was stored: done all the same
        } catch (RuntimeException e) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return false;
            }
            throw new IllegalStateException("The Pivot cache couldn't be written: " + e.getMessage(), e);
        } finally {
            buildLock.unlock();
        }
    }

    /** The rows written since, stored (when the file's rows were given); quick when there are none. */
    private void upToDate(BooleanSupplier stop) {
        if (feed.count >= 0 && table.rowsLoaded() < feed.count) {
            ensure(Set.of(), stop, null);
        }
    }

    private synchronized void setTimes(Pivot.Fields fields) {
        if (fields != null) {
            times = java.util.Collections.unmodifiableList(new ArrayList<>(fields.times())); // null: not a time
        }
    }

    // ---------------------------------------------------------------- column filters

    /**
     * Whether column {@code c}'s values are stored as column filters read them: each row's value
     * as {@link LogFormat#value} gives it (not stripped), by the row's first line.
     */
    public synchronized boolean hasValues(int c) {
        return rowsThere() && stored(Set.of("line", "v" + c));
    }

    /**
     * Reads the file once and stores the values of these columns as filters read them ({@code rows}
     * gives each row whole by its first line, null where a line continues the row above). Gives
     * false when cancelled.
     */
    public boolean buildValues(List<Integer> columns, ColumnProfile.Lines rows, long lineCount,
                               BooleanSupplier cancelled, DoubleConsumer progress) {
        feed(rows, lineCount);
        Set<String> cols = new LinkedHashSet<>(List.of("line"));
        for (int c : new TreeSet<>(columns)) {
            if (c >= 0 && c < format.columns().size()) {
                cols.add("v" + c);
            }
        }
        return ensure(cols, cancelled, progress);
    }

    /**
     * The values of column {@code c} in the rows before {@code upTo} with their counts and first
     * lines, as {@link ColumnStats#count} gives them reading the file; null when the column's
     * values aren't stored. Past {@link ColumnStats#MAX_VALUES} different values, those that come
     * first in the file are kept.
     */
    public ColumnStats.Result valueCounts(int c, long upTo, BooleanSupplier stop) {
        if (!hasValues(c)) {
            return null;
        }
        long t0 = System.nanoTime();
        upToDate(stop);
        String v = "v" + c;
        CancelSignal signal = CancelSignal.from(stop);
        List<ColumnStats.Value> values = new ArrayList<>();
        long lines;
        long distinct;
        try {
            Object[] totals = session.run("SELECT count(" + v + "), count(DISTINCT " + v + ") FROM " + TABLE
                    + " WHERE line < " + upTo, signal).getRows().get(0);
            lines = ((Number) totals[0]).longValue();
            distinct = ((Number) totals[1]).longValue();
            if (stop.getAsBoolean()) {
                return null;
            }
            for (Object[] r : session.run("SELECT " + v + ", count(*), min(line) AS first_line FROM " + TABLE
                    + " WHERE line < " + upTo + " AND " + v + " IS NOT NULL GROUP BY 1 ORDER BY first_line LIMIT "
                    + ColumnStats.MAX_VALUES, signal).getRows()) {
                values.add(new ColumnStats.Value((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue()));
            }
        } catch (QueryCancelled e) {
            return null;
        } catch (RuntimeException e) {
            if (stop.getAsBoolean()) {
                return null;
            }
            throw new IllegalStateException("The cache couldn't be read: " + e.getMessage(), e);
        }
        if (stop.getAsBoolean()) {
            return null;
        }
        values.sort(java.util.Comparator.comparingLong((ColumnStats.Value x) -> -x.count())
                .thenComparing(ColumnStats.Value::value));
        return new ColumnStats.Result(format.columns().get(c), values, lines, (System.nanoTime() - t0) / 1_000_000,
                distinct > values.size());
    }

    /** The tests of the filters being answered, by id, for the function SQL calls. */
    private final Map<Long, java.util.function.Predicate<String>> tests = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextTest = new java.util.concurrent.atomic.AtomicLong();

    /** Lines read back at a time. */
    private static final int PAGE = 200_000;

    /**
     * The lines before {@code upTo} whose value in column {@code c} passes {@code test}, in order;
     * null when the column's values aren't stored. The test runs once for each different value,
     * so it is exactly the filter's own (regexes, units, case).
     */
    public long[] matchingLines(int c, java.util.function.Predicate<String> test, long upTo, BooleanSupplier stop) {
        if (!hasValues(c)) {
            return null;
        }
        upToDate(stop);
        synchronized (this) {
            if (!testRegistered) {
                table.function("logsift_keep", Boolean.class, Long.class, String.class, (id, v) -> {
                    java.util.function.Predicate<String> t = tests.get(id);
                    return t != null && v != null && t.test(v);
                });
                testRegistered = true;
            }
        }
        long id = nextTest.incrementAndGet();
        tests.put(id, test);
        String v = "v" + c;
        // OFFSET 0: the test is asked once per value, not once per row
        String sql = "SELECT line FROM " + TABLE + " WHERE line < " + upTo + " AND " + v + " IN (SELECT x FROM (SELECT"
                + " DISTINCT " + v + " AS x FROM " + TABLE + " WHERE " + v + " IS NOT NULL OFFSET 0) WHERE logsift_keep("
                + id + ", x)) ORDER BY line";
        try (ResultWindow w = session.window(sql, CancelSignal.from(stop))) {
            long n = w.rowCount();
            long[] out = new long[(int) Math.min(n, Integer.MAX_VALUE - 8)];
            int at = 0;
            for (long first = 0; first < n && at < out.length; first += PAGE) {
                if (stop.getAsBoolean()) {
                    return null;
                }
                for (Object[] r : w.rows(first, PAGE)) {
                    out[at++] = ((Number) r[0]).longValue();
                }
            }
            return stop.getAsBoolean() ? null : out;
        } catch (QueryCancelled e) {
            return null;
        } catch (RuntimeException e) {
            if (stop.getAsBoolean()) {
                return null;
            }
            throw new IllegalStateException("The cache couldn't be read: " + e.getMessage(), e);
        } finally {
            tests.remove(id);
        }
    }

    // ---------------------------------------------------------------- answering

    /**
     * The pivot, from the cache; null when it can't be answered from it (columns not stored yet,
     * or more groups than Pivot shows).
     */
    public Pivot.Result compute(Pivot.Spec spec, Pivot.Fields fields, ZoneId zone, long lineCount) {
        long t0 = System.nanoTime();
        setTimes(fields);
        upToDate(null);
        if (!has(spec)) {
            return null;
        }
        registerTime(zone);
        List<Pivot.Value> values = spec.cellValues();
        int nr = spec.rows().size();
        int nm = spec.cols().size();
        List<String> sel = new ArrayList<>();
        for (int r = 0; r < nr; r++) {
            sel.add(label(spec.rows().get(r), fields) + " AS r" + r);
        }
        for (int c = 0; c < nm; c++) {
            sel.add(label(spec.cols().get(c), fields) + " AS k" + c);
        }
        for (int v = 0; v < values.size(); v++) {
            Pivot.Value val = values.get(v);
            List<Integer> read = val.read();
            if (read.size() > 1) {
                String p = poolName(read);
                for (String part : POOL_PARTS) {
                    sel.add(p + "_" + part + " AS v" + v + "_" + part);
                }
            } else if (val.column() >= 0) {
                sel.add("c" + val.column() + " AS v" + v + "_t");
                sel.add("n" + val.column() + " AS v" + v + "_n");
            }
        }
        if (sel.isEmpty()) {
            sel.add("1 AS one");
        }
        StringBuilder inner = new StringBuilder("SELECT ").append(String.join(", ", sel)).append(" FROM ").append(TABLE);
        List<String> where = new ArrayList<>(List.of("pv"));
        for (Map.Entry<Integer, Set<String>> f : spec.filters().entrySet()) {
            if (f.getValue() == null) {
                continue;
            }
            if (f.getValue().isEmpty()) {
                where.add("false");
                continue;
            }
            StringBuilder in = new StringBuilder("coalesce(c" + f.getKey() + ", '" + Pivot.BLANK + "') IN (");
            boolean firstValue = true;
            for (String s : f.getValue()) {
                in.append(firstValue ? "" : ", ").append(literal(s));
                firstValue = false;
            }
            where.add(in.append(')').toString());
        }
        inner.append(" WHERE ").append(String.join(" AND ", where));
        // every level of rows, by the column groups and in total
        List<String> agg = new ArrayList<>();
        agg.add("count(*) AS n");
        for (int v = 0; v < values.size(); v++) {
            Pivot.Value val = values.get(v);
            if (val.read().size() > 1) {
                agg.add("sum(v" + v + "_cnt)");
                agg.add("sum(v" + v + "_nums)");
                agg.add("sum(v" + v + "_s)");
                agg.add("min(v" + v + "_mn)");
                agg.add("max(v" + v + "_mx)");
                agg.add("NULL");
                agg.add("NULL");
            } else if (val.column() < 0) {
                agg.add("count(*)");
                agg.add("0");
                agg.add("NULL");
                agg.add("NULL");
                agg.add("NULL");
                agg.add(val.agg() == Pivot.Agg.COUNT_DISTINCT ? "1" : "NULL");
                agg.add("NULL");
            } else {
                agg.add("count(v" + v + "_t)");
                agg.add("count(v" + v + "_n)");
                agg.add("sum(v" + v + "_n)");
                agg.add("min(v" + v + "_n)");
                agg.add("max(v" + v + "_n)");
                agg.add(val.agg() == Pivot.Agg.COUNT_DISTINCT ? "count(DISTINCT v" + v + "_t)" : "NULL");
                double q = switch (val.agg()) {
                    case MEDIAN -> 0.5;
                    case P95 -> 0.95;
                    case P99 -> 0.99;
                    default -> -1;
                };
                agg.add(q < 0 ? "NULL" : "quantile_cont(v" + v + "_n, " + q + ")");
            }
        }
        List<String> sets = new ArrayList<>();
        for (int i = 0; i <= nr; i++) {
            List<String> g = new ArrayList<>();
            for (int r = 0; r < i; r++) {
                g.add("r" + r);
            }
            sets.add("(" + String.join(", ", g) + ")");
            if (nm > 0) {
                List<String> gc = new ArrayList<>(g);
                for (int c = 0; c < nm; c++) {
                    gc.add("k" + c);
                }
                sets.add("(" + String.join(", ", gc) + ")");
            }
        }
        List<String> outer = new ArrayList<>();
        for (int r = 0; r < nr; r++) {
            outer.add("r" + r);
            outer.add("GROUPING(r" + r + ")");
        }
        for (int c = 0; c < nm; c++) {
            outer.add("k" + c);
        }
        if (nm > 0) {
            outer.add("GROUPING(k0)");
        }
        String sql = "SELECT " + String.join(", ", outer) + (outer.isEmpty() ? "" : ", ") + String.join(", ", agg)
                + " FROM (" + inner + ") GROUP BY GROUPING SETS (" + String.join(", ", sets) + ")";
        Pivot.Node root = new Pivot.Node("Grand Total", null, 0);
        Map<List<String>, Integer> colIndex = new HashMap<>();
        List<List<String>> colKeys = new ArrayList<>();
        int groups = 0;
        int nv = values.size();
        List<Object[]> rows;
        try {
            rows = session.run(sql).getRows();
        } catch (RuntimeException e) {
            throw new IllegalStateException("The Pivot cache couldn't be read: " + e.getMessage(), e);
        }
        for (Object[] rs : rows) {
            int at = 0;
            List<String> path = new ArrayList<>();
            for (int r = 0; r < nr; r++) {
                String label = (String) rs[at++];
                boolean grouped = num(rs[at++]) == 0;
                if (grouped) {
                    path.add(label);
                }
            }
            List<String> key = new ArrayList<>();
            for (int c = 0; c < nm; c++) {
                key.add((String) rs[at++]);
            }
            boolean byCol = nm > 0 && num(rs[at++]) == 0;
            long n = num(rs[at++]);
            Pivot.Node node = root;
            for (String l : path) {
                if (node.kids == null) {
                    node.kids = new LinkedHashMap<>();
                }
                Pivot.Node k = node.kids.get(l);
                if (k == null) {
                    if (++groups > Pivot.MAX_ROW_GROUPS) {
                        return null; // more than Pivot shows: it puts the rest in "(other)" itself
                    }
                    k = new Pivot.Node(l, node, node.depth + 1);
                    node.kids.put(l, k);
                }
                node = k;
            }
            Pivot.Acc[] accs = new Pivot.Acc[nv];
            for (int v = 0; v < nv; v++) {
                Pivot.Acc a = new Pivot.Acc();
                a.count = num(rs[at++]);
                a.numbers = num(rs[at++]);
                Object s = rs[at++];
                a.sum = s == null ? 0 : ((Number) s).doubleValue();
                Object mn = rs[at++];
                a.min = mn == null ? Double.POSITIVE_INFINITY : ((Number) mn).doubleValue();
                Object mx = rs[at++];
                a.max = mx == null ? Double.NEGATIVE_INFINITY : ((Number) mx).doubleValue();
                Object d = rs[at++];
                if (d != null) {
                    a.fixedDistinct = ((Number) d).longValue();
                }
                Object q = rs[at++];
                if (q != null) {
                    switch (values.get(v).agg()) {
                        case MEDIAN -> a.fixedMedian = ((Number) q).doubleValue();
                        case P95 -> a.fixedP95 = ((Number) q).doubleValue();
                        case P99 -> a.fixedP99 = ((Number) q).doubleValue();
                        default -> { }
                    }
                }
                accs[v] = a;
            }
            if (node.cells == null) {
                node.cells = new Pivot.Acc[4][];
            }
            int cell;
            if (byCol) {
                Integer g = colIndex.get(key);
                if (g == null) {
                    if (colKeys.size() >= Pivot.MAX_COL_GROUPS) {
                        return null;
                    }
                    g = colKeys.size();
                    colIndex.put(key, g);
                    colKeys.add(key);
                }
                cell = g + 1;
            } else {
                cell = 0; // the row's total
                node.rows = n;
            }
            if (cell >= node.cells.length) {
                node.cells = java.util.Arrays.copyOf(node.cells, cell + 4);
            }
            node.cells[cell] = accs;
        }
        Map<Integer, List<String>> filterValues = new HashMap<>();
        for (int c : spec.filters().keySet()) {
            // the first ones in the file, as Pivot lists them
            List<String> l = new ArrayList<>();
            for (Object[] r : session.run("SELECT v FROM (SELECT coalesce(c" + c + ", '" + Pivot.BLANK
                    + "') AS v, min(line) AS first_row FROM " + TABLE + " WHERE pv GROUP BY 1) ORDER BY first_row LIMIT "
                    + Pivot.MAX_FILTER_VALUES).getRows()) {
                l.add((String) r[0]);
            }
            filterValues.put(c, l);
        }
        Pivot.Result res = Pivot.finish(spec, format.titles(), root, colKeys, root.rows, lineCount, false, false,
                filterValues, t0);
        res.cached = true;
        return res;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /** A field's label, as Pivot makes it: the text, "(blank)", or its time group. */
    private static String label(Pivot.Field f, Pivot.Fields fields) {
        String x = "c" + f.column();
        boolean time = f.group() != Pivot.Group.NONE && fields != null && f.column() < fields.times().size()
                && fields.times().get(f.column()) != null;
        if (!time) {
            return "coalesce(" + x + ", '" + Pivot.BLANK + "')";
        }
        String e = "e" + f.column();
        return "CASE WHEN " + x + " IS NULL THEN '" + Pivot.BLANK + "' WHEN " + e + " IS NULL THEN " + x
                + " ELSE logsift_time(" + e + ", " + f.group().ordinal() + ") END";
    }

    /** Time groups are labelled by Pivot's own code, called from SQL. */
    private synchronized void registerTime(ZoneId zone) {
        if (zone.equals(udfZone)) {
            return;
        }
        table.function("logsift_time", String.class, Long.class, Integer.class,
                (ms, g) -> Pivot.timeLabel(ms, Pivot.Group.values()[g], zone));
        udfZone = zone;
    }

    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    @Override
    public void close() {
        try {
            session.close();
        } finally {
            table.close();
        }
    }
}
