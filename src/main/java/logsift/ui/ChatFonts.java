package logsift.ui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ContainerEvent;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import logsift.chat.ChatInstructions;
import logsift.chat.ChatService;

/**
 * The assistant's text size, chosen apart from the logs': every font in the assistant (answers,
 * questions, its chats and skills) is what the code chose for it, as much bigger or smaller as the
 * size chosen is from the window's own text. What is added later (each answer, each tool) follows.
 */
final class ChatFonts {
    private ChatFonts() {
    }

    /** The font a component had before it was sized: what the code chose for it. */
    private static final String BASE = "logsift.chatFont.base";
    /** The font it was given here: when it differs, the code chose another one since. */
    private static final String SET = "logsift.chatFont.set";

    private static final Set<Component> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean listening;

    /** Sizes {@code root} and everything put in it from now on. */
    static void follow(JComponent root) {
        ROOTS.add(root);
        if (!listening) {
            listening = true;
            Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
                if (e.getID() == ContainerEvent.COMPONENT_ADDED && e instanceof ContainerEvent ce) {
                    added(ce.getChild());
                }
            }, AWTEvent.CONTAINER_EVENT_MASK);
        }
        apply(root);
    }

    private static void added(Component c) {
        for (Component root : ROOTS) {
            if (root != null && SwingUtilities.isDescendingFrom(c, root)) {
                apply(c);
                return;
            }
        }
    }

    /** The size chosen, in points. */
    static int size() {
        return ChatService.get().options().textSize();
    }

    /** The window's own text size, which the assistant's code sizes its fonts from. */
    static float uiSize() {
        Font f = UIManager.getFont("Label.font");
        return f == null ? 13f : f.getSize2D();
    }

    /** One step bigger (+1) or smaller (-1), kept for next time. */
    static void step(int by) {
        set(size() + by);
    }

    static void set(int size) {
        ChatService s = ChatService.get();
        ChatInstructions.Options o = s.options().withTextSize(size);
        if (o.textSize() != s.options().textSize()) {
            s.saveOptions(o);
        }
        changed();
    }

    /** The size changed (or the theme): everything followed is sized again. */
    static void changed() {
        for (Component root : ROOTS.toArray(new Component[0])) {
            if (root != null) {
                apply(root);
                root.revalidate();
                root.repaint();
            }
        }
    }

    static void apply(Component c) {
        float delta = size() - uiSize();
        apply(c, delta);
    }

    private static void apply(Component c, float delta) {
        if (c instanceof javax.swing.JComboBox<?> box
                && box.getAccessibleContext().getAccessibleChild(0) instanceof javax.swing.plaf.basic.ComboPopup popup) {
            // its list isn't inside it, but the box is as wide as the list's widest item
            apply(popup.getList(), delta);
        }
        if (c instanceof JComponent j) {
            Font f = j.getFont();
            Font base = (Font) j.getClientProperty(BASE);
            if (base != null && !f.equals(j.getClientProperty(SET)) && inRenderer(j)) {
                // a list's or a box's renderer: its owner (sized here) gives it its font each time it paints
            } else if (f != null) {
                if (base == null || !f.equals(j.getClientProperty(SET))) {
                    base = f; // first seen, or given another font since (a new theme)
                    j.putClientProperty(BASE, base);
                }
                Font sized = base.deriveFont(Math.max(6f, base.getSize2D() + delta));
                j.putClientProperty(SET, sized);
                if (!sized.equals(f)) {
                    j.setFont(sized);
                }
            }
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                apply(child, delta);
            }
        }
    }

    /** Inside a renderer (a list's row, a box's value): painted with the font its owner gives it. */
    private static boolean inRenderer(Component c) {
        return SwingUtilities.getAncestorOfClass(javax.swing.CellRendererPane.class, c) != null;
    }
}
