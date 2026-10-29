package logsift.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import logsift.core.CustomFormat;
import logsift.core.Encoding;
import logsift.core.Formats;
import logsift.core.Json;

/**
 * Where a person's logs are and what each file holds, so coding agents can find them by name
 * ("orders", "orders/app") instead of a path: services, each with its folders, what its files
 * hold, its time zone and notes. Kept in {@code logs.json} in Logsift's folder (your own list)
 * and in {@code .logsift/logs.json} in a project (shared with the team; its folders may be
 * relative to the project). A project's service wins over your own of the same name.
 */
public final class LogCatalog {

    /** What files matching a pattern hold: {@code app.log*} → "Application log: …". */
    public record FileKind(String pattern, String description) {}

    /**
     * A service (or app, or anything with logs).
     *
     * @param name        a short name, used by agents: "orders"
     * @param description what it is, one line
     * @param folders     where its logs are (absolute, or relative to the project in a project file)
     * @param files       what its files hold
     * @param timeZone    the zone of the times in its logs ("UTC", "Europe/Berlin"), or ""
     * @param notes       anything an agent should know, or ""
     */
    public record Service(String name, String description, List<String> folders, List<FileKind> files, String timeZone,
                          String notes) {
        public Service {
            name = name == null ? "" : name.strip();
            description = description == null ? "" : description.strip();
            folders = List.copyOf(folders);
            files = List.copyOf(files);
            timeZone = timeZone == null ? "" : timeZone.strip();
            notes = notes == null ? "" : notes.strip();
        }

        /** The description of a file, from the first pattern that matches its name; null if none. */
        public String describe(String fileName) {
            for (FileKind k : files) {
                if (matches(k.pattern(), fileName)) {
                    return k.description();
                }
            }
            return null;
        }
    }

    /** A service and where it was read from. */
    public record Entry(Service service, Path file, boolean project) {
        /** The service's folders as paths: relative ones against the project's folder. */
        public List<Path> folders() {
            List<Path> out = new ArrayList<>();
            Path base = project ? projectRoot(file) : null;
            for (String f : service.folders()) {
                try {
                    String x = expand(f, System.getenv());
                    if (x == null) {
                        continue; // a variable this computer doesn't set
                    }
                    Path p = Path.of(x);
                    out.add((p.isAbsolute() || base == null ? p : base.resolve(p)).normalize());
                } catch (RuntimeException e) {
                    // not a path on this system
                }
            }
            return out;
        }
    }

    private final List<Entry> entries;
    private final List<String> problems;

    private LogCatalog(List<Entry> entries, List<String> problems) {
        this.entries = List.copyOf(entries);
        this.problems = List.copyOf(problems);
    }

    public static final LogCatalog EMPTY = new LogCatalog(List.of(), List.of());

    /** A list made of these entries (the window's preview of what agents see). */
    public static LogCatalog of(List<Entry> entries) {
        return new LogCatalog(entries, List.of());
    }

    // ---------------------------------------------------------------- files

    /** Your own list: {@code logs.json} in Logsift's folder. */
    public static Path userFile(Path logsiftHome) {
        return logsiftHome.resolve("logs.json");
    }

    /** A project's list: {@code .logsift/logs.json} in its folder. */
    public static Path projectFile(Path projectDir) {
        return projectDir.resolve(".logsift").resolve("logs.json");
    }

    /** The project folder a project file belongs to (the one holding .logsift). */
    static Path projectRoot(Path projectFile) {
        Path dir = projectFile.toAbsolutePath().getParent();
        return dir == null || dir.getParent() == null ? dir : dir.getParent();
    }

    /** The project folder of a project's list file. */
    public static Path projectRootOf(Path projectFile) {
        return projectRoot(projectFile);
    }

