package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 인라인 객체 — 텍스트 흐름 내 앵커된 리프 노드.
 * 이미지, 렌더링된 그룹/벡터, 수식 등.
 * 자식 노드를 가질 수 없음 (Stage3에서 축소 완료).
 */
public class ASTInlineObject extends ASTInlineItem {
    public enum ObjectKind { IMAGE, RENDERED_GROUP, INLINE_TEXT_FRAME, INLINE_BADGE_GROUP, SPACER_RECT }

    private ObjectKind kind;
    private String sourceId;

    // 크기 (HWPUNIT)
    private long width;
    private long height;

    // rendered bounds X 좌표 (mm, 인라인 객체 정렬용)
    private double boundsX = -1;

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
    private String shellShapeType;  // Rectangle, Oval, Polygon

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

    // 배경 그래픽 중앙 정렬용 델타 (HWPUNIT)
    // IDML 그룹 내 텍스트프레임 중심 → 감싸는 배경 그래픽 중심 이동량
    private long overlayCenterDeltaX;
    private long overlayCenterDeltaY;

    // resolved.json에서 가져온 페이지 기준 절대 좌표 (HWPUNIT)
    // 설정된 경우 IDML transform 계산 대신 이 좌표를 사용
    private long resolvedPageX = -1;  // -1 = 미설정
    private long resolvedPageY = -1;
    private long resolvedWidth = -1;
    private long resolvedHeight = -1;

    // true이면 TableBuilder.extractCellInlines에서 floating 추출 금지
    // inline_object 타입 (IDML AnchoredPosition=InlineOrAbove)은 항상 true
    private boolean keepInline = false;

    // true이면 HWPX 문단 줄상자 높이에 참여한다.
    // 작은 배지/표식은 글자처럼 줄높이에 참여하지만, 닫힌 inline visual carrier
    // (도표/삽화 PNG)는 story 흐름에는 남아도 문단 leading을 이미지 높이로 키우지 않는다.
    private boolean affectsLineSpacing = true;

    // Stage 1 ownership plan execution hints. Inline writers must not discard
    // source z/layer because inline shells can overlap page-positioned carriers.
    private int plannedZOrder = Integer.MIN_VALUE;
    private String plannedVisualLayer;

    // INLINE_TEXT_FRAME 배경 PNG 바이트 (설정된 경우 winBrush 대신 imgBrush로 배경 표시)
    private byte[] imageFillData;
    // true이면 전역 시각 정책과 무관하게 이 인라인 프레임의 원본 선/채움을 HWP 도형으로 보존.
    // 작은 텍스트 결합 배지처럼 텍스트는 편집 가능해야 하고 배경 도형은 인라인 흐름에 붙어야 하는 경우에만 사용한다.
    private boolean nativeGraphicsAllowed;
    // true이면 imageFillData를 전역 native-textbox-graphics 정책과 무관하게 도형 배경(imgBrush)으로 emit.
    // InDesign에서 추출한 장식 PNG(곡선 꺾쇠/말풍선 등)를 인라인 박스 배경으로 깔고 텍스트는
    // 검색 가능한 런으로 위에 올리는 경우에만 사용(텍스트 래스터화가 아니므로 source ownership policy 정책과 무관).
    private boolean forceImageFill;

    // IMAGE 그룹 내 오버레이 텍스트프레임 목록 (IMAGE kind 전용)
    // 이미지 컨테이너 내부에 중첩하여 이미지 위에 올바르게 배치
    private java.util.List<ASTInlineObject> overlayFrames;

    // 번들 내 이미지 경로 (예: "images/inline_001.png")
    private String bundlePath;

    // 인라인 텍스트 프레임 데이터 (INLINE_TEXT_FRAME)
    private java.util.List<ASTParagraph> paragraphs;
    private java.util.List<ASTTable> inlineTables;

    // 텍스트 프레임 수직 정렬 (TopAlign, CenterAlign, BottomAlign)
    private String verticalJustification;
    // 원본 InDesign 조판에서 각 composed line이 별도 문단인 경우 HWP 자동 줄감기 금지.
    private boolean noAutoLineWrap;

    public ItemType itemType() { return ItemType.INLINE_OBJECT; }

    public ObjectKind kind() { return kind; }
    public void kind(ObjectKind v) { this.kind = v; }

    public String sourceId() { return sourceId; }
    public void sourceId(String v) { this.sourceId = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public double boundsX() { return boundsX; }
    public void boundsX(double v) { this.boundsX = v; }

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

    public String shellShapeType() { return shellShapeType; }
    public void shellShapeType(String v) { this.shellShapeType = v; }

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

    public long overlayCenterDeltaX() { return overlayCenterDeltaX; }
    public void overlayCenterDeltaX(long v) { this.overlayCenterDeltaX = v; }

    public long overlayCenterDeltaY() { return overlayCenterDeltaY; }
    public void overlayCenterDeltaY(long v) { this.overlayCenterDeltaY = v; }

    public long containerWidth() { return containerWidth; }
    public void containerWidth(long v) { this.containerWidth = v; }

    public long containerHeight() { return containerHeight; }
    public void containerHeight(long v) { this.containerHeight = v; }

    public long imageOffsetX() { return imageOffsetX; }
    public void imageOffsetX(long v) { this.imageOffsetX = v; }

    public long imageOffsetY() { return imageOffsetY; }
    public void imageOffsetY(long v) { this.imageOffsetY = v; }

    public long resolvedPageX() { return resolvedPageX; }
    public void resolvedPageX(long v) { this.resolvedPageX = v; }

    public long resolvedPageY() { return resolvedPageY; }
    public void resolvedPageY(long v) { this.resolvedPageY = v; }

    public long resolvedWidth() { return resolvedWidth; }
    public void resolvedWidth(long v) { this.resolvedWidth = v; }

    public long resolvedHeight() { return resolvedHeight; }
    public void resolvedHeight(long v) { this.resolvedHeight = v; }

    public boolean keepInline() { return keepInline; }
    public void keepInline(boolean v) { this.keepInline = v; }

    public boolean affectsLineSpacing() { return affectsLineSpacing; }
    public void affectsLineSpacing(boolean v) { this.affectsLineSpacing = v; }

    public int plannedZOrder() { return plannedZOrder; }
    public void plannedZOrder(int v) { this.plannedZOrder = v; }

    public String plannedVisualLayer() { return plannedVisualLayer; }
    public void plannedVisualLayer(String v) { this.plannedVisualLayer = v; }

    public byte[] imageFillData() { return imageFillData; }
    public void imageFillData(byte[] v) { this.imageFillData = v; }

    public boolean nativeGraphicsAllowed() { return nativeGraphicsAllowed; }
    public void nativeGraphicsAllowed(boolean v) { this.nativeGraphicsAllowed = v; }

    public boolean forceImageFill() { return forceImageFill; }
    public void forceImageFill(boolean v) { this.forceImageFill = v; }

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

    public String verticalJustification() { return verticalJustification; }
    public void verticalJustification(String v) { this.verticalJustification = v; }

    public boolean noAutoLineWrap() { return noAutoLineWrap; }
    public void noAutoLineWrap(boolean v) { this.noAutoLineWrap = v; }
}
