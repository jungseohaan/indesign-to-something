package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import com.google.gson.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.FontMetricEntry;

import java.io.FileReader;
import java.io.Reader;
import java.util.*;

/**
 * IDML 폰트 → HWPX 폰트 3계층 매핑.
 *
 * 매핑 우선순위:
 * [1] 외부 JSON 오버라이드 (font-mapping.json의 mappings)
 * [2] 메트릭 기반 자동 매핑 (InDesign 측정값 vs 한/글 측정값)
 * [3] 카테고리 기반 폴백 (세리프→한컴바탕, 산세리프→한컴돋움 등)
 */
public class FontMapper {

    /** 기본 대체 폰트 */
    public static final String DEFAULT_HWPX_FONT = "함초롬바탕";
    private static final String DEFAULT_SERIF = "한컴바탕";
    private static final String DEFAULT_SANS = "한컴돋움";

    // --- [1] 외부 JSON 매핑 ---
    private Map<String, MappingEntry> externalMappings = new HashMap<String, MappingEntry>();

    // --- [2] 한/글 폰트 메트릭 (font-mapping.json의 hwpxFontMetrics) ---
    private Map<String, HwpxMetric> hwpxMetrics = new HashMap<String, HwpxMetric>();

    // --- InDesign 폰트 메트릭 (resolved.json에서 런타임 로드) ---
    private Map<String, FontMetricEntry> idmlMetrics = new HashMap<String, FontMetricEntry>();

    // --- 매핑 캐시 ---
    private Map<String, MappingResult> cache = new HashMap<String, MappingResult>();

    /**
     * font-mapping.json에서 외부 매핑 및 한/글 메트릭을 로드한다.
     */
    public void loadFontMapping(String jsonPath) {
        try {
            Reader reader = new FileReader(jsonPath);
            try {
                JsonObject root = new JsonParser().parse(reader).getAsJsonObject();

                // mappings 섹션
                if (root.has("mappings")) {
                    JsonObject mappings = root.getAsJsonObject("mappings");
                    for (Map.Entry<String, JsonElement> entry : mappings.entrySet()) {
                        JsonObject val = entry.getValue().getAsJsonObject();
                        MappingEntry me = new MappingEntry();
                        me.ko = val.has("ko") ? val.get("ko").getAsString() : DEFAULT_SANS;
                        me.en = val.has("en") ? val.get("en").getAsString() : me.ko;
                        me.spacing = val.has("spacing") ? val.get("spacing").getAsInt() : 0;
                        externalMappings.put(entry.getKey(), me);
                    }
                }

                // hwpxFontMetrics 섹션
                if (root.has("hwpxFontMetrics")) {
                    JsonObject metrics = root.getAsJsonObject("hwpxFontMetrics");
                    for (Map.Entry<String, JsonElement> entry : metrics.entrySet()) {
                        if (entry.getKey().startsWith("_")) continue; // _comment 스킵
                        JsonObject val = entry.getValue().getAsJsonObject();
                        HwpxMetric hm = new HwpxMetric();
                        hm.korWidth = val.has("korWidth") ? val.get("korWidth").getAsDouble() : 0;
                        hm.latWidth = val.has("latWidth") ? val.get("latWidth").getAsDouble() : 0;
                        hm.weight = val.has("weight") ? val.get("weight").getAsInt() : 400;
                        hm.xHeight = val.has("xHeight") ? val.get("xHeight").getAsDouble() : 0;
                        hm.ascent = val.has("ascent") ? val.get("ascent").getAsDouble() : 0;
                        hm.descent = val.has("descent") ? val.get("descent").getAsDouble() : 0;
                        hm.category = val.has("category") ? val.get("category").getAsString() : "sans";
                        hwpxMetrics.put(entry.getKey(), hm);
                    }
                }

                System.out.println("[FontMapper] font-mapping.json 로드: mappings=" + externalMappings.size()
                        + ", hwpxFontMetrics=" + hwpxMetrics.size());
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            System.err.println("[FontMapper] font-mapping.json 로드 실패: " + e.getMessage());
        }
    }

    /**
     * resolved.json에서 로드한 InDesign 폰트 메트릭을 설정한다.
     */
    public void setIdmlMetrics(List<FontMetricEntry> metrics) {
        if (metrics == null) return;
        for (FontMetricEntry m : metrics) {
            if (m.family() != null) {
                idmlMetrics.put(m.family(), m);
            }
        }
        System.out.println("[FontMapper] InDesign 폰트 메트릭 로드: " + idmlMetrics.size() + "개");
    }

    /**
     * 3계층 매핑: IDML 폰트 → HWPX [ko, en, spacingAdjust%]
     */
    public MappingResult mapFont(String idmlFontFamily) {
        if (idmlFontFamily == null) return new MappingResult(DEFAULT_HWPX_FONT, DEFAULT_HWPX_FONT, 0);

        MappingResult cached = cache.get(idmlFontFamily);
        if (cached != null) return cached;

        MappingResult result;

        // [1] 외부 JSON 명시적 매핑
        MappingEntry ext = externalMappings.get(idmlFontFamily);
        if (ext != null) {
            result = new MappingResult(ext.ko, ext.en, ext.spacing);
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → \"" + ext.ko + "\" (JSON명시)");
        }
        // [2] 메트릭 기반 자동 매핑
        else if (!hwpxMetrics.isEmpty()) {
            FontMetricEntry idmlMetric = idmlMetrics.get(idmlFontFamily);
            if (idmlMetric != null && (idmlMetric.korWidth() > 0 || idmlMetric.latWidth() > 0)) {
                result = findBestMatchByMetrics(idmlFontFamily, idmlMetric);
            } else {
                // InDesign 메트릭 없으면 카테고리 폴백
                result = categoryFallback(idmlFontFamily);
            }
        }
        // [3] 카테고리 폴백
        else {
            result = categoryFallback(idmlFontFamily);
        }

        cache.put(idmlFontFamily, result);
        return result;
    }

