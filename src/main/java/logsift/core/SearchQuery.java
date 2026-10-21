package logsift.core;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What to search for.
 *
 * @param text the search text, or a regular expression when {@code regex} is set
 * @param matchCase case-sensitive matching
 * @param regex treat {@code text} as a Java regular expression
 * @param wholeWord only match whole words
 */
public record SearchQuery(String text, boolean matchCase, boolean regex, boolean wholeWord) {

    public SearchQuery {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Empty search text");
        }
    }

    public static SearchQuery literal(String text) {
        return new SearchQuery(text, false, false, false);
    }

    /**
     * A regex that finds the same matches inside a decoded line. Used for regex search and for
     * highlighting matches on screen.
     *
     * @throws PatternSyntaxException if the regex is invalid
     */
    public Pattern toPattern() {
        String body = regex ? text : Pattern.quote(text);
        if (wholeWord) {
            body = "(?<![\\p{L}\\p{N}_])(?:" + body + ")(?![\\p{L}\\p{N}_])";
        }
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(body, flags);
    }

    /** True when the fast byte-level search can be used instead of a regex. */
    boolean isPlainBytes() {
        return !regex && !wholeWord && (matchCase || isAscii(text));
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }
}
