package logsift.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import logsift.core.CustomFormat;
import logsift.core.LogAnalysis;

/**
 * What one agent session has open, and the rules for what goes back to it: masking, size and
 * where it may read. Lives as long as the agent's connection.
 */
public final class Workspace implements AutoCloseable {

    /**
     * How the workspace behaves.
     *
     * @param scope     where files may be read
     * @param masking   what is hidden in answers
     * @param maxChars  the longest answer, unless a call asks for less
     * @param waitMillis how long a call waits for slow work before saying "still working"
     * @param formats   the user's own log formats
     * @param auditLog  where each call is recorded (null: nowhere)
     * @param logsiftHome Logsift's folder, for settings such as where crash dumps find names (null: none)
     */
    public record Options(Scope scope, Masking.Options masking, int maxChars, long waitMillis,
                          List<CustomFormat> formats, Path auditLog, Path logsiftHome) {
        public Options(Scope scope, Masking.Options masking, int maxChars, long waitMillis, List<CustomFormat> formats,
                       Path auditLog) {
            this(scope, masking, maxChars, waitMillis, formats, auditLog, null);
        }
    }

    /** The most an answer may be, whatever is asked (about 20,000 tokens). */
    public static final int HARD_MAX_CHARS = 80_000;
    public static final int DEFAULT_MAX_CHARS = 24_000;

