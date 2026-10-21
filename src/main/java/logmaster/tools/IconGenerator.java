package logmaster.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the LogMaster icon: log lines on a deep blue tile, one line highlighted like an error,
 * the highlight bar on the right, and a magnifying glass. Drawn in code so every size is sharp;
 * small sizes use a simpler drawing.
 *
 * <p>{@code ./gradlew genIcons} writes the installer icons (.ico, .icns via iconutil, .png) to
 * {@code packaging/icons}.
 */
public final class IconGenerator {
    private IconGenerator() {}

    /** The icon at {@code size} x {@code size} pixels. */
    public static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double k = size / 1024.0;
        g.scale(k, k);
        boolean small = size < 48;

        // tile: macOS-style rounded square (nearly full-bleed when small)
        double inset = small ? 24 : 100;
        double box = 1024 - 2 * inset;
        double radius = box * 0.225;
        RoundRectangle2D tile = new RoundRectangle2D.Double(inset, inset, box, box, radius * 2, radius * 2);
        g.setPaint(new GradientPaint(0, (float) inset, new Color(0x3D5AFE),
                0, (float) (inset + box), new Color(0x1A237E)));
        g.fill(tile);
        g.clip(tile);
        // a gentle top light
        g.setPaint(new GradientPaint(0, (float) inset, new Color(255, 255, 255, 46),
                0, (float) (inset + box * 0.55), new Color(255, 255, 255, 0)));
        g.fill(tile);

        double left = inset + box * 0.14;
        double right = inset + box * 0.80;
        double width = right - left;
        Color line = new Color(0xDCE3FF);
        if (small) {
            // three thick lines, the middle one highlighted
            double t = 120;
            double[] ys = {inset + box * 0.24, inset + box * 0.48, inset + box * 0.72};
            double[] lens = {0.85, 1.0, 0.55};
            for (int i = 0; i < 3; i++) {
                if (i == 1) {
                    g.setColor(new Color(0xFF5A5F));
                    g.fill(new RoundRectangle2D.Double(inset, ys[i] - t * 0.75, box, t * 1.5, 40, 40));
                }
                g.setColor(i == 1 ? Color.WHITE : line);
                g.setStroke(new BasicStroke((float) t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(left, ys[i], left + width * lens[i], ys[i]));
            }
            g.dispose();
            return img;
        }

        // log lines: a short "timestamp" piece, then the message
        double thick = 34;
        double gap = 92;
        double top = inset + box * 0.20;
        double[][] rows = {{0.18, 0.62}, {0.18, 0.78}, {0.18, 0.70}, {0.18, 0.50}, {0.18, 0.74},
            {0.18, 0.40}};
        int highlighted = 2;
        g.setStroke(new BasicStroke((float) thick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < rows.length; i++) {
            double y = top + i * gap;
            if (i == highlighted) {
                g.setColor(new Color(0xFF5A5F));
                g.fill(new RoundRectangle2D.Double(inset + box * 0.07, y - 40, box * 0.80, 80, 36, 36));
            }
            Color c = i == highlighted ? Color.WHITE : (i % 2 == 0 ? line : new Color(0xB9C4F5));
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), i == highlighted ? 255 : 230));
            double tsEnd = left + width * rows[i][0];
            g.draw(new Line2D.Double(left, y, tsEnd, y));
            g.draw(new Line2D.Double(tsEnd + 46, y, left + width * rows[i][1], y));
        }

        // highlight bar on the right with marks
        double barX = inset + box * 0.90;
        g.setColor(new Color(255, 255, 255, 40));
        g.fill(new RoundRectangle2D.Double(barX, inset + box * 0.12, 30, box * 0.76, 16, 16));
        double[][] marks = {{0.18, 0xFFB020}, {0.33, 0xFF5A5F}, {0.41, 0xFFB020}, {0.62, 0xFF5A5F},
            {0.75, 0x5EEAD4}};
        for (double[] m : marks) {
            g.setColor(new Color((int) m[1]));
            g.fill(new RoundRectangle2D.Double(barX + 3, inset + box * m[0], 24, 16, 8, 8));
        }

        // magnifying glass, lower right
        double cx = inset + box * 0.58;
        double cy = inset + box * 0.64;
        double r = box * 0.165;
        g.setColor(new Color(255, 255, 255, 50));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(40, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x0D1240));
        g.draw(new Line2D.Double(cx + r * 0.72, cy + r * 0.72, cx + r * 1.58, cy + r * 1.58));
        g.setStroke(new BasicStroke(30, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        g.draw(new Line2D.Double(cx + r * 0.72, cy + r * 0.72, cx + r * 1.55, cy + r * 1.55));
        g.setStroke(new BasicStroke(30));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.dispose();
        return img;
    }

    /** Icons for windows and the taskbar, small to large. */
    public static List<BufferedImage> windowIcons() {
        List<BufferedImage> out = new ArrayList<>();
        for (int s : new int[] {16, 20, 24, 32, 40, 48, 64, 128, 256}) {
            out.add(render(s));
        }
        return out;
    }

    /** Writes a Windows .ico with PNG images. */
    static void writeIco(Path file, int... sizes) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(render(s), "png", b);
            pngs.add(b.toByteArray());
        }
        try (OutputStream os = Files.newOutputStream(file)) {
            DataOutputStream o = new DataOutputStream(os);
            writeShortLe(o, 0);
            writeShortLe(o, 1); // icon
            writeShortLe(o, sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                int s = sizes[i];
                o.writeByte(s >= 256 ? 0 : s);
                o.writeByte(s >= 256 ? 0 : s);
                o.writeByte(0);
                o.writeByte(0);
                writeShortLe(o, 1);
                writeShortLe(o, 32);
                writeIntLe(o, pngs.get(i).length);
                writeIntLe(o, offset);
                offset += pngs.get(i).length;
            }
            for (byte[] p : pngs) {
                o.write(p);
            }
            o.flush();
        }
    }

    private static void writeShortLe(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xFF);
        o.writeByte((v >> 8) & 0xFF);
    }

    private static void writeIntLe(DataOutputStream o, int v) throws IOException {
        writeShortLe(o, v & 0xFFFF);
        writeShortLe(o, (v >>> 16) & 0xFFFF);
    }

    /**
     * Writes icons to a folder: logmaster.png (512), logmaster.ico, and an .iconset for macOS
     * iconutil. Usage: {@code IconGenerator <dir>}
     */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "packaging/icons");
        Files.createDirectories(dir);
        ImageIO.write(render(512), "png", dir.resolve("logmaster.png").toFile());
        writeIco(dir.resolve("logmaster.ico"), 16, 20, 24, 32, 40, 48, 64, 128, 256);
        Path set = dir.resolve("logmaster.iconset");
        Files.createDirectories(set);
        for (int s : new int[] {16, 32, 128, 256, 512}) {
            ImageIO.write(render(s), "png", set.resolve("icon_" + s + "x" + s + ".png").toFile());
            ImageIO.write(render(s * 2), "png", set.resolve("icon_" + s + "x" + s + "@2x.png").toFile());
        }
        System.out.println("Icons written to " + dir.toAbsolutePath());
    }
}