    /**
     * 다차원 메트릭 유사도로 최적 한/글 폰트를 선택한다.
     */
    private MappingResult findBestMatchByMetrics(String idmlFont, FontMetricEntry idmlInfo) {
        String idmlCategory = inferCategory(idmlFont);
        boolean isWestern = isWesternFont(idmlFont);

        // --- 한글 슬롯 후보: korWidth > 0 인 폰트만 ---
        double bestKoScore = -1;
        String bestKoFont = DEFAULT_SANS;
        for (Map.Entry<String, HwpxMetric> e : hwpxMetrics.entrySet()) {
            HwpxMetric hwpx = e.getValue();
            if (hwpx.korWidth <= 0) continue;

            double score = similarity(idmlInfo, idmlCategory, hwpx, false);
            if (score > bestKoScore) {
                bestKoScore = score;
                bestKoFont = e.getKey();
            }
        }

        // --- 영문 슬롯 후보: 서양 폰트면 latWidth 기반으로 별도 매칭 ---
        String bestEnFont = bestKoFont;
        double bestEnScore = -1;
        if (isWestern) {
            for (Map.Entry<String, HwpxMetric> e : hwpxMetrics.entrySet()) {
                HwpxMetric hwpx = e.getValue();
                if (hwpx.latWidth <= 0) continue;
                if (isSymbolFont(e.getKey())) continue;

                double score = similarity(idmlInfo, idmlCategory, hwpx, true);
                if (score > bestEnScore) {
                    bestEnScore = score;
                    bestEnFont = e.getKey();
                }
            }
        }

        // 자간 보정: (InDesign 한글폭 / HWPX 한글폭 - 1) * 100
        HwpxMetric hwpxInfo = hwpxMetrics.get(bestKoFont);
        int spacing = 0;
        if (hwpxInfo != null && hwpxInfo.korWidth > 0 && idmlInfo.korWidth() > 0) {
            double ratio = idmlInfo.korWidth() / hwpxInfo.korWidth;
            spacing = (int) Math.round((ratio - 1.0) * 100);
        }

        System.out.printf("[FontMap] \"%s\" → ko=\"%s\"(%.3f) en=\"%s\"(%.3f) 자간=%+d%%\n",
                idmlFont, bestKoFont, bestKoScore, bestEnFont, bestEnScore, spacing);

        return new MappingResult(bestKoFont, bestEnFont, spacing);
    }

