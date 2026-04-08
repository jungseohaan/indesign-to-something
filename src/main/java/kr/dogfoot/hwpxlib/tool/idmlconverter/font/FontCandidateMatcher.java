package kr.dogfoot.hwpxlib.tool.idmlconverter.font;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig.FontMappingEntry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig.FontMetricEntry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SPEC-014: IDML 폰트를 HWPX 후보 폰트와 매칭하여 점수가 높은 후보 목록을 만든다.
 *
 * <p>설계는 SPEC-014 §2 후보 매칭 알고리즘을 따른다. 가족명/스크립트/굵기/카테고리/메트릭
 * 유사도를 가중 합산하여 정규화 점수(0~1)를 부여한다. 사용자 매핑 이력은 외부 매핑 우선
 * 처리로 대체했다(이미 conversion-config.json에 매핑이 있으면 그 폰트를 100점 후보로 추가).</p>
 *
 * <p>글리프 폭 검증(SPEC-014 §3)도 수행한다. korWidth/latWidth가 원본 IDML 메트릭과 25% 이상
 * 차이나면 경고를 단다. IDML 메트릭이 없으면 검증 생략.</p>
 *
 * <p>이 클래스는 stateless이고, ConversionConfig + 사용자 IDML 폰트 정보를 받아 결과 DTO를
 * 반환한다. CLI/Tauri/UI는 결과를 JSON으로 직렬화해 사용한다.</p>
 */
public final class FontCandidateMatcher {

    /** 후보 점수가 이 값 미만이면 결과에 포함하지 않는다. */
    private static final double MIN_CANDIDATE_SCORE = 0.10;
    /** 결과로 돌려줄 최대 후보 수. */
    private static final int MAX_CANDIDATES = 5;
    /** 글리프 폭 차이 경고 임계값. */
    private static final double GLYPH_WIDTH_WARN_PCT = 0.10;
    private static final double GLYPH_WIDTH_SEVERE_PCT = 0.25;

    private FontCandidateMatcher() {}

    /**
     * IDML 원본 폰트 1개에 대한 매칭 분석.
     *
     * @param originalName 원본 폰트 패밀리명 (IDML fontFamily)
     * @param originalStyle 원본 fontStyleName (예: "Bold", "Light", null 가능)
     * @param idmlMetric 원본 폰트 메트릭 (없으면 null) — 글리프 폭 검증에 사용
     * @param config 변환 설정 (HWPX 메트릭 + 외부 매핑)
     * @return 분석 결과
     */
    public static FontAnalysis analyze(String originalName, String originalStyle,
                                        FontMetricEntry idmlMetric,
                                        ConversionConfig config) {
        FontAnalysis result = new FontAnalysis();
        result.originalName = originalName;
        result.originalStyle = originalStyle;
        result.candidates = new ArrayList<>();
        result.warnings = new ArrayList<>();

        if (originalName == null || originalName.isEmpty()) {
            result.currentMapping = null;
            return result;
        }

        // 외부 명시 매핑이 있으면 currentMapping에 표시
        FontMappingEntry external = config.fontMappings().get(originalName);
        if (external != null) {
            result.currentMapping = external.ko;
        }

        Map<String, FontMetricEntry> hwpxMetrics = config.hwpxFontMetrics();
        if (hwpxMetrics == null || hwpxMetrics.isEmpty()) {
            result.warnings.add("HWPX 폰트 메트릭이 비어 있어 후보를 추천할 수 없습니다.");
            return result;
        }

        boolean originalIsWestern = FontMapper.isWesternFont(originalName);
        String originalCategory = inferCategory(originalName);
        int originalWeight = inferWeight(originalName, originalStyle);

        for (Map.Entry<String, FontMetricEntry> e : hwpxMetrics.entrySet()) {
            String hwpxName = e.getKey();
            FontMetricEntry hwpxMetric = e.getValue();

            // 한글 슬롯이 의미 있는 폰트만 대상 (원본이 한글 폰트인 경우)
            if (!originalIsWestern && hwpxMetric.korWidth <= 0) continue;

            double score = scoreCandidate(originalName, originalCategory, originalWeight,
                    originalIsWestern, hwpxName, hwpxMetric);
            if (score < MIN_CANDIDATE_SCORE) continue;

            Candidate c = new Candidate();
            c.name = hwpxName;
            c.score = round3(score);
            c.reason = buildReason(originalName, originalCategory, hwpxName, hwpxMetric, originalIsWestern);
            result.candidates.add(c);
        }

        // 외부 매핑 폰트가 후보에 없으면 100점으로 추가 (사용자 의지 우선)
        if (external != null && external.ko != null) {
            boolean alreadyIn = false;
            for (Candidate c : result.candidates) {
                if (external.ko.equals(c.name)) { alreadyIn = true; c.score = 1.0; c.reason = "사용자 매핑 (현재)"; break; }
            }
            if (!alreadyIn) {
                Candidate c = new Candidate();
                c.name = external.ko;
                c.score = 1.0;
                c.reason = "사용자 매핑 (현재)";
                result.candidates.add(0, c);
            }
        }

        // 정렬: score 내림차순
        Collections.sort(result.candidates, (a, b) -> Double.compare(b.score, a.score));
        if (result.candidates.size() > MAX_CANDIDATES) {
            result.candidates = new ArrayList<>(result.candidates.subList(0, MAX_CANDIDATES));
        }

        // 글리프 폭 검증: 원본 metric이 있고 1순위 후보가 있으면 비교
        if (idmlMetric != null && !result.candidates.isEmpty()) {
            Candidate top = result.candidates.get(0);
            FontMetricEntry topMetric = hwpxMetrics.get(top.name);
            if (topMetric != null) {
                addGlyphWidthWarnings(result, idmlMetric, topMetric, originalIsWestern);
            }
        }

        return result;
    }

