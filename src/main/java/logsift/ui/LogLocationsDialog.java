package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import logsift.agent.AgentSettings;
import logsift.agent.LogCatalog;
import logsift.agent.LogTools;
import logsift.agent.Scope;

/**
 * Where your logs are, for coding agents: services (or apps), their folders, and what each
 * file holds, so an agent can be asked "why did orders fail last night?" and find the files
 * itself. Logsift finds the files in the folders and guesses what each holds; you correct the
 * guesses. Saved in {@code ~/.logsift/logs.json}, or in a project's {@code .logsift/logs.json}.
 */
final class LogLocationsDialog extends JDialog {

    /** A service being edited, and the file it is saved in. */
    static final class Item {
        String name;
        String description;
        final List<String> folders = new ArrayList<>();
        /** What the files hold, by pattern: the person's words. */
        final Map<String, String> kinds = new LinkedHashMap<>();
        String timeZone;
        String notes;
        Path file;
        boolean project;

        Item(LogCatalog.Service s, Path file, boolean project) {
            name = s.name();
            description = s.description();
            folders.addAll(s.folders());
            for (LogCatalog.FileKind k : s.files()) {
                kinds.put(k.pattern(), k.description());
            }
            timeZone = s.timeZone();
            notes = s.notes();
            this.file = file;
            this.project = project;
        }

        LogCatalog.Service service() {
            List<LogCatalog.FileKind> files = new ArrayList<>();
            kinds.forEach((p, d) -> {
                if (d != null && !d.isBlank()) {
                    files.add(new LogCatalog.FileKind(p, d.strip()));
                }
            });
            return new LogCatalog.Service(name, description, folders, files, timeZone, notes);
        }

