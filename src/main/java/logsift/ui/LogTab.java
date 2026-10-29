package logsift.ui;

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
import logsift.core.FilterEngine;
import logsift.core.FilterResult;
import logsift.core.Formula;
import logsift.core.HitList;
import logsift.core.LogDocument;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;

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

        /** Opens Log Formats with a new format made from this log's lines. */
        default void defineFormat(LogTab tab) {}

        /** Fills the menu of formats to read this log with (the log's bar shows it). */
        default void fillFormatMenu(LogTab tab, javax.swing.JPopupMenu menu) {}

        /** The user closed the tree of loggers (for every log). */
        default void setValuesTree(boolean on) {}

        /** Opens the thread dumps in this log in a Threads tab. */
        default void analyzeThreads(LogTab tab) {}

        /** Reads the GC lines of this log and explains them in a tab of their own. */
        default void analyzeGc(LogTab tab) {}

        /** Opens Settings on a tab (null: the first). */
        default void showSettings(String tab) {}
        /** Explains the Java crash report (hs_err_pid file) this tab shows. */
        default void explainCrash(LogTab tab) {}

        /** Shows the thread a line names in a thread dump of the log's JVM. */
        default void showThread(LogTab tab, String thread) {}

        /** Opens the source file of a stack frame at its line. */
        default void openSource(LogTab tab, logsift.core.Exceptions.Frame frame) {}

        /** Every open log (to follow a request through them). */
        default List<LogTab> openLogs() {
            return List.of();
        }

        /** Opens a file (or selects its tab) at a line, with this search run in it. */
        default void openAt(Path file, long line, SearchQuery q) {}

        /** Opens Search in Folder for this text. */
        default void searchInFolder(String text) {}

        /** Opens the heap dump the JVM wrote when it ran out of memory (the log says where, near this line). */
        default void openHeapDumpOf(LogTab tab, long line) {}

        /** Compares this CSV file with another one (as tables). */
        default void compareCsv(LogTab tab) {}
    }

    private final LogDocument doc;
    private final Path path;
    private final String encodingLabel;
    private String remoteLabel;          // "user@host:/path" for remote logs
    private logsift.core.RemoteMirror.Target sshTarget; // the server of a log read over SSH
    private String origin;               // a download's web address
    private volatile String remoteStatus;
    private final Host host;
    private final LogView main = new LogView();
    /** Every Find All, as foldable groups in one window. */
    private ResultsPanel results;
    private final FilterPanel filterPanel = new FilterPanel(this);
    private FindBar findBar;
    private LogBar logBar;
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
    private final Runnable filterUpdate;
    private volatile FilterResult filterResult; // written on the EDT
    private List<String> sourceLabels;          // merged logs: the label of each source
    private final List<Runnable> closeHooks = new ArrayList<>();
    private final MessagesPanel messages;
    private final InsightsPanel insights;
    private RequestPanel request; // made the first time a request is followed
    private final ExceptionsPanel exceptions;
    private final MetricsPanel metrics;
    private final ProfilePanel profile;
    private final PivotPanel pivot;
    private final QueryPanel query;
    private logsift.core.Sql.LogTable queryTable;
    private logsift.core.LogFormat queryCsvFor;
    private boolean profileAsked; // the user opened Profile for a log that isn't CSV
    private boolean pivotAsked;   // the user opened Pivot for a log that isn't CSV
    private int insightsGeneration = -1;
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
    private volatile logsift.core.EntryFormat entryFormat; // detected; null = none
    private volatile logsift.core.TimeFormat timeFormat;   // detected; null = none
    private volatile logsift.core.TimeIndex timeIndex;
    private volatile logsift.core.LogFormat logFormat; // columns; null = none found
    private boolean columnsWanted;
    private final TimelineBar timeline;
    private long[] timeRange;                       // selected {from, to} times, or null
    private boolean timelineWanted = true;
    private int detectedGeneration = -1;
    private long indexMillis = -1;
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
        this(LogDocument.open(readPath, logsift.core.LineIndex.DEFAULT_CHUNK_SIZE, charset), path,
                encodingLabel, host);
    }

    /** A tab for a document opened already (opening can be slow on network drives). */
    LogTab(LogDocument doc, Path path, String encodingLabel, Host host) {
        super(new BorderLayout());
        this.host = host;
        this.path = path.toAbsolutePath().normalize();
        this.encodingLabel = encodingLabel != null ? encodingLabel
                : logsift.core.Encoding.label(doc.charset());
        this.doc = doc;
        this.engine = new FilterEngine(doc);
        this.filterResult = engine.result();
        docUpdate = coalesced(this::onDocChanged);
        filterUpdate = coalesced(this::onFilterResult);
        engine.addListener(e -> filterUpdate.run());
        timeline = new TimelineBar(timelineModel());
        timeline.setVisible(false);
        marksUpdate = coalesced(() -> {
            main.overviewChanged();
            timeline.refresh();
        });
        errorLines = levelSearch(logsift.core.Levels.ERROR);
        warningLines = levelSearch(logsift.core.Levels.WARN);
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
            public void textsNow(int first, int count, String[] out) {
                readNow(main, count == 1 ? -1 : 0, first, count, out);
            }

            @Override
            public String placeholder() {
                return doc.isIndexing() ? "Reading " + doc.path().getFileName() + "…" : null;
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
                filteredTexts(first, count, out, false);
            }

            @Override
            public void textsNow(int first, int count, String[] out) {
                filteredTexts(first, count, out, true);
            }

            private void filteredTexts(int first, int count, String[] out, boolean now) {
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
                    if (now) {
                        readNow(main, count == 1 ? -1 : i, start, run, buf);
                    } else {
                        doc.readLines(start, run, buf);
                    }
                    System.arraycopy(buf, 0, out, i, run);
                    i += run;
                }
                for (; i < count; i++) {
                    out[i] = null;
                }
            }
        };
        main.setSource(allLines);
        main.addListener(new LogView.Listener() {
            @Override
            public void followChanged(LogView view, boolean follow) {
                if (logBar != null) {
                    logBar.setFollow(follow);
                }
                applyAutoReload(); // following the end means loading changes as they come
                host.tabStatusChanged(LogTab.this);
            }

            @Override
            public void selectionChanged(LogView view, int row) {
                host.tabStatusChanged(LogTab.this);
            }

            @Override
            public void selectionEdited(LogView view) {
                updateDetails(); // the line, or what is selected
            }
        });
        main.setPopupExtender(this::extendPopup);
        main.setColumnMenu(this::showColumnMenu);
        main.setOverview(overviewModel());

        messages = new MessagesPanel(messagesHost());
        insights = new InsightsPanel(insightsHost());
        exceptions = new ExceptionsPanel(exceptionsHost());
        metrics = new MetricsPanel(metricsHost());
        profile = new ProfilePanel(profileHost());
        pivot = new PivotPanel(pivotHost());
        query = new QueryPanel(queryHost());
        bookmarks = new Bookmarks(this.path, doc::fileSize);
        bookmarkGeneration = doc.generation();
        bookmarksPanel = new BookmarksPanel(bookmarks, this::timeText, doc::line, this::showLine);
        bookmarks.addListener(() -> {
            main.rowsChanged();
            main.overviewChanged();
            bottom.setTitleAt(bottom.indexOfComponent(bookmarksPanel),
                    bookmarks.size() == 0 ? "Bookmarks" : "Bookmarks (" + bookmarks.size() + ")");
        });
        bottom.addTab("Filters", filterPanel);
        bottom.addTab("Insights", insights);
        bottom.addTab("Exceptions", exceptions);
        bottom.addTab("Metrics", metrics);
        bottom.addTab("Query", query);
        bottom.setToolTipTextAt(bottom.indexOfComponent(query), "SQL over the log (or the table), read-only");
        results = new ResultsPanel(doc, resultsHost());
        bottom.addTab("Find results", results);
        bottom.addTab("Messages", messages);
        bottom.addTab(bookmarks.size() == 0 ? "Bookmarks" : "Bookmarks (" + bookmarks.size() + ")",
                bookmarksPanel);
        bottom.addTab("Details", details);
        // which tabs follow the filters: a marker on their title and a strip on top while they do
        for (java.awt.Component c : List.of(metrics, results, messages)) {
            titleLabel(c);
        }
        for (java.awt.Component c : List.of(insights, exceptions, bookmarksPanel)) {
            bottom.setToolTipTextAt(bottom.indexOfComponent(c), TabScope.WHOLE);
        }
        metricsBanner = ScopeBanner.install(metrics, "Show all lines", () -> setMatchingOnly(false));
        profileBanner = ScopeBanner.install(profile, "Show all lines", () -> setMatchingOnly(false));
        pivotBanner = ScopeBanner.install(pivot, "Show all lines", () -> setMatchingOnly(false));
        messagesBanner = ScopeBanner.install(messages, "Count all lines", messages::countWholeLog);
        resultsBanner = ScopeBanner.install(results, "Find in all lines", this::findCurrentInAllLines);
        bottom.addChangeListener(e -> {
            updateDetails();
            if (bottom.getSelectedComponent() == messages && messages.result() == null
                    && !messages.isRunning()) {
                messages.start(); // analyze the first time the tab is opened
            }
            if (bottom.getSelectedComponent() == insights && insights.report() == null && !insights.isRunning()) {
                startInsights();
            }
            if (bottom.getSelectedComponent() == exceptions && exceptions.result() == null
                    && !exceptions.isRunning() && !insights.isRunning()) {
                exceptions.start();
            }
            if (bottom.getSelectedComponent() == metrics && metrics.metrics() == null
                    && !metrics.isRunning() && !insights.isRunning()) {
                metrics.start();
            } else if (bottom.getSelectedComponent() == metrics && !insights.isRunning()) {
                metrics.showing(); // the filters may have changed while it was hidden
            }
            if (bottom.getSelectedComponent() == profile) {
                profile.showing();
            }
            if (bottom.getSelectedComponent() == pivot) {
                pivot.showing();
            }
            if (bottom.getSelectedComponent() == query) {
                query.showing();
            }
        });
        bottom.setPreferredSize(new Dimension(400, 210));
        bottom.putClientProperty("JTabbedPane.tabHeight", 26);
        JPanel top = new JPanel(new BorderLayout());
        findBar = new FindBar(findBarHost());
        logBar = new LogBar(this);
        JPanel bars = new JPanel(new BorderLayout());
        bars.add(logBar, BorderLayout.NORTH);
        bars.add(findBar, BorderLayout.CENTER);
        top.add(bars, BorderLayout.NORTH);
        JPanel north = new JPanel(new BorderLayout());
        north.add(timeline, BorderLayout.NORTH);
        JPanel banners = new JPanel();
        banners.setLayout(new javax.swing.BoxLayout(banners, javax.swing.BoxLayout.Y_AXIS));
        this.banners = banners;
        banners.add(dumpsBanner);
        banners.add(gcBanner);
        banners.add(crashBanner);
        banners.add(noColumns);
        north.add(banners, BorderLayout.SOUTH);
        noColumns.setVisible(false);
        dumpsBanner.setVisible(false);
        gcBanner.setVisible(false);
        crashBanner.setVisible(false);
        checkForThreadDumps();
        JPanel logArea = new JPanel(new BorderLayout());
        logArea.add(north, BorderLayout.NORTH);
        logArea.add(main, BorderLayout.CENTER);
        treePanel = new ValuesTreePanel(doc, new ValuesTreePanel.Listener() {
            @Override
            public void hiddenChanged(ValuesTreePanel p) {
                filtersChanged();
                if (p.hiddenColumn() != null) {
                    setMatchingOnly(true); // hidden lines go away
                }
            }

            @Override
            public void closeRequested() {
                host.setValuesTree(false);
            }
        });
        side = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, logArea);
        side.setBorder(null);
        side.setContinuousLayout(true);
        side.setResizeWeight(0);
        top.add(side, BorderLayout.CENTER);
        updateTreeVisible();
        main.addPaintListener(timeline::repaint);
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(1.0); // the log gets the extra space
        split.setContinuousLayout(true);
        split.setBorder(null);
        dividerSize = split.getDividerSize();
        setUpCollapsing();
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

            @Override
            public void staleChanged(LogDocument d) {
                SwingUtilities.invokeLater(() -> {
                    updateReloadButton();
                    host.tabStatusChanged(LogTab.this);
                });
            }
        });
        doc.startFollowing(AppState.prefs().getInt("pollMs", 500));
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
        if (gen == detectedGeneration || gen == detectingGeneration
                || (n < 200 && doc.isIndexing())) {
            return;
        }
        if (n == 0) {
            return; // an empty file that is still growing: decide when lines arrive
        }
        // reading the sample can be slow on a network drive: do it off the UI thread
        detectingGeneration = gen;
        DETECT.execute(() -> {
            logsift.core.Formats.Detected d = logsift.core.LogAnalysis.detect(doc, n, AppState.customFormats(),
                    path.getFileName() == null ? "" : path.getFileName().toString(), formatChoice);
            SwingUtilities.invokeLater(() -> applyDetected(gen, d.entries(), d.columns(), d.times()));
        });
    }

    /** Who asked for lines being read in the background (a view, and which part of it). */
    record LoadKey(Object view, int part) {}

    /**
     * Reads lines for painting without waiting for the disk; lines not in memory yet are
     * read in the background and the view repaints when they are there.
     */
    void readNow(LogView view, int part, long first, int count, String[] out) {
        doc.readLinesNow(first, count, out, new LoadKey(view, part),
                () -> SwingUtilities.invokeLater(view::textsArrived));
    }

    /** Daemon thread for format detection (shared by all tabs). */
    private static final java.util.concurrent.ExecutorService DETECT =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "logsift-detect");
                t.setDaemon(true);
                return t;
            });

    private int detectingGeneration = -1;
    private volatile String formatChoice; // format chosen by hand (its name), Formats.PLAIN, or null: detect

    /** The format chosen by hand for this file, {@link logsift.core.Formats#PLAIN}, or null (detected). */
    String formatChoice() {
        return formatChoice;
    }

    /** Uses this format (by name; null: detect it again) and reads the file's layout again. */
    void setFormatChoice(String name) {
        formatChoice = name;
        redetect();
        syncLogBar();
    }

    /** The menu of formats for this log (from its bar). */
    void fillFormatMenu(javax.swing.JPopupMenu menu) {
        host.fillFormatMenu(this, menu);
    }

    /** Works out the layout again (after the user's formats changed). */
    void redetect() {
        detectedGeneration = -1;
        detectingGeneration = -1;
        detectEntryFormat();
    }

    /** How often the file is checked for changes. */
    void setPollMillis(int ms) {
        doc.startFollowing(ms);
    }

    /** The first lines of the file (for previews), at most {@code max}. */
    List<String> sampleLines(int max) {
        String[] sample = new String[(int) Math.min(doc.lineCount(), max)];
        doc.readLines(0, sample.length, sample);
        return java.util.Arrays.asList(sample);
    }

    private void applyDetected(int gen, logsift.core.EntryFormat ef, logsift.core.LogFormat lf,
                               logsift.core.TimeFormat tf) {
        if (gen != doc.generation() || closed) {
            return; // the file changed meanwhile: detect again
        }
        detectedGeneration = gen;
        entryFormat = ef;
        boolean levelsChanged = !java.util.Objects.equals(
                logsift.core.Levels.lineRegex(logFormat, List.of("ERROR")),
                logsift.core.Levels.lineRegex(lf, List.of("ERROR")));
        logFormat = lf;
        if (levelsChanged) { // the user's format reads levels from its level column
            configureLevelSearch(errorLines, List.of(logsift.core.Levels.ERROR), lf);
            configureLevelSearch(warningLines, List.of(logsift.core.Levels.WARN), lf);
            if (filterPanel.levelWords() != null) {
                filtersChanged();
            }
        }
        applyColumns();
        updateProfileTab();
        filterPanel.columnsChanged();
        if (filterPanel.filters().stream().anyMatch(d -> d.column != null)) {
            filtersChanged(); // filters on a column use the format
        }
        treePanel.setFormat(lf);
        updateTreeVisible();
        filterPanel.setEntryFormatInfo(entryFormat == null ? null : entryFormat.name());
        applyEntryOptions();

        // timestamps: the timeline, time ranges, Go to Time
        if (timeIndex != null) {
            timeIndex.close();
            timeIndex = null;
        }
        timeFormat = tf;
        if (timeFormat != null) {
            timeIndex = new logsift.core.TimeIndex(doc, timeFormat);
            timeIndex.addListener(ti -> marksUpdate.run());
            timeIndex.start();
        }
        timeline.setVisible(timeFormat != null && timelineWanted);
        timeline.refresh();
        revalidate();
        syncLogBar(); // Tree and Timeline need a format with such columns and times
        tryPendingLine();
        updateFilterStatus();
        host.tabStatusChanged(this);
    }

    /** Whether the file is read as CSV (or TSV) columns. */
    boolean isCsv() {
        return logFormat != null && logFormat.isCsv();
    }

    /** The log's bar: compare this CSV file with another. */
    void compareWithAnother() {
        host.compareCsv(this);
    }

    /** The name of the format lines are read with (for the log's bar). */
    String formatName() {
        if (logFormat != null) {
            return logFormat.name();
        }
        return detectedGeneration < 0 && formatChoice == null ? "" : logsift.core.Formats.PLAIN;
    }

    // ---------------------------------------------------------------- time

    logsift.core.TimeIndex timeIndex() {
        return timeIndex;
    }

    TimelineBar timeline() {
        return timeline;
    }

    void setTimelineVisible(boolean on) {
        timelineWanted = on;
        timeline.setVisible(on && timeFormat != null);
        revalidate();
        syncLogBar();
    }

    boolean isTimelineWanted() {
        return timelineWanted;
    }

    /** True when the log has timestamps (so a timeline). */
    boolean hasTimes() {
        return timeFormat != null;
    }

    /** True when the log has columns a tree can be built from (loggers, threads...). */
    boolean hasTree() {
        return !ValuesTreePanel.treeColumns(logFormat).isEmpty();
    }

    /** Wraps long lines in this log (and in its results below). */
    void setWrap(boolean on) {
        main.setWrap(on);
        results.view().setWrap(on);
        syncLogBar();
    }

    boolean isWrap() {
        return main.isWrap();
    }

    private void syncLogBar() {
        if (logBar != null) {
            logBar.syncView();
        }
    }

    /** First line with a timestamp at or after {@code t} (logs are mostly in time order). */
    long lineAtTime(long t) {
        logsift.core.TimeIndex ti = timeIndex;
        if (ti == null) {
            return 0;
        }
        logsift.core.TimeIndex.Samples s = ti.samples();
        long from = s.lineBefore(t);
        long n = doc.lineCount();
        String[] buf = new String[256];
        for (long first = from; first < n && first < from + 4 * logsift.core.LineIndex.SAMPLE + 512;
             first += buf.length) {
            int k = (int) Math.min(buf.length, n - first);
            doc.readLines(first, k, buf);
            for (int i = 0; i < k; i++) {
                if (buf[i] != null) {
                    long lt = timeFormat.parse(buf[i]);
                    if (lt != logsift.core.TimeFormat.NONE && lt >= t) {
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
            return logsift.core.TimeFormat.NONE;
        }
        for (long l = line; l >= 0 && l > line - 200; l--) {
            String text = doc.lineIfLoaded(l); // for the status bar: never wait for the disk
            if (text == null) {
                break;
            }
            long t = timeFormat.parse(text);
            if (t != logsift.core.TimeFormat.NONE) {
                return t;
            }
        }
        return logsift.core.TimeFormat.NONE;
    }

    void goToTime(long t) {
        long line = lineAtTime(t);
        showLine(Math.min(line, Math.max(0, doc.lineCount() - 1)));
        main.focusView();
    }

    /** Shows only lines logged between the two times (inclusive), as a filter. */
    void selectTimeRange(long from, long to) {
        logsift.core.TimeIndex.Samples s = timeIndex == null ? null : timeIndex.samples();
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
            public logsift.core.TimeIndex.Samples samples() {
                logsift.core.TimeIndex ti = timeIndex;
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

    /** Lines of this level (ERROR or WARN), kept up to date. */
    private FilterEngine levelSearch(String level) {
        FilterEngine e = new FilterEngine(doc);
        configureLevelSearch(e, List.of(level), null);
        e.addListener(x -> marksUpdate.run());
        return e;
    }

    /** Searches for lines of these levels (see {@link logsift.core.LogAnalysis#configureLevelSearch}). */
    private static void configureLevelSearch(FilterEngine e, List<String> levels, logsift.core.LogFormat format) {
        logsift.core.LogAnalysis.configureLevelSearch(e, levels, format);
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
                for (FindGroup f : results.groups()) {
                    other.add(new OverviewBar.MarkSet("Find “" + f.title() + "”", f.colors()[2], f.marks()));
                }
                OverviewBar.Marks quick = findBar == null ? null : findBar.marks();
                if (quick != null) {
                    other.add(new OverviewBar.MarkSet("Find “" + findBar.text() + "”", Theme.SEARCH_COLORS[0][2], quick));
                }
                for (FilterDef d : filterPanel.filters()) {
                    SearchSession fs = d.isActive() && d.color() != null ? engine.session(d.id) : null;
                    if (fs != null) {
                        other.add(new OverviewBar.MarkSet("Filter " + d.id + " “" + d.label(LogTab.this::columnName) + "”",
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
        setBottomCollapsed(false);
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
        logsift.core.TimeFormat tf = timeFormat;
        String text = tf == null ? null : doc.line(line);
        long t = text == null ? logsift.core.TimeFormat.NONE : tf.parse(text);
        return t == logsift.core.TimeFormat.NONE ? "" : TimelineBar.formatFull(t);
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
        return results.view();
    }

    ResultsPanel results() {
        return results;
    }

    /** The selected (or newest) search, or null. */
    FindGroup currentFind() {
        return results.current();
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

    /** The current Find All's search, or null. */
    SearchSession search() {
        FindGroup f = results.current();
        return f == null ? null : f.session();
    }

    long indexMillis() {
        // the document remembers it, in case indexing finished before we were listening
        return indexMillis >= 0 ? indexMillis : doc.lastIndexMillis();
    }


    String error() {
        return error;
    }

    /** Marks this tab as a remote log (shown by its address, not remembered locally). */
    /** Where a downloaded copy came from (its web address), or null. Such a tab isn't remembered. */
    void setOrigin(String label) {
        this.origin = label;
        host.tabStatusChanged(this);
    }

    String origin() {
        return origin;
    }

    void setRemote(String label) {
        this.remoteLabel = label;
    }

    String remoteLabel() {
        return remoteLabel;
    }

    void setSshTarget(logsift.core.RemoteMirror.Target t) {
        this.sshTarget = t;
    }

    /** The server of a log read over SSH, or null. */
    logsift.core.RemoteMirror.Target sshTarget() {
        return sshTarget;
    }

    /**
     * Where the JVM said it wrote a heap dump ("Dumping heap to …"): the mention nearest after
     * {@code near}, else before it, else anywhere in the log; null when the log doesn't say.
     */
    String heapDumpMentioned(long near, java.util.function.BooleanSupplier cancel) {
        long n = doc.lineCount();
        long from = Math.max(0, near);
        for (long l = from; l < Math.min(n, from + 2000); l++) {
            String p = logsift.core.HeapDumpMentions.pathIn(doc.line(l));
            if (p != null) {
                return p;
            }
        }
        for (long l = from - 1; l >= Math.max(0, from - 2000); l--) {
            String p = logsift.core.HeapDumpMentions.pathIn(doc.line(l));
            if (p != null) {
                return p;
            }
        }
        for (long l = n - 1; l >= 0; l--) { // the latest one
            if ((l & 0xFFFF) == 0 && cancel.getAsBoolean()) {
                return null;
            }
            String text = doc.line(l);
            if (text != null && text.contains("Dumping heap to ")) {
                String p = logsift.core.HeapDumpMentions.pathIn(text);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
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
        results.view().rowsChanged();
        updateInsights();
        if (doc.generation() != bookmarkGeneration) {
            bookmarkGeneration = doc.generation();
            bookmarks.clear(); // truncated or rotated: the bookmarked lines are gone
        }
        detectEntryFormat();
        checkNewLinesForDumps();
        main.rowsChanged();
        tryPendingLine();
        results.view().rowsChanged();
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
        if (!matchingOnly || (!filterPanel.hasActiveFilters()
                && (treePanel == null || treePanel.hiddenColumn() == null))) {
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

    FilterResult filterResultForTest() {
        return filterResult;
    }

    FilterEngine filterEngineForTest() {
        return engine;
    }

    boolean isMatchingOnly() {
        return matchingOnly;
    }

    /** Shows only lines passing the filters, or all lines. Keeps the current line in view. */
    void setMatchingOnly(boolean on) {
        filterPanel.setMatchingOnly(on);
        if (logBar != null) {
            logBar.update();
        }
        if (on == matchingOnly) {
            return;
        }
        long line = currentLine();
        boolean follow = main.isFollow();
        matchingOnly = on;
        main.setSource(on ? matchingLines : allLines);
        profileFiltersChanged();
        updateScope();
        if (follow) {
            main.setFollow(true);
        } else if (line >= 0) {
            goNear(line);
        }
        host.tabStatusChanged(this);
    }

    // ---------------------------------------------------------------- filters

    /** A filter's search in its column (null: in the whole line). */
    private logsift.core.ColumnFilter columnFilter(FilterDef d, SearchQuery q) {
        if (d.column == null) {
            return null;
        }
        logsift.core.LogFormat f = logFormat;
        if (f == null) {
            throw new IllegalArgumentException("This log has no columns: its format has none");
        }
        return new logsift.core.ColumnFilter(f, d.column, d.op, q);
    }

    /** A column's name on screen: a CSV's as written in its header, else with a capital. */
    @Override
    public String columnName(String column) {
        logsift.core.LogFormat f = logFormat;
        return f != null ? f.displayName(column)
                : column.isEmpty() ? column : column.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + column.substring(1);
    }

    /** The columns of the log's format (for filters on one column). */
    @Override
    public List<String> columns() {
        logsift.core.LogFormat f = logFormat;
        return f == null ? List.of() : f.columns();
    }

    /** Adds a filter on one column's value and shows only (or hides) its rows. */
    void filterByColumn(String column, logsift.core.ColumnFilter.Op op, String value, FilterDef.Action action) {
        FilterDef d = filterPanel.addFilter(value, action, column, op);
        if (d != null && action != FilterDef.Action.MARK) {
            setMatchingOnly(true);
        }
    }

    /** Opens Filters with a new filter on this column, ready for its text. */
    void addColumnFilter(String column) {
        showFilters();
        filterPanel.addEmptyFilter(column);
    }

    @Override
    public void filtersChanged() {
        List<FilterEngine.Spec> specs = new ArrayList<>();
        List<Character> shows = new ArrayList<>();
        List<Character> hides = new ArrayList<>();
        for (FilterDef d : filterPanel.filters()) {
            d.error = null;
            SearchQuery q = d.query();
            logsift.core.ColumnFilter inColumn = null;
            if (q != null) {
                try {
                    q.toPattern();
                    inColumn = columnFilter(d, q);
                } catch (PatternSyntaxException e) {
                    d.error = "Invalid regular expression: " + e.getDescription();
                } catch (IllegalArgumentException e) {
                    d.error = e.getMessage(); // no such column, or not a number
                }
            }
            filterPanel.refreshRow(d);
            if (!d.isActive()) {
                continue;
            }
            specs.add(new FilterEngine.Spec(d.id, q, inColumn));
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
        String levelRegex = levelWords == null ? null
                : logsift.core.Levels.lineRegex(logFormat, filterPanel.chosenLevels());
        if (levelWords != null) {
            List<Character> ids = new ArrayList<>();
            if (levelRegex != null) { // the user's format: by its level column
                if (!filterPanel.chosenLevels().isEmpty()) {
                    specs.add(new FilterEngine.Spec('0', new SearchQuery(levelRegex, true, true, false)));
                    ids.add('0');
                }
            } else {
                for (int i = 0; i < levelWords.size(); i++) {
                    char id = (char) ('0' + i);
                    specs.add(new FilterEngine.Spec(id, new SearchQuery(levelWords.get(i), true, false, true)));
                    ids.add(id);
                }
            }
            Formula levels = ids.isEmpty() ? null : Formula.auto(ids, List.of());
            if (levels == null) {
                // no level chosen: show nothing
                specs.add(new FilterEngine.Spec('0', new SearchQuery("\\A\\z.", false, true, false)));
                levels = new Formula.Ref('0');
            }
            formula = formula == null ? levels : new Formula.And(formula, levels);
        }
        // branches unchecked in the tree of loggers
        String treeRegex = treePanel == null || treePanel.hiddenColumn() == null || logFormat == null ? null
                : logsift.core.ValueTree.lineRegex(logFormat, treePanel.hiddenColumn(), treePanel.hidden());
        if (treeRegex != null) {
            specs.add(new FilterEngine.Spec('~', new SearchQuery(treeRegex, true, true, false)));
            Formula notHidden = new Formula.Not(new Formula.Ref('~'));
            formula = formula == null ? notHidden : new Formula.And(formula, notHidden);
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
        if (matchingOnly && !filterPanel.hasActiveFilters() && timeRange == null
                && (treePanel == null || treePanel.hiddenColumn() == null)) {
            setMatchingOnly(false); // nothing left that hides lines (Color filters only color them)
        }
        updateFilterStatus();
    }

    @Override
    public void clearAllFilters() {
        clearTimeRange();
        treePanel.showAllValues();
        setMatchingOnly(false);
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

    private boolean autoReload = true;

    /** Loads changes on disk as they happen; off: they wait for {@link #reload()}. */
    void setAutoReload(boolean on) {
        autoReload = on;
        applyAutoReload();
        syncLogBar();
    }

    boolean isAutoReload() {
        return autoReload;
    }

    private void applyAutoReload() {
        doc.setAutoReload(autoReload || main.isFollow());
    }

    /** Loads what changed in the file on disk. */
    void reload() {
        doc.reload();
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
        String text = doc.lineIfLoaded(line); // painting: never wait for the disk
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
        float size = main.fontSize();
        long request = ++detailsRequest;
        int[] sel = main.selection();
        if (sel != null) { // selected text: just that
            long firstLine = main.lineNumberOf(sel[0]);
            long lastLine = main.lineNumberOf(sel[2]);
            DETAILS.execute(() -> {
                if (detailsRequest != request) {
                    return; // moved on
                }
                int rows = sel[2] - sel[0] + 1;
                String t;
                try {
                    t = main.textOf(sel, MAX_DETAIL_ROWS);
                } catch (RuntimeException ex) {
                    logsift.core.Problems.unexpected(ex); // a bug, if not a read failure
                    return;
                }
                String title = rows == 1
                        ? String.format("Selected in line %,d · %,d characters", firstLine + 1, t.length())
                        : String.format("Selected: lines %,d – %,d · %,d lines%s", firstLine + 1, lastLine + 1, rows,
                        rows > MAX_DETAIL_ROWS ? String.format(" (the first %,d shown)", MAX_DETAIL_ROWS) : "");
                SwingUtilities.invokeLater(() -> {
                    if (detailsRequest == request) {
                        details.showSelection(title, t, size);
                    }
                });
            });
            return;
        }
        if (line < 0) {
            details.show(-1, null, size);
            return;
        }
        logsift.core.EntryFormat ef = entryFormat;
        DETAILS.execute(() -> {
            if (detailsRequest != request) {
                return; // moved on
            }
            logsift.core.EntryFormat.Entry e;
            try {
                e = logsift.core.EntryFormat.around(doc, ef, line, 1000);
            } catch (RuntimeException ex) {
                logsift.core.Problems.unexpected(ex); // a bug, if not a read failure
                e = null;
            }
            logsift.core.EntryFormat.Entry entry = e;
            SwingUtilities.invokeLater(() -> {
                if (detailsRequest == request && entry != null) {
                    details.show(entry, line, size);
                }
            });
        });
    }

    private volatile long detailsRequest;
    private static final int MAX_DETAIL_ROWS = 20_000;
    /** Reads entries for the Details tab (off the UI thread: the disk may be slow). */
    private static final java.util.concurrent.ExecutorService DETAILS =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "logsift-details");
                t.setDaemon(true);
                return t;
            });

    DetailsPanel detailsPanel() {
        return details;
    }

    void showDetailsTab() {
        setBottomCollapsed(false);
        bottom.setSelectedComponent(details);
        updateDetails();
    }

    MessagesPanel messagesPanel() {
        return messages;
    }

    void showMessagesTab() {
        setBottomCollapsed(false);
        bottom.setSelectedComponent(messages);
    }

    /** Insights measure durations and numbers in files up to this size. */
    private static final long AUTO_METRICS_BYTES = 512L << 20;

    /** Files up to this size get their insights by themselves once read. */
    private static final long AUTO_INSIGHTS_BYTES = 2L << 30;

    private void startInsights() {
        insightsGeneration = doc.generation();
        insights.start();
    }

    /** Insights for a file just read (and again after it was replaced); notes when it grew. */
    private void updateInsights() {
        if (insightsGeneration >= 0 && insightsGeneration != doc.generation()) {
            insightsGeneration = -1;
            insights.clear();
        }
        if (!doc.isIndexing() && insights.report() == null && !insights.isRunning() && insightsGeneration < 0
                && doc.fileSize() <= AUTO_INSIGHTS_BYTES && doc.lineCount() > 0) {
            startInsights();
        }
        if (insights.analyzedLines() >= 0) {
            insights.setStale(doc.lineCount() - insights.analyzedLines());
        }
    }

    InsightsPanel insightsPanel() {
        return insights;
    }

    private InsightsPanel.Host insightsHost() {
        return new InsightsPanel.Host() {
            @Override
            public logsift.core.Insights.Report analyze(AtomicBoolean cancel, java.util.function.DoubleConsumer progress,
                                                       java.util.function.Consumer<String> step) {
                // wait until the file is read and its errors, warnings and times are found
                step.accept("Reading the log…");
                long waited = 0;
                while (doc.isIndexing() || detectedGeneration != doc.generation()
                        || errorLines.isRunning() || warningLines.isRunning()
                        || !errorLines.result().isComplete() || !warningLines.result().isComplete()
                        || (timeIndex != null && timeIndex.isRunning())) {
                    if (cancel.get() || closed) {
                        return null;
                    }
                    if (waited > 600_000) {
                        break; // still busy after ten minutes: go with what there is
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    waited += 100;
                }
                step.accept("Grouping messages…");
                long n = doc.lineCount();
                logsift.core.MessageStats.Result m = logsift.core.MessageStats.analyze(doc, entryFormat, logFormat,
                        0, Long.MAX_VALUE, null, cancel::get, progress);
                if (cancel.get()) {
                    return null;
                }
                SwingUtilities.invokeLater(() -> messages.useResult(m)); // the Messages tab needn't count again
                step.accept("Looking for exceptions…");
                logsift.core.Exceptions.Result ex = analyzeExceptions(cancel::get, null);
                if (cancel.get() || ex == null) {
                    return null;
                }
                SwingUtilities.invokeLater(() -> exceptions.useResult(ex, n));
                logsift.core.Metrics me = null;
                if (doc.fileSize() <= AUTO_METRICS_BYTES) { // bigger: when the Metrics tab is opened
                    step.accept("Measuring durations and numbers…");
                    Measured w = measureWhole(m, cancel::get);
                    if (cancel.get() || w == null) {
                        return null;
                    }
                    me = w.base().withColumns(w.columns().series(null)); // Insights: always the whole log
                    logsift.core.Metrics found = metricsView(w);
                    SwingUtilities.invokeLater(() -> metrics.useResult(found));
                }
                return logsift.core.Insights.analyze(logsift.core.LogAnalysis.insightsInput(doc, n, timeFormat,
                        timeIndex, errorLines.result(), warningLines.result(), m), ex, me);
            }

            @Override
            public void goToLine(long line) {
                showLine(line);
                main.focusView();
            }

            @Override
            public void showTimeRange(long from, long to) {
                selectTimeRange(from, to);
            }

            @Override
            public void findAll(logsift.core.MessageStats.Template t) {
                messagesHost().findAll(t);
            }

            @Override
            public void showOnly(logsift.core.MessageStats.Template t) {
                messagesHost().filter(List.of(t), FilterDef.Action.SHOW);
            }

            @Override
            public void hide(logsift.core.MessageStats.Template t) {
                messagesHost().filter(List.of(t), FilterDef.Action.HIDE);
            }

            @Override
            public void findAllRegex(String regex) {
                host.runFindAll(new SearchQuery(regex, true, true, false));
            }

            @Override
            public void openHeapDump(long line) {
                host.openHeapDumpOf(LogTab.this, line);
            }

            @Override
            public void showMetric(long line) {
                setBottomCollapsed(false);
                bottom.setSelectedComponent(metrics);
                metrics.select(line);
            }

            @Override
            public void showException(long line) {
                showExceptionsTab();
                exceptions.select(line);
            }

            @Override
            public void reportChanged(logsift.core.Insights.Report r) {
                int i = bottom.indexOfComponent(insights);
                long problems = r == null ? 0 : r.problems();
                bottom.setTitleAt(i, problems == 0 ? "Insights" : "Insights (" + problems + ")");
                bottom.setIconAt(i, problems == 0 ? null : Icons.of("circle-alert", new java.awt.Color(0xDC2626), 14));
                bottom.setToolTipTextAt(i, r == null ? TabScope.WHOLE : r.summary() + " · " + TabScope.WHOLE);
            }
        };
    }

    // ---------------------------------------------------------------- profile

    ProfilePanel profilePanel() {
        return profile;
    }

    /** The Profile tab: there for a CSV file, or when asked for (other logs with columns). */
    private void updateProfileTab() {
        logsift.core.LogFormat f = logFormat;
        boolean want = f != null && (f.isCsv() || profileAsked);
        int at = bottom.indexOfComponent(profile);
        if (want && at < 0) {
            bottom.insertTab("Profile", null, profile, "What each column holds, with a chart",
                    bottom.indexOfComponent(metrics) + 1);
            titleLabel(profile);
            updateScope();
        } else if (!want && at >= 0) {
            profile.cancel();
            bottom.remove(profile);
        }
        boolean wantPivot = f != null && (f.isCsv() || profileAsked || pivotAsked);
        int pat = bottom.indexOfComponent(pivot);
        if (wantPivot && pat < 0) {
            int after = bottom.indexOfComponent(profile);
            bottom.insertTab("Pivot", null, pivot, "A pivot table and chart of the rows, as in a spreadsheet",
                    (after >= 0 ? after : bottom.indexOfComponent(metrics)) + 1);
            titleLabel(pivot);
            updateScope();
        } else if (!wantPivot && pat >= 0) {
            pivot.cancel();
            bottom.remove(pivot);
        }
    }

    PivotPanel pivotPanel() {
        return pivot;
    }

    QueryPanel queryPanel() {
        return query;
    }

    /** Shows the Query tab. */
    void showQuery() {
        setBottomCollapsed(false);
        bottom.setSelectedComponent(query);
    }

    private QueryPanel.Host queryHost() {
        return new QueryPanel.Host() {
            @Override
            public Path file() {
                return doc.path();
            }

            @Override
            public boolean isTable() {
                logsift.core.LogFormat f = logFormat;
                return f != null && f.isCsv();
            }

            @Override
            public List<String> columns() {
                logsift.core.LogFormat f = logFormat;
                List<String> out = new ArrayList<>(List.of("line"));
                if (f != null && f.isCsv()) {
                    out.addAll(f.headerNames() != null ? f.headerNames() : f.columns());
                    return out;
                }
                out.add("starts");
                out.add("time");
                if (f != null) {
                    for (String c : f.columns()) {
                        if (!c.equals("time") && !c.equals("line") && !c.equals("text") && !c.equals("starts")) {
                            out.add(c);
                        }
                    }
                }
                out.add("text");
                return out;
            }

            @Override
            public String prepare(logsift.core.Sql sql, AtomicBoolean stop, java.util.function.DoubleConsumer progress)
                    throws Exception {
                while (doc.isIndexing() || detectedGeneration != doc.generation()) {
                    if (stop.get() || closed) {
                        return "Stopped.";
                    }
                    Thread.sleep(100);
                }
                logsift.core.LogFormat f = logFormat;
                if (f != null && f.isCsv()) {
                    if (queryCsvFor != f) {
                        sql.csv("t", doc.path(), f.delimiter(), f.headerNames() != null, doc.charset());
                        queryCsvFor = f;
                    }
                    return null;
                }
                if (queryTable == null || queryTable.format() != f) {
                    queryTable = sql.log("log", doc, f, timeFormat, entryFormat);
                }
                return queryTable.update(stop::get, progress) ? null : "Stopped.";
            }

            @Override
            public void goToLine(long line) {
                showLine(line - 1);
                mainView().focusView();
            }
        };
    }

    /** Shows the Pivot tab (added for a log that isn't CSV). */
    void showPivot() {
        pivotAsked = true;
        updateProfileTab();
        if (bottom.indexOfComponent(pivot) >= 0) {
            setBottomCollapsed(false);
            bottom.setSelectedComponent(pivot);
        }
    }

    private PivotPanel.Host pivotHost() {
        return new PivotPanel.Host() {
            /** Waits for the file to be indexed and its format known; false if cancelled. */
            private boolean ready(AtomicBoolean cancel) {
                while (doc.isIndexing() || detectedGeneration != doc.generation()) {
                    if (cancel.get() || closed) {
                        return false;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return logFormat != null;
            }

            @Override
            public logsift.core.Pivot.Fields fields(AtomicBoolean cancel) {
                if (!ready(cancel)) {
                    return null;
                }
                return logsift.core.Pivot.fields(logFormat, doc.lineCount(), doc::readLines,
                        java.time.Year.now().getValue());
            }

            @Override
            public logsift.core.Pivot.Result compute(logsift.core.Pivot.Spec spec, logsift.core.Pivot.Fields fields,
                                                     AtomicBoolean cancel, java.util.function.DoubleConsumer progress) {
                if (!ready(cancel)) {
                    return null;
                }
                FilterResult r = filterResult;
                java.util.function.LongPredicate include = matchingOnly && r.isFiltering() ? r::contains : null;
                return logsift.core.Pivot.compute(logFormat, doc.lineCount(), doc::readLines, include, spec, fields,
                        () -> cancel.get() || closed, progress);
            }

            @Override
            public void filter(String column, logsift.core.ColumnFilter.Op op, String text) {
                filterByColumn(column, op, text, FilterDef.Action.SHOW);
            }

            @Override
            public String rememberKey() {
                return path.toString();
            }
        };
    }

    /** Shows the Profile of the columns (the tab is added for a log that isn't CSV). */
    void showProfile() {
        profileAsked = true;
        updateProfileTab();
        if (bottom.indexOfComponent(profile) >= 0) {
            setBottomCollapsed(false);
            bottom.setSelectedComponent(profile);
        }
    }

    private ProfilePanel.Host profileHost() {
        return new ProfilePanel.Host() {
            @Override
            public logsift.core.ColumnProfile.Result analyze(AtomicBoolean cancel,
                                                             java.util.function.DoubleConsumer progress) {
                while (doc.isIndexing() || detectedGeneration != doc.generation()) {
                    if (cancel.get() || closed) {
                        return null;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                logsift.core.LogFormat f = logFormat;
                if (f == null) {
                    return null;
                }
                FilterResult r = filterResult;
                java.util.function.LongPredicate include = matchingOnly && r.isFiltering() ? r::contains : null;
                return logsift.core.ColumnProfile.analyze(f, doc.lineCount(), doc::readLines, include,
                        java.time.Year.now().getValue(), () -> cancel.get() || closed, progress);
            }

            @Override
            public void filter(String column, logsift.core.ColumnFilter.Op op, String text) {
                filterByColumn(column, op, text, FilterDef.Action.SHOW);
            }

            @Override
            public boolean timesClickable(String column) {
                return column.equals("time") && timeFormat != null;
            }

            @Override
            public boolean timeRange(String column, long from, long to) {
                if (!timesClickable(column)) {
                    return false;
                }
                selectTimeRange(from, to);
                return true;
            }
        };
    }

    // ---------------------------------------------------------------- metrics

    MetricsPanel metricsPanel() {
        return metrics;
    }

    /**
     * Durations of things that start and end, numbers in messages, and the log's columns of
     * numbers and of a few values (null if cancelled).
     */
    logsift.core.Metrics measure(logsift.core.MessageStats.Result m, java.util.function.BooleanSupplier stop) {
        Measured w = measureWhole(m, stop);
        return w == null ? null : w.base().withColumns(w.columns().series(null));
    }

    /**
     * What the whole log measures, kept for this version of the file: filters then only pick
     * from it ({@link #metricsView}), without reading the log again.
     */
    record Measured(logsift.core.Metrics base, logsift.core.ColumnMetrics.Data columns) {}

    /** The version of the log a measurement is for. */
    private record LogVersion(int generation, long lines, logsift.core.LogFormat format,
                              logsift.core.Numbers.Options numbers) {}

    private final logsift.core.WholeLogCache<Measured> measured = new logsift.core.WholeLogCache<>();

    private LogVersion logVersion() {
        return new LogVersion(doc.generation(), doc.lineCount(), logFormat, logsift.core.Numbers.options());
    }

    /** The cached measurements, if they are for the file as it is now. */
    Measured measuredNow() {
        return measured.peek(logVersion());
    }

    /** How often the whole log was measured (for tests). */
    int measureRunsForTest() {
        return measured.computations();
    }

    /** Measures the whole log (templates of the whole log in {@code m}) and keeps the result. */
    private Measured measureWhole(logsift.core.MessageStats.Result m, java.util.function.BooleanSupplier stop) {
        java.util.function.BooleanSupplier halt = () -> stop.getAsBoolean() || closed;
        LogVersion version = logVersion();
        long lines = version.lines();
        logsift.core.LogFormat f = version.format();
        logsift.core.Metrics me = logsift.core.Metrics.analyze(m.templates(), (t, c) -> templateLines(t, halt),
                logsift.core.RequestTrace.reader(doc::line, timeFormat, f), halt);
        if (me == null || halt.getAsBoolean()) {
            return null;
        }
        logsift.core.ColumnMetrics.Data cols = logsift.core.ColumnMetrics.collect(f, lines, doc::line, timeFormat, halt);
        if (halt.getAsBoolean()) {
            return null;
        }
        Measured w = new Measured(me, cols);
        measured.put(version, w);
        return w;
    }

    /**
     * The measurements to show: the whole log's, or with Matching only and filters on, only
     * what the lines the filters keep measure (spans by their start line).
     */
    logsift.core.Metrics metricsView(Measured w) {
        FilterResult r = filterResult;
        java.util.function.LongPredicate include = matchingOnly && r.isFiltering() ? r::contains : null;
        if (include == null) {
            return w.base().withColumns(w.columns().series(null));
        }
        return w.base().keep(include).withColumns(w.columns().series(include));
    }

    /** The first lines of entries of a message kind (null if cancelled). */
    private long[] templateLines(logsift.core.MessageStats.Template t, java.util.function.BooleanSupplier stop) {
        return logsift.core.LogAnalysis.templateLines(doc, entryFormat, t, stop);
    }

    /** Which numbers count as measurements changed (Settings → Metrics): measure again. */
    void metricsOptionsChanged() {
        metrics.reset();
    }

    private MetricsPanel.Host metricsHost() {
        return new MetricsPanel.Host() {
            @Override
            public void openMetricsSettings() {
                host.showSettings(SettingsDialog.METRICS);
            }

            @Override
            public logsift.core.Metrics analyze(AtomicBoolean cancel, java.util.function.Consumer<String> step) {
                while (doc.isIndexing() || detectedGeneration != doc.generation()) {
                    if (cancel.get() || closed) {
                        return null;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                Measured w = measuredNow();
                if (w == null) {
                    step.accept("Grouping messages…");
                    // always the whole log's message kinds, whatever the Messages tab counted
                    logsift.core.MessageStats.Result m = messages.isWholeLogResult() ? messages.result()
                            : logsift.core.MessageStats.analyze(doc, entryFormat, logFormat, 0, Long.MAX_VALUE, null,
                                    cancel::get, null);
                    if (cancel.get()) {
                        return null;
                    }
                    step.accept("Measuring durations and numbers…");
                    w = measureWhole(m, cancel::get);
                    if (w == null) {
                        return null;
                    }
                }
                return metricsView(w);
            }

            @Override
            public void goToLine(long line) {
                showLine(line);
            }

            @Override
            public String describeLine(long line) {
                return messagesHost().describeLine(line);
            }
        };
    }

    // ---------------------------------------------------------------- exceptions

    ExceptionsPanel exceptionsPanel() {
        return exceptions;
    }

    void showExceptionsTab() {
        setBottomCollapsed(false);
        bottom.setSelectedComponent(exceptions);
    }

    /** Finds and groups the log's exceptions (null if cancelled). */
    logsift.core.Exceptions.Result analyzeExceptions(java.util.function.BooleanSupplier stop,
                                                     java.util.function.DoubleConsumer progress) {
        return logsift.core.LogAnalysis.exceptions(doc, entryFormat, () -> stop.getAsBoolean() || closed, progress);
    }

    private ExceptionsPanel.Host exceptionsHost() {
        return new ExceptionsPanel.Host() {
            @Override
            public logsift.core.Exceptions.Result analyze(AtomicBoolean cancel,
                                                          java.util.function.DoubleConsumer progress) {
                while (doc.isIndexing() || detectedGeneration != doc.generation()) {
                    if (cancel.get() || closed) {
                        return null;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return analyzeExceptions(cancel::get, progress);
            }

            @Override
            public void goToLine(long line) {
                showLine(line);
            }

            @Override
            public String describeLine(long line) {
                return messagesHost().describeLine(line);
            }

            @Override
            public void findAll(String regex) {
                host.runFindAll(new SearchQuery(regex, true, true, false));
            }

            @Override
            public void openSource(logsift.core.Exceptions.Frame frame) {
                host.openSource(LogTab.this, frame);
            }

            @Override
            public void resultChanged(logsift.core.Exceptions.Result r) {
                int i = bottom.indexOfComponent(exceptions);
                int kinds = r == null ? 0 : r.groups().size();
                bottom.setTitleAt(i, kinds == 0 ? "Exceptions" : "Exceptions (" + kinds + ")");
            }
        };
    }

    private MessagesPanel.Host messagesHost() {
        return new MessagesPanel.Host() {
            @Override
            public void resultShown() {
                updateScope();
            }

            @Override
            public logsift.core.MessageStats.Result analyze(boolean onlyFiltered,
                    AtomicBoolean cancel, java.util.function.DoubleConsumer progress) {
                FilterResult r = filterResult;
                boolean filtered = onlyFiltered && r.isFiltering();
                long from = filtered ? r.windowStart() : 0;
                long to = filtered ? r.windowEnd() : Long.MAX_VALUE;
                return logsift.core.MessageStats.analyze(doc, entryFormat, logFormat, from, to,
                        filtered ? r::contains : null, cancel::get, progress);
            }

            @Override
            public String describeLine(long line) {
                if (timeFormat != null) {
                    String text = doc.line(line);
                    long t = text == null ? logsift.core.TimeFormat.NONE : timeFormat.parse(text);
                    if (t != logsift.core.TimeFormat.NONE) {
                        return TimelineBar.formatFull(t).substring(5, 19);
                    }
                }
                return String.format("line %,d", line + 1);
            }

            @Override
            public void findAll(logsift.core.MessageStats.Template t) {
                host.runFindAll(new SearchQuery(t.regex(), true, true, false));
                showLine(t.firstLine());
            }

            @Override
            public void filter(List<logsift.core.MessageStats.Template> ts, FilterDef.Action action) {
                StringBuilder re = new StringBuilder();
                for (logsift.core.MessageStats.Template t : ts) {
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
            filtersChanged(); // it shows as a chip above the log
        }
    }

    /** Line colors: filter colors first (first match wins), then the built-in level colors. */
    private void updateHighlights() {
        List<HighlightRule> rules = new ArrayList<>();
        for (FilterDef d : filterPanel.filters()) {
            FilterDef.Swatch s = d.color();
            if (s != null && d.isActive()) {
                logsift.core.ColumnFilter cf = null;
                try {
                    cf = columnFilter(d, d.query());
                } catch (IllegalArgumentException e) {
                    continue; // not active
                }
                rules.add(new HighlightRule("Filter " + d.id, cf == null ? d.query().toPattern() : null,
                        cf == null ? null : cf.lineTest(), null, s.light(), null, s.dark()));
            }
        }

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
        if (results != null) {
            results.refresh(); // searches in the filtered lines follow the filters
        }
        tryPendingLine();
        if (!alerts.isEmpty() || hasAlerts()) {
            checkAlerts();
        }
        updateFilterStatus();
        main.overviewChanged();
        profileFiltersChanged();
        host.tabStatusChanged(this);
    }

    // ---------------------------------------------------------------- which tabs follow the filters

    private ScopeBanner metricsBanner;
    private ScopeBanner profileBanner;
    private ScopeBanner pivotBanner;
    private ScopeBanner messagesBanner;
    private ScopeBanner resultsBanner;
    private final java.util.Map<java.awt.Component, JLabel> tabTitles = new java.util.HashMap<>();

    /** A tab title with room for the filter marker after the name. */
    private void titleLabel(java.awt.Component c) {
        int i = bottom.indexOfComponent(c);
        JLabel l = new JLabel(bottom.getTitleAt(i));
        l.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING); // the icon after the name
        l.setIconTextGap(4);
        tabTitles.put(c, l);
        bottom.setTabComponentAt(i, l);
    }

    private void setBottomTitle(java.awt.Component c, String title) {
        int i = bottom.indexOfComponent(c);
        if (i >= 0) {
            bottom.setTitleAt(i, title);
            JLabel l = tabTitles.get(c);
            if (l != null) {
                l.setText(title);
            }
        }
    }

    /** Marks a tab's title while it counts only the filtered lines. */
    private void markTab(java.awt.Component c, boolean filtered) {
        JLabel l = tabTitles.get(c);
        if (l == null || bottom.indexOfComponent(c) < 0) {
            return;
        }
        boolean was = l.getIcon() != null;
        if (was != filtered) {
            l.setIcon(filtered ? Icons.of("filter", Theme.uiColor("Component.accentColor",
                    new java.awt.Color(0x2563EB)), 12) : null);
        }
        // on the tab, not the label: a label with a tooltip catches the clicks meant for the tab
        bottom.setToolTipTextAt(bottom.indexOfComponent(c), filtered ? TabScope.FOLLOWS : null);
    }

    /** Shows which tabs count only the lines the filters keep (markers and strips), as things are now. */
    void updateScope() {
        if (metricsBanner == null) {
            return;
        }
        FilterResult r = filterResult;
        String text = TabScope.bannerText(r.matchCount(), doc.lineCount());
        boolean follow = TabScope.followsMatchingOnly(matchingOnly, r.isFiltering());
        markTab(metrics, follow);
        metricsBanner.show(follow ? text : null);
        markTab(profile, follow);
        profileBanner.show(follow ? text : null);
        markTab(pivot, follow);
        pivotBanner.show(follow ? text : null);
        boolean msgs = messages.isFilteredResult() && r.isFiltering();
        markTab(messages, msgs);
        messagesBanner.show(msgs ? text : null);
        FindGroup g = results.current();
        boolean finds = g != null && g.inFiltered() && r.isFiltering();
        markTab(results, finds);
        resultsBanner.show(finds ? text : null);
    }

    /** Whether a bottom tab is marked as counting only the filtered lines, and its strip's text (tests). */
    String scopeForTest(java.awt.Component c) {
        JLabel l = tabTitles.get(c);
        ScopeBanner b = c == metrics ? metricsBanner : c == profile ? profileBanner : c == messages ? messagesBanner
                : c == results ? resultsBanner : null;
        return (l != null && l.getIcon() != null ? "marked" : "plain") + " | " + (b == null ? null : b.textForTest());
    }

    ScopeBanner bannerForTest(java.awt.Component c) {
        return c == metrics ? metricsBanner : c == profile ? profileBanner : c == messages ? messagesBanner
                : c == results ? resultsBanner : null;
    }

    /** Runs the selected search again over every line (from the Find results strip). */
    private void findCurrentInAllLines() {
        FindGroup g = results.current();
        if (g != null) {
            results.add(g.query, null);
        }
    }

    /** Tells the Profile which rows it should show now (the filters' when Matching only). */
    private void profileFiltersChanged() {
        FilterResult r = filterResult;
        if (profile != null && (!matchingOnly || !r.isFiltering() || r.isComplete())) {
            profile.filtersChanged(matchingOnly && r.isFiltering() ? "v" + r.version() : "");
        }
        if (pivot != null && (!matchingOnly || !r.isFiltering() || r.isComplete())) {
            pivot.filtersChanged(matchingOnly && r.isFiltering() ? "v" + r.version() : "");
        }
        if (metrics != null && (!matchingOnly || !r.isFiltering() || r.isComplete())) {
            metrics.filtersChanged(matchingOnly && r.isFiltering() ? "v" + r.version() : "");
        }
        updateScope();
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
            filterPanel.setCount(d, filterCount(d));
        }
        if (logBar != null) {
            logBar.setResultText(r.isFiltering() ? text.replaceAll("\\s{3}.*", "") : "");
            logBar.update();
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
            logsift.core.Exceptions.Frame frame = cur < 0 ? null : logsift.core.Exceptions.frameOf(doc.line(cur));
            if (frame != null && frame.file() != null) {
                JMenuItem src = new JMenuItem("Open " + frame.shortText() + " in Editor", Icons.of("code"));
                src.setToolTipText("The source file at this line, from your source folders (Settings → Source Code)");
                src.addActionListener(e -> host.openSource(this, frame));
                menu.add(src);
            }
            String sel = view.selectionForFind();
            List<logsift.core.RequestIds.Id> ids = sel != null ? List.of()
                    : logsift.core.RequestIds.find(cur < 0 ? null : doc.line(cur));
            for (logsift.core.RequestIds.Id id : ids) {
                JMenuItem f = new JMenuItem("Follow " + id.label(), Icons.of("route"));
                f.setToolTipText("Its lines in order, with the time between them, across threads");
                f.addActionListener(e -> followRequest(id.name(), id.value()));
                menu.add(f);
            }
            if (sel != null && sel.strip().length() >= 3 && sel.length() <= 200 && !sel.contains("\n")) {
                String shown = sel.strip().length() > 30 ? sel.strip().substring(0, 30) + "…" : sel.strip();
                JMenuItem f = new JMenuItem("Follow “" + shown + "” Through the Log", Icons.of("route"));
                f.setToolTipText("Its lines in order, with the time between them, across threads");
                f.addActionListener(e -> followRequest("", sel.strip()));
                menu.add(f);
            }
            if (cur >= 0 && logsift.core.HeapDumpMentions.isAbout(doc.line(cur))) {
                JMenuItem hd = new JMenuItem("Open the Heap Dump", Icons.of("memory-stick"));
                hd.setToolTipText("The heap dump the JVM wrote when it ran out of memory: what filled it");
                hd.addActionListener(e -> host.openHeapDumpOf(this, cur));
                menu.add(hd);
            }
            String thread = threadOf(cur);
            if (thread != null) {
                String shown = thread.length() > 40 ? thread.substring(0, 40) + "…" : thread;
                JMenuItem th = new JMenuItem("Show Thread “" + shown + "” in Threads", Icons.of("cpu"));
                th.setToolTipText("Its stack in a thread dump of the JVM that writes this log");
                th.addActionListener(e -> host.showThread(this, thread));
                menu.add(th);
                JMenuItem lines = new JMenuItem("Show Only Lines of Thread “" + shown + "”");
                lines.addActionListener(e -> showThreadLines(thread));
                menu.add(lines);
            }
            menu.addSeparator();
        }
        String sel = view.selectionForFind();
        if (sel == null) {
            JMenuItem hint = new JMenuItem("Select text to filter by it");
            hint.setEnabled(false);
            menu.add(hint);
        } else {
            String shown = sel.length() > 40 ? sel.substring(0, 40) + "…" : sel;
            JMenuItem find = new JMenuItem("Find All “" + shown + "”", Icons.of("search"));
            find.setToolTipText("List every line with this text below, with its count");
            find.addActionListener(e -> host.runFindAll(new SearchQuery(sel, true, false, false)));
            menu.add(find);
            for (FilterDef.Action a : FilterDef.Action.values()) {
                JMenuItem mi = new JMenuItem(a.label + " lines with “" + shown + "”");
                mi.setToolTipText(a.tip);
                mi.addActionListener(e -> addFilter(sel, a));
                menu.add(mi);
            }
        }
        LogView.Cell cell = view.popupCell();
        if (cell != null && !cell.column().equals("message") && !cell.column().equals("fields")
                && !cell.value().isBlank() && cell.value().length() <= 80) {
            String col = columnName(cell.column());
            String v = cell.value().strip();
            String shown = col + " = " + (v.length() > 40 ? v.substring(0, 40) + "…" : v);
            JMenuItem only = new JMenuItem("Show Only Rows Where " + shown, Icons.of("filter"));
            only.setToolTipText("Adds a filter on the " + col + " column");
            only.addActionListener(e -> filterByColumn(cell.column(), logsift.core.ColumnFilter.Op.EQUALS, v,
                    FilterDef.Action.SHOW));
            menu.add(only);
            JMenuItem hide = new JMenuItem("Hide Rows Where " + shown, Icons.of("eye-off"));
            hide.setToolTipText("Adds a filter on the " + col + " column");
            hide.addActionListener(e -> filterByColumn(cell.column(), logsift.core.ColumnFilter.Op.EQUALS, v,
                    FilterDef.Action.HIDE));
            menu.add(hide);
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

    // ---------------------------------------------------------------- following a request

    /**
     * Follows the current line's request: the selected text, else the first trace or request
     * ID on the line. False when there is none.
     */
    boolean followCurrentRequest() {
        String sel = main.selectionForFind();
        if (sel != null && !sel.isBlank() && !sel.contains("\n")) {
            followRequest("", sel.strip());
            return true;
        }
        long cur = currentLine();
        List<logsift.core.RequestIds.Id> ids = logsift.core.RequestIds.find(cur < 0 ? null : doc.line(cur));
        if (ids.isEmpty()) {
            return false;
        }
        followRequest(ids.get(0).name(), ids.get(0).value());
        return true;
    }

    /** Shows the lines with this ID in order in the Request tab. */
    void followRequest(String name, String value) {
        if (request == null) {
            request = new RequestPanel(requestHost());
            bottom.insertTab("Request", Icons.of("route"), request, "One request through the log",
                    bottom.indexOfComponent(results) + 1);
        }
        setBottomCollapsed(false);
        bottom.setSelectedComponent(request);
        request.follow(name, value);
    }

    RequestPanel requestPanel() {
        return request;
    }

    private volatile boolean lastRequestCut;

    /** The lines of a request in this log (null if cancelled). */
    List<logsift.core.RequestTrace.Step> requestSteps(String value, boolean withThreads,
                                                      java.util.function.BooleanSupplier stop) {
        SearchQuery q = new SearchQuery(logsift.core.RequestIds.regex(value), true, true, false);
        SearchSession s = new SearchSession(doc, q).start();
        try {
            while (!s.awaitIdle(200)) {
                if (stop.getAsBoolean()) {
                    return null;
                }
            }
            HitList hits = s.hits();
            int n = hits.size();
            long[] lines = new long[Math.min(n, logsift.core.RequestTrace.MAX_STEPS)];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = hits.get(i);
            }
            lastRequestCut = n > lines.length;
            List<logsift.core.RequestTrace.Step> steps = logsift.core.RequestTrace.steps(lines, n,
                    logsift.core.RequestTrace.reader(doc::line, timeFormat, logFormat), withThreads, stop);
            return stop.getAsBoolean() ? null : steps;
        } finally {
            s.close();
        }
    }

    /** Reads the lines a folder search kept, with the format and times they show. */
    private static logsift.core.RequestTrace.Reader linesReader(List<logsift.core.FolderSearch.Hit> hits,
                                                               List<String> texts) {
        java.util.Map<Long, String> byLine = new java.util.HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            byLine.put(hits.get(i).line(), texts.get(i));
        }
        List<String> sample = new ArrayList<>(texts); // a file may have just one or two lines of the request
        while (!texts.isEmpty() && sample.size() < 10) {
            sample.addAll(texts);
        }
        logsift.core.TimeFormat tf = logsift.core.TimeFormat.detect(sample, java.time.Year.now().getValue());
        logsift.core.LogFormat lf = texts.isEmpty() ? null : logsift.core.LogFormat.detect(sample);
        return logsift.core.RequestTrace.reader(byLine::get, tf, lf);
    }

    private RequestPanel.Host requestHost() {
        return new RequestPanel.Host() {
            @Override
            public logsift.core.RequestTrace.Trace follow(String value, boolean withThreads, RequestPanel.Scope scope,
                                                          Path folder, boolean archives, AtomicBoolean cancel) {
                java.util.function.BooleanSupplier stop = () -> cancel.get() || closed;
                if (scope == RequestPanel.Scope.LOG) {
                    List<logsift.core.RequestTrace.Step> steps = requestSteps(value, withThreads, stop);
                    if (steps == null) {
                        return null;
                    }
                    return logsift.core.RequestTrace.combine(List.of(new logsift.core.RequestTrace.Part(
                            title(), null, steps, lastRequestCut)));
                }
                List<logsift.core.RequestTrace.Part> parts = new ArrayList<>();
                if (scope == RequestPanel.Scope.OPEN) {
                    List<LogTab> logs = new ArrayList<>(host.openLogs());
                    if (!logs.contains(LogTab.this)) {
                        logs.add(0, LogTab.this);
                    }
                    for (LogTab t : logs) {
                        List<logsift.core.RequestTrace.Step> steps = t.requestSteps(value, withThreads, stop);
                        if (steps == null) {
                            return null;
                        }
                        parts.add(new logsift.core.RequestTrace.Part(t.title(), t == LogTab.this ? null : t.path(),
                                steps, t.lastRequestCut));
                    }
                } else {
                    SearchQuery q = new SearchQuery(logsift.core.RequestIds.regex(value), true, true, false);
                    try (logsift.core.FolderSearch fs = new logsift.core.FolderSearch(
                            new logsift.core.FolderSearch.Options(folder, "", true, archives, q)).start()) {
                        while (!fs.awaitDone(200)) {
                            if (stop.getAsBoolean()) {
                                return null;
                            }
                        }
                        for (logsift.core.FolderSearch.FileResult r : fs.results()) {
                            Path file = r.entry() == null ? r.file() : logsift.core.Archives.pathOf(r.file(), r.entry());
                            List<String> texts = new ArrayList<>();
                            for (logsift.core.FolderSearch.Hit h : r.hits()) {
                                texts.add(h.text() == null ? "" : h.text());
                            }
                            logsift.core.RequestTrace.Reader reader = linesReader(r.hits(), texts);
                            long[] lines = new long[r.hits().size()];
                            for (int i = 0; i < lines.length; i++) {
                                lines[i] = r.hits().get(i).line();
                            }
                            boolean here = file.toAbsolutePath().normalize().equals(path);
                            String in = r.file().equals(folder) ? "" : folder.relativize(r.file()).toString();
                            String label = r.entry() == null ? in
                                    : (in.isEmpty() ? "" : in + "/") + r.entry().replace(logsift.core.Archives.NESTED, "/");
                            parts.add(new logsift.core.RequestTrace.Part(label.isEmpty() ? file.getFileName().toString()
                                    : label, here ? null : file,
                                    logsift.core.RequestTrace.steps(lines, lines.length, reader, false, stop),
                                    r.hitCount() > lines.length));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return stop.getAsBoolean() ? null : logsift.core.RequestTrace.combine(parts);
            }

            @Override
            public void goTo(logsift.core.RequestTrace.Step step) {
                if (step.file() == null) {
                    showLine(step.line());
                } else {
                    host.openAt(step.file(), step.line(),
                            new SearchQuery(logsift.core.RequestIds.regex(request.value()), true, true, false));
                }
            }

            @Override
            public Path defaultFolder() {
                logsift.core.Archives.Located in = logsift.core.Archives.locate(path);
                Path p = in != null ? in.zip().getParent() : path.getParent();
                return p != null && java.nio.file.Files.isDirectory(p) ? p : null;
            }

            @Override
            public void showOnly(String value) {
                FilterDef d = filterPanel.addFilter(logsift.core.RequestIds.regex(value), FilterDef.Action.SHOW);
                if (d != null) {
                    d.regex = true;
                    d.matchCase = true;
                    filterPanel.refreshRow(d);
                    filtersChanged();
                    setMatchingOnly(true);
                }
            }

            @Override
            public void findAll(String value) {
                host.runFindAll(new SearchQuery(logsift.core.RequestIds.regex(value), true, true, false));
            }

            @Override
            public String message(String line) {
                logsift.core.LogFormat f = logFormat;
                if (f != null && f.columns().contains("message")) {
                    String m = f.value(line, "message");
                    if (m != null) {
                        return m;
                    }
                }
                return line;
            }
        };
    }

    void showFiltersTab() {
        setBottomCollapsed(false);
        bottom.setSelectedComponent(filterPanel);
    }

    logsift.core.EntryFormat entryFormat() {
        return entryFormat;
    }

    logsift.core.LogFormat logFormat() {
        return logFormat;
    }

    /** Shows lines as columns when the log has a known layout. */
    void setColumnsVisible(boolean on) {
        columnsWanted = on;
        applyColumns();
        syncLogBar();
    }

    boolean isColumnsWanted() {
        return columnsWanted;
    }

    boolean isTreeWanted() {
        return treeWanted;
    }

    // ---------------------------------------------------------------- tree of loggers

    private final ValuesTreePanel treePanel;
    private final JSplitPane side;
    private boolean treeWanted = AppState.prefs().getBoolean("valuesTree", true);
    private int sideDivider = -1;

    /** Shows the tree of loggers (threads, components...) when the log has such a column. */
    void setValuesTreeVisible(boolean on) {
        treeWanted = on;
        updateTreeVisible();
        syncLogBar();
    }

    private void updateTreeVisible() {
        boolean show = treeWanted && !ValuesTreePanel.treeColumns(logFormat).isEmpty();
        if (show == treePanel.isVisible() && side.getDividerSize() > 0 == show) {
            return;
        }
        if (!show && treePanel.isVisible()) {
            sideDivider = side.getDividerLocation();
        }
        treePanel.setVisible(show);
        side.setDividerSize(show ? 5 : 0);
        if (show) {
            side.setDividerLocation(sideDivider > 0 ? sideDivider : treePanel.getPreferredSize().width);
        }
        if (!show) {
            treePanel.showAllValues(); // nothing stays hidden by a tree that isn't there
        }
        side.revalidate();
    }

    ValuesTreePanel valuesTree() {
        return treePanel;
    }

    /** Offers the Threads view when the file has thread dumps in it. */
    private final JPanel dumpsBanner = dumpsBanner();
    private JPanel banners;

    /** Shows the bar naming the log's JVM, above the other banners. */
    void setJvmBar(javax.swing.JComponent bar) {
        bar.setAlignmentX(dumpsBanner.getAlignmentX());
        banners.add(bar, 0);
        banners.revalidate();
    }

    /** The thread a line names (its thread column), or null. */
    String threadOf(long line) {
        if (line < 0 || logFormat == null || !logFormat.columns().contains("thread")) {
            return null;
        }
        String[] buf = new String[1];
        doc.readLines(line, 1, buf);
        String t = buf[0] == null ? null : logFormat.value(buf[0], "thread");
        return t == null || t.isBlank() ? null : t.strip();
    }

    /**
     * Shows only the lines a thread wrote. The log may have cut the name short (Spring Boot
     * keeps the last 15 characters), so the form it uses is looked up first.
     */
    void showThreadLines(String thread) {
        String logged = loggedThreadName(thread);
        String q = logged.replaceAll("[\\\\.^$|?*+()\\[\\]{}]", "\\\\$0"); // readable in the filter
        boolean bracketed = logFormat != null && (logFormat.regex().contains("\\[(?<thread>")
                || logFormat.regex().contains("\\[\\s*(?<thread>"));
        String regex = bracketed ? "\\[\\s*" + q + "\\s*\\]" : "(?<![\\w-])" + q + "(?![\\w-])";
        FilterDef d = filterPanel.addFilter(regex, FilterDef.Action.SHOW);
        if (d != null) {
            d.matchCase = true;
            d.regex = true;
            filterPanel.refreshRow(d);
            filtersChanged();
            bottom.setSelectedComponent(filterPanel);
        }
        setMatchingOnly(true);
    }

    /** How this log writes a thread's name: as it is, or cut short; the name itself if it can't tell. */
    String loggedThreadName(String thread) {
        if (logFormat == null || !logFormat.columns().contains("thread")) {
            return thread;
        }
        long n = Math.min(doc.lineCount(), 5_000);
        String[] buf = new String[(int) Math.min(n, 2048)];
        String best = null;
        for (long i = 0; i < n; i += buf.length) {
            int c = (int) Math.min(buf.length, n - i);
            doc.readLines(i, c, buf);
            for (int j = 0; j < c; j++) {
                String v = buf[j] == null ? null : logFormat.value(buf[j], "thread");
                if (v == null) {
                    continue;
                }
                v = v.strip();
                if (v.equals(thread)) {
                    return thread;
                }
                if (v.length() >= 6 && (thread.endsWith(v) || thread.startsWith(v))
                        && (best == null || v.length() > best.length())) {
                    best = v;
                }
            }
        }
        return best != null ? best : thread;
    }

    private JPanel dumpsBanner() {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Component.borderColor", java.awt.Color.GRAY)),
                javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 8)));
        JLabel text = new JLabel("This file has thread dumps (Java or Python). See deadlocks, threads waiting for locks, and stuck threads.",
                Icons.of("cpu"), javax.swing.SwingConstants.LEFT);
        text.setIconTextGap(8);
        p.add(text, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        javax.swing.JButton analyze = new javax.swing.JButton("Analyze Threads");
        analyze.addActionListener(e -> host.analyzeThreads(this));
        javax.swing.JButton close = new javax.swing.JButton(Icons.of("x"));
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Hide");
        close.addActionListener(e -> {
            dumpsDismissed = true;
            p.setVisible(false);
            revalidate();
        });
        buttons.add(analyze);
        buttons.add(close);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    /** Offers to explain a GC log (or the GC lines in a log). */
    private final JPanel gcBanner = gcBanner();

    private JPanel gcBanner() {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Component.borderColor", java.awt.Color.GRAY)),
                javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 8)));
        JLabel text = new JLabel("This is a Java GC log. See how long the program stood still, what the pauses were,"
                + " whether memory keeps growing, and what to tune.", Icons.of("gauge"), javax.swing.SwingConstants.LEFT);
        text.setIconTextGap(8);
        p.add(text, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        javax.swing.JButton analyze = new javax.swing.JButton("Analyze GC");
        analyze.addActionListener(e -> host.analyzeGc(this));
        javax.swing.JButton close = new javax.swing.JButton(Icons.of("x"));
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Hide");
        close.addActionListener(e -> {
            p.setVisible(false);
            revalidate();
        });
        buttons.add(analyze);
        buttons.add(close);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    private final JPanel crashBanner = crashBanner();

    private JPanel crashBanner() {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Component.borderColor", java.awt.Color.GRAY)),
                javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 8)));
        JLabel text = new JLabel("This is a Java crash report. See how the JVM crashed, the code to blame, which threads "
                + "and libraries may have caused it, and what to try.", Icons.of("octagon-alert"), javax.swing.SwingConstants.LEFT);
        text.setIconTextGap(8);
        p.add(text, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        javax.swing.JButton explain = new javax.swing.JButton("Explain the Crash");
        explain.addActionListener(e -> host.explainCrash(this));
        javax.swing.JButton close = new javax.swing.JButton(Icons.of("x"));
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Hide");
        close.addActionListener(e -> {
            p.setVisible(false);
            revalidate();
        });
        buttons.add(explain);
        buttons.add(close);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    boolean crashBannerShownForTest() {
        return crashBanner.isVisible();
    }

    boolean gcBannerShownForTest() {
        return gcBanner.isVisible();
    }

    javax.swing.JButton gcAnalyzeButtonForTest() {
        return (javax.swing.JButton) ((JPanel) gcBanner.getComponent(1)).getComponent(0);
    }

    boolean dumpsBannerShownForTest() {
        return dumpsBanner.isVisible();
    }

    private long dumpsCheckedTo = -1;      // lines looked at for a dump (after the start)
    private boolean dumpsDismissed;
    private volatile boolean dumpsChecking;

    /** A dump written while the log is followed (kill -3 on a running program): offer it too. */
    private void checkNewLinesForDumps() {
        if (dumpsBanner.isVisible() || dumpsDismissed || doc.isIndexing() || dumpsChecking) {
            return;
        }
        long n = doc.lineCount();
        if (dumpsCheckedTo < 0 || n < dumpsCheckedTo) {
            dumpsCheckedTo = n; // the start of the file was looked at already
            return;
        }
        if (n == dumpsCheckedTo) {
            return;
        }
        long from = Math.max(dumpsCheckedTo, n - 50_000);
        dumpsCheckedTo = n;
        dumpsChecking = true;
        Thread t = new Thread(() -> {
            try {
                String[] buf = new String[2048];
                for (long i = from; i < n; i += buf.length) {
                    int c = (int) Math.min(buf.length, n - i);
                    doc.readLines(i, c, buf);
                    for (int j = 0; j < c; j++) {
                        if (buf[j] != null && logsift.jvm.DumpParser.startsDump(buf[j])) {
                            SwingUtilities.invokeLater(() -> {
                                if (!dumpsDismissed) {
                                    dumpsBanner.setVisible(true);
                                    revalidate();
                                }
                            });
                            return;
                        }
                    }
                }
            } catch (RuntimeException e) {
                logsift.core.Problems.unexpected(e); // a bug, if not a read failure
                // no offer then
            } finally {
                dumpsChecking = false;
            }
        }, "logsift-dump-check");
        t.setDaemon(true);
        t.start();
    }

    /** Looks at the start of the file for a thread dump (in the background). */
    private void checkForThreadDumps() {
        Thread t = new Thread(() -> {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(doc.path())) {
                byte[] head = in.readNBytes(512 * 1024);
                String text = new String(head, doc.charset());
                if (logsift.jvm.DumpParser.looksLikeDump(text)) {
                    SwingUtilities.invokeLater(() -> {
                        dumpsBanner.setVisible(true);
                        revalidate();
                    });
                }
                if (logsift.crash.HsErr.looksLike(text.substring(0, Math.min(text.length(), 4096)))) {
                    SwingUtilities.invokeLater(() -> {
                        crashBanner.setVisible(true);
                        revalidate();
                    });
                }
                if (logsift.jvm.GcLog.looksLikeGcLog(text)) {
                    SwingUtilities.invokeLater(() -> {
                        gcBanner.setVisible(true);
                        revalidate();
                    });
                }
            } catch (IOException | RuntimeException e) {
                logsift.core.Problems.unexpected(e); // a bug, if not a read failure
                // no offer then
            }
        }, "logsift-dump-check");
        t.setDaemon(true);
        t.start();
    }

    /** Shown in Columns view when the log's layout isn't known. */
    private final JPanel noColumns = noColumnsBanner();

    private JPanel noColumnsBanner() {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Component.borderColor", java.awt.Color.GRAY)),
                javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 8)));
        p.add(new JLabel("Logsift doesn't know this log's layout, so it can't show it as columns."), BorderLayout.CENTER);
        javax.swing.JButton define = new javax.swing.JButton("Define Its Format…");
        define.addActionListener(e -> host.defineFormat(this));
        p.add(define, BorderLayout.EAST);
        return p;
    }

    boolean noColumnsShownForTest() {
        return noColumns.isVisible();
    }

    private void applyColumns() {
        logsift.core.LogFormat f = logFormat;
        boolean missing = columnsWanted && f == null && detectedGeneration >= 0;
        if (noColumns.isVisible() != missing) {
            noColumns.setVisible(missing);
            revalidate();
        }
        java.util.Set<String> hide = f == null ? java.util.Set.of() : hiddenColumns();
        ColumnLayout layout = columnsWanted && f != null ? new ColumnLayout(f, sourceLabels != null, hide) : null;
        if (layout != null) {
            layout.copyWidths(main.columns());
        }
        main.setColumns(layout);
        ColumnLayout forResults = layout == null ? null : new ColumnLayout(f, sourceLabels != null, hide);
        if (forResults != null) {
            forResults.copyWidths(results.view().columns());
        }
        results.view().setColumns(forResults);
    }

    /**
     * The menu of a column header ({@code col} null: the "hidden" chip): hide this column,
     * show or hide each column, show them all again.
     */
    private void showColumnMenu(String col, java.awt.event.MouseEvent e) {
        ColumnLayout layout = main.columns();
        if (layout == null) {
            return;
        }
        JPopupMenu m = new JPopupMenu();
        if (col != null) {
            String title = columnName(col);
            JMenuItem count = new JMenuItem("Count Values in " + title + "…", Icons.of("chart-column"));
            count.addActionListener(a -> countValues(col));
            m.add(count);
            JMenuItem prof = new JMenuItem("Profile of the Columns", Icons.of("chart-column"));
            prof.setToolTipText("What each column holds, with a chart (the Profile tab below)");
            prof.addActionListener(a -> showProfile());
            m.add(prof);
            JMenuItem piv = new JMenuItem("Pivot Table and Chart", Icons.of("chart-column"));
            piv.setToolTipText("Group the rows, sum up the values and chart them (the Pivot tab below)");
            piv.addActionListener(a -> showPivot());
            m.add(piv);
            JMenuItem filter = new JMenuItem("Filter by " + title + "…", Icons.of("filter"));
            filter.setToolTipText("A filter that looks only in this column: contains, =, or > < for numbers");
            filter.addActionListener(a -> addColumnFilter(col));
            m.add(filter);
            if (!col.equals("message")) {
                JMenuItem hide = new JMenuItem("Hide " + title + " Column", Icons.of("eye-off"));
                hide.addActionListener(a -> setColumnHidden(col, true));
                m.add(hide);
            }
            m.addSeparator();
        }
        JMenuItem label = new JMenuItem("Columns");
        label.setEnabled(false);
        m.add(label);
        java.util.Set<String> hidden = hiddenColumns();
        for (String c : layout.format().columns()) {
            if (c.equals("message") || (c.equals("source") && sourceLabels == null && !layout.format().isCsv())) {
                continue;
            }
            javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(
                    columnName(c), !hidden.contains(c));
            item.addActionListener(a -> setColumnHidden(c, !item.isSelected()));
            m.add(item);
        }
        m.addSeparator();
        JMenuItem all = new JMenuItem(hidden.isEmpty() ? "Show All Columns"
                : "Show All Columns (" + hidden.size() + " hidden)", Icons.of("eye"));
        all.setEnabled(!hidden.isEmpty());
        all.addActionListener(a -> {
            AppState.prefs().remove(hiddenKey());
            applyColumns();
        });
        m.add(all);
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    private String hiddenKey() {
        logsift.core.LogFormat f = logFormat;
        String k = "hiddenColumns." + (f == null ? "" : f.name());
        return k.length() > 80 ? k.substring(0, 80) : k; // preference keys are limited
    }

    /** Columns hidden in this tab's format (remembered per format). */
    java.util.Set<String> hiddenColumns() {
        String v = AppState.prefs().get(hiddenKey(), "");
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String s : v.split(",")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    void setColumnHidden(String column, boolean hide) {
        java.util.Set<String> h = hiddenColumns();
        if (hide) {
            h.add(column);
        } else {
            h.remove(column);
        }
        if (h.isEmpty()) {
            AppState.prefs().remove(hiddenKey());
        } else {
            AppState.prefs().put(hiddenKey(), String.join(",", h));
        }
        applyColumns();
    }

    /** Counts the values of a column in the background and shows them. */
    void countValues(String column) {
        logsift.core.LogFormat f = logFormat;
        if (f == null) {
            return;
        }
        ValuesDialog.show(this, f, column);
    }

    /** Shows only (or hides) lines whose column has this exact value. */
    void filterByValue(String column, String value, FilterDef.Action action) {
        if (column != null && columns().contains(column) && !value.isBlank()) {
            filterByColumn(column, logsift.core.ColumnFilter.Op.EQUALS, value, action);
            return;
        }
        addFilter(value, action);
        if (action != FilterDef.Action.MARK) {
            setMatchingOnly(true);
        }
    }

    /** Lines the log view shows now: matching lines in "Matching only", else every line. */
    logsift.core.LineExporter.Runs visibleRuns() {
        FilterResult r = filterResult;
        long n = doc.lineCount();
        return matchingOnly ? c -> r.forEachRun(n, c) : c -> c.run(0, n);
    }

    /** Lines that pass the filters (with context lines), whatever the view shows. */
    logsift.core.LineExporter.Runs filteredRuns() {
        FilterResult r = filterResult;
        long n = doc.lineCount();
        return c -> r.forEachRun(n, c);
    }

    boolean isFiltering() {
        return filterResult.isFiltering();
    }

    /** The selected lines of the log view (or the current line). */
    logsift.core.LineExporter.Runs selectionRuns() {
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
        if (formatChoice != null) {
            p.setProperty("state.format", formatChoice);
        }
        if (treePanel.hiddenColumn() != null) {
            p.setProperty("state.tree.column", treePanel.hiddenColumn());
            StringBuilder sb = new StringBuilder();
            for (logsift.core.ValueTree.Hidden h : treePanel.hidden()) {
                sb.append(h.withChildren() ? '*' : '=').append(h.path()).append('\n');
            }
            p.setProperty("state.tree.hidden", sb.toString());
        }
        return p;
    }

    /** Applies remembered state, unless the file got smaller since (then lines moved). */
    void restoreState(java.util.Properties p) {
        String format = p.getProperty("state.format"); // kept even when the file got smaller
        if (format != null) {
            setFormatChoice(format);
        }
        try {
            long size = Long.parseLong(p.getProperty("state.size", "0"));
            if (doc.fileSize() < size) {
                return;
            }
            filterPanel.fromProperties(p);
            String treeColumn = p.getProperty("state.tree.column");
            if (treeColumn != null) {
                List<logsift.core.ValueTree.Hidden> h = new ArrayList<>();
                for (String s : p.getProperty("state.tree.hidden", "").split("\n")) {
                    if (s.length() > 1) {
                        h.add(new logsift.core.ValueTree.Hidden(s.substring(1), s.charAt(0) == '*'));
                    }
                }
                treePanel.restoreHidden(treeColumn, h);
            }
            setMatchingOnly(Boolean.parseBoolean(p.getProperty("state.matchingOnly", "false")));
            showLineWhenAvailable(Long.parseLong(p.getProperty("state.line", "0")));
        } catch (IOException | RuntimeException e) {
            // unreadable state: start fresh
        }
    }

    /** Whether this tab's state should be remembered (not for temporary merged files). */
    boolean remembersState() {
        return sourceLabels == null && remoteLabel == null && origin == null;
    }

    /** Shows or hides the Filters / Find results area. */
    void toggleBottom() {
        setBottomCollapsed(!bottomCollapsed);
    }

    private boolean bottomCollapsed;
    private int bottomHeight = 230; // its height when open, to come back to
    private final javax.swing.JButton collapseButton = new javax.swing.JButton();

    JTabbedPane bottomTabsForTest() {
        return bottom;
    }

    int bottomHeightForTest() {
        return split.getHeight() - split.getDividerLocation() - split.getDividerSize();
    }

    boolean isBottomCollapsed() {
        return bottomCollapsed;
    }

    /** Folds the bottom panel down to its tab strip, or opens it again. */
    void setBottomCollapsed(boolean collapse) {
        int total = split.getHeight();
        if (collapse == bottomCollapsed || total <= 0) {
            bottomCollapsed = total > 0 ? bottomCollapsed : collapse;
            updateCollapseButton();
            return;
        }
        if (collapse) {
            bottomHeight = Math.max(120, total - split.getDividerLocation() - split.getDividerSize());
            split.setDividerLocation(total - split.getDividerSize() - tabStripHeight());
        } else {
            split.setDividerLocation(Math.max(80, total - split.getDividerSize() - bottomHeight));
        }
        bottomCollapsed = collapse;
        updateCollapseButton();
    }

    private int tabStripHeight() {
        java.awt.Rectangle r = bottom.getTabCount() > 0 ? bottom.getBoundsAt(0) : null;
        return r == null ? 30 : r.y + r.height + 1;
    }

    private void updateCollapseButton() {
        collapseButton.setText(bottomCollapsed ? "▴" : "▾");
        collapseButton.setToolTipText(bottomCollapsed ? "Open this panel (Ctrl+J)" : "Fold this panel away (Ctrl+J)");
    }

    private void setUpCollapsing() {
        collapseButton.putClientProperty("JButton.buttonType", "toolBarButton");
        collapseButton.setFocusable(false);
        collapseButton.addActionListener(e -> toggleBottom());
        updateCollapseButton();
        JPanel trailing = new JPanel(new BorderLayout()); // takes the free space: button at the far right
        trailing.setOpaque(false);
        trailing.add(collapseButton, BorderLayout.EAST);
        bottom.putClientProperty("JTabbedPane.trailingComponent", trailing);
        bottom.setMinimumSize(new Dimension(0, 0));
        // clicking a tab opens a folded panel; double-clicking a tab folds it
        bottom.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (bottom.indexAtLocation(e.getX(), e.getY()) < 0) {
                    return;
                }
                if (bottomCollapsed) {
                    SwingUtilities.invokeLater(() -> setBottomCollapsed(false));
                } else if (e.getClickCount() == 2) {
                    setBottomCollapsed(true);
                }
            }
        });
        // dragging the divider all the way down counts as folding
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            int h = split.getHeight() - split.getDividerLocation() - split.getDividerSize();
            boolean folded = split.getHeight() > 0 && h <= tabStripHeight() + 6;
            if (folded != bottomCollapsed) {
                bottomCollapsed = folded;
                updateCollapseButton();
            }
        });
    }

    // ---------------------------------------------------------------- search

    /**
     * Starts a Find All. It goes on top of the results window, open; older searches fold away
     * but stay. Results appear in line order while searching; new lines are searched as the
     * file grows.
     *
     * @throws java.util.regex.PatternSyntaxException for an invalid regex
     */
    FindGroup findAll(SearchQuery q) {
        // while the log shows only the filtered lines, find in those (as the filters are then and later)
        FindGroup g = results.add(q, matchingOnly && filterResult.isFiltering()
                ? line -> filterResult.contains(line) : null);
        setBottomCollapsed(false);
        bottom.setSelectedComponent(results);
        return g;
    }

    /** Searches within a search's results. */
    FindGroup findWithin(FindGroup parent, SearchQuery q) {
        return results.addWithin(parent, q);
    }

    private ResultsPanel.Host resultsHost() {
        return new ResultsPanel.Host() {
            @Override
            public void showLine(long line) {
                LogTab.this.showLine(line);
            }

            @Override
            public void focusLog() {
                main.focusView();
            }

            @Override
            public void currentChanged() {
                updateScope();
            }

            @Override
            public void resultsChanged() {
                updateSearchHighlights();
                int n = results.groups().size();
                setBottomTitle(results,
                        n == 0 ? "Find results" : n == 1 ? String.format("Find results (%,d)",
                                results.groups().get(0).count()) : "Find results (" + n + " searches)");
                updateScope();
                host.tabStatusChanged(LogTab.this);
            }

            @Override
            public void extendPopup(LogView view, JPopupMenu menu) {
                LogTab.this.extendPopup(view, menu);
            }
        };
    }

    /** Whether a filter, a time range, the tree or a search is set (what Clear All clears). */
    boolean hasAnythingToClear() {
        for (FilterDef d : filterPanel.filters()) {
            if (!d.text.isEmpty()) {
                return true;
            }
        }
        return filterPanel.hasActiveFilters() || timeRange() != null || treeHiddenCount() > 0 || matchingOnly
                || (findBar != null && findBar.isVisible() && !findBar.text().isEmpty())
                || (results != null && !results.groups().isEmpty());
    }

    /**
     * Clear All: back to the whole log. Every filter, the time range and what the tree hides go;
     * the find bar is emptied and closed, and the Find results are closed.
     */
    void clearEverything() {
        filterPanel.clearAll(); // also the time range, the tree and Matching only
        if (findBar != null) {
            findBar.clear();
        }
        if (results != null) {
            results.closeAll();
        }
        if (logBar != null) {
            logBar.updateClearAll();
        }
        main.focusView();
    }

    /** Matches of Find All searches and of the find bar, in the log and its overview. */
    private void updateSearchHighlights() {
        List<LogView.Highlight> hl = new ArrayList<>();
        for (FindGroup g : results.groups()) {
            hl.add(g.highlight());
        }
        if (logBar != null) {
            logBar.updateClearAll();
        }
        LogView.Highlight quick = findBar == null ? null : findBar.highlight();
        if (quick != null) {
            hl.add(quick);
        }
        main.setHighlights(hl);
        main.overviewChanged();
    }

    // ---------------------------------------------------------------- find bar and log bar

    FindBar findBar() {
        return findBar;
    }

    LogBar logBar() {
        return logBar;
    }

    /** Opens the find bar (Ctrl+F); text selected in the log becomes the search. */
    void openFindBar(String text) {
        if (text == null && main.isViewFocused()) {
            text = main.selectionForFind();
        }
        findBar.open(text);
    }

    /** Opens the Filters tab below the log. */
    void showFilters() {
        setBottomCollapsed(false);
        bottom.setSelectedComponent(filterPanel);
    }

    void updateReloadButton() {
        logBar.updateReload(doc);
    }

    /** "10:02:13 – 10:05:00" of the selected time range, or "". */
    String timeRangeText() {
        long[] r = timeRange;
        if (r == null) {
            return "";
        }
        String a = TimelineBar.formatFull(r[0]).replaceFirst("\\.000$", "");
        String b = TimelineBar.formatFull(r[1]).replaceFirst("\\.000$", "");
        if (a.length() > 11 && b.length() > 11 && a.substring(0, 11).equals(b.substring(0, 11))) {
            b = b.substring(11); // same day
        }
        return a + " – " + b;
    }

    /** How many branches are unchecked in the tree of loggers. */
    int treeHiddenCount() {
        return treePanel == null || treePanel.hiddenColumn() == null ? 0 : treePanel.hidden().size();
    }

    void showAllTreeValues() {
        treePanel.showAllValues();
    }

    /** A filter's number of matching lines ("1,234", "invalid", or "" when off). */
    String filterCount(FilterDef d) {
        SearchSession s = d.isActive() ? engine.session(d.id) : null;
        if (d.error != null) {
            return "invalid";
        }
        if (s == null) {
            return "";
        }
        String c = String.format("%,d", s.hits().size()) + (s.isRunning() ? "…" : "");
        return s.isLimitReached() ? c + "+" : c;
    }

    private FindBar.Host findBarHost() {
        return new FindBar.Host() {
            @Override
            public LogDocument document() {
                return doc;
            }

            @Override
            public long currentLine() {
                return LogTab.this.currentLine();
            }

            @Override
            public long firstVisibleLine() {
                long row = Math.max(0, main.topRow());
                return matchingOnly ? (filterResult.count() == 0 ? 0
                        : filterResult.lineAt(Math.min(row, filterResult.count() - 1))) : row;
            }

            @Override
            public void showLine(long line) {
                LogTab.this.showLine(line);
            }

            @Override
            public void searchChanged() {
                updateSearchHighlights();
            }

            @Override
            public void findAll(SearchQuery q) {
                host.runFindAll(q);
            }

            @Override
            public void filter(SearchQuery q, FilterDef.Action action) {
                FilterDef d = filterPanel.addFilter(q.text(), action);
                if (d != null) {
                    d.matchCase = q.matchCase();
                    d.regex = q.regex();
                    d.wholeWord = q.wholeWord();
                    filterPanel.refreshRow(d);
                    filtersChanged();
                    if (action != FilterDef.Action.MARK) {
                        setMatchingOnly(true);
                    }
                }
            }

            @Override
            public void searchInFolder(String text) {
                host.searchInFolder(text);
            }

            @Override
            public void closed() {
                main.focusView();
            }
        };
    }

    /** Stops the selected search (results so far are kept). */
    void stopSearch() {
        FindGroup f = results.current();
        if (f != null) {
            f.session().close();
        }
    }

    /** Closes the selected search's results. */
    void closeResults() {
        results.closeCurrent();
        if (results.groups().isEmpty()) {
            bottom.setSelectedComponent(filterPanel);
        }
    }

    /** Jumps to the selected search's next (or previous) hit after the current line. */
    boolean findNext(boolean forward) {
        FindGroup f = results.current();
        if (f == null || f.count() == 0) {
            return false;
        }
        int n = f.count();
        long cur = currentLine();
        if (cur < 0) {
            cur = matchingOnly ? -1 : main.topRow() - 1;
        }
        int idx;
        if (forward) {
            idx = f.firstAtOrAfter(cur + 1);
            if (idx >= n) {
                idx = 0; // wrap
            }
        } else {
            idx = f.firstAtOrAfter(cur) - 1;
            if (idx < 0) {
                idx = n - 1;
            }
        }
        long line = f.lineAt(idx);
        results.selectResult(f, idx);
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

    private boolean hasAlerts() {
        for (FilterDef d : filterPanel.filters()) {
            if (d.alert) {
                return true;
            }
        }
        return false;
    }

    private volatile boolean closed;

    void close() {
        closed = true;
        treePanel.dispose();
        if (alertTimer != null) {
            alertTimer.stop();
        }
        closeHooks.forEach(Runnable::run);
        results.closeAllSessions();
        findBar.dispose();
        messages.cancel();
        insights.cancel();
        exceptions.cancel();
        metrics.cancel();
        profile.cancel();
        pivot.cancel();
        query.close();
        if (request != null) {
            request.cancel();
        }
        engine.close();
        if (timeIndex != null) {
            timeIndex.close();
        }
        errorLines.close();
        warningLines.close();
        doc.close();
    }
}
