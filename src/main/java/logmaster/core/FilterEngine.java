package logmaster.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs several filters over one document and combines them with a {@link Formula}.
 *
 * <p>Each filter is a live {@link SearchSession} (fast byte search, follows file growth). Their
 * sorted hit lists are merged on a background thread into a {@link FilterResult}. Merging is
 * linear in the number of hits, so even millions of hits combine in milliseconds.
 */
public final class FilterEngine implements AutoCloseable {

    /** One active filter: its letter and what it searches for. */
    public record Spec(char id, SearchQuery query) {}

    /** Called on a background thread whenever {@link #result()} changed. */
    public interface Listener {
        void resultChanged(FilterEngine engine);
    }

    private final LogDocument doc;
    private final Map<Character, SearchSession> sessions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService combiner =
            Executors.newSingleThreadExecutor(LogDocument.daemon("lm-filter"));
    private final AtomicBoolean queued = new AtomicBoolean();
    private final LogDocument.Listener docListener;
    private Formula formula;      // guarded by this
    private int version;          // guarded by this
    private volatile FilterResult result;
    private volatile boolean closed;
    private volatile Exception lastError;

    public FilterEngine(LogDocument doc) {
        this.doc = doc;
        this.result = FilterResult.none(0, doc.lineCount());
        this.docListener = new LogDocument.Listener() {
            @Override
            public void linesAdded(LogDocument d) {
                schedule();
            }

            @Override
            public void reset(LogDocument d) {
                schedule();
            }
        };
        doc.addListener(docListener);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /**
     * Sets the active filters and how they combine. Filters whose query did not change keep
     * their results; new or changed ones start searching.
     *
     * @param formula null to show every line
     * @throws IllegalArgumentException if the formula uses a letter that is not in {@code specs}
     * @throws java.util.regex.PatternSyntaxException if a query has an invalid regex
     */
    public synchronized void configure(List<Spec> specs, Formula formula) {
        Map<Character, SearchQuery> wanted = new LinkedHashMap<>();
        for (Spec s : specs) {
            wanted.put(s.id(), s.query());
        }
        if (formula != null) {
            for (char c : formula.refs()) {
                if (!wanted.containsKey(c)) {
                    throw new IllegalArgumentException("There is no active filter " + c);
                }
            }
        }
        for (SearchQuery q : wanted.values()) {
            q.toPattern(); // validate before changing anything
        }
        boolean changed = !java.util.Objects.equals(formula, this.formula);
        for (var it = sessions.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().query().equals(wanted.get(e.getKey()))) {
                e.getValue().close();
                it.remove();
                changed = true;
            }
        }
        for (var e : wanted.entrySet()) {
            if (!sessions.containsKey(e.getKey())) {
                SearchSession s = new SearchSession(doc, e.getValue());
                s.addListener(new SearchSession.Listener() {
                    @Override
                    public void updated(SearchSession ss) {
                        schedule();
                    }

                    @Override
                    public void failed(SearchSession ss, Exception ex) {
                        lastError = ex;
                        schedule();
                    }
                });
                sessions.put(e.getKey(), s);
                s.start();
                changed = true;
            }
        }
        this.formula = formula;
        if (changed) {
            version++;
        }
        schedule();
    }

    /** The live search behind filter {@code id}, or null. */
    public synchronized SearchSession session(char id) {
        return sessions.get(id);
    }

    /** Internal state, for test failure messages. */
    public synchronized String describe() {
        StringBuilder sb = new StringBuilder("error=" + lastError + " formula=" + formula + " version=" + version
                + " docGen=" + doc.generation() + " lines=" + doc.lineCount());
        sessions.forEach((c, s) -> sb.append(String.format(" %s[hits=%d covered=%d gen=%d running=%s]",
                c, s.hits().size(), s.coveredLines(), s.coveredGeneration(), s.isRunning())));
        return sb.toString();
    }

    public FilterResult result() {
        return result;
    }

