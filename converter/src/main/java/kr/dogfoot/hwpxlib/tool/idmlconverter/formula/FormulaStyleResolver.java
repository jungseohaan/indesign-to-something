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
        // SPEC-055: 화학식(CHEM_FORMULA)도 hp:equation 으로 방출되므로 본문 폰트
        // 특례를 두지 않는다. 수식 개체에 본문 폰트(함초롬바탕)를 지정하면 한글
        // 수식 렌더러가 글리프를 겹쳐 그려 깨진 한글처럼 보인다 (p20 실측) —
        // 수식 개체는 항상 템플릿 폰트(HYhwpEQ)를 우선한다. 텍스트런 경로
        // (로마숫자 등, template=null)는 아래 폴백으로 기존과 동일하게 동작한다.
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

    public static String applyChemicalUprightScript(ASTEquation eq, String hwpScript) {
        if (!usesBodyTextEquationStyle(eq)) return hwpScript;
        return applyChemicalUprightScript(hwpScript);
    }

    static String applyChemicalUprightScript(String hwpScript) {
        if (hwpScript == null || hwpScript.isEmpty()) return hwpScript;
        String trimmed = hwpScript.trim();
        if (startsWithHwpRomanFontCommand(trimmed)) {
            return hwpScript;
        }
        // Hancom equation syntax treats rm as a font-state command, not as rm{...}.
        return "rm " + hwpScript;
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

    private static boolean startsWithHwpRomanFontCommand(String script) {
        return startsWithCommandToken(script, "rm")
                || startsWithCommandToken(script, "rmbold");
    }

    private static boolean startsWithCommandToken(String script, String command) {
        if (script == null || !script.startsWith(command)) return false;
        if (script.length() == command.length()) return true;
        char next = script.charAt(command.length());
        return Character.isWhitespace(next);
    }
}
