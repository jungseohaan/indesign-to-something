package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 프레임 (text 또는 image).
 * HWPUNIT 좌표.
 */
public class IntermediateFrame {
    /**
     * 콘텐츠 카테고리 (HWPX 내보내기 시 z-order 정렬에 사용).
     * 낮은 숫자가 먼저 내보내짐 (아래에 배치).
     */
    public enum ContentCategory {
        BACKGROUND_IMAGE(0),      // 배경 이미지 (스프레드 전체 크기)
        DESIGN_FORMAT_IMAGE(1),   // PSD, AI, PDF, EPS, TIFF 이미지
        VECTOR_GRAPHIC_IMAGE(2),  // 벡터 그래픽을 렌더링한 이미지
        REGULAR_IMAGE(3),         // 일반 이미지 (PNG, JPEG 등)
        GROUP(4),                 // 그룹 프레임 (인라인 PNG 포함 글상자)
        TEXT_FRAME(5),            // 텍스트 프레임
        TABLE(6),                 // 테이블
        PAGE_BOUNDARY(7);         // 페이지 경계선 (최상위에 표시)

        private final int order;
        ContentCategory(int order) { this.order = order; }
        public int order() { return order; }
    }

    private String frameId;
    private String frameType;  // "text", "image", "rectangle", or "table"
    private long x;
    private long y;
    private long width;
    private long height;
    private int zOrder;
    private boolean isBackgroundImage;  // 배경 이미지 여부

    // 사각형 프레임 속성 (frameType이 "rectangle"인 경우)
    private String borderColor;         // 테두리 색상 (예: "#000000")
    private double borderWidth;         // 테두리 두께 (points)
    private String fillColor;           // 채우기 색상 (null이면 투명)

    // 텍스트 프레임 스트로크/아웃라인 속성
    private String strokeColor;         // 획 색상 (IDML Color 참조 또는 HEX)
    private double strokeWeight;        // 획 두께 (points)
    private double cornerRadius;        // 모서리 둥글기 (points)
    private double[] cornerRadii;       // 개별 모서리 둥글기 [TL, TR, BL, BR]
    private double fillTint = 100;      // 채우기 불투명도 (0~100)
    private double strokeTint = 100;    // 획 불투명도 (0~100)
    private String textFrameFillColor;  // 텍스트 프레임 채우기 색상

    // 텍스트 프레임 컬럼 정보
    private int columnCount = 1;        // 컬럼 수
    private long columnGutter;          // 컬럼 간격 (HWPUNIT)
    private long insetTop;              // 내부 여백 상단 (HWPUNIT)
    private long insetLeft;             // 내부 여백 좌측 (HWPUNIT)
    private long insetBottom;           // 내부 여백 하단 (HWPUNIT)
    private long insetRight;            // 내부 여백 우측 (HWPUNIT)

    // 컬럼 상세 속성
    private String columnType = "FixedNumber";  // FixedNumber, FixedWidth, FlexibleWidth
    private long columnFixedWidth;              // 고정 너비 (HWPUNIT)
    private long[] columnWidths;                // 가변 너비 배열 (HWPUNIT)

    // 수직 정렬
    private String verticalJustification = "top";  // top, center, bottom, justify

    // 텍스트 감싸기 무시
    private boolean ignoreWrap;

    // 단 경계선 (Column Rule)
    private boolean useColumnRule;              // 경계선 삽입 여부
    private long columnRuleWidth;               // 획 두께 (HWPUNIT)
    private String columnRuleType = "Solid";    // 획 유형
    private String columnRuleColor;             // 획 색상 (HEX)
    private double columnRuleTint = 100;        // 획 색조
    private long columnRuleOffset;              // 가로 위치 (HWPUNIT)
    private long columnRuleInsetWidth;          // 경계선 길이 (HWPUNIT, 0=전체)

    // 회전 정보
    private double rotationAngle;       // 회전 각도 (도, degree) - 시계 방향 양수

    // 텍스트 방향
    private boolean verticalText;       // true: 세로쓰기, false: 가로쓰기

