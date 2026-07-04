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

    /** config에서 오버라이드된 기본 폰트 (인스턴스용) */
    private String configSerifKo = DEFAULT_SERIF;
    private String configSansKo = DEFAULT_SANS;
    private String configSerifEn = DEFAULT_LATIN_SERIF;
    private String configSansEn = DEFAULT_LATIN_SANS;

    // --- [1] 외부 JSON 매핑 (exact font mappings의 단일 소스) ---
    private Map<String, MappingEntry> externalMappings = new HashMap<String, MappingEntry>();

    // --- [2] 한/글 폰트 메트릭 (font-mapping.json의 hwpxFontMetrics) ---
    private Map<String, HwpxMetric> hwpxMetrics = new HashMap<String, HwpxMetric>();

    // --- InDesign 폰트 메트릭 (resolved.json에서 런타임 로드) ---
    private Map<String, FontMetricEntry> idmlMetrics = new HashMap<String, FontMetricEntry>();
    /** InDesign 좌표 → pt 변환 스케일 팩터 (mm→pt = 2.8346) */
    private double scaleFactor = 2.8346;

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
                        me.scaleAdjust = val.has("scaleAdjust") ? val.get("scaleAdjust").getAsInt() : 0;
                        me.ratio = val.has("ratio") ? val.get("ratio").getAsDouble() : 1.0;
                        me.forceNormalStyle = val.has("forceNormalStyle") && val.get("forceNormalStyle").getAsBoolean();
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

                // indesignFontMetrics 섹션
                if (root.has("indesignFontMetrics")) {
                    JsonObject metrics = root.getAsJsonObject("indesignFontMetrics");
                    for (Map.Entry<String, JsonElement> entry : metrics.entrySet()) {
                        if (entry.getKey().startsWith("_")) continue;
                        JsonObject val = entry.getValue().getAsJsonObject();
                        FontMetricEntry fm = new FontMetricEntry();
                        fm.family(entry.getKey());
                        fm.korWidth(val.has("korWidth") ? val.get("korWidth").getAsDouble() / scaleFactor : 0);
                        fm.latWidth(val.has("latWidth") ? val.get("latWidth").getAsDouble() / scaleFactor : 0);
                        fm.ascent(val.has("ascent") ? val.get("ascent").getAsDouble() : 0);
                        fm.descent(val.has("descent") ? val.get("descent").getAsDouble() : 0);
                        if (!idmlMetrics.containsKey(entry.getKey())) {
                            idmlMetrics.put(entry.getKey(), fm);
                        }
                    }
                }

                System.out.println("[FontMapper] font-mapping.json 로드: mappings=" + externalMappings.size()
                        + ", hwpxFontMetrics=" + hwpxMetrics.size()
                        + ", indesignFontMetrics=" + idmlMetrics.size());
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            System.err.println("[FontMapper] font-mapping.json 로드 실패: " + e.getMessage());
        }
    }

    /**
     * DSL fontDefaults로 기본 폰트를 초기화한다 (ConversionRules.kt 값 적용).
     * Exact font mappings are loaded only from font-mapping.json.
     * DSL/config values here are limited to defaults and keyword fallback behavior.
     */
    public void loadFromConfig(kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig config) {
        kr.dogfoot.hwpxlib.tool.idmlconverter.rule.FontDefaultsBuilder dslDefaults =
                kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry.getFontDefaults();
        configSerifKo = dslDefaults.getDefaultSerifKo();
        configSansKo  = dslDefaults.getDefaultSansKo();
        configSerifEn = dslDefaults.getDefaultSerifEn();
        configSansEn  = dslDefaults.getDefaultSansEn();
        System.out.println("[FontMapper] 기본 폰트: serif=" + configSerifKo + ", sans=" + configSansKo);
    }

    /**
     * resolved.json에서 로드한 InDesign 폰트 메트릭을 설정한다.
     */
    public void setIdmlMetrics(List<FontMetricEntry> metrics) {
        setIdmlMetrics(metrics, 2.8346);
    }

    /**
     * resolved.json fontMetrics 에 등록된 (실제 InDesign 이 렌더에 사용한) 폰트의 weight 를 반환한다.
     * 없으면 -1.
     * (TT) / (TTF) 등 InDesign 표시용 접미사는 제거하여 매칭한다.
     */
    public int resolvedWeightFor(String idmlFontFamily) {
        if (idmlFontFamily == null) return -1;
        FontMetricEntry m = idmlMetrics.get(idmlFontFamily);
        if (m == null) {
            // (TT) / (TT1) / (TTC) 등 접미사 제거 후 재시도
            String stripped = idmlFontFamily.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            if (!stripped.equals(idmlFontFamily)) {
                m = idmlMetrics.get(stripped);
            }
        }
        return m != null ? m.weight() : -1;
    }

    public void setIdmlMetrics(List<FontMetricEntry> metrics, double scale) {
        if (metrics == null) return;
        this.scaleFactor = scale;
        for (FontMetricEntry m : metrics) {
            if (m.family() != null) {
                idmlMetrics.put(m.family(), m);
            }
        }
        System.out.println("[FontMapper] InDesign 폰트 메트릭 로드: " + idmlMetrics.size() + "개 (scale=" + scale + ")");
    }

    /**
     * 3계층 매핑: IDML 폰트 → HWPX [ko, en, spacingAdjust%]
     */
    public MappingResult mapFont(String idmlFontFamily) {
        return mapFont(idmlFontFamily, null);
    }

    /**
     * 3계층 매핑: IDML 폰트 → HWPX [ko, en, spacingAdjust%]
     * @param fontStyle 가변폰트의 실제 웨이트 힌트 (예: "20", "Bold")
     */
    public MappingResult mapFont(String idmlFontFamily, String fontStyle) {
        if (idmlFontFamily == null) return new MappingResult(DEFAULT_HWPX_FONT, DEFAULT_HWPX_FONT, 0);

        String cacheKey = fontStyle != null ? idmlFontFamily + "/" + fontStyle : idmlFontFamily;
        MappingResult cached = cache.get(cacheKey);
        if (cached != null) return cached;

        MappingResult result;

        // [1] 외부 JSON 명시적 매핑: exact font mappings의 단일 소스
        MappingEntry ext = externalMappings.get(idmlFontFamily);
        if (ext != null) {
            result = new MappingResult(ext.ko, ext.en, ext.spacing, ext.scaleAdjust, ext.ratio, ext.forceNormalStyle);
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → \"" + ext.ko + "\" (JSON명시)" + (ext.ratio != 1.0 ? " 장평=" + ext.ratio : ""));
        }
        // [2] 카테고리/키워드 폴백 (이름 기반)
        // 메트릭 매칭은 비활성 — hwpxFontMetrics 카테고리 정확도 개선 후 재활성화
        else {
            result = categoryFallback(idmlFontFamily, fontStyle);
        }

        cache.put(cacheKey, result);
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

        // 폭 보정: InDesign korWidth(mm) → pt 변환
        // 대체 폰트 값(3.53mm) 감지: 대부분 폰트가 동일 값이면 대체 폰트로 간주하여 무시
        // resolved spacing policy: spacing 과 fontRatio 를 동시에 적용하면 중복 보정으로 자간 과대 → fontRatio (장평) 만 사용.
        // 장평은 글리프를 가로로 늘려 폭을 맞추므로 가시적 자간 공백이 생기지 않음.
        HwpxMetric hwpxInfo = hwpxMetrics.get(bestKoFont);
        int spacing = 0;
        double idmlKorWidthPt = idmlInfo.korWidth() * scaleFactor;
        boolean isReliableMetric = isReliableIdmlMetric(idmlInfo.korWidth());

        // 장평 비율: InDesign 한글폭(mm→pt) / HWPX 한글폭(pt)
        double fontRatio = 1.0;
        if (isReliableMetric && hwpxInfo != null && hwpxInfo.korWidth > 0 && idmlKorWidthPt > 0) {
            fontRatio = idmlKorWidthPt / hwpxInfo.korWidth;
            if (fontRatio > 0.95 && fontRatio < 1.05) fontRatio = 1.0; // 5% 이내면 무시
        }

        System.out.printf("[FontMap] \"%s\" → ko=\"%s\"(%.3f) en=\"%s\"(%.3f) 자간=%+d%% 장평=%.3f\n",
                idmlFont, bestKoFont, bestKoScore, bestEnFont, bestEnScore, spacing, fontRatio);

        return new MappingResult(bestKoFont, bestEnFont, spacing, 0, fontRatio);
    }

    /**
     * InDesign fontMetric의 korWidth가 신뢰할 수 있는 값인지 판별한다.
     * 대부분 폰트가 동일 korWidth를 보고하면 대체 폰트(fallback)로 측정된 것이므로 신뢰 불가.
     */
    private boolean isReliableIdmlMetric(double korWidth) {
        if (korWidth <= 0) return false;
        // 같은 korWidth를 가진 폰트가 전체의 50% 이상이면 대체 폰트 값으로 간주
        int sameCount = 0;
        int total = 0;
        for (FontMetricEntry m : idmlMetrics.values()) {
            if (m.korWidth() > 0) {
                total++;
                if (Math.abs(m.korWidth() - korWidth) < 0.01) sameCount++;
            }
        }
        return total < 3 || sameCount < total / 2;
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

        // 한글 슬롯: 카테고리 필터 + 메트릭 매칭
        // 카테고리 불일치 시 후보에서 제외 (명조↔고딕 교차 매칭 방지)
        if (!idmlCategory.equals(hwpx.category)
                && !"unknown".equals(idmlCategory) && !"unknown".equals(hwpx.category)) {
            return -1.0; // 카테고리 불일치 → 후보 제외
        }

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

        return korSim * 0.45 + latSim * 0.20 + wSim * 0.20 + adSim * 0.10 + xSim * 0.05;
    }

    /** 영문 카테고리 폴백: serif → Times New Roman, sans → Arial */
    private static final String DEFAULT_LATIN_SERIF = "Times New Roman";
    private static final String DEFAULT_LATIN_SANS = "Arial";

    /**
     * 카테고리 폴백 (메트릭 없을 때).
     * 1차: 폰트명 키워드로 한/글 번들 폰트에 직접 매핑
     * 2차: 대분류(세리프/산세리프)로 기본 폰트 매핑
     */
    private MappingResult categoryFallback(String idmlFontFamily, String fontStyle) {
        String lower = idmlFontFamily.toLowerCase();
        boolean isWestern = isWesternFont(idmlFontFamily);

        // --- 1차: 키워드 기반 정밀 매핑 (한/글 번들 폰트명과 직접 연결) ---
        String keywordMatch = keywordMapping(lower, fontStyle);
        if (keywordMatch != null) {
            // DEFAULT_SERIF/DEFAULT_SANS → config 기본 폰트로 교체
            String ko = keywordMatch;
            boolean isSerif = DEFAULT_SERIF.equals(ko);
            if (isSerif) ko = configSerifKo;
            else if (DEFAULT_SANS.equals(ko)) ko = configSansKo;
            // 영문 폰트: 세리프 → Times New Roman, 산세리프 → Arial, 서양 폰트 → 산세리프
            String en;
            if (isWestern) en = DEFAULT_LATIN_SANS;
            else if (isSerif) en = configSerifEn;
            else en = ko;
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" (style=" + fontStyle + ") → ko=\"" + ko + "\" en=\"" + en + "\" (키워드매핑)");
            return new MappingResult(ko, en, 0);
        }

        // --- 2차: 대분류 폴백 (config 기본 폰트 사용) ---

        // 세리프 계열
        if (lower.contains("명조") || lower.contains("부리") || lower.contains("바탕")
                || lower.contains("serif") || lower.contains("roman") || lower.contains("garamond")
                || lower.contains("minion") || lower.contains("times") || lower.contains("palatino")
                || lower.contains("궁서")) {
            String ko = configSerifKo;
            String en = isWestern ? configSerifEn : ko;
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + ko + "\" en=\"" + en + "\" (카테고리폴백: serif)");
            return new MappingResult(ko, en, 0);
        }

        // 산세리프 계열
        if (lower.contains("고딕") || lower.contains("돋움") || lower.contains("굴림")
                || lower.contains("sans") || lower.contains("gothic") || lower.contains("grotesque")
                || lower.contains("arial") || lower.contains("helvetica") || lower.contains("myriad")
                || lower.contains("rounded") || lower.contains("futura") || lower.contains("din")
                || lower.contains("neo") || lower.contains("square") || lower.contains("스퀘어")) {
            String ko = configSansKo;
            String en = isWestern ? configSansEn : ko;
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + ko + "\" en=\"" + en + "\" (카테고리폴백: sans)");
            return new MappingResult(ko, en, 0);
        }

        // 손글씨/필기 계열 → 한컴돋움
        if (lower.contains("필기") || lower.contains("hand") || lower.contains("펜")
                || lower.contains("쓰다") || lower.contains("script") || lower.contains("brush")
                || lower.contains("착한아이") || lower.contains("일기") || lower.contains("캘리")) {
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + configSansKo + "\" (카테고리폴백: handwriting)");
            return new MappingResult(configSansKo, configSansKo, 0);
        }

        // 210 계열 장식 폰트 → 한컴돋움
        if (lower.startsWith("210 ") || lower.startsWith("210")) {
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + configSansKo + "\" (카테고리폴백: 210-decorative)");
            return new MappingResult(configSansKo, configSansKo, 0);
        }

        // HU/Rix/SD 계열 → 한컴돋움 (장평 90% 보정: 장식 폰트는 한컴돋움보다 폭이 좁음)
        if (lower.startsWith("hu") || lower.startsWith("rix") || lower.startsWith("sd ")
                || lower.contains("상상토끼") || lower.contains("둘기마요")) {
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + configSansKo + "\" (카테고리폴백: korean-decorative, ratio=0.9)");
            return new MappingResult(configSansKo, configSansKo, 0, 0, 0.9);
        }

        // 서양 폰트 기본 → 산세리프
        if (isWestern) {
            System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + configSansKo + "\" en=\"" + configSansEn + "\" (카테고리폴백: western-default)");
            return new MappingResult(configSansKo, configSansEn, 0);
        }

        // 기본
        System.out.println("[FontMap] \"" + idmlFontFamily + "\" → ko=\"" + configSansKo + "\" en=\"" + configSansKo + "\" (카테고리폴백: default)");
        return new MappingResult(configSansKo, configSansKo, 0);
    }

    /**
     * 폰트명 키워드로 한/글 번들 폰트에 직접 매핑.
     * ConversionRules.kt의 keywordFontRule() 등록 순서 우선.
     * @return DEFAULT_SERIF/DEFAULT_SANS 센티널 또는 정확한 폰트명, 없으면 null
     */
    private static String keywordMapping(String lowerName, String fontStyle) {
        kr.dogfoot.hwpxlib.tool.idmlconverter.rule.KeywordFontRuleBuilder rule =
                kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry.applyKeywordFontRule(lowerName);
        if (rule == null) return null;
        if (rule.getKoFont() != null) return rule.getKoFont();
        return rule.isSerif() ? DEFAULT_SERIF : DEFAULT_SANS;
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
        String keyword = keywordMapping(lower, null);
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
        /** horizontalScale 보정값 (예: 5 → IDML 95% → HWPX 100%) */
        public final int scaleAdjust;
        /** 장평 비율: 원본 폰트 대비 HWPX 폰트의 폭 비율. 1.0 미만이면 글자 폭 축소 필요 (예: 0.82 → 82%) */
        public final double ratio;
        /** true이면 원본 Bold/Italic style을 HWPX CharPr에 반영하지 않는다. */
        public final boolean forceNormalStyle;

        public MappingResult(String koFont, String enFont, int spacingAdjustPercent) {
            this(koFont, enFont, spacingAdjustPercent, 0, 1.0);
        }

        public MappingResult(String koFont, String enFont, int spacingAdjustPercent, int scaleAdjust) {
            this(koFont, enFont, spacingAdjustPercent, scaleAdjust, 1.0);
        }

        public MappingResult(String koFont, String enFont, int spacingAdjustPercent, int scaleAdjust, double ratio) {
            this(koFont, enFont, spacingAdjustPercent, scaleAdjust, ratio, false);
        }

        public MappingResult(String koFont, String enFont, int spacingAdjustPercent, int scaleAdjust, double ratio, boolean forceNormalStyle) {
            this.koFont = koFont;
            this.enFont = enFont;
            this.spacingAdjustPercent = spacingAdjustPercent;
            this.scaleAdjust = scaleAdjust;
            this.ratio = ratio;
            this.forceNormalStyle = forceNormalStyle;
        }
    }

    private static class MappingEntry {
        String ko;
        String en;
        int spacing;
        int scaleAdjust;
        double ratio;
        boolean forceNormalStyle;
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
