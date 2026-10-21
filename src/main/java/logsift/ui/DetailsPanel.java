package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import logsift.core.Json;

/**
 * The current line in full: wrapped and selectable, and for JSON lines pretty-printed with
 * colors (keys, strings, numbers, true/false/null).
 */
final class DetailsPanel extends JPanel {
    private final JTextPane text = new JTextPane();
    private final JLabel header = new JLabel(" ");
    private String shown;

    DetailsPanel() {
        super(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        header.setForeground(Theme.gutterForeground());
        text.setEditable(false);
        text.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(text), BorderLayout.CENTER);
    }

    String textForTest() {
        return text.getText();
    }

    /** Shows a line (null: nothing selected). */
    void show(long lineNumber, String line, float fontSize) {
        if (line != null && line.equals(shown)) {
            return;
        }
        shown = line;
        text.setFont(Theme.monoFont(fontSize));
        StyledDocument doc = text.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            if (line == null) {
                header.setText("Select a line to see it here in full.");
                return;
            }
            Map<String, Object> json = Json.parseObject(line);
            if (json == null) {
                header.setText(String.format("Line %,d · %,d characters", lineNumber + 1, line.length()));
                doc.insertString(0, line, null);
            } else {
                header.setText(String.format("Line %,d · JSON with %d fields", lineNumber + 1, json.size()));
                colorize(Json.pretty(json), doc);
            }
            text.setCaretPosition(0);
        } catch (BadLocationException e) {
            // cannot happen: we insert at valid offsets
        }
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
