package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 텍스트 프레임 블록 — 하나의 IDML TextFrame에서 유래한 단락 그룹.
 * HWPX에서 Rectangle + DrawText로 변환됨.
 * 좌표 단위: HWPUNIT (페이지 상대).
 */
public class ASTTextFrameBlock extends ASTBlock {
    private long x;
    private long y;
    private long width;
    private long height;
    private int columnCount;
    private long columnGutter;
    private long[] columnWidths; // 각 컬럼의 콘텐츠 폭 (hwpunits), null이면 균등 분할
    private int zOrder;
    private boolean verticalText;
    private String verticalJustification;

    // 텍스트 프레임 내부 여백
    private long insetTop;
    private long insetLeft;
    private long insetBottom;
    private long insetRight;

    // 프레임 스타일
    private String fillColor;
    private byte[] imageFillData;
    private boolean nativeGraphicsAllowed;
    // true이면 전역 native-textbox-graphics OFF여도 배경 fill/stroke를 강제로 칠한다.
    // (deco PNG가 풀어준 대형 배경/제목바를 네이티브 흡수한 사이드박스 블록 전용 — 좁은 범위)
    private boolean forceNativeFill;
    // true이면 전역 native-textbox-graphics OFF여도 imageFillData를 박스 배경으로 강제로 칠한다.
    // (통합 플로팅 배지 전용 — 텍스트는 검색 가능 런으로 유지되므로 source ownership policy 위배 아님)
    private boolean forceImageFill;
    private String strokeColor;
    private double strokeWeight;
    private String strokeType = "Solid"; // Solid, Dashed, Dotted
    private double fillTint = 100;       // 0~100
    private double strokeTint = 100;     // 0~100
    private double cornerRadius;
    private boolean fromGroup;
    private String storyId;
    private boolean distributed; // resolved 기반 문단 재배치 완료 → 연결 글상자 링크 해제
    // 공간 포함 inner TF id: 이 TF의 밑줄 빈칸 위에 오버레이된 예시 답안 TF (다른 story).
    // StoryConverter가 inner story 런을 이 블록의 마지막 단락에 주입.
    private String innerFrameId;
    private double rotationAngle; // 프레임 회전 각도 (도 단위)

    // 래퍼 사각형 배경 (부모 Rectangle에서 전파된 fill — 테두리 효과용)
    private String wrapperFillColor;
    private double wrapperFillTint = -1;

    // 드롭 섀도우
    private boolean dropShadow;

    // 겹침 감지: SQUARE textWrap 적용 플래그
    private boolean textWrapSquare;

    // Phase 2가 non-editable inline TF를 플로팅 글상자로 전환한 경우 true.
    // HwpxTextBoxBuilder가 이 플래그를 보고 투명 DrawText 경로(hp:rect)로 라우팅.
    private boolean inlineToFloating;
    // Stage 1 plan상 별도 visual shell/backdrop 위에 얹히는 HWPX 텍스트.
    // HWPX table은 floating PNG와 평면이 엇갈릴 수 있어 overlay-safe DrawText 경로로 라우팅한다.
    private boolean plannedVisualTextOverlay;
    // Stage 1 PLACE_TEXT_SHELL plan의 visualLayer. 실행 단계는 이 값을 통해
    // native wrapper/shell의 HWPX plane/z-band를 재판정 없이 적용한다.
    private String plannedShellVisualLayer;
    // 페이지 좌표는 유지하지만 앵커 문단과 함께 이동해야 하는 플로팅 글상자.
    // 인라인(treatAsChar=true)과 달리 원본의 page-level group stack 좌표를 보존한다.
    private boolean anchoredFlowWithText;

    // 폴리곤 경로 (비사각형 프레임용, 페이지 상대 HWPUNIT 좌표)
    private long[] pathPointsX; // null이면 사각형
    private long[] pathPointsY;

    // 원본 InDesign 조판에서 각 composed line이 별도 문단인 경우 HWP 자동 줄감기 금지.
    private boolean noAutoLineWrap;
    // 원본 composedLines의 실제 ink box가 carrier frame보다 작은 overlay TF.
    // HWPX 1x1 table carrier는 이런 짧은 오버레이를 재흐름시켜 위치를 망가뜨릴 수 있으므로
    // fixed drawText carrier로 materialize한다.
    private boolean sourceComposedFixedText;

    private List<ASTParagraph> paragraphs;

    public ASTTextFrameBlock() {
        this.paragraphs = new ArrayList<>();
    }

    public BlockType blockType() { return BlockType.TEXT_FRAME_BLOCK; }

    public long x() { return x; }
    public void x(long v) { this.x = v; }

