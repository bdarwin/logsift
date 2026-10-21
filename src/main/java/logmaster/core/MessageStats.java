package logmaster.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/**
 * "Top messages": groups log lines that differ only in their variable parts and counts each
 * group. Every token that contains a digit (numbers, IDs, hosts, durations, timestamps) becomes
 * {@code <*>}, so
 *
 * <pre>
 *   2026-10-21 10:02:13.123 ERROR [worker-7] Database connection lost after 26579 ms
 *   2026-10-21 10:05:40.008 ERROR [worker-2] Database connection lost after 812 ms
 * </pre>
 *
 * are both counted as {@code ERROR [<*>] Database connection lost after <*> ms}. Templates that
 * then differ in just one word (for example a customer name) are merged too.
 *
 * <p>Only the first line of each entry is counted (stack traces belong to their entry). The file
 * is processed in parallel chunks, building templates directly from bytes.
 */
public final class MessageStats {
    private MessageStats() {}

    /** One kind of message. */
    public record Template(String text, String level, long count, long firstLine, long lastLine) {
        /** A regex matching lines of this message (for Find All and filters). */
        public String regex() {
            return toRegex(text);
        }
    }

    /** The result: templates by count (most first), and how many lines were counted. */
    public record Result(List<Template> templates, long lines, long millis, boolean truncated) {}

    static final String WILDCARD = "<*>";
    private static final int MAX_TEMPLATES_PER_RANGE = 50_000;
    private static final int MAX_TEMPLATES = 200_000;
    private static final int MAX_LINE_BYTES = 2000;
    private static final String[] LEVELS = {"FATAL", "CRITICAL", "SEVERE", "ERROR", "WARNING",
        "WARN", "NOTICE", "INFO", "DEBUG", "TRACE"};

