package logsift.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exceptions in a log, grouped by what went wrong: the root cause (the innermost
 * {@code Caused by:} in Java, the first exception of a chained Python traceback) and where it
 * was thrown in the program's own code (framework and library frames are skipped). Five hundred
 * {@code NullPointerException}s from one place are one group; the same exception from two
 * places are two.
 */
public final class Exceptions {
    private Exceptions() {}

    /**
     * A line of a stack. Java: {@code cls}, {@code method}, {@code file} and {@code line} from
     * {@code at com.acme.Order.total(Order.java:42)}; Python: {@code file}, {@code line} and
     * {@code method} from {@code File "/app/orders.py", line 42, in total} ({@code cls} null).
     * {@code own}: the program's code, not a framework's or the language's.
     */
    public record Frame(String text, String cls, String method, String file, int line, boolean own) {
        /** "Order.total(Order.java:42)" or "orders.py:42 in total". */
        public String shortText() {
            if (cls != null) {
                String simple = cls.substring(cls.lastIndexOf('.') + 1);
                return simple + "." + method + "(" + (file == null ? "?" : file + (line > 0 ? ":" + line : "")) + ")";
            }
            String name = file == null ? "?" : file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
            return name + (line > 0 ? ":" + line : "") + (method == null ? "" : " in " + method);
        }

        /** What identifies the place regardless of line numbers: "com.acme.Order.total". */
        String place() {
            return cls != null ? cls + "." + method : file + ":" + method;
        }
    }

    /** One exception: its type, message and stack, the frame that threw it first. */
    public record Thrown(String type, String message, List<Frame> frames) {
        public String simpleType() {
            return type.substring(type.lastIndexOf('.') + 1);
        }

        /** The first frame of the program's own code (or the first frame; null if none). */
        public Frame where() {
            for (Frame f : frames) {
                if (f.own) {
                    return f;
                }
            }
            return frames.isEmpty() ? null : frames.get(0);
        }
    }

    /**
     * An exception as logged: {@code chain} from the outermost (what was caught and logged) to
     * the root cause.
     */
    public record Occurrence(long line, List<Thrown> chain) {
        public Thrown root() {
            return chain.get(chain.size() - 1);
        }

        public Thrown outer() {
            return chain.get(0);
        }
    }

    /**
     * Exceptions with the same root cause thrown from the same place. {@code lines}: where each
     * starts (the log entry), at most {@link #MAX_LINES}; {@code messages}: how many different
     * root-cause messages.
     */
    public record Group(String key, Occurrence sample, long count, long firstLine, long lastLine, long[] lines,
                        int linesKept, List<String> wrappers, int messages) {
        public Thrown root() {
            return sample.root();
        }

        /** Line numbers of the occurrences that were kept. */
        public long[] keptLines() {
            return linesKept == lines.length ? lines : java.util.Arrays.copyOf(lines, linesKept);
        }
    }

    /** All groups, most frequent first, and how many exceptions there were. */
    public record Result(List<Group> groups, long exceptions, boolean truncated) {
        public static final Result EMPTY = new Result(List.of(), 0, false);
    }

    static final int MAX_LINES = 10_000;
    static final int MAX_OCCURRENCES = 500_000;
    /** Most lines read for one logged exception. */
    static final int MAX_BLOCK = 600;

    // ---------------------------------------------------------------- parsing

    private static final Pattern JAVA_FRAME = Pattern.compile(
            "^\\s*at\\s+(?:[\\w.$-]+/)*(?:[\\w.$-]+//)?([\\w$.<>]+)\\.([\\w$<>\\-]+)\\(([^)]*)\\)");
    /** A thrown type: dotted, ending like an exception, or a well-known plain one. */
    private static final Pattern JAVA_HEADER = Pattern.compile(
            "^\\s*(?:Caused by:\\s*|Exception in thread \"[^\"]*\"\\s+)?"
                    + "((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*)(?::\\s?(.*))?$");
    private static final Pattern JAVA_INLINE = Pattern.compile(
            "((?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable|Fault))(?::\\s?(.*))?");
    private static final Pattern PY_FRAME = Pattern.compile("^\\s*File \"([^\"]+)\", line (\\d+)(?:, in (.+))?");
    private static final Pattern PY_EXCEPTION = Pattern.compile("^([A-Za-z_][\\w.]*)(?::\\s?(.*))?$");
    private static final String PY_START = "Traceback (most recent call last):";