    /** 심볼/딩뱃 폰트 제외 (영문 매칭 후보에서 제외) */
    private static boolean isSymbolFont(String name) {
        String lower = name.toLowerCase();
        return lower.contains("wing") || lower.contains("symbol") || lower.contains("webding")
                || lower.contains("monotype sorts") || lower.contains("haan w")
                || lower.contains("확장") || lower.contains("hyhwpeq") || lower.contains("hancomeqn");
    }

    /**
     * 다차원 유사도 계산 (0.0 ~ 1.0).
     * @param latinMode true이면 영문 슬롯 매칭 (latWidth 가중치 60%, korWidth 무시)
     */
    private double similarity(FontMetricEntry idml, String idmlCategory, HwpxMetric hwpx, boolean latinMode) {
        if (latinMode) {
            // 영문 슬롯: latWidth 중심 매칭
            double latSim = 0.5;
            if (idml.latWidth() > 0 && hwpx.latWidth > 0) {
                latSim = 1.0 - Math.abs(idml.latWidth() - hwpx.latWidth) / Math.max(idml.latWidth(), hwpx.latWidth);
            }
            double wSim = 1.0 - Math.abs(idml.weight() - hwpx.weight) / 900.0;
            double xSim = 0.5;
            if (idml.xHeight() > 0 && hwpx.xHeight > 0) {
                xSim = 1.0 - Math.abs(idml.xHeight() - hwpx.xHeight) / Math.max(idml.xHeight(), hwpx.xHeight);
            }
            double catBonus = idmlCategory.equals(hwpx.category) ? 1.0 : 0.0;

            return latSim * 0.60 + wSim * 0.15 + xSim * 0.15 + catBonus * 0.10;
        }

        // 한글 슬롯: 기존 6차원 매칭
        double korSim = 0.5;
        if (idml.korWidth() > 0 && hwpx.korWidth > 0) {
            korSim = 1.0 - Math.abs(idml.korWidth() - hwpx.korWidth) / Math.max(idml.korWidth(), hwpx.korWidth);
        }
        double latSim = 0.5;
        if (idml.latWidth() > 0 && hwpx.latWidth > 0) {
            latSim = 1.0 - Math.abs(idml.latWidth() - hwpx.latWidth) / Math.max(idml.latWidth(), hwpx.latWidth);
        }
        double wSim = 1.0 - Math.abs(idml.weight() - hwpx.weight) / 900.0;
        double adSim = 0.5;
        if (idml.ascent() > 0 && idml.descent() > 0 && hwpx.ascent > 0 && hwpx.descent > 0) {
            double adRatioA = idml.ascent() / (idml.ascent() + idml.descent());
            double adRatioB = hwpx.ascent / (hwpx.ascent + hwpx.descent);
            adSim = 1.0 - Math.abs(adRatioA - adRatioB);
        }
        double xSim = 0.5;
        if (idml.xHeight() > 0 && hwpx.xHeight > 0) {
            xSim = 1.0 - Math.abs(idml.xHeight() - hwpx.xHeight) / Math.max(idml.xHeight(), hwpx.xHeight);
        }
        double catBonus = idmlCategory.equals(hwpx.category) ? 1.0 : 0.0;

        return korSim * 0.40 + latSim * 0.20 + wSim * 0.15 + adSim * 0.10 + xSim * 0.05 + catBonus * 0.10;
    }

