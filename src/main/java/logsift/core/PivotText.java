package logsift.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A pivot as text: the numbers as the Pivot tab shows them, one line per group, for agents and
 * anything else that reads rather than looks.
 */
public final class PivotText {
    private PivotText() {}

    /** A number for people: 1,234 or 1,234.57 (at most 2 decimals; small ones keep 3 significant digits). */
    public static String number(double v) {
        if (Double.isNaN(v)) {
            return "";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "∞" : "-∞";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%,d", (long) v);
        }
        double a = Math.abs(v);
        String s = a >= 1 ? String.format(Locale.ROOT, "%,.2f", v)
                : new java.math.BigDecimal(v).round(new java.math.MathContext(3)).toPlainString();
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    /** The table's columns after the labels: {column group (-1: the total), value}. */
    static List<int[]> columns(Pivot.Result r) {
        List<int[]> cols = new ArrayList<>();
        int nv = r.spec().cellValues().size();
        for (int g = 0; g < r.colKeys().size(); g++) {
            for (int v = 0; v < nv; v++) {
                cols.add(new int[] {g, v});
            }
        }
        for (int v = 0; v < nv; v++) {
            cols.add(new int[] {-1, v}); // the totals
        }
        return cols;
    }

    /** A column's title: "Sum of ms", "500 · Sum of ms", "Total · Count of rows". */
    static String title(Pivot.Result r, int[] gv) {
        String value = r.valueTitle(gv[1]);
        boolean many = r.spec().cellValues().size() > 1;
        if (gv[0] < 0) {
            return r.colKeys().isEmpty() ? value : "Total" + (many ? " · " + value : "");
        }
        String group = String.join(" · ", r.colKeys().get(gv[0]));
        return many ? group + " · " + value : group;
    }

    /** A cell as the Pivot tab shows it: "1,234", "12.5 %", "≥ 10,000", or "" for nothing. */
    static String cell(Pivot.Result r, Pivot.Node n, int[] gv) {
        double d = r.value(n, gv[0], gv[1]);
        if (Double.isNaN(d)) {
            return "";
        }
        boolean pct = r.spec().cellValues().get(gv[1]).showAs() != Pivot.ShowAs.VALUE;
        return (r.distinctCapped(n, gv[0], gv[1]) ? "≥ " : "") + number(d) + (pct ? " %" : "");
    }

    /**
     * The pivot as lines of cells split by " | ": a title line, then each group (a group of an
     * inner level after its outer one, as "outer › inner"), then the grand total. At most
     * {@code maxGroups} groups are listed; the grand total always is.
     */
    public static String table(Pivot.Result r, int maxGroups) {
        List<int[]> cols = columns(r);
        StringBuilder b = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (Pivot.Field f : r.spec().rows()) {
            names.add(r.fieldTitle(f));
        }
        b.append(names.isEmpty() ? "" : String.join(" › ", names));
        for (int[] gv : cols) {
            b.append(" | ").append(title(r, gv));
        }
        b.append('\n');
        int[] listed = {0};
        int[] left = {0};
        walk(r, r.root(), cols, maxGroups, listed, left, b);
        b.append("Grand Total");
        for (int[] gv : cols) {
            b.append(" | ").append(cell(r, r.root(), gv));
        }
        b.append('\n');
        if (left[0] > 0) {
            b.append("(").append(String.format(Locale.ROOT, "%,d", left[0])).append(left[0] == 1 ? " more group" : " more groups")
                    .append(" not listed; raise limit, filter, or group by fewer fields.)\n");
        }
        return b.toString();
    }

    private static void walk(Pivot.Result r, Pivot.Node n, List<int[]> cols, int max, int[] listed, int[] left,
                             StringBuilder b) {
        for (Pivot.Node k : n.kids()) {
            if (listed[0] >= max) {
                left[0] += 1 + count(k);
                continue;
            }
            listed[0]++;
            b.append(String.join(" › ", k.path()).replace('\n', ' '));
            for (int[] gv : cols) {
                b.append(" | ").append(cell(r, k, gv));
            }
            b.append('\n');
            walk(r, k, cols, max, listed, left, b);
        }
    }

    private static int count(Pivot.Node n) {
        int c = 0;
        for (Pivot.Node k : n.kids()) {
            c += 1 + count(k);
        }
        return c;
    }
}
