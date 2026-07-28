package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

import java.util.List;

/**
 * 수식 서브 파이프라인 2단계: 확정된 span plan만 ASTEquation으로 변환한다.
 */
public final class MathPlanConverter {
    private MathPlanConverter() {
    }

    public static void materialize(ASTParagraph paragraph, MathSpanPlan plan) {
        if (paragraph == null || plan == null || plan.isEmpty()) return;
        List<ASTInlineItem> items = paragraph.items();
        List<MathSpanPlan.Span> spans = plan.spans();
        for (int i = spans.size() - 1; i >= 0; i--) {
            MathSpanPlan.Span span = spans.get(i);
            if (span.itemIndex() < 0 || span.itemIndex() >= items.size()) continue;
            ASTInlineItem item = items.get(span.itemIndex());
            if (span.classification() == MathSpanPlan.Classification.MATH
                    && item instanceof ASTTextRun) {
                ASTTextRun source = (ASTTextRun) item;
                ASTEquation equation = new ASTEquation(source.text(), "EH_FONT");
                equation.preferredBaseUnit(source.fontSizeHwpunits());
                equation.preferredFontFamily(source.fontFamily());
                equation.textColor(source.textColor());
                equation.sourceItalic(true);
                items.set(span.itemIndex(), equation);
            }
            // SPEC-084: TEXT 강등(고립 단일 라틴 수식 → 이탤릭 텍스트)은 폐기 —
            // 강등 plan 을 만드는 planner 가 제거되어 TEXT 분기는 더 이상 오지 않는다.
        }
    }

    public static void materialize(ASTParagraph paragraph, MathStructurePlan plan) {
        if (paragraph == null || plan == null || plan.isEmpty()) return;
        List<ASTInlineItem> items = paragraph.items();
        List<MathStructurePlan.Replacement> replacements = plan.replacements();
        for (int i = replacements.size() - 1; i >= 0; i--) {
            MathStructurePlan.Replacement replacement = replacements.get(i);
            if (replacement.startInclusive() < 0
                    || replacement.endExclusive() > items.size()
                    || replacement.startInclusive() >= replacement.endExclusive()) {
                continue;
            }
            for (int index = replacement.endExclusive() - 1;
                 index >= replacement.startInclusive(); index--) {
                items.remove(index);
            }
            items.addAll(replacement.startInclusive(), replacement.items());
        }
    }
}
