package logsift.ui;

import java.awt.Color;

/**
 * The assistant's colors: its own (violet), and one per kind of tool, so a chat reads at a
 * glance: what was searched, read, counted or asked of a JVM. Each in a light and a dark shade.
 */
final class ChatColors {
    private ChatColors() {
    }

    /** The assistant's own color: its sparkles, headings, the chat chosen. */
    static Color accent() {
        return Theme.pick(new Color(0x7C3AED), new Color(0xA78BFA));
    }

    /** A pale wash of the accent (a table's header, the chosen chat). */
    static Color accentWash() {
        return Theme.pick(new Color(0xF1ECFE), new Color(0x2F2A44));
    }

    static Color ok() {
        return Theme.pick(new Color(0x16A34A), new Color(0x4ADE80));
    }

    static Color code() {
        return Theme.pick(new Color(0xB4235A), new Color(0xF38BA8));
    }

    /** What a tool looks like: its icon and color. */
    record Look(String icon, Color color) {}

    static Look tool(String name) {
        String n = name == null ? "" : name;
        if (n.startsWith("search")) {
            return new Look("search", Theme.pick(new Color(0x2F6FDB), new Color(0x6CA4F8)));
        }
        return switch (n) {
            case "read_lines" -> new Look("file-text", Theme.pick(new Color(0x0E9384), new Color(0x3CCBB4)));
            case "query", "query_examples" -> new Look("braces", accent());
            case "insights", "analyze_gc" -> new Look("lightbulb", Theme.pick(new Color(0xD97706), new Color(0xFBBF24)));
            case "exceptions", "analyze_crash", "crash_modules", "crash_threads" -> new Look("bug", Theme.pick(new Color(0xDC2626), new Color(0xF87171)));
            case "list_logs", "list_open", "open_log" ->
                    new Look("folder-search", Theme.pick(new Color(0xEA580C), new Color(0xFB923C)));
            case "metrics", "top_messages", "pivot" ->
                    new Look("chart-column", Theme.pick(new Color(0x0284C7), new Color(0x38BDF8)));
            case "follow_request", "merge_logs", "compare_logs", "compare_files", "compare_folders" -> new Look("route", Theme.pick(new Color(0xDB2777), new Color(0xF472B6)));
            case "analyze_profile" -> new Look("flame", Theme.pick(new Color(0xE8590C), new Color(0xFF8A4C)));
            case "list_jvms", "thread_dump", "analyze_threads" -> new Look("cpu", Theme.pick(new Color(0x4F46E5), new Color(0x818CF8)));
            case "heap_histogram", "analyze_heap_dump" -> new Look("memory-stick", Theme.pick(new Color(0x4F46E5), new Color(0x818CF8)));
            default -> new Look("wrench", Theme.dimmedForeground());
        };
    }

    /** Colors that take turns (the welcome's example questions). */
    static Color turn(int i) {
        Color[][] c = {
                {new Color(0x7C3AED), new Color(0xA78BFA)},
                {new Color(0x2F6FDB), new Color(0x6CA4F8)},
                {new Color(0x0E9384), new Color(0x3CCBB4)},
                {new Color(0xD97706), new Color(0xFBBF24)},
                {new Color(0xDB2777), new Color(0xF472B6)},
        };
        Color[] p = c[Math.floorMod(i, c.length)];
        return Theme.pick(p[0], p[1]);
    }
}
