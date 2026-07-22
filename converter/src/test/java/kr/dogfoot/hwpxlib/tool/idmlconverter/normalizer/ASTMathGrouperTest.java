package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

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
    public void nonNumericExpressionBeforeEHExponentDoesNotStartEHGroup() {
        Assert.assertFalse(preEH("1-2", "Û`"));
        Assert.assertFalse(preEH("23.", "Û`"));
        Assert.assertFalse(preEH("1/2/3", "Û`"));
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
