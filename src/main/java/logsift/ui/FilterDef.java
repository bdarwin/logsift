package logsift.ui;

import java.awt.Color;
import java.util.List;
import logsift.core.ColumnFilter;
import logsift.core.SearchQuery;

/** One filter as the user set it up. Edited on the EDT. */
final class FilterDef {

    enum Action {
        SHOW("Show", "Keep lines that match"),
        HIDE("Hide", "Remove lines that match"),
        MARK("Mark", "Only color lines that match");

        final String label;
        final String tip;

        Action(String label, String tip) {
            this.label = label;
            this.tip = tip;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Line background colors for light and dark mode. */
    record Swatch(String name, Color light, Color dark, Color mark) {
        Color color() {
            return Theme.isDark() ? dark : light;
        }
    }

    static final List<Swatch> SWATCHES = List.of(
            new Swatch("Yellow", new Color(0xFFF1A8), new Color(0x4D4416), new Color(0xD4A800)),
            new Swatch("Green", new Color(0xD3F2CF), new Color(0x1F4026), new Color(0x2FA84F)),
            new Swatch("Blue", new Color(0xD3E5FF), new Color(0x1C3457), new Color(0x3B82F6)),
            new Swatch("Purple", new Color(0xE8DAFB), new Color(0x3B2A57), new Color(0x8B5CF6)),
            new Swatch("Orange", new Color(0xFFDDBC), new Color(0x52341E), new Color(0xF97316)),
            new Swatch("Pink", new Color(0xFBD9E9), new Color(0x52263B), new Color(0xEC4899)),
            new Swatch("Teal", new Color(0xCBF1EC), new Color(0x1A4542), new Color(0x14B8A6)),
            new Swatch("Red", new Color(0xFAD0D0), new Color(0x552326), new Color(0xDC2626)));

    final char id;
    String text = "";
    boolean matchCase;
    boolean regex;
    boolean wholeWord;
    Action action = Action.SHOW;
    int swatch = -1; // index into SWATCHES; -1 = no color
    boolean enabled = true;
    boolean alert;   // notify about new matching lines while the file grows
    String column;   // look only in this column of the log's format; null: the whole line
    ColumnFilter.Op op = ColumnFilter.Op.CONTAINS; // with a column: how its value is compared
    String error;    // invalid regex message, or null

    FilterDef(char id) {
        this.id = id;
    }

    /** The search, or null if the text is empty. */
    SearchQuery query() {
        return text.isEmpty() ? null : new SearchQuery(text, matchCase, regex, wholeWord);
    }

    /** What it looks for, as on its chip: "ERROR", or "Host = web-1" for a column. */
    String label() {
        return label(c -> c.isEmpty() ? c : c.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + c.substring(1));
    }

    /** Like {@link #label()}, with columns named by {@code name} (a CSV's header as written). */
    String label(java.util.function.UnaryOperator<String> name) {
        return column == null ? text : name.apply(column) + " " + op.label + " " + text;
    }

    Swatch color() {
        return swatch < 0 ? null : SWATCHES.get(swatch);
    }

    /** Takes part in filtering (enabled, has text, valid). */
    boolean isActive() {
        return enabled && !text.isEmpty() && error == null;
    }
}
