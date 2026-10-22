package logsift.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.core.CustomFormat;
import logsift.core.FormatGuess;
import logsift.core.Formats;
import logsift.core.LayoutImport;
import logsift.core.LogFormat;
import logsift.core.TimeFormat;

/**
 * The user's own log formats. A format is made by dropping a log file (its layout is guessed),
 * pasting the program's logging setup (a log4j / logback / Python layout, or a whole config
 * file), or writing a pattern by hand; a preview shows how sample lines split into columns.
 */
final class LogFormatsPanel extends JPanel {
    private static final int SAMPLE_LINES = 300;

    private final DefaultListModel<CustomFormat> model = new DefaultListModel<>();
    private final JList<CustomFormat> list = new JList<>(model);
    private final CardLayout cards = new CardLayout();
    private final JPanel right = new JPanel(cards);
    private final JTextField name = new JTextField(24);
    private final JTextField pattern = new JTextField(40);
    private final JTextField time = new JTextField(24);
    private final JTextField files = new JTextField(24);
    private final JLabel status = new JLabel(" ");
    private final JLabel note = new JLabel(" ");
    private final JLabel sampleLabel = new JLabel(" ");
    private final JTextArea paste = new JTextArea(6, 50);
    private final PreviewModel preview = new PreviewModel();
    private final JTable table = new JTable(preview);
    private final Timer refresh = new Timer(200, e -> updatePreview());
    private List<String> sample;
    private String sampleName;
    private boolean loading;
    private boolean changed;

