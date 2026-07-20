package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import java.util.function.BiFunction;
import java.util.function.Function;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

/**
 * 텍스트 런(ASTTextRun)에 fontFamily/fontSize/textColor 등 핵심 속성을 적용할 때
 * 단일 우선순위 규칙을 강제하는 헬퍼.
 *
 * <p>기본 우선순위: <b>IDML CharacterRun → resolved → ParagraphStyle → default</b>
 *
 * <p>초기 SPEC-012 초안은 resolved 우선을 시도했으나(검증 시 회귀 발견):
 * splitIdmlRunByResolvedRuns의 rr 매칭이 불완전한 케이스에서 잘못된 resolved 런의
 * 속성이 적용되어 IDML의 올바른 값을 덮어쓰는 문제가 있었다. 따라서 IDML CR을
 * 우선하고 resolved는 IDML에 값이 없는 경우의 폴백으로만 사용한다.
 *
 * <p>이 헬퍼의 가치: (1) 우선순위 로직을 단일 지점에 집중 (2) 추후 튜닝 시 한 곳만
 * 수정하면 모든 호출 경로에 반영.
 *
 * <p>관련 SPEC: docs/specs/SPEC-012-resolved-priority-unified.md
 */
public final class RunPropertyResolver {
    private RunPropertyResolver() {}

    /**
     * fontFamily 우선순위 해석.
     *
     * <p>EH 수식 폰트나 BT 수식 폰트가 한국어 텍스트에 잘못 적용되는 것을 막기 위해
     * 후보별로 EH 필터를 적용한다. 단일 라틴 문자에는 EH 폰트도 허용한다.
     *
     * @return 적용할 fontFamily, 없으면 null
     */
    public static String resolveFontFamily(
            ResolvedRun rr,
            IDMLCharacterRun cr,
            String paragraphStyleFontFamily,
            String text) {
        // 1. IDML CharacterRun 우선 (원본 의도)
        if (cr != null) {
            String ff = cr.fontFamily();
            if (ff != null && passesFontFilter(ff, text)) return ff;
        }
        // 2. resolved 폴백 (IDML에 값이 없을 때)
        if (rr != null) {
            String ff = rr.fontFamily();
            if (ff != null && passesFontFilter(ff, text)) return ff;
        }
        // 3. ParagraphStyle 폴백
        if (paragraphStyleFontFamily != null) return paragraphStyleFontFamily;
        return null;
    }

    /**
     * fontSize(HWPUNIT) 우선순위 해석.
     *
     * @return 적용할 fontSize(hwpunit), 없으면 null
     */
    public static Integer resolveFontSizeHwpunits(
            ResolvedRun rr,
            IDMLCharacterRun cr,
            Double paragraphStyleFontSize) {
        if (cr != null && cr.fontSize() != null && cr.fontSize() > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(cr.fontSize());
        }
        if (rr != null && rr.fontSize() != null && rr.fontSize() > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(rr.fontSize());
        }
        if (paragraphStyleFontSize != null && paragraphStyleFontSize > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(paragraphStyleFontSize);
        }
        return null;
    }

    /**
     * 텍스트 색상(hex) 우선순위 해석.
     *
     * @param effectiveIdmlColor IDML CharacterRun 또는 grepAppliedCharStyle을 통해
     *                           이미 효과 색상으로 결정된 IDML 색상 (#RRGGBB 또는
     *                           IDML color ref). 호출측에서 GREP 오버라이드를 미리
     *                           적용한 결과를 넘긴다.
     * @param colorResolver 색상 ref → hex 변환 함수
     * @return 적용할 hex 색상, 없으면 null
     */
    public static String resolveTextColorHex(
            ResolvedRun rr,
            String effectiveIdmlColor,
            String paragraphStyleColorHex,
            Function<String, String> colorResolver) {
        return resolveTextColorHex(rr, effectiveIdmlColor, null, paragraphStyleColorHex,
                colorResolver, null);
    }

