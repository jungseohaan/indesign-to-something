package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Stage 3 visual executor: 텍스트프레임이 PNG로 베이킹될 때, 그 프레임의 inline visual
 * 자식들을 부모 PNG 위에 합성한다. ownership 판단을 새로 하지 않고, 이미 floating PNG로
 * 베이킹되기로 정해진 부모에 대해서만 inline 자식 픽셀을 합성하는 순수 이미지 연산이다.
 *
 * <p>SPEC-036: BackgroundInjector(Phase 6)에서 분리한 image 합성 로직.</p>
 */
public final class VisualTfInlineCompositor {

    private VisualTfInlineCompositor() {}

    private static final double TF_INLINE_VISUAL_UNION_MAX_RATIO = 1.25;
    private static final int TF_INLINE_VISUAL_MAX_CANVAS_PIXELS = 25_000_000;

    public static BufferedImage loadImageForPlacement(ResolvedBuildContext ctx, RenderedGroup rg, byte[] pngData) {
        if (ctx == null || rg == null || rg.file() == null) return null;
        try {
            BufferedImage base = VisualCropper.decodePngBytes(pngData);
            if (base == null || !shouldCompositeTfInlineVisuals(ctx, rg)) return base;
            BufferedImage merged = compositeTfInlineVisuals(ctx, rg, base);
            return merged != null ? merged : base;
        } catch (Exception e) {
            System.err.println("[VisualTfInlineCompositor] PNG 합성 실패: " + e.getMessage());
            return null;
        }
    }

    public static boolean hasTfInlineVisuals(RenderedGroup rg) {
        return rg != null && rg.tfInlineVisualIds() != null && rg.tfInlineVisualIds().length > 0;
    }

    public static boolean shouldCompositeTfInlineVisuals(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (!hasTfInlineVisuals(rg)) return false;
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        if (plan == null || !plan.hasVisibleVisual()) return false;
        return plan.textAction != TextAction.OWNED_BY_HWPX_TEXT;
    }

    public static double[] boundsWithTfInlineVisuals(
            ResolvedBuildContext ctx, RenderedGroup rg, double[] fallback) {
        if (!hasTfInlineVisuals(rg) || fallback == null || fallback.length < 4) return fallback;
        double[] union = new double[] { fallback[0], fallback[1], fallback[2], fallback[3] };
        for (int id : rg.tfInlineVisualIds()) {
            RenderedGroup child = findRenderedGroup(ctx, id);
            if (child == null || child.bounds() == null || child.bounds().length < 4) continue;
            double[] b = child.bounds();
            union[0] = Math.min(union[0], b[0]);
            union[1] = Math.min(union[1], b[1]);
            union[2] = Math.max(union[2], b[2]);
            union[3] = Math.max(union[3], b[3]);
        }
        double parentW = fallback[3] - fallback[1];
        double parentH = fallback[2] - fallback[0];
        double unionW = union[3] - union[1];
        double unionH = union[2] - union[0];
        if (parentW <= 0 || parentH <= 0 || unionW <= 0 || unionH <= 0) return fallback;
        double maxRatio = Math.max(unionW / parentW, unionH / parentH);
        if (maxRatio > TF_INLINE_VISUAL_UNION_MAX_RATIO) return fallback;
        return union;
    }

