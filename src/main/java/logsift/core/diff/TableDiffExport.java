package logsift.core.diff;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * The differences as a CSV file: a row per item with its status, its lines in both files, the
 * key, and each compared column's left and right value. A changed row's unchanged values are
 * left empty on the right, so the changes stand out.
 */
public final class TableDiffExport {
    private TableDiffExport() {}

    /** Writes {@code items} of {@code r} (for example its differences) to {@code out}. */
    public static void writeCsv(TableDiffResult r, int[] items, Writer out, BooleanSupplier cancelled) throws IOException {
        ColumnMap m = r.columns();
        List<String> head = new ArrayList<>(List.of("status", "left line", "right line"));
        for (int k : r.spec().keys()) {
            head.add(m.name(k));
        }
        int[] cmp = r.compared();
        for (int p : cmp) {
            head.add(m.name(p) + " (left)");
            head.add(m.name(p) + " (right)");
        }
        line(out, head);
        for (int j = 0; j < items.length; j++) {
            if ((j & 1023) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            int i = items[j];
            DiffStatus s = r.status(i);
            List<String> row = new ArrayList<>();
            row.add(s.label());
            row.add(r.leftLine(i) < 0 ? "" : String.valueOf(r.leftLine(i) + 1));
            row.add(r.rightLine(i) < 0 ? "" : String.valueOf(r.rightLine(i) + 1));
            for (int k : r.spec().keys()) {
                String v = r.leftRow(i) >= 0 ? r.leftValue(i, k) : r.rightValue(i, k);
                row.add(v == null ? "" : v);
            }
            for (int p : cmp) {
                String a = r.leftValue(i, p);
                String b = r.rightValue(i, p);
                row.add(a == null ? "" : a);
                row.add(b == null || (s == DiffStatus.CHANGED && !r.isChanged(i, p)) ? "" : b);
            }
            line(out, row);
        }
    }

    private static void line(Writer out, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(quote(fields.get(i)));
        }
        out.write('\n');
    }

    /** Quoted when it holds a comma, a quote, a line break or spaces at an end. */
    static String quote(String v) {
        boolean needs = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || (!v.isEmpty() && (Character.isWhitespace(v.charAt(0)) || Character.isWhitespace(v.charAt(v.length() - 1))));
        return needs ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }
}
