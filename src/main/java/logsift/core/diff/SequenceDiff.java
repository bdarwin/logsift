package logsift.core.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines up two sequences of items (rows, lines, words), each given as a hash: which runs are
 * equal, inserted, deleted or replaced. Common starts and ends are taken off first; then items
 * that occur once on each side anchor the rest (patience diff), which keeps moved blocks and
 * repeated lines (blank lines, closing braces) from lining up wrongly; what lies between anchors
 * is diffed with Myers' algorithm. A gap that would cost more than {@link #MAX_COST} edits is
 * reported as one replacement, so a diff never takes quadratic time.
 */
public final class SequenceDiff {
    /** The most insertions and deletions Myers' algorithm looks for in one gap. */
    public static final int MAX_COST = 2000;
    private static final int MAX_DEPTH = 64;

    private final int[] a;
    private final int[] b;
    private final int[] countA;
    private final int[] countB;
    private final int[] lastB;
    private final Builder out = new Builder();
    private final int maxCost;
    private boolean patience = true;

    private SequenceDiff(int[] a, int[] b, int alphabet, int maxCost) {
        this.a = a;
        this.b = b;
        this.countA = new int[alphabet];
        this.countB = new int[alphabet];
        this.lastB = new int[alphabet];
        this.maxCost = maxCost;
    }

    /** The runs that turn {@code a} into {@code b} (equal hashes are equal items). */
    public static List<Edit> diff(long[] a, long[] b) {
        return diff(a, b, MAX_COST);
    }

    static List<Edit> diff(long[] a, long[] b, int maxCost) {
        return diff(a, b, maxCost, true);
    }

    /** For tests: {@code patience} false runs Myers' algorithm alone (a minimal diff). */
    static List<Edit> diff(long[] a, long[] b, int maxCost, boolean patience) {
        Map<Long, Integer> ids = new HashMap<>();
        int[] ia = intern(a, ids);
        int[] ib = intern(b, ids);
        SequenceDiff d = new SequenceDiff(ia, ib, ids.size(), maxCost);
        d.patience = patience;
        d.solve(0, ia.length, 0, ib.length, 0);
        return d.out.finish(ia.length, ib.length);
    }

    private static int[] intern(long[] v, Map<Long, Integer> ids) {
        int[] out = new int[v.length];
        for (int i = 0; i < v.length; i++) {
            Integer id = ids.get(v[i]);
            if (id == null) {
                id = ids.size();
                ids.put(v[i], id);
            }
            out[i] = id;
        }
        return out;
    }

    private void solve(int aLo, int aHi, int bLo, int bHi, int depth) {
        int pre = 0;
        while (aLo + pre < aHi && bLo + pre < bHi && a[aLo + pre] == b[bLo + pre]) {
            pre++;
        }
        out.equal(aLo, bLo, pre);
        aLo += pre;
        bLo += pre;
        int suf = 0;
        while (aHi - suf > aLo && bHi - suf > bLo && a[aHi - suf - 1] == b[bHi - suf - 1]) {
            suf++;
        }
        aHi -= suf;
        bHi -= suf;
        if (aLo == aHi) {
            out.gap(aLo, aLo, bLo, bHi);
        } else if (bLo == bHi) {
            out.gap(aLo, aHi, bLo, bLo);
        } else {
            int[][] anchors = patience && depth < MAX_DEPTH ? anchors(aLo, aHi, bLo, bHi) : null;
            if (anchors != null && anchors[0].length > 0) {
                int pa = aLo;
                int pb = bLo;
                for (int i = 0; i < anchors[0].length; i++) {
                    solve(pa, anchors[0][i], pb, anchors[1][i], depth + 1);
                    out.equal(anchors[0][i], anchors[1][i], 1);
                    pa = anchors[0][i] + 1;
                    pb = anchors[1][i] + 1;
                }
                solve(pa, aHi, pb, bHi, depth + 1);
            } else {
                myers(aLo, aHi, bLo, bHi);
            }
        }
        out.equal(aHi, bHi, suf);
    }

    /**
     * Items found once in a's range and once in b's, as pairs of positions, the longest run of
     * them in the same order on both sides ({a positions, b positions}).
     */
    private int[][] anchors(int aLo, int aHi, int bLo, int bHi) {
        for (int i = aLo; i < aHi; i++) {
            countA[a[i]]++;
        }
        for (int i = bLo; i < bHi; i++) {
            countB[b[i]]++;
            lastB[b[i]] = i;
        }
        List<int[]> pairs = new ArrayList<>();
        for (int i = aLo; i < aHi; i++) {
            int x = a[i];
            if (countA[x] == 1 && countB[x] == 1) {
                pairs.add(new int[] {i, lastB[x]});
            }
        }
        for (int i = aLo; i < aHi; i++) {
            countA[a[i]] = 0;
        }
        for (int i = bLo; i < bHi; i++) {
            countB[b[i]] = 0;
        }
        // longest increasing run of b positions (patience sorting)
        int n = pairs.size();
        int[] tails = new int[n];
        int[] prev = new int[n];
        int len = 0;
        for (int i = 0; i < n; i++) {
            int bi = pairs.get(i)[1];
            int lo = 0;
            int hi = len;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (pairs.get(tails[mid])[1] < bi) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            prev[i] = lo > 0 ? tails[lo - 1] : -1;
            tails[lo] = i;
            if (lo == len) {
                len++;
            }
        }
        int[][] res = new int[2][len];
        for (int i = len - 1, k = len > 0 ? tails[len - 1] : -1; i >= 0; i--, k = prev[k]) {
            res[0][i] = pairs.get(k)[0];
            res[1][i] = pairs.get(k)[1];
        }
        return res;
    }

