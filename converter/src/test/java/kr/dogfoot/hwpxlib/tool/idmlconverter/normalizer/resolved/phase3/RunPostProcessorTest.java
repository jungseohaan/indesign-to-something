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
        run.fontSizeHwpunits(850);
        para.addItem(run);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("x", ((ASTEquation) para.items().get(0)).hwpScript());
        Assert.assertEquals(Integer.valueOf(850),
                ((ASTEquation) para.items().get(0)).preferredBaseUnit());
    }

    @Test
    public void italicGeometryLabelBecomesOneSourceSizedEquation() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("ABC");
        run.fontFamily("EH상부자");
        run.fontStyle("Italic");
        run.fontSizeHwpunits(850);
        para.addItem(run);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("ABC", ((ASTEquation) para.items().get(0)).hwpScript());
        Assert.assertEquals(Integer.valueOf(850),
                ((ASTEquation) para.items().get(0)).preferredBaseUnit());
    }

    @Test
    public void punctuationDoesNotPreventItalicLabelEquation() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("A,");
        run.fontFamily("EH상부자");
        run.fontStyle("Italic");
        run.fontSizeHwpunits(850);
        para.addItem(run);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(2, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("A", ((ASTEquation) para.items().get(0)).hwpScript());
        Assert.assertEquals(Integer.valueOf(850),
                ((ASTEquation) para.items().get(0)).preferredBaseUnit());
        Assert.assertTrue(para.items().get(1) instanceof ASTTextRun);
        Assert.assertEquals(",", ((ASTTextRun) para.items().get(1)).text());
    }

    @Test
    public void italicUppercaseGeometryLabelIsNotParsedAsChemistry() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("ABC");
        run.fontFamily("Yoon Gothic");
        run.fontStyle("Italic");
        run.fontSizeHwpunits(850);

        RunBuilder.splitChemicalFormulasAndLatinVarsInMixedText(null, run, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertEquals("ABC", ((ASTTextRun) para.items().get(0)).text());
        Assert.assertEquals(Integer.valueOf(850),
                ((ASTTextRun) para.items().get(0)).fontSizeHwpunits());
    }

    @Test
    public void ehUppercaseGeometryLabelIsNotParsedAsChemistryBeforeItalicResolution() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("ABC");
        run.fontFamily("EH상부자");
        run.fontSizeHwpunits(850);

        RunBuilder.splitChemicalFormulasAndLatinVarsInMixedText(null, run, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertEquals("ABC", ((ASTTextRun) para.items().get(0)).text());
    }

    @Test
    public void triangleLabelRejoinsTextAndEquationFragments() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun marker = new ASTTextRun();
        marker.text("△");
        para.addItem(marker);
        ASTTextRun a = new ASTTextRun();
        a.text("A");
        para.addItem(a);
        ASTEquation bc = new ASTEquation("BC", "EH_FONT");
        bc.preferredBaseUnit(850);
        para.addItem(bc);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(2, para.items().size());
        Assert.assertTrue(para.items().get(1) instanceof ASTEquation);
        Assert.assertEquals("ABC", ((ASTEquation) para.items().get(1)).hwpScript());
        Assert.assertEquals(Integer.valueOf(850),
                ((ASTEquation) para.items().get(1)).preferredBaseUnit());
    }

    @Test
    public void ehYakmulPiAndEllipsisSurviveItalicMathGroupingWithCharacterStyleOnly() {
        ASTParagraph para = new ASTParagraph();

        ASTTextRun pi = new ASTTextRun();
        pi.text("p");
        pi.characterStyleRef("CharacterStyle/약물");
        pi.fontStyle("Plain");
        pi.fontSizeHwpunits(1000);
        para.addItem(pi);

        ASTTextRun digits = new ASTTextRun();
        digits.text("=3.14159265");
        digits.characterStyleRef("CharacterStyle/$ID/[No character style]");
        digits.fontFamily("EH상부자");
        digits.fontStyle("Italic");
        digits.fontSizeHwpunits(1000);
        para.addItem(digits);

        ASTTextRun ellipsis = new ASTTextRun();
        ellipsis.text("y");
        ellipsis.characterStyleRef("CharacterStyle/약물");
        ellipsis.fontStyle("Plain");
        ellipsis.fontSizeHwpunits(1000);
        para.addItem(ellipsis);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("pi=3.14159265 CDOTS",
                ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void mathProcessorRoutesYakmulPiThroughEHBeforeChemicalFormulaCluster() {
        ASTParagraph para = new ASTParagraph();

        ASTTextRun pi = new ASTTextRun();
        pi.text("p");
        pi.characterStyleRef("CharacterStyle/약물");
        pi.fontSizeHwpunits(1000);
        para.addItem(pi);

        ASTTextRun digits = new ASTTextRun();
        digits.text("=3.14159265");
        digits.fontFamily("EH상부자");
        digits.fontStyle("Italic");
        digits.fontSizeHwpunits(1000);
        para.addItem(digits);

        ASTTextRun ellipsis = new ASTTextRun();
        ellipsis.text("y");
        ellipsis.characterStyleRef("CharacterStyle/약물");
        ellipsis.fontSizeHwpunits(1000);
        para.addItem(ellipsis);

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("pi=3.14159265 CDOTS",
                ((ASTEquation) para.items().get(0)).hwpScript());
    }
}
