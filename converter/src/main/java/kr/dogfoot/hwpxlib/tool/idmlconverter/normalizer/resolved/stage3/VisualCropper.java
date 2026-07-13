package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 image decode/crop/mutation utilities.
 *
 * <p>Ownership and placement decisions are not made here. This class only mutates image pixels
 * for visuals that an executor has already decided to place.</p>
 */
public final class VisualCropper {
    private VisualCropper() {
    }

    public static BufferedImage decodePngBytes(byte[] pngData) {
        if (pngData == null || pngData.length == 0) return null;
        try {
            ConversionTiming.addCounter("phase6.pngBytes.imageDecodes", 1);
            return ImageIO.read(new ByteArrayInputStream(pngData));
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    public static BufferedImage invertVisiblePixels(BufferedImage img) {
        if (img == null) return null;
        BufferedImage inv = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a > 0) {
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    argb = (a << 24) | ((255 - r) << 16) | ((255 - g) << 8) | (255 - b);
                }
                inv.setRGB(x, y, argb);
            }
        }
        return inv;
    }

    public static BufferedImage knockOutPaperLikeFill(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return img;
        long total = (long) img.getWidth() * (long) img.getHeight();
        if (total <= 0) return img;

        long paperLike = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a < 220) continue;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (isPaperLikeRgb(r, g, b)) paperLike++;
            }
        }

        if ((double) paperLike / (double) total < 0.55) {
            return img;
        }

        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                out.setRGB(x, y, (a >= 180 && isPaperLikeRgb(r, g, b)) ? 0x00000000 : argb);
            }
        }
        return out;
    }

    public static AlphaCropResult alphaCrop(BufferedImage img) throws Exception {
        int[] bounds = alphaBounds(img);
        if (!shouldApplyAlphaCrop(img, bounds)) return null;
        int pxX = bounds[0];
        int pxY = bounds[1];
        int pxW = bounds[2];
        int pxH = bounds[3];
        BufferedImage cropped = img.getSubimage(pxX, pxY, pxW, pxH);
        return new AlphaCropResult(pxX, pxY, pxW, pxH, cropped, encodePng(cropped));
    }

    public static PageCropResult pageCrop(BufferedImage img, int pxX, int pxY, int pxW, int pxH) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            return null;
        }
        int clampedX = Math.max(0, Math.min(pxX, img.getWidth() - 1));
        int clampedY = Math.max(0, Math.min(pxY, img.getHeight() - 1));
        int clampedW = Math.max(1, Math.min(img.getWidth() - clampedX, pxW));
        int clampedH = Math.max(1, Math.min(img.getHeight() - clampedY, pxH));
        try {
            BufferedImage cropped = img.getSubimage(clampedX, clampedY, clampedW, clampedH);
            return new PageCropResult(
                    clampedX, clampedY, clampedW, clampedH,
                    cropped.getWidth(), cropped.getHeight(),
                    encodePng(cropped),
                    true);
        } catch (Exception ignored) {
            return new PageCropResult(
                    clampedX, clampedY, clampedW, clampedH,
                    img.getWidth(), img.getHeight(),
                    null,
                    false);
        }
    }

    public static PageCropPlan pageCropPlan(
            boolean masterEdgeStrip,
            BufferedImage img,
            int pageIdx,
            boolean hasCropSourceBounds,
            double rawLeft,
            double rawRight,
            double rawTop,
            double rawBottom,
            double cropRefLeft,
            double cropRefTop,
            double cropRefRight,
            double cropRefW,
            double cropRefH,
            double visLeft,
            double visTop,
            double visRight,
            double visBottom,
            double fullW,
            double pageWidthMm,
            double pageHeightMm) {
        boolean pageAnchoredStripCrop = !hasCropSourceBounds && shouldAnchorStripCropToPage(
                masterEdgeStrip, rawLeft, rawRight, rawTop, rawBottom, pageWidthMm, pageHeightMm);
        int[] stripRun = pageAnchoredStripCrop ? edgeAlphaRun(img, pageIdx) : null;
        if (stripRun != null) {
            int pxX = stripRun[0];
            int pxY = 0;
            int pxW = stripRun[1] - stripRun[0] + 1;
            int pxH = img.getHeight();
            return new PageCropPlan(
                    pxX, pxY, pxW, pxH,
                    pageAnchoredStripCrop);
        }

        int pxX = pageAnchoredStripCrop
                ? 0
                : (int) Math.round((visLeft - cropRefLeft) / cropRefW * img.getWidth());
        int pxY = (int) Math.round((visTop - cropRefTop) / cropRefH * img.getHeight());
        int pxW = pageAnchoredStripCrop
                ? (int) Math.round((visRight - visLeft) / fullW * img.getWidth())
                : (int) Math.round((visRight - cropRefLeft) / cropRefW * img.getWidth()) - pxX;
        int pxH = (int) Math.round((visBottom - cropRefTop) / cropRefH * img.getHeight()) - pxY;
        return new PageCropPlan(pxX, pxY, pxW, pxH, pageAnchoredStripCrop);
    }

    public static int[] edgeAlphaRun(BufferedImage img, int pageIdx) {
        if (img == null) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] colorRun = edgeColorRun(img, pageIdx);
        if (colorRun != null) return colorRun;

        boolean[] occupied = new boolean[w];
        int occupiedCount = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                    occupied[x] = true;
                    occupiedCount++;
                    break;
                }
            }
        }
        if (occupiedCount == 0 || occupiedCount > w * 0.35) return null;
        return selectEdgeRun(occupied, pageIdx, w);
    }

    private static int[] edgeColorRun(BufferedImage img, int pageIdx) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] occupied = new boolean[w];
        int occupiedCount = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a <= 8) continue;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max >= 80 && max - min >= 35) {
                    occupied[x] = true;
                    occupiedCount++;
                    break;
                }
            }
        }
        if (occupiedCount == 0 || occupiedCount > w * 0.35) return null;
        return selectEdgeRun(occupied, pageIdx, w);
    }

    private static int[] selectEdgeRun(boolean[] occupied, int pageIdx, int width) {
        int gapLimit = Math.max(8, width / 180);
        List<int[]> runs = new ArrayList<>();
        int runStart = -1;
        int lastOccupied = -1;
        for (int x = 0; x < width; x++) {
            if (!occupied[x]) continue;
            if (runStart < 0 || (lastOccupied >= 0 && x - lastOccupied > gapLimit)) {
                if (runStart >= 0) runs.add(new int[]{runStart, lastOccupied});
                runStart = x;
            }
            lastOccupied = x;
        }
        if (runStart >= 0) runs.add(new int[]{runStart, lastOccupied});
        if (runs.isEmpty()) return null;

        int[] selected = runs.get(0);
        if ((pageIdx & 1) == 1) {
            selected = runs.get(runs.size() - 1);
        }
        int pad = Math.max(2, width / 1000);
        int left = Math.max(0, selected[0] - pad);
        int right = Math.min(width - 1, selected[1] + pad);
        if (right - left + 1 > width * 0.35) return null;
        return new int[]{left, right};
    }

    private static int[] alphaBounds(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return null;
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha <= 10) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX || maxY < minY) return null;
        int pad = 1;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(img.getWidth() - 1, maxX + pad);
        maxY = Math.min(img.getHeight() - 1, maxY + pad);
        return new int[] { minX, minY, maxX - minX + 1, maxY - minY + 1 };
    }

    private static boolean shouldApplyAlphaCrop(BufferedImage img, int[] bounds) {
        if (img == null || bounds == null || bounds.length < 4) return false;
        int cropW = bounds[2];
        int cropH = bounds[3];
        if (cropW <= 0 || cropH <= 0) return false;
        int padLeft = bounds[0];
        int padTop = bounds[1];
        int padRight = img.getWidth() - (bounds[0] + cropW);
        int padBottom = img.getHeight() - (bounds[1] + cropH);
        int maxPad = Math.max(Math.max(padLeft, padRight), Math.max(padTop, padBottom));
        if (maxPad < 2) return false;
        double areaRatio = (double) cropW * (double) cropH
                / ((double) img.getWidth() * (double) img.getHeight());
        return areaRatio > 0.01 && areaRatio < 0.98;
    }

    private static boolean shouldAnchorStripCropToPage(boolean masterEdgeStrip,
                                                       double rawLeft,
                                                       double rawRight,
                                                       double rawTop,
                                                       double rawBottom,
                                                       double pageWidth,
                                                       double pageHeight) {
        if (!masterEdgeStrip) return false;
        if (pageWidth >= 1e8) return false;
        if (rawLeft >= -0.5 || rawRight <= pageWidth + 0.5) return false;
        double fullH = rawBottom - rawTop;
        double maxStripHeight = pageHeight < 1e8 ? Math.min(40.0, pageHeight * 0.15) : 40.0;
        if (fullH > maxStripHeight) return false;
        return true;
    }

    private static boolean isPaperLikeRgb(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return r >= 246 && g >= 246 && b >= 246 && (max - min) <= 8;
    }

    public static final class AlphaCropResult {
        public final int pxX;
        public final int pxY;
        public final int pxW;
        public final int pxH;
        public final BufferedImage image;
        public final byte[] imageData;

        AlphaCropResult(int pxX, int pxY, int pxW, int pxH, BufferedImage image, byte[] imageData) {
            this.pxX = pxX;
            this.pxY = pxY;
            this.pxW = pxW;
            this.pxH = pxH;
            this.image = image;
            this.imageData = imageData;
        }
    }

    public static final class PageCropPlan {
        public final int pxX;
        public final int pxY;
        public final int pxW;
        public final int pxH;
        public final boolean pageAnchoredStripCrop;

        PageCropPlan(
                int pxX,
                int pxY,
                int pxW,
                int pxH,
                boolean pageAnchoredStripCrop) {
            this.pxX = pxX;
            this.pxY = pxY;
            this.pxW = pxW;
            this.pxH = pxH;
            this.pageAnchoredStripCrop = pageAnchoredStripCrop;
        }
    }

    public static final class PageCropResult {
        public final int pxX;
        public final int pxY;
        public final int pxW;
        public final int pxH;
        public final int pixelW;
        public final int pixelH;
        public final byte[] imageData;
        public final boolean cropped;

        PageCropResult(
                int pxX,
                int pxY,
                int pxW,
                int pxH,
                int pixelW,
                int pixelH,
                byte[] imageData,
                boolean cropped) {
            this.pxX = pxX;
            this.pxY = pxY;
            this.pxW = pxW;
            this.pxH = pxH;
            this.pixelW = pixelW;
            this.pixelH = pixelH;
            this.imageData = imageData;
            this.cropped = cropped;
        }
    }
}
