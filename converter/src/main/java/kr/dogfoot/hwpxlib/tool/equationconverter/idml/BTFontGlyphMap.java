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
                || styleRef.contains("BTM")
                || isBTArrowFontStyle(styleRef);
    }

    /**
     * fontFamily 문자열이 BT수식M 폰트인지 확인.
     *
     * <p><b>"BT수식H" 계열은 수식 폰트가 아니다.</b> 이름만 "수식"일 뿐, 과학 교과서에서는
     * 본문 속 영문/숫자/기호를 조판하는 데 쓰인다(GREP 스타일 "00_영문", "00_숫자"가
     * 이 폰트를 적용한다). 실측 내용:
     * <pre>
     *   BT수식H-분수N    (356회)  "1-1", "1-2", "1.", "2.", "H", "O", "2"
     *   BT수식H-편한글씨  (48회)   ":"   (비율 표기)
     * </pre>
     * 분수도 루트도 시그마도 없다. 이걸 수식 폰트로 취급하면 한글 문장 속 원소기호
     * (수소 원자(H))나 번호(1.)까지 HWP 수식(이탤릭)으로 바뀐다.
     *
     * <p>진짜 수식은 수학 교과서의 BT수식M / BTM(괄호·루트·분수·시그마) 계열이다.
     */
    public static boolean isBTFontFamily(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.contains("BT수식")
                || fontFamily.contains("BTM")
                || isBTArrowFont(fontFamily);
    }

    /**
     * "BT수식H" 계열 — 이름은 수식이지만 실제로는 본문 영문/숫자/기호 조판용 폰트.
     */
    public static boolean isBTBodyTextFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.contains("BT수식H");
    }

    /**
     * BT 화살표 전용 폰트인지 확인.
     *
     * 과학 교과서는 화학 반응식의 화살표(→)를 "BT화살표" 라는 별도 폰트로 조판한다.
     * 이 폰트명에는 "BT수식"/"BTM" 이 들어있지 않아 기존 isBTFontFamily 가 잡지 못했고,
     * 그 결과 화살표 런이 BT 수식 그룹에 포함되지 못해 수식이 화살표 지점에서 끊겼다.
     * (예: "N2+3H2 → 2NH3" 이 'N2+' / 'H2 rarrow' 두 수식으로 분할)
     */
    public static boolean isBTArrowFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.contains("BT화살표");
    }

    public static boolean isBTArrowFontStyle(String styleRef) {
        if (styleRef == null) return false;
        // "화살표" URL-encoded = %ed%99%94%ec%82%b4%ed%91%9c
        return styleRef.contains("BT화살표")
                || styleRef.contains("BT%ed%99%94%ec%82%b4%ed%91%9c");
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
