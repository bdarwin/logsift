package logsift.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Below the chat while the assistant answers: a ring that turns, what it's doing now (thinking,
 * a tool it runs, writing the answer) and for how long, so a wait never looks like nothing.
 */
final class ChatWorking extends JPanel {
    private final Timer tick = new Timer(60, e -> step());
    private final JLabel what = new JLabel();
    private final JComponent ring = new JComponent() {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight()) - 4;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            java.awt.Color a = ChatColors.accent();
            g2.setColor(new java.awt.Color(a.getRed(), a.getGreen(), a.getBlue(), 50));
            g2.drawOval(x, y, d, d);
            g2.setColor(a);
            g2.drawArc(x, y, d, d, -angle, 100);
            g2.dispose();
        }
    };
    private int angle;
    private long since;
    private String doing = THINKING;

    static final String THINKING = "Thinking";
    static final String WRITING = "Writing the answer";

    ChatWorking() {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setName("working");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 14, 6, 12));
        ring.setPreferredSize(new Dimension(16, 16));
        add(ring);
        add(what);
        setVisible(false);
    }

    /** The assistant started answering. */
    void start() {
        if (!isVisible()) {
            since = System.currentTimeMillis();
            doing = THINKING;
            setVisible(true);
        }
        tick.start();
        step();
    }

    /** The answer is over (or was stopped). */
    void stop() {
        tick.stop();
        setVisible(false);
    }

    /** What it's doing now: "Thinking", "Read lines 3–4 in log:1", "Writing the answer". */
    void doing(String now) {
        doing = now;
        step();
    }

    private void step() {
        angle = (angle + 24) % 360;
        long s = (System.currentTimeMillis() - since) / 1000;
        String took = s < 60 ? s + " s" : s / 60 + " min " + s % 60 + " s";
        what.setText(doing + "…  " + took);
        ring.repaint();
    }

    String textForTest() {
        return isVisible() ? what.getText() : null;
    }
}
