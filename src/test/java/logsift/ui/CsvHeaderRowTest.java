package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import logsift.core.LogFormat;
import org.junit.jupiter.api.Test;

/** The header row of a CSV in the columns view: named by the strip above, not shown as a row. */
class CsvHeaderRowTest {

    private static final String HEADER = "Timestamp,Host,Message";

    private static List<String> csv(int rows) {
        List<String> out = new ArrayList<>();
        out.add(HEADER);
        for (int i = 0; i < rows; i++) {
            out.add(String.format("2026-10-21 10:00:%02d,web-%d,request %d", i % 60, i % 3 + 1, i));
        }
        return out;
    }

    /** Rows {@code lines[from..]}, numbered as file lines. */
    private static LogView.RowSource source(List<String> lines, List<Integer> fileLines) {
        return new LogView.RowSource() {
            @Override
            public int rowCount() {
                return fileLines.size();
            }

            @Override
            public long lineNumber(int row) {
                return fileLines.get(row);
            }

            @Override
            public void texts(int first, int count, String[] out) {
                for (int i = 0; i < count; i++) {
                    out[i] = first + i < fileLines.size() ? lines.get(fileLines.get(first + i)) : null;
                }
            }
        };
    }

    private static List<Integer> all(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    private static void layout(Container c) {
        c.doLayout();
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof Container cc) {
                layout(cc);
            }
        }
    }

    private static LogView view(List<String> lines, List<Integer> rows, LogFormat f) {
        LogView v = new LogView();
        v.setSize(900, 400);
        layout(v);
        v.setSource(source(lines, rows));
        v.setColumns(f == null ? null : new ColumnLayout(f, false));
        layout(v);
        return v;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void headerRowIsNotShownInColumns() throws Exception {
        List<String> lines = csv(100);
        LogFormat f = LogFormat.detectCsv(lines.subList(0, 20), true);
        assertNotNull(f);
        onEdt(() -> {
            LogView v = view(lines, all(lines.size()), f);
            assertEquals(lines.get(1), v.textAtForTest(0), "the first row shown is line 2");
            assertEquals(1, v.topRow());
            assertEquals(1, v.lineNumberOf(1), "line numbers stay those of the file");
            // going to line 1 (the header) shows the first row instead
            v.goToRow(0, true);
            assertEquals(1, v.selectedRow());
            // the keys stop at the first row
            v.goToRow(3, false);
            for (int i = 0; i < 5; i++) {
                v.canvasForTest().getActionMap().get("up").actionPerformed(null);
            }
            assertEquals(1, v.selectedRow());
            v.canvasForTest().getActionMap().get("home").actionPerformed(null);
            assertEquals(1, v.selectedRow());
            // far down and back to the top
            v.goToRow(90, true);
            v.goToRow(0, true);
            assertEquals(1, v.topRow());
            assertEquals(lines.get(1), v.textAtForTest(0));
            // with wrap too
            v.setWrap(true);
            v.goToRow(0, true);
            assertEquals(lines.get(1), v.textAtForTest(0));
            // the plain view shows the header line as text
            v.setColumns(null);
            v.goToRow(0, true);
            assertEquals(0, v.selectedRow());
            assertEquals(HEADER, v.textAtForTest(0));
        });
    }

    @Test
    void otherFirstRowsAreShown() throws Exception {
        List<String> lines = csv(30);
        LogFormat f = LogFormat.detectCsv(lines.subList(0, 20), true);
        onEdt(() -> {
            // matching lines only, without the header: the first row is a row of values
            LogView v = view(lines, List.of(3, 7, 9), f);
            assertEquals(lines.get(3), v.textAtForTest(0));
            v.goToRow(0, false);
            assertEquals(0, v.selectedRow());
            // a file that is only a header shows it
            LogView one = view(lines, List.of(0), f);
            assertEquals(HEADER, one.textAtForTest(0));
            // a log that isn't CSV keeps line 1
            LogFormat logback = LogFormat.presets().get(0);
            List<String> log = List.of("2026-10-21 10:00:00.000 INFO  [main] a.B - Timestamp,Host,Message",
                    "2026-10-21 10:00:01.000 INFO  [main] a.B - two");
            LogView plain = view(log, all(2), logback);
            assertEquals(log.get(0), plain.textAtForTest(0));
        });
    }

    @Test
    void rowsAfterTheHeaderArriveLater() throws Exception {
        List<String> lines = new ArrayList<>(List.of(HEADER));
        LogFormat f = LogFormat.detectCsv(csv(10), true);
        List<Integer> rows = new ArrayList<>(List.of(0));
        onEdt(() -> {
            LogView v = view(lines, rows, f);
            assertEquals(HEADER, v.textAtForTest(0), "only the header so far");
            // the file grows (tail): the header goes, the new row is first
            lines.add("2026-10-21 10:00:00,web-1,first");
            rows.add(1);
            v.rowsChanged();
            assertEquals(lines.get(1), v.textAtForTest(0));
        });
    }
}
