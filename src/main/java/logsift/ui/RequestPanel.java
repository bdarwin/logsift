package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.Insights;
import logsift.core.RequestTrace;
import logsift.core.TimeFormat;

/**
 * One request followed through the log: its lines in order with the time since the line before,
 * the threads it went through, and the longest wait marked, so it is clear where the time went.
 * Click a line to go there.
 */
final class RequestPanel extends JPanel {

    /** Where to look for the request's lines. */
    enum Scope {
        LOG("This log"), OPEN("All open logs"), FOLDER("Folder");

        final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    interface Host {
        /**
         * Finds the request's lines and builds it (on a background thread); null if cancelled.
         *
         * @param folder for {@link Scope#FOLDER}: the folder (or zip) to search
         * @param archives for {@link Scope#FOLDER}: also inside .zip and .gz files
         */
        RequestTrace.Trace follow(String value, boolean withThreads, Scope scope, java.nio.file.Path folder,
                                  boolean archives, AtomicBoolean cancel);

        /** Shows a line of the request: in this log, another open one, or a file it opens. */
        void goTo(RequestTrace.Step step);

        /** The folder offered first: the log's own. */
        java.nio.file.Path defaultFolder();

        void showOnly(String value);

        void findAll(String value);

        /** The message part of a line (the whole line when the log has no columns). */
        String message(String line);
    }

    private static final Color AMBER = new Color(0xD97706);

    private final Host host;
    private final JLabel title = new JLabel("Follow a request");
    private final JLabel summary = new JLabel(" ");
    private final JCheckBox withThreads = new JCheckBox("Lines of its threads in between");
    private final javax.swing.JComboBox<Scope> scope = new javax.swing.JComboBox<>(Scope.values());
    private final JButton folderButton = new JButton();
    private final JCheckBox archives = new JCheckBox("Inside zips and .gz", true);
    private java.nio.file.Path folder;
    private final JButton showOnly = new JButton("Show Only These Lines", Icons.of("list-filter"));
    private final JButton findAll = new JButton("Find All", Icons.of("search"));
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private String name;
    private String value;
    private RequestTrace.Trace trace;
    private AtomicBoolean running;

    RequestPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        // two rows: what was found; where to look and what to do with it
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(5, 10, 2, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        summary.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        head.add(title);
        head.add(Box.createHorizontalStrut(12));
        head.add(summary);
        head.add(Box.createHorizontalGlue());
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 10, 3, 8));
        withThreads.setToolTipText("<html>Logs often name the ID on only some lines of a request.<br>"
                + "This adds the lines its threads wrote between its first and last line.</html>");
        withThreads.setFocusable(false);
        withThreads.addActionListener(e -> {
            if (value != null) {
                follow(name, value);
            }
        });
        for (JButton b : List.of(showOnly, findAll)) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            b.setEnabled(false);
        }
        showOnly.setToolTipText("Filter the log to the lines with this ID");
        findAll.setToolTipText("Find all lines with this ID");
        showOnly.addActionListener(e -> host.showOnly(value));
        findAll.addActionListener(e -> host.findAll(value));
        scope.setMaximumSize(scope.getPreferredSize());
        scope.setFocusable(false);
        scope.setToolTipText("Where to look for its lines: this log, every open log, or the files of a folder");
        scope.addActionListener(e -> scopeChanged(true));
        folderButton.putClientProperty("JButton.buttonType", "toolBarButton");
        folderButton.setFocusable(false);
        folderButton.setIcon(Icons.of("folder"));
        folderButton.setToolTipText("Choose the folder (or a .zip) to look through");
        folderButton.addActionListener(e -> {
            if (chooseFolder() && value != null) {
                follow(name, value);
            }
        });
        archives.setFocusable(false);
        archives.setToolTipText("Also look inside .zip files (zips in zips too) and .gz files");
        archives.addActionListener(e -> {
            if (value != null) {
                follow(name, value);
            }
        });
        bar.add(new JLabel("In "));
        bar.add(scope);
        bar.add(folderButton);
        bar.add(archives);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(withThreads);
        bar.add(Box.createHorizontalGlue());
        bar.add(findAll);
        bar.add(showOnly);
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        head.setAlignmentX(LEFT_ALIGNMENT);
        bar.setAlignmentX(LEFT_ALIGNMENT);
        north.add(head);
        north.add(bar);
        add(north, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        applyColumns();
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0 && trace != null && row < trace.steps().size()
                    && trace.steps().get(row).file() == null) {
                host.goTo(trace.steps().get(row)); // a line of this log: show it right away
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0 || trace == null || row >= trace.steps().size()) {
                    return;
                }
                RequestTrace.Step s = trace.steps().get(row);
                if (s.file() == null ? row == table.getSelectedRow() && e.getClickCount() == 1
                        : e.getClickCount() == 2) {
                    host.goTo(s); // again after scrolling away; in another file on double-click
                }
            }
        });
        scopeChanged(false);
        add(new JScrollPane(table), BorderLayout.CENTER);
        summary.setText("Right-click a line with a trace or request ID → Follow, or press Ctrl+Shift+R.");
    }

    /** Columns: File only when the request was followed through several files. */
    private void applyColumns() {
        boolean files = model.files;
        String[] names = files ? new String[] {"File", "Time", "After", "Thread", "Level", "Message"}
                : new String[] {"Time", "After", "Thread", "Level", "Message"};
        int[] widths = files ? new int[] {260, 100, 70, 130, 60, 900} : new int[] {100, 70, 130, 60, 900};
        model.names = names;
        model.fireTableStructureChanged();
        int off = files ? 1 : 0;
        for (int i = 0; i < names.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        if (files) {
            table.getColumnModel().getColumn(0).setCellRenderer(new StepRenderer(SwingConstants.LEFT, false));
        }
        table.getColumnModel().getColumn(off).setCellRenderer(new StepRenderer(SwingConstants.LEFT, false));
        table.getColumnModel().getColumn(off + 1).setCellRenderer(new StepRenderer(SwingConstants.RIGHT, false));
        table.getColumnModel().getColumn(off + 2).setCellRenderer(new StepRenderer(SwingConstants.LEFT, false));
        table.getColumnModel().getColumn(off + 3).setCellRenderer(new MessagesPanel.LevelRenderer());
        table.getColumnModel().getColumn(off + 4).setCellRenderer(new StepRenderer(SwingConstants.LEFT, true));
    }

    private void scopeChanged(boolean user) {
        Scope s = (Scope) scope.getSelectedItem();
        boolean inFolder = s == Scope.FOLDER;
        if (inFolder && folder == null) {
            folder = host.defaultFolder();
            if (user && folder == null && !chooseFolder()) {
                scope.setSelectedItem(Scope.LOG);
                return;
            }
        }
        folderButton.setVisible(inFolder);
        folderButton.setText(folder == null ? "Choose…" : folder.getFileName() == null ? folder.toString()
                : folder.getFileName().toString());
        folderButton.setToolTipText(folder == null ? "Choose the folder (or a .zip) to look through"
                : folder + "   (click to choose another)");
        archives.setVisible(inFolder);
        withThreads.setEnabled(s != Scope.FOLDER);
        if (user && value != null) {
            follow(name, value);
        }
    }

    private boolean chooseFolder() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(folder != null ? folder.toFile() : null);
        fc.setFileSelectionMode(javax.swing.JFileChooser.FILES_AND_DIRECTORIES);
        fc.setDialogTitle("Look for the request in this folder (or .zip)");
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return false;
        }
        folder = fc.getSelectedFile().toPath();
        scopeChanged(false);
        return true;
    }

    /** Test access: where to look. */
    void setScopeForTest(Scope s, java.nio.file.Path dir, boolean inArchives) {
        folder = dir;
        archives.setSelected(inArchives);
        scope.setSelectedItem(s);
    }

    String value() {
        return value;
    }

    RequestTrace.Trace trace() {
        return trace;
    }

    boolean isRunning() {
        return running != null;
    }

    JTable tableForTest() {
        return table;
    }

    String summaryForTest() {
        return summary.getText();
    }

    JCheckBox withThreadsForTest() {
        return withThreads;
    }

    /** Follows the lines with this ID (in the background). */
    void follow(String name, String value) {
        if (running != null) {
            running.set(true);
        }
        this.name = name;
        this.value = value;
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        title.setText(name + " " + value);
        title.setToolTipText(name + " " + value);
        summary.setText("Finding its lines…");
        showOnly.setEnabled(true);
        findAll.setEnabled(true);
        Scope where = (Scope) scope.getSelectedItem();
        boolean threads = withThreads.isSelected() && where != Scope.FOLDER;
        java.nio.file.Path dir = folder;
        boolean inArchives = archives.isSelected();
        summary.setText(where == Scope.LOG ? "Finding its lines…"
                : where == Scope.OPEN ? "Finding its lines in the open logs…"
                : "Finding its lines in " + (dir == null ? "the folder" : dir.getFileName()) + "…");
        Thread t = new Thread(() -> {
            RequestTrace.Trace r = null;
            String error = null;
            try {
                r = host.follow(value, threads, where, dir, inArchives, cancel);
            } catch (RuntimeException e) {
                logsift.core.Problems.unexpected(e); // a bug, if not a read or connection failure
                error = e.getMessage();
            }
            RequestTrace.Trace done = r;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (running != cancel) {
                    return;
                }
                running = null;
                if (failed != null) {
                    summary.setText("Could not follow it: " + failed);
                } else if (done != null) {
                    show(done);
                }
            });
        }, "logsift-request");
        t.setDaemon(true);
        t.start();
    }

    void cancel() {
        if (running != null) {
            running.set(true);
            running = null;
        }
    }

    private void show(RequestTrace.Trace t) {
        trace = t;
        boolean files = t.steps().stream().anyMatch(s -> s.source() != null);
        if (files != model.files) {
            model.files = files;
            applyColumns();
        } else {
            model.fireTableDataChanged();
        }
        summary.setText(describe(t));
        if (t.longest() >= 0) { // where the time went
            table.scrollRectToVisible(table.getCellRect(t.longest(), 0, true));
        }
    }

    /** "14 lines · 3 threads · 10:02:13.120 → 10:02:21.400 (8.3 s) · 1 error · longest wait 8.0 s". */
    static String describe(RequestTrace.Trace t) {
        StringBuilder sb = new StringBuilder();
        int n = t.steps().size();
        sb.append(String.format("%,d line%s%s", n, n == 1 ? "" : "s", t.cut() ? " (the first)" : ""));
        long files = t.steps().stream().map(RequestTrace.Step::source).filter(java.util.Objects::nonNull).distinct()
                .count();
        if (files > 1) {
            sb.append("   ·   ").append(files).append(" files");
        }
        if (!t.threads().isEmpty()) {
            sb.append("   ·   ").append(t.threads().size()).append(t.threads().size() == 1 ? " thread" : " threads");
        }
        if (t.duration() >= 0 && n > 1) {
            sb.append("   ·   ").append(clock(t.first())).append(" → ").append(clock(t.last()))
                    .append(" (").append(span(t.duration())).append(")");
        }
        if (t.errors() > 0) {
            sb.append("   ·   ").append(t.errors()).append(t.errors() == 1 ? " error" : " errors");
        }
        if (t.warnings() > 0) {
            sb.append("   ·   ").append(t.warnings()).append(t.warnings() == 1 ? " warning" : " warnings");
        }
        if (t.longest() >= 0 && t.duration() > 0) {
            long gap = t.steps().get(t.longest()).gap();
            if (gap * 3 >= t.duration() && gap >= 100) {
                sb.append("   ·   longest wait ").append(span(gap)).append(" (marked)");
            }
        }
        if (n == 0) {
            return "No lines with this ID.";
        }
        return sb.toString();
    }

    /** "8.3 s", "412 ms", "2 min 5 s". */
    static String span(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        if (ms < 60_000) {
            return String.format(java.util.Locale.ROOT, ms < 10_000 ? "%.2f s" : "%.1f s", ms / 1000.0);
        }
        return Insights.duration(ms);
    }

    /** "10:02:13.120" (UTC wall clock, as written). */
    static String clock(long t) {
        if (t == TimeFormat.NONE) {
            return "";
        }
        long ms = Math.floorMod(t, 86_400_000L);
        return String.format("%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private final class Model extends AbstractTableModel {
        String[] names = {"Time", "After", "Thread", "Level", "Message"};
        boolean files;

        @Override
        public int getRowCount() {
            return trace == null ? 0 : trace.steps().size();
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
            RequestTrace.Step s = trace.steps().get(row);
            if (files) {
                if (col == 0) {
                    return fileLabel(s.source());
                }
                col--;
            }
            return switch (col) {
                case 0 -> clock(s.time());
                case 1 -> s.gap() < 0 ? "" : "+" + span(s.gap());
                case 2 -> s.thread() == null ? "" : s.thread();
                case 3 -> s.level() == null ? "" : logsift.core.Levels.normalize(s.level());
                default -> host.message(s.text());
            };
        }
    }

    /** "bank-adapter.log  in payments.zip/node1": the name first, where it is after (dimmed). */
    private static String fileLabel(String source) {
        if (source == null) {
            return "";
        }
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        if (slash < 0) {
            return source;
        }
        String esc = source.replace("&", "&amp;").replace("<", "&lt;");
        int cut = Math.max(esc.lastIndexOf('/'), esc.lastIndexOf('\\'));
        return "<html><nobr>" + esc.substring(cut + 1) + " <span style='color:#8A8F98'>in "
                + esc.substring(0, cut) + "</span></nobr></html>";
    }

    /** Lines without the ID are dimmed; the step after the longest wait is marked. */
    private final class StepRenderer extends DefaultTableCellRenderer {
        private final boolean mono;

        StepRenderer(int align, boolean mono) {
            this.mono = mono;
            setHorizontalAlignment(align);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            setFont(mono ? Theme.monoFont(t.getFont().getSize2D()) : t.getFont());
            RequestTrace.Step s = trace == null || row >= trace.steps().size() ? null : trace.steps().get(row);
            boolean longest = trace != null && row == trace.longest() && trace.duration() > 0
                    && s != null && s.gap() * 3 >= trace.duration() && s.gap() >= 100;
            int c = model.files ? col - 1 : col;
            if (!sel) {
                setForeground(longest && c == 1 ? AMBER
                        : s != null && !s.withId() ? Theme.dimmedForeground() : t.getForeground());
            }
            if (longest && c == 1) {
                setFont(getFont().deriveFont(Font.BOLD));
                setToolTipText("The longest wait in this request: where the time went");
            } else if (s != null && s.file() != null && c == -1) {
                setToolTipText(s.file() + "   (double-click to open it at this line)");
            } else {
                setToolTipText(s != null && !s.withId() ? "A line of the same thread, without the ID" : null);
            }
            return this;
        }
    }
}
