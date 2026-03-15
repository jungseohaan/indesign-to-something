package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * 폰트 글리프 다차원 메트릭 분석 도구.
 * 한/글 번들 TTF를 직접 로드하여 측정하고, font-mapping.json의 hwpxFontMetrics 섹션을 생성한다.
 *
 * 측정 항목: 한글폭, 영문폭, weight, x-height, ascent, descent, 카테고리
 *
 * 사용법:
 *   java FontMetricsAnalyzer [--hwp-fonts-dir <path>] [--json]
 */
public class FontMetricsAnalyzer {

    // 한글 대표 문자열 (다양한 자소 조합)
    private static final String KOREAN_SAMPLE =
            "가나다라마바사아자차카타파하" +
            "고노도로모보소오조초코토포호" +
            "구누두루무부수우주추쿠투푸후" +
            "기니디리미비시이지치키티피히" +
            "게네데레메베세에제체케테페헤" +
            "갈날달랄말발살알잘찰칼탈팔할" +
            "강낭당랑망방상앙장창캉탕팡항" +
            "한국어는 아름다운 언어입니다";

    // 영문 대표 문자열
    private static final String LATIN_SAMPLE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
            "0123456789" +
            "The quick brown fox jumps over the lazy dog.";

    /**
     * TTF 파일에서 폰트를 직접 로드하여 메트릭을 측정한다.
     */
    public static FontMetricInfo measureFromTTF(File ttfFile, float fontSize) {
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, ttfFile);
            font = font.deriveFont(fontSize);

            String family = font.getFamily();
            String name = font.getFontName();

            // 한글 지원 여부 확인
            boolean supportsKorean = font.canDisplay('가') && font.canDisplay('한');

            FontRenderContext frc = new FontRenderContext(null, true, true);

            // 1) 폭 측정
            double korWidth = 0;
            if (supportsKorean) {
                korWidth = measureAdvanceWidth(font, frc, KOREAN_SAMPLE) / KOREAN_SAMPLE.length();
            }
            double latWidth = measureAdvanceWidth(font, frc, LATIN_SAMPLE) / LATIN_SAMPLE.length();

            // 2) Weight — 폰트명에서 키워드 추출
            int weight = inferWeight(name, ttfFile.getName());

            // 3) Ascent / Descent
            java.awt.FontMetrics fm = new Canvas().getFontMetrics(font);
            double ascent = fm.getAscent();
            double descent = fm.getDescent();

            // 4) x-height — 소문자 'x' 높이
            double xHeight = 0;
            if (font.canDisplay('x')) {
                TextLayout xLayout = new TextLayout("x", font, frc);
                xHeight = xLayout.getBounds().getHeight();
            }

            // 5) 카테고리 추론
            String category = inferCategory(family, name, ttfFile.getName());

