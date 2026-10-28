package logsift.core.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * A three-way merge of text: the common ancestor (base) and two changed versions, ours (left,
 * the branch being merged into) and theirs (right). Each changed region is a chunk: changed on
 * one side only (taken from that side), changed the same way on both, or changed differently
 * on both, a conflict for people to resolve. Lines are compared as {@link TextDiff} does.
 */
public final class Merge3 {
    private Merge3() {}

    /** What happened to a stretch of the base. */
    public enum Kind {
        /** No side changed it. */
        SAME,
        /** Only ours changed it. */
        OURS,
        /** Only theirs changed it. */
        THEIRS,
        /** Both changed it the same way. */
        BOTH_SAME,
        /** Both changed it, differently. */
        CONFLICT
    }

    /**
     * A stretch: base lines {@code [b1, b2)}, our lines {@code [o1, o2)} and their lines
     * {@code [t1, t2)}.
     */
    public record Chunk(Kind kind, int b1, int b2, int o1, int o2, int t1, int t2) {
        public boolean isChange() {
            return kind != Kind.SAME;
        }
    }

    /** How a chunk is resolved: which lines the result takes. */
    public enum Choice {
        /** Not resolved yet (a conflict); the result has the base's lines meanwhile. */
        NONE,
        BASE, OURS, THEIRS,
        /** Ours, then theirs. */
        OURS_THEN_THEIRS,
        /** Theirs, then ours. */
        THEIRS_THEN_OURS,
        /** Lines typed by hand. */
        EDITED
    }

    /** A merge: the three texts and their chunks. */
    public record Result(List<String> base, List<String> ours, List<String> theirs, List<Chunk> chunks) {
        public long conflicts() {
            return chunks.stream().filter(c -> c.kind() == Kind.CONFLICT).count();
        }

        public long changes() {
            return chunks.stream().filter(Chunk::isChange).count();
        }

        /** The choice made for a chunk at first: one side's change is taken, conflicts wait. */
        public Choice automatic(Chunk c) {
            return switch (c.kind()) {
                case SAME -> Choice.BASE;
                case OURS, BOTH_SAME -> Choice.OURS;
                case THEIRS -> Choice.THEIRS;
                case CONFLICT -> Choice.NONE;
            };
        }

        /** A chunk's lines for a choice ({@code edited}: the lines typed, for EDITED). */
        public List<String> lines(Chunk c, Choice choice, List<String> edited) {
            List<String> o = ours.subList(c.o1(), c.o2());
            List<String> t = theirs.subList(c.t1(), c.t2());
            return switch (choice) {
                case NONE, BASE -> base.subList(c.b1(), c.b2());
                case OURS -> o;
                case THEIRS -> t;
                case OURS_THEN_THEIRS -> concat(o, t);
                case THEIRS_THEN_OURS -> concat(t, o);
                case EDITED -> edited == null ? List.of() : edited;
            };
        }

