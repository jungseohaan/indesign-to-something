package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class StoryLoaderTest {
    @Test
    public void inlineEquationCarriesSourceOwnershipIdentityForDuplicateGuard() {
        ASTParagraph paragraph = new ASTParagraph();
        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation equation =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(
                        "{5} over {2-sqrt{3}}", "INLINE_FRACTION");
        equation.sourceObjectId(331203);
        paragraph.addItem(equation);

        Assert.assertTrue(
                StoryLoader.paragraphAlreadyContainsInlineObject(paragraph, 331203));
        Assert.assertFalse(
                StoryLoader.paragraphAlreadyContainsInlineObject(paragraph, 331227));
    }

    @Test
    public void grepMathDigitAfterInlineAnswerBoundaryRemainsEquationOwned() {
        IDMLCharacterRun before = new IDMLCharacterRun();
        before.content("\uFFFC\u2003");
        IDMLCharacterRun digit = decimalRun("2", 10.0);
        IDMLCharacterRun after = new IDMLCharacterRun();
        after.content("\u2002");

        List<IDMLCharacterRun> runs = Arrays.asList(before, digit, after);

        Assert.assertTrue(StoryLoader.shouldEmitGrepSangbujaItalicNumber(digit, runs, 1));
    }

    @Test
    public void splitEhDecimalRunsAreCoalescedBeforeMathPlanning() {
        IDMLCharacterRun zero = decimalRun("0", 9.5);
        IDMLCharacterRun point = decimalRun(".", 9.0);
        IDMLCharacterRun two = decimalRun("2", 9.5);

        List<IDMLCharacterRun> merged = StoryLoader.coalesceSangbujaItalicDecimalRuns(
                Arrays.asList(zero, point, two));

        Assert.assertEquals(1, merged.size());
        Assert.assertEquals("0.2", merged.get(0).content());
        Assert.assertEquals(Double.valueOf(9.5), merged.get(0).fontSize());
    }

    @Test
    public void grepRatioValueAndColonBecomeEquationCandidateAndPlainTextSeparator() {
        IDMLCharacterRun ratio = new IDMLCharacterRun();
        ratio.content("1`:`");
        ratio.fontFamily("EH상부자");
        ratio.fontStyle("Italic");
        ratio.baselineShift(-0.5);
        ratio.grepMathFont(true);
        ratio.grepAppliedCharStyle("CharacterStyle/태광10.5%3a상부자(이탤릭) 10.5");

        List<IDMLCharacterRun> split =
                StoryLoader.splitGrepRatioSeparatorRuns(Collections.singletonList(ratio));

        Assert.assertEquals(2, split.size());
        Assert.assertEquals("1", split.get(0).content());
        Assert.assertTrue(split.get(0).grepMathFont());
        Assert.assertEquals("\u2009:\u2009", split.get(1).content());
        Assert.assertFalse(split.get(1).grepMathFont());
        Assert.assertNull(split.get(1).fontFamily());
        Assert.assertNull(split.get(1).fontStyle());
        Assert.assertNull(split.get(1).baselineShift());

        IDMLCharacterRun prose = new IDMLCharacterRun();
        prose.content("금강비는 ");
        List<IDMLCharacterRun> context = Arrays.asList(prose, split.get(0), split.get(1));
        Assert.assertTrue(StoryLoader.shouldEmitGrepSangbujaItalicNumber(
                split.get(0), context, 1));
    }

    private static IDMLCharacterRun decimalRun(String text, double size) {
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content(text);
        run.fontSize(size);
        run.fontFamily("EH상부자");
        run.grepAppliedCharStyle("CharacterStyle/태광9.5%3a상부자(이탤릭) 9.5");
        return run;
    }

    @Test
    public void sourceTabsBeforeChoiceMarkersAreRestoredAfterMathSplitting() {
        IDMLParagraph source = new IDMLParagraph();
        IDMLCharacterRun sourceRun = new IDMLCharacterRun();
        sourceRun.content("⑴ 0<a<1\t⑵ a=1\t⑶ a>1");
        source.addCharacterRun(sourceRun);

        ASTParagraph para = new ASTParagraph();
        ASTTextRun one = new ASTTextRun();
        one.text("⑴");
        para.addItem(one);
        para.addItem(new ASTEquation("0<a<1", "EH_FONT"));
        ASTTextRun two = new ASTTextRun();
        two.text("⑵");
        para.addItem(two);
        para.addItem(new ASTEquation("a=1", "EH_FONT"));
        ASTTextRun three = new ASTTextRun();
        three.text("⑶");
        para.addItem(three);

        StoryLoader.restoreSourceTabsBeforeMarkers(para, source);

        Assert.assertEquals("\t", ((ASTTextRun) para.items().get(2)).text());
        Assert.assertEquals("⑵", ((ASTTextRun) para.items().get(3)).text());
        Assert.assertEquals("\t", ((ASTTextRun) para.items().get(5)).text());
        Assert.assertEquals("⑶", ((ASTTextRun) para.items().get(6)).text());
    }
    @Test
    public void resolvedMathFontClassifiesIdmlRunBeforeEquationGrouping() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.lastMatchResult = new int[] { -1 };

        IDMLCharacterRun idml = new IDMLCharacterRun();
        idml.content("'\u2002\u2009");
        idml.appliedCharacterStyle("CharacterStyle/#강조(숫자)");

        ResolvedRun resolved = new ResolvedRun();
        resolved.text("'\u2002\u2009");
        resolved.fontFamily("EH분수대문자");
        resolved.fontStyle("Bold");
        resolved.fontSize(11.5);
        resolved.fillColor("Black");

        StoryLoader.enrichMathFontsFromResolvedRuns(
                ctx, Collections.singletonList(idml), Collections.singletonList(resolved));

        Assert.assertEquals("EH분수대문자", idml.fontFamily());
        Assert.assertEquals("Bold", idml.fontStyle());
        Assert.assertEquals(Double.valueOf(11.5), idml.fontSize());
        Assert.assertEquals("Black", idml.fillColor());
        Assert.assertTrue(idml.isEHFont());
    }

    @Test
    public void resolvedStructuralMathFontOverridesBodyTextGlyphStyleGuard() {
        Assert.assertTrue(StoryLoader.hasResolvedStructuralMathFont("EH분수대문자"));
        Assert.assertTrue(StoryLoader.hasResolvedStructuralMathFont("NP_ISHS"));
        Assert.assertFalse(StoryLoader.hasResolvedStructuralMathFont("BT수식H"));
        Assert.assertFalse(StoryLoader.hasResolvedStructuralMathFont("Yoon가변 윤명조100Std_OTF"));
    }

    @Test
    public void grepArithmeticPrefixImmediatelyBeforeSqrtRemainsMathOwned() {
        IDMLCharacterRun prefix = new IDMLCharacterRun();
        prefix.content("4-");
        prefix.grepMathFont(true);
        prefix.grepAppliedCharStyle("CharacterStyle/태광10.5:상부자(이탤릭) 10.5");
        prefix.fontFamily("EH상부자");

        IDMLCharacterRun hook = new IDMLCharacterRun();
        hook.content("'");
        hook.fontFamily("EH분수대문자");

        Assert.assertTrue(StoryLoader.isGrepMathPrefixBeforeEHStructure(
                prefix, Arrays.asList(prefix, hook), 0));
        Assert.assertTrue(prefix.isEHFont());
    }

    @Test
    public void completeGrepArithmeticTermImmediatelyBeforeSqrtRemainsMathOwned() {
        IDMLCharacterRun prefix = new IDMLCharacterRun();
        prefix.content("3-2");
        prefix.grepMathFont(true);
        prefix.grepAppliedCharStyle("CharacterStyle/태광10:상부자(이탤릭) 10");
        prefix.fontFamily("EH상부자");

        IDMLCharacterRun hook = new IDMLCharacterRun();
        hook.content("'");
        hook.fontFamily("EH분수대문자");

        Assert.assertTrue(StoryLoader.isGrepMathPrefixBeforeEHStructure(
                prefix, Arrays.asList(prefix, hook), 0));
    }

    @Test
    public void grepArithmeticPrefixDoesNotCrossWhitespaceIntoSqrt() {
        IDMLCharacterRun prefix = new IDMLCharacterRun();
        prefix.content("4-");
        prefix.grepMathFont(true);

        IDMLCharacterRun space = new IDMLCharacterRun();
        space.content(" ");

        IDMLCharacterRun hook = new IDMLCharacterRun();
        hook.content("'");
        hook.fontFamily("EH분수대문자");

        Assert.assertFalse(StoryLoader.isGrepMathPrefixBeforeEHStructure(
                prefix, Arrays.asList(prefix, space, hook), 0));
    }

    @Test
    public void resolvedBodyFontDoesNotChangeMathClassification() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.lastMatchResult = new int[] { -1 };

        IDMLCharacterRun idml = new IDMLCharacterRun();
        idml.content("x");

        ResolvedRun resolved = new ResolvedRun();
        resolved.text("x");
        resolved.fontFamily("Yoon가변 윤명조100Std_OTF");

        StoryLoader.enrichMathFontsFromResolvedRuns(
                ctx, Collections.singletonList(idml), Collections.singletonList(resolved));

        Assert.assertNull(idml.fontFamily());
        Assert.assertFalse(idml.isMathFont());
    }

    @Test
    public void resolvedFillColorEnrichesExistingMathFontRun() {
        IDMLCharacterRun idml = new IDMLCharacterRun();
        idml.content("'\u2002\u2009");
        idml.fontFamily("EH분수대문자");

        ResolvedRun resolved = new ResolvedRun();
        resolved.text("'\u2002\u2009");
        resolved.fontFamily("EH분수대문자");
        resolved.fillColor("Black");

        StoryLoader.applyResolvedMathFontForClassification(idml, resolved);

        Assert.assertEquals("EH분수대문자", idml.fontFamily());
        Assert.assertEquals("Black", idml.fillColor());
    }

    @Test
    public void idmlGrepMathEvidencePreventsResolvedCellTextFlattening() {
        IDMLTableCell cell = new IDMLTableCell();
        IDMLParagraph paragraph = new IDMLParagraph();

        IDMLCharacterRun body = new IDMLCharacterRun();
        body.content("다음 대화를 읽고 ");
        paragraph.addCharacterRun(body);

        IDMLCharacterRun variable = new IDMLCharacterRun();
        variable.content("a");
        variable.grepMathFont(true);
        variable.grepAppliedCharStyle("CharacterStyle/상부자(이탤릭)");
        paragraph.addCharacterRun(variable);

        IDMLCharacterRun particle = new IDMLCharacterRun();
        particle.content("가");
        paragraph.addCharacterRun(particle);
        cell.addParagraph(paragraph);

        Assert.assertTrue(StoryLoader.hasIdmlCellMathEvidence(cell));
    }

    @Test
    public void enrichResolvedFillColorForExistingMathFontRun() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.lastMatchResult = new int[] { -1 };

        IDMLCharacterRun idml = new IDMLCharacterRun();
        idml.content("'\u2002\u2009");
        idml.fontFamily("EH분수대문자");
        idml.fillColor("Color/C=0 M=100 Y=100 K=0");

        ResolvedRun resolved = new ResolvedRun();
        resolved.text("'\u2002\u2009");
        resolved.fontFamily("EH분수대문자");
        resolved.fillColor("Black");

        StoryLoader.enrichMathFontsFromResolvedRuns(
                ctx, Collections.singletonList(idml), Collections.singletonList(resolved));

        Assert.assertEquals("Black", idml.fillColor());
    }

    // SPEC-067: 셀 문단 오색 누출 되돌림의 채도 게이트.
    // 누출 대상(선명한 유채색)만 true, 근흑·회색 본문색은 false 여야 한다.
    @Test
    public void saturatedChromaticGateTargetsBleedColorsOnly() {
        // 누출되는 선지 녹색(#67B755) — 되돌림 대상
        Assert.assertTrue(StoryLoader.isSaturatedChromatic("#67B755"));
        // 선명한 마젠타(@색자) — 근거 있으면 보존되지만 채도 게이트는 통과해야 함
        Assert.assertTrue(StoryLoader.isSaturatedChromatic("#D7157E"));

        // 근흑 본문색(#1A1A1A, K90) — 건드리면 안 됨
        Assert.assertFalse(StoryLoader.isSaturatedChromatic("#1A1A1A"));
        // 중간 회색 — 채도 0 이라 제외
        Assert.assertFalse(StoryLoader.isSaturatedChromatic("#757877"));
        // 순흑/순백 (호출 전에 이미 걸러지지만 방어)
        Assert.assertFalse(StoryLoader.isSaturatedChromatic("#000000"));
        Assert.assertFalse(StoryLoader.isSaturatedChromatic("#FFFFFF"));
        // 형식 이상 입력
        Assert.assertFalse(StoryLoader.isSaturatedChromatic(null));
        Assert.assertFalse(StoryLoader.isSaturatedChromatic("#12"));
    }
}
