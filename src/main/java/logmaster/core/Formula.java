package logmaster.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How filters combine, for example {@code (A OR B) AND NOT C}. Letters name filters. Accepts
 * AND / OR / NOT in any case, the symbols {@code & && | || ! -}, and parentheses. NOT binds
 * tightest, then AND, then OR.
 */
public sealed interface Formula {

    record Ref(char id) implements Formula {}

    record Not(Formula a) implements Formula {}

    record And(Formula a, Formula b) implements Formula {}

    record Or(Formula a, Formula b) implements Formula {}

    /** Letters used in this formula. */
    default Set<Character> refs() {
        Set<Character> out = new LinkedHashSet<>();
        collect(this, out);
        return out;
    }

    private static void collect(Formula f, Set<Character> out) {
        switch (f) {
            case Ref r -> out.add(r.id());
            case Not n -> collect(n.a(), out);
            case And x -> {
                collect(x.a(), out);
                collect(x.b(), out);
            }
            case Or x -> {
                collect(x.a(), out);
                collect(x.b(), out);
            }
        }
    }

    /** Readable form, with parentheses only where needed. */
    static String format(Formula f) {
        return format(f, 0);
    }

    private static String format(Formula f, int parentPrec) {
        String s;
        int prec;
        switch (f) {
            case Ref r -> {
                return String.valueOf(r.id());
            }
            case Not n -> {
                return "NOT " + format(n.a(), 3);
            }
            case And x -> {
                prec = 2;
                s = format(x.a(), 2) + " AND " + format(x.b(), 2);
            }
            case Or x -> {
                prec = 1;
                s = format(x.a(), 1) + " OR " + format(x.b(), 1);
            }
        }
        return prec < parentPrec ? "(" + s + ")" : s;
    }

    /**
     * The usual way to combine: a line is kept if it matches any Show filter and no Hide filter.
     * Returns null when there are neither (nothing is filtered).
     */
    static Formula auto(List<Character> shows, List<Character> hides) {
        Formula show = anyOf(shows);
        Formula hide = anyOf(hides);
        if (show == null) {
            return hide == null ? null : new Not(hide);
        }
        return hide == null ? show : new And(show, new Not(hide));
    }

    private static Formula anyOf(List<Character> ids) {
        Formula f = null;
        for (char c : ids) {
            f = f == null ? new Ref(c) : new Or(f, new Ref(c));
        }
        return f;
    }

    /**
     * Parses a formula. Blank text gives null (nothing is filtered).
     *
     * @throws IllegalArgumentException with a readable message if the text is not valid
     */
    static Formula parse(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return null;
        }
        Parser p = new Parser(tokens);
        Formula f = p.or();
        if (p.pos < tokens.size()) {
            throw new IllegalArgumentException("Unexpected “" + tokens.get(p.pos) + "”");
        }
        return f;
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')' || c == '!' || c == '-') {
                out.add(String.valueOf(c));
                i++;
            } else if (c == '&' || c == '|') {
                out.add(String.valueOf(c));
                i += i + 1 < s.length() && s.charAt(i + 1) == c ? 2 : 1;
            } else if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length() && Character.isLetter(s.charAt(j))) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else {
                throw new IllegalArgumentException("Unexpected “" + c + "”");
            }
        }
        return out;
    }

    final class Parser {
        private final List<String> t;
        private int pos;

        private Parser(List<String> tokens) {
            this.t = tokens;
        }

        private boolean accept(String... alternatives) {
            if (pos < t.size()) {
                for (String a : alternatives) {
                    if (t.get(pos).equalsIgnoreCase(a)) {
                        pos++;
                        return true;
                    }
                }
            }
            return false;
        }

        Formula or() {
            Formula f = and();
            while (accept("OR", "|")) {
                f = new Or(f, and());
            }
            return f;
        }

        Formula and() {
            Formula f = unary();
            while (accept("AND", "&")) {
                f = new And(f, unary());
            }
            return f;
        }

        Formula unary() {
            if (accept("NOT", "!", "-")) {
                return new Not(unary());
            }
            if (accept("(")) {
                Formula f = or();
                if (!accept(")")) {
                    throw new IllegalArgumentException("Missing “)”");
                }
                return f;
            }
            if (pos >= t.size()) {
                throw new IllegalArgumentException("Formula ends too early");
            }
            String tok = t.get(pos);
            if (tok.length() == 1 && Character.isLetter(tok.charAt(0))) {
                pos++;
                return new Ref(Character.toUpperCase(tok.charAt(0)));
            }
            if (Character.isLetter(tok.charAt(0))) {
                throw new IllegalArgumentException("Unknown word “" + tok
                        + "” (use filter letters with AND, OR, NOT)");
            }
            throw new IllegalArgumentException("Unexpected “" + tok + "”");
        }
    }
}
