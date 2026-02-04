package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

/**
 * IDML TextFrame — 텍스트 프레임 (위치 + Story 참조).
 */
public class IDMLTextFrame {
    private String selfId;
    private String parentStoryId;
    private double[] geometricBounds;
    private double[] itemTransform;
    private String appliedObjectStyle;
    private String previousTextFrame;
    private String nextTextFrame;
    private int columnCount;
    private double columnGutter;      // 컬럼 간 간격 (points)
    private double[] insetSpacing;    // 내부 여백 [top, left, bottom, right] (points)
    private String fillColor;

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public String parentStoryId() { return parentStoryId; }
    public void parentStoryId(String v) { this.parentStoryId = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] itemTransform() { return itemTransform; }
    public void itemTransform(double[] v) { this.itemTransform = v; }

    public String appliedObjectStyle() { return appliedObjectStyle; }
    public void appliedObjectStyle(String v) { this.appliedObjectStyle = v; }

    public String previousTextFrame() { return previousTextFrame; }
    public void previousTextFrame(String v) { this.previousTextFrame = v; }

    public String nextTextFrame() { return nextTextFrame; }
    public void nextTextFrame(String v) { this.nextTextFrame = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public double columnGutter() { return columnGutter; }
    public void columnGutter(double v) { this.columnGutter = v; }

    public double[] insetSpacing() { return insetSpacing; }
    public void insetSpacing(double[] v) { this.insetSpacing = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    /**
     * 조판지시서 (편집 지시) 프레임인지 확인한다.
     */
    public boolean isEditorialNote() {
        return fillColor != null && fillColor.contains("조판지시서");
    }

    /**
     * 프레임 너비 (points).
     */
    public double widthPoints() {
        return geometricBounds != null ? IDMLGeometry.width(geometricBounds) : 0;
    }

    /**
     * 프레임 높이 (points).
     */
    public double heightPoints() {
        return geometricBounds != null ? IDMLGeometry.height(geometricBounds) : 0;
    }
}
