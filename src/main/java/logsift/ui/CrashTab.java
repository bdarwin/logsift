package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import logsift.crash.CrashFiles;
import logsift.crash.CrashReport;
import logsift.crash.CrashSettings;
import logsift.crash.CrashText;

/**
 * A crash file explained: a Windows dump (.dmp, .mdmp) or a Java hs_err_pid file. On top, what
 * happened and where, in plain words; then what stands out with what to try, every thread with
 * its stack (threads with the same stack together), the modules, and the details.
 */
final class CrashTab extends JPanel {

    /** What the tab asks of the window. */
    interface Host {
        /** Opens a file as text, in an ordinary log tab. */
        void openAsText(Path file);

        /** Opens another crash file (the dump the JVM wrote, or the JVM's report next to a dump). */
        void openCrash(Path file);

        /** The status bar should be refreshed. */
        void changed(CrashTab tab);
    }

    private final Path file;
    private final Host host;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel progressText = new JLabel("Reading the crash file…");
    private final JEditorPane problem = new JEditorPane("text/html", "");
    private final AtomicBoolean stop = new AtomicBoolean();
    private volatile CrashReport report;
    private volatile Throwable failure;
    private volatile boolean busy;
    private final JTabbedPane views = new JTabbedPane();
    private final GroupModel groupModel = new GroupModel();
    private final JTable groups = new JTable(groupModel);
    private final FrameModel frameModel = new FrameModel();
    private final JTable frames = new JTable(frameModel);
    private final JLabel threadTitle = new JLabel(" ");
    private final JTextField threadFilter = new JTextField();
    private final ModuleModel moduleModel = new ModuleModel();
    private final JTable modules = new JTable(moduleModel);
    private final JTextField moduleFilter = new JTextField();
    private List<CrashText.Group> allGroups = List.of();

    CrashTab(Path file, Host host) {
        super(new BorderLayout());
        this.file = file;
        this.host = host;
        JPanel loading = new JPanel(new java.awt.GridBagLayout());
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        progressText.setAlignmentX(CENTER_ALIGNMENT);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setMaximumSize(new Dimension(260, 8));
        bar.setAlignmentX(CENTER_ALIGNMENT);
        box.add(progressText);
        box.add(Box.createVerticalStrut(8));
        box.add(bar);
        loading.add(box);
        body.add(loading, "loading");
        problem.setEditable(false);
        problem.setOpaque(false);
        JPanel problemPanel = new JPanel(new java.awt.GridBagLayout());
        JPanel problemBox = new JPanel();
        problemBox.setOpaque(false);
        problemBox.setLayout(new BoxLayout(problemBox, BoxLayout.Y_AXIS));
        problem.setAlignmentX(LEFT_ALIGNMENT);
        problemBox.add(problem);
        problemBox.add(Box.createVerticalStrut(12));
        problemAsText.setAlignmentX(LEFT_ALIGNMENT);
        problemAsText.setToolTipText("Opens the file as it is, as a log");
        problemAsText.addActionListener(e -> host.openAsText(file));
        problemBox.add(problemAsText);
        problemPanel.add(problemBox);
        body.add(problemPanel, "problem");
        add(body, BorderLayout.CENTER);
        load(null);
    }

    private final JButton problemAsText = new JButton("Open as Text", Icons.of("file-text"));

    void openAsTextForTest() {
        problemAsText.doClick();
    }

    /** The problem shown when the file can't be read (null while it reads or once it has). */
    String problemForTest() {
        return report == null && !busy ? problem.getText() : null;
    }

    Path file() {
        return file;
    }

    String title() {
        String n = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        return "Crash: " + n;
    }

    String statusText() {
        CrashReport r = report;
        if (r == null) {
            return busy ? "Reading " + file.getFileName() + "…" : file.toString();
        }
        CrashReport.Thread c = r.crashedThread();
        return r.kind().label() + " · " + r.threads().size() + (r.threads().size() == 1 ? " thread" : " threads")
                + " · " + r.modules().size() + (r.modules().size() == 1 ? " module" : " modules")
                + (c == null ? "" : " · crashed on thread " + c.id());
    }

