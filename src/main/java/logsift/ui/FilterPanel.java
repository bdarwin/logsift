package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The list of filters below the log: each has a letter, Show / Hide / Mark, a pattern, a color
 * and a live hit count. A formula line says how they combine.
 */
final class FilterPanel extends JPanel {

    interface Listener {
        /** Filters or the formula changed. */
        void filtersChanged();

        void matchingOnlyChanged(boolean on);

        /** Jump to the next or previous line matching this filter. */
        void navigate(FilterDef f, boolean forward);

        /** A filter's alert was switched on or off. */
        default void alertChanged(FilterDef f) {}

        /** "Clear all" was pressed: filters are gone; also clear what the owner keeps (time range). */
        default void clearAllFilters() {}
    }

    static final int MAX_FILTERS = 26;
    private static final int TYPING_DELAY_MS = 450;

    private final Listener listener;
    private final List<FilterDef> defs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final JPanel rowsPanel = new JPanel();
    private final JLabel emptyHint = new JLabel();
    private final JToggleButton allLines = new JToggleButton("All lines");
    private final JToggleButton matchingOnly = new JToggleButton("Matching only");
    private final JTextField formulaField = new JTextField(22);
    private final JToggleButton autoFormula = new JToggleButton("Auto", true);
    private final JLabel resultLabel = new JLabel(" ");
    private final JCheckBox keepEntries = new JCheckBox("Keep stack traces",
            AppState.prefs().getBoolean("entries", true));
    /** Level chips: E W I D T, all on = no level filtering. */
    static final String[] LEVEL_CHIPS = {"E", "W", "I", "D", "T"};
    static final String[][] LEVEL_WORDS = logsift.core.Levels.WORDS;
    private final JToggleButton[] levelChips = new JToggleButton[LEVEL_CHIPS.length];
    private final javax.swing.JSpinner context = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(0, 0, 50, 1));
    private final Timer formulaTimer;
    private boolean updating;

    FilterPanel(Listener listener) {
        super(new BorderLayout());
        this.listener = listener;
        formulaTimer = new Timer(TYPING_DELAY_MS, e -> listener.filtersChanged());
        formulaTimer.setRepeats(false);

        add(buildHeader(), BorderLayout.NORTH);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        rowsPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        emptyHint.setText("<html>No filters yet. Click <b>+ Add filter</b>, or select text in"
                + " the log, right-click and choose <b>Show</b>, <b>Hide</b> or <b>Mark</b>.</html>");
        emptyHint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        emptyHint.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        emptyHint.setAlignmentX(LEFT_ALIGNMENT);
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(rowsPanel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        rebuildRows();
    }

    private JComponent buildHeader() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        ButtonGroup g = new ButtonGroup();
        g.add(allLines);
        g.add(matchingOnly);
        allLines.setSelected(true);
        allLines.setToolTipText("Show every line; lines filtered out are grayed");
        matchingOnly.setToolTipText("Show only the lines that pass the filters ("
                + (MainFrame.isMac() ? "⇧⌘H" : "Ctrl+Shift+H") + ")");
        allLines.addActionListener(e -> listener.matchingOnlyChanged(false));
        matchingOnly.addActionListener(e -> listener.matchingOnlyChanged(true));
        allLines.putClientProperty("JButton.buttonType", "roundRect");
        matchingOnly.putClientProperty("JButton.buttonType", "roundRect");

        JButton add = new JButton("+ Add filter");
        add.addActionListener(e -> {
            FilterDef d = addFilter("", FilterDef.Action.SHOW);
            if (d != null) {
                rows.get(defs.indexOf(d)).field.requestFocusInWindow();
            }
        });

        JButton clearAll = new JButton("Clear all");
        clearAll.setToolTipText("Remove all filters, show all levels and times: back to the whole log");
        clearAll.addActionListener(e -> clearAll());

        JLabel combine = new JLabel("Combine:");
        combine.setToolTipText("How filters combine, using their letters: AND, OR, NOT, ( )");
        formulaField.putClientProperty("JTextField.placeholderText", "no filtering");
        formulaField.setToolTipText("<html>How filters combine, using their letters.<br>"
                + "Examples: <tt>(A OR B) AND NOT C</tt> &nbsp; <tt>A AND B</tt> &nbsp;"
                + " <tt>NOT (C OR D)</tt><br>Auto: any Show filter, and no Hide filter.</html>");
        formulaField.setMaximumSize(new Dimension(360, 30));
        formulaField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                formulaEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                formulaEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        formulaField.addActionListener(e -> {
            formulaTimer.stop();
            listener.filtersChanged();
        });
        autoFormula.setToolTipText("Write the formula automatically from Show and Hide filters");
        autoFormula.putClientProperty("JButton.buttonType", "toolBarButton");
        autoFormula.addActionListener(e -> listener.filtersChanged());

        JButton more = new JButton("⋯");
        more.putClientProperty("JButton.buttonType", "toolBarButton");
        more.setToolTipText("Save, load or remove filters");
        more.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            m.add(menuItem("Save Filters…", this::saveDialog));
            m.add(menuItem("Load Filters…", this::loadDialog));
            m.addSeparator();
            m.add(menuItem("Remove All Filters", this::removeAllFilters));
            m.show(more, 0, more.getHeight());
        });

        keepEntries.setToolTipText("No multi-line entries found in this file");
        keepEntries.addActionListener(e -> {
            AppState.prefs()
                    .putBoolean("entries", keepEntries.isSelected());
            listener.filtersChanged();
        });
        context.setToolTipText("Also show this many lines before and after each match (grayed)");
        context.setMaximumSize(new Dimension(64, 30));
        context.addChangeListener(e -> listener.filtersChanged());
        String[] names = {"errors", "warnings", "info", "debug", "trace"};
        for (int i = 0; i < LEVEL_CHIPS.length; i++) {
            JToggleButton b = new JToggleButton(LEVEL_CHIPS[i], true);
            b.setToolTipText("Show " + names[i] + " (" + String.join(", ", LEVEL_WORDS[i]) + ")");
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            b.setFont(b.getFont().deriveFont(Font.BOLD));
            b.addActionListener(e -> listener.filtersChanged());
            levelChips[i] = b;
        }
        JLabel contextLabel = new JLabel("Context ±");
        contextLabel.setToolTipText(context.getToolTipText());
        resultLabel.setFont(resultLabel.getFont().deriveFont(Font.BOLD));
        bar.add(allLines);
        bar.add(matchingOnly);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(add);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(clearAll);
        bar.add(Box.createHorizontalStrut(16));
        bar.add(combine);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(formulaField);
        bar.add(autoFormula);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(new JLabel("Levels "));
        for (JToggleButton b : levelChips) {
            bar.add(b);
        }
        bar.add(Box.createHorizontalStrut(10));
        bar.add(keepEntries);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(contextLabel);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(context);
        bar.add(Box.createHorizontalStrut(16));
        bar.add(resultLabel);
        bar.add(Box.createHorizontalGlue());
        bar.add(more);
        return bar;
    }

    private static JMenuItem menuItem(String name, Runnable r) {
        JMenuItem mi = new JMenuItem(name);
        mi.addActionListener(e -> r.run());
        return mi;
    }

    private void formulaEdited() {
        if (updating) {
            return;
        }
        autoFormula.setSelected(false);
        formulaField.putClientProperty("JComponent.outline", null);
        formulaTimer.restart();
    }

    // ---------------------------------------------------------------- state

    List<FilterDef> filters() {
        return defs;
    }

    /** Whether any filter, level chip or option would filter lines. */
    boolean hasActiveFilters() {
        if (levelWords() != null) {
            return true;
        }
        for (FilterDef d : defs) {
            if (d.isActive() && d.action != FilterDef.Action.MARK) {
                return true;
            }
        }
        return !isAutoFormula() && !formulaText().isBlank();
    }

    /** Level words of the chips that are on, or null when all are on (no level filter). */
    java.util.List<String> levelWords() {
        java.util.List<String> out = new ArrayList<>();
        boolean all = true;
        for (int i = 0; i < levelChips.length; i++) {
            if (levelChips[i].isSelected()) {
                out.addAll(java.util.List.of(LEVEL_WORDS[i]));
            } else {
                all = false;
            }
        }
        return all ? null : out;
    }

    /** The levels whose chips are on (ERROR, WARN, INFO, DEBUG, TRACE). */
    java.util.List<String> chosenLevels() {
        String[] names = {"ERROR", "WARN", "INFO", "DEBUG", "TRACE"};
        java.util.List<String> out = new ArrayList<>();
        for (int i = 0; i < levelChips.length; i++) {
            if (levelChips[i].isSelected()) {
                out.add(names[i]);
            }
        }
        return out;
    }

    /** Turns level chips on or off (by letter, for example "E", "W"). For tests and the API. */
    void setLevels(String... chips) {
        java.util.List<String> on = java.util.List.of(chips);
        for (int i = 0; i < levelChips.length; i++) {
            levelChips[i].setSelected(on.contains(LEVEL_CHIPS[i]));
        }
        listener.filtersChanged();
    }

    /** Keep a log line and the lines under it (stack traces) together. */
    boolean keepEntries() {
        return keepEntries.isSelected();
    }

    void setKeepEntries(boolean on) {
        keepEntries.setSelected(on);
        listener.filtersChanged();
    }

    /** Lines of context around each match. */
    int contextLines() {
        return (Integer) context.getValue();
    }

    void setContextLines(int n) {
        context.setValue(n);
    }

    /** Says which entry format was found (null: none, every line stands alone). */
    void setEntryFormatInfo(String name) {
        keepEntries.setEnabled(name != null);
        keepEntries.setToolTipText(name == null
                ? "No multi-line entries found in this file (every line stands alone)"
                : "<html>Treat a log line and the lines under it (stack traces, wrapped text) as "
                        + "one entry:<br>if any line of it matches, show all of it.<br>"
                        + "Entries here start with: <b>" + name + "</b></html>");
    }

    boolean isAutoFormula() {
        return autoFormula.isSelected();
    }

    String formulaText() {
        return formulaField.getText();
    }

    /** Sets a custom formula (auto off), or turns auto back on when {@code text} is null. */
    void setFormula(String text) {
        autoFormula.setSelected(text == null);
        if (text != null) {
            updating = true;
            formulaField.setText(text);
            updating = false;
        }
        listener.filtersChanged();
    }

    /** Shows the formula that auto mode produced. */
    void showAutoFormula(String text) {
        if (!formulaField.getText().equals(text)) {
            updating = true;
            formulaField.setText(text);
            updating = false;
        }
        setFormulaError(null);
    }

    void setFormulaError(String message) {
        formulaField.putClientProperty("JComponent.outline", message == null ? null : "error");
        formulaField.setToolTipText(message != null ? message : (String) null);
        if (message == null) {
            formulaField.setToolTipText("<html>How filters combine, using their letters.<br>"
                    + "Examples: <tt>(A OR B) AND NOT C</tt> &nbsp; <tt>A AND B</tt><br>"
                    + "Auto: any Show filter, and no Hide filter.</html>");
        }
    }

    void setResultText(String text) {
        resultLabel.setText(text);
    }

    void setMatchingOnly(boolean on) {
        (on ? matchingOnly : allLines).setSelected(true);
    }

    /** Updates the hit count shown next to each filter. */
    void setCount(FilterDef d, String text) {
        int i = defs.indexOf(d);
        if (i >= 0) {
            rows.get(i).count.setText(text);
        }
    }

    /** Adds a filter; returns null if all 26 letters are in use. */
    FilterDef addFilter(String text, FilterDef.Action action) {
        if (defs.size() >= MAX_FILTERS) {
            JOptionPane.showMessageDialog(this, "At most " + MAX_FILTERS + " filters.");
            return null;
        }
        FilterDef d = new FilterDef(freeLetter());
        d.text = text;
        d.action = action;
        d.swatch = action == FilterDef.Action.HIDE ? -1 : nextSwatch();
        defs.add(d);
        rebuildRows();
        listener.filtersChanged();
        return d;
    }

    private char freeLetter() {
        for (char c = 'A'; c <= 'Z'; c++) {
            boolean used = false;
            for (FilterDef d : defs) {
                used |= d.id == c;
            }
            if (!used) {
                return c;
            }
        }
        throw new IllegalStateException("no free letter");
    }

    private int nextSwatch() {
        int[] uses = new int[FilterDef.SWATCHES.size()];
        for (FilterDef d : defs) {
            if (d.swatch >= 0) {
                uses[d.swatch]++;
            }
        }
        int best = 0;
        for (int i = 1; i < uses.length; i++) {
            if (uses[i] < uses[best]) {
                best = i;
            }
        }
        return best;
    }

    void removeFilter(FilterDef d) {
        defs.remove(d);
        rebuildRows();
        listener.filtersChanged();
    }

    /** Turns a filter on or off (from its chip above the log). */
    void setFilterEnabled(FilterDef d, boolean on) {
        d.enabled = on;
        rebuildRows();
        listener.filtersChanged();
    }

    /** Changes what a filter does: show, hide or color its lines. */
    void setFilterAction(FilterDef d, FilterDef.Action a) {
        d.action = a;
        if (a != FilterDef.Action.HIDE && d.swatch < 0) {
            d.swatch = nextSwatch();
        }
        rebuildRows();
        listener.filtersChanged();
    }

    /** Back to the whole log: no filters, all levels, no context, automatic formula. */
    void clearAll() {
        defs.clear();
        autoFormula.setSelected(true);
        for (JToggleButton b : levelChips) {
            b.setSelected(true);
        }
        updating = true;
        context.setValue(0);
        updating = false;
        rebuildRows();
        listener.filtersChanged();
        listener.clearAllFilters();
    }

    private void removeAllFilters() {
        defs.clear();
        autoFormula.setSelected(true);
        rebuildRows();
        listener.filtersChanged();
    }

    /** Shows a row's invalid-regex state. */
    void refreshRow(FilterDef d) {
        int i = defs.indexOf(d);
        if (i >= 0) {
            rows.get(i).refresh();
        }
    }

    private void rebuildRows() {
        rowsPanel.removeAll();
        rows.clear();
        if (defs.isEmpty()) {
            rowsPanel.add(emptyHint);
        }
        for (FilterDef d : defs) {
            Row r = new Row(d);
            rows.add(r);
            rowsPanel.add(r);
        }
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    // ---------------------------------------------------------------- one filter row

    private final class Row extends JPanel {
        final FilterDef d;
        final JCheckBox enabled = new JCheckBox();
        final JLabel badge = new JLabel();
        final JComboBox<FilterDef.Action> action = new JComboBox<>(FilterDef.Action.values());
        final JTextField field = new JTextField();
        final JToggleButton matchCase = MainFrame.toggle("Aa", "Match case");
        final JToggleButton wholeWord = MainFrame.toggle("W", "Whole word");
        final JToggleButton regex = MainFrame.toggle(".*", "Regular expression");
        final JButton color = new JButton();
        final JLabel count = new JLabel("", SwingConstants.RIGHT);
        final Timer typing;

        Row(FilterDef d) {
            this.d = d;
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

            typing = new Timer(TYPING_DELAY_MS, e -> commitText());
            typing.setRepeats(false);

            enabled.setSelected(d.enabled);
            enabled.setToolTipText("Use this filter");
            enabled.addActionListener(e -> {
                d.enabled = enabled.isSelected();
                refresh();
                listener.filtersChanged();
            });

            badge.setIcon(new BadgeIcon(d));
            badge.setToolTipText("Filter " + d.id + " (use this letter in the formula)");

            action.setSelectedItem(d.action);
            action.setMaximumSize(new Dimension(84, 30));
            action.setToolTipText("Show: keep matching lines · Hide: remove them · Mark: only color");
            action.addActionListener(e -> {
                d.action = (FilterDef.Action) action.getSelectedItem();
                listener.filtersChanged();
            });

            field.setText(d.text);
            field.putClientProperty("JTextField.placeholderText", "text to match");
            JToolBar inField = new JToolBar();
            inField.setFloatable(false);
            inField.setOpaque(false);
            inField.add(matchCase);
            inField.add(wholeWord);
            inField.add(regex);
            field.putClientProperty("JTextField.trailingComponent", inField);
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    typing.restart();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    typing.restart();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {}
            });
            field.addActionListener(e -> {
                typing.stop();
                commitText();
            });
            matchCase.setSelected(d.matchCase);
            wholeWord.setSelected(d.wholeWord);
            regex.setSelected(d.regex);
            matchCase.addActionListener(e -> commitOptions());
            wholeWord.addActionListener(e -> commitOptions());
            regex.addActionListener(e -> commitOptions());

            color.putClientProperty("JButton.buttonType", "toolBarButton");
            color.setToolTipText("Line color");
            color.addActionListener(e -> colorMenu().show(color, 0, color.getHeight()));

            count.setPreferredSize(new Dimension(110, 20));
            count.setMaximumSize(new Dimension(110, 30));
            count.setToolTipText("Lines matching this filter");

            JButton prev = small("▲", "Previous line matching this filter");
            prev.addActionListener(e -> listener.navigate(d, false));
            JButton next = small("▼", "Next line matching this filter");
            next.addActionListener(e -> listener.navigate(d, true));
            JToggleButton bell = new JToggleButton(new BellIcon(false));
            bell.setSelectedIcon(new BellIcon(true));
            bell.putClientProperty("JButton.buttonType", "toolBarButton");
            bell.setFocusable(false);
            bell.setSelected(d.alert);
            bell.setToolTipText("Alert me when new lines matching this filter are written");
            bell.addActionListener(e -> {
                d.alert = bell.isSelected();
                listener.alertChanged(d);
            });
            JButton remove = small("✕", "Remove this filter");
            remove.addActionListener(e -> FilterPanel.this.removeFilter(d));

            add(enabled);
            add(badge);
            add(Box.createHorizontalStrut(6));
            add(action);
            add(Box.createHorizontalStrut(6));
            add(field);
            add(Box.createHorizontalStrut(4));
            add(color);
            add(count);
            add(Box.createHorizontalStrut(6));
            add(prev);
            add(next);
            add(bell);
            add(remove);
            refresh();
        }

        private JButton small(String text, String tip) {
            JButton b = new JButton(text);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setToolTipText(tip);
            b.setFocusable(false);
            return b;
        }

        private void commitText() {
            if (!field.getText().equals(d.text)) {
                d.text = field.getText();
                listener.filtersChanged();
            }
        }

        private void commitOptions() {
            d.matchCase = matchCase.isSelected();
            d.wholeWord = wholeWord.isSelected();
            d.regex = regex.isSelected();
            listener.filtersChanged();
        }

        private JPopupMenu colorMenu() {
            JPopupMenu m = new JPopupMenu();
            for (int i = 0; i < FilterDef.SWATCHES.size(); i++) {
                int idx = i;
                FilterDef.Swatch s = FilterDef.SWATCHES.get(i);
                JMenuItem mi = new JMenuItem(s.name(), new SwatchIcon(s));
                mi.addActionListener(e -> setSwatch(idx));
                m.add(mi);
            }
            m.addSeparator();
            JMenuItem none = new JMenuItem("No color", new SwatchIcon(null));
            none.addActionListener(e -> setSwatch(-1));
            m.add(none);
            return m;
        }

        private void setSwatch(int i) {
            d.swatch = i;
            refresh();
            listener.filtersChanged();
        }

        void refresh() {
            color.setIcon(new SwatchIcon(d.color()));
            field.putClientProperty("JComponent.outline", d.error == null ? null : "error");
            field.setToolTipText(d.error == null ? null : d.error);
            boolean on = d.enabled;
            field.setEnabled(on);
            action.setEnabled(on);
            badge.setEnabled(on);
            badge.repaint();
        }
    }

    /** Round badge with the filter's letter, in the filter's color. */
    private static final class BadgeIcon implements Icon {
        private final FilterDef d;

        BadgeIcon(FilterDef d) {
            this.d = d;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FilterDef.Swatch s = d.color();
            Color fill = s != null ? s.color() : Theme.gutterBackground();
            g.setColor(fill);
            g.fillOval(x, y, 20, 20);
            g.setColor(Theme.gutterForeground());
            g.drawOval(x, y, 19, 19);
            g.setColor(d.enabled ? Theme.foreground() : Theme.gutterForeground());
            g.setFont(c.getFont().deriveFont(Font.BOLD, 12f));
            String t = String.valueOf(d.id);
            var fm = g.getFontMetrics();
            g.drawString(t, x + (20 - fm.stringWidth(t)) / 2, y + (20 + fm.getAscent()) / 2 - 2);
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }
    }

    /** A small bell, outlined when off and filled amber when on. */
    static final class BellIcon implements Icon {
        private final boolean on;

        BellIcon(boolean on) {
            this.on = on;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(x, y);
            java.awt.geom.Path2D bell = new java.awt.geom.Path2D.Double();
            bell.moveTo(8, 2);
            bell.curveTo(4.5, 2, 3.5, 5, 3.5, 8);
            bell.lineTo(3.5, 10.5);
            bell.lineTo(2, 12.5);
            bell.lineTo(14, 12.5);
            bell.lineTo(12.5, 10.5);
            bell.lineTo(12.5, 8);
            bell.curveTo(12.5, 5, 11.5, 2, 8, 2);
            bell.closePath();
            if (on) {
                g.setColor(new Color(0xF5A524));
                g.fill(bell);
            }
            g.setColor(on ? new Color(0xB77800) : Theme.gutterForeground());
            g.setStroke(new java.awt.BasicStroke(1.3f));
            g.draw(bell);
            g.fillOval(6, 13, 4, 3);
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    private static final class SwatchIcon implements Icon {
        private final FilterDef.Swatch s;

        SwatchIcon(FilterDef.Swatch s) {
            this.s = s;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (s != null) {
                g.setColor(s.color());
                g.fillRoundRect(x, y, 16, 16, 5, 5);
            }
            g.setColor(Theme.gutterForeground());
            g.drawRoundRect(x, y, 15, 15, 5, 5);
            if (s == null) {
                g.drawLine(x + 3, y + 12, x + 12, y + 3);
            }
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    // ---------------------------------------------------------------- save / load

    void save(Path file) throws IOException {
        Properties p = toProperties();
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "Logsift filters");
        }
    }

    /** The filters, formula and options as properties (for saving). */
    Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("context", Integer.toString(contextLines()));
        p.setProperty("entries", Boolean.toString(keepEntries()));
        StringBuilder lv = new StringBuilder();
        for (int i = 0; i < levelChips.length; i++) {
            if (levelChips[i].isSelected()) {
                lv.append(LEVEL_CHIPS[i]);
            }
        }
        p.setProperty("levels", lv.toString());
        p.setProperty("count", Integer.toString(defs.size()));
        p.setProperty("formula.auto", Boolean.toString(isAutoFormula()));
        p.setProperty("formula", formulaText());
        for (int i = 0; i < defs.size(); i++) {
            FilterDef d = defs.get(i);
            String k = "filter." + i + ".";
            p.setProperty(k + "id", String.valueOf(d.id));
            p.setProperty(k + "text", d.text);
            p.setProperty(k + "action", d.action.name());
            p.setProperty(k + "color", d.swatch < 0 ? "" : FilterDef.SWATCHES.get(d.swatch).name());
            p.setProperty(k + "matchCase", Boolean.toString(d.matchCase));
            p.setProperty(k + "regex", Boolean.toString(d.regex));
            p.setProperty(k + "wholeWord", Boolean.toString(d.wholeWord));
            p.setProperty(k + "enabled", Boolean.toString(d.enabled));
            p.setProperty(k + "alert", Boolean.toString(d.alert));
        }
        return p;
    }

    void load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        fromProperties(p);
    }

    /** Replaces the filters, formula and options with saved ones. */
    void fromProperties(Properties p) throws IOException {
        List<FilterDef> loaded = new ArrayList<>();
        int n = Math.min(MAX_FILTERS, Integer.parseInt(p.getProperty("count", "0")));
        for (int i = 0; i < n; i++) {
            String k = "filter." + i + ".";
            String id = p.getProperty(k + "id", "");
            if (id.length() != 1 || id.charAt(0) < 'A' || id.charAt(0) > 'Z') {
                throw new IOException("Bad filter letter: " + id);
            }
            FilterDef d = new FilterDef(id.charAt(0));
            d.text = p.getProperty(k + "text", "");
            d.action = FilterDef.Action.valueOf(p.getProperty(k + "action", "SHOW"));
            String color = p.getProperty(k + "color", "");
            for (int s = 0; s < FilterDef.SWATCHES.size(); s++) {
                if (FilterDef.SWATCHES.get(s).name().equals(color)) {
                    d.swatch = s;
                }
            }
            d.matchCase = Boolean.parseBoolean(p.getProperty(k + "matchCase"));
            d.regex = Boolean.parseBoolean(p.getProperty(k + "regex"));
            d.wholeWord = Boolean.parseBoolean(p.getProperty(k + "wholeWord"));
            d.enabled = Boolean.parseBoolean(p.getProperty(k + "enabled", "true"));
            d.alert = Boolean.parseBoolean(p.getProperty(k + "alert", "false"));
            loaded.add(d);
        }
        defs.clear();
        defs.addAll(loaded);
        boolean auto = Boolean.parseBoolean(p.getProperty("formula.auto", "true"));
        updating = true;
        formulaField.setText(p.getProperty("formula", ""));
        updating = false;
        autoFormula.setSelected(auto);
        if (p.getProperty("levels") != null) {
            String lv = p.getProperty("levels");
            for (int i = 0; i < levelChips.length; i++) {
                levelChips[i].setSelected(lv.contains(LEVEL_CHIPS[i]));
            }
        }
        if (p.getProperty("context") != null) {
            updating = true;
            context.setValue(Integer.parseInt(p.getProperty("context")));
            updating = false;
        }
        if (p.getProperty("entries") != null) {
            keepEntries.setSelected(Boolean.parseBoolean(p.getProperty("entries")));
        }
        rebuildRows();
        listener.filtersChanged();
    }

    private void saveDialog() {
        FileDialog fd = new FileDialog(frame(), "Save Filters", FileDialog.SAVE);
        fd.setFile("filters.lmfilters");
        fd.setVisible(true);
        if (fd.getFile() != null) {
            try {
                save(Path.of(fd.getDirectory(), fd.getFile()));
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Cannot save: " + e.getMessage());
            }
        }
    }

    private void loadDialog() {
        FileDialog fd = new FileDialog(frame(), "Load Filters", FileDialog.LOAD);
        fd.setFile("*.lmfilters");
        fd.setVisible(true);
        if (fd.getFile() != null) {
            try {
                load(Path.of(fd.getDirectory(), fd.getFile()));
            } catch (IOException | RuntimeException e) {
                JOptionPane.showMessageDialog(this, "Cannot load: " + e.getMessage());
            }
        }
    }

    private Frame frame() {
        Window w = SwingUtilities.getWindowAncestor(this);
        return w instanceof Frame f ? f : null;
    }
}
