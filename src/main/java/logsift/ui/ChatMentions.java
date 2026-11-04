package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;

/**
 * Files named in a question with {@code @}: typing {@code @} lists the logs open in the window and
 * the files opened lately, and picking one puts {@code @app.log} in the question. The assistant is
 * then told which file it is (a handle, or its path) in a line, and may read it without asking.
 */
final class ChatMentions {

    /**
     * A file that can be named.
     *
     * @param name   its file's name, as written after {@code @}
     * @param path   where it is (null for a log open from a server)
     * @param handle its handle when it is open in the window ({@code log:3}), else null
     * @param where  its folder or address, for the list
     */
    record File(String name, Path path, String handle, String where) {}

    private static final Pattern TYPING = Pattern.compile("(?:^|\\s)@([^\\s@]*)$");
    private static final int MAX = 12;

    private final JTextArea input;
    private final Supplier<List<File>> files;
    private final DefaultListModel<File> model = new DefaultListModel<>();
    private final JList<File> list = new JList<>(model);
    private final JPopupMenu popup = new JPopupMenu();
    /** The files picked, by the name written ({@code app.log}). */
    private final Map<String, File> picked = new LinkedHashMap<>();

    ChatMentions(JTextArea input, Supplier<List<File>> files) {
        this.input = input;
        this.files = files;
        list.setCellRenderer(new Renderer());
        list.setFocusable(false);
        list.setVisibleRowCount(8);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    list.setSelectedIndex(i);
                    pick();
                }
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        popup.setFocusable(false);
        popup.setLayout(new BorderLayout());
        JLabel head = new JLabel("Name a file: open logs, then recent files");
        head.setForeground(Theme.dimmedForeground());
        head.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        popup.add(head, BorderLayout.NORTH);
        popup.add(sp, BorderLayout.CENTER);
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(ChatMentions.this::typed);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                SwingUtilities.invokeLater(ChatMentions.this::typed);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
    }

    /** What is typed after an {@code @} just before the caret, or null. */
    String typing() {
        try {
            int at = input.getCaretPosition();
            Matcher m = TYPING.matcher(input.getText(0, at));
            return m.find() ? m.group(1) : null;
        } catch (javax.swing.text.BadLocationException | IllegalArgumentException e) {
            return null;
        }
    }

    private void typed() {
        String q = typing();
        if (q == null || !input.isShowing()) {
            popup.setVisible(false);
            return;
        }
        List<File> found = match(files.get(), q);
        model.clear();
        found.forEach(model::addElement);
        if (found.isEmpty()) {
            popup.setVisible(false);
            return;
        }
        list.setSelectedIndex(0);
        try {
            Rectangle r = input.modelToView2D(input.getCaretPosition()).getBounds();
            popup.pack();
            popup.show(input, r.x, Math.max(0, r.y - popup.getPreferredSize().height - 4));
            input.requestFocusInWindow();
        } catch (javax.swing.text.BadLocationException e) {
            popup.setVisible(false);
        }
    }

    /** The files whose name or folder has {@code q} in it: open ones first, at most {@value #MAX}. */
    static List<File> match(List<File> all, String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        List<File> starts = new ArrayList<>();
        List<File> has = new ArrayList<>();
        for (File f : all) {
            String n = f.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(needle)) {
                starts.add(f);
            } else if (n.contains(needle) || f.where().toLowerCase(Locale.ROOT).contains(needle)) {
                has.add(f);
            }
        }
        starts.addAll(has);
        return starts.size() > MAX ? starts.subList(0, MAX) : starts;
    }

    boolean isOpen() {
        return popup.isVisible();
    }

    void move(int by) {
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + by));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    void close() {
        popup.setVisible(false);
    }

    /** Puts the chosen file in the question, in place of what was typed after {@code @}. */
    void pick() {
        File f = list.getSelectedValue();
        String q = typing();
        if (f == null || q == null) {
            close();
            return;
        }
        int at = input.getCaretPosition();
        input.replaceRange(f.name() + " ", at - q.length(), at);
        picked.put(f.name(), f);
        close();
    }

    /** The files a question names (by {@code @name}), picked here or typed out. */
    List<File> named(String question, List<File> all) {
        List<File> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?:^|\\s)@([^\\s@]+)").matcher(question);
        while (m.find()) {
            String n = m.group(1).replaceAll("[.,;:!?)]+$", "");
            File f = picked.get(n);
            if (f == null) {
                f = all.stream().filter(x -> x.name().equals(n)).findFirst().orElse(null);
            }
            if (f != null && !out.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /** The line that tells the assistant which files are named: a few tokens each. */
    static String line(List<File> named) {
        if (named.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("[Named] ");
        for (int i = 0; i < named.size(); i++) {
            File f = named.get(i);
            b.append(i == 0 ? "" : "; ").append('@').append(f.name()).append(" = ")
                    .append(f.handle() != null ? f.handle() : String.valueOf(f.path()));
        }
        return b.append('.').toString();
    }

    private static final class Renderer extends JPanel implements ListCellRenderer<File> {
        private final JLabel name = new JLabel();
        private final JLabel where = new JLabel();

        Renderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            add(name, BorderLayout.WEST);
            add(where, BorderLayout.CENTER);
            where.setFont(where.getFont().deriveFont(where.getFont().getSize2D() - 1f));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends File> l, File f, int i, boolean sel, boolean focus) {
            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            name.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            where.setForeground(sel ? l.getSelectionForeground() : Theme.dimmedForeground());
            name.setIcon(Icons.of(f.handle() != null ? "file-text" : "clock",
                    f.handle() != null ? ChatColors.tool("read_lines").color() : Theme.dimmedForeground(), 13));
            name.setText(f.name());
            where.setText((f.handle() != null ? "open · " : "recent · ") + f.where());
            return this;
        }
    }

    // ---------------------------------------------------------------- for tests

    List<File> shownForTest() {
        List<File> l = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            l.add(model.get(i));
        }
        return l;
    }
}
