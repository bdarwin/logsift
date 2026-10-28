package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import logsift.core.diff.Merge3;
import logsift.core.diff.TextDiff;

/**
 * A three-way merge, as git asks for one: your version on the left, theirs on the right, and the
 * result in the middle, all on aligned rows. Changes made on one side only are taken already;
 * conflicts (both sides changed the same lines) are red until resolved: » takes yours, « takes
 * theirs (both, one after the other), a right-click offers the rest, a double-click edits the
 * result by hand. F7 goes from conflict to conflict. Save writes the result.
 */
final class MergeTab extends JPanel {

    interface Host {
        /** The merge was saved (true) or given up (false); the tab can close. */
        void done(MergeTab tab, boolean saved);

        default void titleChanged(MergeTab tab) {}
    }

    private final Host host;
    private final Merge3.Result merge;
    private final List<Merge3.Choice> choices = new ArrayList<>();
    private final List<List<String>> edited = new ArrayList<>();
    private final Path output;
    private final String[] names;
    private final TextCompareTab.Doc style; // the encoding and line ends to save with
    private final Deque<List<Merge3.Choice>> undoChoices = new ArrayDeque<>();
    private final Deque<List<List<String>>> undoEdited = new ArrayDeque<>();
    /** Per row: the chunk and the row's place in it. */
    private int[] rowChunk = new int[0];
    private int[] rowOffset = new int[0];
    private int[] resultLineAt = new int[0]; // per chunk: its first line in the result
    private final DiffPane[] panes = new DiffPane[3];
    private final JScrollPane[] scrolls = new JScrollPane[3];
    private final Arrows[] arrows = {new Arrows(0), new Arrows(1)};
    private final JPanel body = new JPanel(new Body());
    private final JLabel status = new JLabel(" ");
    private final JButton save = new JButton("Save and Finish", Icons.of("save"));
    private final JButton undo = new JButton(Icons.of("rotate-ccw"));
    private int current = -1;
    private boolean finished;