    /**
     * Counts the messages of lines {@code [from, to)} that pass {@code include} (null = all).
     *
     * @param entries how entries start (only their first lines are counted); null = every line
     */
    public static Result analyze(LogDocument doc, EntryFormat entries, long from, long to,
                                 LongPredicate include, BooleanSupplier cancelled,
                                 java.util.function.DoubleConsumer progress) {
        long start = System.nanoTime();
        LineIndex.Snapshot s = doc.snapshot();
        to = Math.min(to, s.completeLineCount() > 0 ? s.lineCount : 0);
        from = Math.max(0, from);
        if (to <= from) {
            return new Result(List.of(), 0, 0, false);
        }
        int maxInFlight = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        Map<String, Stat> total = new HashMap<>();
        AtomicLong counted = new AtomicLong();
        boolean truncated = false;
        int chunk = s.chunkFor(from);
        long lo = from;
        long totalLines = to - from;
        try {
            java.util.ArrayDeque<Future<Local>> inFlight = new java.util.ArrayDeque<>();
            while (lo < to || !inFlight.isEmpty()) {
                while (lo < to && inFlight.size() < maxInFlight) {
                    LineIndex.Chunk c = s.chunk(chunk);
                    long hi = Math.min(to, c.endLine());
                    if (hi > lo) {
                        long a = lo;
                        long b = hi;
                        long sb = doc.lineStart(s, a);
                        long eb = doc.lineStart(s, b);
                        inFlight.add(LogDocument.WORKERS.submit(
                                () -> scan(doc, a, sb, eb, entries, include, cancelled)));
                        lo = hi;
                    }
                    chunk++;
                }
                if (cancelled.getAsBoolean()) {
                    inFlight.forEach(f -> f.cancel(false));
                    return new Result(List.of(), counted.get(), 0, true);
                }
                Local l = inFlight.removeFirst().get();
                counted.addAndGet(l.lines);
                truncated |= l.truncated;
                for (var e : l.stats.entrySet()) {
                    Stat t = total.get(e.getKey());
                    if (t == null) {
                        if (total.size() >= MAX_TEMPLATES) {
                            truncated = true;
                            continue;
                        }
                        total.put(e.getKey(), e.getValue());
                    } else {
                        t.merge(e.getValue());
                    }
                }
                if (progress != null) {
                    progress.accept((double) (l.endLine - from) / totalLines);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(List.of(), counted.get(), 0, true);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(c);
        }
        List<Template> templates = mergeSimilar(total);
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new Result(templates, counted.get(), ms, truncated);
    }

    /** Counts for one template. */
    static final class Stat {
        long count;
        long first;
        long last;

        Stat(long line) {
            count = 1;
            first = line;
            last = line;
        }

        void merge(Stat o) {
            count += o.count;
            first = Math.min(first, o.first);
            last = Math.max(last, o.last);
        }
    }

    private record Local(Map<String, Stat> stats, long lines, long endLine, boolean truncated) {}

    private static final ThreadLocal<byte[]> BUFFERS = ThreadLocal.withInitial(() -> new byte[0]);

    private static Local scan(LogDocument doc, long firstLine, long startByte, long endByte,
                              EntryFormat entries, LongPredicate include,
                              BooleanSupplier cancelled) {
        int len = (int) Math.min(endByte - startByte, 256L * 1024 * 1024);
        byte[] buf = BUFFERS.get();
        if (buf.length < len) {
            buf = new byte[Math.max(len, LineIndex.DEFAULT_CHUNK_SIZE + 64 * 1024)];
            BUFFERS.set(buf);
        }
        int n;
        try {
            n = doc.source().read(startByte, buf, 0, len);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Stat> stats = new HashMap<>();
        byte[] tmp = new byte[MAX_LINE_BYTES + 16];
        long line = firstLine;
        long lines = 0;
        boolean truncated = false;
        int pos = 0;
        while (pos < n) {
            int nl = ByteScan.indexOf(buf, pos, n, (byte) '\n');
            int end = nl < 0 ? n : nl;
            if (end > pos && buf[end - 1] == '\r') {
                end--;
            }
            boolean head = entries == null || entries.startsEntry(buf, pos, end);
            if (head && end > pos && (include == null || include.test(line))) {
                int tl = template(buf, pos, Math.min(end, pos + MAX_LINE_BYTES), tmp);
                // bytes kept as Latin-1 chars here (fast); decoded as UTF-8 at the end
                String key = new String(tmp, 0, tl, StandardCharsets.ISO_8859_1);
                Stat st = stats.get(key);
                if (st != null) {
                    st.count++;
                    st.last = line;
                } else if (stats.size() < MAX_TEMPLATES_PER_RANGE) {
                    stats.put(key, new Stat(line));
                } else {
                    truncated = true;
                }
                lines++;
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
            line++;
            if ((line & 8191) == 0 && cancelled.getAsBoolean()) {
                break;
            }
        }
        return new Local(stats, lines, line, truncated);
    }

    private static boolean isDelimiter(byte b) {
        return switch (b) {
            case ' ', '\t', ',', ';', '=', ':', '[', ']', '(', ')', '{', '}', '"', '\'', '/', '|',
                 '<', '>', '&', '?' -> true;
            default -> false;
        };
    }

    /**
     * Writes the template of {@code buf[a, b)} into {@code out}: tokens with a digit become
     * {@code <*>}, runs of them joined by punctuation (10:02:13.123) become one, and the leading
     * run (the timestamp) is dropped. Returns the length.
     */
    static int template(byte[] buf, int a, int b, byte[] out) {
        int o = 0;
        int i = a;
        boolean lastWild = false;     // the last thing written is <*>
        int afterLastWild = -1;       // out position right after the last <*>
        boolean leading = true;       // still in the leading run of variable tokens
        while (i < b) {
            byte c = buf[i];
            if (isDelimiter(c)) {
                if (leading && (c == ' ' || c == '\t' || c == '[' || c == ']' || c == ',' || c == ':')) {
                    i++;
                    continue;
                }
                if (o < out.length) {
                    out[o++] = c == '\t' ? (byte) ' ' : c;
                }
                // collapse runs of spaces
                if (c == ' ' || c == '\t') {
                    while (i + 1 < b && (buf[i + 1] == ' ' || buf[i + 1] == '\t')) {
                        i++;
                    }
                }
                i++;
                continue;
            }
            int j = i;
            boolean digit = false;
            while (j < b && !isDelimiter(buf[j])) {
                byte d = buf[j];
                if (d >= '0' && d <= '9') {
                    digit = true;
                }
                j++;
            }
            if (digit) {
                if (leading) {
                    i = j;
                    continue; // timestamp and similar prefix
                }
                // join "<*>:<*>" and "<*>.<*>" style runs into one <*>
                boolean joinable = lastWild && afterLastWild >= 0 && o - afterLastWild <= 1
                        && (o == afterLastWild || (out[o - 1] != ' '));
                if (joinable) {
                    o = afterLastWild;
                } else if (o + 3 <= out.length) {
                    out[o++] = '<';
                    out[o++] = '*';
                    out[o++] = '>';
                }
                afterLastWild = o;
                lastWild = true;
            } else {
                leading = false;
                int k = Math.min(j - i, out.length - o);
                System.arraycopy(buf, i, out, o, k);
                o += k;
                lastWild = false;
            }
            i = j;
        }
        while (o > 0 && out[o - 1] == ' ') {
            o--;
        }
        return o;
    }

    /** Merges templates that differ in at most one word, and sorts by count. */
    static List<Template> mergeSimilar(Map<String, Stat> raw) {
        // decode once and group by (level, number of tokens)
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            String text = new String(e.getKey().getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8);
            String[] tokens = text.split(" ", -1);
            String key = levelOf(text) + "/" + tokens.length;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[] {tokens, e.getValue()});
        }
        List<Template> out = new ArrayList<>();
        for (List<Object[]> group : groups.values()) {
            group.sort(Comparator.comparingLong((Object[] x) -> -((Stat) x[1]).count));
            List<String[]> merged = new ArrayList<>();
            List<Stat> mergedStats = new ArrayList<>();
            boolean tooBig = group.size() > 3000;
            for (Object[] item : group) {
                String[] t = (String[]) item[0];
                Stat st = (Stat) item[1];
                int into = -1;
                if (!tooBig) {
                    for (int m = 0; m < merged.size() && into < 0; m++) {
                        if (differsInOneWord(merged.get(m), t)) {
                            into = m;
                        }
                    }
                }
                if (into < 0) {
                    merged.add(t.clone());
                    Stat copy = new Stat(st.first);
                    copy.count = st.count;
                    copy.last = st.last;
                    mergedStats.add(copy);
                } else {
                    String[] m = merged.get(into);
                    for (int k = 0; k < m.length; k++) {
                        if (!m[k].equals(t[k])) {
                            m[k] = WILDCARD;
                        }
                    }
                    mergedStats.get(into).merge(st);
                }
            }
            for (int m = 0; m < merged.size(); m++) {
                String text = String.join(" ", merged.get(m));
                Stat st = mergedStats.get(m);
                out.add(new Template(text, levelOf(text), st.count, st.first, st.last));
            }
        }
        out.sort(Comparator.comparingLong((Template t) -> -t.count()).thenComparing(Template::text));
        return out;
    }

    /** Same length, one differing token, which is a plain word (and the templates are long enough). */
    private static boolean differsInOneWord(String[] a, String[] b) {
        if (a.length != b.length || a.length < 4) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i])) {
                if (++diff > 1 || i == 0) {
                    return false; // the first word (often the level or logger) must agree
                }
            }
        }
        return diff == 1;
    }

    /** The log level named in the text (ERROR, WARN, …), or "". */
    static String levelOf(String text) {
        int limit = Math.min(text.length(), 60);
        String head = text.substring(0, limit);
        for (String l : LEVELS) {
            int i = head.indexOf(l);
            if (i >= 0 && (i == 0 || !Character.isLetter(head.charAt(i - 1)))
                    && (i + l.length() >= head.length() || !Character.isLetter(head.charAt(i + l.length())))) {
                return l.equals("WARNING") ? "WARN" : l.equals("SEVERE") || l.equals("CRITICAL") ? "ERROR" : l;
            }
        }
        return "";
    }

    /** A regex for lines of a template: literal text, with <*> matching any variable part. */
    static String toRegex(String template) {
        StringBuilder sb = new StringBuilder();
        String[] parts = template.split(Pattern.quote(WILDCARD), -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append("\\S+?");
            }
            if (!parts[i].isEmpty()) {
                // runs of spaces in the template stand for any whitespace in the line
                String[] words = parts[i].split(" ", -1);
                for (int w = 0; w < words.length; w++) {
                    if (w > 0) {
                        sb.append("\\s+");
                    }
                    escape(words[w], sb);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Escapes regex metacharacters one by one (not with \\Q...\\E), so the search can still
     * find the literal text in the regex and use its fast pre-filter.
     */
    private static void escape(String s, StringBuilder sb) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (".[]{}()*+?^$|\\".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
    }
}
