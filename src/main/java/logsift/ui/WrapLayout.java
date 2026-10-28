package logsift.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} that asks for the height of all its rows: in a narrow window its
 * components go on to a second row instead of being cut off.
 */
final class WrapLayout extends FlowLayout {
    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    private int laidOutWidth = -1;

    /**
     * The parent asked for this panel's height before giving it its new width: when the width
     * changes the rows, lay out again (once per width).
     */
    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        int w = target.getWidth();
        if (w != laidOutWidth) {
            laidOutWidth = w;
            if (preferredLayoutSize(target).height != target.getHeight()) {
                SwingUtilities.invokeLater(target::revalidate);
            }
        }
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return size(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension d = size(target, false);
        d.width -= getHgap() + 1;
        return d;
    }

    private Dimension size(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // the parent's width: it is laid out before it asks this panel for its height, while
            // this panel still has its old width
            Container parent = target.getParent();
            int width = 0;
            if (parent != null && parent.getWidth() > 0) {
                Insets pi = parent.getInsets();
                width = parent.getWidth() - pi.left - pi.right;
            }
            if (width <= 0) {
                width = target.getSize().width;
            }
            Container c = target;
            while (width == 0 && c.getParent() != null) {
                c = c.getParent();
                width = c.getSize().width;
            }
            if (width == 0) {
                width = Integer.MAX_VALUE;
            }
            Insets in = target.getInsets();
            int max = width - (in.left + in.right + getHgap() * 2);
            Dimension dim = new Dimension(0, 0);
            int rowW = 0;
            int rowH = 0;
            for (Component m : target.getComponents()) {
                if (!m.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowW + d.width > max && rowW > 0) {
                    dim.width = Math.max(dim.width, rowW);
                    dim.height += rowH + getVgap();
                    rowW = 0;
                    rowH = 0;
                }
                if (rowW != 0) {
                    rowW += getHgap();
                }
                rowW += d.width;
                rowH = Math.max(rowH, d.height);
            }
            dim.width = Math.max(dim.width, rowW);
            dim.height += rowH;
            dim.width += in.left + in.right + getHgap() * 2;
            dim.height += in.top + in.bottom + getVgap() * 2;
            Container sp = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (sp != null && target.isValid()) {
                dim.width -= getHgap() + 1;
            }
            return dim;
        }
    }
}