    CrashReport report() {
        return report;
    }

    boolean busy() {
        return busy;
    }

    void close() {
        stop.set(true);
    }

    /** Reads the file again in the background; {@code downloadNow}: may download Windows' names this time. */
    private void load(Boolean downloadNow) {
        busy = true;
        cards.show(body, "loading");
        Path home = AppState.home();
        CrashFiles.Options base = CrashFiles.Options.of(home);
        CrashFiles.Options o = downloadNow == null ? base
                : new CrashFiles.Options(base.settings(), base.cache(), downloadNow || base.download());
        progressText.setText(o.download() ? "Reading the dump (and downloading Windows' function names)…"
                : "Reading the crash file…");
        Thread t = new Thread(() -> {
            CrashReport r = null;
            Throwable err = null;
            try {
                r = CrashFiles.analyze(file, o, stop::get);
            } catch (java.io.IOException e) {
                err = e; // not a crash file, or a damaged one: said in the tab
            } catch (RuntimeException | OutOfMemoryError e) {
                logsift.core.Problems.unexpected(e);
                err = e;
            }
            CrashReport done = r;
            Throwable failed = err;
            SwingUtilities.invokeLater(() -> shown(done, failed));
        }, "logsift-crash");
        t.setDaemon(true);
        t.start();
    }

    private void shown(CrashReport r, Throwable err) {
        busy = false;
        if (stop.get()) {
            return;
        }
        report = r;
        failure = err;
        if (r == null) {
            problem.setText("<html><div style='width:520px'><b>Logsift can't read this crash file.</b><br><br>"
                    + esc(err == null ? "" : String.valueOf(err.getMessage())) + "</div></html>");
            cards.show(body, "problem");
        } else {
            JComponent page = page(r);
            body.add(page, "report");
            cards.show(body, "report");
        }
        revalidate();
        repaint();
        host.changed(this);
    }

    // ---------------------------------------------------------------- the page

