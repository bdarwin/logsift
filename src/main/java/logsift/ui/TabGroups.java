package logsift.ui;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * The window's tabs, in one group or two side by side (or one above the other). Tabs are numbered
 * across both: the first group's, then the second's. What's selected is the selected tab of the
 * group last used (clicked, typed in, or chosen); new tabs open there.
 */
final class TabGroups {
    /** Where a tab goes when it's dropped while dragging. */
    enum Drop { NONE, OTHER_SIDE, SPLIT_RIGHT, SPLIT_DOWN }

    private final JTabbedPane first = new JTabbedPane();
    private final JTabbedPane second = new JTabbedPane();
    private final Root root = new Root();
    private JSplitPane split;
    private boolean down;
    private JTabbedPane active = first;
    private Component lastAdded;
    private final List<ChangeListener> listeners = new ArrayList<>();
    private IntConsumer onClose = i -> { };
    /** Right-click on a tab: its number, and where (in that group). */
    private Menu onMenu = (i, pane, x, y) -> { };
    private Runnable onLayout = () -> { };
    private boolean moving;

    interface Menu {
        void show(int index, JTabbedPane pane, int x, int y);
    }

    TabGroups(Supplier<JComponent> trailing) {
        for (JTabbedPane p : List.of(first, second)) {
            p.putClientProperty("JTabbedPane.tabClosable", true);
            p.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close");
            p.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
            p.putClientProperty("JTabbedPane.tabCloseCallback", (java.util.function.BiConsumer<JTabbedPane, Integer>)
                    (pane, i) -> onClose.accept(global(pane, i)));
            p.putClientProperty("JTabbedPane.trailingComponent", trailing.get());
            p.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            p.addChangeListener(e -> {
                if (!moving && (p == active || p.getTabCount() > 0 && other(p).getTabCount() == 0)) {
                    fire();
                }
            });
            Dragger d = new Dragger(p);
            p.addMouseListener(d);
            p.addMouseMotionListener(d);
            p.addContainerListener(new java.awt.event.ContainerAdapter() {
                @Override
                public void componentRemoved(java.awt.event.ContainerEvent e) {
                    if (!moving) {
                        SwingUtilities.invokeLater(TabGroups.this::joinIfEmpty);
                    }
                }
            });
        }
        first.setName("tabs");
        second.setName("tabs2");
        root.add(first, BorderLayout.CENTER);
        // the side last clicked or typed in is the one new tabs go to, and whose tab is "current"
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e.getID() == MouseEvent.MOUSE_PRESSED && e.getSource() instanceof Component c && split != null) {
                use(c);
            }
        }, java.awt.AWTEvent.MOUSE_EVENT_MASK);
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner",
                e -> {
                    if (e.getNewValue() instanceof Component c && split != null) {
                        use(c);
                    }
                });
        style();
        // light or dark: the grey of the side not in use, again
        javax.swing.UIManager.addPropertyChangeListener(e -> {
            if ("lookAndFeel".equals(e.getPropertyName())) {
                SwingUtilities.invokeLater(this::style);
            }
        });
    }

    private void use(Component c) {
        if (SwingUtilities.isDescendingFrom(c, second)) {
            activate(second);
        } else if (SwingUtilities.isDescendingFrom(c, first)) {
            activate(first);
        }
    }

    /** What the window shows: one group, or both. */
    JComponent component() {
        return root;
    }

    void onClose(IntConsumer c) {
        onClose = c;
    }

    void onMenu(Menu m) {
        onMenu = m;
    }

    /** Called when the window splits or joins. */
    void onLayout(Runnable r) {
        onLayout = r;
    }

    // ---------------------------------------------------------------- as one tabbed pane

    int getTabCount() {
        return first.getTabCount() + second.getTabCount();
    }

    Component getComponentAt(int i) {
        return i < first.getTabCount() ? first.getComponentAt(i) : second.getComponentAt(i - first.getTabCount());
    }

    int indexOfComponent(Component c) {
        int i = first.indexOfComponent(c);
        if (i >= 0) {
            return i;
        }
        i = second.indexOfComponent(c);
        return i < 0 ? -1 : first.getTabCount() + i;
    }

    Component getSelectedComponent() {
        Component c = active.getSelectedComponent();
        return c != null ? c : other(active).getSelectedComponent();
    }

    int getSelectedIndex() {
        Component c = getSelectedComponent();
        return c == null ? -1 : indexOfComponent(c);
    }

    void setSelectedIndex(int i) {
        if (i < 0 || i >= getTabCount()) {
            return;
        }
        JTabbedPane p = paneAt(i);
        p.setSelectedIndex(local(i));
        activate(p);
    }

    void setSelectedComponent(Component c) {
        int i = indexOfComponent(c);
        if (i >= 0) {
            setSelectedIndex(i);
        }
    }

    void addTab(String title, Component c) {
        addTab(title, null, c);
    }

    void addTab(String title, Icon icon, Component c) {
        active.addTab(title, icon, c);
        lastAdded = c;
    }

    /** The number of the tab added last (by {@link #addTab}). */
    int lastAdded() {
        return indexOfComponent(lastAdded);
    }

    void insertTab(String title, Icon icon, Component c, String tip, int at) {
        if (at <= first.getTabCount()) {
            first.insertTab(title, icon, c, tip, Math.max(0, at));
        } else {
            second.insertTab(title, icon, c, tip, Math.min(second.getTabCount(), at - first.getTabCount()));
        }
        lastAdded = c;
    }

    void setTitleAt(int i, String title) {
        paneAt(i).setTitleAt(local(i), title);
    }

    String getTitleAt(int i) {
        return paneAt(i).getTitleAt(local(i));
    }

    void setToolTipTextAt(int i, String tip) {
        paneAt(i).setToolTipTextAt(local(i), tip);
    }

    String getToolTipTextAt(int i) {
        return paneAt(i).getToolTipTextAt(local(i));
    }

    void removeTabAt(int i) {
        paneAt(i).removeTabAt(local(i));
    }

    void remove(Component c) {
        if (first.indexOfComponent(c) >= 0) {
            first.remove(c);
        } else {
            second.remove(c);
        }
    }

    void addChangeListener(ChangeListener l) {
        listeners.add(l);
    }

    void addContainerListener(java.awt.event.ContainerListener l) {
        first.addContainerListener(l);
        second.addContainerListener(l);
    }

    /** Both groups' tab strips get {@code l} (a click's tab: {@link #indexAt}). */
    void addMouseListener(java.awt.event.MouseListener l) {
        first.addMouseListener(l);
        second.addMouseListener(l);
    }

    /** The tab under a mouse event on either group's strip, or -1. */
    int indexAt(MouseEvent e) {
        if (!(e.getSource() instanceof JTabbedPane p)) {
            return -1;
        }
        int i = p.indexAtLocation(e.getX(), e.getY());
        return i < 0 ? -1 : global(p, i);
    }

    private JTabbedPane paneAt(int i) {
        return i < first.getTabCount() ? first : second;
    }

    private int local(int i) {
        return i < first.getTabCount() ? i : i - first.getTabCount();
    }

    private int global(JTabbedPane p, int i) {
        return p == first ? i : first.getTabCount() + i;
    }

    private JTabbedPane other(JTabbedPane p) {
        return p == first ? second : first;
    }

    private void fire() {
        ChangeEvent e = new ChangeEvent(this);
        for (ChangeListener l : List.copyOf(listeners)) {
            l.stateChanged(e);
        }
    }

    // ---------------------------------------------------------------- two groups

    boolean isSplit() {
        return split != null;
    }

    /** Split one above the other (else side by side). */
    boolean isDown() {
        return down;
    }

    /** Which group a tab is in: 0 (left or top), 1 (right or bottom), or -1. */
    int sideOf(Component c) {
        return first.indexOfComponent(c) >= 0 ? 0 : second.indexOfComponent(c) >= 0 ? 1 : -1;
    }

    /** The group in use: 0 or 1. */
    int activeSide() {
        return active == first ? 0 : 1;
    }

    /** The selected tab of the group not in use (null when not split). */
    Component otherSelected() {
        return split == null ? null : other(active).getSelectedComponent();
    }

    /** The selected tab of group {@code side}. */
    Component selectedOf(int side) {
        return (side == 0 ? first : second).getSelectedComponent();
    }

    /** The tabs of group {@code side}, in order. */
    List<Component> tabsOf(int side) {
        JTabbedPane p = side == 0 ? first : second;
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < p.getTabCount(); i++) {
            out.add(p.getComponentAt(i));
        }
        return out;
    }

    /** Uses group {@code side} (new tabs go there). */
    void activate(int side) {
        activate(side == 0 ? first : second);
    }

    private void activate(JTabbedPane p) {
        if (p == active || p.getTabCount() == 0) {
            return;
        }
        active = p;
        style();
        fire();
    }

    /** Moves tab {@code i} to the other group, splitting the window when it isn't ({@code down}: below). */
    void moveToOtherSide(int i, boolean down) {
        Component c = getComponentAt(i);
        JTabbedPane from = paneAt(i);
        JTabbedPane to = other(from);
        if (split == null) {
            if (from.getTabCount() < 2) {
                return; // the only tab: nothing would be left on this side
            }
            splitInto(down);
        }
        move(from, local(i), to, to.getTabCount());
        activate(to);
        to.setSelectedComponent(c);
        style();
        fire();
    }

    /** Adds {@code c} to the other group (a copy of a tab), splitting the window when it isn't. */
    void addToOtherSide(String title, Icon icon, Component c, String tip, boolean down) {
        if (split == null) {
            splitInto(down);
        }
        JTabbedPane to = other(active);
        to.addTab(title, icon, c, tip);
        lastAdded = c;
        to.setSelectedComponent(c);
        activate(to);
        style();
        fire();
    }

    /** One group again: the second's tabs go after the first's. */
    void join() {
        if (split == null) {
            return;
        }
        Component sel = getSelectedComponent();
        moving = true;
        try {
            while (second.getTabCount() > 0) {
                move(second, 0, first, first.getTabCount());
            }
        } finally {
            moving = false;
        }
        unsplit();
        if (sel != null && first.indexOfComponent(sel) >= 0) {
            first.setSelectedComponent(sel);
        }
        fire();
    }

    private void move(JTabbedPane from, int i, JTabbedPane to, int at) {
        Component c = from.getComponentAt(i);
        String title = from.getTitleAt(i);
        Icon icon = from.getIconAt(i);
        String tip = from.getToolTipTextAt(i);
        boolean was = moving;
        moving = true;
        try {
            from.removeTabAt(i);
            to.insertTab(title, icon, c, tip, Math.min(at, to.getTabCount()));
        } finally {
            moving = was;
        }
        if (!was) {
            SwingUtilities.invokeLater(this::joinIfEmpty);
        }
    }

    private void splitInto(boolean down) {
        this.down = down;
        root.removeAll();
        split = new JSplitPane(down ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT, first, second);
        split.setName("split");
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        root.revalidate();
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
        onLayout.run();
    }

    private void unsplit() {
        root.removeAll();
        split = null;
        root.add(first, BorderLayout.CENTER);
        active = first;
        root.revalidate();
        root.repaint();
        style();
        onLayout.run();
    }

    /** A group with no tabs left: the window is one group again. */
    private void joinIfEmpty() {
        if (split == null || moving) {
            return;
        }
        if (second.getTabCount() == 0) {
            unsplit();
            fire();
        } else if (first.getTabCount() == 0) {
            moving = true;
            try {
                while (second.getTabCount() > 0) {
                    move(second, 0, first, first.getTabCount());
                }
            } finally {
                moving = false;
            }
            unsplit();
            fire();
        } else if (active.getTabCount() == 0) {
            activate(other(active));
        }
    }

    /** The group in use has the accent's underline; the other a grey one. */
    private void style() {
        Color grey = javax.swing.UIManager.getColor("TabbedPane.inactiveUnderlineColor");
        if (grey == null) {
            grey = Color.GRAY;
        }
        String idle = String.format("underlineColor: #%06x; inactiveUnderlineColor: #%06x",
                grey.getRGB() & 0xFFFFFF, grey.getRGB() & 0xFFFFFF);
        for (JTabbedPane p : List.of(first, second)) {
            p.putClientProperty("FlatLaf.style", split != null && p != active ? idle : null);
            p.repaint();
        }
    }

    /** For tests: whether a side's tabs are underlined as the side not in use. */
    boolean idleForTest(int side) {
        return (side == 0 ? first : second).getClientProperty("FlatLaf.style") != null;
    }

    // ---------------------------------------------------------------- dragging a tab

    /** Where dropping at {@code p} (in the window's tab area) puts a tab dragged from group {@code from}. */
    Drop dropAt(Point p, int from) {
        Rectangle r = new Rectangle(0, 0, root.getWidth(), root.getHeight());
        if (!r.contains(p)) {
            return Drop.NONE;
        }
        if (split != null) {
            JTabbedPane to = from == 0 ? second : first;
            Point q = SwingUtilities.convertPoint(root, p, to);
            return to.contains(q) ? Drop.OTHER_SIDE : Drop.NONE;
        }
        if (first.getTabCount() < 2) {
            return Drop.NONE;
        }
        if (p.x > r.width * 0.7) {
            return Drop.SPLIT_RIGHT;
        }
        if (p.y > r.height * 0.7) {
            return Drop.SPLIT_DOWN;
        }
        return Drop.NONE;
    }

    /** Drops tab {@code i}: true when it moved. */
    boolean drop(int i, Drop d) {
        switch (d) {
            case OTHER_SIDE -> moveToOtherSide(i, down);
            case SPLIT_RIGHT -> moveToOtherSide(i, false);
            case SPLIT_DOWN -> moveToOtherSide(i, true);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The area a drop would fill, in the window's tab area, or null. */
    private Rectangle dropArea(Drop d, int from) {
        return switch (d) {
            case OTHER_SIDE -> SwingUtilities.convertRectangle(from == 0 ? second.getParent() : first.getParent(),
                    (from == 0 ? second : first).getBounds(), root);
            case SPLIT_RIGHT -> new Rectangle(root.getWidth() / 2, 0, root.getWidth() - root.getWidth() / 2, root.getHeight());
            case SPLIT_DOWN -> new Rectangle(0, root.getHeight() / 2, root.getWidth(), root.getHeight() - root.getHeight() / 2);
            default -> null;
        };
    }

    private final class Dragger extends MouseAdapter {
        private final JTabbedPane pane;
        private int index = -1;
        private Point start;
        private boolean dragging;

        Dragger(JTabbedPane pane) {
            this.pane = pane;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                menu(e);
                return;
            }
            index = SwingUtilities.isLeftMouseButton(e) ? pane.indexAtLocation(e.getX(), e.getY()) : -1;
            start = e.getPoint();
            dragging = false;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (index < 0 || start == null) {
                return;
            }
            if (!dragging && start.distance(e.getPoint()) < 12) {
                return;
            }
            dragging = true;
            Point p = SwingUtilities.convertPoint(pane, e.getPoint(), root);
            int from = pane == first ? 0 : 1;
            root.showDrop(dropArea(dropAt(p, from), from));
            pane.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) {
                menu(e);
                return;
            }
            pane.setCursor(null);
            root.showDrop(null);
            if (dragging && index >= 0 && index < pane.getTabCount()) {
                Point p = SwingUtilities.convertPoint(pane, e.getPoint(), root);
                drop(global(pane, index), dropAt(p, pane == first ? 0 : 1));
            }
            index = -1;
            dragging = false;
        }

        private void menu(MouseEvent e) {
            int i = pane.indexAtLocation(e.getX(), e.getY());
            if (i >= 0) {
                activate(pane);
                onMenu.show(global(pane, i), pane, e.getX(), e.getY());
            }
        }
    }

    /** The tab area, which shows where a dragged tab would go. */
    private static final class Root extends JPanel {
        private Rectangle drop;

        Root() {
            super(new BorderLayout());
            setOpaque(false);
        }

        void showDrop(Rectangle r) {
            if (!java.util.Objects.equals(r, drop)) {
                drop = r;
                repaint();
            }
        }

        @Override
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            if (drop != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                Color a = javax.swing.UIManager.getColor("Component.accentColor");
                g2.setColor(a == null ? new Color(0x2F5BEA) : a);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                g2.fillRect(drop.x, drop.y, drop.width, drop.height);
                g2.setComposite(AlphaComposite.SrcOver);
                g2.drawRect(drop.x + 1, drop.y + 1, drop.width - 3, drop.height - 3);
                g2.dispose();
            }
        }
    }

    // ---------------------------------------------------------------- tests

    /** The drop overlay shown now, or null. */
    Rectangle dropShownForTest() {
        return root.drop;
    }

    JTabbedPane paneForTest(int side) {
        return side == 0 ? first : second;
    }

    double dividerForTest() {
        return split == null ? -1 : split.getDividerLocation();
    }
}
