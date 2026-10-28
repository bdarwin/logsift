package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import logsift.core.FileBrowse;
import logsift.core.diff.CsvFile;

/**
 * The Compare tab: a file browser on each side (folders, zips, and zips inside zips) to choose
 * the two files, and Compare. The comparison then takes the tab; its Choose Files shows the
 * browsers again in the table's place. CSV and TSV files are compared as tables.
 */
final class FileCompareTab extends JPanel {

    interface Host {
        /** Opens a file (or selects its tab) at a line (0-based). */
        void openLine(Path file, long line);

        /** How a chosen file is read (a file in a zip, or a .gz, unpacked first); on a background thread. */
        CsvCompareTab.Side sideFor(Path p) throws IOException;

        /** The tab's title changed. */
        void titleChanged(FileCompareTab tab);

        /** Why two files couldn't be compared (also shown in the tab). */
        default void note(String text) {}

        /** Two files of a folder comparison, compared in a tab of their own. */
        default void compareFiles(Path left, Path right) {}

        /** A file of a folder comparison, opened as a log. */
        default void openFile(Path file) {}
    }

    private final Host host;
    private final FileNavigator[] nav = {
        new FileNavigator("Left (older)", new Color(0xDC2626)),
        new FileNavigator("Right (newer)", new Color(0x16A34A))
    };
    /** The two browsers: in the tab before the first comparison, then in the comparison's place for the table. */
    private final JPanel sides = new JPanel(new GridLayout(1, 2, CsvCompareTab.DRAWER_GAP, 0));
    private final JButton compare = new JButton("Compare", Icons.of("git-compare"));
    private final JButton swap = new JButton("⇄ Swap");
    private final JPanel bar;
    private final JLabel status = new JLabel(" ");
    private CsvCompareTab csv;
    private TextCompareTab text;
    private FolderCompareTab folder;
    private int generation;
    private boolean busy;

    FileCompareTab(Path left, Path right, Path near, Host host) {
        super(new BorderLayout());
        this.host = host;

        sides.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        sides.add(nav[0]);
        sides.add(nav[1]);
        for (FileNavigator n : nav) {
            n.onSelect(i -> updateButtons());
            n.onOpen(i -> {
                if (compare.isEnabled()) {
                    runCompare();
                }
            });
        }

        bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        swap.setToolTipText("Swap left and right");
        swap.addActionListener(e -> swapSides());
        compare.setToolTipText("Compare the file chosen on the left with the one on the right");
        compare.addActionListener(e -> runCompare());
        compare.putClientProperty("JButton.buttonType", "default");
        bar.add(swap);
        bar.add(compare);
        bar.add(status);
        add(bar, BorderLayout.NORTH);

        JLabel hint = new JLabel("Choose a file on each side and click Compare. CSV and TSV files are compared as"
                + " tables: rows are matched by key columns, in any order. Files can be dropped on a side too.");
        hint.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        hint.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        JPanel first = new JPanel(new BorderLayout());
        JPanel pad = new JPanel(new BorderLayout());
        pad.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        pad.add(sides, BorderLayout.CENTER);
        first.add(pad, BorderLayout.CENTER);
        first.add(hint, BorderLayout.SOUTH);
        add(first, BorderLayout.CENTER);

        FileDrop.install(this, dropTarget, (JComponent) nav[0].treeForTest().getParent().getParent(),
                (JComponent) nav[1].treeForTest().getParent().getParent(), nav[0].treeForTest(), nav[1].treeForTest(),
                nav[0].pathFieldForTest(), nav[1].pathFieldForTest());

        Path start = near != null ? near : left != null ? left.getParent() : null;
        if (left != null) {
            nav[0].reveal(left);
        } else if (start != null) {
            nav[0].reveal(start);
        }
        if (right != null) {
            nav[1].reveal(right);
        } else if (start != null) {
            nav[1].reveal(start);
        }
        updateButtons();
        if (left != null && right != null) {
            compareWhenRevealed(left, right);
        }
    }

