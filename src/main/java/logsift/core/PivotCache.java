package logsift.core;

import io.quackjvm.agent.udf.Udfs;
import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.TableWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

/**
 * The columns of a big table that Pivot has used, kept in a DuckDB file so the next pivot doesn't
 * read the whole file again: 5 million rows are summed up in milliseconds instead of seconds, and
 * a table of 6,000 columns no longer has every row split.
 *
 * <p>Logsift reads the file as Pivot does (the same splitting, blanks, numbers and times) and
 * stores each column it needs as a table of its own, in file order: {@code c<i>(t, n, e)} with the
 * text, its number and its time. "Values as One" over many columns keeps each row's count, sum,
 * min and max in a table {@code p_<key>}. DuckDB then groups and totals; the labels and answers are
 * what Pivot gives when it reads the file (median and percentiles exactly, where Pivot samples
 * past a million numbers).
 *
 * <p>The cache is for one version of the file: its name holds the file's path, size and time, so
 * a changed file gets a new one. Old ones are removed, oldest first, past a size limit.
 */
public final class PivotCache implements AutoCloseable {
    /** Changes when the stored layout changes, so older caches aren't read. */
    static final int VERSION = 1;
    /** Files smaller than this are pivoted from the file itself, quick enough. */
    public static final long MIN_BYTES = 200L << 20;
    /** The default room for caches. */
    public static final long DEFAULT_LIMIT = 5L << 30;

    private final Quack db;
    private final Path file;
    private final LogFormat format;
    /** Tables built: name to rows. */
    private final Map<String, Long> tables = new HashMap<>();
    private ZoneId udfZone;
    /** The name it was opened by. */
    private String key;

    private final String read;

    private PivotCache(Quack db, Path file, LogFormat format, String read) {
        this.db = db;
        this.file = file;
        this.format = format;
        this.read = read;
    }

    /**
     * The cache of this version of the file, in {@code dir} (made if need be). Older caches past
     * {@code limitBytes} are removed first, oldest used first.
     */
    public static PivotCache open(Path file, LogFormat format, Path dir, long limitBytes) throws IOException {
        return open(file, format, "", dir, limitBytes);
    }

    /**
     * Programs that can have one file's cache open at the same time (the app and an agent's
     * logsift-mcp, say). DuckDB lets one program at a time write a file, so each beyond the first
     * gets a copy of its own next to it.
     */
    static final int MAX_COPIES = 4;

    /** As {@link #open(Path, LogFormat, Path, long)}; {@code read} tells how the file is read (its encoding). */
    public static PivotCache open(Path file, LogFormat format, String read, Path dir, long limitBytes)
            throws IOException {
        Files.createDirectories(dir);
        String key = key(file, format, read);
        trim(dir, limitBytes, key);
        io.quackjvm.core.DatabaseInUse inUse = null;
        for (int copy = 1; copy <= MAX_COPIES; copy++) {
            Path db = dir.resolve(fileName(key, copy));
            if (Files.exists(db)) {
                Files.setLastModifiedTime(db, FileTime.fromMillis(System.currentTimeMillis())); // used now
            }
            Quack q;
            try {
                q = open(db);
            } catch (io.quackjvm.core.DatabaseInUse e) {
                inUse = e; // another program has it open: never removed, the next copy is tried
                continue;
            }
            PivotCache c = new PivotCache(q, file, format, read);
            c.key = key;
            c.load();
            return c;
        }
        throw new IOException("Other programs have every copy of this file's cache open", inUse);
    }

