package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLFontDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFontDef;

import java.util.*;

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

    /** 서양 폰트 매핑: IDML 폰트명 (정확 매치) → HWPX 폰트명 */
    private static final Map<String, String> WESTERN_FONT_MAP = new LinkedHashMap<String, String>();
    static {
        // Serif
        WESTERN_FONT_MAP.put("Minion Pro", "함초롬바탕");
        WESTERN_FONT_MAP.put("Times New Roman", "함초롬바탕");
        WESTERN_FONT_MAP.put("Georgia", "함초롬바탕");
        WESTERN_FONT_MAP.put("Palatino", "함초롬바탕");
        WESTERN_FONT_MAP.put("Cambria", "함초롬바탕");
        WESTERN_FONT_MAP.put("Book Antiqua", "함초롬바탕");

        // Sans-serif
        WESTERN_FONT_MAP.put("Myriad Pro", "함초롬돋움");
        WESTERN_FONT_MAP.put("Arial", "함초롬돋움");
        WESTERN_FONT_MAP.put("Arial Rounded MT Bold", "함초롬돋움");
        WESTERN_FONT_MAP.put("Helvetica", "함초롬돋움");
        WESTERN_FONT_MAP.put("Calibri", "함초롬돋움");
        WESTERN_FONT_MAP.put("Verdana", "함초롬돋움");
        WESTERN_FONT_MAP.put("Tahoma", "함초롬돋움");
        WESTERN_FONT_MAP.put("Segoe UI", "함초롬돋움");
        WESTERN_FONT_MAP.put("Roboto", "함초롬돋움");
        WESTERN_FONT_MAP.put("DIN", "함초롬돋움");
    }

    /** 기본 대체 폰트 */
    public static final String DEFAULT_HWPX_FONT = "함초롬바탕";

    /**
     * IDML 폰트 정의를 Intermediate 폰트 정의로 변환한다.
     */
    public static IntermediateFontDef mapFont(IDMLFontDef idmlFont, String id) {
        IntermediateFontDef font = new IntermediateFontDef();
        font.id(id);
        font.familyName(idmlFont.fontFamily());
        font.styleName(idmlFont.fontStyleName());
        font.fontType(mapFontType(idmlFont.fontType()));
        font.hwpxMappedName(mapToHwpxFont(idmlFont.fontFamily()));
        return font;
    }

    /**
     * IDML 폰트명 컬렉션을 Intermediate 목록으로 변환한다.
     * 중복 폰트 패밀리를 제거하고 고유한 목록을 반환한다.
     */
    public static List<IntermediateFontDef> buildFontList(Map<String, IDMLFontDef> idmlFonts) {
        List<IntermediateFontDef> result = new ArrayList<IntermediateFontDef>();
        Set<String> seenFamilies = new LinkedHashSet<String>();
        int idx = 0;

        for (IDMLFontDef idmlFont : idmlFonts.values()) {
            String family = idmlFont.fontFamily();
            if (family != null && !seenFamilies.contains(family)) {
                seenFamilies.add(family);
                result.add(mapFont(idmlFont, "font_" + idx));
                idx++;
            }
        }

        return result;
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

        // 4. 영문 키워드 폴백
        String lower = idmlFontFamily.toLowerCase();
        if (lower.contains("serif") || lower.contains("roman") || lower.contains("garamond")
                || lower.contains("minion") || lower.contains("times") || lower.contains("palatino")) {
            return "함초롬바탕";
        }
        if (lower.contains("sans") || lower.contains("gothic") || lower.contains("grotesque")
                || lower.contains("arial") || lower.contains("helvetica") || lower.contains("myriad")
                || lower.contains("rounded")) {
            return "함초롬돋움";
        }

        return DEFAULT_HWPX_FONT;
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
