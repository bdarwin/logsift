package logmaster.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import logmaster.core.FilterEngine;
import logmaster.core.FilterResult;
import logmaster.core.Formula;
import logmaster.core.HitList;
import logmaster.core.LogDocument;
import logmaster.core.SearchQuery;
import logmaster.core.SearchSession;

/**
 * One open file: the log view on top; below it the filters and the Find All results.
 *
 * <p>The log view shows either every line (lines the filters remove are grayed) or only the
 * lines that pass the filters. Switching keeps the current line in place.
 */
final class LogTab extends JPanel implements FilterPanel.Listener {

    /** Notifies the frame about status changes (for the status bar and toolbar). */
    interface Host {
        void tabStatusChanged(LogTab tab);

        /** New lines matched a filter with an alert: tell the user. */
        default void alert(LogTab tab, String message, long line) {}

        /** Runs Find All in this tab through the toolbar (so the Find box shows the query). */
        default void runFindAll(SearchQuery q) {}
    }

    private final LogDocument doc;
    private final Path path;
    private final String encodingLabel;
    private String remoteLabel;          // "user@host:/path" for remote logs
    private volatile String remoteStatus;
    private final Host host;
    private final LogView main = new LogView();
    private final LogView results = new LogView();
    private final JLabel resultsHeader = new JLabel(" ");
    private final JPanel resultsPanel = new JPanel(new BorderLayout());
    private final FilterPanel filterPanel = new FilterPanel(this);
    private final JTabbedPane bottom = new JTabbedPane();
    private final JSplitPane split;
    private final int dividerSize;
    private final FilterEngine engine;
    private final FilterEngine errorLines;   // for the highlight bar and F8
    private final FilterEngine warningLines;
    private final OverviewBar.Marks errorMarks;
    private final OverviewBar.Marks warningMarks;
    private final Runnable marksUpdate;
    private final LogView.RowSource allLines;
    private final LogView.RowSource matchingLines;
    private final Runnable docUpdate;
    private final Runnable searchUpdate;
    private final Runnable filterUpdate;
    private volatile SearchSession search;
    private volatile FilterResult filterResult; // written on the EDT
    private List<String> sourceLabels;          // merged logs: the label of each source
    private final List<Runnable> closeHooks = new ArrayList<>();
    private final MessagesPanel messages;
    private final Bookmarks bookmarks;
    private final BookmarksPanel bookmarksPanel;
    private final DetailsPanel details = new DetailsPanel();
    private int bookmarkGeneration;
    /** Per filter with an alert: {line count when switched on, hits already seen, last alert ms, unsent count, first unsent line}. */
    private final java.util.Map<FilterDef, long[]> alerts = new java.util.HashMap<>();
    private int alertGeneration;
    private Formula appliedFormula;
    private boolean matchingOnly;
    private long pendingLine = -1; // line to show once indexing (and filters) reach it
    private boolean pendingExact;  // true: show exactly that line (may leave "Matching only")
    private volatile logmaster.core.EntryFormat entryFormat; // detected; null = none
    private volatile logmaster.core.TimeFormat timeFormat;   // detected; null = none
    private volatile logmaster.core.TimeIndex timeIndex;
    private volatile logmaster.core.LogFormat logFormat; // columns; null = none found
    private boolean columnsWanted;
    private final TimelineBar timeline;
    private long[] timeRange;                       // selected {from, to} times, or null
    private boolean timelineWanted = true;
    private int detectedGeneration = -1;
    private long indexMillis = -1;
    private long searchMillis = -1;
    private String error;

    LogTab(Path path, Host host) throws IOException {
        this(path, path, java.nio.charset.StandardCharsets.UTF_8, null, host);
    }