    /** IDML 폰트 목록 전체를 분석한다. */
    public static List<FontAnalysis> analyzeAll(List<String> idmlFontNames,
                                                  Map<String, FontMetricEntry> idmlMetrics,
                                                  ConversionConfig config) {
        List<FontAnalysis> out = new ArrayList<>();
        for (String name : idmlFontNames) {
            FontMetricEntry metric = (idmlMetrics != null) ? idmlMetrics.get(name) : null;
            out.add(analyze(name, null, metric, config));
        }
        return out;
    }

    // ── 매칭 알고리즘 ─────────────────────────────────────────

    private static double scoreCandidate(String origName, String origCategory, int origWeight,
                                          boolean origIsWestern,
                                          String hwpxName, FontMetricEntry hwpxMetric) {
        double score = 0.0;

        // 1. 가족명 정규화 일치 (정확/접두사)
        String origNorm = normalizeName(origName);
        String hwpxNorm = normalizeName(hwpxName);
        if (origNorm.equals(hwpxNorm)) {
            score += 1.0;
        } else if (origNorm.length() >= 3 && hwpxNorm.contains(origNorm)) {
            score += 0.6;
        } else if (hwpxNorm.length() >= 3 && origNorm.contains(hwpxNorm)) {
            score += 0.5;
        }

        // 2. 카테고리(serif/sans/handwriting/decorative) 일치
        String hwpxCategory = (hwpxMetric.category != null && !hwpxMetric.category.isEmpty())
                ? hwpxMetric.category.toLowerCase()
                : inferCategory(hwpxName);
        if (origCategory.equals(hwpxCategory)) {
            score += 0.4;
        } else if (isCompatibleCategory(origCategory, hwpxCategory)) {
            score += 0.15;
        }

        // 3. 굵기 인접 (±100 = 0.2, ±200 = 0.1)
        if (origWeight > 0 && hwpxMetric.weight > 0) {
            int diff = Math.abs(origWeight - hwpxMetric.weight);
            if (diff <= 100) score += 0.2;
            else if (diff <= 200) score += 0.1;
        }

        // 4. 스타일 키워드 일치 (고딕/명조/바탕/돋움/굴림 등)
        String hwpxLower = hwpxName.toLowerCase();
        String origLower = origName.toLowerCase();
        String[] keywords = {"고딕","명조","바탕","돋움","굴림","부리","gothic","sans","serif"};
        for (String kw : keywords) {
            if (origLower.contains(kw) && hwpxLower.contains(kw)) {
                score += 0.2;
                break;
            }
        }

        // 5. 한글/영문 스크립트 매치
        boolean hwpxIsWestern = FontMapper.isWesternFont(hwpxName);
        if (origIsWestern == hwpxIsWestern) {
            score += 0.2;
        }

        return score;
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        String s = name.toLowerCase();
        // 벤더/포맷 접두사 제거
        s = s.replaceAll("^(hu|adobe|sandoll|210|noto|nanum|함초롬)\\s*", "");
        // OTF/TTF/숫자 접미사 제거
        s = s.replaceAll("\\s*(otf|ttf|pro|std)\\s*$", "");
        s = s.replaceAll("\\d+$", "");
        // 공백/하이픈 제거
        s = s.replaceAll("[\\s\\-]+", "");
        return s.trim();
    }

