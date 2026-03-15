package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.List;

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
    private String parentGroupId;        // 소속 그룹의 IDML Self ID

    // PSD 레이어 가시성 오버라이드 (GraphicLayerOption)
    // InDesign에서 같은 PSD를 여러 프레임에 배치할 때 프레임별로 다른 레이어를 보여줄 수 있다.
    // null이면 오버라이드 없음 (PSD 컴포지트 사용).
    private List<int[]> graphicLayers;   // [{id, visible(0/1)}, ...] — null이면 오버라이드 없음

    // 이미지 채색 (Grayscale/Monotone 이미지의 InDesign 컬러링)
    // InDesign에서 그레이스케일 이미지에 FillColor를 지정하면
    // 그레이스케일 값을 알파 마스크로 사용하여 해당 색으로 채색한다.
    // (흰색=투명, 검정=FillTint% 불투명)
    private String imageFillColor;       // Image 요소의 FillColor (예: "Color/Black")
    private double imageFillTint = -1;   // Image 요소의 FillTint (0~100, -1=미지정)
    private String imageColorSpace;      // Image 요소의 Space (예: "$ID/#Links_Grayscale")

    // IDML 내장 이미지 데이터 (base64 인코딩)
    // LinkResourceURI가 없는 붙여넣기 이미지의 경우 <Contents> 요소에 바이너리 데이터가 포함됨
    private String embeddedContents;

    // 프레임 경로 (비사각형 클리핑용)
    // 각 포인트: [anchorX, anchorY, leftDirX, leftDirY, rightDirX, rightDirY]
    private List<double[]> framePath;    // null이면 사각형 프레임

    // 둥근 모서리
    private double cornerRadius;
    private double[] cornerRadii;        // [topLeft, topRight, bottomLeft, bottomRight]
    private String cornerOption;         // "RoundedCorner", "None" 등

    // 그라디언트 페더 (Image 요소의 TransparencySetting > GradientFeatherSetting)
    private double gradientFeatherAngle = Double.NaN;   // 각도 (degrees)
    private double gradientFeatherLength;                // 길이 (points)
    private double[] gradientFeatherStart;               // 시작점 [x, y] (로컬 좌표)

    // 텍스트 감싸기 (TextWrapPreference)
    private String textWrapMode;         // "None", "BoundingBoxTextWrap", "JumpObjectTextWrap", "Contour"
    private String textWrapSide;         // "BothSides", "LeftSide", "RightSide", "LargestArea"
    private double textWrapTop;          // offset (points)
    private double textWrapLeft;
    private double textWrapBottom;
    private double textWrapRight;

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

    public String parentGroupId() { return parentGroupId; }
    public void parentGroupId(String v) { this.parentGroupId = v; }

    public double widthPoints() {
        return geometricBounds != null ? IDMLGeometry.width(geometricBounds) : 0;
    }

    public double heightPoints() {
        return geometricBounds != null ? IDMLGeometry.height(geometricBounds) : 0;
    }

    public boolean isEmbedded() {
        return "Embedded".equals(linkStoredState);
    }

    public String embeddedContents() { return embeddedContents; }
    public void embeddedContents(String v) { this.embeddedContents = v; }

    /**
     * 내장 이미지 데이터가 있는지 확인한다.
     * LinkResourceURI가 없어도 Contents에 바이너리 데이터가 있으면 true.
     */
    public boolean hasEmbeddedContents() {
        return embeddedContents != null && !embeddedContents.isEmpty();
    }

    public List<double[]> framePath() { return framePath; }
    public void framePath(List<double[]> v) { this.framePath = v; }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public double[] cornerRadii() { return cornerRadii; }
    public void cornerRadii(double[] v) { this.cornerRadii = v; }

    public String cornerOption() { return cornerOption; }
    public void cornerOption(String v) { this.cornerOption = v; }

    public boolean hasRoundedCorners() {
        if (!"RoundedCorner".equals(cornerOption)) return false;
        if (cornerRadii != null && cornerRadii.length >= 4) {
            for (double r : cornerRadii) {
                if (r > 0) return true;
            }
            return false;
        }
        return cornerRadius > 0;
    }

    /**
     * Per-corner radii가 있고 값이 다른지 확인한다.
     */
    public boolean hasPerCornerRadii() {
        if (cornerRadii == null || cornerRadii.length < 4) return false;
        return cornerRadii[0] != cornerRadii[1] || cornerRadii[0] != cornerRadii[2]
                || cornerRadii[0] != cornerRadii[3];
    }

    /**
     * 프레임이 비사각형인지 확인한다.
     * PathPoint가 4개 초과이거나 베지어 곡선이 포함되어 있으면 true.
     */
    public boolean hasNonRectangularFrame() {
        if (framePath == null || framePath.isEmpty()) return false;
        if (framePath.size() > 4) return true;
        for (double[] pt : framePath) {
            // pt: [anchorX, anchorY, leftX, leftY, rightX, rightY]
            double ax = pt[0], ay = pt[1];
            double lx = pt[2], ly = pt[3];
            double rx = pt[4], ry = pt[5];
            if (Math.abs(ax - lx) > 0.001 || Math.abs(ay - ly) > 0.001
                    || Math.abs(ax - rx) > 0.001 || Math.abs(ay - ry) > 0.001) {
                return true; // 베지어 곡선
            }
        }
        return false;
    }

    public String textWrapMode() { return textWrapMode; }
    public void textWrapMode(String v) { this.textWrapMode = v; }

    public String textWrapSide() { return textWrapSide; }
    public void textWrapSide(String v) { this.textWrapSide = v; }

    public double textWrapTop() { return textWrapTop; }
    public void textWrapTop(double v) { this.textWrapTop = v; }

    public double textWrapLeft() { return textWrapLeft; }
    public void textWrapLeft(double v) { this.textWrapLeft = v; }

    public double textWrapBottom() { return textWrapBottom; }
    public void textWrapBottom(double v) { this.textWrapBottom = v; }

    public double textWrapRight() { return textWrapRight; }
    public void textWrapRight(double v) { this.textWrapRight = v; }

    public double gradientFeatherAngle() { return gradientFeatherAngle; }
    public void gradientFeatherAngle(double v) { this.gradientFeatherAngle = v; }

    public double gradientFeatherLength() { return gradientFeatherLength; }
    public void gradientFeatherLength(double v) { this.gradientFeatherLength = v; }

    public double[] gradientFeatherStart() { return gradientFeatherStart; }
    public void gradientFeatherStart(double[] v) { this.gradientFeatherStart = v; }

    public boolean hasGradientFeather() {
        return !Double.isNaN(gradientFeatherAngle) && gradientFeatherLength > 0;
    }

    public String imageFillColor() { return imageFillColor; }
    public void imageFillColor(String v) { this.imageFillColor = v; }

    public double imageFillTint() { return imageFillTint; }
    public void imageFillTint(double v) { this.imageFillTint = v; }

    public String imageColorSpace() { return imageColorSpace; }
    public void imageColorSpace(String v) { this.imageColorSpace = v; }

    /**
     * 그레이스케일 이미지에 채색이 필요한지 판단한다.
     * InDesign에서 그레이스케일 이미지에 FillColor가 지정되어 있으면 true.
     */
    public boolean needsGrayscaleColorization() {
        return imageColorSpace != null
                && imageColorSpace.contains("Grayscale")
                && imageFillColor != null
                && !imageFillColor.isEmpty();
    }

    public List<int[]> graphicLayers() { return graphicLayers; }
    public void graphicLayers(List<int[]> v) { this.graphicLayers = v; }

    /**
     * 레이어 오버라이드가 있는지 확인한다.
     * OriginalVisibility와 CurrentVisibility가 하나라도 다르면 오버라이드가 있는 것.
     */
    public boolean hasLayerOverrides() {
        return graphicLayers != null && !graphicLayers.isEmpty();
    }

    /**
     * 가시 레이어의 ImageMagick 인덱스 목록을 반환한다.
     * PSD에서 [0]=composite, [1]=bottom layer(Id=0), [2]=Id=1, ...
     * 즉, ImageMagick index = layerId + 1.
     */
    public List<Integer> visibleLayerIndices() {
        if (graphicLayers == null) return null;
        java.util.ArrayList<Integer> indices = new java.util.ArrayList<>();
        for (int[] entry : graphicLayers) {
            if (entry[1] == 1) {
                indices.add(entry[0] + 1); // PSD layer Id → ImageMagick index
            }
        }
        return indices.isEmpty() ? null : indices;
    }

    /**
     * 캐시 키용 레이어 서명 문자열을 반환한다.
     * 예: "L4,6" (layer Id 4,6만 보이는 경우)
     * 오버라이드 없으면 null.
     */
    public String layerSignature() {
        if (graphicLayers == null) return null;
        StringBuilder sb = new StringBuilder("L");
        boolean first = true;
        for (int[] entry : graphicLayers) {
            if (entry[1] == 1) {
                if (!first) sb.append(',');
                sb.append(entry[0]);
                first = false;
            }
        }
        return first ? null : sb.toString();
    }
}
