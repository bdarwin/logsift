package logsift.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import logsift.core.LogDocument;

/**
 * The bar at the top of each log: Find, the filters that are on (as chips: click one to turn it
 * off, ✕ to remove it), how the log is shown (wrap, columns, tree, timeline), and Follow and
 * Reload. Each log keeps its own; the last choice is the default for logs opened next.
 */
final class LogBar extends JPanel {

    private final LogTab tab;
    private final JLabel more = new JLabel();
    /** The chips, left to right; those that don't fit fold into "+3 more". */
    private final JPanel chips = new JPanel(new ChipsLayout()) {
        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize(); // the space left goes to the right, before Follow
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }
    };
    private final JToggleButton matchingOnly = new JToggleButton("Matching only");
    private final JLabel result = new JLabel();
    private final JToggleButton follow = new JToggleButton("Follow", Icons.follow());
    private final JToggleButton wrap = new JToggleButton(Icons.wrap());
    private final JToggleButton columns = new JToggleButton(Icons.columns());
    private final JToggleButton tree = new JToggleButton(Icons.of("list-tree"));
    private final JToggleButton timeline = new JToggleButton(Icons.of("chart-column"));
    private final JCheckBoxMenuItem autoReload = new JCheckBoxMenuItem("Reload Automatically", Icons.autoReload());
    private boolean stale;
    private boolean anythingSet; // filters, a time range, the tree or a search: Clear All lights up
    private String shown; // what the chips show now
    /** Reload: an amber pill while the file on disk has changes that aren't loaded. */
    private final JButton reload = new JButton("Reload", Icons.reload()) {
        @Override
        protected void paintComponent(Graphics g0) {
            if (stale) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.isDark() ? new Color(0x6B5310) : new Color(0xFFE08A));
                g.fillRoundRect(1, 2, getWidth() - 2, getHeight() - 4, 10, 10);
                g.dispose();
            }
            super.paintComponent(g0);
        }
    };

    /** Clear All: back to the whole log, no search. A pill in the accent color while anything is set. */
    private final JButton clearAll = new JButton("Clear All", Icons.of("x")) {
        @Override
        protected void paintComponent(Graphics g0) {
            if (anythingSet) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.isDark() ? new Color(0x1E3A6E) : new Color(0xDBE7FF));
                g.fillRoundRect(1, 2, getWidth() - 2, getHeight() - 4, 10, 10);
                g.dispose();
            }
            super.paintComponent(g0);
        }
    };

    LogBar(LogTab tab) {
        this.tab = tab;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));

        JButton find = flat(new JButton("Find", Icons.find()));
        find.setToolTipText("Find in this log (" + (MainFrame.isMac() ? "⌘F" : "Ctrl+F") + ")");
        find.addActionListener(e -> tab.openFindBar(null));
        JButton filters = flat(new JButton("Filters", Icons.of("filter")));
        filters.setToolTipText("Add or change filters: show, hide or color lines (the Filters tab below)");
        filters.addActionListener(e -> tab.showFilters());
        flat(clearAll);
        clearAll.setToolTipText("<html>Back to the whole log: removes every filter, the time range, what the tree hides,"
                + "<br>and the searches (the find bar and Find results)</html>");
        clearAll.addActionListener(e -> tab.clearEverything());
        flat(matchingOnly);
        matchingOnly.setToolTipText("<html>On: only the lines the filters keep.<br>"
                + "Off: every line, with matching lines colored.</html>");
        matchingOnly.addActionListener(e -> tab.setMatchingOnly(matchingOnly.isSelected()));
        chips.setOpaque(false);
        more.setForeground(Theme.uiColor("Component.accentColor", new Color(0x2563EB)));
        more.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        more.setToolTipText("More filters are on: see them all in Filters");
        more.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                tab.showFilters();
            }
        });
        result.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        result.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 8));
        flat(follow);
        follow.setToolTipText("Follow the end of the file as it grows (tail -f)");
        follow.addActionListener(e -> tab.mainView().setFollow(follow.isSelected()));
        flat(reload);
        reload.setToolTipText("Load what changed in the file on disk (F5)");
        reload.addActionListener(e -> tab.reload());
        JButton reloadMenu = flat(new JButton("▾"));
        reloadMenu.setToolTipText("Reload options");
        reloadMenu.setMargin(new java.awt.Insets(0, 1, 0, 1));
        autoReload.setToolTipText("<html>Load changes to the file as they happen.<br>"
                + "Off: changes wait until you click Reload (it lights up). Follow turns it on.</html>");
        autoReload.addActionListener(e -> {
            tab.setAutoReload(autoReload.isSelected());
            remember("autoReload", autoReload.isSelected());
        });
        reloadMenu.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            m.add(autoReload);
            m.show(reloadMenu, reloadMenu.getWidth() - m.getPreferredSize().width, reloadMenu.getHeight());
        });

        view(wrap, "Wrap long lines (" + (MainFrame.isMac() ? "⌥Z" : "Alt+Z") + ")", "wrap", tab::setWrap);
        view(columns, "Show lines as columns: time, level, thread, logger, message ("
                + (MainFrame.isMac() ? "⇧⌘K" : "Ctrl+Shift+K") + ")", "columns", tab::setColumnsVisible);
        view(tree, "Tree of loggers (threads, components…) on the left: hide or show their lines ("
                + (MainFrame.isMac() ? "⇧⌘B" : "Ctrl+Shift+B") + ")", "valuesTree", tab::setValuesTreeVisible);
        view(timeline, "Timeline: lines over time, errors in red; click it to jump to a time", "timeline",
                tab::setTimelineVisible);

        add(find);
        add(Box.createHorizontalStrut(4));
        add(filters);
        add(clearAll);
        add(chips);
        add(matchingOnly);
        add(Box.createHorizontalGlue());
        add(result);
        add(wrap);
        add(columns);
        add(tree);
        add(timeline);
        add(separator());
        add(follow);
        add(reload);
        add(reloadMenu);
        update();
        syncView();
    }

    /** A view toggle: for this log, and remembered as the default for logs opened next. */
    private void view(JToggleButton b, String tip, String pref, java.util.function.Consumer<Boolean> apply) {
        flat(b);
        b.setToolTipText(tip);
        b.addActionListener(e -> {
            apply.accept(b.isSelected());
            remember(pref, b.isSelected());
        });
    }

    private static void remember(String pref, boolean on) {
        AppState.prefs().putBoolean(pref, on);
    }

    private static JComponent separator() {
        javax.swing.JSeparator s = new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL);
        s.setMaximumSize(new Dimension(9, 18));
        s.setPreferredSize(new Dimension(9, 18));
        s.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        return s;
    }

    /** Shows this log's view settings (after a menu, shortcut or new format changed them). */
    void syncView() {
        wrap.setSelected(tab.isWrap());
        columns.setSelected(tab.isColumnsWanted());
        tree.setSelected(tab.isTreeWanted());
        tree.setEnabled(tab.hasTree());
        timeline.setSelected(tab.isTimelineWanted());
        timeline.setEnabled(tab.hasTimes());
        autoReload.setSelected(tab.isAutoReload());
    }

    /** The view toggles' states, for tests: wrap, columns, tree, timeline, auto reload. */
    List<Boolean> viewForTest() {
        return List.of(wrap.isSelected(), columns.isSelected(), tree.isSelected(), timeline.isSelected(),
                autoReload.isSelected());
    }

    private static <T extends javax.swing.AbstractButton> T flat(T b) {
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }

    // ---------------------------------------------------------------- state

    /** Shows the filters that are set up now. */
    void update() {
        FilterPanel fp = tab.filterPanel();
        StringBuilder sig = new StringBuilder();
        for (FilterDef d : fp.filters()) {
            sig.append(d.id).append(d.action).append(d.enabled).append(d.swatch).append(d.regex)
                    .append(d.matchCase).append(d.wholeWord).append(d.text).append('\u0000');
        }
        sig.append(fp.levelWords()).append(tab.timeRangeText()).append(tab.treeHiddenCount());
        matchingOnly.setSelected(tab.isMatchingOnly());
        updateClearAll();
        if (sig.toString().equals(shown)) { // the same chips: only their counts change
            for (java.awt.Component c : chips.getComponents()) {
                if (c instanceof Chip ch && ch.def != null) {
                    String n = tab.filterCount(ch.def);
                    if (!n.equals(ch.count)) {
                        ch.count = n;
                        ch.revalidate();
                        ch.repaint();
                    }
                }
            }
            return;
        }
        shown = sig.toString();
        chips.removeAll();
        for (FilterDef d : fp.filters()) {
            if (!d.text.isEmpty()) {
                chips.add(filterChip(d));
            }
        }
        if (fp.levelWords() != null) {
            List<String> on = fp.chosenLevels();
            chips.add(new Chip(null, "Levels: " + (on.isEmpty() ? "none" : String.join(", ", on)), true,
                    "Only lines of these levels (change them in Filters)", tab::showFilters, () -> {
                        fp.setLevels(FilterPanel.LEVEL_CHIPS);
                    }, null));
        }
        long[] range = tab.timeRange();
        if (range != null) {
            chips.add(new Chip(null, "Time: " + tab.timeRangeText(), true,
                    "Only lines in this time range (drag on the timeline to change it)", null,
                    tab::clearTimeRange, null));
        }
        int hidden = tab.treeHiddenCount();
        if (hidden > 0) {
            chips.add(new Chip(null, "Tree: " + hidden + " hidden", true,
                    "Branches unchecked in the tree of loggers", null, tab::showAllTreeValues, null));
        }
        matchingOnly.setVisible(chips.getComponentCount() > 0);
        chips.add(more); // shown by the layout when some chips don't fit
        chips.revalidate();
        chips.repaint();
        revalidate();
    }

    /** Lights Clear All up while there is anything to clear. */
    void updateClearAll() {
        boolean set = tab.hasAnythingToClear();
        clearAll.setEnabled(set);
        if (set != anythingSet) {
            anythingSet = set;
            clearAll.repaint();
        }
    }

    /** Whether Clear All is lit, for tests. */
    boolean clearAllLitForTest() {
        return anythingSet && clearAll.isEnabled();
    }

    JButton clearAllButtonForTest() {
        return clearAll;
    }

    private Chip filterChip(FilterDef d) {
        String what = switch (d.action) {
            case SHOW -> "Show";
            case HIDE -> "Hide";
            case MARK -> "Color";
        };
        String text = what + ": " + d.text;
        if (text.length() > 48) {
            text = text.substring(0, 46) + "…";
        }
        String tip = "<html>Filter " + d.id + ": " + d.action.tip.toLowerCase(java.util.Locale.ROOT)
                + (d.regex ? " (regex)" : "") + (d.matchCase ? " (match case)" : "")
                + "<br>Click to turn it " + (d.enabled ? "off" : "on") + "; right-click for more.</html>";
        Chip c = new Chip(d.color() == null ? null : d.color().mark(), text, d.enabled, tip,
                () -> tab.filterPanel().setFilterEnabled(d, !d.enabled),
                () -> tab.filterPanel().removeFilter(d), menu -> {
                    JCheckBoxMenuItem on = new JCheckBoxMenuItem("On", d.enabled);
                    on.addActionListener(e -> tab.filterPanel().setFilterEnabled(d, on.isSelected()));
                    menu.add(on);
                    menu.addSeparator();
                    for (FilterDef.Action a : FilterDef.Action.values()) {
                        JCheckBoxMenuItem mi = new JCheckBoxMenuItem(
                                a == FilterDef.Action.MARK ? "Color" : a.label, d.action == a);
                        mi.setToolTipText(a.tip);
                        mi.addActionListener(e -> tab.filterPanel().setFilterAction(d, a));
                        menu.add(mi);
                    }
                    menu.addSeparator();
                    JMenuItem edit = new JMenuItem("Edit in Filters…", Icons.of("filter"));
                    edit.addActionListener(e -> tab.showFilters());
                    menu.add(edit);
                    JMenuItem rm = new JMenuItem("Remove", Icons.of("x"));
                    rm.addActionListener(e -> tab.filterPanel().removeFilter(d));
                    menu.add(rm);
                });
        c.count = tab.filterCount(d);
        c.def = d;
        return c;
    }

    /** "1,234 of 50,000 lines" while filtering. */
    void setResultText(String text) {
        result.setText(text);
    }

    void setFollow(boolean on) {
        follow.setSelected(on);
    }

    /** Lights up Reload when the file changed on disk and the change isn't loaded. */
    void updateReload(LogDocument doc) {
        LogDocument.Change c = doc.pendingChange();
        boolean was = stale;
        stale = c != LogDocument.Change.NONE;
        if (stale) {
            reload.setForeground(Theme.isDark() ? new Color(0xFFE7A3) : new Color(0x5C4300));
            reload.setFont(UIManager.getFont("Button.font").deriveFont(Font.BOLD));
            reload.setText(c == LogDocument.Change.GREW
                    ? "Reload (+" + MainFrame.humanBytes(doc.pendingBytes()) + ")" : "Reload (file replaced)");
        } else if (was || !reload.getText().equals("Reload")) {
            reload.setForeground(null);
            reload.setFont(UIManager.getFont("Button.font"));
            reload.setText("Reload");
        }
    }

    String reloadText() {
        return reload.getText();
    }

    /** The chips' texts, for tests. */
    List<String> chipTexts() {
        List<String> out = new ArrayList<>();
        for (java.awt.Component c : chips.getComponents()) {
            if (c instanceof Chip ch) {
                out.add(ch.text + (ch.on ? "" : " (off)"));
            }
        }
        return out;
    }

    /** Chips left to right; the ones that don't fit are hidden and "+N more" says how many. */
    private final class ChipsLayout implements java.awt.LayoutManager {
        private static final int GAP = 4;

        @Override
        public void addLayoutComponent(String name, java.awt.Component c) {}

        @Override
        public void removeLayoutComponent(java.awt.Component c) {}

        @Override
        public Dimension preferredLayoutSize(java.awt.Container p) {
            int w = 0;
            int h = 0;
            for (java.awt.Component c : p.getComponents()) {
                if (c instanceof Chip) {
                    Dimension d = c.getPreferredSize();
                    w += d.width + GAP;
                    h = Math.max(h, d.height);
                }
            }
            return new Dimension(w + GAP, Math.max(h, 20));
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container p) {
            return new Dimension(0, preferredLayoutSize(p).height);
        }

        @Override
        public void layoutContainer(java.awt.Container p) {
            java.awt.Component[] cs = p.getComponents();
            int n = 0;
            for (java.awt.Component c : cs) {
                n += c instanceof Chip ? 1 : 0;
            }
            int width = p.getWidth();
            int h = p.getHeight();
            int x = GAP;
            int shown = 0;
            for (java.awt.Component c : cs) {
                if (!(c instanceof Chip)) {
                    continue;
                }
                Dimension d = c.getPreferredSize();
                int left = n - shown - 1; // chips after this one
                more.setText("+" + (left + 1) + " more");
                int reserve = left > 0 ? more.getPreferredSize().width + GAP : 0;
                if (x + d.width + reserve > width) {
                    break;
                }
                c.setBounds(x, (h - d.height) / 2, d.width, d.height);
                x += d.width + GAP;
                shown++;
            }
            int i = 0;
            for (java.awt.Component c : cs) {
                if (c instanceof Chip && i++ >= shown) {
                    c.setBounds(0, 0, 0, 0);
                }
            }
            if (shown < n) {
                more.setText("+" + (n - shown) + " more");
                Dimension d = more.getPreferredSize();
                more.setBounds(x, (h - d.height) / 2, d.width, d.height);
            } else {
                more.setBounds(0, 0, 0, 0);
            }
        }
    }

    /** A rounded label with a ✕: one filter, or the levels, time range or tree. */
    private static final class Chip extends JComponent {
        final String text;
        final boolean on;
        final Color dot;
        String count = "";
        FilterDef def;
        private final Runnable click;
        private final Runnable remove;
        private boolean overX;

        Chip(Color dot, String text, boolean on, String tip, Runnable click, Runnable remove,
                java.util.function.Consumer<JPopupMenu> menu) {
            this.dot = dot;
            this.text = text;
            this.on = on;
            this.click = click;
            this.remove = remove;
            setToolTipText(tip);
            setFont(UIManager.getFont("Label.font").deriveFont(UIManager.getFont("Label.font").getSize2D() - 1f));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    boolean x = e.getX() >= getWidth() - 18;
                    if (x != overX) {
                        overX = x;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    overX = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup(e);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup(e);
                        return;
                    }
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    if (e.getX() >= getWidth() - 18) {
                        if (remove != null) {
                            remove.run();
                        }
                    } else if (Chip.this.click != null) {
                        Chip.this.click.run();
                    }
                }

                private void popup(MouseEvent e) {
                    if (menu != null) {
                        JPopupMenu p = new JPopupMenu();
                        menu.accept(p);
                        p.show(Chip.this, e.getX(), e.getY());
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private String label() {
            return count.isEmpty() ? text : text + "  " + count;
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.FontMetrics fm = getFontMetrics(getFont());
            int w = (int) Math.ceil(javax.swing.plaf.basic.BasicGraphicsUtils.getStringWidth(this, fm, label()));
            return new Dimension((dot != null ? 20 : 8) + w + 26, fm.getHeight() + 6);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Color fg = on ? Theme.foreground() : Theme.gutterForeground();
            g.setColor(Theme.isDark() ? new Color(0x2F343C) : new Color(0xE9EDF2));
            g.fillRoundRect(0, 1, w - 1, h - 2, h - 2, h - 2);
            if (!on) {
                g.setColor(Theme.gutterForeground());
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                        new float[] {3f, 2f}, 0f));
                g.drawRoundRect(0, 1, w - 1, h - 3, h - 2, h - 2);
            }
            int x = 8;
            if (dot != null) {
                g.setColor(on ? dot : Theme.gutterForeground());
                g.fillOval(x, h / 2 - 4, 8, 8);
                x += 13;
            }
            g.setFont(getFont());
            java.awt.FontMetrics fm = getFontMetrics(getFont());
            int base = (h - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(fg);
            javax.swing.plaf.basic.BasicGraphicsUtils.drawString(this, g, text, x, base);
            if (!count.isEmpty()) {
                g.setColor(Theme.gutterForeground());
                javax.swing.plaf.basic.BasicGraphicsUtils.drawString(this, g, count,
                        x + (int) javax.swing.plaf.basic.BasicGraphicsUtils.getStringWidth(this, fm, text + "  "), base);
            }
            // the ✕
            int cx = w - 11;
            int cy = h / 2;
            if (overX) {
                g.setColor(Theme.isDark() ? new Color(0x454B55) : new Color(0xD3D9E1));
                g.fillOval(cx - 6, cy - 6, 12, 12);
            }
            g.setColor(fg);
            g.setStroke(new BasicStroke(1.3f));
            g.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
            g.drawLine(cx - 3, cy + 3, cx + 3, cy - 3);
            g.dispose();
        }
    }
}
