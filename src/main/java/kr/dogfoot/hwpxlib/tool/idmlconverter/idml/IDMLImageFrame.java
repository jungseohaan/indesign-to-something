package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

/**
 * IDML 이미지 프레임 (Rectangle + Image + Link).
 *
 * 이미지 클리핑 구조:
 * - 프레임(Rectangle): geometricBounds + itemTransform = 화면에 보이는 영역
 * - 이미지(Image): imageTransform + graphicBounds = 원본 이미지의 위치/스케일
 */
public class IDMLImageFrame {
    private String selfId;
    private double[] geometricBounds;    // 프레임의 bounds [top, left, bottom, right]
    private double[] itemTransform;      // 프레임의 transform [a, b, c, d, tx, ty]
    private int zOrder;                  // 파싱 순서 (렌더링 z-order)
    private String linkResourceURI;
    private String linkStoredState;
    private String linkResourceFormat;
    private String appliedObjectStyle;

    // 이미지 클리핑 정보
    private double[] imageTransform;     // 이미지의 transform (프레임 내 위치/스케일)
    private double[] graphicBounds;      // 원본 이미지 크기 [left, top, right, bottom]
    private boolean fromGroup;           // Group 내에서 추출된 요소 여부

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] itemTransform() { return itemTransform; }
    public void itemTransform(double[] v) { this.itemTransform = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public String linkResourceURI() { return linkResourceURI; }
    public void linkResourceURI(String v) { this.linkResourceURI = v; }

    public String linkStoredState() { return linkStoredState; }
    public void linkStoredState(String v) { this.linkStoredState = v; }

    public String linkResourceFormat() { return linkResourceFormat; }
    public void linkResourceFormat(String v) { this.linkResourceFormat = v; }

    public String appliedObjectStyle() { return appliedObjectStyle; }
    public void appliedObjectStyle(String v) { this.appliedObjectStyle = v; }

    public double[] imageTransform() { return imageTransform; }
    public void imageTransform(double[] v) { this.imageTransform = v; }

    public double[] graphicBounds() { return graphicBounds; }
    public void graphicBounds(double[] v) { this.graphicBounds = v; }

    public boolean fromGroup() { return fromGroup; }
    public void fromGroup(boolean v) { this.fromGroup = v; }

    public double widthPoints() {
        return geometricBounds != null ? IDMLGeometry.width(geometricBounds) : 0;
    }

    public double heightPoints() {
        return geometricBounds != null ? IDMLGeometry.height(geometricBounds) : 0;
    }

    public boolean isEmbedded() {
        return "Embedded".equals(linkStoredState);
    }
}