    private static BufferedImage compositeTfInlineVisuals(
            ResolvedBuildContext ctx, RenderedGroup parent, BufferedImage base) {
        if (ctx == null || parent == null || base == null || parent.bounds() == null
                || parent.bounds().length < 4 || !hasTfInlineVisuals(parent)) {
            return null;
        }
        double[] parentBounds = parent.bounds();
        double[] union = boundsWithTfInlineVisuals(ctx, parent, parentBounds);
        double unionW = union[3] - union[1];
        double unionH = union[2] - union[0];
        double parentW = parentBounds[3] - parentBounds[1];
        double parentH = parentBounds[2] - parentBounds[0];
        if (unionW <= 0 || unionH <= 0 || parentW <= 0 || parentH <= 0) return null;
        double maxRatio = Math.max(unionW / parentW, unionH / parentH);
        if (maxRatio > TF_INLINE_VISUAL_UNION_MAX_RATIO) return null;

        int canvasW = Math.max(1, (int) Math.round(base.getWidth() * unionW / parentW));
        int canvasH = Math.max(1, (int) Math.round(base.getHeight() * unionH / parentH));
        if ((long) canvasW * (long) canvasH > TF_INLINE_VISUAL_MAX_CANVAS_PIXELS) return null;
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        drawAtBounds(canvas, base, parentBounds, union);

        for (int id : parent.tfInlineVisualIds()) {
            RenderedGroup child = findRenderedGroup(ctx, id);
            if (child == null || child.file() == null || child.bounds() == null
                    || child.bounds().length < 4) {
                continue;
            }
            try {
                File childFile = new File(ctx.basePath, child.file());
                if (!childFile.exists()) continue;
                BufferedImage childImg = ImageIO.read(childFile);
                if (childImg == null) continue;
                drawAtBounds(canvas, childImg, child.bounds(), union);
                childImg.flush();
            } catch (Exception ignored) {
            }
        }
        return canvas;
    }

    private static void drawAtBounds(
            BufferedImage canvas, BufferedImage image, double[] bounds, double[] union) {
        int canvasW = canvas.getWidth();
        int canvasH = canvas.getHeight();
        double unionW = union[3] - union[1];
        double unionH = union[2] - union[0];
        int x = (int) Math.round((bounds[1] - union[1]) / unionW * canvasW);
        int y = (int) Math.round((bounds[0] - union[0]) / unionH * canvasH);
        int w = Math.max(1, (int) Math.round((bounds[3] - bounds[1]) / unionW * canvasW));
        int h = Math.max(1, (int) Math.round((bounds[2] - bounds[0]) / unionH * canvasH));
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(canvasW, x + w);
        int y1 = Math.min(canvasH, y + h);
        if (x0 >= x1 || y0 >= y1) return;
        for (int dy = y0; dy < y1; dy++) {
            int sy = Math.max(0, Math.min(image.getHeight() - 1,
                    (int) Math.floor((dy - y) * (double) image.getHeight() / h)));
            for (int dx = x0; dx < x1; dx++) {
                int sx = Math.max(0, Math.min(image.getWidth() - 1,
                        (int) Math.floor((dx - x) * (double) image.getWidth() / w)));
                int src = image.getRGB(sx, sy);
                int sa = (src >>> 24) & 0xFF;
                if (sa == 0) continue;
                if (sa == 255) {
                    canvas.setRGB(dx, dy, src);
                    continue;
                }
                int dst = canvas.getRGB(dx, dy);
                int da = (dst >>> 24) & 0xFF;
                int outA = sa + da * (255 - sa) / 255;
                if (outA == 0) {
                    canvas.setRGB(dx, dy, 0);
                    continue;
                }
                int sr = (src >> 16) & 0xFF;
                int sg = (src >> 8) & 0xFF;
                int sb = src & 0xFF;
                int dr = (dst >> 16) & 0xFF;
                int dg = (dst >> 8) & 0xFF;
                int db = dst & 0xFF;
                int outR = (sr * sa + dr * da * (255 - sa) / 255) / outA;
                int outG = (sg * sa + dg * da * (255 - sa) / 255) / outA;
                int outB = (sb * sa + db * da * (255 - sa) / 255) / outA;
                canvas.setRGB(dx, dy, (outA << 24) | (outR << 16) | (outG << 8) | outB);
            }
        }
    }

    private static RenderedGroup findRenderedGroup(ResolvedBuildContext ctx, int id) {
        if (ctx == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg != null && rg.id() == id) return rg;
        }
        return null;
    }
}