    /** The nearest project file at or above a folder; null if none. */
    public static Path findProject(Path from) {
        Path d = from == null ? null : from.toAbsolutePath().normalize();
        for (int i = 0; d != null && i < 40; i++, d = d.getParent()) {
            Path f = projectFile(d);
            if (Files.isRegularFile(f)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Your own list and the project's at or above {@code cwd} (null: none), and any more files
     * given. A file that can't be read is skipped and said in {@link #problems()}.
     */
    public static LogCatalog load(Path logsiftHome, Path cwd, List<Path> more) {
        List<Entry> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<Path> projects = new ArrayList<>();
        Path p = cwd == null ? null : findProject(cwd);
        if (p != null) {
            projects.add(p);
        }
        if (more != null) {
            projects.addAll(more);
        }
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Path f : projects) {
            for (Service s : readOrNote(f, problems)) {
                byName.putIfAbsent(key(s.name()), new Entry(s, f.toAbsolutePath().normalize(), true));
            }
        }
        if (logsiftHome != null) {
            Path u = userFile(logsiftHome);
            if (Files.isRegularFile(u)) {
                for (Service s : readOrNote(u, problems)) {
                    byName.putIfAbsent(key(s.name()), new Entry(s, u, false));
                }
            }
        }
        out.addAll(byName.values());
        return new LogCatalog(out, problems);
    }

    private static List<Service> readOrNote(Path f, List<String> problems) {
        try {
            return read(f);
        } catch (IOException | RuntimeException e) {
            problems.add(f + ": " + e.getMessage());
            return List.of();
        }
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** The services in a file. */
    public static List<Service> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return parse(text);
    }

    /** The services in a file's text. */
    @SuppressWarnings("unchecked")
    public static List<Service> parse(String text) throws IOException {
        Map<String, Object> root = Json.parseObject(text.strip());
        if (root == null) {
            throw new IOException("not valid JSON (an object with \"services\" was expected)");
        }
        Object list = root.get("services");
        if (!(list instanceof List<?> items)) {
            throw new IOException("no \"services\" list in it");
        }
        List<Service> out = new ArrayList<>();
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> s = (Map<String, Object>) m;
            String name = str(s.get("name"));
            if (name.isBlank()) {
                continue;
            }
            List<String> folders = new ArrayList<>();
            if (s.get("folders") instanceof List<?> fs) {
                for (Object f : fs) {
                    if (!str(f).isBlank()) {
                        folders.add(str(f));
                    }
                }
            } else if (!str(s.get("folder")).isBlank()) {
                folders.add(str(s.get("folder")));
            }
            List<FileKind> files = new ArrayList<>();
            if (s.get("files") instanceof List<?> fl) {
                for (Object f : fl) {
                    if (f instanceof Map<?, ?> fm && !str(fm.get("pattern")).isBlank()) {
                        files.add(new FileKind(str(fm.get("pattern")), str(fm.get("description"))));
                    }
                }
            }
            out.add(new Service(name, str(s.get("description")), folders, files, str(s.get("timeZone")),
                    str(s.get("notes"))));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** The services as JSON, readable and stable (one key per line). */
    public static String format(List<Service> services) {
        StringBuilder b = new StringBuilder("{\n  \"services\": [");
        for (int i = 0; i < services.size(); i++) {
            Service s = services.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\n");
            b.append("      \"name\": ").append(q(s.name())).append(",\n");
            b.append("      \"description\": ").append(q(s.description())).append(",\n");
            b.append("      \"folders\": [");
            for (int j = 0; j < s.folders().size(); j++) {
                b.append(j == 0 ? "" : ", ").append(q(s.folders().get(j)));
            }
            b.append("],\n      \"files\": [");
            for (int j = 0; j < s.files().size(); j++) {
                FileKind k = s.files().get(j);
                b.append(j == 0 ? "\n" : ",\n").append("        { \"pattern\": ").append(q(k.pattern()))
                        .append(", \"description\": ").append(q(k.description())).append(" }");
            }
            b.append(s.files().isEmpty() ? "],\n" : "\n      ],\n");
            b.append("      \"timeZone\": ").append(q(s.timeZone())).append(",\n");
            b.append("      \"notes\": ").append(q(s.notes())).append("\n    }");
        }
        b.append(services.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        return b.toString();
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** Writes services to a file (its folder is made if needed), replacing what was there. */
    public static void save(Path file, List<Service> services) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, format(services), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------------------------------------------------------------- what was read

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Files that couldn't be read, and why. */
    public List<String> problems() {
        return problems;
    }

    /** A service by name (any case); null if none. */
    public Entry entry(String name) {
        String k = key(name);
        for (Entry e : entries) {
            if (key(e.service().name()).equals(k)) {
                return e;
            }
        }
        return null;
    }

    /** The service whose folders hold a file; null if none. */
    public Entry entryFor(Path file) {
        Path f = file.toAbsolutePath().normalize();
        for (Entry e : entries) {
            for (Path d : e.folders()) {
                if (f.startsWith(d)) {
                    return e;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the files of a service

    /**
     * Files that belong together: a log and its rotated copies ({@code app.log},
     * {@code app.log.1}, {@code app.2026-10-28.log.gz}).
     *
     * @param name        the current file's name ("app.log"): the newest uncompressed one
     * @param pattern     a glob for all of them ("app*.log*")
     * @param description what they hold, from the service; null if it doesn't say
     * @param files       the files, newest first
     */
    public record Group(String name, String pattern, String description, List<Path> files, long bytes, long newest,
                        long oldest) {
        public Path current() {
            for (Path f : files) {
                if (f.getFileName().toString().equals(name)) {
                    return f;
                }
            }
            return files.get(0);
        }
    }

    private static final Pattern COMPRESSED = Pattern.compile("(?i)\\.(gz|zip|bz2|xz|zst|z)$");
    private static final Pattern DATE = Pattern.compile(
            "[._-]?(19|20)\\d{2}-?[01]\\d-?[0-3]\\d(?:[T_.-]?[0-2]\\d(?:[-:.]?[0-5]\\d){0,2})?(?=\\.|$|[._-])");
    private static final Pattern INDEX = Pattern.compile("[._-]\\d{1,4}(?=$|\\.log$|\\.txt$|\\.out$)");

    /** The name that rotated copies share: "app.log.1.gz", "app.2026-10-28.log" → "app.log". */
    public static String rotationKey(String fileName) {
        String n = COMPRESSED.matcher(fileName).replaceAll("");
        n = DATE.matcher(n).replaceAll("");
        String before;
        do {
            before = n;
            n = INDEX.matcher(n).replaceAll("");
        } while (!n.equals(before));
        n = n.replaceAll("\\d{3,}", "#"); // pids and run numbers: hs_err_pid1234.log → hs_err_pid#.log
        return n.isEmpty() ? fileName : n;
    }

    /** How deep in a service's folders files are looked for. */
    static final int DEPTH = 3;
    static final int MAX_FILES = 5000;

    /** The files of a service, grouped, the most recently changed group first. */
    public static List<Group> scan(Entry e) {
        Map<String, List<Path>> byKey = new LinkedHashMap<>();
        int[] count = {0};
        for (Path d : e.folders()) {
            collect(d, DEPTH, byKey, count);
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Path>> g : byKey.entrySet()) {
            out.add(group(g.getKey(), g.getValue(), e.service()));
        }
        // the most recently changed first; the same minute: the biggest first
        out.sort((x, y) -> {
            int t = Long.compare(y.newest() / 60_000, x.newest() / 60_000);
            return t != 0 ? t : Long.compare(y.bytes(), x.bytes());
        });
        return out;
    }

    private static void collect(Path dir, int depth, Map<String, List<Path>> byKey, int[] count) {
        if (count[0] >= MAX_FILES || !Files.isDirectory(dir)) {
            if (Files.isRegularFile(dir)) {
                add(dir, byKey, count); // a folder entry that is a file
            }
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".") || count[0] >= MAX_FILES) {
                    continue;
                }
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (depth > 1) {
                        collect(p, depth - 1, byKey, count);
                    }
                } else if (Files.isRegularFile(p)) {
                    add(p, byKey, count);
                }
            }
        } catch (IOException | RuntimeException e) {
            // a folder that can't be read: nothing from it
        }
    }

    private static void add(Path p, Map<String, List<Path>> byKey, int[] count) {
        Path parent = p.getParent();
        String k = (parent == null ? "" : parent + "/") + rotationKey(p.getFileName().toString());
        byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
        count[0]++;
    }

    private static Group group(String key, List<Path> files, Service s) {
        long bytes = 0;
        long newest = Long.MIN_VALUE;
        long oldest = Long.MAX_VALUE;
        Map<Path, Long> times = new LinkedHashMap<>();
        for (Path f : files) {
            try {
                BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
                bytes += a.size();
                long t = a.lastModifiedTime().toMillis();
                times.put(f, t);
                newest = Math.max(newest, t);
                oldest = Math.min(oldest, t);
            } catch (IOException e) {
                times.put(f, 0L);
            }
        }
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort((x, y) -> Long.compare(times.get(y), times.get(x)));
        // the current file: the newest one that isn't compressed and has the shortest name
        Path current = sorted.get(0);
        for (Path f : sorted) {
            String n = f.getFileName().toString();
            if (!COMPRESSED.matcher(n).find()
                    && (COMPRESSED.matcher(current.getFileName().toString()).find()
                    || n.length() < current.getFileName().toString().length())) {
                current = f;
            }
        }
        String name = current.getFileName().toString();
        String pattern = pattern(key.substring(key.lastIndexOf('/') + 1), sorted);
        String description = null;
        for (Path f : sorted) {
            description = s.describe(f.getFileName().toString());
            if (description != null) {
                break;
            }
        }
        return new Group(name, pattern, description, sorted, bytes, newest == Long.MIN_VALUE ? 0 : newest,
                oldest == Long.MAX_VALUE ? 0 : oldest);
    }

    /** A glob that matches every file of a group: "app.log*", "app*.log*", "hs_err_pid*.log". */
    static String pattern(String key, List<Path> files) {
        String base = key.replace("#", "*");
        List<String> tries = new ArrayList<>();
        tries.add(base);
        tries.add(base + "*");
        int dot = base.indexOf('.');
        if (dot > 0) {
            tries.add(base.substring(0, dot) + "*" + base.substring(dot) + "*");
        }
        for (String t : tries) {
            boolean all = true;
            for (Path f : files) {
                if (!matches(t, f.getFileName().toString())) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return t;
            }
        }
        return dot > 0 ? base.substring(0, dot) + "*" : base + "*";
    }

    /** Whether a file name matches a glob (any case). */
    public static boolean matches(String glob, String fileName) {
        try {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob.toLowerCase(Locale.ROOT));
            return m.matches(Path.of(fileName.toLowerCase(Locale.ROOT)));
        } catch (RuntimeException e) {
            return glob.equalsIgnoreCase(fileName);
        }
    }

    /**
     * The file a name means: "orders" (a service with one kind of file), "orders/app" or
     * "orders/app.log" (the newest file of that group), or "orders/app.log.3.gz" (that file).
     * Null if the name isn't a service's.
     */
    public Path resolve(String name) {
        String n = name.strip();
        int slash = n.indexOf('/');
        Entry e = entry(slash < 0 ? n : n.substring(0, slash));
        if (e == null) {
            return null;
        }
        List<Group> groups = scan(e);
        if (slash < 0) {
            return groups.size() == 1 ? groups.get(0).current() : null;
        }
        String rest = n.substring(slash + 1).strip().toLowerCase(Locale.ROOT);
        for (Group g : groups) {
            for (Path f : g.files()) {
                if (f.getFileName().toString().equalsIgnoreCase(rest)) {
                    return f;
                }
            }
        }
        for (Group g : groups) {
            String gn = g.name().toLowerCase(Locale.ROOT);
            if (gn.equals(rest) || gn.startsWith(rest + ".") || stem(gn).equals(rest)) {
                return g.current();
            }
        }
        return null;
    }

    /** "app.log" → "app"; "orders-api.json.log" → "orders-api". */
    static String stem(String fileName) {
        int dot = fileName.indexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ---------------------------------------------------------------- guessing what a file is

    /** What a file seems to hold, from its first lines ("Java GC log", "CSV: id, time, amount"); null if not known. */
    public static String guess(Path file, List<CustomFormat> custom) {
        String n = file.getFileName().toString();
        List<String> sample = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = n.toLowerCase(Locale.ROOT).endsWith(".gz") ? new java.util.zip.GZIPInputStream(raw) : raw) {
            byte[] head = in.readNBytes(64 * 1024);
            String text = new String(head, Encoding.detect(head, head.length));
            for (String l : text.split("\r?\n", 400)) {
                sample.add(l);
                if (sample.size() >= 300) {
                    break;
                }
            }
            if (text.contains("A fatal error has been detected by the Java Runtime Environment")
                    || text.contains("There is insufficient memory for the Java Runtime Environment")) {
                return "Java crash report (hs_err)";
            }
            if (logsift.jvm.GcLog.looksLikeGcLog(text)) {
                return "Java GC log";
            }
            if (logsift.jvm.DumpParser.looksLikeDump(text)) {
                return "Java thread dumps";
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (sample.isEmpty() || sample.size() == 1 && sample.get(0).isBlank()) {
            return "Empty";
        }
        Formats.Detected d = Formats.detect(sample, custom == null ? List.of() : custom, n, null, Year.now().getValue());
        if (d.columns() != null) {
            String fmt = d.columns().name();
            if (fmt.toUpperCase(Locale.ROOT).contains("CSV") || fmt.toUpperCase(Locale.ROOT).contains("TSV")) {
                List<String> cols = d.columns().columns();
                String list = String.join(", ", cols.subList(0, Math.min(8, cols.size())));
                return fmt + ": " + list + (cols.size() > 8 ? ", …" : "");
            }
            int paren = fmt.indexOf(" (");
            return "Log (" + (paren > 0 ? fmt.substring(0, paren) : fmt) + " format)";
        }
        if (d.times() != null) {
            return "Log with times on each line";
        }
        return "Text";
    }

    // ---------------------------------------------------------------- for agents

    /**
     * A folder as written in a list, for this computer: {@code ~} is the home folder,
     * {@code ${NAME}} (or {@code $NAME}) an environment variable, {@code ${NAME:-default}} with a
     * default. Null if a variable isn't set and has no default.
     */
    public static String expand(String folder, Map<String, String> env) {
        StringBuilder b = new StringBuilder();
        java.util.regex.Matcher m = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}|\\$([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(folder);
        int at = 0;
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(3);
            String v = env.get(name);
            if (v == null || v.isEmpty()) {
                v = m.group(2);
            }
            if (v == null) {
                return null;
            }
            b.append(folder, at, m.start()).append(v);
            at = m.end();
        }
        b.append(folder.substring(at));
        return expandHome(b.toString());
    }

    /**
     * A folder written so it works on other computers: relative to the project when it is in
     * it (project lists), {@code ~/…} when it is in the home folder, else as it is. Folders
     * already written with ~ or variables stay as they are.
     */
    public static String portable(String folder, Path projectRoot) {
        if (folder.startsWith("~") || folder.contains("$")) {
            return folder;
        }
        try {
            Path p = Path.of(folder);
            if (!p.isAbsolute()) {
                return folder;
            }
            p = p.normalize();
            if (projectRoot != null && p.startsWith(projectRoot)) {
                String rel = projectRoot.relativize(p).toString().replace('\\', '/');
                return rel.isEmpty() ? "." : rel;
            }
            Path home = Path.of(System.getProperty("user.home"));
            if (p.startsWith(home)) {
                String rel = home.relativize(p).toString().replace('\\', '/');
                return rel.isEmpty() ? "~" : "~/" + rel;
            }
        } catch (RuntimeException e) {
            // not a path here: as it is
        }
        return folder;
    }

    /** "~/logs" → the home folder's logs. */
    static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
