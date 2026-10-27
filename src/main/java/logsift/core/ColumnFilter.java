package logsift.core;

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
        LESS("<", "The value is a number below this one (7 ms, 1.5 s and 2 KB compare too)");

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
        if ((op == Op.MORE || op == Op.LESS) && Double.isNaN(number(query.text()))) {
            throw new IllegalArgumentException("Not a number: " + query.text());
        }
        if (op == Op.CONTAINS) {
            query.toPattern(); // check it
        }
    }

    /** A number as written, in milliseconds or bytes when it has a unit; NaN if it isn't one. */
    public static double number(String text) {
        return text == null ? Double.NaN : Numbers.parse(text, "", new Numbers.Unit[1]);
    }

    /** A matcher for lines of the document (text in {@code cs}); its scans may run in parallel. */
    LineMatcher matcher(java.nio.charset.Charset cs) {
        Pattern p = op == Op.CONTAINS ? query.toPattern() : null;
        double limit = op == Op.MORE || op == Op.LESS ? number(query.text()) : Double.NaN;
        String text = query.text().strip();
        boolean exact = query.matchCase();
        java.util.function.Predicate<String> test = switch (op) {
            case CONTAINS -> v -> p.matcher(v).find();
            case EQUALS -> v -> exact ? v.strip().equals(text) : v.strip().equalsIgnoreCase(text);
            case MORE -> v -> number(v) > limit;
            case LESS -> v -> number(v) < limit;
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
