package logmaster.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import logmaster.core.HitList;
import logmaster.core.LogDocument;
import logmaster.core.SearchQuery;
import logmaster.core.SearchSession;

/** One open file: the log view on top and the Find All results below. */
final class LogTab extends JPanel {

    /** Notifies the frame about status changes (for the status bar and toolbar). */
    interface Host {
        void tabStatusChanged(LogTab tab);
    }

    private final LogDocument doc;
    private final Host host;
    private final LogView main = new LogView();
    private final LogView results = new LogView();
    private final JLabel resultsHeader = new JLabel(" ");
    private final JPanel resultsPanel = new JPanel(new BorderLayout());
    private final JSplitPane split;
    private final int dividerSize;
    private final Runnable docUpdate;
    private final Runnable searchUpdate;
    private volatile SearchSession search;
    private long indexMillis = -1;
    private long searchMillis = -1;
    private String error;

    LogTab(Path path, Host host) throws IOException {
        super(new BorderLayout());
        this.host = host;
        this.doc = LogDocument.open(path);
        docUpdate = coalesced(this::onDocChanged);
        searchUpdate = coalesced(this::onSearchChanged);

        main.setSource(new LogView.RowSource() {
            @Override
            public int rowCount() {
                return (int) Math.min(doc.lineCount(), Integer.MAX_VALUE - 1);
            }

            @Override
            public long lineNumber(int row) {
                return row;
            }

            @Override
            public void texts(int first, int count, String[] out) {
                doc.readLines(first, count, out);
            }
        });
        results.setSource(resultsSource());
        results.setHighlightRules(java.util.List.of());
        results.addListener(new LogView.Listener() {
            @Override
            public void rowActivated(LogView view, int row) {
                showHit(row);
                main.focusView();
            }

            @Override
            public void selectionChanged(LogView view, int row) {
                showHit(row);
            }
        });
        main.addListener(new LogView.Listener() {
            @Override
            public void followChanged(LogView view, boolean follow) {
                host.tabStatusChanged(LogTab.this);
            }

            @Override
            public void selectionChanged(LogView view, int row) {
                host.tabStatusChanged(LogTab.this);
            }
        });

        resultsHeader.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        resultsHeader.setFont(resultsHeader.getFont().deriveFont(Font.BOLD));
        resultsPanel.add(resultsHeader, BorderLayout.NORTH);
        resultsPanel.add(results, BorderLayout.CENTER);
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, main, resultsPanel);
        split.setResizeWeight(0.65);
        split.setContinuousLayout(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        dividerSize = split.getDividerSize();
        resultsPanel.setVisible(false);
        split.setDividerSize(0); // no divider while there are no results

        doc.addListener(new LogDocument.Listener() {
            @Override
            public void linesAdded(LogDocument d) {
                docUpdate.run();
            }

            @Override
            public void reset(LogDocument d) {
                docUpdate.run();
            }

            @Override
            public void indexingFinished(LogDocument d, long millis) {
                indexMillis = millis;
                docUpdate.run();
            }

            @Override
            public void failed(LogDocument d, Exception e) {
                error = e.getMessage();
                docUpdate.run();
            }
        });
        doc.startFollowing(500);
    }

