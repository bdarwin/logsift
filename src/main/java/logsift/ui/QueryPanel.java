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

        /** Shows a line (1-based, as Logsift numbers them). */
        void goToLine(long line);

        /**
         * A table's line by its row's number as the database counts it (1-based, the header
         * being 1): rows over several lines push the ones after them down.
         */
        default long lineOfRow(long row) {
            return row;
        }
    }

    static final int MAX_ROWS = 10_000;

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

    private void refreshHint() {
        String name = host.isTable() ? "t" : "log";
        hint.setText("<html>SQL over this " + (host.isTable() ? "table" : "log") + ": <b>" + name + "</b> ("
                + esc(String.join(", ", host.columns())) + "). Read-only. Ctrl+Enter runs.</html>");
    }

    /** {title, query} for the file. */
    List<String[]> examples() {
        List<String> cols = host.columns();
        List<String[]> ex = new ArrayList<>();
        if (host.isTable()) {
            ex.add(new String[] {"The first rows", "SELECT * FROM t LIMIT 100"});
            ex.add(new String[] {"Every column summarized (min, max, distinct, nulls…)", "SUMMARIZE t"});
            String text = cols.stream().filter(c -> !c.equals("line")).findFirst().orElse(null);
            if (text != null) {
                ex.add(new String[] {"Rows per value of " + text,
                        "SELECT " + Sql.ident(text) + ", count(*) AS rows\nFROM t\nGROUP BY ALL\nORDER BY rows DESC"});
            }
            return ex;
        }
        boolean level = cols.contains("level");
        boolean message = cols.contains("message");
        if (level) {
            ex.add(new String[] {"Lines per level",
                    "SELECT level, count(*) AS lines\nFROM log\nWHERE starts\nGROUP BY level\nORDER BY lines DESC"});
            ex.add(new String[] {"Errors per minute",
                    "SELECT date_trunc('minute', time) AS minute, count(*) AS errors\nFROM log\nWHERE level = 'ERROR'\n"
                            + "GROUP BY minute\nORDER BY minute"});
        }
        if (level && message) {
            ex.add(new String[] {"The most frequent errors (numbers blanked)",
                    "SELECT regexp_replace(message, '[0-9]+', '#', 'g') AS kind, count(*) AS times, min(line) AS first\n"
                            + "FROM log\nWHERE level = 'ERROR'\nGROUP BY kind\nORDER BY times DESC\nLIMIT 50"});
        }
        if (cols.contains("logger") && level) {
            ex.add(new String[] {"Errors and warnings by logger",
                    "PIVOT (SELECT logger, level FROM log WHERE level IN ('ERROR', 'WARN'))\nON level\nUSING count(*)\n"
                            + "ORDER BY 2 DESC"});
        }
        ex.add(new String[] {"Durations in messages (… 345 ms): slowest first",
                "SELECT line, time, CAST(regexp_extract(" + (message ? "message" : "text")
                        + ", '([0-9]+) ?ms', 1) AS INTEGER) AS ms, " + (message ? "message" : "text") + "\nFROM log\n"
                        + "WHERE " + (message ? "message" : "text") + " SIMILAR TO '.*[0-9]+ ?ms.*'\nORDER BY ms DESC\nLIMIT 100"});
        ex.add(new String[] {"Lines with a text", "SELECT line, " + (cols.contains("time") ? "time, " : "") + "text\nFROM log\n"
                + "WHERE text ILIKE '%timeout%'\nLIMIT 1000"});
        ex.add(new String[] {"Lines per hour", "SELECT date_trunc('hour', time) AS hour, count(*) AS lines\nFROM log\n"
                + "WHERE time IS NOT NULL\nGROUP BY hour\nORDER BY hour"});
        return ex;
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
        AtomicBoolean stopNow = new AtomicBoolean();
        running = stopNow;
        run.setEnabled(false);
        stop.setVisible(true);
        bar.setVisible(true);
        bar.setIndeterminate(true);
        status.setText("Running…");
        Thread t = new Thread(() -> {
            Sql.Result r = null;
            String err = null;
            try {
                if (sql == null) {
                    sql = Sql.open(new Sql.Options(readable(), null));
                }
                String ready = host.prepare(sql, stopNow, p -> SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    bar.setValue((int) (p * 100));
                    status.setText("Getting the log ready for SQL (once; new lines are added as it grows)…");
                }));
                if (ready != null) {
                    err = ready;
                } else {
                    SwingUtilities.invokeLater(() -> {
                        bar.setIndeterminate(true);
                        status.setText("Running…");
                    });
                    r = sql.query(q, MAX_ROWS, stopNow::get);
                    if (host.isTable()) {
                        linesOfRows(r);
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

    private List<Path> readable() {
        List<Path> out = new ArrayList<>();
        Path dir = host.file().toAbsolutePath().getParent();
        if (dir != null) {
            out.add(dir);
        }
        out.add(Path.of(System.getProperty("user.home")));
        return out;
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
        status.setText(String.format(Locale.ROOT, "%,d %s%s in %,d ms%s", r.rows().size(), r.rows().size() == 1 ? "row" : "rows",
                r.more() ? " (the first " + String.format(Locale.ROOT, "%,d", MAX_ROWS) + ")" : "", r.millis(),
                hasLine ? " · double-click a row to see its line" : ""));
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
