package logsift.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import logsift.core.FilterResult.Lines;

/**
 * Runs several filters over one document and combines them with a {@link Formula}.
 *
 * <p>Each filter is a live {@link SearchSession} (fast byte search, follows file growth). Their
 * sorted hit lists are merged on a background thread into a {@link FilterResult}. Merging is
 * linear in the number of hits, so even millions of hits combine in milliseconds.
 */
public final class FilterEngine implements AutoCloseable {

    /** One active filter: its letter and what it searches for (in the whole line, or in a column). */
    public record Spec(char id, SearchQuery query, ColumnFilter column) {
        public Spec(char id, SearchQuery query) {
            this(id, query, null);
        }
    }

    /** Called on a background thread whenever {@link #result()} changed. */
    public interface Listener {
        void resultChanged(FilterEngine engine);
    }

    private final LogDocument doc;
    private final Map<Character, SearchSession> sessions = new LinkedHashMap<>();
    private final Map<Character, Spec> specs = new HashMap<>(); // what each session searches for
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService combiner =
            Executors.newSingleThreadExecutor(LogDocument.daemon("logsift-filter"));
    private final AtomicBoolean queued = new AtomicBoolean();
    private final LogDocument.Listener docListener;
    private Formula formula;      // guarded by this
    private SearchSession continuations; // guarded by this; null = every line is an entry
    private EntryFormat entryFormat;     // guarded by this
    private int context;                 // guarded by this
    private long windowStart;            // guarded by this; a time range as lines
    private long windowEnd = Long.MAX_VALUE;
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
        Map<Character, Spec> wanted = new LinkedHashMap<>();
        for (Spec s : specs) {
            wanted.put(s.id(), s);
        }
        if (formula != null) {
            for (char c : formula.refs()) {
                if (!wanted.containsKey(c)) {
                    throw new IllegalArgumentException("There is no active filter " + c);
                }
            }
        }
        for (Spec s : wanted.values()) {
            if (s.column() == null) {
                s.query().toPattern(); // validate before changing anything
            }
        }
        boolean changed = !java.util.Objects.equals(formula, this.formula);
        for (var it = sessions.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!this.specs.get(e.getKey()).equals(wanted.get(e.getKey()))) {
                e.getValue().close();
                it.remove();
                this.specs.remove(e.getKey());
                changed = true;
            }
        }
        for (var e : wanted.entrySet()) {
            if (!sessions.containsKey(e.getKey())) {
                Spec spec = e.getValue();
                SearchSession s = spec.column() == null ? new SearchSession(doc, spec.query())
                        : new SearchSession(doc, spec.query(), spec.column().matcher(doc));
                this.specs.put(e.getKey(), spec);
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

    /**
     * Keeps multi-line entries together: with a format, a match anywhere in an entry (for
     * example in a stack trace) selects the whole entry. Null treats every line separately.
     */
    public synchronized void setEntryFormat(EntryFormat format) {
        if (java.util.Objects.equals(format, entryFormat)) {
            return;
        }
        if (continuations != null) {
            continuations.close();
            continuations = null;
        }
        entryFormat = format;
        if (format != null) {
            continuations = SearchSession.continuationLines(doc, format);
            continuations.addListener(ss -> schedule());
            continuations.start();
        }
        version++;
        schedule();
    }

    public synchronized EntryFormat entryFormat() {
        return entryFormat;
    }

    /**
     * Limits everything to lines {@code [from, to)} (a time range); {@code to} may be
     * Long.MAX_VALUE for "up to the end, including new lines". (0, MAX) removes the limit.
     */
    public synchronized void setWindow(long from, long to) {
        if (from != windowStart || to != windowEnd) {
            windowStart = Math.max(0, from);
            windowEnd = Math.max(windowStart, to);
            version++;
            schedule();
        }
    }

    public synchronized boolean hasWindow() {
        return windowStart > 0 || windowEnd != Long.MAX_VALUE;
    }

    /** Shows {@code n} lines before and after each match (like grep -C). */
    public synchronized void setContext(int n) {
        if (n != context) {
            context = Math.max(0, n);
            version++;
            schedule();
        }
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
        return continuations != null && continuations.isRunning();
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
            if (continuations != null) {
                all.add(continuations); // stack-trace lines must be known too
            }
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

    private void combine() {
        if (closed) {
            return;
        }
        Formula f;
        int ver;
        Map<Character, SearchSession> snap;
        SearchSession cont;
        int ctx;
        long w0;
        long w1;
        synchronized (this) {
            f = formula;
            ver = version;
            snap = new HashMap<>(sessions);
            cont = continuations;
            ctx = context;
            w0 = windowStart;
            w1 = windowEnd;
        }
        int gen = doc.generation();
        boolean windowed = w0 > 0 || w1 != Long.MAX_VALUE;
        if (f == null) {
            if (!windowed) {
                result = FilterResult.none(ver, doc.lineCount());
            } else {
                // only a time range: every line of the window
                long u = doc.lineCount();
                result = new FilterResult(ver, true, u, w0, w1, FilterResult.Lines.NONE_EXCLUDED,
                        FilterResult.Lines.NONE_EXCLUDED, !doc.isIndexing());
            }
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
        long[] contArr = null;
        int contN = 0;
        if (cont != null) {
            contN = cont.hits().size();
            contArr = cont.hits().array();
            contN = Math.min(contN, contArr.length);
            long covered = cont.coveredGeneration() == gen ? cont.coveredLines() : 0;
            universe = Math.min(universe, covered);
            complete &= !cont.isRunning();
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
            Lines leaf = new Lines(arr, n, false);
            if (contArr != null) {
                int cn = java.util.Arrays.binarySearch(contArr, 0, contN, universe);
                cn = cn >= 0 ? cn : -cn - 1;
                leaf = wholeEntries(leaf, contArr, cn, universe);
            }
            leaves.put(c, leaf);
        }
        Lines matched = eval(f, leaves);
        if (arrays.containsValue(matched.a()) || matched.a() == contArr) {
            // never hand out a search's live array
            matched = new Lines(java.util.Arrays.copyOf(matched.a(), matched.n()), matched.n(),
                    matched.comp());
        }
        Lines shown = ctx > 0 ? withContext(matched, ctx, universe) : matched;
        if (windowed) {
            matched = window(matched, w0, w1);
            shown = window(shown, w0, w1);
        }
        result = new FilterResult(ver, true, universe, w0, w1, shown, matched, complete);
        fire();
    }

    /**
     * Widens matching lines to whole entries: a match anywhere in an entry (a line and the
     * continuation lines under it, listed sorted in {@code cont}) selects the whole entry.
     */
    static Lines wholeEntries(Lines x, long[] cont, int cn, long universe) {
        LongBuffer out = new LongBuffer(Math.max(16, x.n() + x.n() / 4));
        long lastEnd = -1;
        for (int i = 0; i < x.n(); i++) {
            long m = x.a()[i];
            if (m <= lastEnd) {
                continue; // already inside an entry we added
            }
            // walk back to the entry's first line
            long head = m;
            int k = java.util.Arrays.binarySearch(cont, 0, cn, m);
            if (k >= 0) {
                while (k > 0 && cont[k - 1] == cont[k] - 1) {
                    k--;
                }
                head = Math.max(0, cont[k] - 1);
            }
            // walk forward over the continuation lines after the head
            long end = head;
            int j = java.util.Arrays.binarySearch(cont, 0, cn, head + 1);
            if (j >= 0) {
                while (j + 1 < cn && cont[j + 1] == cont[j] + 1) {
                    j++;
                }
                end = cont[j];
            }
            end = Math.min(end, universe - 1);
            for (long l = Math.max(head, lastEnd + 1); l <= end; l++) {
                out.add(l);
            }
            lastEnd = Math.max(lastEnd, end);
        }
        return new Lines(out.a, out.n, false);
    }

    /** The part of a set within lines [w0, w1). */
    static Lines window(Lines x, long w0, long w1) {
        int a = java.util.Arrays.binarySearch(x.a(), 0, x.n(), w0);
        a = a >= 0 ? a : -a - 1;
        int b = java.util.Arrays.binarySearch(x.a(), 0, x.n(), w1);
        b = b >= 0 ? b : -b - 1;
        return new Lines(java.util.Arrays.copyOfRange(x.a(), a, Math.max(a, b)), Math.max(0, b - a),
                x.comp());
    }

    /** Adds {@code n} lines before and after every shown line (like grep -C). */
    static Lines withContext(Lines x, int n, long universe) {
        if (!x.comp()) {
            LongBuffer out = new LongBuffer(Math.max(16, x.n() * 3));
            long next = 0; // first line not yet added
            for (int i = 0; i < x.n(); i++) {
                long from = Math.max(next, x.a()[i] - n);
                long to = Math.min(universe - 1, x.a()[i] + n);
                for (long l = from; l <= to; l++) {
                    out.add(l);
                }
                next = Math.max(next, to + 1);
            }
            return new Lines(out.a, out.n, false);
        }
        // "all except e": runs of excluded lines shrink by n at each end that touches a shown line
        LongBuffer out = new LongBuffer(Math.max(16, x.n()));
        int i = 0;
        while (i < x.n()) {
            int j = i;
            while (j + 1 < x.n() && x.a()[j + 1] == x.a()[j] + 1) {
                j++;
            }
            long a = x.a()[i];
            long b = x.a()[j];
            long keepFrom = a == 0 ? a : a + n;
            long keepTo = b >= universe - 1 ? b : b - n;
            for (long l = keepFrom; l <= keepTo; l++) {
                out.add(l);
            }
            i = j + 1;
        }
        return new Lines(out.a, out.n, true);
    }

    private static final class LongBuffer {
        long[] a;
        int n;

        LongBuffer(int capacity) {
            a = new long[capacity];
        }

        void add(long v) {
            if (n == a.length) {
                a = java.util.Arrays.copyOf(a, Math.max(16, n * 2));
            }
            a[n++] = v;
        }
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
                yield new Lines(x.a(), x.n(), !x.comp());
            }
            case Formula.And x -> and(eval(x.a(), leaves), eval(x.b(), leaves));
            case Formula.Or x -> or(eval(x.a(), leaves), eval(x.b(), leaves));
        };
    }

    private static Lines and(Lines x, Lines y) {
        if (!x.comp() && !y.comp()) {
            return intersect(x, y, false);
        }
        if (!x.comp()) {
            return minus(x, y, false);
        }
        if (!y.comp()) {
            return minus(y, x, false);
        }
        return union(x, y, true); // NOT a AND NOT b = NOT (a OR b)
    }

    private static Lines or(Lines x, Lines y) {
        if (!x.comp() && !y.comp()) {
            return union(x, y, false);
        }
        if (!x.comp()) {
            return minus(y, x, true); // a OR NOT b = NOT (b AND NOT a)
        }
        if (!y.comp()) {
            return minus(x, y, true);
        }
        return intersect(x, y, true); // NOT a OR NOT b = NOT (a AND b)
    }

    private static Lines intersect(Lines x, Lines y, boolean comp) {
        long[] out = new long[Math.min(x.n(), y.n())];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < x.n() && j < y.n()) {
            long a = x.a()[i];
            long b = y.a()[j];
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
        long[] out = new long[x.n() + y.n()];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < x.n() || j < y.n()) {
            if (j >= y.n() || (i < x.n() && x.a()[i] < y.a()[j])) {
                out[k++] = x.a()[i++];
            } else if (i >= x.n() || y.a()[j] < x.a()[i]) {
                out[k++] = y.a()[j++];
            } else {
                out[k++] = x.a()[i++];
                j++;
            }
        }
        return new Lines(out, k, comp);
    }

    /** Lines of x (a plain list) that are not in the list of y. */
    private static Lines minus(Lines x, Lines y, boolean comp) {
        long[] out = new long[x.n()];
        int j = 0;
        int k = 0;
        for (int i = 0; i < x.n(); i++) {
            long a = x.a()[i];
            while (j < y.n() && y.a()[j] < a) {
                j++;
            }
            if (j >= y.n() || y.a()[j] != a) {
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
        specs.clear();
        if (continuations != null) {
            continuations.close();
        }
        combiner.shutdown();
    }
}
