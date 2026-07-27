package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import org.junit.Test;

import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RunPropertyResolverTest {
    @Test
    public void fontSizeUsesResolvedForReliableSplitSegment() {
        ResolvedRun rr = new ResolvedRun();
        rr.fontSize(80.0);
        IDMLCharacterRun cr = new IDMLCharacterRun();

        Integer size = RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                rr, cr, 8.0, MatchConfidence.HIGH);

        assertEquals(Integer.valueOf(8000), size);
    }

    @Test
    public void fontSizeKeepsParagraphStyleForLowConfidenceMatch() {
        ResolvedRun rr = new ResolvedRun();
        rr.fontSize(80.0);
        IDMLCharacterRun cr = new IDMLCharacterRun();

        Integer size = RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                rr, cr, 8.0, MatchConfidence.LOW);

        assertEquals(Integer.valueOf(800), size);
    }

    @Test
    public void fontSizeUsesResolvedWhenLowConfidenceHasNoIdmlSizeSource() {
        ResolvedRun rr = new ResolvedRun();
        rr.fontSize(8.5);
        IDMLCharacterRun cr = new IDMLCharacterRun();

        Integer size = RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                rr, cr, null, MatchConfidence.LOW);

        assertEquals(Integer.valueOf(850), size);
    }

    @Test
    public void textColorUsesResolvedForReliableSplitSegmentEvenWhenIdmlTintExists() {
        ResolvedRun rr = new ResolvedRun();
        rr.fillColor("Black");
        Function<String, String> colorResolver = color -> {
            if ("Black".equals(color)) return "#000000";
            if ("BlueLabel".equals(color)) return "#2D4069";
            return null;
        };
        BiFunction<String, Double, String> tintedResolver =
                (color, tint) -> "BlueLabel".equals(color) ? "#2D4069" : null;

        String color = RunPropertyResolver.resolveTextColorHexWithConfidence(
                rr, "BlueLabel", 100.0, null,
                colorResolver, tintedResolver, MatchConfidence.HIGH);

        assertEquals("#000000", color);
    }

    @Test
    public void textColorKeepsIdmlTintForLowConfidenceMatch() {
        ResolvedRun rr = new ResolvedRun();
        rr.fillColor("Black");
        Function<String, String> colorResolver = color -> {
            if ("Black".equals(color)) return "#000000";
            if ("BlueLabel".equals(color)) return "#2D4069";
            return null;
        };
        BiFunction<String, Double, String> tintedResolver =
                (color, tint) -> "BlueLabel".equals(color) ? "#2D4069" : null;

        String color = RunPropertyResolver.resolveTextColorHexWithConfidence(
                rr, "BlueLabel", 100.0, null,
                colorResolver, tintedResolver, MatchConfidence.LOW);

        assertEquals("#2D4069", color);
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

    @Test
    public void ehSangbujaItalicStyleIsMathTypographyNotSuperscript() {
        ResolvedRun rr = new ResolvedRun();
        rr.charStyle("CharacterStyle/상부자(이탤릭)");
        ASTTextRun run = new ASTTextRun();

        TextStyleApplicator.applyResolvedStyle(
                run,
                rr,
                (java.util.function.Function<String, String>) null,
                new TextStyleApplicator.ResolvedStyleOptions());

        assertFalse(run.superscript());
    }
}
