package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/** How a tab says it counts only the filtered lines (no screen needed). */
class TabScopeTest {

    @Test
    void words() {
        assertEquals("Only the 225 of 2,099 lines the filters keep", TabScope.bannerText(225, 2099));
        assertEquals("Only the 0 of 1,000,000 lines the filters keep", TabScope.bannerText(0, 1_000_000));
        assertEquals("Counts only the lines the filters keep", TabScope.FOLLOWS);
        assertEquals("Always the whole log", TabScope.WHOLE);
    }

    @Test
    void whenMetricsAndProfileFollow() {
        assertTrue(TabScope.followsMatchingOnly(true, true));
        assertTrue(!TabScope.followsMatchingOnly(true, false), "Matching only without filters: the whole log");
        assertTrue(!TabScope.followsMatchingOnly(false, true), "filters, but all lines shown: the whole log");
        assertTrue(!TabScope.followsMatchingOnly(false, false));
    }

    @Test
    void theStripSitsAboveTheTabsOwnBar() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            JPanel tab = new JPanel(new BorderLayout());
            JLabel bar = new JLabel("bar");
            tab.add(bar, BorderLayout.NORTH);
            int[] clicks = {0};
            ScopeBanner b = ScopeBanner.install(tab, "Show all lines", () -> clicks[0]++);
            JPanel top = (JPanel) ((BorderLayout) tab.getLayout()).getLayoutComponent(BorderLayout.NORTH);
            assertSame(b, ((BorderLayout) top.getLayout()).getLayoutComponent(BorderLayout.NORTH));
            assertSame(bar, ((BorderLayout) top.getLayout()).getLayoutComponent(BorderLayout.CENTER),
                    "the tab's own bar stays, under the strip");
            assertNull(b.textForTest(), "hidden while the tab covers the whole log");
            b.show(TabScope.bannerText(3, 10));
            assertEquals("Only the 3 of 10 lines the filters keep", b.textForTest());
            b.clickForTest();
            assertEquals(1, clicks[0]);
            b.show(null);
            assertNull(b.textForTest());
        });
    }
}
