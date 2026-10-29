package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;

/**
 * Settings: how logs look, how files are read, updates, and the user's log formats. Changes
 * apply at once; log formats are saved when the window closes.
 */
final class SettingsDialog extends JDialog {
    static final String FORMATS = "Log Formats";
    static final String SOURCE = "Source Code";
    static final String AGENTS = "Agents";
    static final String METRICS = "Metrics";

    private static final Preferences PREFS = AppState.prefs();
    private final MainFrame frame;
    private final LogFormatsPanel formats;
    private final JTabbedPane tabs = new JTabbedPane();
    private final AgentsPanel agents;
    private static SettingsDialog lastForTest;

    /**
     * @param sample lines of the open log (for the formats preview)
     * @param sampleName its file name, or null
     */
    SettingsDialog(MainFrame frame, List<String> sample, String sampleName) {
        super(frame, MainFrame.isMac() ? "Settings" : "Settings", ModalityType.APPLICATION_MODAL);
        this.frame = frame;
        formats = new LogFormatsPanel(sample, sampleName);
        tabs.addTab("General", general());
        tabs.addTab(FORMATS, formats);
        tabs.addTab(SOURCE, sourceCode());
        tabs.addTab(METRICS, metrics());
        agents = new AgentsPanel(logsift.agent.AgentSettings.launcher(), this::openAgentActivity);
        tabs.addTab(AGENTS, agents);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));
        JLabel where = new JLabel("<html><small>Log formats are saved in " + AppState.formatsFile()
                + " (copy it to use them on another computer).</small></html>");
        where.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        south.add(where, BorderLayout.CENTER);
        south.add(close, BorderLayout.EAST);
        add(tabs, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(new Dimension(940, 700));
        setLocationRelativeTo(frame);
        lastForTest = this;
    }

    void showTab(String name) {
        int i = name == null ? -1 : tabs.indexOfTab(name);
        tabs.setSelectedIndex(Math.max(0, i));
    }

    LogFormatsPanel formats() {
        return formats;
    }

    AgentsPanel agents() {
        return agents;
    }

    /** Opens the log of what agents asked (closing Settings). */
    private void openAgentActivity() {
        java.nio.file.Path log = AppState.home().resolve("agent.log");
        if (!java.nio.file.Files.isRegularFile(log)) {
            javax.swing.JOptionPane.showMessageDialog(this, "No agent has asked Logsift anything yet.\n"
                    + "Each call will be listed in " + log + ".", "What Agents Asked",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        dispose();
        frame.openFile(log);
    }

    static SettingsDialog lastForTest() {
        return lastForTest;
    }

    @Override
    public void dispose() {
        if (formats.save()) {
            frame.formatsChanged();
        }
        super.dispose();
    }

    // ---------------------------------------------------------------- Metrics

    /** Which numbers in messages count as measurements (the Metrics tab, and agents' metrics). */
    private JComponent metrics() {
        java.nio.file.Path home = AppState.home();
        logsift.core.Numbers.Options o = logsift.core.Numbers.Options.load(home);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        JLabel intro = new JLabel("<html>The <b>Metrics</b> tab charts numbers that messages report over time. A number "
                + "counts when a unit follows it (<i>345 ms</i>, <i>12 MB</i>, <i>3 items</i>) or the word before it "
                + "says it is a measurement (<i>took</i>, <i>latency</i>, <i>size</i>, <i>queue</i>…). Numbers after a "
                + "key, as in <i>latency=35 items=3</i> or <i>depth: 42</i>, count:</html>");
        p.add(intro, BorderLayout.NORTH);

        javax.swing.JRadioButton known = new javax.swing.JRadioButton("Known keys: latency, size, bytes, count, hits, "
                + "misses, items, retries, errors, …, and keys that name a unit (latency_ms, body_bytes)");
        javax.swing.JRadioButton all = new javax.swing.JRadioButton("Every key, except ids, codes and names (id, "
                + "order_id, user, status, code, port, version, …)");
        javax.swing.JRadioButton only = new javax.swing.JRadioButton("Only these keys:");
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        g.add(known);
        g.add(all);
        g.add(only);
        javax.swing.JTextField onlyKeys = new javax.swing.JTextField(String.join(", ", o.only()), 40);
        onlyKeys.putClientProperty("JTextField.placeholderText", "latency, amount, items");
        javax.swing.JTextField never = new javax.swing.JTextField(String.join(", ", o.never()), 40);
        never.putClientProperty("JTextField.placeholderText", "keys that never count, like: shard, partition");
        JSpinner max = new JSpinner(new SpinnerNumberModel(o.maxSeries(), 1, 100, 1));
        (switch (o.keys()) {
            case KNOWN -> known;
            case ALL_BUT_IDS -> all;
            case ONLY -> only;
        }).setSelected(true);
        onlyKeys.setEnabled(only.isSelected());

        javax.swing.JTextField tryLine = new javax.swing.JTextField(
                "GET /orders status=200 latency=35 bytes=512 items=3 user=u12 order_id=99812", 50);
        JLabel tryResult = new JLabel(" ");
        Runnable apply = () -> {
            logsift.core.Numbers.KeyMode mode = all.isSelected() ? logsift.core.Numbers.KeyMode.ALL_BUT_IDS
                    : only.isSelected() ? logsift.core.Numbers.KeyMode.ONLY : logsift.core.Numbers.KeyMode.KNOWN;
            onlyKeys.setEnabled(only.isSelected());
            logsift.core.Numbers.Options n = new logsift.core.Numbers.Options(mode,
                    logsift.core.Numbers.Options.split(onlyKeys.getText()),
                    logsift.core.Numbers.Options.split(never.getText()), (Integer) max.getValue());
            List<String> yes = new ArrayList<>();
            List<String> no = new ArrayList<>();
            for (logsift.core.Numbers.Explained x : logsift.core.Numbers.explain(tryLine.getText(), n)) {
                String name = x.key().isEmpty() ? x.value() : x.key();
                if (x.measured()) {
                    yes.add("<b>" + LogLocationsDialogLike.esc(name) + "</b>"
                            + (x.unit().equals("none") ? "" : " (" + x.unit() + ")"));
                } else {
                    no.add(LogLocationsDialogLike.esc(name));
                }
            }
            tryResult.setText("<html>" + (yes.isEmpty() ? "No number in it counts." : "Measured: " + String.join(", ", yes)
                    + ".") + (no.isEmpty() ? "" : " <span style='color:#6b7280'>Not: " + String.join(", ", no) + ".</span>")
                    + "</html>");
            if (!n.equals(logsift.core.Numbers.options())) {
                logsift.core.Numbers.setOptions(n);
                try {
                    n.save(home);
                } catch (java.io.IOException e) {
                    // used for this session; said nowhere else
                }
                frame.metricsOptionsChanged();
            }
        };
        for (javax.swing.JRadioButton b : List.of(known, all, only)) {
            b.addActionListener(e -> apply.run());
        }
        javax.swing.event.DocumentListener typed = new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                apply.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                apply.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        };
        onlyKeys.getDocument().addDocumentListener(typed);
        never.getDocument().addDocumentListener(typed);
        tryLine.getDocument().addDocumentListener(typed);
        max.addChangeListener(e -> apply.run());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridwidth = 2;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(3, 0, 3, 0);
        form.add(known, l);
        form.add(all, l);
        JPanel onlyRow = new JPanel(new BorderLayout(8, 0));
        onlyRow.add(only, BorderLayout.WEST);
        onlyRow.add(onlyKeys, BorderLayout.CENTER);
        l.fill = GridBagConstraints.HORIZONTAL;
        form.add(onlyRow, l);
        l.fill = GridBagConstraints.NONE;
        l.gridwidth = 1;
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.anchor = GridBagConstraints.WEST;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.weightx = 1;
        f.insets = new Insets(5, 0, 5, 0);
        l.insets = new Insets(12, 0, 5, 12);
        add(form, l, f, "Never these keys", never);
        l.insets = new Insets(5, 0, 5, 12);
        JPanel maxRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        maxRow.add(max);
        JLabel maxHint = new JLabel("  numbers over time, the most frequent messages' first");
        maxHint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        maxRow.add(maxHint);
        add(form, l, f, "Show at most", maxRow);
        l.insets = new Insets(14, 0, 5, 12);
        add(form, l, f, "Try a line", tryLine);
        l.insets = new Insets(0, 0, 5, 12);
        add(form, l, f, "", tryResult);
        note(form, l, f, "Paste a line of your log to see which of its numbers count. Changes apply to open logs at "
                + "once, and to coding agents' metrics the next time they start.");
        GridBagConstraints end = new GridBagConstraints();
        end.gridx = 0;
        end.gridy = GridBagConstraints.RELATIVE;
        end.weighty = 1;
        form.add(new JLabel(), end);
        p.add(form, BorderLayout.CENTER);
        apply.run();
        return p;
    }

    /** HTML escaping for labels. */
    private static final class LogLocationsDialogLike {
        static String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    // ---------------------------------------------------------------- Source code

    /** Where the program's source is, and the editor that opens it at a stack line. */
    private JComponent sourceCode() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        JLabel intro = new JLabel("<html>Click a line of a stack trace (in the Exceptions tab, or right-click → "
                + "<b>Open … in Editor</b>) and Logsift opens that file at that line. It looks for the file "
                + "in these folders: your projects' source code.</html>");
        p.add(intro, BorderLayout.NORTH);

        javax.swing.DefaultListModel<java.nio.file.Path> model = new javax.swing.DefaultListModel<>();
        SourceOpener.roots().forEach(model::addElement);
        javax.swing.JList<java.nio.file.Path> list = new javax.swing.JList<>(model);
        list.setVisibleRowCount(6);
        JButton add = new JButton("Add Folder…", Icons.of("folder-open"));
        JButton remove = new JButton("Remove");
        remove.setEnabled(false);
        list.addListSelectionListener(e -> remove.setEnabled(list.getSelectedIndex() >= 0));
        Runnable save = () -> SourceOpener.setRoots(java.util.Collections.list(model.elements()));
        add.addActionListener(e -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("The folder of a project's source code");
            if (fc.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                model.addElement(fc.getSelectedFile().toPath().toAbsolutePath().normalize());
                save.run();
            }
        });
        remove.addActionListener(e -> {
            for (java.nio.file.Path q : list.getSelectedValuesList()) {
                model.removeElement(q);
            }
            save.run();
        });
        JPanel buttons = new JPanel();
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.Y_AXIS));
        buttons.add(add);
        buttons.add(javax.swing.Box.createVerticalStrut(6));
        buttons.add(remove);
        JPanel folders = new JPanel(new BorderLayout(8, 0));
        folders.add(new javax.swing.JScrollPane(list), BorderLayout.CENTER);
        folders.add(buttons, BorderLayout.EAST);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(5, 0, 5, 12);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.anchor = GridBagConstraints.WEST;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new Insets(5, 0, 5, 0);
        f.weightx = 1;
        JComboBox<SourceOpener.Editor> editor = new JComboBox<>(SourceOpener.Editor.values());
        editor.setSelectedItem(SourceOpener.editor());
        javax.swing.JTextField command = new javax.swing.JTextField(SourceOpener.command(), 40);
        command.putClientProperty("JTextField.placeholderText", "myeditor --goto {file}:{line}");
        command.setEnabled(SourceOpener.editor() == SourceOpener.Editor.CUSTOM);
        editor.addActionListener(e -> {
            SourceOpener.setEditor((SourceOpener.Editor) editor.getSelectedItem());
            command.setEnabled(editor.getSelectedItem() == SourceOpener.Editor.CUSTOM);
        });
        command.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                SourceOpener.setCommand(command.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                SourceOpener.setCommand(command.getText());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });
        add(form, l, f, "Open with", editor);
        add(form, l, f, "Command", command);
        note(form, l, f, "{file} and {line} are filled in. Use quotes around a path with spaces.");
        GridBagConstraints end = new GridBagConstraints();
        end.gridx = 0;
        end.gridy = GridBagConstraints.RELATIVE;
        end.weighty = 1;
        form.add(new JLabel(), end);

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.add(folders, BorderLayout.NORTH);
        center.add(form, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------- General

    private JComponent general() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(5, 0, 5, 12);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.anchor = GridBagConstraints.WEST;
        f.insets = new Insets(5, 0, 5, 0);
        f.weightx = 1;

        heading(p, "Logs", l, f, false);
        List<String> fonts = new ArrayList<>();
        fonts.add("Automatic (" + Theme.autoFontFamily() + ")");
        fonts.addAll(Theme.monospacedFamilies());
        JComboBox<String> font = new JComboBox<>(fonts.toArray(String[]::new));
        font.setSelectedItem(Theme.fontFamily() != null && fonts.contains(Theme.fontFamily())
                ? Theme.fontFamily() : fonts.get(0));
        font.setMaximumRowCount(20);
        SpinnerNumberModel sizeModel = new SpinnerNumberModel(Math.round(frame.fontSize()), 8, 32, 1);
        JSpinner size = new JSpinner(sizeModel);
        font.addActionListener(e -> frame.setFont(font.getSelectedIndex() <= 0 ? null : (String) font.getSelectedItem(),
                sizeModel.getNumber().floatValue()));
        size.addChangeListener(e -> frame.setFont(Theme.fontFamily(), sizeModel.getNumber().floatValue()));
        JPanel fontRow = new JPanel(new BorderLayout(8, 0));
        fontRow.add(font, BorderLayout.CENTER);
        fontRow.add(size, BorderLayout.EAST);
        add(p, l, f, "Font", fontRow);

        JComboBox<String> tab = new JComboBox<>(new String[] {"2", "4", "8"});
        tab.setSelectedItem(Integer.toString(LogView.tabWidth()));
        tab.addActionListener(e -> frame.setTabWidth(Integer.parseInt((String) tab.getSelectedItem())));
        add(p, l, f, "Tab width", tab);

        JCheckBox stripes = check("Stripe every other line", PREFS.getBoolean("stripes", true), frame::setStripes);
        add(p, l, f, "", stripes);
        JCheckBox timeline = check("Show the timeline", PREFS.getBoolean("timeline", true), frame::setTimelineEverywhere);
        add(p, l, f, "", timeline);
        JCheckBox dark = check("Dark mode", Theme.isDark(), frame::setDark);
        add(p, l, f, "", dark);

        heading(p, "Files", l, f, true);
        List<String> names = new ArrayList<>();
        for (String cs : new String[] {"windows-1252", "ISO-8859-1", "ISO-8859-15", "windows-1250", "windows-1251",
            "Shift_JIS", "EUC-JP", "GB18030", "Big5", "EUC-KR", "KOI8-R"}) {
            if (Charset.isSupported(cs)) {
                names.add(cs);
            }
        }
        JComboBox<String> enc = new JComboBox<>(names.stream()
                .map(n -> logsift.core.Encoding.label(Charset.forName(n))).toArray(String[]::new));
        enc.setSelectedIndex(Math.max(0, names.indexOf(frame.fallbackEncoding().name())));
        enc.addActionListener(e -> PREFS.put("fallbackEncoding", names.get(enc.getSelectedIndex())));
        add(p, l, f, "Text that isn't UTF-8", enc);
        note(p, l, f, "UTF-8 and UTF-16 are recognized by themselves. Others are read this way "
                + "(View → Encoding changes it for one file).");

        int[] millis = {250, 500, 1000, 2000, 5000};
        String[] labels = {"4 times a second", "Twice a second", "Every second", "Every 2 seconds", "Every 5 seconds"};
        JComboBox<String> poll = new JComboBox<>(labels);
        int now = PREFS.getInt("pollMs", 500);
        for (int i = 0; i < millis.length; i++) {
            if (millis[i] == now) {
                poll.setSelectedIndex(i);
            }
        }
        poll.addActionListener(e -> frame.setPollMillis(millis[poll.getSelectedIndex()]));
        add(p, l, f, "Check files for changes", poll);
        note(p, l, f, "Less often uses less power with many open files, or files on network drives.");

        String[] twoValues = {"ask", "compare", "tabs"};
        JComboBox<String> two = new JComboBox<>(new String[] {"Ask each time", "Compare them", "Open each in a tab"});
        two.setSelectedIndex(java.util.Arrays.asList(twoValues).indexOf(MainFrame.twoFilesDropped()));
        two.addActionListener(e -> PREFS.put("dropTwoFiles", twoValues[two.getSelectedIndex()]));
        add(p, l, f, "Two files dropped", two);

        JCheckBox session = check("Reopen the files that were open last time", PREFS.getBoolean("restoreSession", true),
                on -> PREFS.putBoolean("restoreSession", on));
        add(p, l, f, "At start", session);

        heading(p, "Updates", l, f, true);
        JCheckBox updates = check("Check for new versions once a day", PREFS.getBoolean("updates.auto", true),
                frame::setAutoUpdates);
        add(p, l, f, "", updates);
        note(p, l, f, "Asks GitHub for the latest version number only; nothing about you or your logs is sent.");

        GridBagConstraints end = new GridBagConstraints();
        end.gridx = 0;
        end.gridy = GridBagConstraints.RELATIVE;
        end.weighty = 1;
        p.add(new JLabel(), end);
        return p;
    }

    private static void heading(JPanel p, String text, GridBagConstraints l, GridBagConstraints f, boolean gap) {
        JLabel h = new JLabel(text);
        h.setFont(h.getFont().deriveFont(Font.BOLD));
        GridBagConstraints c = (GridBagConstraints) l.clone();
        c.gridy = GridBagConstraints.RELATIVE;
        c.gridwidth = 2;
        c.insets = new Insets(gap ? 16 : 0, 0, 4, 0);
        p.add(h, c);
    }

    private static void add(JPanel p, GridBagConstraints l, GridBagConstraints f, String label, JComponent field) {
        l.gridy = GridBagConstraints.RELATIVE;
        f.gridy = GridBagConstraints.RELATIVE;
        p.add(new JLabel(label), l);
        p.add(field, f);
    }

    private static void note(JPanel p, GridBagConstraints l, GridBagConstraints f, String text) {
        JLabel n = new JLabel(text);
        n.setFont(n.getFont().deriveFont(n.getFont().getSize2D() - 1));
        n.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        GridBagConstraints c = (GridBagConstraints) f.clone();
        c.insets = new Insets(0, 0, 4, 0);
        p.add(new JLabel(), l);
        p.add(n, c);
    }

    private static JCheckBox check(String text, boolean on, java.util.function.Consumer<Boolean> change) {
        JCheckBox b = new JCheckBox(text, on);
        b.addActionListener(e -> change.accept(b.isSelected()));
        return b;
    }
}
