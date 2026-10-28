package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A thin strip at the top of a tab that counts only the lines the filters keep: "Only the 225
 * of 2,099 lines the filters keep", and a link back to all lines. The same in every such tab.
 */
final class ScopeBanner extends JPanel {
    private final JLabel text = new JLabel();
    private final JLabel link = new JLabel();

    ScopeBanner(String linkText, Runnable onLink) {
        super(new BorderLayout(10, 0));
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        text.setIcon(Icons.of("filter", Theme.gutterForeground(), 12));
        link.setText("<html><u>" + linkText + "</u></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onLink.run();
            }
        });
        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.setOpaque(false);
        left.add(text, BorderLayout.WEST);
        left.add(link, BorderLayout.CENTER);
        add(left, BorderLayout.WEST);
        setVisible(false);
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        setBackground(Theme.isDark() ? new Color(0x1E2A3A) : new Color(0xEEF4FD));
        if (text != null) {
            text.setForeground(Theme.foreground());
            link.setForeground(Theme.uiColor("Component.accentColor", new Color(0x2563EB)));
        }
        super.paintComponent(g);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Shows the strip with this text, or hides it ({@code message} null). */
    void show(String message) {
        boolean on = message != null;
        if (on) {
            text.setText(message);
        }
        if (on != isVisible()) {
            setVisible(on);
            revalidate();
        }
        repaint();
    }

    String textForTest() {
        return isVisible() ? text.getText() : null;
    }

    /** Clicks the link, as the mouse would. For tests. */
    void clickForTest() {
        for (java.awt.event.MouseListener l : link.getMouseListeners()) {
            l.mouseClicked(null);
        }
    }

    /** Puts a banner at the top of a panel laid out with a BorderLayout, above its top bar. */
    static ScopeBanner install(JPanel panel, String linkText, Runnable onLink) {
        ScopeBanner b = new ScopeBanner(linkText, onLink);
        BorderLayout layout = (BorderLayout) panel.getLayout();
        Component north = layout.getLayoutComponent(BorderLayout.NORTH);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(b, BorderLayout.NORTH);
        if (north != null) {
            panel.remove(north);
            top.add(north, BorderLayout.CENTER);
        }
        panel.add(top, BorderLayout.NORTH);
        return b;
    }
}