            return new FontMetricInfo(
                    family, name, ttfFile.getName(),
                    supportsKorean, fontSize,
                    korWidth, latWidth, weight,
                    xHeight, ascent, descent, category
            );
        } catch (Exception e) {
            System.err.println("  [SKIP] " + ttfFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static double measureAdvanceWidth(Font font, FontRenderContext frc, String text) {
        if (text.isEmpty()) return 0;
        try {
            TextLayout layout = new TextLayout(text, font, frc);
            return layout.getAdvance();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 폰트명/파일명에서 weight를 추론한다.
     */
    static int inferWeight(String fontName, String fileName) {
        String combined = (fontName + " " + fileName).toLowerCase();

        if (combined.contains("thin") || combined.contains("hairline")) return 100;
        if (combined.contains("ultralight") || combined.contains("extralight")) return 200;
        if (combined.contains("light") || combined.endsWith("l.ttf") || combined.endsWith("l.ttf")) return 300;
        if (combined.contains("black") || combined.contains("heavy") || combined.contains("extrabold")) return 900;
        if (combined.contains("bold") || combined.endsWith("b.ttf") || combined.contains("_b.")) return 700;
        if (combined.contains("semibold") || combined.contains("demibold")) return 600;
        if (combined.contains("medium") || combined.endsWith("m.ttf") || combined.contains("_m.")) return 500;
        // Regular/Normal/Book
        return 400;
    }

    /**
     * 폰트명/파일명에서 카테고리를 추론한다.
     */
    static String inferCategory(String family, String fontName, String fileName) {
        String combined = (family + " " + fontName + " " + fileName).toLowerCase();

        // 명조/바탕/세리프 계열
        if (combined.contains("명조") || combined.contains("batang") || combined.contains("바탕")
                || combined.contains("serif") || combined.contains("myeongjo") || combined.contains("mincho")
                || combined.contains("궁서") || combined.contains("gungsuh") || combined.contains("times")
                || combined.contains("roman") || combined.contains("hmkm") || combined.contains("hymj")) {
            return "serif";
        }

        // 손글씨/캘리그래피 계열
        if (combined.contains("필기") || combined.contains("손글씨") || combined.contains("캘리")
                || combined.contains("handwrit") || combined.contains("script")
                || combined.contains("휴먼팸") || combined.contains("휴먼샘")
                || combined.contains("양재") || combined.contains("along")
                || combined.contains("ybla") || combined.contains("yblo") || combined.contains("ymae")
                || combined.contains("ygbi") || combined.contains("ychm") || combined.contains("ydoo")
                || combined.contains("md") && (combined.contains("art") || combined.contains("sol"))) {
            return "handwriting";
        }

        // 장식/디스플레이 계열
        if (combined.contains("graphic") || combined.contains("그래픽")
                || combined.contains("headline") || combined.contains("헤드라인")
                || combined.contains("백제") || combined.contains("소망") || combined.contains("솔잎")
                || combined.contains("바겐세일") || combined.contains("쿨재즈")
                || combined.contains("윤체") || combined.contains("yhead")
                || combined.contains("display") || combined.contains("decorative")
                || combined.contains("한산뜻") || combined.contains("santteu")) {
            return "decorative";
        }

        // 고딕/돋움/산세리프 계열
        if (combined.contains("고딕") || combined.contains("gothic") || combined.contains("돋움")
                || combined.contains("dotum") || combined.contains("굴림") || combined.contains("gulim")
                || combined.contains("sans") || combined.contains("arial") || combined.contains("verdana")
                || combined.contains("tahoma") || combined.contains("calibri") || combined.contains("malgun")
                || combined.contains("ygo") || combined.contains("견고딕")
                || combined.contains("hdotum") || combined.contains("handotum")) {
            return "sans";
        }

        return "sans"; // 기본값
    }

    /**
     * 두 폰트 간 다차원 유사도를 계산한다 (0.0 ~ 1.0).
     */
    public static double similarity(FontMetricInfo a, FontMetricInfo b) {
        // 한글 폭 (40%)
        double korSim = (a.korWidth > 0 && b.korWidth > 0)
                ? 1.0 - Math.abs(a.korWidth - b.korWidth) / Math.max(a.korWidth, b.korWidth)
                : 0.5;
        // 영문 폭 (20%)
        double latSim = (a.latWidth > 0 && b.latWidth > 0)
                ? 1.0 - Math.abs(a.latWidth - b.latWidth) / Math.max(a.latWidth, b.latWidth)
                : 0.5;
        // Weight (15%)
        double wSim = 1.0 - Math.abs(a.weight - b.weight) / 900.0;
        // Ascent/Descent 비율 (10%)
        double adSim = 0.5;
        if (a.ascent > 0 && a.descent > 0 && b.ascent > 0 && b.descent > 0) {
            double adRatioA = a.ascent / (a.ascent + a.descent);
            double adRatioB = b.ascent / (b.ascent + b.descent);
            adSim = 1.0 - Math.abs(adRatioA - adRatioB);
        }
        // x-height (5%)
        double xSim = (a.xHeight > 0 && b.xHeight > 0)
                ? 1.0 - Math.abs(a.xHeight - b.xHeight) / Math.max(a.xHeight, b.xHeight)
                : 0.5;
        // 카테고리 일치 (10%)
        double catBonus = a.category.equals(b.category) ? 1.0 : 0.0;

        return korSim * 0.40 + latSim * 0.20 + wSim * 0.15 + adSim * 0.10 + xSim * 0.05 + catBonus * 0.10;
    }

    /**
     * 폰트 메트릭 정보 DTO.
     */
    public static class FontMetricInfo {
        public final String family;
        public final String fontName;
        public final String fileName;
        public final boolean supportsKorean;
        public final float fontSize;
        public final double korWidth;    // 한글 1자 평균 폭 (pt)
        public final double latWidth;    // 영문 1자 평균 폭 (pt)
        public final int weight;         // 100~900
        public final double xHeight;     // 소문자 x 높이 (pt)
        public final double ascent;      // 기준선 위 높이
        public final double descent;     // 기준선 아래 깊이
        public final String category;    // serif|sans|decorative|handwriting

        public FontMetricInfo(String family, String fontName, String fileName,
                              boolean supportsKorean, float fontSize,
                              double korWidth, double latWidth, int weight,
                              double xHeight, double ascent, double descent, String category) {
            this.family = family;
            this.fontName = fontName;
            this.fileName = fileName;
            this.supportsKorean = supportsKorean;
            this.fontSize = fontSize;
            this.korWidth = korWidth;
            this.latWidth = latWidth;
            this.weight = weight;
            this.xHeight = xHeight;
            this.ascent = ascent;
            this.descent = descent;
            this.category = category;
        }

        @Override
        public String toString() {
            return String.format("%-28s | %-28s | %-12s | ko=%5.2f lat=%5.2f | wt=%3d xh=%4.2f | a=%4.1f d=%4.1f | %s | %s",
                    family, fontName, fileName,
                    korWidth, latWidth,
                    weight, xHeight,
                    ascent, descent,
                    category, supportsKorean ? "KO" : "--");
        }

        /**
         * font-mapping.json의 hwpxFontMetrics 항목용 JSON 문자열을 반환한다.
         */
        public String toJsonEntry() {
            return String.format(
                    "    \"%s\": { \"korWidth\": %.2f, \"latWidth\": %.2f, \"weight\": %d, \"xHeight\": %.2f, \"ascent\": %.1f, \"descent\": %.1f, \"category\": \"%s\" }",
                    family, korWidth, latWidth, weight, xHeight, ascent, descent, category);
        }
    }

    /**
     * 디렉토리에서 모든 TTF 파일을 재귀 탐색하여 메트릭을 측정한다.
     */
    public static List<FontMetricInfo> measureDirectory(File dir, float fontSize) {
        List<FontMetricInfo> results = new ArrayList<FontMetricInfo>();
        if (!dir.exists() || !dir.isDirectory()) return results;

        File[] files = dir.listFiles();
        if (files == null) return results;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().equals("notice")) {
                    results.addAll(measureDirectory(f, fontSize));
                }
            } else if (f.getName().toLowerCase().endsWith(".ttf")) {
                FontMetricInfo info = measureFromTTF(f, fontSize);
                if (info != null) {
                    results.add(info);
                }
            }
        }
        return results;
    }

    /**
     * CLI 엔트리포인트: 한/글 번들 TTF 측정 및 font-mapping.json 생성.
     *
     * 옵션:
     *   --hwp-fonts-dir <path>  한/글 TTF 경로 (기본: /Applications/Hancom Office HWP.app/.../TTF/)
     *   --json                  font-mapping.json용 JSON 출력
     */
    public static void main(String[] args) {
        String hwpFontsDir = "/Applications/Hancom Office HWP.app/Contents/Resources/Hnc/Shared/TTF/";
        boolean jsonOutput = false;

        for (int i = 0; i < args.length; i++) {
            if ("--hwp-fonts-dir".equals(args[i]) && i + 1 < args.length) {
                hwpFontsDir = args[++i];
            } else if ("--json".equals(args[i])) {
                jsonOutput = true;
            }
        }

        float testSize = 10f;
        System.err.println("=== 한/글 번들 폰트 메트릭 측정 ===");
        System.err.println("경로: " + hwpFontsDir);
        System.err.println("기준: " + testSize + "pt\n");

        List<FontMetricInfo> allMetrics = measureDirectory(new File(hwpFontsDir), testSize);

        // 한글 지원 폰트만 필터 (영문 전용 제외)
        List<FontMetricInfo> koreanFonts = new ArrayList<FontMetricInfo>();
        List<FontMetricInfo> latinOnlyFonts = new ArrayList<FontMetricInfo>();
        // 중복 제거 (family 기준, 첫 번째만)
        Set<String> seenFamilies = new HashSet<String>();

        for (FontMetricInfo m : allMetrics) {
            if (seenFamilies.contains(m.family)) continue;
            seenFamilies.add(m.family);
            if (m.supportsKorean) {
                koreanFonts.add(m);
            } else {
                latinOnlyFonts.add(m);
            }
        }

        if (jsonOutput) {
            // font-mapping.json의 hwpxFontMetrics 섹션 출력
            System.out.println("  \"hwpxFontMetrics\": {");
            System.out.println("    \"_comment\": \"한/글 폰트 메트릭 (10pt 기준, FontMetricsAnalyzer로 측정)\",");

            List<FontMetricInfo> combined = new ArrayList<FontMetricInfo>();
            combined.addAll(koreanFonts);
            combined.addAll(latinOnlyFonts);

            for (int i = 0; i < combined.size(); i++) {
                FontMetricInfo m = combined.get(i);
                System.out.print(m.toJsonEntry());
                if (i < combined.size() - 1) System.out.println(",");
                else System.out.println();
            }
            System.out.println("  }");
        } else {
            // 표 형식 출력
            System.out.println("=== 한글 지원 폰트 (" + koreanFonts.size() + "개) ===\n");
            System.out.printf("%-28s | %-28s | %-12s | %-11s | %-12s | %-9s | %s\n",
                    "Family", "Font Name", "File", "kor    lat", "wt   xh", "a     d", "cat");
            System.out.println("─".repeat(130));
            for (FontMetricInfo m : koreanFonts) {
                System.out.println(m);
            }

            System.out.println("\n=== 영문 전용 폰트 (" + latinOnlyFonts.size() + "개) ===\n");
            for (FontMetricInfo m : latinOnlyFonts) {
                System.out.println(m);
            }
        }

        System.err.println("\n총 " + allMetrics.size() + "개 TTF 측정 완료 (한글 " + koreanFonts.size() + "개, 영문 " + latinOnlyFonts.size() + "개)");
    }
}
