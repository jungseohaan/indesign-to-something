package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.PolicyLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualPlanePolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

/**
 * Stage 3 visual preparation rules that only read Stage 1 ownership plans.
 */
public final class VisualShellPreparationRules {
    private VisualShellPreparationRules() {
    }

    public static boolean isPaperFilledContainerShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!isRenderedContainerShell(ctx, rg)) return false;

        int[] sourceIds = rg.sourceObjectIds();
        boolean sawSource = false;
        boolean sawPaperFill = false;
        if (sourceIds != null) {
            for (int sourceId : sourceIds) {
                if (isPaperFilledPageItem(ctx, String.valueOf(sourceId))) {
                    sawSource = true;
                    sawPaperFill = true;
                    continue;
                }
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
                if (pi != null) return false;
            }
        }
        if (!sawSource) {
            return isPaperFilledPageItem(ctx, String.valueOf(rg.id()));
        }
        return sawPaperFill;
    }

    public static boolean shouldKnockOutContainerShell(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            String visualLayer,
            double rawLeft,
            double rawTop,
            double rawRight,
            double rawBottom,
            double pageWidth,
            double pageHeight) {
        if (ctx == null || rg == null) return false;
        ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
        if (plan == null || ShellRole.isTextShell(plan)) return false;
        if (plan.visualPolicyLayer() == PolicyLayer.BACKGROUND) return false;
        if (isForegroundVisualLayer(visualLayer)) return false;
        if (isPaperFilledContainerBackdrop(ctx, rg, visualLayer)) return false;
        if (isBackgroundLike(ctx, rg, rawLeft, rawTop, rawRight, rawBottom, pageWidth, pageHeight)) return false;
        return isRenderedContainerShell(ctx, rg);
    }

    public static boolean isPaperFilledContainerBackdrop(ResolvedBuildContext ctx, RenderedGroup rg, String visualLayer) {
        return ("CONTAINER_BACKDROP".equals(visualLayer) || "CONTAINER_FACE".equals(visualLayer))
                && isPaperFilledContainerShell(ctx, rg);
    }

    public static double minimumVisibleWidthForMasterEdgeStrip(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double rawLeft,
            double rawRight,
            double rawTop,
            double rawBottom,
            double cropRefLeft,
            double cropRefRight,
            double visLeft,
            double visRight,
            double pageWidth,
            double pageHeight) {
        if (ctx == null || rg == null || pageWidth >= 1e8) return 0.0;
        ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
        if (!isMasterStripPlan(plan)) return 0.0;

        boolean leftEdge = (rawLeft < -0.5 || cropRefLeft < -0.5) && Math.abs(visLeft) < 0.5;
        boolean rightEdge = (rawRight > pageWidth + 0.5 || cropRefRight > pageWidth + 0.5)
                && Math.abs(visRight - pageWidth) < 0.5;
        if (!leftEdge && !rightEdge) return 0.0;

        double fullW = Math.max(rawRight - rawLeft, cropRefRight - cropRefLeft);
        double fullH = rawBottom - rawTop;
        if (fullW <= 1.0 || fullH <= 0.5) return 0.0;
        double maxStripHeight = pageHeight < 1e8 ? Math.min(40.0, pageHeight * 0.15) : 40.0;
        if (fullH > maxStripHeight) return 0.0;

        return Math.max(Math.min(fullW * 0.60, 24.0), 0.0);
    }

    public static boolean isMasterEdgeStripPlan(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double pageWidth) {
        if (ctx == null || rg == null || pageWidth >= 1e8) return false;
        return isMasterStripPlan(ctx.findOwnershipPlanForRendered(rg));
    }

    private static boolean isRenderedContainerShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || rg == null) return false;
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        if (plan == null || !plan.hasVisibleVisual()) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && !ShellRole.isTextShell(plan)) return false;
        PolicyLayer layer = plan.visualPolicyLayer();
        if (layer != PolicyLayer.BACKGROUND && layer != PolicyLayer.DECORATION) return false;
        return hasContainerSizedBounds(rg);
    }

    private static boolean isBackgroundLike(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double rawLeft,
            double rawTop,
            double rawRight,
            double rawBottom,
            double pageWidth,
            double pageHeight) {
        if (pageWidth >= 1e9 || pageHeight >= 1e9) return false;
        double area = Math.max(0.0, rawRight - rawLeft) * Math.max(0.0, rawBottom - rawTop);
        boolean coversPageByArea = area >= 0.3 * pageWidth * pageHeight;
        boolean isFullPageBg = rawLeft <= 10.0
                && rawTop <= 10.0
                && (rawBottom >= pageHeight - 1.0 || coversPageByArea);
        return isFullPageBg || (isPlannedTextShell(ctx, rg) && coversPageByArea);
    }

    private static boolean isMasterStripPlan(ObjectPlan plan) {
        if (plan == null) return false;
        String reason = safe(plan.reason);
        return "master_graphic".equals(reason)
                || "haseera_graphic".equals(reason);
    }

    private static boolean isPlannedTextShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        ObjectPlan plan = ctx != null && rg != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        return ShellRole.isTextShell(plan);
    }

    private static boolean isForegroundVisualLayer(String visualLayer) {
        return VisualPlanePolicy.isInFrontLayerName(visualLayer);
    }

    private static boolean hasContainerSizedBounds(RenderedGroup rg) {
        if (rg == null) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w >= 18.0 && h >= 12.0 && area(b) >= 300.0;
    }

    private static boolean isPaperFilledPageItem(ResolvedBuildContext ctx, String domId) {
        if (ctx == null || ctx.resolvedData == null || domId == null) return false;
        ResolvedPageItem pi = ctx.resolvedData.getPageItem(domId);
        if (pi == null) return false;
        return isPaperColor(pi.fillColorName());
    }

    private static boolean isPaperColor(String colorName) {
        return "Paper".equals(colorName) || "[Paper]".equals(colorName);
    }

    private static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        return Math.max(0.0, b[2] - b[0]) * Math.max(0.0, b[3] - b[1]);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
