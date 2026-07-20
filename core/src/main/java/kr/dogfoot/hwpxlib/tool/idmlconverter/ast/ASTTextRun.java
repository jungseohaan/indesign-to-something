package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.Objects;

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
    private String shadeColor;    // 문자 배경색/강조색 (HWPX CharPr shadeColor)
    private Short letterSpacing;
    private boolean subscript;
    private boolean superscript;
    private boolean grepMathFont;  // GREP 스타일에서 BT수식M이 동적 적용된 런
    private boolean underline;     // 밑줄
    private String underlineColor; // 밑줄 색상 (null이면 textColor 사용)
    private String underlineShape; // 밑줄 선 형태 (null=SOLID, "DOT", "DASH")
    private boolean strikeThrough; // 취소선
    private Short horizontalScale; // 장평 (%, 100 = normal)
    private Short verticalScale;   // 세로 비율 (%, 100 = normal)
    private Short baselineShift;   // 기준선 이동 (%, 양수=위)
    private boolean grepStyleApplied; // GREP 스타일에서 색상/폰트가 동적 적용됨 (resolved 보강 시 보호)

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

    public String shadeColor() { return shadeColor; }
    public void shadeColor(String v) { this.shadeColor = v; }

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

    public boolean grepStyleApplied() { return grepStyleApplied; }
    public void grepStyleApplied(boolean v) { this.grepStyleApplied = v; }

    public boolean hasSameStyle(ASTTextRun other) {
        if (other == null) return false;
        return Objects.equals(characterStyleRef, other.characterStyleRef)
                && Objects.equals(fontFamily, other.fontFamily)
                && Objects.equals(fontStyle, other.fontStyle)
                && Objects.equals(fontSizeHwpunits, other.fontSizeHwpunits)
                && Objects.equals(textColor, other.textColor)
                && Objects.equals(shadeColor, other.shadeColor)
                && Objects.equals(letterSpacing, other.letterSpacing)
                && subscript == other.subscript
                && superscript == other.superscript
                && grepMathFont == other.grepMathFont
                && underline == other.underline
                && Objects.equals(underlineColor, other.underlineColor)
                && Objects.equals(underlineShape, other.underlineShape)
                && strikeThrough == other.strikeThrough
                && Objects.equals(horizontalScale, other.horizontalScale)
                && Objects.equals(verticalScale, other.verticalScale)
                && Objects.equals(baselineShift, other.baselineShift)
                && grepStyleApplied == other.grepStyleApplied;
    }

    public ASTTextRun copyWithText(String value) {
        ASTTextRun copy = new ASTTextRun();
        copy.characterStyleRef(characterStyleRef);
        copy.text(value);
        copy.fontFamily(fontFamily);
        copy.fontStyle(fontStyle);
        copy.fontSizeHwpunits(fontSizeHwpunits);
        copy.textColor(textColor);
        copy.shadeColor(shadeColor);
        copy.letterSpacing(letterSpacing);
        copy.subscript(subscript);
        copy.superscript(superscript);
        copy.grepMathFont(grepMathFont);
        copy.underline(underline);
        copy.underlineColor(underlineColor);
        copy.underlineShape(underlineShape);
        copy.strikeThrough(strikeThrough);
        copy.horizontalScale(horizontalScale);
        copy.verticalScale(verticalScale);
        copy.baselineShift(baselineShift);
        copy.grepStyleApplied(grepStyleApplied);
        return copy;
    }

}
