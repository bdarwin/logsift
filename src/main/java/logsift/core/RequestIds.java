package logsift.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The IDs in a log line that tie it to one request, trace, job or user: {@code traceId=…},
 * {@code request_id: …}, {@code "correlationId":"…"}, the {@code [app,trace,span]} that tracing
 * libraries add, a W3C {@code traceparent}, or a bare UUID. Following one of them through the
 * log shows what happened to that request, across threads and files.
 */
public final class RequestIds {
    private RequestIds() {}

    /** An ID found in a line: what it is called ("traceId", or "id" for a bare UUID) and its value. */
    public record Id(String name, String value) {
        /** "traceId 4bf92f35…" for menus. */
        public String label() {
            String v = value.length() > 24 ? value.substring(0, 12) + "…" + value.substring(value.length() - 6) : value;
            return name + " " + v;
        }
    }

    /** key=value, key: value and "key":"value" where the key names an ID. */
    private static final Pattern KEYED = Pattern.compile(
            "(?<![\\w.-])\"?([A-Za-z][\\w.-]{0,40}?)\"?"
                    + "\\s*[=:]\\s*\"?([A-Za-z0-9][\\w.:/@+-]{2,99}?)(?=\"|[\\s,;)\\]}|&]|$)");

    /** Tracing libraries: [app,traceId,spanId] or [app,traceId,spanId,true]. */
    private static final Pattern SLEUTH = Pattern.compile(
            "\\[[\\w.-]*,([0-9a-f]{16}|[0-9a-f]{32}),([0-9a-f]{16})(?:,(?:true|false))?\\]");

    /** Spring Boot 3 log correlation: [traceId-spanId]. */
    private static final Pattern BOOT3 = Pattern.compile("\\[([0-9a-f]{32})-([0-9a-f]{16})\\]");

    /** W3C trace context: traceparent: 00-traceId-spanId-01. */
    private static final Pattern TRACEPARENT = Pattern.compile(
            "(?i)traceparent\"?\\s*[=:]\\s*\"?[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    private static final Pattern UUID = Pattern.compile(
            "(?<![\\w-])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![\\w-])");

    /** Keys that name a process, thread or similar, not a request. */
    private static final Set<String> NOT_REQUESTS = Set.of("pid", "ppid", "tid", "uid", "gid", "id", "thread_id",
            "threadid", "thread-id", "process_id", "processid", "lwp_id", "nid", "cpuid", "guid", "spanid",
            "span_id", "span-id", "parentid", "parent_id", "parentspanid", "parent_span_id");

    /** Values that are not IDs. */
    private static final Set<String> NOT_VALUES = Set.of("null", "none", "nil", "undefined", "true", "false",
            "empty", "unknown", "n/a", "na", "missing");

    /** Most IDs offered for one line. */
    static final int MAX = 4;

    /** The IDs in a line, most telling first: traces, then requests and correlations, then others. */
    public static List<Id> find(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        String s = line.length() > 4000 ? line.substring(0, 4000) : line;
        Map<String, Id> found = new LinkedHashMap<>(); // by value
        Matcher m = SLEUTH.matcher(s);
        if (m.find()) {
            found.putIfAbsent(m.group(1), new Id("traceId", m.group(1)));
        }
        m = BOOT3.matcher(s);
        if (m.find()) {
            found.putIfAbsent(m.group(1), new Id("traceId", m.group(1)));
        }
        m = TRACEPARENT.matcher(s);
        if (m.find()) {
            found.putIfAbsent(m.group(1), new Id("traceId", m.group(1)));
        }
        m = KEYED.matcher(s);
        while (m.find()) {
            String key = m.group(1);
            String value = trimValue(m.group(2));
            String k = key.toLowerCase(Locale.ROOT);
            if (!namesAnId(key) || NOT_REQUESTS.contains(k) || k.endsWith("threadid") || k.endsWith("thread_id")
                    || NOT_VALUES.contains(value.toLowerCase(Locale.ROOT)) || value.length() < 3) {
                continue;
            }
            found.putIfAbsent(value, new Id(key, value));
        }
        m = UUID.matcher(s);
        while (m.find()) {
            found.putIfAbsent(m.group(), new Id("id", m.group()));
        }
        List<Id> out = new ArrayList<>(found.values());
        out.sort((a, b) -> Integer.compare(rank(a.name), rank(b.name)));
        return out.size() > MAX ? List.copyOf(out.subList(0, MAX)) : out;
    }

    /** requestId, request_id, x-request-id, traceID, rid; not "paid" or "valid". */
    static boolean namesAnId(String key) {
        int n = key.length();
        if (n < 2 || !key.regionMatches(true, n - 2, "id", 0, 2)) {
            return false;
        }
        if (n == 2) {
            return true;
        }
        char before = key.charAt(n - 3);
        if (before == '_' || before == '-' || before == '.') {
            return true;
        }
        if (key.charAt(n - 2) == 'I' && (Character.isLowerCase(before) || Character.isDigit(before))) {
            return true; // camelCase: requestId, traceID
        }
        return n == 3 && "rcxt".indexOf(Character.toLowerCase(key.charAt(0))) >= 0; // rid, cid, xid, tid
    }

    /** A trailing dot or colon belongs to the sentence, not the ID. */
    private static String trimValue(String v) {
        int end = v.length();
        while (end > 3 && ".:/-".indexOf(v.charAt(end - 1)) >= 0) {
            end--;
        }
        return v.substring(0, end);
    }

    private static int rank(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        if (k.contains("trace")) {
            return 0;
        }
        if (k.contains("request") || k.contains("req") || k.contains("correlation") || k.contains("corr")
                || k.contains("transaction") || k.contains("txn") || k.equals("rid") || k.equals("cid")) {
            return 1;
        }
        if (k.equals("id")) {
            return 3; // a bare UUID: could be anything
        }
        return 2;
    }

    /**
     * A regex for lines with this value as a whole token (so {@code abc-1} doesn't find
     * {@code abc-12}). Characters are escaped one by one so the search keeps its fast pre-filter.
     */
    public static String regex(String value) {
        StringBuilder sb = new StringBuilder("(?<![\\w-])");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (".[]{}()*+?^$|\\".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append("(?![\\w-])").toString();
    }
}
