package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

/** Applies source IDML text style metadata to resolved text runs. */
public final class SourceTextStyleResolver {
    private SourceTextStyleResolver() {}

    public static ResolvedRun copyRun(ResolvedRun source) {
        if (source == null) return null;
        ResolvedRun copy = new ResolvedRun();
        copy.text(source.text());
        copy.fontFamily(source.fontFamily());
        copy.fontSize(source.fontSize());
        copy.fontStyle(source.fontStyle());
        copy.fillColor(source.fillColor());
        copy.charStyle(source.charStyle());
        copy.tracking(source.tracking());
        copy.horizontalScale(source.horizontalScale());
        copy.verticalScale(source.verticalScale());
        copy.baselineShift(source.baselineShift());
        copy.position(source.position());
        copy.underline(source.underline());
        copy.strikeThru(source.strikeThru());
        copy.type(source.type());
        copy.anchoredObjectId(source.anchoredObjectId());
        copy.storyAnchorPlacement(source.storyAnchorPlacement());
        return copy;
    }

    public static void applyGeneratedBulletStyle(
            ResolvedBuildContext ctx,
            ResolvedRun markerRun,
            String paragraphStyleRef) {
        if (ctx == null || markerRun == null || ctx.styleResolver == null
                || paragraphStyleRef == null) {
            return;
        }
        IDMLStyleDef paragraphStyle = ctx.styleResolver.getResolvedParagraphStyle(paragraphStyleRef);
        if (paragraphStyle == null) return;
        String charStyleRef = paragraphStyle.bulletsCharacterStyle();
        if (charStyleRef != null) markerRun.charStyle(charStyleRef);
        IDMLStyleDef charStyle = charStyleRef != null
                ? ctx.styleResolver.getResolvedCharacterStyle(charStyleRef)
                : null;
        if (charStyle != null) {
            applyStyleDef(markerRun, charStyle);
        }
        if (markerRun.fontFamily() == null && paragraphStyle.bulletsFont() != null) {
            markerRun.fontFamily(paragraphStyle.bulletsFont());
        }
        if (paragraphStyle.bulletsFontStyle() != null) {
            markerRun.fontStyle(paragraphStyle.bulletsFontStyle());
        }
    }

    public static void applyCharacterRun(
            ResolvedBuildContext ctx,
            ResolvedRun target,
            IDMLCharacterRun source) {
        if (target == null || source == null) return;
        String styleRef = isRealCharacterStyle(source.grepAppliedCharStyle())
                ? source.grepAppliedCharStyle()
                : source.appliedCharacterStyle();
        if (isRealCharacterStyle(styleRef)) {
            target.charStyle(styleRef);
            IDMLStyleDef style = ctx != null && ctx.styleResolver != null
                    ? ctx.styleResolver.getResolvedCharacterStyle(styleRef)
                    : null;
            if (style != null) {
                applyStyleDef(target, style);
            }
        }
        if (source.fontFamily() != null) target.fontFamily(source.fontFamily());
        if (source.fontSize() != null) target.fontSize(source.fontSize());
        if (source.fontStyle() != null) target.fontStyle(source.fontStyle());
        if (source.fillColor() != null) target.fillColor(source.fillColor());
        if (source.tracking() != null) target.tracking(source.tracking());
        if (source.horizontalScale() != null) target.horizontalScale(source.horizontalScale());
        if (source.verticalScale() != null) target.verticalScale(source.verticalScale());
        if (source.baselineShift() != null) target.baselineShift(source.baselineShift());
        if (source.position() != null) target.position(source.position());
        if (source.underline() != null) target.underline(source.underline());
        if (source.strikeThrough() != null) target.strikeThru(source.strikeThrough());
    }

    public static boolean hasEffectiveStyle(IDMLCharacterRun run) {
        if (run == null) return false;
        return isRealCharacterStyle(run.grepAppliedCharStyle())
                || isRealCharacterStyle(run.appliedCharacterStyle())
                || run.fontFamily() != null
                || run.fontSize() != null
                || run.fontStyle() != null
                || run.fillColor() != null
                || run.tracking() != null
                || run.horizontalScale() != null
                || run.verticalScale() != null
                || run.baselineShift() != null
                || run.position() != null
                || run.underline() != null
                || run.strikeThrough() != null;
    }

    public static boolean isRealCharacterStyle(String styleRef) {
        if (styleRef == null || styleRef.isEmpty()) return false;
        return !styleRef.contains("[No character style]");
    }

    public static void applyStyleDef(ResolvedRun target, IDMLStyleDef style) {
        if (target == null || style == null) return;
        if (style.fontFamily() != null) target.fontFamily(style.fontFamily());
        if (style.fontSize() != null) target.fontSize(style.fontSize());
        if (style.fontStyle() != null) target.fontStyle(style.fontStyle());
        if (style.fillColor() != null) target.fillColor(style.fillColor());
        if (style.tracking() != null) target.tracking(style.tracking());
        if (style.horizontalScale() != null) target.horizontalScale(style.horizontalScale());
        if (style.verticalScale() != null) target.verticalScale(style.verticalScale());
        if (style.baselineShift() != null) target.baselineShift(style.baselineShift());
        if (style.position() != null) target.position(style.position());
        if (style.underline() != null) target.underline(style.underline());
        if (style.strikeThrough() != null) target.strikeThru(style.strikeThrough());
    }
}
