package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

final class AlphaSafePng {
    private AlphaSafePng() {
    }

    static byte[] flattenOntoWhiteIfNeeded(byte[] pngData) {
        if (pngData == null || pngData.length == 0) return pngData;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngData));
            if (src == null || !hasNonOpaqueAlpha(src)) return pngData;
            BufferedImage flat = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = flat.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, src.getWidth(), src.getHeight());
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(flat, "png", out);
            return out.toByteArray();
        } catch (Exception ignore) {
            return pngData;
        }
    }

    private static boolean hasNonOpaqueAlpha(BufferedImage src) {
        if (src.getColorModel() == null || !src.getColorModel().hasAlpha()) return false;
        int width = src.getWidth();
        int height = src.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = (src.getRGB(x, y) >>> 24) & 0xff;
                if (alpha < 255) return true;
            }
        }
        return false;
    }
}
