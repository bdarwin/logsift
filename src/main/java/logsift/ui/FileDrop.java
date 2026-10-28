package logsift.ui;

import java.awt.Component;
import java.awt.Point;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

/**
 * Files dropped on a view with two sides (a comparison): where a drop lands is shown while
 * dragging, and the files go to that side, or to both. The components inside the view keep what
 * they did with other drops and with copy and paste (text fields take text, a table copies).
 */
final class FileDrop {
    private FileDrop() {}

    /** Both sides at once (two files). */
    static final int BOTH = 2;

    interface Target {
        /**
         * Where a drop of {@code count} files (-1: not known yet) at {@code p} (in the target's
         * coordinates) lands: 0 left, 1 right, {@link #BOTH}, or -1 nowhere.
         */
        int sideAt(Point p, int count);

        /** Like {@link #sideAt(Point, int)}, knowing the component under the drop (inside the target). */
        default int sideAt(Component over, Point p, int count) {
            return sideAt(p, count);
        }

        /** Shows where a drag would land (-1: the drag left, or ended). */
        void hover(int side);

        void dropped(List<Path> files, int side);
    }

    /** Takes files dropped on {@code target} and on each of {@code parts} (inside it). */
    static void install(JComponent target, Target t, JComponent... parts) {
        handle(target, target, t);
        for (JComponent c : parts) {
            handle(c, target, t);
        }
    }

    private static void handle(JComponent c, JComponent target, Target t) {
        c.setTransferHandler(new Handler(c.getTransferHandler(), target, t));
        if (c.getDropTarget() != null) {
            try {
                c.getDropTarget().addDropTargetListener(new DropTargetAdapter() {
                    @Override
                    public void dragExit(DropTargetEvent e) {
                        t.hover(-1);
                    }

                    @Override
                    public void dragOver(DropTargetDragEvent e) {
                        // canImport shows the side
                    }

                    @Override
                    public void drop(DropTargetDropEvent e) {
                        t.hover(-1);
                    }
                });
            } catch (java.util.TooManyListenersException e) {
                // the outline then stays until the next drag: harmless
            }
        }
    }

    /** The files of a drop, or null if it isn't one of files. */
    static List<Path> files(TransferHandler.TransferSupport s) {
        if (!s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            return files.stream().map(File::toPath).toList();
        } catch (Exception e) {
            return null;
        }
    }

    /** Files go to the target; anything else to what the component did before. */
    static final class Handler extends TransferHandler {
        private static final long serialVersionUID = 1L;
        private final transient TransferHandler previous;
        private final JComponent target;
        private final transient Target t;

        Handler(TransferHandler previous, JComponent target, Target t) {
            this.previous = previous;
            this.target = target;
            this.t = t;
        }

        private int side(TransferSupport s, int count) {
            Point p = s.isDrop() ? s.getDropLocation().getDropPoint() : new Point(0, 0);
            Component from = s.getComponent();
            return t.sideAt(from, from == target ? p : SwingUtilities.convertPoint(from, p, target), count);
        }

        @Override
        public boolean canImport(TransferSupport s) {
            if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                int side = side(s, -1);
                if (s.isDrop()) {
                    t.hover(side);
                }
                return side >= 0;
            }
            return previous != null && previous.canImport(s);
        }

        @Override
        public boolean importData(TransferSupport s) {
            List<Path> files = files(s);
            if (files != null) {
                t.hover(-1);
                if (files.isEmpty()) {
                    return false;
                }
                int side = side(s, files.size());
                if (side < 0) {
                    return false;
                }
                t.dropped(files, side);
                return true;
            }
            return previous != null && previous.importData(s);
        }

        @Override
        public int getSourceActions(JComponent c) {
            return previous != null ? previous.getSourceActions(c) : NONE;
        }

        @Override
        public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
            if (previous != null) {
                previous.exportToClipboard(comp, clip, action);
            }
        }

        @Override
        public void exportAsDrag(JComponent comp, InputEvent e, int action) {
            if (previous != null) {
                previous.exportAsDrag(comp, e, action);
            }
        }

        @Override
        public Icon getVisualRepresentation(java.awt.datatransfer.Transferable tr) {
            return previous != null ? previous.getVisualRepresentation(tr) : null;
        }
    }
}
