package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

class RequiredLiteralTest {

    @Test
    void extractsFromRegex() {
        assertEquals("Payment failed ", RequiredLiteral.fromRegex("Payment failed .* user=u12\\d4 "));
        assertEquals("timeout", RequiredLiteral.fromRegex("^timeout$"));
        assertEquals("a.b", RequiredLiteral.fromRegex("a\\.b"));
        assertEquals("ORDE", RequiredLiteral.fromRegex("[A-Z]+ORDER{2}"));
        assertEquals("ab", RequiredLiteral.fromRegex("ab+c"));
        assertEquals("connect", RequiredLiteral.fromRegex("(re)?connect(ed|ing)?"));
        assertEquals("user=", RequiredLiteral.fromRegex("user=\\d+"));
        assertNull(RequiredLiteral.fromRegex("ERROR|WARN"));
        assertNull(RequiredLiteral.fromRegex("(?i)error"));
        assertNull(RequiredLiteral.fromRegex("\\x41BC"));
        assertNull(RequiredLiteral.fromRegex("\\d+"));
    }

    @Test
    void ignoreCaseUsesOnlySafeAscii() {
        assertEquals("ller", RequiredLiteral.of(SearchQuery.literal("müller")));
        assertEquals("ERROR", RequiredLiteral.of(new SearchQuery("ERROR", false, false, true)));
        // k, s and i are excluded because of Kelvin sign, long s and Turkish i
        assertEquals("tac", RequiredLiteral.longestCaseSafeRun("stack"));
        assertNull(RequiredLiteral.of(SearchQuery.literal("ski")));
    }

    /** Whatever the regex matches must contain the extracted text. */
    @Test
    void everyMatchContainsTheLiteral() {
        List<String> regexes = List.of("Payment failed .* user=u12\\d4 ", "(ERROR|WARN) user",
                "ord(er)?", "x+y", "a\\.b", "[A-Z]+ORDER{2}", "Ord[a-z]r", "error\\b", "\\berr",
                "user=\\d{2,}", "a(b(c)d)e", "[]a]bc", "[^\\]]xyz", "ab*c", "abc?d", "x{2}yy");
        Random rnd = new Random(2);
        String alphabet = "abcdexyzERORWANuser=0123456789 .]ODlp";
        for (String re : regexes) {
            for (boolean matchCase : new boolean[] {true, false}) {
                SearchQuery q = new SearchQuery(re, matchCase, true, false);
                String lit = RequiredLiteral.of(q);
                if (lit == null) {
                    continue;
                }
                Matcher m = q.toPattern().matcher("");
                for (int i = 0; i < 20_000; i++) {
                    StringBuilder sb = new StringBuilder();
                    int len = rnd.nextInt(30);
                    for (int k = 0; k < len; k++) {
                        sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                    }
                    // sometimes plant a real match
                    if (rnd.nextInt(4) == 0) {
                        sb.insert(rnd.nextInt(sb.length() + 1), lit);
                    }
                    String s = sb.toString();
                    if (m.reset(s).find()) {
                        String hay = matchCase ? s : s.toLowerCase(Locale.ROOT);
                        String needle = matchCase ? lit : lit.toLowerCase(Locale.ROOT);
                        assertTrue(hay.contains(needle), re + " matched '" + s + "' without '" + lit + "'");
                    }
                }
            }
        }
    }
}