    /**
     * @param sample lines of the open log, for the preview (may be empty)
     * @param sampleName the open log's file name, or null
     */
    LogFormatsPanel(List<String> sample, String sampleName) {
        super(new BorderLayout());
        this.sample = new ArrayList<>(sample);
        this.sampleName = sampleName;
        for (CustomFormat f : AppState.customFormats()) {
            model.addElement(f);
        }
        refresh.setRepeats(false);

        // ---- left: the formats
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                CustomFormat f = (CustomFormat) v;
                String text = f.name().isEmpty() ? "(no name)" : f.name();
                super.getListCellRendererComponent(l, text, i, sel, focus);
                if (!valid(f) && !sel) {
                    setForeground(errorColor());
                }
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        JButton add = new JButton("New");
        add.setToolTipText("Make a format: drop a log file, paste a logging setup, or write a pattern");
        add.addActionListener(e -> {
            list.clearSelection();
            cards.show(right, "start");
        });
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0) {
                model.remove(i);
                changed = true;
                if (!model.isEmpty()) {
                    list.setSelectedIndex(Math.min(i, model.size() - 1));
                } else {
                    cards.show(right, "start");
                }
            }
        });
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(add);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(delete);
        buttons.add(Box.createHorizontalGlue());
        JLabel builtIn = new JLabel("<html><small>Built in: logback and log4j, Python, syslog, web server "
                + "access logs, JSON lines, and lines starting with a time.</small></html>");
        builtIn.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(new JLabel("Your formats"), BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel leftBottom = new JPanel(new BorderLayout(0, 6));
        leftBottom.add(buttons, BorderLayout.NORTH);
        leftBottom.add(builtIn, BorderLayout.CENTER);
        left.add(leftBottom, BorderLayout.SOUTH);
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        left.setPreferredSize(new Dimension(210, 300));

        right.add(startCard(), "start");
        right.add(editCard(), "edit");
        right.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setBorder(null);
        split.setResizeWeight(0);
        add(split, BorderLayout.CENTER);

        setTransferHandler(new Drop());
        if (model.isEmpty()) {
            cards.show(right, "start");
        } else {
            list.setSelectedIndex(0);
        }
    }

    // ---------------------------------------------------------------- the two cards

    /** Ways to make a new format. */
    private JComponent startCard() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 10, 0);

        JLabel title = new JLabel("Add a log format");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3));
        p.add(title, c);

        JPanel drop = new JPanel(new BorderLayout(0, 8));
        drop.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createDashedBorder(Theme.uiColor("Component.borderColor", Color.GRAY), 2, 6, 4, true),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)));
        JLabel dropText = new JLabel("<html><div style='text-align:center'><b>Drop a log file here</b><br>"
                + "Logsift guesses its layout and shows how lines split into columns.</div></html>",
                SwingConstants.CENTER);
        drop.add(dropText, BorderLayout.CENTER);
        JButton open = new JButton("Open a Log File…");
        open.addActionListener(e -> openSample(true));
        JPanel openRow = new JPanel();
        openRow.setOpaque(false);
        openRow.add(open);
        drop.add(openRow, BorderLayout.SOUTH);
        drop.setTransferHandler(new Drop());
        p.add(drop, c);

        p.add(new JLabel("<html><b>Or paste how your program logs</b>: a log4j, logback or Python layout, "
                + "or a whole logback.xml, log4j2.xml or log4j.properties</html>"), c);
        paste.setFont(Theme.monoFont(12f));
        paste.setLineWrap(true);
        paste.putClientProperty("JTextArea.placeholderText",
                "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        JButton fromPaste = new JButton("Create Format");
        fromPaste.addActionListener(e -> createFromLayout(paste.getText(), null));
        c.insets = new Insets(0, 0, 4, 0);
        p.add(new JScrollPane(paste), c);
        JPanel pasteRow = new JPanel(new BorderLayout());
        pasteRow.add(fromPaste, BorderLayout.EAST);
        c.insets = new Insets(0, 0, 14, 0);
        p.add(pasteRow, c);

        JButton empty = new JButton("Write a Pattern Yourself…");
        empty.addActionListener(e -> add(new CustomFormat(uniqueName("My format"), "{time} {level} {message}", "", "")));
        JPanel emptyRow = new JPanel(new BorderLayout());
        emptyRow.add(empty, BorderLayout.WEST);
        p.add(emptyRow, c);

        c.weighty = 1;
        p.add(Box.createGlue(), c);
        return p;
    }

    /** The selected format: its fields and a preview. */
    private JComponent editCard() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.anchor = GridBagConstraints.NORTHWEST;
        l.insets = new Insets(4, 0, 0, 8);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.weightx = 1;
        f.insets = new Insets(2, 0, 0, 0);

        pattern.setFont(Theme.monoFont(13f));
        time.setFont(Theme.monoFont(13f));
        pattern.setTransferHandler(new LayoutPaste(pattern.getTransferHandler()));
        name.putClientProperty("JTextField.placeholderText", "shown in View → Log Format");
        time.putClientProperty("JTextField.placeholderText", "any common timestamp");
        files.putClientProperty("JTextField.placeholderText", "app*.log; *.trace");

        row(form, l, f, "Name", name, null);
        row(form, l, f, "Line pattern", pattern, "<html>Columns in braces, the rest as written: "
                + "<tt>{time} {level} [{thread}] {logger} - {message}</tt>. {time} and {level} take "
                + "timestamps and level words, {*} skips a part, other columns take everything up to "
                + "the next character. Pasting a log4j, logback or Python layout converts it.</html>");
        row(form, l, f, "Time format", time, "<html>Optional, for unusual timestamps: "
                + "<tt>dd.MM.yy HH:mm:ss.SSS</tt>, <tt>MMM d HH:mm:ss</tt>, <tt>yyyyMMdd'T'HHmmss</tt>.</html>");
        row(form, l, f, "Always use for", files, "<html>Optional file names. Without them the format is "
                + "used when most of a log's lines fit it, or when you choose it in View → Log Format.</html>");

        DocumentListener edits = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        };
        for (JTextField t : List.of(name, pattern, time, files)) {
            t.getDocument().addDocumentListener(edits);
        }

        JButton guess = new JButton("Guess from Sample");
        guess.setToolTipText("Suggest a pattern from the sample lines below");
        guess.addActionListener(e -> guessPattern());
        JButton openSample = new JButton("Open Sample…");
        openSample.setToolTipText("Preview with lines of another log file (or drop one here)");
        openSample.addActionListener(e -> openSample(false));
        JPanel tools = new JPanel();
        tools.setLayout(new BoxLayout(tools, BoxLayout.X_AXIS));
        tools.add(status);
        tools.add(Box.createHorizontalGlue());
        tools.add(guess);
        tools.add(Box.createHorizontalStrut(6));
        tools.add(openSample);
        f.insets = new Insets(10, 0, 0, 0);
        l.gridy = GridBagConstraints.RELATIVE;
        form.add(new JLabel(), l);
        form.add(tools, f);
        note.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        f.insets = new Insets(2, 0, 4, 0);
        form.add(new JLabel(), l);
        form.add(note, f);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setFillsViewportHeight(true);
        table.setFont(Theme.monoFont(12f));
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 4);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, col);
                if (!sel) {
                    setForeground(preview.fits(r) ? t.getForeground() : Theme.dimmedForeground());
                }
                setToolTipText(preview.fits(r) ? null : "Doesn't fit the pattern: continues the line above");
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, 220));
        JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
        previewPanel.add(sampleLabel, BorderLayout.NORTH);
        previewPanel.add(scroll, BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(form, BorderLayout.NORTH);
        p.add(previewPanel, BorderLayout.CENTER);
        p.setTransferHandler(new Drop());
        return p;
    }

    private static void row(JPanel form, GridBagConstraints l, GridBagConstraints f, String label,
                            JComponent field, String help) {
        l.gridy = GridBagConstraints.RELATIVE;
        f.insets = new Insets(6, 0, 0, 0);
        form.add(new JLabel(label), l);
        form.add(field, f);
        if (help != null) {
            JLabel h = new JLabel(help.replace("<html>", "<html><div style='width:520px'>"));
            h.setFont(h.getFont().deriveFont(h.getFont().getSize2D() - 1));
            h.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            f.insets = new Insets(2, 0, 0, 0);
            form.add(new JLabel(), l);
            form.add(h, f);
        }
    }

    // ---------------------------------------------------------------- editing

    private void showSelected() {
        CustomFormat f = list.getSelectedValue();
        if (f == null) {
            return;
        }
        loading = true;
        name.setText(f.name());
        pattern.setText(f.pattern());
        pattern.setCaretPosition(0);
        time.setText(f.timePattern());
        files.setText(f.files());
        loading = false;
        note.setText(" ");
        cards.show(right, "edit");
        updatePreview();
    }

    private void edited() {
        if (loading) {
            return;
        }
        int i = list.getSelectedIndex();
        if (i < 0) {
            return;
        }
        model.set(i, current());
        changed = true;
        refresh.restart();
    }

    private CustomFormat current() {
        return new CustomFormat(name.getText(), pattern.getText(), time.getText(), files.getText());
    }

    private void add(CustomFormat f) {
        model.addElement(f);
        changed = true;
        list.setSelectedIndex(model.size() - 1);
        list.ensureIndexIsVisible(model.size() - 1);
        SwingUtilities.invokeLater(() -> pattern.requestFocusInWindow());
    }

    private String uniqueName(String base) {
        String n = base;
        for (int k = 2; nameTaken(n); k++) {
            n = base + " " + k;
        }
        return n;
    }

    private boolean nameTaken(String n) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).name().equalsIgnoreCase(n)) {
                return true;
            }
        }
        for (LogFormat p : LogFormat.presets()) {
            if (p.name().equalsIgnoreCase(n)) {
                return true;
            }
        }
        return n.equalsIgnoreCase(Formats.PLAIN);
    }

    private static boolean valid(CustomFormat f) {
        try {
            f.compile();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * New formats from a logging layout or config: one for each layout in it (a config often
     * has several appenders). With {@code into}, the best one replaces that format.
     */
    private boolean createFromLayout(String text, CustomFormat into) {
        List<LayoutImport.Result> all = LayoutImport.convertAll(text);
        if (all.isEmpty()) {
            note.setText(" ");
            String problem = LayoutImport.problem(text);
            javax.swing.JOptionPane.showMessageDialog(this, problem != null ? problem
                            : "No logging layout found. Paste something like\n"
                            + "%d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n\n"
                            + "or a logback.xml, log4j2.xml, log4j.properties, logging.properties or Python logging config.",
                    "Log Formats", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        List<LayoutImport.Result> ordered = order(all);
        List<String> names = new ArrayList<>();
        int skipped = 0;
        int firstIndex = -1;
        for (int k = 0; k < ordered.size(); k++) {
            LayoutImport.Result r = ordered.get(k);
            if (k == 0 && into != null) {
                CustomFormat f = new CustomFormat(into.name(), r.pattern(), r.timePattern(), into.files());
                int i = list.getSelectedIndex();
                model.set(i, f);
                changed = true;
                names.add(f.name());
                firstIndex = i;
                continue;
            }
            if (exists(r)) {
                skipped++;
                continue;
            }
            String name = uniqueName(ordered.size() == 1 ? defaultName() : nameFor(r));
            model.addElement(new CustomFormat(name, r.pattern(), r.timePattern(), ""));
            changed = true;
            names.add(name);
            if (firstIndex < 0) {
                firstIndex = model.size() - 1;
            }
        }
        if (into == null) {
            paste.setText("");
        }
        if (firstIndex >= 0) {
            list.setSelectedIndex(firstIndex);
            list.ensureIndexIsVisible(firstIndex);
            showSelected();
        }
        LayoutImport.Result r = ordered.get(0);
        if (all.size() == 1) {
            note.setText("Converted from a " + r.from() + " layout" + (r.where().isEmpty() ? "" : " (" + r.where() + ")") + ".");
        } else {
            note.setText("<html>" + (names.isEmpty() ? "No new formats" : "Created " + names.size() + " format"
                    + (names.size() == 1 ? "" : "s") + ": " + esc(String.join(", ", names)))
                    + (skipped > 0 ? " (" + skipped + " already here)" : "") + ". Each log uses the one that fits it; "
                    + "change it with the Format button in the status bar.</html>");
        }
        return true;
    }

    /** The layouts the sample fits best first, then file appenders' before the console's. */
    private List<LayoutImport.Result> order(List<LayoutImport.Result> all) {
        List<String> lines = sample.stream().filter(l -> !l.isBlank()).toList();
        java.util.Map<LayoutImport.Result, Integer> fit = new java.util.IdentityHashMap<>();
        for (LayoutImport.Result r : all) {
            fit.put(r, lines.isEmpty() ? 0 : LayoutImport.fits(r, lines));
        }
        List<LayoutImport.Result> out = new ArrayList<>(all);
        out.sort(java.util.Comparator.<LayoutImport.Result>comparingInt(r -> -fit.get(r))
                .thenComparing(LayoutImport.Result::console));
        return out;
    }

    private boolean exists(LayoutImport.Result r) {
        for (int i = 0; i < model.size(); i++) {
            CustomFormat f = model.get(i);
            if (f.pattern().equals(r.pattern()) && f.timePattern().equals(r.timePattern())) {
                return true;
            }
        }
        return false;
    }

    /** "RollingFile “Audit”" → "Audit (Log4j 2)"; without a name, the kind of layout. */
    private String nameFor(LayoutImport.Result r) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\u201c(.+?)\u201d").matcher(r.where());
        String kind = r.from().replace(" console", "");
        String base = m.find() ? m.group(1) : r.where().isEmpty() ? defaultName() : r.where();
        return base + " (" + kind + ")";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private String defaultName() {
        return sampleName != null ? baseName(sampleName) : "My format";
    }

    /** "app.2026-10-21.log" → "app". */
    private static String baseName(String file) {
        String n = file.replaceAll("(\\.(log|txt|out|gz|zip|\\d+))+$", "").replaceAll("[._-]?\\d{4}-?\\d{2}-?\\d{2}.*$", "");
        return n.isBlank() ? file : n;
    }

    private void guessPattern() {
        FormatGuess.Guess g = FormatGuess.guess(sample);
        if (g == null) {
            note.setText("No layout found in the sample. Open another sample, or write the pattern.");
            return;
        }
        pattern.setText(g.pattern());
        if (!g.timePattern().isEmpty() || time.getText().isBlank()) {
            time.setText(g.timePattern());
        }
        note.setText("Guessed from the sample: " + g.matching() + " of " + g.lines() + " lines fit.");
    }

    // ---------------------------------------------------------------- samples

    /** Opens a log (or a logging config) for the preview. {@code create}: make a new format from it. */
    private void openSample(boolean create) {
        FileDialog fd = new FileDialog(SwingUtilities.getWindowAncestor(this) instanceof java.awt.Frame fr ? fr : null,
                "Open a Log File", FileDialog.LOAD);
        String last = AppState.prefs().get("lastDir", null);
        if (last != null) {
            fd.setDirectory(last);
        }
        fd.setVisible(true);
        File[] chosen = fd.getFiles();
        if (chosen.length > 0) {
            useFile(chosen[0].toPath(), create);
        }
    }

    /** A dropped or opened file: a logging config is imported, a log becomes the sample. */
    void useFile(Path file, boolean create) {
        String text;
        try {
            text = readStart(file);
        } catch (IOException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Cannot read " + file + ":\n" + e.getMessage(),
                    "Log Formats", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        String fn = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean config = fn.matches(".*\\.(xml|properties|ya?ml|json|conf|cfg|ini|toml|py)$")
                && LayoutImport.looksLikeLayout(text)
                && (!LayoutImport.convertAll(text).isEmpty() || LayoutImport.problem(text) != null);
        if (config) {
            CustomFormat sel = list.getSelectedValue();
            boolean editing = sel != null && right.isAncestorOf(pattern) && pattern.isShowing();
            createFromLayout(text, editing && !create ? sel : null);
            return;
        }
        sample = new ArrayList<>(List.of(text.split("\\R", -1)));
        if (sample.size() > SAMPLE_LINES) {
            sample = new ArrayList<>(sample.subList(0, SAMPLE_LINES));
        }
        sampleName = file.getFileName().toString();
        AppState.prefs().put("lastDir", file.toAbsolutePath().getParent().toString());
        if (create || list.getSelectedValue() == null) {
            FormatGuess.Guess g = FormatGuess.guess(sample);
            add(new CustomFormat(uniqueName(defaultName()), g == null ? "{time} {level} {message}" : g.pattern(),
                    g == null ? "" : g.timePattern(), ""));
            note.setText(g == null ? "No layout found in this file: adjust the pattern so lines fit."
                    : "Guessed from " + sampleName + ": " + g.matching() + " of " + g.lines() + " lines fit.");
        } else {
            updatePreview();
        }
    }

    /** The first lines of a file (up to 1 MB), in its detected encoding. */
    private static String readStart(Path file) throws IOException {
        Charset cs = logsift.core.Encoding.detect(file);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(file), cs))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < SAMPLE_LINES && sb.length() < 1 << 20) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- preview

    private void updatePreview() {
        CustomFormat f = list.getSelectedValue();
        if (f == null) {
            return;
        }
        sampleLabel.setText(sample.isEmpty() ? "No sample: open a log file or drop one here to see how its lines split."
                : "Sample: first " + sample.size() + " lines of " + (sampleName == null ? "the log" : sampleName));
        LogFormat lf;
        try {
            lf = f.compile();
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            status.setForeground(errorColor());
            preview.set(null, null, sample);
            return;
        }
        Formats.Detected d = Formats.detect(sample, List.of(f), null, lf.name(), java.time.Year.now().getValue());
        int[] count = Formats.matchCount(lf, sample);
        String times = !lf.columns().contains("time") ? ""
                : d.times() == null ? " · times not recognized: set the time format" : " · times read";
        if (count[1] == 0) {
            status.setText("Pattern OK");
            status.setForeground(okColor());
        } else if (count[0] == 0) {
            status.setText("No sample line fits the pattern");
            status.setForeground(errorColor());
        } else {
            status.setText(count[0] + " of " + count[1] + " lines fit" + times
                    + (count[0] < count[1] ? " (the others continue the line above)" : ""));
            status.setForeground(d.times() == null && !times.isEmpty() ? errorColor() : okColor());
        }
        preview.set(lf, d.times(), sample);
        sizeColumns();
        list.repaint();
    }

    /** Columns as wide as their values (up to a limit); the last one takes the rest. */
    private void sizeColumns() {
        java.awt.FontMetrics fm = table.getFontMetrics(table.getFont());
        int n = table.getColumnCount();
        for (int c = 0; c < n - 1; c++) {
            int w = fm.stringWidth(table.getColumnName(c)) + 24;
            for (int r = 0; r < Math.min(60, table.getRowCount()); r++) {
                if (preview.fits(r)) {
                    Object v = table.getValueAt(r, c);
                    w = Math.max(w, fm.stringWidth(v == null ? "" : v.toString()) + 14);
                }
            }
            table.getColumnModel().getColumn(c).setPreferredWidth(Math.min(w, 320));
        }
    }

    private static Color okColor() {
        return Theme.pick(new Color(0x1A7F37), new Color(0x56D364));
    }

    private static Color errorColor() {
        return Theme.pick(new Color(0xCF222E), new Color(0xFF7B72));
    }

    /** Sample lines split into the format's columns, plus the time as read. */
    private static final class PreviewModel extends AbstractTableModel {
        private final List<String[]> rows = new ArrayList<>();
        private final List<Boolean> fit = new ArrayList<>();
        private List<String> names = List.of("line");

        void set(LogFormat f, TimeFormat tf, List<String> lines) {
            rows.clear();
            fit.clear();
            List<String> cols = new ArrayList<>();
            if (f != null) {
                for (String c : f.columns()) {
                    if (!c.equals("source")) {
                        cols.add(c);
                    }
                }
            }
            boolean showTime = tf != null && cols.contains("time");
            List<String> header = new ArrayList<>();
            if (showTime) {
                header.add("time read as");
            }
            header.addAll(cols.isEmpty() ? List.of("line") : cols);
            java.time.format.DateTimeFormatter out = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneOffset.UTC);
            int count = lines.size();
            while (count > 0 && (lines.get(count - 1) == null || lines.get(count - 1).isEmpty())) {
                count--; // the empty end of the file
            }
            for (String s : lines.subList(0, count)) {
                if (s == null) {
                    continue;
                }
                String[] row = new String[header.size()];
                boolean ok = f != null && f.matches(s);
                if (ok) {
                    int k = 0;
                    if (showTime) {
                        long t = tf.parse(s);
                        row[k++] = t == TimeFormat.NONE ? "?" : out.format(java.time.Instant.ofEpochMilli(t));
                    }
                    for (String c : cols) {
                        row[k++] = f.value(s, c);
                    }
                } else {
                    row[row.length - 1] = s; // shown whole in the last column
                }
                rows.add(row);
                fit.add(ok);
            }
            boolean same = header.equals(names);
            names = header;
            if (same) {
                fireTableDataChanged();
            } else {
                fireTableStructureChanged();
            }
        }

        boolean fits(int row) {
            return row < fit.size() && fit.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return names.size();
        }

        @Override
        public String getColumnName(int c) {
            return names.get(c);
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }
    }

    // ---------------------------------------------------------------- saving

    /** Saves the formats if they changed; returns whether they did. */
    boolean save() {
        if (!changed) {
            return false;
        }
        List<CustomFormat> out = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            out.add(model.get(i));
        }
        try {
            AppState.saveCustomFormats(out);
        } catch (IOException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Cannot save the log formats:\n" + e.getMessage(),
                    "Log Formats", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
        changed = false;
        return true;
    }

    /** Starts a new format from the open log's lines (View → Log Format → New Format from This Log). */
    void newFromSample() {
        FormatGuess.Guess g = FormatGuess.guess(sample);
        add(new CustomFormat(uniqueName(defaultName()), g == null ? "{time} {level} {message}" : g.pattern(),
                g == null ? "" : g.timePattern(), ""));
        note.setText(g == null ? "No layout found in this log: adjust the pattern so lines fit."
                : "Guessed from this log: " + g.matching() + " of " + g.lines() + " lines fit.");
    }

    // for tests
    JTextField patternField() {
        return pattern;
    }

    JTextField timeField() {
        return time;
    }

    JTextArea pasteArea() {
        return paste;
    }

    JLabel statusLabel() {
        return status;
    }

    JTable previewTable() {
        return table;
    }

    JList<CustomFormat> formatList() {
        return list;
    }

    // ---------------------------------------------------------------- drop and paste

    /** Files dropped anywhere on the panel. */
    private final class Drop extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport s) {
            try {
                @SuppressWarnings("unchecked")
                List<File> dropped = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (dropped.isEmpty()) {
                    return false;
                }
                boolean create = !pattern.isShowing();
                SwingUtilities.invokeLater(() -> useFile(dropped.get(0).toPath(), create));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Pasting a logging layout (even a whole config file) into the pattern converts it. */
    private final class LayoutPaste extends TransferHandler {
        private final TransferHandler normal;

        LayoutPaste(TransferHandler normal) {
            this.normal = normal;
        }

        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.stringFlavor) || normal.canImport(s);
        }

        @Override
        public boolean importData(TransferSupport s) {
            if (s.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    String text = (String) s.getTransferable().getTransferData(DataFlavor.stringFlavor);
                    if (LayoutImport.looksLikeLayout(text) && list.getSelectedValue() != null
                            && (!LayoutImport.convertAll(text).isEmpty() || LayoutImport.problem(text) != null)) {
                        return createFromLayout(text, list.getSelectedValue());
                    }
                } catch (Exception e) {
                    // paste it as it is
                }
            }
            return normal.importData(s);
        }

        @Override
        public int getSourceActions(JComponent c) {
            return normal.getSourceActions(c);
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            return null; // copying is done by exportToClipboard below
        }

        @Override
        public void exportToClipboard(JComponent comp, java.awt.datatransfer.Clipboard clip, int action) {
            normal.exportToClipboard(comp, clip, action);
        }
    }
}