    /** Where files dropped on the tab go: two to both sides, one to the half it is dropped on. */
    private final FileDrop.Target dropTarget = new FileDrop.Target() {
        @Override
        public int sideAt(Point p, int count) {
            return count >= 2 ? FileDrop.BOTH : p.x < getWidth() / 2 ? 0 : 1;
        }

        @Override
        public int sideAt(java.awt.Component over, Point p, int count) {
            if (count < 2) {
                for (int s = 0; s < 2; s++) {
                    if (over != null && SwingUtilities.isDescendingFrom(over, nav[s])) {
                        return s;
                    }
                }
            }
            return sideAt(p, count);
        }

        @Override
        public void hover(int side) {}

        @Override
        public void dropped(List<Path> dropped, int side) {
            drop(dropped, side);
        }
    };

    /** Files dropped: shown and selected on their side (two: both sides). */
    void drop(List<Path> dropped, int side) {
        if (dropped.size() >= 2) {
            nav[0].reveal(dropped.get(0));
            nav[1].reveal(dropped.get(1));
        } else if (!dropped.isEmpty()) {
            nav[side == FileDrop.BOTH ? 0 : side].reveal(dropped.get(0));
        }
    }

    /** Shows the file browsers (before the first comparison they always are). */
    private void showChooser(boolean on) {
        if (csv != null) {
            csv.showDrawer(on);
        }
        if (text != null) {
            text.showDrawer(on);
        }
        if (folder != null) {
            folder.showDrawer(on);
        }
    }

    /** Whether a comparison is shown (CSV, text or folders). */
    private boolean comparing() {
        return csv != null || text != null || folder != null;
    }

    /** The files (or folders) compared now, or null. */
    private Path compared(int side) {
        return csv != null ? csv.sideForTest(side).open() : text != null ? text.sideForTest(side).open()
                : folder != null ? folder.rootForTest(side) : null;
    }

    /** Whether an item is a folder or zip on disk (not inside a zip), so it can be compared as a folder. */
    private static boolean container(FileBrowse.Item i) {
        return i != null && !i.isFile() && !i.inZip();
    }

    private void scrollBrowsers() {
        SwingUtilities.invokeLater(() -> {
            for (FileNavigator n : nav) {
                n.scrollToSelected();
            }
        });
    }

    private void updateButtons() {
        FileBrowse.Item a = nav[0].selected();
        FileBrowse.Item b = nav[1].selected();
        boolean folders = container(a) && container(b);
        boolean ok = a != null && b != null && (a.isFile() && b.isFile() || folders);
        compare.setEnabled(ok && !busy);
        boolean other = ok && comparing() && !chosenAreCompared();
        // new files chosen (or none compared yet): Compare stands out
        CsvCompareTab.highlightCompare(compare, ok && !busy && (!comparing() || other));
        if (!busy) {
            status.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            if (a != null && b != null && !a.isFile() && !b.isFile() && !folders) {
                status.setText("A folder inside a zip: choose the zip itself, or a file in it");
            } else if (!ok) {
                status.setText(a == null || !a.isFile() ? b == null || !b.isFile() ? "Choose a file on each side"
                        : "Choose the left file" : "Choose the right file");
            } else {
                status.setText(a.name() + " ↔ " + b.name());
            }
        }
        String chosen = !ok ? status.getText() : other ? a.name() + " ↔ " + b.name() + ": press Compare"
                : "These are compared: choose others";
        if (csv != null) {
            csv.filesChosen(other, chosen);
        }
        if (text != null) {
            text.filesChosen(other, chosen);
        }
        if (folder != null) {
            folder.filesChosen(other, chosen);
        }
    }

    /** Whether the files chosen are the ones compared. */
    private boolean chosenAreCompared() {
        FileBrowse.Item a = nav[0].selected();
        FileBrowse.Item b = nav[1].selected();
        return comparing() && a != null && b != null && a.path().equals(compared(0)) && b.path().equals(compared(1));
    }

    private void swapSides() {
        FileBrowse.Item a = nav[0].selected();
        FileBrowse.Item b = nav[1].selected();
        if (a != null) {
            nav[1].reveal(a.path());
        }
        if (b != null) {
            nav[0].reveal(b.path());
        }
    }

