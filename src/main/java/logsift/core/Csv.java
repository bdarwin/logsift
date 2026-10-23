package logsift.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one CSV line into fields: a field may be quoted ({@code "a, b"}), with {@code ""}
 * for a quote inside. Spaces around a quoted field are allowed.
 */
final class Csv {
    private Csv() {}

    /** How many fields a line has (a quote left open takes the rest of the line). */
    static int fieldCount(String line, char delimiter) {
        return split(line, delimiter, null).size();
    }

    /**
     * The fields of a line, unquoted. {@code starts} (or null) receives where each field's text
     * begins in the line, as far as it has room.
     */
    static List<String> split(String line, char delimiter, int[] starts) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int len = line.length();
        while (true) {
            int start = i;
            while (start < len && line.charAt(start) == ' ' && delimiter != ' ') {
                start++;
            }
            StringBuilder sb = new StringBuilder();
            int textStart;
            if (start < len && line.charAt(start) == '"') {
                textStart = start + 1;
                int j = start + 1;
                while (j < len) {
                    char c = line.charAt(j);
                    if (c == '"') {
                        if (j + 1 < len && line.charAt(j + 1) == '"') {
                            sb.append('"');
                            j += 2;
                            continue;
                        }
                        j++;
                        break;
                    }
                    sb.append(c);
                    j++;
                }
                // anything after the closing quote, up to the separator, belongs to the field
                while (j < len && line.charAt(j) != delimiter) {
                    if (line.charAt(j) != ' ') {
                        sb.append(line.charAt(j));
                    }
                    j++;
                }
                i = j;
            } else {
                textStart = i;
                int j = i;
                while (j < len && line.charAt(j) != delimiter) {
                    j++;
                }
                sb.append(line, i, j);
                i = j;
            }
            if (starts != null && out.size() < starts.length) {
                starts[out.size()] = textStart;
            }
            out.add(sb.toString());
            if (i >= len) {
                break;
            }
            i++; // the separator
            if (i == len) {
                if (starts != null && out.size() < starts.length) {
                    starts[out.size()] = len;
                }
                out.add(""); // a separator at the end: one more, empty, field
                break;
            }
        }
        return out;
    }
}
