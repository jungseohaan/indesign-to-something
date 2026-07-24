package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class StoryLoaderTest {
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
