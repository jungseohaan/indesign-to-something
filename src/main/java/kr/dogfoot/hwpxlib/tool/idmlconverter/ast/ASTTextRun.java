package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 텍스트 런 — 동일 스타일의 연속 문자열.
 */
public class ASTTextRun extends ASTInlineItem {
    private String characterStyleRef;
    private String text;
    private String fontFamily;
    private String fontStyle;
    private Integer fontSizeHwpunits;
    private String textColor;
    private Short letterSpacing;
    private boolean subscript;
    private boolean superscript;
    private boolean grepMathFont;  // GREP 스타일에서 BT수식M이 동적 적용된 런
    private boolean underline;     // 밑줄
    private boolean strikeThrough; // 취소선
    private Short horizontalScale; // 장평 (%, 100 = normal)

    public ItemType itemType() { return ItemType.TEXT_RUN; }

    public String characterStyleRef() { return characterStyleRef; }
    public void characterStyleRef(String v) { this.characterStyleRef = v; }

    public String text() { return text; }
    public void text(String v) { this.text = v; }

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

    public boolean subscript() { return subscript; }
    public void subscript(boolean v) { this.subscript = v; }

    public boolean superscript() { return superscript; }
    public void superscript(boolean v) { this.superscript = v; }

    public boolean grepMathFont() { return grepMathFont; }
    public void grepMathFont(boolean v) { this.grepMathFont = v; }

    public boolean underline() { return underline; }
    public void underline(boolean v) { this.underline = v; }

    public boolean strikeThrough() { return strikeThrough; }
    public void strikeThrough(boolean v) { this.strikeThrough = v; }

    public Short horizontalScale() { return horizontalScale; }
    public void horizontalScale(Short v) { this.horizontalScale = v; }

}
