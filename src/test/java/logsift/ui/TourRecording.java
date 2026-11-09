package logsift.ui;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Records a tour of Logsift (about two minutes): a 1 GB log, find and filters, exceptions, a
 * request followed across threads, thread dumps, a GC log, a heap dump and a question to the
 * assistant, answered for real by Claude Code on Haiku (it costs a fraction of a cent, on the
 * Claude account signed in on this computer).
 *
 * <p>Run with {@code ./gradlew visualTest --tests logsift.ui.TourRecording -Pdemo=/some/dir};
 * needs ffmpeg and a signed-in {@code claude} on the PATH. {@code -Dtour.noAssistant=true}
 * leaves the assistant out.
 */
@Tag("visual")
@EnabledIfSystemProperty(named = "logsift.demo", matches = ".+")
class TourRecording extends Recording {
    private static final String QUESTION = "Why are orders failing, and which one should I fix first?";

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
        writeErrors(errors);
        Path requests = dir.resolve("shop-requests.log");
        long slow = writeRequests(requests);
        Path threads = dir.resolve("orders-threads.txt");
        try (InputStream in = TourRecording.class.getResourceAsStream("/threaddumps/jstack-17.txt")) {
            Files.write(threads, in.readAllBytes());
        }
        Path gc = dir.resolve("orders-gc.log");
        try (var in = new java.util.zip.GZIPInputStream(TourRecording.class.getResourceAsStream("/gc/j17-g1-time.log.gz"))) {
            Files.write(gc, in.readAllBytes());
        }
        Path hprof = dir.resolve("shop.hprof");
        if (!Files.exists(hprof)) {
            logsift.heap.LeakApp.run(hprof, dir.resolve("shop-histogram.txt"));
        }
        boolean assistant = !Boolean.getBoolean("tour.noAssistant");
        if (assistant) {
            var service = logsift.chat.ChatService.get();
            service.saveSettings(logsift.chat.ChatSettings.DEFAULT.withEngine(logsift.chat.ChatSettings.CLAUDE)
                    .withModel(logsift.chat.ClaudeEngine.PREFIX + "haiku", "low"));
            service.saveOptions(logsift.chat.ChatInstructions.Options.DEFAULT.withInTab(false));
            for (var c : service.store().chats(null, 10_000)) {
                service.store().delete(c.id()); // last recording's chats
            }
        }

