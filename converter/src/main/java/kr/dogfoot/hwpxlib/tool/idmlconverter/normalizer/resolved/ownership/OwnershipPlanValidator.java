package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPEC-036 Stage 4 validator.
 *
 * <p>This class does not create or repair ownership. It only checks the
 * ObjectPlan list that Stage 1/2.5 produced. Keep new policy decisions in
 * OwnershipPlanner; keep final invariant checks here.</p>
 */
public final class OwnershipPlanValidator {
    public static void validate(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.ownershipPlans == null || ctx.ownershipPlans.isEmpty()) {
            return;
        }
        OwnershipPlanValidator v = new OwnershipPlanValidator(ctx);
        v.validateDuplicateVisibleSourceSlots();
        v.validateConflictingTextOwnership();
        v.validateInlineFloatingSourceSplit();
        v.validateHwpxTextNotPlacedAsCompletePng();
        v.validateTextShellBehindOwnedText();
    }

    private final ResolvedBuildContext ctx;

    private OwnershipPlanValidator(ResolvedBuildContext ctx) {
        this.ctx = ctx;
    }

    private void validateDuplicateVisibleSourceSlots() {
        Map<String, List<ObjectPlan>> bySource = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            for (int sourceId : plan.sourceObjectIds) {
                String key = plan.pageIndex + ":" + sourceId;
                bySource.computeIfAbsent(key, k -> new ArrayList<>()).add(plan);
            }
        }
        for (Map.Entry<String, List<ObjectPlan>> e : bySource.entrySet()) {
            if (e.getValue().size() <= 1) continue;
            warn("STAGE4_DUPLICATE_VISIBLE_SOURCE_SLOT",
                    "source=" + e.getKey() + " plans=" + planRefs(e.getValue()));
        }
    }

    private void validateConflictingTextOwnership() {
        Map<Integer, Boolean> pngOwned = new LinkedHashMap<>();
        Map<Integer, Boolean> hwpxOwned = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            for (int textFrameId : textFrameSourceIds(plan)) {
                if (plan.textAction == TextAction.OWNED_BY_PNG) {
                    pngOwned.put(textFrameId, Boolean.TRUE);
                } else if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
                    hwpxOwned.put(textFrameId, Boolean.TRUE);
                }
            }
        }
        for (Integer textFrameId : pngOwned.keySet()) {
            if (Boolean.TRUE.equals(hwpxOwned.get(textFrameId))) {
                warn("STAGE4_CONFLICTING_TEXT_OWNER",
                        "textFrameId=" + textFrameId
                                + " has both OWNED_BY_PNG and OWNED_BY_HWPX_TEXT");
            }
        }
    }

    private void validateInlineFloatingSourceSplit() {
        Map<String, List<ObjectPlan>> bySource = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            for (int sourceId : plan.sourceObjectIds) {
                String key = plan.pageIndex + ":" + sourceId;
                bySource.computeIfAbsent(key, k -> new ArrayList<>()).add(plan);
            }
        }
        for (Map.Entry<String, List<ObjectPlan>> e : bySource.entrySet()) {
            boolean inline = false;
            boolean floating = false;
            for (ObjectPlan plan : e.getValue()) {
                inline |= plan.placement == Placement.INLINE;
                floating |= plan.placement == Placement.FLOATING;
            }
            if (inline && floating) {
                warn("STAGE4_INLINE_FLOATING_SOURCE_SPLIT",
                        "source=" + e.getKey() + " plans=" + planRefs(e.getValue()));
            }
        }
    }

    private void validateHwpxTextNotPlacedAsCompletePng() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    || plan.visualAction == VisualAction.ABSORB_TEXT_STYLE
                    || plan.visualAction == VisualAction.PLACE_TABLE_STYLE) {
                continue;
            }
            int[] textIds = textFrameSourceIds(plan);
            if (textIds.length == 0) continue;
            warn("STAGE4_HWPX_TEXT_SOURCE_VISIBLE_AS_PNG",
                    "plan=" + planRef(plan) + " textFrameIds=" + ObjectPlan.intArrayJson(textIds));
        }
    }

    private void validateTextShellBehindOwnedText() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            for (int textFrameId : textFrameSourceIds(plan)) {
                ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
                if (tf == null) continue;
                if (plan.zOrder >= tf.zOrder()) {
                    warn("STAGE4_TEXT_SHELL_NOT_BEHIND_TEXT",
                            "shell=" + planRef(plan)
                                    + " textFrameId=" + textFrameId
                                    + " shellZ=" + plan.zOrder
                                    + " textZ=" + tf.zOrder());
                }
            }
        }
    }

    private boolean hasVisibleVisualSlot(ObjectPlan plan) {
        return plan != null && plan.hasVisibleVisual();
    }

    private int[] textFrameSourceIds(ObjectPlan plan) {
        if (plan == null || plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0
                || ctx.resolvedData == null) {
            return new int[0];
        }
        List<Integer> ids = new ArrayList<>();
        for (int sourceId : plan.sourceObjectIds) {
            if (ctx.resolvedData.getTextFrame(String.valueOf(sourceId)) != null) {
                ids.add(sourceId);
            }
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
        return out;
    }

    private void warn(String code, String detail) {
        ctx.ownershipWarningLines.add("{\"code\":\"" + ObjectPlan.escape(code)
                + "\",\"stage\":\"validate\",\"detail\":\"" + ObjectPlan.escape(detail) + "\"}");
    }

    private static String planRefs(List<ObjectPlan> plans) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plans.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(planRef(plans.get(i)));
        }
        return sb.toString();
    }

    private static String planRef(ObjectPlan plan) {
        if (plan == null) return "null";
        return "dom=" + plan.domId
                + ",render=" + (plan.renderId != null ? plan.renderId : -1)
                + ",text=" + plan.textAction
                + ",visual=" + plan.visualAction
                + ",placement=" + plan.placement
                + ",reason=" + plan.reason;
    }
}
