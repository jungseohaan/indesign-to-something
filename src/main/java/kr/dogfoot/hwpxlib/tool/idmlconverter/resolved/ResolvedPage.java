package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * resolved.json의 page 항목.
 * InDesign DOM에서 수집한 페이지 기하 정보와 마진.
 */
public class ResolvedPage {
    private int index;              // 문서 내 페이지 인덱스 (0-based)
    private String name;            // 페이지 이름 ("1", "2", ...)
    private double[] bounds;        // [top, left, bottom, right] (pasteboard 좌표, pt)
    private double marginTop;
    private double marginBottom;
    private double marginLeft;
    private double marginRight;

    public int index() { return index; }
    public void index(int v) { this.index = v; }

    public String name() { return name; }
    public void name(String v) { this.name = v; }

    public double[] bounds() { return bounds; }
    public void bounds(double[] v) { this.bounds = v; }

    public double marginTop() { return marginTop; }
    public void marginTop(double v) { this.marginTop = v; }

    public double marginBottom() { return marginBottom; }
    public void marginBottom(double v) { this.marginBottom = v; }

    public double marginLeft() { return marginLeft; }
    public void marginLeft(double v) { this.marginLeft = v; }

    public double marginRight() { return marginRight; }
    public void marginRight(double v) { this.marginRight = v; }

    /** 페이지 폭 (pt) */
    public double width() {
        return bounds != null ? bounds[3] - bounds[1] : 0;
    }

    /** 페이지 높이 (pt) */
    public double height() {
        return bounds != null ? bounds[2] - bounds[0] : 0;
    }
}
