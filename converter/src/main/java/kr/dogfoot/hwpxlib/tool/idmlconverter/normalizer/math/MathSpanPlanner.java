package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

import java.util.List;
import java.util.Locale;

/**
 * 수식 서브 파이프라인 1단계: source typography에 근거해 수식 범위를 확정한다.
 *
 * <p>이 클래스만 판정하며 converter/HWPX builder는 이 결정을 재해석하지 않는다.</p>
 */
public final class MathSpanPlanner {
    public static final String REASON_SINGLE_LATIN_SOURCE_MATH_TYPOGRAPHY =
            "single-latin-source-math-typography";
    public static final String REASON_ISOLATED_SINGLE_LATIN_WITHOUT_MATH_CONTEXT =
            "isolated-single-latin-without-math-context";

    private MathSpanPlanner() {
    }

    public static MathSpanPlan plan(ASTParagraph paragraph) {
        MathSpanPlan plan = new MathSpanPlan();
        if (paragraph == null || paragraph.items() == null) return plan;

        List<ASTInlineItem> items = paragraph.items();
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            if (isSingleLatinSourceMathRun(run)) {
                plan.add(i, MathSpanPlan.Classification.MATH,
                        REASON_SINGLE_LATIN_SOURCE_MATH_TYPOGRAPHY);
            }
        }
        return plan;
    }

    /**
     * 변환 결과에 대한 의미 계획. 인접 수식 근거가 없는 단일 라틴 수식은 TEXT로
     * 확정한다. 출력 단계가 다시 판단하지 않도록 이 결정도 plan에 기록한다.
     */
    public static MathSpanPlan planConvertedItems(ASTParagraph paragraph) {
        MathSpanPlan plan = new MathSpanPlan();
        if (paragraph == null || paragraph.items() == null) return plan;
        List<ASTInlineItem> items = paragraph.items();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof ASTEquation)) continue;
            ASTEquation equation = (ASTEquation) items.get(i);
            String letter = singleLatinLetterOfScript(equation.hwpScript());
            if (letter == null) continue;
            if (equation.sourceItalic() && Character.isLowerCase(letter.charAt(0))) continue;
            if (adjacentItemIsEquation(items, i, -1) || adjacentItemIsEquation(items, i, 1)) {
                continue;
            }
            plan.add(i, MathSpanPlan.Classification.TEXT,
                    REASON_ISOLATED_SINGLE_LATIN_WITHOUT_MATH_CONTEXT);
        }
        return plan;
    }

    public static boolean isSingleLatinSourceMathRun(ASTTextRun run) {
        if (run == null || run.text() == null || !run.text().matches("[A-Za-z]")) {
            return false;
        }
        // BT/EH/NP 전용 폰트 런은 ASTMathGrouper가 인접 run과 함께 구조를 해석한다.
        // 여기서 단일 항목으로 선점하면 반응식/분수/근호 클러스터가 조각난다.
        String fontFamily = run.fontFamily();
        if (fontFamily != null && (EHFontGlyphMap.isEHFontFamily(fontFamily)
                || BTFontGlyphMap.isBTFontFamily(fontFamily)
                || NPFontGlyphMap.isNPFont(fontFamily))) {
            return false;
        }
        String styleRef = run.characterStyleRef();
        String style = styleRef == null ? "" : styleRef.toLowerCase(Locale.ROOT)
                .replace("%3a", ":")
                .replace("%25", "%");
        String fontStyle = run.fontStyle() == null
                ? "" : run.fontStyle().toLowerCase(Locale.ROOT);
        return (style.contains("상부자") && style.contains("이탤릭"))
                || run.grepMathFont()
                || fontStyle.contains("italic")
                || fontStyle.contains("oblique");
    }

    static String singleLatinLetterOfScript(String script) {
        if (script == null) return null;
        String normalized = script.trim();
        if (normalized.startsWith("rm ")) normalized = normalized.substring(3).trim();
        if (normalized.length() != 1) return null;
        char c = normalized.charAt(0);
        return ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
                ? String.valueOf(c) : null;
    }

    private static boolean adjacentItemIsEquation(
            List<ASTInlineItem> items, int index, int direction) {
        for (int i = index + direction; i >= 0 && i < items.size(); i += direction) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTEquation) return true;
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return false;
            } else {
                return false;
            }
        }
        return false;
    }
}
