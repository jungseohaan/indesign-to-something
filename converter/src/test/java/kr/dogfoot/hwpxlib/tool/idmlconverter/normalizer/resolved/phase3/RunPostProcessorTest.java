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
    public void ehMathVariablesRemainEquationsBesideKoreanParticles() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(ehItalicMathRun("a>0"));
        para.addItem(bodyRun("일 때 "));
        para.addItem(ehItalicMathRun("a"));
        para.addItem(bodyRun("와 "));
        para.addItem(ehItalicMathRun("a"));
        para.addItem(bodyRun("의 대소 관계"));

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertEquals(6, para.items().size());
        assertEquation(para, 0, "a>0");
        Assert.assertEquals("일 때 ", ((ASTTextRun) para.items().get(1)).text());
        assertEquation(para, 2, "a");
        Assert.assertEquals("와 ", ((ASTTextRun) para.items().get(3)).text());
        assertEquation(para, 4, "a");
        Assert.assertEquals("의 대소 관계", ((ASTTextRun) para.items().get(5)).text());
    }

    private static ASTTextRun ehItalicMathRun(String text) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        run.fontFamily("EH상부자");
        run.fontStyle("Italic");
        run.fontSizeHwpunits(850);
        run.characterStyleRef("CharacterStyle/상부자(이탤릭)");
        return run;
    }

    private static ASTTextRun bodyRun(String text) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        run.fontFamily("Sandoll 고딕NeoRound");
        run.fontStyle("Regular");
        run.fontSizeHwpunits(850);
        return run;
    }

    private static ASTTextRun underlinedRun(String text) {
        ASTTextRun run = bodyRun(text);
        run.underline(true);
        run.underlineColor("#000000");
        run.underlineShape("SOLID");
        run.characterStyleRef("CharacterStyle/밑줄");
        return run;
    }

    private static void assertEquation(ASTParagraph para, int index, String script) {
        Assert.assertTrue(para.items().get(index) instanceof ASTEquation);
        ASTEquation equation = (ASTEquation) para.items().get(index);
        Assert.assertEquals(script, equation.hwpScript());
        Assert.assertEquals(Integer.valueOf(850), equation.preferredBaseUnit());
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
    public void mixedKoreanLatinVariableKeepsSourceEquationMetrics() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("일 때 a가");
        run.fontFamily("EH상부자");
        run.fontStyle("Italic");
        run.fontSizeHwpunits(1050);
        run.textColor("#123456");

        RunBuilder.splitLatinVarsInMixedText(null, run, para);

        Assert.assertEquals(3, para.items().size());
        Assert.assertTrue(para.items().get(1) instanceof ASTEquation);
        ASTEquation equation = (ASTEquation) para.items().get(1);
        Assert.assertEquals("a", equation.hwpScript());
        Assert.assertEquals(Integer.valueOf(1050), equation.preferredBaseUnit());
        Assert.assertEquals("EH상부자", equation.preferredFontFamily());
        Assert.assertEquals("#123456", equation.textColor());
    }

    @Test
    public void italicEquationWithoutPointSizeInheritsAdjacentSourceTextSize() {
        ASTParagraph para = new ASTParagraph();

        ASTTextRun equationSource = new ASTTextRun();
        equationSource.text("a");
        equationSource.fontFamily("EH상부자");
        equationSource.fontStyle("Italic");
        para.addItem(equationSource);

        ASTTextRun particle = new ASTTextRun();
        particle.text("가");
        particle.fontSizeHwpunits(1050);
        para.addItem(particle);

        RunPostProcessor.convertItalicRunsToEquations(para);

        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals(Integer.valueOf(1050),
                ((ASTEquation) para.items().get(0)).preferredBaseUnit());
    }

    @Test
    public void italicEquationWithoutPointSizeInheritsAdjacentEquationSize() {
        ASTParagraph para = new ASTParagraph();

        ASTTextRun equationSource = new ASTTextRun();
        equationSource.text("a");
        equationSource.fontFamily("EH상부자");
        equationSource.fontStyle("Italic");
        equationSource.characterStyleRef("CharacterStyle/상부자(이탤릭)");
        equationSource.fontSizeHwpunits(1000);
        para.addItem(equationSource);

        ASTTextRun particle = new ASTTextRun();
        particle.text("가");
        para.addItem(particle);

        ASTEquation radical = new ASTEquation("sqrt{a}", "EH_FONT");
        radical.preferredBaseUnit(1050);
        para.addItem(radical);

        RunPostProcessor.convertItalicRunsToEquations(para);
        RunPostProcessor.resolveInheritedEquationSizes(para);

        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals(Integer.valueOf(1050),
                ((ASTEquation) para.items().get(0)).preferredBaseUnit());
    }

    @Test
    public void genericTenPointVariableAlignsWithNearbyEquationFlow() {
        ASTParagraph para = new ASTParagraph();
        ASTEquation inequality = new ASTEquation("a>0", "EH_FONT");
        inequality.preferredBaseUnit(1050);
        para.addItem(inequality);
        ASTTextRun prose = new ASTTextRun();
        prose.text("일 때 ");
        para.addItem(prose);
        ASTEquation variable = new ASTEquation("a", "EH_FONT");
        variable.preferredBaseUnit(1000);
        para.addItem(variable);

        RunPostProcessor.resolveInheritedEquationSizes(para);

        Assert.assertEquals(Integer.valueOf(1050), variable.preferredBaseUnit());
    }

    @Test
    public void leadingUnderlinedIndentAfterItemMarkerIsRemovedWhenItemHasCorrectionTarget() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(bodyRun("⑴"));
        para.addItem(bodyRun(" "));
        ASTTextRun leadingIndent = underlinedRun("\u00A0\u00A0\u00A0");
        para.addItem(leadingIndent);
        para.addItem(bodyRun("The more you water the plant, the "));
        ASTTextRun target = underlinedRun("tall");
        para.addItem(target);
        para.addItem(bodyRun(" it grows. "));
        ASTTextRun answerBlank = underlinedRun("\u00A0\u00A0\u00A0");
        para.addItem(answerBlank);

        RunPostProcessor.suppressLeadingUnderlineIndentAfterListMarker(para);

        Assert.assertEquals("The more you water the plant, the ",
                ((ASTTextRun) para.items().get(2)).text());
        Assert.assertFalse(para.items().contains(leadingIndent));
        Assert.assertTrue(target.underline());
        Assert.assertTrue(answerBlank.underline());
    }

    @Test
    public void leadingUnderlinedAnswerBlankWithoutCorrectionTargetIsPreserved() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(bodyRun("⑴"));
        para.addItem(bodyRun(" "));
        ASTTextRun answerBlank = underlinedRun("\u00A0\u00A0\u00A0");
        para.addItem(answerBlank);
        para.addItem(bodyRun("is on the table."));

        RunPostProcessor.suppressLeadingUnderlineIndentAfterListMarker(para);

        Assert.assertTrue(answerBlank.underline());
        Assert.assertEquals("CharacterStyle/밑줄", answerBlank.characterStyleRef());
    }

    @Test
    public void colonPromptLeadingBlankIsPreserved() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(bodyRun("A: "));
        ASTTextRun answerBlank = underlinedRun("\u00A0\u00A0\u00A0");
        para.addItem(answerBlank);
        para.addItem(bodyRun("without soil?"));

        RunPostProcessor.suppressLeadingUnderlineIndentAfterListMarker(para);

        Assert.assertTrue(answerBlank.underline());
        Assert.assertEquals("CharacterStyle/밑줄", answerBlank.characterStyleRef());
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
