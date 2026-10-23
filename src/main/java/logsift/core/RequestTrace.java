package logsift.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

/**
 * One request through the log: its lines in order, with the time between them, the threads it
 * passed through, its errors, and the longest wait (where the time went).
 */
public final class RequestTrace {
    private RequestTrace() {}

    /**
     * One line of the request. {@code time}: written on the line ({@link TimeFormat#NONE} if
     * none); {@code gap}: milliseconds since the previous line with a time (-1: none).
     * {@code withId}: the line names the ID (false for lines of its threads in between).
     */
    public record Step(long line, long time, long gap, String thread, String level, String text, boolean withId) {}

    /**
     * The whole request. {@code longest}: the index of the step after the longest wait (-1:
     * no times). {@code cut}: there were more lines than shown.
     */
    public record Trace(List<Step> steps, long first, long last, List<String> threads, int errors,
                        int warnings, int longest, boolean cut) {
        public long duration() {
            return first == TimeFormat.NONE || last == TimeFormat.NONE ? -1 : last - first;
        }
    }

    /** What a line says, read from the log. */
    public interface Reader {
        String text(long line);

        /** The time written on the line, or {@link TimeFormat#NONE}. */
        long time(String text);

        /** The thread (or null), the level (or null). */
        String thread(String text);

        String level(String text);
    }

    /** Most steps shown. */
    public static final int MAX_STEPS = 5000;
    /** Most lines looked through for lines of the request's threads in between. */
    static final long MAX_SCAN = 2_000_000;

    /**
     * Builds the request from the lines that name its ID (sorted).
     *
     * @param withThreads also the lines its threads wrote between their first and last line of
     *                    the request (logs often name the ID on only some lines)
     */
    public static Trace build(long[] idLines, int count, Reader r, boolean withThreads, BooleanSupplier cancelled) {
        boolean cut = count > MAX_STEPS;
        int n = Math.min(count, MAX_STEPS);
        List<Step> steps = new ArrayList<>(n);
        for (int i = 0; i < n && !cancelled.getAsBoolean(); i++) {
            steps.add(step(idLines[i], r, true));
        }
        if (withThreads && !steps.isEmpty()) {
            List<Step> more = threadLines(steps, r, cancelled);
            if (!more.isEmpty()) {
                steps.addAll(more);
                steps.sort((a, b) -> Long.compare(a.line, b.line));
                if (steps.size() > MAX_STEPS) {
                    steps = new ArrayList<>(steps.subList(0, MAX_STEPS));
                    cut = true;
                }
            }
        }
        return finish(steps, cut);
    }

    private static Step step(long line, Reader r, boolean withId) {
        String text = r.text(line);
        if (text == null) {
            text = "";
        }
        return new Step(line, r.time(text), -1, blankToNull(r.thread(text)), blankToNull(r.level(text)), text, withId);
    }

    /**
     * Lines of the request's threads between their first and last line of the request. A thread
     * that logs another request's ID has moved on: its lines don't count until it names this
     * request again.
     */
    private static List<Step> threadLines(List<Step> steps, Reader r, BooleanSupplier cancelled) {
        Map<String, long[]> spans = new HashMap<>(); // thread -> {first line, last line}
        for (Step s : steps) {
            if (s.thread != null) {
                long[] span = spans.computeIfAbsent(s.thread, t -> new long[] {s.line, s.line});
                span[1] = s.line;
            }
        }
        if (spans.isEmpty()) {
            return List.of();
        }
        long from = Long.MAX_VALUE;
        long to = -1;
        for (long[] span : spans.values()) {
            from = Math.min(from, span[0]);
            to = Math.max(to, span[1]);
        }
        to = Math.min(to, from + MAX_SCAN);
        long[] have = steps.stream().mapToLong(Step::line).toArray();
        String kind = kindOf(steps);
        Map<String, Boolean> ours = new HashMap<>(); // thread -> working on this request now
        List<Step> out = new ArrayList<>();
        for (long line = from; line < to; line++) {
            if ((line & 1023) == 0 && cancelled.getAsBoolean()) {
                break;
            }
            String text = r.text(line);
            String thread = text == null ? null : blankToNull(r.thread(text));
            long[] span = thread == null ? null : spans.get(thread);
            if (span == null || line < span[0] || line >= span[1]) {
                continue;
            }
            if (Arrays.binarySearch(have, line) >= 0) {
                ours.put(thread, true);
                continue;
            }
            if (kind != null && namesAnother(text, kind)) {
                ours.put(thread, false);
                continue;
            }
            if (ours.getOrDefault(thread, false)) {
                out.add(new Step(line, r.time(text), -1, thread, blankToNull(r.level(text)), text, false));
                if (out.size() + steps.size() >= MAX_STEPS * 2) {
                    break;
                }
            }
        }
        return out;
    }

