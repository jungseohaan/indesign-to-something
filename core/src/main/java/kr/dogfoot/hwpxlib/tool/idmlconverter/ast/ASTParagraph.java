package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 텍스트 단락 — 인라인 항목들의 평탄한 시퀀스.
 * 좌표 단위: HWPUNIT.
 */
public class ASTParagraph {
    private String paragraphStyleRef;

    // 단락 속성 (로컬 오버라이드)
    private String alignment;
    private Long firstLineIndent;
    private Long leftMargin;
    private Long rightMargin;
    private Long spaceBefore;
    private Long spaceAfter;
    private Integer lineSpacing;
    private String lineSpacingType; // "fixed" or "percent"
    private Short letterSpacing;

    // 단락 배경
    private boolean shadingOn;
    private String shadingColor;
    private Double shadingTint;
    private Long shadingLeftOffset;
    private Long shadingRightOffset;
    private Long shadingTopOffset;
    private Long shadingBottomOffset;

    // 탭 정지점 (인라인 오버라이드, HWPUNIT 단위)
    private List<ASTTabStop> tabStops;

    // 프레임 내 Y 오프셋 (points, resolved.json에서 전파, -1 = 미설정)
    private double yOffsetInFrame = -1;

    // 원본 조판 line bounds의 page-relative union (HWPUNIT, -1 = 미설정)
    private long pageX = -1;
    private long pageY = -1;
    private long pageWidth = -1;
    private long pageHeight = -1;

    // 인라인 테이블 (이 문단이 테이블 자리표시자인 경우)
    private ASTTable inlineTable;

    // 컬럼 브레이크 (이 단락 뒤에서 다음 컬럼으로 이동)
    private boolean columnBreakAfter;

    // 단락 분리 제어
    private boolean keepWithNext;
    private boolean keepLinesTogether;
    private boolean pageBreakBefore;

    // "Indent to Here" (ACE 7) 위치 (HWPUNIT). 0이면 미설정.
    // U+2028 강제 줄바꿈 후 이 위치에 탭 삽입하여 들여쓰기 재현.
    private long indentToHerePosition;

    // 장식 선(GraphicLine)의 stroke 색상 → 후속 텍스트 런에 underline으로 전파
    private String pendingUnderlineColor;

    // 불릿 단락 플래그 (●, • 등으로 시작 — 불릿 이후 런 색상 리셋용)
    private boolean bulletParagraph;

    // 중복 도비라 소단원명 story에서 앞쪽에 섞여 들어온 소형 장식 마커 제거
    private boolean dropLeadingSmallInlineObjects;

    // 인라인 항목 (읽기 순서)
    private List<ASTInlineItem> items;

    public ASTParagraph() {
        this.items = new ArrayList<>();
    }

    public String paragraphStyleRef() { return paragraphStyleRef; }
    public void paragraphStyleRef(String v) { this.paragraphStyleRef = v; }

    public String alignment() { return alignment; }
    public void alignment(String v) { this.alignment = v; }

    public Long firstLineIndent() { return firstLineIndent; }
    public void firstLineIndent(Long v) { this.firstLineIndent = v; }

    public Long leftMargin() { return leftMargin; }
    public void leftMargin(Long v) { this.leftMargin = v; }

    public Long rightMargin() { return rightMargin; }
    public void rightMargin(Long v) { this.rightMargin = v; }

    public Long spaceBefore() { return spaceBefore; }
    public void spaceBefore(Long v) { this.spaceBefore = v; }

    public Long spaceAfter() { return spaceAfter; }
    public void spaceAfter(Long v) { this.spaceAfter = v; }

    public Integer lineSpacing() { return lineSpacing; }
    public void lineSpacing(Integer v) { this.lineSpacing = v; }

    public String lineSpacingType() { return lineSpacingType; }
    public void lineSpacingType(String v) { this.lineSpacingType = v; }

    public Short letterSpacing() { return letterSpacing; }
    public void letterSpacing(Short v) { this.letterSpacing = v; }

    public boolean shadingOn() { return shadingOn; }
    public void shadingOn(boolean v) { this.shadingOn = v; }

    public String shadingColor() { return shadingColor; }
    public void shadingColor(String v) { this.shadingColor = v; }

    public Double shadingTint() { return shadingTint; }
    public void shadingTint(Double v) { this.shadingTint = v; }

    public Long shadingLeftOffset() { return shadingLeftOffset; }
    public void shadingLeftOffset(Long v) { this.shadingLeftOffset = v; }

    public Long shadingRightOffset() { return shadingRightOffset; }
    public void shadingRightOffset(Long v) { this.shadingRightOffset = v; }

    public Long shadingTopOffset() { return shadingTopOffset; }
    public void shadingTopOffset(Long v) { this.shadingTopOffset = v; }

    public Long shadingBottomOffset() { return shadingBottomOffset; }
    public void shadingBottomOffset(Long v) { this.shadingBottomOffset = v; }

    public long indentToHerePosition() { return indentToHerePosition; }
    public void indentToHerePosition(long v) { this.indentToHerePosition = v; }

    public List<ASTTabStop> tabStops() { return tabStops; }
    public boolean hasTabStops() { return tabStops != null && !tabStops.isEmpty(); }
    public void addTabStop(ASTTabStop ts) {
        if (this.tabStops == null) {
            this.tabStops = new ArrayList<>();
        }
        this.tabStops.add(ts);
    }

    public double yOffsetInFrame() { return yOffsetInFrame; }
    public void yOffsetInFrame(double v) { this.yOffsetInFrame = v; }

    public long pageX() { return pageX; }
    public void pageX(long v) { this.pageX = v; }

    public long pageY() { return pageY; }
    public void pageY(long v) { this.pageY = v; }

    public long pageWidth() { return pageWidth; }
    public void pageWidth(long v) { this.pageWidth = v; }

    public long pageHeight() { return pageHeight; }
    public void pageHeight(long v) { this.pageHeight = v; }

    public boolean hasPageBounds() {
        return pageX >= 0 && pageY >= 0 && pageWidth > 0 && pageHeight > 0;
    }

    public ASTTable inlineTable() { return inlineTable; }
    public void inlineTable(ASTTable v) { this.inlineTable = v; }

    public boolean columnBreakAfter() { return columnBreakAfter; }
    public void columnBreakAfter(boolean v) { this.columnBreakAfter = v; }

    public boolean keepWithNext() { return keepWithNext; }
    public void keepWithNext(boolean v) { this.keepWithNext = v; }

    public boolean keepLinesTogether() { return keepLinesTogether; }
    public void keepLinesTogether(boolean v) { this.keepLinesTogether = v; }

    public boolean pageBreakBefore() { return pageBreakBefore; }
    public void pageBreakBefore(boolean v) { this.pageBreakBefore = v; }

    public String pendingUnderlineColor() { return pendingUnderlineColor; }
    public void pendingUnderlineColor(String v) { this.pendingUnderlineColor = v; }

    public boolean bulletParagraph() { return bulletParagraph; }
    public void bulletParagraph(boolean v) { this.bulletParagraph = v; }

    public boolean dropLeadingSmallInlineObjects() { return dropLeadingSmallInlineObjects; }
    public void dropLeadingSmallInlineObjects(boolean v) { this.dropLeadingSmallInlineObjects = v; }

    public List<ASTInlineItem> items() { return items; }
    public void addItem(ASTInlineItem item) { items.add(item); }
}
