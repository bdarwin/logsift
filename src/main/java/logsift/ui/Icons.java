package logsift.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * Small line icons for the toolbar, drawn in code: sharp at any size, and in the theme's text
 * color (gray when the button is disabled). Drawn on a 16×16 grid.
 */
final class Icons {
    private Icons() {}

    static Icon open() {
        return of(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(1.5, 4);
            p.lineTo(1.5, 13);
            p.lineTo(14.5, 13);
            p.lineTo(14.5, 5.5);
            p.lineTo(7.5, 5.5);
            p.lineTo(6, 3.5);
            p.lineTo(2, 3.5);
            p.closePath();
            g.draw(p);
        });
    }

    /** A page with a folded corner. */
    static Icon file() {
        return of(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(3.5, 1.5);
            p.lineTo(9.5, 1.5);
            p.lineTo(12.5, 4.5);
            p.lineTo(12.5, 14.5);
            p.lineTo(3.5, 14.5);
            p.closePath();
            g.draw(p);
            g.draw(new Line2D.Double(9.5, 1.5, 9.5, 4.5));
            g.draw(new Line2D.Double(9.5, 4.5, 12.5, 4.5));
        });
    }

    static Icon remote() {
        return of(g -> {
            g.draw(new RoundRectangle2D.Double(2, 2, 12, 5, 2, 2));
            g.draw(new RoundRectangle2D.Double(2, 9, 12, 5, 2, 2));
            g.fill(new Ellipse2D.Double(4, 4, 1.6, 1.6));
            g.fill(new Ellipse2D.Double(4, 11, 1.6, 1.6));
        });
    }

    static Icon find() {
        return of(g -> {
            g.draw(new Ellipse2D.Double(2, 2, 8.5, 8.5));
            g.draw(new Line2D.Double(9.5, 9.5, 14, 14));
        });
    }

    static Icon up() {
        return of(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(3, 10.5);
            p.lineTo(8, 5.5);
            p.lineTo(13, 10.5);
            g.draw(p);
        });
    }

    static Icon down() {
        return of(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(3, 5.5);
            p.lineTo(8, 10.5);
            p.lineTo(13, 5.5);
            g.draw(p);
        });
    }

    /** An arrow down onto a line: keep showing the end of the file. */
    static Icon follow() {
        return of(g -> {
            g.draw(new Line2D.Double(8, 1.5, 8, 10.5));
            Path2D p = new Path2D.Double();
            p.moveTo(4.5, 7);
            p.lineTo(8, 10.5);
            p.lineTo(11.5, 7);
            g.draw(p);
            g.draw(new Line2D.Double(2.5, 14, 13.5, 14));
        });
    }

    /** A line that turns back to the start of the next one. */
    static Icon wrap() {
        return of(g -> {
            g.draw(new Line2D.Double(1.5, 3.5, 14.5, 3.5));
            Path2D p = new Path2D.Double();
            p.moveTo(1.5, 8);
            p.lineTo(11.5, 8);
            p.quadTo(14.5, 8, 14.5, 10.5);
            p.quadTo(14.5, 13, 11.5, 13);
            p.lineTo(6.5, 13);
            g.draw(p);
            Path2D a = new Path2D.Double();
            a.moveTo(8.5, 11);
            a.lineTo(6.5, 13);
            a.lineTo(8.5, 15);
            g.draw(a);
        });
    }

    static Icon columns() {
        return of(g -> {
            g.draw(new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 2, 2));
            g.draw(new Line2D.Double(5.5, 2.5, 5.5, 13.5));
            g.draw(new Line2D.Double(9.5, 2.5, 9.5, 13.5));
            g.draw(new Line2D.Double(1.5, 5.5, 14.5, 5.5));
        });
    }

    static Icon reload() {
        return of(g -> {
            g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 70, 290, Arc2D.OPEN));
            Path2D a = new Path2D.Double();
            a.moveTo(8.5, 0.8);
            a.lineTo(10.6, 2.9);
            a.lineTo(8.3, 4.9);
            g.draw(a);
        });
    }

    /** Two arrows chasing each other: keep the file up to date by itself. */
    static Icon autoReload() {
        return of(g -> {
            g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 20, 140, Arc2D.OPEN));
            g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 200, 140, Arc2D.OPEN));
            Path2D a = new Path2D.Double();
            a.moveTo(1.2, 5.2);
            a.lineTo(3.1, 7.6);
            a.lineTo(5.6, 6);
            g.draw(a);
            Path2D b = new Path2D.Double();
            b.moveTo(14.8, 10.8);
            b.lineTo(12.9, 8.4);
            b.lineTo(10.4, 10);
            g.draw(b);
        });
    }

    private static Icon of(Consumer<Graphics2D> drawing) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    g.translate(x, y);
                    Color fg = c != null && c.getForeground() != null ? c.getForeground()
                            : UIManager.getColor("Label.foreground");
                    if (c != null && !c.isEnabled()) {
                        fg = UIManager.getColor("Label.disabledForeground");
                    }
                    g.setColor(fg == null ? Color.DARK_GRAY : fg);
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    drawing.accept(g);
                } finally {
                    g.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }
}
