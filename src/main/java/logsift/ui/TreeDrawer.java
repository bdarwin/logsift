package logsift.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * The tree of values while it's hidden: a narrow strip at the left edge, its name written down
 * it, that brings the tree back when clicked. Without it a hidden tree is easily never found.
 */
final class TreeDrawer extends JComponent {
    private final Runnable open;
    private String column = "";
    private boolean hover;

    TreeDrawer(Runnable open) {
        this.open = open;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        setOpaque(true);
        getAccessibleContext().setAccessibleName("Show the tree of values");
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                open.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        };
        addMouseListener(m);
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "open");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                open.run();
            }
        });
        setColumn(null);
    }

    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public javax.accessibility.AccessibleRole getAccessibleRole() {
                    return javax.accessibility.AccessibleRole.PUSH_BUTTON; // read out as a button
                }
            };
        }
        return accessibleContext;
    }

    /** The column the tree shows ("host"), or null. */
    void setColumn(String title) {
        column = title == null ? "" : title;
        setToolTipText("Show the tree" + (column.isEmpty() ? "" : " of " + column) + ": hide or show lines by their value ("
                + (MainFrame.isMac() ? "⇧⌘B" : "Ctrl+Shift+B") + ")");
        repaint();
    }

    /** What the strip reads. */
    String label() {
        return "▲  Tree" + (column.isEmpty() ? "" : " · " + column); // ▲ points right once turned
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.getHeight() + 12, 80);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(hover || isFocusOwner() ? Theme.uiColor("Button.hoverBackground", Theme.gutterBackground())
                    : Theme.gutterBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Theme.uiColor("Separator.foreground", java.awt.Color.LIGHT_GRAY));
            g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            // written top to bottom, from near the top
            g.setFont(getFont().deriveFont(Font.PLAIN));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Theme.foreground()); // plain to see: the point of it is to be noticed
            g.rotate(Math.PI / 2);
            String text = label();
            int room = getHeight() - 16;
            while (fm.stringWidth(text) > room && text.length() > 4) {
                text = text.substring(0, text.length() - 2) + "…";
                text = text.replace("……", "…");
            }
            g.drawString(text, 10, -(getWidth() - fm.getAscent() + fm.getDescent()) / 2 - 1);
        } finally {
            g.dispose();
        }
    }
}
