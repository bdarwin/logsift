package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import logsift.core.HitList;
import logsift.core.LogDocument;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;

/**
 * The find bar at the top of a log (Ctrl+F): matches are highlighted as you type, Enter goes to
 * the next one and Shift+Enter to the previous one. From here a search can become a Find All
 * (every match listed below the log) or a filter. Esc closes it.
 */
final class FindBar extends JPanel {

    interface Host {
        LogDocument document();

        /** The selected line, or -1. */
        long currentLine();

        /** The first line in view. */
        long firstVisibleLine();

        void showLine(long line);

        /** The search or its hits changed: repaint highlights and marks. */
        void searchChanged();

        void findAll(SearchQuery q);

        void filter(SearchQuery q, FilterDef.Action action);

        void searchInFolder(String text);

        /** The bar was closed: back to the log. */
        void closed();
    }

    private static final int TYPING_MS = 200;
    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private final Host host;
    private final JTextField field = new JTextField(30);
    private final JToggleButton matchCase = MainFrame.toggle("Aa", "Match case");
    private final JToggleButton wholeWord = MainFrame.toggle("W", "Whole word");
    private final JToggleButton regex = MainFrame.toggle(".*", "Regular expression");
    private final JLabel count = new JLabel();
    private final Timer typing;
    private final AtomicBoolean updateQueued = new AtomicBoolean();
    private SearchSession session;
    private SearchQuery query;
    private boolean jumpPending; // go to the first match once there is one
    private long anchor;         // ... at or after this line

    FindBar(Host host) {
        super(new BorderLayout());
        this.host = host;
        typing = new Timer(TYPING_MS, e -> restart());
        typing.setRepeats(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));

