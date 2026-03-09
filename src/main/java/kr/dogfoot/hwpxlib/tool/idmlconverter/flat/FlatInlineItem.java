package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

/**
 * Layer 2: 인라인 항목 — FlatComponent 내부의 원자 단위.
 * TEXT_RUN, BREAK, EQUATION, LAYOUT_REF 네 가지 타입을 하나의 클래스로 통합.
 * LAYOUT_REF는 재귀를 끊는 핵심 참조 — 인라인 객체 대신 Layer 1 nodeId를 가리킨다.
 */
public class FlatInlineItem {
    public enum ItemType { TEXT_RUN, BREAK, EQUATION, LAYOUT_REF }

    private ItemType itemType;

    // TEXT_RUN fields
    private String characterStyleRef;
    private String text;
    private String fontFamily;
    private String fontStyle;
    private Integer fontSizeHwpunits;
    private String textColor;
    private Short letterSpacing;
    private boolean subscript;
    private boolean superscript;
    private boolean grepMathFont;
    private boolean underline;
    private String underlineColor;
    private String underlineShape;
    private boolean strikeThrough;
    private Short horizontalScale;
    private Short verticalScale;
    private Short baselineShift;

    // BREAK fields
    private String breakType;

    // EQUATION fields
    private String hwpScript;
    private String equationSourceType;
    private String equationTextColor;

    // LAYOUT_REF fields
    private String layoutNodeId;

    // --- Static factory methods ---

    public static FlatInlineItem textRun() {
        FlatInlineItem item = new FlatInlineItem();
        item.itemType = ItemType.TEXT_RUN;
        return item;
    }

    public static FlatInlineItem lineBreak() {
        FlatInlineItem item = new FlatInlineItem();
        item.itemType = ItemType.BREAK;
        item.breakType = "LINE";
        return item;
    }

    public static FlatInlineItem equation(String hwpScript, String sourceType) {
        FlatInlineItem item = new FlatInlineItem();
        item.itemType = ItemType.EQUATION;
        item.hwpScript = hwpScript;
        item.equationSourceType = sourceType;
        return item;
    }

    public static FlatInlineItem layoutRef(String nodeId) {
        FlatInlineItem item = new FlatInlineItem();
        item.itemType = ItemType.LAYOUT_REF;
        item.layoutNodeId = nodeId;
        return item;
    }

    // --- Accessors ---

    public ItemType itemType() { return itemType; }
    public void itemType(ItemType v) { this.itemType = v; }

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

    public String underlineColor() { return underlineColor; }
    public void underlineColor(String v) { this.underlineColor = v; }

    public String underlineShape() { return underlineShape; }
    public void underlineShape(String v) { this.underlineShape = v; }

    public boolean strikeThrough() { return strikeThrough; }
    public void strikeThrough(boolean v) { this.strikeThrough = v; }

    public Short horizontalScale() { return horizontalScale; }
    public void horizontalScale(Short v) { this.horizontalScale = v; }

    public Short verticalScale() { return verticalScale; }
    public void verticalScale(Short v) { this.verticalScale = v; }

    public Short baselineShift() { return baselineShift; }
    public void baselineShift(Short v) { this.baselineShift = v; }

    public String breakType() { return breakType; }
    public void breakType(String v) { this.breakType = v; }

    public String hwpScript() { return hwpScript; }
    public void hwpScript(String v) { this.hwpScript = v; }

    public String equationSourceType() { return equationSourceType; }
    public void equationSourceType(String v) { this.equationSourceType = v; }

    public String equationTextColor() { return equationTextColor; }
    public void equationTextColor(String v) { this.equationTextColor = v; }

    public String layoutNodeId() { return layoutNodeId; }
    public void layoutNodeId(String v) { this.layoutNodeId = v; }
}
