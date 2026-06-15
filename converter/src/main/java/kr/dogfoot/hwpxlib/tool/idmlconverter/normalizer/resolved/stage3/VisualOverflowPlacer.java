package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Stage 3 helper for spread-crossing visual fragments.
 *
 * <p>This class does not decide ownership. It only crops and places adjacent-page
 * copies for a visual that the Stage 3 executor has already chosen to render.</p>
 */
public final class VisualOverflowPlacer {
    private VisualOverflowPlacer() {
    }

    public static int placeSpreadOverflowCopies(
            ResolvedBuildContext ctx,
            List<ASTSection> sections,
            RenderedGroup rg,
            int pageIdx,
            double rawLeft,
            double rawTop,
            double rawRight,
            double rawBottom,
            double fullW,
            double fullH,
            double pageWidthMm,
            byte[] originalImageData,
            String visualLayer) {
        if (originalImageData == null || fullW <= 1.0 || fullH <= 1.0) {
            return 0;
        }
        int placed = 0;

        boolean overflowsRight = rawRight > pageWidthMm + 10.0 && pageIdx + 1 < sections.size();
        if (overflowsRight) {
            int nextPageIdx = pageIdx + 1;
            double nextPageWidthMm = 1e9, nextPageHeightMm = 1e9;
            if (ctx.resolvedData.pages() != null && nextPageIdx < ctx.resolvedData.pages().size()) {
                double[] npB = ctx.resolvedData.pages().get(nextPageIdx).bounds();
                if (npB != null && npB.length >= 4) {
                    nextPageWidthMm = (npB[3] - npB[1]) / ctx.scaleFactor;
                    nextPageHeightMm = (npB[2] - npB[0]) / ctx.scaleFactor;
                }
            }
            double nextVisLeft = Math.max(0.0, rawLeft - pageWidthMm);
            double nextVisTop = Math.max(0.0, rawTop);
            double nextVisRight = Math.min(rawRight - pageWidthMm, nextPageWidthMm);
            double nextVisBottom = Math.min(rawBottom, nextPageHeightMm);
            if (nextVisLeft < nextVisRight && nextVisTop < nextVisBottom) {
                OverflowImage overflow = cropOverflowImage(
                        originalImageData,
                        nextVisLeft + pageWidthMm,
                        nextVisTop,
                        nextVisRight + pageWidthMm,
                        nextVisBottom,
                        rawLeft,
                        rawTop,
                        fullW,
                        fullH);

                long nx = CoordinateConverter.pointsToHwpunits(nextVisLeft * ctx.scaleFactor);
                long ny = CoordinateConverter.pointsToHwpunits(nextVisTop * ctx.scaleFactor);
                long nw = CoordinateConverter.pointsToHwpunits((nextVisRight - nextVisLeft) * ctx.scaleFactor);
                long nh = CoordinateConverter.pointsToHwpunits((nextVisBottom - nextVisTop) * ctx.scaleFactor);
                if (nw > 0 && nh > 0) {
                    ASTFigure fig = buildOverflowFigure(
                            rg, overflow, nx, ny, nw, nh, "page_obj_" + rg.id() + "_ov");
                    if (visualLayer != null) {
                        fig.visualLayer(visualLayer);
                    }
                    sections.get(nextPageIdx).addBlockAtFront(fig);
                    ctx.recordRenderedDecision(rg, "Stage3.VisualOverflowPlacer", "PLACE_OVERFLOW_NEXT_PAGE",
                            "spread-crossing visual placed on next page");
                    placed++;
                }
            }
        }

        boolean overflowsLeft = rawLeft < -10.0 && pageIdx - 1 >= 0;
        if (overflowsLeft) {
            int prevPageIdx = pageIdx - 1;
            double prevPageWidthMm = 1e9, prevPageHeightMm = 1e9;
            if (ctx.resolvedData.pages() != null && prevPageIdx < ctx.resolvedData.pages().size()) {
                double[] ppB = ctx.resolvedData.pages().get(prevPageIdx).bounds();
                if (ppB != null && ppB.length >= 4) {
                    prevPageWidthMm = (ppB[3] - ppB[1]) / ctx.scaleFactor;
                    prevPageHeightMm = (ppB[2] - ppB[0]) / ctx.scaleFactor;
                }
            }
            double prevVisLeft = Math.max(0.0, rawLeft + pageWidthMm);
            double prevVisTop = Math.max(0.0, rawTop);
            double prevVisRight = Math.min(rawRight + pageWidthMm, prevPageWidthMm);
            double prevVisBottom = Math.min(rawBottom, prevPageHeightMm);
            if (prevVisLeft < prevVisRight && prevVisTop < prevVisBottom) {
                OverflowImage overflow = cropOverflowImage(
                        originalImageData,
                        prevVisLeft - pageWidthMm,
                        prevVisTop,
                        prevVisRight - pageWidthMm,
                        prevVisBottom,
                        rawLeft,
                        rawTop,
                        fullW,
                        fullH);

                long px = CoordinateConverter.pointsToHwpunits(prevVisLeft * ctx.scaleFactor);
                long py = CoordinateConverter.pointsToHwpunits(prevVisTop * ctx.scaleFactor);
                long pw = CoordinateConverter.pointsToHwpunits((prevVisRight - prevVisLeft) * ctx.scaleFactor);
                long ph = CoordinateConverter.pointsToHwpunits((prevVisBottom - prevVisTop) * ctx.scaleFactor);
                if (pw > 0 && ph > 0) {
                    ASTFigure fig = buildOverflowFigure(
                            rg, overflow, px, py, pw, ph, "page_obj_" + rg.id() + "_ov_prev");
                    if (visualLayer != null) {
                        fig.visualLayer(visualLayer);
                    }
                    sections.get(prevPageIdx).addBlockAtFront(fig);
                    ctx.recordRenderedDecision(rg, "Stage3.VisualOverflowPlacer", "PLACE_OVERFLOW_PREVIOUS_PAGE",
                            "spread-crossing visual placed on previous page");
                    placed++;
                }
            }
        }

        return placed;
    }