        field.putClientProperty("JTextField.placeholderText", "Find in this log");
        field.putClientProperty("JTextField.leadingIcon", Icons.find());
        field.putClientProperty("JTextField.showClearButton", true);
        JToolBar inField = new JToolBar();
        inField.setFloatable(false);
        inField.setOpaque(false);
        inField.add(matchCase);
        inField.add(wholeWord);
        inField.add(regex);
        field.putClientProperty("JTextField.trailingComponent", inField);
        field.setMaximumSize(new Dimension(460, 30));
        field.setPreferredSize(new Dimension(380, field.getPreferredSize().height));
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
            public void changedUpdate(DocumentEvent e) {
                typing.restart();
            }
        });
        for (JToggleButton b : new JToggleButton[] {matchCase, wholeWord, regex}) {
            b.addActionListener(e -> restart());
        }
        key(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next", () -> next(true));
        key(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previous",
                () -> next(false));
        key(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, MENU), "findAll", this::findAll);
        key(this, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::close);

        JButton prev = button(Icons.up(), "Previous match (Shift+Enter)", () -> next(false));
        JButton next = button(Icons.down(), "Next match (Enter)", () -> next(true));
        JButton all = new JButton("Find All", Icons.find());
        all.setToolTipText("List every match below the log (" + (MainFrame.isMac() ? "⌘" : "Ctrl+") + "Enter)");
        all.addActionListener(e -> findAll());
        JButton filter = new JButton("Filter ▾", Icons.of("filter"));
        filter.setToolTipText("Turn this search into a filter");
        filter.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            m.add(menuItem("Show Only Matching Lines", "eye", () -> filter(FilterDef.Action.SHOW)));
            m.add(menuItem("Hide Matching Lines", "eye-off", () -> filter(FilterDef.Action.HIDE)));
            m.add(menuItem("Color Matching Lines", "list-filter", () -> filter(FilterDef.Action.MARK)));
            m.show(filter, 0, filter.getHeight());
        });
        JButton folder = button(Icons.of("folder-search"), "Search for this in a folder ("
                + (MainFrame.isMac() ? "⇧⌘F" : "Ctrl+Shift+F") + ")", () -> host.searchInFolder(field.getText()));
        JButton close = button(Icons.of("x"), "Close (Esc)", this::close);
        for (JButton b : new JButton[] {all, filter}) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
        }
        count.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        count.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(field);
        row.add(count);
        row.add(prev);
        row.add(next);
        row.add(Box.createHorizontalStrut(6));
        row.add(all);
        row.add(filter);
        row.add(folder);
        row.add(Box.createHorizontalGlue());
        row.add(close);
        add(row, BorderLayout.CENTER);
        setVisible(false);
    }

    private static void key(JComponent c, KeyStroke k, String name, Runnable r) {
        c.getInputMap(c instanceof JTextField ? WHEN_FOCUSED : WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(k, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }

    private static JButton button(javax.swing.Icon icon, String tip, Runnable r) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JMenuItem menuItem(String text, String icon, Runnable r) {
        JMenuItem mi = new JMenuItem(text, Icons.of(icon));
        mi.addActionListener(e -> r.run());
        return mi;
    }

    // ---------------------------------------------------------------- opening and closing

    /** Shows the bar and puts the cursor in it; {@code text} (if not null) becomes the search. */
    void open(String text) {
        boolean was = isVisible();
        setVisible(true);
        if (text != null && !text.equals(field.getText())) {
            field.setText(text);
            restart();
        } else if (!was && !field.getText().isEmpty()) {
            restart(); // the last search again
        }
        revalidate();
        field.requestFocusInWindow();
        field.selectAll();
    }

    /** Hides the bar; its highlights go away (the text stays for next time). */
    void close() {
        typing.stop();
        stopSession();
        setVisible(false);
        count.setText("");
        host.searchChanged();
        host.closed();
    }

    /** Sets the search (text and options) without running it. */
    void setQuery(SearchQuery q) {
        matchCase.setSelected(q.matchCase());
        wholeWord.setSelected(q.wholeWord());
        regex.setSelected(q.regex());
        field.setText(q.text());
        typing.stop();
    }

    String text() {
        return field.getText();
    }

    JTextField field() {
        return field;
    }

    /** The search in the box, or null when it is empty. */
    SearchQuery typedQuery() {
        String t = field.getText();
        return t.isEmpty() ? null
                : new SearchQuery(t, matchCase.isSelected(), regex.isSelected(), wholeWord.isSelected());
    }

    // ---------------------------------------------------------------- searching

    /** Starts the search for what is in the box now. */
    private void restart() {
        typing.stop();
        stopSession();
        field.putClientProperty("JComponent.outline", null);
        field.setToolTipText(null);
        SearchQuery q = isVisible() ? typedQuery() : null;
        if (q == null) {
            count.setText("");
            host.searchChanged();
            return;
        }
        try {
            q.toPattern();
        } catch (PatternSyntaxException e) {
            field.putClientProperty("JComponent.outline", "error");
            field.setToolTipText("Invalid regular expression: " + e.getDescription());
            count.setText("invalid regex");
            host.searchChanged();
            return;
        }
        query = q;
        long cur = host.currentLine();
        anchor = cur >= 0 ? cur : host.firstVisibleLine();
        jumpPending = true;
        SearchSession s = new SearchSession(host.document(), q);
        session = s;
        s.addListener(new SearchSession.Listener() {
            @Override
            public void updated(SearchSession ss) {
                queueUpdate();
            }

            @Override
            public void caughtUp(SearchSession ss, long ms) {
                queueUpdate();
            }
        });
        s.start();
        host.searchChanged();
        updated();
    }

    private void queueUpdate() {
        if (updateQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                updateQueued.set(false);
                updated();
            });
        }
    }

    private void updated() {
        SearchSession s = session;
        if (s == null) {
            return;
        }
        HitList hits = s.hits();
        int n = hits.size();
        if (jumpPending && n > 0) {
            int i = hits.firstAtOrAfter(anchor);
            if (i < n) {
                jumpPending = false;
                host.showLine(hits.get(i));
            } else if (!s.isRunning()) {
                jumpPending = false;
                host.showLine(hits.get(0)); // none after: from the top
            }
        }
        updateCount();
        host.searchChanged();
    }

    /** "3 of 1,240" when the selected line is a match, else "1,240 matches". */
    void updateCount() {
        SearchSession s = session;
        if (s == null) {
            return;
        }
        HitList hits = s.hits();
        int n = hits.size();
        boolean running = s.isRunning() || host.document().isIndexing();
        String more = s.isLimitReached() ? "+" : running ? "…" : "";
        if (n == 0) {
            count.setText(running ? "searching…" : "no matches");
            field.putClientProperty("JComponent.outline", running ? null : "warning");
            return;
        }
        field.putClientProperty("JComponent.outline", null);
        long cur = host.currentLine();
        int i = cur < 0 ? n : hits.firstAtOrAfter(cur);
        count.setText(i < n && hits.get(i) == cur
                ? String.format("%,d of %,d%s", i + 1, n, more)
                : String.format("%,d %s%s", n, n == 1 ? "match" : "matches", more));
    }

    /** Goes to the next (or previous) match from the selected line, wrapping around. */
    boolean next(boolean forward) {
        if (typing.isRunning() || session == null || !query.equals(typedQuery())) {
            if (typedQuery() == null) {
                return false;
            }
            restart(); // goes to the first match itself
            return true;
        }
        HitList hits = session.hits();
        int n = hits.size();
        if (n == 0) {
            Toolkit.getDefaultToolkit().beep();
            return false;
        }
        jumpPending = false;
        long cur = host.currentLine();
        if (cur < 0) {
            cur = host.firstVisibleLine() - 1;
        }
        int i;
        if (forward) {
            i = hits.firstAtOrAfter(cur + 1);
            if (i >= n) {
                i = 0;
            }
        } else {
            i = hits.firstAtOrAfter(cur) - 1;
            if (i < 0) {
                i = n - 1;
            }
        }
        host.showLine(hits.get(i));
        updateCount();
        return true;
    }

    private void findAll() {
        SearchQuery q = typedQuery();
        if (q == null) {
            return;
        }
        try {
            q.toPattern();
        } catch (PatternSyntaxException e) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        host.findAll(q);
    }

    void filter(FilterDef.Action action) {
        SearchQuery q = typedQuery();
        if (q == null) {
            return;
        }
        try {
            q.toPattern();
        } catch (PatternSyntaxException e) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        host.filter(q, action);
        close(); // the filter's color shows the lines now
    }

    // ---------------------------------------------------------------- for the log

    /** The live search, or null when the bar is closed or empty. */
    SearchSession session() {
        return session;
    }

    /** How the log shows this search's matches, or null. */
    LogView.Highlight highlight() {
        return session == null ? null : new LogView.Highlight(query.toPattern(), Theme.matchLight(), Theme.matchDark());
    }

    OverviewBar.Marks marks() {
        return session == null ? null : OverviewBar.of(session.hits());
    }

    String countText() {
        return count.getText();
    }

    private void stopSession() {
        if (session != null) {
            session.close();
            session = null;
        }
        query = null;
    }

    void dispose() {
        typing.stop();
        stopSession();
    }
}
