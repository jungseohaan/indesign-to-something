package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.VisualSourcePolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 3 helper for synthetic GraphicLine visuals lost by empty parent PNG exports.
 *
 * <p>Ownership is assumed to have already allowed the parent visual pass. This helper only
 * restores visible child line pixels when InDesign exported an essentially transparent parent.</p>
 */
public final class VisualSyntheticLinePlacer {
    private VisualSyntheticLinePlacer() {
    }

    public static void injectSyntheticGraphicLines(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (!VisualSourcePolicy.useJavaSyntheticGraphicPngs()) return;
        if (ctx.resolvedData == null || ctx.resolvedData.pageItems() == null) return;
        List<RenderedGroup> floatingItems = ctx.resolvedData.allRenderedFloatingItems();
        if (floatingItems == null) return;

        Set<Integer> syntheticDone = new HashSet<>();

        for (RenderedGroup rg : floatingItems) {
            ObjectPlan parentPlan = ctx.findOwnershipPlanForRendered(rg);
            if (parentPlan == null || parentPlan.placement != Placement.FLOATING) continue;
            if (!isFloatingSyntheticVisualPlan(parentPlan)) {
                ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.SyntheticLine",
                        "SKIP_SYNTHETIC_LINE_PARENT_NOT_PLANNED",
                        "synthetic GraphicLine generation is plan-only");
                continue;
            }
            if (rg.childIds() == null || rg.childIds().length == 0) continue;
            if (parentPlan.file == null || parentPlan.file.isEmpty()) continue;

            File pngFile = new File(ctx.basePath, parentPlan.file);
            if (!pngFile.exists() || pngFile.length() > 1000) continue;

            int[] dims = VisualPngHeader.readDimensions(pngFile);
            if (dims == null || dims[0] < 50 || dims[1] < 5) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;
            if (ctx.resolvedData.pages() == null || pageIdx >= ctx.resolvedData.pages().size()) continue;

            double[] pgBounds = ctx.resolvedData.pages().get(pageIdx).bounds();
            if (pgBounds == null || pgBounds.length < 4) continue;
            double pagePtLeft = pgBounds[1];
            double pagePtTop = pgBounds[0];
            double pageWidthPt = pgBounds[3] - pgBounds[1];
            double pageHeightPt = pgBounds[2] - pgBounds[0];

            for (int cid : rg.childIds()) {
                if (syntheticDone.contains(cid)) continue;
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(String.valueOf(cid));
                if (pi == null || !"GraphicLine".equals(pi.type())) continue;
                ObjectPlan childPlan = ctx.findOwnershipPlanForDomId(cid);
                if (!isFloatingSyntheticVisualPlan(childPlan)) {
                    ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.SyntheticLine",
                            "SKIP_SYNTHETIC_LINE_CHILD_NOT_PLANNED",
                            "GraphicLine child " + cid + " is not owned by a visible floating ObjectPlan");
                    continue;
                }
                if (pi.strokeWeight() <= 0 || pi.strokeColorName() == null) continue;
                if (pi.opacity() <= 0) continue;

                double[] lineBounds = pi.geometricBounds();
                if (lineBounds == null || lineBounds.length < 4) continue;

                double lineX1 = lineBounds[1] - pagePtLeft;
                double lineX2 = lineBounds[3] - pagePtLeft;
                double lineY1 = lineBounds[0] - pagePtTop;
                double lineY2 = lineBounds[2] - pagePtTop;

                if (pi.parentId() != null) {
                    ResolvedPageItem parentPi = ctx.resolvedData.getPageItem(pi.parentId());
                    if (parentPi != null && parentPi.geometricBounds() != null) {
                        double[] fb = parentPi.geometricBounds();
                        lineX1 = Math.max(lineX1, fb[1] - pagePtLeft);
                        lineX2 = Math.min(lineX2, fb[3] - pagePtLeft);
                        lineY1 = Math.max(lineY1, fb[0] - pagePtTop);
                        lineY2 = Math.min(lineY2, fb[2] - pagePtTop);
                    }
                }

                lineX1 = Math.max(0.0, lineX1);
                lineX2 = Math.min(lineX2, pageWidthPt);
                if (lineX1 >= lineX2) continue;

                double strokePt = Math.max(pi.strokeWeight(), 1.0);
                double lineYCenter = (lineY1 + lineY2) / 2.0;
                double visTop = Math.max(0.0, lineYCenter - strokePt / 2.0);
                double visBottom = Math.min(lineYCenter + strokePt / 2.0, pageHeightPt);
                if (visTop >= visBottom) continue;

                byte[] imageData = generateSolidLinePng(pi, ctx);
                if (imageData == null) continue;

                long x = CoordinateConverter.pointsToHwpunits(lineX1);
                long y = CoordinateConverter.pointsToHwpunits(visTop);
                long w = CoordinateConverter.pointsToHwpunits(lineX2 - lineX1);
                long h = CoordinateConverter.pointsToHwpunits(visBottom - visTop);
                if (w <= 0 || h <= 0) continue;

                ASTFigure fig = new ASTFigure();
                fig.x(x);
                fig.y(y);
                fig.width(w);
                fig.height(h);
                fig.imageData(imageData);
                fig.imageFormat("png");
                fig.pixelWidth(100);
                fig.pixelHeight(4);
                fig.zOrder(childPlan.zOrder);
                if (childPlan.visualLayer != null) {
                    fig.visualLayer(childPlan.visualLayer.name());
                }
                fig.fromGroup(true);
                fig.sourceId("synth_line_" + cid);
                sections.get(pageIdx).addBlockAtFront(fig);
                syntheticDone.add(cid);
                System.err.println("[VisualSyntheticLinePlacer] synthetic line id=" + cid
                        + " x=" + String.format("%.1f", lineX1) + "pt y=" + String.format("%.1f", visTop)
                        + "pt w=" + String.format("%.1f", lineX2 - lineX1) + "pt h="
                        + String.format("%.1f", visBottom - visTop)
                        + "pt hwpW=" + w + " hwpH=" + h + " stroke=" + pi.strokeColorName());
            }
        }
    }

    private static boolean isFloatingSyntheticVisualPlan(ObjectPlan plan) {
        if (plan == null || !plan.hasVisibleVisual()) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) return false;
        return plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                || ShellRole.isTextShell(plan);
    }

    private static byte[] generateSolidLinePng(ResolvedPageItem pi, ResolvedBuildContext ctx) {
        String colorName = pi.strokeColorName();
        int r = 128, g = 128, b = 128;
        String hex = ctx.resolvedData.resolveColorHex(colorName);
        if (hex != null && hex.startsWith("#") && hex.length() >= 7) {
            try {
                r = Integer.parseInt(hex.substring(1, 3), 16);
                g = Integer.parseInt(hex.substring(3, 5), 16);
                b = Integer.parseInt(hex.substring(5, 7), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        int alpha = (int) Math.round(255 * pi.opacity() / 100.0);
        try {
            BufferedImage img = new BufferedImage(100, 4, BufferedImage.TYPE_INT_ARGB);
            int argb = (alpha << 24) | (r << 16) | (g << 8) | b;
            for (int px = 0; px < 100; px++) {
                for (int py = 0; py < 4; py++) {
                    img.setRGB(px, py, argb);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
