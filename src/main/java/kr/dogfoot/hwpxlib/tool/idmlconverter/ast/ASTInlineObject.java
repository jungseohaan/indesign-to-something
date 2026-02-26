package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 인라인 객체 — 텍스트 흐름 내 앵커된 리프 노드.
 * 이미지, 렌더링된 그룹/벡터, 수식 등.
 * 자식 노드를 가질 수 없음 (Stage3에서 축소 완료).
 */
public class ASTInlineObject extends ASTInlineItem {
    public enum ObjectKind { IMAGE, RENDERED_GROUP, INLINE_TEXT_FRAME, SPACER_RECT }

    private ObjectKind kind;
    private String sourceId;

    // 크기 (HWPUNIT)
    private long width;
    private long height;

    // 이미지 데이터 (IMAGE, RENDERED_GROUP)
    private String imageFormat;
    private byte[] imageData;
    private String imagePath;
    private int pixelWidth;
    private int pixelHeight;

    // 앵커/래핑 속성 (IDML 원본값)
    private String anchoredPosition;   // "InlinePosition", "AboveLine", "Anchored"
    private String textWrapMode;       // "None", "BoundingBoxTextWrap", "JumpObjectTextWrap"
    private String textWrapSide;       // "BothSides", "LeftSide", "RightSide", "LargestArea"
    private long textWrapTop, textWrapLeft, textWrapBottom, textWrapRight; // HWPUNIT

    // 프레임 스타일 (INLINE_TEXT_FRAME — 부모 Group의 배경 사각형에서 전달)
    private String fillColor;      // "#RRGGBB" hex
    private double fillTint = 100; // 0~100
    private String strokeColor;    // "#RRGGBB" hex
    private double strokeWeight;   // points
    private double strokeTint = 100;
    private double cornerRadius;   // points

    // 텍스트 여백 (HWPUNIT) — Group 배경 사각형과 텍스트 프레임의 위치 차이에서 산출
    private long textMarginTop;
    private long textMarginLeft;
    private long textMarginBottom;
    private long textMarginRight;

    // 오버레이 위치 — IMAGE 그룹 내 자식 텍스트프레임의 상대 위치 (HWPUNIT)
    // isOverlay=true이면 부모 이미지 위에 떠 있는 글상자로 배치
    private boolean isOverlay;
    private long overlayX;        // 부모 그룹 왼쪽 기준 X 오프셋
    private long overlayY;        // 부모 그룹 상단 기준 Y 오프셋
    private long overlayParentWidth;  // 부모 그룹(이미지)의 너비
    private long overlayParentHeight; // 부모 그룹(이미지)의 높이

    // 컨테이너 크기 (HWPUNIT) — IMAGE 그룹의 바운딩 박스 표시 크기
    // 그룹 내 이미지 + 모든 텍스트프레임을 포함하는 크기 (오버레이 있을 때만 설정)
    private long containerWidth;
    private long containerHeight;

    // 이미지의 컨테이너 내 오프셋 (HWPUNIT) — 그룹 바운딩 박스 원점 기준
    private long imageOffsetX;
    private long imageOffsetY;

    // IMAGE 그룹 내 오버레이 텍스트프레임 목록 (IMAGE kind 전용)
    // 이미지 컨테이너 내부에 중첩하여 이미지 위에 올바르게 배치
    private java.util.List<ASTInlineObject> overlayFrames;

    // 번들 내 이미지 경로 (예: "images/inline_001.png")
    private String bundlePath;

    // 인라인 텍스트 프레임 데이터 (INLINE_TEXT_FRAME)
    private java.util.List<ASTParagraph> paragraphs;
    private java.util.List<ASTTable> inlineTables;

    public ItemType itemType() { return ItemType.INLINE_OBJECT; }

    public ObjectKind kind() { return kind; }
    public void kind(ObjectKind v) { this.kind = v; }

