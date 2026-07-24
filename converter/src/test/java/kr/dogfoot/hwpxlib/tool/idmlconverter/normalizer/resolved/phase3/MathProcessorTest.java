package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import org.junit.Assert;
import org.junit.Test;

public class MathProcessorTest {
    @Test
    public void normalLatinTextWithHyphenDoesNotBecomeFormulaEquation() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("Self-Discovery and Growth");
        run.fontFamily("Helvetica Neue LT Std");
        run.fontStyle("55 Roman");
        para.addItem(run);

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertEquals("Self-Discovery and Growth", ((ASTTextRun) para.items().get(0)).text());
    }

    @Test
    public void formulaFontEvidenceMayStillProduceEquation() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("C□");
        run.fontFamily("BT수식M");
        run.grepMathFont(true);
        para.addItem(run);

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertTrue(para.items().stream().anyMatch(it -> it instanceof ASTEquation));
    }

    @Test
    public void inlineFractionEquationIsNotCollapsedWithBodyGlyphFormulaRuns() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("상대 습도(", "Sandoll 고딕NeoRound"));
        para.addItem(text("%", "BT수식H-분수N"));
        para.addItem(text(") ", "Sandoll 고딕NeoRound"));
        para.addItem(text("=", "BT수식H-분수N"));

        ASTEquation fraction = new ASTEquation(
                "{현재 공기의 수증기압(hPa)} over {현재 기온에서의 포화 수증기압(hPa)}",
                "INLINE_FRACTION");
        para.addItem(fraction);
        para.addItem(text("×100", "BT수식H-분수N"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(6, para.items().size());
        Assert.assertSame(fraction, para.items().get(4));
        Assert.assertEquals(
                "{현재 공기의 수증기압(hPa)} over {현재 기온에서의 포화 수증기압(hPa)}",
                ((ASTEquation) para.items().get(4)).hwpScript());
        Assert.assertEquals("×100", ((ASTTextRun) para.items().get(5)).text());
    }

    @Test
    public void ehPlainDecimalSurvivesWhenParagraphAlsoHasEquation() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("⑶ ", "Yoon가변 윤명조100Std_OTF"));
        para.addItem(text("0.36", "EH상부자"));
        para.addItem(text("\t\t⑷ ", "Yoon가변 윤명조100Std_OTF"));
        para.addItem(new ASTEquation("{4} over {81}", "EH_FONT"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(4, para.items().size());
        Assert.assertEquals("0.36", ((ASTTextRun) para.items().get(1)).text());
        Assert.assertTrue(para.items().get(3) instanceof ASTEquation);
    }

    @Test
    public void btBodyTextChemicalReactionContextProducesEquation() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("(가) ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("HCO", "BT수식H-분수N"));
        para.addItem(text("3", "BT수식H-분수N"));
        para.addItem(sup("-", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("+", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("H", "BT수식H-분수N"));
        para.addItem(text("2", "BT수식H-분수N"));
        para.addItem(text("O", "BT수식H-분수N"));
        para.addItem(text(" ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("\u2192", "BT수식-편한글씨"));
        para.addItem(text(" ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("CO", "BT수식H-분수N"));
        para.addItem(text("3", "BT수식H-분수N"));
        para.addItem(sup("2-", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("+", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("H", "BT수식H-분수N"));
        para.addItem(text("3", "BT수식H-분수N"));
        para.addItem(text("O", "BT수식H-분수N"));
        para.addItem(sup("+", "BT수식H-분수N"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(2, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertTrue(para.items().get(1) instanceof ASTEquation);
        Assert.assertEquals(
                "HCO_{3}^{-}+H_{2}O ~ rarrow ~ CO_{3}^{2-}+H_{3}O^{+}",
                ((ASTEquation) para.items().get(1)).hwpScript());
    }

    @Test
    public void formulaAnswerPlaceholdersDoNotBreakEquationFontReactionRange() {
        ResolvedBuildContext ctx = contextWithFormulaAnswerPlaceholders(7001, 7002);
        ASTParagraph para = new ASTParagraph();
        para.addItem(formula("N"));
        para.addItem(formula("2"));
        para.addItem(formula("+"));
        para.addItem(placeholder(7001));
        para.addItem(formula("H"));
        para.addItem(formula("2"));
        para.addItem(formula("\u2192"));
        para.addItem(placeholder(7002));
        para.addItem(formula("NH"));
        para.addItem(formula("3"));

        MathProcessor.convertMathRunsInParagraph(ctx, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals(
                "N_{2}+□H_{2} ~ rarrow ~ □NH_{3}",
                ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void textSeparatedAlgebraFragmentsAreStitched() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(new ASTEquation("1^{2}", "EH_FONT"));
        para.addItem(text("=1,", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("2^{2}", "EH_FONT"));
        para.addItem(text("=4", "[Yoon가변] 윤명조100_OTF"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("1^{2}=1,2^{2}=4", ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void overlineEquationsSeparatedByEqualsAreNotStitched() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(new ASTEquation("overline{AB}", "EH_FONT"));
        para.addItem(text("=1,", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("overline{CD}", "EH_FONT"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(3, para.items().size());
        Assert.assertEquals("overline{AB}", ((ASTEquation) para.items().get(0)).hwpScript());
        Assert.assertEquals("=1,", ((ASTTextRun) para.items().get(1)).text());
        Assert.assertEquals("overline{CD}", ((ASTEquation) para.items().get(2)).hwpScript());
    }

    private static ASTTextRun text(String text, String fontFamily) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        run.fontFamily(fontFamily);
        return run;
    }

    private static ASTTextRun formula(String text) {
        ASTTextRun run = text(text, "BT수식M");
        run.grepMathFont(true);
        return run;
    }

    private static ASTTextRun sup(String text, String fontFamily) {
        ASTTextRun run = text(text, fontFamily);
        run.characterStyleRef("00_수식(첨자-상부자)");
        return run;
    }

    private static ASTInlineObject placeholder(int sourceId) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        obj.sourceId(String.valueOf(sourceId));
        return obj;
    }

    private static ResolvedBuildContext contextWithFormulaAnswerPlaceholders(int... sourceIds) {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ResolvedData data = new ResolvedData();
        ctx.resolvedData = data;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = new ResolvedPageItem();
            item.id(String.valueOf(sourceId));
            item.type("Rectangle");
            item.geometricBounds(new double[] { 0, 0, 8, 8 });
            item.storyAnchorPlacement("INLINE");
            item.strokeColorName("Black");
            item.strokeWeight(1.0);
            data.addPageItem(item);
            ctx.ownershipPlans.add(ObjectPlan.legacyDefaulted(
                    sourceId,
                    "formula_answer_placeholder",
                    0,
                    TextAction.DROP_TEXT,
                    VisualAction.PLACE_INLINE_PNG,
                    VisualLayer.CONTENT_VISUAL,
                    Placement.INLINE,
                    sourceId,
                    new int[] { sourceId },
                    0,
                    "test_formula_answer_placeholder",
                    "rendered_frames/inline_" + sourceId + ".png",
                    null));
        }
        return ctx;
    }
}
