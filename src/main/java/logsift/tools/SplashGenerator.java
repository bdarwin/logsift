package logsift.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws the splash screen shown while Logsift starts (by the launcher, before Java is up):
 * {@code splash.png} and {@code splash@2x.png} for high-resolution screens.
 */
public final class SplashGenerator {
    private SplashGenerator() {}

    static final int WIDTH = 480;
    static final int HEIGHT = 260;

    public static BufferedImage render(int scale) {
        int w = WIDTH * scale;
        int h = HEIGHT * scale;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.scale(scale, scale);
            g.setPaint(new GradientPaint(0, 0, new Color(0x111827), WIDTH, HEIGHT, new Color(0x1E293B)));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            // faint log lines in the background
            g.setColor(new Color(255, 255, 255, 10));
            for (int y = 26, i = 0; y < HEIGHT - 20; y += 14, i++) {
                int len = 120 + (i * 73) % 240;
                g.fillRoundRect(WIDTH - 24 - len, y, len, 5, 5, 5);
            }
            BufferedImage icon = IconGenerator.render(96 * scale);
            g.drawImage(icon, 40, (HEIGHT - 96) / 2 - 10, 96, 96, null);
            int x = 160;
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
            g.drawString("Logsift", x, HEIGHT / 2 - 2);
            g.setColor(new Color(0x94A3B8));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.drawString("Viewer for very large log files", x + 2, HEIGHT / 2 + 26);
            g.setColor(new Color(0x64748B));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.drawString("Starting…", 40, HEIGHT - 24);
            // accent line along the bottom
            g.setPaint(new GradientPaint(0, 0, new Color(0x2563EB), WIDTH, 0, new Color(0x22C55E)));
            g.fillRect(0, HEIGHT - 4, WIDTH, 4);
        } finally {
            g.dispose();
        }
        return img;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "packaging/splash");
        Files.createDirectories(dir);
        ImageIO.write(render(1), "png", dir.resolve("splash.png").toFile());
        ImageIO.write(render(2), "png", dir.resolve("splash@2x.png").toFile());
        System.out.println("Splash written to " + dir.toAbsolutePath());
    }
}
