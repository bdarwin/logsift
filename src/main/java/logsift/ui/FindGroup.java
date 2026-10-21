package logsift.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import logsift.core.HitList;
import logsift.core.LineExporter;
import logsift.core.LogDocument;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;

/**
 * One Find All in the results window: its live search, and the lines it shows. A search within
 * results ("ERROR › timeout") shows the lines of its parent that its own search also finds; it
 * searches the whole file (fast) and merges with the parent's lines, so it stays up to date.
 */
final class FindGroup {
    final SearchQuery query;
    final FindGroup parent;
    final int colorIndex;
    private final LogDocument doc;
    private final SearchSession session;
    private volatile long[] rows = new long[0]; // refined groups: the lines shown
    private volatile int rowCount;
    volatile long millis = -1;
    boolean expanded = true;

    /** @throws java.util.regex.PatternSyntaxException for an invalid regex */
    FindGroup(LogDocument doc, SearchQuery query, FindGroup parent, int colorIndex, Runnable onUpdate) {
        query.toPattern();
        this.doc = doc;
        this.query = query;
        this.parent = parent;
        this.colorIndex = colorIndex;
        this.session = new SearchSession(doc, query);
        session.addListener(new SearchSession.Listener() {
            @Override
            public void updated(SearchSession s) {
                onUpdate.run();
            }

            @Override
            public void caughtUp(SearchSession s, long ms) {
                if (millis < 0 && !doc.isIndexing()) {
                    millis = ms;
                }
                onUpdate.run();
            }
        });
        session.start();
    }

    SearchSession session() {
        return session;
    }

    int depth() {
        return parent == null ? 0 : parent.depth() + 1;
    }

    /** "ERROR" or, within results, "ERROR › timeout". */
    String title() {
        return parent == null ? query.text() : parent.title() + " › " + query.text();
    }

    int count() {
        return parent == null ? session.hits().size() : rowCount;
    }

    long lineAt(int i) {
        return parent == null ? session.hits().get(i) : rows[i];
    }

    int firstAtOrAfter(long line) {
        if (parent == null) {
            return session.hits().firstAtOrAfter(line);
        }
        int i = java.util.Arrays.binarySearch(rows, 0, rowCount, line);
        return i >= 0 ? i : -i - 1;
    }

    boolean isRunning() {
        return session.isRunning() || (parent != null && parent.isRunning());
    }

    boolean awaitIdle(long ms) {
        return (parent == null || parent.awaitIdle(ms)) && session.awaitIdle(ms);
    }

    /** {highlight light, highlight dark, mark}. */
    Color[] colors() {
        return Theme.SEARCH_COLORS[colorIndex % Theme.SEARCH_COLORS.length];
    }

    LogView.Highlight highlight() {
        Color[] c = colors();
        return new LogView.Highlight(query.toPattern(), c[0], c[1]);
    }

    /** For a search within results: the parent's lines that this search also finds. */
    void refresh() {
        if (parent == null) {
            return;
        }
        int pn = parent.count();
        HitList mine = session.hits();
        int mn = mine.size();
        long[] out = new long[Math.min(pn, mn)];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < pn && j < mn) {
            long a = parent.lineAt(i);
            long b = mine.get(j);
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
        rows = out;
        rowCount = k;
    }

    /** The header line in the results window. */
    String header() {
        StringBuilder sb = new StringBuilder("   ".repeat(depth()));
        sb.append(expanded ? "▾ " : "▸ ").append('“').append(title()).append('”');
        List<String> opts = new ArrayList<>();
        if (query.regex()) {
            opts.add("regex");
        }
        if (query.matchCase()) {
            opts.add("match case");
        }
        if (query.wholeWord()) {
            opts.add("whole word");
        }
        if (!opts.isEmpty()) {
            sb.append("  (").append(String.join(", ", opts)).append(')');
        }
        sb.append(String.format("  —  %,d lines", count()));
        if (doc.isIndexing() || isRunning()) {
            sb.append(String.format("  searching %d%%", (int) (session.progress() * 100)));
        } else if (millis >= 0) {
            sb.append(String.format("  in %,d ms", millis));
        }
        if (session.isLimitReached()) {
            sb.append("  (limit reached)");
        }
        return sb.toString();
    }

    OverviewBar.Marks marks() {
        return new OverviewBar.Marks() {
            @Override
            public long firstAtOrAfter(long line) {
                int i = FindGroup.this.firstAtOrAfter(line);
                return i < count() ? lineAt(i) : -1;
            }

            @Override
            public long lastBefore(long line) {
                int i = FindGroup.this.firstAtOrAfter(line) - 1;
                return i >= 0 && i < count() ? lineAt(i) : -1;
            }
        };
    }

    LineExporter.Runs runs() {
        return out -> {
            int n = count();
            int i = 0;
            while (i < n) {
                long a = lineAt(i);
                int j = i;
                while (j + 1 < n && lineAt(j + 1) == lineAt(j) + 1) {
                    j++;
                }
                out.run(a, lineAt(j) + 1);
                i = j + 1;
            }
        };
    }

    void close() {
        session.close();
    }
}
