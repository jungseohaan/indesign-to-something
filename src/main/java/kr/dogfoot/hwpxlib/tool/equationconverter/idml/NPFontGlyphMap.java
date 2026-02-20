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
    /** NP 폰트 ASCII → 유니코드 매핑 (텍스트 출력용) */
    private static final Map<String, Map<String, String>> UNICODE_MAP = new HashMap<String, Map<String, String>>();

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
        sunMap.put("Z", "bar ");  // 선분 기호 (overline)
        GLYPH_MAP.put("NP_SUN", sunMap);
        GLYPH_MAP.put("NP_SUNB", sunMap);

        // NP_YP/NP_YB 특수 글리프 매핑
        Map<String, String> ypMap = new HashMap<String, String>();
        ypMap.put("E", "inf");    // ∞ (무한대) - subscript context에서
        ypMap.put("9", "left lbrace ");   // { (중괄호 열기 - HWP 수식 표시용)
        ypMap.put("0", " right rbrace");  // } (중괄호 닫기 - HWP 수식 표시용)
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

        // ── 유니코드 매핑 (텍스트 출력용) ──

        // NP_SUN/NP_SUNB → 유니코드 특수 기호
        Map<String, String> sunUni = new HashMap<String, String>();
        sunUni.put("!", "\u2192");  // → (화살표)
        sunUni.put("@", "\u2192");  // → (화살표 변형)
        sunUni.put("Z", "\u0305");  // ◌̅ (결합 윗줄 - overline, 앞 글자에 결합)
        UNICODE_MAP.put("NP_SUN", sunUni);
        UNICODE_MAP.put("NP_SUNB", sunUni);

        // NP_YP/NP_YB → 유니코드
        Map<String, String> ypUni = new HashMap<String, String>();
        ypUni.put("E", "\u221E");   // ∞ (무한대)
        ypUni.put("9", "{");        // { (중괄호)
        ypUni.put("0", "}");        // } (중괄호)
        ypUni.put("y", "\u22EF");   // ⋯ (가운데 말줄임)
        ypUni.put("/", "\u2234");   // ∴ (따라서)
        UNICODE_MAP.put("NP_YP", ypUni);
        UNICODE_MAP.put("NP_YB", ypUni);

        // NP_INTE/NP_INTEB → 유니코드
        Map<String, String> inteUni = new HashMap<String, String>();
        inteUni.put("@", "\u222B");  // ∫ (적분)
        inteUni.put("N", "");        // 그룹핑 기호 — 텍스트에서는 생략
        UNICODE_MAP.put("NP_INTE", inteUni);
        UNICODE_MAP.put("NP_INTEB", inteUni);

        // NP_RUT/NP_RUTB → 유니코드
        Map<String, String> rutUni = new HashMap<String, String>();
        rutUni.put("j", "\u221A");   // √ (근호)
        // "!" "@" 등은 케이스 마커 — 소괄호로 대체하거나 생략
        UNICODE_MAP.put("NP_RUT", rutUni);
        UNICODE_MAP.put("NP_RUTB", rutUni);

        // NP_SIG/NP_SIGB → 유니코드
        Map<String, String> sigUni = new HashMap<String, String>();
        sigUni.put("S", "\u03A3");   // Σ (시그마)
        UNICODE_MAP.put("NP_SIG", sigUni);
        UNICODE_MAP.put("NP_SIGB", sigUni);

        // NP_SUSP/NP_SUSB → 유니코드 (삼각형 등)
        Map<String, String> suspUni = new HashMap<String, String>();
        suspUni.put("s", "\u25B3");  // △ (삼각형)
        UNICODE_MAP.put("NP_SUSP", suspUni);
        UNICODE_MAP.put("NP_SUSB", suspUni);

        // NP_BUN/NP_BUNB → 분수 괄호 (텍스트에서는 생략)
        Map<String, String> bunUni = new HashMap<String, String>();
        bunUni.put("[", "");  // 분수 왼쪽 괄호 → 생략 (분수 내용은 인라인 TextFrame에 있음)
        bunUni.put("]", "");  // 분수 오른쪽 괄호 → 생략
        UNICODE_MAP.put("NP_BUN", bunUni);
        UNICODE_MAP.put("NP_BUNB", bunUni);

        // NP_ISHS 등 아래첨자: 유니코드 아래첨자 숫자 사용
        Map<String, String> ishsUni = new HashMap<String, String>();
        ishsUni.put("0", "\u2080"); ishsUni.put("1", "\u2081");
        ishsUni.put("2", "\u2082"); ishsUni.put("3", "\u2083");
        ishsUni.put("4", "\u2084"); ishsUni.put("5", "\u2085");
        ishsUni.put("6", "\u2086"); ishsUni.put("7", "\u2087");
        ishsUni.put("8", "\u2088"); ishsUni.put("9", "\u2089");
        ishsUni.put("N", "n");      // 대문자 N → 소문자 n
        ishsUni.put("n", "\u2099"); // n → ₙ
        UNICODE_MAP.put("NP_ISHS", ishsUni);
        UNICODE_MAP.put("NP_BISHS", ishsUni);
        UNICODE_MAP.put("NP_ISHD", ishsUni);
        UNICODE_MAP.put("NP_BISHD", ishsUni);
        UNICODE_MAP.put("NP_PSHS", ishsUni);
        UNICODE_MAP.put("NP_PSHD", ishsUni);
        UNICODE_MAP.put("NP_BSHS", ishsUni);
        UNICODE_MAP.put("NP_BSHD", ishsUni);
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
     * NP 폰트 ASCII 텍스트를 유니코드 문자열로 변환한다 (텍스트 출력용).
     * 매핑이 없으면 원본 텍스트를 그대로 반환한다.
     */
    public static String mapToUnicode(String npFontName, String text) {
        if (text == null || text.isEmpty()) return text;
        Map<String, String> map = UNICODE_MAP.get(npFontName);
        if (map == null) return text; // 매핑 테이블 없으면 원본 반환

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = map.get(ch);
            if (mapped != null) {
                sb.append(mapped);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * NP 폰트 런의 전체 텍스트를 유니코드로 변환한다.
     * 카테고리에 따라 적절한 매핑을 적용한다.
     */
    public static String convertRunToUnicode(String npFontName, String text) {
        if (npFontName == null || text == null || text.isEmpty()) return text;
        return mapToUnicode(npFontName, text);
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