    /** The kind of ID followed ("requestId"), from a line that names it; null if unknown. */
    private static String kindOf(List<Step> steps) {
        for (Step s : steps.subList(0, Math.min(5, steps.size()))) {
            List<RequestIds.Id> ids = RequestIds.find(s.text);
            for (RequestIds.Id id : ids) {
                if (s.text.contains(id.value()) && steps.stream().limit(5).allMatch(o -> o.text.contains(id.value()))) {
                    return id.name();
                }
            }
        }
        return null;
    }

    /** The line names an ID of this kind (one of another request: lines with ours are known). */
    private static boolean namesAnother(String text, String kind) {
        for (RequestIds.Id id : RequestIds.find(text)) {
            if (id.name().equalsIgnoreCase(kind)) {
                return true;
            }
        }
        return false;
    }

    /** Gaps, threads, counts and the longest wait. */
    static Trace finish(List<Step> steps, boolean cut) {
        List<Step> out = new ArrayList<>(steps.size());
        long first = TimeFormat.NONE;
        long last = TimeFormat.NONE;
        long prev = TimeFormat.NONE;
        long longestGap = -1;
        int longest = -1;
        int errors = 0;
        int warnings = 0;
        LinkedHashSet<String> threads = new LinkedHashSet<>();
        for (Step s : steps) {
            long gap = -1;
            if (s.time != TimeFormat.NONE) {
                if (first == TimeFormat.NONE) {
                    first = s.time;
                }
                last = Math.max(last == TimeFormat.NONE ? s.time : last, s.time);
                if (prev != TimeFormat.NONE) {
                    gap = Math.max(0, s.time - prev);
                    if (gap > longestGap) {
                        longestGap = gap;
                        longest = out.size();
                    }
                }
                prev = s.time;
            }
            String level = Levels.normalize(s.level);
            if (Levels.ERROR.equals(level)) {
                errors++;
            } else if (Levels.WARN.equals(level)) {
                warnings++;
            }
            if (s.thread != null) {
                threads.add(s.thread);
            }
            out.add(new Step(s.line, s.time, gap, s.thread, s.level, s.text, s.withId));
        }
        if (longestGap <= 0) {
            longest = -1;
        }
        return new Trace(out, first, last, List.copyOf(threads), errors, warnings, longest, cut);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Reads lines of a document with its format (either may be missing). */
    public static Reader reader(LongFunction<String> text, TimeFormat time, LogFormat format) {
        return new Reader() {
            @Override
            public String text(long line) {
                return text.apply(line);
            }

            @Override
            public long time(String t) {
                return time == null ? TimeFormat.NONE : time.parse(t);
            }

            @Override
            public String thread(String t) {
                return format == null || !format.columns().contains("thread") ? null : format.value(t, "thread");
            }

            @Override
            public String level(String t) {
                if (format != null && format.columns().contains("level")) {
                    return format.value(t, "level");
                }
                String l = MessageStats.levelOf(t);
                return l.isEmpty() ? null : l;
            }
        };
    }
}
