package logsift.ui;

import java.awt.Color;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Colors a whole line when it matches its pattern (or, for a filter on a column, passes its
 * test). The first matching rule wins. Colors may be null to keep the default.
 */
record HighlightRule(String name, Pattern pattern, java.util.function.Predicate<String> test,
                     Color fgLight, Color bgLight, Color fgDark, Color bgDark) {

    HighlightRule(String name, Pattern pattern, Color fgLight, Color bgLight, Color fgDark, Color bgDark) {
        this(name, pattern, null, fgLight, bgLight, fgDark, bgDark);
    }

    /** Whether a line gets this rule's colors ({@code m}: a matcher of the pattern, or null). */
    boolean matches(String line, java.util.regex.Matcher m) {
        return test != null ? test.test(line) : m.reset(line).find();
    }

    Color foreground() {
        return Theme.isDark() ? fgDark : fgLight;
    }

    Color background() {
        return Theme.isDark() ? bgDark : bgLight;
    }

    static List<HighlightRule> defaults() {
        return List.of(
                new HighlightRule("Error", Pattern.compile("\\b(ERROR|FATAL|SEVERE|CRITICAL)\\b"),
                        new Color(0x9B1C1C), new Color(0xFDE2E2),
                        new Color(0xFFB4B4), new Color(0x4A2327)),
                new HighlightRule("Warning", Pattern.compile("\\bWARN(ING)?\\b"),
                        new Color(0x7A5300), new Color(0xFFF3C4),
                        new Color(0xF5D98B), new Color(0x413818)),
                new HighlightRule("Stack trace",
                        Pattern.compile("^\\s+at |^Caused by:|^\\s+\\.\\.\\. \\d+ more|^[\\w.$]+(Exception|Error)(: |$)"),
                        new Color(0xB4372F), null,
                        new Color(0xE58C84), null),
                new HighlightRule("Debug", Pattern.compile("\\b(DEBUG|TRACE)\\b"),
                        new Color(0x8A8F98), null,
                        new Color(0x7C828A), null));
    }
}