    /** Myers' O(ND) diff of the ranges (which differ at both ends), or one replacement if too costly. */
    private void myers(int aLo, int aHi, int bLo, int bHi) {
        int n = aHi - aLo;
        int m = bHi - bLo;
        int cap = Math.min(n + m, maxCost);
        int off = cap + 1;
        int[] v = new int[2 * cap + 3];
        List<int[]> trace = new ArrayList<>();
        for (int d = 0; d <= cap; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[off + k - 1] < v[off + k + 1]) ? v[off + k + 1] : v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[aLo + x] == b[bLo + y]) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    int[] snap = new int[2 * d + 1];
                    System.arraycopy(v, off - d, snap, 0, 2 * d + 1);
                    trace.add(snap);
                    backtrack(trace, aLo, bLo, n, m);
                    return;
                }
            }
            int[] snap = new int[2 * d + 1];
            System.arraycopy(v, off - d, snap, 0, 2 * d + 1);
            trace.add(snap);
        }
        out.gap(aLo, aHi, bLo, bHi);
    }

    private void backtrack(List<int[]> trace, int aLo, int bLo, int n, int m) {
        // moves from the end back to the start: {kind (0 equal, 1 delete, 2 insert), x, y, length}
        List<int[]> moves = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = trace.size() - 1; d > 0; d--) {
            int[] vp = trace.get(d - 1);
            int k = x - y;
            boolean down = k == -d || (k != d && at(vp, d - 1, k - 1) < at(vp, d - 1, k + 1));
            int pk = down ? k + 1 : k - 1;
            int px = at(vp, d - 1, pk);
            int py = px - pk;
            int sx = down ? px : px + 1;
            int sy = sx - k;
            if (x > sx) {
                moves.add(new int[] {0, sx, sy, x - sx});
            }
            moves.add(down ? new int[] {2, px, py, 1} : new int[] {1, px, py, 1});
            x = px;
            y = py;
        }
        if (x > 0) {
            moves.add(new int[] {0, 0, 0, x});
        }
        for (int i = moves.size() - 1; i >= 0; i--) {
            int[] mv = moves.get(i);
            switch (mv[0]) {
                case 0 -> out.equal(aLo + mv[1], bLo + mv[2], mv[3]);
                case 1 -> out.gap(aLo + mv[1], aLo + mv[1] + 1, bLo + mv[2], bLo + mv[2]);
                default -> out.gap(aLo + mv[1], aLo + mv[1], bLo + mv[2], bLo + mv[2] + 1);
            }
        }
    }

    private static int at(int[] snap, int d, int k) {
        return snap[k + d];
    }

    /** Collects runs in order, joining neighbours: a deletion next to an insertion is a replacement. */
    private static final class Builder {
        private final List<Edit> edits = new ArrayList<>();
        private int eqA = -1;
        private int eqB = -1;
        private int eqLen;
        private int gapA0 = -1;
        private int gapA1;
        private int gapB0;
        private int gapB1;

        void equal(int ai, int bi, int len) {
            if (len <= 0) {
                return;
            }
            flushGap();
            if (eqLen > 0 && eqA + eqLen == ai && eqB + eqLen == bi) {
                eqLen += len;
            } else {
                flushEqual();
                eqA = ai;
                eqB = bi;
                eqLen = len;
            }
        }

        void gap(int a0, int a1, int b0, int b1) {
            if (a0 == a1 && b0 == b1) {
                return;
            }
            flushEqual();
            if (gapA0 < 0) {
                gapA0 = a0;
                gapA1 = a1;
                gapB0 = b0;
                gapB1 = b1;
            } else {
                gapA1 = Math.max(gapA1, a1);
                gapB1 = Math.max(gapB1, b1);
            }
        }

        private void flushEqual() {
            if (eqLen > 0) {
                edits.add(new Edit(Edit.Kind.EQUAL, eqA, eqA + eqLen, eqB, eqB + eqLen));
                eqLen = 0;
            }
        }

        private void flushGap() {
            if (gapA0 >= 0) {
                Edit.Kind k = gapA0 == gapA1 ? Edit.Kind.INSERT : gapB0 == gapB1 ? Edit.Kind.DELETE : Edit.Kind.REPLACE;
                edits.add(new Edit(k, gapA0, gapA1, gapB0, gapB1));
                gapA0 = -1;
            }
        }

        List<Edit> finish(int n, int m) {
            flushGap();
            flushEqual();
            return List.copyOf(edits);
        }
    }
}
