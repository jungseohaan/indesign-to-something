package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 단락.
 * 텍스트 런과 인라인 수식이 순서대로 공존할 수 있다.
 */
public class IntermediateParagraph {
    private String paragraphStyleRef;
    private List<ContentItem> contentItems;

    // 인라인 단락 속성 (로컬 오버라이드, 스타일보다 우선)
    private String inlineAlignment;     // "left", "center", "right", "justify"
    private Long inlineFirstLineIndent; // 첫 줄 들여쓰기 (HWPUNIT)
    private Long inlineLeftMargin;      // 왼쪽 여백 (HWPUNIT)
    private Long inlineRightMargin;     // 오른쪽 여백 (HWPUNIT)
    private Long inlineSpaceBefore;     // 단락 앞 간격 (HWPUNIT)
    private Long inlineSpaceAfter;      // 단락 뒤 간격 (HWPUNIT)
    private Integer inlineLineSpacing;  // 줄간격 (%, 130 = 130%)
    private Short inlineLetterSpacing;  // 자간 (-50 ~ 50, HWPX spacing 단위)

    // 단락 음영 (Paragraph Shading)
    private boolean shadingOn;          // 음영 사용 여부
    private String shadingColor;        // 음영 색상 (HEX)
    private Double shadingTint;         // 음영 색조 (0~100)
    private Long shadingOffsetLeft;     // 음영 왼쪽 오프셋 (HWPUNIT)
    private Long shadingOffsetRight;    // 음영 오른쪽 오프셋 (HWPUNIT)
    private Long shadingOffsetTop;      // 음영 위쪽 오프셋 (HWPUNIT)
    private Long shadingOffsetBottom;   // 음영 아래쪽 오프셋 (HWPUNIT)

    public IntermediateParagraph() {
        this.contentItems = new ArrayList<ContentItem>();
    }

    public String paragraphStyleRef() { return paragraphStyleRef; }
    public void paragraphStyleRef(String v) { this.paragraphStyleRef = v; }

    public String inlineAlignment() { return inlineAlignment; }
    public void inlineAlignment(String v) { this.inlineAlignment = v; }

    public Long inlineFirstLineIndent() { return inlineFirstLineIndent; }
    public void inlineFirstLineIndent(Long v) { this.inlineFirstLineIndent = v; }

    public Long inlineLeftMargin() { return inlineLeftMargin; }
    public void inlineLeftMargin(Long v) { this.inlineLeftMargin = v; }

    public Long inlineRightMargin() { return inlineRightMargin; }
    public void inlineRightMargin(Long v) { this.inlineRightMargin = v; }

    public Long inlineSpaceBefore() { return inlineSpaceBefore; }
    public void inlineSpaceBefore(Long v) { this.inlineSpaceBefore = v; }

    public Long inlineSpaceAfter() { return inlineSpaceAfter; }
    public void inlineSpaceAfter(Long v) { this.inlineSpaceAfter = v; }

    public Integer inlineLineSpacing() { return inlineLineSpacing; }
    public void inlineLineSpacing(Integer v) { this.inlineLineSpacing = v; }

    public Short inlineLetterSpacing() { return inlineLetterSpacing; }
    public void inlineLetterSpacing(Short v) { this.inlineLetterSpacing = v; }

    public boolean shadingOn() { return shadingOn; }
    public void shadingOn(boolean v) { this.shadingOn = v; }

    public String shadingColor() { return shadingColor; }
    public void shadingColor(String v) { this.shadingColor = v; }

    public Double shadingTint() { return shadingTint; }
    public void shadingTint(Double v) { this.shadingTint = v; }

    public Long shadingOffsetLeft() { return shadingOffsetLeft; }
    public void shadingOffsetLeft(Long v) { this.shadingOffsetLeft = v; }

    public Long shadingOffsetRight() { return shadingOffsetRight; }
    public void shadingOffsetRight(Long v) { this.shadingOffsetRight = v; }

    public Long shadingOffsetTop() { return shadingOffsetTop; }
    public void shadingOffsetTop(Long v) { this.shadingOffsetTop = v; }

    public Long shadingOffsetBottom() { return shadingOffsetBottom; }
    public void shadingOffsetBottom(Long v) { this.shadingOffsetBottom = v; }

    public List<ContentItem> contentItems() { return contentItems; }

    /**
     * 텍스트 런 추가.
     */
    public void addRun(IntermediateTextRun run) {
        contentItems.add(new ContentItem(run));
    }

    /**
     * 인라인 수식 추가.
     */
    public void addEquation(IntermediateEquation eq) {
        contentItems.add(new ContentItem(eq));
    }

    /**
     * 수식이 포함되어 있는지 확인.
     */
    public boolean hasEquation() {
        for (ContentItem item : contentItems) {
            if (item.isEquation()) return true;
        }
        return false;
    }

    /**
     * 텍스트 런 목록 반환 (하위 호환).
     */
    public List<IntermediateTextRun> runs() {
        List<IntermediateTextRun> result = new ArrayList<IntermediateTextRun>();
        for (ContentItem item : contentItems) {
            if (item.isTextRun()) result.add(item.textRun());
        }
        return result;
    }

    /**
     * 첫 번째 수식 반환 (하위 호환).
     */
    public IntermediateEquation equation() {
        for (ContentItem item : contentItems) {
            if (item.isEquation()) return item.equation();
        }
        return null;
    }

    /**
     * 수식 설정 (하위 호환 setter).
     */
    public void equation(IntermediateEquation v) {
        contentItems.add(new ContentItem(v));
    }

    /**
     * 콘텐츠 항목 (텍스트 런 또는 인라인 수식).
     */
    public static class ContentItem {
        private final IntermediateTextRun textRun;
        private final IntermediateEquation equation;

        public ContentItem(IntermediateTextRun textRun) {
            this.textRun = textRun;
            this.equation = null;
        }

        public ContentItem(IntermediateEquation equation) {
            this.textRun = null;
            this.equation = equation;
        }

        public boolean isTextRun() { return textRun != null; }
        public boolean isEquation() { return equation != null; }
        public IntermediateTextRun textRun() { return textRun; }
        public IntermediateEquation equation() { return equation; }
    }
}
