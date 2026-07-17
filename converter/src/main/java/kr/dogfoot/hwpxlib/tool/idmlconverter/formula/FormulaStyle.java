package kr.dogfoot.hwpxlib.tool.idmlconverter.formula;

/**
 * Resolved visual style for one formula materialization.
 *
 * <p>Both editable subscript text runs and HWP equation objects should read
 * size/font/color from this value instead of re-resolving them independently.
 */
public final class FormulaStyle {
    private final Integer baseUnit;
    private final String fontFamily;
    private final String textColor;

    public FormulaStyle(Integer baseUnit, String fontFamily, String textColor) {
        this.baseUnit = baseUnit;
        this.fontFamily = fontFamily;
        this.textColor = textColor;
    }

    public Integer baseUnit() {
        return baseUnit;
    }

    public String fontFamily() {
        return fontFamily;
    }

    public String textColor() {
        return textColor;
    }
}
