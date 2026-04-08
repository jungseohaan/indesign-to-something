package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

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
 * <p>우선순위: <b>resolved → IDML CharacterRun → ParagraphStyle → default</b>
 *
 * <p>이유: resolved는 InDesign이 GREP/중첩 캐릭터 스타일을 모두 적용한 후 실제로
 * 렌더링한 값이므로 가장 권위 있는 출처. IDML CharacterRun은 XML에 직접 명시된
 * 값이라 GREP/중첩 스타일 오버라이드 이전 상태일 수 있다.
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
        // 1. resolved 우선
        if (rr != null) {
            String ff = rr.fontFamily();
            if (ff != null && passesFontFilter(ff, text)) return ff;
        }
        // 2. IDML CharacterRun
        if (cr != null) {
            String ff = cr.fontFamily();
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
        if (rr != null && rr.fontSize() != null && rr.fontSize() > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(rr.fontSize());
        }
        if (cr != null && cr.fontSize() != null && cr.fontSize() > 0) {
            return (int) CoordinateConverter.pointsToHwpunits(cr.fontSize());
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
        if (rr != null && rr.fillColor() != null) {
            String hex = colorResolver.apply(rr.fillColor());
            if (hex != null) return hex;
        }
        if (effectiveIdmlColor != null) {
            String hex = colorResolver.apply(effectiveIdmlColor);
            if (hex != null) return hex;
        }
        if (paragraphStyleColorHex != null) {
            return paragraphStyleColorHex;
        }
        return null;
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