    /** Runs {@code r} on the EDT, at most once per pending request. */
    private static Runnable coalesced(Runnable r) {
        AtomicBoolean pending = new AtomicBoolean();
        return () -> {
            if (pending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    pending.set(false);
                    r.run();
                });
            }
        };
    }

    LogDocument document() {
        return doc;
    }

    LogView mainView() {
        return main;
    }

    LogView resultsView() {
        return results;
    }

    SearchSession search() {
        return search;
    }

    long indexMillis() {
        return indexMillis;
    }

    long searchMillis() {
        return searchMillis;
    }

    String error() {
        return error;
    }

    String title() {
        return doc.path().getFileName().toString();
    }

    private void onDocChanged() {
        main.rowsChanged();
        results.rowsChanged();
        host.tabStatusChanged(this);
    }

    private void onSearchChanged() {
        results.rowsChanged();
        updateResultsHeader();
        host.tabStatusChanged(this);
    }

    // ---------------------------------------------------------------- search

    /**
     * Starts a Find All. Results appear below, in line order, while searching; new lines are
     * searched as the file grows.
     *
     * @throws java.util.regex.PatternSyntaxException for an invalid regex
     */
    void findAll(SearchQuery q) {
        Pattern highlight = q.toPattern();
        SearchSession old = search;
        if (old != null) {
            old.close();
        }
        searchMillis = -1;
        SearchSession s = new SearchSession(doc, q);
        s.addListener(new SearchSession.Listener() {
            @Override
            public void updated(SearchSession ss) {
                searchUpdate.run();
            }

            @Override
            public void caughtUp(SearchSession ss, long millis) {
                if (searchMillis < 0 && !doc.isIndexing()) {
                    searchMillis = millis;
                }
                searchUpdate.run();
            }

            @Override
            public void failed(SearchSession ss, Exception e) {
                error = "Search failed: " + e.getMessage();
                searchUpdate.run();
            }
        });
        search = s;
        main.setMatchPattern(highlight);
        results.setMatchPattern(highlight);
        results.setSource(resultsSource());
        if (!resultsPanel.isVisible()) {
            resultsPanel.setVisible(true);
            split.setDividerSize(dividerSize);
            split.setDividerLocation(0.65);
        }
        updateResultsHeader();
        s.start();
    }

    /** Rows of the results view: one per matching line. */
    private LogView.RowSource resultsSource() {
        return new LogView.RowSource() {
            @Override
            public int rowCount() {
                SearchSession s = search;
                return s == null ? 0 : s.hits().size();
            }

            @Override
            public long lineNumber(int row) {
                SearchSession s = search;
                return s == null || row >= s.hits().size() ? row : s.hits().get(row);
            }

            @Override
            public void texts(int first, int count, String[] out) {
                SearchSession s = search;
                HitList hits = s == null ? null : s.hits();
                for (int i = 0; i < count; i++) {
                    int row = first + i;
                    out[i] = hits == null || row >= hits.size() ? null : doc.line(hits.get(row));
                }
            }
        };
    }

    /** Stops the running search (results so far are kept). */
    void stopSearch() {
        SearchSession s = search;
        if (s != null) {
            s.close();
            updateResultsHeader();
        }
    }

    void closeResults() {
        stopSearch();
        search = null;
        main.setMatchPattern(null);
        results.setMatchPattern(null);
        results.rowsChanged();
        resultsPanel.setVisible(false);
        split.setDividerSize(0);
    }

    /** Jumps to the next (or previous) hit after the selected line. Returns false if none. */
    boolean findNext(boolean forward) {
        SearchSession s = search;
        if (s == null || s.hits().size() == 0) {
            return false;
        }
        HitList hits = s.hits();
        long cur = main.selectedRow() >= 0 ? main.selectedRow() : main.topRow() - 1;
        int idx;
        if (forward) {
            idx = hits.firstAtOrAfter(cur + 1);
            if (idx >= hits.size()) {
                idx = 0; // wrap
            }
        } else {
            idx = hits.firstAtOrAfter(cur) - 1;
            if (idx < 0) {
                idx = hits.size() - 1;
            }
        }
        results.goToRow(idx, false);
        showHit(idx);
        return true;
    }

    private void showHit(int row) {
        SearchSession s = search;
        if (s != null && row >= 0 && row < s.hits().size()) {
            main.goToRow((int) s.hits().get(row), true);
            host.tabStatusChanged(this);
        }
    }

    private void updateResultsHeader() {
        SearchSession s = search;
        if (s == null) {
            resultsHeader.setText(" ");
            return;
        }
        SearchQuery q = s.query();
        StringBuilder sb = new StringBuilder("Find All  “").append(q.text()).append('”');
        if (q.regex()) {
            sb.append("  (regex)");
        }
        if (q.matchCase()) {
            sb.append("  (match case)");
        }
        if (q.wholeWord()) {
            sb.append("  (whole word)");
        }
        sb.append("   —   ").append(String.format("%,d", s.hits().size())).append(" lines");
        if (doc.isIndexing()) {
            sb.append("   searching (file still indexing)…");
        } else if (s.isRunning()) {
            sb.append(String.format("   searching %d%%", (int) (s.progress() * 100)));
        } else if (searchMillis >= 0) {
            sb.append(String.format("   in %,d ms", searchMillis));
        }
        if (s.isLimitReached()) {
            sb.append("   (limit reached)");
        }
        resultsHeader.setText(sb.toString());
    }

    void close() {
        SearchSession s = search;
        if (s != null) {
            s.close();
        }
        doc.close();
    }
}
