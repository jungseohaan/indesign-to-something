package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextStyleApplicator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextRunSegmenter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

/** Executes Stage 1 SimpleButtonLabelPlan as an inline AST object. */
public final class SimpleButtonLabelInlineFactory {
    private SimpleButtonLabelInlineFactory() {}

    public static boolean hasPlan(ResolvedBuildContext ctx, int anchorDomId) {
        return ctx != null && ctx.simpleButtonLabelPlan(anchorDomId) != null;
    }

    public static ASTInlineObject create(ResolvedBuildContext ctx, int anchorDomId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        SimpleButtonLabelPlan plan = ctx.simpleButtonLabelPlan(anchorDomId);
        if (plan == null) return null;
        if (plan.mode == SimpleButtonLabelPlan.Mode.COMPLETE_PNG) {
            return InlineFrameHandler.loadCompleteSimpleButtonLabelInlineObject(ctx, anchorDomId);
        }
        return createTextShell(ctx, plan);
    }

    private static ASTInlineObject createTextShell(ResolvedBuildContext ctx, SimpleButtonLabelPlan plan) {
        ResolvedPageItem anchor = ctx.resolvedData.getPageItem(String.valueOf(plan.anchorDomId));
        ResolvedPageItem shell = ctx.resolvedData.getPageItem(String.valueOf(plan.shellDomId));
        if (anchor == null || shell == null || plan.labelText == null || plan.labelText.isEmpty()) {
            return null;
        }
        double[] bounds = anchor.geometricBounds();
        if (bounds == null || bounds.length < 4) bounds = anchor.pageRelativeBounds();
        if (bounds == null || bounds.length < 4) {
            bounds = shell.geometricBounds();
            if (bounds == null || bounds.length < 4) bounds = shell.pageRelativeBounds();
        }
        if (bounds == null || bounds.length < 4) return null;
        double w = Math.abs(bounds[3] - bounds[1]);
        double h = Math.abs(bounds[2] - bounds[0]);
        if (w <= 0 || h <= 0) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId("u" + Integer.toHexString(plan.anchorDomId));
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.verticalJustification("CenterAlign");
        obj.nativeGraphicsAllowed(true);

        String fillHex = ctx.resolvedData.resolveColorHex(shell.fillColorName());
        if (fillHex != null) {
            obj.fillColor(fillHex);
            obj.fillTint(shell.fillTint() > 0 ? shell.fillTint() : 100);
        }
        String strokeHex = ctx.resolvedData.resolveColorHex(shell.strokeColorName());
        if (strokeHex != null && shell.strokeWeight() > 0) {
            obj.strokeColor(strokeHex);
            obj.strokeTint(shell.strokeTint() > 0 ? shell.strokeTint() : 100);
            obj.strokeWeight(shell.strokeWeight());
        }
        if ("Oval".equals(shell.type())) {
            obj.cornerRadius(h / 2.0);
        } else if (shell.cornerRadius() > 0) {
            obj.cornerRadius(shell.cornerRadius());
        }

        ASTParagraph paragraph = new ASTParagraph();
        paragraph.alignment("CenterAlign");
        TextStyleApplicator.ExplicitStyle style = new TextStyleApplicator.ExplicitStyle();
        style.fontFamily = plan.labelFontFamily;
        style.fontStyle = plan.labelFontStyle;
        style.textColorHex = plan.labelTextColorHex;
        style.fontSizePt = labelFontSizePt(plan, h);
        style.tracking = plan.labelTracking;
        style.horizontalScale = plan.labelHorizontalScale;
        for (ASTTextRun run : TextRunSegmenter.fromSyntheticText(plan.labelText, style, false)) {
            paragraph.addItem(run);
        }
        obj.addParagraph(paragraph);
        return obj;
    }

    private static double labelFontSizePt(SimpleButtonLabelPlan plan, double shellHeightPt) {
        double size = plan.labelFontSizePt != null && plan.labelFontSizePt > 0
                ? plan.labelFontSizePt
                : 0.0;
        if (shellHeightPt > 0) {
            double max = shellHeightPt * 0.8;
            if (size <= 0 || size > max) size = max;
        }
        return size;
    }
}
