package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import logsift.core.FolderSearch;
import logsift.core.SearchQuery;

/**
 * Search in all files of a folder. Results are grouped by file; double-click a
 * line to open the file there, double-click a file to fold it.
 */
final class FolderSearchTab extends JPanel {

    interface Host {
        /** Opens a hit: the file (unpacked first if compressed) at the line, with Find All. */
        void openHit(FolderSearch.FileResult file, long line, SearchQuery query);

        void folderTabChanged(FolderSearchTab tab);
    }

    private static final Preferences PREFS = AppState.prefs();

    private final Host host;
    private final JTextField folder = new JTextField(36);
    private final JTextField names = new JTextField(PREFS.get("fs.names", "*.log;*.txt;*.out"), 16);
    private final JCheckBox subfolders = new JCheckBox("Subfolders", PREFS.getBoolean("fs.sub", true));
    private final JCheckBox archives = new JCheckBox("Inside .gz / .zip", PREFS.getBoolean("fs.zip", true));
    private final JTextField find = new JTextField(28);
    private final JToggleButton matchCase = MainFrame.toggle("Aa", "Match case");
    private final JToggleButton wholeWord = MainFrame.toggle("W", "Whole word");
    private final JToggleButton regex = MainFrame.toggle(".*", "Regular expression");
    private final JButton searchButton = new JButton("Search");
    private final JButton stopButton = new JButton("Stop");
    private final JLabel status = new JLabel(" ");
    private final LogView results = new LogView();
    private final Set<FolderSearch.FileResult> collapsed = new HashSet<>();
    private final Runnable update;
    private volatile FolderSearch search;
    private List<FolderSearch.FileResult> files = List.of(); // EDT snapshot
    private int[] firstRow = new int[0];   // first row of each file in the list
    private int rowCount;
    private long maxLine;
    private long finishedMillis = -1;

