package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

/**
 * 중간 포맷 텍스트 런.
 * null인 속성은 스타일에서 상속.
 */
public class IntermediateTextRun {
    private String characterStyleRef;
    private String text;
    private Boolean bold;
    private Boolean italic;
    private Integer fontSizeHwpunits;
    private String textColor;
    private String fontFamily;
    private Short letterSpacing;  // 자간 (-50 ~ 50)

    public String characterStyleRef() { return characterStyleRef; }
    public void characterStyleRef(String v) { this.characterStyleRef = v; }

    public String text() { return text; }
    public void text(String v) { this.text = v; }

    public Boolean bold() { return bold; }
    public void bold(Boolean v) { this.bold = v; }

    public Boolean italic() { return italic; }
    public void italic(Boolean v) { this.italic = v; }

    public Integer fontSizeHwpunits() { return fontSizeHwpunits; }
    public void fontSizeHwpunits(Integer v) { this.fontSizeHwpunits = v; }

    public String textColor() { return textColor; }
    public void textColor(String v) { this.textColor = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public Short letterSpacing() { return letterSpacing; }
    public void letterSpacing(Short v) { this.letterSpacing = v; }
}
