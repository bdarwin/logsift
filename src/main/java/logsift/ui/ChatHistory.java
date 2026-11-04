package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.Timer;
import logsift.chat.ChatInfo;
import logsift.chat.ChatMessage;
import logsift.chat.ChatStore;

/**
 * Every chat, newest first, read a page at a time as the list is scrolled; and a search across
 * all of them that goes to the very entry where the words are.
 */
final class ChatHistory extends JPanel {
    static final int PAGE = 50;

    interface Host {
        /** Opens a chat, at an entry ({@code seq} > 0) or at its end. */
        void open(String chat, long seq);

        void deleted(String chat);

        void back();
    }

    /** A row: a chat, or a place where the words searched for are. */
    record Row(ChatInfo chat, ChatStore.Hit hit) {}

    private final ChatStore store;
    private final Host host;
    private final JTextField search = new JTextField();
    private final DefaultListModel<Row> rows = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(rows);
    private final JLabel empty = new JLabel(" ", JLabel.CENTER);
    private final Timer searchLater;
    private boolean more;

    ChatHistory(ChatStore store, Host host) {
        super(new BorderLayout(0, 6));
        this.store = store;
        this.host = host;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        JButton back = new JButton("‹ Back");
        back.putClientProperty("JButton.buttonType", "toolBarButton");
        back.addActionListener(e -> host.back());
        top.add(back, BorderLayout.WEST);
        search.putClientProperty("JTextField.placeholderText", "Search every chat");
        search.putClientProperty("JTextField.showClearButton", true);
        search.putClientProperty("JTextField.leadingIcon", Icons.of("search"));
        top.add(search, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        list.setCellRenderer(new Renderer());
        list.setFixedCellHeight(-1);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && e.getClickCount() == 1 && !e.isPopupTrigger() && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    open(rows.get(i));
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (list.getSelectedValue() != null) {
                    open(list.getSelectedValue());
                }
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        sp.getVerticalScrollBar().getModel().addChangeListener(e -> {
            var b = sp.getVerticalScrollBar();
            if (more && search.getText().isBlank() && b.getValue() + b.getVisibleAmount() > b.getMaximum() - 200) {
                more = false;
                javax.swing.SwingUtilities.invokeLater(this::loadMore);
            }
        });
        add(sp, BorderLayout.CENTER);
        empty.setForeground(Theme.dimmedForeground());
        add(empty, BorderLayout.SOUTH);
        searchLater = new Timer(250, e -> refresh());
        searchLater.setRepeats(false);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                searchLater.restart();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                searchLater.restart();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                searchLater.restart();
            }
        });
    }

    /** Reads the list again (or the search's hits). */
    void refresh() {
        rows.clear();
        String q = search.getText().strip();
        if (q.isEmpty()) {
            List<ChatInfo> page = store.chats(null, PAGE);
            page.forEach(c -> rows.addElement(new Row(c, null)));
            more = page.size() == PAGE;
            empty.setText(page.isEmpty() ? "No chats yet" : String.format("%,d chats", store.chatCount()));
        } else {
            List<ChatStore.Hit> hits = store.search(q, 200);
            hits.forEach(h -> rows.addElement(new Row(null, h)));
            more = false;
            empty.setText(hits.isEmpty() ? "Nothing found" : hits.size() == 200 ? "The newest 200 places"
                    : hits.size() == 1 ? "1 place" : hits.size() + " places");
        }
    }

    private void loadMore() {
        if (rows.isEmpty() || rows.lastElement().chat() == null) {
            return;
        }
        List<ChatInfo> page = store.chats(rows.lastElement().chat(), PAGE);
        page.forEach(c -> rows.addElement(new Row(c, null)));
        more = page.size() == PAGE;
    }

    private void open(Row r) {
        if (r.chat() != null) {
            host.open(r.chat().id(), 0);
        } else {
            host.open(r.hit().chat(), r.hit().seq());
        }
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int i = list.locationToIndex(e.getPoint());
        if (i < 0 || rows.get(i).chat() == null) {
            return;
        }
        list.setSelectedIndex(i);
        ChatInfo c = rows.get(i).chat();
        JPopupMenu m = new JPopupMenu();
        JMenuItem del = new JMenuItem("Delete Chat…");
        del.addActionListener(a -> {
            int ok = javax.swing.JOptionPane.showConfirmDialog(this, "Delete “" + c.title() + "” and everything in it?",
                    "Delete Chat", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
            if (ok == javax.swing.JOptionPane.OK_OPTION) {
                store.delete(c.id());
                host.deleted(c.id());
                refresh();
            }
        });
        m.add(del);
        m.show(list, e.getX(), e.getY());
    }

    /** "10:42" today, "Tue 10:42" this week, else the date. */
    static String when(long ms) {
        ZoneId z = ZoneId.systemDefault();
        var t = Instant.ofEpochMilli(ms).atZone(z);
        LocalDate today = LocalDate.now(z);
        if (t.toLocalDate().equals(today)) {
            return DateTimeFormatter.ofPattern("HH:mm").format(t);
        }
        if (t.toLocalDate().isAfter(today.minusDays(7))) {
            return DateTimeFormatter.ofPattern("EEE HH:mm").format(t);
        }
        return DateTimeFormatter.ofPattern("d MMM yyyy").format(t);
    }

    private static final class Renderer extends JPanel implements ListCellRenderer<Row> {
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        Renderer() {
            super(new BorderLayout(0, 1));
            setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
            add(name, BorderLayout.NORTH);
            add(detail, BorderLayout.CENTER);
            detail.setFont(detail.getFont().deriveFont(detail.getFont().getSize2D() - 1f));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Row> l, Row r, int index, boolean sel, boolean focus) {
            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            name.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            detail.setForeground(sel ? l.getSelectionForeground() : Theme.dimmedForeground());
            if (r.chat() != null) {
                ChatInfo c = r.chat();
                name.setText(c.title());
                String cost = c.nanoAiu() > 0 ? " · " + ChatEntryView.credits(c.credits()) : "";
                detail.setText(when(c.updated()) + " · " + c.messages() + (c.messages() == 1 ? " entry" : " entries") + cost);
            } else {
                ChatStore.Hit h = r.hit();
                name.setText(h.title());
                String who = switch (h.kind()) {
                    case USER -> "You: ";
                    case ASSISTANT -> "Copilot: ";
                    case TOOL -> "Logsift: ";
                    default -> "";
                };
                detail.setText(when(h.time()) + " · " + (h.seq() == 0 ? "in its name" : who + h.snippet()));
            }
            setToolTipText(r.hit() != null ? r.hit().snippet() : null);
            return this;
        }
    }

    // ---------------------------------------------------------------- for tests

    JTextField searchForTest() {
        return search;
    }

    int rowsForTest() {
        return rows.size();
    }

    Row rowForTest(int i) {
        return rows.get(i);
    }

    void openForTest(int i) {
        open(rows.get(i));
    }

    void loadMoreForTest() {
        loadMore();
    }
}
