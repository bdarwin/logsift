package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /** A Compare tab that reads nothing: only its file browsers are tried here. */
    private static final FileCompareTab.Host HOST = new FileCompareTab.Host() {
        @Override
        public void openLine(Path file, long line) {}

        @Override
        public CsvCompareTab.Side sideFor(Path p) {
            return CsvCompareTab.Side.of(p);
        }

        @Override
        public void titleChanged(FileCompareTab tab) {}
    };

    private static <T> T edt(java.util.concurrent.Callable<T> c) throws Exception {
        Object[] out = new Object[1];
        Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                out[0] = c.call();
            } catch (Exception e) {
                err[0] = e;
            }
        });
        if (err[0] != null) {
            throw err[0];
        }
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static Path selected(FileCompareTab t, int side) throws Exception {
        return edt(() -> t.navigatorForTest(side).selected() == null ? null
                : t.navigatorForTest(side).selected().path());
    }

    private static void waitForSelected(FileCompareTab t, int side, Path p) throws Exception {
        long until = System.currentTimeMillis() + 20_000;
        while (!p.equals(selected(t, side))) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("side " + side + " shows " + selected(t, side) + ", not " + p);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void compareTabSidesTakeDroppedFiles(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.csv"), "id,v\n1,x\n");
        Path b = Files.writeString(dir.resolve("b.csv"), "id,v\n1,y\n");
        Path c = Files.writeString(dir.resolve("c.csv"), "id,v\n1,z\n");
        FileCompareTab t = edt(() -> {
            FileCompareTab x = new FileCompareTab(null, null, dir, HOST);
            x.setSize(900, 500);
            x.validate();
            return x;
        });
        try {
            assertFalse(edt(() -> t.compareButtonForTest().isEnabled()), "nothing chosen yet");
            assertTrue(edt(() -> drop(t, files(a, b))), "two files fill both sides");
            waitForSelected(t, 0, a);
            waitForSelected(t, 1, b);
            assertTrue(edt(() -> t.compareButtonForTest().isEnabled()), "a file on each side: Compare");
            assertTrue(edt(() -> drop(t.navigatorForTest(0).treeForTest(), files(c))), "one file on a side's tree");
            waitForSelected(t, 0, c);
            assertEquals(b, selected(t, 1));
            // a folder on a side: no comparison
            assertTrue(edt(() -> drop(t.navigatorForTest(1).treeForTest(), files(dir))));
            waitForSelected(t, 1, dir);
            assertFalse(edt(() -> t.compareButtonForTest().isEnabled()), "a folder is not compared (yet)");
            assertEquals("Choose the right file", edt(t::chooserStatusForTest));
        } finally {
            edt(() -> {
                t.close();
                return null;
            });
        }
    }

    @Test
    void pathFieldsStillTakeText(@TempDir Path dir) throws Exception {
        FileCompareTab t = edt(() -> new FileCompareTab(null, null, dir, HOST));
        try {
            waitForSelected(t, 1, dir); // the browser has opened its folder (and written its path)
            JTextField f = edt(() -> t.navigatorForTest(1).pathFieldForTest());
            edt(() -> {
                f.selectAll(); // the folder's path: replaced by what is pasted
                return null;
            });
            assertTrue(edt(() -> drop(f, new StringSelection("/typed/x.csv"))), "text is pasted as before");
            assertEquals("/typed/x.csv", edt(f::getText));
        } finally {
            edt(() -> {
                t.close();
                return null;
            });
        }
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