    public long y() { return y; }
    public void y(long v) { this.y = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public long columnGutter() { return columnGutter; }
    public void columnGutter(long v) { this.columnGutter = v; }

    public long[] columnWidths() { return columnWidths; }
    public void columnWidths(long[] v) { this.columnWidths = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public boolean verticalText() { return verticalText; }
    public void verticalText(boolean v) { this.verticalText = v; }

    public String verticalJustification() { return verticalJustification; }
    public void verticalJustification(String v) { this.verticalJustification = v; }

    public long insetTop() { return insetTop; }
    public void insetTop(long v) { this.insetTop = v; }

    public long insetLeft() { return insetLeft; }
    public void insetLeft(long v) { this.insetLeft = v; }

    public long insetBottom() { return insetBottom; }
    public void insetBottom(long v) { this.insetBottom = v; }

    public long insetRight() { return insetRight; }
    public void insetRight(long v) { this.insetRight = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public byte[] imageFillData() { return imageFillData; }
    public void imageFillData(byte[] v) { this.imageFillData = v; }

    public boolean nativeGraphicsAllowed() { return nativeGraphicsAllowed; }
    public void nativeGraphicsAllowed(boolean v) { this.nativeGraphicsAllowed = v; }

    public boolean forceImageFill() { return forceImageFill; }
    public void forceImageFill(boolean v) { this.forceImageFill = v; }

    public boolean forceNativeFill() { return forceNativeFill; }
    public void forceNativeFill(boolean v) { this.forceNativeFill = v; }

    public String strokeColor() { return strokeColor; }
    public void strokeColor(String v) { this.strokeColor = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public String strokeType() { return strokeType; }
    public void strokeType(String v) { this.strokeType = v; }

    public double fillTint() { return fillTint; }
    public void fillTint(double v) { this.fillTint = v; }

    public double strokeTint() { return strokeTint; }
    public void strokeTint(double v) { this.strokeTint = v; }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public boolean fromGroup() { return fromGroup; }
    public void fromGroup(boolean v) { this.fromGroup = v; }

    public String storyId() { return storyId; }
    public void storyId(String v) { this.storyId = v; }

    public boolean distributed() { return distributed; }
    public void distributed(boolean v) { this.distributed = v; }

    public String innerFrameId() { return innerFrameId; }
    public void innerFrameId(String v) { this.innerFrameId = v; }

    public boolean inlineToFloating() { return inlineToFloating; }
    public void inlineToFloating(boolean v) { this.inlineToFloating = v; }

    public boolean plannedVisualTextOverlay() { return plannedVisualTextOverlay; }
    public void plannedVisualTextOverlay(boolean v) { this.plannedVisualTextOverlay = v; }

    public String plannedShellVisualLayer() { return plannedShellVisualLayer; }
    public void plannedShellVisualLayer(String v) { this.plannedShellVisualLayer = v; }

    public boolean anchoredFlowWithText() { return anchoredFlowWithText; }
    public void anchoredFlowWithText(boolean v) { this.anchoredFlowWithText = v; }

    public boolean noAutoLineWrap() { return noAutoLineWrap; }
    public void noAutoLineWrap(boolean v) { this.noAutoLineWrap = v; }

    public boolean sourceComposedFixedText() { return sourceComposedFixedText; }
    public void sourceComposedFixedText(boolean v) { this.sourceComposedFixedText = v; }

    public double rotationAngle() { return rotationAngle; }
    public void rotationAngle(double v) { this.rotationAngle = v; }

    public boolean dropShadow() { return dropShadow; }
    public void dropShadow(boolean v) { this.dropShadow = v; }

    public boolean textWrapSquare() { return textWrapSquare; }
    public void textWrapSquare(boolean v) { this.textWrapSquare = v; }

    /** 실제 렌더링에 사용할 폭. Source ownership pipeline에서는 원본 carrier width를 그대로 쓴다. */
    public long effectiveWidth() {
        return width;
    }

    /** 실제 렌더링에 사용할 X 좌표. Source ownership pipeline에서는 원본 carrier x를 그대로 쓴다. */
    public long effectiveX() {
        return x;
    }

    public String wrapperFillColor() { return wrapperFillColor; }
    public void wrapperFillColor(String v) { this.wrapperFillColor = v; }

    public double wrapperFillTint() { return wrapperFillTint; }
    public void wrapperFillTint(double v) { this.wrapperFillTint = v; }

    public boolean hasWrapperFill() { return wrapperFillColor != null && wrapperFillColor.startsWith("#"); }

    public long[] pathPointsX() { return pathPointsX; }
    public long[] pathPointsY() { return pathPointsY; }
    public void pathPoints(long[] px, long[] py) {
        this.pathPointsX = px;
        this.pathPointsY = py;
    }
    public boolean hasNonRectPath() { return pathPointsX != null && pathPointsX.length > 4; }

    public List<ASTParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(ASTParagraph p) { paragraphs.add(p); }

    // overflow 감지용: Story 전체 텍스트 길이 vs 프레임에 보이는 텍스트 길이
    private int storyTotalTextLength;
    private int frameVisibleTextLength;
    private String frameVisibleText; // wrap 분할 시 블록별 보이는 텍스트
    public int storyTotalTextLength() { return storyTotalTextLength; }
    public void storyTotalTextLength(int v) { this.storyTotalTextLength = v; }
    public int frameVisibleTextLength() { return frameVisibleTextLength; }
    public void frameVisibleTextLength(int v) { this.frameVisibleTextLength = v; }
    public String frameVisibleText() { return frameVisibleText; }
    public void frameVisibleText(String v) { this.frameVisibleText = v; }

    /**
     * 배경 전용 블록인지 판별.
     * fillColor가 있으면서 실질 텍스트가 없는 블록 (장식용 배경 사각형).
     */
    public boolean isBackgroundOnly() {
        if (fillColor == null || fillColor.isEmpty()) return false;
        for (ASTParagraph para : paragraphs) {
            for (ASTInlineItem item : para.items()) {
                if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.trim().isEmpty()) return false;
                } else if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT
                        || item.itemType() == ASTInlineItem.ItemType.EQUATION) {
                    return false;
                }
            }
        }
        return true;
    }
}
