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
    private Short letterSpacing;

    // 단락 배경
    private boolean shadingOn;
    private String shadingColor;
    private Double shadingTint;
    private Long shadingLeftOffset;
    private Long shadingRightOffset;
    private Long shadingTopOffset;
    private Long shadingBottomOffset;

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

    public List<ASTInlineItem> items() { return items; }
    public void addItem(ASTInlineItem item) { items.add(item); }
}
