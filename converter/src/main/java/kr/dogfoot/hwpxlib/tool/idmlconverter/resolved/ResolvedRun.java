package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * resolved.json의 런 단위 데이터.
 * InDesign DOM에서 textStyleRange 단위로 수집한 최종 계산값.
 */
public class ResolvedRun {
    private String text;
    private String fontFamily;
    private Double fontSize;
    private String fontStyle;
    private String fillColor;    // 색상 이름 (hex 아님)
    private String charStyle;
    private Double tracking;
    private Double horizontalScale;
    private Double verticalScale;
    private Double baselineShift;
    private String position;     // "Position.NORMAL", "Position.SUPERSCRIPT" etc.
    private Boolean underline;
    private Boolean strikeThru;

    // IDML-Free 파이프라인: inline_anchor 지원
    private String type;             // null(일반 텍스트) | "inline_anchor"
    private Integer anchoredObjectId; // inline_anchor일 때 앵커된 객체의 DOM ID
    private String storyAnchorPlacement; // INLINE | FLOATING_ANCHORED | PAGE

    public String type() { return type; }
    public void type(String v) { this.type = v; }

    public Integer anchoredObjectId() { return anchoredObjectId; }
    public void anchoredObjectId(Integer v) { this.anchoredObjectId = v; }

    public boolean isInlineAnchor() { return "inline_anchor".equals(type); }

    public String storyAnchorPlacement() { return storyAnchorPlacement; }
    public void storyAnchorPlacement(String v) { this.storyAnchorPlacement = v; }

    public String text() { return text; }
    public void text(String v) { this.text = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public Double fontSize() { return fontSize; }
    public void fontSize(Double v) { this.fontSize = v; }

    public String fontStyle() { return fontStyle; }
    public void fontStyle(String v) { this.fontStyle = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public String charStyle() { return charStyle; }
    public void charStyle(String v) { this.charStyle = v; }

    public Double tracking() { return tracking; }
    public void tracking(Double v) { this.tracking = v; }

    public Double horizontalScale() { return horizontalScale; }
    public void horizontalScale(Double v) { this.horizontalScale = v; }

    public Double verticalScale() { return verticalScale; }
    public void verticalScale(Double v) { this.verticalScale = v; }

    public Double baselineShift() { return baselineShift; }
    public void baselineShift(Double v) { this.baselineShift = v; }

    public String position() { return position; }
    public void position(String v) { this.position = v; }

    public Boolean underline() { return underline; }
    public void underline(Boolean v) { this.underline = v; }

    public Boolean strikeThru() { return strikeThru; }
    public void strikeThru(Boolean v) { this.strikeThru = v; }
}