    public static String resolveTextColorHex(
            ResolvedRun rr,
            String effectiveIdmlColor,
            Double effectiveIdmlTint,
            String paragraphStyleColorHex,
            Function<String, String> colorResolver,
            BiFunction<String, Double, String> tintedColorResolver) {
        if (effectiveIdmlColor != null) {
            String hex = resolveColor(effectiveIdmlColor, effectiveIdmlTint,
                    colorResolver, tintedColorResolver);
            if (hex != null) return hex;
        }
        if (paragraphStyleColorHex != null) {
            return paragraphStyleColorHex;
        }
        if (rr != null && rr.fillColor() != null) {
            String hex = resolveColor(rr.fillColor(), effectiveIdmlTint,
                    colorResolver, tintedColorResolver);
            if (hex != null) return hex;
        }
        return null;
    }

    // ── SPEC-016: confidence-aware 변형 ───────────────────────────────────

    /**
     * SPEC-016: 매칭 신뢰도를 고려한 fontFamily 해석.
     *
     * <p>HIGH/MEDIUM: resolved 우선. LOW: IDML CR 우선 (기존 동작).
     */
    public static String resolveFontFamilyWithConfidence(
            ResolvedRun rr,
            IDMLCharacterRun cr,
            String paragraphStyleFontFamily,
            String text,
            MatchConfidence confidence) {
        if ((confidence == MatchConfidence.HIGH || confidence == MatchConfidence.MEDIUM) && rr != null) {
            String ff = rr.fontFamily();
            if (ff != null && passesFontFilter(ff, text)) return ff;
        }
        // IDML CR 우선 (LOW 또는 resolved가 없을 때)
        return resolveFontFamily(rr, cr, paragraphStyleFontFamily, text);
    }

    /**
     * fontStyle 우선순위 해석.
     *
     * <p>fontFamily와 같은 우선순위를 사용해야 HWPX font mapping 단계에서
     * family/style 조합이 서로 다른 출처에서 섞이지 않는다.
     */
    public static String resolveFontStyle(
            ResolvedRun rr,
            IDMLCharacterRun cr,
            String paragraphStyleFontStyle) {
        if (cr != null && cr.fontStyle() != null) return cr.fontStyle();
        if (rr != null && rr.fontStyle() != null) return rr.fontStyle();
        return paragraphStyleFontStyle;
    }

    /**
     * SPEC-016: 매칭 신뢰도를 고려한 fontStyle 해석.
     *
     * <p>HIGH/MEDIUM에서는 resolved의 effective style을 우선한다. LOW에서는
     * IDML CharacterRun을 우선해 잘못 매칭된 resolved run이 원본 style을 덮지
     * 못하게 한다.
     */
    public static String resolveFontStyleWithConfidence(
            ResolvedRun rr,
            IDMLCharacterRun cr,
            String paragraphStyleFontStyle,
            MatchConfidence confidence) {
        if ((confidence == MatchConfidence.HIGH || confidence == MatchConfidence.MEDIUM)
                && rr != null && rr.fontStyle() != null) {
            return rr.fontStyle();
        }
        return resolveFontStyle(rr, cr, paragraphStyleFontStyle);
    }

    /**
     * SPEC-016: 매칭 신뢰도를 고려한 fontSize 해석.
     *
     * <p>HIGH/MEDIUM에서는 resolved의 effective style 크기를 우선한다. IDML
     * CharacterStyleRange 하나가 GREP/중첩 스타일에 의해 여러 resolved run으로
     * 나뉜 경우, raw IDML 런의 크기를 고집하면 앞 세그먼트의 스타일이 뒤 세그먼트로
     * 번진다. LOW에서는 기존처럼 IDML/ParagraphStyle을 우선해 오매칭 회귀를 막는다.
     */
    public static Integer resolveFontSizeHwpunitsWithConfidence(
            ResolvedRun rr,
            IDMLCharacterRun cr,
            Double paragraphStyleFontSize,
            MatchConfidence confidence) {
        if ((confidence == MatchConfidence.HIGH || confidence == MatchConfidence.MEDIUM)
                && rr != null && rr.fontSize() != null && rr.fontSize() > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(rr.fontSize());
        }
        if (cr != null && cr.fontSize() != null && cr.fontSize() > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(cr.fontSize());
        }
        if (paragraphStyleFontSize != null && paragraphStyleFontSize > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(paragraphStyleFontSize);
        }
        if (rr != null && rr.fontSize() != null && rr.fontSize() > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(rr.fontSize());
        }
        return null;
    }

