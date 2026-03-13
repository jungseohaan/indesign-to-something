package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.*;
import java.util.LinkedHashSet;

/**
 * IDML 폰트 → Intermediate/HWPX 폰트 매핑.
 *
 * 매핑 우선순위:
 * 1. KOREAN_FONT_MAP — 한글 폰트 부분 매치 (구체적인 폰트명 우선)
 * 2. WESTERN_FONT_MAP — 서양 폰트 정확 매치
 * 3. 한국어 키워드 폴백 — "명조", "고딕", "부리" 등
 * 4. 영문 키워드 폴백 — "serif", "sans", "gothic" 등
 * 5. 기본값 — 함초롬바탕
 */
public class FontMapper {

    /** 한글 폰트 매핑: IDML 폰트명 (부분 매치) → HWPX 폰트명 */
    private static final Map<String, String> KOREAN_FONT_MAP = new LinkedHashMap<String, String>();
    static {
        // ── 윤디자인 ──
        KOREAN_FONT_MAP.put("윤명조", "함초롬바탕");
        KOREAN_FONT_MAP.put("윤고딕", "함초롬돋움");

        // ── Sandoll ──
        KOREAN_FONT_MAP.put("Sandoll 명조", "함초롬바탕");
        KOREAN_FONT_MAP.put("Sandoll 고딕", "함초롬돋움");
        KOREAN_FONT_MAP.put("Sandoll 안단테", "함초롬바탕");
        KOREAN_FONT_MAP.put("Sandoll 제비", "함초롬돋움");
        KOREAN_FONT_MAP.put("Sandoll 고고", "함초롬돋움");

        // ── Rix ──
        KOREAN_FONT_MAP.put("Rix정고딕", "함초롬돋움");
        KOREAN_FONT_MAP.put("Rix착한아이", "함초롬돋움");
        KOREAN_FONT_MAP.put("Rix개봉박두", "함초롬돋움");
        KOREAN_FONT_MAP.put("Rix", "함초롬돋움");

        // ── 210 시리즈 (장식/디스플레이) ──
        KOREAN_FONT_MAP.put("210 나무굴림", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 나무젓가락", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 네모진", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 데이라잇", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 딱지치기", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 밤의해변", "함초롬바탕");
        KOREAN_FONT_MAP.put("210 비밀정원", "함초롬바탕");
        KOREAN_FONT_MAP.put("210 자연공원", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 한반도", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 가장자리", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 공중전화", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 꽃길", "함초롬바탕");
        KOREAN_FONT_MAP.put("210 늘솔길", "함초롬바탕");
        KOREAN_FONT_MAP.put("210 라임", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 리얼러브", "함초롬바탕");
        KOREAN_FONT_MAP.put("210 생활반장", "함초롬돋움");
        KOREAN_FONT_MAP.put("210 잎새바람", "함초롬바탕");
        KOREAN_FONT_MAP.put("210", "함초롬돋움");

        // ── HU 시리즈 (손글씨/캘리그래피) ──
        KOREAN_FONT_MAP.put("HU가는펜글씨", "함초롬바탕");
        KOREAN_FONT_MAP.put("HU금요일오후", "함초롬바탕");
        KOREAN_FONT_MAP.put("HU너무자몽다", "함초롬돋움");
        KOREAN_FONT_MAP.put("HU달달한코코아", "함초롬바탕");
        KOREAN_FONT_MAP.put("HU바야흐로꽃", "함초롬바탕");
        KOREAN_FONT_MAP.put("HU", "함초롬바탕");

        // ── DX ──
        KOREAN_FONT_MAP.put("DX바른필기", "함초롬바탕");
        KOREAN_FONT_MAP.put("DX새명조", "함초롬바탕");
        KOREAN_FONT_MAP.put("DX", "함초롬바탕");

        // ── THE ──
        KOREAN_FONT_MAP.put("THE삐끗삐끗", "함초롬돋움");
        KOREAN_FONT_MAP.put("THE수수깡", "함초롬돋움");
        KOREAN_FONT_MAP.put("THE", "함초롬돋움");

        // ── 기타 한글 폰트 ──
        KOREAN_FONT_MAP.put("둘기마요_고딕", "HG꼬딕체");
        KOREAN_FONT_MAP.put("둘기마요", "HG꼬딕체");
        KOREAN_FONT_MAP.put("마루 부리", "함초롬바탕");
        KOREAN_FONT_MAP.put("양진체", "HG꼬딕체");
        KOREAN_FONT_MAP.put("나눔스퀘어", "함초롬돋움");
        KOREAN_FONT_MAP.put("땅스부대찌개", "함초롬돋움");
        KOREAN_FONT_MAP.put("ONE 모바일POP", "함초롬돋움");
        KOREAN_FONT_MAP.put("TT더좋은날에", "함초롬바탕");
        KOREAN_FONT_MAP.put("ViMaru", "함초롬바탕");

        // ── Adobe / Noto ──
        KOREAN_FONT_MAP.put("Adobe 명조", "함초롬바탕");
        KOREAN_FONT_MAP.put("Adobe 고딕", "함초롬돋움");
        KOREAN_FONT_MAP.put("Noto Sans", "함초롬돋움");
        KOREAN_FONT_MAP.put("Noto Serif", "함초롬바탕");
        KOREAN_FONT_MAP.put("본명조", "함초롬바탕");
        KOREAN_FONT_MAP.put("본고딕", "함초롬돋움");

        // ── 나눔 시리즈 ──
        KOREAN_FONT_MAP.put("나눔명조", "함초롬바탕");
        KOREAN_FONT_MAP.put("나눔고딕", "함초롬돋움");
        KOREAN_FONT_MAP.put("나눔바른", "함초롬돋움");
        KOREAN_FONT_MAP.put("나눔손글씨", "함초롬바탕");
        KOREAN_FONT_MAP.put("나눔", "함초롬돋움");

        // ── 기본 시스템 폰트 ──
        KOREAN_FONT_MAP.put("맑은 고딕", "함초롬돋움");
        KOREAN_FONT_MAP.put("바탕", "함초롬바탕");
        KOREAN_FONT_MAP.put("돋움", "함초롬돋움");
        KOREAN_FONT_MAP.put("굴림", "함초롬돋움");
        KOREAN_FONT_MAP.put("궁서", "함초롬바탕");
        KOREAN_FONT_MAP.put("신명조", "함초롬바탕");
    }

    /** 서양 폰트 → 한글 폴백 매핑 (hangul 슬롯용) */
    private static final Map<String, String> WESTERN_FONT_HANGUL_MAP = new LinkedHashMap<String, String>();
    /** 서양 폰트 세트 (latin 슬롯에 원본 폰트명 유지 대상) */
    private static final Set<String> WESTERN_FONT_SET = new LinkedHashSet<String>();
    static {
        // Serif → hangul 폴백: 함초롬바탕
        String[] serifFonts = {
            "Minion Pro", "Times New Roman", "Georgia", "Palatino", "Cambria",
            "Book Antiqua", "Garamond", "Baskerville", "Caslon", "Bodoni",
            "Century", "Source Serif Pro"
        };
        for (String f : serifFonts) {
            WESTERN_FONT_HANGUL_MAP.put(f, "함초롬바탕");
            WESTERN_FONT_SET.add(f);
        }

        // Sans-serif → hangul 폴백: 함초롬돋움
        String[] sansFonts = {
            "Myriad Pro", "Arial", "Arial Rounded MT Bold", "Helvetica",
            "Helvetica Neue", "Calibri", "Verdana", "Tahoma", "Segoe UI",
            "Roboto", "DIN", "Montserrat", "Futura", "Lato", "Open Sans",
            "Source Sans Pro", "Proxima Nova", "Gotham", "Avenir", "Gill Sans",
            "Century Gothic", "Trebuchet MS", "Franklin Gothic", "Frutiger",
            "Univers", "Impact"
        };
        for (String f : sansFonts) {
            WESTERN_FONT_HANGUL_MAP.put(f, "함초롬돋움");
            WESTERN_FONT_SET.add(f);
        }

        // Monospace → hangul 폴백: 함초롬돋움
        String[] monoFonts = {
            "Courier", "Courier New", "Consolas", "Monaco", "Menlo", "Source Code Pro"
        };
        for (String f : monoFonts) {
            WESTERN_FONT_HANGUL_MAP.put(f, "함초롬돋움");
            WESTERN_FONT_SET.add(f);
        }
    }

    // 하위 호환용: 기존 WESTERN_FONT_MAP → hangul 폴백 반환
    private static final Map<String, String> WESTERN_FONT_MAP = WESTERN_FONT_HANGUL_MAP;

    /** 기본 대체 폰트 */
    public static final String DEFAULT_HWPX_FONT = "함초롬바탕";

    /**
     * JSON 파일에서 사용자 지정 폰트 매핑을 로드한다.
     * JSON 형식: {"IDML폰트명": "HWPX폰트명", ...}
     */
    public static Map<String, String> loadFontMapFromJson(String jsonPath) {
        try {
            Reader reader = new FileReader(jsonPath);
            try {
                Type mapType = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> map = new Gson().fromJson(reader, mapType);
                return map != null ? map : Collections.<String, String>emptyMap();
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            System.err.println("Warning: 폰트 매핑 파일 로드 실패 (" + jsonPath + "): " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * IDML 폰트 패밀리명을 HWPX 폰트명으로 매핑한다.
     * 커스텀 맵이 있으면 최우선으로 적용한다.
     */
    public static String mapToHwpxFont(String idmlFontFamily, Map<String, String> customMap) {
        if (idmlFontFamily == null) return DEFAULT_HWPX_FONT;

        // 0. 커스텀 맵 정확 매치 (최우선)
        if (customMap != null) {
            String custom = customMap.get(idmlFontFamily);
            if (custom != null) return custom;
        }

        return mapToHwpxFont(idmlFontFamily);
    }

    /**
     * IDML 폰트 패밀리명을 HWPX 폰트명으로 매핑한다.
     */
    public static String mapToHwpxFont(String idmlFontFamily) {
        if (idmlFontFamily == null) return DEFAULT_HWPX_FONT;

        // 1. 한글 폰트 매핑 (부분 매치, 구체적 → 일반적 순서)
        for (Map.Entry<String, String> entry : KOREAN_FONT_MAP.entrySet()) {
            if (idmlFontFamily.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 2. 서양 폰트 매핑 (정확 매치)
        String western = WESTERN_FONT_MAP.get(idmlFontFamily);
        if (western != null) return western;

        // 3. 한국어 키워드 폴백
        if (idmlFontFamily.contains("명조") || idmlFontFamily.contains("부리")) {
            return "함초롬바탕";
        }
        if (idmlFontFamily.contains("고딕") || idmlFontFamily.contains("돋움")) {
            return "함초롬돋움";
        }

        // 4. 영문 키워드 폴백 (sans를 serif보다 먼저 검사 — "sans-serif" 오분류 방지)
        String lower = idmlFontFamily.toLowerCase();
        if (lower.contains("sans") || lower.contains("gothic") || lower.contains("grotesque")
                || lower.contains("arial") || lower.contains("helvetica") || lower.contains("myriad")
                || lower.contains("rounded")) {
            return "함초롬돋움";
        }
        if (lower.contains("serif") || lower.contains("roman") || lower.contains("garamond")
                || lower.contains("minion") || lower.contains("times") || lower.contains("palatino")) {
            return "함초롬바탕";
        }

        return DEFAULT_HWPX_FONT;
    }

    /**
     * 서양 폰트 여부 판별.
     * 등록된 서양 폰트 이름에 정확히 매치하거나, 영문 키워드 폴백에 해당하면 true.
     */
    public static boolean isWesternFont(String fontFamily) {
        if (fontFamily == null) return false;
        if (WESTERN_FONT_SET.contains(fontFamily)) return true;
        // 정확 매치 안 되면 키워드 기반 판별
        String lower = fontFamily.toLowerCase();
        // 한글 문자가 포함되면 서양 폰트 아님
        for (int i = 0; i < fontFamily.length(); i++) {
            char c = fontFamily.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false; // 한글 음절
            if (c >= 0x3131 && c <= 0x318E) return false; // 한글 자모
        }
        // 한글 키워드가 포함되면 서양 폰트 아님
        if (lower.contains("명조") || lower.contains("부리") || lower.contains("고딕")
                || lower.contains("돋움") || lower.contains("굴림") || lower.contains("바탕")) {
            return false;
        }
        // 순수 영문+숫자+공백+기호로만 이루어진 경우 서양 폰트로 판별
        return fontFamily.matches("[\\x20-\\x7E]+");
    }

    /**
     * IDML 폰트 → HWPX 폰트 매핑 결과 (hangul + latin 슬롯).
     */
    public static String[] mapToHwpxFontPair(String idmlFontFamily, Map<String, String> customMap) {
        if (idmlFontFamily == null) return new String[]{DEFAULT_HWPX_FONT, DEFAULT_HWPX_FONT};

        // 커스텀 맵 (양쪽 슬롯 동일)
        if (customMap != null) {
            String custom = customMap.get(idmlFontFamily);
            if (custom != null) return new String[]{custom, custom};
        }

        return mapToHwpxFontPair(idmlFontFamily);
    }

    /**
     * IDML 폰트 → HWPX 폰트 매핑 결과 (hangul + latin 슬롯).
     * 서양 폰트: [한글 폴백, 원본 서양 폰트명]
     * 한글 폰트: [매핑 폰트, 매핑 폰트] (동일)
     */
    public static String[] mapToHwpxFontPair(String idmlFontFamily) {
        if (idmlFontFamily == null) return new String[]{DEFAULT_HWPX_FONT, DEFAULT_HWPX_FONT};

        // 1. 한글 폰트 매핑 → hangul, latin 동일
        for (Map.Entry<String, String> entry : KOREAN_FONT_MAP.entrySet()) {
            if (idmlFontFamily.contains(entry.getKey())) {
                String mapped = entry.getValue();
                return new String[]{mapped, mapped};
            }
        }

        // 2. 서양 폰트 → hangul 폴백 + latin 원본
        String hangulFallback = WESTERN_FONT_HANGUL_MAP.get(idmlFontFamily);
        if (hangulFallback != null) {
            return new String[]{hangulFallback, idmlFontFamily};
        }

        // 3. 한국어 키워드 폴백 → 동일
        if (idmlFontFamily.contains("명조") || idmlFontFamily.contains("부리")) {
            return new String[]{"함초롬바탕", "함초롬바탕"};
        }
        if (idmlFontFamily.contains("고딕") || idmlFontFamily.contains("돋움")) {
            return new String[]{"함초롬돋움", "함초롬돋움"};
        }

        // 4. 영문 키워드 폴백 — 서양 폰트로 간주
        String lower = idmlFontFamily.toLowerCase();
        if (isWesternFont(idmlFontFamily)) {
            String fallback;
            if (lower.contains("sans") || lower.contains("gothic") || lower.contains("grotesque")
                    || lower.contains("arial") || lower.contains("helvetica") || lower.contains("myriad")
                    || lower.contains("rounded")) {
                fallback = "함초롬돋움";
            } else if (lower.contains("serif") || lower.contains("roman") || lower.contains("garamond")
                    || lower.contains("minion") || lower.contains("times") || lower.contains("palatino")) {
                fallback = "함초롬바탕";
            } else {
                fallback = DEFAULT_HWPX_FONT;
            }
            return new String[]{fallback, idmlFontFamily};
        }

        return new String[]{DEFAULT_HWPX_FONT, DEFAULT_HWPX_FONT};
    }

    /**
     * IDML FontType → Intermediate fontType.
     */
    public static String mapFontType(String idmlFontType) {
        if (idmlFontType == null) return "TTF";
        if (idmlFontType.contains("OpenType") || idmlFontType.contains("OTF")) return "OTF";
        if (idmlFontType.contains("TrueType") || idmlFontType.contains("TTF")) return "TTF";
        return "TTF";
    }
}
