package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualLayeringRules;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

/**
 * SPEC-036: 렌더 그래픽의 "배치 vs 억제" 결정을 한 곳으로 모으기 위한 공용 결정 함수.
 *
 * <p>현재는 legacy Phase 6 executor 상단의 OwnershipPlanner 권위 판정과,
 * 아직 plan으로 흡수되지 않은 legacy suppress fallback을 격리한다.
 * 다음 단계에서는 이 fallback을 Stage 1 ObjectPlan refinement로 옮긴다.</p>
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
            return new PlanRejection("SKIP_OBJECT_PLAN_NOT_FLOATING_VISUAL",
                    "OwnershipPlanner placement/action is not handled by floating visual executor");
        }
        return null;
    }

    /**
     * Phase 6의 가장 앞단에서 적용 가능한 suppress 판정.
     * 순서가 결과에 영향을 줄 수 있는 배지/자식 정책은 아직 실행기 쪽에 남긴다.
     */
    public static PlanRejection phase6InitialRejection(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx.nativeFillAbsorbedIds.contains(rg.id())) {
            return new PlanRejection("SKIP_NATIVE_FILL_ABSORBED",
                    "background shape is painted as native cell fill by FramePlacer");
        }
        return planRejection(ctx, rg);
    }

    /** Phase 6 문맥상 decomposition 판정 뒤에 적용해야 하는 disposed suppress 판정. */
    public static PlanRejection phase6DisposedRejection(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx.isRenderedDisposed(rg.id(), FrameDisposition.TEXT_BLOCK_PLACED)) {
            if (rg.hasEditableTextHiddenFromPng()
                    && ctx.shouldPlaceFloatingVisualByOwnershipPlan(rg)) {
                return null;
            }
            return new PlanRejection("SKIP_RENDERED_DISPOSED", "rendered item already handled");
        }
        return null;
    }

    /**
     * Phase 6에 남아 있던 legacy ownership suppress.
     *
     * <p>ObjectPlan이 있는 객체는 {@link #planRejection(ResolvedBuildContext, RenderedGroup)}이
     * 최종 권위다. 여기서는 plan이 없는 구 캐시/미계획 객체에 대해서만 resolved/rendered
     * ownership fallback을 적용한다.</p>
     */
    public static PlanRejection phase6LegacyOwnershipRejection(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            boolean protectedEditableLabelShell) {
        if (ctx.hasOwnershipPlan(rg)) {
            return null;
        }
        boolean badgeShell = VisualLayeringRules.isBadgeShellGraphicBehind(rg);
        if (!badgeShell
                && ctx.resolvedData.shouldKeepVisualLabelTextEditable(rg)
                && !protectedEditableLabelShell) {
            return new PlanRejection("SKIP_VISUAL_LABEL_TEXT_EDITABLE", "text label kept editable");
        }
        if (!badgeShell && rg.shouldSkipByOwnership()) {
            return new PlanRejection("SKIP_OWNERSHIP", "render ownership says text is not hidden");
        }
        if (shouldSkipByChildPolicy(ctx, rg) && !protectedEditableLabelShell) {
            return new PlanRejection("SKIP_CHILD_POLICY", "child policy suppresses render");
        }
        return null;
    }

    private static boolean shouldSkipByChildPolicy(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg.childIds() == null || rg.childIds().length == 0) {
            return false;
        }

        boolean allChildrenAreEditableTf = true;
        boolean hasEditableTfChild = false;
        boolean anyChildIsInlineObject = false;
        for (int childId : rg.childIds()) {
            if (ctx.resolvedData.isInlineObjectId(childId)) {
                anyChildIsInlineObject = true;
                allChildrenAreEditableTf = false;
                continue;
            }
            if (ctx.resolvedData.isEditableTextFrame(String.valueOf(childId))) {
                hasEditableTfChild = true;
            } else {
                allChildrenAreEditableTf = false;
            }
        }

        if (anyChildIsInlineObject && !rg.hasEditableTextHiddenFromPng()) {
            return true;
        }
        if (hasEditableTfChild && !rg.hasEditableTextHiddenFromPng()) {
            return true;
        }
        return allChildrenAreEditableTf;
    }

}
