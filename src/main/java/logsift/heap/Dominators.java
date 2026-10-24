package logsift.heap;

/**
 * Dominators of a graph (a node X dominates Y when every path from the root to Y goes through X:
 * freeing X frees Y). Semi-NCA (Georgiadis): a depth-first numbering, semidominators with path
 * compression, then each node's immediate dominator as a nearest common ancestor. Iterative, so
 * deep object chains (long linked lists) don't overflow the stack.
 */
final class Dominators {
    private Dominators() {
    }

    /** The result: in depth-first order from the root. */
    static final class Result {
        /** Nodes reached, in depth-first preorder ({@code order.get(0)} is the root). */
        final Store.Ints order;
        /** For each node: its immediate dominator (a node), or -1 for the root and nodes not reached. */
        final Store.Ints idom;
        final int reached;

        Result(Store.Ints order, Store.Ints idom, int reached) {
            this.order = order;
            this.idom = idom;
            this.reached = reached;
        }
    }

    /**
     * @param n nodes 0..n-1
     * @param succStart for node v, its successors are {@code succ[succStart[v] .. succStart[v+1])}
     */
    static Result compute(Store store, int n, int root, Store.Longs succStart, Store.Ints succ,
                          Store.Longs predStart, Store.Ints pred, Progress progress) {
        // 1. depth-first preorder from the root; num is 1-based (0: not reached)
        Store.Ints num = store.ints("dom-num").size(n);
        Store.Ints order = store.ints("dom-order").size(n);
        Store.Ints parent = store.ints("dom-parent").size(n);
        Store.Ints stackNode = store.ints("dom-stack-node");
        Store.Longs stackEdge = store.longs("dom-stack-edge");
        int count = 0;
        int sp = 0;
        num.set(root, 1);
        order.set(0, root);
        parent.set(0, -1);
        count = 1;
        stackNode.set(0, root);
        stackEdge.set(0, succStart.get(root));
        sp = 1;
        while (sp > 0) {
            int v = stackNode.get(sp - 1);
            long e = stackEdge.get(sp - 1);
            long end = succStart.get(v + 1);
            boolean pushed = false;
            while (e < end) {
                int w = succ.get(e++);
                if (num.get(w) == 0) {
                    stackEdge.set(sp - 1, e);
                    num.set(w, count + 1);
                    order.set(count, w);
                    parent.set(count, num.get(v) - 1);
                    count++;
                    stackNode.set(sp, w);
                    stackEdge.set(sp, succStart.get(w));
                    sp++;
                    pushed = true;
                    break;
                }
            }
            if (!pushed) {
                sp--;
            }
            if ((count & 0xFFFFF) == 0 && progress != null) {
                progress.at(0.3 * count / n);
            }
        }
        stackNode.free();
        stackEdge.free();

        // 2. semidominators, in reverse preorder (all in preorder numbers, 0-based)
        Store.Ints semi = store.ints("dom-semi").size(count);
        Store.Ints label = store.ints("dom-label").size(count);
        Store.Ints ancestor = store.ints("dom-ancestor").size(count); // 1-based, 0: none
        for (int i = 0; i < count; i++) {
            semi.set(i, i);
            label.set(i, i);
        }
        int[] path = new int[1024];
        for (int w = count - 1; w > 0; w--) {
            int node = order.get(w);
            int s = semi.get(w);
            for (long e = predStart.get(node), end = predStart.get(node + 1); e < end; e++) {
                int vn = num.get(pred.get(e));
                if (vn == 0) {
                    continue; // a predecessor that isn't reached from the root
                }
                int v = vn - 1;
                int u;
                if (ancestor.get(v) == 0) {
                    u = v;
                } else {
                    path = compress(v, ancestor, label, semi, path);
                    u = label.get(v);
                }
                int su = semi.get(u);
                if (su < s) {
                    s = su;
                }
            }
            semi.set(w, s);
            ancestor.set(w, parent.get(w) + 1); // link w to its parent
            if ((w & 0xFFFFF) == 0 && progress != null) {
                progress.at(0.3 + 0.5 * (count - w) / count);
            }
        }
        label.free();
        ancestor.free();

        // 3. immediate dominators: the nearest common ancestor in the tree built so far
        Store.Ints dom = parent; // reused: dom[i] starts as parent[i]
        for (int i = 1; i < count; i++) {
            int d = dom.get(i);
            int si = semi.get(i);
            while (d > si) {
                d = dom.get(d);
            }
            dom.set(i, d);
        }
        semi.free();

        // 4. back to node numbers
        Store.Ints idom = store.ints("dom-idom").size(n);
        for (int v = 0; v < n; v++) {
            idom.set(v, -1);
        }
        for (int i = 1; i < count; i++) {
            idom.set(order.get(i), order.get(dom.get(i)));
        }
        num.free();
        parent.free();
        if (progress != null) {
            progress.at(1);
        }
        return new Result(order, idom, count);
    }

    /** Path compression for {@code v}, without recursion; returns the (maybe grown) scratch array. */
    private static int[] compress(int v, Store.Ints ancestor, Store.Ints label, Store.Ints semi, int[] path) {
        int top = 0;
        int x = v;
        // up to the node whose ancestor is a tree root
        while (true) {
            int a = ancestor.get(x) - 1;
            if (ancestor.get(a) == 0) {
                break;
            }
            if (top == path.length) {
                path = java.util.Arrays.copyOf(path, top * 2);
            }
            path[top++] = x;
            x = a;
        }
        while (top > 0) {
            int y = path[--top];
            int a = ancestor.get(y) - 1;
            if (semi.get(label.get(a)) < semi.get(label.get(y))) {
                label.set(y, label.get(a));
            }
            ancestor.set(y, ancestor.get(a));
        }
        return path;
    }

    /** Reports how far a long step is (0..1). */
    interface Progress {
        void at(double fraction);
    }
}
