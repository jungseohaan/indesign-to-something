package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import java.util.List;

/**
 * Stage 3 visual placement planner.
 *
 * <p>This class decides geometry, z-order, and HWPX plane for already-owned
 * visible visual output. Execution code must only materialize this plan.</p>
 */
public final class VisualPlacementPlanBuilder {
    private VisualPlacementPlanBuilder() {
    }

    public static VisualPlacementPlan build(
            ResolvedBuildContext ctx,
            ASTSection section,
            List<RenderedGroup> floatingItems,
            RenderedGroup rg,
            PreparedVisualImage prepared,
            double visLeft,
            double visTop,
            double visRight,
            double visBottom,
            String visualLayer,
            boolean isBackgroundLike,
            boolean planKeepsForegroundZ,
            boolean isContainerVisualShell,
            boolean isInferredTextFrameVisualShell,
            boolean isTextFrameVisualShell) {
        if (ctx == null || rg == null || prepared == null) {
            return null;
        }

        long x = CoordinateConverter.pointsToHwpunits(visLeft * ctx.scaleFactor);
        long y = CoordinateConverter.pointsToHwpunits(visTop * ctx.scaleFactor);
        long w = CoordinateConverter.pointsToHwpunits((visRight - visLeft) * ctx.scaleFactor);
        long h = CoordinateConverter.pointsToHwpunits((visBottom - visTop) * ctx.scaleFactor);
        if (prepared.hasStripCropOverride()) {
            x = CoordinateConverter.pointsToHwpunits(prepared.stripCropLeftOverride * ctx.scaleFactor);
            w = CoordinateConverter.pointsToHwpunits(prepared.stripCropWidthOverride * ctx.scaleFactor);
        }
        if (w <= 0 || h <= 0) {
            return null;
        }

        boolean isTextFrameBackdrop = VisualZOrderPlanner.inferredTextLineBackdropZOrder(ctx, rg) >= 0;
        boolean isEditableLabelShell = VisualLayeringRules.isEditableLabelShellCandidate(rg);
        boolean isTextOwnedVisualShell = VisualLayeringRules.isTextOwnedVisualShell(rg) && !isEditableLabelShell;
        boolean isTextOwnedRenderedContent = VisualLayeringRules.isTextOwnedRenderedContent(rg);
        int resolvedZ = isBackgroundLike
                ? 0
                : Math.max(5, VisualZOrderPlanner.effectiveZOrder(ctx, rg));
        if (prepared.hasStripCropOverride()) {
            resolvedZ = Math.max(resolvedZ, 900);
        }
        boolean completePngSimpleButtonLabel = VisualLayeringRules.isCompletePngSimpleButtonLabel(ctx, rg);
        if (completePngSimpleButtonLabel) {
            resolvedZ = Math.max(resolvedZ,
                    VisualOverlapZOrderPlanner.foregroundMarkerZOrder(section, x, y, w, h, resolvedZ));
        }

        boolean demotedBehindForeground = false;
        if (!planKeepsForegroundZ) {
            int containerAdjustedZ = VisualOverlapZOrderPlanner.containerShellZOrderBehindRenderedContent(
                    ctx, floatingItems, rg, resolvedZ);
            if (containerAdjustedZ < resolvedZ) {
                resolvedZ = containerAdjustedZ;
                demotedBehindForeground = true;
            }
        }
        if (!planKeepsForegroundZ
                && !isBackgroundLike && !isTextFrameBackdrop
                && !isTextOwnedVisualShell && !isTextOwnedRenderedContent
                && !isEditableLabelShell
                && !completePngSimpleButtonLabel) {
            int adjustedZ = VisualOverlapZOrderPlanner.foregroundOverlapShellZOrder(
                    section, rg, x, y, w, h, resolvedZ);
            demotedBehindForeground = demotedBehindForeground || adjustedZ < resolvedZ;
            resolvedZ = adjustedZ;
        }

        boolean isPaperOnlyContainerShell = VisualOverlapZOrderPlanner.isPaperOnlyContainerShell(ctx, rg);
        if (!planKeepsForegroundZ
                && isContainerVisualShell
                && !isInferredTextFrameVisualShell
                && (!rg.zOrderKnown() || isPaperOnlyContainerShell)) {
            resolvedZ = Math.max(1, Math.min(resolvedZ, 4));
        }

        boolean inlineBadgeGraphic = VisualLayeringRules.isBadgeShellGraphicBehind(rg);
        if (inlineBadgeGraphic) {
            resolvedZ = 1;
        }

        boolean keepShellInFrontLayer = isContainerVisualShell
                || (isTextFrameVisualShell && !isBackgroundLike);
        Boolean planInFrontLayer = ctx.inFrontLayerByOwnershipPlan(rg);
        boolean fromGroup = inlineBadgeGraphic ? false : (planInFrontLayer != null
                ? planInFrontLayer
                : keepShellInFrontLayer
                || !(isBackgroundLike || isTextFrameBackdrop || isTextOwnedVisualShell
                || demotedBehindForeground));

        return new VisualPlacementPlan(x, y, w, h, resolvedZ, visualLayer, fromGroup);
    }
}
