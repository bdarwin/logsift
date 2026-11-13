package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Text at 125%, 150% and 200% (Windows display scaling): it must end where the view thinks it
 * does, or highlights, the selection and clicks land on the wrong characters.
 */
class ScaledTextTest {

    private static final int CHARS = 60;

    private static void layout(Container c) {
        c.doLayout();
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof Container cc) {
                layout(cc);
            }
        }
    }

    @Test
    void textStaysOnTheGrid() throws Exception {
        String line = "m".repeat(CHARS);
        SwingUtilities.invokeAndWait(() -> {
            LogView v = new LogView();
            v.setSize(900, 200);
            layout(v);
            v.setSource(new LogView.RowSource() {
                @Override
                public int rowCount() {
                    return 1;
                }

                @Override
                public long lineNumber(int row) {
                    return row;
                }

                @Override
                public void texts(int first, int count, String[] out) {
                    for (int i = 0; i < count; i++) {
                        out[i] = first + i == 0 ? line : null;
                    }
                }
            });
            v.setHighlightRules(List.of());
            layout(v);
            int start = v.xForCharForTest(0, 0);
            int end = v.xForCharForTest(0, CHARS);
            int y = v.yForRowForTest(0);
            for (double s : new double[] {1, 1.25, 1.5, 1.75, 2}) {
                var canvas = v.canvasForTest();
                BufferedImage img = new BufferedImage((int) Math.ceil(canvas.getWidth() * s),
                        (int) Math.ceil(canvas.getHeight() * s), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.scale(s, s);
                canvas.paint(g);
                g.dispose();
                // the last dark pixel on the row, right of where the text starts
                int last = -1;
                int row = (int) (y * s);
                int right = (int) (canvas.getWidth() * s) - 1; // the image's last column may not be painted
                for (int dy = (int) (-4 * s); dy <= 4 * s; dy++) {
                    for (int x = (int) (start * s); x < right; x++) {
                        int p = img.getRGB(x, row + dy);
                        int lum = ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 3;
                        if (lum < 128) {
                            last = Math.max(last, x);
                        }
                    }
                }
                double cw = (end - start) / (double) CHARS;
                double lastUser = last / s;
                assertTrue(lastUser > end - cw && lastUser < end + 1,
                        "at " + s + "x the text ends at " + lastUser + ", the grid at " + end);
            }
        });
    }
}