        LogCatalog.Entry entry() {
            return new LogCatalog.Entry(service(), file, project);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Path home;
    private final DefaultListModel<Item> items = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(items);
    private final JTextField name = new JTextField(24);
    private final JTextField description = new JTextField(20);
    private final DefaultListModel<String> folders = new DefaultListModel<>();
    private final JList<String> folderList = new JList<>(folders);
    private final FileModel fileModel = new FileModel();
    private final JTable files = new JTable(fileModel);
    private final JLabel filesStatus = new JLabel(" ");
    private final JComboBox<String> timeZone = new JComboBox<>();
    private final JTextArea notes = new JTextArea(3, 20);
    private final JTextArea preview = new JTextArea();
    private final JLabel where = new JLabel(" ");
    private final JComboBox<Object> savedIn = new JComboBox<>();
    /** Every list file read or written here: each is saved, so a service moved out of one leaves it. */
    private final java.util.Set<Path> listFiles = new java.util.LinkedHashSet<>();
    private final JPanel access = new JPanel(new BorderLayout(10, 0));
    private final JLabel accessText = new JLabel();
    private final JPanel form = new JPanel(new BorderLayout());
    private final JLabel empty = new JLabel();
    private final AtomicInteger scans = new AtomicInteger();
    private Item current;
    private boolean loading;
    private boolean dirty;

    /**
     * @param focus a log or folder to start from: its service is shown, or a new one is made
     *              for its folder; null: just the list
     */
    LogLocationsDialog(java.awt.Window owner, Path focus) {
        super(owner, "Log Locations for Agents", ModalityType.APPLICATION_MODAL);
        this.home = AppState.home();
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left(), right());
        split.setDividerLocation(250);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        load();
        if (focus != null) {
            focusOn(focus);
        } else if (!items.isEmpty()) {
            list.setSelectedIndex(0);
        }
        showSelected();
        getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                close();
            }
        });
        setSize(new Dimension(1100, 760));
        setLocationRelativeTo(owner);
    }

    private JComponent header() {
        JLabel l = new JLabel("<html><b style='font-size:1.15em'>Where are your logs?</b><br>"
                + "<span style='color:#6b7280'>Tell coding agents where each service writes its logs and what each "
                + "file holds. Then ask “why did orders fail last night?” and the agent finds the files itself. "
                + "Logsift finds the files and guesses what they hold: correct what it got wrong.</span></html>",
                Icons.of("sparkles"), JLabel.LEFT);
        l.setIconTextGap(10);
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        return l;
    }

    // ---------------------------------------------------------------- the list

    private JComponent left() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, v, i, sel, focus);
                Item it = (Item) v;
                String d = it.description == null || it.description.isBlank() ? "" : it.description;
                setText("<html><b>" + esc(it.name.isBlank() ? "(no name)" : it.name) + "</b>"
                        + (it.project ? " <span style='color:#2563eb'>project</span>" : "")
                        + "<br><span style='color:" + (sel ? "inherit" : "#6b7280") + "'>"
                        + esc(d.length() > 46 ? d.substring(0, 46) + "…" : d) + "</span></html>");
                setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        JButton add = new JButton("Add Folder…", Icons.of("folder-open"));
        add.setToolTipText("A new service for a folder of logs: Logsift finds its files and guesses what they hold");
        add.addActionListener(e -> addFromFolder());
        JButton remove = new JButton(Icons.of("x"));
        remove.setToolTipText("Remove the selected service");
        remove.addActionListener(e -> removeSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(add);
        buttons.add(remove);
        buttons.add(shareButton());
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 6));
        JLabel title = new JLabel("Services");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        p.add(title, BorderLayout.NORTH);
        p.add(new JScrollPane(list), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void load() {
        loading = true;
        items.clear();
        Path user = LogCatalog.userFile(home);
        try {
            if (Files.isRegularFile(user)) {
                for (LogCatalog.Service s : LogCatalog.read(user)) {
                    items.addElement(new Item(s, user, false));
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Logsift couldn't read " + user + ": " + e.getMessage()
                    + "\nFix or remove the file; nothing is changed until you add a service.", getTitle(),
                    JOptionPane.WARNING_MESSAGE);
        }
        listFiles.add(user);
        for (Path p : AppState.logLocationProjects()) {
            try {
                listFiles.add(p);
                for (LogCatalog.Service s : LogCatalog.read(p)) {
                    items.addElement(new Item(s, p, true));
                }
            } catch (Exception e) {
                // a project moved away: not shown
            }
        }
        loading = false;
    }

    /** Shows the service of a log or folder, or a new one for its folder. */
    private void focusOn(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        for (int i = 0; i < items.size(); i++) {
            LogCatalog.Entry e = items.get(i).entry();
            for (Path d : e.folders()) {
                if (abs.startsWith(d)) {
                    list.setSelectedIndex(i);
                    return;
                }
            }
        }
        Path folder = Files.isDirectory(abs) ? abs : abs.getParent();
        if (folder != null) {
            addService(folder);
        }
    }

    /** A name for a folder's service: the folder's name, or its parent's for a folder called logs. */
    static String nameFor(Path folder) {
        Path f = folder;
        String n = f.getFileName() == null ? "logs" : f.getFileName().toString();
        String low = n.toLowerCase(Locale.ROOT);
        if ((low.equals("logs") || low.equals("log") || low.equals("var")) && f.getParent() != null
                && f.getParent().getFileName() != null) {
            n = f.getParent().getFileName().toString();
        }
        return n.replace('/', '-').replace(' ', '-');
    }

    private void addFromFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("The folder a service writes its logs to");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
            addService(fc.getSelectedFile().toPath().toAbsolutePath().normalize());
        }
    }

    void addService(Path folder) {
        String base = nameFor(folder);
        String n = base;
        for (int k = 2; nameTaken(n, null); k++) {
            n = base + "-" + k;
        }
        Item it = new Item(new LogCatalog.Service(n, "", List.of(folder.toString()), List.of(), "", ""),
                LogCatalog.userFile(home), false);
        items.addElement(it);
        list.setSelectedIndex(items.size() - 1);
        dirty = true;
        SwingUtilities.invokeLater(() -> description.requestFocusInWindow());
    }

    private boolean nameTaken(String n, Item except) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it != except && it.name.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }

    private void removeSelected() {
        Item it = list.getSelectedValue();
        if (it == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Remove " + it.name + " from the log locations? Its logs stay where "
                + "they are.", getTitle(), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        int i = list.getSelectedIndex();
        current = null;
        items.remove(i);
        dirty = true;
        if (!items.isEmpty()) {
            list.setSelectedIndex(Math.min(i, items.size() - 1));
        }
        showSelected();
    }

    // ---------------------------------------------------------------- the form

    private JComponent right() {
        JPanel f = new WidthTracking(new GridBagLayout());
        f.setBorder(BorderFactory.createEmptyBorder(10, 8, 6, 14));
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.anchor = GridBagConstraints.NORTHWEST;
        l.insets = new Insets(7, 0, 4, 12);
        GridBagConstraints v = new GridBagConstraints();
        v.gridx = 1;
        v.weightx = 1;
        v.fill = GridBagConstraints.HORIZONTAL;
        v.anchor = GridBagConstraints.WEST;
        v.insets = new Insets(4, 0, 4, 0);

        name.putClientProperty("JTextField.placeholderText", "orders");
        name.setToolTipText("A short name agents use: “orders”, and “orders/app” for its app.log");
        description.putClientProperty("JTextField.placeholderText",
                "What it is: “Order service: takes orders from the shop and charges cards”");
        row(f, l, v, "Name", name);
        row(f, l, v, "What it is", description);
        savedIn.setToolTipText("Your own list, or a project's .logsift/logs.json (commit it: your team's agents get it too)");
        savedIn.addActionListener(e -> savedInChosen());
        JPanel si = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        si.add(savedIn);
        row(f, l, v, "Saved in", si);

        folderList.setVisibleRowCount(2);
        JButton addFolder = new JButton("Add Folder…", Icons.of("plus"));
        addFolder.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Another folder of " + (current == null ? "this service" : current.name) + "'s logs");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null && current != null) {
                current.folders.add(fc.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
                changed(true);
            }
        });
        JButton removeFolder = new JButton("Remove");
        removeFolder.addActionListener(e -> {
            if (current != null && folderList.getSelectedValue() != null) {
                current.folders.remove(folderList.getSelectedValue());
                changed(true);
            }
        });
        JPanel fb = new JPanel();
        fb.setLayout(new BoxLayout(fb, BoxLayout.Y_AXIS));
        fb.add(addFolder);
        fb.add(Box.createVerticalStrut(4));
        fb.add(removeFolder);
        JPanel folderRow = new JPanel(new BorderLayout(8, 0));
        JScrollPane flsp = new JScrollPane(folderList);
        flsp.setMinimumSize(new Dimension(150, 50));
        folderRow.add(flsp, BorderLayout.CENTER);
        folderRow.add(fb, BorderLayout.EAST);
        row(f, l, v, "Folders", folderRow);

        // the files found, with what they hold
        files.setFillsViewportHeight(true);
        files.setShowGrid(false);
        files.setRowHeight(Math.max(files.getRowHeight(), 24));
        files.getTableHeader().setReorderingAllowed(false);
        int[] widths = {170, 60, 80, 130, 420};
        for (int i = 0; i < widths.length; i++) {
            files.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        files.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean focus, int row,
                                                           int col) {
                super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                FileModel.Row r = fileModel.rows.get(row);
                boolean guess = col == 4 && (r.description == null || r.description.isBlank());
                setFont(t.getFont().deriveFont(guess ? Font.ITALIC : col == 0 ? Font.BOLD : Font.PLAIN));
                if (guess) {
                    setText(r.guess == null ? "say what it holds" : r.guess + "  (guessed: click to change)");
                    if (!sel) {
                        setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
                    }
                } else if (!sel) {
                    setForeground(t.getForeground());
                }
                setToolTipText(col == 0 ? r.pattern + (r.files.isEmpty() ? "" : "  ·  " + String.join(", ", r.sample()))
                        : col == 4 ? "What this log holds, in a few words: agents read it" : null);
                return this;
            }
        });
        files.putClientProperty("terminateEditOnFocusLost", true);
        JScrollPane fsp = new JScrollPane(files);
        fsp.setPreferredSize(new Dimension(420, 170));
        fsp.setMinimumSize(new Dimension(200, 170));
        JButton useGuesses = new JButton("Use the Guesses");
        useGuesses.setToolTipText("Keeps Logsift's guesses as the descriptions of the files you haven't described");
        useGuesses.addActionListener(e -> {
            if (current == null) {
                return;
            }
            for (FileModel.Row r : fileModel.rows) {
                if ((r.description == null || r.description.isBlank()) && r.guess != null) {
                    r.description = r.guess;
                    current.kinds.put(r.pattern, r.guess);
                }
            }
            fileModel.fireTableDataChanged();
            changed(false);
        });
        JButton rescan = new JButton("Look Again", Icons.reload());
        rescan.setToolTipText("Finds the files in the folders again");
        rescan.addActionListener(e -> scan());
        JPanel fbar = new JPanel(new BorderLayout(8, 0));
        filesStatus.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        fbar.add(filesStatus, BorderLayout.CENTER);
        JPanel fbtn = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        fbtn.add(useGuesses);
        fbtn.add(rescan);
        fbar.add(fbtn, BorderLayout.EAST);
        JPanel filesPanel = new JPanel(new BorderLayout(0, 4));
        filesPanel.add(fsp, BorderLayout.CENTER);
        filesPanel.add(fbar, BorderLayout.SOUTH);
        row(f, l, v, "Its logs", filesPanel);

        timeZone.setEditable(true);
        for (String z : new String[] {"", "UTC", ZoneId.systemDefault().getId(), "Europe/London", "Europe/Berlin",
                "America/New_York", "America/Chicago", "America/Los_Angeles", "Asia/Singapore", "Asia/Kolkata",
                "Asia/Tokyo", "Australia/Sydney"}) {
            if (((javax.swing.DefaultComboBoxModel<String>) timeZone.getModel()).getIndexOf(z) < 0) {
                timeZone.addItem(z);
            }
        }
        timeZone.setToolTipText("The time zone of the times written in its logs, if not this computer's");
        JPanel tz = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tz.add(timeZone);
        JLabel tzHint = new JLabel("  the zone of the times in its logs (empty: not said)");
        tzHint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        tz.add(tzHint);
        row(f, l, v, "Times in", tz);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.putClientProperty("JTextArea.placeholderText",
                "Anything an agent should know: “payment timeouts before 06:00 are the nightly maintenance”");
        JScrollPane nsp = new JScrollPane(notes);
        nsp.setMinimumSize(new Dimension(200, 60));
        row(f, l, v, "Notes", nsp);

        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(false);
        preview.setFont(Theme.monoFont(preview.getFont().getSize() - 1));
        preview.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        JScrollPane psp = new JScrollPane(preview);
        psp.setPreferredSize(new Dimension(420, 130));
        psp.setMinimumSize(new Dimension(200, 130));
        row(f, l, v, "An agent sees", psp);
        GridBagConstraints end = new GridBagConstraints();
        end.gridx = 0;
        end.gridy = GridBagConstraints.RELATIVE;
        end.weighty = 1;
        f.add(new JLabel(), end);

        DocumentListener edits = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fromForm();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fromForm();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fromForm();
            }
        };
        name.getDocument().addDocumentListener(edits);
        description.getDocument().addDocumentListener(edits);
        notes.getDocument().addDocumentListener(edits);
        timeZone.addActionListener(e -> fromForm());

        empty.setText("<html><div style='width:420px;text-align:center'><b>No services yet.</b><br><br>"
                + "Click <b>Add Folder…</b> and choose a folder a service writes its logs to: Logsift finds the files "
                + "and guesses what each holds.</div></html>");
        empty.setHorizontalAlignment(JLabel.CENTER);
        formScroll = new JScrollPane(f, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        form.add(formScroll, BorderLayout.CENTER);
        return form;
    }

    /** A panel as wide as its scroll pane: fields shrink with the window instead of being cut off. */
    private static final class WidthTracking extends JPanel implements javax.swing.Scrollable {
        WidthTracking(java.awt.LayoutManager l) {
            super(l);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(16, r.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static void row(JPanel f, GridBagConstraints l, GridBagConstraints v, String label, JComponent c) {
        JLabel t = new JLabel(label);
        t.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        f.add(t, l);
        f.add(c, v);
    }

    private JComponent footer() {
        access.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)));
        accessText.setIcon(Icons.of("triangle-alert", new Color(0xD97706), 16));
        accessText.setIconTextGap(8);
        access.add(accessText, BorderLayout.CENTER);
        JButton allow = new JButton("Let Agents Read Them");
        allow.setToolTipText("Adds these folders to the folders agents may read (Settings → Agents)");
        allow.addActionListener(e -> allowFolders());
        access.add(allow, BorderLayout.EAST);
        access.setVisible(false);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 14, 12, 14));
        where.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        bottom.add(where, BorderLayout.CENTER);
        JButton done = new JButton("Done");
        done.putClientProperty("JButton.buttonType", "default");
        done.addActionListener(e -> close());
        getRootPane().setDefaultButton(done);
        bottom.add(done, BorderLayout.EAST);
        JPanel p = new JPanel(new BorderLayout());
        p.add(access, BorderLayout.NORTH);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    private void showSelected() {
        Item it = list.getSelectedValue();
        current = it;
        form.removeAll();
        if (it == null) {
            form.add(empty, BorderLayout.CENTER);
            access.setVisible(false);
            where.setText(items.isEmpty() ? "Saved in " + LogCatalog.userFile(home) : " ");
            form.revalidate();
            form.repaint();
            return;
        }
        form.add(formScroll, BorderLayout.CENTER);
        loading = true;
        name.setText(it.name);
        description.setText(it.description);
        notes.setText(it.notes);
        timeZone.setSelectedItem(it.timeZone == null ? "" : it.timeZone);
        fillSavedIn(it);
        loading = false;
        refreshFolders();
        where.setText("Saved in " + it.file + (it.project ? " (the project's: shared with your team)" : ""));
        scan();
        form.revalidate();
        form.repaint();
    }

    private JScrollPane formScroll;

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void refreshFolders() {
        folders.clear();
        if (current != null) {
            current.folders.forEach(folders::addElement);
        }
        updateAccess();
    }

    private void fromForm() {
        if (loading || current == null) {
            return;
        }
        String n = name.getText().strip().replace('/', '-');
        current.name = n;
        current.description = description.getText().strip();
        current.notes = notes.getText().strip();
        Object z = timeZone.getSelectedItem();
        current.timeZone = z == null ? "" : z.toString().strip();
        list.repaint();
        changed(false);
    }

    /** Something changed; {@code files}: the folders changed, so look for the files again. */
    private void changed(boolean files) {
        dirty = true;
        if (files) {
            refreshFolders();
            scan();
        } else {
            updatePreview();
        }
    }

    /** The choices of where the current service is saved. */
    private void fillSavedIn(Item it) {
        savedIn.removeAllItems();
        savedIn.addItem(new Where(LogCatalog.userFile(home), "My list (" + LogCatalog.userFile(home) + ")"));
        for (Path f : listFiles) {
            if (!f.equals(LogCatalog.userFile(home))) {
                savedIn.addItem(new Where(f, "Project: " + LogCatalog.projectRootOf(f)));
            }
        }
        if (it.project && !listFiles.contains(it.file)) {
            savedIn.addItem(new Where(it.file, "Project: " + LogCatalog.projectRootOf(it.file)));
        }
        savedIn.addItem("A project's folder…");
        for (int i = 0; i < savedIn.getItemCount(); i++) {
            if (savedIn.getItemAt(i) instanceof Where w && w.file().equals(it.file)) {
                savedIn.setSelectedIndex(i);
            }
        }
    }

    private record Where(Path file, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void savedInChosen() {
        if (loading || current == null) {
            return;
        }
        Object o = savedIn.getSelectedItem();
        Path file;
        if (o instanceof Where w) {
            file = w.file();
        } else {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("The project's folder (its .logsift/logs.json is shared with your team)");
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
                loading = true;
                fillSavedIn(current);
                loading = false;
                return;
            }
            file = LogCatalog.projectFile(fc.getSelectedFile().toPath().toAbsolutePath().normalize());
            listFiles.add(file);
        }
        current.file = file;
        current.project = !file.equals(LogCatalog.userFile(home));
        loading = true;
        fillSavedIn(current);
        loading = false;
        where.setText("Saved in " + file + (current.project ? " (the project's: shared with your team)" : ""));
        list.repaint();
        changed(false);
    }

    /** Saves this service in a project's list (for tests: no file chooser). */
    void saveInProjectForTest(Path projectDir) {
        Path f = LogCatalog.projectFile(projectDir);
        listFiles.add(f);
        current.file = f;
        current.project = true;
        loading = true;
        fillSavedIn(current);
        loading = false;
        changed(false);
    }

    // ---------------------------------------------------------------- the files

    /** Finds the service's files in the background, with guesses of what each holds. */
    private void scan() {
        Item it = current;
        if (it == null) {
            return;
        }
        int n = scans.incrementAndGet();
        filesStatus.setText("Looking at the files…");
        LogCatalog.Entry e = it.entry();
        Thread t = new Thread(() -> {
            List<LogCatalog.Group> groups = LogCatalog.scan(e);
            List<FileModel.Row> rows = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            int guessed = 0;
            for (LogCatalog.Group g : groups) {
                String pattern = g.pattern();
                String desc = null;
                for (Map.Entry<String, String> k : it.kinds.entrySet()) {
                    for (Path f : g.files()) {
                        if (LogCatalog.matches(k.getKey(), f.getFileName().toString())) {
                            pattern = k.getKey();
                            desc = k.getValue();
                            break;
                        }
                    }
                    if (desc != null) {
                        break;
                    }
                }
                seen.add(pattern);
                String guess = guessed++ < 60 ? LogCatalog.guess(g.current(), AppState.customFormats()) : null;
                rows.add(new FileModel.Row(g.name(), pattern, g.files(), g.bytes(), g.newest(), desc, guess));
            }
            // descriptions of files not found now (rotated away, another machine) stay
            it.kinds.forEach((p, d) -> {
                if (!seen.contains(p)) {
                    rows.add(new FileModel.Row(p, p, List.of(), 0, 0, d, null));
                }
            });
            SwingUtilities.invokeLater(() -> {
                if (scans.get() != n || current != it) {
                    return;
                }
                fileModel.set(rows);
                long found = groups.stream().mapToLong(g -> g.files().size()).sum();
                filesStatus.setText(groups.isEmpty() ? "No files found in its folders."
                        : String.format(Locale.ROOT, "%,d %s, as %d %s (rotated copies together)", found,
                        found == 1 ? "file" : "files", groups.size(), groups.size() == 1 ? "log" : "logs"));
                filesStatus.setToolTipText("Click a gray guess in What it holds to say what that log holds");
                updatePreview();
            });
        }, "logsift-log-locations");
        t.setDaemon(true);
        t.start();
    }

    private final class FileModel extends AbstractTableModel {
        static final class Row {
            final String name;
            String pattern;
            final List<Path> files;
            final long bytes;
            final long newest;
            String description;
            final String guess;

            Row(String name, String pattern, List<Path> files, long bytes, long newest, String description, String guess) {
                this.name = name;
                this.pattern = pattern;
                this.files = files;
                this.bytes = bytes;
                this.newest = newest;
                this.description = description;
                this.guess = guess;
            }

            List<String> sample() {
                List<String> s = new ArrayList<>();
                for (int i = 0; i < files.size() && i < 4; i++) {
                    s.add(files.get(i).getFileName().toString());
                }
                if (files.size() > 4) {
                    s.add("…");
                }
                return s;
            }
        }

        List<Row> rows = new ArrayList<>();

        void set(List<Row> r) {
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
                case 0 -> "Log";
                case 1 -> "Files";
                case 2 -> "Size";
                case 3 -> "Last changed";
                default -> "What it holds";
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 4;
        }

        private final DateTimeFormatter when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

        @Override
        public Object getValueAt(int r, int c) {
            Row x = rows.get(r);
            return switch (c) {
                case 0 -> x.name;
                case 1 -> x.files.isEmpty() ? "not found" : String.valueOf(x.files.size());
                case 2 -> x.files.isEmpty() ? "" : logsift.jvm.GcReport.size(x.bytes);
                case 3 -> x.newest <= 0 ? "" : when.format(Instant.ofEpochMilli(x.newest).atZone(ZoneId.systemDefault()));
                default -> x.description == null ? "" : x.description;
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c != 4 || current == null) {
                return;
            }
            Row x = rows.get(r);
            x.description = v == null ? "" : v.toString().strip();
            if (x.description.isEmpty()) {
                current.kinds.remove(x.pattern);
            } else {
                current.kinds.put(x.pattern, x.description);
            }
            fireTableRowsUpdated(r, r);
            changed(false);
        }
    }

    // ---------------------------------------------------------------- what agents see

    private Scope agentScope() {
        AgentSettings a = AgentSettings.load(home);
        Path userHome = Path.of(System.getProperty("user.home"));
        List<Path> roots = a.roots().isEmpty() ? List.of(userHome) : a.roots();
        return new Scope(roots, userHome);
    }

    private void updatePreview() {
        if (current == null) {
            preview.setText("");
            return;
        }
        LogCatalog one = LogCatalog.of(List.of(current.entry()));
        Scope scope = agentScope();
        String text = LogTools.catalogText(one, scope, AppState.customFormats(), null);
        preview.setText(text);
        preview.setCaretPosition(0);
        updateAccess();
    }

    /** Says which folders agents may not read, with a button to allow them. */
    private void updateAccess() {
        List<Path> denied = deniedFolders();
        access.setVisible(!denied.isEmpty());
        if (!denied.isEmpty()) {
            accessText.setText("<html>Agents may not read " + esc(denied.size() == 1 ? denied.get(0).toString()
                    : denied.size() + " of these folders") + " yet: they read only the folders in Settings → Agents "
                    + "(your home folder unless you chose others).</html>");
        }
    }

    private List<Path> deniedFolders() {
        List<Path> out = new ArrayList<>();
        if (current == null) {
            return out;
        }
        Scope scope = agentScope();
        for (Path f : current.entry().folders()) {
            if (!scope.allows(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private void allowFolders() {
        List<Path> denied = deniedFolders();
        if (denied.isEmpty()) {
            return;
        }
        AgentSettings a = AgentSettings.load(home);
        List<Path> roots = new ArrayList<>(a.roots());
        if (roots.isEmpty()) {
            roots.add(Path.of(System.getProperty("user.home"))); // keep what agents could read before
        }
        for (Path d : denied) {
            if (!roots.contains(d)) {
                roots.add(d);
            }
        }
        try {
            new AgentSettings(a.masking(), roots).save(home);
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Logsift couldn't save the agent settings: " + e.getMessage(), getTitle(),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        updatePreview();
    }

    // ---------------------------------------------------------------- saving

    /** Saves every changed list file; false if one couldn't be saved (and says so). */
    boolean save() {
        if (!dirty) {
            return true;
        }
        if (files.isEditing()) {
            files.getCellEditor().stopCellEditing();
        }
        Map<Path, List<LogCatalog.Service>> byFile = new LinkedHashMap<>();
        for (Path f : listFiles) {
            byFile.put(f, new ArrayList<>());
        }
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.name.isBlank()) {
                continue;
            }
            byFile.computeIfAbsent(it.file, k -> new ArrayList<>()).add(portable(it));
        }
        for (Map.Entry<Path, List<LogCatalog.Service>> e : byFile.entrySet()) {
            boolean user = e.getKey().equals(LogCatalog.userFile(home));
            if (e.getValue().isEmpty() && !Files.exists(e.getKey())) {
                continue; // nothing to save, and never was
            }
            try {
                LogCatalog.save(e.getKey(), e.getValue());
            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(this, "Logsift couldn't save " + e.getKey() + ": " + ex.getMessage(),
                        getTitle(), JOptionPane.WARNING_MESSAGE);
                return false;
            }
            if (!user) {
                AppState.addLogLocationProject(e.getKey());
            }
        }
        dirty = false;
        return true;
    }

    /** The service with its folders written to work on other computers too. */
    private LogCatalog.Service portable(Item it) {
        LogCatalog.Service s = it.service();
        Path root = it.project ? LogCatalog.projectRootOf(it.file) : null;
        List<String> fs = new ArrayList<>();
        for (String f : s.folders()) {
            fs.add(LogCatalog.portable(f, root));
        }
        return new LogCatalog.Service(s.name(), s.description(), fs, s.files(), s.timeZone(), s.notes());
    }

    // ---------------------------------------------------------------- sharing

    private JButton shareButton() {
        JButton b = new JButton("Share", Icons.of("chevron-down"));
        b.setHorizontalTextPosition(JButton.LEFT);
        b.setToolTipText("Send services to your team, or take theirs");
        b.addActionListener(e -> {
            javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
            Item it = list.getSelectedValue();
            javax.swing.JMenuItem copy = new javax.swing.JMenuItem("Copy " + (it == null ? "" : it.name + " ") + "as Text",
                    Icons.of("copy"));
            copy.setEnabled(it != null);
            copy.setToolTipText("For chat or email: who pastes it gets the same service");
            copy.addActionListener(a -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(LogCatalog.format(List.of(portable(it)))), null));
            javax.swing.JMenuItem copyAll = new javax.swing.JMenuItem("Copy All as Text");
            copyAll.setEnabled(!items.isEmpty());
            copyAll.addActionListener(a -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(LogCatalog.format(allPortable())), null));
            javax.swing.JMenuItem paste = new javax.swing.JMenuItem("Paste Services", Icons.of("plus"));
            paste.setToolTipText("Adds the services someone copied (Copy as Text) to your list");
            paste.addActionListener(a -> pasteServices());
            javax.swing.JMenuItem export = new javax.swing.JMenuItem("Export to a File…", Icons.of("save"));
            export.setEnabled(!items.isEmpty());
            export.addActionListener(a -> exportServices());
            javax.swing.JMenuItem imp = new javax.swing.JMenuItem("Import a File…", Icons.of("folder-open"));
            imp.addActionListener(a -> importServices());
            m.add(copy);
            m.add(copyAll);
            m.add(paste);
            m.addSeparator();
            m.add(export);
            m.add(imp);
            m.show(b, 0, b.getHeight());
        });
        return b;
    }

    private List<LogCatalog.Service> allPortable() {
        List<LogCatalog.Service> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).name.isBlank()) {
                out.add(portable(items.get(i)));
            }
        }
        return out;
    }

    private void pasteServices() {
        try {
            Object t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            addServices(LogCatalog.parse(String.valueOf(t)), "the clipboard");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "The clipboard doesn't hold services: copy them with Share → Copy as "
                    + "Text in Logsift (or copy a logs.json file's text).", getTitle(), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void exportServices() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("Export Log Locations");
        fc.setSelectedFile(new java.io.File("logs.json"));
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        try {
            LogCatalog.save(fc.getSelectedFile().toPath(), allPortable());
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Logsift couldn't write the file: " + e.getMessage(), getTitle(),
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void importServices() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("Import Log Locations (a logs.json)");
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        try {
            addServices(LogCatalog.read(fc.getSelectedFile().toPath()), fc.getSelectedFile().getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Logsift couldn't read " + fc.getSelectedFile().getName() + ": "
                    + e.getMessage(), getTitle(), JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Adds services to your list; one with a name already there replaces it. */
    void addServices(List<LogCatalog.Service> services, String from) {
        if (services.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no services in " + from + ".", getTitle(),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int replaced = 0;
        for (LogCatalog.Service s : services) {
            Item it = new Item(s, LogCatalog.userFile(home), false);
            int at = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).name.equalsIgnoreCase(s.name())) {
                    at = i;
                }
            }
            if (at >= 0) {
                it.file = items.get(at).file;
                it.project = items.get(at).project;
                items.set(at, it);
                replaced++;
            } else {
                items.addElement(it);
            }
        }
        dirty = true;
        list.setSelectedIndex(items.size() - 1);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name.equalsIgnoreCase(services.get(0).name())) {
                list.setSelectedIndex(i);
            }
        }
        showSelected();
        if (replaced > 0) {
            JOptionPane.showMessageDialog(this, replaced + (replaced == 1 ? " service was" : " services were")
                    + " already there and now as in " + from + ".", getTitle(), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void close() {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (!it.name.isBlank() && nameTaken(it.name, it)) {
                JOptionPane.showMessageDialog(this, "Two services are called " + it.name + ". Give each its own name: "
                        + "agents ask for them by name.", getTitle(), JOptionPane.INFORMATION_MESSAGE);
                list.setSelectedIndex(i);
                return;
            }
        }
        if (save()) {
            dispose();
        }
    }

    // ---------------------------------------------------------------- for tests

    JList<Item> listForTest() {
        return list;
    }

    JTable filesForTest() {
        return files;
    }

    String previewForTest() {
        return preview.getText();
    }

    String filesStatusForTest() {
        return filesStatus.getText();
    }

    boolean accessShownForTest() {
        return access.isVisible();
    }

    void setDescriptionForTest(String text) {
        description.setText(text);
    }

    void setNotesForTest(String text) {
        notes.setText(text);
    }

    void setTimeZoneForTest(String z) {
        timeZone.setSelectedItem(z);
    }

    void describeForTest(int row, String text) {
        fileModel.setValueAt(text, row, 4);
    }

    void closeForTest() {
        close();
    }

    boolean scanningForTest() {
        return filesStatus.getText().startsWith("Looking");
    }
}
