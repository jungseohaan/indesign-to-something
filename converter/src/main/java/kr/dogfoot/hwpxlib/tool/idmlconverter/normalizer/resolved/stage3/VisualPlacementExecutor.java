package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import java.util.HashSet;
import java.util.Set;

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

        ASTFigure fig = buildFigure(rg, image, plan);
        if (ctx.visualActionByOwnershipPlan(rg) == VisualAction.PLACE_TEXT_SHELL) {
            ASTTextFrameBlock textShell = InlineFrameHandler.buildFloatingBadge(
                    ctx, rg, plan.x, plan.y, plan.width, plan.height);
            if (textShell != null) {
                textShell.zOrder(plan.zOrder);
                textShell.sourceId("page_obj_text_shell_" + rg.id());
                textShell.imageFillData(image.imageData);
                textShell.nativeGraphicsAllowed(true);
                textShell.forceImageFill(true);
                textShell.fromGroup(fig.fromGroup());
                removeEditableTextFrameBlocks(section, rg);
                section.addBlock(textShell);
                ctx.markRenderedVisualHandled(rg.id());
                ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.Phase6",
                        "PLACE_TEXT_SHELL",
                        "placed as ASTTextFrameBlock imageFill+drawText from ObjectPlan");
                return PlacementResult.textShellPlaced();
            }
        }

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

    private static void removeEditableTextFrameBlocks(ASTSection section, RenderedGroup rg) {
        if (section == null || section.blocks() == null || rg == null) return;
        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds == null || editableIds.length == 0) return;
        Set<String> sourceIds = new HashSet<>();
        for (String editableId : editableIds) {
            if (editableId == null || editableId.isEmpty()) continue;
            sourceIds.add(ParagraphTextHelpers.domIdToSourceId(editableId));
        }
        if (sourceIds.isEmpty()) return;
        section.blocks().removeIf(block ->
                block instanceof ASTTextFrameBlock
                        && block.sourceId() != null
                        && sourceIds.contains(block.sourceId()));
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
