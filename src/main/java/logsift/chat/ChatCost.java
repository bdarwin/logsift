package logsift.chat;

import java.util.Locale;

/**
 * What answers cost, in the money each assistant tells it in: AI credits for Copilot, dollars at
 * API prices for Claude Code (a Claude subscription doesn't charge them, but they say how much was
 * read and written). Both are kept as billionths; a model's id tells which ({@link ClaudeEngine#PREFIX}).
 */
public final class ChatCost {
    private ChatCost() {
    }

    /** Whether what this model cost is in dollars. */
    public static boolean dollars(String model) {
        return model != null && model.startsWith(ClaudeEngine.PREFIX);
    }

    /** "0.08 credits", "1 credit", "$0.03", "<$0.01". */
    public static String format(String model, double nano) {
        double c = nano / 1e9;
        if (dollars(model)) {
            if (c < 0.01) {
                return "<$0.01";
            }
            return c < 100 ? String.format(Locale.ROOT, "$%.2f", c) : String.format(Locale.ROOT, "$%,.0f", c);
        }
        if (c < 0.01) {
            return "<0.01 credits";
        }
        return (c < 10 ? String.format(Locale.ROOT, "%.2f", c) : String.format(Locale.ROOT, "%,.0f", c))
                + (c == 1 ? " credit" : " credits");
    }

    /** A model's name for people when the engine's list doesn't have it: "claude-code/claude-haiku-5-5" → "Claude Haiku 5.5". */
    public static String name(String model) {
        if (model == null) {
            return null;
        }
        String m = dollars(model) ? model.substring(ClaudeEngine.PREFIX.length()) : model;
        if (!dollars(model) || !m.startsWith("claude-")) {
            return m;
        }
        String[] parts = m.substring("claude-".length()).split("-");
        StringBuilder b = new StringBuilder("Claude");
        StringBuilder version = new StringBuilder();
        for (String p : parts) {
            if (p.matches("\\d{1,2}")) {
                version.append(version.length() == 0 ? "" : ".").append(p);
            } else if (!p.matches("\\d{8}")) { // a date at the end
                b.append(' ').append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return version.length() == 0 ? b.toString() : b + " " + version;
    }

    /** What answers by several models cost in all: credits and dollars apart ("0.31 credits · $0.04"). */
    public static String total(java.util.List<ChatStore.Usage> usage) {
        double credits = 0;
        double dollars = 0;
        for (ChatStore.Usage u : usage) {
            if (dollars(u.model())) {
                dollars += u.nanoAiu();
            } else {
                credits += u.nanoAiu();
            }
        }
        if (dollars > 0 && credits == 0) {
            return format(ClaudeEngine.PREFIX, dollars);
        }
        String c = format(null, credits);
        return dollars > 0 ? c + " · " + format(ClaudeEngine.PREFIX, dollars) : c;
    }
}
