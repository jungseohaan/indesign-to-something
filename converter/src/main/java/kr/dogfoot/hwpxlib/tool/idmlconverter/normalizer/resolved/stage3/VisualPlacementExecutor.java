package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

/**
 * Stage 3 visible-output executor.
 *
 * <p>Ownership, layer, z-order, and geometry are decided before this class is called.
 * This class only materializes the already-decided visual output into AST nodes.</p>
 */
public final class VisualPlacementExecutor {
    private VisualPlacementExecutor() {
    }

    public static PlacementResult place(
            ResolvedBuildContext ctx,
            ASTSection section,
            RenderedGroup rg,
            PreparedVisualImage image,
            VisualPlacementPlan plan) {
        ObjectPlan ownershipPlan = ctx != null && rg != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        return place(ctx, section, rg, image, plan, ownershipPlan);
    }

    public static PlacementResult place(
            ResolvedBuildContext ctx,
            ASTSection section,
            RenderedGroup rg,
            PreparedVisualImage image,
            VisualPlacementPlan plan,
            ObjectPlan ownershipPlan) {
        if (ctx == null || section == null || rg == null || image == null || plan == null
                || !plan.hasPositiveSize()) {
            return PlacementResult.notPlaced();
        }

        ShellRole shellRole = ShellRole.from(ownershipPlan);
        if (shellRole != ShellRole.NONE) {
            ASTFigure fig = buildFigure(rg, image, plan);
            addVisualByPlannedOrder(section, fig);
            ctx.markRenderedVisualHandled(rg.id());
            ctx.recordRenderedDecision(rg, ownershipPlan, "Stage3.VisualBuilder.Phase6",
                    "PLACE_" + shellRole.name(),
                    "placed planned " + shellRole.name() + " as ASTFigure");
            return PlacementResult.textShellPlaced();
        }

        ASTFigure fig = buildFigure(rg, image, plan);
        addVisualByPlannedOrder(section, fig);
        String decision = ownershipPlan != null
                && ownershipPlan.materialization == kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization.TEXTLESS_VISUAL_FRAGMENT
                ? "PLACE_TEXTLESS_VISUAL_FRAGMENT"
                : "PLACE";
        ctx.recordRenderedDecision(rg, ownershipPlan, "Phase6", decision, "placed as ASTFigure");
        return PlacementResult.figurePlaced();
    }

    private static ASTFigure buildFigure(
            RenderedGroup rg,
            PreparedVisualImage image,
            VisualPlacementPlan plan) {
        ASTFigure fig = new ASTFigure();
        fig.x(plan.x);
        fig.y(plan.y);
        fig.width(plan.width);
        fig.height(plan.height);
        fig.imageData(image.imageData);
        String fmt = rg.imageFormat();
        fig.imageFormat((fmt != null && !fmt.isEmpty()) ? fmt : "png");
        fig.pixelWidth(image.pixelW);
        fig.pixelHeight(image.pixelH);
        fig.zOrder(plan.zOrder);
        if (plan.visualLayer != null) {
            fig.visualLayer(plan.visualLayer);
        }
        fig.sourceLayerIndex(plan.sourceLayerIndex);
        fig.fromGroup(plan.fromGroup);
        fig.sourceId("page_obj_" + rg.id());
        return fig;
    }

    private static void addVisualByPlannedOrder(ASTSection section, ASTFigure fig) {
        int index = 0;
        while (index < section.blocks().size()) {
            ASTBlock block = section.blocks().get(index);
            if (!(block instanceof ASTFigure)) break;
            ASTFigure existing = (ASTFigure) block;
            if (existing.zOrder() < fig.zOrder()) break;
            index++;
        }
        section.blocks().add(index, fig);
    }

    public static final class PlacementResult {
        public final boolean placed;
        public final boolean textShellPlaced;

        private PlacementResult(boolean placed, boolean textShellPlaced) {
            this.placed = placed;
            this.textShellPlaced = textShellPlaced;
        }

        static PlacementResult notPlaced() {
            return new PlacementResult(false, false);
        }

        static PlacementResult figurePlaced() {
            return new PlacementResult(true, false);
        }

        static PlacementResult textShellPlaced() {
            return new PlacementResult(true, true);
        }
    }
}
