package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RunPropertyResolverTest {
    @Test
    public void fontSizeKeepsParagraphStyleWhenCharacterRunHasNoExplicitSize() {
        ResolvedRun rr = new ResolvedRun();
        rr.fontSize(80.0);
        IDMLCharacterRun cr = new IDMLCharacterRun();

        Integer size = RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                rr, cr, 8.0, MatchConfidence.HIGH);

        assertEquals(Integer.valueOf(800), size);
    }

    @Test
    public void fontSizeUsesResolvedOnlyWhenIdmlHasNoSizeSource() {
        ResolvedRun rr = new ResolvedRun();
        rr.fontSize(8.5);
        IDMLCharacterRun cr = new IDMLCharacterRun();

        Integer size = RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                rr, cr, null, MatchConfidence.HIGH);

        assertEquals(Integer.valueOf(850), size);
    }

    @Test
    public void proportionalScaleDoesNotMutateFontSize() {
        ResolvedRun rr = new ResolvedRun();
        rr.fontSize(8.0);
        rr.horizontalScale(45.0);
        rr.verticalScale(45.0);
        ASTTextRun run = new ASTTextRun();

        TextStyleApplicator.ResolvedStyleOptions options =
                new TextStyleApplicator.ResolvedStyleOptions();
        options.proportionalScaleAsFontSize = true;
        options.applyVerticalScale = false;
        TextStyleApplicator.applyResolvedStyle(run, rr, (java.util.function.Function<String, String>) null, options);

        assertEquals(Integer.valueOf(800), run.fontSizeHwpunits());
        assertEquals(Short.valueOf((short) 45), run.horizontalScale());
    }
}
