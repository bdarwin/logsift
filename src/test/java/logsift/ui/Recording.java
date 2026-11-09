package logsift.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import logsift.core.SearchQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;


/**
 * Records the real window as a video: drives it step by step and draws a pointer, the keys
 * pressed and a caption on each frame (needs ffmpeg on the PATH). Subclasses write the script.
 */
abstract class Recording {
    static final int FPS = 15;
    static final int SCALE = 2;

    final Path out = Path.of(System.getProperty("logsift.demo"));
    MainFrame frame;
    JRootPane root;
    int w;
    int h;

    // overlay state, read by the capture
    volatile String caption = "";
    volatile String keys = "";
    volatile double cx = 700;
    volatile double cy = 500;
    volatile int ripple = -1;
    volatile boolean showCursor = true;

    Process ffmpeg;
    /** How many times faster than real time frames are taken (and so the video plays). */
    volatile int speed = 1;
    final BlockingQueue<BufferedImage> queue = new ArrayBlockingQueue<>(8);
    Thread writer;
    long nextFrame;
    int stills;

    // ---------------------------------------------------------------- actions

    void say(String text) {
        caption = text;
    }

    void press(String k) throws Exception {
        keys = k;
        new Thread(() -> {
            try {
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                return;
            }
            if (keys.equals(k)) {
                keys = "";
            }
        }).start();
        hold(200);
    }

    void type(FindBar fb, String text) throws Exception {
        for (int i = 1; i <= text.length(); i++) {
            String s = text.substring(0, i);
            onEdt(() -> {
                fb.field().setText(s);
                return null;
            });
            hold(130);
        }
    }

    void moveTo(Component c, int ms) throws Exception {
        moveTo(c, ms, 0.5);
    }

