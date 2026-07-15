package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
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

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
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

    private static ASTTextRun text(String text, String fontFamily) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        run.fontFamily(fontFamily);
        return run;
    }
}
