import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Development tool: draws the 16x16 caravan marker item texture.
 * Usage: {@code java GenerateMarkerTexture.java <output.png>}
 */
public final class GenerateMarkerTexture
{
    private GenerateMarkerTexture()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final File output = new File(args.length > 0 ? args[0] : "caravan_marker.png");

        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, 16, 16);

        // Flag pole
        g.setColor(new Color(0x6B, 0x4F, 0x2E, 255));
        g.fillRect(7, 2, 2, 12);

        // Flag: red with gold trim
        g.setColor(new Color(0xC6, 0x27, 0x28, 255));
        g.fillRect(9, 2, 6, 5);
        g.setColor(new Color(0xF2, 0xC9, 0x4C, 255));
        g.fillRect(9, 2, 6, 1);
        g.fillRect(9, 2, 1, 5);

        // Emerald gem on the flag
        g.setColor(new Color(0x3F, 0xB5, 0x4A, 255));
        g.fillRect(11, 3, 3, 3);
        g.setColor(new Color(0x9B, 0xE8, 0xA0, 255));
        g.fillRect(11, 3, 3, 1);
        g.fillRect(11, 3, 1, 3);

        // Base
        g.setColor(new Color(0x4E, 0x3A, 0x24, 255));
        g.fillRect(6, 14, 4, 1);

        g.dispose();
        ImageIO.write(image, "png", output);
        System.out.println("Wrote " + output.getAbsolutePath());
    }
}