    /**
     * @param readPath the file to read (a UTF-8 copy for UTF-16 logs)
     * @param path the file the user opened (title, recent files, bookmarks, memory)
     * @param charset the text encoding of {@code readPath}
     * @param encodingLabel what to show as the encoding (null: the charset's name)
     */
    LogTab(Path readPath, Path path, java.nio.charset.Charset charset, String encodingLabel, Host host)
            throws IOException {
        super(new BorderLayout());
        this.host = host;
        this.path = path.toAbsolutePath().normalize();
        this.encodingLabel = encodingLabel != null ? encodingLabel : logmaster.core.Encoding.label(charset);
        this.doc = LogDocument.open(readPath, logmaster.core.LineIndex.DEFAULT_CHUNK_SIZE, charset);
        this.engine = new FilterEngine(doc);
        this.filterResult = engine.result();
        docUpdate = coalesced(this::onDocChanged);
        searchUpdate = coalesced(this::onSearchChanged);
        filterUpdate = coalesced(this::onFilterResult);
        engine.addListener(e -> filterUpdate.run());
        timeline = new TimelineBar(timelineModel());
        timeline.setVisible(false);
        marksUpdate = coalesced(() -> {
            main.overviewChanged();
            results.overviewChanged();
            timeline.refresh();
        });
        errorLines = levelSearch("ERROR", "FATAL", "SEVERE", "CRITICAL");
        warningLines = levelSearch("WARN", "WARNING");
        errorMarks = OverviewBar.of(errorLines::result);
        warningMarks = OverviewBar.of(warningLines::result);

        allLines = new LogView.RowSource() {
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

            @Override
            public boolean dimmed(int row) {
                FilterResult r = filterResult;
                return r.isFiltering() && !r.isMatch(row);
            }

            @Override
            public java.awt.Color tag(int row) {
                return lineSourceColor(row);
            }

            @Override
            public java.awt.Color marker(int row) {
                return bookmarks.contains(row) ? Theme.marker() : null;
            }
        };
        matchingLines = new LogView.RowSource() {
            @Override
            public int rowCount() {
                return (int) Math.min(filterResult.count(), Integer.MAX_VALUE - 1);
            }

            @Override
            public long lineNumber(int row) {
                return filterResult.lineAt(row);
            }

            @Override
            public boolean dimmed(int row) {
                FilterResult r = filterResult;
                return row < r.count() && !r.isMatch(r.lineAt(row)); // a context line
            }

            @Override
            public java.awt.Color tag(int row) {
                FilterResult r = filterResult;
                return row < r.count() ? lineSourceColor(r.lineAt(row)) : null;
            }

            @Override
            public java.awt.Color marker(int row) {
                FilterResult r = filterResult;
                return row < r.count() && bookmarks.contains(r.lineAt(row)) ? Theme.marker() : null;
            }

            @Override
            public boolean gapBefore(int row) {
                FilterResult r = filterResult;
                return filterPanel.contextLines() > 0 && row < r.count()
                        && r.lineAt(row) != r.lineAt(row - 1) + 1;
            }

            @Override
            public void texts(int first, int count, String[] out) {
                FilterResult r = filterResult;
                int n = (int) Math.min(count, r.count() - first);
                // read runs of consecutive lines together
                int i = 0;
                while (i < n) {
                    long start = r.lineAt(first + i);
                    int run = 1;
                    while (i + run < n && r.lineAt(first + i + run) == start + run) {
                        run++;
                    }
                    String[] buf = new String[run];
                    doc.readLines(start, run, buf);
                    System.arraycopy(buf, 0, out, i, run);
                    i += run;
                }
                for (; i < count; i++) {
                    out[i] = null;
                }
            }
        };
        main.setSource(allLines);
        results.setSource(resultsSource());
        results.setHighlightRules(List.of());
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
                updateDetails();
            }
        });
        main.setPopupExtender(this::extendPopup);
        main.setColumnMenu((col, e) -> {
            JPopupMenu m = new JPopupMenu();
            JMenuItem count = new JMenuItem("Count Values in " + col.substring(0, 1).toUpperCase()
                    + col.substring(1) + "…");
            count.addActionListener(a -> countValues(col));
            m.add(count);
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        main.setOverview(overviewModel());
        results.setPopupExtender(this::extendPopup);

        resultsHeader.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        resultsHeader.setFont(resultsHeader.getFont().deriveFont(Font.BOLD));
        resultsPanel.add(resultsHeader, BorderLayout.NORTH);
        resultsPanel.add(results, BorderLayout.CENTER);

        messages = new MessagesPanel(messagesHost());
        bookmarks = new Bookmarks(path);
        bookmarkGeneration = doc.generation();
        bookmarksPanel = new BookmarksPanel(bookmarks, this::timeText, doc::line, this::showLine);
        bookmarks.addListener(() -> {
            main.rowsChanged();
            main.overviewChanged();
            bottom.setTitleAt(bottom.indexOfComponent(bookmarksPanel),
                    bookmarks.size() == 0 ? "Bookmarks" : "Bookmarks (" + bookmarks.size() + ")");
        });
        bottom.addTab("Filters", filterPanel);
        bottom.addTab("Find results", resultsPanel);
        bottom.addTab("Messages", messages);
        bottom.addTab(bookmarks.size() == 0 ? "Bookmarks" : "Bookmarks (" + bookmarks.size() + ")",
                bookmarksPanel);
        bottom.addTab("Details", details);
        bottom.addChangeListener(e -> {
            updateDetails();
            if (bottom.getSelectedComponent() == messages && messages.result() == null
                    && !messages.isRunning()) {
                messages.start(); // analyze the first time the tab is opened
            }
        });
        bottom.setPreferredSize(new Dimension(400, 210));
        bottom.putClientProperty("JTabbedPane.tabHeight", 26);
        JPanel top = new JPanel(new BorderLayout());
        top.add(timeline, BorderLayout.NORTH);
        top.add(main, BorderLayout.CENTER);
        main.addPaintListener(timeline::repaint);
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(1.0); // the log gets the extra space
        split.setContinuousLayout(true);
        split.setBorder(null);
        dividerSize = split.getDividerSize();
        add(split, BorderLayout.CENTER);

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
        updateFilterStatus();
        // A small file may be fully indexed before the listener above was added, so look at it
        // once now too (detects entries and timestamps).
        docUpdate.run();
    }

    /**
     * Finds how entries start (date, time, level…) from the first lines of the file, once
     * enough of it is indexed, and again after truncation or rotation.
     */
    private void detectEntryFormat() {
        int gen = doc.generation();
        long n = doc.lineCount();
        if (gen == detectedGeneration || (n < 200 && doc.isIndexing())) {
            return;
        }
        if (n == 0) {
            return; // an empty file that is still growing: decide when lines arrive
        }
        detectedGeneration = gen;
        String[] sample = new String[(int) Math.min(n, 2000)];
        doc.readLines(0, sample.length, sample);
        List<String> lines = java.util.Arrays.asList(sample);
        entryFormat = logmaster.core.EntryFormat.detect(lines);
        logFormat = logmaster.core.LogFormat.detect(lines);
        applyColumns();
        filterPanel.setEntryFormatInfo(entryFormat == null ? null : entryFormat.name());
        applyEntryOptions();

        // timestamps: the timeline, time ranges, Go to Time
        if (timeIndex != null) {
            timeIndex.close();
            timeIndex = null;
        }
        int year = java.time.Year.now().getValue();
        try {
            year = java.time.Instant.ofEpochMilli(java.nio.file.Files.getLastModifiedTime(doc.path())
                    .toMillis()).atZone(java.time.ZoneId.systemDefault()).getYear();
        } catch (IOException e) {
            // keep this year
        }
        timeFormat = logmaster.core.TimeFormat.detect(lines, year);
        if (timeFormat != null) {
            timeIndex = new logmaster.core.TimeIndex(doc, timeFormat);
            timeIndex.addListener(ti -> marksUpdate.run());
            timeIndex.start();
        }
        timeline.setVisible(timeFormat != null && timelineWanted);
        timeline.refresh();
        revalidate();
    }

    // ---------------------------------------------------------------- time

    logmaster.core.TimeIndex timeIndex() {
        return timeIndex;
    }

    TimelineBar timeline() {
        return timeline;
    }

    void setTimelineVisible(boolean on) {
        timelineWanted = on;
        timeline.setVisible(on && timeFormat != null);
        revalidate();
    }

    /** First line with a timestamp at or after {@code t} (logs are mostly in time order). */
    long lineAtTime(long t) {
        logmaster.core.TimeIndex ti = timeIndex;
        if (ti == null) {
            return 0;
        }
        logmaster.core.TimeIndex.Samples s = ti.samples();
        long from = s.lineBefore(t);
        long n = doc.lineCount();
        String[] buf = new String[256];
        for (long first = from; first < n && first < from + 4 * logmaster.core.LineIndex.SAMPLE + 512;
             first += buf.length) {
            int k = (int) Math.min(buf.length, n - first);
            doc.readLines(first, k, buf);
            for (int i = 0; i < k; i++) {
                if (buf[i] != null) {
                    long lt = timeFormat.parse(buf[i]);
                    if (lt != logmaster.core.TimeFormat.NONE && lt >= t) {
                        return first + i;
                    }
                }
            }
        }
        // past the last timestamp
        int last = s.sampleAtOrBefore(Long.MAX_VALUE);
        return last >= 0 && s.times()[last] < t ? n : from;
    }

    /** Time of the current line (or the nearest line above with a timestamp), or NONE. */
    long currentLineTime() {
        long line = currentLine();
        if (timeFormat == null || line < 0) {
            return logmaster.core.TimeFormat.NONE;
        }
        for (long l = line; l >= 0 && l > line - 200; l--) {
            String text = doc.line(l);
            long t = text == null ? logmaster.core.TimeFormat.NONE : timeFormat.parse(text);
            if (t != logmaster.core.TimeFormat.NONE) {
                return t;
            }
        }
        return logmaster.core.TimeFormat.NONE;
    }

    void goToTime(long t) {
        long line = lineAtTime(t);
        showLine(Math.min(line, Math.max(0, doc.lineCount() - 1)));
        main.focusView();
    }

    /** Shows only lines logged between the two times (inclusive), as a filter. */
    void selectTimeRange(long from, long to) {
        logmaster.core.TimeIndex.Samples s = timeIndex == null ? null : timeIndex.samples();
        if (s == null || s.n() == 0) {
            return;
        }
        long first = lineAtTime(from);
        long end = to >= s.maxTime() ? Long.MAX_VALUE : lineAtTime(to + 1);
        timeRange = new long[] {from, to};
        engine.setWindow(first, end);
        setMatchingOnly(true);
        timeline.repaint();
        updateFilterStatus();
    }

    void clearTimeRange() {
        timeRange = null;
        engine.setWindow(0, Long.MAX_VALUE);
        timeline.repaint();
        updateFilterStatus();
    }

    long[] timeRange() {
        return timeRange;
    }

    private TimelineBar.Model timelineModel() {
        return new TimelineBar.Model() {
            @Override
            public logmaster.core.TimeIndex.Samples samples() {
                logmaster.core.TimeIndex ti = timeIndex;
                return ti == null ? null : ti.samples();
            }

            @Override
            public long lineCount() {
                return doc.lineCount();
            }

            @Override
            public FilterResult errors() {
                return errorLines.result();
            }

            @Override
            public FilterResult warnings() {
                return warningLines.result();
            }

            @Override
            public long[] viewLines() {
                int[] rows = main.shownRows();
                if (rows == null) {
                    return null;
                }
                LogView.RowSource src = main.source();
                return new long[] {src.lineNumber(rows[0]), src.lineNumber(rows[1])};
            }

            @Override
            public long[] timeRange() {
                return timeRange;
            }

            @Override
            public void goToTime(long time) {
                LogTab.this.goToTime(time);
            }

            @Override
            public void selectTimeRange(long from, long to) {
                LogTab.this.selectTimeRange(from, to);
            }

            @Override
            public void clearTimeRange() {
                LogTab.this.clearTimeRange();
            }
        };
    }

    private void applyEntryOptions() {
        engine.setEntryFormat(filterPanel.keepEntries() ? entryFormat : null);
        engine.setContext(filterPanel.contextLines());
    }

    /** Lines containing any of these words (whole word, exact case), kept up to date. */
    private FilterEngine levelSearch(String... words) {
        FilterEngine e = new FilterEngine(doc);
        List<FilterEngine.Spec> specs = new ArrayList<>();
        List<Character> ids = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            char id = (char) ('A' + i);
            specs.add(new FilterEngine.Spec(id, new SearchQuery(words[i], true, false, true)));
            ids.add(id);
        }
        e.configure(specs, Formula.auto(ids, List.of()));
        e.addListener(x -> marksUpdate.run());
        return e;
    }

    private OverviewBar.Model overviewModel() {
        return new OverviewBar.Model() {
            @Override
            public List<List<OverviewBar.MarkSet>> lanes() {
                List<OverviewBar.MarkSet> levels = List.of(
                        new OverviewBar.MarkSet("Error", Theme.errorMark(), errorMarks),
                        new OverviewBar.MarkSet("Warning", Theme.warningMark(), warningMarks));
                List<OverviewBar.MarkSet> other = new ArrayList<>();
                if (bookmarks.size() > 0) {
                    other.add(new OverviewBar.MarkSet("Bookmark", Theme.marker(), bookmarkMarks()));
                }
                SearchSession s = search;
                if (s != null) {
                    other.add(new OverviewBar.MarkSet("Find “" + s.query().text() + "”",
                            Theme.findMark(), OverviewBar.of(s.hits())));
                }
                for (FilterDef d : filterPanel.filters()) {
                    SearchSession fs = d.isActive() && d.color() != null ? engine.session(d.id) : null;
                    if (fs != null) {
                        other.add(new OverviewBar.MarkSet("Filter " + d.id + " “" + d.text + "”",
                                d.color().mark(), OverviewBar.of(fs.hits())));
                    }
                }
                return List.of(levels, other);
            }

            @Override
            public long lineOfRow(long row) {
                return matchingOnly ? filterResult.lineAt(row) : row;
            }

            @Override
            public long lineLimit() {
                return doc.lineCount();
            }

            @Override
            public boolean visible(long line) {
                return !matchingOnly || filterResult.contains(line);
            }

            @Override
            public String lineText(long line) {
                return doc.line(line);
            }

            @Override
            public void goToLine(long line) {
                showLine(line);
            }
        };
    }

    Bookmarks bookmarks() {
        return bookmarks;
    }

    /** Bookmarks or un-bookmarks the current line. */
    void toggleBookmark() {
        long line = currentLine();
        if (line >= 0) {
            bookmarks.toggle(line);
        }
    }

    /** Goes to the next (or previous) bookmark, wrapping. Returns false if there are none. */
    boolean nextBookmark(boolean forward) {
        long line = bookmarks.next(Math.max(-1, currentLine()), forward);
        if (line < 0) {
            return false;
        }
        showLine(line);
        return true;
    }

    void showBookmarksTab() {
        if (!bottom.isVisible()) {
            toggleBottom();
        }
        bottom.setSelectedComponent(bookmarksPanel);
    }

    private OverviewBar.Marks bookmarkMarks() {
        return new OverviewBar.Marks() {
            @Override
            public long firstAtOrAfter(long line) {
                Long l = bookmarks.all().ceilingKey(line);
                return l == null ? -1 : l;
            }

            @Override
            public long lastBefore(long line) {
                Long l = bookmarks.all().lowerKey(line);
                return l == null ? -1 : l;
            }
        };
    }

    /** "2026-10-21 10:02:13.123" of a line, or "" without timestamps. */
    private String timeText(long line) {
        logmaster.core.TimeFormat tf = timeFormat;
        String text = tf == null ? null : doc.line(line);
        long t = text == null ? logmaster.core.TimeFormat.NONE : tf.parse(text);
        return t == logmaster.core.TimeFormat.NONE ? "" : TimelineBar.formatFull(t);
    }

    /** Jumps to the next (or previous) error line, wrapping around. Returns false if none. */
    boolean nextError(boolean forward) {
        long cur = currentLine();
        long line;
        if (forward) {
            line = errorMarks.firstAtOrAfter(cur + 1);
            if (line < 0) {
                line = errorMarks.firstAtOrAfter(0);
            }
        } else {
            line = errorMarks.lastBefore(cur < 0 ? Long.MAX_VALUE : cur);
            if (line < 0) {
                line = errorMarks.lastBefore(Long.MAX_VALUE);
            }
        }
        if (line < 0) {
            return false;
        }
        showLine(line);
        return true;
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

    /** The file the user opened (for a UTF-16 log, not the UTF-8 copy that is read). */
    Path path() {
        return path;
    }

    String encodingLabel() {
        return encodingLabel;
    }

    LogView mainView() {
        return main;
    }

    LogView resultsView() {
        return results;
    }

    FilterPanel filterPanel() {
        return filterPanel;
    }

    FilterEngine filterEngine() {
        return engine;
    }

    FilterEngine errorLines() {
        return errorLines;
    }

    SearchSession search() {
        return search;
    }

    long indexMillis() {
        // the document remembers it, in case indexing finished before we were listening
        return indexMillis >= 0 ? indexMillis : doc.lastIndexMillis();
    }

    long searchMillis() {
        return searchMillis;
    }

    String error() {
        return error;
    }

    /** Marks this tab as a remote log (shown by its address, not remembered locally). */
    void setRemote(String label) {
        this.remoteLabel = label;
    }

    String remoteLabel() {
        return remoteLabel;
    }

    void setRemoteStatus(String status) {
        this.remoteStatus = status;
        host.tabStatusChanged(this);
    }

    String remoteStatus() {
        return remoteStatus;
    }

    String title() {
        if (remoteLabel != null) {
            int colon = remoteLabel.indexOf(':');
            String file = remoteLabel.substring(remoteLabel.lastIndexOf('/') + 1);
            String hostPart = remoteLabel.substring(remoteLabel.indexOf('@') + 1, colon);
            return file + " @ " + hostPart;
        }
        return path.getFileName().toString();
    }

    private void onDocChanged() {
        if (doc.generation() != bookmarkGeneration) {
            bookmarkGeneration = doc.generation();
            bookmarks.clear(); // truncated or rotated: the bookmarked lines are gone
        }
        detectEntryFormat();
        main.rowsChanged();
        tryPendingLine();
        results.rowsChanged();
        host.tabStatusChanged(this);
    }

    private void onSearchChanged() {
        results.rowsChanged();
        main.overviewChanged();
        updateResultsHeader();
        host.tabStatusChanged(this);
    }

    // ---------------------------------------------------------------- lines and rows

    /** File line number of the current line in the log view, or -1. */
    long currentLine() {
        int row = main.selectedRow();
        if (row < 0 || (matchingOnly && filterResult.count() == 0)) {
            return -1;
        }
        return matchingOnly ? filterResult.lineAt(Math.min(row, filterResult.count() - 1)) : row;
    }

    /** Shows a file line; if the filters hide it, switches to showing all lines first. */
    void showLine(long line) {
        if (matchingOnly && !filterResult.contains(line)) {
            setMatchingOnly(false);
        }
        goNear(line);
        host.tabStatusChanged(this);
    }

    /**
     * Shows a line now, or as soon as indexing (and, in "Matching only", the filters) have
     * reached it, for a file just opened.
     */
    void showLineWhenAvailable(long line) {
        pendingLine = line;
        pendingExact = true;
        tryPendingLine();
    }

    private void tryPendingLine() {
        long line = pendingLine;
        if (line < 0) {
            return;
        }
        boolean indexed = doc.lineCount() > line || !doc.isIndexing();
        FilterResult r = filterResult;
        boolean filtered;
        if (!matchingOnly || !filterPanel.hasActiveFilters()) {
            filtered = true;
        } else if (pendingExact) {
            // wait until filtering has settled (entries detected, every filter done), or a
            // line inside a stack trace may look hidden for a moment
            filtered = r.isFiltering() && r.isComplete() && !engine.isRunning()
                    && detectedGeneration == doc.generation() && r.version() == engine.result().version();
        } else {
            filtered = r.isFiltering() && (r.checkedLines() > line || r.isComplete());
        }
        if (indexed && filtered) {
            pendingLine = -1;
            if (pendingExact) {
                showLine(line);
            } else {
                goNear(line); // keep the place: this line, or the next one shown
            }
        }
    }

    /** Goes to the line, or the next one shown if it is filtered out. */
    private void goNear(long line) {
        long row = matchingOnly ? filterResult.rowAtOrAfter(line) : line;
        main.goToRow((int) Math.min(row, Integer.MAX_VALUE - 1), true);
    }

    boolean isMatchingOnly() {
        return matchingOnly;
    }

    /** Shows only lines passing the filters, or all lines. Keeps the current line in view. */
    void setMatchingOnly(boolean on) {
        filterPanel.setMatchingOnly(on);
        if (on == matchingOnly) {
            return;
        }
        long line = currentLine();
        boolean follow = main.isFollow();
        matchingOnly = on;
        main.setSource(on ? matchingLines : allLines);
        if (follow) {
            main.setFollow(true);
        } else if (line >= 0) {
            goNear(line);
        }
        host.tabStatusChanged(this);
    }

    // ---------------------------------------------------------------- filters

    @Override
    public void filtersChanged() {
        List<FilterEngine.Spec> specs = new ArrayList<>();
        List<Character> shows = new ArrayList<>();
        List<Character> hides = new ArrayList<>();
        for (FilterDef d : filterPanel.filters()) {
            d.error = null;
            SearchQuery q = d.query();
            if (q != null) {
                try {
                    q.toPattern();
                } catch (PatternSyntaxException e) {
                    d.error = "Invalid regular expression: " + e.getDescription();
                }
            }
            filterPanel.refreshRow(d);
            if (!d.isActive()) {
                continue;
            }
            specs.add(new FilterEngine.Spec(d.id, q));
            if (d.action == FilterDef.Action.SHOW) {
                shows.add(d.id);
            } else if (d.action == FilterDef.Action.HIDE) {
                hides.add(d.id);
            }
        }
        Formula formula;
        String formulaError = null;
        if (filterPanel.isAutoFormula()) {
            formula = Formula.auto(shows, hides);
            filterPanel.showAutoFormula(formula == null ? "" : Formula.format(formula));
        } else {
            try {
                formula = Formula.parse(filterPanel.formulaText());
            } catch (IllegalArgumentException e) {
                formulaError = e.getMessage();
                formula = appliedFormula; // keep the last good one while the user types
            }
        }
        // level chips: AND (any of the chosen level words), using ids that aren't letters
        List<String> levelWords = filterPanel.levelWords();
        if (levelWords != null) {
            List<Character> ids = new ArrayList<>();
            for (int i = 0; i < levelWords.size(); i++) {
                char id = (char) ('0' + i);
                specs.add(new FilterEngine.Spec(id, new SearchQuery(levelWords.get(i), true, false, true)));
                ids.add(id);
            }
            Formula levels = ids.isEmpty() ? null : Formula.auto(ids, List.of());
            if (levels == null) {
                // no level chosen: show nothing
                specs.add(new FilterEngine.Spec('0', new SearchQuery("\\A\\z.", false, true, false)));
                levels = new Formula.Ref('0');
            }
            formula = formula == null ? levels : new Formula.And(formula, levels);
        }
        try {
            engine.configure(specs, formula);
            appliedFormula = formula;
        } catch (IllegalArgumentException e) {
            formulaError = formulaError != null ? formulaError : e.getMessage();
            try {
                engine.configure(specs, appliedFormula);
            } catch (IllegalArgumentException e2) {
                engine.configure(specs, null);
                appliedFormula = null;
            }
        }
        if (!filterPanel.isAutoFormula()) {
            filterPanel.setFormulaError(formulaError);
        }
        applyEntryOptions();
        updateHighlights();
        updateFilterStatus();
    }

    @Override
    public void matchingOnlyChanged(boolean on) {
        setMatchingOnly(on);
        main.focusView();
    }

    @Override
    public void navigate(FilterDef d, boolean forward) {
        SearchSession s = d.isActive() ? engine.session(d.id) : null;
        long line = s == null ? -1 : nextHit(s.hits(), forward);
        if (line < 0) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        showLine(line);
    }

    /** Marks this tab as a merged log: lines start with "<label> " for each source. */
    void setSourceLabels(List<String> labels) {
        this.sourceLabels = List.copyOf(labels);
        main.rowsChanged();
    }

    List<String> sourceLabels() {
        return sourceLabels;
    }

    /** Runs when the tab closes (for example, stops a merge that keeps following). */
    void addCloseHook(Runnable r) {
        closeHooks.add(r);
    }

    /** The source's color of a merged log's line, or null. */
    private java.awt.Color lineSourceColor(long line) {
        List<String> labels = sourceLabels;
        if (labels == null) {
            return null;
        }
        String text = doc.line(line);
        if (text == null || !text.startsWith("<")) {
            return null;
        }
        int gt = text.indexOf("> ");
        int i = gt < 0 ? -1 : labels.indexOf(text.substring(1, gt));
        return i < 0 ? null : sourceColor(i);
    }

    static java.awt.Color sourceColor(int i) {
        return FilterDef.SWATCHES.get(i % FilterDef.SWATCHES.size()).mark();
    }

    /** HTML legend of the sources, for the tab's tooltip. */
    String sourceLegend() {
        if (sourceLabels == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<html><b>Merged by time</b><br>");
        for (int i = 0; i < sourceLabels.size(); i++) {
            java.awt.Color c = sourceColor(i);
            sb.append(String.format("<span style='color:#%06x'>■</span> &lt;%s&gt;<br>",
                    c.getRGB() & 0xFFFFFF, sourceLabels.get(i)));
        }
        return sb.append("</html>").toString();
    }

    /** Shows the current line in the Details tab (when that tab is open). */
    void updateDetails() {
        if (bottom.getSelectedComponent() != details) {
            return;
        }
        long line = currentLine();
        details.show(line, line < 0 ? null : doc.line(line), main.fontSize());
    }

    DetailsPanel detailsPanel() {
        return details;
    }

    void showDetailsTab() {
        if (!bottom.isVisible()) {
            toggleBottom();
        }
        bottom.setSelectedComponent(details);
        updateDetails();
    }

    MessagesPanel messagesPanel() {
        return messages;
    }

    void showMessagesTab() {
        if (!bottom.isVisible()) {
            toggleBottom();
        }
        bottom.setSelectedComponent(messages);
    }

    private MessagesPanel.Host messagesHost() {
        return new MessagesPanel.Host() {
            @Override
            public logmaster.core.MessageStats.Result analyze(boolean onlyFiltered,
                    AtomicBoolean cancel, java.util.function.DoubleConsumer progress) {
                FilterResult r = filterResult;
                boolean filtered = onlyFiltered && r.isFiltering();
                long from = filtered ? r.windowStart() : 0;
                long to = filtered ? r.windowEnd() : Long.MAX_VALUE;
                return logmaster.core.MessageStats.analyze(doc, entryFormat, from, to,
                        filtered ? r::contains : null, cancel::get, progress);
            }

            @Override
            public String describeLine(long line) {
                if (timeFormat != null) {
                    String text = doc.line(line);
                    long t = text == null ? logmaster.core.TimeFormat.NONE : timeFormat.parse(text);
                    if (t != logmaster.core.TimeFormat.NONE) {
                        return TimelineBar.formatFull(t).substring(5, 19);
                    }
                }
                return String.format("line %,d", line + 1);
            }

            @Override
            public void findAll(logmaster.core.MessageStats.Template t) {
                host.runFindAll(new SearchQuery(t.regex(), true, true, false));
                showLine(t.firstLine());
            }

            @Override
            public void filter(List<logmaster.core.MessageStats.Template> ts, FilterDef.Action action) {
                StringBuilder re = new StringBuilder();
                for (logmaster.core.MessageStats.Template t : ts) {
                    if (re.length() > 0) {
                        re.append('|');
                    }
                    re.append(ts.size() == 1 ? t.regex() : "(?:" + t.regex() + ")");
                }
                FilterDef d = filterPanel.addFilter(re.toString(), action);
                if (d != null) {
                    d.regex = true;
                    d.matchCase = true;
                    filterPanel.refreshRow(d);
                    filtersChanged();
                    if (action != FilterDef.Action.MARK) {
                        setMatchingOnly(true);
                    }
                }
            }
        };
    }

    /** Adds a filter for exactly this text (from a selection). */
    void addFilter(String text, FilterDef.Action action) {
        FilterDef d = filterPanel.addFilter(text, action);
        if (d != null) {
            d.matchCase = true;
            filterPanel.refreshRow(d);
            filtersChanged();
            bottom.setSelectedComponent(filterPanel);
        }
    }

    /** Line colors: filter colors first (first match wins), then the built-in level colors. */
    private void updateHighlights() {
        List<HighlightRule> rules = new ArrayList<>();
        for (FilterDef d : filterPanel.filters()) {
            FilterDef.Swatch s = d.color();
            if (s != null && d.isActive()) {
                rules.add(new HighlightRule("Filter " + d.id, d.query().toPattern(),
                        null, s.light(), null, s.dark()));
            }
        }
        results.setHighlightRules(rules);
        rules.addAll(HighlightRule.defaults());
        main.setHighlightRules(rules);
    }

    @Override
    public void alertChanged(FilterDef d) {
        if (d.alert) {
            SearchSession s = engine.session(d.id);
            alerts.put(d, new long[] {doc.lineCount(), s == null ? 0 : s.hits().size(), 0, 0, -1});
        } else {
            alerts.remove(d);
        }
    }

    /** Looks for new matching lines of filters with an alert, and reports them (at most every 3 s). */
    private void checkAlerts() {
        if (doc.generation() != alertGeneration) {
            alertGeneration = doc.generation();
            for (long[] a : alerts.values()) {
                a[0] = 0; // a new file (rotation): everything in it is new
                a[1] = 0;
            }
        }
        long now = System.currentTimeMillis();
        for (FilterDef d : filterPanel.filters()) {
            if (!d.alert || !d.isActive()) {
                continue;
            }
            long[] a = alerts.computeIfAbsent(d, k -> new long[] {doc.lineCount(), 0, 0, 0, -1});
            SearchSession s = engine.session(d.id);
            if (s == null) {
                continue;
            }
            HitList hits = s.hits();
            int n = hits.size();
            if (n < a[1]) {
                a[1] = 0; // search restarted
            }
            for (int i = (int) a[1]; i < n; i++) {
                long line = hits.get(i);
                if (line >= a[0]) {
                    if (a[3] == 0) {
                        a[4] = line;
                    }
                    a[3]++;
                }
            }
            a[1] = n;
            if (a[3] > 0 && now - a[2] >= 3000) {
                String text = doc.line(a[4]);
                String msg = String.format("%,d new line%s matching “%s”: %s", a[3], a[3] == 1 ? "" : "s",
                        d.text, text == null ? "" : text.strip());
                host.alert(this, msg, a[4]);
                a[2] = now;
                a[3] = 0;
            }
        }
        if (alertTimer == null && !alerts.isEmpty()) {
            // flush alerts that were held back by the 3 s limit
            alertTimer = new javax.swing.Timer(1000, e -> checkAlerts());
            alertTimer.start();
        }
    }

    private javax.swing.Timer alertTimer;

    private void onFilterResult() {
        FilterResult r = engine.result();
        boolean redefined = r.version() != filterResult.version();
        if (redefined && matchingOnly && !main.isFollow() && pendingLine < 0) {
            // keep the place while the new result is computed (it may be empty for a moment)
            long keep = currentLine();
            if (keep >= 0) {
                pendingLine = keep;
                pendingExact = false;
            }
        }
        filterResult = r;
        main.rowsChanged();
        tryPendingLine();
        if (!alerts.isEmpty() || hasAlerts()) {
            checkAlerts();
        }
        updateFilterStatus();
        main.overviewChanged();
        host.tabStatusChanged(this);
    }

    private void updateFilterStatus() {
        FilterResult r = filterResult;
        boolean running = engine.isRunning() || doc.isIndexing();
        String text;
        if (!r.isFiltering()) {
            text = filterPanel.filters().isEmpty() ? "" : "no Show or Hide filter";
        } else {
            text = r.count() != r.matchCount()
                    ? String.format("%,d matching, %,d shown of %,d lines", r.matchCount(), r.count(),
                            doc.lineCount())
                    : String.format("%,d of %,d lines", r.count(), doc.lineCount());
            if (running) {
                text += String.format("   filtering %d%%", (int) (engine.progress() * 100));
            }
            if (timeRange != null) {
                text += "   in the selected time range";
            }
            if (filterPanel.levelWords() != null) {
                text += "   · some levels hidden";
            }
        }
        filterPanel.setResultText(text);
        for (FilterDef d : filterPanel.filters()) {
            SearchSession s = d.isActive() ? engine.session(d.id) : null;
            String c = "";
            if (d.error != null) {
                c = "invalid";
            } else if (s != null) {
                c = String.format("%,d", s.hits().size()) + (s.isRunning() ? "…" : "");
                if (s.isLimitReached()) {
                    c += "+";
                }
            }
            filterPanel.setCount(d, c);
        }
    }

    private void extendPopup(LogView view, JPopupMenu menu) {
        menu.addSeparator();
        if (view == main) {
            long cur = currentLine();
            JMenuItem bm = new JMenuItem(cur >= 0 && bookmarks.contains(cur) ? "Remove Bookmark"
                    : "Bookmark This Line");
            bm.addActionListener(e -> toggleBookmark());
            menu.add(bm);
            menu.addSeparator();
        }
        String sel = view.selectionForFind();
        if (sel == null) {
            JMenuItem hint = new JMenuItem("Select text to filter by it");
            hint.setEnabled(false);
            menu.add(hint);
        } else {
            String shown = sel.length() > 40 ? sel.substring(0, 40) + "…" : sel;
            for (FilterDef.Action a : FilterDef.Action.values()) {
                JMenuItem mi = new JMenuItem(a.label + " lines with “" + shown + "”");
                mi.setToolTipText(a.tip);
                mi.addActionListener(e -> addFilter(sel, a));
                menu.add(mi);
            }
        }
        if (sourceLabels != null) {
            menu.addSeparator();
            for (String label : sourceLabels) {
                JMenuItem mi = new JMenuItem("Show Only Lines from <" + label + ">");
                mi.addActionListener(e -> {
                    addFilter("<" + label + "> ", FilterDef.Action.SHOW);
                    setMatchingOnly(true);
                });
                menu.add(mi);
            }
        }
        JCheckBoxMenuItem only = new JCheckBoxMenuItem("Show Matching Lines Only", matchingOnly);
        only.addActionListener(e -> setMatchingOnly(only.isSelected()));
        menu.add(only);
    }

    void showFiltersTab() {
        if (!bottom.isVisible()) {
            toggleBottom();
        }
        bottom.setSelectedComponent(filterPanel);
    }

    logmaster.core.EntryFormat entryFormat() {
        return entryFormat;
    }

    logmaster.core.LogFormat logFormat() {
        return logFormat;
    }

    /** Shows lines as columns when the log has a known layout. */
    void setColumnsVisible(boolean on) {
        columnsWanted = on;
        applyColumns();
    }

    private void applyColumns() {
        logmaster.core.LogFormat f = logFormat;
        ColumnLayout layout = columnsWanted && f != null ? new ColumnLayout(f, sourceLabels != null) : null;
        main.setColumns(layout);
        results.setColumns(layout == null ? null : new ColumnLayout(f, sourceLabels != null));
    }

    /** Counts the values of a column in the background and shows them. */
    void countValues(String column) {
        logmaster.core.LogFormat f = logFormat;
        if (f == null) {
            return;
        }
        ValuesDialog.show(this, f, column);
    }

    /** Shows only (or hides) lines whose column has this exact value. */
    void filterByValue(String value, FilterDef.Action action) {
        addFilter(value, action);
        if (action != FilterDef.Action.MARK) {
            setMatchingOnly(true);
        }
    }

    /** Lines the log view shows now: matching lines in "Matching only", else every line. */
    logmaster.core.LineExporter.Runs visibleRuns() {
        FilterResult r = filterResult;
        long n = doc.lineCount();
        return matchingOnly ? c -> r.forEachRun(n, c) : c -> c.run(0, n);
    }

    /** Lines that pass the filters (with context lines), whatever the view shows. */
    logmaster.core.LineExporter.Runs filteredRuns() {
        FilterResult r = filterResult;
        long n = doc.lineCount();
        return c -> r.forEachRun(n, c);
    }

    boolean isFiltering() {
        return filterResult.isFiltering();
    }

    /** The selected lines of the log view (or the current line). */
    logmaster.core.LineExporter.Runs selectionRuns() {
        int[] rows = main.selectedRows();
        LogView.RowSource src = main.source();
        return c -> {
            if (rows == null) {
                return;
            }
            long runStart = -1;
            long prev = -2;
            for (int row = rows[0]; row <= rows[1]; row++) {
                long line = src.lineNumber(row);
                if (line != prev + 1) {
                    if (runStart >= 0) {
                        c.run(runStart, prev + 1);
                    }
                    runStart = line;
                }
                prev = line;
            }
            if (runStart >= 0) {
                c.run(runStart, prev + 1);
            }
        };
    }

    /** Filters, view mode and position, to remember for the next time the file is opened. */
    java.util.Properties saveState() {
        java.util.Properties p = filterPanel.toProperties();
        p.setProperty("state.size", Long.toString(doc.fileSize()));
        p.setProperty("state.line", Long.toString(Math.max(0, currentLine())));
        p.setProperty("state.matchingOnly", Boolean.toString(matchingOnly));
        return p;
    }

    /** Applies remembered state, unless the file got smaller since (then lines moved). */
    void restoreState(java.util.Properties p) {
        try {
            long size = Long.parseLong(p.getProperty("state.size", "0"));
            if (doc.fileSize() < size) {
                return;
            }
            filterPanel.fromProperties(p);
            setMatchingOnly(Boolean.parseBoolean(p.getProperty("state.matchingOnly", "false")));
            showLineWhenAvailable(Long.parseLong(p.getProperty("state.line", "0")));
        } catch (IOException | RuntimeException e) {
            // unreadable state: start fresh
        }
    }

    /** Whether this tab's state should be remembered (not for temporary merged files). */
    boolean remembersState() {
        return sourceLabels == null && remoteLabel == null;
    }

    /** Shows or hides the Filters / Find results area. */
    void toggleBottom() {
        boolean show = !bottom.isVisible();
        bottom.setVisible(show);
        split.setDividerSize(show ? dividerSize : 0);
        split.resetToPreferredSizes();
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
        if (!bottom.isVisible()) {
            toggleBottom();
        }
        bottom.setSelectedComponent(resultsPanel);
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
        updateResultsHeader();
        bottom.setSelectedComponent(filterPanel);
    }

    /** Jumps to the next (or previous) hit after the current line. Returns false if none. */
    boolean findNext(boolean forward) {
        SearchSession s = search;
        if (s == null || s.hits().size() == 0) {
            return false;
        }
        HitList hits = s.hits();
        long line = nextHit(hits, forward);
        int idx = hits.firstAtOrAfter(line);
        results.goToRow(idx, false);
        showLine(line);
        return true;
    }

    /** The hit after (or before) the current line, wrapping around; -1 if there are none. */
    private long nextHit(HitList hits, boolean forward) {
        int n = hits.size();
        if (n == 0) {
            return -1;
        }
        long cur = currentLine();
        if (cur < 0) {
            cur = matchingOnly ? -1 : main.topRow() - 1;
        }
        int idx;
        if (forward) {
            idx = hits.firstAtOrAfter(cur + 1);
            if (idx >= n) {
                idx = 0; // wrap
            }
        } else {
            idx = hits.firstAtOrAfter(cur) - 1;
            if (idx < 0) {
                idx = n - 1;
            }
        }
        return hits.get(idx);
    }

    private void showHit(int row) {
        SearchSession s = search;
        if (s != null && row >= 0 && row < s.hits().size()) {
            showLine(s.hits().get(row));
        }
    }

    private void updateResultsHeader() {
        SearchSession s = search;
        if (s == null) {
            resultsHeader.setText(" ");
            bottom.setTitleAt(1, "Find results");
            return;
        }
        bottom.setTitleAt(1, String.format("Find results (%,d)", s.hits().size()));
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

    private boolean hasAlerts() {
        for (FilterDef d : filterPanel.filters()) {
            if (d.alert) {
                return true;
            }
        }
        return false;
    }

    void close() {
        if (alertTimer != null) {
            alertTimer.stop();
        }
        closeHooks.forEach(Runnable::run);
        SearchSession s = search;
        if (s != null) {
            s.close();
        }
        messages.cancel();
        engine.close();
        if (timeIndex != null) {
            timeIndex.close();
        }
        errorLines.close();
        warningLines.close();
        doc.close();
    }
}