    private static String inferCategory(String fontFamily) {
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
        if (lower.contains("graphic") || lower.contains("display")
                || lower.contains("headline") || lower.contains("헤드라인")) {
            return "decorative";
        }
        return "sans";
    }

    private static boolean isCompatibleCategory(String a, String b) {
        // serif↔sans, handwriting↔decorative은 약하게 호환
        if ("serif".equals(a) && "sans".equals(b)) return true;
        if ("sans".equals(a) && "serif".equals(b)) return true;
        if ("handwriting".equals(a) && "decorative".equals(b)) return true;
        if ("decorative".equals(a) && "handwriting".equals(b)) return true;
        return false;
    }

    private static int inferWeight(String name, String style) {
        if (name == null) return 0;
        String s = (name + " " + (style == null ? "" : style)).toLowerCase();
        if (s.contains("ultralight") || s.contains("thin")) return 100;
        if (s.contains("extralight")) return 200;
        if (s.contains("light")) return 300;
        if (s.contains("medium")) return 500;
        if (s.contains("semibold") || s.contains("demibold")) return 600;
        if (s.contains("extrabold") || s.contains("ultrabold") || s.contains("heavy")) return 800;
        if (s.contains("black")) return 900;
        if (s.contains("bold")) return 700;
        if (s.contains("regular") || s.contains("normal")) return 400;
        // 숫자 추출 (Sandoll 고딕Neo1 30 → 300)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{1,3})\\b").matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 10 && n <= 90) return n * 10;
            if (n >= 100 && n <= 900) return n;
        }
        return 0;
    }

    private static String buildReason(String origName, String origCategory,
                                       String hwpxName, FontMetricEntry hwpxMetric,
                                       boolean origIsWestern) {
        StringBuilder sb = new StringBuilder();
        String hwpxCategory = hwpxMetric.category != null ? hwpxMetric.category : inferCategory(hwpxName);
        if (origCategory.equals(hwpxCategory.toLowerCase())) {
            sb.append(origCategory);
        }
        boolean hwpxIsWestern = FontMapper.isWesternFont(hwpxName);
        if (origIsWestern == hwpxIsWestern) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(origIsWestern ? "라틴" : "한글");
        }
        if (sb.length() == 0) sb.append("이름 유사");
        return sb.toString();
    }

    // ── 글리프 폭 검증 ────────────────────────────────────────

    private static void addGlyphWidthWarnings(FontAnalysis result,
                                                FontMetricEntry idmlMetric, FontMetricEntry hwpxMetric,
                                                boolean originalIsWestern) {
        // 한글 폭 비교
        if (!originalIsWestern && idmlMetric.korWidth > 0 && hwpxMetric.korWidth > 0) {
            double diffPct = Math.abs(hwpxMetric.korWidth - idmlMetric.korWidth) / idmlMetric.korWidth;
            if (diffPct >= GLYPH_WIDTH_SEVERE_PCT) {
                result.warnings.add(String.format("한글 글리프 폭이 원본보다 %.0f%% 차이 — 줄바꿈/자간 큰 차이 가능",
                        diffPct * 100));
            } else if (diffPct >= GLYPH_WIDTH_WARN_PCT) {
                result.warnings.add(String.format("한글 글리프 폭이 원본보다 %.0f%% 차이", diffPct * 100));
            }
        }
        // 라틴 폭 비교
        if (idmlMetric.latWidth > 0 && hwpxMetric.latWidth > 0) {
            double diffPct = Math.abs(hwpxMetric.latWidth - idmlMetric.latWidth) / idmlMetric.latWidth;
            if (diffPct >= GLYPH_WIDTH_SEVERE_PCT) {
                result.warnings.add(String.format("라틴 글리프 폭이 원본보다 %.0f%% 차이 — 워드 간격 회귀 위험",
                        diffPct * 100));
            } else if (diffPct >= GLYPH_WIDTH_WARN_PCT) {
                result.warnings.add(String.format("라틴 글리프 폭이 원본보다 %.0f%% 차이", diffPct * 100));
            }
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ── DTO ──────────────────────────────────────────────────

    public static final class FontAnalysis {
        public String originalName;
        public String originalStyle;
        public String currentMapping;       // null if no external mapping
        public List<Candidate> candidates;
        public List<String> warnings;
    }

    public static final class Candidate {
        public String name;
        public double score;
        public String reason;
    }
}
