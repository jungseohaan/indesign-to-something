package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML ParagraphStyleRange — 하나의 단락.
 */
public class IDMLParagraph {
    private String appliedParagraphStyle;
    private List<IDMLCharacterRun> characterRuns;

    // 인라인 단락 속성 (로컬 오버라이드)
    private String justification;      // 정렬 (LeftAlign, CenterAlign, RightAlign, *Justified)
    private Double firstLineIndent;    // 첫 줄 들여쓰기 (points)
    private Double leftIndent;         // 왼쪽 들여쓰기 (points)
    private Double rightIndent;        // 오른쪽 들여쓰기 (points)
    private Double spaceBefore;        // 단락 앞 간격 (points)
    private Double spaceAfter;         // 단락 뒤 간격 (points)
    private Double leading;            // 줄간격 (points, Auto일 경우 null)
    private Double tracking;           // 자간 (1/1000 em)

    // 단락 음영 (Paragraph Shading)
    private boolean shadingOn;         // 음영 사용 여부
    private String shadingColor;       // 음영 색상 (Color 참조)
    private Double shadingTint;        // 음영 색조 (0~100)
    private String shadingWidth;       // 음영 너비 (Column, Text)
    private Double shadingOffsetLeft;  // 음영 왼쪽 오프셋 (points)
    private Double shadingOffsetRight; // 음영 오른쪽 오프셋 (points)
    private Double shadingOffsetTop;   // 음영 위쪽 오프셋 (points)
    private Double shadingOffsetBottom;// 음영 아래쪽 오프셋 (points)

    public IDMLParagraph() {
        this.characterRuns = new ArrayList<IDMLCharacterRun>();
    }

    public String appliedParagraphStyle() { return appliedParagraphStyle; }
    public void appliedParagraphStyle(String v) { this.appliedParagraphStyle = v; }

    public String justification() { return justification; }
    public void justification(String v) { this.justification = v; }

    public Double firstLineIndent() { return firstLineIndent; }
    public void firstLineIndent(Double v) { this.firstLineIndent = v; }

    public Double leftIndent() { return leftIndent; }
    public void leftIndent(Double v) { this.leftIndent = v; }

    public Double rightIndent() { return rightIndent; }
    public void rightIndent(Double v) { this.rightIndent = v; }

    public Double spaceBefore() { return spaceBefore; }
    public void spaceBefore(Double v) { this.spaceBefore = v; }

    public Double spaceAfter() { return spaceAfter; }
    public void spaceAfter(Double v) { this.spaceAfter = v; }

    public Double leading() { return leading; }
    public void leading(Double v) { this.leading = v; }

    public Double tracking() { return tracking; }
    public void tracking(Double v) { this.tracking = v; }

    public boolean shadingOn() { return shadingOn; }
    public void shadingOn(boolean v) { this.shadingOn = v; }

    public String shadingColor() { return shadingColor; }
    public void shadingColor(String v) { this.shadingColor = v; }

    public Double shadingTint() { return shadingTint; }
    public void shadingTint(Double v) { this.shadingTint = v; }

    public String shadingWidth() { return shadingWidth; }
    public void shadingWidth(String v) { this.shadingWidth = v; }

    public Double shadingOffsetLeft() { return shadingOffsetLeft; }
    public void shadingOffsetLeft(Double v) { this.shadingOffsetLeft = v; }

    public Double shadingOffsetRight() { return shadingOffsetRight; }
    public void shadingOffsetRight(Double v) { this.shadingOffsetRight = v; }

    public Double shadingOffsetTop() { return shadingOffsetTop; }
    public void shadingOffsetTop(Double v) { this.shadingOffsetTop = v; }

    public Double shadingOffsetBottom() { return shadingOffsetBottom; }
    public void shadingOffsetBottom(Double v) { this.shadingOffsetBottom = v; }

    public List<IDMLCharacterRun> characterRuns() { return characterRuns; }
    public void addCharacterRun(IDMLCharacterRun run) { characterRuns.add(run); }

    /**
     * 단락의 전체 텍스트.
     */
    public String getPlainText() {
        StringBuilder sb = new StringBuilder();
        for (IDMLCharacterRun run : characterRuns) {
            if (run.content() != null) {
                sb.append(run.content());
            }
        }
        return sb.toString();
    }

    /**
     * NP 폰트 수식 내용이 포함되어 있는지 확인.
     */
    public boolean hasEquationContent() {
        for (IDMLCharacterRun run : characterRuns) {
            if (run.isNPFont()) return true;
        }
        return false;
    }
}
