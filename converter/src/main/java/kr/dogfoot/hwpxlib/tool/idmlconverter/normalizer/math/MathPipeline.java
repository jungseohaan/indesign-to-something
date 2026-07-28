package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;

/**
 * 수식 서브 파이프라인의 유일한 실행 조정기.
 *
 * <p>Stage 1에서 계획하고, Stage 2에서 계획을 실행한 뒤, Stage 3에서 구조를
 * 검증하는 순서를 고정한다. 호출자는 출력 직전에 수식 정책을 다시 조합하지 않는다.</p>
 */
public final class MathPipeline {
    public enum SpanPolicy {
        SOURCE_TEXT,
        CONVERTED_ITEMS
    }

    private MathPipeline() {
    }

    /**
     * resolved source text 중 명시적인 수식 후보만 먼저 materialize한다.
     * 복합 수식 후처리가 끝나기 전이므로 구조 병합과 최종 검증은 수행하지 않는다.
     */
    public static void materializeSourceSpans(ASTParagraph paragraph) {
        MathPlanConverter.materialize(paragraph, MathSpanPlanner.plan(paragraph));
    }

    /**
     * 최종 수식 AST에 대해 분류, 구조 병합, 검증을 정해진 순서로 수행한다.
     */
    public static void finalizeParagraph(
            ASTParagraph paragraph, SpanPolicy spanPolicy) {
        if (paragraph == null) return;
        if (spanPolicy == SpanPolicy.CONVERTED_ITEMS) {
            MathPlanConverter.materialize(
                    paragraph, MathSpanPlanner.planConvertedItems(paragraph));
        } else {
            MathPlanConverter.materialize(paragraph, MathSpanPlanner.plan(paragraph));
        }
        MathPlanConverter.materialize(
                paragraph, MathStructurePlanner.plan(paragraph));
        MathMaterializationValidator.validateIfEnabled(paragraph);
    }
}
