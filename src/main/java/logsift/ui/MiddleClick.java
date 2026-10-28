package logsift.ui;

import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

/** A middle click closes what it is on: a tab, or a search in Find results. */
final class MiddleClick {
    private MiddleClick() {}

    /** Whether this press closes the thing at {@code index} (-1: nothing there, like the empty tab strip). */
    static boolean closes(MouseEvent e, int index) {
        return index >= 0 && e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isMiddleMouseButton(e);
    }
}
