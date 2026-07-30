package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ASTMathGrouperTest {
    @Test
    public void singleTextBaseBeforeEHExponentStartsEHGroup() {
        List<IDMLCharacterRun> runs = Arrays.asList(
                run("x", null, "CharacterStyle/$ID/[No character style]"),
                run("Û`", "EH상부자", "CharacterStyle/상부자"));

        Assert.assertTrue(ASTMathGrouper.isPreEHMathRun(runs.get(0), runs, 0));
    }

    @Test
    public void normalSingleTextWithoutFollowingEHScriptDoesNotStartEHGroup() {
        List<IDMLCharacterRun> runs = Arrays.asList(
                run("x", null, "CharacterStyle/$ID/[No character style]"),
                run("라고", null, "CharacterStyle/$ID/[No character style]"));

        Assert.assertFalse(ASTMathGrouper.isPreEHMathRun(runs.get(0), runs, 0));
    }

    @Test
    public void wordBeforeEHScriptDoesNotStartEHGroup() {
        List<IDMLCharacterRun> runs = Arrays.asList(
                run("width", null, "CharacterStyle/$ID/[No character style]"),
                run("Û`", "EH상부자", "CharacterStyle/상부자"));

        Assert.assertFalse(ASTMathGrouper.isPreEHMathRun(runs.get(0), runs, 0));
    }

    @Test
    public void separatedTextBaseBeforeEHScriptDoesNotStartEHGroup() {
        List<IDMLCharacterRun> runs = Arrays.asList(
                run("x", null, "CharacterStyle/$ID/[No character style]"),
                run(" ", null, "CharacterStyle/$ID/[No character style]"),
                run("Û`", "EH상부자", "CharacterStyle/상부자"));

        Assert.assertFalse(ASTMathGrouper.isPreEHMathRun(runs.get(0), runs, 0));
    }

    @Test
    public void singleTextBaseBeforeRawEHEncodedExponentStartsEHGroup() {
        List<IDMLCharacterRun> runs = Arrays.asList(
                run("x", null, "CharacterStyle/$ID/[No character style]"),
                run("Û`", null, "CharacterStyle/$ID/[No character style]"));

        Assert.assertTrue(ASTMathGrouper.isPreEHMathRun(runs.get(0), runs, 0));
    }

    @Test
    public void numericTextBasesBeforeEHExponentStartEHGroup() {
        Assert.assertTrue(preEH("1", "Û`"));
        Assert.assertTrue(preEH("11", "Û`"));
        Assert.assertTrue(preEH("23.0", "Û`"));
        Assert.assertTrue(preEH("1/2", "Û`"));
    }

    @Test
    public void standaloneEHSqrtMarkerBecomesVisibleTextSymbol() {
        ASTParagraph para = new ASTParagraph();
        IDMLCharacterRun source = run("'\u2002\u2009", "EH분수대문자", "CharacterStyle/#강조(숫자)");
        source.fillColor("Black");
        ASTMathGrouper.flushEHMathGroup(
                Arrays.asList(source),
                para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        ASTTextRun textRun = (ASTTextRun) para.items().get(0);
        Assert.assertEquals("√", textRun.text());
        Assert.assertEquals("#000000", textRun.textColor());
        Assert.assertNull(textRun.characterStyleRef());
    }

    @Test
    public void topLevelCommaSpaceBetweenEHExpressionsIsPlainText() {
        ASTParagraph para = new ASTParagraph();
        ASTMathGrouper.flushEHMathGroup(Arrays.asList(
                run("4-", "EH상부자", "CharacterStyle/상부자"),
                run("'", "EH분수대문자", "CharacterStyle/분수대문자"),
                run("3,", "EH상부자", "CharacterStyle/상부자"),
                run(" ", null, "CharacterStyle/$ID/[No character style]"),
                run("-2+", "EH상부자", "CharacterStyle/상부자"),
                run("'", "EH분수대문자", "CharacterStyle/분수대문자"),
                run("1", "EH상부자", "CharacterStyle/상부자"),
                run("", "EH분수대문자", "CharacterStyle/분수대문자"),
                run("1", "EH상부자", "CharacterStyle/상부자")),
                para);

        Assert.assertEquals(3, para.items().size());
        Assert.assertEquals("4-sqrt{3}", ((ASTEquation) para.items().get(0)).hwpScript());
        Assert.assertEquals(", ", ((ASTTextRun) para.items().get(1)).text());
        Assert.assertEquals("-2+sqrt{11}", ((ASTEquation) para.items().get(2)).hwpScript());
    }

    @Test
    public void completeArithmeticTermAndAdjacentSqrtBecomeOneEquation() {
        ASTParagraph para = new ASTParagraph();
        ASTMathGrouper.flushEHMathGroup(Arrays.asList(
                run("3-2", "EH상부자", "CharacterStyle/상부자"),
                run("'", "EH분수대문자", "CharacterStyle/분수대문자"),
                run("2", "EH상부자", "CharacterStyle/상부자")),
                para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertEquals("3-2sqrt{2}", ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void ehYakmulPiGlyphRemainsEquationSymbol() {
        ASTParagraph para = new ASTParagraph();
        ASTMathGrouper.flushEHMathGroup(Arrays.asList(
                run("p", "EH약물", "CharacterStyle/상부자"),
                run(",", "EH상부자", "CharacterStyle/상부자")),
                para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(String.valueOf(para.items().get(0)),
                para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("pi,", ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void standaloneDigitInExplicitEHFormulaFontRemainsEquation() {
        ASTParagraph para = new ASTParagraph();
        IDMLCharacterRun source = run(
                "`0", null, "CharacterStyle/$ID/[No character style]");
        source.grepAppliedCharStyle("CharacterStyle/태광10%3a상부자(이탤릭) 10");
        ASTMathGrouper.flushEHMathGroup(Arrays.asList(source), para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("0", ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void standaloneDigitInResolvedEHUpperFontIsBaselineEquation() {
        ASTParagraph para = new ASTParagraph();
        IDMLCharacterRun source = run(
                "`0", "EH상부자",
                "CharacterStyle/태광10%3a상부자(이탤릭) 10");
        ASTMathGrouper.flushEHMathGroup(Arrays.asList(source), para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("0", ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void standaloneEHAtomicEquationPreservesInheritedTextColor() {
        ASTParagraph para = new ASTParagraph();
        IDMLCharacterRun source = run(
                "`0", "EH상부자",
                "CharacterStyle/태광10%3a상부자(이탤릭) 10");
        source.fillColor("#30428E");
        ASTMathGrouper.flushEHMathGroup(
                Arrays.asList(source), para, color -> color);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("#30428E", ((ASTEquation) para.items().get(0)).textColor());
    }

    @Test
    public void nonNumericExpressionBeforeEHExponentDoesNotStartEHGroup() {
        Assert.assertFalse(preEH("1-2", "Û`"));
        Assert.assertFalse(preEH("23.", "Û`"));
        Assert.assertFalse(preEH("1/2/3", "Û`"));
    }

    @Test
    public void npFallbackPreservesSourceMathEvidence() {
        ASTParagraph para = new ASTParagraph();
        IDMLCharacterRun source = run(
                "A", "NP_BE", "CharacterStyle/np서체%3aNP_BE");
        source.grepMathFont(true);
        source.tracking(-25.0);
        source.horizontalScale(98.0);

        ASTMathGrouper.flushNPMathGroup(Arrays.asList(source), para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        ASTTextRun fallback = (ASTTextRun) para.items().get(0);
        Assert.assertEquals("NP_BE", fallback.fontFamily());
        Assert.assertEquals("CharacterStyle/np서체%3aNP_BE",
                fallback.characterStyleRef());
        Assert.assertTrue(fallback.grepMathFont());
    }

    private static boolean preEH(String base, String exponent) {
        List<IDMLCharacterRun> runs = Arrays.asList(
                run(base, null, "CharacterStyle/$ID/[No character style]"),
                run(exponent, "EH상부자", "CharacterStyle/상부자"));
        return ASTMathGrouper.isPreEHMathRun(runs.get(0), runs, 0);
    }

    private static IDMLCharacterRun run(String content, String fontFamily, String charStyle) {
        IDMLCharacterRun r = new IDMLCharacterRun();
        r.content(content);
        r.fontFamily(fontFamily);
        r.appliedCharacterStyle(charStyle);
        return r;
    }
}