        System.setProperty("logsift.noJvmBar", "true");
        var prefs = AppState.prefs();
        prefs.putBoolean("columns", true);
        prefs.putBoolean("wrap", false);
        prefs.putBoolean("valuesTree", false);
        prefs.putBoolean("timeline", true);
        onEdt(() -> {
            Theme.apply(false);
            frame = new MainFrame();
            frame.setSize(1440, 880);
            frame.setLocation(40, 40);
            frame.setVisible(true);
            return null;
        });
        Thread.sleep(800);
        root = onEdt(frame::getRootPane);
        w = onEdt(root::getWidth) & ~1;
        h = onEdt(root::getHeight) & ~1;
        startVideo();
        try {
            script(big, errors, requests, slow, threads, gc, hprof, assistant);
        } finally {
            stopVideo();
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    private void script(Path big, Path errors, Path requests, long slow, Path threads, Path gc, Path hprof,
            boolean assistant) throws Exception {
        say("Logsift: find out what your app did, from its logs to its threads");
        hold(2800);

        // a 1 GB log
        StartPage start = onEdt(frame::startPageForTest);
        moveTo(onEdt(() -> start.actionForTest("Open Log…")), 700);
        ripple = 0;
        say("Open a 1 GB log ...");
        hold(500);
        long t0 = System.nanoTime();
        onEdt(() -> {
            frame.openFiles(List.of(big));
            return null;
        });
        LogTab tab = waitForTab(big.getFileName().toString());
        waitUntil(() -> !tab.document().isIndexing(), 120_000);
        double secs = (System.nanoTime() - t0) / 1e9;
        long lines = onEdt(() -> tab.document().lineCount());
        say(String.format(Locale.ROOT, "... %,d lines, ready in %.1f s. Scrolling stays smooth at any size", lines, secs));
        moveTo(tab.mainView(), 700);
        for (long row : new long[] {lines / 3, lines / 2, lines * 4 / 5, lines - 40}) {
            onEdt(() -> {
                tab.showLine(row);
                return null;
            });
            hold(700);
        }
        onEdt(() -> {
            tab.mainView().goToRow(0, true);
            return null;
        });
        hold(800);
        still("1gb-log");

        // find and filters
        say("⌘F: matches light up as you type");
        press(MainFrame.isMac() ? "⌘ F" : "Ctrl F");
        onEdt(() -> {
            frame.openFind();
            return null;
        });
        hold(600);
        type(tab.findBar(), "Payment failed");
        waitUntil(() -> tab.findBar().countText().contains(" of ") && !tab.findBar().session().isRunning(), 60_000);
        hold(1500);
        say("Find All: every match, counted over the whole gigabyte");
        AbstractButton all = button(tab.findBar(), "Find All");
        moveTo(all, 600);
        click(all);
        waitUntil(() -> tab.currentFind() != null);
        hold(2600);
        still("find-all");
        say("Filters: errors only, as a chip you can turn off with a click");
        AbstractButton filters = button(tab.logBar(), "Filters");
        moveTo(filters, 600);
        click(filters);
        hold(600);
        onEdt(() -> {
            tab.filterPanel().setLevels("E");
            return null;
        });
        waitUntil(() -> !tab.filterEngine().isRunning(), 120_000);
        hold(1200);
        say("The timeline on top shows when they happened");
        moveTo(onEdt(tab::timeline), 800);
        hold(2400);
        still("filters");

        // exceptions
        say("Exceptions, grouped by root cause and where in your code");
        onEdt(() -> {
            frame.openFiles(List.of(errors));
            return null;
        });
        LogTab err = waitForTab(errors.getFileName().toString());
        waitUntil(() -> err.exceptionsPanel().result() != null, 60_000);
        onEdt(() -> {
            err.showExceptionsTab();
            return null;
        });
        hold(4500);
        still("exceptions");

        // a request
        say("Follow one request across threads: the 8-second wait for the bank stands out");
        onEdt(() -> {
            frame.openFiles(List.of(requests));
            return null;
        });
        LogTab req = waitForTab(requests.getFileName().toString());
        waitUntil(() -> req.logFormat() != null && !req.document().isIndexing());
        onEdt(() -> {
            req.showLine(slow);
            return null;
        });
        hold(1200);
        onEdt(req::followCurrentRequest);
        waitUntil(() -> req.requestPanel() != null && !req.requestPanel().isRunning() && req.requestPanel().trace() != null);
        hold(4500);
        still("request");

        // thread dumps
        say("Thread dumps, explained: deadlocks, who waits for which lock, busy threads");
        javax.swing.SwingUtilities.invokeLater(() -> frame.openThreadDump(threads));
        waitUntil(() -> frame.currentThreadsTab() != null && frame.tabsForTest().getSelectedComponent() == frame.currentThreadsTab(),
                60_000);
        hold(5000);
        still("threads");

        // GC
        say("GC logs, explained: how long the program stood still, and why");
        onEdt(() -> {
            frame.openFile(gc);
            return null;
        });
        LogTab gcLog = waitForTab(gc.getFileName().toString());
        waitUntil(gcLog::gcBannerShownForTest);
        hold(600);
        AbstractButton analyze = onEdt(gcLog::gcAnalyzeButtonForTest);
        moveTo(analyze, 600);
        click(analyze);
        waitUntil(() -> frame.currentGcTabForTest() != null, 60_000);
        GcTab gt = onEdt(frame::currentGcTabForTest);
        hold(3500);
        onEdt(() -> {
            gt.viewsForTest().setSelectedIndex(2);
            return null;
        });
        say("... with charts of the heap and every pause");
        hold(3000);
        still("gc");

        // heap
        say("Heap dumps, explained in plain sentences: what fills the memory, and what keeps it");
        onEdt(() -> {
            frame.openFiles(List.of(hprof));
            return null;
        });
        waitUntil(() -> frame.currentHeapDumpTab() != null && frame.currentHeapDumpTab().isLoadedForTest(), 120_000);
        hold(5000);
        still("heap");

        if (assistant) {
            say("Ask the assistant: Claude Code or GitHub Copilot, with Logsift's own tools");
            onEdt(() -> {
                frame.tabsForTest().setSelectedComponent(err);
                return null;
            });
            hold(800);
            ChatPanel chat = onEdt(frame::showChat);
            hold(1200);
            moveTo(onEdt(chat::inputForTest), 700);
            for (int i = 1; i <= QUESTION.length(); i++) {
                String s = QUESTION.substring(0, i);
                onEdt(() -> {
                    chat.inputForTest().setText(s);
                    return null;
                });
                hold(55);
            }
            hold(600);
            press("Enter");
            onEdt(() -> {
                chat.sendForTest();
                return null;
            });
            say("It reads the log with Logsift's tools (sped up 4×)");
            speed = 4;
            waitUntil(() -> !chat.busyForTest(), 240_000);
            speed = 1;
            say("The answer cites lines you can click, and shows what it cost");
            // from the top of the answer down through it, as someone reading it would
            ChatTranscript tr = onEdt(chat::transcriptForTest);
            javax.swing.JScrollBar bar = onEdt(() -> ((javax.swing.JScrollPane) javax.swing.SwingUtilities
                    .getAncestorOfClass(javax.swing.JScrollPane.class, tr)).getVerticalScrollBar());
            int top = onEdt(() -> {
                for (ChatEntryView v : tr.viewsForTest()) {
                    if (v.message().kind() == logsift.chat.ChatMessage.Kind.ASSISTANT) {
                        return Math.max(0, v.getY() - 12);
                    }
                }
                return 0;
            });
            scrollTo(bar, top, 600);
            hold(3500);
            still("assistant");
            int end = onEdt(() -> bar.getMaximum() - bar.getVisibleAmount());
            scrollTo(bar, end, Math.min(9000, Math.max(2000, (end - top) * 5)));
            hold(1500);
            String href = onEdt(() -> {
                var views = chat.transcriptForTest().viewsForTest();
                for (int i = views.size() - 1; i >= 0; i--) {
                    var m = java.util.regex.Pattern.compile("\\((logsift:[^)\\s]+)\\)").matcher(views.get(i).message().text());
                    if (views.get(i).message().kind() == logsift.chat.ChatMessage.Kind.ASSISTANT && m.find()) {
                        return m.group(1);
                    }
                }
                return null;
            });
            if (href != null) {
                say("Click one, and the log shows that line");
                onEdt(() -> {
                    chat.linkForTest(href);
                    return null;
                });
                hold(3500);
            }
        }

        say("Logsift: free and open source, for Windows, macOS and Linux · bdarwin.github.io/logsift");
        showCursor = false;
        hold(4000);
    }

    /** Scrolls smoothly to this value in about this many milliseconds. */
    private void scrollTo(javax.swing.JScrollBar bar, int to, int ms) throws Exception {
        int from = onEdt(bar::getValue);
        int n = Math.max(1, ms * FPS / 1000);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double e = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            int v = (int) Math.round(from + (to - from) * e);
            onEdt(() -> {
                bar.setValue(v);
                return null;
            });
            frame();
        }
    }

