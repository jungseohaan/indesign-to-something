package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import org.junit.Assert;
import org.junit.Test;

public class RunPostProcessorTest {
    @Test
    public void longLatinPhrasesRemainEditableText() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("Self-Discovery and Growth");
        run.fontFamily("Helvetica Neue LT Std");
        run.fontStyle("Italic");
        para.addItem(run);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertEquals("Self-Discovery and Growth", ((ASTTextRun) para.items().get(0)).text());
    }

    @Test
    public void shortItalicVariablesStillBecomeEquations() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("x");
        run.fontFamily("Times New Roman");
        run.fontStyle("Italic");
        para.addItem(run);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("x", ((ASTEquation) para.items().get(0)).hwpScript());
    }
}
