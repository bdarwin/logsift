package logsift.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Records a tour of Logsift's features (about two and a half minutes): a 1 GB log, find and
 * filters, exceptions of Java, .NET and Python, a request, CSV tables (a pivot, SQL, comparing
 * two exports), a running JVM over JMX, a GC log and a heap dump. The assistant has a video of
 * its own ({@link AssistantRecording}).
 *
 * <p>Run with {@code ./gradlew visualTest --tests logsift.ui.TourRecording -Pdemo=/some/dir};
 * needs ffmpeg on the PATH.
 */
@Tag("visual")
@EnabledIfSystemProperty(named = "logsift.demo", matches = ".+")
class TourRecording extends Recording {

    @Override
    String videoName() {
        return "logsift-tour.mp4";
    }

    @Test
    void record() throws Exception {
        Path dir = Files.createDirectories(out.resolve("logs"));
        Path big = dir.resolve("orders-1gb.log");
        if (!Files.exists(big) || Files.size(big) < 1L << 30) {
            logsift.tools.LogGenerator.generate(big, 1L << 30, 42);
        }
        Path errors = dir.resolve("orders-errors.log");
        TourData.writeErrors(errors);
        Path dotnet = dir.resolve("shop-api.log");
        Files.writeString(dotnet, logsift.tools.TestData.serilog());
        Path python = dir.resolve("checkout-api.log");
        Files.writeString(python, logsift.tools.TestData.pythonApi());
        Path requests = dir.resolve("shop-requests.log");
        long slow = TourData.writeRequests(requests);
        Path sales = dir.resolve("sales.csv");
        TourData.writeSales(sales);
        AppState.prefs().remove("pivot." + Integer.toHexString(sales.toString().hashCode()));
        Path monday = dir.resolve("orders-monday.csv");
        Path tuesday = dir.resolve("orders-tuesday.csv");
        TourData.writeOrders(monday, tuesday);
        Path gc = TourData.gcLog(dir);
        Path hprof = TourData.heapDump(dir);
        int port = freePort();
        Process jvm = TourData.jvmWithJmx(port, dir.resolve("billing.log"));
        try {
            openWindow();
            startVideo();
            try {
                script(big, errors, dotnet, python, requests, slow, sales, monday, tuesday, port, gc, hprof);
            } finally {
                stopVideo();
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        } finally {
            logsift.jvm.FixtureApp.stop(jvm);
        }
    }

    private void script(Path big, Path errors, Path dotnet, Path python, Path requests, long slow, Path sales,
            Path monday, Path tuesday, int port, Path gc, Path hprof) throws Exception {
        say("Logsift: find out what your app did, from its logs to its threads");
        hold(2800);

        // ---- a 1 GB log
        StartPage start = onEdt(frame::startPageForTest);
        moveTo(onEdt(() -> start.actionForTest("Open Log…")), 700);
        ripple = 0;
        say("Open a 1 GB log ...");
        hold(500);
        long t0 = System.nanoTime();
        LogTab tab = open(big);
        double secs = (System.nanoTime() - t0) / 1e9;
        long lines = onEdt(() -> tab.document().lineCount());
        say(String.format(Locale.ROOT, "... %,d lines, ready in %.1f s. Scrolling stays smooth at any size", lines, secs));
        moveTo(tab.mainView(), 700);
        for (long row : new long[] {lines / 3, lines / 2, lines * 4 / 5, 0}) {
            onEdt(() -> {
                tab.showLine(row);
                return null;
            });
            hold(650);
        }
        still("1gb-log");

        // ---- find and filters
        say("⌘F: matches light up as you type; Find All counts them over the whole gigabyte");
        press(MainFrame.isMac() ? "⌘ F" : "Ctrl F");
        onEdt(() -> {
            frame.openFind();
            return null;
        });
        hold(500);
        type(tab.findBar(), "Payment failed");
        waitUntil(() -> tab.findBar().countText().contains(" of ") && !tab.findBar().session().isRunning(), 60_000);
        AbstractButton all = button(tab.findBar(), "Find All");
        moveTo(all, 500);
        click(all);
        waitUntil(() -> tab.currentFind() != null);
        hold(2000);
        still("find-all");
        say("Filters: errors only, as a chip; the timeline shows when they happened");
        AbstractButton filters = button(tab.logBar(), "Filters");
        moveTo(filters, 500);
        click(filters);
        onEdt(() -> {
            tab.filterPanel().setLevels("E");
            return null;
        });
        waitUntil(() -> !tab.filterEngine().isRunning(), 120_000);
        moveTo(onEdt(tab::timeline), 700);
        hold(2200);

        // ---- exceptions: Java, .NET, Python
        say("Exceptions grouped by root cause and where in your code: Java ...");
        LogTab err = open(errors);
        waitUntil(() -> err.exceptionsPanel().result() != null, 60_000);
        onEdt(() -> {
            err.showExceptionsTab();
            return null;
        });
        hold(3200);
        still("exceptions-java");
        say("... .NET (Serilog, NLog, log4net, ASP.NET Core): the inner exception is the cause ...");
        LogTab net = open(dotnet);
        waitUntil(() -> net.exceptionsPanel().result() != null, 60_000);
        onEdt(() -> {
            net.showExceptionsTab();
            net.exceptionsPanel().tableForTest().setRowSelectionInterval(0, 0);
            return null;
        });
        hold(3500);
        still("exceptions-dotnet");
        say("... and Python tracebacks, chained ones too");
        LogTab py = open(python);
        waitUntil(() -> py.exceptionsPanel().result() != null, 60_000);
        onEdt(() -> {
            py.showExceptionsTab();
            py.exceptionsPanel().tableForTest().setRowSelectionInterval(0, 0);
            return null;
        });
        hold(3200);
        still("exceptions-python");

        // ---- a request
        say("Follow one request across threads: the 8-second wait for the bank stands out");
        LogTab req = open(requests);
        waitUntil(() -> req.logFormat() != null);
        onEdt(() -> {
            req.showLine(slow);
            return null;
        });
        hold(800);
        onEdt(req::followCurrentRequest);
        waitUntil(() -> req.requestPanel() != null && !req.requestPanel().isRunning() && req.requestPanel().trace() != null);
        hold(3800);
        still("request");

        // ---- CSV
        say("CSV files open as tables: a pivot table and its chart, as in a spreadsheet");
        LogTab csv = open(sales);
        PivotPanel pp = onEdt(csv::pivotPanel);
        onEdt(() -> {
            csv.showPivot();
            javax.swing.JSplitPane sp = (javax.swing.JSplitPane) javax.swing.SwingUtilities.getAncestorOfClass(
                    javax.swing.JSplitPane.class, javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JTabbedPane.class, pp));
            sp.setDividerLocation(170);
            pp.setViewForTest("both");
            pp.setChartTypeForTest(PivotChart.Type.COLUMN);
            return null;
        });
        waitUntil(() -> !pp.isRunning());
        hold(900);
        onEdt(() -> {
            pp.clickQuickStartForTest();
            return null;
        });
        waitUntil(() -> !pp.isRunning() && pp.result() != null && pp.result().spec().equals(pp.spec()));
        hold(1500);
        onEdt(() -> {
            pp.drop("field:" + pp.columnForTest("product"), PivotPanel.Area.COLUMNS, -1);
            return null;
        });
        waitUntil(() -> !pp.isRunning() && pp.result() != null && pp.result().spec().equals(pp.spec()));
        hold(3500);
        still("pivot");
        if (logsift.core.Sql.available()) {
            say("Query: ask a log or a table anything SQL can, in milliseconds");
            QueryPanel q = onEdt(csv::queryPanel);
            onEdt(() -> {
                csv.showQuery();
                q.setQueryForTest("");
                return null;
            });
            hold(600);
            String sql = "SELECT region, product, round(sum(amount)) AS total FROM t GROUP BY 1, 2 ORDER BY total DESC LIMIT 5";
            for (int i = 1; i <= sql.length(); i += 2) {
                String s = sql.substring(0, Math.min(sql.length(), i + 1));
                onEdt(() -> {
                    q.setQueryForTest(s);
                    return null;
                });
                hold(40);
            }
            press(MainFrame.isMac() ? "⌘ Enter" : "Ctrl Enter");
            onEdt(() -> {
                q.run();
                return null;
            });
            waitUntil(() -> !q.runningForTest(), 60_000);
            hold(3500);
            still("query");
        }
        say("Compare two exports by key: what was added, removed and changed, value by value");
        onEdt(() -> {
            frame.compareFiles(monday, tuesday);
            return null;
        });
        waitUntil(() -> frame.currentCsvCompareTab() != null && !frame.currentCsvCompareTab().isBusy()
                && frame.currentCsvCompareTab().result() != null, 60_000);
        CsvCompareTab cmp = onEdt(frame::currentCsvCompareTab);
        var before = onEdt(cmp::result);
        AbstractButton key = onEdt(cmp::useSuggestedForTest);
        moveTo(key, 600);
        click(key);
        waitUntil(() -> !cmp.isBusy() && cmp.result() != null && cmp.result() != before, 60_000);
        hold(4000);
        still("compare");

        // ---- a running JVM over JMX
        say("Attach to a running JVM, here through its JMX port ...");
        JvmDialog jd = onEdt(frame::openJvmDialogForTest);
        waitUntil(jd::isShowing);
        hold(1200);
        onEdt(() -> {
            jd.setJmxForTest("127.0.0.1", port);
            return null;
        });
        hold(1600);
        onEdt(() -> {
            jd.attachSelected();
            return null;
        });
        waitUntil(() -> frame.currentThreadsTab() != null && frame.currentThreadsTab().isLive(), 60_000);
        onEdt(() -> {
            if (jd.isShowing()) {
                jd.dispose();
            }
            return null;
        });
        say("... its threads, live: two deadlocks, a lock five threads wait for, a busy thread");
        hold(5000);
        still("jmx");

        // ---- GC and heap
        say("GC logs, explained: how long the program stood still, and why");
        LogTab gcLog = open(gc);
        waitUntil(gcLog::gcBannerShownForTest);
        AbstractButton analyze = onEdt(gcLog::gcAnalyzeButtonForTest);
        moveTo(analyze, 500);
        click(analyze);
        waitUntil(() -> frame.currentGcTabForTest() != null, 60_000);
        GcTab gt = onEdt(frame::currentGcTabForTest);
        hold(2500);
        onEdt(() -> {
            gt.viewsForTest().setSelectedIndex(2);
            return null;
        });
        hold(2500);
        still("gc");
        say("Heap dumps, explained in plain sentences: what fills the memory, and what keeps it");
        onEdt(() -> {
            frame.openFiles(List.of(hprof));
            return null;
        });
        waitUntil(() -> frame.currentHeapDumpTab() != null && frame.currentHeapDumpTab().isLoadedForTest(), 120_000);
        hold(4500);
        still("heap");

        say("Logsift: free and open source, for Windows, macOS and Linux · bdarwin.github.io/logsift");
        showCursor = false;
        hold(4000);
    }
}
