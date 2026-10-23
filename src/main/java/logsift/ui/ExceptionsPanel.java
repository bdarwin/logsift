package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.Exceptions;

/**
 * The log's exceptions grouped by root cause and the place in the program's own code that threw
 * it: how often, since when, and the stack, with the program's own frames as links to the
 * source. Step through the occurrences of one with ◀ ▶.
 */
final class ExceptionsPanel extends JPanel {

    interface Host {
        /** Finds and groups the exceptions (on a background thread); null if cancelled. */
        Exceptions.Result analyze(AtomicBoolean cancel, java.util.function.DoubleConsumer progress);

        void goToLine(long line);

        /** "10:02:13" for a line, or its number when the log has no times. */
        String describeLine(long line);

        void findAll(String regex);

        /** Opens a frame's source file at its line (in the editor chosen in Settings). */
        void openSource(Exceptions.Frame frame);

        /** The number of groups changed (for the tab title). */
        void resultChanged(Exceptions.Result result);
    }

    private final Host host;
    private final JLabel summary = new JLabel("Exceptions grouped by root cause and where they were thrown.");
    private final JButton refresh = new JButton("Refresh", Icons.reload());
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JEditorPane stack = new JEditorPane();
    private final JLabel occurrence = new JLabel(" ");
    private final JButton prev = new JButton("◀");
    private final JButton next = new JButton("▶");
    private final JButton findAll = new JButton("Find All", Icons.of("search"));
    private Exceptions.Result result;
    private AtomicBoolean running;
    private long analyzedLines = -1;
    private int at; // the occurrence shown of the selected group
    private boolean quiet; // selecting without going to the line (results just arrived)

    ExceptionsPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        top.add(summary);
        top.add(Box.createHorizontalGlue());
        bar.setPreferredSize(new Dimension(120, 10));
        bar.setMaximumSize(new Dimension(120, 10));
        bar.setVisible(false);
        top.add(bar);
        top.add(Box.createHorizontalStrut(8));
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.setToolTipText("Look through the log again (it may have grown)");
        refresh.addActionListener(e -> start());
        top.add(refresh);
        add(top, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {56, 330, 210, 112, 112};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(0).setCellRenderer(right);
        table.getColumnModel().getColumn(1).setCellRenderer(new ExceptionRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                at = 0;
                showSelected(!quiet);
            }
        });

        stack.setContentType("text/html");
        stack.setEditable(false);
        stack.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        stack.setFont(Theme.monoFont(table.getFont().getSize2D()));
        stack.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
                openLink(e.getDescription());
            }
        });
        JPanel right2 = new JPanel(new BorderLayout());
        JPanel nav = new JPanel();
        nav.setLayout(new BoxLayout(nav, BoxLayout.X_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        for (JButton b : List.of(prev, next, findAll)) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            b.setEnabled(false);
        }
        prev.setToolTipText("The occurrence before (and go to it in the log)");
        next.setToolTipText("The next occurrence (and go to it in the log)");
        findAll.setToolTipText("Find all lines with this exception");
        prev.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));
        findAll.addActionListener(e -> {
            Exceptions.Group g = selected();
            if (g != null) {
                host.findAll(java.util.regex.Pattern.quote(g.root().type()) + "(?::|$)");
            }
        });
        nav.add(prev);
        nav.add(occurrence);
        nav.add(next);
        nav.add(Box.createHorizontalGlue());
        nav.add(findAll);
        right2.add(nav, BorderLayout.NORTH);
        right2.add(new JScrollPane(stack), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), right2);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        showStack(null);
    }

    Exceptions.Result result() {
        return result;
    }

    boolean isRunning() {
        return running != null;
    }

    long analyzedLines() {
        return analyzedLines;
    }

    JTable tableForTest() {
        return table;
    }

    String stackForTest() {
        return stack.getText();
    }

    String summaryForTest() {
        return summary.getText();
    }

    String occurrenceForTest() {
        return occurrence.getText();
    }

    void stepForTest(int d) {
        step(d);
    }

    void openLinkForTest(String link) {
        openLink(link);
    }

    /** Looks through the log (again). */
    void start() {
        if (running != null) {
            running.set(true);
        }
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        bar.setValue(0);
        bar.setVisible(true);
        refresh.setEnabled(false);
        summary.setText("Looking for exceptions…");
        Thread t = new Thread(() -> {
            Exceptions.Result r = null;
            String error = null;
            try {
                r = host.analyze(cancel, p -> SwingUtilities.invokeLater(() -> bar.setValue((int) (p * 1000))));
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            Exceptions.Result done = r;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                bar.setVisible(false);
                refresh.setEnabled(true);
                if (failed != null) {
                    summary.setText("Could not look through the log: " + failed);
                } else if (done != null) {
                    useResult(done, -1);
                }
            });
        }, "logsift-exceptions");
        t.setDaemon(true);
        t.start();
    }

    void cancel() {
        if (running != null) {
            running.set(true);
            running = null;
        }
    }

    /** Shows groups found elsewhere (by Insights), for a log of {@code lines} lines (-1: unknown). */
    void useResult(Exceptions.Result r, long lines) {
        if (running != null) {
            return; // a refresh is on its way
        }
        result = r;
        analyzedLines = lines;
        model.fireTableDataChanged();
        if (r.exceptions() == 0) {
            summary.setText("No exceptions with a stack in this log.");
        } else {
            summary.setText(String.format("%,d exception%s, %d different%s", r.exceptions(),
                    r.exceptions() == 1 ? "" : "s", r.groups().size(),
                    r.truncated() ? " (counted the first " + String.format("%,d", r.exceptions()) + ")" : ""));
        }
        if (!r.groups().isEmpty()) {
            quiet = true; // the log stays where it is (it may be following its end)
            try {
                table.setRowSelectionInterval(0, 0);
            } finally {
                quiet = false;
            }
        } else {
            showStack(null);
        }
        host.resultChanged(r);
    }

    /** Selects the group with an occurrence at this line (from Insights). */
    void select(long line) {
        if (result == null) {
            return;
        }
        for (int i = 0; i < result.groups().size(); i++) {
            Exceptions.Group g = result.groups().get(i);
            if (g.firstLine() == line || java.util.Arrays.binarySearch(g.keptLines(), line) >= 0) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    private Exceptions.Group selected() {
        int row = table.getSelectedRow();
        return result == null || row < 0 || row >= result.groups().size() ? null : result.groups().get(row);
    }

    private void step(int d) {
        Exceptions.Group g = selected();
        if (g == null) {
            return;
        }
        at = Math.floorMod(at + d, g.linesKept());
        showSelected(true);
    }

    private void showSelected(boolean go) {
        Exceptions.Group g = selected();
        prev.setEnabled(g != null && g.linesKept() > 1);
        next.setEnabled(g != null && g.linesKept() > 1);
        findAll.setEnabled(g != null);
        if (g == null) {
            occurrence.setText(" ");
            showStack(null);
            return;
        }
        long line = g.keptLines()[at];
        occurrence.setText(String.format("  %,d of %,d%s · %s  ", at + 1, g.count(),
                g.linesKept() < g.count() ? " (first " + g.linesKept() + " kept)" : "", host.describeLine(line)));
        showStack(g);
        if (go) {
            host.goToLine(line);
        }
    }

    private void openLink(String link) {
        Exceptions.Group g = selected();
        if (g == null || !link.startsWith("frame:")) {
            return;
        }
        String[] p = link.substring(6).split(":");
        int chain = Integer.parseInt(p[0]);
        int frame = Integer.parseInt(p[1]);
        List<Exceptions.Thrown> c = g.sample().chain();
        if (chain < c.size() && frame < c.get(chain).frames().size()) {
            host.openSource(c.get(chain).frames().get(frame));
        }
    }

    // ---------------------------------------------------------------- the stack

    private static final int FRAMES_SHOWN = 12;

    private void showStack(Exceptions.Group g) {
        String fg = hex(Theme.foreground());
        String dim = hex(Theme.dimmedForeground());
        String red = hex(Theme.errorMark());
        StringBuilder h = new StringBuilder("<html><body style='font-family:" + Theme.monoFont(12).getFamily()
                + "; color:" + fg + "; margin:6px'>");
        if (g == null) {
            h.append("<span style='color:").append(dim).append("'>")
                    .append(result == null ? "Click Refresh to look for exceptions."
                            : result.groups().isEmpty() ? "No exceptions." : "Select an exception.")
                    .append("</span>");
        } else {
            List<Exceptions.Thrown> chain = g.sample().chain();
            int rootIndex = chain.size() - 1;
            Exceptions.Thrown root = chain.get(rootIndex);
            h.append("<div style='color:").append(dim).append("'>ROOT CAUSE</div>");
            h.append("<b style='color:").append(red).append("'>").append(esc(root.type())).append("</b>");
            if (!root.message().isEmpty()) {
                h.append(": ").append(esc(cut(root.message(), 400)).replace("\n", "<br>"));
            }
            frames(h, root, rootIndex, dim);
            if (chain.size() > 1) {
                h.append("<div style='margin-top:8px; color:").append(dim).append("'>CAUGHT AND WRAPPED IN</div>");
                for (int i = rootIndex - 1; i >= 0; i--) {
                    Exceptions.Thrown t = chain.get(i);
                    Exceptions.Frame w = t.where();
                    h.append("<div><b>").append(esc(t.simpleType())).append("</b>");
                    if (!t.message().isEmpty()) {
                        h.append(": ").append(esc(cut(t.message(), 160)));
                    }
                    if (w != null) {
                        h.append(" <span style='color:").append(dim).append("'>at</span> ");
                        link(h, w, i, t.frames().indexOf(w));
                    }
                    h.append("</div>");
                }
            }
            h.append("<div style='margin-top:8px; color:").append(dim).append("'>");
            h.append(String.format("%,d time%s · first %s · last %s", g.count(), g.count() == 1 ? "" : "s",
                    host.describeLine(g.firstLine()), host.describeLine(g.lastLine())));
            if (g.messages() > 1) {
                h.append(" · ").append(g.messages() >= 50 ? "50+" : g.messages()).append(" different messages");
            }
            h.append("</div>");
        }
        h.append("</body></html>");
        stack.setText(h.toString());
        stack.setCaretPosition(0);
    }

    /** The frames of one exception: the program's own as links, runs of framework frames folded. */
    private void frames(StringBuilder h, Exceptions.Thrown t, int chain, String dim) {
        int shown = 0;
        int folded = 0;
        List<Exceptions.Frame> fs = t.frames();
        for (int i = 0; i < fs.size(); i++) {
            Exceptions.Frame f = fs.get(i);
            if (shown >= FRAMES_SHOWN) {
                h.append("<div style='color:").append(dim).append("'>&nbsp;&nbsp;… ").append(fs.size() - i)
                        .append(" more</div>");
                return;
            }
            if (!f.own() && i > 0) {
                folded++;
                continue;
            }
            if (folded > 0) {
                h.append("<div style='color:").append(dim).append("'>&nbsp;&nbsp;… ").append(folded)
                        .append(folded == 1 ? " framework frame" : " framework frames").append("</div>");
                folded = 0;
            }
            h.append("<div>&nbsp;&nbsp;<span style='color:").append(dim).append("'>at</span> ");
            if (f.own()) {
                link(h, f, chain, i);
            } else {
                h.append("<span style='color:").append(dim).append("'>").append(esc(f.shortText())).append("</span>");
            }
            h.append("</div>");
            shown++;
        }
        if (folded > 0) {
            h.append("<div style='color:").append(dim).append("'>&nbsp;&nbsp;… ").append(folded)
                    .append(folded == 1 ? " framework frame" : " framework frames").append("</div>");
        }
    }

    private static void link(StringBuilder h, Exceptions.Frame f, int chain, int index) {
        String where = f.cls() != null ? f.cls().substring(0, Math.max(0, f.cls().lastIndexOf('.'))) : null;
        h.append("<a href='frame:").append(chain).append(':').append(index).append("'>").append(esc(f.shortText()))
                .append("</a>");
        if (where != null && !where.isEmpty()) {
            h.append(" <span style='color:#8A8F98'>").append(esc(where)).append("</span>");
        }
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---------------------------------------------------------------- the table

    private final class Model extends AbstractTableModel {
        private final String[] names = {"Count", "Exception (root cause)", "Where", "First", "Last"};

        @Override
        public int getRowCount() {
            return result == null ? 0 : result.groups().size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public Object getValueAt(int row, int col) {
            Exceptions.Group g = result.groups().get(row);
            return switch (col) {
                case 0 -> String.format("%,d", g.count());
                case 1 -> g;
                case 2 -> g.root().where() == null ? "" : g.root().where().shortText();
                case 3 -> host.describeLine(g.firstLine());
                default -> host.describeLine(g.lastLine());
            };
        }
    }

    /** "NullPointerException: Cannot invoke …  wrapped in IllegalStateException". */
    private static final class ExceptionRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row,
                                                       int col) {
            Exceptions.Group g = (Exceptions.Group) v;
            Exceptions.Thrown root = g.root();
            String msg = root.message().isEmpty() ? "" : ": " + esc(cut(root.message().replace('\n', ' '), 200));
            String wrapped = g.wrappers().isEmpty() ? ""
                    : " <span style='color:#8A8F98'>· in " + esc(String.join(", ", g.wrappers())) + "</span>";
            super.getTableCellRendererComponent(t, "<html><nobr><b>" + esc(root.simpleType()) + "</b>" + msg + wrapped
                    + "</nobr></html>", sel, focus, row, col);
            setToolTipText(root.type() + (root.message().isEmpty() ? "" : ": " + cut(root.message(), 300)));
            return this;
        }
    }

    /** Clicking a row goes to the occurrence shown (again). */
    {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (table.rowAtPoint(e.getPoint()) == table.getSelectedRow() && e.getClickCount() == 1) {
                    showSelected(true);
                }
            }
        });
    }
}
