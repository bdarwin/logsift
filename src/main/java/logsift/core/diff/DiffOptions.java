package logsift.core.diff;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * When two values count as equal.
 *
 * @param trim spaces at either end don't count
 * @param ignoreCase upper and lower case are the same
 * @param numbersAsNumbers values that are both numbers are compared as numbers ("1.50" = "1.5")
 * @param tolerance numbers this far apart are equal (0: exactly equal)
 * @param tolerancePercent the tolerance is a percentage of the larger of the two
 * @param emptyEqualsMissing an empty value equals one that isn't there (a short row)
 * @param ignoreRowOrder without keys: match equal rows wherever they are, not in file order
 */
public record DiffOptions(boolean trim, boolean ignoreCase, boolean numbersAsNumbers, double tolerance,
                          boolean tolerancePercent, boolean emptyEqualsMissing, boolean ignoreRowOrder) {

    public static final DiffOptions DEFAULT = new DiffOptions(true, false, true, 0, false, true, false);

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d{1,3})?");

    public DiffOptions withTrim(boolean on) {
        return new DiffOptions(on, ignoreCase, numbersAsNumbers, tolerance, tolerancePercent, emptyEqualsMissing,
                ignoreRowOrder);
    }

    public DiffOptions withIgnoreCase(boolean on) {
        return new DiffOptions(trim, on, numbersAsNumbers, tolerance, tolerancePercent, emptyEqualsMissing,
                ignoreRowOrder);
    }

    public DiffOptions withNumbers(boolean on, double tol, boolean percent) {
        return new DiffOptions(trim, ignoreCase, on, Math.max(0, tol), percent, emptyEqualsMissing, ignoreRowOrder);
    }

    public DiffOptions withEmptyEqualsMissing(boolean on) {
        return new DiffOptions(trim, ignoreCase, numbersAsNumbers, tolerance, tolerancePercent, on, ignoreRowOrder);
    }

    public DiffOptions withIgnoreRowOrder(boolean on) {
        return new DiffOptions(trim, ignoreCase, numbersAsNumbers, tolerance, tolerancePercent, emptyEqualsMissing, on);
    }

    /** A key part as it is matched: spaces and case as the options say (numbers stay text). */
    public String keyOf(String v) {
        if (v == null) {
            return "";
        }
        String s = trim ? v.strip() : v;
        return ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
    }

    /**
     * The value as it is hashed: equal hashes mean equal values. Numbers are written the same
     * whatever their zeros ("1.50" and "1.5"); null is a value that isn't there.
     */
    String canonical(String v) {
        if (v == null) {
            return emptyEqualsMissing ? "" : null;
        }
        String s = trim ? v.strip() : v;
        if (numbersAsNumbers) {
            BigDecimal d = number(s);
            if (d != null) {
                return "\u0002" + (d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
            }
        }
        return ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** Whether two values (null: not there) are equal with these options. */
    public boolean same(String a, String b) {
        if (a == null || b == null) {
            if (emptyEqualsMissing) {
                return (a == null || (trim ? a.isBlank() : a.isEmpty())) && (b == null || (trim ? b.isBlank() : b.isEmpty()));
            }
            return a == null && b == null;
        }
        String x = trim ? a.strip() : a;
        String y = trim ? b.strip() : b;
        if (ignoreCase ? x.equalsIgnoreCase(y) : x.equals(y)) {
            return true;
        }
        if (numbersAsNumbers) {
            BigDecimal p = number(x);
            BigDecimal q = p == null ? null : number(y);
            if (q != null) {
                if (p.compareTo(q) == 0) {
                    return true;
                }
                if (tolerance > 0) {
                    double diff = p.subtract(q).abs().doubleValue();
                    double allowed = tolerancePercent
                            ? tolerance / 100 * Math.max(Math.abs(p.doubleValue()), Math.abs(q.doubleValue()))
                            : tolerance;
                    return diff <= allowed * (1 + 1e-12);
                }
            }
        }
        return false;
    }

    static BigDecimal number(String s) {
        if (s.isEmpty() || s.length() > 60 || !NUMBER.matcher(s).matches()) {
            return null;
        }
        try {
            return new BigDecimal(s.startsWith("+") ? s.substring(1) : s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
