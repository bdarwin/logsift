package logsift.core;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Keeps one result worked out over a whole log, for one version of it (its {@code key}: the
 * file's generation, line count, format…). Filtering then only picks from the result: a change
 * of filters doesn't read the log again.
 */
public final class WholeLogCache<T> {
    private Object key;
    private T value;
    private int computations;

    /** The result for this version of the log, or null if there is none yet. */
    public synchronized T peek(Object key) {
        return Objects.equals(this.key, key) ? value : null;
    }

    /** The result for this version of the log, worked out now if needed (null results aren't kept). */
    public T get(Object key, Supplier<T> compute) {
        T v = peek(key);
        if (v != null) {
            return v;
        }
        v = compute.get();
        if (v != null) {
            put(key, v);
        }
        return v;
    }

    public synchronized void put(Object key, T value) {
        this.key = key;
        this.value = value;
        computations++;
    }

    /** Forgets the result (the log's layout changed). */
    public synchronized void clear() {
        key = null;
        value = null;
    }

    /** How many results were worked out (for tests). */
    public synchronized int computations() {
        return computations;
    }
}
