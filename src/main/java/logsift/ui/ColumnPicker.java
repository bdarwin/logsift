package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * A list of columns to tick, right below the button that opens it, with a filter box on top
 * (type part of a name; Enter ticks the first match). What is ticked is handed over when it
 * closes, so several columns can be ticked and the comparison runs once.
 */
final class ColumnPicker {
    private final JPopupMenu popup = new JPopupMenu();
    private final JTextField filter = new JTextField(18);
    private final JPanel list = new JPanel();
    private final List<JCheckBox> boxes = new ArrayList<>();
    private final LinkedHashSet<Integer> ticked = new LinkedHashSet<>(); // in the order ticked
    private final List<Integer> before;
    private final JLabel count = new JLabel(" ");
    private boolean done;

    /**
     * @param names   the columns to choose from
     * @param ticked  indices into {@code names} ticked now, in order
     * @param onClose the indices ticked when it closes (in the order ticked), if they changed
     */
    ColumnPicker(String title, String hint, List<String> names, List<Integer> ticked, Consumer<List<Integer>> onClose) {
        this.ticked.addAll(ticked);
        this.before = List.copyOf(ticked);
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD));
        head.add(t, BorderLayout.NORTH);
        filter.putClientProperty("JTextField.placeholderText", "filter columns");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.setToolTipText("Type part of a name; Enter ticks the first match");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        filter.addActionListener(e -> {
            for (JCheckBox b : boxes) {
                if (b.isVisible()) {
                    b.doClick();
                    filter.selectAll();
                    return;
                }
            }
        });
        head.add(filter, BorderLayout.CENTER);
        p.add(head, BorderLayout.NORTH);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (int i = 0; i < names.size(); i++) {
            int at = i;
            JCheckBox b = new JCheckBox(names.get(i), this.ticked.contains(i));
            b.setFocusable(false);
            b.addActionListener(e -> {
                if (b.isSelected()) {
                    this.ticked.add(at);
                } else {
                    this.ticked.remove(at);
                }
                showCount();
            });
            boxes.add(b);
            list.add(b);
        }
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        int rowH = boxes.isEmpty() ? 22 : boxes.get(0).getPreferredSize().height;
        sp.setPreferredSize(new Dimension(Math.max(240, list.getPreferredSize().width + 24),
                Math.min(14 * rowH, Math.max(rowH, names.size() * rowH)) + 4));
        p.add(sp, BorderLayout.CENTER);

        JPanel foot = new JPanel(new BorderLayout(8, 0));
        foot.setOpaque(false);
        count.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        foot.add(count, BorderLayout.CENTER);
        JButton clear = new JButton("Clear");
        clear.putClientProperty("JButton.buttonType", "borderless");
        clear.setFocusable(false);
        clear.addActionListener(e -> {
            this.ticked.clear();
            boxes.forEach(b -> b.setSelected(false));
            showCount();
        });
        foot.add(clear, BorderLayout.EAST);
        if (hint != null) {
            JLabel h = new JLabel(hint);
            h.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
            h.setFont(h.getFont().deriveFont(h.getFont().getSize2D() - 1f));
            foot.add(h, BorderLayout.NORTH);
        }
        p.add(foot, BorderLayout.SOUTH);
        popup.add(p);
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                finish(onClose);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        // Escape closes it (and what is ticked counts, as with a click outside)
        p.registerKeyboardAction(e -> popup.setVisible(false), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        showCount();
    }

    private void finish(Consumer<List<Integer>> onClose) {
        if (done) {
            return;
        }
        done = true;
        List<Integer> now = List.copyOf(ticked);
        if (!now.equals(before)) {
            onClose.accept(now);
        }
    }

    private void refilter() {
        String q = filter.getText().strip().toLowerCase(Locale.ROOT);
        for (JCheckBox b : boxes) {
            b.setVisible(q.isEmpty() || b.getText().toLowerCase(Locale.ROOT).contains(q));
        }
        list.revalidate();
        list.repaint();
        showCount();
    }

    private void showCount() {
        long shown = boxes.stream().filter(Component::isVisible).count();
        count.setText(ticked.size() + " ticked" + (shown < boxes.size() ? " · " + shown + " of " + boxes.size() + " shown" : ""));
    }

    /** Opens right below {@code under}, the filter box ready for typing. */
    void show(JComponent under) {
        popup.show(under, 0, under.getHeight());
        SwingUtilities.invokeLater(filter::requestFocusInWindow);
    }

    // ---------------------------------------------------------------- tests

    void setFilterForTest(String text) {
        filter.setText(text);
    }

    List<String> shownForTest() {
        return boxes.stream().filter(Component::isVisible).map(JCheckBox::getText).toList();
    }

    void tickForTest(String name) {
        for (JCheckBox b : boxes) {
            if (b.getText().equals(name)) {
                b.doClick();
                return;
            }
        }
        throw new IllegalArgumentException("no column " + name);
    }

    /** Enter in the filter box. */
    void enterForTest() {
        filter.postActionEvent();
    }

    JComponent popupForTest() {
        return popup;
    }

    boolean isShowingForTest() {
        return popup.isVisible();
    }

    /** Closes it, as a click outside does. */
    void closeForTest() {
        popup.setVisible(false);
    }
}
