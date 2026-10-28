package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * File → Compare Files…: the two files to compare, typed, browsed for or dropped (one file on a
 * field fills it; two files fill both).
 */
final class ComparePicker extends JPanel {
    final JTextField left = new JTextField(42);
    final JTextField right = new JTextField(42);
    private int hover = -1;

    ComparePicker(Path a, Path b, String lastDir) {
        super(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JLabel what = new JLabel("<html>Compare two files. CSV and TSV files are compared as tables: rows are"
                + " matched<br>by a key you choose (Logsift suggests one), whatever their order."
                + "<br><span style='color:gray'>Drop files here: one on a field, or two at once.</span></html>");
        add(what, BorderLayout.NORTH);
        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        row(rows, c, 0, "Left (older):", left, lastDir);
        row(rows, c, 1, "Right (newer):", right, lastDir);
        JButton swap = new JButton("Swap ⇅");
        swap.setToolTipText("Swap the left and right file");
        swap.addActionListener(e -> {
            String t = left.getText();
            left.setText(right.getText());
            right.setText(t);
        });
        c.gridx = 1;
        c.gridy = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        rows.add(swap, c);
        add(rows, BorderLayout.CENTER);
        if (a != null) {
            left.setText(a.toString());
        }
        if (b != null) {
            right.setText(b.toString());
        }
        FileDrop.install(this, new FileDrop.Target() {
            @Override
            public int sideAt(Point p, int count) {
                if (count >= 2) {
                    return FileDrop.BOTH;
                }
                Rectangle r = javax.swing.SwingUtilities.convertRectangle(right.getParent(), right.getBounds(),
                        ComparePicker.this);
                return p.y >= r.y - 4 ? 1 : 0;
            }

            @Override
            public int sideAt(java.awt.Component over, Point p, int count) {
                if (count < 2 && over == left) {
                    return 0;
                }
                if (count < 2 && over == right) {
                    return 1;
                }
                return sideAt(p, count);
            }

            @Override
            public void hover(int side) {
                if (side != hover) {
                    hover = side;
                    repaint();
                }
            }

            @Override
            public void dropped(List<Path> files, int side) {
                fill(files, side);
            }
        }, left, right, rows);
    }

    private static void row(JPanel p, GridBagConstraints c, int y, String label, JTextField field, String lastDir) {
        c.gridy = y;
        c.gridx = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        field.putClientProperty("JTextField.placeholderText", "a file, or drop one here");
        p.add(field, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(p);
            String title = "Choose a File to Compare";
            FileDialog fd = w instanceof java.awt.Dialog d ? new FileDialog(d, title, FileDialog.LOAD)
                    : new FileDialog(w instanceof Frame f ? f : null, title, FileDialog.LOAD);
            String dir = field.getText().isBlank() ? lastDir : parentOf(field.getText());
            if (dir != null) {
                fd.setDirectory(dir);
            }
            fd.setVisible(true);
            if (fd.getFile() != null) {
                field.setText(Path.of(fd.getDirectory(), fd.getFile()).toString());
            }
        });
        p.add(browse, c);
    }

    private static String parentOf(String file) {
        try {
            Path p = Path.of(file.strip()).getParent();
            return p == null ? null : p.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Dropped files: two (or more: the first two) fill both fields, one fills {@code side}. */
    void fill(List<Path> files, int side) {
        if (files.size() >= 2) {
            left.setText(files.get(0).toString());
            right.setText(files.get(1).toString());
        } else if (side == 1) {
            right.setText(files.get(0).toString());
        } else {
            left.setText(files.get(0).toString());
        }
    }

    /** The two files, or null if a field is empty. */
    Path[] files() {
        if (left.getText().isBlank() || right.getText().isBlank()) {
            return null;
        }
        return new Path[] {Path.of(left.getText().strip()), Path.of(right.getText().strip())};
    }

    int hoverForTest() {
        return hover;
    }

    @Override
    public void paint(Graphics g0) {
        super.paint(g0);
        if (hover < 0) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        Color accent = Theme.uiColor("Component.accentColor", new Color(0x2563EB));
        g.setColor(accent);
        g.setStroke(new java.awt.BasicStroke(3f));
        for (JTextField f : hover == FileDrop.BOTH ? List.of(left, right) : List.of(hover == 0 ? left : right)) {
            Rectangle r = javax.swing.SwingUtilities.convertRectangle(f.getParent(), f.getBounds(), this);
            g.drawRoundRect(r.x - 3, r.y - 3, r.width + 6, r.height + 6, 8, 8);
        }
        g.dispose();
    }

    /** Asks for two files (with these filled in, if not null); null if cancelled. */
    static Path[] ask(java.awt.Component owner, Path a, Path b, String lastDir) {
        ComparePicker p = new ComparePicker(a, b, lastDir);
        while (true) {
            int r = JOptionPane.showConfirmDialog(owner, p, "Compare Files", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            Path[] files;
            try {
                files = p.files();
            } catch (java.nio.file.InvalidPathException e) {
                files = null;
            }
            if (files != null) {
                return files;
            }
            JOptionPane.showMessageDialog(owner, "Choose a left and a right file.", "Compare Files",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
