package logsift.heap;

/**
 * Object id (its address in the dump) → node number, as an open-addressing hash table in a
 * {@link Store} (so it can live on disk). Ids are never 0 (0 is null in a dump).
 */
final class IdMap {
    private final Store.Longs keys;
    private final Store.Ints values;
    private final long mask;
    private long size;

    /** For about {@code expected} ids. */
    IdMap(Store store, long expected) {
        long cap = Long.highestOneBit(Math.max(16, expected + expected / 2)) << 1;
        mask = cap - 1;
        keys = store.longs("idmap-keys").size(cap);
        values = store.ints("idmap-values").size(cap);
    }

    private static long hash(long id) {
        long h = id * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 29);
    }

    void put(long id, int node) {
        long i = hash(id) & mask;
        while (true) {
            long k = keys.get(i);
            if (k == 0) {
                keys.set(i, id);
                values.set(i, node);
                size++;
                if (size > mask - mask / 4) {
                    throw new IllegalStateException("more objects than counted");
                }
                return;
            }
            if (k == id) {
                values.set(i, node);
                return;
            }
            i = (i + 1) & mask;
        }
    }

    /** The node for this id, or -1. */
    int get(long id) {
        if (id == 0) {
            return -1;
        }
        long i = hash(id) & mask;
        while (true) {
            long k = keys.get(i);
            if (k == id) {
                return values.get(i);
            }
            if (k == 0) {
                return -1;
            }
            i = (i + 1) & mask;
        }
    }

    void free() {
        keys.free();
        values.free();
    }
}