    /** Moves the pointer to the component, at this fraction of its width. */
    void moveTo(Component c, int ms, double fx) throws Exception {
        Point p = onEdt(() -> {
            Point s = c.getLocationOnScreen();
            Point r = root.getLocationOnScreen();
            return new Point(s.x - r.x + (int) (c.getWidth() * fx), s.y - r.y + c.getHeight() / 2);
        });
        double x0 = cx;
        double y0 = cy;
        int n = Math.max(1, ms * FPS / 1000);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double e = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            cx = x0 + (p.x - x0) * e;
            cy = y0 + (p.y - y0) * e;
            frame();
        }
    }

    void click(AbstractButton b) throws Exception {
        ripple = 0;
        hold(150);
        onEdt(() -> {
            b.doClick(0);
            if (b instanceof JMenuItem) { // a real click closes the menu
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
            }
            return null;
        });
    }

    void clickChip(Component chip, boolean onX) throws Exception {
        ripple = 0;
        hold(150);
        onEdt(() -> {
            int x = onX ? chip.getWidth() - 8 : chip.getWidth() / 3;
            int y = chip.getHeight() / 2;
            long now = System.currentTimeMillis();
            chip.dispatchEvent(new MouseEvent(chip, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK,
                    x, y, 1, false, MouseEvent.BUTTON1));
            chip.dispatchEvent(new MouseEvent(chip, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false,
                    MouseEvent.BUTTON1));
            return null;
        });
    }

    void rightClick(Component chip) throws Exception {
        ripple = 0;
        hold(150);
        onEdt(() -> {
            int x = chip.getWidth() / 3;
            int y = chip.getHeight() / 2;
            chip.dispatchEvent(new MouseEvent(chip, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON3_DOWN_MASK, x, y, 1, true, MouseEvent.BUTTON3));
            return null;
        });
    }

    void hold(int ms) throws Exception {
        int n = Math.max(1, ms * FPS / 1000);
        for (int i = 0; i < n; i++) {
            frame();
        }
    }

    // ---------------------------------------------------------------- finding components

    static List<Component> findAll(Container c, Predicate<Component> p) {
        List<Component> out = new ArrayList<>();
        for (Component k : c.getComponents()) {
            if (p.test(k)) {
                out.add(k);
            }
            if (k instanceof Container kc) {
                out.addAll(findAll(kc, p));
            }
        }
        return out;
    }

    static AbstractButton button(Container in, String text) throws Exception {
        return onEdt(() -> (AbstractButton) findAll(in, c -> c instanceof AbstractButton b && b.isShowing()
                && b.getText() != null && b.getText().startsWith(text)).get(0));
    }

    static AbstractButton byTip(Container in, String tip) throws Exception {
        return onEdt(() -> (AbstractButton) findAll(in, c -> c instanceof AbstractButton b && b.isShowing()
                && b.getToolTipText() != null && b.getToolTipText().startsWith(tip)).get(0));
    }

    static List<Component> chips(LogBar bar) throws Exception {
        return onEdt(() -> findAll(bar, c -> c.getClass().getSimpleName().equals("Chip") && c.getWidth() > 0));
    }

    JMenuItem menuItem(String text) throws Exception {
        waitUntil(() -> !items(text).isEmpty());
        return onEdt(() -> items(text).get(0));
    }

    static List<JMenuItem> items(String text) {
        List<JMenuItem> out = new ArrayList<>();
        for (Window win : Window.getWindows()) {
            if (win.isShowing()) {
                for (Component c : findAll(win, k -> k instanceof JMenuItem m && m.isShowing() && text.equals(m.getText()))) {
                    out.add((JMenuItem) c);
                }
            }
        }
        return out;
    }

    LogTab waitForTab(String title) throws Exception {
        waitUntil(() -> frame.currentTab() != null && frame.currentTab().title().equals(title));
        return onEdt(frame::currentTab);
    }

    /** Waits (recording) until this is true on the EDT. */
    void waitUntil(Callable<Boolean> cond) throws Exception {
        waitUntil(cond, 30_000);
    }

    /** The same, waiting up to this long. */
    void waitUntil(Callable<Boolean> cond, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!onEdt(cond)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out");
            }
            if (ffmpeg != null) {
                frame();
            } else {
                Thread.sleep(50);
            }
        }
    }

    // ---------------------------------------------------------------- capture

    /** The video file's name in {@link #out}. */
    abstract String videoName();

    void startVideo() throws Exception {
        ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24",
                "-s", (w * SCALE) + "x" + (h * SCALE), "-r", String.valueOf(FPS), "-i", "-",
                "-vf", "scale=1920:-2:flags=lanczos", "-c:v", "libx264", "-preset", "slow", "-crf", "20",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.resolve(videoName()).toString())
                .redirectErrorStream(true).redirectOutput(out.resolve("ffmpeg.txt").toFile()).start();
        OutputStream os = ffmpeg.getOutputStream();
        writer = new Thread(() -> {
            try {
                while (true) {
                    BufferedImage img = queue.take();
                    if (img.getWidth() == 1) {
                        break;
                    }
                    os.write(((DataBufferByte) img.getRaster().getDataBuffer()).getData());
                }
                os.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        writer.start();
        nextFrame = System.nanoTime();
    }

    void stopVideo() throws Exception {
        queue.put(new BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR));
        writer.join();
        ffmpeg.waitFor();
    }

    /** Captures one frame, keeping to FPS in real time. */
    void frame() throws Exception {
        long wait = nextFrame - System.nanoTime();
        if (wait > 0) {
            Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
        nextFrame = Math.max(nextFrame, System.nanoTime() - 200_000_000L) + 1_000_000_000L * speed / FPS;
        BufferedImage img = capture();
        overlay(img);
        queue.put(img);
        if (ripple >= 0 && ++ripple > 6) {
            ripple = -1;
        }
    }

    BufferedImage capture() throws Exception {
        return onEdt(() -> {
            BufferedImage im = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = im.createGraphics();
            g.scale(SCALE, SCALE);
            root.printAll(g);
            // popup menus in their own windows
            Point r = root.getLocationOnScreen();
            for (Window win : Window.getWindows()) {
                if (win != frame && win.isShowing() && win instanceof RootPaneContainer rpc
                        && !(win instanceof java.awt.Dialog) && !(win instanceof java.awt.Frame)) {
                    JComponent c = rpc.getRootPane();
                    Point s = c.getLocationOnScreen();
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.translate(s.x - r.x, s.y - r.y);
                    c.printAll(g2);
                    g2.dispose();
                }
            }
            g.dispose();
            return im;
        });
    }

    void overlay(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(SCALE, SCALE);
        // caption
        String cap = caption;
        if (!cap.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
            var fm = g.getFontMetrics();
            int tw = fm.stringWidth(cap);
            int bw = tw + 44;
            int bh = 46;
            int bx = (w - bw) / 2;
            int by = h - bh - 34;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.86f));
            g.setColor(new Color(0x111827));
            g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 14, 14));
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(Color.WHITE);
            g.drawString(cap, bx + 22, by + (bh + fm.getAscent() - fm.getDescent()) / 2);
        }
        // keys pressed
        String k = keys;
        if (!k.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            var fm = g.getFontMetrics();
            String[] parts = k.split(" ");
            int x = w - 40;
            int y = h - 150;
            for (int i = parts.length - 1; i >= 0; i--) {
                int kw = Math.max(44, fm.stringWidth(parts[i]) + 26);
                x -= kw;
                g.setColor(new Color(0xF9FAFB));
                g.fill(new RoundRectangle2D.Double(x, y, kw, 46, 10, 10));
                g.setColor(new Color(0x374151));
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Double(x, y, kw, 46, 10, 10));
                g.drawString(parts[i], x + (kw - fm.stringWidth(parts[i])) / 2, y + 31);
                x -= 8;
            }
        }
        // pointer
        if (showCursor) {
            int x = (int) cx;
            int y = (int) cy;
            if (ripple >= 0) {
                int r = 8 + ripple * 4;
                g.setColor(new Color(37, 99, 235, Math.max(0, 170 - ripple * 24)));
                g.fillOval(x - r, y - r, 2 * r, 2 * r);
            }
            Polygon arrow = new Polygon(new int[] {0, 0, 4, 7, 9, 6, 11}, new int[] {0, 16, 12, 18, 17, 11, 11}, 7);
            arrow.translate(x, y);
            g.setColor(Color.BLACK);
            g.fill(arrow);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(arrow);
        }
        g.dispose();
    }

    /** Saves this moment as a picture too. */
    void still(String name) throws Exception {
        BufferedImage img = capture();
        overlay(img);
        ImageIO.write(img, "png", out.resolve(String.format("%02d-%s.png", ++stills, name.replaceFirst("^\\d+-", ""))).toFile());
    }

    static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(c.call());
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }
}
