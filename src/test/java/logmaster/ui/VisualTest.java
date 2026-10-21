package logmaster.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import logmaster.core.FilterResult;
import logmaster.core.LogDocument;
import logmaster.core.SearchQuery;
import logmaster.core.SearchSession;
import logmaster.tools.LogGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Opens a large generated log in the real UI, checks what is shown against an independent
 * (slow, simple) reader, and saves screenshots to build/screenshots.
 *
 * <p>Run with {@code ./gradlew visualTest} (or {@code -PsizeMb=3072} for a bigger file).
 */
@Tag("visual")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VisualTest {
    private final long sizeMb = Long.getLong("lm.visual.sizeMb", 1024);
    private final Path dir = Path.of(System.getProperty("lm.visual.dir", "build/visual"));
    private final Path shots = Path.of(System.getProperty("lm.screenshots", "build/screenshots"));
    private final Map<String, String> summary = new LinkedHashMap<>();

    private Path big;
    private Properties stats;
    private MainFrame frame;

    @BeforeAll
    void setUp() throws Exception {
        Files.createDirectories(dir);
        Files.createDirectories(shots);
        try (var old = Files.list(shots)) {
            for (Path p : old.toList()) {
                Files.delete(p);
            }
        }
        big = dir.resolve("big-" + sizeMb + "mb.log");
        stats = generateIfNeeded(big, sizeMb);
        onEdt(() -> {
            Theme.apply(false);
            frame = new MainFrame();
            frame.setSize(1400, 860);
            frame.setLocation(40, 40);
            frame.setVisible(true);
            return null;
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        onEdt(() -> {
            frame.dispose();
            return null;
        });
        StringBuilder sb = new StringBuilder();
        summary.forEach((k, v) -> sb.append(String.format("%-34s %s%n", k, v)));
        Files.writeString(shots.resolve("summary.txt"), sb);
        System.out.println("\n===== LogMaster visual test summary =====\n" + sb);
    }

    @Test
    @Order(1)
    void opensLargeFileAndIndexes() throws Exception {
        long t0 = System.nanoTime();
        onEdt(() -> {
            frame.openFile(big);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        LogDocument doc = tab.document();
        waitFor(() -> doc.lineCount() > 100, 10_000, "first lines visible");
        long firstMs = ms(t0);
        waitFor(() -> !doc.isIndexing() && tab.indexMillis() >= 0, 300_000, "indexing done");
        long indexMs = ms(t0);
        assertEquals(Long.parseLong(stats.getProperty("lines")), doc.lineCount(), "line count");
        settle();
        summary.put("file", big + " (" + MainFrame.humanBytes(Files.size(big)) + ")");
        summary.put("lines", String.format("%,d", doc.lineCount()));
        summary.put("first lines visible after", firstMs + " ms");
        summary.put("full index built after", indexMs + " ms");
        summary.put("index throughput", mbPerSec(Files.size(big), indexMs));
        shot("01-open-top");
    }

    @Test
    @Order(2)
    void showsCorrectTextAnywhere() throws Exception {
        LogTab tab = onEdt(frame::currentTab);
        LogDocument doc = tab.document();
        long lines = doc.lineCount();
        Random rnd = new Random(5);
        long[] picks = new long[25];
        for (int i = 0; i < picks.length; i++) {
            picks[i] = (long) (rnd.nextDouble() * lines);
        }
        picks[0] = 0;
        picks[1] = lines - 1;
        picks[2] = lines / 2;
        Arrays.sort(picks);
        Map<Long, String> expected = naiveReadLines(big, picks);
        for (long p : picks) {
            assertEquals(expected.get(p), doc.line(p), "line " + p);
        }
        // time a jump plus a synchronous repaint of the window, several times
        long best = Long.MAX_VALUE;
        for (int i = 1; i <= 5; i++) {
            long row = lines * i / 6;
            long t0 = System.nanoTime();
            onEdt(() -> {
                tab.mainView().goToRow((int) row, true);
                var root = frame.getRootPane();
                root.paintImmediately(0, 0, root.getWidth(), root.getHeight());
                return null;
            });
            best = Math.min(best, (System.nanoTime() - t0) / 1000);
        }
        onEdt(() -> {
            tab.mainView().goToRow((int) (lines / 2), true);
            return null;
        });
        settle();
        summary.put("jump to line + full repaint", String.format("%.1f ms", best / 1000.0));
        summary.put("random lines verified", picks.length + " (vs independent reader)");
        shot("02-middle-of-file");
    }

    @Test
    @Order(3)
    void findAllLiteral() throws Exception {
        LogTab tab = onEdt(frame::currentTab);
        long t0 = System.nanoTime();
        onEdt(() -> {
            frame.findAll(new SearchQuery("ERROR", true, false, false));
            return null;
        });
        SearchSession s = waitForSearch(tab);
        long took = ms(t0);
        long expected = Long.parseLong(stats.getProperty("count.ERROR"));
        assertEquals(expected, s.hits().size(), "ERROR lines");
        summary.put("Find All \"ERROR\" (literal)", String.format("%,d lines in %d ms (%s)",
                s.hits().size(), took, mbPerSec(Files.size(big), took)));
        onEdt(() -> {
            tab.resultsView().goToRow(0, false);
            tab.mainView().goToRow((int) s.hits().get(0), true);
            return null;
        });
        settle();
        shot("03-find-all-error");
    }

    @Test
    @Order(4)
    void findAllRegexAndUnicodeIgnoreCase() throws Exception {
        LogTab tab = onEdt(frame::currentTab);
        SearchQuery regex = new SearchQuery("Payment failed .* user=u12\\d4 ", false, true, false);
        SearchQuery unicode = new SearchQuery("müller", false, false, false);
        long[] naive = naiveCount(big, List.of(regex.toPattern(), unicode.toPattern()));

        long t0 = System.nanoTime();
        onEdt(() -> {
            frame.findAll(unicode);
            return null;
        });
        SearchSession s1 = waitForSearch(tab);
        long took1 = ms(t0);
        assertEquals(naive[1], s1.hits().size(), "müller (ignore case)");
        assertEquals(Long.parseLong(stats.getProperty("count.Müller")), s1.hits().size());
        summary.put("Find All \"müller\" (ignore case)", String.format("%,d lines in %d ms",
                s1.hits().size(), took1));

        t0 = System.nanoTime();
        onEdt(() -> {
            frame.findAll(regex);
            return null;
        });
        SearchSession s2 = waitForSearch(tab);
        long took2 = ms(t0);
        assertEquals(naive[0], s2.hits().size(), "regex hits");
        summary.put("Find All regex", String.format("%,d lines in %d ms (%s)",
                s2.hits().size(), took2, mbPerSec(Files.size(big), took2)));
        onEdt(() -> {
            tab.resultsView().goToRow(Math.min(5, s2.hits().size() - 1), false);
            tab.mainView().goToRow((int) s2.hits().get(Math.min(5, s2.hits().size() - 1)), true);
            return null;
        });
        settle();
        shot("04-find-all-regex");
    }

    @Test
    @Order(5)
    void nextPreviousHit() throws Exception {
        LogTab tab = onEdt(frame::currentTab);
        onEdt(() -> {
            frame.findAll(new SearchQuery("Exception", true, false, false));
            return null;
        });
        SearchSession s = waitForSearch(tab);
        assertEquals(Long.parseLong(stats.getProperty("count.Exception")), s.hits().size());
        long target = s.hits().get(s.hits().size() / 3);
        int sel = onEdt(() -> {
            tab.mainView().goToRow((int) target - 1, true);
            tab.findNext(true);
            return tab.mainView().selectedRow();
        });
        assertEquals(target, sel, "F3 lands on next hit");
        int back = onEdt(() -> {
            tab.findNext(false);
            return tab.mainView().selectedRow();
        });
        assertEquals(s.hits().get(s.hits().size() / 3 - 1), back, "Shift+F3 previous hit");
        settle();
        shot("05-next-previous-hit");
    }

    @Test
    @Order(6)
    void tailFollowWithLiveFindAll() throws Exception {
        Path tail = dir.resolve("tail.log");
        Files.deleteIfExists(dir.resolve("tail.log.1"));
        LogGenerator gen = new LogGenerator(9, LogGenerator.DEFAULT_TOKENS, false);
        LogGenerator.Stats first = gen.write(tail, 200_000, false);
        long lines = first.lines();
        long errors = first.linesContaining().get("ERROR");
        onEdt(() -> {
            frame.openFile(tail);
            frame.setFollow(true);
            frame.findAll(new SearchQuery("ERROR", true, false, false));
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        // append for ~3 s, the way a running application writes its log
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            LogGenerator.Stats st = gen.write(tail, 40_000, true);
            lines += st.lines();
            errors += st.linesContaining().get("ERROR");
            Thread.sleep(100);
        }
        long expLines = lines;
        long expErrors = errors;
        waitFor(() -> tab.document().lineCount() == expLines, 10_000, "tail caught up");
        SearchSession s = waitForSearch(tab);
        assertEquals(expErrors, s.hits().size(), "live Find All hit count");
        settle();
        boolean atBottom = onEdt(() -> {
            LogView v = tab.mainView();
            return v.isFollow() && v.topRow() + v.visibleRows() >= tab.document().lineCount();
        });
        assertTrue(atBottom, "follow keeps the end in view");
        summary.put("tail: lines appended & followed", String.format("%,d (live hits %,d)",
                expLines - first.lines(), expErrors));
        shot("06-tail-follow-live-search");
    }

    @Test
    @Order(7)
    void followsRotation() throws Exception {
        Path tail = dir.resolve("tail.log");
        LogTab tab = onEdt(frame::currentTab);
        int gen = tab.document().generation();
        Files.move(tail, dir.resolve("tail.log.1"));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("2026-10-21 12:00:00.").append(100 + i)
              .append(i % 4 == 0 ? " ERROR" : " INFO ")
              .append(" [main] c.a.Rotated - line ").append(i).append(" after rotation\n");
        }
        Files.writeString(tail, sb);
        waitFor(() -> tab.document().generation() != gen && tab.document().lineCount() == 12,
                10_000, "rotation detected");
        SearchSession s = waitForSearch(tab);
        assertEquals(3, s.hits().size(), "search re-ran on the new file");
        settle();
        shot("07-after-rotation");
    }

    @Test
    @Order(8)
    void darkMode() throws Exception {
        onEdt(() -> {
            frame.toggleDark();
            // back to the big file
            for (int i = 0; i < 1; i++) {
                frame.openFile(big);
            }
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        long t0 = System.nanoTime();
        onEdt(() -> {
            frame.findAll(new SearchQuery("WARN", true, false, true));
            return null;
        });
        SearchSession s = waitForSearch(tab);
        summary.put("Find All \"WARN\" (whole word)", String.format("%,d lines in %d ms",
                s.hits().size(), ms(t0)));
        assertEquals(Long.parseLong(stats.getProperty("count.WARN")), s.hits().size());
        onEdt(() -> {
            tab.resultsView().goToRow(3, false);
            tab.mainView().goToRow((int) tab.search().hits().get(3), true);
            return null;
        });
        settle();
        shot("08-dark-mode");
        onEdt(() -> {
            frame.toggleDark();
            return null;
        });
    }

    @Test
    @Order(9)
    void selectText() throws Exception {
        LogTab tab = onEdt(frame::currentTab);
        LogView v = tab.mainView();
        // a few consecutive plain ASCII lines, so columns map straight to pixels
        int row = onEdt(() -> {
            for (int r = 1000; ; r++) {
                if (isPlainAscii(tab.document().line(r)) && isPlainAscii(tab.document().line(r + 2))) {
                    v.goToRow(r, true);
                    return r;
                }
            }
        });
        String l0 = onEdt(() -> tab.document().line(row));
        String l1 = onEdt(() -> tab.document().line(row + 1));
        String l2 = onEdt(() -> tab.document().line(row + 2));
        String nl = System.lineSeparator();

        // drag from column 5 to 25
        String dragged = onEdt(() -> {
            mouse(v, MouseEvent.MOUSE_PRESSED, row, 5, 1, 0);
            mouse(v, MouseEvent.MOUSE_DRAGGED, row, 25, 1, 0);
            mouse(v, MouseEvent.MOUSE_RELEASED, row, 25, 1, 0);
            return v.selectedText();
        });
        assertEquals(l0.substring(5, 25), dragged, "drag selects characters");

        // double-click the log level word (third field: "date time LEVEL ...")
        String level = l0.split(" ")[2];
        int levelCol = l0.indexOf(" " + level + " ") + 1;
        String word = onEdt(() -> {
            mouse(v, MouseEvent.MOUSE_PRESSED, row, levelCol + 1, 1, 0);
            mouse(v, MouseEvent.MOUSE_RELEASED, row, levelCol + 1, 1, 0);
            mouse(v, MouseEvent.MOUSE_PRESSED, row, levelCol + 1, 2, 0);
            mouse(v, MouseEvent.MOUSE_RELEASED, row, levelCol + 1, 2, 0);
            return v.selectedText();
        });
        assertEquals(level, word, "double-click selects a word");
        assertEquals(level, onEdt(v::selectionForFind), "selection pre-fills Find");

        // triple-click selects the whole line
        String line = onEdt(() -> {
            mouse(v, MouseEvent.MOUSE_PRESSED, row, 3, 3, 0);
            mouse(v, MouseEvent.MOUSE_RELEASED, row, 3, 3, 0);
            return v.selectedText();
        });
        assertEquals(l0, line, "triple-click selects the line");

        // click, then shift-click two lines down: a selection across lines
        String multi = onEdt(() -> {
            mouse(v, MouseEvent.MOUSE_PRESSED, row, 12, 1, 0);
            mouse(v, MouseEvent.MOUSE_RELEASED, row, 12, 1, 0);
            mouse(v, MouseEvent.MOUSE_PRESSED, row + 2, 19, 1, InputEvent.SHIFT_DOWN_MASK);
            mouse(v, MouseEvent.MOUSE_RELEASED, row + 2, 19, 1, InputEvent.SHIFT_DOWN_MASK);
            return v.selectedText();
        });
        assertEquals(l0.substring(12) + nl + l1 + nl + l2.substring(0, 19), multi,
                "shift-click extends across lines");
        onEdt(() -> {
            v.focusView();
            return null;
        });
        settle();
        shot("09-text-selection");

        // with nothing selected, copy takes the current line
        String current = onEdt(() -> {
            mouse(v, MouseEvent.MOUSE_PRESSED, row + 1, 4, 1, 0);
            mouse(v, MouseEvent.MOUSE_RELEASED, row + 1, 4, 1, 0);
            return v.hasSelection() ? "selection!" : v.selectedText();
        });
        assertEquals(l1, current, "copy without selection takes the line");
        summary.put("text selection", "drag, double/triple-click, shift-click verified");
    }

    @Test
    @Order(10)
    void filtersWithFormula() throws Exception {
        LogTab tab = onEdt(frame::currentTab);
        long t0 = System.nanoTime();
        onEdt(() -> {
            FilterPanel p = tab.filterPanel();
            FilterDef a = p.addFilter("ERROR", FilterDef.Action.SHOW);
            a.matchCase = true;
            p.addFilter("slow query", FilterDef.Action.SHOW);          // ignore case
            FilterDef c = p.addFilter("user=u1\\d\\d4 ", FilterDef.Action.HIDE);
            c.regex = true;
            FilterDef d = p.addFilter("Müller", FilterDef.Action.MARK);
            d.matchCase = true;
            tab.filtersChanged();
            return null;
        });
        FilterResult r = waitForFilters(tab, t0);
        long filterMs = filterMillis;
        Pattern pa = Pattern.compile("ERROR");
        Pattern pb = Pattern.compile("slow query", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern pc = Pattern.compile("user=u1\\d\\d4 ");
        List<Long> expected = naiveFilter(big, line ->
                (pa.matcher(line).find() || pb.matcher(line).find()) && !pc.matcher(line).find());
        assertEquals("(A OR B) AND NOT C", onEdt(() -> tab.filterPanel().formulaText()));
        assertEquals(expected.size(), r.count(), "filtered line count");
        Random rnd = new Random(4);
        for (int i = 0; i < 200; i++) {
            int row = rnd.nextInt(expected.size());
            assertEquals(expected.get(row), r.lineAt(row), "row " + row);
        }
        summary.put("Filters (A OR B) AND NOT C", String.format("%,d lines in %d ms",
                r.count(), filterMs));

        // Matching only keeps the current line in place, and back again.
        long target = expected.get(expected.size() / 2);
        long[] lines = onEdt(() -> {
            tab.showFiltersTab();
            tab.showLine(target);
            tab.setMatchingOnly(true);
            long inFiltered = tab.currentLine();
            int rows = tab.mainView().source().rowCount();
            return new long[] {inFiltered, rows};
        });
        assertEquals(target, lines[0], "same line after switching to matching only");
        assertEquals(expected.size(), lines[1], "rows in matching-only view");
        settle();
        shot("10-filters-matching-only");

        // A custom formula: ERROR lines that are also "slow query" lines, or not hidden.
        t0 = System.nanoTime();
        onEdt(() -> {
            tab.filterPanel().setFormula("A AND NOT C");
            return null;
        });
        r = waitForFilters(tab, t0);
        long formulaMs = filterMillis;
        List<Long> expected2 = naiveFilter(big, line ->
                pa.matcher(line).find() && !pc.matcher(line).find());
        assertEquals(expected2.size(), r.count(), "custom formula count");
        summary.put("Filters A AND NOT C (custom)", String.format("%,d lines in %d ms",
                r.count(), formulaMs));
        long kept = onEdt(tab::currentLine);
        assertTrue(kept >= target, "stays near the same place after the formula changed");

        long back = onEdt(() -> {
            tab.setMatchingOnly(false);
            return tab.currentLine();
        });
        assertEquals(kept, back, "same line after switching back to all lines");
        settle();
        shot("11-filters-all-lines-grayed");
    }

    @Test
    @Order(11)
    void highlightBarAndNextError() throws Exception {
        LogTab tab = onEdt(frame::currentTab);
        assertTrue(tab.errorLines().awaitIdle(300_000), "error search finished");
        Pattern err = Pattern.compile("(?<![\\p{L}\\p{N}_])(ERROR|FATAL|SEVERE|CRITICAL)(?![\\p{L}\\p{N}_])");
        List<Long> errors = naiveFilter(big, line -> err.matcher(line).find());
        assertEquals(errors.size(), tab.errorLines().result().count(), "error lines");

        // F8 from the top: first error, then the second; Shift+F8 from the top wraps to the last
        long[] f8 = onEdt(() -> {
            tab.setMatchingOnly(false);
            tab.mainView().goToRow(0, false);
            tab.nextError(true);
            long first = tab.currentLine();
            tab.nextError(true);
            long second = tab.currentLine();
            tab.mainView().goToRow(0, false);
            tab.nextError(false);
            return new long[] {first, second, tab.currentLine()};
        });
        assertEquals(errors.get(0), f8[0], "F8 first error");
        assertEquals(errors.get(1), f8[1], "F8 second error");
        assertEquals(errors.get(errors.size() - 1), f8[2], "Shift+F8 wraps to the last error");

        // Clicking the bar at an error's position jumps to an error line near it
        long target = errors.get(errors.size() * 2 / 3);
        long clicked = onEdt(() -> {
            OverviewBar bar = tab.mainView().overviewForTest();
            int y = bar.yOfRow(target);
            bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, 6, y, 1, false,
                    MouseEvent.BUTTON1));
            return tab.currentLine();
        });
        assertTrue(errors.contains(clicked), "clicking a mark lands on an error line: " + clicked);
        long lines = Long.parseLong(stats.getProperty("lines"));
        assertTrue(Math.abs(clicked - target) < lines / 50, "near the clicked position");
        settle();
        shot("12-highlight-bar");
        summary.put("highlight bar", String.format("%,d error marks; F8 / click verified",
                errors.size()));

        // A more typical log: errors and warnings are rare, so each mark stands out.
        Path sparse = dir.resolve("sparse.log");
        Random rnd = new Random(8);
        try (var rd = Files.newBufferedReader(big, StandardCharsets.UTF_8);
             var wr = Files.newBufferedWriter(sparse, StandardCharsets.UTF_8)) {
            String line;
            int n = 0;
            while ((line = rd.readLine()) != null && n < 400_000) {
                boolean error = err.matcher(line).find();
                boolean warn = line.contains(" WARN ");
                if ((error && rnd.nextInt(400) != 0) || (warn && rnd.nextInt(60) != 0)) {
                    continue;
                }
                wr.write(line);
                wr.write('\n');
                n++;
            }
        }
        onEdt(() -> {
            frame.openFile(sparse);
            return null;
        });
        LogTab st = onEdt(frame::currentTab);
        waitFor(() -> !st.document().isIndexing(), 60_000, "indexing");
        assertTrue(st.errorLines().awaitIdle(60_000));
        onEdt(() -> {
            st.mainView().goToRow(0, false);
            st.nextError(true);
            st.nextError(true);
            return null;
        });
        settle();
        shot("13-highlight-bar-sparse-errors");
    }

    // ---------------------------------------------------------------- helpers

    private long filterMillis;

    /** Waits for the filters; {@link #filterMillis} gets the time from {@code t0} until done. */
    private FilterResult waitForFilters(LogTab tab, long t0) throws Exception {
        assertTrue(tab.filterEngine().awaitIdle(300_000), "filters finished");
        filterMillis = ms(t0);
        settle();
        FilterResult r = tab.filterEngine().result();
        assertTrue(r.isComplete(), "filter result complete: checked " + r.checkedLines()
                + " of " + tab.document().lineCount() + ", running " + tab.filterEngine().isRunning()
                + ", indexing " + tab.document().isIndexing() + " " + tab.filterEngine().describe());
        return r;
    }

    /** Lines (0-based) passing {@code keep}, by plain BufferedReader. */
    private static List<Long> naiveFilter(Path f, java.util.function.Predicate<String> keep)
            throws IOException {
        List<Long> out = new ArrayList<>();
        try (var rd = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            long n = 0;
            while ((line = rd.readLine()) != null) {
                if (keep.test(line)) {
                    out.add(n);
                }
                n++;
            }
        }
        return out;
    }

    private static boolean isPlainAscii(String s) {
        return s != null && s.length() > 40 && s.chars().allMatch(c -> c >= 32 && c < 127);
    }

    /** Sends a left-button mouse event at a column of a row, as a real click would. */
    private static void mouse(LogView v, int id, int row, int col, int clicks, int mods) {
        JComponent c = v.canvasForTest();
        int m = mods | (id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK);
        c.dispatchEvent(new MouseEvent(c, id, System.currentTimeMillis(), m,
                v.xForColumnForTest(col), v.yForRowForTest(row), clicks, false,
                MouseEvent.BUTTON1));
    }

    private SearchSession waitForSearch(LogTab tab) throws Exception {
        SearchSession s = onEdt(tab::search);
        waitFor(() -> !tab.document().isIndexing(), 300_000, "indexing");
        assertTrue(s.awaitIdle(300_000), "search finished");
        return s;
    }

    private static Properties generateIfNeeded(Path file, long sizeMb) throws IOException {
        Path statsFile = file.resolveSibling(file.getFileName() + ".stats");
        Properties p = new Properties();
        if (Files.exists(file) && Files.exists(statsFile)) {
            try (var in = Files.newBufferedReader(statsFile)) {
                p.load(in);
            }
            System.out.println("Reusing " + file);
            return p;
        }
        long t0 = System.nanoTime();
        LogGenerator.Stats s = LogGenerator.generate(file, sizeMb * 1024 * 1024, 42);
        System.out.printf("Generated %s (%,d lines) in %d ms%n", file, s.lines(), ms(t0));
        p.setProperty("lines", Long.toString(s.lines()));
        p.setProperty("bytes", Long.toString(s.bytes()));
        s.linesContaining().forEach((k, v) -> p.setProperty("count." + k.trim(), Long.toString(v)));
        try (var out = Files.newBufferedWriter(statsFile)) {
            p.store(out, "LogGenerator stats");
        }
        return p;
    }

    /** Reads the given (sorted) line numbers with a plain byte-by-byte pass over the file. */
    private static Map<Long, String> naiveReadLines(Path f, long[] sorted) throws IOException {
        Map<Long, String> out = new LinkedHashMap<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f), 1 << 20)) {
            long line = 0;
            int k = 0;
            ByteArrayOutputStream cur = new ByteArrayOutputStream();
            int b;
            while (k < sorted.length && (b = in.read()) >= 0) {
                if (line == sorted[k]) {
                    if (b == '\n') {
                        String s = cur.toString(StandardCharsets.UTF_8);
                        if (s.endsWith("\r")) {
                            s = s.substring(0, s.length() - 1);
                        }
                        while (k < sorted.length && sorted[k] == line) {
                            out.put(line, s);
                            k++;
                        }
                        cur.reset();
                    } else {
                        cur.write(b);
                    }
                }
                if (b == '\n') {
                    line++;
                }
            }
            if (k < sorted.length) {
                out.put(sorted[k], cur.toString(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** Counts lines matching each pattern using plain BufferedReader + regex. */
    private static long[] naiveCount(Path f, List<Pattern> patterns) throws IOException {
        long[] counts = new long[patterns.size()];
        List<java.util.regex.Matcher> ms = new ArrayList<>();
        patterns.forEach(p -> ms.add(p.matcher("")));
        try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                for (int i = 0; i < counts.length; i++) {
                    if (ms.get(i).reset(line).find()) {
                        counts[i]++;
                    }
                }
            }
        }
        return counts;
    }

    private void shot(String name) throws Exception {
        BufferedImage img = onEdt(() -> {
            var root = frame.getRootPane();
            BufferedImage im = new BufferedImage(root.getWidth(), root.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = im.createGraphics();
            root.printAll(g);
            g.dispose();
            return im;
        });
        Path out = shots.resolve(name + ".png");
        ImageIO.write(img, "png", out.toFile());
        System.out.println("Screenshot: " + out);
    }

    /** Lets coalesced UI updates run and the window repaint. */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            onEdt(() -> null);
            Thread.sleep(120);
        }
    }

    private static void waitFor(BooleanSupplier cond, long timeoutMs, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(c.call());
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String mbPerSec(long bytes, long ms) {
        return ms <= 0 ? "-" : String.format("%,.0f MB/s", bytes / 1048576.0 / (ms / 1000.0));
    }
}
