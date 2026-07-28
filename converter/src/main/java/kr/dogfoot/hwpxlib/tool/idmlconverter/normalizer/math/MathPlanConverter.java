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
                // SPEC-085: 이탤릭은 하드코딩이 아니라 원본 증거로 — GREP 규칙이
                // 대문자에 상부자(직립)를 적용한 문서는 직립을 유지해야 한다.
                boolean italic = runHasItalicEvidence(source);
                equation.sourceItalic(italic);
                // 비이탤릭인데 GREP 수식 스타일이 적용됐다면(승자 규칙이 직립 상부자)
                // 원본 조판이 직립이라는 뜻 — rm 방출 근거.
                equation.sourceUpright(!italic
                        && (source.grepMathFont() || runHasUprightScriptStyle(source)));
                items.set(span.itemIndex(), equation);
            }
            // SPEC-084: TEXT 강등(고립 단일 라틴 수식 → 이탤릭 텍스트)은 폐기 —
            // 강등 plan 을 만드는 planner 가 제거되어 TEXT 분기는 더 이상 오지 않는다.
        }
    }

    /** SPEC-085: 원본 런의 이탤릭 증거 (fontStyle 또는 문자 스타일 이름). */
    private static boolean runHasItalicEvidence(ASTTextRun run) {
        String fs = run.fontStyle();
        if (fs != null) {
            String lower = fs.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("italic") || lower.contains("oblique")) return true;
        }
        String style = normalizedStyleRef(run);
        return style.contains("이탤릭") || style.contains("italic");
    }

    /** SPEC-085: 명시적 직립 첨자 스타일(상부자/하부자 계열, 이탤릭 표기 없음). */
    private static boolean runHasUprightScriptStyle(ASTTextRun run) {
        String style = normalizedStyleRef(run);
        return style.contains("상부자") || style.contains("하부자");
    }

    private static String normalizedStyleRef(ASTTextRun run) {
        String ref = run.characterStyleRef();
        if (ref == null) return "";
        return ref.toLowerCase(java.util.Locale.ROOT)
                .replace("%3a", ":")
                .replace("%25", "%");
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
