package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.Locale;
import java.util.function.Function;

/**
 * Single gateway for applying IDML/resolved text style information to AST text runs.
 *
 * <p>TextFrames, table cells, and drawText-like label text must not invent their
 * own font-size/font/color fallback chains. This class applies the shared
 * priority: CharacterRun explicit value → CharacterStyle → ParagraphStyle →
 * caller-provided explicit fallback.</p>
 */
public final class TextStyleApplicator {
    private TextStyleApplicator() {}

    public static final class ExplicitStyle {
        public String fontFamily;
        public String fontStyle;
        public Double fontSizePt;
        public String textColorHex;
        public Double tracking;
        public Double horizontalScale;
    }

    public static final class ResolvedStyleOptions {
        public boolean proportionalScaleAsFontSize;
        public boolean applyVerticalScale = true;
        public boolean applyPosition = true;
    }

    public static ASTTextRun createStyledRun(
            String text,
            IDMLCharacterRun characterRun,
            String paragraphStyleRef,
            StylePropertyResolver styleResolver,
            ResolvedData resolvedData) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        applyIdmlStyle(run, characterRun, paragraphStyleRef, styleResolver, resolvedData);
        return run;
    }

    public static void applyIdmlStyle(
            ASTTextRun target,
            IDMLCharacterRun characterRun,
            String paragraphStyleRef,
            StylePropertyResolver styleResolver,
            ResolvedData resolvedData) {
        if (target == null) return;

        IDMLStyleDef paraStyle = styleResolver != null
                ? styleResolver.getResolvedParagraphStyle(paragraphStyleRef) : null;
        IDMLStyleDef charStyle = resolvedCharacterStyle(styleResolver, characterRun);
        IDMLStyleDef grepStyle = resolvedGrepStyle(styleResolver, characterRun);

        if (characterRun != null && characterRun.appliedCharacterStyle() != null) {
            target.characterStyleRef(characterRun.appliedCharacterStyle());
        }

        String fontFamily = firstNonEmpty(
                characterRun != null ? characterRun.fontFamily() : null,
                grepStyle != null ? grepStyle.fontFamily() : null,
                charStyle != null ? charStyle.fontFamily() : null,
                paraStyle != null ? paraStyle.fontFamily() : null);
        if (fontFamily != null) target.fontFamily(fontFamily);

        String fontStyle = firstNonEmpty(
                characterRun != null ? characterRun.fontStyle() : null,
                grepStyle != null ? grepStyle.fontStyle() : null,
                charStyle != null ? charStyle.fontStyle() : null,
                paraStyle != null ? paraStyle.fontStyle() : null);
        if (fontStyle != null) target.fontStyle(fontStyle);

        Double fontSize = firstPositive(
                characterRun != null ? characterRun.fontSize() : null,
                grepStyle != null ? grepStyle.fontSize() : null,
                charStyle != null ? charStyle.fontSize() : null,
                paraStyle != null ? paraStyle.fontSize() : null);
        if (fontSize != null) {
            target.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(fontSize));
        }

        String colorRef = firstNonEmpty(
                characterRun != null ? characterRun.fillColor() : null,
                grepStyle != null ? grepStyle.fillColor() : null,
                charStyle != null ? charStyle.fillColor() : null,
                paraStyle != null ? paraStyle.fillColor() : null);
        String colorHex = resolveColor(resolvedData, colorRef);
        if (colorHex != null) target.textColor(colorHex);

        Double tracking = firstNonZero(
                characterRun != null ? characterRun.tracking() : null,
                grepStyle != null ? grepStyle.tracking() : null,
                charStyle != null ? charStyle.tracking() : null,
                paraStyle != null ? paraStyle.tracking() : null);
        if (tracking != null) {
            target.letterSpacing((short) Math.round(tracking / 10.0));
        }

        Double horizontalScale = firstNonDefaultScale(
                characterRun != null ? characterRun.horizontalScale() : null,
                grepStyle != null ? grepStyle.horizontalScale() : null,
                charStyle != null ? charStyle.horizontalScale() : null,
                paraStyle != null ? paraStyle.horizontalScale() : null);
        if (horizontalScale != null) {
            target.horizontalScale((short) Math.round(horizontalScale));
        }

        Boolean underline = firstBoolean(
                characterRun != null ? characterRun.underline() : null,
                grepStyle != null ? grepStyle.underline() : null,
                charStyle != null ? charStyle.underline() : null,
                paraStyle != null ? paraStyle.underline() : null);
        if (Boolean.TRUE.equals(underline)) target.underline(true);

        Boolean strike = firstBoolean(
                characterRun != null ? characterRun.strikeThrough() : null,
                grepStyle != null ? grepStyle.strikeThrough() : null,
                charStyle != null ? charStyle.strikeThrough() : null,
                paraStyle != null ? paraStyle.strikeThrough() : null);
        if (Boolean.TRUE.equals(strike)) target.strikeThrough(true);
    }

    public static void applyExplicitStyle(ASTTextRun target, ExplicitStyle style) {
        if (target == null || style == null) return;
        if (style.fontFamily != null) target.fontFamily(style.fontFamily);
        if (style.fontStyle != null) target.fontStyle(style.fontStyle);
        if (style.fontSizePt != null && style.fontSizePt > 0) {
            target.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(style.fontSizePt));
        }
        if (style.textColorHex != null) target.textColor(style.textColorHex);
        if (style.tracking != null && style.tracking != 0) {
            target.letterSpacing((short) Math.round(style.tracking / 10.0));
        }
        if (style.horizontalScale != null
                && style.horizontalScale != 0
                && style.horizontalScale != 100.0) {
            target.horizontalScale((short) Math.round(style.horizontalScale));
        }
    }

    public static void applyResolvedStyle(ASTTextRun target,
                                          ResolvedRun resolvedRun,
                                          ColorResolver colorResolver) {
        applyResolvedStyle(target, resolvedRun,
                colorResolver != null ? colorResolver::resolve : null,
                null);
    }

    public static void applyResolvedStyle(ASTTextRun target,
                                          ResolvedRun resolvedRun,
                                          Function<String, String> colorResolver,
                                          ResolvedStyleOptions options) {
        if (target == null || resolvedRun == null) return;
        ResolvedStyleOptions opts = options != null ? options : new ResolvedStyleOptions();
        if (resolvedRun.charStyle() != null) {
            target.characterStyleRef(resolvedRun.charStyle());
        }
        if (resolvedRun.fontFamily() != null) {
            target.fontFamily(resolvedRun.fontFamily());
        }
        if (resolvedRun.fontStyle() != null) {
            target.fontStyle(resolvedRun.fontStyle());
        }
        if (resolvedRun.fontSize() != null && resolvedRun.fontSize() > 0) {
            target.fontSizeHwpunits((int) Math.round(resolvedRun.fontSize() * 100));
        }
        if (resolvedRun.fillColor() != null) {
            String color = colorResolver != null
                    ? colorResolver.apply(resolvedRun.fillColor())
                    : resolvedRun.fillColor();
            target.textColor(color != null ? color : resolvedRun.fillColor());
        }
        if (resolvedRun.tracking() != null && resolvedRun.tracking() != 0) {
            target.letterSpacing((short) Math.round(resolvedRun.tracking() / 10.0));
        }
        if (resolvedRun.horizontalScale() != null
                && resolvedRun.horizontalScale() != 0
                && resolvedRun.horizontalScale() != 100) {
            Double vs = resolvedRun.verticalScale();
            if (opts.proportionalScaleAsFontSize
                    && vs != null
                    && Math.abs(resolvedRun.horizontalScale() - vs) < 1.0) {
                if (target.fontSizeHwpunits() != null) {
                    target.fontSizeHwpunits((int) Math.round(
                            target.fontSizeHwpunits() * resolvedRun.horizontalScale() / 100.0));
                }
            } else {
                target.horizontalScale((short) resolvedRun.horizontalScale().doubleValue());
            }
        }
        if (opts.applyVerticalScale
                && resolvedRun.verticalScale() != null
                && resolvedRun.verticalScale() != 0
                && resolvedRun.verticalScale() != 100) {
            target.verticalScale((short) resolvedRun.verticalScale().doubleValue());
        }
        if (resolvedRun.baselineShift() != null && resolvedRun.baselineShift() != 0) {
            target.baselineShift((short) resolvedRun.baselineShift().doubleValue());
        }
        if (Boolean.TRUE.equals(resolvedRun.underline())) {
            target.underline(true);
        }
        if (Boolean.TRUE.equals(resolvedRun.strikeThru())) {
            target.strikeThrough(true);
        }
        String position = resolvedRun.position();
        if (opts.applyPosition && position != null) {
            String p = position.toLowerCase(Locale.ROOT);
            if (p.contains("superscript")) target.superscript(true);
            if (p.contains("subscript")) target.subscript(true);
        }
    }

    private static IDMLStyleDef resolvedCharacterStyle(
            StylePropertyResolver styleResolver,
            IDMLCharacterRun characterRun) {
        if (styleResolver == null || characterRun == null) return null;
        String ref = characterRun.appliedCharacterStyle();
        if (isNoCharacterStyle(ref)) return null;
        return styleResolver.getResolvedCharacterStyle(ref);
    }

    private static IDMLStyleDef resolvedGrepStyle(
            StylePropertyResolver styleResolver,
            IDMLCharacterRun characterRun) {
        if (styleResolver == null || characterRun == null) return null;
        String ref = characterRun.grepAppliedCharStyle();
        if (isNoCharacterStyle(ref)) return null;
        return styleResolver.getResolvedCharacterStyle(ref);
    }

    private static boolean isNoCharacterStyle(String ref) {
        return ref == null || ref.isEmpty() || ref.contains("[No character style]");
    }

    private static String resolveColor(ResolvedData resolvedData, String colorRef) {
        if (colorRef == null || colorRef.isEmpty() || colorRef.contains("None")) return null;
        if (colorRef.startsWith("#")) return colorRef;
        if (resolvedData == null) return colorRef;
        String hex = resolvedData.resolveColorHex(colorRef);
        return hex != null ? hex : colorRef;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    private static Double firstPositive(Double... values) {
        if (values == null) return null;
        for (Double value : values) {
            if (value != null && value > 0) return value;
        }
        return null;
    }

    private static Double firstNonZero(Double... values) {
        if (values == null) return null;
        for (Double value : values) {
            if (value != null && value != 0) return value;
        }
        return null;
    }

    private static Double firstNonDefaultScale(Double... values) {
        if (values == null) return null;
        for (Double value : values) {
            if (value != null && value != 0 && value != 100.0) return value;
        }
        return null;
    }

    private static Boolean firstBoolean(Boolean... values) {
        if (values == null) return null;
        for (Boolean value : values) {
            if (value != null) return value;
        }
        return null;
    }
}
