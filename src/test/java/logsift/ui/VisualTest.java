package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import javax.swing.JTabbedPane;
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
        // A folder like a real log directory: plain, rotated .gz and a .zip of old logs
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
        LogGenerator.generate(tmp, size, 4);
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(folder.resolve("archive.zip")))) {
            z.putNextEntry(new java.util.zip.ZipEntry("2026-10/old.log"));
            Files.copy(tmp, z);
        }
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
        Map<String, Long> actual = new java.util.TreeMap<>();
        for (var r : fs.results()) {
            actual.put(folder.relativize(r.file()).toString().replace('\\', '/')
                    + (r.entry() == null ? "" : "!" + r.entry()), r.hitCount());
        }
        assertEquals(expected, actual, "hits per file");
        summary.put("Folder search (4 files, 48 MB)", String.format("%,d lines in %d ms",
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

        // double-click a hit inside the zip: unpacks it and opens the file at that line
        var zipResult = fs.results().stream().filter(r -> r.entry() != null).findFirst().orElseThrow();
        long zipLine = zipResult.hit(2).line();
        onEdt(() -> {
            LogView v = tab.resultsView();
            int n = v.source().rowCount();
            String[] buf = new String[n];
            v.source().texts(0, n, buf);
            for (int row = 0; row < n; row++) {
                if (buf[row] != null && buf[row].contains("archive.zip › 2026-10/old.log")) {
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
                    return t != null && t.title().equals("old.log") && t.currentLine() == zipLine;
                });
            } catch (Exception e) {
                return false;
            }
        }, 60_000, "opened old.log from the zip at line " + zipLine);
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
        String selected = onEdt(() -> {
            int y = v.yForRowForTest(longRow);
            JComponent c = v.canvasForTest();
            int x1 = v.xForSegmentColumnForTest(longRow, 1, segs[1] + 4);
            int x2 = v.xForSegmentColumnForTest(longRow, 2, segs[2] + 7);
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
            return v.selectedText();
        });
        assertEquals(l3.substring(segs[1] + 4, segs[2] + 7), selected,
                "selection across wrapped rows");

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
            waitFor(() -> {
                try {
                    return onEdt(() -> "/var/log".equals(dlg.folderForTest()));
                } catch (Exception e) {
                    return false;
                }
            }, 10_000, "typed folder listed");
            assertEquals(List.of("..", "app"), onEdt(dlg::namesForTest));
            onEdt(() -> {
                dlg.go("app");
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> dlg.namesForTest().contains("server.log"));
                } catch (Exception e) {
                    return false;
                }
            }, 10_000, "app folder listed");
            assertEquals(List.of("..", "archive", "access.log", "server.log"), onEdt(dlg::namesForTest),
                    "folders first, then files");
            int before = onEdt(dlg::listingsForTest);
            onEdt(() -> {
                dlg.go("/var/log/app");
                return null;
            });
            waitFor(() -> {
                try {
                    return onEdt(() -> dlg.listingsForTest() > before && "/var/log/app".equals(dlg.folderForTest()));
                } catch (Exception e) {
                    return false;
                }
            }, 10_000, "app folder by its full path");
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
