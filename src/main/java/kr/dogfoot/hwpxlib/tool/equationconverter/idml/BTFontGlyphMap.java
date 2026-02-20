package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

/**
 * BT수식M 커스텀 폰트 체계의 스타일 감지.
 * InDesign 한국 수학 교과서에서 사용되는 BT수식M 폰트 패밀리:
 * - BT수식M:0-Regular (연산자, C/P 기호, 팩토리얼)
 * - BT수식M:0-ltalic (이탤릭 변수, 아래첨자)
 * - BT수식M:1-BoldItalic (강조 수식)
 * - BT수식M:0-Input (입력 필드)
 * - BT수식M:1-bold (볼드 기호)
 * - BT수식M:5-괄호Italic (BTM괄호 폰트 — 괄호 글리프)
 * - BT수식M:2-루트Italic (BTM루트 — 근호)
 * - BT수식M:7-분수Italic (BTM분수 — 분수선)
 * - BT수식M:8-점Italic (BTM점 — 점)
 * - BT수식M:9-화살표Italic (BTM화살표 — 화살표)
 */
public class BTFontGlyphMap {

    /**
     * AppliedCharacterStyle 문자열이 BT수식M 폰트인지 확인.
     */
    public static boolean isBTFontStyle(String styleRef) {
        if (styleRef == null) return false;
        // URL-encoded: BT%ec%88%98%ec%8b%9d = BT수식, BTM = BTM괄호/BTM루트 등
        return styleRef.contains("BT%ec%88%98%ec%8b%9d")  // BT수식 (URL-encoded)
                || styleRef.contains("BT수식")              // BT수식 (decoded)
                || styleRef.contains("BTM");
    }

    /**
     * fontFamily 문자열이 BT수식M 폰트인지 확인.
     */
    public static boolean isBTFontFamily(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.contains("BT수식") || fontFamily.contains("BTM");
    }

    /**
     * BT수식M 스타일에서 서브폰트 유형을 추출.
     * @return "Regular", "ltalic", "BoldItalic", "괄호Italic", "루트Italic", etc. 또는 null
     */
    public static String extractSubFont(String styleRef) {
        if (styleRef == null) return null;
        // "CharacterStyle/BT수식M%3a0-Regular" → "Regular"
        // "CharacterStyle/BT%ec%88%98%ec%8b%9dM%3a5-%ea%b4%84%ed%98%b8Italic" → "괄호Italic"
        int dashIdx = styleRef.lastIndexOf('-');
        if (dashIdx < 0) return null;
        String suffix = styleRef.substring(dashIdx + 1);
        // URL-decode common patterns
        suffix = suffix.replace("%ea%b4%84%ed%98%b8", "괄호")
                       .replace("%eb%a3%a8%ed%8a%b8", "루트")
                       .replace("%eb%b6%84%ec%88%98", "분수")
                       .replace("%ec%a0%90", "점")
                       .replace("%ed%99%94%ec%82%b4%ed%91%9c", "화살표");
        return suffix;
    }
}
