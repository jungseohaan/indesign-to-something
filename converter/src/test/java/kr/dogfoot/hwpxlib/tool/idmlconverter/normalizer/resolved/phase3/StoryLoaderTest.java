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
}
