package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

/**
 * SPEC-036: 렌더 그래픽의 "배치 vs 억제" 결정을 한 곳으로 모으기 위한 공용 결정 함수.
 *
 * <p>현재는 Phase 6(BackgroundInjector)과 Phase 7(RenderableFramePlacer)이 상단에서
 * 그대로 중복하던 OwnershipPlanner 권위 판정만 담는다(Tier 1, 순수 이동·골든디프 0).
 * Tier 2/3에서 나머지 suppress 휴리스틱을 이 클래스로 흡수해 plan을 단일 권위로 만든다.</p>
 *
 * <p>부작용(phase6PlacedIds 등록 등)은 호출자가 phase 고유로 처리한다. 이 클래스는
 * "왜 억제하는가(code/detail)"와 "Phase 6에서 placed로 표시해야 하는가(markPhase6Placed)"만
 * 반환한다.</p>
 */
public final class VisualPlacementResolver {
    private VisualPlacementResolver() {
    }

    /** plan 권위 억제 사유. null이면 plan은 floating 배치를 막지 않는다. */
    public static final class PlanRejection {
        public final String code;
        public final String detail;
        /** Phase 6에서 이 사유로 skip할 때 phase6PlacedIds에 등록해 Phase 7 중복을 막는가. */
        public final boolean markPhase6Placed;

        PlanRejection(String code, String detail, boolean markPhase6Placed) {
            this.code = code;
            this.detail = detail;
            this.markPhase6Placed = markPhase6Placed;
        }
    }

    /**
     * OwnershipPlanner plan이 이 렌더의 floating PNG 배치를 거부하는지 판정한다.
     * Phase 6/7 공용. (두 phase가 동일 조건을 verbatim 중복하던 것을 통합.)
     *
     * @return 거부 사유, 또는 plan이 floating을 막지 않으면 null
     */
    public static PlanRejection planRejection(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx.shouldDropVisualByOwnershipPlan(rg)) {
            return new PlanRejection("SKIP_OBJECT_PLAN_DROP_VISUAL",
                    "OwnershipPlanner visualAction=DROP_VISUAL", false);
        }
        if (ctx.hasOwnershipPlan(rg) && !ctx.shouldPlaceFloatingVisualByOwnershipPlan(rg)) {
            return new PlanRejection("SKIP_OBJECT_PLAN_NOT_FLOATING_VISUAL",
                    "OwnershipPlanner placement/action is not handled by floating visual executor", true);
        }
        return null;
    }
}
