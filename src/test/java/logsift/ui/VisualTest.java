package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.stream.IntStream;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import logsift.core.FilterResult;
import logsift.core.LogDocument;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;
import logsift.tools.LogGenerator;
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
    private final long sizeMb = Long.getLong("logsift.visual.sizeMb", 1024);
    private final Path dir = Path.of(System.getProperty("logsift.visual.dir", "build/visual"));
    private final Path shots = Path.of(System.getProperty("logsift.screenshots", "build/screenshots"));
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
        // start from clean settings and memory (tests use their own, see build.gradle.kts)
        Path home = AppState.home();
        if (Files.exists(home)) {
            try (var walk = Files.walk(home)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        AppState.prefs().removeNode();
        // the test's own JVM writes the logs here: no "this JVM writes this log" bar in the
        // screenshots, except in the test about that bar
        System.setProperty("logsift.noJvmBar", "true");
        big = dir.resolve("big-" + sizeMb + "mb.log");
        stats = generateIfNeeded(big, sizeMb);
        onEdt(() -> {
            Theme.apply(false);
            frame = new MainFrame();
            frame.setAutoReload(true); // most tests follow growing files; manual mode is tested below
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
        System.out.println("\n===== Logsift visual test summary =====\n" + sb);
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
            tab.results().selectResult(tab.currentFind(), 0);
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
            tab.results().selectResult(tab.currentFind(), Math.min(5, s2.hits().size() - 1));
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
            tab.results().selectResult(tab.currentFind(), 3);
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
            p.setKeepEntries(false); // this test checks single lines; entries are tested below
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
        // only a Color filter left: nothing is filtered, so "Matching only" goes off by itself
        boolean[] matching = onEdt(() -> {
            FilterPanel p = tab.filterPanel();
            tab.setMatchingOnly(true);
            boolean before = tab.isMatchingOnly();
            p.setFormula(null);
            for (FilterDef d : p.filters()) {
                d.enabled = d.action == FilterDef.Action.MARK;
            }
            tab.filtersChanged();
            boolean after = tab.isMatchingOnly();
            for (FilterDef d : p.filters()) {
                d.enabled = true;
            }
            p.setFormula("A AND NOT C");
            tab.filtersChanged();
            tab.setMatchingOnly(false);
            return new boolean[] {before, after};
        });
        assertTrue(matching[0], "matching only on");
        assertTrue(!matching[1], "matching only off when only a Color filter is left");
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

    @Test
    @Order(12)
    void folderSearch() throws Exception {
        // A folder like a real log directory: plain, rotated .gz and a .zip of old logs (with a zip inside)
        Path folder = dir.resolve("folder");
        if (Files.exists(folder)) {
            try (var walk = Files.walk(folder)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(folder.resolve("node2"));
        long size = 12L << 20;
        LogGenerator.generate(folder.resolve("app.log"), size, 1);
        LogGenerator.generate(folder.resolve("node2/app.log"), size, 2);
        Path tmp = folder.resolve("tmp.log");
        LogGenerator.generate(tmp, size, 3);
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(folder.resolve("app.log.1.gz")))) {
            Files.copy(tmp, out);
        }
        Path older = folder.resolve("older.log");
        LogGenerator.generate(older, 2L << 20, 5);
        java.io.ByteArrayOutputStream innerZip = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(innerZip)) {
            z.putNextEntry(new java.util.zip.ZipEntry("older.log"));
            Files.copy(older, z);
        }
        LogGenerator.generate(tmp, size, 4);
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(folder.resolve("archive.zip")))) {
            z.putNextEntry(new java.util.zip.ZipEntry("2026-10/old.log"));
            Files.copy(tmp, z);
            z.putNextEntry(new java.util.zip.ZipEntry("2026-09.zip"));
            z.write(innerZip.toByteArray());
        }
        long olderHits = naiveCount(Files.newInputStream(older));
        Files.delete(older);
        Files.delete(tmp);
        Files.writeString(folder.resolve("notes.txt"), "Payment failed (not a .log file)\n");

        SearchQuery q = new SearchQuery("Payment failed", true, false, false);
        long t0 = System.nanoTime();
        FolderSearchTab tab = onEdt(() -> {
            FolderSearchTab t = frame.openFolderSearch(folder);
            t.search(folder, "*.log", q);
            return t;
        });
        logsift.core.FolderSearch fs = onEdt(tab::search);
        assertTrue(fs.awaitDone(120_000), "folder search finished");
        long searchMs = ms(t0);
        settle();

        // naive counts, reading each file (unpacked) line by line
        Map<String, Long> expected = new java.util.TreeMap<>();
        expected.put("app.log", naiveCount(Files.newInputStream(folder.resolve("app.log"))));
        expected.put("node2/app.log", naiveCount(Files.newInputStream(folder.resolve("node2/app.log"))));
        expected.put("app.log.1.gz", naiveCount(new java.util.zip.GZIPInputStream(
                Files.newInputStream(folder.resolve("app.log.1.gz")))));
        try (var zip = new java.util.zip.ZipFile(folder.resolve("archive.zip").toFile())) {
            expected.put("archive.zip!2026-10/old.log",
                    naiveCount(zip.getInputStream(zip.getEntry("2026-10/old.log"))));
        }
        expected.put("archive.zip!2026-09.zip!/older.log", olderHits);
        Map<String, Long> actual = new java.util.TreeMap<>();
        for (var r : fs.results()) {
            actual.put(folder.relativize(r.file()).toString().replace('\\', '/')
                    + (r.entry() == null ? "" : "!" + r.entry()), r.hitCount());
        }
        assertEquals(expected, actual, "hits per file");
        assertTrue(onEdt(tab::statusText).contains(" in 4 of 4 files"), "the zip counts once: "
                + onEdt(tab::statusText));
        summary.put("Folder search (5 files, 50 MB)", String.format("%,d lines in %d ms",
                fs.totalHits(), searchMs));
        shot("14-folder-search");
        onEdt(() -> {
            tab.collapseAll(true);
            return null;
        });
        settle();
        shot("14b-folder-search-collapsed");
        onEdt(() -> {
            tab.collapseAll(false);
            return null;
        });

        // double-click a hit in the zip inside the zip: unpacks it and opens the file at that line
        var zipResult = fs.results().stream().filter(r -> "2026-09.zip!/older.log".equals(r.entry()))
                .findFirst().orElseThrow();
        long zipLine = zipResult.hit(2).line();
        onEdt(() -> {
            LogView v = tab.resultsView();
            int n = v.source().rowCount();
            String[] buf = new String[n];
            v.source().texts(0, n, buf);
            for (int row = 0; row < n; row++) {
                if (buf[row] != null && buf[row].contains("archive.zip › 2026-09.zip › older.log")) {
                    v.goToRow(row + 3, true); // the third hit of that file
                    tab.openSelected();
                    return null;
                }
            }
            throw new AssertionError("zip entry not in results");
        });
        waitFor(() -> {
            try {
                return onEdt(() -> {
                    LogTab t = frame.currentTab();
                    return t != null && t.title().equals("older.log") && t.currentLine() == zipLine;
                });
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "opened older.log from the zip in the zip at line " + zipLine);
        LogTab opened = onEdt(frame::currentTab);
        assertTrue(opened.search() != null && opened.search().query().equals(q),
                "Find All runs in the opened file");
        waitForSearch(opened);
        settle();
        shot("15-opened-from-folder-search");
    }

    @Test
    @Order(13)
    void wrapLongLines() throws Exception {
        // a log with long lines (wrapped), tabs and CJK text
        Path f = dir.resolve("wrap.log");
        Random rnd = new Random(13);
        StringBuilder sb = new StringBuilder();
        String[] words = {"payload", "request", "user=u1234", "status=OK", "latency=12ms",
            "items=[a,b,c]", "trace=9f2c1e", "retry", "cache", "region=eu-west-1"};
        for (int i = 0; i < 3000; i++) {
            sb.append(String.format("2026-10-21 10:%02d:%02d.%03d INFO  [worker-%d] ", i / 60 % 60,
                    i % 60, i % 1000, i % 8));
            int n = i % 3 == 0 ? 60 + rnd.nextInt(200) : 3 + rnd.nextInt(6);
            for (int k = 0; k < n; k++) {
                sb.append(words[rnd.nextInt(words.length)]).append(' ');
            }
            if (i % 50 == 0) {
                sb.append("\t\ttabbed 田中 太郎 東京 ").append("漢字".repeat(40));
            }
            sb.append('\n');
        }
        Files.writeString(f, sb);
        List<String> lines = Files.readAllLines(f);
        onEdt(() -> {
            frame.openFile(f);
            frame.setWrap(true);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> !tab.document().isIndexing(), 30_000, "indexing");
        LogView v = tab.mainView();
        int longRow = 3; // i % 3 == 0 lines are long
        int[] segs = onEdt(() -> {
            v.goToRow(longRow, false);
            return v.segmentStartsForTest(longRow);
        });
        assertTrue(segs.length >= 3, "a long line wraps into several rows: " + segs.length);
        assertEquals(1, (int) onEdt(() -> v.segmentsForTest(1)), "a short line stays one row");
        assertTrue(!onEdt(v::horizontalScrollbarVisibleForTest), "no sideways scrolling in wrap");

        // click on the second row of the wrapped line, shift-click on its third row
        String l3 = lines.get(longRow);
        // where rows break is read right before clicking (the layout can still change while the
        // log is being read); clicking a wrapped line that is partly off screen must not scroll
        settle();
        String[] got = onEdt(() -> {
            v.goToRow(longRow, false); // on screen right now (a small screen may have scrolled it away)
            int[] at = v.segmentStartsForTest(longRow);
            int y = v.yForRowForTest(longRow);
            assertTrue(y >= 0, "the long line is on screen");
            JComponent c = v.canvasForTest();
            int x1 = v.xForSegmentColumnForTest(longRow, 1, at[1] + 4);
            int x2 = v.xForSegmentColumnForTest(longRow, 2, at[2] + 7);
            int lineH = rowHeightOf(v);
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK, x1, y + lineH, 1, false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, x1, y + lineH, 1, false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, x2, y + 2 * lineH,
                    1, false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    InputEvent.SHIFT_DOWN_MASK, x2, y + 2 * lineH, 1, false, MouseEvent.BUTTON1));
            return new String[] {v.selectedText(), l3.substring(at[1] + 4, at[2] + 7)};
        });
        assertEquals(got[1], got[0], "selection across wrapped rows");

        // follow shows the very end of the last line
        boolean lastVisible = onEdt(() -> {
            frame.setFollow(true);
            return v.yForRowForTest(lines.size() - 1) >= 0;
        });
        assertTrue(lastVisible, "follow shows the last line in wrap mode");
        onEdt(() -> {
            frame.setFollow(false);
            v.goToRow(longRow, false);
            frame.findAll(new SearchQuery("status=OK", true, false, false));
            return null;
        });
        waitForSearch(tab);
        onEdt(() -> {
            v.goToRow(longRow - 1, false);
            return null;
        });
        settle();
        shot("16-wrap");
        onEdt(() -> {
            frame.setWrap(false);
            return null;
        });
    }

    /** Height of one display row (as LogView computes it). */
    private static int rowHeightOf(LogView v) {
        return v.getFontMetrics(Theme.monoFont(v.fontSize())).getHeight() + 2;
    }

    @Test
    @Order(14)
    void stackTracesAndContext() throws Exception {
        onEdt(() -> {
            frame.openFile(big);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        long t0 = System.nanoTime();
        onEdt(() -> {
            FilterPanel p = tab.filterPanel();
            for (FilterDef d : List.copyOf(p.filters())) {
                d.enabled = false;
            }
            FilterDef npe = p.addFilter("NullPointerException", FilterDef.Action.SHOW);
            npe.matchCase = true;
            p.setFormula(null);
            p.setKeepEntries(true);
            p.setContextLines(0);
            tab.filtersChanged();
            tab.setMatchingOnly(true);
            return null;
        });
        FilterResult r = waitForFilters(tab, t0);
        long entriesMs = filterMillis;
        // naive: an entry is a dated line plus the lines under it; keep entries mentioning NPE
        Pattern start = Pattern.compile("^\\d{4}-\\d\\d-\\d\\d");
        List<Long> expected = new ArrayList<>();
        try (var rd = Files.newBufferedReader(big, StandardCharsets.UTF_8)) {
            List<String> entry = new ArrayList<>();
            long first = 0;
            long n = 0;
            String line;
            while (true) {
                line = rd.readLine();
                if (line == null || (start.matcher(line).find() && !entry.isEmpty())) {
                    if (entry.stream().anyMatch(s -> s.contains("NullPointerException"))) {
                        for (int k = 0; k < entry.size(); k++) {
                            expected.add(first + k);
                        }
                    }
                    entry.clear();
                    first = n;
                }
                if (line == null) {
                    break;
                }
                entry.add(line);
                n++;
            }
        }
        assertEquals(expected.size(), r.count(), "lines of entries with a NullPointerException");
        for (int i = 0; i < expected.size(); i += Math.max(1, expected.size() / 300)) {
            assertEquals(expected.get(i), r.lineAt(i), "row " + i);
        }
        summary.put("Filter whole entries (stack traces)", String.format(
                "%,d lines in %d ms", r.count(), entriesMs));
        onEdt(() -> {
            tab.mainView().goToRow(0, false);
            return null;
        });
        settle();
        shot("17-filter-keeps-stack-traces");

        // two lines of context around each entry, grayed, with separators
        onEdt(() -> {
            tab.filterPanel().setContextLines(2);
            return null;
        });
        r = waitForFilters(tab, System.nanoTime());
        assertEquals(expected.size(), r.matchCount(), "matches unchanged by context");
        assertTrue(r.count() > r.matchCount(), "context lines added");
        long firstContext = expected.get(0) - 1;
        assertTrue(r.contains(firstContext) && !r.isMatch(firstContext), "line before is context");
        onEdt(() -> {
            tab.mainView().goToRow(0, false);
            return null;
        });
        settle();
        shot("18-context-lines");
        onEdt(() -> {
            tab.filterPanel().setContextLines(0);
            tab.setMatchingOnly(false);
            return null;
        });
    }

    @Test
    @Order(15)
    void timeline() throws Exception {
        onEdt(() -> {
            frame.openFile(big);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> tab.timeIndex() != null, 30_000, "timestamps detected");
        assertTrue(tab.timeIndex().awaitIdle(300_000));
        assertTrue(tab.errorLines().awaitIdle(300_000));
        long lines = Long.parseLong(stats.getProperty("lines"));
        waitFor(() -> {
            TimelineBar.Histogram h = tab.timeline().histogramForTest();
            return h != null && java.util.Arrays.stream(h.volume()).sum()
                    == lines - tab.timeIndex().samples().lines()[0];
        }, 30_000, "timeline counts every line");
        TimelineBar.Histogram h = tab.timeline().histogramForTest();
        assertEquals(tab.errorLines().result().count(), java.util.Arrays.stream(h.errors()).sum(),
                "every error line is on the timeline");

        // Go to Time lands on the first line at or after that time
        logsift.core.TimeFormat fmt = tab.timeIndex().format();
        List<Long> times = new ArrayList<>(); // time of each line (or of its entry)
        try (var rd = Files.newBufferedReader(big, StandardCharsets.UTF_8)) {
            String line;
            long last = Long.MIN_VALUE;
            while ((line = rd.readLine()) != null) {
                long t = fmt.parse(line);
                if (t != logsift.core.TimeFormat.NONE) {
                    last = t;
                }
                times.add(t == logsift.core.TimeFormat.NONE ? Long.MIN_VALUE : t);
            }
        }
        long target = timeNear(times, times.size() / 2);
        long firstAt = -1;
        for (int i = 0; i < times.size() && firstAt < 0; i++) {
            if (times.get(i) >= target) {
                firstAt = i;
            }
        }
        long landed = onEdt(() -> {
            tab.setMatchingOnly(false);
            tab.goToTime(target);
            return tab.currentLine();
        });
        assertEquals(firstAt, landed, "Go to Time");

        // a time range shows exactly the lines from the first at 'from' to the last at 'to'
        long from = timeNear(times, times.size() / 3);
        long to = timeNear(times, times.size() / 3 + 20_000);
        long t0 = System.nanoTime();
        onEdt(() -> {
            for (FilterDef d : tab.filterPanel().filters()) {
                d.enabled = false; // earlier tests' filters: only the time range here
            }
            tab.filtersChanged();
            tab.selectTimeRange(from, to);
            return null;
        });
        FilterResult r = waitForFilters(tab, t0);
        long a = -1;
        long b = times.size();
        for (int i = 0; i < times.size(); i++) {
            if (a < 0 && times.get(i) >= from) {
                a = i;
            }
            if (times.get(i) > to && times.get(i) != Long.MIN_VALUE) {
                b = i;
                if (a >= 0) {
                    break;
                }
            }
        }
        assertEquals(b - a, r.count(), "lines in the time range");
        assertEquals(a, r.lineAt(0), "first line in the range");
        assertTrue(onEdt(tab::isMatchingOnly), "a time range shows only its lines");
        onEdt(() -> {
            tab.clearTimeRange();
            tab.setMatchingOnly(false);
            return null;
        });

        // a realistic picture: an hour of traffic with an incident at 10:20
        Path incident = dir.resolve("incident.log");
        Random rnd = new Random(15);
        try (var w = Files.newBufferedWriter(incident, StandardCharsets.UTF_8)) {
            long start = TimelineBar.parseUserTime("2026-10-21 10:00:00", 0);
            long t = start;
            int n = 0;
            while (t < start + 3_600_000L) {
                long minute = (t - start) / 60_000;
                boolean burst = minute >= 20 && minute < 25;
                t += burst ? 5 + rnd.nextInt(20) : 40 + rnd.nextInt(80);
                String level = burst && rnd.nextInt(6) == 0 ? "ERROR"
                        : rnd.nextInt(400) == 0 ? "ERROR" : rnd.nextInt(60) == 0 ? "WARN" : "INFO";
                w.write(TimelineBar.formatFull(t) + " " + level + " [worker-" + rnd.nextInt(8)
                        + "] c.a.Service - " + (level.equals("ERROR")
                        ? "Database connection lost after " + rnd.nextInt(30000) + " ms"
                        : "request handled in " + rnd.nextInt(300) + " ms") + "\n");
                if (level.equals("ERROR") && rnd.nextBoolean()) {
                    w.write("java.sql.SQLTransientConnectionException: Connection is not available\n");
                    w.write("\tat com.acme.db.Pool.get(Pool.java:42)\n");
                }
                n++;
            }
        }
        onEdt(() -> {
            frame.openFile(incident);
            return null;
        });
        LogTab it = onEdt(frame::currentTab);
        waitFor(() -> it.timeIndex() != null, 30_000, "timestamps detected");
        assertTrue(it.timeIndex().awaitIdle(60_000));
        assertTrue(it.errorLines().awaitIdle(60_000));
        waitFor(() -> it.timeline().histogramForTest() != null, 30_000, "timeline");
        onEdt(() -> {
            it.timeline().refresh();
            it.goToTime(TimelineBar.parseUserTime("2026-10-21 10:21:00", 0));
            return null;
        });
        settle();
        settle();
        shot("19-timeline");
        onEdt(() -> {
            it.selectTimeRange(TimelineBar.parseUserTime("2026-10-21 10:19:30", 0),
                    TimelineBar.parseUserTime("2026-10-21 10:25:30", 0));
            return null;
        });
        waitForFilters(it, System.nanoTime());
        onEdt(() -> {
            it.filterPanel().addFilter("ERROR", FilterDef.Action.SHOW).matchCase = true;
            it.filtersChanged();
            return null;
        });
        waitForFilters(it, System.nanoTime());
        settle();
        shot("20-time-range-and-filter");
    }

    @Test
    @Order(16)
    void topMessages() throws Exception {
        Path incident = dir.resolve("incident.log");
        onEdt(() -> {
            frame.openFile(incident);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        onEdt(() -> {
            for (FilterDef d : tab.filterPanel().filters()) {
                d.enabled = false;
            }
            tab.filtersChanged();
            tab.clearTimeRange();
            tab.setMatchingOnly(false);
            tab.showMessagesTab();
            return null;
        });
        waitFor(() -> tab.messagesPanel().result() != null, 60_000, "messages analyzed");
        logsift.core.MessageStats.Result r = tab.messagesPanel().result();
        List<String> lines = Files.readAllLines(incident);
        long heads = lines.stream().filter(l -> l.startsWith("2026-")).count();
        assertEquals(heads, r.lines(), "every entry counted");
        assertEquals(heads, r.templates().stream().mapToLong(logsift.core.MessageStats.Template::count).sum());
        long dbLost = lines.stream().filter(l -> l.contains("Database connection lost")).count();
        var db = r.templates().stream().filter(t -> t.text().contains("Database connection lost"))
                .findFirst().orElseThrow();
        assertEquals(dbLost, db.count(), "errors grouped into one message");
        assertEquals("ERROR", db.level());
        var top = r.templates().get(0);
        assertTrue(top.text().contains("request handled in <*> ms"), "top message: " + top.text());
        settle();
        shot("21-messages");

        // hide the most frequent message: what remains is everything else
        onEdt(() -> {
            tab.messagesPanel().tableForTest().setRowSelectionInterval(0, 0);
            return null;
        });
        long t0 = System.nanoTime();
        onEdt(() -> {
            MessagesPanel mp = tab.messagesPanel();
            // same as right-click → Hide These Lines
            FilterDef d = tab.filterPanel().addFilter(top.regex(), FilterDef.Action.HIDE);
            d.regex = true;
            d.matchCase = true;
            tab.filtersChanged();
            tab.setMatchingOnly(true);
            return mp;
        });
        FilterResult fr = waitForFilters(tab, t0);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(top.regex());
        // whole entries are hidden: lines of entries whose first line matches
        long hidden = 0;
        boolean inHidden = false;
        for (String l : lines) {
            if (l.startsWith("2026-")) {
                inHidden = p.matcher(l).find();
            }
            if (inHidden) {
                hidden++;
            }
        }
        assertEquals(lines.size() - hidden, fr.count(), "lines left after hiding the top message");

        // speed on the big log
        onEdt(() -> {
            frame.openFile(big);
            return null;
        });
        LogTab bt = onEdt(frame::currentTab);
        long t1 = System.nanoTime();
        onEdt(() -> {
            bt.showMessagesTab();
            if (bt.messagesPanel().result() != null) {
                bt.messagesPanel().start();
            }
            return null;
        });
        waitFor(() -> bt.messagesPanel().result() != null && !bt.messagesPanel().isRunning(),
                300_000, "big log analyzed");
        logsift.core.MessageStats.Result br = bt.messagesPanel().result();
        settle();
        shot("22-messages-big-log");
        summary.put("Top messages (whole file)", String.format("%,d kinds in %,d entries in %d ms",
                br.templates().size(), br.lines(), ms(t1)));
        onEdt(() -> {
            tab.setMatchingOnly(false);
            return null;
        });
    }

    @Test
    @Order(17)
    void mergeByTime() throws Exception {
        Path folder = dir.resolve("folder");
        List<Path> files = List.of(folder.resolve("app.log"), folder.resolve("node2/app.log"));
        long total = 0;
        for (Path f : files) {
            try (var lines = Files.lines(f)) {
                total += lines.count();
            }
        }
        onEdt(() -> {
            frame.mergeFiles(files);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null
                        && frame.currentTab().sourceLabels() != null);
            } catch (Exception e) {
                return false;
            }
        }, 120_000, "merged tab opened");
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> !tab.document().isIndexing() && tab.timeIndex() != null, 60_000, "indexed");
        assertEquals(total, tab.document().lineCount(), "every line of both logs");
        assertEquals(List.of("folder-app", "node2-app"), tab.sourceLabels());
        // time order and a source color on every line
        logsift.core.TimeFormat tf = tab.timeIndex().format();
        long last = Long.MIN_VALUE;
        for (long l = 0; l < 20_000; l++) {
            String text = tab.document().line(l);
            long t = tf.parse(text);
            if (t != logsift.core.TimeFormat.NONE) {
                assertTrue(t >= last, "time order at line " + l);
                last = t;
            }
        }
        for (int row = 0; row < 2000; row += 97) {
            int r0 = row;
            String text = tab.document().line(row);
            int source = text.startsWith("<folder-app>") ? 0 : 1;
            assertEquals(LogTab.sourceColor(source), onEdt(() -> tab.mainView().source().tag(r0)),
                    "the color of the line's source, row " + row);
        }
        onEdt(() -> {
            tab.mainView().goToRow(1000, true);
            return null;
        });
        settle();
        shot("23-merged-by-time");
        // only the second node's errors
        onEdt(() -> {
            tab.addFilter("<node2-app> ", FilterDef.Action.SHOW);
            FilterDef err = tab.filterPanel().addFilter("ERROR", FilterDef.Action.SHOW);
            err.matchCase = true;
            tab.filterPanel().setFormula("A AND B");
            tab.setMatchingOnly(true);
            return null;
        });
        FilterResult r = waitForFilters(tab, System.nanoTime());
        long expected;
        try (var lines = Files.lines(files.get(1))) {
            // whole entries: ERROR lines plus their stack traces
            List<String> all = lines.toList();
            expected = 0;
            boolean in = false;
            for (String l : all) {
                if (l.startsWith("2026-")) {
                    in = l.contains("ERROR");
                }
                if (in) {
                    expected++;
                }
            }
        }
        assertEquals(expected, r.count(), "node2 errors with their stack traces");
        settle();
        shot("24-merged-one-source-errors");
    }

    @Test
    @Order(18)
    void columnsLevelsAndValues() throws Exception {
        onEdt(() -> {
            frame.openFile(big);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        onEdt(() -> {
            for (FilterDef d : tab.filterPanel().filters()) {
                d.enabled = false;
            }
            tab.filtersChanged();
            tab.clearTimeRange();
            tab.setMatchingOnly(false);
            frame.setColumns(true);
            tab.mainView().goToRow(12, true);
            return null;
        });
        logsift.core.LogFormat f = tab.logFormat();
        assertTrue(f != null, "column layout detected");
        assertEquals("logback / log4j (time level [thread] logger - message)", f.name());
        assertTrue(onEdt(() -> tab.mainView().columns()) != null, "columns shown");
        settle();
        shot("25-columns");

        // level chips: only errors and warnings (whole entries)
        long t0 = System.nanoTime();
        onEdt(() -> {
            tab.filterPanel().setLevels("E", "W");
            tab.setMatchingOnly(true);
            return null;
        });
        FilterResult r = waitForFilters(tab, t0);
        java.util.regex.Pattern lv = java.util.regex.Pattern.compile(
                "(?<![\\p{L}\\p{N}_])(ERROR|FATAL|SEVERE|CRITICAL|WARN|WARNING)(?![\\p{L}\\p{N}_])");
        long expected = 0;
        java.util.Map<String, Long> threads = new java.util.HashMap<>();
        try (var rd = Files.newBufferedReader(big, StandardCharsets.UTF_8)) {
            List<String> entry = new ArrayList<>();
            String line;
            while (true) {
                line = rd.readLine();
                if (line == null || (line.startsWith("2026-") && !entry.isEmpty())) {
                    if (entry.stream().anyMatch(x -> lv.matcher(x).find())) {
                        expected += entry.size();
                    }
                    entry.clear();
                }
                if (line == null) {
                    break;
                }
                entry.add(line);
                String[] v = f.split(line, null);
                if (v != null) {
                    threads.merge(v[f.columns().indexOf("thread")], 1L, Long::sum);
                }
            }
        }
        assertEquals(expected, r.count(), "errors and warnings with their stack traces");
        settle();
        shot("26-columns-errors-and-warnings");

        // count the values of the thread column
        onEdt(() -> {
            tab.countValues("thread");
            return null;
        });
        waitFor(() -> ValuesDialog.lastForTest() != null && ValuesDialog.lastForTest().result()[0] != null,
                120_000, "thread values counted");
        logsift.core.ColumnStats.Result vr = ValuesDialog.lastForTest().result()[0];
        java.util.Map<String, Long> got = new java.util.HashMap<>();
        vr.values().forEach(v -> got.put(v.value(), v.count()));
        assertEquals(threads, got, "thread counts");
        settle();
        shotOf(ValuesDialog.lastForTest().dialog().getRootPane(), "27-thread-values");
        onEdt(() -> {
            ValuesDialog.lastForTest().dialog().dispose();
            tab.filterPanel().setLevels("E", "W", "I", "D", "T");
            tab.setMatchingOnly(false);
            frame.setColumns(false);
            return null;
        });
    }

    @Test
    @Order(19)
    void exportFilteredLines() throws Exception {
        onEdt(() -> {
            frame.openFile(big);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        onEdt(() -> {
            for (FilterDef d : tab.filterPanel().filters()) {
                d.enabled = false;
            }
            FilterDef d = tab.filterPanel().addFilter("Payment failed", FilterDef.Action.SHOW);
            d.matchCase = true;
            tab.filtersChanged();
            return null;
        });
        FilterResult r = waitForFilters(tab, System.nanoTime());
        Path out = dir.resolve("exported.log");
        Files.deleteIfExists(out);
        long[] written = {-1};
        long t0 = System.nanoTime();
        onEdt(() -> {
            frame.exportTo(tab, tab.filteredRuns(), out, n -> written[0] = n);
            return null;
        });
        waitFor(() -> written[0] >= 0, 120_000, "export finished");
        long exportMs = ms(t0);
        assertEquals(r.count(), written[0], "lines written");
        List<String> got = Files.readAllLines(out);
        assertEquals(r.count(), got.size());
        for (int i = 0; i < got.size(); i += Math.max(1, got.size() / 500)) {
            assertEquals(tab.document().line(r.lineAt(i)), got.get(i), "exported line " + i);
        }
        summary.put("Export filtered lines", String.format("%,d lines in %d ms", written[0], exportMs));
        Files.delete(out);
        onEdt(() -> {
            for (FilterDef d : tab.filterPanel().filters()) {
                d.enabled = false;
            }
            tab.filtersChanged();
            return null;
        });
    }

    @Test
    @Order(20)
    void bookmarks() throws Exception {
        Path f = dir.resolve("bookmarks.log");
        Files.copy(dir.resolve("incident.log"), f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        onEdt(() -> {
            frame.openFile(f);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> !tab.document().isIndexing(), 30_000, "indexed");
        onEdt(() -> {
            tab.bookmarks().clear();
            for (long line : new long[] {30_000, 100, 5_000}) {
                tab.mainView().goToRow((int) line, true);
                tab.toggleBookmark();
            }
            tab.bookmarks().set(5_000, "first DB error of the incident?");
            tab.showBookmarksTab();
            return null;
        });
        // F2 goes through them in line order and wraps around; Shift+F2 goes back
        long[] visits = onEdt(() -> {
            tab.mainView().goToRow(0, false);
            long[] v = new long[5];
            for (int i = 0; i < 4; i++) {
                tab.nextBookmark(true);
                v[i] = tab.currentLine();
            }
            tab.nextBookmark(false);
            v[4] = tab.currentLine();
            return v;
        });
        assertArrayEquals(new long[] {100, 5_000, 30_000, 100, 30_000}, visits);
        onEdt(() -> {
            tab.mainView().goToRow(4_990, true);
            return null;
        });
        settle();
        shot("28-bookmarks");

        // remembered when the file is opened again
        onEdt(() -> {
            frame.closeFile(f);
            frame.openFile(f);
            return null;
        });
        LogTab again = onEdt(frame::currentTab);
        assertEquals(java.util.Map.of(100L, "", 5_000L, "first DB error of the incident?", 30_000L, ""),
                new java.util.HashMap<>(again.bookmarks().all()), "bookmarks restored");

        // a truncated file forgets them (the lines moved)
        onEdt(() -> {
            frame.closeFile(f);
            return null;
        });
        Files.writeString(f, "short\n");
        onEdt(() -> {
            frame.openFile(f);
            return null;
        });
        assertEquals(0, onEdt(() -> frame.currentTab().bookmarks().size()), "dropped after truncation");
        onEdt(() -> {
            frame.closeFile(f);
            return null;
        });
    }

    @Test
    @Order(21)
    void sessionAndRecentFiles() throws Exception {
        Path incident = dir.resolve("incident.log");
        Path sparse = dir.resolve("sparse.log");
        onEdt(() -> {
            frame.openFile(sparse);
            frame.openFile(incident);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        onEdt(() -> {
            for (FilterDef d : List.copyOf(tab.filterPanel().filters())) {
                d.enabled = false;
            }
            FilterDef d = tab.filterPanel().addFilter("Database connection lost", FilterDef.Action.SHOW);
            d.matchCase = true;
            tab.filtersChanged();
            tab.setMatchingOnly(true);
            return null;
        });
        FilterResult r = waitForFilters(tab, System.nanoTime());
        long line = r.lineAt(r.count() / 2);
        onEdt(() -> {
            tab.showLine(line);
            frame.saveSession();
            return null;
        });
        assertTrue(AppState.recentFiles().contains(incident.toAbsolutePath().normalize()), "recent files");
        assertEquals(incident.toAbsolutePath().normalize(), AppState.recentFiles().get(0), "most recent first");

        // a new window restores the session: files, filters, view mode and position
        MainFrame second = onEdt(() -> {
            MainFrame m = new MainFrame();
            m.setSize(1200, 700);
            m.setVisible(true);
            m.restoreSession();
            return m;
        });
        try {
            // the session holds files from earlier tests too; some (in a zip) open in the background
            waitForRestore(second);
            LogTab restored = onEdt(second::currentTab);
            assertEquals(incident.toAbsolutePath().normalize(), restored.document().path(), "selected tab");
            waitFor(() -> {
                try {
                    return onEdt(() -> restored.isMatchingOnly() && restored.currentLine() == line);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "filters, view and position restored (expected line " + line + ")");
            assertTrue(onEdt(() -> restored.filterPanel().filters().stream()
                    .anyMatch(f -> f.text.equals("Database connection lost") && f.enabled)), "filter restored");
        } finally {
            onEdt(() -> {
                second.dispose();
                return null;
            });
        }
    }

    /** Waits until every file of the last session has opened (or failed to) in this window. */
    private static void waitForRestore(MainFrame m) throws InterruptedException {
        waitFor(() -> {
            try {
                return onEdt(() -> !m.isRestoringSessionForTest());
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "session restored");
    }

    /** Restores this session in a new window; returns the selected tab's file once all are open. */
    private static Path restoredSelection(List<Path> files, int selected) throws Exception {
        MainFrame m = onEdt(() -> {
            // on the EDT: after the last window's own save of its session when it closed
            AppState.saveSession(files, selected);
            MainFrame f = new MainFrame();
            f.setSize(900, 600);
            f.setVisible(true);
            f.restoreSession();
            return f;
        });
        try {
            waitForRestore(m);
            settle();
            return onEdt(() -> {
                assertEquals(files.size(), m.tabsForTest().getTabCount(), "all tabs open");
                return m.currentTab() == null ? null : m.currentTab().path();
            });
        } finally {
            onEdt(() -> {
                m.dispose();
                return null;
            });
        }
    }

    @Test
    @Order(21)
    void sessionRestoreSelectsSavedTabWhateverOpensLast() throws Exception {
        Path a = dir.resolve("session-a.log").toAbsolutePath().normalize();
        Path b = dir.resolve("session-b.log").toAbsolutePath().normalize();
        Path gz = dir.resolve("session-slow.log.gz").toAbsolutePath().normalize();
        Files.writeString(a, "2026-10-21 10:00:00 INFO [main] a\n");
        Files.writeString(b, "2026-10-21 10:00:00 INFO [main] b\n");
        try (var o = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            o.write("2026-10-21 10:00:00 INFO [main] packed\n".getBytes(StandardCharsets.UTF_8));
        }
        // a .gz is always unpacked in the background, so it opens after the plain files
        assertEquals(a, restoredSelection(List.of(gz, a, b), 1), "selected file opens before a packed one");
        assertEquals(b, restoredSelection(List.of(a, b, gz), 1), "packed file last in the session");
        assertEquals(gz, restoredSelection(List.of(a, gz, b), 1), "the selected file is the slow one");
        // every file slow (a network drive): they finish in any order, each with "Opening…"
        System.setProperty("logsift.openWaitMs", "50");
        System.setProperty("logsift.testOpenDelayMs", "600");
        try {
            assertEquals(a, restoredSelection(List.of(b, a, gz), 1), "all files slow");
            assertEquals(b, restoredSelection(List.of(gz, a, b), 2), "all files slow, last selected");
        } finally {
            System.setProperty("logsift.openWaitMs", "10000");
            System.clearProperty("logsift.testOpenDelayMs");
        }
    }

    @Test
    @Order(22)
    void alertsOnNewLines() throws Exception {
        Path f = dir.resolve("alerts.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("2026-10-21 10:00:").append(String.format("%02d", i % 60)).append(" INFO [main] ok ").append(i).append('\n');
        }
        sb.append("2026-10-21 10:01:00 ERROR [main] java.lang.OutOfMemoryError: old, before the alert\n");
        Files.writeString(f, sb);
        onEdt(() -> {
            frame.openFile(f);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> !tab.document().isIndexing(), 30_000, "indexed");
        onEdt(() -> {
            FilterDef d = tab.filterPanel().addFilter("OutOfMemory", FilterDef.Action.MARK);
            d.matchCase = true;
            tab.filtersChanged();
            return null;
        });
        waitForFilters(tab, System.nanoTime());
        String before = Notifier.lastMessageForTest();
        onEdt(() -> {
            FilterDef d = tab.filterPanel().filters().get(tab.filterPanel().filters().size() - 1);
            d.alert = true;
            tab.alertChanged(d);
            frame.openFile(dir.resolve("incident.log")); // another tab is in front: expect a badge
            return null;
        });
        Thread.sleep(1500);
        assertEquals(before, Notifier.lastMessageForTest(), "lines that were already there don't alert");
        Files.writeString(f, "2026-10-21 10:02:00 ERROR [worker-3] java.lang.OutOfMemoryError: Java heap space\n"
                + "2026-10-21 10:02:01 ERROR [worker-4] java.lang.OutOfMemoryError: Metaspace\n",
                java.nio.file.StandardOpenOption.APPEND);
        waitFor(() -> Notifier.lastMessageForTest() != null && !Notifier.lastMessageForTest().equals(before),
                15_000, "an alert for the new lines");
        String msg = Notifier.lastMessageForTest();
        assertTrue(msg.contains("2 new lines matching “OutOfMemory”"), msg);
        assertTrue(msg.contains("Java heap space"), "shows the first new line: " + msg);
        assertTrue(onEdt(() -> tab.getClientProperty("alerts")) != null, "the tab shows a badge");
        settle();
        shot("29-alert");
        if (Notifier.popupForTest() != null) {
            shotOf(Notifier.popupForTest().getRootPane(), "29b-alert-popup");
        }
        Notifier.dismiss();
    }

    @Test
    @Order(23)
    void encodings() throws Exception {
        String text = "2026-10-21 10:00:00 INFO Bestellung für Müller angelegt\n"
                + "2026-10-21 10:00:01 ERROR Zahlung fehlgeschlagen: Straße unbekannt\n"
                + "2026-10-21 10:00:02 WARN 東京 キャッシュ 😀\n";
        Path utf16 = dir.resolve("windows-utf16.log");
        Files.write(utf16, ("\uFEFF" + text).getBytes(StandardCharsets.UTF_16LE));
        Path cp1252 = dir.resolve("windows-1252.log");
        Files.write(cp1252, text.replace("東京 キャッシュ 😀", "Grüße").getBytes(logsift.core.Encoding.WINDOWS_1252));

        onEdt(() -> {
            frame.openFile(cp1252);
            return null;
        });
        LogTab w = onEdt(frame::currentTab);
        waitFor(() -> !w.document().isIndexing(), 10_000, "indexed");
        assertEquals("Windows-1252", w.encodingLabel());
        assertEquals("2026-10-21 10:00:01 ERROR Zahlung fehlgeschlagen: Straße unbekannt", w.document().line(1));
        onEdt(() -> {
            frame.findAll(new SearchQuery("straße", false, false, false));
            return null;
        });
        assertEquals(1, waitForSearch(w).hits().size(), "ignore-case search with non-ASCII");

        onEdt(() -> {
            frame.openFile(utf16);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null && frame.currentTab().path().equals(utf16.toAbsolutePath().normalize()));
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "UTF-16 log opened");
        LogTab u = onEdt(frame::currentTab);
        waitFor(() -> !u.document().isIndexing() && u.document().lineCount() == 3, 10_000, "indexed");
        assertEquals("UTF-16LE", u.encodingLabel());
        assertEquals("2026-10-21 10:00:02 WARN 東京 キャッシュ 😀", u.document().line(2));
        // the UTF-16 log grows: the new line shows up
        Files.write(utf16, "2026-10-21 10:00:03 ERROR neu hinzugefügt\n".getBytes(StandardCharsets.UTF_16LE),
                java.nio.file.StandardOpenOption.APPEND);
        waitFor(() -> u.document().lineCount() == 4, 10_000, "appended line visible");
        assertEquals("2026-10-21 10:00:03 ERROR neu hinzugefügt", u.document().line(3));
        onEdt(() -> {
            frame.findAll(new SearchQuery("Müller", true, false, false));
            return null;
        });
        assertEquals(1, waitForSearch(u).hits().size());
        settle();
        shot("30-utf16-log");
    }

    @Test
    @Order(24)
    void jsonLogs() throws Exception {
        Path f = dir.resolve("app.json.log");
        Random rnd = new Random(24);
        String[] levels = {"INFO", "INFO", "INFO", "DEBUG", "WARN", "ERROR"};
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, Long> levelCount = new java.util.TreeMap<>();
        for (int i = 0; i < 3000; i++) {
            String level = levels[rnd.nextInt(levels.length)];
            levelCount.merge(level, 1L, Long::sum);
            sb.append(String.format("{\"@timestamp\":\"2026-10-21T10:%02d:%02d.%03dZ\",\"level\":\"%s\","
                    + "\"logger\":\"com.acme.%s\",\"thread\":\"worker-%d\",\"message\":\"%s\","
                    + "\"user\":\"u%d\",\"durationMs\":%d,\"tags\":[\"api\",\"v2\"]}%n",
                    i / 60 % 60, i % 60, i % 1000, level, rnd.nextBoolean() ? "OrderService" : "PaymentGateway",
                    rnd.nextInt(4), level.equals("ERROR") ? "Payment failed: card declined" : "request handled",
                    rnd.nextInt(1000), rnd.nextInt(500)));
        }
        Files.writeString(f, sb);
        onEdt(() -> {
            frame.openFile(f);
            frame.setColumns(true);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> tab.logFormat() != null && tab.timeIndex() != null, 30_000, "JSON detected");
        assertTrue(tab.logFormat().isJson());
        assertEquals(List.of("time", "level", "thread", "logger", "fields", "message"), tab.logFormat().columns());
        long errorLine = onEdt(() -> {
            for (long l = 0; l < 3000; l++) {
                if (tab.document().line(l).contains("\"ERROR\"")) {
                    tab.mainView().goToRow((int) l, true);
                    return l;
                }
            }
            return -1L;
        });
        onEdt(() -> {
            tab.showDetailsTab();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.detailsPanel().textForTest()).contains("\"level\"");
            } catch (Exception e) {
                return false;
            }
        }, 10_000, "details shown");
        // text components give Windows line endings on Windows
        String details = onEdt(() -> tab.detailsPanel().textForTest()).replace("\r\n", "\n");
        assertTrue(details.contains("\"level\": \"ERROR\"") && details.contains("\"tags\": [\n"), details);
        settle();
        shot("31-json-columns-and-details");

        onEdt(() -> {
            tab.countValues("level");
            return null;
        });
        waitFor(() -> ValuesDialog.lastForTest() != null && ValuesDialog.lastForTest().result()[0] != null
                && ValuesDialog.lastForTest().result()[0].column().equals("level"), 60_000, "levels counted");
        java.util.Map<String, Long> got = new java.util.TreeMap<>();
        ValuesDialog.lastForTest().result()[0].values().forEach(v -> got.put(v.value(), v.count()));
        assertEquals(levelCount, got, "level counts in JSON");
        onEdt(() -> {
            ValuesDialog.lastForTest().dialog().dispose();
            frame.setColumns(false);
            return null;
        });
        assertTrue(errorLine >= 0);
    }

    @Test
    @Order(25)
    void remoteLogOverSftp() throws Exception {
        Path remoteRoot = Files.createDirectories(dir.resolve("sftp-root/var/log/app")).getParent().getParent().getParent();
        Path remoteLog = remoteRoot.resolve("var/log/app/server.log");
        Files.copy(dir.resolve("incident.log"), remoteLog, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var server = org.apache.sshd.server.SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
                dir.resolve("sftp-hostkey.ser")));
        server.setPasswordAuthenticator((u, pw, sess) -> u.equals("ops") && pw.equals("secret"));
        server.setSubsystemFactories(List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory()));
        server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(remoteRoot));
        server.start();
        System.setProperty("logsift.noKnownHosts", "true"); // don't touch the real known_hosts
        try {
            Files.writeString(remoteLog.resolveSibling("access.log"), "x\n");
            Files.createDirectories(remoteLog.getParent().resolve("archive"));
            // the dialog: connect, type a folder in the address bar, open a folder, pick the file
            RemoteDialog dlg = onEdt(() -> {
                RemoteDialog d = frame.openRemoteDialogForTest();
                d.setConnection("127.0.0.1", server.getPort(), "ops", "secret");
                d.connect();
                return d;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> dlg.folderForTest() != null);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "connected and listed the start folder");
            onEdt(() -> {
                dlg.go("/var/log");
                return null;
            });
            waitForListing(dlg, () -> "/var/log".equals(dlg.folderForTest()), "typed folder listed");
            assertEquals(List.of("..", "app"), onEdt(dlg::namesForTest));
            onEdt(() -> {
                dlg.go("app");
                return null;
            });
            waitForListing(dlg, () -> dlg.namesForTest().contains("server.log"), "app folder listed");
            assertEquals(List.of("..", "archive", "access.log", "server.log"), onEdt(dlg::namesForTest),
                    "folders first, then files");
            int before = onEdt(dlg::listingsForTest);
            onEdt(() -> {
                dlg.go("/var/log/app");
                return null;
            });
            waitForListing(dlg, () -> dlg.listingsForTest() > before && "/var/log/app".equals(dlg.folderForTest()),
                    "app folder by its full path (listings before: " + before + ")");
            onEdt(() -> {
                dlg.select("server.log");
                return null;
            });
            settle();
            shotOf(dlg.getRootPane(), "38-remote-file-browser");
            onEdt(() -> {
                dlg.openSelected();
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentTab() != null && frame.currentTab().remoteLabel() != null);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "remote log opened");
            LogTab tab = onEdt(frame::currentTab);
            long lines;
            try (var l = Files.lines(remoteLog)) {
                lines = l.count();
            }
            waitFor(() -> tab.document().lineCount() == lines, 30_000, "all remote lines");
            assertEquals("server.log @ 127.0.0.1", tab.title());
            // the server's log grows: new lines show up
            Files.writeString(remoteLog, "2026-10-21 11:00:01.000 ERROR [worker-1] c.a.Service - written on the server\n",
                    java.nio.file.StandardOpenOption.APPEND);
            waitFor(() -> tab.document().lineCount() == lines + 1, 30_000, "appended remote line");
            assertTrue(tab.document().line(lines).endsWith("written on the server"));
            onEdt(() -> {
                frame.setFollow(true);
                return null;
            });
            settle();
            shot("32-remote-log");
            onEdt(() -> {
                frame.closeTab(frame.currentTabIndexForTest());
                return null;
            });
        } finally {
            System.clearProperty("logsift.noKnownHosts");
            server.stop(true);
        }
    }

    @Test
    @Order(26)
    void compareTwoLogs() throws Exception {
        Path good = dir.resolve("run-good.log");
        Path bad = dir.resolve("run-bad.log");
        for (int run = 0; run < 2; run++) {
            boolean isBad = run == 1;
            Random rnd = new Random(26 + run);
            StringBuilder sb = new StringBuilder();
            int n = isBad ? 60_000 : 40_000;
            for (int i = 0; i < n; i++) {
                sb.append(String.format("2026-10-21 10:%02d:%02d.%03d ", i / 3600 % 60, i / 60 % 60, i % 1000));
                int r = rnd.nextInt(100);
                if (isBad && r < 2) {
                    sb.append("ERROR [worker-").append(rnd.nextInt(8)).append("] c.a.Db - Connection refused to db-")
                            .append(rnd.nextInt(3)).append(":5432\n");
                } else if (r < (isBad ? 12 : 3)) {
                    sb.append("WARN [worker-").append(rnd.nextInt(8)).append("] c.a.Http - Retrying request (attempt ")
                            .append(1 + rnd.nextInt(5)).append("/5)\n");
                } else if (!isBad && r < 5) {
                    sb.append("INFO [main] c.a.Cache - Cache warmed with ").append(rnd.nextInt(9999)).append(" entries\n");
                } else {
                    sb.append("INFO [worker-").append(rnd.nextInt(8)).append("] c.a.Api - request handled in ")
                            .append(rnd.nextInt(300)).append(" ms\n");
                }
            }
            Files.writeString(isBad ? bad : good, sb);
        }
        onEdt(() -> {
            frame.openFile(good);
            frame.openFile(bad);
            return null;
        });
        LogTab badTab = onEdt(frame::currentTab);
        waitFor(() -> !badTab.document().isIndexing(), 30_000, "indexed");
        CompareTab ct = onEdt(() -> {
            LogTab goodTab = null;
            for (int i = 0; i < 200; i++) {
                frame.openFile(good);
                goodTab = frame.currentTab();
                break;
            }
            LogTab g = goodTab;
            return frame.compare(g, badTab);
        });
        waitFor(() -> ct.result() != null, 60_000, "compared");
        settle();
        List<logsift.core.LogCompare.Diff> shown = onEdt(ct::shownForTest);
        assertEquals(logsift.core.LogCompare.Kind.NEW, shown.get(0).kind());
        assertTrue(shown.get(0).text().contains("Connection refused to"), shown.get(0).text());
        assertTrue(shown.stream().anyMatch(d -> d.kind() == logsift.core.LogCompare.Kind.GONE
                && d.text().contains("Cache warmed")), "gone message listed");
        assertTrue(shown.stream().anyMatch(d -> d.kind() == logsift.core.LogCompare.Kind.MORE
                && d.text().contains("Retrying request")), "more frequent warning listed");
        shot("33-compare-logs");
        // double-click: the bad log, at the first new error, with Find All
        var first = shown.get(0);
        onEdt(() -> {
            frame.showDiff(ct, first);
            return null;
        });
        assertEquals(badTab, onEdt(frame::currentTab));
        assertEquals(first.firstB(), (long) onEdt(badTab::currentLine));
        assertEquals(first.countB(), waitForSearch(badTab).hits().size(), "Find All finds all of them");
    }

    @Test
    @Order(27)
    void wrapInColumnsClearAllFoldAndSlowOpen() throws Exception {
        // 1. wrap works in the columns view: long messages wrap under the Message column
        Path f = dir.resolve("wrap-columns.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append(String.format("2026-10-21 10:00:%02d.%03d INFO  [worker-%d] c.a.Svc - ", i % 60, i, i % 4));
            sb.append(i % 3 == 0 ? "a long message with many words ".repeat(12) : "short").append('\n');
        }
        Files.writeString(f, sb);
        onEdt(() -> {
            frame.openFile(f);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> tab.logFormat() != null, 30_000, "layout detected");
        int segs = onEdt(() -> {
            frame.setColumns(true);
            frame.setWrap(true);
            tab.mainView().goToRow(0, false);
            return tab.mainView().segmentsForTest(3);
        });
        assertTrue(segs > 1, "a long message wraps in the columns view: " + segs);
        assertEquals(1, (int) onEdt(() -> tab.mainView().segmentsForTest(1)), "a short one doesn't");
        settle();
        shot("34-wrap-in-columns-and-stripes");
        onEdt(() -> {
            frame.setWrap(false);
            frame.setColumns(false);
            return null;
        });

        // 2. Clear all: filters, level chips and the time range go; all lines are shown
        onEdt(() -> {
            tab.filterPanel().addFilter("long", FilterDef.Action.SHOW);
            tab.filterPanel().setLevels("E", "W");
            tab.setMatchingOnly(true);
            return null;
        });
        waitForFilters(tab, System.nanoTime());
        onEdt(() -> {
            tab.filterPanel().clearAll();
            return null;
        });
        waitFor(() -> !tab.filterEngine().result().isFiltering(), 10_000, "no filtering after Clear all");
        assertTrue(onEdt(() -> tab.filterPanel().filters().isEmpty() && tab.filterPanel().levelWords() == null
                && !tab.isMatchingOnly() && tab.timeRange() == null), "everything cleared");

        // 3. Clear on the Find results
        onEdt(() -> {
            frame.findAll(new SearchQuery("worker-2", true, false, false));
            return null;
        });
        waitForSearch(tab);
        onEdt(() -> {
            tab.closeResults();
            return null;
        });
        assertEquals(null, onEdt(tab::search), "results cleared");

        // 4. the bottom panel folds to its tab strip and comes back
        int open = onEdt(tab::bottomHeightForTest);
        int folded = onEdt(() -> {
            tab.setBottomCollapsed(true);
            return tab.bottomHeightForTest();
        });
        assertTrue(folded < 45 && folded < open, "folded to the tabs: " + folded + " (was " + open + ")");
        settle();
        shot("35-bottom-panel-folded");
        int reopened = onEdt(() -> {
            JTabbedPane b = tab.bottomTabsForTest();
            java.awt.Rectangle r = b.getBoundsAt(1);
            b.dispatchEvent(new MouseEvent(b, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK, r.x + 5, r.y + 5, 1, false, MouseEvent.BUTTON1));
            return 0;
        });
        settle();
        int afterClick = onEdt(tab::bottomHeightForTest);
        assertTrue(afterClick > 100 && reopened == 0, "clicking a tab opens it again: " + afterClick);

        // 6. a file on a slow drive: the window stays responsive, "Opening…" shows, then the tab
        Path slow = dir.resolve("slow-drive.log");
        Files.writeString(slow, "2026-10-21 10:00:00 INFO from a slow drive\n");
        System.setProperty("logsift.openWaitMs", "100");
        System.setProperty("logsift.testOpenDelayMs", "1500");
        try {
            long t0 = System.nanoTime();
            onEdt(() -> {
                frame.openFile(slow);
                return null;
            });
            long blocked = ms(t0);
            assertTrue(blocked < 800, "the window was blocked for " + blocked + " ms");
            assertTrue(onEdt(() -> frame.isOpeningForTest(slow)), "opening in the background");
            settle();
            shot("36-opening-from-slow-drive");
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentTab() != null
                            && frame.currentTab().path().equals(slow.toAbsolutePath().normalize()));
                } catch (Exception e) {
                    return false;
                }
            }, 15_000, "slow file opened");
        } finally {
            System.setProperty("logsift.openWaitMs", "10000");
            System.clearProperty("logsift.testOpenDelayMs");
        }
    }

    @Test
    @Order(28)
    void manualReload() throws Exception {
        Path f = dir.resolve("manual-reload.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("2026-10-21 10:00:00 INFO line ").append(i).append('\n');
        }
        Files.writeString(f, sb);
        onEdt(() -> {
            frame.setAutoReload(false);
            frame.openFile(f);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> !tab.document().isIndexing() && tab.document().lineCount() == 100, 10_000, "loaded");
        String extra = "2026-10-21 10:00:01 ERROR written after opening\n".repeat(50);
        Files.writeString(f, extra, java.nio.file.StandardOpenOption.APPEND);
        waitFor(() -> tab.document().pendingChange() == logsift.core.LogDocument.Change.GREW, 10_000,
                "the change on disk is noticed");
        Thread.sleep(700);
        assertEquals(100, tab.document().lineCount(), "not loaded by itself");
        settle();
        assertTrue(onEdt(frame::reloadButtonTextForTest).startsWith("Reload (+"),
                "the Reload button shows there is more: " + onEdt(frame::reloadButtonTextForTest));
        shot("37-reload-waiting");
        onEdt(() -> {
            frame.reload();
            return null;
        });
        waitFor(() -> tab.document().lineCount() == 150, 10_000, "loaded after Reload");
        settle();
        assertEquals("Reload", onEdt(frame::reloadButtonTextForTest));
        // Follow loads changes by itself, even with auto reload off
        onEdt(() -> {
            frame.setFollow(true);
            return null;
        });
        Files.writeString(f, "2026-10-21 10:00:02 INFO while following\n", java.nio.file.StandardOpenOption.APPEND);
        waitFor(() -> tab.document().lineCount() == 151, 10_000, "followed");
        onEdt(() -> {
            frame.setFollow(false);
            frame.setAutoReload(true);
            return null;
        });
    }

    @Test
    @Order(29)
    void severalSearchesFoldAndSearchWithin() throws Exception {
        Path f = dir.resolve("several-searches.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            String level = i % 7 == 0 ? "ERROR" : i % 5 == 0 ? "WARN" : "INFO";
            sb.append(String.format("2026-10-21 10:%02d:%02d %s [worker-%d] request %d %s%n",
                    i / 60 % 60, i % 60, level, i % 4, i, i % 3 == 0 ? "timeout after 30s" : "done"));
        }
        Files.writeString(f, sb);
        onEdt(() -> {
            frame.openFile(f);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        waitFor(() -> !tab.document().isIndexing() && tab.document().lineCount() == 3000, 10_000, "loaded");
        onEdt(() -> {
            frame.findAll(new SearchQuery("ERROR", true, false, false));
            return null;
        });
        FindGroup errors = onEdt(tab::currentFind);
        assertTrue(errors.awaitIdle(10_000));
        onEdt(() -> {
            frame.findAll(new SearchQuery("WARN", true, false, false));
            return null;
        });
        FindGroup warns = onEdt(tab::currentFind);
        assertTrue(warns.awaitIdle(10_000));
        // both are kept: the new one on top and open, the old one folded below
        assertEquals(List.of(warns, errors), onEdt(() -> tab.results().groups()));
        assertTrue(warns.expanded && !errors.expanded);
        long nErrors = IntStream.range(0, 3000).filter(i -> i % 7 == 0).count();
        assertEquals(nErrors, errors.count());
        assertEquals(warns.count() + 2, onEdt(() -> tab.resultsView().source().rowCount()),
                "two headers plus the open search's lines");

        // open the errors again and search within them
        onEdt(() -> {
            tab.results().toggle(errors);
            return null;
        });
        FindGroup within = onEdt(() -> tab.findWithin(errors, new SearchQuery("timeout", true, false, false)));
        assertTrue(within.awaitIdle(10_000));
        waitFor(() -> within.count() == IntStream.range(0, 3000).filter(i -> i % 7 == 0 && i % 3 == 0).count(),
                10_000, "errors that timed out: " + within.count());
        for (int i = 0; i < within.count(); i++) {
            String line = tab.document().line(within.lineAt(i));
            assertTrue(line.contains("ERROR") && line.contains("timeout"), line);
        }
        assertEquals("ERROR › timeout", within.title());
        assertEquals(List.of(warns, errors, within), onEdt(() -> tab.results().groups()));
        onEdt(() -> {
            tab.results().toggle(warns);
            tab.results().selectResult(within, 2);
            return null;
        });
        settle();
        shot("39-several-searches");

        // the same search again goes back to the top; closing one keeps the others
        onEdt(() -> {
            frame.findAll(new SearchQuery("ERROR", true, false, false));
            return null;
        });
        assertEquals(List.of(errors, within, warns), onEdt(() -> tab.results().groups()));
        onEdt(() -> {
            tab.results().close(errors);
            return null;
        });
        assertEquals(List.of(warns), onEdt(() -> tab.results().groups()), "closing a search closes the searches in it");
        onEdt(() -> {
            tab.results().closeAll();
            return null;
        });
        assertEquals(null, onEdt(tab::currentFind));
    }

    @Test
    @Order(30)
    void helpUpdatesAndErrors() throws Exception {
        // every menu shortcut is listed, read from the menus themselves
        List<String[]> rows = onEdt(() -> HelpSupport.shortcuts(frame.getJMenuBar()));
        assertTrue(rows.stream().anyMatch(r -> r[1].equals("Find Next") && r[2].equals("F3")), "F3 listed");
        assertTrue(rows.stream().anyMatch(r -> r[1].equals("Keyboard Shortcuts")), "the dialog lists itself");
        javax.swing.JDialog keys = onEdt(() -> {
            javax.swing.JDialog d = HelpSupport.shortcutsDialog(frame, frame.getJMenuBar());
            d.setVisible(true);
            return d;
        });
        settle();
        shotOf(keys.getRootPane(), "40-keyboard-shortcuts");
        onEdt(() -> {
            keys.dispose();
            return null;
        });
        assertTrue(HelpSupport.bugReportUrl("t", "x").contains("version="), "bug form is filled in");

        // a newer release on the (local, fake) update server shows a button in the toolbar
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", ex -> {
            byte[] b = "{\"tag_name\":\"v9.9.0\",\"html_url\":\"https://example.invalid/r\"}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        System.setProperty("logsift.updateUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
        System.setProperty("logsift.version", "1.1.0");
        try {
            onEdt(() -> {
                frame.checkForUpdates(false);
                return null;
            });
            waitFor(() -> "Logsift 9.9.0 is available".equals(frame.updateButtonTextForTest()), 10_000,
                    "update button");
            settle();
            shot("41-update-available");
        } finally {
            server.stop(0);
            System.setProperty("logsift.updateUrl", "none");
            System.clearProperty("logsift.version");
            onEdt(() -> {
                frame.hideUpdateForTest(); // not in the screenshots that follow
                return null;
            });
            AppState.prefs().remove("updates.latest");
        }

        // an unexpected error: a window with the details, Logsift keeps running
        onEdt(() -> {
            ErrorReporter.report(new IllegalStateException("Something unexpected (test)"));
            return null;
        });
        waitFor(() -> ErrorReporter.openForTest() != null, 10_000, "error window");
        settle();
        javax.swing.JDialog err = onEdt(ErrorReporter::openForTest);
        shotOf(err.getRootPane(), "42-error-window");
        assertTrue(Files.readString(Path.of(System.getProperty("logsift.home"), "errors.log"))
                .contains("Something unexpected (test)"), "logged to errors.log");
        onEdt(() -> {
            err.dispose();
            return null;
        });
    }

    @Test
    @Order(31)
    void customLogFormatsAndSettings() throws Exception {
        // a log in a layout Logsift doesn't know: "|"-separated, day-first dates with 2-digit years
        Path orders = dir.resolve("orders.log");
        StringBuilder sb = new StringBuilder();
        String[] comps = {"OrderService", "Payments", "Cache", "Http"};
        String[] lv = {"I", "I", "W", "E"};
        for (int i = 0; i < 400; i++) {
            int s = 13 + i;
            sb.append(String.format("21.10.26 10:%02d:%02d.%03d|%s|%s|%s%n", s / 60 % 60, s % 60, i * 7 % 1000,
                    lv[i % 4], comps[i % 4], i % 4 == 3 ? "payment " + i + " failed: card declined" : "request " + i + " ok"));
            if (i % 4 == 3) {
                sb.append("java.lang.IllegalStateException: declined\n    at com.acme.Pay.charge(Pay.java:12)\n");
            }
        }
        Files.writeString(orders, sb.toString().replace("\\n", "\n"));
        onEdt(() -> {
            frame.openFile(orders);
            frame.setColumns(true);
            return null;
        });
        waitFor(() -> {
            try {
                LogTab t = onEdt(frame::currentTab);
                return t != null && t.path().equals(orders.toAbsolutePath().normalize()) && t.noColumnsShownForTest();
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the unknown-layout banner");
        LogTab tab = onEdt(frame::currentTab);
        settle();
        shot("43-unknown-layout");

        // "Define Its Format…": the layout and its time format are guessed from the file
        javax.swing.SwingUtilities.invokeLater(() -> frame.defineFormat(tab));
        waitFor(() -> SettingsDialog.lastForTest() != null && SettingsDialog.lastForTest().isShowing(), 30_000,
                "the Log Formats window");
        SettingsDialog dlg = SettingsDialog.lastForTest();
        LogFormatsPanel panel = dlg.formats();
        settle();
        assertEquals("{time}|{level}|{field3}|{message}", onEdt(() -> panel.patternField().getText()));
        assertEquals("dd.MM.yy HH:mm:ss.SSS", onEdt(() -> panel.timeField().getText()));
        waitFor(() -> panel.statusLabel().getText().contains("times read"), 5_000, "status: " + panel.statusLabel().getText());
        assertTrue(panel.statusLabel().getText().startsWith("200 of 300 lines fit"), panel.statusLabel().getText());
        // rename the guessed column
        onEdt(() -> {
            panel.patternField().setText("{time}|{level}|{component}|{message}");
            return null;
        });
        Thread.sleep(400);
        settle();
        assertEquals("component", onEdt(() -> panel.previewTable().getColumnName(3)));
        shotOf(dlg.getRootPane(), "44-format-guessed-from-log");

        // New → paste a whole logback.xml: converted into a pattern and a time format
        onEdt(() -> {
            button(panel, "New").doClick();
            panel.pasteArea().setText("""
                    <configuration>
                      <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder>
                          <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
                        </encoder>
                      </appender>
                    </configuration>""");
            return null;
        });
        settle();
        shotOf(dlg.getRootPane(), "45-add-format-start");
        onEdt(() -> {
            button(panel, "Create Format").doClick();
            return null;
        });
        settle();
        assertEquals("{time} [{thread}] {level} {logger} - {message}", onEdt(() -> panel.patternField().getText()));
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", onEdt(() -> panel.timeField().getText()));
        assertEquals(2, (int) onEdt(() -> panel.formatList().getModel().getSize()));

        // closing saves the formats; the open log now has columns and a timeline
        onEdt(() -> {
            dlg.dispose();
            return null;
        });
        assertTrue(Files.readString(AppState.formatsFile()).contains("component"), "saved");
        waitFor(() -> {
            try {
                return onEdt(() -> tab.logFormat() != null && "orders".equals(tab.logFormat().name())
                        && !tab.noColumnsShownForTest() && tab.timeIndex() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "columns from the new format");
        settle();
        shot("46-custom-format-columns");
        // "E" in the level column counts as an error: marks, F8 and the level buttons
        waitFor(() -> tab.errorLines().result().matchCount() == 100, 20_000, "100 error lines");
        // a stack trace stays with its entry: entries start with lines that fit the format
        assertTrue(onEdt(() -> tab.entryFormat().startsEntry("21.10.26 10:00:13.000|I|Http|x")));
        assertTrue(!onEdt(() -> tab.entryFormat().startsEntry("    at com.acme.Pay.charge(Pay.java:12)")));

        // the log's bar shows the format; clicking it picks another for this file
        waitFor(() -> {
            try {
                return onEdt(() -> tab.logBar().formatButtonForTest().isShowing()
                        && tab.logBar().formatButtonForTest().getText().equals("Format: orders ▾"));
            } catch (Exception e) {
                return false;
            }
        }, 10_000, "format in the log's bar");
        onEdt(() -> {
            tab.logBar().formatButtonForTest().doClick();
            return null;
        });
        settle();
        shot("46b-format-from-log-bar");
        // choosing a format by hand is remembered for the file
        onEdt(() -> {
            for (java.awt.Component c : tab.logBar().formatMenuForTest().getComponents()) {
                if (c instanceof javax.swing.JMenuItem mi && mi.getText().equals(logsift.core.Formats.PLAIN)) {
                    mi.doClick();
                }
            }
            tab.logBar().formatMenuForTest().setVisible(false);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.logFormat() == null);
            } catch (Exception e) {
                return false;
            }
        }, 10_000, "plain text");
        assertEquals(logsift.core.Formats.PLAIN, tab.saveState().getProperty("state.format"));
        onEdt(() -> {
            tab.setFormatChoice(null);
            frame.setColumns(false);
            return null;
        });

        // Settings → General
        javax.swing.SwingUtilities.invokeLater(() -> frame.showSettings(null));
        waitFor(() -> SettingsDialog.lastForTest() != dlg && SettingsDialog.lastForTest() != null
                && SettingsDialog.lastForTest().isShowing(), 10_000, "Settings");
        SettingsDialog general = SettingsDialog.lastForTest();
        settle();
        shotOf(general.getRootPane(), "47-settings");
        onEdt(() -> {
            frame.setTabWidth(8);
            frame.setFont(null, 14f);
            general.dispose();
            return null;
        });
        assertEquals(8, LogView.tabWidth());
        assertEquals(8, AppState.prefs().getInt("tabWidth", 0));
        onEdt(() -> {
            frame.setTabWidth(4);
            frame.setFont(null, 13f);
            return null;
        });
    }

    @Test
    @Order(32)
    void treeOfLoggers() throws Exception {
        Path app = dir.resolve("shop.log");
        String[] loggers = {"com.acme.db.Pool", "com.acme.db.PoolMonitor", "com.acme.web.HttpServer",
            "com.acme.web.Session", "com.acme.orders.OrderService", "org.hibernate.SQL", "main"};
        int[] weight = {30, 3, 25, 8, 20, 12, 2};
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 5000; i++) {
            int r = rnd.nextInt(100);
            int k = 0;
            for (int acc = weight[0]; r >= acc; acc += weight[++k]) { }
            String lg = loggers[k];
            counts.merge(lg, 1, Integer::sum);
            sb.append(String.format("2026-10-21 10:%02d:%02d.%03d [worker-%d] %s %s - step %d%n", i / 60 % 60, i % 60,
                    i % 1000, i % 4, i % 50 == 0 ? "ERROR" : "INFO ", lg, i));
            if (i % 50 == 0) {
                sb.append("java.lang.IllegalStateException: boom\n\tat ").append(lg).append(".run(X.java:1)\n");
            }
        }
        Files.writeString(app, sb.toString().replace("\\n", "\n").replace("\\t", "\t"));
        onEdt(() -> {
            frame.setColumns(true);
            frame.openFile(app);
            return null;
        });
        LogTab tab = onEdt(frame::currentTab);
        ValuesTreePanel tree = tab.valuesTree();
        waitFor(() -> tree.isShowing() && tree.valuesForTest() != null && !tree.isCountingForTest(), 20_000,
                "the tree of loggers");
        logsift.core.ValueTree t = tree.valuesForTest();
        assertEquals(5000, t.root().total());
        assertEquals("com.acme", t.root().children().get(0).label(), "com › acme joined");
        assertEquals((long) counts.get("com.acme.db.Pool") + counts.get("com.acme.db.PoolMonitor"),
                t.find("com.acme.db").total());
        settle();
        shot("48-tree-of-loggers");

        // uncheck com.acme.db: its lines (and their stack traces) go away
        onEdt(() -> {
            tree.toggleForTest("com.acme.db");
            return null;
        });
        long dbLines = counts.get("com.acme.db.Pool") + counts.get("com.acme.db.PoolMonitor");
        waitForFilter(tab);
        assertTrue(tab.isMatchingOnly(), "hidden lines go away");
        long shown = onEdt(() -> tab.filterResultForTest().matchCount());
        assertEquals(5000 - dbLines, shownEntries(app, l -> !l.contains(" com.acme.db.")), "");
        assertEquals(shownLines(app, "com.acme.db."), shown, "lines not under com.acme.db (with their stack traces)");
        settle();
        shot("49-tree-hide-branch");

        // double-click (Show Only): just one logger
        onEdt(() -> {
            tree.showOnlyForTest("org.hibernate.SQL");
            return null;
        });
        waitForFilter(tab);
        long only = onEdt(() -> tab.filterResultForTest().matchCount());
        assertEquals(onlyLines(app, "org.hibernate.SQL"), only);

        // remembered for the file
        java.util.Properties st = onEdt(tab::saveState);
        assertEquals("logger", st.getProperty("state.tree.column"));
        assertTrue(st.getProperty("state.tree.hidden").contains("*com.acme"), st.getProperty("state.tree.hidden"));

        // Show all, and the panel can be closed (and comes back from the View menu)
        onEdt(() -> {
            tree.showAllValues();
            frame.setValuesTree(false);
            return null;
        });
        assertTrue(!onEdt(tree::isVisible));
        onEdt(() -> {
            frame.setValuesTree(true);
            frame.setColumns(false);
            tab.setMatchingOnly(false);
            return null;
        });
        assertTrue(onEdt(tree::isVisible));
    }

    private static void waitForFilter(LogTab tab) throws Exception {
        Thread.sleep(150);
        waitFor(() -> {
            try {
                return onEdt(() -> tab.filterResultForTest().isComplete() && !tab.filterEngineForTest().isRunning());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "filtering");
        settle();
    }

    /** Lines kept when entries (a line and its stack trace) under this logger prefix are hidden. */
    private static long shownLines(Path f, String hiddenPrefix) throws IOException {
        long n = 0;
        boolean hidden = false;
        for (String l : Files.readAllLines(f)) {
            boolean entry = l.startsWith("2026-");
            if (entry) {
                hidden = l.contains(" " + hiddenPrefix);
            }
            if (!hidden) {
                n++;
            }
        }
        return n;
    }

    /** Lines of entries of exactly this logger (with their stack traces). */
    private static long onlyLines(Path f, String logger) throws IOException {
        long n = 0;
        boolean in = false;
        for (String l : Files.readAllLines(f)) {
            if (l.startsWith("2026-")) {
                in = l.contains(" " + logger + " - ");
            }
            if (in) {
                n++;
            }
        }
        return n;
    }

    private static long shownEntries(Path f, java.util.function.Predicate<String> keep) throws IOException {
        return Files.readAllLines(f).stream().filter(l -> l.startsWith("2026-")).filter(keep).count();
    }

    /** The first button with this text inside a component. */
    private static javax.swing.AbstractButton button(java.awt.Container c, String text) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.AbstractButton b && text.equals(b.getText())) {
                return b;
            }
            if (k instanceof java.awt.Container sub) {
                javax.swing.AbstractButton b = button(sub, text);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    /** The time of the first line at or after {@code i} that has a timestamp. */
    private static long timeNear(List<Long> times, int i) {
        while (times.get(i) == Long.MIN_VALUE) {
            i++;
        }
        return times.get(i);
    }

    private static long naiveCount(InputStream in) throws IOException {
        long n = 0;
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("Payment failed")) {
                    n++;
                }
            }
        }
        return n;
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
        shotOf(frame.getRootPane(), name);
    }

    /** Screenshot of any window's content (dialogs too). */
    private void shotOf(javax.swing.JComponent root0, String name) throws Exception {
        BufferedImage img = onEdt(() -> {
            var root = root0;
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

    @Test
    @Order(33)
    void slowDriveIndexingKeepsWindowResponsive() throws Exception {
        // a log on a network share: every read takes a while
        Path f = dir.resolve("network-share.log");
        try (var w = Files.newBufferedWriter(f)) {
            for (int i = 0; i < 600_000; i++) {
                w.write(String.format("2026-10-21 10:%02d:%02d.%03d [worker-%d] %s com.acme.Service%d - request %d took %d ms%n",
                        (i / 6000) % 60, (i / 100) % 60, i % 1000, i % 8, i % 50 == 0 ? "ERROR" : i % 7 == 0 ? "WARN " : "INFO ",
                        i % 13, i, i % 997));
            }
        }
        logsift.core.FileSource.setTestNetwork(2, 8);
        java.util.List<String> stalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicLong worst = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean watching = new java.util.concurrent.atomic.AtomicBoolean(true);
        // watches the UI thread: how long a tiny task waits, and where the thread is when it is stuck
        Thread watchdog = new Thread(() -> {
            while (watching.get()) {
                java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
                long t0 = System.nanoTime();
                SwingUtilities.invokeLater(ran::countDown);
                try {
                    if (!ran.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        for (var e : Thread.getAllStackTraces().entrySet()) {
                            if (e.getKey().getName().startsWith("AWT-EventQueue")) {
                                StringBuilder sb = new StringBuilder();
                                for (StackTraceElement el : e.getValue()) {
                                    if (el.getClassName().startsWith("logsift")) {
                                        sb.append("\n    at ").append(el);
                                    }
                                }
                                stalls.add(sb.toString());
                            }
                        }
                        ran.await();
                    }
                    worst.accumulateAndGet(ms(t0), Math::max);
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-watchdog");
        try {
            watchdog.start();
            onEdt(() -> {
                frame.openFile(f);
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentTab() != null && frame.currentTab().path().equals(f.toAbsolutePath().normalize()));
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "share log opened");
            LogTab tab = onEdt(frame::currentTab);
            // move around while it is indexed (pages that are not read yet)
            java.util.Random rnd = new java.util.Random(1);
            long end = System.currentTimeMillis() + 6000;
            boolean shotTaken = false;
            while (System.currentTimeMillis() < end) {
                if (!shotTaken && System.currentTimeMillis() > end - 5000) {
                    onEdt(() -> {
                        int n = tab.mainView().rowCount();
                        tab.mainView().goToRow(n / 2, true);
                        return null;
                    });
                    Thread.sleep(500);
                    shot("49b-share-lines-loading"); // lines still being read: gray bars and a badge
                    shotTaken = true;
                }
                onEdt(() -> {
                    int n = tab.mainView().rowCount();
                    if (n > 0) {
                        tab.mainView().goToRow(rnd.nextInt(n), true);
                    }
                    return null;
                });
                Thread.sleep(150);
            }
            onEdt(() -> {
                tab.mainView().goToRow(0, false);
                return null;
            });
            // the lines on screen arrive and are painted
            waitFor(() -> {
                try {
                    return onEdt(() -> tab.mainView().textAtForTest(0) != null && !tab.mainView().textAtForTest(0).isEmpty());
                } catch (Exception e) {
                    return false;
                }
            }, 10_000, "first line painted");
            settle();
        } finally {
            watching.set(false);
            watchdog.join(5000);
            logsift.core.FileSource.setTestNetwork(0, 0);
        }
        System.out.println("slow drive: the window waited at most " + worst.get() + " ms");
        assertTrue(stalls.isEmpty() && worst.get() < 400, "the window froze for " + worst.get() + " ms:"
                + String.join("\n  --", stalls.stream().distinct().limit(4).toList()));
    }

    @Test
    @Order(34)
    void menusWithIcons() throws Exception {
        javax.swing.JMenu view = onEdt(() -> {
            javax.swing.JMenuBar bar = frame.getJMenuBar();
            for (int i = 0; i < bar.getMenuCount(); i++) {
                if (bar.getMenu(i).getText().equals("View")) {
                    return bar.getMenu(i);
                }
            }
            return null;
        });
        assertNotNull(view);
        onEdt(() -> {
            view.doClick();
            return null;
        });
        settle();
        // on/off items show their state; most items have an icon
        int checks = onEdt(() -> {
            int n = 0;
            for (java.awt.Component c : view.getMenuComponents()) {
                if (c instanceof javax.swing.JCheckBoxMenuItem cb && cb.getText().equals("Timeline")) {
                    assertEquals(AppState.prefs().getBoolean("timeline", true), cb.isSelected());
                }
                if (c instanceof javax.swing.JCheckBoxMenuItem) {
                    n++;
                }
            }
            return n;
        });
        assertTrue(checks >= 8, "on/off items: " + checks);
        shotOf(view.getPopupMenu(), "50-view-menu");
        onEdt(() -> {
            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
            return null;
        });
        // the share log was read with its layout once the drive was fast again
        LogTab cur = onEdt(frame::currentTab);
        if (cur != null && cur.path().getFileName().toString().equals("network-share.log")) {
            waitFor(() -> {
                try {
                    return onEdt(() -> cur.logFormat() != null && cur.timeIndex() != null);
                } catch (Exception e) {
                    return false;
                }
            }, 20_000, "layout and timeline of the share log");
        }
        settle();
        shot("51-toolbar");
    }

    @Test
    @Order(35)
    void insightsAtAGlance() throws Exception {
        Path f = dir.resolve("checkout-service.log");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        try (var w = Files.newBufferedWriter(f)) {
            java.time.LocalDateTime t = java.time.LocalDateTime.of(2026, 10, 21, 10, 0, 0);
            int i = 0;
            w.write(fmt.format(t) + " INFO  [main] com.acme.CheckoutApp - Started CheckoutApp in 2.31 seconds\n");
            while (t.isBefore(java.time.LocalDateTime.of(2026, 10, 21, 12, 0))) {
                t = t.plusSeconds(1);
                i++;
                int hm = t.getHour() * 100 + t.getMinute();
                if (hm >= 1120 && hm < 1130) {
                    continue; // the service hung
                }
                String ts = fmt.format(t);
                if (hm == 1130 && t.getSecond() == 0) {
                    w.write(ts + " INFO  [main] com.acme.CheckoutApp - Started CheckoutApp in 2.47 seconds\n");
                }
                w.write(ts + " INFO  [http-" + (i % 8) + "] com.acme.web.Requests - GET /cart/" + (i % 977) + " 200 in " + (i % 90) + " ms\n");
                if (i % 300 == 0) {
                    w.write(ts + " ERROR [http-" + (i % 8) + "] com.acme.pay.Payments - Payment declined for order " + i + "\n");
                }
                if (i % 120 == 0) {
                    w.write(ts + " WARN  [pool-1] com.acme.inventory.Client - Slow response from inventory: " + (900 + i % 300) + " ms\n");
                }
                if (hm >= 1042 && hm < 1045 && i % 2 == 0) {
                    w.write(ts + " ERROR [db-" + (i % 4) + "] com.acme.db.Pool - Database connection lost after " + (20000 + i) + " ms\n");
                }
                if (hm >= 1100 && i % 60 == 0) {
                    w.write(ts + " ERROR [http-" + (i % 8) + "] com.acme.cache.Redis - Cache unreachable at cache-" + (i % 3) + ".internal:6379\n");
                }
            }
        }
        onEdt(() -> {
            frame.openFile(f);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null && frame.currentTab().path().equals(f.toAbsolutePath().normalize())
                        && frame.currentTab().insightsPanel().report() != null);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "insights of the log");
        LogTab tab = onEdt(frame::currentTab);
        logsift.core.Insights.Report r = onEdt(() -> tab.insightsPanel().report());
        String all = r.findings().stream().map(x -> x.kind() + " " + x.title() + " | " + x.detail())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(logsift.core.Insights.Kind.BURST, r.findings().get(0).kind(), all);
        assertTrue(all.contains("NEW_ERROR Cache unreachable at …"), all);
        assertTrue(all.contains("SILENCE No lines for 10 min"), all);
        assertTrue(all.contains("RESTART Started 2 times"), all);
        // the tab says how many problems there are, without opening it
        String title = onEdt(() -> {
            JTabbedPane b = (JTabbedPane) tab.insightsPanel().getParent();
            return b.getTitleAt(b.indexOfComponent(tab.insightsPanel()));
        });
        assertEquals("Insights (" + r.problems() + ")", title);
        onEdt(() -> {
            JTabbedPane b = (JTabbedPane) tab.insightsPanel().getParent();
            b.setSelectedComponent(tab.insightsPanel());
            frame.setColumns(true);
            return null;
        });
        settle();
        shot("52-insights");
        // "Show these minutes" on the burst shows just that time
        onEdt(() -> {
            button(tab.insightsPanel(), "Show these minutes").doClick();
            return null;
        });
        settle();
        assertNotNull(onEdt(tab::timeRange), "time range selected");
        shot("53-insights-burst");
        onEdt(() -> {
            tab.clearTimeRange();
            tab.setMatchingOnly(false);
            return null;
        });
    }

    @Test
    @Order(36)
    void columnsSelectionHiddenColumnsAndDetails() throws Exception {
        Path f = dir.resolve("api-gateway.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            String ts = String.format("2026-10-21 10:%02d:%02d.%03d", i / 60, i % 60, i);
            if (i == 200) {
                sb.append(ts).append(" ERROR [http-3] com.acme.api.Gateway - Upstream call failed\n")
                        .append("java.lang.IllegalStateException: upstream returned 502\n")
                        .append("\tat com.acme.api.Gateway.call(Gateway.java:88)\n")
                        .append("\tat com.acme.api.Router.route(Router.java:41)\n")
                        .append("Caused by: java.net.SocketTimeoutException: Read timed out\n")
                        .append("\t... 2 more\n");
            }
            sb.append(ts).append(" INFO  [http-").append(i % 8).append("] com.acme.api.Gateway - response ")
                    .append("{\"id\":").append(i).append(",\"payload\":\"{\\\"user\\\":\\\"u").append(i % 9)
                    .append("\\\"}\",\"items\":[1,2]}\n");
        }
        Files.writeString(f, sb);
        onEdt(() -> {
            frame.openFile(f);
            frame.setColumns(true);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null && frame.currentTab().path().equals(f.toAbsolutePath().normalize())
                        && frame.currentTab().logFormat() != null && frame.currentTab().mainView().columns() != null);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "columns of the gateway log");
        LogTab tab = onEdt(frame::currentTab);
        LogView v = tab.mainView();

        // hide a column: the header says what is hidden; Show All brings it back
        onEdt(() -> {
            tab.setColumnHidden("thread", true);
            return null;
        });
        assertEquals(List.of("thread"), onEdt(() -> v.columns().hidden()));
        assertTrue(onEdt(() -> java.util.Arrays.stream(v.columns().visible()).noneMatch(c -> v.columns().format().columns().get(c).equals("thread"))));

        // select characters inside the message, in the columns view
        String line0 = onEdt(() -> tab.document().line(0));
        int from = line0.indexOf("response");
        onEdt(() -> {
            v.goToRow(0, false);
            return null;
        });
        settle();
        onEdt(() -> {
            JComponent c = v.canvasForTest();
            int y = v.yForRowForTest(0);
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK, v.xForCharForTest(0, from), y, 1, false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK, v.xForCharForTest(0, from + 8), y, 1, false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, v.xForCharForTest(0, from + 8), y, 1, false, MouseEvent.BUTTON1));
            tab.showDetailsTab();
            return null;
        });
        assertEquals("response", onEdt(v::selectedText));
        waitFor(() -> details(tab).equals("response"), 10_000, "the selection in Details");

        // select the JSON: Details shows it pretty-printed, the JSON string inside opened up
        int json = line0.indexOf('{');
        onEdt(() -> {
            JComponent c = v.canvasForTest();
            int y = v.yForRowForTest(0);
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK, v.xForCharForTest(0, json), y, 1, false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, v.xForCharForTest(0, line0.length()), y, 1,
                    false, MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, v.xForCharForTest(0, line0.length()), y, 1, false, MouseEvent.BUTTON1));
            return null;
        });
        assertEquals(line0.substring(json), onEdt(v::selectedText));
        waitFor(() -> details(tab).contains("\"user\": \"u0\""), 10_000, "pretty JSON in Details");
        assertEquals(DetailsPanel.View.JSON, onEdt(() -> tab.detailsPanel().viewForTest()));
        settle();
        shot("54-columns-selection-json-details");
        // back to the text as it is
        onEdt(() -> {
            tab.detailsPanel().chooseForTest(DetailsPanel.View.TEXT);
            return null;
        });
        assertEquals(line0.substring(json), details(tab));
        onEdt(() -> {
            tab.detailsPanel().chooseForTest(null);
            return null;
        });

        // the caret on a stack trace line: Details shows the whole entry
        onEdt(() -> {
            v.goToRow(203, false);
            return null;
        });
        waitFor(() -> details(tab).startsWith("2026-10-21 10:03:20.200 ERROR [http-3]"), 10_000, "the entry in Details");
        assertTrue(details(tab).endsWith("\t... 2 more"), details(tab));
        settle();
        shot("55-details-whole-stack-trace");

        onEdt(() -> {
            tab.setColumnHidden("thread", false);
            return null;
        });
        assertTrue(onEdt(() -> v.columns().hidden().isEmpty()));
    }

    @Test
    @Order(37)
    void threadDumpsExplained() throws Exception {
        // an application's console log with three kill -3 dumps in it
        String dumps;
        try (InputStream in = VisualTest.class.getResourceAsStream("/threaddumps/jstack-17.txt")) {
            dumps = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path f = dir.resolve("orders-service-console.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append(String.format("2026-10-22 18:5%d:%02d.%03d INFO  [http-nio-8080-exec-%d] c.a.orders.Api - GET /orders/%d%n",
                    i / 60, i % 60, i, i % 5 + 1, 1000 + i));
        }
        sb.append(dumps);
        sb.append("2026-10-22 18:59:59.000 WARN  [main] c.a.orders.App - shutting down\n");
        Files.writeString(f, sb);
        onEdt(() -> {
            frame.setColumns(false);
            frame.openFile(f);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null
                        && frame.currentTab().path().equals(f.toAbsolutePath().normalize())
                        && frame.currentTab().dumpsBannerShownForTest());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the offer to analyze the thread dumps");
        LogTab log = onEdt(frame::currentTab);
        waitFor(() -> !log.document().isIndexing(), 20_000, "indexing");
        settle();
        shot("56-log-with-thread-dumps");

        // a log being followed when a dump is written to it (kill -3 on the running program)
        Path growing = dir.resolve("billing-console.log");
        Files.writeString(growing, "2026-10-22 18:50:00.000 INFO  [main] billing - started\n");
        onEdt(() -> {
            frame.openFile(growing);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null
                        && frame.currentTab().path().equals(growing.toAbsolutePath().normalize())
                        && !frame.currentTab().document().isIndexing());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the growing log");
        LogTab billing = onEdt(frame::currentTab);
        Thread.sleep(300);
        assertTrue(!onEdt(billing::dumpsBannerShownForTest), "no dump yet");
        Files.writeString(growing, "2026-10-22 18:50:01.000 INFO  [main] billing - working\n",
                java.nio.file.StandardOpenOption.APPEND);
        waitFor(() -> billing.document().lineCount() >= 2, 20_000, "a new line");
        Thread.sleep(300);
        Files.writeString(growing, dumps.substring(0, dumps.indexOf("JNI global")),
                java.nio.file.StandardOpenOption.APPEND);
        waitFor(() -> {
            try {
                return onEdt(billing::dumpsBannerShownForTest);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the offer once a dump was written");
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(billing));
            frame.tabsForTest().setSelectedComponent(log);
            return null;
        });
        onEdt(() -> {
            frame.analyzeThreads(log);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentThreadsTab() != null);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the Threads tab");
        ThreadsTab tt = onEdt(frame::currentThreadsTab);
        assertEquals(3, onEdt(() -> tt.dumps().size()));
        assertEquals(2, onEdt(tt::current));
        logsift.jvm.ThreadAnalysis.Report r = onEdt(tt::report);
        assertEquals(logsift.jvm.ThreadAnalysis.Kind.DEADLOCK, r.findings().get(0).kind());
        assertTrue(r.findings().stream().anyMatch(x -> x.kind() == logsift.jvm.ThreadAnalysis.Kind.CONTENTION));
        assertTrue(onEdt(() -> frame.getTitle().startsWith("Threads: orders-service-console.log")));
        // grouped by pool: the five http threads are one row
        assertTrue(onEdt(() -> {
            javax.swing.JTable t = tt.tableForTest();
            for (int i = 0; i < t.getRowCount(); i++) {
                if (String.valueOf(t.getValueAt(i, 1)).equals("5 blocked")) {
                    return true;
                }
            }
            return false;
        }), "a group row for the http pool");
        settle();
        shot("57-threads-findings");

        // Show the threads of the lock queue: the holder first, its stack says who waits
        logsift.jvm.ThreadAnalysis.Finding queue = r.findings().stream()
                .filter(x -> x.kind() == logsift.jvm.ThreadAnalysis.Kind.CONTENTION).findFirst().orElseThrow();
        onEdt(() -> {
            tt.showFinding(queue);
            return null;
        });
        assertEquals(6, onEdt(() -> tt.tableForTest().getRowCount()));
        assertEquals("db-writer", onEdt(() -> tt.stackForTest().thread().name()));
        String html = onEdt(() -> tt.stackForTest().getText());
        assertTrue(html.contains("5 threads wait"), "the holder's stack names who waits");
        settle();
        shot("58-threads-lock-queue");

        // a link in the stack goes to that thread
        String waiter = queue.keys().get(1);
        onEdt(() -> {
            tt.selectKey(waiter);
            return null;
        });
        assertEquals(waiter, onEdt(() -> tt.stackForTest().thread().key()));
        assertTrue(onEdt(() -> tt.stackForTest().getText()).contains("held by"));

        // over time: the stuck threads are one bar
        onEdt(() -> {
            tt.viewsForTest().setSelectedIndex(1);
            return null;
        });
        assertTrue(onEdt(() -> tt.timelineForTest().rowsForTest()) >= 8, "busy threads in the timeline");
        onEdt(() -> {
            tt.timelineForTest().pick(waiter, 0);
            return null;
        });
        assertEquals(0, onEdt(tt::current), "a cell shows that dump");
        settle();
        shot("59-threads-timeline");

        // what changed between the first and last dump
        onEdt(() -> {
            tt.viewsForTest().setSelectedIndex(2);
            return null;
        });
        // (this program does the same thing in every dump: nothing changed)
        assertTrue(onEdt(tt::changesForTest).isEmpty());
        assertTrue(onEdt(tt::compareSummaryForTest).endsWith("nothing changed"), onEdt(tt::compareSummaryForTest));
        onEdt(() -> {
            tt.showUnchangedForTest(true);
            return null;
        });
        List<logsift.jvm.ThreadAnalysis.Change> ch = onEdt(tt::changesForTest);
        assertTrue(ch.size() > 10, "threads compared: " + ch.size());
        settle();
        shot("60-threads-compare");

        // saved, they open again with the same threads
        Path saved = dir.resolve("saved-threads.txt");
        onEdt(() -> {
            tt.save(saved);
            frame.openThreadDump(saved);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentThreadsTab() != null && frame.currentThreadsTab() != tt);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the saved dumps in a new tab");
        ThreadsTab again = onEdt(frame::currentThreadsTab);
        assertEquals(3, onEdt(() -> again.dumps().size()));
        for (int i = 0; i < 3; i++) {
            int k = i;
            assertEquals(onEdt(() -> tt.dumps().get(k).threads()), onEdt(() -> again.dumps().get(k).threads()));
        }
        onEdt(() -> {
            frame.closeCurrentTabForTest();
            return null;
        });
    }

    @Test
    @Order(38)
    void attachToRunningJvmsAndProfile() throws Exception {
        Process app = logsift.jvm.FixtureApp.start(true);
        var server = org.apache.sshd.server.SshServer.setUpDefaultServer();
        try {
            // ---- a JVM on this computer
            JvmDialog dlg = onEdt(frame::openJvmDialogForTest);
            waitFor(() -> {
                try {
                    return onEdt(dlg::localCountForTest) > 0;
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "the JVMs on this computer");
            onEdt(() -> {
                dlg.selectForTest(app.pid());
                return null;
            });
            settle();
            shotOf(dlg.getRootPane(), "61-attach-dialog");
            onEdt(() -> {
                dlg.attachSelected();
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentThreadsTab() != null && frame.currentThreadsTab().isLive());
                } catch (Exception e) {
                    return false;
                }
            }, 60_000, "a live Threads tab");
            ThreadsTab live = onEdt(frame::currentThreadsTab);
            assertEquals(1, onEdt(() -> live.dumps().size()));
            onEdt(() -> {
                live.takeDumps(2, 1000);
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> live.dumps().size() == 3 && !live.isRecording());
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "two more dumps");
            logsift.jvm.ThreadAnalysis.Report r = onEdt(live::report);
            assertEquals(logsift.jvm.ThreadAnalysis.Kind.DEADLOCK, r.findings().get(0).kind());
            assertTrue(r.findings().stream().anyMatch(f -> f.kind() == logsift.jvm.ThreadAnalysis.Kind.HOT
                    && f.title().startsWith("hot-loop")), "CPU between the dumps: " + r.findings());
            // waiting for input, or taking this very dump, isn't being stuck
            assertTrue(r.findings().stream().noneMatch(f -> f.kind() == logsift.jvm.ThreadAnalysis.Kind.STUCK),
                    "nothing stuck: " + r.findings());
            settle();
            shot("62-threads-live-jvm");

            // ---- profile it: the spinning thread's method is most of the CPU
            onEdt(() -> {
                live.profileButtonForTest().doClick();
                return null;
            });
            ProfileTab prof = onEdt(frame::currentProfileTab);
            assertNotNull(prof);
            waitFor(() -> {
                try {
                    return onEdt(prof::choicesLoadedForTest);
                } catch (Exception e) {
                    return false;
                }
            }, 60_000, "what can be measured");
            assertTrue(onEdt(prof::modesForTest).containsAll(List.of(logsift.jvm.Profiler.Mode.CPU,
                    logsift.jvm.Profiler.Mode.ALLOC, logsift.jvm.Profiler.Mode.LOCK)));
            settle();
            logsift.jvm.Profile p = profileRun(prof, logsift.jvm.Profiler.Mode.CPU, 3);
            long spin = p.find("logsift.jvm.FixtureApp.lambda$main$8").stream()
                    .mapToLong(logsift.jvm.Profile.Node::total).sum();
            // (Flight Recorder, used where async-profiler doesn't run, may miss the loop: it is mostly native code)
            boolean asyncProfiler = onEdt(prof::modesForTest).contains(logsift.jvm.Profiler.Mode.LIVE);
            assertTrue(spin > 0 || !asyncProfiler, "the hot loop: " + p.hottest(5));
            assertTrue(p.total() > 0);
            assertTrue(onEdt(() -> prof.methodsForTest().getRowCount()) > 0);
            settle();
            shot("63-profile-flame-graph");
            onEdt(() -> {
                prof.methodsForTest().getSelectionModel().setSelectionInterval(0, 0);
                return null;
            });
            assertTrue(onEdt(() -> prof.graphForTest().matchedShare()) > 0, "the method is highlighted");
            // zoom into a frame: the hot loop's, or the biggest
            onEdt(() -> {
                List<logsift.jvm.Profile.Node> hot = p.find("logsift.jvm.FixtureApp.lambda$main$8");
                prof.graphForTest().zoomTo(hot.isEmpty() ? p.root().children().get(0) : hot.get(0));
                return null;
            });
            settle();
            shot("64-profile-zoomed");
            Path saved = dir.resolve("saved-profile.collapsed.txt");
            onEdt(() -> {
                prof.save(saved);
                return null;
            });
            assertEquals(p.total(), logsift.jvm.Profiler.open(saved).get(0).total());

            // who calls the hot loop; then only the stacks through it
            if (spin > 0) {
            onEdt(() -> {
                prof.focusForTest("callers", "logsift.jvm.FixtureApp.lambda$main$8");
                return null;
            });
            logsift.jvm.Profile callers = onEdt(prof::profile);
            assertEquals("logsift.jvm.FixtureApp.lambda$main$8", callers.root().children().get(0).name());
            assertEquals(spin, callers.total());
            onEdt(() -> {
                prof.focusForTest("only", "logsift.jvm.FixtureApp.lambda$main$8");
                return null;
            });
            assertEquals(spin, onEdt(prof::profile).total());
            onEdt(() -> {
                prof.focusForTest(null, null);
                return null;
            });
            }

            // a second run, compared with the first: red grew, blue shrank
            profileRun(prof, logsift.jvm.Profiler.Mode.CPU, 2);
            assertEquals(2, onEdt(prof::runCountForTest));
            onEdt(() -> {
                prof.compareForTest(1);
                return null;
            });
            assertNotNull(onEdt(() -> prof.graphForTest().baseline()));
            assertEquals(4, onEdt(() -> prof.methodsForTest().getColumnCount()), "a Change column");
            settle();
            shot("65-profile-compared");

            // memory: live objects (where the heap grows), with async-profiler where it runs
            if (onEdt(prof::modesForTest).contains(logsift.jvm.Profiler.Mode.LIVE)) {
                logsift.jvm.Profile liveObjects = profileRun(prof, logsift.jvm.Profiler.Mode.LIVE, 3);
                assertTrue(liveObjects.find("byte[]").size() > 0, "the leaked arrays: " + liveObjects.hottest(5));
                settle();
                shot("66-profile-live-objects");
                // everything at once: one run, several views
                profileRun(prof, logsift.jvm.Profiler.Mode.ALL, 3);
                assertTrue(onEdt(prof::viewCountForTest) > 2, "views of one run");
            }

            // ---- the heap: what fills it, and what grew
            onEdt(() -> {
                frame.tabsForTest().setSelectedComponent(live);
                live.heapButtonForTest().doClick();
                return null;
            });
            HeapTab heap = onEdt(frame::currentHeapTab);
            assertNotNull(heap);
            for (int i = 0; i < 2; i++) {
                int want = i + 1;
                onEdt(() -> {
                    heap.setLiveForTest(true);
                    heap.takeHistogram();
                    return null;
                });
                waitFor(() -> {
                    try {
                        return onEdt(() -> heap.snapshotCountForTest() == want && !heap.isBusy());
                    } catch (Exception e) {
                        return false;
                    }
                }, 60_000, "histogram " + want);
                Thread.sleep(1200);
            }
            assertEquals(6, onEdt(() -> heap.tableForTest().getColumnCount()), "the change columns");
            assertTrue(onEdt(heap::statusText).contains("since #1"), onEdt(heap::statusText));
            assertTrue(onEdt(heap::statusText).contains("grew most: byte[]"), "the leak: " + onEdt(heap::statusText));
            settle();
            shot("67-heap-histogram");

            // ---- the same JVM over SSH
            Path bin = Files.createDirectories(dir.resolve("ssh-bin"));
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
                    dir.resolve("jvm-hostkey.ser")));
            server.setPasswordAuthenticator((u, pw, sess) -> u.equals("ops") && pw.equals("secret"));
            server.setSubsystemFactories(List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory()));
            server.setCommandFactory((channel, command) -> new org.apache.sshd.server.shell.ProcessShellFactory(
                    command, "/bin/sh", "-c", "PATH=" + bin + ":/usr/bin:/bin; export PATH; " + command)
                    .createShell(channel));
            if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                server.start();
                System.setProperty("logsift.noKnownHosts", "true");
                JvmDialog ssh = onEdt(() -> {
                    JvmDialog d = frame.openJvmDialogForTest();
                    d.setSshForTest("127.0.0.1", server.getPort(), "ops", "secret");
                    d.setRemoteJdkForTest(System.getProperty("java.home"));
                    d.connectSsh();
                    return d;
                });
                waitFor(() -> {
                    try {
                        return onEdt(ssh::remoteCountForTest) > 0;
                    } catch (Exception e) {
                        return false;
                    }
                }, 30_000, "the JVMs on the server");
                onEdt(() -> {
                    ssh.selectForTest(app.pid());
                    return null;
                });
                settle();
                shotOf(ssh.getRootPane(), "65-attach-dialog-ssh");
                onEdt(() -> {
                    ssh.attachSelected();
                    return null;
                });
                waitFor(() -> {
                    try {
                        return onEdt(() -> frame.currentThreadsTab() != null && frame.currentThreadsTab() != live
                                && frame.currentThreadsTab().isLive());
                    } catch (Exception e) {
                        return false;
                    }
                }, 60_000, "a Threads tab for the JVM on the server");
                ThreadsTab remote = onEdt(frame::currentThreadsTab);
                assertTrue(onEdt(remote::title).contains("on 127.0.0.1"), onEdt(remote::title));
                assertEquals(logsift.jvm.ThreadAnalysis.Kind.DEADLOCK,
                        onEdt(remote::report).findings().get(0).kind());
                settle();
                shot("66-threads-over-ssh");
                onEdt(() -> {
                    frame.closeCurrentTabForTest();
                    return null;
                });
            }
            // closing the tabs lets go of the JVM
            onEdt(() -> {
                while (frame.currentProfileTab() != null || frame.currentThreadsTab() != null) {
                    frame.closeCurrentTabForTest();
                }
                return null;
            });
        } finally {
            if (server.isStarted()) {
                server.stop(true);
            }
            logsift.jvm.FixtureApp.stop(app);
        }
    }

    @Test
    @Order(39)
    void aLogKnowsItsJvm() throws Exception {
        System.clearProperty("logsift.noJvmBar");
        try {
            aLogKnowsItsJvmWithBar();
        } finally {
            System.setProperty("logsift.noJvmBar", "true");
        }
    }

    private void aLogKnowsItsJvmWithBar() throws Exception {
        // a running program that writes its log here, with a JMX port too
        Path log = dir.resolve("fixture-app.log");
        Files.deleteIfExists(log);
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Process app = logsift.jvm.FixtureApp.start(false, log, "-Dcom.sun.management.jmxremote.port=" + port,
                "-Dcom.sun.management.jmxremote.rmi.port=" + port, "-Djava.rmi.server.hostname=127.0.0.1",
                "-Dcom.sun.management.jmxremote.authenticate=false", "-Dcom.sun.management.jmxremote.ssl=false");
        try {
            waitFor(() -> {
                try {
                    return Files.readAllLines(log).size() > 5;
                } catch (IOException e) {
                    return false;
                }
            }, 20_000, "the program's log");
            onEdt(() -> {
                frame.setColumns(true);
                frame.openFile(log);
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentTab() != null
                            && frame.currentTab().path().equals(log.toAbsolutePath().normalize())
                            && frame.jvmBarForTest(frame.currentTab()) != null
                            && frame.jvmBarForTest(frame.currentTab()).state() == JvmBar.State.FOUND);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "the bar naming the log's JVM");
            LogTab tab = onEdt(frame::currentTab);
            JvmBar bar = onEdt(() -> frame.jvmBarForTest(tab));
            String text = onEdt(bar::textForTest);
            assertTrue(text.contains("FixtureApp") && text.contains("(" + app.pid() + ")"), text);
            // Windows can't say who has a file open: there the log's own process id finds it
            assertTrue(text.contains(isWindows() ? "process id is in the log" : "has this log open"), text);
            settle();
            shot("67-log-knows-its-jvm");

            // ---- a log line's thread, in a dump of the JVM (Spring Boot cut its name short)
            assertEquals("Spring Boot (time level pid --- [thread] logger : message)",
                    onEdt(() -> tab.logFormat().name()));
            onEdt(() -> {
                frame.showThread(tab, "io-8080-exec-10");
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentThreadsTab() != null && frame.currentThreadsTab().isLive()
                            && "http-nio-8080-exec-10".equals(frame.currentThreadsTab().selectedThreadNameForTest()));
                } catch (Exception e) {
                    return false;
                }
            }, 60_000, "the line's thread in a dump");
            ThreadsTab threads = onEdt(frame::currentThreadsTab);
            assertEquals(tab.title(), onEdt(threads::logName));
            assertEquals(JvmBar.State.CONNECTED, onEdt(bar::state));
            settle();
            shot("68-log-thread-in-dump");

            // ---- and back: the thread's lines in the log
            onEdt(() -> {
                assertTrue(threads.selectThreadNamed("scheduler-1"));
                threads.inLogButtonForTest().doClick();
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentTab() == tab && tab.filterResultForTest().isComplete()
                            && !tab.filterEngineForTest().isRunning() && tab.filterResultForTest().matchCount() > 0);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "the thread's lines");
            long shown = onEdt(() -> tab.filterResultForTest().matchCount());
            long all = tab.document().lineCount();
            assertTrue(shown > 2 && shown < all, shown + " of " + all);
            settle();
            shot("69-thread-lines-in-log");

            // ---- the link is remembered by program: a rotated log in the same folder finds it again
            Path rotated = dir.resolve("fixture-app.log.1");
            Files.writeString(rotated, "2026-10-21 10:00:00.000 INFO  [main] app - an older log, no process id\n");
            onEdt(() -> {
                frame.openFile(rotated);
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentTab() != null
                            && frame.currentTab().path().equals(rotated.toAbsolutePath().normalize())
                            && frame.jvmBarForTest(frame.currentTab()) != null
                            && frame.jvmBarForTest(frame.currentTab()).state() == JvmBar.State.FOUND);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "the remembered JVM");
            assertTrue(onEdt(() -> frame.jvmBarForTest(frame.currentTab()).textForTest()).contains("linked before"));
            onEdt(() -> {
                frame.closeCurrentTabForTest();
                return null;
            });

            // ---- a log copied from a server: it says where it came from
            Path copied = dir.resolve("orders-from-prod.log");
            Files.writeString(copied, "2026-10-22T10:00:00.000+02:00  INFO 4242 --- [           main] c.a.o.OrdersApplication"
                    + "                  : Starting OrdersApplication v1.4.2 using Java 21.0.4 on prod-7 with PID 4242"
                    + " (/opt/orders/orders-1.4.2.jar started by ops in /opt/orders)\n");
            onEdt(() -> {
                frame.openFile(copied);
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentTab() != null
                            && frame.currentTab().path().equals(copied.toAbsolutePath().normalize())
                            && frame.jvmBarForTest(frame.currentTab()) != null
                            && frame.jvmBarForTest(frame.currentTab()).state() == JvmBar.State.SUGGESTED);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "a suggestion");
            JvmBar sg = onEdt(() -> frame.jvmBarForTest(frame.currentTab()));
            assertTrue(onEdt(sg::textForTest).contains("orders-1.4.2.jar on prod-7 (PID 4242)"), onEdt(sg::textForTest));
            settle();
            shot("70-log-from-a-server");
            onEdt(() -> {
                sg.linkButtonForTest().doClick();
                return null;
            });
            JvmDialog link = onEdt(frame::linkDialogForTest);
            assertNotNull(link);
            assertEquals(1, onEdt(link::whereForTest), "the SSH tab");
            assertEquals("prod-7", onEdt(link::sshHostForTest));
            settle();
            shotOf(link.getRootPane(), "71-link-dialog");

            // ---- through the JMX port: dumps, but heap dumps stay on that machine
            onEdt(() -> {
                link.setJmxForTest("127.0.0.1", port);
                return null;
            });
            settle();
            shotOf(link.getRootPane(), "72-attach-jmx");
            onEdt(() -> {
                link.attachSelected();
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentThreadsTab() != null && frame.currentThreadsTab() != threads
                            && frame.currentThreadsTab().isLive());
                } catch (Exception e) {
                    return false;
                }
            }, 60_000, "a Threads tab over JMX");
            ThreadsTab jmx = onEdt(frame::currentThreadsTab);
            assertTrue(onEdt(jmx::title).contains("127.0.0.1:" + port), onEdt(jmx::title));
            assertEquals(logsift.jvm.ThreadAnalysis.Kind.DEADLOCK, onEdt(jmx::report).findings().get(0).kind());
            assertEquals(JvmBar.State.CONNECTED, onEdt(sg::state), "the copied log is now linked to it");
            onEdt(() -> {
                jmx.heapButtonForTest().doClick();
                return null;
            });
            HeapTab heap = onEdt(frame::currentHeapTab);
            assertNotNull(heap);
            assertTrue(onEdt(heap::canHeapDumpForTest), "over JMX too (written on the JVM's machine, copied over SSH)");
            onEdt(() -> {
                while (frame.currentHeapTab() != null || frame.currentThreadsTab() != null) {
                    frame.closeCurrentTabForTest();
                }
                return null;
            });
        } finally {
            logsift.jvm.FixtureApp.stop(app);
        }
    }

    @Test
    @Order(40)
    void zipsGzipsUrlsAndCsv() throws Exception {
        Path box = dir.resolve("archives");
        Files.createDirectories(box);
        StringBuilder csv = new StringBuilder("Timestamp,Level,Host,User,Message\n");
        for (int i = 0; i < 400; i++) {
            csv.append(String.format("2026-10-21 10:%02d:%02d,%s,web-%d,u%d,\"%s\"\n", i / 60, i % 60,
                    i % 25 == 0 ? "ERROR" : i % 7 == 0 ? "WARN" : "INFO", i % 3 + 1, i % 40,
                    i % 25 == 0 ? "Payment failed, card declined" : "Request " + i + " took " + (i * 7 % 300) + " ms"));
        }
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            log.append(String.format("2026-10-21 09:%02d:%02d.000 %s [main] c.a.App - step %d\n", i / 60, i % 60,
                    i % 50 == 0 ? "ERROR" : "INFO ", i));
        }
        java.io.ByteArrayOutputStream inner = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(inner)) {
            z.putNextEntry(new java.util.zip.ZipEntry("exports/orders.csv"));
            z.write(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path zip = box.resolve("support-bundle.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("logs/app.log"));
            z.write(log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.putNextEntry(new java.util.zip.ZipEntry("logs/old.log.gz"));
            java.io.ByteArrayOutputStream g = new java.io.ByteArrayOutputStream();
            try (var gz = new java.util.zip.GZIPOutputStream(g)) {
                gz.write(log.toString().replace("step", "old step").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            z.write(g.toByteArray());
            z.putNextEntry(new java.util.zip.ZipEntry("data.zip"));
            z.write(inner.toByteArray());
        }

        // a dropped zip opens as a tab: its folders, files and the zip inside it as a tree
        onEdt(() -> {
            frame.setColumns(true);
            frame.openFiles(List.of(zip)); // as when dropped on the window
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentArchiveTabForTest() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the zip's tab");
        ArchiveTab at = onEdt(frame::currentArchiveTabForTest);
        assertEquals(List.of("data.zip", "  exports", "    orders.csv", "logs", "  app.log", "  old.log.gz"),
                onEdt(at::rowsForTest), "folders and the zip inside, open");
        assertEquals("3 files, 41.49 KB unpacked", onEdt(at::summaryForTest));
        assertEquals(zip.toAbsolutePath().normalize(), AppState.recentFiles().get(0), "the zip is a recent file");
        onEdt(() -> {
            at.selectForTest("logs/app.log", "logs/old.log.gz");
            return null;
        });
        settle();
        shot("73-zip-contents");
        onEdt(() -> {
            at.setFilterForTest("csv");
            return null;
        });
        assertEquals(List.of("data.zip!/exports/orders.csv"), onEdt(at::shownForTest), "filtered");
        assertEquals(List.of("data.zip", "  exports", "    orders.csv"), onEdt(at::rowsForTest));
        onEdt(() -> {
            at.selectForTest("data.zip!/exports/orders.csv");
            return null;
        });
        settle();
        shot("73b-zip-filtered");
        onEdt(() -> {
            at.openSelected();
            return null;
        });
        LogTab csvTab = waitForTab("orders.csv");
        Path csvPath = zip.toAbsolutePath().normalize().resolve("data.zip").resolve("exports").resolve("orders.csv");
        assertEquals(csvPath, onEdt(csvTab::path), "named by its place in the zip");
        assertEquals(csvPath, AppState.recentFiles().get(0), "a recent file by that name, not the copy");
        waitFor(() -> {
            try {
                return onEdt(() -> csvTab.logFormat() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the CSV's columns");
        logsift.core.LogFormat f = onEdt(csvTab::logFormat);
        assertTrue(f.isCsv(), f.name());
        assertEquals(List.of("time", "level", "Host", "User", "message"), f.columns());
        onEdt(() -> {
            frame.findAll(new SearchQuery("Payment failed", true, false, false));
            return null;
        });
        assertEquals(16, waitForSearch(csvTab).hits().size(), "16 failed payments");
        assertTrue(onEdt(frame::statusTextForTest).contains(csvPath.toString()), onEdt(frame::statusTextForTest));
        // the tree has the hosts (the header row is not a host); unchecking one hides its rows
        ValuesTreePanel hosts = csvTab.valuesTree();
        waitFor(() -> hosts.isShowing() && hosts.valuesForTest() != null && !hosts.isCountingForTest(), 20_000,
                "the tree of hosts");
        assertEquals(400, hosts.valuesForTest().root().total(), "rows, not the header");
        assertEquals(List.of("web-1", "web-2", "web-3"),
                hosts.valuesForTest().root().children().stream().map(n -> n.label()).sorted().toList());
        onEdt(() -> {
            hosts.toggleForTest("web-1");
            return null;
        });
        waitForFilter(csvTab);
        assertEquals(401 - 134, onEdt(() -> csvTab.filterResultForTest().matchCount()), "web-1's rows hidden");
        onEdt(() -> {
            hosts.toggleForTest("web-1");
            return null;
        });
        waitForFilter(csvTab);
        settle();
        shot("74-csv-columns");

        // with a branch of the tree hidden (only the lines kept shown), Find All finds in those lines
        onEdt(() -> {
            hosts.toggleForTest("web-1");
            return null;
        });
        waitForFilter(csvTab);
        assertTrue(onEdt(csvTab::isMatchingOnly));
        onEdt(() -> {
            frame.findAll(new SearchQuery("Payment failed", true, false, false));
            return null;
        });
        waitForSearch(csvTab);
        FindGroup inFiltered = onEdt(() -> csvTab.results().current());
        waitFor(() -> {
            try {
                return onEdt(() -> {
                    csvTab.results().refresh();
                    return inFiltered.count() == 10;
                });
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the failed payments not on web-1");
        assertTrue(onEdt(inFiltered::header).contains("in filtered lines"), onEdt(inFiltered::header));
        assertEquals(2, onEdt(() -> csvTab.results().groups().size()), "the whole-file search is kept too");
        // Clear All (lit while anything is set): no filters, no tree hiding, no searches
        assertTrue(onEdt(() -> csvTab.logBar().clearAllLitForTest()));
        settle();
        shot("74b-find-in-filtered-and-clear-all");
        onEdt(() -> {
            csvTab.logBar().clearAllButtonForTest().doClick();
            return null;
        });
        waitForFilter(csvTab);
        assertEquals(0, onEdt(() -> csvTab.results().groups().size()), "searches closed");
        assertTrue(!onEdt(csvTab::isMatchingOnly) && onEdt(csvTab::treeHiddenCount) == 0, "the whole log again");
        assertEquals(List.of(), onEdt(() -> csvTab.logBar().chipTexts()));
        assertTrue(!onEdt(() -> csvTab.logBar().clearAllLitForTest()), "nothing left to clear");

        // the header row is named by the strip above the columns, not shown again as a row
        onEdt(() -> {
            csvTab.mainView().goToRow(0, true);
            return null;
        });
        assertEquals(1, (int) onEdt(() -> csvTab.mainView().selectedRow()), "line 1 is the header: line 2");
        assertTrue(onEdt(() -> csvTab.mainView().textAtForTest(0)).startsWith("2026-10-21 10:00:00,ERROR,web-1"),
                onEdt(() -> csvTab.mainView().textAtForTest(0)));
        // the format is in the log's bar
        assertEquals("Format: CSV ▾", onEdt(() -> csvTab.logBar().formatButtonForTest().getText()));
        assertTrue(onEdt(() -> csvTab.logBar().formatButtonForTest().isShowing()));
        // right-click a host: show only the rows of that host (a filter on the Host column)
        int hostX = onEdt(() -> csvTab.mainView().xForCellForTest("Host"));
        int rowY = onEdt(() -> csvTab.mainView().yForRowForTest(3));
        assertEquals(new LogView.Cell("Host", "web-3"), onEdt(() -> csvTab.mainView().cellAt(hostX, rowY)));
        javax.swing.JPopupMenu cellMenu = onEdt(() -> {
            javax.swing.JPopupMenu m = csvTab.mainView().menuForTest(hostX, rowY);
            m.show(csvTab.mainView().canvasForTest(), hostX, rowY);
            return m;
        });
        settle();
        onEdt(() -> {
            for (java.awt.Component c : cellMenu.getComponents()) {
                if (c instanceof javax.swing.JMenuItem mi && mi.getText().equals("Show Only Rows Where Host = web-3")) {
                    mi.doClick();
                }
            }
            cellMenu.setVisible(false);
            return null;
        });
        waitForFilter(csvTab);
        assertEquals(List.of("Show: Host = web-3"), onEdt(() -> csvTab.logBar().chipTexts()));
        assertEquals(133, onEdt(() -> csvTab.filterResultForTest().matchCount()), "web-3's rows, not the header");
        assertTrue(onEdt(csvTab::isMatchingOnly));
        // a filter on one column from the column's menu: User contains "u1" (u1, u10…u19)
        onEdt(() -> {
            csvTab.addColumnFilter("User");
            FilterDef d = csvTab.filterPanel().filters().get(1);
            d.text = "u1";
            csvTab.filterPanel().setFilterAction(d, FilterDef.Action.SHOW);
            csvTab.filterPanel().setFormula("A AND B");
            return null;
        });
        waitForFilter(csvTab);
        // web-3 rows are i % 3 == 2; users u1, u10..u19 are i % 40 in {1, 10..19}
        long expected = java.util.stream.IntStream.range(0, 400)
                .filter(i -> i % 3 == 2 && (i % 40 == 1 || (i % 40 >= 10 && i % 40 <= 19))).count();
        assertEquals(expected, (long) onEdt(() -> csvTab.filterResultForTest().matchCount()));
        assertEquals(List.of("Show: Host = web-3", "Show: User contains u1"), onEdt(() -> csvTab.logBar().chipTexts()));
        settle();
        shot("74d-csv-column-filters");
        // the metrics of a CSV: numbers in its messages, and rows per stretch of time for each host
        onEdt(() -> {
            setBottomFor(csvTab, csvTab.metricsPanel());
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> csvTab.metricsPanel().metrics() != null);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the CSV's metrics");
        // with Matching only on, the metrics are those of the rows the filters keep (web-3's)
        waitFor(() -> {
            try {
                return onEdt(() -> csvTab.metricsPanel().metrics().filtered() && !csvTab.metricsPanel().isRunning());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the filtered rows' metrics");
        List<String> keptNames = onEdt(() -> csvTab.metricsPanel().metrics().numbers().stream()
                .map(logsift.core.Numbers.Series::name).toList());
        assertTrue(keptNames.contains("Host = web-3") && !keptNames.contains("Host = web-1"), keptNames.toString());
        // the whole log's metrics with Matching only off (the filters stay)
        onEdt(() -> {
            csvTab.setMatchingOnly(false);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !csvTab.metricsPanel().metrics().filtered() && !csvTab.metricsPanel().isRunning());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the whole CSV's metrics");
        List<String> measured = onEdt(() -> csvTab.metricsPanel().metrics().numbers().stream()
                .map(logsift.core.Numbers.Series::name).toList());
        assertTrue(measured.contains("Host = web-1") && measured.stream().anyMatch(n -> n.contains("took # ms")),
                measured.toString());
        onEdt(() -> {
            int row = 0;
            for (MetricsPanel.Item it : csvTab.metricsPanel().itemListForTest()) {
                if (it.name().equals("Host = web-1")) {
                    break;
                }
                row++;
            }
            csvTab.metricsPanel().itemsForTest().setRowSelectionInterval(row, row);
            return null;
        });
        settle();
        shot("74e-csv-metrics");
        // a narrow window: the log's bar still fits, the format's name is cut short
        onEdt(() -> {
            frame.setSize(820, 860);
            frame.validate();
            return null;
        });
        settle();
        onEdt(() -> {
            LogBar bar = csvTab.logBar();
            java.awt.Component lastOne = bar.getComponent(bar.getComponentCount() - 1);
            assertTrue(lastOne.getX() + lastOne.getWidth() <= bar.getWidth(), "Reload ▾ in the window");
            assertTrue(bar.formatButtonForTest().isShowing() && bar.formatButtonForTest().getWidth() >= 50,
                    "the format button stays");
            assertEquals("CSV ▾", bar.formatButtonForTest().getText(), "without “Format: ”");
            return null;
        });
        shot("74f-narrow-window");
        onEdt(() -> {
            frame.setSize(1400, 860);
            frame.validate();
            csvTab.logBar().clearAllButtonForTest().doClick();
            return null;
        });
        waitForFilter(csvTab);
        assertEquals(List.of(), onEdt(() -> csvTab.logBar().chipTexts()));

        // like any log: bookmarks are remembered, and it opens again by its name (recent files, sessions)
        onEdt(() -> {
            csvTab.mainView().goToRow(26, true);
            csvTab.toggleBookmark();
            frame.closeFile(csvPath);
            frame.openFile(csvPath);
            return null;
        });
        LogTab again = waitForTab("orders.csv");
        assertTrue(again != csvTab);
        assertEquals(java.util.Set.of(26L), onEdt(() -> again.bookmarks().all().keySet()), "bookmark kept");

        // several files of the zip merged by time
        onEdt(() -> {
            frame.tabsForTest().setSelectedComponent(at);
            at.setFilterForTest("");
            at.selectForTest("logs/app.log", "logs/old.log.gz");
            at.mergeButtonForTest().doClick();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null && frame.currentTab().sourceLabels() != null);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the merged log");
        LogTab merged = onEdt(frame::currentTab);
        waitFor(() -> merged.document().lineCount() >= 600, 30_000, "both logs merged");
        assertEquals(600, merged.document().lineCount());

        // a .gz opens right away, by its own name
        Path gz = box.resolve("worker.log.gz");
        try (var o = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            o.write(log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        onEdt(() -> {
            frame.openFiles(List.of(gz));
            return null;
        });
        LogTab gzTab = waitForTab("worker.log.gz");
        assertEquals(300, onEdt(() -> gzTab.document().lineCount()));
        assertEquals(gz.toAbsolutePath().normalize(), onEdt(gzTab::path));
        assertEquals(gz.toAbsolutePath().normalize(), AppState.recentFiles().get(0), "the .gz is the recent file");
        // a zip with one file opens it right away
        Path one = box.resolve("single.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(one))) {
            z.putNextEntry(new java.util.zip.ZipEntry("only.log"));
            z.write(log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        onEdt(() -> {
            frame.openFile(one);
            return null;
        });
        waitForTab("only.log");
        // search in a whole zip
        FolderSearchTab zs = onEdt(() -> {
            FolderSearchTab t = frame.searchInArchiveForTest(zip);
            t.search(zip, "", new SearchQuery("ERROR", true, false, false));
            return t;
        });
        logsift.core.FolderSearch zsr = onEdt(zs::search);
        assertTrue(zsr.awaitDone(30_000));
        java.util.Map<String, Long> hits = new java.util.TreeMap<>();
        for (var r : zsr.results()) {
            hits.put(r.entry(), r.hitCount());
        }
        assertEquals(java.util.Map.of("data.zip!/exports/orders.csv", 16L, "logs/app.log", 6L, "logs/old.log.gz", 6L),
                hits, "every file in the zip, the .gz and the zip inside it too");
        // the results narrowed by file name, and folded or opened all at once
        onEdt(() -> {
            zs.setFileFilterForTest("old.log");
            return null;
        });
        assertEquals(1, onEdt(zs::fileRowsForTest).size(), onEdt(zs::fileRowsForTest).toString());
        assertTrue(onEdt(zs::fileRowsForTest).get(0).contains("old.log.gz"));
        onEdt(() -> {
            zs.setFileFilterForTest("");
            zs.collapseAll(true);
            return null;
        });
        assertEquals(3, onEdt(zs::rowCountForTest), "one row per file");
        onEdt(() -> {
            zs.collapseAll(false);
            return null;
        });
        assertTrue(onEdt(zs::rowCountForTest) > 3 + 16, "the lines again");

        // in the zip's own tab: find in all its files (Ctrl+F goes there), whatever they are called
        onEdt(() -> {
            frame.tabsForTest().setSelectedComponent(at);
            at.setFindForTest("Payment failed");
            at.runFindForTest();
            return null;
        });
        FolderSearchTab fromZip = onEdt(() -> (FolderSearchTab) frame.tabsForTest().getSelectedComponent());
        logsift.core.FolderSearch fz = onEdt(fromZip::search);
        assertTrue(fz.awaitDone(30_000));
        assertEquals(16L, fz.totalHits(), "the CSV in the zip inside the zip");
        assertEquals(zip.toString(), onEdt(fromZip::folderForTest));
        settle();
        shot("75b-find-in-a-zip");

        // from the web: one file opens, several are searched together
        com.sun.net.httpserver.HttpServer web = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        web.createContext("/", x -> {
            boolean ok = "Basic YWRtaW46czNjcmV0".equals(x.getRequestHeaders().getFirst("Authorization"));
            x.sendResponseHeaders(ok ? 200 : 401, ok ? body.length : -1);
            try (var o = x.getResponseBody()) {
                if (ok) {
                    o.write(body);
                }
            }
        });
        web.start();
        try {
            String base = "http://127.0.0.1:" + web.getAddress().getPort();
            UrlDialog ud = onEdt(frame::urlDialogForTest);
            onEdt(() -> {
                ud.setForTest("ftp://nope", null, null);
                ud.submit();
                return null;
            });
            assertTrue(onEdt(ud::errorForTest).contains("Not an http"), onEdt(ud::errorForTest));
            onEdt(() -> {
                ud.setForTest(base + "/node1/server.log", "admin", "s3cret");
                return null;
            });
            settle();
            shotOf(ud.getRootPane(), "75-open-url");
            onEdt(() -> {
                ud.submit();
                return null;
            });
            LogTab web1 = waitForTab("server.log");
            String webTip = onEdt(() -> frame.tabsForTest().getToolTipTextAt(frame.tabsForTest().indexOfComponent(web1)));
            assertEquals(base + "/node1/server.log", webTip);
            assertEquals(300, onEdt(() -> web1.document().lineCount()));
            int tabsBefore = onEdt(() -> frame.tabsForTest().getTabCount());
            onEdt(() -> {
                frame.openUrls(List.of(java.net.URI.create(base + "/node1/server.log"),
                        java.net.URI.create(base + "/node2/server.log")), new logsift.core.UrlFetch.Auth("admin", "s3cret", null));
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.tabsForTest().getTabCount() > tabsBefore
                            && frame.tabsForTest().getSelectedComponent() instanceof FolderSearchTab);
                } catch (Exception e) {
                    return false;
                }
            }, 20_000, "a search over the downloads");
            FolderSearchTab ws = onEdt(() -> (FolderSearchTab) frame.tabsForTest().getSelectedComponent());
            onEdt(() -> {
                ws.search(Path.of(ws.folderForTest()), "", new SearchQuery("ERROR", true, false, false));
                return null;
            });
            logsift.core.FolderSearch wsr = onEdt(ws::search);
            assertTrue(wsr.awaitDone(30_000));
            assertEquals(2, wsr.results().size(), "both downloads: " + wsr.results());
            assertEquals(12, wsr.totalHits());
        } finally {
            web.stop(0);
        }
    }

    @Test
    @Order(41)
    void followARequest() throws Exception {
        // requests handled by web threads, paid on a pool thread; one waits 8 s for the payment
        java.util.List<String[]> events = new java.util.ArrayList<>(); // {time ms, line}
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 2, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        java.util.Random rnd = new java.util.Random(7);
        for (int r = 0; r < 400; r++) {
            long start = t0 + r * 450L + rnd.nextInt(200);
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
                {36 + pay, "DEBUG", web, "c.a.shop.OrderRepository", "update orders set state=?"},
                {40 + pay, declined ? "WARN " : "INFO ", web, "c.a.shop.OrderController",
                    (declined ? "402 Payment Required" : "201 Created") + " requestId=" + id}};
            for (Object[] st : steps) {
                long t = start + (Long) st[0];
                events.add(new String[] {Long.toString(t), TimelineBar.formatFull(t).substring(0, 23) + " " + st[1]
                        + " [" + st[2] + "] " + st[3] + " - " + st[4]});
            }
        }
        events.sort(java.util.Comparator.comparingLong(e -> Long.parseLong(e[0])));
        StringBuilder sb = new StringBuilder();
        for (String[] e : events) {
            sb.append(e[1]).append('\n');
        }
        Path log = dir.resolve("shop-requests.log");
        Files.writeString(log, sb.toString());
        onEdt(() -> {
            frame.setColumns(true);
            frame.openFiles(List.of(log));
            return null;
        });
        LogTab tab = waitForTab("shop-requests.log");
        waitFor(() -> {
            try {
                return onEdt(() -> tab.logFormat() != null && !tab.document().isIndexing());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the format");
        long line = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i)[1].endsWith("POST /orders requestId=r-1042")) {
                line = i;
            }
        }
        long start = line;
        assertTrue(onEdt(() -> {
            tab.showLine(start);
            return tab.followCurrentRequest();
        }), "the line has an ID: " + onEdt(() -> tab.currentLine() + " " + tab.document().line(tab.currentLine())));
        waitFor(() -> {
            try {
                return onEdt(() -> tab.requestPanel() != null && !tab.requestPanel().isRunning()
                        && tab.requestPanel().trace() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the request");
        RequestPanel rp = onEdt(tab::requestPanel);
        logsift.core.RequestTrace.Trace tr = onEdt(rp::trace);
        assertEquals("r-1042", onEdt(rp::value));
        assertEquals(4, tr.steps().size(), "the lines naming it");
        assertEquals(List.of("http-nio-8080-exec-3", "payments-1"), tr.threads());
        assertEquals(1, tr.errors());
        assertEquals(2, tr.longest(), "the wait for the bank");
        assertTrue(tr.steps().get(2).gap() >= 8_200 && tr.steps().get(2).gap() < 8_300, "" + tr.steps().get(2).gap());
        String summary = onEdt(rp::summaryForTest);
        assertTrue(summary.contains("4 lines") && summary.contains("2 threads") && summary.contains("longest wait 8."),
                summary);
        // with the lines its threads wrote in between (the database calls name no ID)
        onEdt(() -> {
            rp.withThreadsForTest().doClick();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !rp.isRunning() && rp.trace() != null && rp.trace().steps().size() > 4);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the thread lines");
        tr = onEdt(rp::trace);
        assertEquals(6, tr.steps().stream().filter(s -> s.thread() != null).count(), "" + tr.steps());
        assertEquals(2, tr.steps().stream().filter(s -> !s.withId()).count());
        onEdt(() -> {
            rp.tableForTest().setRowSelectionInterval(3, 3);
            return null;
        });
        assertEquals(tr.steps().get(3).line(), onEdt(tab::currentLine), "a click goes to the line");
        settle();
        shot("76-follow-request");

        // through a folder: the gateway's log, and the bank adapter's log in a zip inside a zip
        Path services = dir.resolve("services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("gateway.log"),
                "2026-10-21 10:02:19.090 INFO  [gw-4] c.a.gw.Route - POST /orders -> shop requestId=r-1042\n"
                        + "2026-10-21 10:02:19.095 INFO  [gw-2] c.a.gw.Route - GET /health requestId=r-1043\n"
                        + "2026-10-21 10:02:27.340 WARN  [gw-4] c.a.gw.Route - 402 from shop in 8250 ms requestId=r-1042\n");
        java.io.ByteArrayOutputStream innerZip = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(innerZip)) {
            z.putNextEntry(new java.util.zip.ZipEntry("bank-adapter.log"));
            z.write(("2026-10-21 10:02:19.130 INFO  [bank-io-1] c.a.bank.Client - calling bank requestId=r-1042\n"
                    + "2026-10-21 10:02:27.320 ERROR [bank-io-1] c.a.bank.Client - bank timeout after 8190 ms requestId=r-1042\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(services.resolve("payments.zip")))) {
            z.putNextEntry(new java.util.zip.ZipEntry("node1/logs.zip"));
            z.write(innerZip.toByteArray());
        }
        onEdt(() -> {
            rp.withThreadsForTest().doClick(); // off: lines naming the ID only
            rp.setScopeForTest(RequestPanel.Scope.FOLDER, services, true);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !rp.isRunning() && rp.trace() != null
                        && rp.trace().steps().stream().anyMatch(st -> st.source() != null));
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the request in the folder");
        tr = onEdt(rp::trace);
        assertEquals(List.of("gateway.log", "payments.zip/node1/logs.zip/bank-adapter.log",
                "payments.zip/node1/logs.zip/bank-adapter.log", "gateway.log"),
                tr.steps().stream().map(logsift.core.RequestTrace.Step::source).toList(), "by time, across files");
        assertEquals(services.resolve("payments.zip").resolve("node1").resolve("logs.zip").resolve("bank-adapter.log"),
                tr.steps().get(1).file(), "named by its place in the zips");
        assertEquals(List.of("gw-4", "bank-io-1"), tr.threads());
        assertTrue(onEdt(rp::summaryForTest).contains("2 files"), onEdt(rp::summaryForTest));
        // without looking inside zips
        onEdt(() -> {
            rp.setScopeForTest(RequestPanel.Scope.FOLDER, services, false);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !rp.isRunning() && rp.trace() != null && rp.trace().steps().size() == 2);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the request outside zips");
        // through the open logs: this one and the gateway's
        onEdt(() -> {
            frame.openFiles(List.of(services.resolve("gateway.log")));
            return null;
        });
        waitForTab("gateway.log");
        onEdt(() -> {
            frame.tabsForTest().setSelectedComponent(tab);
            rp.setScopeForTest(RequestPanel.Scope.OPEN, null, true);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !rp.isRunning() && rp.trace() != null && rp.trace().steps().size() == 6);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the request in the open logs");
        tr = onEdt(rp::trace);
        assertEquals("gateway.log", tr.steps().get(0).source());
        assertEquals("shop-requests.log", tr.steps().get(1).source());
        assertEquals(null, tr.steps().get(1).file(), "lines of this log");
        assertEquals("gateway.log", tr.steps().get(5).source());
        // a double-click on another file's line opens it there
        onEdt(() -> {
            rp.setScopeForTest(RequestPanel.Scope.FOLDER, services, true);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !rp.isRunning() && rp.trace() != null && rp.trace().steps().size() == 4);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the folder again");
        onEdt(() -> {
            rp.tableForTest().setRowSelectionInterval(1, 1);
            return null;
        });
        settle();
        shot("77-follow-request-in-folder");
        onEdt(() -> {
            javax.swing.JTable table = rp.tableForTest();
            java.awt.Rectangle r = table.getCellRect(2, 2, true);
            for (int clicks = 1; clicks <= 2; clicks++) {
                table.dispatchEvent(new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, r.x + 5, r.y + 5, clicks, false, java.awt.event.MouseEvent.BUTTON1));
            }
            return null;
        });
        LogTab bank = waitForTab("bank-adapter.log");
        waitFor(() -> {
            try {
                return onEdt(() -> bank.currentLine() == 1);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the bank's line");
    }

    @Test
    @Order(42)
    void exceptionsByRootCause() throws Exception {
        StringBuilder sb = new StringBuilder();
        java.util.Random rnd = new java.util.Random(3);
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 0, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        for (int i = 0; i < 3000; i++) {
            String time = TimelineBar.formatFull(t0 + i * 1200L).substring(0, 23);
            int kind = i % 50 == 7 ? 0 : i % 160 == 11 ? 1 : i % 400 == 13 ? 2 : -1;
            if (kind < 0) {
                sb.append(time).append(" INFO  [http-nio-8080-exec-").append(i % 8 + 1)
                        .append("] c.a.shop.OrderController - order ").append(1000 + i).append(" placed in ")
                        .append(20 + rnd.nextInt(200)).append(" ms\n");
                continue;
            }
            sb.append(time).append(" ERROR [http-nio-8080-exec-").append(i % 8 + 1)
                    .append("] o.a.c.c.C.[dispatcherServlet] - Servlet.service() threw exception\n");
            if (kind == 0) { // the same NPE from the price calculator, wrapped
                sb.append("org.springframework.web.util.NestedServletException: Request processing failed\n")
                        .append("\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014)\n")
                        .append("\tat org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:909)\n")
                        .append("\tat javax.servlet.http.HttpServlet.service(HttpServlet.java:665)\n")
                        .append("Caused by: java.lang.IllegalStateException: Could not place order ").append(1000 + i).append('\n')
                        .append("\tat com.acme.shop.OrderService.place(OrderService.java:142)\n")
                        .append("\tat com.acme.shop.OrderController.create(OrderController.java:57)\n")
                        .append("\tat jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\n")
                        .append("\t... 3 more\n")
                        .append("Caused by: java.lang.NullPointerException: Cannot invoke \"com.acme.shop.Coupon.percent()\" because \"coupon\" is null\n")
                        .append("\tat com.acme.shop.PriceCalculator.discount(PriceCalculator.java:88)\n")
                        .append("\tat com.acme.shop.PriceCalculator.total(PriceCalculator.java:42)\n")
                        .append("\tat com.acme.shop.OrderService.place(OrderService.java:139)\n")
                        .append("\t... 5 more\n");
            } else if (kind == 1) {
                sb.append("org.springframework.dao.DataAccessResourceFailureException: could not acquire connection\n")
                        .append("\tat org.springframework.orm.jpa.vendor.HibernateJpaDialect.convert(HibernateJpaDialect.java:277)\n")
                        .append("\tat com.acme.shop.OrderRepository.save(OrderRepository.java:31)\n")
                        .append("Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.\n")
                        .append("\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)\n")
                        .append("\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)\n")
                        .append("\tat com.acme.shop.OrderRepository.save(OrderRepository.java:31)\n")
                        .append("\t... 1 more\n");
            } else {
                sb.append("java.lang.ArithmeticException: / by zero\n")
                        .append("\tat com.acme.shop.Stats.average(Stats.java:19)\n")
                        .append("\tat com.acme.shop.ReportJob.run(ReportJob.java:44)\n");
            }
        }
        Path log = dir.resolve("orders-errors.log");
        Files.writeString(log, sb.toString());
        onEdt(() -> {
            frame.setColumns(false);
            frame.openFiles(List.of(log));
            return null;
        });
        LogTab tab = waitForTab("orders-errors.log");
        waitFor(() -> {
            try {
                return onEdt(() -> tab.exceptionsPanel().result() != null && tab.insightsPanel().report() != null);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the exceptions (with Insights)");
        ExceptionsPanel ep = onEdt(tab::exceptionsPanel);
        logsift.core.Exceptions.Result r = onEdt(ep::result);
        assertEquals(3, r.groups().size(), r.groups().stream().map(logsift.core.Exceptions.Group::key).toList().toString());
        logsift.core.Exceptions.Group npe = r.groups().get(0);
        assertEquals("java.lang.NullPointerException", npe.root().type());
        assertEquals(60, npe.count());
        assertEquals("PriceCalculator.discount(PriceCalculator.java:88)", npe.root().where().shortText());
        assertEquals(List.of("NestedServletException", "IllegalStateException"), npe.wrappers(), "outermost first");
        assertEquals("java.sql.SQLTransientConnectionException", r.groups().get(1).root().type());
        assertEquals("OrderRepository.save(OrderRepository.java:31)", r.groups().get(1).root().where().shortText(),
                "the pool's frames are a library's");
        assertTrue(onEdt(ep::summaryForTest).startsWith("87 exceptions, 3 different"), onEdt(ep::summaryForTest));
        // Insights names the top root causes
        List<logsift.core.Insights.Finding> fs = onEdt(() -> tab.insightsPanel().report().findings());
        assertTrue(fs.stream().anyMatch(f -> f.kind() == logsift.core.Insights.Kind.EXCEPTION
                && f.title().equals("NullPointerException at PriceCalculator.discount(PriceCalculator.java:88) (60×)")),
                fs.stream().map(logsift.core.Insights.Finding::title).toList().toString());
        // a known problem: the connection pool, with what to try
        logsift.core.Insights.Finding pool = fs.stream().filter(f -> f.kind() == logsift.core.Insights.Kind.KNOWN)
                .findFirst().orElseThrow();
        assertEquals("The database connection pool ran out (19×)", pool.title());
        assertEquals("db-pool", pool.known().id());
        onEdt(() -> {
            setBottomFor(tab, tab.insightsPanel());
            return null;
        });
        settle();
        shot("82-insights-known-problems");
        // step through the occurrences: the log follows
        onEdt(() -> {
            tab.showExceptionsTab();
            ep.tableForTest().setRowSelectionInterval(0, 0);
            ep.stepForTest(1);
            return null;
        });
        assertEquals(npe.keptLines()[1], onEdt(tab::currentLine));
        assertTrue(onEdt(ep::occurrenceForTest).contains("2 of 60"), onEdt(ep::occurrenceForTest));
        String html = onEdt(ep::stackForTest);
        assertTrue(html.contains("PriceCalculator.total(PriceCalculator.java:42)"), html);
        settle();
        shot("78-exceptions");
        onEdt(() -> {
            ep.tableForTest().setRowSelectionInterval(1, 1);
            return null;
        });
        html = onEdt(ep::stackForTest);
        assertTrue(html.contains("1 framework frame"), "the pool's frames folded: " + html);
        assertTrue(html.contains("HikariPool.createTimeoutException"), "the first frame is always shown");

        // a frame opens its source in the editor, at its line
        Path src = dir.resolve("shop-src/src/main/java/com/acme/shop/PriceCalculator.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.acme.shop;\n");
        List<List<String>> launched = new java.util.concurrent.CopyOnWriteArrayList<>();
        SourceOpener.launchedForTest = launched::add;
        try {
            SourceOpener.setRoots(List.of(dir.resolve("shop-src")));
            SourceOpener.setEditor(SourceOpener.Editor.CUSTOM);
            SourceOpener.setCommand("\"my editor\" --goto {file}:{line}");
            onEdt(() -> {
                ep.tableForTest().setRowSelectionInterval(0, 0);
                ep.openLinkForTest("frame:2:1"); // the root cause's second frame: PriceCalculator.total
                return null;
            });
            waitFor(() -> !launched.isEmpty(), 10_000, "the editor");
            assertEquals(List.of("my editor", "--goto", src.toString() + ":42"), launched.get(0));
            SettingsDialog before = SettingsDialog.lastForTest();
            javax.swing.SwingUtilities.invokeLater(() -> frame.showSettings(SettingsDialog.SOURCE));
            waitFor(() -> SettingsDialog.lastForTest() != before && SettingsDialog.lastForTest() != null
                    && SettingsDialog.lastForTest().isShowing(), 10_000, "Settings");
            SettingsDialog sd = SettingsDialog.lastForTest();
            settle();
            shotOf(sd.getRootPane(), "79-settings-source-code");
            onEdt(() -> {
                sd.dispose();
                return null;
            });
            // right-click a stack line in the log: the same
            assertEquals(List.of("myeditor", "+42", src.toString()),
                    SourceOpener.custom("myeditor +{line}", src, 42));
        } finally {
            SourceOpener.launchedForTest = null;
            SourceOpener.setEditor(SourceOpener.Editor.AUTO);
            SourceOpener.setRoots(List.of());
        }
    }

    @Test
    @Order(43)
    void durationsAndNumbers() throws Exception {
        // a shop: orders placed (slow for two minutes), sync jobs on a pool (one hangs), queue sizes
        java.util.List<String[]> ev = new java.util.ArrayList<>();
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 0, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < 1800; i++) {
            long t = t0 + i * 1000L;
            boolean slow = i >= 900 && i < 1020;
            int ms = slow ? 2500 + rnd.nextInt(3000) : 60 + rnd.nextInt(120);
            ev.add(new String[] {Long.toString(t), "INFO ", "http-nio-8080-exec-" + (i % 8 + 1), "c.a.shop.OrderController",
                "order " + (5000 + i) + " placed in " + ms + " ms"});
            if (i % 10 == 0) {
                ev.add(new String[] {Long.toString(t + 3), "DEBUG", "metrics", "c.a.shop.Queue", "queue size=" + (slow ? 80 + rnd.nextInt(40) : rnd.nextInt(12))});
            }
            if (i % 30 == 0) {
                String job = "sync-" + (i / 30);
                ev.add(new String[] {Long.toString(t + 5), "INFO ", "jobs-" + (i / 30 % 3), "c.a.shop.SyncJob", "Started job " + job});
                if (i / 30 != 33) { // sync-33 never ends
                    long took = i / 30 == 45 ? 21_000 : 800 + rnd.nextInt(1500);
                    ev.add(new String[] {Long.toString(t + 5 + took), "INFO ", "jobs-" + (i / 30 % 3), "c.a.shop.SyncJob",
                        "Finished job " + job + " in " + took + " ms"});
                }
            }
        }
        ev.sort(java.util.Comparator.comparingLong(e -> Long.parseLong(e[0])));
        StringBuilder sb = new StringBuilder();
        for (String[] e : ev) {
            sb.append(TimelineBar.formatFull(Long.parseLong(e[0])).substring(0, 23)).append(' ').append(e[1]).append(" [")
                    .append(e[2]).append("] ").append(e[3]).append(" - ").append(e[4]).append('\n');
        }
        Path log = dir.resolve("shop-metrics.log");
        Files.writeString(log, sb.toString());
        onEdt(() -> {
            frame.setColumns(true);
            frame.openFiles(List.of(log));
            return null;
        });
        LogTab tab = waitForTab("shop-metrics.log");
        waitFor(() -> {
            try {
                return onEdt(() -> tab.metricsPanel().metrics() != null && tab.insightsPanel().report() != null);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the measurements (with Insights)");
        MetricsPanel mp = onEdt(tab::metricsPanel);
        logsift.core.Metrics m = onEdt(mp::metrics);
        assertEquals(1, m.spans().kinds().size(), "" + m.spans().kinds());
        logsift.core.Spans.Kind job = m.spans().kinds().get(0);
        assertEquals("job", job.name());
        assertEquals(59, job.count());
        assertEquals(1, job.unfinishedCount());
        assertEquals("sync-33", job.unfinished().get(0).key());
        assertEquals("sync-45", job.slowest().get(0).key());
        assertEquals(21_000, job.max());
        List<String> names = m.numbers().stream().map(logsift.core.Numbers.Series::name).toList();
        assertEquals(List.of("order … placed in # ms", "queue size=#"), names, "not the job's own numbers");
        // Insights: what never ended, and when orders got slow
        List<logsift.core.Insights.Finding> fs = onEdt(() -> tab.insightsPanel().report().findings());
        assertTrue(fs.stream().anyMatch(f -> f.kind() == logsift.core.Insights.Kind.UNFINISHED
                && f.title().equals("1 job started and never ended")), fs.stream().map(logsift.core.Insights.Finding::title).toList().toString());
        logsift.core.Insights.Finding spike = fs.stream().filter(f -> f.kind() == logsift.core.Insights.Kind.SPIKE
                && f.title().contains("order … placed in # ms")).findFirst().orElseThrow();
        assertTrue(spike.title().contains("10:15:"), spike.title());
        // the chart of order times, and its slowest lines
        onEdt(() -> {
            tab.insightsPanel();
            setBottomFor(tab, mp);
            int row = names.indexOf("order … placed in # ms") + 1; // after the job
            mp.itemsForTest().setRowSelectionInterval(row, row);
            return null;
        });
        assertTrue(onEdt(() -> mp.examplesForTest().getRowCount()) > 10);
        settle();
        shot("80-metrics-numbers");
        onEdt(() -> {
            mp.itemsForTest().setRowSelectionInterval(0, 0);
            mp.examplesForTest().setRowSelectionInterval(0, 0);
            return null;
        });
        assertEquals("never ended", onEdt(() -> mp.examplesForTest().getValueAt(0, 1)));
        assertEquals(job.unfinished().get(0).startLine(), onEdt(tab::currentLine), "a click goes to the line");
        settle();
        shot("81-metrics-durations");

        // with a filter and Matching only, Metrics counts only the lines the filters keep
        int runs = onEdt(tab::measureRunsForTest);
        long allOrders = onEdt(() -> mp.metrics().numbers().stream()
                .filter(n -> n.name().equals("order … placed in # ms")).findFirst().orElseThrow().count());
        assertEquals(1800, allOrders);
        onEdt(() -> {
            tab.addFilter("[http-nio-8080-exec-1]", FilterDef.Action.SHOW);
            tab.setMatchingOnly(true);
            return null;
        });
        waitForFilter(tab);
        waitFor(() -> {
            try {
                return onEdt(() -> mp.metrics() != null && !mp.isRunning() && mp.metrics().filtered());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the metrics of the kept lines");
        // the strip above the tab says it's filtered (checked below); the summary doesn't repeat it
        assertFalse(onEdt(mp::summaryForTest).contains("filters"), onEdt(mp::summaryForTest));
        logsift.core.Metrics kept = onEdt(mp::metrics);
        assertEquals(List.of("order … placed in # ms"), kept.numbers().stream().map(logsift.core.Numbers.Series::name)
                .toList(), "queue sizes are written by another thread");
        assertEquals(225, kept.numbers().get(0).count(), "one in 8 orders: exec-1's");
        assertTrue(kept.spans().kinds().isEmpty(), "jobs run on other threads");
        assertEquals(runs, (int) onEdt(tab::measureRunsForTest), "the log isn't measured again for the filters");
        onEdt(() -> {
            mp.itemsForTest().setRowSelectionInterval(0, 0);
            return null;
        });
        settle();
        shot("81b-metrics-filtered");
        // the tabs say which follow the filters: a marker on the title and a strip on top
        String strip = "Only the 225 of " + String.format("%,d", onEdt(() -> tab.document().lineCount()))
                + " lines the filters keep";
        assertEquals("marked | " + strip, onEdt(() -> tab.scopeForTest(mp)));
        // a click on a marked tab's name selects it (the title must not catch the click)
        for (java.awt.Component target : List.of(tab.messagesPanel(), mp, tab.messagesPanel(), mp)) {
            onEdt(() -> {
                clickTabTitle(tab.bottomTabsForTest(), target);
                return null;
            });
            assertEquals(target, onEdt(() -> tab.bottomTabsForTest().getSelectedComponent()),
                    "a click on the tab's name selects it");
        }
        assertEquals(TabScope.FOLLOWS, onEdt(() -> tab.bottomTabsForTest().getToolTipTextAt(
                tab.bottomTabsForTest().indexOfComponent(mp))), "the tab says why it is marked");
        assertEquals("plain | null", onEdt(() -> tab.scopeForTest(tab.messagesPanel())));
        assertEquals("plain | null", onEdt(() -> tab.scopeForTest(tab.results())));
        // a Find All while the log shows only the filtered lines finds in those: marked too
        onEdt(() -> {
            frame.findAll(new SearchQuery("placed", true, false, false));
            return null;
        });
        waitForSearch(tab);
        assertEquals("marked | " + strip, onEdt(() -> tab.scopeForTest(tab.results())));
        settle();
        shot("81c-find-results-filtered");
        onEdt(() -> {
            frame.toggleDark();
            setBottomFor(tab, mp);
            return null;
        });
        settle();
        shot("81d-metrics-filtered-dark");
        onEdt(() -> {
            frame.toggleDark();
            frame.setSize(820, 860);
            frame.validate();
            return null;
        });
        settle();
        assertEquals("marked | " + strip, onEdt(() -> tab.scopeForTest(mp)), "a narrow window keeps the marker");
        shot("81e-filtered-narrow-window");
        onEdt(() -> {
            frame.setSize(1400, 860);
            frame.validate();
            return null;
        });
        // "Show all lines" in the strip turns Matching only off: no markers, no strips
        onEdt(() -> {
            tab.bannerForTest(mp).clickForTest();
            return null;
        });
        assertTrue(!onEdt(tab::isMatchingOnly));
        assertEquals("plain | null", onEdt(() -> tab.scopeForTest(mp)));
        assertTrue(onEdt(() -> tab.scopeForTest(tab.results())).startsWith("marked"),
                "the search kept to the filtered lines");
        onEdt(() -> {
            tab.bannerForTest(tab.results()).clickForTest(); // Find in all lines
            return null;
        });
        waitForSearch(tab);
        assertEquals("plain | null", onEdt(() -> tab.scopeForTest(tab.results())));
        waitFor(() -> {
            try {
                return onEdt(() -> mp.metrics() != null && !mp.isRunning() && !mp.metrics().filtered());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the whole log's metrics again");
        assertEquals(1800, (long) onEdt(() -> mp.metrics().numbers().get(0).count()));
        assertEquals(1, (int) onEdt(() -> mp.metrics().spans().kinds().size()));
        assertTrue(!onEdt(mp::summaryForTest).contains("filters keep"));
        assertEquals(runs, (int) onEdt(tab::measureRunsForTest));
        onEdt(() -> {
            tab.logBar().clearAllButtonForTest().doClick();
            return null;
        });
        waitForFilter(tab);
    }

    private static void setBottomFor(LogTab tab, java.awt.Component c) {
        javax.swing.JTabbedPane bottom = (javax.swing.JTabbedPane) javax.swing.SwingUtilities
                .getAncestorOfClass(javax.swing.JTabbedPane.class, c);
        bottom.setSelectedComponent(c);
    }

    @Test
    @Order(44)
    void pythonThreadDumps() throws Exception {
        Path f = dir.resolve("celery-py-spy.txt");
        try (var in = getClass().getResourceAsStream("/threaddumps/py-spy-celery.txt")) {
            Files.write(f, in.readAllBytes());
        }
        ThreadsTab before = onEdt(frame::currentThreadsTab);
        onEdt(() -> {
            frame.openThreadDump(f);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentThreadsTab() != null && frame.currentThreadsTab() != before);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the Python threads");
        ThreadsTab tt = onEdt(frame::currentThreadsTab);
        assertEquals(1, onEdt(() -> tt.dumps().size()));
        assertEquals(3, onEdt(() -> tt.dumps().get(0).threads().size()));
        settle();
        shot("83-python-threads");
        onEdt(() -> {
            frame.closeCurrentTabForTest();
            return null;
        });
    }

    @Test
    @Order(45)
    void findBarAndFilterChips() throws Exception {
        StringBuilder sb = new StringBuilder();
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 2, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        for (int i = 0; i < 3000; i++) {
            boolean declined = i % 97 == 13;
            sb.append(TimelineBar.formatFull(t0 + i * 150L).substring(0, 23))
                    .append(declined ? " WARN  " : i % 11 == 0 ? " DEBUG " : " INFO  ")
                    .append("[http-nio-8080-exec-").append(i % 12 + 1).append("] c.a.shop.OrderController - ")
                    .append(declined ? "card declined by bank requestId=r-" + i : "201 Created requestId=r-" + i)
                    .append('\n');
        }
        Path log = dir.resolve("shop-find.log");
        Files.writeString(log, sb.toString());
        onEdt(() -> {
            frame.openFiles(List.of(log));
            return null;
        });
        LogTab tab = waitForTab("shop-find.log");
        waitFor(() -> {
            try {
                return onEdt(() -> !tab.document().isIndexing() && tab.document().lineCount() >= 3000);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the log");
        // Ctrl+F: typing finds and goes to the first match
        onEdt(() -> {
            tab.mainView().goToRow(0, true);
            frame.openFind();
            tab.findBar().field().setText("declined");
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.findBar().countText().startsWith("1 of"));
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the first match");
        assertTrue(onEdt(() -> tab.findBar().isVisible()), "Ctrl+F opens the find bar");
        long first = onEdt(tab::currentLine);
        assertEquals(13, first, "typing goes to the first match");
        assertEquals("1 of 31", onEdt(() -> tab.findBar().countText()));
        long second = onEdt(() -> {
            tab.findBar().next(true);
            return tab.currentLine();
        });
        assertEquals(110, second, "Enter: the next match");
        assertEquals("2 of 31", onEdt(() -> tab.findBar().countText()));
        assertEquals(13L, (long) onEdt(() -> {
            tab.findBar().next(false);
            return tab.currentLine();
        }), "Shift+Enter: the previous match");
        assertTrue(onEdt(() -> tab.currentFind() == null), "no Find All results until asked");
        settle();
        shot("84-find-bar");

        // Filter ▾ → Show only matching lines: a chip above the log
        onEdt(() -> {
            tab.findBar().filter(FilterDef.Action.SHOW);
            tab.filterPanel().setLevels("E", "W");
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !tab.filterEngine().isRunning()
                        && tab.logBar().chipTexts().equals(List.of("Show: declined", "Levels: ERROR, WARN")));
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the filter chips");
        assertTrue(!onEdt(() -> tab.findBar().isVisible()), "the search became a filter");
        assertTrue(onEdt(tab::isMatchingOnly), "only matching lines");
        assertEquals(31L, (long) onEdt(() -> tab.filterResultForTest().count()));
        settle();
        shot("85-filter-chips");

        // clicking a chip turns its filter off
        onEdt(() -> {
            FilterDef d = tab.filterPanel().filters().get(0);
            tab.filterPanel().setFilterEnabled(d, false);
            return null;
        });
        assertEquals(List.of("Show: declined (off)", "Levels: ERROR, WARN"), onEdt(() -> tab.logBar().chipTexts()));
        onEdt(() -> {
            tab.filterPanel().clearAll();
            frame.closeCurrentTabForTest();
            return null;
        });
    }

    @Test
    @Order(46)
    void startPageWhenNothingIsOpen() throws Exception {
        // a second window with nothing open shows the ways to start, not a toolbar
        MainFrame empty = onEdt(() -> {
            MainFrame f = new MainFrame();
            f.setSize(1400, 860);
            f.setLocation(60, 60);
            f.setVisible(true);
            return f;
        });
        try {
            settle();
            assertTrue(onEdt(() -> empty.startPageForTest().isShowing()), "the start page is shown");
            assertEquals(List.of("Open Log…", "Open Folder…", "Open Remote…", "Open URL…", "Attach to a JVM…",
                    "Open Heap Dump…", "Open Thread Dump…", "Open Profile…", "Compare Files…"),
                    onEdt(() -> empty.startPageForTest().actionsForTest()));
            assertTrue(onEdt(() -> empty.startPageForTest().actionForTest("Open Log…").isShowing()));
            shotOf(empty.getRootPane(), "86-start-page");

            // with a log open, + after the tabs shows the start page in a tab; opening
            // something from it replaces it
            Path log = dir.resolve("start-tab.log");
            Files.writeString(log, "2026-10-24 10:00:00.000 INFO  [main] c.a.App - started\n");
            onEdt(() -> {
                empty.openFiles(List.of(log));
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> empty.currentTab() != null);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "the log");
            onEdt(() -> {
                empty.openStartTab();
                return null;
            });
            settle();
            assertTrue(onEdt(() -> empty.tabsForTest().getSelectedComponent() instanceof StartPage),
                    "+ opens the start page in a tab");
            assertEquals(2, (int) onEdt(() -> empty.tabsForTest().getTabCount()));
            shotOf(empty.getRootPane(), "87-start-tab");
            Path other = dir.resolve("start-tab-2.log");
            Files.writeString(other, "2026-10-24 10:00:01.000 INFO  [main] c.a.App - ready\n");
            onEdt(() -> {
                empty.openFiles(List.of(other));
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> empty.tabsForTest().getTabCount() == 2
                            && empty.currentTab() != null && empty.currentTab().title().equals("start-tab-2.log"));
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "the start tab replaced by the log");
        } finally {
            onEdt(() -> {
                empty.dispose();
                return null;
            });
        }
    }

    @Test
    @Order(47)
    void heapDumpExplained() throws Exception {
        // a program with a known leak dumps its heap; opening the .hprof explains it
        Path hprof = dir.resolve("shop-leak.hprof");
        logsift.heap.LeakApp.run(hprof, dir.resolve("shop-leak-histogram.txt"));
        onEdt(() -> {
            frame.openFiles(List.of(hprof));
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentHeapDumpTab() != null && frame.currentHeapDumpTab().isLoadedForTest());
            } catch (Exception e) {
                return false;
            }
        }, 120_000, "the heap dump read");
        HeapDumpTab tab = onEdt(frame::currentHeapDumpTab);
        String found = onEdt(tab::findingsTextForTest);
        assertTrue(found.contains("One HashMap (20,000 entries) in static LeakApp.CACHE holds"), found);
        assertTrue(found.contains("Mostly 20,000 × LeakApp.Session"), found);
        assertTrue(found.contains("isn't strongly reachable"), found);
        waitFor(() -> {
            try {
                return onEdt(tab::whyTextForTest).contains("static CACHE");
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "why the map is alive");
        String why = onEdt(tab::whyTextForTest);
        assertTrue(why.contains("GC root: a loaded class") && why.contains("class logsift.heap.LeakApp"), why);
        settle();
        shot("88-heap-dump");

        // what the map holds, in the tree
        onEdt(() -> {
            tab.showInTree(tab.dumpForTest().objectsOf("java.util.HashMap", 1)[0]);
            return null;
        });
        settle();
        shot("89-heap-biggest-objects");

        // the heap as a map: boxes as big as what they hold, the biggest objects inside
        onEdt(() -> {
            tab.showTabForTest(HeapDumpTab.MAP_TAB);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.mapForTest().isReadForTest());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the map");
        List<String> boxes = onEdt(() -> tab.mapForTest().boxesForTest());
        assertTrue(boxes.get(0).startsWith("class LeakApp "), boxes.toString());
        int leakApp = onEdt(() -> {
            logsift.heap.HeapModel d = tab.dumpForTest();
            return d.held(d.root(), 1)[0];
        });
        onEdt(() -> {
            tab.mapForTest().zoomIntoForTest(leakApp);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.mapForTest().isReadForTest());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the map of class LeakApp");
        List<String> inside = onEdt(() -> tab.mapForTest().boxesForTest());
        assertTrue(inside.get(0).startsWith("HashMap (20,000 entries) "), inside.toString());
        List<String> sessions = onEdt(() -> tab.mapForTest().insideFirstForTest());
        assertTrue(sessions.get(0).startsWith("20,000 × LeakApp.Session "), sessions.toString());
        onEdt(() -> {
            tab.mapForTest().zoomOutForTest();
            return null;
        });
        settle();
        shot("89a-heap-map");

        // the classes; selecting one shows why its biggest object is alive
        onEdt(() -> {
            tab.showTabForTest(HeapDumpTab.CLASSES_TAB);
            JTable t = tab.classTableForTest();
            for (int r = 0; r < t.getRowCount(); r++) {
                if ("logsift.heap.LeakApp$Session".equals(t.getValueAt(r, 0))) {
                    t.setRowSelectionInterval(r, r);
                }
            }
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(tab::whyTextForTest).trim().startsWith("logsift.heap.LeakApp$Session");
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "why a session is alive");
        assertTrue(onEdt(tab::whyTextForTest).contains("value"), onEdt(tab::whyTextForTest));
        settle();
        shot("90-heap-classes");

        // memory spent for nothing: the copies of "ACTIVE", the empty lists
        waitFor(() -> {
            try {
                return onEdt(tab::isWasteDoneForTest);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the wasted memory");
        String waste = onEdt(tab::wasteTextForTest);
        assertTrue(waste.contains("\"ACTIVE\" | 20,001 | LeakApp.Session.status")
                || waste.contains("\"ACTIVE\" | 20001 | LeakApp.Session.status"), waste);
        assertTrue(waste.contains("Empty ArrayList | 20000 | LeakApp.Session.tags"), waste);
        assertTrue(onEdt(tab::findingsTextForTest).contains("is spent on copies of the same strings and empty"),
                onEdt(tab::findingsTextForTest));
        onEdt(() -> {
            tab.showTabForTest(HeapDumpTab.WASTE_TAB);
            JTable t = tab.wasteTableForTest(0);
            t.setRowSelectionInterval(0, 0);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(tab::whyTextForTest).contains("status");
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "why a copy of ACTIVE is alive");
        settle();
        shot("91-heap-waste");

        // the dump's threads, in the Threads view
        onEdt(() -> {
            tab.threads();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentThreadsTab() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the dump's threads");
        settle();
        shot("92-heap-threads");
        onEdt(() -> {
            frame.closeCurrentTabForTest();
            return null;
        });
        onEdt(() -> {
            frame.closeCurrentTabForTest();
            return null;
        });

        // an earlier dump of the same program, compared: what grew, and what holds it
        Path early = dir.resolve("shop-early.hprof");
        logsift.heap.LeakApp.run(early, dir.resolve("shop-early-histogram.txt"), 5_000);
        Path later = dir.resolve("shop-later.hprof");
        logsift.heap.LeakApp.run(later, dir.resolve("shop-later-histogram.txt"));
        HeapCompareTab cmp = onEdt(() -> frame.openHeapCompare(later, early)); // in either order
        waitFor(() -> {
            try {
                return onEdt(cmp::isLoadedForTest);
            } catch (Exception e) {
                return false;
            }
        }, 120_000, "the dumps compared");
        String grew = onEdt(cmp::findingsTextForTest);
        assertTrue(grew.contains("HashMap in static LeakApp.CACHE grew by"), grew);
        assertTrue(grew.contains("5,000 entries → 20,000 entries"), grew);
        assertTrue(grew.contains("Mostly 20,000 × LeakApp.Session"), grew);
        settle();
        shot("93-heap-compare");
        onEdt(() -> {
            frame.closeCurrentTabForTest();
            return null;
        });

        // a log where the JVM ran out of memory: its heap dump opens from the line
        Path oomLog = dir.resolve("shop-oom.log");
        Files.writeString(oomLog, "2026-10-24 10:00:00.000 INFO  [main] shop.App - Started\n"
                + "java.lang.OutOfMemoryError: Java heap space\n"
                + "Dumping heap to shop-leak.hprof ...\n"
                + "Heap dump file created [31000000 bytes in 0.3 secs]\n");
        onEdt(() -> {
            frame.openFiles(List.of(oomLog));
            return null;
        });
        LogTab log = waitForTab("shop-oom.log");
        waitFor(() -> {
            try {
                return onEdt(() -> log.heapDumpMentioned(1, () -> false) != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the log read");
        onEdt(() -> {
            frame.openHeapDumpOf(log, 1);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentHeapDumpTab() != null
                        && frame.currentHeapDumpTab().file().equals(hprof.toAbsolutePath().normalize()));
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the heap dump opened from the log");
        onEdt(() -> {
            frame.closeCurrentTabForTest();
            frame.closeCurrentTabForTest();
            return null;
        });
    }

    @Test
    @Order(48)
    void heapDumpOnAServer() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"), "needs a Unix shell");
        // a "server": an SSH server in the test running commands here, with a home of its own
        Path hprof = dir.resolve("shop-leak.hprof");
        if (!Files.exists(hprof)) {
            logsift.heap.LeakApp.run(hprof, dir.resolve("shop-leak-histogram.txt"));
        }
        Path home = Files.createDirectories(dir.resolve("server-home"));
        var server = org.apache.sshd.server.SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
                dir.resolve("heap-hostkey.ser")));
        server.setPasswordAuthenticator((u, pw, sess) -> u.equals("ops") && pw.equals("secret"));
        server.setSubsystemFactories(List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory()));
        server.setForwardingFilter(org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE);
        String java = Path.of(System.getProperty("java.home"), "bin").toString();
        server.setCommandFactory((channel, command) -> new org.apache.sshd.server.shell.ProcessShellFactory(
                command, "/bin/sh", "-c", "HOME=" + home + "; PATH=" + java + ":/usr/bin:/bin; export HOME PATH; "
                + command).createShell(channel));
        server.start();
        System.setProperty("logsift.noKnownHosts", "true");
        try {
            logsift.core.RemoteMirror.Target target = new logsift.core.RemoteMirror.Target("127.0.0.1",
                    server.getPort(), "ops", hprof.toString(), "secret", null, null);
            List<logsift.jvm.RemoteHeaps.Plan> asked = new java.util.ArrayList<>();
            onEdt(() -> {
                frame.heapServerConfirm = plan -> {
                    asked.add(plan);
                    return 0; // read it there
                };
                frame.openHeapDumpOnServer(target, null);
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentHeapDumpTab() != null
                            && frame.currentHeapDumpTab().isLoadedForTest());
                } catch (Exception e) {
                    return false;
                }
            }, 120_000, "the dump read on the server");
            HeapDumpTab tab = onEdt(frame::currentHeapDumpTab);
            assertEquals(1, asked.size(), "asked once before starting");
            assertTrue(onEdt(tab::title).equals("Heap: shop-leak.hprof on ops@127.0.0.1"), onEdt(tab::title));
            String found = onEdt(tab::findingsTextForTest);
            assertTrue(found.contains("One HashMap (20,000 entries) in static LeakApp.CACHE holds"), found);
            waitFor(() -> {
                try {
                    return onEdt(tab::whyTextForTest).contains("static CACHE");
                } catch (Exception e) {
                    return false;
                }
            }, 20_000, "why the map is alive (asked on the server)");
            assertTrue(Files.exists(home.resolve(".logsift/heap")), "the reader's files on the server");
            settle();
            shot("94-heap-on-a-server");

            // the question asked first, as it looks
            javax.swing.JDialog question = onEdt(() -> {
                javax.swing.JDialog d = frame.heapServerPane(asked.get(0)).createDialog(frame, "Heap Dump on a server");
                d.setModal(false);
                d.setVisible(true);
                return d;
            });
            settle();
            shotOf(question.getRootPane(), "95-heap-on-a-server-question");
            onEdt(() -> {
                question.dispose();
                return null;
            });

            // closing the tab ends the reader there: its index was temporary files, gone with it
            String work = asked.get(0).workDir();
            onEdt(() -> {
                frame.closeCurrentTabForTest();
                return null;
            });
            waitFor(() -> {
                try (var left = Files.list(Path.of(work))) {
                    return left.findAny().isEmpty();
                } catch (java.io.IOException e) {
                    return false;
                }
            }, 30_000, "the reader's temporary index gone");
        } finally {
            System.clearProperty("logsift.noKnownHosts");
            server.stop(true);
        }
    }

    /** Waits until the current tab is a log titled so. */
    @Test
    @Order(48)
    void gcLogExplained() throws Exception {
        // a GC log of a JVM whose heap was too small for what it kept (Java 17, G1, with dates)
        Path gc = dir.resolve("orders-gc.log");
        try (var in = new java.util.zip.GZIPInputStream(getClass().getResourceAsStream("/gc/j17-g1-time.log.gz"))) {
            Files.write(gc, in.readAllBytes());
        }
        onEdt(() -> {
            frame.openFile(gc);
            return null;
        });
        LogTab log = waitForTab("orders-gc.log");
        waitFor(() -> {
            try {
                return onEdt(log::gcBannerShownForTest);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the offer to analyze the GC log");
        settle();
        shot("96-gc-log-banner");
        onEdt(() -> {
            log.gcAnalyzeButtonForTest().doClick();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentGcTabForTest() != null);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the GC tab");
        GcTab gt = onEdt(frame::currentGcTabForTest);
        logsift.jvm.GcReport r = gt.report();
        assertEquals("G1", r.log().collector());
        assertTrue(r.pauses().count() > 100, "pauses: " + r.pauses().count());
        List<String> titles = r.findings().stream().map(logsift.jvm.GcReport.Finding::title).toList();
        assertTrue(titles.stream().anyMatch(t -> t.startsWith("GC stopped the program")), titles.toString());
        assertTrue(onEdt(frame::statusTextForTest).contains("pauses"), onEdt(frame::statusTextForTest));
        settle();
        shot("97-gc-summary");
        String[] views = {"98-gc-heap", "99-gc-pauses", "100-gc-allocation", "101-gc-by-kind", "102-gc-all"};
        for (int i = 0; i < views.length; i++) {
            int tab = i + 1;
            onEdt(() -> {
                gt.viewsForTest().setSelectedIndex(tab);
                return null;
            });
            settle();
            shot(views[i]);
        }
        // charts zoom together
        double[] whole = onEdt(() -> gt.heapChartForTest().rangeForTest());
        onEdt(() -> {
            gt.viewsForTest().setSelectedIndex(1);
            GcChart c = gt.heapChartForTest();
            int y = c.getHeight() / 2;
            java.awt.event.MouseEvent press = new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_PRESSED,
                    0, 0, 200, y, 1, false, java.awt.event.MouseEvent.BUTTON1);
            for (var l : c.getMouseListeners()) {
                l.mousePressed(press);
            }
            java.awt.event.MouseEvent drag = new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                    0, 0, 400, y, 1, false, java.awt.event.MouseEvent.BUTTON1);
            for (var l : c.getMouseMotionListeners()) {
                l.mouseDragged(drag);
            }
            java.awt.event.MouseEvent rel = new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_RELEASED,
                    0, 0, 400, y, 1, false, java.awt.event.MouseEvent.BUTTON1);
            for (var l : c.getMouseListeners()) {
                l.mouseReleased(rel);
            }
            return null;
        });
        double[] zoomed = onEdt(() -> gt.pauseChartForTest().rangeForTest());
        assertTrue(zoomed[0] > whole[0] && zoomed[1] < whole[1], "the pause chart zoomed with the heap chart");
        // a collection in the table: double-click shows its line in the log
        JTable t = onEdt(gt::eventsTableForTest);
        long line = onEdt(() -> {
            gt.viewsForTest().setSelectedIndex(5);
            java.awt.Rectangle cell = t.getCellRect(10, 3, true);
            java.awt.event.MouseEvent click = new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    0, 0, cell.x + 2, cell.y + 2, 2, false, java.awt.event.MouseEvent.BUTTON1);
            for (var l : t.getMouseListeners()) {
                l.mouseClicked(click);
            }
            return r.log().events().get(t.convertRowIndexToModel(10)).line();
        });
        assertEquals(log, onEdt(frame::currentTab), "back in the log");
        assertEquals(line, onEdt(log::currentLine), "at the collection's line");
        // JVM → Open GC Log opens a file and explains it in one go
        onEdt(() -> {
            frame.openGcLog(gc);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentGcTabForTest() != null && frame.currentGcTabForTest() != gt);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "a second GC tab");
    }

    @Test
    @Order(49)
    void heapDumpOverJmxIsCopiedFromTheServer() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(), "the test SSH server runs commands with /bin/sh");
        // over a JMX port the JVM writes the dump on its own machine; Logsift copies it over SSH (and leaves it there)
        Path hprof = dir.resolve("shop-leak.hprof");
        if (!Files.exists(hprof)) {
            logsift.heap.LeakApp.run(hprof, dir.resolve("shop-leak-histogram.txt"));
        }
        Path serverTmp = Files.createDirectories(dir.resolve("server-tmp"));
        Path there = serverTmp.resolve("logsift-heap-4242-1.hprof");
        Files.copy(hprof, there, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var server = org.apache.sshd.server.SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
                dir.resolve("dump-hostkey.ser")));
        server.setPasswordAuthenticator((u, pw, sess) -> u.equals("ops") && pw.equals("secret"));
        server.setSubsystemFactories(List.of(new org.apache.sshd.sftp.server.SftpSubsystemFactory()));
        server.setCommandFactory((channel, command) -> new org.apache.sshd.server.shell.ProcessShellFactory(
                command, "/bin/sh", "-c", command).createShell(channel));
        server.start();
        System.setProperty("logsift.noKnownHosts", "true");
        Path here = dir.resolve("copied-over-ssh.hprof");
        Files.deleteIfExists(here);
        try {
            onEdt(() -> {
                frame.sshForDumpForTest = d -> new logsift.core.RemoteMirror.Target("127.0.0.1", server.getPort(), "ops",
                        "", "secret", null, null);
                frame.copyDumpFromServer(new logsift.jvm.JmxJvms.DumpOnServer("db1", there.toString()), here);
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> frame.currentHeapDumpTab() != null
                            && frame.currentHeapDumpTab().file().equals(here.toAbsolutePath().normalize()));
                } catch (Exception e) {
                    return false;
                }
            }, 60_000, "the copied dump opened");
            assertEquals(Files.size(hprof), Files.size(here), "copied whole");
            assertTrue(Files.exists(there), "left on the server: Logsift doesn't remove it");
        } finally {
            onEdt(() -> {
                frame.sshForDumpForTest = null;
                return null;
            });
            System.clearProperty("logsift.noKnownHosts");
            server.stop(true);
        }
    }

    @Test
    @Order(50)
    void tableCsvProfile() throws Exception {
        // a table, not a log: no time column; an ID, categories, numbers, yes/no and free text
        StringBuilder sb = new StringBuilder("order_id,country,amount_eur,weight_kg,paid,size_bytes,note\n");
        String[] countries = {"DE", "FR", "NL", "DE", "DE", "IT", "ES", "DE", "FR", "PL"};
        Random rnd = new Random(7);
        for (int i = 0; i < 2000; i++) {
            double amount = Math.round(Math.exp(2 + rnd.nextGaussian() * 0.6) * 100) / 100.0;
            sb.append(String.format(java.util.Locale.ROOT, "A-%05d,%s,%.2f,%d,%s,%d,%s%n", i, countries[rnd.nextInt(10)],
                    amount, 1 + rnd.nextInt(5), rnd.nextInt(5) == 0 ? "no" : "yes",
                    (long) Math.pow(10, 2 + rnd.nextInt(5)) + rnd.nextInt(900),
                    i % 7 == 0 ? "\"Leave it with the neighbour, number " + (i % 40) + ", please\"" : ""));
        }
        Path csv = dir.resolve("orders-table.csv");
        Files.writeString(csv, sb.toString());
        onEdt(() -> {
            frame.setColumns(true);
            frame.openFiles(List.of(csv));
            return null;
        });
        LogTab tab = waitForTab("orders-table.csv");
        waitFor(() -> {
            try {
                return onEdt(() -> tab.logFormat() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the table's columns");
        // a CSV's columns are named as in its header, everywhere
        assertEquals("order_id", onEdt(() -> tab.mainView().columns().title(0)));
        assertEquals("amount_eur", onEdt(() -> tab.columnName("amount_eur")));
        assertEquals("order_id", onEdt(() -> ValuesTreePanel.title(tab.logFormat(), "order_id")));
        // no times: no timeline, and its button says why
        assertTrue(!onEdt(tab::hasTimes));
        assertTrue(!onEdt(() -> tab.timeline().isVisible()));
        assertTrue(!onEdt(() -> tab.logBar().timelineButtonForTest().isEnabled()));
        assertTrue(onEdt(() -> tab.logBar().timelineButtonForTest().getToolTipText()).startsWith("No times in this file"));
        // the Profile tab: a card per column with its kind and a chart
        ProfilePanel pp = onEdt(tab::profilePanel);
        javax.swing.JSplitPane split = onEdt(() -> {
            setBottomFor(tab, pp);
            javax.swing.JSplitPane sp = (javax.swing.JSplitPane) SwingUtilities.getAncestorOfClass(
                    javax.swing.JSplitPane.class, SwingUtilities.getAncestorOfClass(JTabbedPane.class, pp));
            sp.setDividerLocation(260); // room for the cards
            return sp;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> pp.result() != null && !pp.isRunning());
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the profile");
        Map<String, logsift.core.ColumnProfile.Kind> kinds = new LinkedHashMap<>();
        onEdt(() -> {
            pp.result().columns().forEach(c -> kinds.put(c.title(), c.kind()));
            return null;
        });
        assertEquals(List.of("ID", "category", "number", "category", "yes / no", "number", "text"),
                kinds.values().stream().map(k -> k.label).toList(), kinds.toString());
        assertEquals(2000, onEdt(() -> pp.result().rows()), "rows, not the header");
        assertTrue(onEdt(() -> pp.result().columns().get(5).logScale()), "sizes over orders of magnitude");
        assertEquals(7, onEdt(() -> pp.cardsForTest().size()));
        settle();
        shot("103-table-profile");
        // click a value: a filter on that column; the profile then counts the rows the filters keep
        onEdt(() -> {
            pp.cardsForTest().get(1).chart.clickForTest(0); // the most frequent country
            return null;
        });
        waitForFilter(tab);
        String top = onEdt(() -> pp.result().columns().get(1).bars().get(0).label());
        assertEquals("DE", top);
        assertEquals(List.of("Show: country = DE"), onEdt(() -> tab.logBar().chipTexts()), "named as in the file");
        waitFor(() -> {
            try {
                return onEdt(() -> pp.result() != null && !pp.isRunning() && pp.result().filtered());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the profile of the filtered rows");
        long des = onEdt(() -> tab.filterResultForTest().matchCount());
        assertEquals(des, (long) onEdt(() -> pp.result().rows()));
        assertEquals(1, (long) onEdt(() -> pp.result().columns().get(1).distinct()));
        assertTrue(onEdt(() -> tab.scopeForTest(pp)).startsWith("marked | Only the "), onEdt(() -> tab.scopeForTest(pp)));
        assertFalse(onEdt(pp::summaryForTest).contains("filters"), onEdt(pp::summaryForTest));
        // a histogram bucket: a "between" filter on the amounts
        long barCount = onEdt(() -> {
            ProfilePanel.ColumnCard amount = pp.cardsForTest().get(2);
            int biggest = 0;
            List<logsift.core.ColumnProfile.Bar> bars = amount.column.bars();
            for (int i = 1; i < bars.size(); i++) {
                biggest = bars.get(i).count() > bars.get(biggest).count() ? i : biggest;
            }
            amount.chart.clickForTest(biggest);
            return bars.get(biggest).count();
        });
        waitForFilter(tab);
        List<String> chips = onEdt(() -> tab.logBar().chipTexts());
        assertEquals(2, chips.size(), chips.toString());
        assertTrue(chips.get(1).startsWith("Show: amount_eur between ") && chips.get(1).contains("..<"),
                chips.toString());
        onEdt(() -> {
            tab.filterPanel().setFormula("A AND B");
            return null;
        });
        waitForFilter(tab);
        waitFor(() -> {
            try {
                return onEdt(() -> pp.result() != null && !pp.isRunning() && pp.result().rows()
                        == tab.filterResultForTest().matchCount());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the profile follows the filters");
        assertEquals(barCount, (long) onEdt(() -> tab.filterResultForTest().matchCount()),
                "the bar's rows, no more (a value on an edge is in one bar)");
        settle();
        shot("103b-table-profile-filtered");
        // dark mode
        onEdt(() -> {
            tab.logBar().clearAllButtonForTest().doClick();
            frame.toggleDark();
            return null;
        });
        waitForFilter(tab);
        waitFor(() -> {
            try {
                return onEdt(() -> pp.result() != null && !pp.isRunning() && !pp.result().filtered());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the whole table again");
        settle();
        shot("103c-table-profile-dark");
        onEdt(() -> {
            frame.toggleDark();
            return null;
        });
        // Metrics and Insights work without times
        onEdt(() -> {
            setBottomFor(tab, tab.metricsPanel());
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.metricsPanel().metrics() != null && !tab.metricsPanel().isRunning());
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the table's metrics");
        List<String> measured = onEdt(() -> tab.metricsPanel().metrics().numbers().stream()
                .map(logsift.core.Numbers.Series::name).toList());
        assertTrue(measured.contains("amount_eur") && measured.contains("size_bytes"), measured.toString());
        assertTrue(measured.stream().noneMatch(n -> n.contains(" = ")), "no rows per minute without times");
        onEdt(() -> {
            tab.metricsPanel().itemsForTest().setRowSelectionInterval(0, 0);
            return null;
        });
        settle();
        shot("103d-table-metrics-no-times");
        onEdt(() -> {
            setBottomFor(tab, tab.insightsPanel());
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.insightsPanel().report() != null);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the table's insights");
        assertTrue(onEdt(() -> tab.insightsPanel().report().findings().stream()
                .noneMatch(f -> f.kind() == logsift.core.Insights.Kind.NOISE)), "rows of values are no messages");
        settle();
        shot("103e-table-insights-no-times");
        onEdt(() -> {
            setBottomFor(tab, tab.messagesPanel());
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> tab.messagesPanel().statusForTest().startsWith("This table has no message column"));
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "Messages says why it is empty");
        onEdt(() -> {
            split.setDividerLocation(split.getHeight() - 240);
            return null;
        });
    }

    @Test
    @Order(51)
    void mergeCsvFiles() throws Exception {
        // three exports of one shop: one has an extra column, one uses ; between fields
        Path box = Files.createDirectories(dir.resolve("csv-merge"));
        StringBuilder eu = new StringBuilder("time,host,status,duration_ms\n");
        StringBuilder us = new StringBuilder("time,host,status,duration_ms,region\n");
        StringBuilder ap = new StringBuilder("Time;Host;Status;Duration_ms\n");
        Random rnd = new Random(3);
        for (int i = 0; i < 300; i++) {
            String t = String.format("2026-10-21 10:%02d:%02d", i / 60 % 60, i % 60);
            int ms = 20 + rnd.nextInt(200);
            switch (i % 3) {
                case 0 -> eu.append(String.format("%s,eu-%d,%d,%d%n", t, i % 2 + 1, i % 40 == 0 ? 500 : 200, ms));
                case 1 -> us.append(String.format("%s,us-%d,%d,%d,\"us-east, zone %d\"%n", t, i % 2 + 1,
                        i % 25 == 1 ? 404 : 200, ms, i % 3));
                default -> ap.append(String.format("%s;ap-1;%d;%d%n", t, 200, ms));
            }
        }
        Path a = box.resolve("shop-eu.csv");
        Path b = box.resolve("shop-us.csv");
        Path c = box.resolve("shop-ap.csv");
        Files.writeString(a, eu);
        Files.writeString(b, us);
        Files.writeString(c, ap);
        String how = MainFrame.mergeExplanation(List.of(a, b, c));
        assertTrue(how.contains("These are CSV files") && how.contains("time order"), how);
        // CSV files and a log together: merged as logs, and the dialog says so
        assertTrue(MainFrame.mergeExplanation(List.of(a, dir.resolve("shop-requests.log"))).contains("merged as logs"));
        onEdt(() -> {
            frame.setColumns(true);
            frame.mergeFiles(List.of(a, b, c));
            return null;
        });
        LogTab tab = waitForTab("merged-3-files.csv");
        assertEquals("Merged (3 CSV files)", onEdt(() -> frame.tabsForTest().getTitleAt(
                frame.tabsForTest().indexOfComponent(tab))));
        waitFor(() -> {
            try {
                return onEdt(() -> tab.logFormat() != null && tab.timeIndex() != null);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the merged CSV's columns");
        logsift.core.LogFormat f = onEdt(tab::logFormat);
        assertTrue(f.isCsv());
        assertEquals(List.of("source", "time", "host", "status", "duration_ms", "region"), f.columns());
        assertEquals(301, onEdt(() -> tab.document().lineCount()), "one header and every row");
        String first = onEdt(() -> tab.document().line(1));
        assertTrue(first.startsWith("shop-eu,2026-10-21 10:00:00,eu-1,500,") && first.endsWith(","),
                "the first row, by time, region empty: " + first);
        assertTrue(onEdt(() -> tab.document().line(2)).startsWith("shop-us,2026-10-21 10:00:01,us-2,404,"));
        assertTrue(onEdt(() -> tab.document().line(2)).endsWith(",\"us-east, zone 1\""), "quoted again");
        // the tree splits by source
        ValuesTreePanel tree = tab.valuesTree();
        waitFor(() -> tree.isShowing() && tree.valuesForTest() != null && !tree.isCountingForTest(), 20_000,
                "the tree of sources");
        assertEquals(List.of("shop-ap", "shop-eu", "shop-us"),
                tree.valuesForTest().root().children().stream().map(n -> n.label()).sorted().toList());
        settle();
        shot("104-csv-merge");
        // the Profile has the sources as a category
        ProfilePanel pp = onEdt(tab::profilePanel);
        onEdt(() -> {
            setBottomFor(tab, pp);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> pp.result() != null && !pp.isRunning());
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the merged CSV's profile");
        assertEquals(logsift.core.ColumnProfile.Kind.CATEGORY, onEdt(() -> pp.result().columns().get(0).kind()));
        assertEquals(3, (long) onEdt(() -> pp.result().columns().get(0).distinct()));
        settle();
        shot("104b-csv-merge-profile");
        // rows written to a source later are added, in the merged columns
        Files.writeString(c, "2026-10-21 11:00:00;ap-9;503;999\n", java.nio.file.StandardOpenOption.APPEND);
        waitFor(() -> {
            try {
                return onEdt(() -> tab.document().lineCount() == 302);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the new row followed");
        assertEquals("shop-ap,2026-10-21 11:00:00,ap-9,503,999,", onEdt(() -> tab.document().line(301)));
        // tables without times: one file after the other
        Path t1 = box.resolve("stock-berlin.csv");
        Path t2 = box.resolve("stock-paris.csv");
        Files.writeString(t1, "sku,qty\nA-1,5\nA-2,7\n");
        Files.writeString(t2, "qty;sku;aisle\n3;B-1;4\n");
        assertTrue(MainFrame.mergeExplanation(List.of(t1, t2)).contains("one file after the other"));
        onEdt(() -> {
            frame.mergeFiles(List.of(t1, t2));
            return null;
        });
        LogTab stock = waitForTab("merged-2-files.csv");
        waitFor(() -> {
            try {
                return onEdt(() -> stock.logFormat() != null && stock.document().lineCount() == 4);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the merged tables");
        assertEquals(List.of("source,sku,qty,aisle", "stock-berlin,A-1,5,", "stock-berlin,A-2,7,", "stock-paris,B-1,3,4"),
                onEdt(() -> java.util.stream.LongStream.range(0, 4).mapToObj(l -> stock.document().line(l)).toList()));
    }

    @Test
    @Order(52)
    void middleClickClosesTabs() throws Exception {
        Path box = Files.createDirectories(dir.resolve("middle-click"));
        List<Path> files = new ArrayList<>();
        for (String n : List.of("one.log", "two.log", "three.log")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append(String.format("2026-10-21 10:00:%02d.000 INFO  [main] c.a.App - %s step %d%n", i, n, i));
            }
            Path p = box.resolve(n);
            Files.writeString(p, sb);
            files.add(p);
        }
        onEdt(() -> {
            files.forEach(frame::openFile);
            return null;
        });
        waitForTab("three.log");
        JTabbedPane tabs = onEdt(frame::tabsForTest);
        int two = onEdt(() -> {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (tabs.getComponentAt(i) instanceof LogTab t && t.title().equals("two.log")) {
                    return i;
                }
            }
            return -1;
        });
        assertTrue(two >= 0);
        int before = onEdt(tabs::getTabCount);
        // something to remember about two.log: a bookmark
        onEdt(() -> {
            tabs.setSelectedIndex(two);
            LogTab t = (LogTab) tabs.getComponentAt(two);
            t.mainView().goToRow(7, true);
            t.toggleBookmark();
            return null;
        });
        java.awt.Component right = onEdt(() -> tabs.getComponentAt(two + 1));
        // a middle click on the empty strip does nothing
        onEdt(() -> {
            java.awt.Rectangle last = tabs.getBoundsAt(tabs.getTabCount() - 1);
            int x = Math.min(tabs.getWidth() - 5, last.x + last.width + 3);
            if (tabs.indexAtLocation(x, last.y + last.height / 2) < 0) {
                tabs.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        InputEvent.BUTTON2_DOWN_MASK, x, last.y + last.height / 2, 1, false, MouseEvent.BUTTON2));
            }
            return null;
        });
        assertEquals(before, (int) onEdt(tabs::getTabCount), "nothing closed");
        // a middle click on two.log's tab closes it, like its ✕
        onEdt(() -> {
            java.awt.Rectangle r = tabs.getBoundsAt(two);
            tabs.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON2_DOWN_MASK, r.x + r.width / 3, r.y + r.height / 2, 1, false, MouseEvent.BUTTON2));
            return null;
        });
        assertEquals(before - 1, (int) onEdt(tabs::getTabCount));
        assertTrue(onEdt(() -> {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (tabs.getComponentAt(i) instanceof LogTab t && t.title().equals("two.log")) {
                    return false;
                }
            }
            return true;
        }), "two.log closed");
        assertEquals(right, onEdt(tabs::getSelectedComponent), "the neighbour is selected, as with the ✕");
        assertTrue(AppState.loadFileState(files.get(1).toAbsolutePath().normalize()).toString().contains("7"),
                "its state (the bookmark) was saved");
        onEdt(() -> {
            frame.openFile(files.get(1));
            return null;
        });
        LogTab again = waitForTab("two.log");
        assertEquals(java.util.Set.of(7L), onEdt(() -> again.bookmarks().all().keySet()), "the bookmark is back");
        // in Find results, a middle click on a search's header closes that search
        onEdt(() -> {
            frame.findAll(new SearchQuery("step 1", true, false, false));
            return null;
        });
        waitForSearch(again);
        onEdt(() -> {
            frame.findAll(new SearchQuery("step 2", true, false, false));
            return null;
        });
        waitForSearch(again);
        assertEquals(2, (int) onEdt(() -> again.results().groups().size()));
        onEdt(() -> {
            LogView v = again.results().view();
            int y = v.yForRowForTest(0);
            v.canvasForTest().dispatchEvent(new MouseEvent(v.canvasForTest(), MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), InputEvent.BUTTON2_DOWN_MASK, 40, y, 1, false, MouseEvent.BUTTON2));
            return null;
        });
        assertEquals(1, (int) onEdt(() -> again.results().groups().size()), "the top search closed");
        assertEquals("step 1", onEdt(() -> again.results().groups().get(0).query.text()));
        onEdt(() -> {
            for (Path p : files) {
                frame.closeFile(p);
            }
            return null;
        });
    }

    @Test
    @Order(53)
    void openFolderShowsItsFiles() throws Exception {
        // a folder of logs: a subfolder with a .gz and a zip, and a CSV in another
        Path box = dir.resolve("folder-tab");
        if (Files.exists(box)) {
            try (var walk = Files.walk(box)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(box.resolve("sub"));
        Files.createDirectories(box.resolve("data"));
        StringBuilder app = new StringBuilder();
        StringBuilder api = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            app.append(String.format("2026-10-21 10:%02d:%02d.000 INFO  [main] c.a.App - app step %d%n", i / 60, i % 60, i));
            api.append(String.format("2026-10-21 10:%02d:%02d.500 WARN  [http-1] c.a.Api - api step %d%n", i / 60, i % 60, i));
        }
        Files.writeString(box.resolve("app.log"), app);
        Files.writeString(box.resolve("api.log"), api);
        try (var gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(box.resolve("sub/old.log.gz")))) {
            gz.write(app.toString().replace("app step", "old step").getBytes(StandardCharsets.UTF_8));
        }
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(box.resolve("sub/bundle.zip")))) {
            z.putNextEntry(new java.util.zip.ZipEntry("node1.log"));
            z.write(app.toString().replace("app step", "node step").getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new java.util.zip.ZipEntry("node2.log"));
            z.write(api.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(box.resolve("data/orders.csv"), "time,host\n2026-10-21 10:00:00,web-1\n2026-10-21 10:00:01,web-2\n");
        onEdt(() -> {
            frame.openFolder(box);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentArchiveTabForTest() != null && frame.currentArchiveTabForTest().isFolder()
                        && !frame.currentArchiveTabForTest().rowsForTest().isEmpty());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the folder's tab");
        ArchiveTab ft = onEdt(frame::currentArchiveTabForTest);
        assertEquals(List.of("data", "  orders.csv", "sub", "  bundle.zip", "  old.log.gz", "api.log", "app.log"),
                onEdt(ft::rowsForTest), "folders first, then files; a zip is a file here");
        assertTrue(onEdt(ft::summaryForTest).startsWith("5 files, "), onEdt(ft::summaryForTest));
        assertEquals("folder-tab", onEdt(() -> frame.tabsForTest().getTitleAt(frame.tabsForTest().indexOfComponent(ft))));
        assertTrue(AppState.recentFiles().contains(box.toAbsolutePath().normalize()), "a recent folder");
        settle();
        shot("105-folder-tab");
        // the name filter
        onEdt(() -> {
            ft.setFilterForTest("log");
            return null;
        });
        assertEquals(List.of("sub/old.log.gz", "api.log", "app.log"), onEdt(ft::shownForTest));
        settle();
        shot("105b-folder-filtered");
        // open a file: a log tab
        onEdt(() -> {
            ft.selectForTest("api.log");
            ft.openSelected();
            return null;
        });
        LogTab apiTab = waitForTab("api.log");
        assertEquals(box.resolve("api.log").toAbsolutePath().normalize(), onEdt(apiTab::path));
        // dropping the folder again (or Open with on it) selects its tab
        onEdt(() -> {
            frame.openFiles(List.of(box));
            return null;
        });
        assertEquals(ft, onEdt(() -> frame.tabsForTest().getSelectedComponent()));
        // merge two files by time (a .gz too: unpacked first)
        onEdt(() -> {
            ft.selectForTest("app.log", "sub/old.log.gz");
            ft.mergeButtonForTest().doClick();
            return null;
        });
        LogTab merged = waitForTab("merged-2-logs.log");
        waitFor(() -> {
            try {
                return onEdt(() -> merged.document().lineCount() == 240 && !merged.document().isIndexing());
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the merged lines");
        // the .gz unpacked; at the same time, the files in the tree's order (sub/ first)
        assertTrue(onEdt(() -> merged.document().line(0)).startsWith("<old> 2026-10-21 10:00:00.000"),
                onEdt(() -> merged.document().line(0)));
        assertTrue(onEdt(() -> merged.document().line(1)).startsWith("<app> 2026-10-21 10:00:00.000"),
                onEdt(() -> merged.document().line(1)));
        // find in all files: a folder search over the folder, inside the .gz and the zip too
        onEdt(() -> {
            frame.tabsForTest().setSelectedComponent(ft);
            ft.setFindForTest("step 7");
            ft.runFindForTest();
            return null;
        });
        FolderSearchTab fs = onEdt(() -> (FolderSearchTab) frame.tabsForTest().getSelectedComponent());
        logsift.core.FolderSearch search = onEdt(fs::search);
        assertTrue(search.awaitDone(30_000));
        assertEquals(box.toString(), onEdt(fs::folderForTest));
        // "step 7" and "step 7x" (70-79) in: app, api, old (.gz), node1 and node2 (zip): 11 lines each
        assertEquals(55L, search.totalHits());
        // Refresh lists new files
        Files.writeString(box.resolve("new.log"), "2026-10-21 11:00:00.000 INFO  [main] c.a.App - new\n");
        onEdt(() -> {
            frame.tabsForTest().setSelectedComponent(ft);
            ft.setFilterForTest("");
            ft.refreshButtonForTest().doClick();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> ft.rowsForTest().contains("new.log"));
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the new file listed");
        // a narrow window: the folder's bar still fits
        onEdt(() -> {
            frame.setSize(820, 860);
            frame.validate();
            return null;
        });
        settle();
        shot("105c-folder-narrow");
        onEdt(() -> {
            frame.setSize(1400, 860);
            frame.validate();
            return null;
        });
    }

    @Test
    @Order(54)
    void unexpectedErrorsAreCountedAndListed() throws Exception {
        onEdt(() -> {
            ErrorReporter.resetForTest(); // a new session: no errors yet
            frame.setSize(1400, 860);
            return null;
        });
        assertTrue(!onEdt(() -> frame.errorsButtonForTest().isVisible()), "no errors: no indicator");
        // Help → Show Errors… with none
        onEdt(() -> {
            frame.showErrors();
            return null;
        });
        waitFor(() -> ErrorsDialog.openForTest() != null, 10_000, "the errors window");
        assertTrue(onEdt(ErrorsDialog::headForTest).contains("No errors this session"));
        onEdt(() -> {
            ErrorsDialog.openForTest().dispose();
            return null;
        });
        // the first error of a session pops up, and the status bar counts it
        onEdt(() -> {
            ErrorReporter.report(new IllegalStateException("First unexpected error (test)"));
            return null;
        });
        waitFor(() -> ErrorReporter.openForTest() != null, 10_000, "the error window");
        settle();
        assertEquals("⚠ 1 error", onEdt(() -> frame.errorsButtonForTest().getText()));
        assertTrue(onEdt(() -> frame.errorsButtonForTest().isVisible()));
        onEdt(() -> {
            ErrorReporter.openForTest().dispose();
            return null;
        });
        // a later error only counts: no window
        onEdt(() -> {
            for (int i = 0; i < 2; i++) { // the same error twice, from the same place
                ErrorReporter.report(new IllegalArgumentException("Second unexpected error (test)"));
            }
            return null;
        });
        settle();
        assertTrue(ErrorReporter.openForTest() == null, "no pop-up for the second error");
        assertEquals("⚠ 2 errors", onEdt(() -> frame.errorsButtonForTest().getText()), "the same error counts once");
        settle();
        shot("106-errors-indicator");
        // the button opens the list
        onEdt(() -> {
            frame.errorsButtonForTest().doClick();
            return null;
        });
        waitFor(() -> ErrorsDialog.openForTest() != null, 10_000, "the errors window");
        settle();
        assertTrue(onEdt(ErrorsDialog::headForTest).contains("2 errors in this session"));
        assertTrue(onEdt(ErrorsDialog::headForTest).contains("Check the text before sending"));
        shotOf(onEdt(() -> ErrorsDialog.openForTest().getRootPane()), "106b-errors-window");
        assertTrue(Files.readString(Path.of(System.getProperty("logsift.home"), "errors.log"))
                .contains("Second unexpected error (test)"), "logged to errors.log (the test's own home)");
        // Clear hides the indicator until the next error
        onEdt(() -> {
            ErrorsDialog.clickForTest("Clear");
            return null;
        });
        assertTrue(ErrorsDialog.openForTest() == null);
        assertTrue(!onEdt(() -> frame.errorsButtonForTest().isVisible()), "cleared");
        onEdt(() -> {
            ErrorReporter.report(new IllegalStateException("Third unexpected error (test)"));
            return null;
        });
        assertEquals("⚠ 1 error", onEdt(() -> frame.errorsButtonForTest().getText()));
        assertTrue(ErrorReporter.openForTest() == null, "not the first of the session: no pop-up");
        // leave the window as it was for the tests after this one
        onEdt(() -> {
            ErrorReporter.resetForTest();
            return null;
        });
        assertTrue(!onEdt(() -> frame.errorsButtonForTest().isVisible()));
    }

    @Test
    @Order(55)
    void comparesCsvFilesByKey() throws Exception {
        // two exports of the same orders: the newer one shuffled, with a region column, some
        // prices and statuses changed, some orders gone and some new
        Path box = Files.createDirectories(dir.resolve("csv-compare"));
        Path left = box.resolve("orders-monday.csv");
        Path right = box.resolve("orders-tuesday.csv");
        StringBuilder a = new StringBuilder("order_id,customer,qty,price,status\n");
        List<String> rows = new ArrayList<>();
        int priceChanges = 0;
        int statusChanges = 0;
        int removed = 0;
        String[] regions = {"eu", "us", "apac"};
        for (int i = 1; i <= 500; i++) {
            String customer = "customer " + (i % 37);
            String price = String.format(java.util.Locale.ROOT, "%d.%02d", 5 + i % 90, (i * 7) % 100);
            String status = i % 4 == 0 ? "paid" : "open";
            a.append(String.format("ORD-%04d,%s,%d,%s,%s%n", i, customer, 1 + i % 9, price, status));
            if (i % 61 == 0) {
                removed++;
                continue;
            }
            String newPrice = price;
            String newStatus = status;
            if (i % 50 == 7) {
                newPrice = String.format(java.util.Locale.ROOT, "%d.%02d", 6 + i % 90, (i * 7) % 100);
                priceChanges++;
            } else if (i % 50 == 21) {
                newStatus = "shipped";
                statusChanges++;
            } else if (i % 25 == 3) {
                newPrice = price + "0"; // 12.30 → 12.300: the same number
            }
            rows.add(String.format("ORD-%04d,%s,%d,%s,%s,%s", i, customer, 1 + i % 9, newPrice, newStatus,
                    regions[i % 3]));
        }
        for (int i = 501; i <= 506; i++) {
            rows.add(String.format("ORD-%04d,customer %d,1,9.99,open,eu", i, i % 37));
        }
        java.util.Collections.shuffle(rows, new Random(5));
        StringBuilder b = new StringBuilder("order_id,customer,qty,price,status,region\n");
        rows.forEach(r -> b.append(r).append('\n'));
        Files.writeString(left, a);
        Files.writeString(right, b);
        int changed = priceChanges + statusChanges;
        int same = 500 - removed - changed;

        onEdt(() -> {
            frame.compareFiles(left, right);
            return null;
        });
        CsvCompareTab tab = waitForCompare(null);
        // a key is suggested, not chosen: rows are lined up in file order until one is picked
        assertEquals(List.of(), onEdt(tab::keysForTest), "no key chosen by default");
        assertEquals("lined up in file order", onEdt(tab::result).matchedBy());
        assertTrue(onEdt(tab::suggestionForTest).startsWith("Suggested: order_id — unique and filled in"),
                onEdt(tab::suggestionForTest));
        assertEquals("Use order_id as Key", onEdt(() -> tab.useSuggestedForTest().getText()));
        assertFalse(onEdt(tab::repeatsOfferedForTest), "no key: no key can repeat");
        assertEquals("‼ Key repeats  (with a key)", onEdt(() -> tab.showTextForTest(4)));
        settle();
        shot("106g-csv-compare-suggested-key");
        // a key that isn't unique: its repeats are explained with an example from the files
        onEdt(() -> {
            tab.setKeysForTest(List.of("customer"));
            return null;
        });
        logsift.core.diff.TableDiffResult byOrder = onEdt(tab::result);
        onEdt(() -> {
            tab.runCompare();
            return null;
        });
        waitForNewResult(tab, byOrder, "comparing by customer");
        assertTrue(onEdt(tab::repeatsOfferedForTest));
        assertTrue(onEdt(tab::result).duplicateKeys() > 0);
        String tip = onEdt(tab::repeatsTipForTest);
        assertTrue(tip.matches("(?s).*For example, customer <b>customer \\d+</b> is on \\d+ rows of orders-monday.csv and \\d+ rows?"
                + " of orders-tuesday.csv.*"), tip);
        assertTrue(tip.contains("tick another key column too"), tip);
        assertTrue(onEdt(tab::statusText).contains(" key repeats"), onEdt(tab::statusText));
        settle();
        shot("106h-csv-compare-key-repeats");
        useSuggestedKey(tab);
        assertTrue(onEdt(tab::repeatsTipForTest).contains("None here: every order_id is on one row in each file"));
        assertEquals(List.of("order_id"), onEdt(tab::keysForTest), "one click: the suggested key");
        assertNull(onEdt(tab::useSuggestedForTest), "the suggestion is in use: no button");
        assertEquals("Compare: orders-monday.csv ↔ orders-tuesday.csv", onEdt(tab::title));
        logsift.core.diff.TableDiffResult r = onEdt(tab::result);
        assertEquals("by key order_id", r.matchedBy());
        assertEquals(6, r.count(logsift.core.diff.DiffStatus.ADDED));
        assertEquals(removed, r.count(logsift.core.diff.DiffStatus.REMOVED));
        assertEquals(changed, r.count(logsift.core.diff.DiffStatus.CHANGED));
        assertEquals(same, r.count(logsift.core.diff.DiffStatus.SAME));
        // Show's list has the counts
        assertEquals("+ Added (only right)  6", onEdt(() -> tab.showTextForTest(2)));
        assertEquals("≠ Changed  " + changed, onEdt(() -> tab.showTextForTest(1)));
        assertEquals("All differences  " + (6 + removed + changed), onEdt(() -> tab.showTextForTest(0)));
        assertEquals("Everything  " + r.size(), onEdt(() -> tab.showTextForTest(6)));
        assertTrue(r.notes().contains("Column region only in the right file (orders-tuesday.csv)"), r.notes().toString());
        assertEquals(6 + removed + changed, onEdt(tab::shownForTest).length, "all differences shown");
        settle();
        shot("106-csv-compare");
        summary.put("csv compare (500 rows)", r.millis() + " ms");

        // changes per column: a click shows only the rows changed there
        List<String> counts = onEdt(tab::columnCountsForTest);
        int priceAt = counts.indexOf("price: " + priceChanges + " rows");
        assertTrue(priceAt > 0, counts.toString());
        assertTrue(counts.contains("status: " + statusChanges + " rows"), counts.toString());
        onEdt(() -> {
            tab.clickColumnForTest(priceAt);
            return null;
        });
        assertEquals(priceChanges, onEdt(tab::shownForTest).length);
        String cell = onEdt(() -> tab.cellText(0, 4 + 1)); // status, line, key, customer, qty, price
        assertTrue(cell.matches("\\d+\\.\\d+ → \\d+\\.\\d+"), cell);
        settle();
        shot("106b-csv-compare-price");
        onEdt(() -> {
            tab.clickColumnForTest(0);
            return null;
        });
        // Show: only the rows added
        onEdt(() -> {
            tab.showForTest().setSelectedIndex(2);
            return null;
        });
        assertEquals(6, onEdt(tab::shownForTest).length);
        assertEquals("Added", onEdt(() -> tab.showForTest().getSelectedItem()));
        onEdt(() -> {
            tab.showForTest().setSelectedIndex(6); // everything
            return null;
        });
        assertEquals(r.size(), onEdt(tab::shownForTest).length);
        // F7: the next difference, skipping rows that are the same
        onEdt(() -> {
            tab.tableForTest().setRowSelectionInterval(0, 0);
            frame.findMenuItemForTest("Next Difference").doClick();
            return null;
        });
        int at = onEdt(() -> tab.tableForTest().getSelectedRow());
        int item = onEdt(() -> tab.shownForTest()[at]);
        assertTrue(r.status(item).isDifference());
        for (int k = 1; k < at; k++) {
            assertFalse(r.status(onEdt(tab::shownForTest)[k]).isDifference(), "no difference skipped");
        }
        String pos = onEdt(tab::positionForTest);
        assertTrue(pos.startsWith("difference ") && pos.contains(" of " + (6 + removed + changed) + " · "), pos);
        onEdt(() -> {
            frame.findMenuItemForTest("Next Difference").doClick();
            frame.findMenuItemForTest("Previous Difference").doClick();
            return null;
        });
        assertEquals(at, (int) onEdt(() -> tab.tableForTest().getSelectedRow()));
        // search within the results
        onEdt(() -> {
            tab.showForTest().setSelectedIndex(0);
            tab.setSearchForTest("shipped");
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> !tab.isSearchingForTest());
            } catch (Exception e) {
                return false;
            }
        }, 10_000, "the search");
        assertEquals(statusChanges, onEdt(tab::shownForTest).length);
        // export
        Path out = box.resolve("differences.csv");
        onEdt(() -> {
            tab.exportForTest(out);
            return null;
        });
        List<String> exported = Files.readAllLines(out);
        assertEquals(1 + 6 + removed + changed, exported.size());
        assertEquals("status,left line,right line,order_id,customer (left),customer (right),qty (left),qty (right),"
                + "price (left),price (right),status (left),status (right)", exported.get(0));
        onEdt(() -> {
            tab.setSearchForTest("");
            return null;
        });

        // double-click: the row's line in the right file's tab
        int changedRow = onEdt(() -> {
            int[] sh = tab.shownForTest();
            for (int i = 0; i < sh.length; i++) {
                if (r.status(sh[i]) == logsift.core.diff.DiffStatus.CHANGED) {
                    return i;
                }
            }
            return -1;
        });
        long rightLine = r.rightLine(onEdt(tab::shownForTest)[changedRow]);
        onEdt(() -> {
            tab.openRowForTest(changedRow);
            return null;
        });
        LogTab rightTab = waitForTab("orders-tuesday.csv");
        waitFor(() -> {
            try {
                return onEdt(rightTab::currentLine) == rightLine;
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "line " + rightLine + " in the right file");
        // a CSV tab has Compare in its bar
        waitFor(() -> {
            try {
                return onEdt(() -> rightTab.logBar().compareButtonForTest().isVisible());
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the CSV tab's Compare button");
        settle();
        shot("106c-csv-compare-opened-line");
        onEdt(() -> {
            frame.tabsForTest().setSelectedComponent(compareTabOf(tab));
            return null;
        });

        // removing the key compares again at once (in file order), and adding it back too
        logsift.core.diff.TableDiffResult keyed = onEdt(tab::result);
        onEdt(() -> {
            tab.removeKeyForTest("order_id");
            return null;
        });
        waitForNewResult(tab, keyed, "comparing without the key");
        assertEquals(List.of(), onEdt(tab::keysForTest));
        assertEquals("lined up in file order", onEdt(tab::result).matchedBy(), "compared again, without the key");
        useSuggestedKey(tab);
        assertEquals("by key order_id", onEdt(tab::result).matchedBy());
        assertEquals(changed, onEdt(tab::result).count(logsift.core.diff.DiffStatus.CHANGED));

        // dark mode
        onEdt(() -> {
            frame.toggleDark();
            tab.showForTest().setSelectedIndex(6);
            return null;
        });
        settle();
        shot("106d-csv-compare-dark");
        onEdt(() -> {
            frame.toggleDark();
            tab.showForTest().setSelectedIndex(0);
            return null;
        });
        // a narrow window: the bars wrap, nothing is cut off
        onEdt(() -> {
            frame.setSize(820, 860);
            frame.validate();
            return null;
        });
        settle();
        shot("106e-csv-compare-narrow");
        onEdt(() -> {
            frame.setSize(1400, 860);
            frame.validate();
            return null;
        });

        // drag and drop: the outline over the side a file lands on
        onEdt(() -> {
            tab.setHoverForTest(1);
            return null;
        });
        settle();
        shot("106f-csv-compare-drop-outline");
        onEdt(() -> {
            tab.setHoverForTest(-1);
            return null;
        });
        // a file dropped on the right half replaces the right file and compares again
        Path wednesday = box.resolve("orders-wednesday.tsv");
        Files.writeString(wednesday, Files.readString(left).replace(',', '\t').replace("open", "closed"));
        onEdt(() -> {
            tab.dropForTest(List.of(wednesday), new java.awt.Point(tab.getWidth() - 50, 200));
            return null;
        });
        waitForCompare(tab, "orders-monday.csv", "orders-wednesday.tsv");
        logsift.core.diff.TableDiffResult r2 = onEdt(tab::result);
        long open = a.toString().lines().filter(l -> l.endsWith(",open")).count();
        assertEquals(open, r2.count(logsift.core.diff.DiffStatus.CHANGED), "open → closed");
        assertEquals(0, r2.count(logsift.core.diff.DiffStatus.ADDED) + r2.count(logsift.core.diff.DiffStatus.REMOVED));
        // two files dropped through the tab's transfer handler fill both sides
        onEdt(() -> {
            javax.swing.TransferHandler.TransferSupport ts = new javax.swing.TransferHandler.TransferSupport(
                    tab.tableForTest(), FileDropTest.files(right, left));
            assertTrue(tab.tableForTest().getTransferHandler().importData(ts));
            return null;
        });
        waitForCompare(tab, "orders-tuesday.csv", "orders-monday.csv");
        assertEquals(6, onEdt(tab::result).count(logsift.core.diff.DiffStatus.REMOVED), "swapped: new orders are removed");

        // not CSV: compared as text, line by line
        Path logA = box.resolve("a.log");
        Path logB = box.resolve("b.log");
        Files.writeString(logA, "2026-10-21 10:00:00.000 INFO  [main] c.a.App - started\n");
        Files.writeString(logB, "2026-10-21 10:00:00.000 INFO  [main] c.a.App - stopped\n");
        onEdt(() -> {
            frame.compareFiles(logA, logB);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentCompareTabForTest() != null
                        && frame.currentCompareTabForTest().text() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the logs compared as text");
        FileCompareTab noted = onEdt(frame::currentCompareTabForTest);
        assertEquals(1, onEdt(() -> noted.text().result().linesChanged()));

        // the folder's tab: two files selected → Compare
        onEdt(() -> {
            frame.openFolder(box);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentArchiveTabForTest() != null
                        && frame.currentArchiveTabForTest().rowsForTest().contains("orders-monday.csv"));
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the folder's tab");
        ArchiveTab ft = onEdt(frame::currentArchiveTabForTest);
        onEdt(() -> {
            ft.selectForTest("orders-monday.csv");
            return null;
        });
        assertFalse(onEdt(() -> ft.compareButtonForTest().isEnabled()), "one file: nothing to compare with");
        onEdt(() -> {
            ft.selectForTest("orders-monday.csv", "orders-tuesday.csv");
            ft.compareButtonForTest().doClick();
            return null;
        });
        CsvCompareTab fromFolder = waitForCompare(tab);
        useSuggestedKey(fromFolder);
        assertEquals(6, onEdt(fromFolder::result).count(logsift.core.diff.DiffStatus.ADDED));
        // the old Compare (by kind of message) is still there, renamed
        assertNotNull(onEdt(() -> frame.findMenuItemForTest("Compare Logs by Message…")));
        onEdt(() -> {
            for (java.awt.Component c : List.of(compareTabOf(tab), noted, compareTabOf(fromFolder), ft)) {
                frame.closeTab(frame.tabsForTest().indexOfComponent(c));
            }
            frame.closeFile(right);
            return null;
        });
        assertFalse(onEdt(() -> frame.errorsButtonForTest().isVisible()), "no errors in the app while comparing");
    }

    @Test
    @Order(57)
    void pivotTableAndChart() throws Exception {
        // sales: a region, a product, an amount, a quantity and when it was sold, over three months
        StringBuilder sb = new StringBuilder("region,product,amount,qty,sold_at\n");
        String[] regions = {"North", "South", "East", "West"};
        String[] products = {"Tea", "Coffee", "Cake"};
        double total = 0;
        double north = 0;
        Random rnd = new Random(11);
        for (int i = 0; i < 1200; i++) {
            String region = regions[rnd.nextInt(4)];
            double amount = 5 + rnd.nextInt(200) / 4.0;
            total += amount;
            if (region.equals("North")) {
                north += amount;
            }
            sb.append(String.format(java.util.Locale.ROOT, "%s,%s,%.2f,%d,2026-%02d-%02d %02d:15:00%n", region,
                    products[rnd.nextInt(3)], amount, 1 + rnd.nextInt(5), 1 + i % 3, 1 + i % 28, i % 24));
        }
        Path csv = dir.resolve("sales.csv");
        Files.writeString(csv, sb.toString());
        AppState.prefs().remove("pivot." + Integer.toHexString(csv.toString().hashCode()));
        onEdt(() -> {
            frame.openFiles(List.of(csv));
            return null;
        });
        LogTab tab = waitForTab("sales.csv");
        PivotPanel pp = onEdt(tab::pivotPanel);
        onEdt(() -> {
            setBottomFor(tab, pp);
            javax.swing.JSplitPane sp = (javax.swing.JSplitPane) SwingUtilities.getAncestorOfClass(
                    javax.swing.JSplitPane.class, SwingUtilities.getAncestorOfClass(JTabbedPane.class, pp));
            sp.setDividerLocation(150); // room for the table and the chart
            pp.setViewForTest("both");
            return null;
        });
        assertEquals("Pivot", onEdt(() -> {
            JTabbedPane b = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, pp);
            return b.getTitleAt(b.indexOfComponent(pp));
        }));
        waitForPivot(pp);
        assertEquals(List.of("region", "product", "amount", "qty", "sold_at"), onEdt(pp::fieldsForTest));
        assertEquals("Sum of amount by region", onEdt(pp::quickStartForTest));
        settle();
        shot("108-pivot-empty");

        // quick start: the regions down the side, the amounts summed
        onEdt(() -> {
            pp.clickQuickStartForTest();
            return null;
        });
        waitForPivot(pp);
        assertEquals(List.of("region"), onEdt(() -> pp.areaForTest(PivotPanel.Area.ROWS)));
        assertEquals(List.of("Sum of amount"), onEdt(() -> pp.areaForTest(PivotPanel.Area.VALUES)));
        JTable t = onEdt(pp::tableForTest);
        assertEquals(List.of("East", "North", "South", "West", "Grand Total"), onEdt(() -> {
            List<String> out = new ArrayList<>();
            for (int r = 0; r < t.getRowCount(); r++) {
                out.add(pp.cellTextForTest(r, 0));
            }
            return out;
        }));
        assertEquals(PivotPanel.format(Math.round(north * 100) / 100.0), onEdt(() -> pp.cellTextForTest(1, 1)));
        assertEquals(PivotPanel.format(Math.round(total * 100) / 100.0), onEdt(() -> pp.cellTextForTest(4, 1)));
        assertEquals(4, onEdt(() -> pp.chartForTest().data().size()), "a bar per region");

        // the products across the top (dragged there), the grand total last
        onEdt(() -> {
            pp.drop("field:" + pp.columnForTest("product"), PivotPanel.Area.COLUMNS, -1);
            return null;
        });
        waitForPivot(pp);
        assertEquals(List.of("", "Cake", "Coffee", "Tea", "Total"), onEdt(() -> {
            List<String> out = new ArrayList<>();
            for (int c = 0; c < t.getColumnCount(); c++) {
                out.add(t.getColumnName(c));
            }
            return out;
        }).stream().map(n -> n.startsWith("region") ? "" : n).toList());
        assertEquals(3, onEdt(() -> pp.chartForTest().data().series().size()), "a series per product");
        settle();
        shot("108b-pivot-table-and-chart");
        onEdt(() -> {
            pp.setChartTypeForTest(PivotChart.Type.STACKED_COLUMN);
            pp.setHeatForTest(true);
            return null;
        });
        settle();
        shot("108c-pivot-stacked-color-scale");

        // months under the regions, counted as % of each row; sorted by the total, largest first
        onEdt(() -> {
            pp.setHeatForTest(false);
            pp.drop("field:" + pp.columnForTest("sold_at"), PivotPanel.Area.ROWS, -1);
            pp.setAggForTest(0, logsift.core.Pivot.Agg.COUNT, logsift.core.Pivot.ShowAs.PCT_ROW);
            return null;
        });
        waitForPivot(pp);
        assertEquals(List.of("region", "sold_at (Month)"), onEdt(() -> pp.areaForTest(PivotPanel.Area.ROWS)));
        assertEquals("North", onEdt(() -> pp.cellTextForTest(4, 0)), "East, its three months, then North");
        assertEquals("100 %", onEdt(() -> pp.cellTextForTest(0, t.getColumnCount() - 1)), "a row is 100 % of itself");
        assertEquals("2026-01", onEdt(() -> pp.cellTextForTest(1, 0)), "months under the first region");
        onEdt(() -> {
            pp.toggleForTest(0); // fold the first region
            return null;
        });
        assertEquals("North", onEdt(() -> pp.cellTextForTest(1, 0)), "East folded: North next");
        onEdt(() -> {
            pp.toggleForTest(0);
            pp.setChartTypeForTest(PivotChart.Type.LINE);
            return null;
        });
        settle();
        shot("108d-pivot-months-percent-line");

        // a filter: only tea; the sums again, then a cell's rows in the log
        onEdt(() -> {
            pp.drop("item:ROWS:1", null, -1); // the months dragged out
            pp.drop("item:COLUMNS:0", PivotPanel.Area.FILTERS, -1);
            pp.setAggForTest(0, logsift.core.Pivot.Agg.SUM, logsift.core.Pivot.ShowAs.VALUE);
            return null;
        });
        waitForPivot(pp);
        onEdt(() -> {
            pp.keepForTest(0, java.util.Set.of("Tea"));
            return null;
        });
        waitForPivot(pp);
        long tea = sb.toString().lines().filter(l -> l.contains(",Tea,")).count();
        assertEquals(tea, (long) onEdt(() -> pp.result().rows()));
        assertEquals(List.of("product: 1 values"), onEdt(() -> pp.areaForTest(PivotPanel.Area.FILTERS)));
        onEdt(() -> {
            pp.setChartTypeForTest(PivotChart.Type.DOUGHNUT);
            pp.sortByForTest(1); // by the sum, largest first
            return null;
        });
        String first = onEdt(() -> pp.cellTextForTest(0, 0));
        double firstSum = Double.parseDouble(onEdt(() -> pp.cellTextForTest(0, 1)).replace(",", ""));
        double secondSum = Double.parseDouble(onEdt(() -> pp.cellTextForTest(1, 1)).replace(",", ""));
        assertTrue(firstSum >= secondSum, first + " " + firstSum + " " + secondSum);
        settle();
        shot("108e-pivot-filter-doughnut");
        String copied = onEdt(() -> pp.tableText(true));
        assertTrue(copied.startsWith("region\tSum of amount\n"), copied);
        assertTrue(onEdt(pp::csvText).startsWith("region,Sum of amount\n"));
        onEdt(() -> {
            pp.showRowsForTest(0, 1);
            return null;
        });
        waitForFilter(tab);
        assertEquals(List.of("Show: region = " + first), onEdt(() -> tab.logBar().chipTexts()));

        // the layout is remembered for the file
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(tab));
            frame.openFiles(List.of(csv));
            return null;
        });
        LogTab again = waitForTab("sales.csv");
        PivotPanel pp2 = onEdt(again::pivotPanel);
        onEdt(() -> {
            setBottomFor(again, pp2);
            return null;
        });
        waitForPivot(pp2);
        assertEquals(List.of("region"), onEdt(() -> pp2.areaForTest(PivotPanel.Area.ROWS)));
        assertEquals(List.of("Sum of amount"), onEdt(() -> pp2.areaForTest(PivotPanel.Area.VALUES)));
        assertEquals(List.of("product: all"), onEdt(() -> pp2.areaForTest(PivotPanel.Area.FILTERS)),
                "the filter's field is remembered, its values aren't");
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(again));
            return null;
        });
        assertFalse(onEdt(() -> frame.errorsButtonForTest().isVisible()), "no errors in the app");
    }

    /** Waits for a pivot to show what its areas ask for. */
    private void waitForPivot(PivotPanel pp) throws Exception {
        waitFor(() -> {
            try {
                return onEdt(() -> !pp.isRunning() && pp.result() != null && pp.result().spec().equals(pp.spec()));
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the pivot");
    }

    @Test
    @Order(58)
    void comparesTextFiles() throws Exception {
        // two runs of a job: some lines changed, one added, one gone, and every time different
        Path box = Files.createDirectories(dir.resolve("text-compare"));
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            String msg = switch (i % 40) {
                case 7 -> "INFO  Connecting to db-1.prod:5432 as app";
                case 19 -> "WARN  Pool is 80% full (16 of 20)";
                default -> "INFO  Step " + i + " done";
            };
            a.add(String.format(java.util.Locale.ROOT, "2026-10-28 10:%02d:%02d,123 %s", i / 60, i % 60, msg));
            if (i == 50) {
                continue; // gone in the second run
            }
            String m2 = i == 7 ? "INFO  Connecting to db-2.prod:5432 as app" : i == 87 ? "ERROR Step 87 failed: timeout" : msg;
            b.add(String.format(java.util.Locale.ROOT, "2026-10-29 11:%02d:%02d,456 %s", i / 60, i % 60, m2));
            if (i == 30) {
                b.add("2026-10-29 11:00:30,999 INFO  Retrying step 30");
            }
        }
        Path left = box.resolve("run-monday.log");
        Path right = box.resolve("run-tuesday.log");
        Files.write(left, a);
        Files.write(right, b);
        FileCompareTab ct = onEdt(() -> frame.openCompare(left, right));
        waitFor(() -> {
            try {
                return onEdt(() -> ct.text() != null);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the text comparison");
        TextCompareTab tt = onEdt(ct::text);
        assertNull(onEdt(ct::csv), "logs aren't tables: compared as text");
        // every line differs (the times): one changed block
        assertEquals(1, onEdt(() -> tt.result().changes().size()));
        // times masked: what really changed
        onEdt(() -> {
            tt.setOptions(new logsift.core.diff.TextDiff.Options(logsift.core.diff.TextDiff.Spaces.EXACT, false, true,
                    false, false));
            return null;
        });
        logsift.core.diff.TextDiff.Result r = onEdt(tt::result);
        assertEquals(4, r.changes().size(), r.changes().toString());
        assertEquals(1, r.linesAdded());
        assertEquals(1, r.linesRemoved());
        assertEquals(2, r.linesChanged());
        assertTrue(onEdt(tt::statusText).startsWith("4 changes · 2 lines changed, 1 added, 1 removed"),
                onEdt(tt::statusText));
        // F7: the first change, then the next
        onEdt(() -> {
            frame.findMenuItemForTest("Next Difference").doClick();
            return null;
        });
        assertEquals("change 1 of 4", onEdt(tt::positionForTest));
        onEdt(() -> {
            frame.findMenuItemForTest("Next Difference").doClick();
            return null;
        });
        assertEquals("change 2 of 4", onEdt(tt::positionForTest));
        // the right file's name starts where its pane does
        settle();
        int[] at = onEdt(tt::fileRowSplitForTest);
        assertTrue(Math.abs(at[0] - at[1]) <= 1, at[0] + " vs " + at[1]);
        shot("109-text-compare");
        // unchanged stretches folded away
        int all = onEdt(tt::shownRowsForTest);
        onEdt(() -> {
            tt.setFoldForTest(true);
            return null;
        });
        assertTrue(onEdt(tt::shownRowsForTest) < all / 2, onEdt(tt::shownRowsForTest) + " of " + all);
        settle();
        shot("109b-text-compare-folded");
        onEdt(() -> {
            tt.setFoldForTest(false);
            return null;
        });

        // » copies a change to the right; the right file can be saved; undo
        int changed = onEdt(() -> {
            List<logsift.core.diff.TextDiff.Block> bl = tt.result().blocks();
            for (int k = 0; k < bl.size(); k++) {
                if (bl.get(k).kind() == logsift.core.diff.Edit.Kind.REPLACE && bl.get(k).l1() == 7) {
                    return k;
                }
            }
            return -1;
        });
        assertTrue(changed >= 0);
        onEdt(() -> {
            tt.apply(changed, 1);
            return null;
        });
        assertTrue(onEdt(() -> tt.edited(1)));
        assertEquals(3, onEdt(() -> tt.result().changes().size()), "one change fewer");
        assertTrue(onEdt(() -> tt.linesForTest(1).get(7)).endsWith("db-1.prod:5432 as app"));
        assertTrue(onEdt(() -> tt.saveEnabledForTest(1)));
        assertTrue(onEdt(tt::title).endsWith(" •"), "edited, not saved");
        onEdt(() -> {
            tt.undoForTest();
            return null;
        });
        assertEquals(4, onEdt(() -> tt.result().changes().size()));
        onEdt(() -> {
            tt.apply(changed, 1);
            tt.saveForTest(1);
            return null;
        });
        assertTrue(Files.readAllLines(right).get(7).endsWith("db-1.prod:5432 as app"), "saved");
        assertEquals(b.size(), Files.readAllLines(right).size());
        assertFalse(onEdt(() -> tt.edited(1)));

        // Choose Files: the browsers in the panes' place, the button stays put
        java.awt.Point closedAt = onEdt(() -> SwingUtilities.convertPoint(ct.filesButtonForTest(), 0, 0, ct));
        onEdt(() -> {
            ct.filesButtonForTest().doClick();
            return null;
        });
        assertTrue(onEdt(ct::chooserShownForTest));
        settle();
        assertEquals(closedAt, onEdt(() -> SwingUtilities.convertPoint(ct.filesButtonForTest(), 0, 0, ct)));
        onEdt(() -> {
            ct.filesButtonForTest().doClick();
            return null;
        });
        assertFalse(onEdt(ct::chooserShownForTest));

        // two binary files: whether they are the same
        Path bin1 = box.resolve("a.bin");
        Path bin2 = box.resolve("b.bin");
        Files.write(bin1, new byte[] {1, 0, 2, 3});
        Files.write(bin2, new byte[] {1, 0, 2, 4});
        FileCompareTab bt = onEdt(() -> frame.openCompare(bin1, bin2));
        waitFor(() -> {
            try {
                return onEdt(() -> bt.chooserStatusForTest().startsWith("Binary files"));
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the binary note");
        assertTrue(onEdt(bt::chooserStatusForTest).contains("they differ"));

        // CSV files: as a table, or as text, and back
        Path c1 = box.resolve("prices-old.csv");
        Path c2 = box.resolve("prices-new.csv");
        Files.writeString(c1, "sku,price\nA,1\nB,2\n");
        Files.writeString(c2, "sku,price\nB,2\nA,3\n");
        FileCompareTab cc = onEdt(() -> frame.openCompare(c1, c2));
        CsvCompareTab table = waitForCompare(null);
        onEdt(() -> {
            table.compareAsTextForTest();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> cc.text() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the same files as text");
        assertNull(onEdt(cc::csv));
        assertEquals(4, onEdt(() -> cc.text().result().rows().size()), "the header, A,1 gone, B,2, A,3 added");
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(cc));
            frame.closeTab(frame.tabsForTest().indexOfComponent(bt));
            frame.tabsForTest().setSelectedComponent(ct);
            return null;
        });
        // dark mode
        onEdt(() -> {
            frame.setDark(true);
            return null;
        });
        settle();
        shot("109c-text-compare-dark");
        onEdt(() -> {
            frame.setDark(false);
            frame.closeTab(frame.tabsForTest().indexOfComponent(ct));
            return null;
        });
        assertFalse(onEdt(() -> frame.errorsButtonForTest().isVisible()), "no errors in the app");
    }

    @Test
    @Order(59)
    void mergesThreeWays() throws Exception {
        // a config changed on two branches: some changes on one side, one conflict
        Path box = Files.createDirectories(dir.resolve("merge"));
        List<String> base = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            base.add("setting." + i + " = " + i);
        }
        List<String> ours = new ArrayList<>(base);
        List<String> theirs = new ArrayList<>(base);
        ours.set(3, "setting.3 = 300   # ours");
        theirs.set(30, "setting.30 = 3000   # theirs");
        ours.set(20, "setting.20 = twenty (ours)");
        theirs.set(20, "setting.20 = 20.0 (theirs)");
        theirs.add("setting.40 = 40   # new in theirs");
        Path local = box.resolve("app.conf.LOCAL");
        Path remote = box.resolve("app.conf.REMOTE");
        Path basePath = box.resolve("app.conf.BASE");
        Path merged = box.resolve("app.conf");
        Files.write(local, ours);
        Files.write(remote, theirs);
        Files.write(basePath, base);
        Files.writeString(merged, "<<<<<<< git's own markers\n");
        int[] code = {-9};
        MergeTab m = onEdt(() -> frame.openMerge(local, remote, basePath, merged, c -> code[0] = c));
        assertEquals(1L, onEdt(() -> m.mergeForTest().conflicts()));
        assertEquals("1 of 1 conflict left", onEdt(m::statusForTest));
        assertEquals("Merge: app.conf (1 conflict)", onEdt(m::title));
        // non-conflicting changes are taken already; the conflict waits (the base's line meanwhile)
        List<String> now = onEdt(m::result);
        assertTrue(now.contains("setting.3 = 300   # ours"));
        assertTrue(now.contains("setting.30 = 3000   # theirs"));
        assertTrue(now.contains("setting.40 = 40   # new in theirs"));
        assertTrue(now.contains("<<<<<<< yours"), "unresolved: git's markers");
        settle();
        shot("110-merge-conflict");
        // F7 goes to the conflict; » takes yours, « then adds theirs after it
        int conflict = onEdt(() -> {
            List<logsift.core.diff.Merge3.Chunk> cs = m.mergeForTest().chunks();
            for (int k = 0; k < cs.size(); k++) {
                if (cs.get(k).kind() == logsift.core.diff.Merge3.Kind.CONFLICT) {
                    return k;
                }
            }
            return -1;
        });
        onEdt(() -> {
            frame.findMenuItemForTest("Next Difference").doClick();
            return null;
        });
        assertEquals(conflict, onEdt(m::currentForTest));
        onEdt(() -> {
            m.take(conflict, 0);
            return null;
        });
        assertEquals("All conflicts resolved", onEdt(m::statusForTest));
        onEdt(() -> {
            m.take(conflict, 2);
            return null;
        });
        assertEquals(logsift.core.diff.Merge3.Choice.OURS_THEN_THEIRS, onEdt(() -> m.choicesForTest().get(conflict)));
        List<String> both = onEdt(m::result);
        int at = both.indexOf("setting.20 = twenty (ours)");
        assertEquals("setting.20 = 20.0 (theirs)", both.get(at + 1));
        settle();
        shot("110b-merge-resolved");
        onEdt(() -> {
            m.undoForTest();
            return null;
        });
        assertEquals(logsift.core.diff.Merge3.Choice.OURS, onEdt(() -> m.choicesForTest().get(conflict)));
        // by hand
        onEdt(() -> {
            m.setEdited(conflict, "setting.20 = 20   # agreed");
            return null;
        });
        assertTrue(onEdt(m::result).contains("setting.20 = 20   # agreed"));
        // Save: the file is written, git hears 0, the tab closes
        onEdt(() -> {
            m.save();
            return null;
        });
        assertEquals(0, code[0]);
        assertTrue(Files.readAllLines(merged).contains("setting.20 = 20   # agreed"));
        assertEquals(41, Files.readAllLines(merged).size());
        assertEquals(-1, onEdt(() -> frame.tabsForTest().indexOfComponent(m)), "closed");

        // given up (Abort, or the tab closed): git hears 1 and the file stays as it was
        Files.writeString(merged, "untouched\n");
        int[] code2 = {-9};
        MergeTab m2 = onEdt(() -> frame.openMerge(local, remote, basePath, merged, c -> code2[0] = c));
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(m2));
            return null;
        });
        assertEquals(1, code2[0]);
        assertEquals("untouched\n", Files.readString(merged));
        assertFalse(onEdt(() -> frame.errorsButtonForTest().isVisible()), "no errors in the app");
    }

    @Test
    @Order(60)
    void comparesFolders() throws Exception {
        // a release folder and the next one: a changed config, a new file, one gone, logs the same
        Path base = Files.createDirectories(dir.resolve("folders"));
        Path a = base.resolve("release-1.0");
        Path b = base.resolve("release-1.1");
        for (Path p : List.of(a, b)) {
            Files.createDirectories(p.resolve("conf"));
            Files.createDirectories(p.resolve("logs"));
            Files.createDirectories(p.resolve("lib"));
            Files.writeString(p.resolve("README.txt"), "the app\n");
            for (int i = 0; i < 6; i++) {
                Files.writeString(p.resolve("lib/part-" + i + ".jar"), "jar " + i);
            }
            Files.writeString(p.resolve("logs/app.log"), "2026-10-28 10:00:00 INFO started\n");
        }
        Files.writeString(a.resolve("conf/app.conf"), "port = 8080\nthreads = 8\n");
        Files.writeString(b.resolve("conf/app.conf"), "port = 8080\nthreads = 16\n");
        Files.writeString(a.resolve("conf/legacy.conf"), "old = yes\n");
        Files.writeString(b.resolve("conf/feature.conf"), "new = yes\n");
        Files.writeString(b.resolve("lib/part-6.jar"), "jar 6");
        FileCompareTab ct = onEdt(() -> frame.openCompare(null, null));
        onEdt(() -> {
            ct.navigatorForTest(0).reveal(a);
            ct.navigatorForTest(1).reveal(b);
            return null;
        });
        waitForNavigator(onEdt(() -> ct.navigatorForTest(0)), a);
        waitForNavigator(onEdt(() -> ct.navigatorForTest(1)), b);
        assertTrue(onEdt(() -> ct.compareButtonForTest().isEnabled()), "two folders can be compared");
        onEdt(() -> {
            ct.compareButtonForTest().doClick();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> ct.folder() != null && !ct.folder().isRunning() && ct.folder().result() != null);
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the folder comparison");
        FolderCompareTab ft = onEdt(ct::folder);
        logsift.core.diff.FolderDiff.Result r = onEdt(ft::result);
        assertEquals(12, r.files());
        assertEquals(1, r.different());
        assertEquals(1, r.leftOnly());
        assertEquals(2, r.rightOnly());
        // at first: the differences, in their folders (opened)
        assertEquals(List.of("conf ≠", "conf/app.conf ≠", "conf/feature.conf ▶", "conf/legacy.conf ◀", "lib ≠",
                "lib/part-6.jar ▶"), onEdt(ft::rowsForTest));
        assertEquals("Differences  4", onEdt(() -> ft.showTextForTest(0)));
        assertEquals("Compare: release-1.0 ↔ release-1.1",
                onEdt(() -> frame.tabsForTest().getTitleAt(frame.tabsForTest().indexOfComponent(ct))));
        settle();
        shot("111-folder-compare");
        onEdt(() -> {
            ft.setShowForTest(1); // everything
            ft.expandAllForTest();
            return null;
        });
        assertEquals(15, onEdt(() -> ft.rowsForTest().size()), "3 folders and 12 files");
        settle();
        shot("111b-folder-compare-everything");
        onEdt(() -> {
            ft.setShowForTest(0);
            ft.setSearchForTest("app");
            return null;
        });
        assertEquals(List.of("conf ≠", "conf/app.conf ≠"), onEdt(ft::rowsForTest));
        // double-click two files: compared in a tab of their own (text)
        onEdt(() -> {
            ft.activateForTest("conf/app.conf");
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.tabsForTest().getSelectedComponent() instanceof FileCompareTab f
                        && f != ct && f.text() != null);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the two files compared");
        FileCompareTab files = onEdt(() -> (FileCompareTab) frame.tabsForTest().getSelectedComponent());
        assertEquals(1, onEdt(() -> files.text().result().changes().size()));
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(files));
            frame.closeTab(frame.tabsForTest().indexOfComponent(ct));
            return null;
        });
        assertFalse(onEdt(() -> frame.errorsButtonForTest().isVisible()), "no errors in the app");
    }

    @Test
    @Order(56)
    void comparesFromTheCompareTab() throws Exception {
        // the left file is in a zip inside a zip (old/exports.zip in bundle.zip); the right one is on disk
        Path box = Files.createDirectories(dir.resolve("compare-tab"));
        StringBuilder older = new StringBuilder("sku,name,stock\n");
        List<String> newer = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            older.append(String.format("SKU-%02d,item %d,%d%n", i, i, i * 3));
            if (i != 17) {
                newer.add(String.format("SKU-%02d,item %d,%d", i, i, i % 10 == 0 ? i * 3 + 1 : i * 3));
            }
        }
        newer.add("SKU-41,item 41,5");
        java.util.Collections.shuffle(newer, new Random(9));
        Path right = box.resolve("stock-today.csv");
        Files.writeString(right, "sku,name,stock\n" + String.join("\n", newer) + "\n");
        java.io.ByteArrayOutputStream inner = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(inner)) {
            z.putNextEntry(new java.util.zip.ZipEntry("data/stock.csv"));
            z.write(older.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Path bundle = box.resolve("bundle.zip");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(bundle))) {
            z.putNextEntry(new java.util.zip.ZipEntry("old/exports.zip"));
            z.write(inner.toByteArray());
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("notes.txt"));
            z.write("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Path inZip = bundle.resolve("old/exports.zip/data/stock.csv");

        // the start page's Compare Files… opens a Compare tab, not a pop-up
        onEdt(() -> {
            frame.openStartTab();
            return null;
        });
        StartPage start = onEdt(() -> (StartPage) frame.tabsForTest().getSelectedComponent());
        onEdt(() -> {
            start.actionForTest("Compare Files…").doClick();
            return null;
        });
        FileCompareTab ct = onEdt(frame::currentCompareTabForTest);
        assertNotNull(ct, "a Compare tab");
        assertEquals("Compare", onEdt(() -> frame.tabsForTest().getTitleAt(frame.tabsForTest().getSelectedIndex())));
        assertTrue(onEdt(ct::chooserShownForTest), "the file browsers are shown");
        assertFalse(onEdt(() -> ct.compareButtonForTest().isEnabled()), "nothing chosen yet");

        // the left side: the folder, then into the zip, the zip in it, and its folder
        FileNavigator left = onEdt(() -> ct.navigatorForTest(0));
        onEdt(() -> {
            left.pathFieldForTest().setText(box.toString());
            left.pathFieldForTest().postActionEvent();
            return null;
        });
        waitForNavigator(left, box);
        waitFor(() -> {
            try {
                return onEdt(() -> left.childrenForTest().equals(List.of("bundle.zip", "stock-today.csv")));
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the folder's files");
        onEdt(() -> {
            left.pathFieldForTest().setText(bundle.toString());
            left.pathFieldForTest().postActionEvent();
            return null;
        });
        waitForNavigator(left, bundle);
        waitFor(() -> {
            try {
                return onEdt(() -> left.childrenForTest().equals(List.of("old", "notes.txt")));
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the zip opened like a folder");
        onEdt(() -> {
            left.reveal(inZip);
            return null;
        });
        waitForNavigator(left, inZip);
        assertTrue(onEdt(() -> left.selected().inZip()));
        assertFalse(onEdt(() -> ct.compareButtonForTest().isEnabled()), "one side only");
        // the right side: a file dropped on it
        onEdt(() -> {
            javax.swing.JTree t = ct.navigatorForTest(1).treeForTest();
            javax.swing.TransferHandler.TransferSupport ts = new javax.swing.TransferHandler.TransferSupport(t,
                    FileDropTest.files(right));
            assertTrue(t.getTransferHandler().importData(ts));
            return null;
        });
        waitForNavigator(onEdt(() -> ct.navigatorForTest(1)), right);
        assertTrue(onEdt(() -> ct.compareButtonForTest().isEnabled()), "a file on each side");
        assertEquals("stock.csv ↔ stock-today.csv", onEdt(ct::chooserStatusForTest));
        assertTrue(onEdt(ct::compareHighlightedForTest), "two files chosen: Compare stands out");
        settle();
        shot("107-compare-tab-browsers");

        onEdt(() -> {
            ct.compareButtonForTest().doClick();
            return null;
        });
        CsvCompareTab tab = waitForCompare(null);
        assertFalse(onEdt(ct::chooserShownForTest), "the browsers fold away for the results");
        assertFalse(onEdt(ct::compareHighlightedForTest), "compared: nothing to click");
        assertTrue(onEdt(() -> ct.filesButtonForTest().isShowing()), "Choose Files in the comparison's row");
        assertEquals("Choose Files", onEdt(() -> ct.filesButtonForTest().getText()));
        assertEquals("Compare: stock.csv ↔ stock-today.csv",
                onEdt(() -> frame.tabsForTest().getTitleAt(frame.tabsForTest().getSelectedIndex())));
        assertEquals(List.of(), onEdt(tab::keysForTest), "no key chosen by default");
        assertEquals("Use sku as Key", onEdt(() -> tab.useSuggestedForTest().getText()));
        useSuggestedKey(tab);
        logsift.core.diff.TableDiffResult r = onEdt(tab::result);
        assertEquals(1, r.count(logsift.core.diff.DiffStatus.ADDED));
        assertEquals(1, r.count(logsift.core.diff.DiffStatus.REMOVED));
        assertEquals(4, r.count(logsift.core.diff.DiffStatus.CHANGED));
        assertEquals(inZip, onEdt(() -> tab.sideForTest(0).open()), "the file in the zip in the zip");
        settle();
        shot("107b-compare-tab-results");

        // + Leave Out: a column's changes don't count; it is still shown, gray, at the end
        // the keys' list: right below its button, with what is ticked, and a filter
        ColumnPicker keyList = onEdt(tab::openKeyPickerForTest);
        assertTrue(onEdt(keyList::isShowingForTest));
        assertEquals(List.of("sku", "name", "stock"), onEdt(keyList::shownForTest));
        onEdt(() -> {
            keyList.closeForTest();
            return null;
        });
        assertTrue(onEdt(tab::result) == r, "nothing changed: not compared again");
        // the list of columns to leave out: filter, Enter ticks the match, closing compares
        ColumnPicker outList = onEdt(tab::openLeaveOutPickerForTest);
        assertEquals(List.of("name", "stock"), onEdt(outList::shownForTest), "keys can't be left out");
        onEdt(() -> {
            outList.setFilterForTest("sto");
            return null;
        });
        assertEquals(List.of("stock"), onEdt(outList::shownForTest));
        onEdt(() -> {
            outList.enterForTest();
            return null;
        });
        settle();
        shotOf(onEdt(outList::popupForTest), "107h-compare-leave-out-list");
        assertTrue(onEdt(tab::result) == r, "not compared while the list is open");
        onEdt(() -> {
            outList.closeForTest();
            return null;
        });
        waitForNewResult(tab, r, "comparing without stock");
        assertEquals(List.of("stock"), onEdt(tab::leftOutForTest));
        assertEquals(0, onEdt(tab::result).count(logsift.core.diff.DiffStatus.CHANGED), "stock no longer counts");
        assertEquals(List.of("Status", "Line (left → right)", "sku (key)", "name", "stock (not compared)"),
                onEdt(tab::columnsForTest));
        settle();
        shot("107e-compare-left-out");
        logsift.core.diff.TableDiffResult without = onEdt(tab::result);
        onEdt(() -> {
            tab.compareAgainForTest("stock");
            return null;
        });
        waitForNewResult(tab, without, "comparing stock again");
        assertEquals(List.of(), onEdt(tab::leftOutForTest));
        logsift.core.diff.TableDiffResult again = onEdt(tab::result);
        assertEquals(4, again.count(logsift.core.diff.DiffStatus.CHANGED));

        // Columns ▾ only shows or hides: nothing is compared again
        onEdt(() -> {
            tab.showColumnForTest("name", false);
            return null;
        });
        assertEquals(List.of("Status", "Line (left → right)", "sku (key)", "stock"), onEdt(tab::columnsForTest));
        assertTrue(onEdt(tab::result) == again, "hiding a column doesn't compare again");
        onEdt(() -> {
            tab.showColumnForTest("name", true);
            return null;
        });
        assertEquals(List.of("Status", "Line (left → right)", "sku (key)", "name", "stock"), onEdt(tab::columnsForTest));

        // side by side: the left file's rows next to the right file's, one line per key, every row
        onEdt(() -> {
            JTable t = tab.tableForTest();
            t.setRowSelectionInterval(1, 1);
            return null;
        });
        int pickedItem = onEdt(() -> tab.shownForTest()[1]);
        onEdt(() -> {
            tab.setSideBySideForTest(true);
            return null;
        });
        assertEquals("Everything", onEdt(() -> tab.showForTest().getSelectedItem()), "side by side shows every row");
        assertEquals(again.size(), onEdt(tab::shownForTest).length);
        assertEquals(pickedItem, onEdt(() -> tab.shownForTest()[tab.tableForTest().getSelectedRow()]),
                "the selected row stays selected");
        // the strip by the scroll bar goes to the difference nearest a click
        onEdt(() -> {
            tab.clickChangeStripForTest(0.5);
            return null;
        });
        assertTrue(onEdt(() -> again.status(tab.shownForTest()[tab.tableForTest().getSelectedRow()]).isDifference()));
        // the right file's name starts where the right table does, and follows the divider
        settle();
        int[] at0 = onEdt(tab::fileRowSplitForTest);
        assertTrue(Math.abs(at0[0] - at0[1]) <= 1, "names over their tables: " + at0[0] + " vs " + at0[1]);

        // cells, rows and columns can be selected; Cmd/Ctrl+C copies them
        onEdt(() -> {
            tab.selectCellForTest(0, 2, 2, false); // left table, row 3, sku
            tab.selectCellForTest(0, 3, 3, true);  // to row 4, name
            return null;
        });
        String twoByTwo = onEdt(() -> tab.selectionTextForTest(0));
        assertEquals(2, twoByTwo.split("\n").length, twoByTwo);
        assertEquals(2, twoByTwo.split("\n")[0].split("\t", -1).length, twoByTwo);
        assertEquals(0, onEdt(() -> tab.sideTableForTest(1).getSelectedColumnCount()),
                "a cell on the left: nothing selected on the right");
        settle();
        shot("107j-compare-cells-selected");
        onEdt(() -> {
            tab.selectColumnForTest(0, 4, false); // the stock column, every row
            return null;
        });
        String column = onEdt(() -> tab.selectionTextForTest(0));
        assertEquals(onEdt(() -> tab.shownForTest().length) + 1, column.split("\n").length, "with its title");
        assertEquals("stock", column.split("\n")[0]);
        settle();
        shot("107k-compare-column-selected");
        onEdt(() -> {
            tab.selectCellForTest(1, 4, 0, false); // the line on the right: the whole row, both sides
            return null;
        });
        assertEquals(onEdt(() -> tab.sideTableForTest(0).getColumnCount()),
                onEdt(() -> tab.sideTableForTest(0).getSelectedColumnCount()));
        assertEquals(onEdt(() -> tab.sideTableForTest(1).getColumnCount()),
                onEdt(() -> tab.sideTableForTest(1).getSelectedColumnCount()));
        assertEquals(4, onEdt(() -> tab.selectionTextForTest(1).split("\t", -1).length));
        assertEquals(List.of("Status", "Line", "sku (key)", "name", "stock"), onEdt(() -> tab.sideColumnsForTest(0)));
        assertEquals(List.of("Line", "sku (key)", "name", "stock"), onEdt(() -> tab.sideColumnsForTest(1)));
        int[] sideShown = onEdt(tab::shownForTest);
        int addedRow = -1;
        int changedRow = -1;
        for (int i = 0; i < sideShown.length; i++) {
            logsift.core.diff.DiffStatus st = again.status(sideShown[i]);
            if (st == logsift.core.diff.DiffStatus.ADDED) {
                addedRow = i;
            } else if (st == logsift.core.diff.DiffStatus.CHANGED && changedRow < 0) {
                changedRow = i;
            }
        }
        int ar = addedRow;
        int cr = changedRow;
        assertEquals("", onEdt(() -> tab.sideCellTextForTest(0, ar, 2)), "an added row: nothing on the left");
        assertEquals("SKU-41", onEdt(() -> tab.sideCellTextForTest(1, ar, 1)));
        String key = onEdt(() -> tab.sideCellTextForTest(0, cr, 2));
        assertEquals(key, onEdt(() -> tab.sideCellTextForTest(1, cr, 1)), "a changed row: one line, both files");
        int n = Integer.parseInt(key.substring(4));
        assertEquals(String.valueOf(n * 3), onEdt(() -> tab.sideCellTextForTest(0, cr, 4)));
        assertEquals(String.valueOf(n * 3 + 1), onEdt(() -> tab.sideCellTextForTest(1, cr, 3)));
        settle();
        shot("107d-compare-side-by-side");
        onEdt(() -> {
            tab.showForTest().setSelectedIndex(6); // everything
            return null;
        });
        settle();
        shot("107f-compare-side-by-side-everything");
        onEdt(() -> {
            tab.showForTest().setSelectedIndex(0);
            return null;
        });
        settle();
        shot("107i-compare-side-by-side-differences");
        onEdt(() -> {
            tab.showForTest().setSelectedIndex(6); // remembered for side by side
            tab.setSideBySideForTest(false);
            return null;
        });
        assertEquals("All differences", onEdt(() -> tab.showForTest().getSelectedItem()),
                "the table's own Show comes back");
        onEdt(() -> {
            tab.setSideBySideForTest(true);
            return null;
        });
        assertEquals("Everything", onEdt(() -> tab.showForTest().getSelectedItem()));
        onEdt(() -> {
            tab.setSideBySideForTest(false);
            return null;
        });

        // an option changed: the comparison's Compare stands out until it is clicked
        assertFalse(onEdt(tab::compareHighlightedForTest));
        onEdt(() -> {
            tab.clickTrimForTest();
            return null;
        });
        assertTrue(onEdt(tab::compareHighlightedForTest), "an option changed: Compare stands out");
        settle();
        shot("107g-compare-option-changed");
        logsift.core.diff.TableDiffResult beforeTrim = onEdt(tab::result);
        onEdt(() -> {
            tab.runCompare();
            return null;
        });
        waitForNewResult(tab, beforeTrim, "comparing with the option");
        assertFalse(onEdt(tab::compareHighlightedForTest), "compared again: back to normal");
        logsift.core.diff.TableDiffResult withTrim = onEdt(tab::result);
        onEdt(() -> {
            tab.clickTrimForTest();
            tab.runCompare();
            return null;
        });
        waitForNewResult(tab, withTrim, "comparing with trim again");

        // Choose Files shows the browsers again, in the table's place; the button stays where it is
        java.awt.Point closedAt = onEdt(() -> SwingUtilities.convertPoint(ct.filesButtonForTest(), 0, 0, ct));
        onEdt(() -> {
            ct.filesButtonForTest().doClick();
            return null;
        });
        assertTrue(onEdt(ct::chooserShownForTest));
        settle();
        assertEquals("Close Files", onEdt(() -> ct.filesButtonForTest().getText()));
        java.awt.Point openAt = onEdt(() -> SwingUtilities.convertPoint(ct.filesButtonForTest(), 0, 0, ct));
        assertEquals(closedAt, openAt, "the button doesn't move");
        assertTrue(onEdt(() -> ct.navigatorForTest(0).isShowing()), "the browsers are shown");
        assertFalse(onEdt(() -> tab.tableForTest().isShowing()), "in the table's place");
        assertFalse(onEdt(ct::compareHighlightedForTest), "the same files: nothing new to compare");
        onEdt(() -> {
            ct.navigatorForTest(1).reveal(inZip);
            return null;
        });
        waitForNavigator(onEdt(() -> ct.navigatorForTest(1)), inZip);
        assertTrue(onEdt(ct::compareHighlightedForTest), "another file chosen: Compare stands out");
        settle();
        shot("107c-compare-tab-files-again");
        // Close Files: the table again, the same comparison
        logsift.core.diff.TableDiffResult kept = onEdt(tab::result);
        onEdt(() -> {
            ct.filesButtonForTest().doClick();
            return null;
        });
        assertFalse(onEdt(ct::chooserShownForTest));
        assertTrue(onEdt(() -> tab.tableForTest().isShowing()));
        assertTrue(onEdt(tab::result) == kept, "closing the browsers doesn't compare");
        assertFalse(onEdt(ct::compareHighlightedForTest));
        // the other file chosen, then Compare: it compares that file, keeping the key
        onEdt(() -> {
            ct.filesButtonForTest().doClick();
            return null;
        });
        onEdt(() -> {
            tab.compareButtonForTest().doClick();
            return null;
        });
        waitForNewResult(tab, kept, "comparing the other file");
        assertFalse(onEdt(ct::chooserShownForTest), "compared: the table again");
        assertEquals(inZip, onEdt(() -> tab.sideForTest(1).open()));
        assertEquals(List.of("sku"), onEdt(tab::keysForTest), "the key is kept");

        // two files dropped on the window: compare them, or open each in a tab (asked, unless chosen)
        Path other = Files.createDirectories(dir.resolve("drop-two")).resolve("stock-copy.csv");
        Files.writeString(other, "sku,name,stock\nSKU-01,item 1,3\n");
        List<String> asked = new ArrayList<>();
        int tabs0 = onEdt(() -> frame.tabsForTest().getTabCount());
        onEdt(() -> {
            frame.askTwoFiles = (a, b) -> {
                asked.add(a.getFileName() + " " + b.getFileName());
                return null; // cancelled
            };
            frame.droppedTwo(right, other);
            return null;
        });
        assertEquals(List.of("stock-today.csv stock-copy.csv"), asked);
        assertEquals(tabs0, onEdt(() -> frame.tabsForTest().getTabCount()), "cancelled: nothing opens");
        onEdt(() -> {
            frame.askTwoFiles = (a, b) -> "tabs";
            frame.droppedTwo(right, other);
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> frame.tabsForTest().getTabCount() == tabs0 + 2);
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "two tabs");
        onEdt(() -> {
            frame.askTwoFiles = (a, b) -> "compare";
            frame.droppedTwo(right, other);
            return null;
        });
        CsvCompareTab dropped = waitForCompare(tab);
        assertEquals(other, onEdt(() -> dropped.sideForTest(1).open()));
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(compareTabOf(dropped)));
            frame.closeTab(frame.tabsForTest().getTabCount() - 1);
            frame.closeTab(frame.tabsForTest().getTabCount() - 1);
            frame.tabsForTest().setSelectedComponent(ct);
            return null;
        });
        onEdt(() -> {
            frame.closeTab(frame.tabsForTest().indexOfComponent(ct));
            return null;
        });
        assertFalse(onEdt(() -> frame.errorsButtonForTest().isVisible()), "no errors in the app while comparing");
    }

    /** Waits for a file browser to show and select {@code p}. */
    private void waitForNavigator(FileNavigator n, Path p) throws Exception {
        waitFor(() -> {
            try {
                return onEdt(() -> n.selected() != null && n.selected().path().equals(p));
            } catch (Exception e) {
                return false;
            }
        }, 20_000, "the browser showing " + p);
    }

    private static FileCompareTab compareTabOf(CsvCompareTab t) {
        return (FileCompareTab) SwingUtilities.getAncestorOfClass(FileCompareTab.class, t);
    }

    /** Waits for {@code tab} to have compared again (a result other than {@code before}). */
    private void waitForNewResult(CsvCompareTab tab, logsift.core.diff.TableDiffResult before, String what)
            throws Exception {
        waitFor(() -> {
            try {
                return onEdt(() -> !tab.isBusy() && tab.result() != null && tab.result() != before);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, what);
    }

    /** Clicks the suggested key's button and waits for the comparison by it. */
    private void useSuggestedKey(CsvCompareTab tab) throws Exception {
        logsift.core.diff.TableDiffResult before = onEdt(tab::result);
        onEdt(() -> {
            tab.useSuggestedForTest().doClick();
            return null;
        });
        waitForNewResult(tab, before, "comparing by the suggested key");
    }

    /** Waits for a comparison tab other than {@code not} to be shown and done. */
    private CsvCompareTab waitForCompare(CsvCompareTab not) throws Exception {
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentCsvCompareTab() != null && frame.currentCsvCompareTab() != not
                        && !frame.currentCsvCompareTab().isBusy() && frame.currentCsvCompareTab().result() != null);
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "the comparison");
        return onEdt(frame::currentCsvCompareTab);
    }

    /** Waits for {@code tab} to have compared these files. */
    private void waitForCompare(CsvCompareTab tab, String leftName, String rightName) throws Exception {
        waitFor(() -> {
            try {
                return onEdt(() -> !tab.isBusy() && tab.result() != null
                        && tab.result().left().name().equals(leftName) && tab.result().right().name().equals(rightName));
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "comparing " + leftName + " with " + rightName);
    }

    private LogTab waitForTab(String title) throws Exception {
        waitFor(() -> {
            try {
                return onEdt(() -> frame.currentTab() != null && frame.currentTab().title().equals(title));
            } catch (Exception e) {
                return false;
            }
        }, 30_000, "the tab " + title);
        return onEdt(frame::currentTab);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Runs a profile in the tab and waits for it. */
    private static logsift.jvm.Profile profileRun(ProfileTab prof, logsift.jvm.Profiler.Mode mode, int seconds)
            throws Exception {
        int before = onEdt(prof::runCountForTest);
        onEdt(() -> {
            prof.setForTest(mode, null, seconds);
            prof.startProfiling();
            return null;
        });
        waitFor(() -> {
            try {
                return onEdt(() -> prof.runCountForTest() > before && !prof.isRunning());
            } catch (Exception e) {
                return false;
            }
        }, 90_000, "the " + mode + " profile");
        return onEdt(prof::profile);
    }

    private static String details(LogTab tab) {
        try {
            return onEdt(() -> tab.detailsPanel().textForTest()).replace("\r\n", "\n");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Clicks the middle of a tab's title the way the mouse does: the press goes to the deepest
     * component there that listens to the mouse (a title that listens would keep it from the tabs).
     */
    private static void clickTabTitle(javax.swing.JTabbedPane tabs, java.awt.Component tab) {
        java.awt.Rectangle r = tabs.getBoundsAt(tabs.indexOfComponent(tab));
        int x = r.x + r.width / 2;
        int y = r.y + r.height / 2;
        java.awt.Component c = SwingUtilities.getDeepestComponentAt(tabs, x, y);
        while (c != null && c != tabs && c.getMouseListeners().length == 0) {
            c = c.getParent();
        }
        java.awt.Point p = SwingUtilities.convertPoint(tabs, x, y, c);
        long now = System.currentTimeMillis();
        for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            int mods = id == MouseEvent.MOUSE_PRESSED ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK : 0;
            c.dispatchEvent(new MouseEvent(c, id, now, mods, p.x, p.y, 1, false, MouseEvent.BUTTON1));
        }
    }

    /**
     * Waits for the server folder dialog to show a listing (30 s: a loaded build server is slow
     * over SFTP); if it doesn't, says what the dialog shows instead.
     */
    private static void waitForListing(RemoteDialog dlg, java.util.function.Supplier<Boolean> shown, String what)
            throws Exception {
        try {
            waitFor(() -> {
                try {
                    return onEdt(shown::get);
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, what);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "; the dialog shows " + onEdt(dlg::folderForTest) + ", "
                    + onEdt(dlg::namesForTest) + ", listings " + onEdt(dlg::listingsForTest) + ", status: "
                    + onEdt(dlg::statusForTest), e);
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

    @Test
    @Order(50)
    void agentsAreSetUpInSettings() throws Exception {
        // where logsift-mcp is, what to paste for Copilot, and what is masked
        Path launcher = dir.resolve("Logsift App").resolve("logsift-mcp");
        System.setProperty("logsift.mcpLauncher", launcher.toString());
        Path props = logsift.agent.AgentSettings.file(AppState.home());
        try {
            SettingsDialog before = SettingsDialog.lastForTest();
            javax.swing.SwingUtilities.invokeLater(() -> frame.showSettings(SettingsDialog.AGENTS));
            waitFor(() -> SettingsDialog.lastForTest() != before && SettingsDialog.lastForTest() != null
                    && SettingsDialog.lastForTest().isShowing(), 10_000, "Settings");
            SettingsDialog sd = SettingsDialog.lastForTest();
            AgentsPanel ap = sd.agents();
            String vscode = onEdt(ap::snippetText);
            assertTrue(vscode.contains("\"servers\"") && vscode.contains("\"type\": \"stdio\""), vscode);
            assertTrue(vscode.contains(logsift.agent.JsonWriter.write(launcher.toString())), vscode);
            onEdt(() -> {
                ap.selectAgent(logsift.agent.AgentSettings.Agent.COPILOT_CLI);
                return null;
            });
            String cli = onEdt(ap::snippetText);
            assertTrue(cli.contains("\"mcpServers\"") && cli.contains("\"tools\": [\"*\"]"), cli);
            assertTrue(onEdt(() -> ap.secretsBox().isSelected()), "secrets are masked by default");
            settle();
            shotOf(sd.getRootPane(), "80-settings-agents");
            onEdt(() -> {
                ap.secretsBox().doClick(); // off, saved for logsift-mcp
                return null;
            });
            assertTrue(Files.readString(props).contains("mask.off=true"), Files.readString(props));
            onEdt(() -> {
                ap.secretsBox().doClick();
                sd.dispose();
                return null;
            });
            assertTrue(logsift.agent.AgentSettings.load(AppState.home()).masking().custom().isEmpty());
            assertTrue(logsift.agent.AgentSettings.load(AppState.home()).masking().secrets(), "back on");
        } finally {
            System.clearProperty("logsift.mcpLauncher");
            Files.deleteIfExists(props);
        }
    }

    private static String mbPerSec(long bytes, long ms) {
        return ms <= 0 ? "-" : String.format("%,.0f MB/s", bytes / 1048576.0 / (ms / 1000.0));
    }
}