    /** True while any filter is still searching or the result is being combined. */
    public synchronized boolean isRunning() {
        if (queued.get()) {
            return true;
        }
        for (SearchSession s : sessions.values()) {
            if (s.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /** Lowest search progress over all filters, 0..1. */
    public synchronized double progress() {
        double p = 1;
        for (SearchSession s : sessions.values()) {
            p = Math.min(p, s.progress());
        }
        return p;
    }

    /** Waits until every filter has caught up and the result is combined. */
    public boolean awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<SearchSession> all;
        synchronized (this) {
            all = new ArrayList<>(sessions.values());
        }
        for (SearchSession s : all) {
            if (!s.awaitIdle(Math.max(1, deadline - System.currentTimeMillis()))) {
                return false;
            }
        }
        schedule();
        return LogDocument.awaitExecutor(combiner,
                Math.max(1, deadline - System.currentTimeMillis()));
    }

    private void schedule() {
        if (!closed && queued.compareAndSet(false, true)) {
            combiner.execute(() -> {
                queued.set(false);
                combine();
            });
        }
    }

    /** A sorted list of lines, or all lines except that list. */
    private record Lines(long[] a, int n, boolean comp) {}

    private void combine() {
        if (closed) {
            return;
        }
        Formula f;
        int ver;
        Map<Character, SearchSession> snap;
        synchronized (this) {
            f = formula;
            ver = version;
            snap = new HashMap<>(sessions);
        }
        int gen = doc.generation();
        if (f == null) {
            result = FilterResult.none(ver, doc.lineCount());
            fire();
            return;
        }
        // Only lines every filter has searched can be decided.
        long universe = Long.MAX_VALUE;
        boolean complete = !doc.isIndexing();
        Map<Character, Lines> leaves = new HashMap<>();
        Map<Character, long[]> arrays = new HashMap<>();
        Map<Character, Integer> sizes = new HashMap<>();
        for (char c : f.refs()) {
            SearchSession s = snap.get(c);
            int n = s.hits().size();
            long[] arr = s.hits().array();
            long covered = s.coveredGeneration() == gen ? s.coveredLines() : 0;
            universe = Math.min(universe, covered);
            arrays.put(c, arr);
            sizes.put(c, Math.min(n, arr.length));
            complete &= !s.isRunning();
        }
        if (universe == Long.MAX_VALUE) {
            universe = 0;
        }
        if (complete) {
            complete = universe >= doc.lineCount();
        }
        for (char c : f.refs()) {
            long[] arr = arrays.get(c);
            int n = sizes.get(c);
            int cut = java.util.Arrays.binarySearch(arr, 0, n, universe);
            n = cut >= 0 ? cut : -cut - 1;
            leaves.put(c, new Lines(arr, n, false));
        }
        Lines out = eval(f, leaves);
        long[] list = out.a;
        if (arrays.containsValue(list)) {
            list = java.util.Arrays.copyOf(list, out.n); // never hand out a search's live array
        }
        result = new FilterResult(ver, true, universe, list, out.n, out.comp, complete);
        fire();
    }

    private void fire() {
        for (Listener l : listeners) {
            l.resultChanged(this);
        }
    }

    private static Lines eval(Formula f, Map<Character, Lines> leaves) {
        return switch (f) {
            case Formula.Ref r -> leaves.get(r.id());
            case Formula.Not n -> {
                Lines x = eval(n.a(), leaves);
                yield new Lines(x.a, x.n, !x.comp);
            }
            case Formula.And x -> and(eval(x.a(), leaves), eval(x.b(), leaves));
            case Formula.Or x -> or(eval(x.a(), leaves), eval(x.b(), leaves));
        };
    }

    private static Lines and(Lines x, Lines y) {
        if (!x.comp && !y.comp) {
            return intersect(x, y, false);
        }
        if (!x.comp) {
            return minus(x, y, false);
        }
        if (!y.comp) {
            return minus(y, x, false);
        }
        return union(x, y, true); // NOT a AND NOT b = NOT (a OR b)
    }

    private static Lines or(Lines x, Lines y) {
        if (!x.comp && !y.comp) {
            return union(x, y, false);
        }
        if (!x.comp) {
            return minus(y, x, true); // a OR NOT b = NOT (b AND NOT a)
        }
        if (!y.comp) {
            return minus(x, y, true);
        }
        return intersect(x, y, true); // NOT a OR NOT b = NOT (a AND b)
    }

    private static Lines intersect(Lines x, Lines y, boolean comp) {
        long[] out = new long[Math.min(x.n, y.n)];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < x.n && j < y.n) {
            long a = x.a[i];
            long b = y.a[j];
            if (a == b) {
                out[k++] = a;
                i++;
                j++;
            } else if (a < b) {
                i++;
            } else {
                j++;
            }
        }
        return new Lines(out, k, comp);
    }

    private static Lines union(Lines x, Lines y, boolean comp) {
        long[] out = new long[x.n + y.n];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < x.n || j < y.n) {
            if (j >= y.n || (i < x.n && x.a[i] < y.a[j])) {
                out[k++] = x.a[i++];
            } else if (i >= x.n || y.a[j] < x.a[i]) {
                out[k++] = y.a[j++];
            } else {
                out[k++] = x.a[i++];
                j++;
            }
        }
        return new Lines(out, k, comp);
    }

    /** Lines of x (a plain list) that are not in the list of y. */
    private static Lines minus(Lines x, Lines y, boolean comp) {
        long[] out = new long[x.n];
        int j = 0;
        int k = 0;
        for (int i = 0; i < x.n; i++) {
            long a = x.a[i];
            while (j < y.n && y.a[j] < a) {
                j++;
            }
            if (j >= y.n || y.a[j] != a) {
                out[k++] = a;
            }
        }
        return new Lines(out, k, comp);
    }

    @Override
    public synchronized void close() {
        closed = true;
        doc.removeListener(docListener);
        sessions.values().forEach(SearchSession::close);
        sessions.clear();
        combiner.shutdown();
    }
}
