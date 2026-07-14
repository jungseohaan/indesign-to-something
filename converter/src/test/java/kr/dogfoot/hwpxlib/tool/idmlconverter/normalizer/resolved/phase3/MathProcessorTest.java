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
}
