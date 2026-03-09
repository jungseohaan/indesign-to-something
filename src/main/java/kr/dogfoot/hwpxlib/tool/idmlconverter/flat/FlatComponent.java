package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2: 원자 컴포넌트 — 문단 단위의 콘텐츠.
 * 현재는 PARAGRAPH 타입만 존재하며, 향후 확장 가능.
 */
public class FlatComponent {
    public enum ComponentType { PARAGRAPH }

    private String componentId;
    private ComponentType type = ComponentType.PARAGRAPH;
    private String parentNodeId;

    // 단락 스타일 참조
    private String paragraphStyleRef;

    // 단락 속성 (로컬 오버라이드)
    private String alignment;
    private Long firstLineIndent;
    private Long leftMargin;
    private Long rightMargin;
    private Long spaceBefore;
    private Long spaceAfter;
    private Integer lineSpacing;
    private String lineSpacingType;
    private Short letterSpacing;

    // 단락 배경
    private boolean shadingOn;
    private String shadingColor;
    private Double shadingTint;
    private Long shadingLeftOffset;
    private Long shadingRightOffset;
    private Long shadingTopOffset;
    private Long shadingBottomOffset;

    // 탭 정지점
    private List<ASTTabStop> tabStops;

    // 프레임 내 Y 오프셋 (points, resolved.json에서 전파, -1 = 미설정)
    private double yOffsetInFrame = -1;

    // 컬럼 브레이크
    private boolean columnBreakAfter;

    // 장식 선 stroke 색상 → 후속 텍스트 런에 underline으로 전파
    private String pendingUnderlineColor;

    // 인라인 항목 (읽기 순서)
    private List<FlatInlineItem> items;

    public FlatComponent() {
        this.items = new ArrayList<>();
    }

    public String componentId() { return componentId; }
    public void componentId(String v) { this.componentId = v; }

    public ComponentType type() { return type; }
    public void type(ComponentType v) { this.type = v; }

    public String parentNodeId() { return parentNodeId; }
    public void parentNodeId(String v) { this.parentNodeId = v; }

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

    public List<ASTTabStop> tabStops() { return tabStops; }
    public void tabStops(List<ASTTabStop> v) { this.tabStops = v; }
    public boolean hasTabStops() { return tabStops != null && !tabStops.isEmpty(); }
    public void addTabStop(ASTTabStop ts) {
        if (this.tabStops == null) {
            this.tabStops = new ArrayList<>();
        }
        this.tabStops.add(ts);
    }

    public double yOffsetInFrame() { return yOffsetInFrame; }
    public void yOffsetInFrame(double v) { this.yOffsetInFrame = v; }

    public boolean columnBreakAfter() { return columnBreakAfter; }
    public void columnBreakAfter(boolean v) { this.columnBreakAfter = v; }

    public String pendingUnderlineColor() { return pendingUnderlineColor; }
    public void pendingUnderlineColor(String v) { this.pendingUnderlineColor = v; }

    public List<FlatInlineItem> items() { return items; }
    public void items(List<FlatInlineItem> v) { this.items = v; }
    public void addItem(FlatInlineItem item) { items.add(item); }
}
