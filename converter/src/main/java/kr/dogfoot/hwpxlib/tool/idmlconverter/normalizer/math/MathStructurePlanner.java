package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 출력기에 남아 있던 인접 수식 구조 결정을 Stage 1 계획으로 만든다.
 */
public final class MathStructurePlanner {
    public static final String REASON_UNIT_WITH_BARE_EXPONENT =
            "UNIT_WITH_BARE_EXPONENT";
    public static final String REASON_TRIANGLE_VERTEX_LABEL =
            "TRIANGLE_VERTEX_LABEL";

    private static final Pattern BARE_EXPONENT =
            Pattern.compile("\\s*\\^\\{(\\d+)\\}\\s*([,.)\\]]?)\\s*");
    private static final String[] EXPONENT_UNITS =
            {"cm", "mm", "km", "kg", "m", "g", "L", "t"};

    private MathStructurePlanner() {
    }

    public static MathStructurePlan plan(ASTParagraph paragraph) {
        MathStructurePlan plan = new MathStructurePlan();
        if (paragraph == null || paragraph.items() == null) return plan;
        List<ASTInlineItem> items = paragraph.items();
        for (int i = 0; i < items.size(); i++) {
            if (planUnitExponent(items, i, plan)) {
                i++;
                continue;
            }
            if (planTriangleLabel(items, i, plan)) {
                i++;
            }
        }
        return plan;
    }

    private static boolean planUnitExponent(
            List<ASTInlineItem> items, int index, MathStructurePlan plan) {
        if (index + 1 >= items.size()
                || !(items.get(index) instanceof ASTTextRun)
                || !(items.get(index + 1) instanceof ASTEquation)) {
            return false;
        }
        ASTTextRun textRun = (ASTTextRun) items.get(index);
        ASTEquation equation = (ASTEquation) items.get(index + 1);
        String text = textRun.text();
        String script = equation.hwpScript();
        if (text == null || text.isEmpty() || script == null) return false;
        Matcher matcher = BARE_EXPONENT.matcher(script);
        if (!matcher.matches()) return false;
        String unit = trailingUnit(text);
        if (unit == null) return false;

        List<ASTInlineItem> replacements = new ArrayList<>();
        String remainder = text.substring(0, text.length() - unit.length());
        if (!remainder.isEmpty()) replacements.add(textRun.copyWithText(remainder));
        String tail = matcher.group(2) == null ? "" : matcher.group(2);
        replacements.add(copyEquation(
                equation, "rm " + unit + "^{" + matcher.group(1) + "}" + tail));
        plan.add(index, index + 2, replacements, REASON_UNIT_WITH_BARE_EXPONENT);
        return true;
    }

    private static boolean planTriangleLabel(
            List<ASTInlineItem> items, int index, MathStructurePlan plan) {
        if (index <= 0 || index + 1 >= items.size()
                || !(items.get(index + 1) instanceof ASTEquation)) {
            return false;
        }
        String marker = inlineTextOrEquation(items.get(index - 1));
        String head = inlineUppercaseLabel(items.get(index));
        ASTEquation tailEquation = (ASTEquation) items.get(index + 1);
        String tail = tailEquation.hwpScript();
        if (marker == null || !marker.endsWith("△")
                || !isUppercaseLabelFragment(head)
                || !isUppercaseLabelFragment(tail)) {
            return false;
        }
        List<ASTInlineItem> replacements = new ArrayList<>();
        replacements.add(copyEquation(tailEquation, head + tail));
        plan.add(index, index + 2, replacements, REASON_TRIANGLE_VERTEX_LABEL);
        return true;
    }

    private static ASTEquation copyEquation(ASTEquation source, String script) {
        ASTEquation copy = new ASTEquation(script, source.sourceType());
        copy.preferredBaseUnit(source.preferredBaseUnit());
        copy.preferredFontFamily(source.preferredFontFamily());
        copy.textColor(source.textColor());
        copy.sourceItalic(source.sourceItalic());
        copy.sourceUpright(source.sourceUpright());
        copy.sourceObjectId(source.sourceObjectId());
        return copy;
    }

    private static String trailingUnit(String text) {
        for (String unit : EXPONENT_UNITS) {
            if (!text.endsWith(unit)) continue;
            int at = text.length() - unit.length();
            if (at > 0) {
                char previous = text.charAt(at - 1);
                if (Character.isLetter(previous) && previous < 0x80) return null;
            }
            return unit;
        }
        return null;
    }

    private static String inlineUppercaseLabel(ASTInlineItem item) {
        if (item instanceof ASTTextRun) return ((ASTTextRun) item).text();
        if (item instanceof ASTEquation) return ((ASTEquation) item).hwpScript();
        return null;
    }

    private static String inlineTextOrEquation(ASTInlineItem item) {
        if (item instanceof ASTTextRun) return ((ASTTextRun) item).text();
        if (item instanceof ASTEquation) return ((ASTEquation) item).hwpScript();
        return null;
    }

    private static boolean isUppercaseLabelFragment(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }
}
