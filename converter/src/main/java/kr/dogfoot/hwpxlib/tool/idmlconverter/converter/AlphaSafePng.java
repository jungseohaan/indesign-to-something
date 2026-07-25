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
        return prepareTextBoxImageFill(pngData, null);
    }

    static byte[] prepareTextBoxImageFill(byte[] pngData, String backgroundColor) {
        if (pngData == null || pngData.length == 0) return pngData;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngData));
            if (src == null) return pngData;
            Color bg = parseRgb(backgroundColor);
            boolean hasAlpha = hasNonOpaqueAlpha(src);
            if (hasAlpha) {
                src = trimVerticalTransparentPadding(src);
                hasAlpha = hasNonOpaqueAlpha(src);
            }
            if (bg == null) {
                return hasAlpha ? flattenOnto(src, Color.WHITE) : pngData;
            }
            if (!hasAlpha) {
                byte[] replaced = replaceBorderConnectedPaper(src, bg);
                return replaced != null ? replaced : pngData;
            }
            return flattenOnto(src, bg);
        } catch (Exception ignore) {
            return pngData;
        }
    }

    private static Color parseRgb(String color) {
        if (color == null) return null;
        String hex = color.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 8) {
            hex = hex.substring(2);
        }
        if (hex.length() != 6) return null;
        try {
            int rgb = Integer.parseInt(hex, 16);
            return new Color(rgb);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static byte[] flattenOntoWhiteIfNeeded(byte[] pngData) {
        if (pngData == null || pngData.length == 0) return pngData;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngData));
            if (src == null || !hasNonOpaqueAlpha(src)) return pngData;
            return flattenOnto(src, Color.WHITE);
        } catch (Exception ignore) {
            return pngData;
        }
    }

    private static byte[] flattenOnto(BufferedImage src, Color background) throws Exception {
        BufferedImage flat = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        try {
            g.setColor(background);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(flat, "png", out);
        return out.toByteArray();
    }

    private static byte[] replaceBorderConnectedPaper(BufferedImage src, Color background) throws Exception {
        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) return null;

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }

        boolean[] seen = new boolean[width * height];
        int[] queue = new int[width * height];
        int head = 0;
        int tail = 0;

        for (int x = 0; x < width; x++) {
            tail = enqueuePaper(out, seen, queue, tail, width, x, 0);
            tail = enqueuePaper(out, seen, queue, tail, width, x, height - 1);
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueuePaper(out, seen, queue, tail, width, 0, y);
            tail = enqueuePaper(out, seen, queue, tail, width, width - 1, y);
        }

        if (tail == 0) return null;
        int bgRgb = background.getRGB() & 0x00ffffff;
        int replaced = 0;
        while (head < tail) {
            int pos = queue[head++];
            int x = pos % width;
            int y = pos / width;
            out.setRGB(x, y, 0xff000000 | bgRgb);
            replaced++;
            if (x > 0) tail = enqueuePaper(out, seen, queue, tail, width, x - 1, y);
            if (x + 1 < width) tail = enqueuePaper(out, seen, queue, tail, width, x + 1, y);
            if (y > 0) tail = enqueuePaper(out, seen, queue, tail, width, x, y - 1);
            if (y + 1 < height) tail = enqueuePaper(out, seen, queue, tail, width, x, y + 1);
        }
        if (replaced == 0) return null;

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", outBytes);
        return outBytes.toByteArray();
    }

    private static int enqueuePaper(
            BufferedImage image,
            boolean[] seen,
            int[] queue,
            int tail,
            int width,
            int x,
            int y) {
        int pos = y * width + x;
        if (seen[pos]) return tail;
        seen[pos] = true;
        if (isNonWhiteRgb(image.getRGB(x, y))) return tail;
        queue[tail++] = pos;
        return tail;
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
        if (visibleH >= src.getHeight() || paddingRatio < 0.20) return src;
        if (visibleHorizontalCoverage(src, hasAlpha, top, bottom) < 0.60) return src;

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

    private static double visibleHorizontalCoverage(BufferedImage src, boolean hasAlpha, int top, int bottom) {
        int left = src.getWidth();
        int right = -1;
        for (int y = Math.max(0, top); y <= bottom && y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int argb = src.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                if (hasAlpha ? alpha > 8 : isNonWhiteRgb(argb)) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (right < left || src.getWidth() <= 0) return 0.0;
        return (double) (right - left + 1) / (double) src.getWidth();
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
