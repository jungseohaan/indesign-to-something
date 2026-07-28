package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
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

    // SPEC-084: 고립 단일 라틴 강등(planConvertedItems)은 폐기됐다. 강등이 보호하던
    // 오검(과학자 이니셜 수식화)보다 부수 피해(기하 점 라벨 "점 A"·단독 보기 라벨의
    // 수식화 차단)가 더 컸다. GREP 증거로 수식화된 단일 라틴 문자는 문맥과 무관하게
    // 수식으로 유지한다.

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

}