    /** Frameworks, libraries and the language itself (not where the program went wrong). */
    private static final String[] JAVA_FRAMEWORKS = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "jakarta.",
        "kotlin.", "kotlinx.", "scala.", "groovy.", "org.codehaus.groovy.", "org.springframework.", "org.apache.",
        "org.hibernate.", "org.eclipse.", "io.netty.", "reactor.", "io.reactivex.", "rx.", "org.jboss.",
        "io.undertow.", "com.zaxxer.", "org.postgresql.", "com.mysql.", "oracle.", "com.microsoft.sqlserver.",
        "org.h2.", "okhttp3.", "okio.", "retrofit2.", "feign.", "io.grpc.", "com.google.", "com.fasterxml.",
        "org.slf4j.", "ch.qos.", "org.junit.", "junit.", "org.mockito.", "net.bytebuddy.", "org.aspectj.",
        "io.micrometer.", "io.opentelemetry.", "com.netflix.", "io.github.resilience4j.", "org.glassfish.",
        "io.vertx.", "io.quarkus.", "io.micronaut.", "akka.", "org.mybatis.", "org.jooq.", "com.amazonaws.",
        "software.amazon.", "org.elasticsearch.", "co.elastic.", "org.mongodb.", "com.mongodb.", "redis.",
        "io.lettuce.", "org.redisson.", "org.quartz.", "com.fasterxml.", "org.thymeleaf.", "freemarker.",
        "org.yaml.", "com.esotericsoftware.", "org.objectweb.", "$Proxy", "jdk.proxy", "org.gradle.",
        "worker.org.gradle.", "org.testng.", "org.openqa.", "io.restassured.", "graphql.", "org.dataloader.",
        "io.smallrye.", "org.wildfly.", "com.ibm.", "weblogic.", "org.camunda.", "org.flywaydb.", "liquibase."};

    /** Groups exceptions in a log entry's lines (its head line first). Empty if it has none. */
    public static List<Thrown> parse(List<String> lines) {
        List<Thrown> py = parsePython(lines);
        return py.isEmpty() ? parseJava(lines) : py;
    }

    static List<Thrown> parseJava(List<String> lines) {
        List<Thrown> chain = new ArrayList<>();
        String type = null;
        String message = null;
        List<Frame> frames = null;
        int suppressedIndent = -1;
        int prevHeader = -1; // index of a line that could be the header
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int indent = indent(l);
            if (suppressedIndent >= 0) {
                if (indent > suppressedIndent || l.isBlank()) {
                    continue; // inside a suppressed exception
                }
                suppressedIndent = -1;
            }
            String t = l.strip();
            if (t.startsWith("Suppressed:")) {
                suppressedIndent = indent;
                continue;
            }
            Matcher f = JAVA_FRAME.matcher(l);
            if (f.find()) {
                if (frames == null) { // frames without a header line: look back for one
                    String[] h = header(lines, prevHeader >= 0 ? prevHeader : i - 1);
                    type = h[0];
                    message = h[1];
                    frames = new ArrayList<>();
                }
                frames.add(javaFrame(t, f));
                continue;
            }
            if (t.startsWith("... ") && t.endsWith(" more") || t.startsWith("... ") && t.contains("common frames")) {
                continue;
            }
            Matcher h = JAVA_HEADER.matcher(l);
            boolean causedBy = t.startsWith("Caused by:");
            if (causedBy || h.matches() && (frames == null || indent == 0)) {
                if (frames != null) {
                    chain.add(new Thrown(type, message, frames));
                    frames = null;
                }
                if (h.matches()) {
                    type = h.group(1);
                    message = h.group(2) == null ? "" : h.group(2).strip();
                    frames = new ArrayList<>();
                    prevHeader = -1;
                } else {
                    prevHeader = i;
                }
                continue;
            }
            if (frames != null && frames.isEmpty()) {
                message = message.isEmpty() ? t : message + "\n" + t; // a message over several lines
                continue;
            }
            if (frames != null) {
                chain.add(new Thrown(type, message, frames));
                frames = null;
            }
            prevHeader = i;
        }
        if (frames != null && !frames.isEmpty()) {
            chain.add(new Thrown(type, message, frames));
        }
        chain.removeIf(th -> th.frames.isEmpty() || th.type == null);
        return chain;
    }

    /** The exception type and message on a line (or inside a log line), else "Exception" and the line. */
    private static String[] header(List<String> lines, int i) {
        if (i < 0 || i >= lines.size()) {
            return new String[] {"Exception", ""};
        }
        String l = lines.get(i);
        Matcher h = JAVA_HEADER.matcher(l);
        if (h.matches()) {
            return new String[] {h.group(1), h.group(2) == null ? "" : h.group(2).strip()};
        }
        Matcher in = JAVA_INLINE.matcher(l);
        String[] best = null;
        while (in.find()) {
            best = new String[] {in.group(1), in.group(2) == null ? "" : in.group(2).strip()};
        }
        return best != null ? best : new String[] {"Exception", l.strip()};
    }

    private static Frame javaFrame(String text, Matcher f) {
        String cls = f.group(1);
        String method = f.group(2);
        String loc = f.group(3);
        String file = null;
        int line = -1;
        int colon = loc.lastIndexOf(':');
        if (colon > 0) {
            file = loc.substring(0, colon);
            try {
                line = Integer.parseInt(loc.substring(colon + 1).strip());
            } catch (NumberFormatException e) {
                line = -1;
            }
        } else if (!loc.isEmpty() && !loc.contains(" ")) {
            file = loc;
        }
        return new Frame(text, cls, method, file, line, isOwnJava(cls));
    }

    static boolean isOwnJava(String cls) {
        for (String p : JAVA_FRAMEWORKS) {
            if (cls.startsWith(p)) {
                return false;
            }
        }
        return !cls.contains("$$") && !cls.startsWith("$") && !cls.contains("CGLIB") && !cls.contains("$Proxy");
    }

    static List<Thrown> parsePython(List<String> lines) {
        List<Thrown> found = new ArrayList<>(); // as written: causes first
        int i = 0;
        while (i < lines.size()) {
            String t = lines.get(i).strip();
            if (!t.equals(PY_START) && !t.endsWith(PY_START)) {
                i++;
                continue;
            }
            int base = indent(lines.get(i));
            List<Frame> frames = new ArrayList<>();
            i++;
            String type = null;
            String message = "";
            while (i < lines.size()) {
                String l = lines.get(i);
                Matcher f = PY_FRAME.matcher(l);
                if (f.find()) {
                    String file = f.group(1);
                    String method = f.group(3) == null ? null : f.group(3).strip();
                    frames.add(new Frame(l.strip(), null, method, file, Integer.parseInt(f.group(2)), isOwnPython(file)));
                    i++;
                    continue;
                }
                if (l.isBlank() || indent(l) > base || l.strip().startsWith("^") || l.strip().startsWith("~")) {
                    i++; // source line under a frame, or markers under it
                    continue;
                }
                Matcher e = PY_EXCEPTION.matcher(l.strip());
                if (e.matches() || l.strip().contains(":")) {
                    if (e.matches()) {
                        type = e.group(1);
                        message = e.group(2) == null ? "" : e.group(2).strip();
                    } else {
                        int c = l.strip().indexOf(':');
                        type = l.strip().substring(0, c);
                        message = l.strip().substring(c + 1).strip();
                    }
                    i++;
                }
                break;
            }
            if (type != null && !type.contains(" ")) {
                Collections.reverse(frames); // where it was raised first, like Java
                found.add(new Thrown(type, message, frames));
            }
        }
        Collections.reverse(found); // the outermost first, the root cause last
        return found;
    }

    static boolean isOwnPython(String file) {
        String f = file.replace('\\', '/');
        String lower = f.toLowerCase(Locale.ROOT);
        return !(lower.contains("/site-packages/") || lower.contains("/dist-packages/") || lower.startsWith("<")
                || lower.matches(".*/lib/python\\d[\\d.]*/.*") || lower.matches(".*/python\\d+/lib/.*")
                || lower.contains("/.venv/") || lower.contains("/venv/") || lower.contains("/__pypackages__/"));
    }

    private static int indent(String l) {
        int i = 0;
        while (i < l.length() && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    // ---------------------------------------------------------------- grouping

    /** What makes two exceptions the same problem: the root cause's type and its own top frames. */
    static String key(Occurrence o) {
        Thrown root = o.root();
        StringBuilder sb = new StringBuilder(root.type);
        int n = 0;
        for (Frame f : root.frames) {
            if (f.own) {
                sb.append('|').append(f.place());
                if (++n == 2) {
                    break;
                }
            }
        }
        if (n == 0) {
            for (int i = 0; i < Math.min(2, root.frames.size()); i++) {
                sb.append('|').append(root.frames.get(i).place());
            }
        }
        return sb.toString();
    }

    /** Collects occurrences into groups. */
    public static final class Grouper {
        private final Map<String, Builder> groups = new LinkedHashMap<>();
        private long total;
        private boolean truncated;

        private static final class Builder {
            final Occurrence sample;
            long count;
            long first;
            long last;
            long[] lines = new long[16];
            int kept;
            final Set<String> wrappers = new LinkedHashSet<>();
            final Set<String> messages = new LinkedHashSet<>();

            Builder(Occurrence o) {
                sample = o;
                first = o.line;
            }
        }

        public void add(Occurrence o) {
            if (o.chain.isEmpty()) {
                return;
            }
            total++;
            if (total > MAX_OCCURRENCES) {
                truncated = true;
                return;
            }
            Builder b = groups.computeIfAbsent(key(o), k -> new Builder(o));
            b.count++;
            b.first = Math.min(b.first, o.line);
            b.last = Math.max(b.last, o.line);
            if (b.kept < MAX_LINES) {
                if (b.kept == b.lines.length) {
                    b.lines = java.util.Arrays.copyOf(b.lines, Math.min(MAX_LINES, b.lines.length * 2));
                }
                b.lines[b.kept++] = o.line;
            }
            for (int i = 0; i < o.chain.size() - 1 && b.wrappers.size() < 5; i++) {
                b.wrappers.add(o.chain.get(i).simpleType());
            }
            if (b.messages.size() < 50) {
                b.messages.add(o.root().message);
            }
        }

        public Result result() {
            List<Group> out = new ArrayList<>();
            for (var e : groups.entrySet()) {
                Builder b = e.getValue();
                long[] lines = java.util.Arrays.copyOf(b.lines, b.kept);
                java.util.Arrays.sort(lines);
                List<String> wrappers = new ArrayList<>(b.wrappers);
                wrappers.remove(b.sample.root().simpleType());
                out.add(new Group(e.getKey(), b.sample, b.count, b.first, b.last, lines, b.kept,
                        List.copyOf(wrappers), b.messages.size()));
            }
            out.sort(Comparator.comparingLong(Group::count).reversed().thenComparingLong(Group::firstLine));
            return new Result(out, total, truncated);
        }
    }

    // ---------------------------------------------------------------- in a document

    /**
     * The searches that find {@link #STACK_LINES}, each with a plain-text part so it runs at full
     * speed: Java frames, Python's traceback line, Python frames.
     */
    public static final List<String> STACK_SEARCHES = List.of("^\\s+at [\\w$./<>-]+\\(",
            "Traceback \\(most recent call last\\)", "^\\s+File \"[^\"]+\", line \\d+");

    /** Lines where a stack starts or goes on: Java frames and Python tracebacks and frames. */
    public static final String STACK_LINES =
            "^\\s+at [\\w$./<>-]+\\(|Traceback \\(most recent call last\\)|^\\s+File \"[^\"]+\", line \\d+";

    /**
     * Finds and groups the exceptions of a document.
     *
     * @param stackLines the lines matching {@link #STACK_LINES}, sorted
     * @param startsEntry whether a line starts a log entry (null: unknown)
     */
    public static Result analyze(long[] stackLines, int count, java.util.function.LongFunction<String> text,
                                 java.util.function.Predicate<String> startsEntry, BooleanSupplier cancelled,
                                 DoubleConsumer progress) {
        Grouper g = new Grouper();
        int i = 0;
        long lastEnd = -1;
        while (i < count) {
            if ((i & 255) == 0) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                if (progress != null) {
                    progress.accept((double) i / count);
                }
            }
            long a = stackLines[i];
            int j = i;
            while (j + 1 < count && stackLines[j + 1] - stackLines[j] <= 6 && stackLines[j + 1] - a < MAX_BLOCK) {
                j++;
            }
            long b = stackLines[j];
            i = j + 1;
            if (b <= lastEnd) {
                continue; // read with the block before
            }
            // back to the start of the entry (the log line with the time)
            List<String> block = new ArrayList<>();
            long start = Math.max(a, lastEnd + 1);
            for (long l = start - 1; l >= Math.max(0, a - 40) && l > lastEnd; l--) {
                String t = text.apply(l);
                if (t == null) {
                    break;
                }
                block.add(0, t);
                start = l;
                if (startsEntry != null ? startsEntry.test(t) : !t.isEmpty() && !Character.isWhitespace(t.charAt(0))
                        && !JAVA_HEADER.matcher(t).matches() && !t.startsWith("Caused by:")) {
                    break;
                }
            }
            long end = start + block.size() - 1;
            for (long l = start + block.size(); l <= b + 4; l++) {
                String t = text.apply(l);
                if (t == null) {
                    break;
                }
                if (l > b && startsEntry != null && startsEntry.test(t)) {
                    break; // the next entry
                }
                block.add(t);
                end = l;
            }
            lastEnd = end;
            // one block can hold several entries close together: each is parsed on its own
            int from = 0;
            for (int k = 1; k <= block.size(); k++) {
                if (k == block.size() || startsEntry != null && startsEntry.test(block.get(k))) {
                    List<Thrown> chain = parse(block.subList(from, k));
                    if (!chain.isEmpty()) {
                        g.add(new Occurrence(start + from, chain));
                    }
                    from = k;
                }
            }
        }
        return g.result();
    }
}