    private JComponent page(CrashReport r) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(header(r), BorderLayout.NORTH);
        views.removeAll();
        views.addTab("What Happened", findings(r));
        views.addTab("Threads (" + r.threads().size() + ")", threads(r));
        views.addTab("Modules (" + r.modules().size() + ")", modules(r));
        views.addTab("Details", details(r));
        p.add(views, BorderLayout.CENTER);
        return p;
    }

    private static Color severityColor(CrashReport.Severity s) {
        return switch (s) {
            case ERROR -> new Color(0xDC2626);
            case WARN -> new Color(0xD97706);
            case INFO -> new Color(0x9CA3AF);
        };
    }

    private JComponent header(CrashReport r) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        boolean noCrash = r.title().startsWith("No crash");
        Color accent = noCrash ? new Color(0x2563EB) : new Color(0xDC2626);
        JLabel title = new JLabel("<html><b style='font-size:1.3em'>" + esc(r.title()) + "</b></html>",
                Icons.of(noCrash ? "info" : "octagon-alert", accent, 22), SwingConstants.LEFT);
        title.setIconTextGap(10);
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(title, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        JButton copy = new JButton("Copy Summary", Icons.of("copy"));
        copy.setToolTipText("Copies what happened, the findings, the crashed thread's stack and the facts as text");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(CrashText.summary(r, 40)), null));
        buttons.add(copy);
        if (r.kind() == CrashReport.Kind.JAVA_CRASH) {
            JButton text = new JButton("Open as Text", Icons.of("file-text"));
            text.setToolTipText("Opens the crash report itself, as a log");
            text.addActionListener(e -> host.openAsText(file));
            buttons.add(text);
        }
        JButton help = new JButton(Icons.of("book-open"));
        help.putClientProperty("JButton.buttonType", "toolBarButton");
        help.setToolTipText("Opens the Crash Dumps page of the user guide in your browser");
        help.addActionListener(e -> HelpSupport.browse(this, HelpSupport.guide("crash-dumps")));
        buttons.add(help);
        top.add(buttons, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setBorder(BorderFactory.createEmptyBorder(0, 32, 0, 0));
        if (r.where() != null) {
            JLabel where = new JLabel("<html><div style='width:900px'>" + esc(cap(r.where())) + "</div></html>");
            where.setFont(Theme.monoFont(where.getFont().getSize()));
            where.setAlignmentX(LEFT_ALIGNMENT);
            text.add(where);
            text.add(Box.createVerticalStrut(4));
        }
        if (r.summary() != null && !r.summary().isBlank()) {
            JLabel sum = new JLabel("<html><div style='width:900px'>" + esc(r.summary()) + "</div></html>");
            sum.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            sum.setAlignmentX(LEFT_ALIGNMENT);
            text.add(sum);
        }
        JComponent links = links(r);
        if (links != null) {
            links.setAlignmentX(LEFT_ALIGNMENT);
            text.add(Box.createVerticalStrut(6));
            text.add(links);
        }
        p.add(text, BorderLayout.CENTER);
        JComponent strip = symbolsStrip(r);
        if (strip != null) {
            p.add(strip, BorderLayout.SOUTH);
        }
        return p;
    }

    /** Links to the file that goes with this one: the JVM's report next to a dump, the dump an hs_err names. */
    private JComponent links(CrashReport r) {
        Path other = null;
        String label = null;
        if (r.kind() == CrashReport.Kind.JAVA_CRASH) {
            try {
                other = logsift.crash.HsErr.read(file).coreDumpFile(file);
            } catch (java.io.IOException e) {
                other = null;
            }
            label = "Open the Windows dump the JVM wrote";
        } else {
            for (CrashReport.Finding f : r.findings()) {
                if (f.title().equals("A Java program") && f.detail() != null && f.detail().contains("It is next to the dump: ")) {
                    String s = f.detail().substring(f.detail().indexOf("It is next to the dump: ") + 24);
                    s = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
                    try {
                        other = Path.of(s);
                    } catch (RuntimeException e) {
                        other = null;
                    }
                    label = "Open the JVM's crash report (" + other.getFileName() + ")";
                }
            }
        }
        if (other == null) {
            return null;
        }
        Path target = other;
        JButton b = new JButton(label, Icons.of("link"));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.addActionListener(e -> host.openCrash(target));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(b);
        return row;
    }

    /**
     * For a Windows dump whose Windows modules have no names: offers to download them from
     * Microsoft (only Windows' own files are asked for), and to add a folder with the program's PDBs.
     */
    private JComponent symbolsStrip(CrashReport r) {
        if (r.kind() != CrashReport.Kind.WINDOWS_DUMP) {
            return null;
        }
        int systemWithout = 0;
        int ownWithout = 0;
        for (CrashReport.Module m : r.modules()) {
            if (m.note().equals("unloaded") || m.symbols().startsWith("PDB:")) {
                continue;
            }
            String path = m.path().toLowerCase(Locale.ROOT).replace('/', '\\');
            if (path.contains("\\windows\\")) {
                systemWithout++;
            } else {
                ownWithout++;
            }
        }
        CrashSettings s = CrashSettings.load(AppState.home());
        boolean offerDownload = systemWithout > 0 && !s.downloadMicrosoft();
        if (!offerDownload && ownWithout == 0) {
            return null;
        }
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(6, 0, 0, 0)));
        p.setOpaque(false);
        String msg = offerDownload
                ? "Function names for Windows' own files (" + systemWithout + ") can come from Microsoft's symbol server; "
                + "only Windows' files are asked for, never the program's."
                : "For function names and source lines in the program's own files, add the folder with its .pdb files.";
        JLabel l = new JLabel("<html>" + esc(msg) + "</html>", Icons.of("lightbulb"), SwingConstants.LEFT);
        l.setIconTextGap(8);
        p.add(l, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        if (offerDownload) {
            JButton dl = new JButton("Download Windows' Names", Icons.of("download"));
            dl.setToolTipText("Downloads the PDBs of Windows' modules in this dump from msdl.microsoft.com, once "
                    + "(kept in Logsift's folder). Always is in Settings → Crash Dumps.");
            dl.addActionListener(e -> load(true));
            buttons.add(dl);
        }
        JButton folder = new JButton("Add PDB Folder…", Icons.of("folder-open"));
        folder.setToolTipText("A folder with the program's .pdb files (and .exe/.dll files), looked in for every dump");
        folder.addActionListener(e -> addFolder());
        buttons.add(folder);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    private void addFolder() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(file.getParent() == null ? null : file.getParent().toFile());
        fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder with the Program's PDB Files");
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        addFolder(fc.getSelectedFile().toPath());
    }

    void addFolder(Path dir) {
        CrashSettings s = CrashSettings.load(AppState.home());
        List<Path> fs = new ArrayList<>(s.symbolFolders());
        if (!fs.contains(dir)) {
            fs.add(dir);
        }
        try {
            s.withFolders(fs).save(AppState.home());
        } catch (java.io.IOException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Logsift couldn't save the folder: " + e.getMessage(),
                    "Crash Dumps", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        load(null);
    }

    // ---------------------------------------------------------------- what happened

    private JComponent findings(CrashReport r) {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        if (r.findings().isEmpty()) {
            JLabel none = new JLabel("Nothing else stands out. See the threads and the details.");
            none.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            list.add(none);
        }
        for (CrashReport.Finding f : r.findings()) {
            list.add(card(f));
            list.add(Box.createVerticalStrut(10));
        }
        CrashReport.Thread c = r.crashedThread();
        if (c != null && !c.frames().isEmpty()) {
            JLabel h = new JLabel((c.state().startsWith("crashed") ? "The crashed thread: " : "Where it stopped: ")
                    + c.id() + (c.name() == null ? "" : " “" + c.name() + "”"));
            h.setFont(h.getFont().deriveFont(Font.BOLD));
            h.setAlignmentX(LEFT_ALIGNMENT);
            h.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
            list.add(h);
            JTable stack = new JTable(new FrameModel(c.frames()));
            styleFrames(stack);
            stack.setAlignmentX(LEFT_ALIGNMENT);
            JPanel holder = new JPanel(new BorderLayout());
            holder.setAlignmentX(LEFT_ALIGNMENT);
            holder.add(stack.getTableHeader(), BorderLayout.NORTH);
            holder.add(stack, BorderLayout.CENTER);
            holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, holder.getPreferredSize().height));
            list.add(holder);
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(list, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(top);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    private JPanel card(CrashReport.Finding f) {
        JPanel c = new JPanel(new BorderLayout(0, 6));
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, severityColor(f.severity())),
                BorderFactory.createEmptyBorder(6, 12, 6, 6)));
        c.setAlignmentX(LEFT_ALIGNMENT);
        String detail = f.detail() == null || f.detail().isBlank() ? "" : "<br><span style='color:#6b7280'>"
                + esc(f.detail()) + "</span>";
        String advice = f.tryThis() == null || f.tryThis().isBlank() ? ""
                : "<br><span style='color:#2563eb'>What to try: </span>" + esc(f.tryThis());
        JLabel t = new JLabel("<html><div style='width:760px'><b style='font-size:1.1em'>" + esc(f.title()) + "</b>"
                + detail + advice + "</div></html>");
        c.add(t, BorderLayout.CENTER);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    // ---------------------------------------------------------------- threads

    private JComponent threads(CrashReport r) {
        allGroups = CrashText.groups(r, 6);
        groupModel.set(allGroups);
        groups.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groups.setFillsViewportHeight(true);
        groups.setShowGrid(false);
        groups.setRowHeight(Math.max(groups.getRowHeight(), 22));
        groups.getColumnModel().getColumn(0).setPreferredWidth(220);
        groups.getColumnModel().getColumn(1).setPreferredWidth(220);
        groups.getColumnModel().getColumn(2).setPreferredWidth(320);
        groups.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showGroup(groups.getSelectedRow());
            }
        });
        groups.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                CrashText.Group g = groupModel.rows.get(row);
                boolean crashed = g.threads().get(0).crashed();
                setFont(getFont().deriveFont(crashed ? Font.BOLD : Font.PLAIN));
                if (!sel && crashed && col == 1) {
                    setForeground(new Color(0xDC2626));
                } else if (!sel) {
                    setForeground(t.getForeground());
                }
                return this;
            }
        });
        threadFilter.putClientProperty("JTextField.placeholderText", "Filter: a name, a state, a function");
        threadFilter.putClientProperty("JTextField.showClearButton", true);
        threadFilter.getDocument().addDocumentListener(onChange(this::filterThreads));
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        left.add(threadFilter, BorderLayout.NORTH);
        left.add(new JScrollPane(groups), BorderLayout.CENTER);
        JLabel hint = new JLabel("Threads with the same stack are one row.");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        left.add(hint, BorderLayout.SOUTH);

        frames.createDefaultColumnsFromModel(); // read again: the columns as for a new report
        styleFrames(frames);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 8));
        threadTitle.setFont(threadTitle.getFont().deriveFont(Font.BOLD));
        right.add(threadTitle, BorderLayout.NORTH);
        right.add(new JScrollPane(frames), BorderLayout.CENTER);
        JLabel legend = new JLabel("<html><b>Bold</b>: the program's own code · gray: Windows, the JVM or the runtime · "
                + "<i>italic</i>: guessed from the stack's contents</html>");
        legend.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        right.add(legend, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.42);
        split.setBorder(BorderFactory.createEmptyBorder());
        if (groupModel.getRowCount() > 0) {
            groups.setRowSelectionInterval(0, 0);
        }
        return split;
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }

    private void filterThreads() {
        String f = threadFilter.getText().strip().toLowerCase(Locale.ROOT);
        List<CrashText.Group> out = new ArrayList<>();
        for (CrashText.Group g : allGroups) {
            if (f.isEmpty() || matches(g, f)) {
                out.add(g);
            }
        }
        groupModel.set(out);
        if (!out.isEmpty()) {
            groups.setRowSelectionInterval(0, 0);
        } else {
            showGroup(-1);
        }
    }

    private static boolean matches(CrashText.Group g, String f) {
        for (CrashReport.Thread t : g.threads()) {
            if (t.id().toLowerCase(Locale.ROOT).contains(f) || t.name() != null && t.name().toLowerCase(Locale.ROOT).contains(f)
                    || t.state().toLowerCase(Locale.ROOT).contains(f)) {
                return true;
            }
        }
        for (CrashReport.Frame fr : g.threads().get(0).frames()) {
            if (fr.text().toLowerCase(Locale.ROOT).contains(f)) {
                return true;
            }
        }
        return false;
    }

    private void showGroup(int row) {
        if (row < 0 || row >= groupModel.rows.size()) {
            threadTitle.setText(" ");
            frameModel.set(List.of());
            return;
        }
        CrashText.Group g = groupModel.rows.get(row);
        CrashReport.Thread t = g.threads().get(0);
        String who;
        if (g.threads().size() == 1) {
            who = "Thread " + t.id() + (t.name() == null ? "" : " “" + t.name() + "”");
        } else {
            List<String> names = new ArrayList<>();
            for (CrashReport.Thread x : g.threads()) {
                if (names.size() == 8) {
                    names.add("…");
                    break;
                }
                names.add(x.name() == null ? x.id() : x.name());
            }
            who = g.threads().size() + " threads (" + String.join(", ", names) + ")";
        }
        threadTitle.setText(who + " — " + t.state() + (t.cpu() == null ? "" : " · CPU " + t.cpu()));
        frameModel.set(t.frames());
        if (t.frames().isEmpty()) {
            threadTitle.setText(threadTitle.getText() + (report != null && report.kind() == CrashReport.Kind.JAVA_CRASH
                    ? " (an hs_err file has the stack of the crashed thread only)" : " (no stack)"));
        }
    }

    /** Whether any frame of the report has a source file and line (PDBs with lines). */
    private boolean anySource() {
        CrashReport r = report;
        if (r == null) {
            return false;
        }
        for (CrashReport.Thread t : r.threads()) {
            for (CrashReport.Frame f : t.frames()) {
                if (f.source() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private void styleFrames(JTable t) {
        if (!anySource() && t.getColumnModel().getColumnCount() == 3) {
            t.removeColumn(t.getColumnModel().getColumn(2)); // no sources: no empty column
        }
        t.setShowGrid(false);
        t.setFillsViewportHeight(true);
        t.setRowHeight(Math.max(t.getRowHeight(), 21));
        t.getColumnModel().getColumn(0).setPreferredWidth(36);
        t.getColumnModel().getColumn(0).setMaxWidth(48);
        t.getColumnModel().getColumn(1).setPreferredWidth(620);
        if (t.getColumnModel().getColumnCount() > 2) {
            t.getColumnModel().getColumn(2).setPreferredWidth(200);
        }
        Font mono = Theme.monoFont(t.getFont().getSize());
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object v, boolean sel, boolean focus, int row,
                                                           int col) {
                super.getTableCellRendererComponent(table, v, sel, focus, row, col);
                FrameModel m = (FrameModel) table.getModel();
                CrashReport.Frame f = m.rows.get(table.convertRowIndexToModel(row));
                int style = f.kind() == CrashReport.FrameKind.OWN ? Font.BOLD : Font.PLAIN;
                if (f.guessed() || f.kind() == CrashReport.FrameKind.GENERATED) {
                    style |= Font.ITALIC;
                }
                setFont((col == 1 ? mono : table.getFont()).deriveFont(style));
                if (!sel) {
                    setForeground(f.kind() == CrashReport.FrameKind.SYSTEM || f.guessed()
                            ? Theme.uiColor("Label.disabledForeground", Color.GRAY)
                            : f.kind() == CrashReport.FrameKind.GENERATED ? new Color(0x7C3AED) : table.getForeground());
                }
                setHorizontalAlignment(col == 0 ? RIGHT : LEFT);
                setToolTipText(col == 1 ? kindText(f) : null);
                return this;
            }
        });
    }

    private static String kindText(CrashReport.Frame f) {
        String k = switch (f.kind()) {
            case OWN -> "the program's own code";
            case SYSTEM -> "Windows, the JVM or the runtime";
            case JAVA -> "Java code of the JDK";
            case GENERATED -> "code generated while running (a JIT's)";
            case UNKNOWN -> "not known";
        };
        return f.guessed() ? k + "; guessed from the stack's contents" : k;
    }

    private static final class GroupModel extends AbstractTableModel {
        List<CrashText.Group> rows = List.of();

        void set(List<CrashText.Group> r) {
            rows = r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "Threads";
                case 1 -> "Doing";
                default -> "At";
            };
        }

        @Override
        public Object getValueAt(int r, int c) {
            CrashText.Group g = rows.get(r);
            CrashReport.Thread t = g.threads().get(0);
            return switch (c) {
                case 0 -> g.threads().size() == 1 ? t.id() + (t.name() == null ? "" : " “" + t.name() + "”")
                        : g.threads().size() + " threads";
                case 1 -> t.state();
                default -> at(t);
            };
        }

        private static String at(CrashReport.Thread t) {
            for (CrashReport.Frame f : t.frames()) {
                if (f.kind() == CrashReport.FrameKind.OWN) {
                    return f.text();
                }
            }
            return t.frames().isEmpty() ? "" : t.frames().get(0).text();
        }
    }

    private static final class FrameModel extends AbstractTableModel {
        List<CrashReport.Frame> rows;

        FrameModel() {
            this(List.of());
        }

        FrameModel(List<CrashReport.Frame> rows) {
            this.rows = rows;
        }

        void set(List<CrashReport.Frame> r) {
            rows = r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "#";
                case 1 -> "Frame";
                default -> "Source";
            };
        }

        @Override
        public Object getValueAt(int r, int c) {
            CrashReport.Frame f = rows.get(r);
            return switch (c) {
                case 0 -> String.valueOf(r);
                case 1 -> f.text();
                default -> f.source() == null ? "" : f.source();
            };
        }
    }

    // ---------------------------------------------------------------- modules

    private JComponent modules(CrashReport r) {
        moduleModel.set(r.modules());
        TableRowSorter<ModuleModel> sorter = new TableRowSorter<>(moduleModel);
        modules.setRowSorter(sorter);
        modules.setShowGrid(false);
        modules.setFillsViewportHeight(true);
        modules.setRowHeight(Math.max(modules.getRowHeight(), 21));
        int[] widths = {200, 120, 160, 200, 520};
        for (int i = 0; i < widths.length; i++) {
            modules.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        modules.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                CrashReport.Module m = moduleModel.rows.get(t.convertRowIndexToModel(row));
                setFont(t.getFont().deriveFont(m.note().equals("the crash is here") ? Font.BOLD : Font.PLAIN));
                if (!sel) {
                    setForeground(m.note().equals("the crash is here") ? new Color(0xDC2626)
                            : m.note().equals("unloaded") ? Theme.uiColor("Label.disabledForeground", Color.GRAY)
                            : t.getForeground());
                }
                setToolTipText(col == 4 ? m.path() : null);
                return this;
            }
        });
        moduleFilter.putClientProperty("JTextField.placeholderText", "Filter modules by name or path");
        moduleFilter.putClientProperty("JTextField.showClearButton", true);
        moduleFilter.getDocument().addDocumentListener(onChange(() -> {
            String f = moduleFilter.getText().strip();
            sorter.setRowFilter(f.isEmpty() ? null : javax.swing.RowFilter.regexFilter("(?i)"
                    + java.util.regex.Pattern.quote(f), 0, 4));
        }));
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(moduleFilter, BorderLayout.NORTH);
        p.add(new JScrollPane(modules), BorderLayout.CENTER);
        return p;
    }

    private static final class ModuleModel extends AbstractTableModel {
        List<CrashReport.Module> rows = List.of();

        void set(List<CrashReport.Module> r) {
            rows = r;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "Module";
                case 1 -> "Version";
                case 2 -> "Note";
                case 3 -> "Names";
                default -> "Path";
            };
        }

        @Override
        public Object getValueAt(int r, int c) {
            CrashReport.Module m = rows.get(r);
            return switch (c) {
                case 0 -> m.name();
                case 1 -> m.version();
                case 2 -> m.note();
                case 3 -> m.symbols();
                default -> m.path();
            };
        }
    }

    // ---------------------------------------------------------------- details

    private JComponent details(CrashReport r) {
        StringBuilder h = new StringBuilder("<html><body style='margin:10px'>");
        h.append("<table cellspacing=0 cellpadding=3>");
        for (CrashReport.Fact f : r.facts()) {
            h.append("<tr><td valign=top style='color:#6b7280;padding-right:14px' nowrap>").append(esc(f.label()))
                    .append("</td><td>").append(esc(f.value())).append("</td></tr>");
        }
        h.append("</table>");
        for (CrashReport.Section s : r.sections()) {
            h.append("<h3 style='margin-top:16px;margin-bottom:4px'>").append(esc(s.title())).append("</h3><pre>")
                    .append(esc(cut(s.text(), 200_000))).append("</pre>");
        }
        h.append("</body></html>");
        JEditorPane pane = new JEditorPane("text/html", "");
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        pane.setFont(getFont());
        pane.setText(h.toString());
        pane.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(pane);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "\n… (" + (s.length() - n) + " more characters)";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- for tests

    JTabbedPane viewsForTest() {
        return views;
    }

    JTable groupsForTest() {
        return groups;
    }

    JTable framesForTest() {
        return frames;
    }

    JTable modulesForTest() {
        return modules;
    }

    void filterThreadsForTest(String text) {
        threadFilter.setText(text);
    }

    String threadTitleForTest() {
        return threadTitle.getText();
    }

    Throwable failureForTest() {
        return failure;
    }
}
