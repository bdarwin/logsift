package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.Test;

/** Files dropped on the compare tool, through the components' transfer handlers (headless-safe). */
class FileDropTest {

    static Transferable files(Path... paths) {
        List<File> list = new ArrayList<>();
        for (Path p : paths) {
            list.add(p.toFile());
        }
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.javaFileListFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor f) {
                return DataFlavor.javaFileListFlavor.equals(f);
            }

            @Override
            public Object getTransferData(DataFlavor f) {
                return list;
            }
        };
    }

    private static boolean drop(javax.swing.JComponent c, Transferable t) {
        TransferHandler.TransferSupport s = new TransferHandler.TransferSupport(c, t);
        return c.getTransferHandler().canImport(s) && c.getTransferHandler().importData(s);
    }

    @Test
    void pickerFieldsTakeDroppedFiles() {
        ComparePicker p = new ComparePicker(null, null, null);
        p.setSize(700, 220);
        p.validate();
        Path a = Path.of("/data/a.csv");
        Path b = Path.of("/data/b.csv");
        assertTrue(drop(p, files(a, b)), "two files fill both fields");
        assertEquals(a.toString(), p.left.getText());
        assertEquals(b.toString(), p.right.getText());
        Path c = Path.of("/data/c.csv");
        assertTrue(drop(p.left, files(c)), "one file on a field");
        assertEquals(c.toString(), p.left.getText());
        assertEquals(b.toString(), p.right.getText());
        p.fill(List.of(a), 1);
        assertEquals(a.toString(), p.right.getText(), "one file dropped on the right field's half");
        assertEquals(-1, p.hoverForTest(), "no outline left after a drop");
        assertEquals(List.of(c, a), List.of(p.files()));
    }

    @Test
    void textFieldsStillTakeText() {
        ComparePicker p = new ComparePicker(null, null, null);
        assertTrue(drop(p.right, new StringSelection("/typed/x.csv")), "text is pasted as before");
        assertEquals("/typed/x.csv", p.right.getText());
    }

    @Test
    void sidesAndHover() {
        JPanel view = new JPanel();
        view.setSize(400, 200);
        JTextField inside = new JTextField();
        view.add(inside);
        inside.setBounds(300, 10, 80, 20);
        List<String> got = new ArrayList<>();
        int[] hover = {-5};
        FileDrop.install(view, new FileDrop.Target() {
            @Override
            public int sideAt(Point pt, int count) {
                return count >= 2 ? FileDrop.BOTH : pt.x < 200 ? 0 : 1;
            }

            @Override
            public void hover(int side) {
                hover[0] = side;
            }

            @Override
            public void dropped(List<Path> files, int side) {
                got.add(files.size() + "@" + side);
            }
        }, inside);
        assertTrue(drop(view, files(Path.of("/a"), Path.of("/b"))));
        assertTrue(drop(inside, files(Path.of("/a"))));
        // a drop that isn't a drag (a paste) lands at the component's top left: here, the right half
        assertEquals(List.of("2@2", "1@1"), got);
        assertEquals(-1, hover[0]);
        assertFalse(drop(view, files()), "no files: nothing happens");
    }
}
