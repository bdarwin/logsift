package logmaster.core;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds matching lines in a buffer of whole lines. The buffer starts at a line start; line
 * numbers are counted from {@code firstLine}.
 */
abstract sealed class LineMatcher {

    interface HitSink {
        /** Returns false to stop (for example, when the hit limit is reached). */
        boolean hit(long line);
    }

    static LineMatcher of(SearchQuery q) {
        if (q.isPlainBytes()) {
            return new Literal(q.text(), q.matchCase());
        }
        Pattern p = q.toPattern();
        String required = RequiredLiteral.of(q);
        if (required != null) {
            // Find candidate lines by fast byte search, confirm each with the regex.
            return new Prefiltered(new Literal(required, q.matchCase()), p);
        }
        return new Regex(p);
    }

    abstract void scan(byte[] buf, int len, long firstLine, HitSink sink, BooleanSupplier cancelled);

    /**
     * Plain text search on raw UTF-8 bytes. Searches the whole buffer for the text and only then
     * works out which line it is in, so lines without a hit cost almost nothing.
     */
    static final class Literal extends LineMatcher {
        private final byte[] needle;
        private final boolean ignoreCase;

        Literal(String text, boolean matchCase) {
            this.ignoreCase = !matchCase;
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            if (ignoreCase) {
                for (int i = 0; i < b.length; i++) {
                    b[i] = lower(b[i]);
                }
            }
            this.needle = b;
        }

        private static byte lower(byte b) {
            return b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b;
        }

        private static byte upper(byte b) {
            return b >= 'a' && b <= 'z' ? (byte) (b - 32) : b;
        }

        /** Position of the next occurrence in {@code buf[from, to)}, or -1. */
        int find(byte[] buf, int from, int to) {
            int m = needle.length;
            int last = to - m; // last possible start
            byte first = needle[0];
            byte firstUpper = upper(first);
            int i = from;
            while (i <= last) {
                i = ignoreCase
                        ? ByteScan.indexOfEither(buf, i, last + 1, first, firstUpper)
                        : ByteScan.indexOf(buf, i, last + 1, first);
                if (i < 0) {
                    return -1;
                }
                if (matchesAt(buf, i)) {
                    return i;
                }
                i++;
            }
            return -1;
        }

        private boolean matchesAt(byte[] buf, int at) {
            if (ignoreCase) {
                for (int k = 1; k < needle.length; k++) {
                    if (lower(buf[at + k]) != needle[k]) {
                        return false;
                    }
                }
            } else {
                for (int k = 1; k < needle.length; k++) {
                    if (buf[at + k] != needle[k]) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        void scan(byte[] buf, int len, long firstLine, HitSink sink, BooleanSupplier cancelled) {
            scanCandidates(buf, len, firstLine, null, sink, cancelled);
        }

        /**
         * Reports lines containing the text. If {@code confirm} is given, a line is only
         * reported when it also passes that check (used for regex confirmation).
         */
        void scanCandidates(byte[] buf, int len, long firstLine, Regex confirm, HitSink sink,
                            BooleanSupplier cancelled) {
            int lineStart = 0;
            long line = firstLine;
            int checks = 0;
            while (lineStart < len) {
                int hit = find(buf, lineStart, len);
                if (hit < 0) {
                    return;
                }
                int nlBefore = ByteScan.count(buf, lineStart, hit, (byte) '\n');
                if (nlBefore > 0) {
                    line += nlBefore;
                    lineStart = ByteScan.lastIndexOf(buf, lineStart, hit, (byte) '\n') + 1;
                }
                int nl = ByteScan.indexOf(buf, hit, len, (byte) '\n');
                int lineEnd = nl < 0 ? len : nl;
                if ((confirm == null || confirm.matches(buf, lineStart, lineEnd))
                        && !sink.hit(line)) {
                    return;
                }
                if (nl < 0) {
                    return;
                }
                lineStart = nl + 1;
                line++;
                if ((++checks & 1023) == 0 && cancelled.getAsBoolean()) {
                    return;
                }
            }
        }
    }

    /** Byte search for text every match must contain, then a regex check of that line. */
    static final class Prefiltered extends LineMatcher {
        private final Literal literal;
        private final Pattern pattern;

        Prefiltered(Literal literal, Pattern pattern) {
            this.literal = literal;
            this.pattern = pattern;
        }

        @Override
        void scan(byte[] buf, int len, long firstLine, HitSink sink, BooleanSupplier cancelled) {
            literal.scanCandidates(buf, len, firstLine, new Regex(pattern), sink, cancelled);
        }
    }

    /** Regular expression search, one decoded line at a time. */
    static final class Regex extends LineMatcher {
        private final Pattern pattern;
        private Matcher matcher; // per scan; a Regex instance is used by one thread

        Regex(Pattern pattern) {
            this.pattern = pattern;
        }

        /** Whether the line {@code buf[start, end)} (end excludes the newline) matches. */
        boolean matches(byte[] buf, int start, int end) {
            if (end > start && buf[end - 1] == '\r') {
                end--;
            }
            String s = new String(buf, start, end - start, StandardCharsets.UTF_8);
            if (matcher == null) {
                matcher = pattern.matcher(s);
            } else {
                matcher.reset(s);
            }
            return matcher.find();
        }

        @Override
        void scan(byte[] buf, int len, long firstLine, HitSink sink, BooleanSupplier cancelled) {
            Matcher m = pattern.matcher("");
            int pos = 0;
            long line = firstLine;
            while (pos < len) {
                int nl = ByteScan.indexOf(buf, pos, len, (byte) '\n');
                int end = nl < 0 ? len : nl;
                int textEnd = end > pos && buf[end - 1] == '\r' ? end - 1 : end;
                String s = new String(buf, pos, textEnd - pos, StandardCharsets.UTF_8);
                if (m.reset(s).find() && !sink.hit(line)) {
                    return;
                }
                if (nl < 0) {
                    return;
                }
                pos = nl + 1;
                line++;
                if ((line & 4095) == 0 && cancelled.getAsBoolean()) {
                    return;
                }
            }
        }
    }
}