    /**
     * @param names  the names of yours, the result and theirs, as shown above the panes
     * @param output where Save writes the result
     */
    MergeTab(TextCompareTab.Doc base, TextCompareTab.Doc ours, TextCompareTab.Doc theirs, Path output, String[] names,
             Host host) {
        super(new BorderLayout());
        this.host = host;
        this.output = output;
        this.names = names;
        this.style = ours;
        merge = Merge3.merge(base.lines, ours.lines, theirs.lines, TextDiff.Options.DEFAULT);
        for (Merge3.Chunk c : merge.chunks()) {
            choices.add(merge.automatic(c));
            edited.add(null);
        }
        for (int s = 0; s < 3; s++) {
            int side = s;
            panes[s] = new DiffPane(rowsOf(s));
            panes[s].onSelect(() -> selected(side));
            panes[s].onOpen(r -> {
                if (side == 1) {
                    edit(rowChunk[r]);
                }
            });
            panes[s].addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    popup(e, side);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    popup(e, side);
                }
            });
            scrolls[s] = new JScrollPane(panes[s]);
            scrolls[s].setBorder(BorderFactory.createEmptyBorder());
            scrolls[s].getVerticalScrollBar().setUnitIncrement(panes[s].rowHeight());
            if (s < 2) {
                scrolls[s].setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
                scrolls[s].setWheelScrollingEnabled(false);
                scrolls[s].addMouseWheelListener(e -> {
                    for (java.awt.event.MouseWheelListener l : scrolls[2].getMouseWheelListeners()) {
                        l.mouseWheelMoved(e);
                    }
                });
            }
        }
        scrolls[0].getVerticalScrollBar().setModel(scrolls[2].getVerticalScrollBar().getModel());
        scrolls[1].getVerticalScrollBar().setModel(scrolls[2].getVerticalScrollBar().getModel());
        scrolls[2].getVerticalScrollBar().getModel().addChangeListener(e -> {
            arrows[0].repaint();
            arrows[1].repaint();
        });
        body.add(scrolls[0]);
        body.add(arrows[0]);
        body.add(scrolls[1]);
        body.add(arrows[1]);
        body.add(scrolls[2]);

        add(toolbar(), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout());
        center.add(heads(), BorderLayout.NORTH);
        center.add(body, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        rebuild();
        SwingUtilities.invokeLater(() -> nextConflict(true));
    }

    private JComponent toolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        JButton prev = new JButton(Icons.of("chevron-up"));
        prev.setToolTipText("Previous conflict (Shift+F7)");
        prev.addActionListener(e -> nextConflict(false));
        JButton next = new JButton(Icons.of("chevron-down"));
        next.setToolTipText("Next conflict (F7)");
        next.addActionListener(e -> nextConflict(true));
        bar.add(prev);
        bar.add(next);
        JButton yours = new JButton("Accept Yours");
        yours.setToolTipText("Every conflict resolved with your lines");
        yours.addActionListener(e -> all(Merge3.Choice.OURS));
        JButton theirs = new JButton("Accept Theirs");
        theirs.setToolTipText("Every conflict resolved with their lines");
        theirs.addActionListener(e -> all(Merge3.Choice.THEIRS));
        bar.add(yours);
        bar.add(theirs);
        undo.setToolTipText("Undo");
        undo.setEnabled(false);
        undo.addActionListener(e -> undo());
        bar.add(undo);
        bar.add(Box.createHorizontalStrut(10));
        save.putClientProperty("JButton.buttonType", "default");
        save.setToolTipText("Write the result to " + output.getFileName() + " and close");
        save.addActionListener(e -> save());
        bar.add(save);
        JButton abort = new JButton("Abort");
        abort.setToolTipText("Leave " + output.getFileName() + " as it was, and close");
        abort.addActionListener(e -> abort());
        bar.add(abort);
        bar.add(status);
        return bar;
    }

    private JComponent heads() {
        JPanel p = new JPanel(new GridLayout(1, 3, Arrows.WIDTH, 0));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 1, 0,
                Theme.uiColor("Separator.foreground", Color.LIGHT_GRAY)), BorderFactory.createEmptyBorder(3, 0, 3, 0)));
        Color[] colors = {new Color(0x2563EB), new Color(0x6B7280), new Color(0x16A34A)};
        String[] what = {"Yours", "Result", "Theirs"};
        for (int s = 0; s < 3; s++) {
            JLabel l = new JLabel("<html><b>" + what[s] + "</b>&nbsp;&nbsp;<font color='"
                    + String.format("#%06x", Theme.uiColor("Label.disabledForeground", Color.GRAY).getRGB() & 0xFFFFFF)
                    + "'>" + names[s].replace("&", "&amp;").replace("<", "&lt;") + "</font></html>");
            l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, colors[s]),
                    BorderFactory.createEmptyBorder(1, 6, 1, 4)));
            p.add(l);
        }
        return p;
    }

    /** Three panes of equal width, the arrows between them. */
    private final class Body implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component c) {}

        @Override
        public void removeLayoutComponent(Component c) {}

        @Override
        public Dimension preferredLayoutSize(Container p) {
            return new Dimension(900, 400);
        }

        @Override
        public Dimension minimumLayoutSize(Container p) {
            return new Dimension(200, 100);
        }

        @Override
        public void layoutContainer(Container p) {
            int w = p.getWidth();
            int h = p.getHeight();
            int third = (w - 2 * Arrows.WIDTH) / 3;
            scrolls[0].setBounds(0, 0, third, h);
            arrows[0].setBounds(third, 0, Arrows.WIDTH, h);
            scrolls[1].setBounds(third + Arrows.WIDTH, 0, third, h);
            arrows[1].setBounds(2 * third + Arrows.WIDTH, 0, Arrows.WIDTH, h);
            scrolls[2].setBounds(2 * third + 2 * Arrows.WIDTH, 0, w - 2 * third - 2 * Arrows.WIDTH, h);
        }
    }

    // ---------------------------------------------------------------- the rows

    /** The lines of a chunk on a side (0 yours, 1 the result, 2 theirs). */
    private List<String> lines(int chunk, int side) {
        Merge3.Chunk c = merge.chunks().get(chunk);
        return switch (side) {
            case 0 -> merge.ours().subList(c.o1(), c.o2());
            case 2 -> merge.theirs().subList(c.t1(), c.t2());
            default -> merge.lines(c, choices.get(chunk), edited.get(chunk));
        };
    }

    private void rebuild() {
        List<Integer> chunk = new ArrayList<>();
        List<Integer> offset = new ArrayList<>();
        resultLineAt = new int[merge.chunks().size()];
        int resultLine = 0;
        for (int i = 0; i < merge.chunks().size(); i++) {
            resultLineAt[i] = resultLine;
            int h = Math.max(lines(i, 0).size(), Math.max(lines(i, 1).size(), lines(i, 2).size()));
            if (merge.chunks().get(i).isChange()) {
                h = Math.max(h, 1); // a change that took lines away still shows as a row
            }
            for (int k = 0; k < h; k++) {
                chunk.add(i);
                offset.add(k);
            }
            resultLine += lines(i, 1).size();
        }
        rowChunk = chunk.stream().mapToInt(Integer::intValue).toArray();
        rowOffset = offset.stream().mapToInt(Integer::intValue).toArray();
        for (int s = 0; s < 3; s++) {
            panes[s].setRows(rowsOf(s));
        }
        long left = unresolved();
        status.setForeground(left > 0 ? Theme.errorMark() : Theme.uiColor("Label.disabledForeground", Color.GRAY));
        status.setText(left == 0 ? (merge.conflicts() == 0 ? "No conflicts: " + merge.changes()
                + " changes taken from both sides" : "All conflicts resolved") : String.format(Locale.ROOT,
                "%d of %d %s left", left, merge.conflicts(), merge.conflicts() == 1 ? "conflict" : "conflicts"));
        arrows[0].repaint();
        arrows[1].repaint();
        host.titleChanged(this);
    }

    /** Conflicts not resolved yet. */
    long unresolved() {
        long n = 0;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i) == Merge3.Choice.NONE) {
                n++;
            }
        }
        return n;
    }

    private DiffPane.Rows rowsOf(int side) {
        return new DiffPane.Rows() {
            @Override
            public int size() {
                return rowChunk.length;
            }

            @Override
            public int line(int row) {
                int c = rowChunk[row];
                int k = rowOffset[row];
                if (k >= lines(c, side).size()) {
                    return -1;
                }
                Merge3.Chunk ch = merge.chunks().get(c);
                return switch (side) {
                    case 0 -> ch.o1() + k;
                    case 2 -> ch.t1() + k;
                    default -> resultLineAt[c] + k;
                };
            }

            @Override
            public String text(int row) {
                List<String> l = lines(rowChunk[row], side);
                int k = rowOffset[row];
                return k < l.size() ? l.get(k) : null;
            }

            @Override
            public DiffPane.Kind kind(int row) {
                int c = rowChunk[row];
                Merge3.Chunk ch = merge.chunks().get(c);
                Merge3.Choice choice = choices.get(c);
                if (ch.kind() == Merge3.Kind.SAME) {
                    return DiffPane.Kind.SAME;
                }
                boolean conflict = ch.kind() == Merge3.Kind.CONFLICT;
                if (side == 1) {
                    return choice == Merge3.Choice.NONE ? DiffPane.Kind.CONFLICT : conflict ? DiffPane.Kind.ADDED
                            : DiffPane.Kind.CHANGED;
                }
                boolean changedHere = side == 0 ? ch.kind() != Merge3.Kind.THEIRS : ch.kind() != Merge3.Kind.OURS;
                if (!changedHere) {
                    return DiffPane.Kind.SAME;
                }
                return conflict ? DiffPane.Kind.CONFLICT : DiffPane.Kind.CHANGED;
            }

            @Override
            public List<TextDiff.Span> words(int row) {
                int c = rowChunk[row];
                if (merge.chunks().get(c).kind() != Merge3.Kind.CONFLICT || side == 1) {
                    return List.of();
                }
                List<String> o = lines(c, 0);
                List<String> t = lines(c, 2);
                int k = rowOffset[row];
                if (k >= o.size() || k >= t.size()) {
                    return List.of();
                }
                return TextDiff.words(o.get(k), t.get(k), TextDiff.Options.DEFAULT).get(side == 0 ? 0 : 1);
            }

            @Override
            public boolean blockStart(int row) {
                int c = rowChunk[row];
                return merge.chunks().get(c).isChange() && rowOffset[row] == 0;
            }

            @Override
            public boolean blockEnd(int row) {
                int c = rowChunk[row];
                return merge.chunks().get(c).isChange() && (row + 1 >= rowChunk.length || rowChunk[row + 1] != c);
            }
        };
    }

    private int firstRow(int chunk) {
        for (int r = 0; r < rowChunk.length; r++) {
            if (rowChunk[r] == chunk) {
                return r;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- resolving

    private void remember() {
        undoChoices.push(new ArrayList<>(choices));
        undoEdited.push(new ArrayList<>(edited));
        undo.setEnabled(true);
    }

    /** Takes one side's change for a chunk (0 yours, 2 theirs); taking both puts them one after the other. */
    void take(int chunk, int side) {
        Merge3.Choice now = choices.get(chunk);
        Merge3.Choice want = side == 0 ? Merge3.Choice.OURS : Merge3.Choice.THEIRS;
        Merge3.Choice next;
        if (merge.chunks().get(chunk).kind() != Merge3.Kind.CONFLICT) {
            next = want;
        } else if (now == Merge3.Choice.THEIRS && side == 0) {
            next = Merge3.Choice.THEIRS_THEN_OURS;
        } else if (now == Merge3.Choice.OURS && side == 2) {
            next = Merge3.Choice.OURS_THEN_THEIRS;
        } else {
            next = want;
        }
        choose(chunk, next);
    }

    void choose(int chunk, Merge3.Choice c) {
        remember();
        choices.set(chunk, c);
        rebuild();
    }

    private void all(Merge3.Choice c) {
        remember();
        for (int i = 0; i < choices.size(); i++) {
            if (merge.chunks().get(i).kind() == Merge3.Kind.CONFLICT) {
                choices.set(i, c);
            }
        }
        rebuild();
    }

    private void undo() {
        if (undoChoices.isEmpty()) {
            return;
        }
        List<Merge3.Choice> c = undoChoices.pop();
        List<List<String>> e = undoEdited.pop();
        Collections.copy(choices, c);
        Collections.copy(edited, e);
        undo.setEnabled(!undoChoices.isEmpty());
        rebuild();
    }

    /** The result's lines of a chunk, typed by hand. */
    private void edit(int chunk) {
        if (!merge.chunks().get(chunk).isChange()) {
            return;
        }
        JTextArea area = new JTextArea(String.join("\n", lines(chunk, 1)), 12, 70);
        area.setFont(DiffPane.monoFont());
        JScrollPane sp = new JScrollPane(area);
        if (JOptionPane.showConfirmDialog(this, sp, "Edit the Result", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            setEdited(chunk, area.getText());
        }
    }

    void setEdited(int chunk, String text) {
        remember();
        edited.set(chunk, text.isEmpty() ? List.of() : List.of(text.split("\r?\n", -1)));
        choices.set(chunk, Merge3.Choice.EDITED);
        rebuild();
    }

    private void popup(MouseEvent e, int side) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = panes[side].rowAt(e.getY());
        if (row < 0 || !merge.chunks().get(rowChunk[row]).isChange()) {
            return;
        }
        int c = rowChunk[row];
        JPopupMenu m = new JPopupMenu();
        String[][] items = {{"Accept Yours", "OURS"}, {"Accept Theirs", "THEIRS"},
            {"Yours, Then Theirs", "OURS_THEN_THEIRS"}, {"Theirs, Then Yours", "THEIRS_THEN_OURS"},
            {"Keep the Original (Base)", "BASE"}};
        for (String[] it : items) {
            JMenuItem mi = new JMenuItem(it[0]);
            mi.addActionListener(a -> choose(c, Merge3.Choice.valueOf(it[1])));
            m.add(mi);
        }
        m.addSeparator();
        JMenuItem ed = new JMenuItem("Edit the Result…");
        ed.addActionListener(a -> edit(c));
        m.add(ed);
        m.show(panes[side], e.getX(), e.getY());
    }

    // ---------------------------------------------------------------- moving around

    /** F7 / Shift+F7: the next (or previous) conflict, or change when no conflict is left. */
    void nextConflict(boolean forward) {
        int n = merge.chunks().size();
        boolean conflictsLeft = unresolved() > 0;
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod((current < 0 ? (forward ? -1 : 0) : current) + (forward ? k : -k), n);
            Merge3.Chunk c = merge.chunks().get(i);
            boolean want = conflictsLeft ? choices.get(i) == Merge3.Choice.NONE : c.isChange();
            if (want) {
                goTo(i);
                return;
            }
        }
    }

    private void goTo(int chunk) {
        current = chunk;
        int row = firstRow(chunk);
        int end = row;
        while (end + 1 < rowChunk.length && rowChunk[end + 1] == chunk) {
            end++;
        }
        for (DiffPane p : panes) {
            p.select(row, end);
        }
        int rh = panes[2].rowHeight();
        scrolls[2].getVerticalScrollBar().setValue(Math.max(0, row * rh - scrolls[2].getViewport().getHeight() / 3));
    }

    private void selected(int side) {
        int[] sel = panes[side].selection();
        for (int s = 0; s < 3; s++) {
            if (s != side) {
                panes[s].select(sel[0], sel[1]);
            }
        }
        current = sel[0] >= 0 && sel[0] < rowChunk.length ? rowChunk[sel[0]] : -1;
    }

    // ---------------------------------------------------------------- saving

    /** The result as it is now (conflicts left unresolved keep git's markers). */
    List<String> result() {
        return merge.withMarkers(choices, edited, "yours", "theirs");
    }

    void save() {
        if (unresolved() > 0 && JOptionPane.showConfirmDialog(this, unresolved() + " conflicts aren't resolved."
                + " Save with conflict markers (<<<<<<< ======= >>>>>>>) where they are?", "Save the Merge",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (!saveTo(output)) {
            return;
        }
        finished = true;
        host.done(this, true);
    }

    /** Writes the result; false (and says why) if it couldn't. */
    boolean saveTo(Path p) {
        List<String> lines = result();
        String text = String.join(style.eol, lines) + (style.endsWithEol && !lines.isEmpty() ? style.eol : "");
        try {
            Files.write(p, text.getBytes(style.charset));
            return true;
        } catch (IOException e) {
            status.setText("Couldn't save: " + e.getMessage());
            status.setForeground(Theme.errorMark());
            return false;
        }
    }

    void abort() {
        finished = true;
        host.done(this, false);
    }

    /** Whether Save or Abort was clicked. */
    boolean finished() {
        return finished;
    }

    String title() {
        long left = unresolved();
        return "Merge: " + output.getFileName() + (left > 0 ? " (" + left + (left == 1 ? " conflict)" : " conflicts)") : "");
    }

    Merge3.Result mergeForTest() {
        return merge;
    }

    List<Merge3.Choice> choicesForTest() {
        return List.copyOf(choices);
    }

    String statusForTest() {
        return status.getText();
    }

    int currentForTest() {
        return current;
    }

    void undoForTest() {
        undo();
    }

    // ---------------------------------------------------------------- the arrows

    /** Between two panes: » (take yours) or « (take theirs) at each change. */
    private final class Arrows extends JComponent {
        static final int WIDTH = 34;
        private final int gap; // 0: between yours and the result, 1: between the result and theirs
        private int hover = -1;

        Arrows(int gap) {
            this.gap = gap;
            setOpaque(true);
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int c = hit(e.getY());
                    if (c != hover) {
                        hover = c;
                        setCursor(Cursor.getPredefinedCursor(c >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        setToolTipText(c < 0 ? null : gap == 0 ? "Take your change (»)" : "Take their change («)");
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int c = hit(e.getY());
                    if (c >= 0) {
                        take(c, gap == 0 ? 0 : 2);
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        }

        private int offset() {
            return scrolls[2].getViewport().getViewPosition().y;
        }

        /** The chunk whose arrow is at y, or -1. */
        int hit(int y) {
            int rh = panes[2].rowHeight();
            int row = (y + offset()) / rh;
            if (row < 0 || row >= rowChunk.length || rowOffset[row] != 0) {
                return -1;
            }
            return offers(rowChunk[row]) ? rowChunk[row] : -1;
        }

        /** Whether this side changed the chunk, so its arrow is shown. */
        private boolean offers(int chunk) {
            Merge3.Kind k = merge.chunks().get(chunk).kind();
            return gap == 0 ? k == Merge3.Kind.OURS || k == Merge3.Kind.CONFLICT || k == Merge3.Kind.BOTH_SAME
                    : k == Merge3.Kind.THEIRS || k == Merge3.Kind.CONFLICT;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.gutterBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            int rh = panes[2].rowHeight();
            int off = offset();
            int first = Math.max(0, off / rh);
            int last = Math.min(rowChunk.length - 1, (off + getHeight()) / rh);
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            FontMetrics fm = g.getFontMetrics();
            for (int r = first; r <= last; r++) {
                int c = rowChunk[r];
                Merge3.Chunk ch = merge.chunks().get(c);
                if (!ch.isChange()) {
                    continue;
                }
                int y = r * rh - off;
                DiffPane.Kind band = ch.kind() == Merge3.Kind.CONFLICT && choices.get(c) == Merge3.Choice.NONE
                        ? DiffPane.Kind.CONFLICT : DiffPane.Kind.CHANGED;
                g.setColor(DiffPane.background(band, false));
                g.fillRect(0, y, getWidth(), rh);
                if (rowOffset[r] == 0 && offers(c)) {
                    String a = gap == 0 ? "»" : "«";
                    int x = (getWidth() - fm.stringWidth(a)) / 2;
                    boolean hot = c == hover;
                    if (hot) {
                        g.setColor(DiffPane.edge(band));
                        g.fillRoundRect(x - 4, y + 1, fm.stringWidth(a) + 8, rh - 2, 6, 6);
                    }
                    g.setColor(hot ? Color.WHITE : Theme.pick(new Color(0x3B5BA5), new Color(0x9DB7F0)));
                    g.drawString(a, x, y + fm.getAscent() + 1);
                }
            }
            g.dispose();
        }
    }
}
