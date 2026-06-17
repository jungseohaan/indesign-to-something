package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * resolved.json의 pageItem 항목.
 * InDesign DOM에서 수집한 페이지 아이템의 평탄화된 속성.
 *
 * ID는 InDesign DOM의 10진수 문자열 (예: "5941").
 * IDML의 "u" + 16진수 형식(예: "u1735")과 변환:
 *   parseInt("1735", 16) = 5941
 */
public class ResolvedPageItem {
    private String id;              // InDesign DOM id (10진수)
    private String type;            // "Rectangle", "Oval", "Polygon", "Group", "TextFrame", "GraphicLine"
    private String name;
    private String parentId;        // 부모 DOM id (Spread/Page 직속이면 null)
    private int pageIndex = -1;     // 문서 내 페이지 인덱스 (0-based)
    private String layerId;         // InDesign ItemLayer id
    private String layerName;       // InDesign ItemLayer name
    private int layerIndex = -1;    // document.layers index, when available

    // 기하 (pasteboard 좌표, pt) — [top, left, bottom, right]
    private double[] geometricBounds;
    private double[] visibleBounds;     // 스트로크/효과 포함 실제 렌더링 영역

    // 절대 변환 (InDesign이 계산한 최종 값)
    private double absoluteRotationAngle;
    private double absoluteShearAngle;
    private double absoluteHorizontalScale = 100;
    private double absoluteVerticalScale = 100;

    // 채우기 (색상 이름 → ResolvedData.colorHexMap으로 해결)
    private String fillColorName;
    private double fillTint = 100;

    // 스트로크
    private String strokeColorName;
    private double strokeTint = 100;
    private double strokeWeight;
    private String strokeAlignment;

    // 효과
    private double opacity = 100;

    // 그라디언트 페더
    private boolean gradientFeatherApplied;
    private double gradientFeatherAngle;
    private double gradientFeatherLength;
    private String gradientFeatherType;

    // 드롭 섀도우
    private double dropShadowAngle;
    private double dropShadowDistance;
    private double dropShadowSize;
    private double dropShadowOpacity;
    private String dropShadowColorName;

    // 플립
    private String absoluteFlip;  // "Flip.NONE", "Flip.HORIZONTAL", "Flip.VERTICAL", "Flip.HORIZONTAL_AND_VERTICAL"

    // 코너
    private double cornerRadius;

    // IDML-Free 파이프라인 보강
    private int zOrder;
    private boolean isInline;
    private int[] childIds;          // Group 자식 ID
    private boolean clipContent;     // Group 클리핑
    private double[] pageRelativeBounds; // [top, left, bottom, right] page-relative

    // --- 접근자 ---

    public String id() { return id; }
    public void id(String v) { this.id = v; }

    public String type() { return type; }
    public void type(String v) { this.type = v; }

    public String name() { return name; }
    public void name(String v) { this.name = v; }

    public String parentId() { return parentId; }
    public void parentId(String v) { this.parentId = v; }

    public int pageIndex() { return pageIndex; }
    public void pageIndex(int v) { this.pageIndex = v; }

    public String layerId() { return layerId; }
    public void layerId(String v) { this.layerId = v; }

    public String layerName() { return layerName; }
    public void layerName(String v) { this.layerName = v; }

    public int layerIndex() { return layerIndex; }
    public void layerIndex(int v) { this.layerIndex = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] visibleBounds() { return visibleBounds; }
    public void visibleBounds(double[] v) { this.visibleBounds = v; }

    public double absoluteRotationAngle() { return absoluteRotationAngle; }
    public void absoluteRotationAngle(double v) { this.absoluteRotationAngle = v; }

    public double absoluteShearAngle() { return absoluteShearAngle; }
    public void absoluteShearAngle(double v) { this.absoluteShearAngle = v; }

    public double absoluteHorizontalScale() { return absoluteHorizontalScale; }
    public void absoluteHorizontalScale(double v) { this.absoluteHorizontalScale = v; }

    public double absoluteVerticalScale() { return absoluteVerticalScale; }
    public void absoluteVerticalScale(double v) { this.absoluteVerticalScale = v; }

    public String fillColorName() { return fillColorName; }
    public void fillColorName(String v) { this.fillColorName = v; }

    public double fillTint() { return fillTint; }
    public void fillTint(double v) { this.fillTint = v; }

    public String strokeColorName() { return strokeColorName; }
    public void strokeColorName(String v) { this.strokeColorName = v; }

    public double strokeTint() { return strokeTint; }
    public void strokeTint(double v) { this.strokeTint = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public String strokeAlignment() { return strokeAlignment; }
    public void strokeAlignment(String v) { this.strokeAlignment = v; }

    public double opacity() { return opacity; }
    public void opacity(double v) { this.opacity = v; }

    public boolean gradientFeatherApplied() { return gradientFeatherApplied; }
    public void gradientFeatherApplied(boolean v) { this.gradientFeatherApplied = v; }

    public double gradientFeatherAngle() { return gradientFeatherAngle; }
    public void gradientFeatherAngle(double v) { this.gradientFeatherAngle = v; }

    public double gradientFeatherLength() { return gradientFeatherLength; }
    public void gradientFeatherLength(double v) { this.gradientFeatherLength = v; }

    public String gradientFeatherType() { return gradientFeatherType; }
    public void gradientFeatherType(String v) { this.gradientFeatherType = v; }

    public double dropShadowAngle() { return dropShadowAngle; }
    public void dropShadowAngle(double v) { this.dropShadowAngle = v; }

    public double dropShadowDistance() { return dropShadowDistance; }
    public void dropShadowDistance(double v) { this.dropShadowDistance = v; }

    public double dropShadowSize() { return dropShadowSize; }
    public void dropShadowSize(double v) { this.dropShadowSize = v; }

    public double dropShadowOpacity() { return dropShadowOpacity; }
    public void dropShadowOpacity(double v) { this.dropShadowOpacity = v; }

    public String dropShadowColorName() { return dropShadowColorName; }
    public void dropShadowColorName(String v) { this.dropShadowColorName = v; }

    public boolean hasDropShadow() {
        return dropShadowDistance > 0 || dropShadowSize > 0;
    }

    public String absoluteFlip() { return absoluteFlip; }
    public void absoluteFlip(String v) { this.absoluteFlip = v; }

    public boolean isFlippedHorizontal() {
        return absoluteFlip != null && absoluteFlip.contains("HORIZONTAL");
    }

    public boolean isFlippedVertical() {
        return absoluteFlip != null && absoluteFlip.contains("VERTICAL");
    }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public boolean isInline() { return isInline; }
    public void isInline(boolean v) { this.isInline = v; }

    public int[] childIds() { return childIds; }
    public void childIds(int[] v) { this.childIds = v; }

    public boolean clipContent() { return clipContent; }
    public void clipContent(boolean v) { this.clipContent = v; }

    public double[] pageRelativeBounds() { return pageRelativeBounds; }
    public void pageRelativeBounds(double[] v) { this.pageRelativeBounds = v; }
}