    /**
     * The cache file at {@code db}, made again if it can't be opened (cut short, or written by a
     * newer DuckDB). One another program has open is left as it is: {@link io.quackjvm.core.DatabaseInUse}.
     */
    private static Quack open(Path db) {
        try {
            return Quack.open(db.toString());
        } catch (io.quackjvm.core.DatabaseInUse e) {
            throw e;
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(db);
                Files.deleteIfExists(Path.of(db + ".wal"));
            } catch (IOException gone) {
                throw e;
            }
            return Quack.open(db.toString());
        }
    }

    /** The file of a cache: the first copy {@code <key>.duckdb}, then {@code <key>.duckdb.2} and so on. */
    static String fileName(String key, int copy) {
        return key + ".duckdb" + (copy == 1 ? "" : "." + copy);
    }

    /** The name of a file's cache: its path, size, time and columns. */
    static String key(Path file, LogFormat format, String read) throws IOException {
        String id = file.toAbsolutePath().normalize() + "|" + read + "|" + Files.size(file) + "|"
                + Files.getLastModifiedTime(file).toMillis() + "|" + (int) format.delimiter() + "|"
                + String.join("\u0001", format.columns()) + "|" + VERSION;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                b.append(String.format("%02x", h[i]));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Removes caches, oldest used first, until those left (but {@code keep}) fit in {@code limit}. */
    static void trim(Path dir, long limit, String keep) throws IOException {
        record Entry(String key, long bytes, long used) {}
        Map<String, long[]> by = new HashMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                int dot = name.indexOf('.');
                if (dot != 32 || !name.substring(dot).startsWith(".duckdb")) {
                    continue; // only our own files
                }
                long[] e = by.computeIfAbsent(name.substring(0, dot), k -> new long[2]);
                e[0] += Files.size(p);
                e[1] = Math.max(e[1], Files.getLastModifiedTime(p).toMillis());
            }
        }
        List<Entry> all = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, long[]> e : by.entrySet()) {
            all.add(new Entry(e.getKey(), e.getValue()[0], e.getValue()[1]));
            total += e.getValue()[0];
        }
        all.sort(java.util.Comparator.comparingLong(Entry::used));
        for (Entry e : all) {
            if (total <= limit) {
                break;
            }
            if (!e.key().equals(keep) && deleteCache(dir, e.key())) {
                total -= e.bytes();
            }
        }
    }

    /** How much room the caches in {@code dir} take. */
    public static long size(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long total = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.getFileName().toString().contains(".duckdb")) {
                    total += Files.size(p);
                }
            }
        }
        return total;
    }

    /** Removes every cache in {@code dir} but those in use ({@code keep}). */
    public static void clear(Path dir, Set<String> keep) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Set<String> keys = new HashSet<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                int dot = name.indexOf('.');
                if (dot == 32 && name.substring(dot).startsWith(".duckdb")) {
                    keys.add(name.substring(0, dot));
                }
            }
        }
        for (String k : keys) {
            if (!keep.contains(k)) {
                deleteCache(dir, k);
            }
        }
    }

    /**
     * Removes a cache's files (every copy); one that can't be removed (open in another program on
     * Windows) stays. Gives whether all went.
     */
    private static boolean deleteCache(Path dir, String key) throws IOException {
        boolean all = true;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.getFileName().toString().startsWith(key + ".duckdb")) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        all = false;
                    }
                }
            }
        }
        return all;
    }

    /** This cache's name, for the file as it is now: another than when opened if the file changed. */
    public String key() throws IOException {
        return key(file, format, read);
    }

    /** Whether it is the cache of this file as it is now, read this way. */
    public boolean isFor(Path file, LogFormat format, String read) {
        try {
            return this.file.equals(file) && this.format.equals(format) && this.read.equals(read)
                    && key.equals(key(file, format, read));
        } catch (IOException e) {
            return false;
        }
    }

    private void load() {
        db.execute("CREATE TABLE IF NOT EXISTS built (name VARCHAR PRIMARY KEY, rows BIGINT)");
        db.query("SELECT name, rows FROM built").forEachRow(r -> tables.put(r.getString(1), r.getLong(2)));
        // a build that didn't finish left a table that isn't listed: it goes
        List<String> present = db.query("SELECT table_name FROM duckdb_tables() WHERE schema_name = 'main'")
                .list(String.class);
        for (String t : present) {
            if (!t.equals("built") && !tables.containsKey(t)) {
                db.execute("DROP TABLE IF EXISTS \"" + t + "\"");
            }
        }
    }

    // ---------------------------------------------------------------- what a pivot needs

    /** The tables a pivot reads, or null when its answer can't come from the cache. */
    private record Needs(Set<Integer> columns, Map<String, List<Integer>> pools) {}

    private static Needs needs(Spec spec) {
        Set<Integer> cols = new TreeSet<>();
        Map<String, List<Integer>> pools = new LinkedHashMap<>();
        for (Integer f : spec.filters().keySet()) {
            cols.add(f);
        }
        spec.rows().forEach(f -> cols.add(f.column()));
        spec.cols().forEach(f -> cols.add(f.column()));
        for (Pivot.Value v : spec.cellValues()) {
            List<Integer> read = v.read();
            if (read.size() > 1) {
                if (v.agg() == Pivot.Agg.COUNT_DISTINCT || v.agg() == Pivot.Agg.MEDIAN || v.agg() == Pivot.Agg.P95
                        || v.agg() == Pivot.Agg.P99) {
                    return null; // over many columns: the file is read
                }
                pools.put(poolName(read), read);
            } else if (v.column() >= 0) {
                cols.add(v.column());
            }
        }
        return new Needs(cols, pools);
    }

    private record Spec(List<Pivot.Field> rows, List<Pivot.Field> cols, List<Pivot.Value> cellValues,
                        Map<Integer, Set<String>> filters) {
        static Spec of(Pivot.Spec s) {
            return new Spec(s.rows(), s.cols(), s.cellValues(), s.filters());
        }
    }

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

    /** Whether this pivot can be answered from the cache, as it is now. */
    public synchronized boolean has(Pivot.Spec spec) {
        Needs n = needs(Spec.of(spec));
        if (n == null || !tables.containsKey("lines")) {
            return false;
        }
        for (int c : n.columns()) {
            if (!tables.containsKey("c" + c)) {
                return false;
            }
        }
        for (String p : n.pools().keySet()) {
            if (!tables.containsKey(p)) {
                return false;
            }
        }
        return true;
    }

    /** Whether this pivot can be answered from a cache at all (else the file is always read). */
    public static boolean canCache(Pivot.Spec spec) {
        return needs(Spec.of(spec)) != null;
    }

    // ---------------------------------------------------------------- building

    /**
     * Reads the file once and stores the columns this pivot needs that aren't stored yet. Gives
     * false when cancelled (nothing half-built is kept).
     */
    public boolean build(Pivot.Spec spec, Pivot.Fields fields, ColumnProfile.Lines lines, long lineCount,
                         BooleanSupplier cancelled, DoubleConsumer progress) {
        // one build at a time: a pivot started while another (cancelled) one still stops
        buildLock.lock();
        try {
            return buildNow(spec, fields, lines, lineCount, cancelled, progress);
        } finally {
            buildLock.unlock();
        }
    }

    private final java.util.concurrent.locks.ReentrantLock buildLock = new java.util.concurrent.locks.ReentrantLock();

    private boolean buildNow(Pivot.Spec spec, Pivot.Fields fields, ColumnProfile.Lines lines, long lineCount,
                             BooleanSupplier cancelled, DoubleConsumer progress) {
        Needs n = needs(Spec.of(spec));
        if (n == null) {
            return false;
        }
        List<Integer> cols = new ArrayList<>();
        synchronized (this) {
            for (int c : n.columns()) {
                if (!tables.containsKey("c" + c)) {
                    cols.add(c);
                }
            }
        }
        Map<String, List<Integer>> pools = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : n.pools().entrySet()) {
            if (!tables.containsKey(e.getKey())) {
                pools.put(e.getKey(), e.getValue());
            }
        }
        boolean withLines = !tables.containsKey("lines");
        if (cols.isEmpty() && pools.isEmpty() && !withLines) {
            return true;
        }
        int nc = format.columns().size();
        boolean[] want = new boolean[nc];
        cols.forEach(c -> {
            if (c < nc) {
                want[c] = true;
            }
        });
        pools.values().forEach(l -> l.forEach(c -> {
            if (c >= 0 && c < nc) {
                want[c] = true;
            }
        }));
        boolean[] time = new boolean[cols.size()];
        List<TableWriter> writers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (withLines) {
            names.add("lines");
            writers.add(new TableWriter("lines", List.of(new ColumnDef("line", Long.class)), false, 16));
        }
        for (int i = 0; i < cols.size(); i++) {
            int c = cols.get(i);
            time[i] = fields != null && c < fields.times().size() && fields.times().get(c) != null;
            List<ColumnDef> defs = new ArrayList<>(List.of(new ColumnDef("t", String.class),
                    new ColumnDef("n", Double.class)));
            if (time[i]) {
                defs.add(new ColumnDef("e", Long.class));
            }
            names.add("c" + c);
            writers.add(new TableWriter("c" + c, defs, false, 16));
        }
        int[][] poolCols = new int[pools.size()][];
        int pi = 0;
        for (Map.Entry<String, List<Integer>> e : pools.entrySet()) {
            names.add(e.getKey());
            writers.add(new TableWriter(e.getKey(), List.of(new ColumnDef("cnt", Integer.class),
                    new ColumnDef("nums", Integer.class), new ColumnDef("s", Double.class),
                    new ColumnDef("mn", Double.class), new ColumnDef("mx", Double.class)), false, 16));
            poolCols[pi++] = e.getValue().stream().mapToInt(Integer::intValue).toArray();
        }
        int tablesOut = writers.size();
        int[] colIdx = cols.stream().mapToInt(Integer::intValue).toArray();
        List<TimeFormat> times = fields == null ? List.of() : fields.times();
        try (Connection con = db.connection()) {
            for (int i = 0; i < tablesOut; i++) {
                try (Statement st = con.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS \"" + names.get(i) + "\"");
                }
                writers.get(i).createTable(con, false);
            }
            List<TableWriter.AppenderHandle> aps = new ArrayList<>();
            long rows = 0;
            boolean done = false;
            try {
                for (TableWriter w : writers) {
                    aps.add(w.openAppender(con));
                }
                int chunk = Pivot.chunkLines(nc, cols.size() + poolCols.length);
                int threads = Math.max(1, Math.min(Pivot.MAX_THREADS, Runtime.getRuntime().availableProcessors() - 1));
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads,
                        r -> {
                            Thread t = new Thread(r, "logsift-pivot-cache");
                            t.setDaemon(true);
                            return t;
                        });
                java.util.ArrayDeque<java.util.concurrent.Future<List<Object[][]>>> pending = new java.util.ArrayDeque<>();
                try {
                    for (long first = 0; first < lineCount; first += chunk) {
                        if (cancelled.getAsBoolean()) {
                            return false;
                        }
                        long at = first;
                        int k = (int) Math.min(chunk, lineCount - first);
                        pending.add(pool.submit(() -> rowsOf(lines, at, k, want, colIdx, time, times, poolCols,
                                withLines)));
                        while (pending.size() >= threads * 2) {
                            rows += append(pending.poll().get(), aps);
                        }
                        if (progress != null) {
                            progress.accept((double) (first + k) / Math.max(1, lineCount));
                        }
                    }
                    while (!pending.isEmpty()) {
                        if (cancelled.getAsBoolean()) {
                            return false;
                        }
                        rows += append(pending.poll().get(), aps);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
                } finally {
                    pool.shutdownNow();
                }
                done = true;
            } finally {
                for (TableWriter.AppenderHandle a : aps) {
                    try {
                        a.close();
                    } catch (RuntimeException e) {
                        done = false;
                    }
                }
                if (!done) {
                    for (String t : names) {
                        try (Statement st = con.createStatement()) {
                            st.execute("DROP TABLE IF EXISTS \"" + t + "\"");
                        }
                    }
                }
            }
            if (!withLines && tables.get("lines") != rows) {
                throw new IllegalStateException("the cache has " + tables.get("lines") + " rows, this read " + rows);
            }
            try (var ps = con.prepareStatement("INSERT OR REPLACE INTO built VALUES (?, ?)")) {
                for (String t : names) {
                    ps.setString(1, t);
                    ps.setLong(2, rows);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            synchronized (this) {
                for (String t : names) {
                    tables.put(t, rows);
                }
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("The Pivot cache couldn't be written: " + e.getMessage(), e);
        }
    }

    /** A block of lines as the rows of each table being built: {@code [table][row]} per line. */
    private List<Object[][]> rowsOf(ColumnProfile.Lines lines, long first, int k, boolean[] want, int[] cols,
                                    boolean[] time, List<TimeFormat> times, int[][] pools, boolean withLines) {
        String[] buf = new String[k];
        lines.read(first, k, buf);
        List<Object[][]> out = new ArrayList<>(k);
        int n = (withLines ? 1 : 0) + cols.length + pools.length;
        for (int i = 0; i < k; i++) {
            String t = buf[i];
            if (t == null || t.isBlank()) {
                continue; // as Pivot skips them
            }
            String[] cells = format.splitSome(t, want);
            if (cells == null) {
                continue;
            }
            Object[][] r = new Object[n][];
            int j = 0;
            if (withLines) {
                r[j++] = new Object[] {first + i};
            }
            for (int x = 0; x < cols.length; x++) {
                String v = text(cells, cols[x]);
                Double num = null;
                Long e = null;
                if (v != null) {
                    double d = Pivot.number(v);
                    num = Double.isNaN(d) ? null : d;
                    if (time[x]) {
                        long ms = times.get(cols[x]).parse(v);
                        e = ms == TimeFormat.NONE ? null : ms;
                    }
                }
                r[j++] = time[x] ? new Object[] {v, num, e} : new Object[] {v, num};
            }
            for (int[] p : pools) {
                int cnt = 0;
                int nums = 0;
                double s = 0;
                double mn = Double.POSITIVE_INFINITY;
                double mx = Double.NEGATIVE_INFINITY;
                for (int c : p) {
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
                r[j++] = new Object[] {cnt, nums, nums == 0 ? null : s, nums == 0 ? null : mn, nums == 0 ? null : mx};
            }
            out.add(r);
        }
        return out;
    }

    /** A cell as Pivot reads it: stripped, null when blank. */
    private static String text(String[] cells, int c) {
        String v = c < cells.length && cells[c] != null ? cells[c].strip() : "";
        return v.isEmpty() ? null : v;
    }

    private static long append(List<Object[][]> rows, List<TableWriter.AppenderHandle> aps) {
        for (Object[][] r : rows) {
            for (int i = 0; i < r.length; i++) {
                aps.get(i).appendRow(r[i]);
            }
        }
        return rows.size();
    }

    // ---------------------------------------------------------------- column filters

    /**
     * Whether column {@code c}'s values are stored as column filters read them: each row's value
     * as {@link LogFormat#value} gives it (not stripped), by the row's first line.
     */
    public synchronized boolean hasValues(int c) {
        return tables.containsKey("v" + c);
    }

    /**
     * Reads the file once and stores the values of these columns as filters read them ({@code rows}
     * gives each row whole by its first line, null where a line continues the row above). Gives
     * false when cancelled (nothing half-built is kept).
     */
    public boolean buildValues(List<Integer> columns, ColumnProfile.Lines rows, long lineCount,
                               BooleanSupplier cancelled, DoubleConsumer progress) {
        buildLock.lock();
        try {
            List<Integer> cols = new ArrayList<>();
            for (int c : new TreeSet<>(columns)) {
                if (c >= 0 && c < format.columns().size() && !hasValues(c)) {
                    cols.add(c);
                }
            }
            if (cols.isEmpty()) {
                return true;
            }
            List<String> names = new ArrayList<>();
            List<TableWriter> writers = new ArrayList<>();
            for (int c : cols) {
                names.add("v" + c);
                writers.add(new TableWriter("v" + c, List.of(new ColumnDef("line", Long.class),
                        new ColumnDef("v", String.class)), false, 16));
            }
            try (Connection con = db.connection()) {
                for (int i = 0; i < names.size(); i++) {
                    try (Statement st = con.createStatement()) {
                        st.execute("DROP TABLE IF EXISTS \"" + names.get(i) + "\"");
                    }
                    writers.get(i).createTable(con, false);
                }
                List<TableWriter.AppenderHandle> aps = new ArrayList<>();
                boolean done = false;
                try {
                    for (TableWriter w : writers) {
                        aps.add(w.openAppender(con));
                    }
                    done = appendValues(cols, rows, lineCount, cancelled, progress, aps);
                } finally {
                    for (TableWriter.AppenderHandle a : aps) {
                        try {
                            a.close();
                        } catch (RuntimeException e) {
                            done = false;
                        }
                    }
                    if (!done) {
                        for (String t : names) {
                            try (Statement st = con.createStatement()) {
                                st.execute("DROP TABLE IF EXISTS \"" + t + "\"");
                            }
                        }
                    }
                }
                if (!done) {
                    return false;
                }
                try (var ps = con.prepareStatement("INSERT OR REPLACE INTO built VALUES (?, ?)")) {
                    for (String t : names) {
                        ps.setString(1, t);
                        ps.setLong(2, lineCount);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                synchronized (this) {
                    for (String t : names) {
                        tables.put(t, lineCount);
                    }
                }
                return true;
            } catch (SQLException e) {
                throw new IllegalStateException("The cache couldn't be written: " + e.getMessage(), e);
            }
        } finally {
            buildLock.unlock();
        }
    }

    /** Lines read at a time for column values, each block on one of several threads. */
    private static final int VALUE_BLOCK = 4096;

    private boolean appendValues(List<Integer> cols, ColumnProfile.Lines rows, long lineCount,
                                 BooleanSupplier cancelled, DoubleConsumer progress,
                                 List<TableWriter.AppenderHandle> aps) {
        List<String> names = new ArrayList<>();
        for (int c : cols) {
            names.add(format.columns().get(c));
        }
        int threads = Math.max(1, Math.min(Pivot.MAX_THREADS, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "logsift-filter-cache");
            t.setDaemon(true);
            return t;
        });
        java.util.ArrayDeque<java.util.concurrent.Future<List<Object[][]>>> pending = new java.util.ArrayDeque<>();
        try {
            for (long first = 0; first < lineCount; first += VALUE_BLOCK) {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                long at = first;
                int k = (int) Math.min(VALUE_BLOCK, lineCount - first);
                pending.add(pool.submit(() -> valuesOf(rows, at, k, names)));
                while (pending.size() >= threads * 2) {
                    appendValueRows(pending.poll().get(), aps);
                }
                if (progress != null) {
                    progress.accept((double) (first + k) / Math.max(1, lineCount));
                }
            }
            while (!pending.isEmpty()) {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                appendValueRows(pending.poll().get(), aps);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** A block of rows as {@code [column][line, value]}: the rows a column filter would test. */
    private List<Object[][]> valuesOf(ColumnProfile.Lines rows, long first, int k, List<String> names) {
        String[] buf = new String[k];
        rows.read(first, k, buf);
        List<Object[][]> out = new ArrayList<>(names.size());
        for (int c = 0; c < names.size(); c++) {
            List<Object[]> col = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                if (buf[i] == null) {
                    continue; // continues the row above: a row is matched at its first line
                }
                String v = format.value(buf[i], names.get(c));
                if (v != null) {
                    col.add(new Object[] {first + i, v});
                }
            }
            out.add(col.toArray(new Object[0][]));
        }
        return out;
    }

    private static void appendValueRows(List<Object[][]> block, List<TableWriter.AppenderHandle> aps) {
        for (int c = 0; c < block.size(); c++) {
            for (Object[] row : block.get(c)) {
                aps.get(c).appendRow(row);
            }
        }
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
        String where = " FROM v" + c + " WHERE line < " + upTo;
        List<ColumnStats.Value> values = new ArrayList<>();
        long lines;
        long distinct;
        try (Connection con = db.connection(); Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*), count(DISTINCT v)" + where)) {
                rs.next();
                lines = rs.getLong(1);
                distinct = rs.getLong(2);
            }
            if (stop.getAsBoolean()) {
                return null;
            }
            try (ResultSet rs = st.executeQuery("SELECT v, count(*), min(line) AS first_line" + where
                    + " GROUP BY v ORDER BY first_line LIMIT " + ColumnStats.MAX_VALUES)) {
                while (rs.next()) {
                    values.add(new ColumnStats.Value(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                }
            }
        } catch (SQLException e) {
            if (stop.getAsBoolean()) {
                return null;
            }
            throw new IllegalStateException("The cache couldn't be read: " + e.getMessage(), e);
        }
        if (stop.getAsBoolean()) {
            return null;
        }
        values.sort(java.util.Comparator.comparingLong((ColumnStats.Value v) -> -v.count())
                .thenComparing(ColumnStats.Value::value));
        return new ColumnStats.Result(format.columns().get(c), values, lines, (System.nanoTime() - t0) / 1_000_000,
                distinct > values.size());
    }

    /** The tests of the filters being answered, by id, for the function SQL calls. */
    private final Map<Long, java.util.function.Predicate<String>> tests = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextTest = new java.util.concurrent.atomic.AtomicLong();
    private boolean testRegistered;

    /**
     * The lines before {@code upTo} whose value in column {@code c} passes {@code test}, in order;
     * null when the column's values aren't stored. The test runs once for each different value,
     * so it is exactly the filter's own (regexes, units, case).
     */
    public long[] matchingLines(int c, java.util.function.Predicate<String> test, long upTo, BooleanSupplier stop) {
        if (!hasValues(c)) {
            return null;
        }
        synchronized (this) {
            if (!testRegistered) {
                db.register(root -> Udfs.register(root, "logsift_keep", Boolean.class, Long.class, String.class,
                        (id, v) -> {
                            java.util.function.Predicate<String> t = tests.get(id);
                            return t != null && v != null && t.test(v);
                        }));
                testRegistered = true;
            }
        }
        long id = nextTest.incrementAndGet();
        tests.put(id, test);
        String sql = "SELECT line FROM v" + c + " WHERE line < " + upTo + " AND v IN (SELECT v FROM (SELECT DISTINCT v "
                + "FROM v" + c + ") WHERE logsift_keep(" + id + ", v)) ORDER BY line";
        try (Connection con = db.connection(); Statement st = con.createStatement()) {
            Thread watcher = new Thread(() -> {
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
            }, "logsift-filter-cache-cancel");
            watcher.setDaemon(true);
            watcher.start();
            try (ResultSet rs = st.executeQuery(sql)) {
                long[] out = new long[1024];
                int n = 0;
                while (rs.next()) {
                    if (n == out.length) {
                        out = java.util.Arrays.copyOf(out, n * 2);
                    }
                    out[n++] = rs.getLong(1);
                }
                return stop.getAsBoolean() ? null : java.util.Arrays.copyOf(out, n);
            } finally {
                watcher.interrupt();
            }
        } catch (SQLException e) {
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
     * a filter of the log's own lines, or more groups than Pivot shows).
     */
    public Pivot.Result compute(Pivot.Spec spec, Pivot.Fields fields, ZoneId zone, long lineCount) {
        long t0 = System.nanoTime();
        if (!has(spec)) {
            return null;
        }
        registerTime(zone);
        List<Pivot.Value> values = spec.cellValues();
        int nr = spec.rows().size();
        int nm = spec.cols().size();
        Set<Integer> used = new LinkedHashSet<>();
        StringBuilder inner = new StringBuilder("SELECT ");
        List<String> sel = new ArrayList<>();
        for (int r = 0; r < nr; r++) {
            sel.add(label(spec.rows().get(r), fields) + " AS r" + r);
            used.add(spec.rows().get(r).column());
        }
        for (int c = 0; c < nm; c++) {
            sel.add(label(spec.cols().get(c), fields) + " AS k" + c);
            used.add(spec.cols().get(c).column());
        }
        Set<String> poolsUsed = new LinkedHashSet<>();
        for (int v = 0; v < values.size(); v++) {
            Pivot.Value val = values.get(v);
            List<Integer> read = val.read();
            if (read.size() > 1) {
                String p = poolName(read);
                poolsUsed.add(p);
                sel.add(p + ".cnt AS v" + v + "_cnt");
                sel.add(p + ".nums AS v" + v + "_nums");
                sel.add(p + ".s AS v" + v + "_s");
                sel.add(p + ".mn AS v" + v + "_mn");
                sel.add(p + ".mx AS v" + v + "_mx");
            } else if (val.column() >= 0) {
                used.add(val.column());
                sel.add("x" + val.column() + ".t AS v" + v + "_t");
                sel.add("x" + val.column() + ".n AS v" + v + "_n");
            }
        }
        if (sel.isEmpty()) {
            sel.add("1 AS one");
        }
        inner.append(String.join(", ", sel)).append(" FROM lines");
        for (int c : spec.filters().keySet()) {
            used.add(c);
        }
        for (int c : used) {
            inner.append(" POSITIONAL JOIN c").append(c).append(" AS x").append(c);
        }
        for (String p : poolsUsed) {
            inner.append(" POSITIONAL JOIN ").append(p);
        }
        List<String> where = new ArrayList<>();
        for (Map.Entry<Integer, Set<String>> f : spec.filters().entrySet()) {
            if (f.getValue() == null) {
                continue;
            }
            if (f.getValue().isEmpty()) {
                where.add("false");
                continue;
            }
            StringBuilder in = new StringBuilder("coalesce(x" + f.getKey() + ".t, '" + Pivot.BLANK + "') IN (");
            boolean firstValue = true;
            for (String s : f.getValue()) {
                in.append(firstValue ? "" : ", ").append(literal(s));
                firstValue = false;
            }
            where.add(in.append(')').toString());
        }
        if (!where.isEmpty()) {
            inner.append(" WHERE ").append(String.join(" AND ", where));
        }
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
        int[] groups = {0};
        int nv = values.size();
        try (Connection con = db.connection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int at = 1;
                List<String> path = new ArrayList<>();
                for (int r = 0; r < nr; r++) {
                    String label = rs.getString(at++);
                    boolean grouped = rs.getInt(at++) == 0;
                    if (grouped) {
                        path.add(label);
                    }
                }
                List<String> key = new ArrayList<>();
                for (int c = 0; c < nm; c++) {
                    key.add(rs.getString(at++));
                }
                boolean byCol = nm > 0 && rs.getInt(at++) == 0;
                long n = rs.getLong(at++);
                Pivot.Node node = root;
                for (String l : path) {
                    if (node.kids == null) {
                        node.kids = new LinkedHashMap<>();
                    }
                    Pivot.Node k = node.kids.get(l);
                    if (k == null) {
                        if (++groups[0] > Pivot.MAX_ROW_GROUPS) {
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
                    a.count = rs.getLong(at++);
                    a.numbers = rs.getLong(at++);
                    double s = rs.getDouble(at++);
                    a.sum = rs.wasNull() ? 0 : s;
                    double mn = rs.getDouble(at++);
                    a.min = rs.wasNull() ? Double.POSITIVE_INFINITY : mn;
                    double mx = rs.getDouble(at++);
                    a.max = rs.wasNull() ? Double.NEGATIVE_INFINITY : mx;
                    long d = rs.getLong(at++);
                    if (!rs.wasNull()) {
                        a.fixedDistinct = d;
                    }
                    double q = rs.getDouble(at++);
                    if (!rs.wasNull()) {
                        switch (values.get(v).agg()) {
                            case MEDIAN -> a.fixedMedian = q;
                            case P95 -> a.fixedP95 = q;
                            case P99 -> a.fixedP99 = q;
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
        } catch (SQLException e) {
            throw new IllegalStateException("The Pivot cache couldn't be read: " + e.getMessage(), e);
        }
        Map<Integer, List<String>> filterValues = new HashMap<>();
        for (int c : spec.filters().keySet()) {
            // the first ones in the file, as Pivot lists them
            filterValues.put(c, new ArrayList<>(db.query("SELECT v FROM (SELECT coalesce(t, '" + Pivot.BLANK
                    + "') AS v, min(rowid) AS first_row FROM c" + c + " GROUP BY 1) ORDER BY first_row LIMIT "
                    + Pivot.MAX_FILTER_VALUES).list(String.class)));
        }
        Pivot.Result res = Pivot.finish(spec, format.titles(), root, colKeys, root.rows, lineCount, false, false,
                filterValues, t0);
        res.cached = true;
        return res;
    }

    /** A field's label, as Pivot makes it: the text, "(blank)", or its time group. */
    private static String label(Pivot.Field f, Pivot.Fields fields) {
        String x = "x" + f.column();
        boolean time = f.group() != Pivot.Group.NONE && fields != null && f.column() < fields.times().size()
                && fields.times().get(f.column()) != null;
        if (!time) {
            return "coalesce(" + x + ".t, '" + Pivot.BLANK + "')";
        }
        return "CASE WHEN " + x + ".t IS NULL THEN '" + Pivot.BLANK + "' WHEN " + x + ".e IS NULL THEN " + x
                + ".t ELSE logsift_time(" + x + ".e, " + f.group().ordinal() + ") END";
    }

    /** Time groups are labelled by Pivot's own code, called from SQL. */
    private synchronized void registerTime(ZoneId zone) {
        if (zone.equals(udfZone)) {
            return;
        }
        db.register(root -> Udfs.register(root, "logsift_time", String.class, Long.class, Integer.class,
                (ms, g) -> Pivot.timeLabel(ms, Pivot.Group.values()[g], zone)));
        udfZone = zone;
    }

    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    @Override
    public void close() {
        db.close();
    }
}