    public String sourceId() { return sourceId; }
    public void sourceId(String v) { this.sourceId = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public String imageFormat() { return imageFormat; }
    public void imageFormat(String v) { this.imageFormat = v; }

    public byte[] imageData() { return imageData; }
    public void imageData(byte[] v) { this.imageData = v; }

    public String imagePath() { return imagePath; }
    public void imagePath(String v) { this.imagePath = v; }

    public int pixelWidth() { return pixelWidth; }
    public void pixelWidth(int v) { this.pixelWidth = v; }

    public int pixelHeight() { return pixelHeight; }
    public void pixelHeight(int v) { this.pixelHeight = v; }

    public String anchoredPosition() { return anchoredPosition; }
    public void anchoredPosition(String v) { this.anchoredPosition = v; }

    public String textWrapMode() { return textWrapMode; }
    public void textWrapMode(String v) { this.textWrapMode = v; }

    public String textWrapSide() { return textWrapSide; }
    public void textWrapSide(String v) { this.textWrapSide = v; }

    public long textWrapTop() { return textWrapTop; }
    public void textWrapTop(long v) { this.textWrapTop = v; }
    public long textWrapLeft() { return textWrapLeft; }
    public void textWrapLeft(long v) { this.textWrapLeft = v; }
    public long textWrapBottom() { return textWrapBottom; }
    public void textWrapBottom(long v) { this.textWrapBottom = v; }
    public long textWrapRight() { return textWrapRight; }
    public void textWrapRight(long v) { this.textWrapRight = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public double fillTint() { return fillTint; }
    public void fillTint(double v) { this.fillTint = v; }

    public String strokeColor() { return strokeColor; }
    public void strokeColor(String v) { this.strokeColor = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public double strokeTint() { return strokeTint; }
    public void strokeTint(double v) { this.strokeTint = v; }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public long textMarginTop() { return textMarginTop; }
    public void textMarginTop(long v) { this.textMarginTop = v; }
    public long textMarginLeft() { return textMarginLeft; }
    public void textMarginLeft(long v) { this.textMarginLeft = v; }
    public long textMarginBottom() { return textMarginBottom; }
    public void textMarginBottom(long v) { this.textMarginBottom = v; }
    public long textMarginRight() { return textMarginRight; }
    public void textMarginRight(long v) { this.textMarginRight = v; }

    public boolean isOverlay() { return isOverlay; }
    public void isOverlay(boolean v) { this.isOverlay = v; }

    public long overlayX() { return overlayX; }
    public void overlayX(long v) { this.overlayX = v; }

    public long overlayY() { return overlayY; }
    public void overlayY(long v) { this.overlayY = v; }

    public long overlayParentWidth() { return overlayParentWidth; }
    public void overlayParentWidth(long v) { this.overlayParentWidth = v; }

    public long overlayParentHeight() { return overlayParentHeight; }
    public void overlayParentHeight(long v) { this.overlayParentHeight = v; }

    public long containerWidth() { return containerWidth; }
    public void containerWidth(long v) { this.containerWidth = v; }

    public long containerHeight() { return containerHeight; }
    public void containerHeight(long v) { this.containerHeight = v; }

    public long imageOffsetX() { return imageOffsetX; }
    public void imageOffsetX(long v) { this.imageOffsetX = v; }

    public long imageOffsetY() { return imageOffsetY; }
    public void imageOffsetY(long v) { this.imageOffsetY = v; }

    public String bundlePath() { return bundlePath; }
    public void bundlePath(String v) { this.bundlePath = v; }

    public java.util.List<ASTInlineObject> overlayFrames() { return overlayFrames; }
    public void addOverlayFrame(ASTInlineObject f) {
        if (this.overlayFrames == null) this.overlayFrames = new java.util.ArrayList<>();
        this.overlayFrames.add(f);
    }

    public java.util.List<ASTParagraph> paragraphs() { return paragraphs; }
    public void paragraphs(java.util.List<ASTParagraph> v) { this.paragraphs = v; }
    public void addParagraph(ASTParagraph p) {
        if (this.paragraphs == null) this.paragraphs = new java.util.ArrayList<>();
        this.paragraphs.add(p);
    }

    public java.util.List<ASTTable> inlineTables() { return inlineTables; }
    public void addInlineTable(ASTTable t) {
        if (this.inlineTables == null) this.inlineTables = new java.util.ArrayList<>();
        this.inlineTables.add(t);
    }
}
