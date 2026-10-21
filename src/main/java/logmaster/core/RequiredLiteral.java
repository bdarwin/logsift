package logmaster.core;

/**
 * Finds a piece of plain text that every match of a query must contain. Search then scans the
 * raw bytes for that text (fast) and runs the regex only on lines that contain it. This is the
 * trick that makes tools like ripgrep fast.
 *
 * <p>The extraction is deliberately conservative: when unsure, it returns null and the search
 * falls back to running the regex on every line.
 */
final class RequiredLiteral {
    private RequiredLiteral() {}

    /** Minimum length worth using; shorter ones match too often to help. */
    private static final int MIN_LENGTH = 3;

    /**
     * Returns text that every match must contain, or null. When {@code matchCase} is false the
     * result is pure ASCII and safe for an ASCII-only case-insensitive byte search.
     */
    static String of(SearchQuery q) {
        String lit = q.regex() ? fromRegex(q.text()) : q.text();
        if (lit == null) {
            return null;
        }
        if (!q.matchCase()) {
            lit = longestCaseSafeRun(lit);
        }
        return lit != null && lit.length() >= MIN_LENGTH ? lit : null;
    }

    /**
     * Longest run of characters that an ASCII case-insensitive byte search handles exactly like
     * Java's Unicode case-insensitive regex. Non-ASCII characters are excluded, and so are k, s
     * and i, because the Kelvin sign, long s and Turkish dotted/dotless i fold to them.
     */
    static String longestCaseSafeRun(String s) {
        String best = null;
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            boolean safe = i < s.length() && isCaseSafe(s.charAt(i));
            if (!safe) {
                if (i - start > (best == null ? 0 : best.length())) {
                    best = s.substring(start, i);
                }
                start = i + 1;
            }
        }
        return best;
    }

    private static boolean isCaseSafe(char c) {
        if (c >= 0x80) {
            return false;
        }
        char l = Character.toLowerCase(c);
        return l != 'k' && l != 's' && l != 'i';
    }

    /**
     * Longest run of literal characters outside groups, classes and escapes, in a regex without
     * top-level alternation. Returns null if the regex uses constructs we do not analyse.
     */
    static String fromRegex(String re) {
        if (re.contains("(?")) {
            return null; // inline flags, lookarounds, non-capturing groups: don't guess
        }
        for (String multi : new String[] {"\\Q", "\\E", "\\x", "\\u", "\\0", "\\c", "\\p",
            "\\P", "\\k", "\\N"}) {
            if (re.contains(multi)) {
                return null; // escapes longer than two characters: don't guess
            }
        }
        String best = "";
        StringBuilder run = new StringBuilder();
        int i = 0;
        int n = re.length();
        while (i < n) {
            char c = re.charAt(i);
            if (c == '|') {
                return null; // top-level alternative: no single required text
            }
            if (c == '(') {
                int end = skipGroup(re, i);
                if (end < 0) {
                    return null;
                }
                best = longer(best, run);
                run.setLength(0);
                i = skipQuantifier(re, end);
                continue;
            }
            if (c == '[') {
                int end = skipClass(re, i);
                if (end < 0) {
                    return null;
                }
                best = longer(best, run);
                run.setLength(0);
                i = skipQuantifier(re, end);
                continue;
            }
            if (c == '\\') {
                if (i + 1 >= n) {
                    return null;
                }
                char e = re.charAt(i + 1);
                if (!Character.isLetterOrDigit(e)) {
                    // escaped punctuation like \. or \[ is a literal character
                    run.append(e);
                    i += 2;
                    continue;
                }
                // a class like \d or \w, an anchor like \b, or a back reference like \1
                best = longer(best, run);
                run.setLength(0);
                i += 2;
                while (Character.isDigit(e) && i < n && Character.isDigit(re.charAt(i))) {
                    i++;
                }
                i = skipQuantifier(re, i);
                continue;
            }
            if (".^$)*+?{}]".indexOf(c) >= 0) {
                if (c == '*' || c == '?' || c == '{') {
                    // previous char was optional/repeated: it is not required
                    if (run.length() > 0) {
                        run.setLength(run.length() - 1);
                    }
                    best = longer(best, run);
                    run.setLength(0);
                    i = c == '{' ? skipBraces(re, i) : i + 1;
                    if (i < n && (re.charAt(i) == '?' || re.charAt(i) == '+')) {
                        i++;
                    }
                    continue;
                }
                if (c == '+') {
                    // "ab+" still requires "ab"; but text after it is not contiguous
                    best = longer(best, run);
                    run.setLength(0);
                    i++;
                    if (i < n && (re.charAt(i) == '?' || re.charAt(i) == '+')) {
                        i++;
                    }
                    continue;
                }
                best = longer(best, run);
                run.setLength(0);
                i++;
                continue;
            }
            run.append(c);
            i++;
        }
        best = longer(best, run);
        return best.isEmpty() ? null : best;
    }

    private static String longer(String best, CharSequence run) {
        return run.length() > best.length() ? run.toString() : best;
    }

    private static int skipGroup(String re, int open) {
        int depth = 0;
        for (int i = open; i < re.length(); i++) {
            char c = re.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                int end = skipClass(re, i);
                if (end < 0) {
                    return -1;
                }
                i = end - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static int skipClass(String re, int open) {
        int i = open + 1;
        if (i < re.length() && re.charAt(i) == '^') {
            i++;
        }
        if (i < re.length() && re.charAt(i) == ']') {
            i++; // ']' first in a class is literal
        }
        int depth = 1;
        for (; i < re.length(); i++) {
            char c = re.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                depth++; // nested class like [a-z&&[^b]]
            } else if (c == ']') {
                if (--depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static int skipBraces(String re, int open) {
        int close = re.indexOf('}', open);
        return close < 0 ? re.length() : close + 1;
    }

    private static int skipQuantifier(String re, int i) {
        if (i < re.length()) {
            char c = re.charAt(i);
            if (c == '*' || c == '+' || c == '?') {
                i++;
            } else if (c == '{') {
                i = skipBraces(re, i);
            } else {
                return i;
            }
            if (i < re.length() && (re.charAt(i) == '?' || re.charAt(i) == '+')) {
                i++;
            }
        }
        return i;
    }
}
