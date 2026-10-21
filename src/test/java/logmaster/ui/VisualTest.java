package logmaster.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
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
import javax.swing.SwingUtilities;
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

    // ---------------------------------------------------------------- helpers

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
