package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** The window's tabs in one group or two: numbered across both, moved, joined, dropped. */
class TabGroupsTest {

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        SwingUtilities.invokeAndWait(() -> { }); // and what it left for later
    }

    private static JLabel tab(String name) {
        JLabel l = new JLabel(name);
        l.setName(name);
        return l;
    }

    private static List<String> names(TabGroups g, int side) {
        List<String> out = new ArrayList<>();
        for (Component c : g.tabsOf(side)) {
            out.add(c.getName());
        }
        return out;
    }

    @Test
    void tabsAreNumberedAcrossBothSides() throws Exception {
        onEdt(() -> {
            TabGroups g = new TabGroups(JPanel::new);
            JLabel a = tab("a");
            JLabel b = tab("b");
            JLabel c = tab("c");
            g.addTab("a", a);
            g.addTab("b", b);
            g.addTab("c", c);
            assertFalse(g.isSplit());
            assertEquals(2, g.lastAdded());
            g.moveToOtherSide(1, false);
            assertTrue(g.isSplit());
            assertFalse(g.isDown());
            assertEquals(List.of("a", "c"), names(g, 0));
            assertEquals(List.of("b"), names(g, 1));
            assertEquals(3, g.getTabCount());
            assertSame(b, g.getComponentAt(2), "the second side's after the first's");
            assertEquals(2, g.indexOfComponent(b));
            assertSame(b, g.getSelectedComponent(), "the moved tab is shown, and its side is in use");
            assertEquals(1, g.activeSide());
            assertSame(a, g.otherSelected(), "the first side still shows the tab it showed");
            // a new tab opens on the side in use
            JLabel d = tab("d");
            g.addTab("d", d);
            assertEquals(List.of("b", "d"), names(g, 1));
            assertEquals(3, g.lastAdded());
            g.setSelectedComponent(a);
            assertEquals(0, g.activeSide(), "choosing a tab uses its side");
            assertEquals(0, g.getSelectedIndex());
            g.setTitleAt(3, "D!");
            assertEquals("D!", g.getTitleAt(3));
        });
    }

    @Test
    void aSideWithNoTabsLeftJoinsTheWindowAgain() throws Exception {
        TabGroups[] g = new TabGroups[1];
        JLabel b = tab("b");
        onEdt(() -> {
            g[0] = new TabGroups(JPanel::new);
            g[0].addTab("a", tab("a"));
            g[0].addTab("b", b);
            g[0].moveToOtherSide(1, true);
            assertTrue(g[0].isDown(), "one above the other");
            g[0].remove(b); // its tab closed
        });
        onEdt(() -> {
            assertFalse(g[0].isSplit());
            assertEquals(List.of("a"), names(g[0], 0));
        });
        // the first side emptied: the second's tabs come over
        JLabel a2 = tab("a2");
        onEdt(() -> {
            g[0].addTab("x", tab("x"));
            g[0].moveToOtherSide(0, false);
            assertEquals(List.of("x"), names(g[0], 0));
            assertEquals(List.of("a"), names(g[0], 1));
            g[0].addTab("a2", a2);
            g[0].removeTabAt(0);
        });
        onEdt(() -> {
            assertFalse(g[0].isSplit());
            assertEquals(List.of("a", "a2"), names(g[0], 0));
        });
    }

    @Test
    void theOnlyTabDoesntMoveAndJoinKeepsTheOrder() throws Exception {
        onEdt(() -> {
            TabGroups g = new TabGroups(JPanel::new);
            g.addTab("a", tab("a"));
            g.moveToOtherSide(0, false);
            assertFalse(g.isSplit(), "nothing would be left beside it");
            g.addTab("b", tab("b"));
            g.addTab("c", tab("c"));
            g.moveToOtherSide(0, false);
            g.addToOtherSide("copy", null, tab("copy"), "tip", false);
            assertEquals(List.of("b", "c", "copy"), names(g, 0), "a copy goes to the side not in use");
            assertEquals(List.of("a"), names(g, 1));
            g.join();
            assertFalse(g.isSplit());
            assertEquals(List.of("b", "c", "copy", "a"), names(g, 0));
            assertEquals("copy", g.getSelectedComponent().getName(), "what was shown still is");
        });
    }

    @Test
    void aClosedTabIsNumberedAcrossTheSides() throws Exception {
        onEdt(() -> {
            List<Integer> closed = new ArrayList<>();
            TabGroups g = new TabGroups(JPanel::new);
            g.onClose(closed::add);
            g.addTab("a", tab("a"));
            g.addTab("b", tab("b"));
            g.addTab("c", tab("c"));
            g.moveToOtherSide(0, false);
            // FlatLaf's ✕ on the second side's first tab
            @SuppressWarnings("unchecked")
            var close = (java.util.function.BiConsumer<javax.swing.JTabbedPane, Integer>)
                    g.paneForTest(1).getClientProperty("JTabbedPane.tabCloseCallback");
            close.accept(g.paneForTest(1), 0);
            assertEquals(List.of(2), closed);
        });
    }

    @Test
    void aDraggedTabSplitsTheWindowOrCrossesOver() throws Exception {
        onEdt(() -> {
            TabGroups g = new TabGroups(JPanel::new);
            g.component().setSize(1000, 600);
            g.component().doLayout();
            g.addTab("a", tab("a"));
            assertEquals(TabGroups.Drop.NONE, g.dropAt(new Point(900, 300), 0), "one tab: it can't leave");
            g.addTab("b", tab("b"));
            assertEquals(TabGroups.Drop.SPLIT_RIGHT, g.dropAt(new Point(900, 300), 0));
            assertEquals(TabGroups.Drop.SPLIT_DOWN, g.dropAt(new Point(300, 550), 0));
            assertEquals(TabGroups.Drop.NONE, g.dropAt(new Point(300, 200), 0));
            assertEquals(TabGroups.Drop.NONE, g.dropAt(new Point(1300, 200), 0), "outside");
            assertTrue(g.drop(1, TabGroups.Drop.SPLIT_RIGHT));
            g.component().doLayout();
            ((java.awt.Container) g.component().getComponent(0)).doLayout();
            assertEquals(List.of("b"), names(g, 1));
            Point inSecond = SwingUtilities.convertPoint(g.paneForTest(1), new Point(20, 20), g.component());
            Point inFirst = SwingUtilities.convertPoint(g.paneForTest(0), new Point(20, 20), g.component());
            assertEquals(TabGroups.Drop.OTHER_SIDE, g.dropAt(inSecond, 0));
            assertEquals(TabGroups.Drop.NONE, g.dropAt(inFirst, 0), "its own side");
            assertFalse(g.drop(0, TabGroups.Drop.NONE));
        });
    }

    @Test
    void revealSelectsTheFileOnEachSystem() {
        Path f = Path.of("/logs/app 1.log").toAbsolutePath();
        List<String> win = HelpSupport.revealCommand("Windows 11", f);
        assertTrue(win.get(0).endsWith("explorer.exe"), "" + win);
        assertEquals(List.of("/select,", f.toString()), win.subList(1, 3));
        List<String> linux = HelpSupport.revealCommand("Linux", f);
        assertEquals("dbus-send", linux.get(0));
        assertTrue(linux.contains("array:string:" + f.toUri()), "" + linux);
        assertNull(HelpSupport.revealCommand("Mac OS X", f), "Finder: Java's own");
    }
}
