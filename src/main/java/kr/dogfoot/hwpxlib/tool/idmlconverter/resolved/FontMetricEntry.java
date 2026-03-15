package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * InDesign 문서에서 측정한 폰트 메트릭 (resolved.json의 fontMetrics[] 항목).
 */
public class FontMetricEntry {
    private String family;
    private String style;
    private double korWidth;   // 한글 1자 평균 폭 (pt, 10pt 기준)
    private double latWidth;   // 영문 1자 평균 폭 (pt, 10pt 기준)
    private int weight;        // 100~900
    private double xHeight;    // 소문자 x 높이 (pt)
    private double ascent;     // 기준선 위 높이
    private double descent;    // 기준선 아래 깊이

    public String family() { return family; }
    public void family(String v) { this.family = v; }

    public String style() { return style; }
    public void style(String v) { this.style = v; }

    public double korWidth() { return korWidth; }
    public void korWidth(double v) { this.korWidth = v; }

    public double latWidth() { return latWidth; }
    public void latWidth(double v) { this.latWidth = v; }

    public int weight() { return weight; }
    public void weight(int v) { this.weight = v; }

    public double xHeight() { return xHeight; }
    public void xHeight(double v) { this.xHeight = v; }

    public double ascent() { return ascent; }
    public void ascent(double v) { this.ascent = v; }

    public double descent() { return descent; }
    public void descent(double v) { this.descent = v; }
}