    /** 영문 카테고리 폴백: serif → Times New Roman, sans → Arial */
    private static final String DEFAULT_LATIN_SERIF = "Times New Roman";
    private static final String DEFAULT_LATIN_SANS = "Arial";

    /**
     * 카테고리 폴백 (메트릭 없을 때).
     * 1차: 폰트명 키워드로 한/글 번들 폰트에 직접 매핑
     * 2차: 대분류(세리프/산세리프)로 기본 폰트 매핑
     */
    private MappingResult categoryFallback(String idmlFontFamily) {
        String lower = idmlFontFamily.toLowerCase();
        boolean isWestern = isWesternFont(idmlFontFamily);

        // --- 1차: 키워드 기반 정밀 매핑 (한/글 번들 폰트명과 직접 연결) ---
        String keywordMatch = keywordMapping(lower);
        if (keywordMatch != null) {
            String en = isWestern ? DEFAULT_LATIN_SANS : keywordMatch;
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + keywordMatch + "\" en=\"" + en + "\" (키워드매핑)");
            return new MappingResult(keywordMatch, en, 0);
        }

        // --- 2차: 대분류 폴백 ---

        // 세리프 계열
        if (lower.contains("명조") || lower.contains("부리") || lower.contains("바탕")
                || lower.contains("serif") || lower.contains("roman") || lower.contains("garamond")
                || lower.contains("minion") || lower.contains("times") || lower.contains("palatino")
                || lower.contains("궁서")) {
            String ko = DEFAULT_SERIF;
            String en = isWestern ? DEFAULT_LATIN_SERIF : ko;
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + ko + "\" en=\"" + en + "\" (카테고리폴백: serif)");
            return new MappingResult(ko, en, 0);
        }

        // 산세리프 계열
        if (lower.contains("고딕") || lower.contains("돋움") || lower.contains("굴림")
                || lower.contains("sans") || lower.contains("gothic") || lower.contains("grotesque")
                || lower.contains("arial") || lower.contains("helvetica") || lower.contains("myriad")
                || lower.contains("rounded") || lower.contains("futura") || lower.contains("din")) {
            String ko = DEFAULT_SANS;
            String en = isWestern ? DEFAULT_LATIN_SANS : ko;
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + ko + "\" en=\"" + en + "\" (카테고리폴백: sans)");
            return new MappingResult(ko, en, 0);
        }

        // 서양 폰트 기본 → 산세리프
        if (isWestern) {
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + DEFAULT_SANS + "\" en=\"" + DEFAULT_LATIN_SANS + "\" (카테고리폴백: western-default)");
            return new MappingResult(DEFAULT_SANS, DEFAULT_LATIN_SANS, 0);
        }

        // 기본
        System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + DEFAULT_SANS + "\" en=\"" + DEFAULT_SANS + "\" (카테고리폴백: default)");
        return new MappingResult(DEFAULT_SANS, DEFAULT_SANS, 0);
    }

    /**
     * 폰트명 키워드로 한/글 번들 폰트에 직접 매핑.
     * 가변 폰트, 외부 폰트 등 이름만으로 한/글 번들에 매핑 가능한 경우.
     * @return 매핑된 한/글 번들 폰트명, 없으면 null
     */
    private static String keywordMapping(String lowerName) {
        // 윤고딕 계열 → 한컴 윤고딕 (번호 기반 웨이트 매핑)
        if (lowerName.contains("윤고딕")) {
            // 윤고딕100~300 → 한컴 윤고딕 230 (가는)
            // 윤고딕400~500 → 한컴 윤고딕 240 (중간)
            // 윤고딕600~700 → 한컴 윤고딕 250 (굵은)
            // 윤고딕700~900 → 한컴 윤고딕 760 (진한)
            int weight = extractWeightNumber(lowerName);
            if (weight <= 300) return "한컴 윤고딕 230";
            if (weight <= 500) return "한컴 윤고딕 240";
            if (weight <= 700) return "한컴 윤고딕 250";
            return "한컴 윤고딕 760";
        }
        // 윤명조 → 한컴바탕
        if (lowerName.contains("윤명조")) return DEFAULT_SERIF;
        // 나눔고딕 → 한컴돋움
        if (lowerName.contains("나눔고딕") || lowerName.contains("nanum gothic")) return DEFAULT_SANS;
        // 나눔명조 → 한컴바탕
        if (lowerName.contains("나눔명조") || lowerName.contains("nanum myeongjo")) return DEFAULT_SERIF;
        // 나눔스퀘어 → 한컴 윤고딕 740
        if (lowerName.contains("나눔스퀘어") || lowerName.contains("nanumsquare")) return "한컴 윤고딕 740";
        // 본고딕/Noto Sans → 한컴 윤고딕 720
        if (lowerName.contains("본고딕") || lowerName.contains("noto sans")) return "한컴 윤고딕 720";
        // 본명조/Noto Serif → 한컴바탕
        if (lowerName.contains("본명조") || lowerName.contains("noto serif")) return DEFAULT_SERIF;
        // 맑은 고딕 → 맑은 고딕 (한/글 번들에 포함)
        if (lowerName.contains("맑은") && lowerName.contains("고딕")) return "맑은 고딕";
        // 함초롬 → 함초롬 시리즈
        if (lowerName.contains("함초롬") && lowerName.contains("바탕")) return "함초롬바탕";
        if (lowerName.contains("함초롬") && lowerName.contains("돋움")) return "함초롬돋움";

        return null;
    }

    /** 폰트명에서 웨이트 숫자를 추출 (예: "윤고딕100" → 100, "윤고딕 700" → 700) */
    private static int extractWeightNumber(String lowerName) {
        // "윤고딕" 뒤의 숫자를 찾음
        int idx = lowerName.indexOf("윤고딕");
        if (idx >= 0) {
            String after = lowerName.substring(idx + 3); // "윤고딕" 이후
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < after.length(); i++) {
                char c = after.charAt(i);
                if (Character.isDigit(c)) {
                    sb.append(c);
                } else if (sb.length() > 0) {
                    break;
                }
            }
            if (sb.length() > 0) {
                return Integer.parseInt(sb.toString());
            }
        }
        return 400; // 기본 웨이트
    }

    /**
     * 폰트명에서 카테고리를 추론한다.
     */
    private String inferCategory(String fontFamily) {
        if (fontFamily == null) return "sans";
        String lower = fontFamily.toLowerCase();

        if (lower.contains("명조") || lower.contains("바탕") || lower.contains("부리")
                || lower.contains("serif") || lower.contains("roman") || lower.contains("minion")
                || lower.contains("times") || lower.contains("garamond") || lower.contains("궁서")) {
            return "serif";
        }
        if (lower.contains("손글씨") || lower.contains("캘리") || lower.contains("필기")
                || lower.contains("handwrit") || lower.contains("script") || lower.contains("cursive")) {
            return "handwriting";
        }
        if (lower.contains("graphic") || lower.contains("그래픽") || lower.contains("display")
                || lower.contains("headline") || lower.contains("헤드라인")) {
            return "decorative";
        }

        return "sans";
    }

    /**
     * 서양 폰트 여부 판별.
     */
    public static boolean isWesternFont(String fontFamily) {
        if (fontFamily == null) return false;
        for (int i = 0; i < fontFamily.length(); i++) {
            char c = fontFamily.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false; // 한글 음절
            if (c >= 0x3131 && c <= 0x318E) return false; // 한글 자모
        }
        String lower = fontFamily.toLowerCase();
        if (lower.contains("명조") || lower.contains("부리") || lower.contains("고딕")
                || lower.contains("돋움") || lower.contains("굴림") || lower.contains("바탕")) {
            return false;
        }
        return fontFamily.matches("[\\x20-\\x7E]+");
    }

    // --- 하위 호환 메서드 (기존 FontRegistry 호출 유지) ---

    /**
     * 하위 호환: IDML 폰트 → HWPX 폰트명 (한글 슬롯).
     */
    public static String mapToHwpxFont(String idmlFontFamily) {
        // 인스턴스 없이 호출될 때는 카테고리 폴백만 사용
        return staticCategoryFallback(idmlFontFamily);
    }

    public static String mapToHwpxFont(String idmlFontFamily, Map<String, String> customMap) {
        if (idmlFontFamily == null) return DEFAULT_HWPX_FONT;
        if (customMap != null) {
            String custom = customMap.get(idmlFontFamily);
            if (custom != null) return custom;
        }
        return mapToHwpxFont(idmlFontFamily);
    }

    /**
     * 하위 호환: IDML 폰트 → [hangul, latin] 쌍.
     */
    public static String[] mapToHwpxFontPair(String idmlFontFamily) {
        if (idmlFontFamily == null) return new String[]{DEFAULT_HWPX_FONT, DEFAULT_HWPX_FONT};
        String ko = staticCategoryFallback(idmlFontFamily);
        String en;
        if (isWesternFont(idmlFontFamily)) {
            en = staticLatinFallback(idmlFontFamily);
        } else {
            en = ko;
        }
        return new String[]{ko, en};
    }

    private static String staticLatinFallback(String fontFamily) {
        if (fontFamily == null) return DEFAULT_LATIN_SANS;
        String lower = fontFamily.toLowerCase();
        if (lower.contains("serif") || lower.contains("roman") || lower.contains("garamond")
                || lower.contains("minion") || lower.contains("times") || lower.contains("palatino")) {
            return DEFAULT_LATIN_SERIF;
        }
        return DEFAULT_LATIN_SANS;
    }

    public static String[] mapToHwpxFontPair(String idmlFontFamily, Map<String, String> customMap) {
        if (idmlFontFamily == null) return new String[]{DEFAULT_HWPX_FONT, DEFAULT_HWPX_FONT};
        if (customMap != null) {
            String custom = customMap.get(idmlFontFamily);
            if (custom != null) return new String[]{custom, custom};
        }
        return mapToHwpxFontPair(idmlFontFamily);
    }

    private static String staticCategoryFallback(String fontFamily) {
        if (fontFamily == null) return DEFAULT_HWPX_FONT;
        String lower = fontFamily.toLowerCase();

        // 키워드 기반 정밀 매핑 우선
        String keyword = keywordMapping(lower);
        if (keyword != null) return keyword;

        if (lower.contains("명조") || lower.contains("부리") || lower.contains("바탕")
                || lower.contains("serif") || lower.contains("roman") || lower.contains("garamond")
                || lower.contains("minion") || lower.contains("times") || lower.contains("palatino")
                || lower.contains("궁서")) {
            return DEFAULT_SERIF;
        }
        if (lower.contains("고딕") || lower.contains("돋움") || lower.contains("굴림")
                || lower.contains("sans") || lower.contains("gothic") || lower.contains("grotesque")
                || lower.contains("arial") || lower.contains("helvetica") || lower.contains("myriad")
                || lower.contains("rounded") || lower.contains("futura") || lower.contains("din")) {
            return DEFAULT_SANS;
        }

        return DEFAULT_SANS;
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

    // --- 내부 DTO ---

    public static class MappingResult {
        public final String koFont;
        public final String enFont;
        public final int spacingAdjustPercent;

        public MappingResult(String koFont, String enFont, int spacingAdjustPercent) {
            this.koFont = koFont;
            this.enFont = enFont;
            this.spacingAdjustPercent = spacingAdjustPercent;
        }
    }

    private static class MappingEntry {
        String ko;
        String en;
        int spacing;
    }

    static class HwpxMetric {
        double korWidth;
        double latWidth;
        int weight;
        double xHeight;
        double ascent;
        double descent;
        String category;
    }
}
