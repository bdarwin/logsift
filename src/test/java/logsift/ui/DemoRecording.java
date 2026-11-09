package logsift.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import logsift.core.SearchQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Records a video of the find bar and the log bar: drives the real window step by step and
 * draws a pointer, the keys pressed and a caption on each frame.
 *
 * <p>Run with {@code ./gradlew visualTest --tests logsift.ui.DemoRecording -Pdemo=/some/dir};
 * needs ffmpeg on the PATH.
 */
@Tag("visual")
@EnabledIfSystemProperty(named = "logsift.demo", matches = ".+")
class DemoRecording extends Recording {
    @Override
    String videoName() {
        return "logsift-find-bar.mp4";
    }

    @Test
    void record() throws Exception {
        Files.createDirectories(out);
        Path dir = out.resolve("logs");
        Files.createDirectories(dir);
        Path log = dir.resolve("shop.log");
        writeShopLog(log, 0, 3000);
        writeOtherLogs(dir);

        System.setProperty("logsift.noJvmBar", "true");
        var prefs = AppState.prefs();
        prefs.putBoolean("columns", true);
        prefs.putBoolean("wrap", false);
        prefs.putBoolean("valuesTree", true);
        prefs.putBoolean("timeline", true);
        prefs.putBoolean("autoReload", true);
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
            script(log, dir);
        } finally {
            stopVideo();
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    private void script(Path log, Path dir) throws Exception {
        say("With nothing open, the window shows the ways to start, and your recent files");
        StartPage start = onEdt(frame::startPageForTest);
        hold(1200);
        for (String a : new String[] {"Open Log…", "Open Folder…", "Open Remote…", "Open URL…",
                "Attach to a JVM…", "Open Heap Dump…", "Open Thread Dump…", "Open Profile…"}) {
            moveTo(onEdt(() -> start.actionForTest(a)), 450);
            hold(350);
        }
        still("0-start-page");
        moveTo(onEdt(() -> start.actionForTest("Open Log…")), 500);
        say("Open Log… (here: shop.log)");
        ripple = 0;
        hold(400);
        onEdt(() -> {
            frame.openFiles(List.of(log));
            return null;
        });
        LogTab tab = waitForTab("shop.log");
        waitUntil(() -> !tab.document().isIndexing() && tab.document().lineCount() >= 3000);
        onEdt(() -> {
            tab.mainView().goToRow(0, true);
            return null;
        });
        say("Logsift: the new find bar, and a bar for each log");
        hold(2500);

        // no toolbar: app-wide things are in the menus
        say("No toolbar on top: Open, Remote, Search in Folder and Settings are in the menus");
        javax.swing.JMenuBar mb = onEdt(frame::getJMenuBar);
        moveTo(onEdt(() -> mb.getMenu(0)), 700);
        hold(500);
        moveTo(onEdt(() -> mb.getMenu(1)), 500);
        hold(1200);

        // the log bar
        LogBar bar = tab.logBar();
        say("Each log has its own bar: Find and Filters on the left ...");
        moveTo(button(bar, "Find"), 600);
        moveTo(button(bar, "Filters"), 500);
        hold(900);
        say("... and on the right: Wrap, Columns, Tree, Timeline, then Follow and Reload");
        for (String tip : new String[] {"Wrap", "Show lines as columns", "Tree of loggers", "Timeline"}) {
            moveTo(byTip(bar, tip), 450);
            hold(250);
        }
        moveTo(button(bar, "Follow"), 500);
        moveTo(button(bar, "Reload"), 400);
        hold(700);
        say("Each log keeps its own view; your last choice is the default for the next log");
        AbstractButton cols = byTip(bar, "Show lines as columns");
        moveTo(cols, 700);
        click(cols);
        hold(1600);
        click(cols);
        hold(1200);
        still("0-log-bar");

        // Ctrl+F
        moveTo(tab.mainView(), 600);
        say("⌘F (Ctrl+F) opens the find bar at the top of the log");
        press(MainFrame.isMac() ? "⌘ F" : "Ctrl F");
        onEdt(() -> {
            frame.openFind();
            return null;
        });
        hold(1000);
        say("Matches light up as you type, and the log jumps to the first one");
        type(tab.findBar(), "declined");
        waitUntil(() -> tab.findBar().countText().endsWith(" of 31"));
        hold(2000);
        still("1-find-bar");

        say("Enter: next match.  Shift+Enter: previous.  It wraps around at the end");
        for (int i = 0; i < 3; i++) {
            press("Enter");
            onEdt(() -> tab.findBar().next(true));
            hold(900);
        }
        press("⇧ Enter");
        onEdt(() -> tab.findBar().next(false));
        hold(1300);

        // regex toggle
        say("Aa, W and .* sit in the field: match case, whole word, regex");
        AbstractButton rx = button(tab.findBar(), ".*");
        moveTo(rx, 700);
        click(rx);
        hold(500);
        onEdt(() -> {
            tab.findBar().field().selectAll();
            return null;
        });
        type(tab.findBar(), "declined|timeout");
        waitUntil(() -> tab.findBar().countText().contains(" of ") && !tab.findBar().session().isRunning());
        hold(1800);

        // Find All
        say("Find All lists every match below the log");
        AbstractButton all = button(tab.findBar(), "Find All");
        moveTo(all, 700);
        click(all);
        waitUntil(() -> tab.currentFind() != null);
        hold(3000);
        still("2-find-all");

        // Filter ▾
        say("Filter ▾ turns the search into a filter");
        AbstractButton filter = button(tab.findBar(), "Filter ▾");
        moveTo(filter, 700);
        click(filter);
        hold(700);
        JMenuItem show = menuItem("Show Only Matching Lines");
        moveTo(show, 600);
        hold(500);
        still("3-filter-menu");
        click(show);
        say("Now it's a chip above the log, and only the matching lines are shown");
        waitUntil(() -> !tab.filterEngine().isRunning() && !tab.logBar().chipTexts().isEmpty());
        hold(1200);
        moveTo(chips(bar).get(0), 700);
        hold(2200);
        still("4-chip");

        // levels
        say("Choose levels in Filters, and they show up as a chip too");
        AbstractButton filters = button(bar, "Filters");
        moveTo(filters, 700);
        click(filters);
        hold(900);
        onEdt(() -> {
            tab.filterPanel().setLevels("E");
            return null;
        });
        waitUntil(() -> !tab.filterEngine().isRunning() && tab.logBar().chipTexts().size() == 2);
        hold(2500);
        still("5-levels-chip");

        // chip on / off
        say("Click a chip to turn it off ...");
        Component chip = chips(bar).get(0);
        moveTo(chip, 700, 0.35);
        clickChip(chip, false);
        waitUntil(() -> !tab.filterEngine().isRunning());
        hold(2000);
        say("... and on again");
        chip = chips(bar).get(0);
        clickChip(chip, false);
        waitUntil(() -> !tab.filterEngine().isRunning());
        hold(1600);

        // right-click menu
        say("Right-click a chip: turn it off, show / hide / color, edit, or remove");
        chip = chips(bar).get(0);
        rightClick(chip);
        hold(1200);
        JMenuItem color = menuItem("Color");
        moveTo(color, 600);
        hold(600);
        still("6-chip-menu");
        click(color);
        say("Color: every line again, with the matching ones colored");
        waitUntil(() -> !tab.filterEngine().isRunning());
        hold(2400);

        // ✕
        say("✕ removes a chip: here, back to all levels");
        Component levels = chips(bar).get(1);
        moveTo(levels, 700, 0.93);
        hold(400);
        clickChip(levels, true);
        waitUntil(() -> !tab.filterEngine().isRunning() && tab.logBar().chipTexts().size() == 1);
        hold(2000);
        still("7-after-remove");

        // Reload
        say("Reload ▾ → Reload Automatically: off, so changes wait for you");
        AbstractButton more = button(bar, "▾");
        moveTo(more, 900);
        click(more);
        hold(700);
        JMenuItem auto = menuItem("Reload Automatically");
        moveTo(auto, 600);
        hold(600);
        still("8-reload-menu");
        click(auto);
        hold(500);
        say("The file changed on disk: Reload lights up with how much is new");
        writeShopLog(log, 3000, 400);
        waitUntil(() -> tab.logBar().reloadText().startsWith("Reload ("));
        AbstractButton reload = button(bar, "Reload");
        moveTo(reload, 900);
        hold(2200);
        still("8-reload");
        say("Click it to load what's new");
        click(reload);
        waitUntil(() -> !tab.logBar().reloadText().startsWith("Reload ("));
        hold(1800);

        // Follow
        say("Follow tails the file as it grows; the chip counts keep up");
        AbstractButton follow = button(bar, "Follow");
        moveTo(follow, 700);
        click(follow);
        int[] at = {3400};
        for (int i = 0; i < 12; i++) {
            writeShopLog(log, at[0], 40);
            at[0] += 40;
            hold(400);
        }
        hold(1200);
        still("9-follow");
        click(follow);
        hold(600);

        // folder search
        say("⇧⌘F or the folder button: search the whole folder, zips included");
        press(MainFrame.isMac() ? "⌘ F" : "Ctrl F");
        onEdt(() -> {
            frame.openFind();
            tab.findBar().field().setText("declined");
            return null;
        });
        hold(900);
        AbstractButton folder = onEdt(() -> (AbstractButton) findAll(tab.findBar(),
                c -> c instanceof AbstractButton b && b.getToolTipText() != null
                        && b.getToolTipText().startsWith("Search for this in a folder")).get(0));
        moveTo(folder, 700);
        click(folder);
        hold(700);
        FolderSearchTab fs = onEdt(() -> (FolderSearchTab) frame.tabsForTest().getSelectedComponent());
        press("Enter");
        onEdt(() -> {
            fs.search(dir, "", new SearchQuery("declined", false, false, false));
            return null;
        });
        waitUntil(() -> !fs.isSearching());
        hold(3500);
        still("10-folder-search");

        // + after the tabs
        say("+ after the tabs: the start page again, to open something else");
        JButton plus = onEdt(() -> (JButton) findAll(frame.getContentPane(), c -> c instanceof JButton b
                && b.isShowing() && "Open something: a log, a folder, a remote log, a JVM…".equals(b.getToolTipText()))
                .get(0));
        moveTo(plus, 900);
        click(plus);
        hold(3000);
        still("11-start-tab");
        say("That's the whole flow");
        showCursor = false;
        hold(2000);
    }

    // ---------------------------------------------------------------- the log

    private static void writeShopLog(Path log, int from, int n) throws Exception {
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 2, 0)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + n; i++) {
            boolean declined = i % 97 == 13;
            boolean timeout = i % 211 == 50;
            String level = declined ? " WARN  " : timeout ? " ERROR " : i % 11 == 0 ? " DEBUG " : " INFO  ";
            String msg = declined ? "card declined by bank requestId=r-" + i
                    : timeout ? "payment gateway timeout after 30000 ms requestId=r-" + i
                    : i % 11 == 0 ? "cart " + (i * 7 % 900) + " has " + (i % 5 + 1) + " items requestId=r-" + i
                    : "POST /orders 201 Created in " + (i * 13 % 180 + 9) + " ms requestId=r-" + i;
            sb.append(TimelineBar.formatFull(t0 + i * 150L).substring(0, 23)).append(level)
                    .append("[http-nio-8080-exec-").append(i % 12 + 1).append("] c.a.shop.OrderController - ")
                    .append(msg).append('\n');
        }
        if (from == 0) {
            Files.writeString(log, sb.toString());
        } else {
            Files.writeString(log, sb.toString(), java.nio.file.StandardOpenOption.APPEND);
        }
    }

    private static void writeOtherLogs(Path dir) throws Exception {
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            a.append("2026-10-20 09:").append(String.format("%02d:%02d", i / 60 % 60, i % 60))
                    .append(i % 53 == 7 ? ".000 WARN  [worker-2] c.a.billing.Charge - card declined, retry 1 of 3"
                            : ".000 INFO  [worker-1] c.a.billing.Charge - charged invoice " + (10_000 + i))
                    .append('\n');
        }
        Files.writeString(dir.resolve("billing.log"), a.toString());
        Path zip = dir.resolve("yesterday.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("shop-2026-10-19.log"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 1500; i++) {
                b.append("2026-10-19 18:00:").append(String.format("%02d", i % 60))
                        .append(i % 131 == 3 ? ".000 WARN  [http-nio-8080-exec-3] c.a.shop.OrderController - card declined by bank"
                                : ".000 INFO  [http-nio-8080-exec-1] c.a.shop.OrderController - 201 Created")
                        .append('\n');
            }
            z.write(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

}
