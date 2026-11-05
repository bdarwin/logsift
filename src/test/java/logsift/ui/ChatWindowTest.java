package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Rectangle;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class ChatWindowTest {
    static final Rectangle LAPTOP = new Rectangle(0, 25, 1440, 875);
    static final Rectangle MONITOR = new Rectangle(1440, 0, 2560, 1415);

    @Test
    void itGoesBackWhereItWas() {
        Rectangle there = new Rectangle(2000, 100, 700, 900);
        assertEquals(there, ChatWindow.place(there, List.of(LAPTOP, MONITOR), new Rectangle(0, 25, 1200, 800)));
    }

    @Test
    void aScreenGoneBringsItBesideTheLogs() {
        Rectangle onMonitor = new Rectangle(2000, 100, 700, 900);
        Rectangle logs = new Rectangle(0, 25, 1000, 800);
        Rectangle r = ChatWindow.place(onMonitor, List.of(LAPTOP), logs);
        assertEquals(new Rectangle(540, 25, 900, 800), r, "over the logs' right side: no room beside them");
    }

    @Test
    void besideTheLogsWhenThereIsRoom() {
        Rectangle logs = new Rectangle(1440, 0, 1200, 900);
        assertEquals(new Rectangle(2640, 0, 900, 900), ChatWindow.place(null, List.of(LAPTOP, MONITOR), logs));
        Rectangle right = new Rectangle(3200, 0, 800, 900);
        assertEquals(new Rectangle(2300, 0, 900, 900), ChatWindow.place(null, List.of(LAPTOP, MONITOR), right),
                "at their left");
    }

    @Test
    void halfOffAScreenIsMovedOnto() {
        Rectangle half = new Rectangle(1200, 600, 700, 900); // its title on the laptop, its foot below
        assertEquals(new Rectangle(740, 25, 700, 875), ChatWindow.place(half, List.of(LAPTOP), null));
    }

    @Test
    void onlyItsFootOnAScreenIsNotEnough() {
        Rectangle above = new Rectangle(100, -600, 700, 640); // title bar above every screen
        Rectangle r = ChatWindow.place(above, List.of(LAPTOP), null);
        assertEquals(new Rectangle(270, 52, 900, 820), r, "centered instead");
    }

    @Test
    void tooSmallIsNotKept() {
        Rectangle tiny = new Rectangle(100, 100, 120, 80);
        assertEquals(900, ChatWindow.place(tiny, List.of(MONITOR), null).width);
    }

    @Test
    void noScreensInAHeadlessTest() {
        assertEquals(new Rectangle(0, 0, 900, 820), ChatWindow.place(null, List.of(), null));
    }

    @Test
    void itsPlaceIsReadBack() throws Exception {
        Preferences p = Preferences.userRoot().node("/logsift-test/chat-window-test");
        try {
            assertNull(ChatWindow.saved(p));
            p.put("window", "10, 20,700,800");
            assertEquals(new Rectangle(10, 20, 700, 800), ChatWindow.saved(p));
            p.put("window", "10,x,700,800");
            assertNull(ChatWindow.saved(p));
        } finally {
            p.removeNode();
        }
    }
}
