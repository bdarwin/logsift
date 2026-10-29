package logsift.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A filter on one column of a log's format: its value contains the search, is exactly the text,
 * or is a number above or below it. Lines without the format (stack traces, a CSV's header row)
 * don't match; with "Keep stack traces" they follow their entry.
 *
 * @param format the log's format
 * @param column one of its columns
 * @param op how the value is compared
 * @param query the text (or regex) to look for; a number for {@link Op#MORE} and {@link Op#LESS}
 */
public record ColumnFilter(LogFormat format, String column, Op op, SearchQuery query) {

    /** How a column's value is compared with the filter's text. */
    public enum Op {
        CONTAINS("contains", "The value contains the text (Aa, W and .* apply)"),
        EQUALS("=", "The whole value is the text"),
        MORE(">", "The value is a number above this one (7 ms, 1.5 s and 2 KB compare too)"),
        LESS("<", "The value is a number below this one (7 ms, 1.5 s and 2 KB compare too)"),
        BETWEEN("between", "The value is a number from the first to the second: 10..20 (both included), or"
                + " 10..<20 (without 20)");

        public final String label;
        public final String tip;

        Op(String label, String tip) {
            this.label = label;
            this.tip = tip;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * @throws IllegalArgumentException if the column isn't in the format, or the text of a
     *     comparison isn't a number
     * @throws java.util.regex.PatternSyntaxException for an invalid regex
     */
    public ColumnFilter {
        if (!format.columns().contains(column)) {
            throw new IllegalArgumentException("This log has no column “" + column + "”");
        }
        String hint = unitHint(format, column);
        if ((op == Op.MORE || op == Op.LESS) && Double.isNaN(number(query.text(), hint))) {
            throw new IllegalArgumentException("Not a number: " + query.text());
        }
        if (op == Op.BETWEEN && range(query.text(), hint) == null) {
            throw new IllegalArgumentException("Not two numbers like 10..20 or 10..<20: " + query.text());
        }
        if (op == Op.CONTAINS) {
            query.toPattern(); // check it
        }
    }

    /** A number as written, in milliseconds or bytes when it has a unit; NaN if it isn't one. */
    public static double number(String text) {
        return number(text, "");
    }

    /** Like {@link #number(String)}; a number without a unit has the column's ({@code hint}: "ms", "kb"…). */
    static double number(String text, String hint) {
        return text == null ? Double.NaN : Numbers.parse(text, hint, new Numbers.Unit[1]);
    }

    /** The unit a column's name gives its numbers (duration_ms: ms), or "". */
    static String unitHint(LogFormat format, String column) {
        List<String> titles = format.headerNames();
        int i = format.columns().indexOf(column);
        return ColumnMetrics.unitOf(titles != null && i >= 0 ? titles.get(i) : column);
    }

    /**
     * "10..20" as {10, 20, 1} (both included), "10..<20" as {10, 20, 0} (20 left out); null if
     * it isn't one.
     */
    static double[] range(String text, String hint) {
        int dots = text.indexOf("..");
        if (dots < 0) {
            return null;
        }
        String to = text.substring(dots + 2).strip();
        boolean open = to.startsWith("<");
        double a = number(text.substring(0, dots), hint);
        double b = number(open ? to.substring(1) : to, hint);
        return Double.isNaN(a) || Double.isNaN(b) || (open && b < a) ? null
                : new double[] {Math.min(a, b), Math.max(a, b), open ? 0 : 1};
    }

    /** A number in a column's base unit (ms, bytes), written so that filters read it back. */
    public static String write(double v, Numbers.Unit unit) {
        String n = java.math.BigDecimal.valueOf(v).round(new java.math.MathContext(10)).stripTrailingZeros()
                .toPlainString();
        return switch (unit) {
            case MILLIS -> n + " ms";
            case BYTES -> n + " b";
            case PERCENT -> n + "%";
            case NONE -> n;
        };
    }

    /** A matcher for lines of the document (text in {@code cs}); its scans may run in parallel. */
    /** A matcher for a document's lines: a table's rows are read whole. */
    LineMatcher matcher(LogDocument doc) {
        LineMatcher m = matcher(doc.charset());
        if (m instanceof LineMatcher.InColumn c) {
            c.rows = CsvRows.of(doc, format);
        }
        return m;
    }

    LineMatcher matcher(java.nio.charset.Charset cs) {
        Pattern p = op == Op.CONTAINS ? query.toPattern() : null;
        String hint = unitHint(format, column);
        double limit = op == Op.MORE || op == Op.LESS ? number(query.text(), hint) : Double.NaN;
        double[] range = op == Op.BETWEEN ? range(query.text(), hint) : null;
        String text = query.text().strip();
        boolean exact = query.matchCase();
        java.util.function.Predicate<String> test = switch (op) {
            case CONTAINS -> v -> p.matcher(v).find();
            case EQUALS -> v -> exact ? v.strip().equals(text) : v.strip().equalsIgnoreCase(text);
            case MORE -> v -> number(v, hint) > limit;
            case LESS -> v -> number(v, hint) < limit;
            case BETWEEN -> v -> {
                double x = number(v, hint);
                return x >= range[0] && (range[2] == 1 ? x <= range[1] : x < range[1]);
            };
        };
        // a line can only match if it has the text somewhere: a fast byte search finds those first
        boolean literal = (op == Op.EQUALS || op == Op.CONTAINS) && !text.isEmpty()
                && new SearchQuery(text, query.matchCase(), false, false).isPlainBytes()
                && (op == Op.EQUALS || query.isPlainBytes());
        return new LineMatcher.InColumn(format, column, test,
                literal ? new LineMatcher.Literal(text, query.matchCase(), cs) : null, cs);
    }

    /** Whether a line's value in the column passes (for coloring lines on screen). */
    public java.util.function.Predicate<String> lineTest() {
        LineMatcher.InColumn m = (LineMatcher.InColumn) matcher(java.nio.charset.StandardCharsets.UTF_8);
        return m::matchesLine;
    }

    /** "Host = web-1", "Time contains 10:02", "Duration > 100". */
    public String describe() {
        String c = column.isEmpty() ? column : column.substring(0, 1).toUpperCase(Locale.ROOT) + column.substring(1);
        return c + " " + op.label + " " + query.text();
    }
}