    private final Options options;
    private final Masking masking;
    private final Map<String, LogAnalysis> logs = new LinkedHashMap<>();
    private final Map<String, Path> shown = new LinkedHashMap<>();
    private final Map<String, Future<?>> work = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "logsift-agent-work");
        t.setDaemon(true);
        return t;
    });
    private int nextLog = 1;
    private logsift.core.Sql sql;
    private final Map<String, logsift.core.Sql.LogTable> sqlTables = new LinkedHashMap<>();
    private volatile String client = "agent";
    private volatile java.util.function.Supplier<LogCatalog> catalog = () -> LogCatalog.EMPTY;

    public Workspace(Options options) {
        this.options = options;
        this.masking = new Masking(options.masking());
    }

    public Options options() {
        return options;
    }

    public Masking masking() {
        return masking;
    }

    /** Where the person's logs are (read again on each use, so edits count at once). */
    public void setCatalog(java.util.function.Supplier<LogCatalog> c) {
        catalog = c == null ? () -> LogCatalog.EMPTY : c;
    }

    public LogCatalog catalog() {
        try {
            return catalog.get();
        } catch (RuntimeException e) {
            return LogCatalog.EMPTY;
        }
    }

    /**
     * A path from what an agent gave: a path, or a name from the log locations ("orders/app":
     * the newest file of that log). A name that is neither stays a path (and fails as one).
     */
    public Path pathOf(String pathOrName) {
        String s = expandHome(pathOrName.strip());
        try {
            Path p = Path.of(s);
            if (p.isAbsolute() || Files.exists(p)) {
                return p;
            }
        } catch (RuntimeException e) {
            // not a path: maybe a name
        }
        Path named = catalog().resolve(s);
        return named != null ? named : Path.of(s);
    }

    /**
     * {@code ~} or {@code ~/…} as the home folder: a shell doesn't expand it inside quotes or
     * JSON, and agents write it often.
     */
    public static String expandHome(String s) {
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            return System.getProperty("user.home") + s.substring(1);
        }
        return s;
    }

    /** A path an agent gave ({@code ~} is the home folder). */
    public static Path path(String s) {
        return Path.of(expandHome(s.strip()));
    }

    /** The agent's name and version, as it said ("Visual Studio Code 1.106"). */
    public void setClient(String name) {
        client = name == null || name.isBlank() ? "agent" : name;
    }

    public String client() {
        return client;
    }

    // ---------------------------------------------------------------- logs

    /**
     * Opens a log (or finds it already open) and gives its handle, like {@code log:1}. A .gz
     * file, or a file in a zip ({@code logs.zip/app/app.log}), is unpacked to a temporary copy
     * first, as the window does.
     */
    public String open(Path path) throws Exception {
        Path abs = path.toAbsolutePath().normalize();
        synchronized (this) {
            for (Map.Entry<String, Path> e : shown.entrySet()) {
                if (e.getValue().equals(abs)) {
                    return e.getKey();
                }
            }
        }
        logsift.core.Archives.Located in = logsift.core.Archives.locate(abs);
        Path read;
        if (in != null && !Files.isRegularFile(abs)) {
            Path zip = options.scope().check(in.zip());
            read = await("unpack:" + abs, "unpacking " + in.entry() + " from " + zip.getFileName(),
                    () -> unpack(zip, in.entry()));
        } else {
            Path real = options.scope().check(abs);
            if (Files.isDirectory(real)) {
                throw new IOException("That is a folder: " + real + ". Use search_folder to search it.");
            }
            if (logsift.core.Archives.isZip(real)) {
                throw new IOException("That is a zip. Use search_folder on it, or open a file in it: "
                        + real.resolve("name-in-the-zip.log"));
            }
            read = logsift.core.Archives.isGzip(real)
                    ? await("unpack:" + abs, "unpacking " + real.getFileName(), () -> unpack(real, null)) : real;
        }
        synchronized (this) {
            for (Map.Entry<String, Path> e : shown.entrySet()) {
                if (e.getValue().equals(abs)) {
                    return e.getKey(); // opened meanwhile
                }
            }
            String h = "log:" + nextLog++;
            logs.put(h, LogAnalysis.open(read, options.formats()));
            shown.put(h, abs);
            return h;
        }
    }

    /**
     * A log open in the window, for an assistant in the same window: tools read the window's own
     * document, so there is nothing to read again and line numbers are the ones on screen. {@code
     * shown} is what it goes by (its path, or for a remote log its name). Gives its handle; the
     * same document again gives the same one.
     */
    public String adopt(Path shown, logsift.core.LogDocument doc) {
        Path abs = shown.toAbsolutePath().normalize();
        synchronized (this) {
            for (Map.Entry<String, LogAnalysis> e : logs.entrySet()) {
                if (e.getValue().document() == doc) {
                    return e.getKey();
                }
            }
            String h = "log:" + nextLog++;
            logs.put(h, LogAnalysis.borrowed(doc, abs, options.formats()));
            this.shown.put(h, abs);
            return h;
        }
    }

    /** Forgets a log the window closed (one it {@linkplain #adopt adopted}). */
    public void forget(logsift.core.LogDocument doc) {
        LogAnalysis gone = null;
        BigTable big = null;
        synchronized (this) {
            for (var it = logs.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                if (e.getValue().document() == doc) {
                    gone = e.getValue();
                    shown.remove(e.getKey());
                    big = bigTables.remove(e.getKey());
                    logsift.core.Sql.LogTable t = sqlTables.remove(e.getKey());
                    if (t != null && sql != null) {
                        try {
                            sql.drop(t);
                        } catch (java.sql.SQLException ignored) {
                            // gone with the session at the latest
                        }
                    }
                    it.remove();
                    break;
                }
            }
        }
        if (big != null) {
            big.close();
        }
        if (gone != null) {
            gone.close(); // leaves the document open: it's the window's
        }
    }

    /** Files Logsift wrote for this session (merged logs): removed when it ends. */
    private final List<Path> generated = new ArrayList<>();

    /**
     * Opens a file Logsift itself wrote for this session (a merge of logs, in its temporary
     * folder, so outside the agent's folders) and gives its handle.
     */
    public String openGenerated(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        synchronized (this) {
            String h = "log:" + nextLog++;
            logs.put(h, LogAnalysis.open(abs, options.formats()));
            shown.put(h, abs);
            generated.add(abs);
            return h;
        }
    }

    /** The path a log was opened by (the .gz or zip entry, not the copy read). */
    public synchronized Path shownPath(String handle) {
        return shown.get(handle);
    }

    private static Path unpack(Path archive, String entry) throws IOException, InterruptedException {
        String name = logsift.core.Archives.gunzippedName(entry != null
                ? logsift.core.Archives.baseName(entry) : archive.getFileName().toString());
        Path dir = Files.createTempDirectory("logsift-agent-");
        dir.toFile().deleteOnExit();
        Path out = dir.resolve(name.isBlank() ? "entry.log" : name);
        out.toFile().deleteOnExit();
        if (!logsift.core.Archives.extract(archive, entry, -1, out, null,
                () -> Thread.currentThread().isInterrupted())) {
            throw new InterruptedException();
        }
        return out;
    }

    /** The log of a handle ({@code log:1}) or a path (opened if needed). */
    public LogAnalysis log(String handleOrPath) throws Exception {
        String h = handleOrPath.strip();
        synchronized (this) {
            LogAnalysis a = logs.get(h);
            if (a != null) {
                return a;
            }
            if (h.matches("log:\\d+")) {
                throw new Tool.Failure("No open log " + h + ". Open it with open_log, or pass its path.");
            }
        }
        String opened = open(pathOf(h));
        synchronized (this) {
            return logs.get(opened);
        }
    }

    /** The handle of an open log. */
    public synchronized String handleOf(LogAnalysis a) {
        for (Map.Entry<String, LogAnalysis> e : logs.entrySet()) {
            if (e.getValue() == a) {
                return e.getKey();
            }
        }
        return "?";
    }

    public synchronized Map<String, LogAnalysis> logs() {
        return new LinkedHashMap<>(logs);
    }

    // ---------------------------------------------------------------- slow work

    /** Thrown when work goes on after the call's wait: the agent should call again. */
    public static final class StillWorking extends Exception {
        public StillWorking(String message) {
            super(message);
        }
    }

    /**
     * Runs slow work once per key in the background and waits for it a while. Calling again
     * with the same key waits for the same work, so an agent can poll.
     */
    @SuppressWarnings("unchecked")
    public <T> T await(String key, String what, Callable<T> job) throws Exception {
        Future<T> f;
        synchronized (this) {
            f = (Future<T>) work.get(key);
            if (f == null || f.isCancelled()) {
                f = pool.submit(job);
                work.put(key, f);
            }
        }
        try {
            return f.get(options.waitMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new StillWorking("Still " + what + ". Call the same tool again in a few seconds.");
        } catch (ExecutionException e) {
            synchronized (this) {
                work.remove(key);
            }
            Throwable c = e.getCause();
            throw c instanceof Exception ex ? ex : new IOException(c);
        }
    }

    // ---------------------------------------------------------------- answers

    /**
     * Masks an answer and cuts it to {@code maxChars}, saying how much was cut and how much
     * was masked.
     */
    public String finish(String text, int maxChars) {
        AtomicLong masked = new AtomicLong();
        String s = masking.mask(text, masked);
        int max = Math.max(500, Math.min(maxChars, HARD_MAX_CHARS));
        StringBuilder out = new StringBuilder();
        if (s.length() > max) {
            int cut = s.lastIndexOf('\n', max);
            if (cut < max / 2) {
                cut = max;
            }
            out.append(s, 0, cut).append("\n… cut here: ").append(String.format("%,d", s.length() - cut))
                    .append(" more characters. Ask for less (a smaller limit, a line or time range) to see the rest.");
        } else {
            out.append(s);
        }
        if (masked.get() > 0) {
            out.append("\n(Logsift masked ").append(masked.get()).append(masked.get() == 1 ? " value" : " values")
                    .append(" that looked like ").append(options.masking().describe()).append(".)");
        }
        return out.toString();
    }

    /** Records a call in the audit log: when, who, what, how big the answer was. Never the content. */
    public void audit(String tool, String args, int chars, String outcome) {
        Path f = options.auditLog();
        if (f == null) {
            return;
        }
        String line = java.time.OffsetDateTime.now().withNano(0) + "\t" + client + "\t" + tool + "\t"
                + masking.mask(args, null) + "\t" + chars + " chars\t" + outcome + "\n";
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // an audit log that can't be written doesn't stop the work
        }
    }

    /**
     * The SQL table of an open log (made and loaded the first time, brought up to date after):
     * read by Logsift itself, so SQL never reads a file (DuckDB gets no file access at all).
     */
    public synchronized logsift.core.Sql.LogTable sqlTable(String handle, LogAnalysis log, BooleanSupplierEx loader)
            throws Exception {
        if (sql == null) {
            sql = logsift.core.Sql.open(new logsift.core.Sql.Options(List.of(), null));
        }
        logsift.core.Sql.LogTable t = sqlTables.get(handle);
        if (t == null || t.format() != (log.formats() == null ? null : log.formats().columns())) {
            t = loader.make(sql);
            sqlTables.put(handle, t);
        }
        return t;
    }

    public synchronized logsift.core.Sql sql() {
        return sql;
    }

    // ---------------------------------------------------------------- big tables

    /**
     * A big table's cache of columns (the one the app uses, in {@code ~/.logsift/cache/pivot}),
     * for SQL and pivots that read only the columns they need.
     */
    public static final class BigTable implements AutoCloseable {
        final logsift.core.PivotCache cache;
        final logsift.core.LogFormat format;
        final logsift.core.Pivot.Fields fields;
        final logsift.core.TableQuery query;
        final logsift.core.LogDocument doc;

        BigTable(logsift.core.PivotCache cache, logsift.core.LogDocument doc, logsift.core.LogFormat format,
                 logsift.core.Pivot.Fields fields) throws java.sql.SQLException {
            this.cache = cache;
            this.doc = doc;
            this.format = format;
            this.fields = fields;
            this.query = new logsift.core.TableQuery(fields);
        }

        /** The cache's name: another when the file changed. */
        String key() throws IOException {
            return cache.key();
        }

        /** The table's columns as SQL names them. */
        public List<String> sqlColumns() {
            List<String> out = new ArrayList<>(List.of("line"));
            out.addAll(logsift.core.Sql.tableColumns(fields.titles()));
            return out;
        }

        /** A query's answer from the cache (storing the columns it reads first); null: ask the usual way. */
        public logsift.core.Sql.Result query(String sql, int maxRows, java.util.function.BooleanSupplier stop)
                throws java.sql.SQLException {
            return query.query(cache, sql, maxRows, logsift.core.CsvRows.reader(doc, format), doc.lineCount(), stop,
                    null, null);
        }

        /** How many columns the table has. */
        public int width() {
            return fields.titles().size();
        }

        /** Why the cache can't answer this query; null when it can. */
        public String whyNot(String sql) throws java.sql.SQLException {
            return query.whyNot(cache, sql);
        }

        /**
         * A pivot from the cache, storing the columns it needs first (one read of the file); null
         * when the cache can't answer it (it's added up from the file then).
         */
        public logsift.core.Pivot.Result pivot(logsift.core.Pivot.Spec spec, logsift.core.Pivot.Fields fields,
                                               java.util.function.BooleanSupplier stop) {
            if (!logsift.core.PivotCache.canCache(spec)) {
                return null;
            }
            if (!cache.has(spec) && !cache.build(spec, fields, logsift.core.CsvRows.reader(doc, format), doc.lineCount(),
                    stop, null)) {
                return null;
            }
            // log times are kept as written, as UTC (as the Pivot tab groups them)
            return cache.compute(spec, fields, java.time.ZoneOffset.UTC, doc.lineCount());
        }

        /** Throws DuckDB's refusal of a mistake in the query, reading nothing. */
        public void tryOnEmpty(String sql) throws java.sql.SQLException {
            query.tryOnEmpty(sql);
        }

        @Override
        public void close() {
            query.close();
            cache.close();
        }
    }

    private final Map<String, BigTable> bigTables = new LinkedHashMap<>();

    /**
     * The cache of an open table big enough for one (a CSV or TSV from 200 MB, as in the app),
     * opened the first time and again when the file changed; null when it's smaller, caches are
     * off (Settings → General → Pivot cache), or the cache can't be used.
     */
    public synchronized BigTable bigTable(String handle, LogAnalysis log) {
        var formats = log.formats();
        logsift.core.LogFormat f = formats == null ? null : formats.columns();
        logsift.core.LogDocument doc = log.document();
        if (f == null || !f.isCsv() || !logsift.core.CacheSettings.enabled()
                || doc.fileSize() < logsift.core.CacheSettings.minBytes() || doc.isIndexing()) {
            return null;
        }
        String read = doc.charset().name();
        BigTable t = bigTables.get(handle);
        if (t != null && (t.format != f || !t.cache.isFor(log.path(), f, read))) {
            t.close(); // the file changed
            bigTables.remove(handle);
            t = null;
        }
        if (t == null) {
            logsift.core.PivotCache c = null;
            try {
                c = logsift.core.PivotCache.open(log.path(), f, read, logsift.core.CacheSettings.dir(),
                        logsift.core.CacheSettings.limit());
                t = new BigTable(c, doc, f, logsift.core.Pivot.fields(f, doc.lineCount(),
                        logsift.core.CsvRows.reader(doc, f), java.time.Year.now().getValue()));
            } catch (IOException | java.sql.SQLException | RuntimeException e) {
                if (c != null) {
                    c.close();
                }
                return null; // read the usual way
            }
            bigTables.put(handle, t);
        }
        return t;
    }

    /** Makes a log's table. */
    @FunctionalInterface
    public interface BooleanSupplierEx {
        logsift.core.Sql.LogTable make(logsift.core.Sql sql) throws Exception;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (sql != null) {
                sql.close();
                sql = null;
            }
            bigTables.values().forEach(BigTable::close);
            bigTables.clear();
        }
        pool.shutdownNow();
        List<LogAnalysis> all;
        synchronized (this) {
            all = new ArrayList<>(logs.values());
            logs.clear();
            shown.clear();
        }
        all.forEach(LogAnalysis::close);
        List<Path> files;
        synchronized (this) {
            files = new ArrayList<>(generated);
            generated.clear();
        }
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
                Path dir = f.getParent();
                if (dir != null && dir.getFileName().toString().startsWith("logsift-agent-")) {
                    Files.deleteIfExists(dir); // only our own folder, and only if empty
                }
            } catch (IOException e) {
                // left for the system's temp cleanup
            }
        }
    }
}
