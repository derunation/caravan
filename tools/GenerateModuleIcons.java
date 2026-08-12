import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Development tool: draws the 16x16 module tab icons for the caravan hut GUI.
 * Usage: {@code java GenerateModuleIcons.java <outputDir>}
 */
public final class GenerateModuleIcons
{
    private GenerateModuleIcons()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final File outputDir = new File(args.length > 0 ? args[0] : ".");
        outputDir.mkdirs();

        // Settings icon: a gear made of two circles + spokes.
        final BufferedImage settings = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D gs = settings.createGraphics();
        clear(gs);
        gs.setColor(new Color(0x8D, 0x8D, 0x8D, 255));
        gs.fillRect(7, 1, 2, 3);
        gs.fillRect(7, 12, 2, 3);
        gs.fillRect(1, 7, 3, 2);
        gs.fillRect(12, 7, 3, 2);
        gs.fillRect(3, 3, 2, 2);
        gs.fillRect(11, 3, 2, 2);
        gs.fillRect(3, 11, 2, 2);
        gs.fillRect(11, 11, 2, 2);
        gs.setColor(new Color(0x6B, 0x6B, 0x6B, 255));
        gs.fillOval(5, 5, 6, 6);
        gs.setColor(new Color(0xD8, 0xD8, 0xD8, 255));
        gs.fillOval(6, 6, 4, 4);
        gs.dispose();
        ImageIO.write(settings, "png", new File(outputDir, "settings.png"));

        // Trades icon: two overlapping arrows (trade/exchange).
        final BufferedImage trades = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D gt = trades.createGraphics();
        clear(gt);
        gt.setColor(new Color(0x2E, 0x8B, 0x57, 255));
        gt.fillRect(2, 2, 8, 2);
        gt.fillRect(2, 2, 2, 6);
        gt.fillRect(2, 6, 2, 2);
        gt.setColor(new Color(0xC6, 0x27, 0x28, 255));
        gt.fillRect(6, 12, 8, 2);
        gt.fillRect(12, 8, 2, 6);
        gt.fillRect(12, 12, 2, 2);
        gt.setColor(new Color(0xF2, 0xC9, 0x4C, 255));
        gt.fillRect(4, 4, 2, 2);
        gt.fillRect(10, 10, 2, 2);
        gt.dispose();
        ImageIO.write(trades, "png", new File(outputDir, "trades.png"));

        // Log icon: a small open book.
        final BufferedImage log = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D gl = log.createGraphics();
        clear(gl);
        gl.setColor(new Color(0x8D, 0x6E, 0x4B, 255));
        gl.fillRect(2, 3, 12, 10);
        gl.setColor(new Color(0xF2, 0xE8, 0xD5, 255));
        gl.fillRect(3, 4, 10, 8);
        gl.setColor(new Color(0x6B, 0x4F, 0x2E, 255));
        gl.fillRect(7, 4, 2, 8);
        gl.setColor(new Color(0x8D, 0x6E, 0x4B, 255));
        gl.fillRect(3, 5, 3, 1);
        gl.fillRect(10, 5, 3, 1);
        gl.fillRect(3, 8, 3, 1);
        gl.fillRect(10, 8, 3, 1);
        gl.dispose();
        ImageIO.write(log, "png", new File(outputDir, "log.png"));

        System.out.println("Wrote " + outputDir.getAbsolutePath());
    }

    private static void clear(final Graphics2D g)
    {
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, 16, 16);
    }
}