    // 인라인/앵커 객체 플래그
    private boolean isInline;           // 인라인(앵커) 객체 여부

    // 벡터 도형 속성 (frameType이 "shape"인 경우)
    private String shapeType;           // "rectangle", "oval", "polygon"
    private List<double[]> pathPoints;  // 폴리곤 경로 점 목록 [x, y]
    private short cornerRatio;          // 둥근 모서리 비율 (0=직각, 20=둥근, 50=반원)

    private List<IntermediateParagraph> paragraphs;
    private IntermediateImage image;
    private IntermediateTable table;

    // 그룹 프레임 자식 이미지 (frameType == "group")
    private List<IntermediateFrame> groupChildImages;

    public IntermediateFrame() {
        this.paragraphs = new ArrayList<IntermediateParagraph>();
    }

    public String frameId() { return frameId; }
    public void frameId(String v) { this.frameId = v; }

    public String frameType() { return frameType; }
    public void frameType(String v) { this.frameType = v; }

    public long x() { return x; }
    public void x(long v) { this.x = v; }

    public long y() { return y; }
    public void y(long v) { this.y = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public boolean isBackgroundImage() { return isBackgroundImage; }
    public void isBackgroundImage(boolean v) { this.isBackgroundImage = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public long columnGutter() { return columnGutter; }
    public void columnGutter(long v) { this.columnGutter = v; }

    public long insetTop() { return insetTop; }
    public void insetTop(long v) { this.insetTop = v; }

    public long insetLeft() { return insetLeft; }
    public void insetLeft(long v) { this.insetLeft = v; }

    public long insetBottom() { return insetBottom; }
    public void insetBottom(long v) { this.insetBottom = v; }

    public long insetRight() { return insetRight; }
    public void insetRight(long v) { this.insetRight = v; }

    public double rotationAngle() { return rotationAngle; }
    public void rotationAngle(double v) { this.rotationAngle = v; }

    public boolean verticalText() { return verticalText; }
    public void verticalText(boolean v) { this.verticalText = v; }

    public boolean isInline() { return isInline; }
    public void isInline(boolean v) { this.isInline = v; }

    public String shapeType() { return shapeType; }
    public void shapeType(String v) { this.shapeType = v; }

    public List<double[]> pathPoints() { return pathPoints; }
    public void pathPoints(List<double[]> v) { this.pathPoints = v; }

    public short cornerRatio() { return cornerRatio; }
    public void cornerRatio(short v) { this.cornerRatio = v; }

    public List<IntermediateParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(IntermediateParagraph para) { paragraphs.add(para); }

    public IntermediateImage image() { return image; }
    public void image(IntermediateImage v) { this.image = v; }

    public IntermediateTable table() { return table; }
    public void table(IntermediateTable v) { this.table = v; }

    public String borderColor() { return borderColor; }
    public void borderColor(String v) { this.borderColor = v; }

    public double borderWidth() { return borderWidth; }
    public void borderWidth(double v) { this.borderWidth = v; }

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

    public String textFrameFillColor() { return textFrameFillColor; }
    public void textFrameFillColor(String v) { this.textFrameFillColor = v; }

    public String columnType() { return columnType; }
    public void columnType(String v) { this.columnType = v; }

    public long columnFixedWidth() { return columnFixedWidth; }
    public void columnFixedWidth(long v) { this.columnFixedWidth = v; }

    public long[] columnWidths() { return columnWidths; }
    public void columnWidths(long[] v) { this.columnWidths = v; }

    public String verticalJustification() { return verticalJustification; }
    public void verticalJustification(String v) { this.verticalJustification = v; }

    public boolean ignoreWrap() { return ignoreWrap; }
    public void ignoreWrap(boolean v) { this.ignoreWrap = v; }

    public boolean useColumnRule() { return useColumnRule; }
    public void useColumnRule(boolean v) { this.useColumnRule = v; }

    public long columnRuleWidth() { return columnRuleWidth; }
    public void columnRuleWidth(long v) { this.columnRuleWidth = v; }

    public String columnRuleType() { return columnRuleType; }
    public void columnRuleType(String v) { this.columnRuleType = v; }

    public String columnRuleColor() { return columnRuleColor; }
    public void columnRuleColor(String v) { this.columnRuleColor = v; }

    public double columnRuleTint() { return columnRuleTint; }
    public void columnRuleTint(double v) { this.columnRuleTint = v; }

    public long columnRuleOffset() { return columnRuleOffset; }
    public void columnRuleOffset(long v) { this.columnRuleOffset = v; }

    public long columnRuleInsetWidth() { return columnRuleInsetWidth; }
    public void columnRuleInsetWidth(long v) { this.columnRuleInsetWidth = v; }

    public List<IntermediateFrame> groupChildImages() { return groupChildImages; }
    public void groupChildImages(List<IntermediateFrame> v) { this.groupChildImages = v; }
    public void addGroupChildImage(IntermediateFrame child) {
        if (this.groupChildImages == null) {
            this.groupChildImages = new ArrayList<IntermediateFrame>();
        }
        this.groupChildImages.add(child);
    }

    /**
     * 프레임의 콘텐츠 카테고리를 반환한다.
     * HWPX 내보내기 시 z-order 정렬에 사용된다.
     */
    public ContentCategory contentCategory() {
        // 페이지 경계 사각형
        if ("rectangle".equals(frameType)) {
            return ContentCategory.PAGE_BOUNDARY;
        }

        // 텍스트 프레임
        if ("text".equals(frameType)) {
            return ContentCategory.TEXT_FRAME;
        }

        // 테이블
        if ("table".equals(frameType)) {
            return ContentCategory.TABLE;
        }

        // 그룹 프레임
        if ("group".equals(frameType)) {
            return ContentCategory.GROUP;
        }

        // 인라인 벡터 도형
        if ("shape".equals(frameType)) {
            return ContentCategory.TEXT_FRAME;  // 텍스트와 같은 레벨
        }

        // 이미지 프레임
        if ("image".equals(frameType) && image != null) {
            // 배경 이미지
            if (isBackgroundImage) {
                return ContentCategory.BACKGROUND_IMAGE;
            }

            // 벡터 그래픽 렌더링 이미지 (frameId가 "vector_"로 시작)
            if (frameId != null && frameId.startsWith("vector_")) {
                return ContentCategory.VECTOR_GRAPHIC_IMAGE;
            }

            // 디자인 포맷 이미지 (PSD, AI, PDF, EPS, TIFF)
            String format = image.format();
            if (format != null) {
                String lowerFormat = format.toLowerCase();
                if ("psd".equals(lowerFormat) || "ai".equals(lowerFormat) ||
                    "pdf".equals(lowerFormat) || "eps".equals(lowerFormat) ||
                    "tiff".equals(lowerFormat) || "tif".equals(lowerFormat)) {
                    return ContentCategory.DESIGN_FORMAT_IMAGE;
                }
            }

            // originalPath로도 확인 (변환 후 format이 png로 바뀔 수 있음)
            String originalPath = image.originalPath();
            if (originalPath != null) {
                String lowerPath = originalPath.toLowerCase();
                if (lowerPath.endsWith(".psd") || lowerPath.endsWith(".ai") ||
                    lowerPath.endsWith(".pdf") || lowerPath.endsWith(".eps") ||
                    lowerPath.endsWith(".tiff") || lowerPath.endsWith(".tif")) {
                    return ContentCategory.DESIGN_FORMAT_IMAGE;
                }
            }

            // 일반 이미지
            return ContentCategory.REGULAR_IMAGE;
        }

        // 기본값
        return ContentCategory.TEXT_FRAME;
    }

    /**
     * HWPX 내보내기용 정렬 순서를 반환한다.
     * 낮은 값이 먼저 내보내짐 (아래에 배치).
     */
    public int exportOrder() {
        return contentCategory().order() * 10000 + zOrder;
    }
}
