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

    // Stroke/Outline properties
    private String strokeColor;
    private double strokeWeight;
    private double cornerRadius;
    private double[] cornerRadii;    // [topLeft, topRight, bottomLeft, bottomRight]
    private double fillTint = 100;   // 0~100, 100=opaque
    private double strokeTint = 100;
    private String strokeType = "Solid"; // Solid, Dashed, Dotted

    // 컬럼 상세 속성
    private String columnType = "FixedNumber";  // FixedNumber, FixedWidth, FlexibleWidth
    private double columnFixedWidth;            // 고정 너비 (points)
    private double[] columnWidths;              // 가변 너비 배열 (points)

    // 수직 정렬
    private String verticalJustification = "TopAlign";  // TopAlign, CenterAlign, BottomAlign, JustifyAlign

    // 텍스트 감싸기 무시
    private boolean ignoreWrap;

    // 앵커 위치 (AnchoredObjectSetting)
    private String anchoredPosition;  // InlinePosition, AboveLinePosition, Anchored

    // 그룹 소속 정보
    private String parentGroupId;

    // 단 경계선 (Column Rule)
    private boolean useColumnRule;              // 경계선 삽입 여부
    private double columnRuleWidth;             // 획 두께 (points)
    private String columnRuleType = "Solid";    // 획 유형 (Solid, Dashed, etc.)
    private String columnRuleColor;             // 획 색상
    private double columnRuleTint = 100;        // 획 색조
    private double columnRuleOffset;            // 가로 위치 (gutter 중심 기준 오프셋)
    private double columnRuleInsetWidth;        // 경계선 길이 (0=전체)

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

    public String strokeColor() { return strokeColor; }
    public void strokeColor(String v) { this.strokeColor = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public double[] cornerRadii() { return cornerRadii; }
    public void cornerRadii(double[] v) { this.cornerRadii = v; }

    public double fillTint() { return fillTint; }
    public void fillTint(double v) { this.fillTint = v; }

    public double strokeTint() { return strokeTint; }
    public void strokeTint(double v) { this.strokeTint = v; }

    public String strokeType() { return strokeType; }
    public void strokeType(String v) { this.strokeType = v; }

    public String columnType() { return columnType; }
    public void columnType(String v) { this.columnType = v; }

    public double columnFixedWidth() { return columnFixedWidth; }
    public void columnFixedWidth(double v) { this.columnFixedWidth = v; }

    public double[] columnWidths() { return columnWidths; }
    public void columnWidths(double[] v) { this.columnWidths = v; }

    public String verticalJustification() { return verticalJustification; }
    public void verticalJustification(String v) { this.verticalJustification = v; }

    public boolean ignoreWrap() { return ignoreWrap; }
    public void ignoreWrap(boolean v) { this.ignoreWrap = v; }

    public String anchoredPosition() { return anchoredPosition; }
    public void anchoredPosition(String v) { this.anchoredPosition = v; }

    public String parentGroupId() { return parentGroupId; }
    public void parentGroupId(String v) { this.parentGroupId = v; }

    public boolean useColumnRule() { return useColumnRule; }
    public void useColumnRule(boolean v) { this.useColumnRule = v; }

    public double columnRuleWidth() { return columnRuleWidth; }
    public void columnRuleWidth(double v) { this.columnRuleWidth = v; }

    public String columnRuleType() { return columnRuleType; }
    public void columnRuleType(String v) { this.columnRuleType = v; }

    public String columnRuleColor() { return columnRuleColor; }
    public void columnRuleColor(String v) { this.columnRuleColor = v; }

    public double columnRuleTint() { return columnRuleTint; }
    public void columnRuleTint(double v) { this.columnRuleTint = v; }

    public double columnRuleOffset() { return columnRuleOffset; }
    public void columnRuleOffset(double v) { this.columnRuleOffset = v; }

    public double columnRuleInsetWidth() { return columnRuleInsetWidth; }
    public void columnRuleInsetWidth(double v) { this.columnRuleInsetWidth = v; }

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
