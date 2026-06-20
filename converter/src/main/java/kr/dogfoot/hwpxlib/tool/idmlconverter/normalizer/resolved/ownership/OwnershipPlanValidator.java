package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source ownership policy Stage 4 validator.
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
        v.validatePlanContractFields();
        v.validateDuplicateVisibleSourceSlots();
        v.validateConflictingTextOwnership();
        v.validateInlineFloatingSourceSplit();
        v.validateInlineFloatingTextFramePlacementSplit();
        v.validateHwpxTextNotPlacedAsCompletePng();
        v.validateTextShellBehindOwnedText();
        v.validateParentTextShellDescendantsDropped();
        v.validateParentTextShellOwnedTextFrames();
    }

    private final ResolvedBuildContext ctx;

    private OwnershipPlanValidator(ResolvedBuildContext ctx) {
        this.ctx = ctx;
    }

    private void validateDuplicateVisibleSourceSlots() {
        Map<String, List<ObjectPlan>> bySource = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!hasVisibleVisualSlot(plan)) continue;
            for (int sourceId : visualSourceIds(plan)) {
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
            for (int sourceId : visualSourceIds(plan)) {
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

    private void validateInlineFloatingTextFramePlacementSplit() {
        Map<String, List<ObjectPlan>> byTextFrame = new LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int textFrameId : textFrameSourceIds(plan)) {
                String key = plan.pageIndex + ":" + textFrameId;
                byTextFrame.computeIfAbsent(key, k -> new ArrayList<>()).add(plan);
            }
        }
        for (Map.Entry<String, List<ObjectPlan>> e : byTextFrame.entrySet()) {
            boolean inline = false;
            boolean floating = false;
            for (ObjectPlan plan : e.getValue()) {
                inline |= plan.placement == Placement.INLINE;
                floating |= plan.placement == Placement.FLOATING;
            }
            if (inline && floating) {
                warn("STAGE4_INLINE_FLOATING_TEXT_FRAME_SPLIT",
                        "textFrame=" + e.getKey() + " plans=" + planRefs(e.getValue()));
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
            if (!visualSourcesContainAny(plan, textIds)) continue;
            warn("STAGE4_HWPX_TEXT_SOURCE_VISIBLE_AS_PNG",
                    "plan=" + planRef(plan) + " textFrameIds=" + ObjectPlan.intArrayJson(textIds));
        }
    }

    private void validateTextShellBehindOwnedText() {
        if (ctx.resolvedData == null) return;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (isBackPlaneTextShell(plan)) continue;
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

    private static boolean isBackPlaneTextShell(ObjectPlan plan) {
        if (plan == null) return false;
        return plan.visualLayer == VisualLayer.LABEL_BACKDROP
                || plan.visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                || plan.visualLayer == VisualLayer.CONTAINER_BACKDROP
                || plan.visualLayer == VisualLayer.TEXT_CARD_BACKDROP;
    }

    private void validateParentTextShellDescendantsDropped() {
        for (ObjectPlan parent : ctx.ownershipPlans) {
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (parent.descendantVisualObjectIds == null || parent.descendantVisualObjectIds.length == 0) continue;
            for (ObjectPlan candidate : ctx.ownershipPlans) {
                if (candidate == parent || !hasVisibleVisualSlot(candidate)) continue;
                if (candidate.pageIndex != parent.pageIndex) continue;
                if (!referencesAnyDescendant(candidate, parent.descendantVisualObjectIds)) continue;
                warn("STAGE4_PARENT_TEXT_SHELL_DESCENDANT_VISIBLE",
                        "parent=" + planRef(parent)
                                + " descendantVisible=" + planRef(candidate));
            }
        }
    }

    private void validateParentTextShellOwnedTextFrames() {
        for (ObjectPlan parent : ctx.ownershipPlans) {
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (parent.ownedTextFrameIds == null || parent.ownedTextFrameIds.length == 0) continue;
            for (int textFrameId : parent.ownedTextFrameIds) {
                ObjectPlan textPlan = findTextFramePlan(textFrameId, parent.pageIndex);
                if (!isTextFrameAccountedForByShell(textPlan)) {
                    warn("STAGE4_PARENT_TEXT_SHELL_OWNED_TEXT_MISSING",
                            "parent=" + planRef(parent)
                                    + " textFrameId=" + textFrameId);
                }
            }
        }
    }

    private static boolean isTextFrameAccountedForByShell(ObjectPlan textPlan) {
        if (textPlan == null) return false;
        if (textPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return true;
        if (textPlan.textAction != TextAction.DROP_TEXT) return false;
        if (textPlan.visualAction != VisualAction.DROP_VISUAL) return false;
        String reason = textPlan.reason != null ? textPlan.reason : "";
        return reason.equals("owned_by_inline_text_shell")
                || reason.equals("owned_by_inline_complete_png");
    }

    private void validatePlanContractFields() {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (plan.textAction == null) {
                warn("STAGE4_PLAN_MISSING_TEXT_ACTION", "plan=" + planRef(plan));
            }
            if (plan.visualAction == null) {
                warn("STAGE4_PLAN_MISSING_VISUAL_ACTION", "plan=" + planRef(plan));
            }
            if (plan.placement == null && plan.hasVisibleVisual()) {
                warn("STAGE4_PLAN_MISSING_PLACEMENT", "plan=" + planRef(plan));
            }
            if (plan.materialization == null) {
                warn("STAGE4_PLAN_MISSING_MATERIALIZATION", "plan=" + planRef(plan));
            }
            if (plan.coordinateSpace == null) {
                warn("STAGE4_PLAN_MISSING_COORDINATE_SPACE", "plan=" + planRef(plan));
            }
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) {
                if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) {
                    warn("STAGE4_TEXT_SHELL_WITHOUT_HWPX_TEXT",
                            "plan=" + planRef(plan));
                }
                if (plan.materialization != Materialization.EXTRACTED_PNG_VECTOR
                        && plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) {
                    warn("STAGE4_TEXT_SHELL_INVALID_MATERIALIZATION",
                            "plan=" + planRef(plan)
                                    + " materialization=" + plan.materialization);
                }
                if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
                    warn("STAGE4_TEXT_SHELL_MISSING_OWNED_TEXT",
                            "plan=" + planRef(plan));
                }
            }
            if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE
                    && plan.materialization != Materialization.HWPX_TABLE_STYLE) {
                warn("STAGE4_TABLE_STYLE_INVALID_MATERIALIZATION",
                        "plan=" + planRef(plan)
                                + " materialization=" + plan.materialization);
            }
            if (plan.textAction == TextAction.OWNED_BY_PNG
                    && plan.materialization != Materialization.COMPLETE_PNG) {
                warn("STAGE4_PNG_TEXT_OWNER_NOT_COMPLETE_PNG",
                        "plan=" + planRef(plan)
                                + " materialization=" + plan.materialization);
            }
        }
    }

    private boolean hasVisibleVisualSlot(ObjectPlan plan) {
        return plan != null && plan.hasVisibleVisual();
    }

    private int[] textFrameSourceIds(ObjectPlan plan) {
        if (plan != null && plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) {
            return plan.ownedTextFrameIds;
        }
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

    private static int[] visualSourceIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        if (plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0) {
            return plan.visualSourceObjectIds;
        }
        return plan.sourceObjectIds != null ? plan.sourceObjectIds : new int[0];
    }

    private static boolean visualSourcesContainAny(ObjectPlan plan, int[] sourceIds) {
        if (plan == null || sourceIds == null || sourceIds.length == 0) return false;
        int[] visualIds = visualSourceIds(plan);
        if (visualIds.length == 0) return false;
        for (int id : sourceIds) {
            if (contains(visualIds, id)) return true;
        }
        return false;
    }

    private ObjectPlan findTextFramePlan(int textFrameId, int pageIndex) {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.pageIndex != pageIndex) continue;
            if (plan.domId != textFrameId) continue;
            if (plan.kind != null && plan.kind.startsWith("text_frame")) return plan;
        }
        return null;
    }

    private static boolean referencesAnyDescendant(ObjectPlan plan, int[] descendants) {
        if (plan == null || descendants == null || descendants.length == 0) return false;
        if (contains(descendants, plan.domId)) return true;
        if (plan.renderId != null && contains(descendants, plan.renderId)) return true;
        for (int sourceId : visualSourceIds(plan)) {
            if (contains(descendants, sourceId)) return true;
        }
        return false;
    }

    private static boolean contains(int[] values, int candidate) {
        if (values == null) return false;
        for (int value : values) {
            if (value == candidate) return true;
        }
        return false;
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
