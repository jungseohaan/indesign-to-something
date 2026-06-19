package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

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
            RenderedGroup rg,
            PreparedVisualImage prepared,
            double visLeft,
            double visTop,
            double visRight,
            double visBottom) {
        if (ctx == null || section == null || rg == null || prepared == null) {
            return null;
        }

        ObjectPlan ownershipPlan = ctx.findOwnershipPlanForRendered(rg);
        if (ownershipPlan == null || !ownershipPlan.hasVisibleVisual()) {
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

        Boolean planInFrontLayer = ctx.inFrontLayerByOwnershipPlan(rg);
        boolean fromGroup = Boolean.TRUE.equals(planInFrontLayer);
        String visualLayer = ctx.visualLayerByOwnershipPlan(rg);

        return new VisualPlacementPlan(x, y, w, h, ownershipPlan.zOrder, visualLayer, fromGroup);
    }
}
