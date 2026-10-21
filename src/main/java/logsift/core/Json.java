package logsift.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for log lines: objects keep their key order, numbers are kept as their
 * text, and anything malformed gives null instead of an exception. Also pretty-prints.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** The JSON object in {@code text}, or null if it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (!t.startsWith("{")) {
            return null;
        }
        Json p = new Json(t);
        try {
            Object v = p.value();
            p.space();
            return v instanceof Map<?, ?> m && p.i == t.length() ? (Map<String, Object>) m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A number, kept as written. */
    public record Num(String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private Object value() {
        space();
        char c = s.charAt(i);
        switch (c) {
            case '{' -> {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                space();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    space();
                    String k = string();
                    space();
                    expect(':');
                    m.put(k, value());
                    space();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect('}');
                        return m;
                    }
                }
            }
            case '[' -> {
                i++;
                List<Object> l = new ArrayList<>();
                space();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    space();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect(']');
                        return l;
                    }
                }
            }
            case '"' -> {
                return string();
            }
            case 't' -> {
                word("true");
                return Boolean.TRUE;
            }
            case 'f' -> {
                word("false");
                return Boolean.FALSE;
            }
            case 'n' -> {
                word("null");
                return null;
            }
            default -> {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("bad value at " + i);
                }
                return new Num(s.substring(start, i));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private void word(String w) {
        if (!s.startsWith(w, i)) {
            throw new IllegalArgumentException("expected " + w);
        }
        i += w.length();
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new IllegalArgumentException("expected " + c + " at " + i);
        }
        i++;
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    /** Short text for a value in a column: strings as is, others as JSON. */
    public static String display(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String str) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        write(v, sb, -1, 0);
        return sb.toString();
    }

    /** Pretty-printed JSON (2-space indent). */
    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, 2, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, int indent, int depth) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, depth + 1);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(indent < 0 ? ":" : ": ");
                write(e.getValue(), sb, indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                newline(sb, indent, depth + 1);
                write(l.get(k), sb, indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append(']');
        } else if (v instanceof String str) {
            quote(str, sb);
        } else {
            sb.append(v);
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent >= 0) {
            sb.append('\n').append(" ".repeat(indent * depth));
        }
    }

    private static void quote(String str, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
