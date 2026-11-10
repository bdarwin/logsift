package logsift.core.diff;

import java.util.List;
import java.util.Locale;
import logsift.core.ColumnFilter;

/**
 * Which rows of both files are compared: a column (one in both files) and a condition on its
 * value, such as {@code region = EU} or {@code amount > 100}. Rows that don't pass are left out
 * of the comparison in both files, as if they weren't there, so a subset compares on its own.
 *
 * @param pair the column, an index into {@link ColumnMap#pairs()}
 * @param op how the value is tested
 * @param text the text (or number) it is tested against; unused for {@link Op#EMPTY} and {@link Op#NOT_EMPTY}
 */
public record RowFilter(int pair, Op op, String text) {

    /** How a value is tested. Text compares in any case, spaces around left out. */
    public enum Op {
        EQUALS("="),
        NOT_EQUALS("≠"),
        CONTAINS("contains"),
        NOT_CONTAINS("doesn't contain"),
        MORE(">"),
        LESS("<"),
        EMPTY("is empty"),
        NOT_EMPTY("isn't empty");

        public final String label;

        Op(String label) {
            this.label = label;
        }

        /** Whether the op takes a text (or number). */
        public boolean takesText() {
            return this != EMPTY && this != NOT_EMPTY;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** @throws IllegalArgumentException when a number is needed and the text isn't one */
    public RowFilter {
        text = text == null ? "" : text.strip();
        if ((op == Op.MORE || op == Op.LESS) && Double.isNaN(ColumnFilter.number(text))) {
            throw new IllegalArgumentException("Not a number: " + text);
        }
    }

    /** Whether a row whose value in the column is {@code value} (null: the row has no such field) passes. */
    public boolean matches(String value) {
        String v = value == null ? "" : value.strip();
        return switch (op) {
            case EQUALS -> v.equalsIgnoreCase(text);
            case NOT_EQUALS -> !v.equalsIgnoreCase(text);
            case CONTAINS -> contains(v, text);
            case NOT_CONTAINS -> !contains(v, text);
            case MORE, LESS -> {
                double x = ColumnFilter.number(v);
                double t = ColumnFilter.number(text);
                yield !Double.isNaN(x) && (op == Op.MORE ? x > t : x < t);
            }
            case EMPTY -> v.isEmpty();
            case NOT_EMPTY -> !v.isEmpty();
        };
    }

    private static boolean contains(String v, String q) {
        for (int i = 0; i + q.length() <= v.length(); i++) {
            if (v.regionMatches(true, i, q, 0, q.length())) {
                return true;
            }
        }
        return false;
    }

    /** In words: "region = EU", "note is empty". */
    public String describe(ColumnMap columns) {
        String name = pair < columns.pairs().size() ? columns.name(pair) : "column " + (pair + 1);
        return op.takesText() ? name + " " + op.label + " " + text : name + " " + op.label;
    }

    /** All of them, in words, joined with "and". */
    public static String describe(List<RowFilter> filters, ColumnMap columns) {
        return String.join(" and ", filters.stream().map(f -> f.describe(columns)).toList());
    }

    /**
     * Reads a condition as people write it: {@code region = EU}, {@code region != EU},
     * {@code note contains late}, {@code amount > 100}, {@code note is empty}.
     *
     * @throws IllegalArgumentException if it isn't one, or the column isn't in both files
     */
    public static RowFilter parse(String condition, ColumnMap columns) {
        String c = condition.strip();
        String[][] ops = {
            {" doesn't contain ", "NOT_CONTAINS"}, {" not contains ", "NOT_CONTAINS"}, {" contains ", "CONTAINS"},
            {" isn't empty", "NOT_EMPTY"}, {" is not empty", "NOT_EMPTY"}, {" is empty", "EMPTY"},
            {"!=", "NOT_EQUALS"}, {"≠", "NOT_EQUALS"}, {"<>", "NOT_EQUALS"}, {">=", null}, {"<=", null},
            {"==", "EQUALS"}, {"=", "EQUALS"}, {">", "MORE"}, {"<", "LESS"}};
        String lower = c.toLowerCase(Locale.ROOT);
        for (String[] o : ops) {
            int at = lower.indexOf(o[0]);
            if (at <= 0) {
                continue;
            }
            if (o[1] == null) {
                throw new IllegalArgumentException("Use > or < (not " + o[0] + "): " + condition);
            }
            String column = c.substring(0, at).strip();
            String value = c.substring(at + o[0].length()).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            int pair = columns.pairNamed(column);
            if (pair < 0) {
                throw new IllegalArgumentException("No column " + column + " in both files");
            }
            return new RowFilter(pair, Op.valueOf(o[1]), value);
        }
        throw new IllegalArgumentException("Not a condition like region = EU, amount > 100 or note contains late: "
                + condition);
    }
}
