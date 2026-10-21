package logsift.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Helpers that build tricky file contents and split them the simple, obviously-correct way. */
final class TestFiles {
    private TestFiles() {}

    /** Random lines: empty lines, CRLF, long lines, unicode, optional missing final newline. */
    static String randomContent(Random rnd, int lines) {
        StringBuilder sb = new StringBuilder();
        String[] words = {"ERROR", "error", "WARN", "info", "Exception", "user=42", "äöü", "日本",
            "timeout", "x", "", "Order", "ORDER"};
        for (int i = 0; i < lines; i++) {
            int kind = rnd.nextInt(20);
            if (kind == 0) {
                // empty line
            } else if (kind == 1) {
                sb.append("x".repeat(rnd.nextInt(5000))); // long line
            } else {
                int n = 1 + rnd.nextInt(8);
                for (int w = 0; w < n; w++) {
                    sb.append(words[rnd.nextInt(words.length)]).append(' ');
                }
            }
            sb.append(rnd.nextInt(6) == 0 ? "\r\n" : "\n");
        }
        if (rnd.nextBoolean() && sb.length() > 0) {
            sb.setLength(sb.length() - 1); // no trailing newline (may leave a lone \r)
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("tail-without-newline");
        }
        return sb.toString();
    }

    /** Splits like the index should: lines end at \n, a trailing \r is dropped. */
    static List<String> naiveLines(byte[] content) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\n') {
                out.add(decode(content, start, i));
                start = i + 1;
            }
        }
        if (start < content.length) {
            out.add(decode(content, start, content.length));
        }
        return out;
    }

    /** Byte offset of each line start. */
    static List<Long> naiveStarts(byte[] content) {
        List<Long> out = new ArrayList<>();
        if (content.length > 0) {
            out.add(0L);
        }
        for (int i = 0; i < content.length - 1; i++) {
            if (content[i] == '\n') {
                out.add((long) i + 1);
            }
        }
        return out;
    }

    private static String decode(byte[] b, int from, int to) {
        if (to > from && b[to - 1] == '\r') {
            to--;
        }
        return new String(b, from, to - from, StandardCharsets.UTF_8);
    }
}
