package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

/**
 * source ownership policy: 렌더 그래픽의 "배치 vs 억제" 결정을 한 곳으로 모으기 위한 공용 결정 함수.
 *
 * <p>Stage 3 visual execution is plan-only. This resolver reports only the
 * already-decided ObjectPlan rejection reason; it does not create fallback
 * ownership decisions.</p>
 *
 * <p>이 클래스는 "왜 억제하는가(code/detail)"만 반환한다. 실제 배치/기록 부작용은
 * Stage 3 실행기가 처리한다.</p>
 */
public final class VisualPlacementResolver {
    private VisualPlacementResolver() {
    }

    /** plan 권위 억제 사유. null이면 plan은 floating 배치를 막지 않는다. */
    public static final class PlanRejection {
        public final String code;
        public final String detail;

        PlanRejection(String code, String detail) {
            this.code = code;
            this.detail = detail;
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
                    "OwnershipPlanner visualAction=DROP_VISUAL");
        }
        if (ctx.hasOwnershipPlan(rg) && !ctx.shouldPlaceFloatingVisualByOwnershipPlan(rg)) {
            return new PlanRejection("ROUTE_INLINE_VISUAL",
                    "OwnershipPlanner assigned this visual to the inline visual executor");
        }
        return null;
    }

}
