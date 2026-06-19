package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
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
        if (ctx == null || section == null || rg == null || image == null || plan == null
                || !plan.hasPositiveSize()) {
            return PlacementResult.notPlaced();
        }

        if (ctx.visualActionByOwnershipPlan(rg) == VisualAction.PLACE_TEXT_SHELL) {
            ASTFigure fig = buildFigure(rg, image, plan);
            addTextShellBehindTextAbovePageBackground(section, fig);
            ctx.markRenderedVisualHandled(rg.id());
            ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.Phase6",
                    "PLACE_TEXT_SHELL_FIGURE",
                    "placed extracted InDesign textless shell as ASTFigure; editable text is owned by Stage2 HWPX TF");
            return PlacementResult.textShellPlaced();
        }

        ASTFigure fig = buildFigure(rg, image, plan);
        section.addBlockAtFront(fig);
        ctx.recordRenderedDecision(rg, "Phase6", "PLACE", "placed as ASTFigure");
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
        fig.fromGroup(plan.fromGroup);
        fig.sourceId("page_obj_" + rg.id());
        return fig;
    }

    private static void addTextShellBehindTextAbovePageBackground(ASTSection section, ASTFigure fig) {
        int index = 0;
        while (index < section.blocks().size()) {
            ASTBlock block = section.blocks().get(index);
            if (!(block instanceof ASTFigure)) break;
            ASTFigure existing = (ASTFigure) block;
            if (visualOrderRank(existing) > visualOrderRank(fig)) break;
            if (visualOrderRank(existing) == visualOrderRank(fig)
                    && existing.zOrder() > fig.zOrder()) {
                break;
            }
            index++;
        }
        section.blocks().add(index, fig);
    }

    private static int visualOrderRank(ASTFigure fig) {
        if (fig == null) return 2;
        String layer = fig.visualLayer();
        if ("PAGE_BACKGROUND".equals(layer)) return 0;
        if (fig.zOrder() <= -5000) return 0;
        if ("CONTAINER_BACKDROP".equals(layer)
                || "TEXT_CARD_BACKDROP".equals(layer)
                || "LABEL_BACKDROP".equals(layer)
                || "LABEL_OVERLAY_BACKDROP".equals(layer)) {
            return 1;
        }
        return 2;
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
