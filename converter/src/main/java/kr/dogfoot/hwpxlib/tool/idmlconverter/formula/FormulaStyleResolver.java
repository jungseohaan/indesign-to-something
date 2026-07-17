package kr.dogfoot.hwpxlib.tool.idmlconverter.formula;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;

/**
 * Single source for formula style resolution.
 *
 * <p>The classifier decides whether content is a formula. This resolver decides
 * how that formula should look, regardless of whether it is later materialized
 * as editable text runs or as an HWP equation object.
 */
public final class FormulaStyleResolver {
    public interface FontMapper {
        String map(String sourceFont);
    }

    private FormulaStyleResolver() {
    }

    public static FormulaStyle resolve(
            ASTEquation eq,
            Integer inheritedBaseUnit,
            String inheritedTextColor,
            Integer templateBaseUnit,
            String templateFont,
            String templateColor,
            FontMapper fontMapper) {
        Integer baseUnit = firstPositive(
                eq != null ? eq.preferredBaseUnit() : null,
                inheritedBaseUnit,
                templateBaseUnit,
                Integer.valueOf(1100));
        String fontFamily = resolveFont(eq, templateFont, fontMapper);
        String textColor = resolveColor(
                eq != null ? eq.textColor() : null,
                inheritedTextColor,
                templateColor);
        return new FormulaStyle(baseUnit, fontFamily, textColor);
    }

    private static String resolveFont(ASTEquation eq, String templateFont, FontMapper fontMapper) {
        if (usesBodyTextEquationStyle(eq)) {
            String sourceFont = eq != null ? eq.preferredFontFamily() : null;
            if (sourceFont != null && !sourceFont.isEmpty()) {
                return fontMapper != null ? fontMapper.map(sourceFont) : sourceFont;
            }
            return "함초롬바탕";
        }
        if (templateFont != null && !templateFont.isEmpty()) {
            return templateFont;
        }
        String sourceFont = eq != null ? eq.preferredFontFamily() : null;
        if (sourceFont != null && !sourceFont.isEmpty()) {
            return fontMapper != null ? fontMapper.map(sourceFont) : sourceFont;
        }
        return "함초롬바탕";
    }

    public static boolean usesBodyTextEquationStyle(ASTEquation eq) {
        return eq != null && "CHEM_FORMULA".equals(eq.sourceType());
    }

    private static String resolveColor(String sourceColor, String inheritedColor, String templateColor) {
        if (sourceColor != null && !sourceColor.isEmpty()) {
            if (!isDefaultBlack(sourceColor)) {
                return sourceColor;
            }
            if (inheritedColor != null && !inheritedColor.isEmpty()) {
                return inheritedColor;
            }
            return sourceColor;
        }
        if (inheritedColor != null && !inheritedColor.isEmpty()) {
            return inheritedColor;
        }
        if (templateColor != null && !templateColor.isEmpty()) {
            return templateColor;
        }
        return "#000000";
    }

    private static Integer firstPositive(Integer... values) {
        if (values == null) return null;
        for (Integer value : values) {
            if (value != null && value > 0) return value;
        }
        return null;
    }

    private static boolean isDefaultBlack(String color) {
        if (color == null) return false;
        String normalized = color.trim();
        return "#000000".equalsIgnoreCase(normalized)
                || "000000".equalsIgnoreCase(normalized);
    }
}