    /** Compares the two files once both sides show them (a reveal reads the folders first). */
    private void compareWhenRevealed(Path left, Path right) {
        int gen = generation;
        javax.swing.Timer t = new javax.swing.Timer(50, null);
        long until = System.currentTimeMillis() + 30_000;
        t.addActionListener(e -> {
            if (gen != generation || System.currentTimeMillis() > until) {
                t.stop();
                return;
            }
            if (selectedIs(0, left) && selectedIs(1, right)) {
                t.stop();
                runCompare();
            } else if (!nav[0].isLoadingForTest() && !nav[1].isLoadingForTest()) {
                // the browsers are done and don't show the files (one isn't there): compare what was asked
                t.stop();
                compare(left, right);
            }
        });
        t.start();
    }

    private boolean selectedIs(int side, Path p) {
        FileBrowse.Item i = nav[side].selected();
        return i != null && i.path().equals(p.toAbsolutePath().normalize());
    }

    /** Compare: reads the two files chosen, then shows the comparison below. */
    void runCompare() {
        FileBrowse.Item a = nav[0].selected();
        FileBrowse.Item b = nav[1].selected();
        boolean folders = container(a) && container(b);
        if (a == null || b == null || !(a.isFile() && b.isFile() || folders)) {
            return;
        }
        if (comparing() && chosenAreCompared()) {
            showChooser(false);
            return;
        }
        if (folders) {
            showFolders(a.path(), b.path());
            return;
        }
        compare(a.path(), b.path());
    }

    private void compare(Path left, Path right) {
        compare(left, right, false);
    }

    /** The most a text compared may have (both sides are read into memory). */
    static final long MAX_TEXT_BYTES = 256L << 20;