    FolderSearchTab(Host host, Path startFolder, String text) {
        super(new BorderLayout());
        this.host = host;
        AtomicBoolean pending = new AtomicBoolean();
        update = () -> {
            if (pending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    pending.set(false);
                    refresh();
                });
            }
        };
        folder.setText(startFolder == null ? PREFS.get("fs.folder", System.getProperty("user.home"))
                : startFolder.toString());
        find.setText(text == null ? "" : text);
        add(buildForm(), BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);

        results.setSource(new Rows());
        results.setHighlightRules(List.of(
                new HighlightRule("More", Pattern.compile("^ {3}… "),
                        new Color(0x8A8F98), null, new Color(0x7C828A), null)));
        results.addListener(new LogView.Listener() {
            @Override
            public void foldToggled(LogView view, int row) {
                activate(row); // on a file's header: folds or opens it
            }

            @Override
            public void rowActivated(LogView view, int row) {
                activate(row);
            }
        });
        results.setPopupExtender((view, menu) -> extendPopup(menu));
    }

    private JComponent buildForm() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> browse());
        folder.putClientProperty("JTextField.placeholderText", "folder to search");
        names.putClientProperty("JTextField.placeholderText", "*.log;*.txt (empty = all files)");
        names.setToolTipText("<html>File name patterns, separated by ; or spaces.<br>"
                + "Rotated files count too: *.log finds app.log.1 and app.log.2026-10-21,<br>"
                + "and with archives on also app.log.2.gz and .log files inside any .zip.</html>");
        find.putClientProperty("JTextField.placeholderText", "text to find (Enter = Search)");
        find.putClientProperty("JTextField.showClearButton", true);
        JToolBar inField = new JToolBar();
        inField.setFloatable(false);
        inField.setOpaque(false);
        inField.add(matchCase);
        inField.add(wholeWord);
        inField.add(regex);
        find.putClientProperty("JTextField.trailingComponent", inField);
        find.addActionListener(e -> startSearch());
        folder.addActionListener(e -> startSearch());
        names.addActionListener(e -> startSearch());
        find.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stop");
        find.getActionMap().put("stop", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                stopSearch();
            }
        });
        searchButton.addActionListener(e -> startSearch());
        searchButton.putClientProperty("JButton.buttonType", "default");
        stopButton.addActionListener(e -> stopSearch());
        stopButton.setEnabled(false);

        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("Folder"), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(folder, c);
        c.gridx = 2;
        c.weightx = 0;
        p.add(browse, c);
        c.gridx = 3;
        p.add(new JLabel("Files"), c);
        c.gridx = 4;
        c.weightx = 0.3;
        p.add(names, c);
        c.gridx = 5;
        c.weightx = 0;
        p.add(subfolders, c);
        c.gridx = 6;
        p.add(archives, c);

        c.gridy = 1;
        c.gridx = 0;
        p.add(new JLabel("Find"), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(find, c);
        c.gridx = 2;
        c.weightx = 0;
        p.add(searchButton, c);
        c.gridx = 3;
        p.add(stopButton, c);
        c.gridx = 4;
        c.gridwidth = 3;
        p.add(status, c);
        return p;
    }

    private void browse() {
        if (MainFrame.isMac()) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true");
            FileDialog fd = new FileDialog((Frame) SwingUtilities.getWindowAncestor(this),
                    "Choose Folder", FileDialog.LOAD);
            fd.setDirectory(folder.getText());
            fd.setVisible(true);
            System.setProperty("apple.awt.fileDialogForDirectories", "false");
            if (fd.getFile() != null) {
                folder.setText(Path.of(fd.getDirectory(), fd.getFile()).toString());
            }
        } else {
            JFileChooser ch = new JFileChooser(folder.getText());
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                folder.setText(ch.getSelectedFile().toString());
            }
        }
    }

    // ---------------------------------------------------------------- searching

    String title() {
        String t = find.getText();
        return "Search: " + (t.isEmpty() ? "…" : t.length() > 20 ? t.substring(0, 20) + "…" : t);
    }

    FolderSearch search() {
        return search;
    }

    LogView resultsView() {
        return results;
    }

    void focusFind() {
        find.requestFocusInWindow();
        find.selectAll();
    }

    /** Sets the fields and starts searching (used by tests and the API). */
    void search(Path dir, String fileNames, SearchQuery q) {
        folder.setText(dir.toString());
        names.setText(fileNames);
        find.setText(q.text());
        matchCase.setSelected(q.matchCase());
        wholeWord.setSelected(q.wholeWord());
        regex.setSelected(q.regex());
        startSearch();
    }

    private void startSearch() {
        String text = find.getText();
        Path dir;
        try {
            dir = Path.of(folder.getText().trim());
        } catch (RuntimeException e) {
            dir = null;
        }
        if (dir == null || !Files.isDirectory(dir)) {
            folder.putClientProperty("JComponent.outline", "error");
            status.setText("Not a folder");
            return;
        }
        folder.putClientProperty("JComponent.outline", null);
        if (text.isEmpty()) {
            find.requestFocusInWindow();
            return;
        }
        SearchQuery q = new SearchQuery(text, matchCase.isSelected(), regex.isSelected(),
                wholeWord.isSelected());
        FolderSearch s;
        try {
            s = new FolderSearch(new FolderSearch.Options(dir, names.getText(),
                    subfolders.isSelected(), archives.isSelected(), q));
            find.putClientProperty("JComponent.outline", null);
        } catch (PatternSyntaxException e) {
            find.putClientProperty("JComponent.outline", "error");
            status.setText("Invalid regular expression: " + e.getDescription());
            return;
        }
        PREFS.put("fs.folder", dir.toString());
        PREFS.put("fs.names", names.getText());
        PREFS.putBoolean("fs.sub", subfolders.isSelected());
        PREFS.putBoolean("fs.zip", archives.isSelected());
        FolderSearch old = search;
        if (old != null) {
            old.close();
        }
        collapsed.clear();
        finishedMillis = -1;
        search = s;
        s.addListener(new FolderSearch.Listener() {
            @Override
            public void updated(FolderSearch fs) {
                update.run();
            }

            @Override
            public void finished(FolderSearch fs, long millis) {
                if (fs == search) {
                    finishedMillis = millis;
                }
                update.run();
            }
        });
        results.setMatchPattern(q.toPattern());
        stopButton.setEnabled(true);
        s.start();
        refresh();
        host.folderTabChanged(this);
    }

    void stopSearch() {
        FolderSearch s = search;
        if (s != null) {
            s.close();
        }
    }

    private void refresh() {
        FolderSearch s = search;
        if (s == null) {
            return;
        }
        files = s.results();
        firstRow = new int[files.size()];
        int row = 0;
        long max = 0;
        for (int i = 0; i < files.size(); i++) {
            FolderSearch.FileResult f = files.get(i);
            firstRow[i] = row;
            row += rowsOf(f);
            int kept = f.keptHits();
            if (kept > 0) {
                max = Math.max(max, f.hit(kept - 1).line());
            }
        }
        rowCount = row;
        maxLine = max;
        results.rowsChanged();
        stopButton.setEnabled(s.isRunning());
        status.setText(statusText());
        status.setToolTipText(s.errors().isEmpty() ? null
                : "<html>" + String.join("<br>", s.errors().stream().limit(20).toList()) + "</html>");
        host.folderTabChanged(this);
    }

    private int rowsOf(FolderSearch.FileResult f) {
        if (collapsed.contains(f)) {
            return 1;
        }
        int kept = f.keptHits();
        return 1 + kept + (f.hitCount() > kept ? 1 : 0);
    }

    String statusText() {
        FolderSearch s = search;
        if (s == null) {
            return " ";
        }
        String counts = String.format("%,d matching lines in %,d of %,d files",
                s.totalHits(), s.results().size(), s.filesFound());
        StringBuilder sb = new StringBuilder();
        if (s.isRunning()) {
            sb.append(String.format("Searching… %,d of %,d files, %s · ", s.filesDone(),
                    s.filesFound(), MainFrame.humanBytes(s.bytesDone())));
            sb.append(counts);
        } else {
            sb.append(counts);
            if (s.isCancelled()) {
                sb.append(" (stopped)");
            } else if (finishedMillis >= 0) {
                sb.append(String.format(" · %s in %,d ms", MainFrame.humanBytes(s.bytesDone()),
                        finishedMillis));
            }
        }
        if (s.filesSkipped() > 0) {
            sb.append(String.format(" · %,d skipped (binary or unreadable)", s.filesSkipped()));
        }
        return sb.toString();
    }

    double progress() {
        FolderSearch s = search;
        return s == null || s.filesFound() == 0 ? 0 : (double) s.filesDone() / s.filesFound();
    }

    boolean isSearching() {
        FolderSearch s = search;
        return s != null && s.isRunning();
    }

    // ---------------------------------------------------------------- rows

    /** File index of a row. */
    private int fileOf(int row) {
        int i = java.util.Arrays.binarySearch(firstRow, row);
        return i >= 0 ? i : -i - 2;
    }

    private String label(FolderSearch.FileResult f) {
        Path root = search == null ? null : search.options().root();
        String path = root != null && f.file().startsWith(root)
                ? root.relativize(f.file()).toString() : f.file().toString();
        if (path.isEmpty()) {
            path = f.file().getFileName().toString();
        }
        return f.entry() == null ? path : path + " › " + f.entry();
    }

    private final class Rows implements LogView.RowSource {
        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public long lineNumber(int row) {
            int fi = fileOf(row);
            if (fi < 0) {
                return -1;
            }
            int k = row - firstRow[fi] - 1;
            FolderSearch.FileResult f = files.get(fi);
            return k >= 0 && k < f.keptHits() ? f.hit(k).line() : -1;
        }

        @Override
        public long maxLineNumber() {
            return maxLine;
        }

        @Override
        public void texts(int first, int count, String[] out) {
            for (int i = 0; i < count; i++) {
                out[i] = text(first + i);
            }
        }

        @Override
        public Boolean folded(int row) {
            if (row >= rowCount) {
                return null;
            }
            int fi = fileOf(row);
            return row == firstRow[fi] ? collapsed.contains(files.get(fi)) : null;
        }

        private String text(int row) {
            if (row >= rowCount) {
                return null;
            }
            int fi = fileOf(row);
            FolderSearch.FileResult f = files.get(fi);
            int k = row - firstRow[fi] - 1;
            if (k < 0) {
                return label(f)
                        + String.format("   (%,d %s)", f.hitCount(), f.hitCount() == 1 ? "line" : "lines");
            }
            if (k < f.keptHits()) {
                return f.hit(k).text();
            }
            return String.format("   … %,d more matching lines; open the file to see all",
                    f.hitCount() - f.keptHits());
        }
    }

    private void activate(int row) {
        if (row < 0 || row >= rowCount) {
            return;
        }
        int fi = fileOf(row);
        FolderSearch.FileResult f = files.get(fi);
        int k = row - firstRow[fi] - 1;
        if (k < 0) {
            if (!collapsed.remove(f)) {
                collapsed.add(f);
            }
            refresh();
            results.goToRow(firstRow[fileOf(row)], false);
            return;
        }
        int kept = f.keptHits();
        long line = k < kept ? f.hit(k).line() : kept > 0 ? f.hit(kept - 1).line() : 0;
        host.openHit(f, line, search.options().query());
    }

    /** Folds (or unfolds) every file to its header row. */
    void collapseAll(boolean collapse) {
        if (collapse) {
            collapsed.addAll(files);
        } else {
            collapsed.clear();
        }
        refresh();
    }

    /** Opens the hit in the selected row (for tests and the Enter key). */
    void openSelected() {
        activate(results.selectedRow());
    }

    private void extendPopup(JPopupMenu menu) {
        int row = results.selectedRow();
        if (row < 0 || row >= rowCount) {
            return;
        }
        FolderSearch.FileResult f = files.get(fileOf(row));
        menu.addSeparator();
        JMenuItem open = new JMenuItem("Open File Here");
        open.addActionListener(e -> activate(row));
        menu.add(open);
        JMenuItem copy = new JMenuItem("Copy File Path");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(f.file().toString()), null));
        menu.add(copy);
        JMenuItem collapseAll = new JMenuItem("Collapse All Files");
        collapseAll.addActionListener(e -> collapseAll(true));
        menu.add(collapseAll);
        JMenuItem expandAll = new JMenuItem("Expand All Files");
        expandAll.addActionListener(e -> collapseAll(false));
        menu.add(expandAll);
    }

    void close() {
        stopSearch();
    }
}
