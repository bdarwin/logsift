package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import logsift.core.Json;

/**
 * The current entry (a line with its stack trace) or the selected text, in full: wrapped and
 * selectable. JSON in it can be shown pretty-printed with colors (keys, strings, numbers,
 * true/false/null), JSON written inside strings opened up too; a switch goes back to the text.
 */
final class DetailsPanel extends JPanel {
    enum View { TEXT, JSON }

    /** What is shown: a title, the text, and (for an entry) its first line and the current one. */
    private record Content(String title, String body, long firstLine, long current, int lines, float fontSize) {}

    private final JTextPane text = new JTextPane();
    private final JLabel header = new JLabel(" ");
    private final JToggleButton asText = new JToggleButton("Text");
    private final JToggleButton asJson = new JToggleButton("JSON", Icons.of("braces"));
    private Content content;
    private View chosen; // what the user picked; null: JSON when it is all JSON
    private View shownView = View.TEXT;

    DetailsPanel() {
        super(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 6));
        header.setForeground(Theme.gutterForeground());
        top.add(header, BorderLayout.CENTER);
        JPanel views = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        views.setOpaque(false);
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        for (JToggleButton b : List.of(asText, asJson)) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            g.add(b);
            views.add(b);
        }
        asText.setToolTipText("Show the text as it is in the log");
        asJson.setToolTipText("Show the JSON in it pretty-printed (JSON inside strings opened up too)");
        asText.addActionListener(e -> choose(View.TEXT));
        asJson.addActionListener(e -> choose(View.JSON));
        top.add(views, BorderLayout.EAST);
        text.setEditable(false);
        text.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(text), BorderLayout.CENTER);
        render();
    }

    String textForTest() {
        return text.getText();
    }

    View viewForTest() {
        return shownView;
    }

    void chooseForTest(View v) {
        choose(v);
    }

    private void choose(View v) {
        chosen = v;
        render();
    }

    /** Shows a line (null: nothing selected). */
    void show(long lineNumber, String line, float fontSize) {
        show(line == null ? null : new logsift.core.EntryFormat.Entry(lineNumber, lineNumber, List.of(line)),
                lineNumber, fontSize);
    }

    /** Shows a whole entry (a line with its stack trace, say), the current line marked; null: nothing. */
    void show(logsift.core.EntryFormat.Entry entry, long current, float fontSize) {
        if (entry == null || entry.lines().isEmpty()) {
            set(null);
            return;
        }
        String title;
        String body = String.join("\n", entry.lines());
        if (entry.lines().size() == 1) {
            title = String.format("Line %,d · %,d characters", entry.first() + 1, body.length());
        } else {
            title = String.format("Lines %,d – %,d · an entry of %,d lines", entry.first() + 1,
                    entry.last() + 1, entry.lines().size());
        }
        set(new Content(title, body, entry.first(), current, entry.lines().size(), fontSize));
    }

    /** Shows selected text (several lines, or part of one). */
    void showSelection(String title, String body, float fontSize) {
        set(new Content(title, body, -1, -1, 1, fontSize));
    }

    private void set(Content c) {
        if (c != null && c.equals(content)) {
            return;
        }
        content = c;
        render();
    }

    private void render() {
        StyledDocument doc = text.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            Content c = content;
            if (c == null) {
                header.setText("Select a line to see it here in full, or select text to see just that.");
                asText.setEnabled(false);
                asJson.setEnabled(false);
                return;
            }
            text.setFont(Theme.monoFont(c.fontSize()));
            List<Json.Found> found = c.body().length() > 5_000_000 ? List.of() : Json.findAll(c.body());
            boolean allJson = found.size() == 1 && c.body().strip().length() == found.get(0).end() - found.get(0).start();
            View v = chosen != null ? chosen : allJson ? View.JSON : View.TEXT;
            if (found.isEmpty()) {
                v = View.TEXT;
            }
            shownView = v;
            asText.setEnabled(!found.isEmpty());
            asJson.setEnabled(!found.isEmpty());
            asText.setSelected(v == View.TEXT);
            asJson.setSelected(v == View.JSON);
            if (v == View.JSON) {
                String what = allJson && found.get(0).value() instanceof Map<?, ?> m
                        ? "JSON with " + m.size() + " fields"
                        : found.size() == 1 ? "JSON in it" : found.size() + " pieces of JSON in it";
                header.setText(c.title().replaceAll(" · [\\d,]+ characters$", "") + " · " + what);
                json(c.body(), found, doc);
                text.setCaretPosition(0);
                return;
            }
            header.setText(c.title());
            int caret = 0;
            if (c.lines() > 1) { // an entry: its first line bold, the current one marked
                SimpleAttributeSet first = new SimpleAttributeSet();
                StyleConstants.setBold(first, true);
                SimpleAttributeSet mark = new SimpleAttributeSet();
                StyleConstants.setBackground(mark, Theme.currentLine());
                String[] lines = c.body().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    long l = c.firstLine() + i;
                    if (l == c.current()) {
                        caret = doc.getLength();
                    }
                    doc.insertString(doc.getLength(), lines[i], i == 0 ? first : l == c.current() ? mark : null);
                    if (i + 1 < lines.length) {
                        doc.insertString(doc.getLength(), "\n", null);
                    }
                }
            } else {
                doc.insertString(0, c.body(), null);
            }
            text.setCaretPosition(Math.min(caret, doc.getLength()));
        } catch (BadLocationException e) {
            // cannot happen: we insert at valid offsets
        }
    }

    /** The text with each piece of JSON pretty-printed; the text around it grayed. */
    private static void json(String body, List<Json.Found> found, StyledDocument doc) throws BadLocationException {
        SimpleAttributeSet dim = attrs(Theme.gutterForeground(), false);
        int at = 0;
        for (Json.Found f : found) {
            String before = body.substring(at, f.start()).strip();
            if (!before.isEmpty()) {
                if (doc.getLength() > 0) {
                    doc.insertString(doc.getLength(), "\n", null);
                }
                doc.insertString(doc.getLength(), before + "\n", dim);
            } else if (doc.getLength() > 0) {
                doc.insertString(doc.getLength(), "\n", null);
            }
            colorize(Json.pretty(expand(f.value(), 0)), doc);
            doc.insertString(doc.getLength(), "\n", null);
            at = f.end();
        }
        String after = body.substring(at).strip();
        if (!after.isEmpty()) {
            doc.insertString(doc.getLength(), after, dim);
        }
    }

    /** Strings that hold JSON themselves ("payload": "{\"id\": 7}") opened up. */
    static Object expand(Object v, int depth) {
        if (depth > 20) {
            return v;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), expand(x, depth + 1)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new java.util.ArrayList<>();
            l.forEach(x -> out.add(expand(x, depth + 1)));
            return out;
        }
        if (v instanceof String str && str.length() > 1 && (str.startsWith("{") || str.startsWith("["))) {
            Object inner = Json.parseValue(str, str);
            if (inner instanceof Map || inner instanceof List) {
                return expand(inner, depth + 1);
            }
        }
        return v;
    }

    /** Pretty JSON with colors: keys blue, strings green, numbers purple, literals orange. */
    private static void colorize(String s, StyledDocument doc) throws BadLocationException {
        boolean dark = Theme.isDark();
        SimpleAttributeSet key = attrs(dark ? new Color(0x7FB4FF) : new Color(0x1D4ED8), true);
        SimpleAttributeSet str = attrs(dark ? new Color(0x98C379) : new Color(0x15803D), false);
        SimpleAttributeSet num = attrs(dark ? new Color(0xD19AFF) : new Color(0x7E22CE), false);
        SimpleAttributeSet lit = attrs(dark ? new Color(0xE5A062) : new Color(0xC2410C), false);
        SimpleAttributeSet punct = attrs(Theme.gutterForeground(), false);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '"') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, s.length());
                int k = j;
                while (k < s.length() && s.charAt(k) == ' ') {
                    k++;
                }
                boolean isKey = k < s.length() && s.charAt(k) == ':';
                doc.insertString(doc.getLength(), s.substring(i, j), isKey ? key : str);
                i = j;
            } else if (c == '-' || Character.isDigit(c)) {
                int j = i;
                while (j < s.length() && "+-0123456789.eE".indexOf(s.charAt(j)) >= 0) {
                    j++;
                }
                doc.insertString(doc.getLength(), s.substring(i, j), num);
                i = j;
            } else if (s.startsWith("true", i) || s.startsWith("null", i)) {
                doc.insertString(doc.getLength(), s.substring(i, i + 4), lit);
                i += 4;
            } else if (s.startsWith("false", i)) {
                doc.insertString(doc.getLength(), "false", lit);
                i += 5;
            } else {
                doc.insertString(doc.getLength(), String.valueOf(c), "{}[],:".indexOf(c) >= 0 ? punct : null);
                i++;
            }
        }
    }

    private static SimpleAttributeSet attrs(Color c, boolean bold) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        StyleConstants.setBold(a, bold);
        return a;
    }
}
