package logmaster.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Compares two logs by their kinds of messages (see {@link MessageStats}), not line by line
 * (timestamps make every line differ). Finds messages that are new in the second log, gone from
 * it, or much more or less frequent, taking the logs' lengths into account.
 */
public final class LogCompare {
    private LogCompare() {}

    public enum Kind { NEW, GONE, MORE, LESS, SAME }

    /**
     * One kind of message in both logs.
     *
     * @param ratio how much more often it occurs in B than in A, per entry (A's length scaled to B's)
     */
    public record Diff(String text, String level, long countA, long countB, long firstA, long firstB,
                       double ratio) {
        public Kind kind() {
            if (countA == 0) {
                return Kind.NEW;
            }
            if (countB == 0) {
                return Kind.GONE;
            }
            if (ratio >= 2) {
                return Kind.MORE;
            }
            if (ratio <= 0.5) {
                return Kind.LESS;
            }
            return Kind.SAME;
        }

        public String regex() {
            return MessageStats.toRegex(text);
        }
    }

    public record Result(List<Diff> diffs, long entriesA, long entriesB, long millis) {}

    /** Compares log A (the baseline, for example a good run) with log B. */
    public static Result compare(LogDocument a, EntryFormat entriesA, LogDocument b, EntryFormat entriesB,
                                 BooleanSupplier cancelled, DoubleConsumer progress) {
        long start = System.nanoTime();
        MessageStats.Raw ra = MessageStats.countRaw(a, entriesA, 0, Long.MAX_VALUE, null, cancelled,
                progress == null ? null : f -> progress.accept(f / 2));
        MessageStats.Raw rb = MessageStats.countRaw(b, entriesB, 0, Long.MAX_VALUE, null, cancelled,
                progress == null ? null : f -> progress.accept(0.5 + f / 2));
        // exact templates of both logs: text -> {countA, countB, firstA, firstB}
        Map<String, long[]> both = new LinkedHashMap<>();
        add(both, ra.stats(), a.charset(), 0);
        add(both, rb.stats(), b.charset(), 1);
        List<Diff> diffs = mergeSimilar(both, ra.lines(), rb.lines());
        return new Result(diffs, ra.lines(), rb.lines(), (System.nanoTime() - start) / 1_000_000);
    }

    private static void add(Map<String, long[]> both, Map<String, MessageStats.Stat> raw,
                            java.nio.charset.Charset cs, int side) {
        for (var e : raw.entrySet()) {
            String text = new String(e.getKey().getBytes(StandardCharsets.ISO_8859_1), cs);
            long[] v = both.computeIfAbsent(text, k -> new long[] {0, 0, Long.MAX_VALUE, Long.MAX_VALUE});
            v[side] += e.getValue().count;
            v[2 + side] = Math.min(v[2 + side], e.getValue().first);
        }
    }

    /** Merges templates differing in one word (as MessageStats does), then sorts by importance. */
    static List<Diff> mergeSimilar(Map<String, long[]> both, long entriesA, long entriesB) {
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (var e : both.entrySet()) {
            String[] tokens = e.getKey().split(" ", -1);
            groups.computeIfAbsent(MessageStats.levelOf(e.getKey()) + "/" + tokens.length,
                    k -> new ArrayList<>()).add(new Object[] {tokens, e.getValue()});
        }
        List<Diff> out = new ArrayList<>();
        for (List<Object[]> group : groups.values()) {
            group.sort(Comparator.comparingLong((Object[] x) -> -(((long[]) x[1])[0] + ((long[]) x[1])[1])));
            List<String[]> merged = new ArrayList<>();
            List<long[]> counts = new ArrayList<>();
            boolean tooBig = group.size() > 3000;
            for (Object[] item : group) {
                String[] t = (String[]) item[0];
                long[] c = (long[]) item[1];
                int into = -1;
                for (int m = 0; !tooBig && m < merged.size() && into < 0; m++) {
                    if (MessageStats.differsInOneWord(merged.get(m), t)) {
                        into = m;
                    }
                }
                if (into < 0) {
                    merged.add(t.clone());
                    counts.add(c.clone());
                } else {
                    String[] m = merged.get(into);
                    for (int k = 0; k < m.length; k++) {
                        if (!m[k].equals(t[k])) {
                            m[k] = MessageStats.WILDCARD;
                        }
                    }
                    long[] mc = counts.get(into);
                    mc[0] += c[0];
                    mc[1] += c[1];
                    mc[2] = Math.min(mc[2], c[2]);
                    mc[3] = Math.min(mc[3], c[3]);
                }
            }
            for (int m = 0; m < merged.size(); m++) {
                String text = String.join(" ", merged.get(m));
                long[] c = counts.get(m);
                double rateA = (double) c[0] / Math.max(1, entriesA);
                double rateB = (double) c[1] / Math.max(1, entriesB);
                double ratio = rateA == 0 ? Double.POSITIVE_INFINITY : rateB / rateA;
                out.add(new Diff(text, MessageStats.levelOf(text), c[0], c[1],
                        c[2] == Long.MAX_VALUE ? -1 : c[2], c[3] == Long.MAX_VALUE ? -1 : c[3], ratio));
            }
        }
        out.sort(LogCompare::byImportance);
        return out;
    }

    /** New first (errors first, then by count), then large increases, then gone, then the rest. */
    private static int byImportance(Diff x, Diff y) {
        int c = Integer.compare(rank(x), rank(y));
        if (c != 0) {
            return c;
        }
        c = Integer.compare(levelRank(x.level()), levelRank(y.level()));
        if (c != 0) {
            return c;
        }
        return switch (x.kind()) {
            case NEW -> Long.compare(y.countB(), x.countB());
            case GONE -> Long.compare(y.countA(), x.countA());
            case MORE -> Double.compare(y.ratio(), x.ratio());
            case LESS -> Double.compare(x.ratio(), y.ratio());
            case SAME -> Long.compare(y.countB(), x.countB());
        };
    }

    private static int rank(Diff d) {
        return switch (d.kind()) {
            case NEW -> 0;
            case MORE -> 1;
            case GONE -> 2;
            case LESS -> 3;
            case SAME -> 4;
        };
    }

    private static int levelRank(String level) {
        return switch (level) {
            case "FATAL", "ERROR" -> 0;
            case "WARN" -> 1;
            default -> 2;
        };
    }
}
