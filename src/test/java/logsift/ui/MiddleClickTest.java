package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/** Which clicks close a tab (no screen needed). */
class MiddleClickTest {
    private static MouseEvent press(int button, int mask) {
        return new MouseEvent(new JPanel(), MouseEvent.MOUSE_PRESSED, 0, mask, 10, 10, 1, false, button);
    }

    @Test
    void onlyAMiddlePressOnATab() {
        MouseEvent middle = press(MouseEvent.BUTTON2, InputEvent.BUTTON2_DOWN_MASK);
        assertTrue(MiddleClick.closes(middle, 0));
        assertTrue(MiddleClick.closes(middle, 3));
        assertTrue(!MiddleClick.closes(middle, -1), "the empty tab strip: nothing");
        assertTrue(!MiddleClick.closes(press(MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK), 0), "a left click selects");
        assertTrue(!MiddleClick.closes(press(MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK), 0), "a right click: its menu");
        MouseEvent release = new MouseEvent(new JPanel(), MouseEvent.MOUSE_RELEASED, 0, 0, 10, 10, 1, false,
                MouseEvent.BUTTON2);
        assertTrue(!MiddleClick.closes(release, 0), "only the press, once");
    }
}
