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
    private String strokeColor;
    private double strokeWeight;
    private String strokeType = "Solid"; // Solid, Dashed, Dotted
    private double fillTint = 100;       // 0~100
    private double strokeTint = 100;     // 0~100
    private double cornerRadius;
    private boolean fromGroup;
    private String storyId;
    private boolean distributed; // resolved 기반 문단 재배치 완료 → 연결 글상자 링크 해제
    private double rotationAngle; // 프레임 회전 각도 (도 단위)
    private long narrowedWidth;   // side-by-side 이미지로 축소된 폭 (0 = 미적용)
    private long narrowedXOffset; // 왼쪽 side-by-side 이미지로 X 이동량 (0 = 미적용)

    // 래퍼 사각형 배경 (부모 Rectangle에서 전파된 fill — 테두리 효과용)
    private String wrapperFillColor;
    private double wrapperFillTint = -1;

    // 드롭 섀도우
    private boolean dropShadow;

    // 겹침 감지: SQUARE textWrap 적용 플래그
    private boolean textWrapSquare;

    // composedLines 기반 분할: Story 내 문자 범위
    private int composedCharStart = -1;
    private int composedCharEnd = -1;

    // 첫 N개 단락을 출력에서 제외 (예: 타이틀 오버레이 패턴 — 본문 TF의 첫 단락이
    // 별도 타이틀 TF로 위에 덮여 있어 본문에서는 숨겨야 하는 경우).
    private int skipParagraphs = 0;

    // 특정 절대 단락 인덱스(스토리 기준)를 출력에서 제외.
    // 본문 중간에 있는 타이틀이 별도 TF 로 덮인 케이스 (예: page 18 "Ava's Story: Keeping a Time Diary").
    private java.util.Set<Integer> excludedParagraphIndices;

    // 폴리곤 경로 (비사각형 프레임용, 페이지 상대 HWPUNIT 좌표)
    private long[] pathPointsX; // null이면 사각형
    private long[] pathPointsY;

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

    public double rotationAngle() { return rotationAngle; }
    public void rotationAngle(double v) { this.rotationAngle = v; }

    public long narrowedWidth() { return narrowedWidth; }
    public void narrowedWidth(long v) { this.narrowedWidth = v; }

    public long narrowedXOffset() { return narrowedXOffset; }
    public void narrowedXOffset(long v) { this.narrowedXOffset = v; }

    public boolean dropShadow() { return dropShadow; }
    public void dropShadow(boolean v) { this.dropShadow = v; }

    public boolean textWrapSquare() { return textWrapSquare; }
    public void textWrapSquare(boolean v) { this.textWrapSquare = v; }

    public int composedCharStart() { return composedCharStart; }
    public void composedCharStart(int v) { this.composedCharStart = v; }
    public int composedCharEnd() { return composedCharEnd; }
    public void composedCharEnd(int v) { this.composedCharEnd = v; }

    public int skipParagraphs() { return skipParagraphs; }
    public void skipParagraphs(int v) { this.skipParagraphs = v; }

    public java.util.Set<Integer> excludedParagraphIndices() { return excludedParagraphIndices; }
    public void addExcludedParagraphIndex(int idx) {
        if (excludedParagraphIndices == null) excludedParagraphIndices = new java.util.HashSet<>();
        excludedParagraphIndices.add(idx);
    }

    /** 실제 렌더링에 사용할 폭. narrowedWidth가 설정되면 그 값, 아니면 원래 width. */
    public long effectiveWidth() {
        return narrowedWidth > 0 ? narrowedWidth : width;
    }

    /** 실제 렌더링에 사용할 X 좌표. narrowedXOffset이 있으면 오른쪽으로 이동. */
    public long effectiveX() {
        return x + narrowedXOffset;
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