        /** The merged text for these choices (one per chunk). */
        public List<String> merged(List<Choice> choices, List<List<String>> edited) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                out.addAll(lines(chunks.get(i), choices.get(i), edited == null ? null : edited.get(i)));
            }
            return out;
        }

        /** The text with git's conflict markers where conflicts are unresolved (what git leaves in the file). */
        public List<String> withMarkers(List<Choice> choices, List<List<String>> edited, String oursName,
                                        String theirsName) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                if (choices.get(i) == Choice.NONE && c.kind() == Kind.CONFLICT) {
                    out.add("<<<<<<< " + oursName);
                    out.addAll(ours.subList(c.o1(), c.o2()));
                    out.add("=======");
                    out.addAll(theirs.subList(c.t1(), c.t2()));
                    out.add(">>>>>>> " + theirsName);
                } else {
                    out.addAll(lines(c, choices.get(i), edited == null ? null : edited.get(i)));
                }
            }
            return out;
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Merges {@code ours} and {@code theirs}, both changed from {@code base}. */
    public static Result merge(List<String> base, List<String> ours, List<String> theirs, TextDiff.Options o) {
        List<Edit> eo = edits(base, ours, o);
        List<Edit> et = edits(base, theirs, o);
        // the base lines each side changed, as ranges (an insert is an empty range at its place)
        List<int[]> co = changed(eo);
        List<int[]> ct = changed(et);
        List<Chunk> chunks = new ArrayList<>();
        int i = 0;
        int j = 0;
        int b = 0; // base position
        int oShift = 0; // our line = base line + shift, outside our changes
        int tShift = 0;
        while (i < co.size() || j < ct.size()) {
            int[] x = i < co.size() ? co.get(i) : null;
            int[] y = j < ct.size() ? ct.get(j) : null;
            // the next change starts at the smaller base position
            int start = x == null ? y[0] : y == null ? x[0] : Math.min(x[0], y[0]);
            if (start > b) {
                chunks.add(new Chunk(Kind.SAME, b, start, b + oShift, start + oShift, b + tShift, start + tShift));
                b = start;
            }
            // grow the region while the two sides' changes overlap or touch
            int end = start;
            int oi = i;
            int tj = j;
            boolean grew = true;
            boolean any = false;
            while (grew) {
                grew = false;
                while (oi < co.size() && overlaps(co.get(oi), start, end, any)) {
                    end = Math.max(end, co.get(oi)[1]);
                    oi++;
                    grew = true;
                    any = true;
                }
                while (tj < ct.size() && overlaps(ct.get(tj), start, end, any)) {
                    end = Math.max(end, ct.get(tj)[1]);
                    tj++;
                    grew = true;
                    any = true;
                }
            }
            // the region on each side: from the base's start to its end, through that side's changes
            int o1 = start + oShift;
            int t1 = start + tShift;
            int newOShift = oShift;
            for (int k = i; k < oi; k++) {
                newOShift = co.get(k)[3] - co.get(k)[1];
            }
            int newTShift = tShift;
            for (int k = j; k < tj; k++) {
                newTShift = ct.get(k)[3] - ct.get(k)[1];
            }
            int o2 = end + newOShift;
            int t2 = end + newTShift;
            boolean oursChanged = oi > i;
            boolean theirsChanged = tj > j;
            Kind kind;
            if (oursChanged && theirsChanged) {
                kind = ours.subList(o1, o2).equals(theirs.subList(t1, t2)) ? Kind.BOTH_SAME : Kind.CONFLICT;
            } else {
                kind = oursChanged ? Kind.OURS : Kind.THEIRS;
            }
            chunks.add(new Chunk(kind, start, end, o1, o2, t1, t2));
            b = end;
            oShift = newOShift;
            tShift = newTShift;
            i = oi;
            j = tj;
        }
        if (b < base.size() || chunks.isEmpty()) {
            chunks.add(new Chunk(Kind.SAME, b, base.size(), b + oShift, base.size() + oShift, b + tShift,
                    base.size() + tShift));
        }
        return new Result(List.copyOf(base), List.copyOf(ours), List.copyOf(theirs), List.copyOf(chunks));
    }

    /** Whether a change (base range [c0, c1)) belongs to the region [start, end). */
    private static boolean overlaps(int[] c, int start, int end, boolean any) {
        if (!any) {
            return c[0] == start;
        }
        // overlapping, or touching (an insert at the region's edge, or a change right after it)
        return c[0] < end || c[0] == end && (c[0] == c[1] || end == start);
    }

    /**
     * The changes of an edit list, each {@code [baseStart, baseEnd, sideStart, sideEnd]}, with the
     * side's position after the change: {@code sideEnd - baseEnd} is the shift that follows.
     */
    private static List<int[]> changed(List<Edit> edits) {
        List<int[]> out = new ArrayList<>();
        for (Edit e : edits) {
            if (e.kind() != Edit.Kind.EQUAL) {
                out.add(new int[] {e.aStart(), e.aEnd(), e.bStart(), e.bEnd()});
            }
        }
        // a delete and an insert next to each other are one change
        List<int[]> merged = new ArrayList<>();
        for (int[] c : out) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last[1] == c[0] && last[3] == c[2]) {
                last[1] = c[1];
                last[3] = c[3];
            } else {
                merged.add(c);
            }
        }
        return merged;
    }

    private static List<Edit> edits(List<String> a, List<String> b, TextDiff.Options o) {
        long[] ha = new long[a.size()];
        long[] hb = new long[b.size()];
        for (int i = 0; i < ha.length; i++) {
            String s = TextDiff.normalize(a.get(i), o);
            ha[i] = Hashes.add2(Hashes.add1(Hashes.SEED1, s), s);
        }
        for (int i = 0; i < hb.length; i++) {
            String s = TextDiff.normalize(b.get(i), o);
            hb[i] = Hashes.add2(Hashes.add1(Hashes.SEED1, s), s);
        }
        return SequenceDiff.diff(ha, hb);
    }
}
