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

    public double widthPoints() {
        return geometricBounds != null ? IDMLGeometry.width(geometricBounds) : 0;
    }

    public double heightPoints() {
        return geometricBounds != null ? IDMLGeometry.height(geometricBounds) : 0;
    }

    public boolean isEmbedded() {
        return "Embedded".equals(linkStoredState);
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
