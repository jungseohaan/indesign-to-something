package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * InDesign ExtendScript에서 렌더링된 텍스트 프레임/그룹 메타데이터.
 * resolved.json의 renderedTextFrames 배열에서 파싱된다.
 */
public class RenderedGroup {
    private int id;              // InDesign DOM ID (decimal)
    private String file;         // 상대 경로 (예: "group_renders/group_5941.jpg")
    private double[] bounds;     // [top, left, bottom, right] (points)
    private int pageIndex;       // 0-based 페이지 인덱스
    private double[] visibleExpansion;  // [widthRatio, heightRatio, offsetRatioX, offsetRatioY]
                                        // geometricBounds → visibleBounds 보정 비율

    public int id() { return id; }
    public void id(int v) { this.id = v; }

    public String file() { return file; }
    public void file(String v) { this.file = v; }

    public double[] bounds() { return bounds; }
    public void bounds(double[] v) { this.bounds = v; }

    public int pageIndex() { return pageIndex; }
    public void pageIndex(int v) { this.pageIndex = v; }

    public double[] visibleExpansion() { return visibleExpansion; }
    public void visibleExpansion(double[] v) { this.visibleExpansion = v; }
}
