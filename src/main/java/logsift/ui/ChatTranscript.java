package logsift.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import logsift.chat.ChatMessage;
import logsift.chat.ChatStore;

/**
 * A chat's entries, newest at the bottom, read from the store a page at a time as the person
 * scrolls: up for older ones, down again for newer, so a chat of any length opens at once and
 * holds only a few hundred entries in the window.
 */
final class ChatTranscript extends JPanel implements Scrollable {
    /** Entries read at a time. */
    static final int PAGE = 40;
    /** The most entries kept in the window; beyond, the far end is let go (and read again if needed). */
    static final int KEEP = 240;
    /** How near an end (pixels) more is read. */
    static final int NEAR = 400;

    /** What an entry does when clicked: show lines, open a query… */
    interface Actions {
        /** A link clicked in an entry ({@code from}: the entry, for what its handles meant). */
        void link(ChatMessage from, String href);

        void action(ChatMessage tool, String what);

        /** Something the window shows under a tool's row (a chart), or null. */
        default javax.swing.JComponent inline(ChatMessage tool) {
            return null;
        }

        /** The person's verdict on this answer: 1 good, -1 bad, 0 none (or no audit to keep it). */
        default int verdict(ChatMessage answer) {
            return 0;
        }

        /** Whether answers can be rated here. */
        default boolean judges() {
            return false;
        }

        /** The person rated the answer (0: took it back); a bad one may ask what went wrong. Gives the verdict kept. */
        default int judge(ChatMessage answer, int verdict, java.awt.Component from) {
            return verdict;
        }
    }

    private final ChatStore store;
    private final Actions actions;
    private final JScrollPane scroll;
    private String chat;
    private final List<ChatEntryView> views = new ArrayList<>();
    /** Whether older entries than the first shown exist (and newer than the last). */
    private boolean moreBefore;
    private boolean moreAfter;
    private boolean loading;
    /** Moving the view itself (opening a chat, adding a page): the scrollbar's moves then aren't the person's. */
    private int quiet;
    private Consumer<Boolean> onEndChanged = b -> {};

    /** The verdicts were changed elsewhere (the audit's window): each answer shows its own again. */
    void verdictsChanged() {
        for (ChatEntryView v : views) {
            v.refreshVerdict();
        }
    }

