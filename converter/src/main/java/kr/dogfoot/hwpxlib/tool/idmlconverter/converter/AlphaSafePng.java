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

    static byte[] prepareTextBoxImageFill(byte[] pngData) {
        if (pngData == null || pngData.length == 0) return pngData;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngData));
            if (src == null) return pngData;
            BufferedImage normalized = trimVerticalTransparentPadding(src);
            if (hasNonOpaqueAlpha(normalized)) {
                return flattenOntoWhite(normalized);
            }
            if (normalized != src) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(normalized, "png", out);
                return out.toByteArray();
            }
            return pngData;
        } catch (Exception ignore) {
            return pngData;
        }
    }

    static byte[] flattenOntoWhiteIfNeeded(byte[] pngData) {
        if (pngData == null || pngData.length == 0) return pngData;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngData));
            if (src == null || !hasNonOpaqueAlpha(src)) return pngData;
            return flattenOntoWhite(src);
        } catch (Exception ignore) {
            return pngData;
        }
    }

    private static byte[] flattenOntoWhite(BufferedImage src) throws Exception {
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
    }

    private static BufferedImage trimVerticalTransparentPadding(BufferedImage src) {
        if (src == null || src.getWidth() <= 2 || src.getHeight() <= 2) return src;
        boolean hasAlpha = src.getColorModel() != null && src.getColorModel().hasAlpha();

        int top = src.getHeight();
        int bottom = -1;
        for (int y = 0; y < src.getHeight(); y++) {
            boolean rowVisible = false;
            for (int x = 0; x < src.getWidth(); x++) {
                int argb = src.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                if (hasAlpha ? alpha > 8 : isNonWhiteRgb(argb)) {
                    rowVisible = true;
                    break;
                }
            }
            if (rowVisible) {
                if (top == src.getHeight()) top = y;
                bottom = y;
            }
        }
        if (bottom < top) return src;

        int visibleH = bottom - top + 1;
        int paddingH = src.getHeight() - visibleH;
        double visibleRatio = (double) visibleH / (double) src.getHeight();
        double paddingRatio = (double) paddingH / (double) src.getHeight();
        if (visibleH >= src.getHeight() || visibleRatio < 0.45 || paddingRatio < 0.20) return src;

        BufferedImage out = new BufferedImage(src.getWidth(), visibleH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src,
                    0, 0, src.getWidth(), visibleH,
                    0, top, src.getWidth(), bottom + 1,
                    null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static boolean isNonWhiteRgb(int argb) {
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>> 8) & 0xff;
        int b = argb & 0xff;
        return r < 247 || g < 247 || b < 247;
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
