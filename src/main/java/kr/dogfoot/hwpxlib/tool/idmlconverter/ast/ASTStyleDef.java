package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 스타일 정의 — IntermediateStyleDef와 호환.
 */
public class ASTStyleDef {
    private String styleId;
    private String styleName;
    private String basedOnStyleRef;

    // 단락 속성
    private String alignment;
    private Long firstLineIndent;
    private Long leftMargin;
    private Long rightMargin;
    private Long spaceBefore;
    private Long spaceAfter;
    private Integer lineSpacing;
    private String lineSpacingType; // "percent" or "fixed"

    // 문자 속성
    private String fontFamily;
    private String fontStyle;
    private Integer fontSizeHwpunits;
    private String textColor;
    private Short letterSpacing;

    public String styleId() { return styleId; }
    public void styleId(String v) { this.styleId = v; }

    public String styleName() { return styleName; }
    public void styleName(String v) { this.styleName = v; }

    public String basedOnStyleRef() { return basedOnStyleRef; }
    public void basedOnStyleRef(String v) { this.basedOnStyleRef = v; }

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

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public String fontStyle() { return fontStyle; }
    public void fontStyle(String v) { this.fontStyle = v; }

    public Integer fontSizeHwpunits() { return fontSizeHwpunits; }
    public void fontSizeHwpunits(Integer v) { this.fontSizeHwpunits = v; }

    public String textColor() { return textColor; }
    public void textColor(String v) { this.textColor = v; }

    public Short letterSpacing() { return letterSpacing; }
    public void letterSpacing(Short v) { this.letterSpacing = v; }
}