    ChatTranscript(ChatStore store, Actions actions) {
        super(new Stack());
        this.store = store;
        this.actions = actions;
        setOpaque(true);
        setBackground(Theme.background());
        scroll = new JScrollPane(this, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().setBackground(Theme.background());
        scroll.getVerticalScrollBar().getModel().addChangeListener(e -> nearEnd());
    }

    JScrollPane scrollPane() {
        return scroll;
    }

    /** Told whether the newest entry is in the window (else "jump to the newest" is offered). */
    void onEndChanged(Consumer<Boolean> c) {
        onEndChanged = c;
    }

    String chat() {
        return chat;
    }

    /** Shows a chat from its end (null: none). */
    void show(String chatId) {
        chat = chatId;
        clear();
        if (chatId == null) {
            return;
        }
        List<ChatMessage> page = store.last(chatId, PAGE);
        moreBefore = !page.isEmpty() && page.get(0).seq() > 1;
        moreAfter = false;
        quiet++;
        for (ChatMessage m : page) {
            addView(m, views.size());
        }
        revalidate();
        repaint();
        scrollToEnd();
        settled(this::scrollToEnd);
        onEndChanged.accept(true);
    }

    /** Shows a chat around one entry (a search hit), marked. */
    void showAround(String chatId, long seq) {
        chat = chatId;
        clear();
        List<ChatMessage> page = store.around(chatId, seq, PAGE / 2);
        if (page.isEmpty()) {
            show(chatId);
            return;
        }
        moreBefore = page.get(0).seq() > 1;
        moreAfter = page.get(page.size() - 1).seq() < lastSeq();
        quiet++;
        for (ChatMessage m : page) {
            ChatEntryView v = addView(m, views.size());
            v.setMarked(m.seq() == seq);
        }
        revalidate();
        validateNow();
        settled(() -> {
            validateNow();
            for (ChatEntryView v : views) {
                if (v.message().seq() == seq) {
                    // the hit near the top third of the view
                    JScrollBar b = scroll.getVerticalScrollBar();
                    b.setValue(Math.max(0, v.getY() - scroll.getViewport().getHeight() / 3));
                }
            }
        });
        onEndChanged.accept(!moreAfter);
    }

    private long lastSeq() {
        List<ChatMessage> l = store.last(chat, 1);
        return l.isEmpty() ? 0 : l.get(0).seq();
    }

    private void clear() {
        removeAll();
        views.clear();
        moreBefore = false;
        moreAfter = false;
        revalidate();
        repaint();
    }

    private ChatEntryView addView(ChatMessage m, int at) {
        ChatEntryView v = new ChatEntryView(m, actions);
        views.add(at, v);
        add(v, at);
        return v;
    }

    /** An entry was added or changed in the chat shown. */
    void changed(ChatMessage m, boolean added) {
        if (chat == null || !chat.equals(m.chat())) {
            return;
        }
        for (ChatEntryView v : views) {
            if (v.message().seq() == m.seq()) {
                boolean stick = atEnd();
                v.update(m);
                revalidate();
                if (stick) {
                    scrollToEndLater();
                }
                return;
            }
        }
        if (added && !moreAfter) {
            boolean stick = atEnd() || m.kind() == ChatMessage.Kind.USER;
            addView(m, views.size()).setLive(true);
            trim(false);
            revalidate();
            repaint();
            if (stick) {
                scrollToEndLater();
            } else {
                onEndChanged.accept(false);
            }
        } else if (added) {
            onEndChanged.accept(false); // newer than the window: "jump to the newest" says so
        }
    }

    /** Whether the bottom is in view (then new words keep it there). */
    boolean atEnd() {
        JScrollBar b = scroll.getVerticalScrollBar();
        return !moreAfter && b.getValue() + b.getVisibleAmount() >= b.getMaximum() - 40;
    }

    void scrollToEnd() {
        validateNow();
        JScrollBar b = scroll.getVerticalScrollBar();
        b.setValue(b.getMaximum());
    }

    private void scrollToEndLater() {
        SwingUtilities.invokeLater(this::scrollToEnd);
    }

    /** Goes to the newest entry, reading it again if the window had let it go. */
    void jumpToEnd() {
        if (moreAfter) {
            show(chat);
        } else {
            scrollToEnd();
            onEndChanged.accept(true);
        }
    }

    /** Once the moves made now have been laid out: {@code then}, and the person's scrolling counts again. */
    private void settled(Runnable then) {
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            try {
                if (then != null) {
                    then.run();
                }
            } finally {
                quiet--;
                if (getPreferredSize().height < scroll.getViewport().getHeight()) {
                    nearEnd(); // too few to fill the view: read more
                }
            }
        }));
    }

    private void validateNow() {
        if (scroll.getParent() != null) {
            scroll.validate();
        }
        validate();
    }

    /** Reads more when the person scrolls near an end. */
    private void nearEnd() {
        if (quiet > 0 || loading || chat == null || views.isEmpty() || !isShowing() || scroll.getViewport().getHeight() <= 0) {
            return; // not on screen: where it's scrolled to means nothing yet
        }
        JScrollBar b = scroll.getVerticalScrollBar();
        if (moreBefore && b.getValue() < NEAR) {
            loading = true;
            SwingUtilities.invokeLater(() -> {
                try {
                    loadBefore();
                } finally {
                    loading = false;
                }
            });
        } else if (moreAfter && b.getValue() + b.getVisibleAmount() > b.getMaximum() - NEAR) {
            loading = true;
            SwingUtilities.invokeLater(() -> {
                try {
                    loadAfter();
                } finally {
                    loading = false;
                }
            });
        }
    }

    private void loadBefore() {
        long first = views.get(0).message().seq();
        List<ChatMessage> page = store.before(chat, first, PAGE);
        moreBefore = !page.isEmpty() && page.get(0).seq() > 1;
        if (page.isEmpty()) {
            return;
        }
        JScrollBar b = scroll.getVerticalScrollBar();
        int oldHeight = getPreferredSize().height;
        int oldValue = b.getValue();
        quiet++;
        settled(null);
        for (int i = 0; i < page.size(); i++) {
            addView(page.get(i), i);
        }
        trim(true);
        revalidate();
        validateNow();
        // the same entries stay where they were on screen
        b.setValue(oldValue + getPreferredSize().height - oldHeight);
        repaint();
    }

    private void loadAfter() {
        long last = views.get(views.size() - 1).message().seq();
        List<ChatMessage> page = store.after(chat, last, PAGE);
        moreAfter = page.size() == PAGE && page.get(page.size() - 1).seq() < lastSeq();
        JScrollBar b = scroll.getVerticalScrollBar();
        int oldValue = b.getValue();
        int before = getPreferredSize().height;
        quiet++;
        settled(null);
        for (ChatMessage m : page) {
            addView(m, views.size());
        }
        int removedHeight = trimTop(before);
        revalidate();
        validateNow();
        b.setValue(Math.max(0, oldValue - removedHeight));
        repaint();
        if (!moreAfter) {
            onEndChanged.accept(atEnd());
        }
    }

    /** Lets go of entries beyond {@link #KEEP}: at the bottom when reading older ones, else the top. */
    private void trim(boolean fromBottom) {
        if (!fromBottom) {
            trimTop(getPreferredSize().height);
            return;
        }
        while (views.size() > KEEP) {
            ChatEntryView v = views.remove(views.size() - 1);
            remove(v);
            moreAfter = true;
        }
    }

    private int trimTop(int heightBefore) {
        int removed = 0;
        while (views.size() > KEEP) {
            ChatEntryView v = views.remove(0);
            removed += v.getHeight() + Stack.GAP;
            remove(v);
            moreBefore = true;
        }
        return removed;
    }

    // ---------------------------------------------------------------- for tests

    int shownForTest() {
        return views.size();
    }

    long firstSeqForTest() {
        return views.isEmpty() ? 0 : views.get(0).message().seq();
    }

    long lastSeqForTest() {
        return views.isEmpty() ? 0 : views.get(views.size() - 1).message().seq();
    }

    List<ChatEntryView> viewsForTest() {
        return List.copyOf(views);
    }

    /** Whether entry {@code seq} is in view. */
    boolean inViewForTest(long seq) {
        java.awt.Rectangle seen = scroll.getViewport().getViewRect();
        return views.stream().anyMatch(v -> v.message().seq() == seq && v.getBounds().intersects(seen));
    }

    void scrollToTopForTest() {
        scroll.getVerticalScrollBar().setValue(0);
    }

    // ---------------------------------------------------------------- Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
        return Math.max(24, r.height - 48);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }

    /** Entries one under another, each as wide as the panel and as tall as its words need there. */
    static final class Stack implements LayoutManager {
        static final int GAP = 10;
        static final int PAD = 12;
        /** The entries' width and where they start, in a panel {@code w} wide inside {@code in}: all of it. */
        static int[] column(int w, Insets in) {
            return new int[] {w - in.left - in.right - 2 * PAD, in.left + PAD};
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        private static int width(Container parent) {
            int w = parent.getWidth();
            if (w <= 0 && parent.getParent() != null) {
                w = parent.getParent().getWidth();
            }
            return Math.max(120, w);
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int w = column(width(parent), in)[0];
            int h = in.top + in.bottom + PAD;
            for (Component c : parent.getComponents()) {
                h += height(c, w) + GAP;
            }
            return new Dimension(width(parent), h + PAD);
        }

        static int height(Component c, int w) {
            if (c instanceof ChatEntryView v) {
                return v.heightFor(w);
            }
            return c.getPreferredSize().height;
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(120, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int[] col = column(parent.getWidth(), in);
            int w = col[0];
            int y = in.top + PAD;
            for (Component c : parent.getComponents()) {
                int h = height(c, w);
                c.setBounds(col[1], y, w, h);
                y += h + GAP;
            }
        }
    }
}
