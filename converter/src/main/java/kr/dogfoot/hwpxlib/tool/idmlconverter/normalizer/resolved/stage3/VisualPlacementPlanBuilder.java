package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualPlanePolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

/**
 * Stage 3 visual placement adapter.
 *
 * <p>Ownership, z-order, and HWPX plane are already decided by Stage 1
 * {@link ObjectPlan}. This class only converts planned page-local geometry to
 * HWPX units and copies the planned visual-layer/z-order fields into an
 * executable placement record.</p>
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
        ObjectPlan ownershipPlan = ctx != null && rg != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        return build(ctx, section, rg, prepared, ownershipPlan, visLeft, visTop, visRight, visBottom);
    }

    public static VisualPlacementPlan build(
            ResolvedBuildContext ctx,
            ASTSection section,
            RenderedGroup rg,
            PreparedVisualImage prepared,
            ObjectPlan ownershipPlan,
            double visLeft,
            double visTop,
            double visRight,
            double visBottom) {
        if (ctx == null || section == null || rg == null || prepared == null) {
            return null;
        }

        if (ownershipPlan == null || !ownershipPlan.hasVisibleVisual()) {
            return null;
        }

        long x = CoordinateConverter.pointsToHwpunits(visLeft * ctx.scaleFactor);
        long y = CoordinateConverter.pointsToHwpunits(visTop * ctx.scaleFactor);
        long w = CoordinateConverter.pointsToHwpunits((visRight - visLeft) * ctx.scaleFactor);
        long h = CoordinateConverter.pointsToHwpunits((visBottom - visTop) * ctx.scaleFactor);
        if (w <= 0 || h <= 0) {
            return null;
        }

        boolean fromGroup = VisualPlanePolicy.isInFrontLayer(ownershipPlan.visualLayer);
        String visualLayer = ownershipPlan.visualLayer != null ? ownershipPlan.visualLayer.name() : null;
        int zOrder = ownershipPlan.zOrder;
        int sourceLayerIndex = ownershipPlan.sourceLayerIndex;

        return new VisualPlacementPlan(x, y, w, h, zOrder, visualLayer, sourceLayerIndex, fromGroup);
    }
}
