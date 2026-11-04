package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.Sql;

/**
 * The Query tab: SQL over the log (a table {@code log}: line, time, level, thread, logger,
 * message, text…) or a CSV file (a table {@code t}), with DuckDB. Read-only; results show their
 * rows, and a double-click on a row with a line goes to it in the log.
 */
final class QueryPanel extends JPanel {

    /**
     * Another open table, by the name a query gives it ({@code orders}), with what reading it
     * needs and its columns as SQL names them.
     */
    record Other(String name, Path file, char delimiter, List<String> titles, java.nio.charset.Charset charset,
                 List<String> columns) {}

    /** What the panel needs from its log. */
    interface Host {
        /** The file. */
        Path file();

        /** Whether it's a table (CSV, TSV): then {@code t}, else the log as {@code log}. */
        boolean isTable();

        /** Sets up the session's table, or brings it up to date (a background thread). */
        String prepare(Sql sql, AtomicBoolean stop, java.util.function.DoubleConsumer progress) throws Exception;

        /** The columns a query can use, for the examples and the hint. */
        List<String> columns();

        /** What a table's columns hold (by their names in {@link #columns}), as far as known. */
        default java.util.Map<String, logsift.core.Pivot.Kind> kinds() {
            return java.util.Map.of();
        }

        /** The other tables open in Logsift, which a query here can name too. */
        default List<Other> others() {
            return List.of();
        }

        /** Shows a line (1-based, as Logsift numbers them). */
        void goToLine(long line);

        /**
         * A table's line by its row's number as the database counts it (1-based, the header
         * being 1): rows over several lines push the ones after them down.
         */
        default long lineOfRow(long row) {
            return row;
        }

        /**
         * The query's answer from the cache of a big table (its {@code line}s the lines rows start
         * at), after {@link #prepare}; null to query the table the usual way.
         */
        default Sql.Result fromCache(String query, int maxRows, AtomicBoolean stop,
                                     java.util.function.DoubleConsumer progress,
                                     java.util.function.Consumer<String> say) throws Exception {
            return null;
        }
    }

    static final int MAX_ROWS = 10_000;

    /** Whether the last answer came from the cache of a big table. */
    private volatile boolean cached;

    private final Host host;
    private final JTextArea editor = new JTextArea(5, 60);
    private final JButton run = new JButton("Run", Icons.of("square-terminal"));
    private final JButton stop = new JButton("Stop");
    private final JButton examples = new JButton("Examples", Icons.of("chevron-down"));
    private final JButton copy = new JButton("Copy", Icons.of("copy"));
    private final JLabel hint = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final ResultModel model = new ResultModel();
    private final JTable table = new JTable(model);
    private Sql sql;
    private volatile AtomicBoolean running;
    private Sql.Result last;
    private String failure;
    private boolean typed;

    QueryPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        top.add(hint, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        bar.setPreferredSize(new Dimension(90, 10));
        bar.setVisible(false);
        buttons.add(bar);
        examples.setHorizontalTextPosition(JButton.LEFT);
        examples.setToolTipText("Queries to start from");
        examples.addActionListener(e -> examplesMenu().show(examples, 0, examples.getHeight()));
        buttons.add(examples);
        run.putClientProperty("JButton.buttonType", "default");
        run.setToolTipText("Run the query (Ctrl+Enter)");
        run.addActionListener(e -> run());
        buttons.add(run);
        stop.setVisible(false);
        stop.addActionListener(e -> {
            AtomicBoolean r = running;
            if (r != null) {
                r.set(true);
            }
        });
        buttons.add(stop);
        top.add(buttons, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        editor.setFont(Theme.monoFont(editor.getFont().getSize()));
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setTabSize(4);
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "run");
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "run");
        editor.getActionMap().put("run", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                run();
            }
        });
        editor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                typed = true;
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                typed = true;
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });

        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        DefaultTableCellRenderer cells = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v == null ? "" : v, sel, focus, row, col);
                setHorizontalAlignment(v instanceof Number ? RIGHT : LEFT);
                if (!sel) {
                    setForeground(v == null ? Theme.uiColor("Label.disabledForeground", Color.GRAY) : t.getForeground());
                }
                if (v == null) {
                    setText("null");
                    setFont(t.getFont().deriveFont(Font.ITALIC));
                } else if (v instanceof Number n) {
                    setText(number(n));
                }
                return this;
            }
        };
        table.setDefaultRenderer(Object.class, cells);
        table.setDefaultRenderer(Number.class, cells);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    goToRow(table.convertRowIndexToModel(table.getSelectedRow()));
                }
            }
        });
        JPanel results = new JPanel(new BorderLayout());
        JPanel resultBar = new JPanel(new BorderLayout(8, 0));
        resultBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        resultBar.add(status, BorderLayout.CENTER);
        copy.putClientProperty("JButton.buttonType", "toolBarButton");
        copy.setToolTipText("Copies the rows as tab-separated text, with the column names");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(resultText()), null));
        copy.setEnabled(false);
        resultBar.add(copy, BorderLayout.EAST);
        results.add(resultBar, BorderLayout.NORTH);
        results.add(new JScrollPane(table), BorderLayout.CENTER);
        JScrollPane ed = new JScrollPane(editor);
        ed.setMinimumSize(new Dimension(100, editor.getFontMetrics(editor.getFont()).getHeight() * 3 + 8));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ed, results);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        // loading DuckDB takes a moment: found out in the background, never while a file opens
        if (Sql.availableIfKnown() == null) {
            Sql.checkInBackground(() -> SwingUtilities.invokeLater(this::availabilityKnown));
        } else {
            availabilityKnown();
        }
    }

    private void availabilityKnown() {
        if (Boolean.FALSE.equals(Sql.availableIfKnown())) {
            run.setEnabled(false);
            examples.setEnabled(false);
            editor.setEnabled(false);
            status.setText("Query isn't available here: " + Sql.unavailableReason());
        } else if (isShowing()) {
            showing();
        }
    }

    /** The tab is shown: a first query to start from. */
    void showing() {
        refreshHint();
        if (!typed && editor.getText().isBlank() && Boolean.TRUE.equals(Sql.availableIfKnown())) {
            List<String[]> ex = examples();
            if (!ex.isEmpty()) {
                setQuery(ex.get(0)[1]);
            }
        }
    }

    private void setQuery(String q) {
        editor.setText(q);
        editor.setCaretPosition(0);
        typed = false;
    }

    /** Columns named in the hint; a wider table's others are counted (they'd hide the results). */
    static final int HINT_COLUMNS = 24;

    private void refreshHint() {
        List<String> cols = host.columns();
        hint.setText(hintText(host.isTable(), cols, host.others().stream().map(Other::name).toList()));
        hint.setToolTipText(cols.size() > HINT_COLUMNS ? "<html><p width='600'>" + esc(String.join(", ", cols)) + "</p></html>"
                : null);
    }

    static String hintText(boolean table, List<String> cols, List<String> others) {
        String name = table ? "t" : "log";
        String shown = String.join(", ", cols.subList(0, Math.min(cols.size(), HINT_COLUMNS)));
        if (cols.size() > HINT_COLUMNS) {
            shown += String.format(Locale.ROOT, "… and %,d more: DESCRIBE %s lists them", cols.size() - HINT_COLUMNS, name);
        }
        String also = others.isEmpty() ? "" : " Other open tables: <b>" + esc(String.join(", ", others.subList(0,
                Math.min(others.size(), 8)))) + "</b>" + (others.size() > 8 ? "…" : "") + ".";
        return "<html>SQL over this " + (table ? "table" : "log") + ": <b>" + name + "</b> (" + esc(shown)
                + ")." + also + " Read-only. Ctrl+Enter runs.</html>";
    }

    String hintForTest() {
        return hint.getText();
    }

    /** {title, query} for the file. */
    List<String[]> examples() {
        List<String> cols = host.columns();
        List<logsift.core.QueryExamples.Example> ex = host.isTable()
                ? logsift.core.QueryExamples.forTable(cols, host.kinds())
                : logsift.core.QueryExamples.forLog(cols);
        java.util.Map<String, List<String>> others = new java.util.LinkedHashMap<>();
        for (Other o : host.others()) {
            others.put(o.name(), o.columns());
        }
        ex = new ArrayList<>(ex);
        ex.addAll(logsift.core.QueryExamples.withOthers(host.isTable() ? "t" : "log", cols, others));
        return ex.stream().map(e -> new String[] {e.title(), e.sql()}).toList();
    }

    private JPopupMenu examplesMenu() {
        JPopupMenu m = new JPopupMenu();
        for (String[] e : examples()) {
            JMenuItem it = new JMenuItem(e[0]);
            it.addActionListener(a -> {
                setQuery(e[1]);
                run();
            });
            m.add(it);
        }
        return m;
    }

    // ---------------------------------------------------------------- running

    void run() {
        if (running != null || Boolean.FALSE.equals(Sql.availableIfKnown())) {
            return;
        }
        String q = editor.getText();
        refreshHint(); // tables opened or closed since
        List<Other> others = host.others(); // here: the other tabs are read on this thread
        AtomicBoolean stopNow = new AtomicBoolean();
        running = stopNow;
        run.setEnabled(false);
        stop.setVisible(true);
        bar.setVisible(true);
        bar.setIndeterminate(true);
        status.setText("Running…");
        cached = false;
        Thread t = new Thread(() -> {
            Sql.Result r = null;
            String err = null;
            try {
                List<Path> folders = readable(others);
                if (sql != null && !sqlFolders.containsAll(folders)) {
                    sql.close(); // another table in a folder this session can't read: a new one
                    sql = null;
                }
                if (sql == null) {
                    sql = Sql.open(new Sql.Options(folders, null));
                    sqlFolders = folders;
                    registered.clear();
                }
                String ready = host.prepare(sql, stopNow, p -> SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    bar.setValue((int) (p * 100));
                    status.setText("Getting the log ready for SQL (once; new lines are added as it grows)…");
                }));
                if (ready == null) {
                    try {
                        registerOthers(others);
                    } catch (java.sql.SQLException e) {
                        // a table that can't be read as one now: queries without it still run
                    }
                }
                if (ready != null) {
                    err = ready;
                } else {
                    SwingUtilities.invokeLater(() -> {
                        bar.setIndeterminate(true);
                        status.setText("Running…");
                    });
                    r = host.fromCache(q, MAX_ROWS, stopNow, p -> SwingUtilities.invokeLater(() -> {
                        bar.setIndeterminate(false);
                        bar.setValue((int) (p * 100));
                    }), say -> SwingUtilities.invokeLater(() -> {
                        bar.setIndeterminate(true);
                        status.setText(say);
                    }));
                    cached = r != null;
                    if (r == null) {
                        r = sql.query(q, MAX_ROWS, stopNow::get);
                        if (host.isTable()) {
                            linesOfRows(r);
                        }
                    }
                }
            } catch (java.sql.SQLException e) {
                err = e.getMessage();
            } catch (OutOfMemoryError e) {
                err = "Not enough memory for this (" + MainFrame.memoryAdvice() + ")";
            } catch (Exception e) {
                logsift.core.Problems.unexpected(e);
                err = String.valueOf(e.getMessage());
            }
            Sql.Result done = r;
            String failed = err;
            SwingUtilities.invokeLater(() -> shown(stopNow, done, failed));
        }, "logsift-query");
        t.setDaemon(true);
        t.start();
    }

    private List<Path> readable(List<Other> others) {
        List<Path> out = new ArrayList<>();
        Path dir = host.file().toAbsolutePath().getParent();
        if (dir != null) {
            out.add(dir);
        }
        out.add(Path.of(System.getProperty("user.home")));
        for (Other o : others) {
            Path d = o.file().toAbsolutePath().getParent();
            if (d != null && !out.contains(d)) {
                out.add(d); // the other tables' folders: DuckDB reads them
            }
        }
        return out;
    }

    /** The folders the session was opened with: SQL's settings are locked, so more need a new one. */
    private List<Path> sqlFolders = List.of();
    /** The other tables named in the session, as registered. */
    private final java.util.Map<String, Other> registered = new java.util.HashMap<>();

    /** Names the other open tables in the session, and forgets those closed since. */
    private void registerOthers(List<Other> others) throws java.sql.SQLException {
        java.util.Set<String> now = new java.util.HashSet<>();
        for (Other o : others) {
            now.add(o.name());
            if (!o.equals(registered.get(o.name()))) {
                sql.csv(o.name(), o.file(), o.delimiter(), o.titles(), o.charset());
                registered.put(o.name(), o);
            }
        }
        for (String gone : new ArrayList<>(registered.keySet())) {
            if (!now.contains(gone)) {
                sql.dropView(gone);
                registered.remove(gone);
            }
        }
    }

    private void shown(AtomicBoolean which, Sql.Result r, String err) {
        if (running != which) {
            return;
        }
        running = null;
        run.setEnabled(true);
        stop.setVisible(false);
        bar.setVisible(false);
        last = r;
        failure = err;
        if (err != null) {
            model.set(null);
            copy.setEnabled(false);
            status.setText("<html><span style='color:#dc2626'>" + esc(firstLines(err, 4)).replace("\n", "<br>")
                    + "</span></html>");
            return;
        }
        model.set(r);
        copy.setEnabled(!r.rows().isEmpty());
        for (int c = 0; c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(width(c));
        }
        boolean hasLine = r.columns().stream().anyMatch(c -> c.equalsIgnoreCase("line"));
        status.setText(String.format(Locale.ROOT, "%,d %s%s in %,d ms%s%s", r.rows().size(), r.rows().size() == 1 ? "row" : "rows",
                r.more() ? " (the first " + String.format(Locale.ROOT, "%,d", MAX_ROWS) + ")" : "", r.millis(),
                cached ? ", from the cache" : "", hasLine ? " · double-click a row to see its line" : ""));
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String firstLines(String s, int n) {
        return String.join("\n", s.lines().limit(n).toList());
    }

    private int width(int c) {
        int w = Math.max(60, table.getFontMetrics(table.getFont()).stringWidth(model.getColumnName(c)) + 24);
        for (int r = 0; r < Math.min(50, model.getRowCount()); r++) {
            Object v = model.getValueAt(r, c);
            String s = v == null ? "null" : v instanceof Number n ? number(n) : v.toString();
            w = Math.max(w, Math.min(480, table.getFontMetrics(table.getFont()).stringWidth(s) + 16));
        }
        return w;
    }

    private static String number(Number n) {
        if (n instanceof Double || n instanceof Float || n instanceof java.math.BigDecimal) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.format(Locale.ROOT, "%,.0f", d)
                    : String.format(Locale.ROOT, "%,.3f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return String.format(Locale.ROOT, "%,d", n.longValue());
    }

    /** A table's "line" values are its rows' numbers: they become the lines the rows start at. */
    private void linesOfRows(Sql.Result r) {
        for (int c = 0; c < r.columns().size(); c++) {
            if (r.columns().get(c).equalsIgnoreCase("line")) {
                for (Object[] row : r.rows()) {
                    if (row[c] instanceof Number n) {
                        row[c] = host.lineOfRow(n.longValue());
                    }
                }
            }
        }
    }

    private void goToRow(int row) {
        Sql.Result r = last;
        if (r == null) {
            return;
        }
        for (int c = 0; c < r.columns().size(); c++) {
            if (r.columns().get(c).equalsIgnoreCase("line") && r.rows().get(row)[c] instanceof Number n) {
                host.goToLine(n.longValue());
                return;
            }
        }
    }

    String resultText() {
        Sql.Result r = last;
        if (r == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(String.join("\t", r.columns())).append('\n');
        for (Object[] row : r.rows()) {
            for (int i = 0; i < row.length; i++) {
                b.append(i == 0 ? "" : "\t").append(row[i] == null ? "" : row[i] instanceof Number n ? n.toString() : row[i]);
            }
            b.append('\n');
        }
        return b.toString();
    }

    void close() {
        AtomicBoolean r = running;
        if (r != null) {
            r.set(true);
        }
        Sql s = sql;
        sql = null;
        if (s != null) {
            Thread t = new Thread(s::close, "logsift-query-close");
            t.setDaemon(true);
            t.start();
        }
    }

    private static final class ResultModel extends AbstractTableModel {
        Sql.Result r;

        void set(Sql.Result r) {
            this.r = r;
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return r == null ? 0 : r.rows().size();
        }

        @Override
        public int getColumnCount() {
            return r == null ? 0 : r.columns().size();
        }

        @Override
        public String getColumnName(int c) {
            return r.columns().get(c);
        }

        @Override
        public Class<?> getColumnClass(int c) {
            for (Object[] row : r.rows()) {
                if (row[c] != null) {
                    return row[c] instanceof Number ? Number.class : Object.class;
                }
            }
            return Object.class;
        }

        @Override
        public Object getValueAt(int row, int c) {
            return r.rows().get(row)[c];
        }
    }

    // ---------------------------------------------------------------- for tests

    /** Puts a query in the box and runs it (from the assistant: "Open in Query"). */
    void runQuery(String q) {
        setQuery(q);
        run();
    }

    void setQueryForTest(String q) {
        setQuery(q);
    }

    boolean runningForTest() {
        return running != null;
    }

    Sql.Result resultForTest() {
        return last;
    }

    String failureForTest() {
        return failure;
    }

    String statusForTest() {
        return status.getText();
    }

    JTable tableForTest() {
        return table;
    }

    void goToRowForTest(int row) {
        goToRow(row);
    }

    JComponent editorForTest() {
        return editor;
    }
}