    // ---------------------------------------------------------------- the logs

    /** A shop's log with three kinds of exceptions; the most common one is a NullPointerException. */
    private static void writeErrors(Path log) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.util.Random rnd = new java.util.Random(3);
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 0, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        for (int i = 0; i < 3000; i++) {
            String time = TimelineBar.formatFull(t0 + i * 1200L).substring(0, 23);
            int kind = i < 900 ? -1 : i % 50 == 7 ? 0 : i % 160 == 11 ? 1 : i % 400 == 13 ? 2 : -1;
            if (kind < 0) {
                sb.append(time).append(" INFO  [http-nio-8080-exec-").append(i % 8 + 1)
                        .append("] c.a.shop.OrderController - order ").append(1000 + i).append(" placed in ")
                        .append(20 + rnd.nextInt(200)).append(" ms\n");
                continue;
            }
            sb.append(time).append(" ERROR [http-nio-8080-exec-").append(i % 8 + 1)
                    .append("] o.a.c.c.C.[dispatcherServlet] - Servlet.service() threw exception\n");
            if (kind == 0) {
                sb.append("org.springframework.web.util.NestedServletException: Request processing failed\n")
                        .append("\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014)\n")
                        .append("Caused by: java.lang.IllegalStateException: Could not place order ").append(1000 + i).append('\n')
                        .append("\tat com.acme.shop.OrderService.place(OrderService.java:142)\n")
                        .append("\tat com.acme.shop.OrderController.create(OrderController.java:57)\n")
                        .append("Caused by: java.lang.NullPointerException: Cannot invoke \"com.acme.shop.Coupon.percent()\" because \"coupon\" is null\n")
                        .append("\tat com.acme.shop.PriceCalculator.discount(PriceCalculator.java:88)\n")
                        .append("\tat com.acme.shop.PriceCalculator.total(PriceCalculator.java:42)\n")
                        .append("\tat com.acme.shop.OrderService.place(OrderService.java:139)\n");
            } else if (kind == 1) {
                sb.append("org.springframework.dao.DataAccessResourceFailureException: could not acquire connection\n")
                        .append("\tat com.acme.shop.OrderRepository.save(OrderRepository.java:31)\n")
                        .append("Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.\n")
                        .append("\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)\n")
                        .append("\tat com.acme.shop.OrderRepository.save(OrderRepository.java:31)\n");
            } else {
                sb.append("java.lang.ArithmeticException: / by zero\n")
                        .append("\tat com.acme.shop.Stats.average(Stats.java:19)\n")
                        .append("\tat com.acme.shop.ReportJob.run(ReportJob.java:44)\n");
            }
        }
        Files.writeString(log, sb.toString());
    }

    /** Requests handled by web threads and paid on a pool thread; r-1042 waits 8 s for the bank. Gives its first line. */
    private static long writeRequests(Path log) throws Exception {
        java.util.List<String[]> events = new java.util.ArrayList<>();
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 2, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        java.util.Random rnd = new java.util.Random(7);
        for (int r = 0; r < 400; r++) {
            long st = t0 + r * 450L + rnd.nextInt(200);
            String id = "r-" + (1000 + r);
            String web = "http-nio-8080-exec-" + (r % 40 + 1);
            String pool = "payments-" + (r % 3 + 1);
            long pay = r == 42 ? 8_200 : 40 + rnd.nextInt(300);
            boolean declined = r == 42 || r % 37 == 0;
            Object[][] steps = {
                {0L, "INFO ", web, "c.a.shop.OrderController", "POST /orders requestId=" + id},
                {12L, "DEBUG", web, "c.a.shop.OrderRepository", "select * from orders where customer=?"},
                {30L, "INFO ", pool, "c.a.shop.PaymentClient", "charging card requestId=" + id},
                {30 + pay, declined ? "ERROR" : "INFO ", pool, "c.a.shop.PaymentClient",
                    (declined ? "card declined by bank" : "payment authorized") + " requestId=" + id},
                {40 + pay, declined ? "WARN " : "INFO ", web, "c.a.shop.OrderController",
                    (declined ? "402 Payment Required" : "201 Created") + " requestId=" + id}};
            for (Object[] s : steps) {
                long t = st + (Long) s[0];
                events.add(new String[] {Long.toString(t), TimelineBar.formatFull(t).substring(0, 23) + " " + s[1]
                        + " [" + s[2] + "] " + s[3] + " - " + s[4]});
            }
        }
        events.sort(java.util.Comparator.comparingLong(e -> Long.parseLong(e[0])));
        StringBuilder sb = new StringBuilder();
        long line = -1;
        for (int i = 0; i < events.size(); i++) {
            sb.append(events.get(i)[1]).append('\n');
            if (events.get(i)[1].endsWith("POST /orders requestId=r-1042")) {
                line = i;
            }
        }
        Files.writeString(log, sb.toString());
        return line;
    }
}
