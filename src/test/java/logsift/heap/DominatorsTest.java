package logsift.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DominatorsTest {

    /** Builds the successor and predecessor arrays of a graph given as edge lists. */
    private static Dominators.Result run(int n, List<int[]> edges) {
        Store st = Store.inMemory();
        Store.Rising ss = st.rising("ss").size(n + 1);
        Store.Rising ps = st.rising("ps").size(n + 1);
        Store.Ints s = st.ints("s").size(Math.max(1, edges.size()));
        Store.Ints p = st.ints("p").size(Math.max(1, edges.size()));
        int[] outDeg = new int[n];
        int[] inDeg = new int[n];
        for (int[] e : edges) {
            outDeg[e[0]]++;
            inDeg[e[1]]++;
        }
        long a = 0;
        long b = 0;
        for (int v = 0; v < n; v++) {
            ss.set(v, a);
            ps.set(v, b);
            a += outDeg[v];
            b += inDeg[v];
        }
        ss.set(n, a);
        ps.set(n, b);
        long[] fo = new long[n];
        long[] fi = new long[n];
        for (int v = 0; v < n; v++) {
            fo[v] = ss.get(v);
            fi[v] = ps.get(v);
        }
        for (int[] e : edges) {
            s.set(fo[e[0]]++, e[1]);
            p.set(fi[e[1]]++, e[0]);
        }
        return Dominators.compute(st, n, 0, ss, s, ps, p, null);
    }

    /** Immediate dominators the slow way: d dominates v when v is unreachable without d. */
    private static int[] naive(int n, List<int[]> edges) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (int[] e : edges) {
            adj.get(e[0]).add(e[1]);
        }
        boolean[] reach = reach(n, adj, -1);
        boolean[][] dom = new boolean[n][n]; // dom[v][d]: d dominates v
        for (int d = 0; d < n; d++) {
            boolean[] without = reach(n, adj, d);
            for (int v = 0; v < n; v++) {
                dom[v][d] = reach[v] && (v == d || !without[v]);
            }
        }
        int[] idom = new int[n];
        for (int v = 0; v < n; v++) {
            idom[v] = -1;
            if (v == 0 || !reach[v]) {
                continue;
            }
            // the strict dominator dominated by all other strict dominators
            for (int d = 0; d < n; d++) {
                if (d == v || !dom[v][d]) {
                    continue;
                }
                boolean closest = true;
                for (int o = 0; o < n; o++) {
                    if (o != v && o != d && dom[v][o] && !dom[d][o]) {
                        closest = false;
                    }
                }
                if (closest) {
                    idom[v] = d;
                }
            }
        }
        return idom;
    }

    private static boolean[] reach(int n, List<List<Integer>> adj, int removed) {
        boolean[] seen = new boolean[n];
        if (removed == 0) {
            return seen;
        }
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(0);
        seen[0] = true;
        while (!q.isEmpty()) {
            int v = q.poll();
            for (int w : adj.get(v)) {
                if (w != removed && !seen[w]) {
                    seen[w] = true;
                    q.add(w);
                }
            }
        }
        return seen;
    }

    @Test
    void matchesTheSlowWayOnRandomGraphs() {
        Random r = new Random(42);
        for (int round = 0; round < 400; round++) {
            int n = 2 + r.nextInt(40);
            int m = r.nextInt(n * 3);
            List<int[]> edges = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                edges.add(new int[] {r.nextInt(n), r.nextInt(n)});
            }
            Dominators.Result res = run(n, edges);
            int[] want = naive(n, edges);
            for (int v = 0; v < n; v++) {
                assertEquals(want[v], res.idom.get(v), "round " + round + " node " + v + " edges " + str(edges));
            }
        }
    }

    @Test
    void aVeryLongChainDoesNotOverflowTheStack() {
        int n = 300_000;
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) {
            edges.add(new int[] {i, i + 1});
        }
        edges.add(new int[] {n - 1, 1}); // a cycle back
        Dominators.Result res = run(n, edges);
        assertEquals(n, res.reached);
        assertEquals(n - 2, res.idom.get(n - 1));
        assertEquals(0, res.idom.get(1));
    }

    private static String str(List<int[]> edges) {
        StringBuilder sb = new StringBuilder();
        for (int[] e : edges) {
            sb.append(e[0]).append("->").append(e[1]).append(' ');
        }
        return sb.toString();
    }
}
