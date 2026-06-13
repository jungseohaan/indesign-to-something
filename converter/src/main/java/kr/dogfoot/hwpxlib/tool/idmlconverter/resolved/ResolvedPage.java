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

    /**
     * 아이템 geometricBounds → 페이지 상대 좌표 [x, y] 반환.
     *
     * InDesign DOM의 좌표계 차이를 처리:
     * - page.bounds는 항상 spread 좌표 (오른쪽 페이지: left=pageWidth)
     * - pageItem.geometricBounds는 rulerOrigin에 따라 page-relative일 수 있음
     *
     * 감지 방법: 오른쪽 페이지에서 아이템 left가 페이지 left보다 작으면
     * 아이템 좌표가 이미 페이지 상대이거나 spread를 가로지르는 page-local
     * geometry이다. 이 경우 offset을 적용하지 않는다.
     */
    public double[] toPageRelative(double[] gb) {
        if (bounds == null || gb == null) return null;
        boolean pageRelativeCoords = bounds[1] > 1.0 && gb[1] < bounds[1];
        double x = pageRelativeCoords ? gb[1] : (gb[1] - bounds[1]);
        double y = pageRelativeCoords ? gb[0] : (gb[0] - bounds[0]);
        return new double[]{x, y};
    }

    /**
     * rendered bounds(spread 좌표) → 페이지 상대 좌표 [x, y] 반환.
     *
     * renderedGraphicFrame/renderedTextFrame의 bounds는 항상 spread 좌표계를 사용:
     * - 왼쪽 페이지: x = 0 ~ pageWidth
     * - 오른쪽 페이지: x = -pageWidth ~ 0
     *
     * 오른쪽 페이지(bounds[1] > 0)에서 spread 좌표를 보정하여 페이지 상대 좌표로 변환.
     */
    public double[] spreadBoundsToPageRelative(double[] rb) {
        if (bounds == null || rb == null) return null;
        double pageWidth = bounds[3] - bounds[1];
        boolean isRightPage = bounds[1] > 1.0;
        double x = isRightPage ? (rb[1] + pageWidth) : rb[1];
        double y = rb[0] - bounds[0];
        return new double[]{x, y};
    }
}
