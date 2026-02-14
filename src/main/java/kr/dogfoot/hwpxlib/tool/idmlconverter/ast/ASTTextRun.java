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

}
