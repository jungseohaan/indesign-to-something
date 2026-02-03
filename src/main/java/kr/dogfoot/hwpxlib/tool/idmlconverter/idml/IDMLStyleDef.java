package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

/**
 * IDML 스타일 정의 (ParagraphStyle 또는 CharacterStyle).
 */
public class IDMLStyleDef {
    private String selfRef;
    private String name;
    private String basedOn;
    private String fontFamily;
    private Double fontSize;
    private String fillColor;
    private String fontStyle;
    private Boolean bold;
    private Boolean italic;
    private String textAlignment;
    private Double firstLineIndent;
    private Double leftIndent;
    private Double rightIndent;
    private Double spaceBefore;
    private Double spaceAfter;
    private Double leading;
    private String leadingType;
    private Double autoLeading;
    private Double horizontalScale;
    private Double tracking;

    public String selfRef() { return selfRef; }
    public void selfRef(String v) { this.selfRef = v; }

    public String name() { return name; }
    public void name(String v) { this.name = v; }

    public String basedOn() { return basedOn; }
    public void basedOn(String v) { this.basedOn = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public Double fontSize() { return fontSize; }
    public void fontSize(Double v) { this.fontSize = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public String fontStyle() { return fontStyle; }
    public void fontStyle(String v) { this.fontStyle = v; }

    public Boolean bold() { return bold; }
    public void bold(Boolean v) { this.bold = v; }

    public Boolean italic() { return italic; }
    public void italic(Boolean v) { this.italic = v; }

    public String textAlignment() { return textAlignment; }
    public void textAlignment(String v) { this.textAlignment = v; }

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

    public String leadingType() { return leadingType; }
    public void leadingType(String v) { this.leadingType = v; }

    public Double autoLeading() { return autoLeading; }
    public void autoLeading(Double v) { this.autoLeading = v; }

    public Double horizontalScale() { return horizontalScale; }
    public void horizontalScale(Double v) { this.horizontalScale = v; }

    public Double tracking() { return tracking; }
    public void tracking(Double v) { this.tracking = v; }

    /**
     * selfRef에서 간단한 이름 추출.
     * "ParagraphStyle/Body" -> "Body"
     */
    public String simpleName() {
        if (selfRef == null) return name;
        int idx = selfRef.lastIndexOf('/');
        return idx >= 0 ? selfRef.substring(idx + 1) : selfRef;
    }
}