    /**
     * Reads the two files in the background, then compares them: CSV and TSV files as tables
     * (unless {@code asText}), others as text.
     */
    private void compare(Path left, Path right, boolean asText) {
        int gen = ++generation;
        busy = true;
        compare.setEnabled(false);
        status.setForeground(Theme.uiColor("Label.foreground", Color.BLACK));
        status.setText("Reading the files…");
        if (csv != null) {
            csv.filesChosen(false, "Reading the files…");
        }
        Thread t = new Thread(() -> {
            List<CsvCompareTab.Side> got = new ArrayList<>();
            List<CsvFile> opened = new ArrayList<>();
            List<TextCompareTab.Doc> docs = new ArrayList<>();
            String error = null;
            try {
                for (Path p : List.of(left, right)) {
                    got.add(host.sideFor(p));
                }
                boolean table = !asText;
                if (table) {
                    try {
                        for (CsvCompareTab.Side s : got) {
                            opened.add(CsvFile.open(s.read(), s.name()));
                        }
                    } catch (CsvFile.NotCsvException e) {
                        opened.forEach(CsvFile::close);
                        opened.clear();
                        table = false;
                    }
                }
                if (!table) {
                    for (CsvCompareTab.Side s : got) {
                        long size = java.nio.file.Files.size(s.read());
                        if (size > MAX_TEXT_BYTES) {
                            throw new IOException(s.name() + " is " + MainFrame.humanBytes(size)
                                    + ": text is compared up to " + MainFrame.humanBytes(MAX_TEXT_BYTES));
                        }
                        docs.add(TextCompareTab.Doc.read(s));
                    }
                }
            } catch (IOException | RuntimeException e) {
                opened.forEach(CsvFile::close);
                error = "binary".equals(e.getMessage()) ? binaryNote(got) : "Couldn't compare: " + e.getMessage();
            }
            String err = error;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    opened.forEach(CsvFile::close);
                    return;
                }
                busy = false;
                if (err != null) {
                    updateButtons();
                    status.setForeground(Theme.errorMark());
                    status.setText(err.replace("\n\n", " ").replace("\n", " "));
                    status.setToolTipText("<html>" + err.replace("&", "&amp;").replace("<", "&lt;")
                            .replace("\n", "<br>") + "</html>");
                    if (csv != null) {
                        csv.filesChosen(true, err);
                    }
                    if (text != null) {
                        text.filesChosen(true, err);
                    }
                    host.note(err);
                    return;
                }
                status.setToolTipText(null);
                if (!opened.isEmpty() && csv != null) {
                    // another pair of tables: the comparison reads them, keeping its keys and options
                    opened.forEach(CsvFile::close);
                    csv.showDrawer(false);
                    csv.drop(List.of(left, right), FileDrop.BOTH);
                } else if (!opened.isEmpty()) {
                    show(got.get(0), got.get(1), opened.get(0), opened.get(1));
                } else {
                    showText(docs.get(0), docs.get(1));
                }
                updateButtons();
            });
        }, "logsift-compare-open");
        t.setDaemon(true);
        t.start();
    }

    /** Two files that aren't text: whether they are the same, byte for byte. */
    private static String binaryNote(List<CsvCompareTab.Side> sides) {
        if (sides.size() < 2) {
            return "Binary files aren't compared";
        }
        try {
            long a = java.nio.file.Files.size(sides.get(0).read());
            long b = java.nio.file.Files.size(sides.get(1).read());
            boolean same = a == b && java.nio.file.Files.mismatch(sides.get(0).read(), sides.get(1).read()) < 0;
            return same ? "The files are the same (binary files, byte for byte)"
                    : "Binary files: they differ (" + MainFrame.humanBytes(a) + " and " + MainFrame.humanBytes(b)
                            + "); only text is compared line by line";
        } catch (IOException e) {
            return "Binary files aren't compared";
        }
    }

    /** Shows two texts compared, in place of what was shown. */
    private void showText(TextCompareTab.Doc a, TextCompareTab.Doc b) {
        closeShown();
        text = new TextCompareTab(a, b, textHost);
        removeAll();
        add(text, BorderLayout.CENTER);
        revalidate();
        repaint();
        host.titleChanged(this);
    }

    private void closeShown() {
        if (csv != null) {
            csv.close();
            csv = null;
        }
        text = null;
        folder = null;
    }

    /** Shows two folders (or zips) compared. */
    private void showFolders(Path left, Path right) {
        generation++;
        busy = false;
        closeShown();
        folder = new FolderCompareTab(left, right, folderHost);
        removeAll();
        add(folder, BorderLayout.CENTER);
        revalidate();
        repaint();
        host.titleChanged(this);
        updateButtons();
    }

    private final FolderCompareTab.Host folderHost = new FolderCompareTab.Host() {
        @Override
        public void compareFiles(Path left, Path right) {
            host.compareFiles(left, right);
        }

        @Override
        public void openFile(Path file) {
            host.openFile(file);
        }

        @Override
        public void titleChanged(FolderCompareTab tab) {
            host.titleChanged(FileCompareTab.this);
        }

        @Override
        public JComponent drawer() {
            return sides;
        }

        @Override
        public void drawerShown(boolean on) {
            if (on) {
                scrollBrowsers();
                updateButtons();
            }
        }

        @Override
        public boolean compareChosen() {
            if (chosenAreCompared()) {
                return false;
            }
            runCompare();
            return true;
        }
    };

    /** The folder comparison shown, or null. */
    FolderCompareTab folder() {
        return folder;
    }

    private final TextCompareTab.Host textHost = new TextCompareTab.Host() {
        @Override
        public void openLine(Path file, long line) {
            host.openLine(file, line);
        }

        @Override
        public void titleChanged(TextCompareTab tab) {
            for (int s = 0; s < 2; s++) {
                Path p = tab.sideForTest(s).open();
                if (!selectedIs(s, p)) {
                    nav[s].reveal(p);
                }
            }
            host.titleChanged(FileCompareTab.this);
        }

        @Override
        public JComponent drawer() {
            return sides;
        }

        @Override
        public void drawerShown(boolean on) {
            if (on) {
                scrollBrowsers();
                updateButtons();
            }
        }

        @Override
        public boolean compareChosen() {
            FileBrowse.Item a = nav[0].selected();
            FileBrowse.Item b = nav[1].selected();
            if (a == null || b == null || !a.isFile() || !b.isFile() || chosenAreCompared()) {
                return false;
            }
            runCompare();
            return true;
        }

        @Override
        public boolean asTable() {
            if (text == null) {
                return false;
            }
            compare(text.sideForTest(0).open(), text.sideForTest(1).open(), false);
            return true;
        }
    };

    private void show(CsvCompareTab.Side a, CsvCompareTab.Side b, CsvFile fa, CsvFile fb) {
        closeShown();
        csv = new CsvCompareTab(a, b, fa, fb, csvHost);
        removeAll();
        add(csv, BorderLayout.CENTER);
        revalidate();
        repaint();
        host.titleChanged(this);
    }

    private final CsvCompareTab.Host csvHost = new CsvCompareTab.Host() {
        @Override
        public void openLine(Path file, long line) {
            host.openLine(file, line);
        }

        @Override
        public void titleChanged(CsvCompareTab tab) {
            // files dropped on the comparison (or swapped there): the browsers show them too
            for (int s = 0; s < 2; s++) {
                Path p = tab.sideForTest(s).open();
                if (!selectedIs(s, p)) {
                    nav[s].reveal(p);
                }
            }
            host.titleChanged(FileCompareTab.this);
        }

        @Override
        public CsvCompareTab.Side sideFor(Path dropped) throws IOException {
            return host.sideFor(dropped);
        }

        @Override
        public JComponent drawer() {
            return sides;
        }

        @Override
        public void drawerShown(boolean on) {
            if (on) {
                scrollBrowsers();
                updateButtons();
            }
        }

        @Override
        public boolean compareChosen() {
            FileBrowse.Item a = nav[0].selected();
            FileBrowse.Item b = nav[1].selected();
            if (a == null || b == null || !a.isFile() || !b.isFile() || chosenAreCompared()) {
                return false;
            }
            runCompare();
            return true;
        }

        @Override
        public void asText() {
            if (csv != null) {
                compare(csv.sideForTest(0).open(), csv.sideForTest(1).open(), true);
            }
        }
    };

    String title() {
        return csv != null ? csv.title() : text != null ? text.title() : folder != null ? folder.title() : "Compare";
    }

    String tooltip() {
        return comparing() ? compared(0) + "  ↔  " + compared(1) : "Choose two files to compare";
    }

    String statusText() {
        return csv != null ? csv.statusText() : text != null ? text.statusText() : folder != null ? folder.statusText()
                : status.getText();
    }

    /** The comparison shown, or null before the first one. */
    CsvCompareTab csv() {
        return csv;
    }

    /** The text comparison shown, or null. */
    TextCompareTab text() {
        return text;
    }

    boolean isBusy() {
        return busy || csv != null && csv.isBusy();
    }

    void close() {
        generation++;
        for (FileNavigator n : nav) {
            n.close();
        }
        if (csv != null) {
            csv.close();
        }
    }

    FileNavigator navigatorForTest(int side) {
        return nav[side];
    }

    JButton compareButtonForTest() {
        return compare;
    }

    /** Choose Files in the comparison's row (after the first comparison). */
    javax.swing.AbstractButton filesButtonForTest() {
        return csv != null ? csv.filesButtonForTest() : text != null ? text.filesButtonForTest()
                : folder != null ? folder.filesButtonForTest() : null;
    }

    /** Whether Compare stands out: the tab's before the first comparison, then the comparison's. */
    boolean compareHighlightedForTest() {
        return csv != null ? csv.compareHighlightedForTest() : text != null ? text.compareHighlightedForTest()
                : folder != null ? folder.compareHighlightedForTest() : CsvCompareTab.isHighlighted(compare);
    }

    boolean chooserShownForTest() {
        return csv != null ? csv.isDrawerOpen() : text != null ? text.isDrawerOpen()
                : folder == null || folder.isDrawerOpen();
    }

    String chooserStatusForTest() {
        return status.getText();
    }
}
