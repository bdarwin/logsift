package logsift.heap;

import java.util.function.BooleanSupplier;

/**
 * Object id (its address in the dump) → node number. A JVM writes the objects of its heap mostly
 * in address order, so the ids come in a few hundred increasing runs: merged, they give a sorted
 * list to search (12 bytes per object, read in order). A dump in no order gets a hash table
 * ({@link IdMap}) instead.
 */
final class IdIndex {
    /** More runs than this: a hash table. */
    static final int MAX_RUNS = 1 << 16;
    private static final int BLOCK = 256;

    private Store.Longs sortedIds;
    private Store.Ints sortedNodes;
    private long[] firstOfBlock; // sortedIds[k * BLOCK]
    private long count;
    private IdMap map;

    /** The index of {@code ids[0 .. n)}, where ids[v] is node v's id. */
    static IdIndex of(Store store, Store.Longs ids, int n, BooleanSupplier cancel) {
        IdIndex x = new IdIndex();
        // the runs of increasing ids
        int runs = 0;
        long prev = Long.MIN_VALUE;
        for (int v = 0; v < n; v++) {
            long id = ids.get(v);
            if (id <= prev) {
                runs++;
                if (runs >= MAX_RUNS) {
                    break;
                }
            }
            prev = id;
        }
        runs++;
        if (runs >= MAX_RUNS) {
            x.map = new IdMap(store, n);
            for (int v = 0; v < n; v++) {
                x.map.put(ids.get(v), v);
            }
            return x;
        }
        int[] start = new int[runs + 1];
        int r = 0;
        prev = Long.MIN_VALUE;
        for (int v = 0; v < n; v++) {
            long id = ids.get(v);
            if (v > 0 && id <= prev) {
                start[++r] = v;
            }
            prev = id;
        }
        start[runs] = n;
        // merge the runs: a binary heap of runs by their next id
        int[] at = new int[runs];
        long[] head = new long[runs];
        int[] heap = new int[runs];
        int size = 0;
        for (int i = 0; i < runs; i++) {
            at[i] = start[i];
            if (at[i] < start[i + 1]) {
                head[i] = ids.get(at[i]);
                heap[size] = i;
                up(heap, head, size++);
            }
        }
        x.sortedIds = store.longs("id-sorted").size(Math.max(1, n));
        x.sortedNodes = store.ints("id-nodes").size(Math.max(1, n));
        x.firstOfBlock = new long[(n + BLOCK - 1) / BLOCK];
        long k = 0;
        while (size > 0) {
            int i = heap[0];
            long id = head[i];
            if ((k & (BLOCK - 1)) == 0) {
                x.firstOfBlock[(int) (k / BLOCK)] = id;
            }
            x.sortedIds.set(k, id);
            x.sortedNodes.set(k, at[i]);
            k++;
            if (++at[i] < start[i + 1]) {
                head[i] = ids.get(at[i]);
            } else {
                heap[0] = heap[--size];
            }
            down(heap, head, size);
            if ((k & 0xFFFFF) == 0 && cancel.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
        }
        x.count = k;
        return x;
    }

    private static void up(int[] heap, long[] head, int i) {
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (head[heap[p]] <= head[heap[i]]) {
                return;
            }
            int t = heap[p];
            heap[p] = heap[i];
            heap[i] = t;
            i = p;
        }
    }

    private static void down(int[] heap, long[] head, int size) {
        int i = 0;
        while (true) {
            int a = 2 * i + 1;
            if (a >= size) {
                return;
            }
            int b = a + 1;
            int m = b < size && head[heap[b]] < head[heap[a]] ? b : a;
            if (head[heap[i]] <= head[heap[m]]) {
                return;
            }
            int t = heap[m];
            heap[m] = heap[i];
            heap[i] = t;
            i = m;
        }
    }

    /** The node for this id, or -1. */
    int get(long id) {
        if (id == 0) {
            return -1;
        }
        if (map != null) {
            return map.get(id);
        }
        // the block: the last one that starts at or before id
        int lo = 0;
        int hi = firstOfBlock.length - 1;
        if (hi < 0 || id < firstOfBlock[0]) {
            return -1;
        }
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (firstOfBlock[mid] <= id) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        long a = (long) lo * BLOCK;
        long b = Math.min(count, a + BLOCK) - 1;
        while (a <= b) {
            long mid = (a + b) >>> 1;
            long m = sortedIds.get(mid);
            if (m < id) {
                a = mid + 1;
            } else if (m > id) {
                b = mid - 1;
            } else {
                return sortedNodes.get(mid);
            }
        }
        return -1;
    }

    void free() {
        if (map != null) {
            map.free();
        } else {
            sortedIds.free();
            sortedNodes.free();
            firstOfBlock = null;
        }
    }
}
