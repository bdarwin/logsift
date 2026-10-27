package logsift.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hides secrets (and, if asked, emails, IP addresses and the user's own patterns) in text that
 * goes to a coding agent, and counts what it hid. Analyses run on the original text; only what
 * leaves is masked.
 */
public final class Masking {

    /**
     * What to hide.
     *
     * @param secrets passwords, tokens, keys, card numbers (on by default)
     * @param emails  email addresses
     * @param ips     IPv4 and IPv6 addresses
     * @param custom  the user's own regular expressions
     */
    public record Options(boolean secrets, boolean emails, boolean ips, List<String> custom) {
        public static final Options DEFAULT = new Options(true, false, false, List.of());
        public static final Options NONE = new Options(false, false, false, List.of());

        public Options {
            custom = List.copyOf(custom);
        }

        /** "secrets, emails" or "nothing". */
        public String describe() {
            List<String> on = new ArrayList<>();
            if (secrets) {
                on.add("secrets");
            }
            if (emails) {
                on.add("emails");
            }
            if (ips) {
                on.add("IP addresses");
            }
            if (!custom.isEmpty()) {
                on.add(custom.size() + " own pattern" + (custom.size() == 1 ? "" : "s"));
            }
            return on.isEmpty() ? "nothing" : String.join(", ", on);
        }
    }

    /** A rule: what it finds, which group is the secret (0: all of it), and what it is called. */
    private record Rule(Pattern pattern, int group, String label) {}

    private static final List<Rule> SECRETS = List.of(
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
                    0, "private-key"),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), 0, "jwt"),
            new Rule(Pattern.compile("(?i)\\bbearer\\s+([A-Za-z0-9\\-._~+/]{8,}=*)"), 1, "token"),
            new Rule(Pattern.compile("(?i)\\bbasic\\s+([A-Za-z0-9+/]{8,}=*)"), 1, "token"),
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), 0, "aws-key"),
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b|\\bgithub_pat_[A-Za-z0-9_]{30,}\\b"), 0, "token"),
            new Rule(Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"), 0, "token"),
            new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), 0, "api-key"),
            new Rule(Pattern.compile("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}"), 0, "api-key"),
            new Rule(Pattern.compile("(?i)(?:AccountKey|SharedAccessSignature|sig)=([A-Za-z0-9%+/=]{16,})"), 1, "key"),
            // user:password@ in a URL
            new Rule(Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/:@]+:([^\\s/@]+)@"), 1, "password"),
            // password=…, "apiKey": "…", secret: …
            new Rule(Pattern.compile("(?i)[\"']?\\b(?:password|passwd|pwd|secret|client[_-]?secret|api[_-]?key"
                    + "|access[_-]?key|secret[_-]?key|private[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token"
                    + "|session[_-]?token|token|credentials?)[\"']?\\s*[:=]\\s*[\"']?([^\\s\"',;&}\\]]{3,})"), 1, "secret"));

    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
    // full (8 groups) or shortened with "::", with a digit in it ("std::string" and times like 10:00:01 aren't)
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![0-9a-z:.])(?=[0-9a-f:]*\\d)"
            + "(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
            + "|(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?)"
            + "(?![0-9a-z:])");

    private final Options options;
    private final List<Rule> rules = new ArrayList<>();
    private final List<String> badPatterns = new ArrayList<>();
    private final AtomicLong total = new AtomicLong();

    public Masking(Options options) {
        this.options = options;
        if (options.secrets()) {
            rules.addAll(SECRETS);
        }
        if (options.emails()) {
            rules.add(new Rule(EMAIL, 0, "email"));
        }
        if (options.ips()) {
            rules.add(new Rule(IPV4, 0, "ip"));
            rules.add(new Rule(IPV6, 0, "ip"));
        }
        for (String c : options.custom()) {
            try {
                rules.add(new Rule(Pattern.compile(c), 0, "custom"));
            } catch (PatternSyntaxException e) {
                badPatterns.add(c);
            }
        }
    }

    public Options options() {
        return options;
    }

    /** Own patterns that aren't valid regular expressions (not used). */
    public List<String> badPatterns() {
        return List.copyOf(badPatterns);
    }

    /** How many values were hidden so far. */
    public long total() {
        return total.get();
    }

    /** Text with its secrets hidden as {@code ‹masked:jwt›}; counts into {@code count} (may be null). */
    public String mask(String text, AtomicLong count) {
        if (text == null || text.isEmpty() || (rules.isEmpty() && !options.secrets())) {
            return text;
        }
        String s = text;
        long n = 0;
        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(s);
            if (!m.find()) {
                continue;
            }
            StringBuilder out = new StringBuilder(s.length());
            int last = 0;
            do {
                int a = r.group() == 0 ? m.start() : m.start(r.group());
                int b = r.group() == 0 ? m.end() : m.end(r.group());
                if (a < 0 || s.startsWith("‹masked:", a)) {
                    continue;
                }
                out.append(s, last, a).append("‹masked:").append(r.label()).append('›');
                last = b;
                n++;
            } while (m.find());
            s = out.append(s, last, s.length()).toString();
        }
        if (options.secrets()) {
            Matcher m = CARD.matcher(s);
            StringBuilder out = null;
            int last = 0;
            while (m.find()) {
                String digits = m.group().replaceAll("[ -]", "");
                if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits) && !allSame(digits)) {
                    if (out == null) {
                        out = new StringBuilder(s.length());
                    }
                    out.append(s, last, m.start()).append("‹masked:card›");
                    last = m.end();
                    n++;
                }
            }
            if (out != null) {
                s = out.append(s, last, s.length()).toString();
            }
        }
        if (n > 0) {
            total.addAndGet(n);
            if (count != null) {
                count.addAndGet(n);
            }
        }
        return s;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static boolean allSame(String digits) {
        return digits.chars().distinct().count() == 1;
    }
}