    /**
     * SPEC-016: 매칭 신뢰도를 고려한 textColor 해석.
     *
     * <p>HIGH/MEDIUM: resolved 우선. LOW: IDML CR 우선.
     */
    public static String resolveTextColorHexWithConfidence(
            ResolvedRun rr,
            String effectiveIdmlColor,
            String paragraphStyleColorHex,
            Function<String, String> colorResolver,
            MatchConfidence confidence) {
        return resolveTextColorHexWithConfidence(rr, effectiveIdmlColor, null,
                paragraphStyleColorHex, colorResolver, null, confidence);
    }

    public static String resolveTextColorHexWithConfidence(
            ResolvedRun rr,
            String effectiveIdmlColor,
            Double effectiveIdmlTint,
            String paragraphStyleColorHex,
            Function<String, String> colorResolver,
            BiFunction<String, Double, String> tintedColorResolver,
            MatchConfidence confidence) {
        // Text color is ownership-sensitive: source IDML/GREP/style metadata must
        // authorize a color before resolved composed-line values may override it.
        // InDesign DOM can report a previous inline label's Paper fill on the
        // following editable body range, while the IDML CharacterStyleRange has
        // no FillColor. Font metrics still need resolved values, but color does
        // not get the same blanket HIGH/MEDIUM priority.
        if ((confidence == MatchConfidence.HIGH || confidence == MatchConfidence.MEDIUM)
                && rr != null && rr.fillColor() != null
                && (effectiveIdmlColor != null || paragraphStyleColorHex != null)) {
            String hex = resolveColor(rr.fillColor(), null,
                    colorResolver, tintedColorResolver);
            if (hex != null) return hex;
        }
        if (effectiveIdmlColor != null && effectiveIdmlTint != null) {
            String hex = resolveColor(effectiveIdmlColor, effectiveIdmlTint,
                    colorResolver, tintedColorResolver);
            if (hex != null) return hex;
        }
        if (effectiveIdmlColor != null) {
            String hex = resolveColor(effectiveIdmlColor, effectiveIdmlTint,
                    colorResolver, tintedColorResolver);
            if (hex != null) return hex;
        }
        if (paragraphStyleColorHex != null) {
            return paragraphStyleColorHex;
        }
        return null;
    }

    private static String resolveColor(String color,
                                       Double tint,
                                       Function<String, String> colorResolver,
                                       BiFunction<String, Double, String> tintedColorResolver) {
        if (color == null || colorResolver == null) return null;
        if (tint != null) {
            String tinted = tintedColorResolver != null
                    ? tintedColorResolver.apply(color, tint)
                    : null;
            if (tinted != null) return tinted;
        }
        return colorResolver.apply(color);
    }

    /**
     * EH/BT 수식 폰트가 한국어 텍스트에 잘못 매칭되는 케이스를 거른다.
     *
     * @return 폰트를 그대로 사용해도 되면 true, 거부하면 false
     */
    private static boolean passesFontFilter(String fontFamily, String text) {
        if (fontFamily == null) return false;
        boolean isEHorBT = EHFontGlyphMap.isEHFontFamily(fontFamily)
                || fontFamily.contains("BT수식");
        if (!isEHorBT) return true;
        if (text == null) return true;
        // 단일 라틴 문자는 EH 폰트도 허용 (수식 변수)
        String trimmed = text.trim();
        boolean isSingleLatin = trimmed.length() == 1
                && Character.isLetter(trimmed.charAt(0));
        if (isSingleLatin) return true;
        // 한국어 전용 텍스트면 EH/BT 폰트 거부
        return !EHTextClassifier.isKoreanOnly(text);
    }
}
