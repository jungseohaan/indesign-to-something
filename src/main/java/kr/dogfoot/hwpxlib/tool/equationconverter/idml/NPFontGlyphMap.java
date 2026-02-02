package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import java.util.HashMap;
import java.util.Map;

/**
 * NP 커스텀 폰트의 ASCII 글리프를 수학 기호로 매핑하는 테이블.
 * InDesign에서 NP_SUN, NP_YP 등의 폰트는 ASCII 문자를 수학 기호로 렌더링한다.
 */
public class NPFontGlyphMap {

    /**
     * NP Font 카테고리
     */
    public enum FontCategory {
        /** 일반 텍스트/연산자 (PE, BE) - lim, sin 등 함수명 */
        OPERATOR,
        /** 변수 (YP, YB) - 이탤릭 변수 */
        VARIABLE,
        /** 아래첨자 인덱스 (ISHS, BISHS, PSHS, BSHS) */
        SUBSCRIPT_INDEX,
        /** 분수선 (BUN, BUNB) */
        FRACTION_BAR,
        /** 특수 기호 (SUN, SUNB) - "!" = →, 기타 */
        SPECIAL_SYMBOL,
        /** 적분 (INTE, INTEB) - "@" = ∫ */
        INTEGRAL,
        /** 시그마/합 (SIG, SIGB) */
        SUMMATION,
        /** 극한 (LIM, LIMB) */
        LIMIT,
        /** 근호 (RUT, RUTB) */
        ROOT,
        /** 위첨자 (SUSP, SUSB) */
        SUPERSCRIPT_INDEX,
        /** 이탤릭 (IE, BIE) - 이탤릭 변수/텍스트 */
        ITALIC,
        /** 알 수 없는 카테고리 */
        UNKNOWN
    }

    private static final Map<String, FontCategory> FONT_CATEGORY_MAP = new HashMap<String, FontCategory>();
    private static final Map<String, Map<String, String>> GLYPH_MAP = new HashMap<String, Map<String, String>>();

    static {
        // Font → Category 매핑
        FONT_CATEGORY_MAP.put("NP_PE", FontCategory.OPERATOR);
        FONT_CATEGORY_MAP.put("NP_BE", FontCategory.OPERATOR);
        FONT_CATEGORY_MAP.put("NP_YP", FontCategory.VARIABLE);
        FONT_CATEGORY_MAP.put("NP_YB", FontCategory.VARIABLE);
        FONT_CATEGORY_MAP.put("NP_ISHS", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_BISHS", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_PSHS", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_PSHD", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_BSHS", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_BSHD", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_ISHD", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_BISHD", FontCategory.SUBSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_BUN", FontCategory.FRACTION_BAR);
        FONT_CATEGORY_MAP.put("NP_BUNB", FontCategory.FRACTION_BAR);
        FONT_CATEGORY_MAP.put("NP_SUN", FontCategory.SPECIAL_SYMBOL);
        FONT_CATEGORY_MAP.put("NP_SUNB", FontCategory.SPECIAL_SYMBOL);
        FONT_CATEGORY_MAP.put("NP_INTE", FontCategory.INTEGRAL);
        FONT_CATEGORY_MAP.put("NP_INTEB", FontCategory.INTEGRAL);
        FONT_CATEGORY_MAP.put("NP_SIG", FontCategory.SUMMATION);
        FONT_CATEGORY_MAP.put("NP_SIGB", FontCategory.SUMMATION);
        FONT_CATEGORY_MAP.put("NP_LIM", FontCategory.LIMIT);
        FONT_CATEGORY_MAP.put("NP_LIMB", FontCategory.LIMIT);
        FONT_CATEGORY_MAP.put("NP_RUT", FontCategory.ROOT);
        FONT_CATEGORY_MAP.put("NP_RUTB", FontCategory.ROOT);
        FONT_CATEGORY_MAP.put("NP_SUSP", FontCategory.SUPERSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_SUSB", FontCategory.SUPERSCRIPT_INDEX);
        FONT_CATEGORY_MAP.put("NP_IE", FontCategory.ITALIC);
        FONT_CATEGORY_MAP.put("NP_BIE", FontCategory.ITALIC);

        // NP_SUN/NP_SUNB 특수 글리프 매핑
        Map<String, String> sunMap = new HashMap<String, String>();
        sunMap.put("!", " -> ");  // → (화살표)
        sunMap.put("@", " -> ");  // → (화살표 변형)
        GLYPH_MAP.put("NP_SUN", sunMap);
        GLYPH_MAP.put("NP_SUNB", sunMap);

        // NP_YP/NP_YB 특수 글리프 매핑
        Map<String, String> ypMap = new HashMap<String, String>();
        ypMap.put("E", "inf");    // ∞ (무한대) - subscript context에서
        ypMap.put("9", "{");      // { (중괄호 열기)
        ypMap.put("0", "}");      // } (중괄호 닫기)
        ypMap.put("y", "cdots");  // ⋯ (말줄임표)
        GLYPH_MAP.put("NP_YP", ypMap);
        GLYPH_MAP.put("NP_YB", ypMap);

        // NP_INTE/NP_INTEB 특수 글리프 매핑
        Map<String, String> inteMap = new HashMap<String, String>();
        inteMap.put("@", "int");  // ∫ (적분)
        GLYPH_MAP.put("NP_INTE", inteMap);
        GLYPH_MAP.put("NP_INTEB", inteMap);

        // NP_ISHS/NP_BISHS 아래첨자 글리프 매핑
        Map<String, String> ishsMap = new HashMap<String, String>();
        ishsMap.put("N", "n");  // N → n (아래첨자 폰트에서 대문자 N은 소문자 n으로 렌더링)
        GLYPH_MAP.put("NP_ISHS", ishsMap);
        GLYPH_MAP.put("NP_BISHS", ishsMap);
        GLYPH_MAP.put("NP_PSHS", ishsMap);
        GLYPH_MAP.put("NP_BSHS", ishsMap);
    }

    /**
     * NP 폰트 이름에서 카테고리를 반환한다.
     */
    public static FontCategory getCategory(String npFontName) {
        FontCategory cat = FONT_CATEGORY_MAP.get(npFontName);
        return cat != null ? cat : FontCategory.UNKNOWN;
    }

    /**
     * NP 폰트 이름과 ASCII 텍스트로 수학 기호 텍스트를 반환한다.
     * 매핑이 없으면 원본 텍스트를 그대로 반환한다.
     */
    public static String mapGlyph(String npFontName, String text) {
        Map<String, String> map = GLYPH_MAP.get(npFontName);
        if (map != null && map.containsKey(text)) {
            return map.get(text);
        }
        return text;
    }

    /**
     * NP 폰트 이름이 NP_ 계열인지 확인한다.
     */
    public static boolean isNPFont(String fontName) {
        return fontName != null && fontName.startsWith("NP_");
    }

    /**
     * CharacterStyle 속성값에서 NP 폰트 이름을 추출한다.
     * 예: "CharacterStyle/np서체%3aNP_PE" → "NP_PE"
     */
    public static String extractNPFontName(String characterStyleRef) {
        if (characterStyleRef == null) return null;
        int idx = characterStyleRef.lastIndexOf("NP_");
        if (idx >= 0) {
            return characterStyleRef.substring(idx);
        }
        return null;
    }
}