    private static ASTFigure buildOverflowFigure(
            RenderedGroup rg,
            OverflowImage image,
            long x,
            long y,
            long width,
            long height,
            String sourceId) {
        ASTFigure fig = new ASTFigure();
        fig.x(x);
        fig.y(y);
        fig.width(width);
        fig.height(height);
        fig.imageData(image.data);
        String fmt = rg.imageFormat();
        fig.imageFormat((fmt != null && !fmt.isEmpty()) ? fmt : "png");
        fig.pixelWidth(image.pixelWidth);
        fig.pixelHeight(image.pixelHeight);
        fig.zOrder(0);
        fig.fromGroup(false);
        fig.sourceId(sourceId);
        return fig;
    }

    private static OverflowImage cropOverflowImage(
            byte[] originalImageData,
            double cropLeft,
            double cropTop,
            double cropRight,
            double cropBottom,
            double rawLeft,
            double rawTop,
            double fullW,
            double fullH) {
        byte[] overflowData = originalImageData;
        int pixelW = 0, pixelH = 0;
        try {
            BufferedImage img = decodePngBytes(overflowData);
            if (img != null) {
                int pxX = (int) Math.round((cropLeft - rawLeft) / fullW * img.getWidth());
                int pxY = (int) Math.round((cropTop - rawTop) / fullH * img.getHeight());
                int pxW = (int) Math.round((cropRight - rawLeft) / fullW * img.getWidth()) - pxX;
                int pxH = (int) Math.round((cropBottom - rawTop) / fullH * img.getHeight()) - pxY;
                pxX = Math.max(0, Math.min(pxX, img.getWidth() - 1));
                pxY = Math.max(0, Math.min(pxY, img.getHeight() - 1));
                pxW = Math.max(1, Math.min(img.getWidth() - pxX, pxW));
                pxH = Math.max(1, Math.min(img.getHeight() - pxY, pxH));
                try {
                    BufferedImage cropped = img.getSubimage(pxX, pxY, pxW, pxH);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(cropped, "png", baos);
                    overflowData = baos.toByteArray();
                    pixelW = cropped.getWidth();
                    pixelH = cropped.getHeight();
                    cropped.flush();
                } catch (Exception ignored) {
                    pixelW = img.getWidth();
                    pixelH = img.getHeight();
                }
                img.flush();
            }
        } catch (Exception ignored) {
        }
        return new OverflowImage(overflowData, pixelW, pixelH);
    }

    private static BufferedImage decodePngBytes(byte[] pngData) {
        if (pngData == null) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(pngData));
        } catch (Exception e) {
            return null;
        }
    }

    private static final class OverflowImage {
        final byte[] data;
        final int pixelWidth;
        final int pixelHeight;

        OverflowImage(byte[] data, int pixelWidth, int pixelHeight) {
            this.data = data;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
        }
    }
}
